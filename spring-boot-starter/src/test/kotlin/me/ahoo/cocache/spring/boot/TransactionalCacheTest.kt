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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.cache.CoCacheSpringCache
import me.ahoo.cocache.spring.cache.DegradingCacheErrorHandler
import me.ahoo.cocache.spring.tx.TransactionAwareSpringCache
import me.ahoo.cocache.spring.tx.TransactionAwareSpringCacheManager
import me.ahoo.cocache.spring.tx.TransactionalInvalidator
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.CachingConfigurer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.cache.interceptor.CacheInterceptor
import org.springframework.cache.interceptor.SimpleCacheErrorHandler
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.transaction.SavepointManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.cache.Cache as SpringCache

/**
 * starter 装配的事务感知（RFC 0005 §4.9）：`cocacheCacheManager` 是 CoCache 的事务装饰器
 * （[TransactionAwareSpringCacheManager]），经 Spring Cache 注解验证提交、回滚与回滚到 savepoint；提交之后的失败交给
 * 缓存通知的 `CacheErrorHandler`；可选的 [DegradingCacheErrorHandler]；缓存通知与事务通知 order 相同时的告警；
 * `@CacheEvict(allEntries = true)` 的告警。方法体中直接写入 L1，模拟事务内读穿透写入的未提交值，或提交之前并发的读穿透写回的旧值。
 */
class TransactionalCacheTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withUserConfiguration(TransactionalConfig::class.java)
        .withPropertyValues("cocache.backend=memory")
    private val transactions = SavepointTransactionManager()
    private val tx = TransactionTemplate(transactions)
    private val nestedTx = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
    }

    open class UserWriter(private val context: ApplicationContext) {
        @CacheEvict(cacheNames = ["user"], key = "#id", beforeInvocation = true)
        open fun update(id: String) {
            user(context).put(id, "stale")
        }

        /**
         * 方法体写入的值模拟同一事务内读穿透写入 L1 的未提交行。
         */
        @CacheEvict(cacheNames = ["user"], key = "#id")
        open fun delete(id: String) {
            user(context).put(id, "uncommitted")
        }

        @CachePut(cacheNames = ["user"], key = "#id")
        open fun save(id: String, value: String): String = value

        @CacheEvict(cacheNames = ["user"], allEntries = true)
        open fun clearAll() = Unit
    }

    open class ProfileReader {
        @Cacheable(cacheNames = ["profile"], key = "#id")
        open fun unsynced(id: String): String = "profile-$id"

        @Cacheable(cacheNames = ["profile"], key = "#id", sync = true)
        open fun synced(id: String): String = "profile-$id"
    }

    @EnableCaching
    @Configuration(proxyBeanMethods = false)
    class TransactionalConfig {
        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader { "user-$it" }
        }

        /**
         * 与 Spring Cache 的通常用法一样没有数据源：被注解的方法是数据源。
         */
        @Bean
        fun profileSpec(): CacheSpec<String, String> = cacheSpec("profile") {
            codec = StringCodec
        }

        @Bean
        fun userWriter(context: ApplicationContext): UserWriter = UserWriter(context)

        @Bean
        fun profileReader(): ProfileReader = ProfileReader()
    }

    @Configuration(proxyBeanMethods = false)
    class FaultConfig {
        @Bean(destroyMethod = "close")
        fun hookedBackend(): CoherenceBackend = HookedBackend(InMemoryBackend()).apply { closesDelegate = true }
    }

    @Configuration(proxyBeanMethods = false)
    class ErrorHandlerConfig {
        @Bean
        fun recordingErrorHandler(): RecordingErrorHandler = RecordingErrorHandler()

        @Bean
        fun cachingConfigurer(handler: RecordingErrorHandler): CachingConfigurer = object : CachingConfigurer {
            override fun errorHandler(): CacheErrorHandler = handler
        }
    }

    @EnableTransactionManagement
    @Configuration(proxyBeanMethods = false)
    class SameOrderConfig

    @EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)
    @Configuration(proxyBeanMethods = false)
    class TransactionOutermostConfig

    @Test
    fun cacheManagerIsTheCoCacheTransactionDecorator() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val manager = context.getBean(CacheManager::class.java)
            manager.assert().isInstanceOf(TransactionAwareSpringCacheManager::class.java)
            val cache = requireNotNull(manager.getCache("user"))
            cache.assert().isInstanceOf(TransactionAwareSpringCache::class.java)
            (cache as TransactionAwareSpringCache).targetCache.assert().isInstanceOf(CoCacheSpringCache::class.java)
            manager.getCache("user").assert().isSameAs(cache)
        }
    }

    /**
     * 缺陷复现（S5）：事务内的 `@CacheEvict(beforeInvocation = true)` 只在提交之前失效，提交之前写回 L1 的旧值留到 `ttl`。
     */
    @Test
    fun evictBeforeInvocationInsideTransactionInvalidatesAgainAfterCommit() {
        runner.run { context ->
            val writer = context.getBean(UserWriter::class.java)
            tx.executeWithoutResult {
                writer.update("1")
                user(context)["1"].assert().isEqualTo("stale")
            }
            user(context)["1"].assert().isEqualTo("user-1")
        }
    }

    @Test
    fun evictBeforeInvocationInsideTransactionInvalidatesAgainAfterRollback() {
        runner.run { context ->
            val writer = context.getBean(UserWriter::class.java)
            tx.executeWithoutResult { status ->
                writer.update("2")
                status.setRollbackOnly()
            }
            user(context)["2"].assert().isEqualTo("user-2")
        }
    }

    /**
     * 缺陷复现（U3）：Spring 的 `TransactionAwareCacheDecorator` 只在提交之后执行 `@CacheEvict`，回滚时同一事务内读穿透写入的
     * 未提交值留到 `ttl`。CoCache 的装饰器把它登记到事务，提交与回滚都失效。
     */
    @Test
    fun evictInsideTransactionInvalidatesOnCommitAndOnRollback() {
        runner.run { context ->
            val writer = context.getBean(UserWriter::class.java)
            tx.executeWithoutResult {
                writer.delete("3")
                user(context)["3"].assert().isEqualTo("uncommitted")
            }
            user(context)["3"].assert().isEqualTo("user-3")
            tx.executeWithoutResult { status ->
                writer.delete("4")
                status.setRollbackOnly()
            }
            user(context)["4"].assert().isEqualTo("user-4")
        }
    }

    /**
     * `@CachePut` 推迟到提交之后，回滚时丢弃；回滚到 savepoint 时丢弃嵌套段内的写入（6.x 的已知限制：Spring 的装饰器看不到
     * 回滚到 savepoint，嵌套段内的值仍在外层提交时写入）。之后的 `@CacheEvict` 取代之前的 `@CachePut`。
     */
    @Test
    fun cachePutIsDeferredUntilCommitAndDroppedOnRollback() {
        runner.run { context ->
            val writer = context.getBean(UserWriter::class.java)
            val user = user(context)
            tx.executeWithoutResult {
                writer.save("5", "saved")
                user["5"].assert().isEqualTo("user-5")
            }
            user["5"].assert().isEqualTo("saved")
            tx.executeWithoutResult { status ->
                writer.save("6", "rolled-back")
                status.setRollbackOnly()
            }
            user["6"].assert().isEqualTo("user-6")
            tx.executeWithoutResult {
                writer.save("7", "outer")
                nestedTx.executeWithoutResult { nested ->
                    writer.save("7", "nested")
                    writer.save("8", "nested")
                    nested.setRollbackOnly()
                }
            }
            user["7"].assert().isEqualTo("outer")
            user["8"].assert().isEqualTo("user-8")
            tx.executeWithoutResult {
                writer.save("9", "deleted")
                writer.delete("9")
            }
            user["9"].assert().isEqualTo("user-9")
        }
    }

    /**
     * 缺陷复现（S6）：回滚到 savepoint 之后，嵌套段内写入 L1 的未提交值不应在外层事务结束之前一直可见；
     * 经 `cocacheTransactionalInvalidator`、`@CacheEvict` 与 `@CacheEvict(beforeInvocation = true)` 登记的 key 都在回滚到 savepoint 时失效。
     */
    @Test
    fun savepointRollbackInvalidatesRegisteredKeysBeforeTheOuterCommit() {
        runner.run { context ->
            val invalidator = context.getBean(TransactionalInvalidator::class.java)
            val writer = context.getBean(UserWriter::class.java)
            val user = user(context)
            tx.executeWithoutResult {
                nestedTx.executeWithoutResult { nested ->
                    invalidator.invalidate(user, "10")
                    user.put("10", "draft")
                    writer.update("11")
                    writer.delete("12")
                    nested.setRollbackOnly()
                }
                user["10"].assert().isEqualTo("user-10")
                user["11"].assert().isEqualTo("user-11")
                user["12"].assert().isEqualTo("user-12")
            }
        }
    }

    /**
     * 缺陷复现（S18）：提交之后 `@CachePut` 的失败交给缓存通知的 `CacheErrorHandler`（`CachingConfigurer` 配置的），
     * 不在提交之后抛给调用方。
     */
    @Test
    fun failureAfterCommitGoesToTheConfiguredErrorHandler() {
        runner.withUserConfiguration(FaultConfig::class.java, ErrorHandlerConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val backend = context.getBean(HookedBackend::class.java)
            val handler = context.getBean(RecordingErrorHandler::class.java)
            val writer = context.getBean(UserWriter::class.java)
            tx.executeWithoutResult {
                writer.save("13", "saved")
                backend.failWrites = true
            }
            backend.failWrites = false
            handler.events.assert().containsExactly("put:user:13:saved")
            user(context)["13"].assert().isEqualTo("user-13")
        }
    }

    /**
     * 不对称（S18）：Spring 默认的处理器下，不带 `sync` 的 `@Cacheable` 在 L1 故障时失败，带 `sync = true` 的降级回源。
     * `degrade-on-error` 没有开启时缓存通知的处理器保持不变。
     */
    @Test
    fun unsyncedCacheableFailsOnAnL1FaultByDefault() {
        runner.withUserConfiguration(FaultConfig::class.java).run { context ->
            val interceptor = context.getBean(CacheInterceptor::class.java)
            interceptor.errorHandler.assert().isInstanceOf(SimpleCacheErrorHandler::class.java)
            val backend = context.getBean(HookedBackend::class.java)
            val reader = context.getBean(ProfileReader::class.java)
            backend.failAll = true
            runCatching { reader.unsynced("1") }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
            reader.synced("2").assert().isEqualTo("profile-2")
            backend.failAll = false
        }
    }

    /**
     * `cocache.spring-cache.degrade-on-error=true`：CoCache 缓存的读写失败放行，不带 `sync` 的 `@Cacheable` 按未命中执行方法；
     * 原来的处理器（`CachingConfigurer` 配置的）继续处理其它失败。
     */
    @Test
    fun degradeOnErrorLetsCoCacheReadAndWriteFailuresThrough() {
        runner.withUserConfiguration(FaultConfig::class.java, ErrorHandlerConfig::class.java)
            .withPropertyValues("cocache.spring-cache.degrade-on-error=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                val handler = context.getBean(CacheInterceptor::class.java).errorHandler
                handler.assert().isInstanceOf(DegradingCacheErrorHandler::class.java)
                val recording = context.getBean(RecordingErrorHandler::class.java)
                (handler as DegradingCacheErrorHandler).delegate.assert().isSameAs(recording)
                val backend = context.getBean(HookedBackend::class.java)
                val reader = context.getBean(ProfileReader::class.java)
                backend.failAll = true
                reader.unsynced("1").assert().isEqualTo("profile-1")
                backend.failAll = false
                recording.events.assert().isEmpty()
            }
    }

    @Test
    fun degradeOnErrorWrapsSpringsDefaultHandlerOnce() {
        runner.withPropertyValues("cocache.spring-cache.degrade-on-error=true").run { context ->
            val interceptor = context.getBean(CacheInterceptor::class.java)
            val handler = interceptor.errorHandler as DegradingCacheErrorHandler
            handler.delegate.assert().isInstanceOf(SimpleCacheErrorHandler::class.java)
            val installer = context.getBean("cocacheDegradingCacheErrorHandler") as SmartInitializingSingleton
            installer.afterSingletonsInstantiated()
            interceptor.errorHandler.assert().isSameAs(handler)
        }
    }

    /**
     * 缓存通知与事务通知的 order 相同（默认都是 `LOWEST_PRECEDENCE`）时告警（S19）；事务通知在外层时不告警。
     */
    @Test
    fun sameAdviceOrderIsWarned() {
        captureLogs(SpringCacheSyncCheck::class.java) { logs ->
            runner.withUserConfiguration(SameOrderConfig::class.java).run { context ->
                assertThat(context).hasNotFailed()
                val check = context.getBean(SpringCacheSyncCheck::class.java)
                check.sameAdviceOrder.assert().isEqualTo(Ordered.LOWEST_PRECEDENCE)
            }
            logs.filter { it.level == Level.WARN }.map { it.formattedMessage }
                .any { it.contains("same order") && it.contains("@EnableTransactionManagement(order") }
                .assert().withFailMessage { "advice order warning not logged: $logs" }.isTrue()
        }
        runner.withUserConfiguration(TransactionOutermostConfig::class.java).run { context ->
            context.getBean(SpringCacheSyncCheck::class.java).sameAdviceOrder.assert().isNull()
        }
        runner.run { context ->
            context.getBean(SpringCacheSyncCheck::class.java).sameAdviceOrder.assert().isNull()
        }
    }

    /**
     * `@CacheEvict(allEntries = true)` 只清空本节点的本地副本，每个缓存只告警一次（U19）。
     */
    @Test
    fun allEntriesClearsTheLocalCopyAndWarnsOnce() {
        captureLogs(CoCacheSpringCache::class.java) { logs ->
            runner.run { context ->
                val writer = context.getBean(UserWriter::class.java)
                user(context).put("14", "kept")
                writer.clearAll()
                writer.clearAll()
                tx.executeWithoutResult { writer.clearAll() }
                user(context)["14"].assert().isEqualTo("kept")
            }
            logs.filter { it.level == Level.WARN && it.formattedMessage.contains("allEntries = true") }
                .map { it.formattedMessage }.assert().hasSize(1)
        }
    }

    class RecordingErrorHandler : CacheErrorHandler {
        val events = CopyOnWriteArrayList<String>()

        override fun handleCacheGetError(exception: RuntimeException, cache: SpringCache, key: Any) {
            events += "get:${cache.name}:$key"
        }

        override fun handleCachePutError(exception: RuntimeException, cache: SpringCache, key: Any, value: Any?) {
            events += "put:${cache.name}:$key:$value"
        }

        override fun handleCacheEvictError(exception: RuntimeException, cache: SpringCache, key: Any) {
            events += "evict:${cache.name}:$key"
        }

        override fun handleCacheClearError(exception: RuntimeException, cache: SpringCache) {
            events += "clear:${cache.name}"
        }
    }

    /**
     * 只提供事务同步与 savepoint 的事务管理器，没有数据。
     */
    private class SavepointTransactionManager : AbstractPlatformTransactionManager() {
        private val active = ThreadLocal<Boolean>()

        init {
            isNestedTransactionAllowed = true
        }

        private class Transaction(val existing: Boolean) : SavepointManager {
            override fun createSavepoint(): Any = Any()

            override fun rollbackToSavepoint(savepoint: Any) = Unit

            override fun releaseSavepoint(savepoint: Any) = Unit
        }

        override fun doGetTransaction(): Any = Transaction(active.get() == true)

        override fun isExistingTransaction(transaction: Any): Boolean = (transaction as Transaction).existing

        override fun doBegin(transaction: Any, definition: TransactionDefinition) = active.set(true)

        override fun doCommit(status: DefaultTransactionStatus) = Unit

        override fun doRollback(status: DefaultTransactionStatus) = Unit

        override fun doCleanupAfterCompletion(transaction: Any) = active.remove()
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun user(context: ApplicationContext): Cache<String, String> =
            context.getBean("user") as Cache<String, String>

        private fun captureLogs(type: Class<*>, block: (List<ILoggingEvent>) -> Unit) {
            val logger = LoggerFactory.getLogger(type) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                block(appender.list)
            } finally {
                logger.detachAppender(appender)
            }
        }
    }
}
