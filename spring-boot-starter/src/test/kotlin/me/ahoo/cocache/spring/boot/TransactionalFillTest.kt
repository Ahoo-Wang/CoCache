/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cocache.spring.boot

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.FillObserver
import me.ahoo.cocache.core.FillScope
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.PolicyReport
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.spring.tx.FillInTransaction
import me.ahoo.cocache.spring.tx.TransactionalFillObserver
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.transaction.SavepointManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionAspectSupport
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * starter 装配的写事务内回源（RFC 0005 §4.9）：`cocacheFillObserver` 接入运行时；`@Cacheable(sync = true)` 在事务内读到的未提交行
 * 在回滚、回滚到 savepoint 时失效（含只写 L2 的降级填充）；`@CacheEvict` 登记的 key 在同一事务内私有回源；两种通知嵌套下的行为。
 */
class TransactionalFillTest {
    private val base = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withUserConfiguration(FillConfig::class.java)
        .withPropertyValues("cocache.backend=memory")
    private val runner = base.withUserConfiguration(CachingConfig::class.java)

    /**
     * 带数据的事务管理器：写入先进入当前线程的事务缓冲，提交之后才对其它线程可见；savepoint 是缓冲的快照。
     */
    class Rows : AbstractPlatformTransactionManager() {
        private val committed = ConcurrentHashMap<String, String>()
        private val current = ThreadLocal<MutableMap<String, String>?>()

        init {
            isNestedTransactionAllowed = true
        }

        fun write(key: String, value: String) {
            requireNotNull(current.get()) { "No transaction." }[key] = value
        }

        fun read(key: String): String? = current.get()?.get(key) ?: committed[key]

        private inner class Transaction(val existing: Boolean) : SavepointManager {
            override fun createSavepoint(): Any = LinkedHashMap(requireNotNull(current.get()))

            @Suppress("UNCHECKED_CAST")
            override fun rollbackToSavepoint(savepoint: Any) {
                requireNotNull(current.get()).apply {
                    clear()
                    putAll(savepoint as Map<String, String>)
                }
            }

            override fun releaseSavepoint(savepoint: Any) = Unit
        }

        override fun doGetTransaction(): Any = Transaction(current.get() != null)

        override fun isExistingTransaction(transaction: Any): Boolean = (transaction as Transaction).existing

        override fun doBegin(transaction: Any, definition: TransactionDefinition) = current.set(LinkedHashMap())

        override fun doCommit(status: DefaultTransactionStatus) = committed.putAll(requireNotNull(current.get()))

        override fun doRollback(status: DefaultTransactionStatus) = Unit

        override fun doSetRollbackOnly(status: DefaultTransactionStatus) = Unit

        override fun doCleanupAfterCompletion(transaction: Any) = current.remove()
    }

    open class AccountService(private val rows: Rows) {
        open val loads = AtomicInteger()

        @Cacheable(cacheNames = ["account"], key = "#id", sync = true)
        open fun find(id: String): String? {
            loads.incrementAndGet()
            return rows.read(id)
        }

        @CacheEvict(cacheNames = ["account"], key = "#id")
        open fun update(id: String, value: String) = rows.write(id, value)

        /**
         * 在自己的事务中写入并读出未提交的行，然后标记回滚（不抛出：缓存通知照常写入）。
         */
        @Transactional
        @Cacheable(cacheNames = ["account"], key = "#id", sync = true)
        open fun draft(id: String): String {
            rows.write(id, "draft")
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
            return requireNotNull(rows.read(id))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class FillConfig {
        @Bean
        fun rows(): Rows = Rows()

        @Bean
        fun accountSpec(): CacheSpec<String, String> = cacheSpec("account") {
            codec = StringCodec
            externallySourced()
        }

        @Bean
        fun accountService(rows: Rows): AccountService = AccountService(rows)
    }

    @Configuration(proxyBeanMethods = false)
    class FaultConfig {
        @Bean(destroyMethod = "close")
        fun hookedBackend(): CoherenceBackend = HookedBackend(InMemoryBackend()).apply { closesDelegate = true }
    }

    @EnableCaching
    @Configuration(proxyBeanMethods = false)
    class CachingConfig

    /**
     * 事务通知在外层（推荐的 order）。
     */
    @EnableCaching
    @EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)
    @Configuration(proxyBeanMethods = false)
    class TransactionOutermostConfig

    /**
     * 缓存通知在外层。
     */
    @EnableTransactionManagement
    @EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 1)
    @Configuration(proxyBeanMethods = false)
    class CacheOutermostConfig

    @Configuration(proxyBeanMethods = false)
    class OwnObserverConfig {
        @Bean
        fun customFillObserver(): FillObserver = object : FillObserver {
            override fun enter(): FillScope? = null
        }
    }

    @Configuration(proxyBeanMethods = false)
    class LayeredConfig {
        @Bean
        fun ledgerSpec(): CacheSpec<String, String> = cacheSpec("ledger") {
            codec = StringCodec
            loader = CacheLoader { "ledger-$it" }
        }

        @Bean
        fun docSpec(): StoreSpec<String, String> = storeSpec("doc") { codec = StringCodec }
    }

    @Test
    fun fillObserverIsWired() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            context.getBean("cocacheFillObserver").assert().isInstanceOf(TransactionalFillObserver::class.java)
            fillEntries(context, "account").assert().containsExactly(
                PolicyReport.Entry("invalidate-fills-on-rollback", "true", PolicyReport.Source.TYPE_DEFAULT),
                PolicyReport.Entry("fill-in-transaction", "registered-keys", PolicyReport.Source.TYPE_DEFAULT),
            )
        }
        // 应用自己的回源钩子：两个字段不登记、不列入报告
        runner.withUserConfiguration(OwnObserverConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean("cocacheFillObserver")
            assertThat(context).doesNotHaveBean(TransactionalFillModes::class.java)
            fillEntries(context, "account").assert().isEmpty()
        }
    }

    /**
     * 两个机制按字段分层（RFC 0005 §4.8）：`defaults.cache` 设定，`overrides.cache` 取更严的值，`caches.[name]` 设定；报告列出来源。
     * 两者对一个缓存都关闭时，它的回源不感知事务：回滚之后事务内的填充留下。没有登记的缓存（未分层）取类型默认值。
     */
    @Test
    fun modesLayerPerCache() {
        runner.withUserConfiguration(LayeredConfig::class.java)
            .withPropertyValues(
                "cocache.defaults.cache.fill-in-transaction=allow",
                "cocache.defaults.cache.invalidate-fills-on-rollback=false",
                "cocache.overrides.cache.fill-in-transaction=registered-keys",
                "cocache.caches.account.fill-in-transaction=allow",
                "cocache.defaults.store.fill-in-transaction=private",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val modes = context.getBean(TransactionalFillModes::class.java)
                modes.modeOf("account").assert().isEqualTo(TransactionalFillModes.Mode(false, FillInTransaction.ALLOW))
                modes.modeOf("ledger").assert().isEqualTo(
                    TransactionalFillModes.Mode(false, FillInTransaction.REGISTERED_KEYS)
                )
                modes.modeOf("unlayered").assert().isEqualTo(TransactionalFillModes.Mode.DEFAULT)
                fillEntries(context, "account").assert().containsExactly(
                    PolicyReport.Entry("invalidate-fills-on-rollback", "false", PolicyReport.Source.DEFAULTS),
                    PolicyReport.Entry("fill-in-transaction", "allow", PolicyReport.Source.CACHES),
                )
                fillEntries(context, "ledger").assert().containsExactly(
                    PolicyReport.Entry("invalidate-fills-on-rollback", "false", PolicyReport.Source.DEFAULTS),
                    PolicyReport.Entry(
                        "fill-in-transaction",
                        "registered-keys",
                        PolicyReport.Source.OVERRIDES,
                        "stricter than allow"
                    ),
                )
                // 权威副本没有回源：全局层跳过，报告列出
                val doc = requireNotNull(requireNotNull(runtime(context).admin("doc")).policyReport)
                fillEntries(context, "doc").assert().isEmpty()
                doc.skipped.assert().contains(
                    PolicyReport.Skipped(
                        "cocache.defaults.store.fill-in-transaction",
                        "cocache.defaults.store",
                        "not applicable to a Store"
                    )
                )
                val (rows, service) = beans(context)
                TransactionTemplate(rows).executeWithoutResult { status ->
                    rows.write("7", "draft")
                    service.find("7").assert().isEqualTo("draft")
                    status.setRollbackOnly()
                }
                inspect(context, "7").remote.value.assert().isEqualTo("draft")
            }
    }

    /**
     * `overrides` 只收紧：`invalidate-fills-on-rollback` 中 `true` 严于 `false`；比下层宽松的 `fill-in-transaction` 不生效。
     */
    @Test
    fun overridesTakeTheStricterMode() {
        runner.withPropertyValues(
            "cocache.defaults.cache.invalidate-fills-on-rollback=false",
            "cocache.overrides.cache.invalidate-fills-on-rollback=true",
            "cocache.overrides.cache.fill-in-transaction=allow",
        ).run { context ->
            fillEntries(context, "account").assert().containsExactly(
                PolicyReport.Entry(
                    "invalidate-fills-on-rollback",
                    "true",
                    PolicyReport.Source.OVERRIDES,
                    "stricter than false"
                ),
                PolicyReport.Entry("fill-in-transaction", "registered-keys", PolicyReport.Source.TYPE_DEFAULT),
            )
        }
    }

    /**
     * `caches.[name]` 中的这两个键不适用于权威副本：默认启动失败，`caches-validation: warn` 时只告警。
     */
    @Test
    fun perCacheModesOnAStoreFollowCachesValidation() {
        val store = runner.withUserConfiguration(LayeredConfig::class.java)
            .withPropertyValues("cocache.caches.doc.invalidate-fills-on-rollback=true")
        store.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).failure.rootCause.hasMessageContaining(
                "Cache[doc] - cocache.caches.doc.invalidate-fills-on-rollback doesn't apply to a Store."
            )
        }
        store.withPropertyValues("cocache.caches-validation=warn").run { context ->
            assertThat(context).hasNotFailed()
        }
    }

    /**
     * 缺陷复现（C2）：事务内 `@Cacheable(sync = true)` 读到的未提交行进入 L1，回滚之后留到 `ttl`。机制 1 在回滚时失效它。
     */
    @Test
    fun rollbackInvalidatesACacheableFillInsideTheTransaction() {
        runner.run { context ->
            val (rows, service) = beans(context)
            TransactionTemplate(rows).executeWithoutResult { status ->
                rows.write("1", "draft")
                service.find("1").assert().isEqualTo("draft")
                status.setRollbackOnly()
            }
            inspect(context, "1").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
            service.find("1").assert().isNull()
            service.loads.get().assert().isEqualTo(2)
        }
    }

    /**
     * `NESTED` 段回滚到 savepoint 时立即失效段内的填充，不等外层结束。
     */
    @Test
    fun savepointRollbackInvalidatesTheNestedFills() {
        runner.run { context ->
            val (rows, service) = beans(context)
            val nested = TransactionTemplate(
                rows
            ).apply { propagationBehavior = TransactionDefinition.PROPAGATION_NESTED }
            TransactionTemplate(rows).executeWithoutResult {
                nested.executeWithoutResult { status ->
                    rows.write("2", "nested")
                    service.find("2").assert().isEqualTo("nested")
                    status.setRollbackOnly()
                }
                inspect(context, "2").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
            }
        }
    }

    /**
     * S15：L1 故障时只写本节点 L2 的降级填充同样在回滚时失效。
     */
    @Test
    fun rollbackInvalidatesADegradedFill() {
        runner.withUserConfiguration(FaultConfig::class.java).run { context ->
            val (rows, service) = beans(context)
            val backend = context.getBean(HookedBackend::class.java)
            TransactionTemplate(rows).executeWithoutResult { status ->
                rows.write("3", "draft")
                backend.failAll = true
                service.find("3").assert().isEqualTo("draft")
                backend.failAll = false
                inspect(context, "3").local.assert().isNotNull()
                status.setRollbackOnly()
            }
            inspect(context, "3").local.assert().isNull()
        }
    }

    /**
     * 机制 2（默认 `registered-keys`）：`@CacheEvict` 登记的 key 在同一事务内私有回源，不把未提交的行写进 L1；提交之后照常失效。
     */
    @Test
    fun evictedKeyLoadsPrivatelyInTheSameTransaction() {
        runner.run { context ->
            val (rows, service) = beans(context)
            TransactionTemplate(rows).executeWithoutResult { rows.write("4", "old") }
            service.find("4").assert().isEqualTo("old")
            TransactionTemplate(rows).executeWithoutResult {
                service.update("4", "new")
                service.find("4").assert().isEqualTo("new")
                inspect(context, "4").remote.value.assert().isEqualTo("old")
            }
            requireNotNull(runtime(context).admin("account")).metrics.fillsGated.assert().isEqualTo(1)
            service.find("4").assert().isEqualTo("new")
        }
    }

    /**
     * 事务通知在外层（S19 推荐的 order）：`sync = true` 的 loader 在事务内运行，回滚时失效它的填充。
     */
    @Test
    fun transactionOutermostInvalidatesTheRolledBackFill() {
        base.withUserConfiguration(TransactionOutermostConfig::class.java).run { context ->
            val (_, service) = beans(context)
            service.draft("5").assert().isEqualTo("draft")
            inspect(context, "5").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        }
    }

    /**
     * 缓存通知在外层时（RFC 0005 §4.9 S19 的限制）：调用入口还没有事务，loader 在方法自己开启的事务中运行，填充发生在事务结束之后，
     * 不记录：回滚的值留在缓存中。文档因此建议让事务通知在外层。
     */
    @Test
    fun cacheOutermostFillsAfterTheTransaction() {
        base.withUserConfiguration(CacheOutermostConfig::class.java).run { context ->
            val (_, service) = beans(context)
            service.draft("6").assert().isEqualTo("draft")
            inspect(context, "6").remote.value.assert().isEqualTo("draft")
        }
    }

    private fun beans(context: ApplicationContext): Pair<Rows, AccountService> =
        context.getBean(Rows::class.java) to context.getBean(AccountService::class.java)

    private fun runtime(context: ApplicationContext): CacheRuntime = context.getBean(CacheRuntime::class.java)

    private fun fillEntries(context: ApplicationContext, cacheName: String): List<PolicyReport.Entry> =
        requireNotNull(requireNotNull(runtime(context).admin(cacheName)).policyReport).entries.filter {
            it.field == "invalidate-fills-on-rollback" || it.field == "fill-in-transaction"
        }

    private fun inspect(context: ApplicationContext, key: String): KeyInspection =
        requireNotNull(runtime(context).admin("account")).inspect(key)
}
