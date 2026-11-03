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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.spring.tx.TransactionalInvalidator
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.SavepointManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

/**
 * starter 装配的事务感知：`cocacheCacheManager` 是 `TransactionAwareCacheManagerProxy`，它直接委托
 * `@CacheEvict(beforeInvocation = true)`（`evictIfPresent`）；`cocacheTransactionalInvalidator` 处理回滚到 savepoint。
 * 方法体中直接写入 L1，模拟提交之前并发的读穿透写回的旧值，或嵌套段内读穿透写入的未提交值。
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
    }

    @EnableCaching
    @Configuration(proxyBeanMethods = false)
    class TransactionalConfig {
        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader { "user-$it" }
        }

        @Bean
        fun userWriter(context: ApplicationContext): UserWriter = UserWriter(context)
    }

    /**
     * 缺陷复现（S5）：事务内的 `@CacheEvict(beforeInvocation = true)` 只在提交之前失效，提交之前写回 L1 的旧值留到 `ttl`。
     */
    @Test
    fun evictBeforeInvocationInsideTransactionInvalidatesAgainAfterCommit() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val manager = context.getBean(CacheManager::class.java)
            manager.assert().isInstanceOf(TransactionAwareCacheManagerProxy::class.java)
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
     * 缺陷复现（S6）：回滚到 savepoint 之后，嵌套段内写入 L1 的未提交值不应在外层事务结束之前一直可见；
     * 经 `cocacheTransactionalInvalidator` 与 `@CacheEvict(beforeInvocation = true)` 登记的 key 都在回滚到 savepoint 时失效。
     */
    @Test
    fun savepointRollbackInvalidatesRegisteredKeysBeforeTheOuterCommit() {
        runner.run { context ->
            val invalidator = context.getBean(TransactionalInvalidator::class.java)
            val writer = context.getBean(UserWriter::class.java)
            val user = user(context)
            tx.executeWithoutResult {
                nestedTx.executeWithoutResult { nested ->
                    invalidator.invalidate(user, "3")
                    user.put("3", "draft")
                    writer.update("4")
                    nested.setRollbackOnly()
                }
                user["3"].assert().isEqualTo("user-3")
                user["4"].assert().isEqualTo("user-4")
            }
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
    }
}
