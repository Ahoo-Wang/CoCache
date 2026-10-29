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

package me.ahoo.cocache.spring.tx

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TransactionalInvalidatorTest {
    private val invalidator = TransactionalInvalidator()
    private val users = RecordingCache("user")
    private val orders = RecordingCache("order")
    private val manager = InMemoryTransactionManager()
    private val tx = TransactionTemplate(manager)
    private val newTx = TransactionTemplate(manager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Test
    fun invalidatesImmediatelyWithoutTransaction() {
        invalidator.invalidate(users, "a")
        users.invalidations.assert().containsExactly(listOf("a"))
    }

    @Test
    fun emptyKeysAreIgnored() {
        invalidator.invalidateAll(users, emptyList())
        tx.executeWithoutResult { invalidator.invalidateAll(users, emptyList()) }
        users.invalidations.assert().isEmpty()
    }

    @Test
    fun defersUntilCommitAndMergesKeys() {
        tx.executeWithoutResult {
            invalidator.invalidate(users, "a")
            invalidator.invalidateAll(users, listOf("b", "a"))
            invalidator.invalidate(orders, "o1")
            invalidator.invalidate(users, "b")
            users.invalidations.assert().isEmpty()
            orders.invalidations.assert().isEmpty()
        }
        users.invalidations.assert().containsExactly(listOf("a", "b"))
        orders.invalidations.assert().containsExactly(listOf("o1"))
    }

    @Test
    fun rollbackInvalidates() {
        tx.executeWithoutResult { status ->
            invalidator.invalidate(users, "a")
            users.invalidations.assert().isEmpty()
            status.setRollbackOnly()
        }
        users.invalidations.assert().containsExactly(listOf("a"))
        invalidator.invalidate(users, "after")
        users.invalidations.assert().containsExactly(listOf("a"), listOf("after"))
    }

    /**
     * 缺陷复现：事务内先写库、登记失效，再在同一事务内读穿透。loader 在调用方线程上参与事务，
     * 读到未提交的行并凭 lease 写入 L1。回滚之后，任何节点都不应读到这个从未提交的值。
     */
    @Test
    fun rollbackRemovesUncommittedValueLoadedInsideTheTransaction() {
        val backend = InMemoryBackend()
        val nodeA = CacheRuntime.builder().backend(backend).build()
        val nodeB = CacheRuntime.builder().backend(backend).build()
        try {
            val spec = cacheSpec<String, String>("user") {
                codec = StringCodec
                loader = CacheLoader { manager.read(it) }
            }
            val cacheA = nodeA.create(spec)
            val cacheB = nodeB.create(spec)
            tx.executeWithoutResult { status ->
                manager.write("u1", "draft")
                invalidator.invalidate(cacheA, "u1")
                cacheA["u1"].assert().isEqualTo("draft")
                status.setRollbackOnly()
            }
            cacheB["u1"].assert().isNull()
            cacheA["u1"].assert().isNull()
        } finally {
            nodeA.close()
            nodeB.close()
            backend.close()
        }
    }

    @Test
    fun rollbackOnErrorInvalidatesEveryCacheAndKeepsTheOriginalError() {
        val failing = RecordingCache("failing", failure = IllegalStateException("remote down"))
        val business = IllegalArgumentException("business")
        val error = runCatching {
            tx.executeWithoutResult {
                invalidator.invalidate(failing, "f")
                invalidator.invalidate(users, "a")
                throw business
            }
        }.exceptionOrNull()
        // 回滚时失效的失败由 Spring 记录日志，不掩盖调用方的异常
        error.assert().isSameAs(business)
        failing.invalidations.assert().containsExactly(listOf("f"))
        users.invalidations.assert().containsExactly(listOf("a"))
    }

    /**
     * 提交失败（`STATUS_UNKNOWN`）：数据源可能已经改变，同样失效。
     */
    @Test
    fun commitFailureInvalidates() {
        manager.failCommit = true
        val error = runCatching { tx.executeWithoutResult { invalidator.invalidate(users, "a") } }.exceptionOrNull()
        error.assert().isInstanceOf(TransactionSystemException::class.java)
        users.invalidations.assert().containsExactly(listOf("a"))
    }

    @Test
    fun innerNewTransactionInvalidatesOnItsOwnCompletion() {
        tx.executeWithoutResult { outer ->
            invalidator.invalidate(users, "outer")
            newTx.executeWithoutResult { invalidator.invalidate(users, "inner") }
            users.invalidations.assert().containsExactly(listOf("inner"))
            newTx.executeWithoutResult { inner ->
                invalidator.invalidate(orders, "inner")
                inner.setRollbackOnly()
            }
            orders.invalidations.assert().containsExactly(listOf("inner"))
            outer.setRollbackOnly()
        }
        users.invalidations.assert().containsExactly(listOf("inner"), listOf("outer"))
        orders.invalidations.assert().containsExactly(listOf("inner"))
    }

    @Test
    fun failureAfterCommitReachesTheCaller() {
        val failing = RecordingCache("failing", failure = IllegalStateException("remote down"))
        val error = runCatching { tx.executeWithoutResult { invalidator.invalidate(failing, "a") } }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalStateException::class.java)
        failing.invalidations.assert().containsExactly(listOf("a"))
    }

    /**
     * 缺陷复现：一个缓存失效失败，其余缓存照常失效；第一个异常抛给调用方，其余作为 suppressed。
     */
    @Test
    fun oneFailingCacheDoesNotStopTheOthers() {
        val first = RecordingCache("first", failure = IllegalStateException("first down"))
        val second = RecordingCache("second", failure = IllegalArgumentException("second down"))
        val error = runCatching {
            tx.executeWithoutResult {
                invalidator.invalidate(first, "f")
                invalidator.invalidate(users, "a")
                invalidator.invalidate(second, "s")
                invalidator.invalidate(orders, "o")
            }
        }.exceptionOrNull()
        error.assert().isSameAs(first.failure)
        requireNotNull(error).suppressed.toList().assert().containsExactly(second.failure)
        first.invalidations.assert().containsExactly(listOf("f"))
        users.invalidations.assert().containsExactly(listOf("a"))
        second.invalidations.assert().containsExactly(listOf("s"))
        orders.invalidations.assert().containsExactly(listOf("o"))
    }

    /**
     * 缺陷复现：本类的同步已经在 `afterCommit` 中 flush，之后另一个同步的 `afterCommit` 再登记失效。
     */
    @Test
    fun registrationInALaterAfterCommitIsNotLost() {
        tx.executeWithoutResult {
            invalidator.invalidate(users, "a")
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = invalidator.invalidate(users, "late")
            })
        }
        users.invalidations.assert().containsExactly(listOf("a"), listOf("late"))
    }

    /**
     * 缺陷复现：第一次登记发生在另一个同步的 `afterCommit` 中。Spring 对同步列表的快照调用 `afterCommit`，
     * 此时注册的同步只收到 `afterCompletion`。
     */
    @Test
    fun firstRegistrationInAnotherAfterCommitIsNotLost() {
        tx.executeWithoutResult {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    invalidator.invalidate(users, "late")
                    invalidator.invalidate(users, "later")
                }
            })
        }
        users.invalidations.assert().containsExactly(listOf("late", "later"))
    }

    /**
     * 缺陷复现：排在前面的同步在 `afterCommit` 中抛出，Spring 不再调用其余同步的 `afterCommit`。
     */
    @Test
    fun earlierFailingAfterCommitDoesNotDropInvalidations() {
        val failure = IllegalStateException("listener failed")
        val error = runCatching {
            tx.executeWithoutResult {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() = throw failure
                })
                invalidator.invalidate(users, "a")
            }
        }.exceptionOrNull()
        error.assert().isSameAs(failure)
        users.invalidations.assert().containsExactly(listOf("a"))
    }

    /**
     * `afterCompletion`（`@TransactionalEventListener(AFTER_COMMIT)` 在这里执行）中同步已经清除，立即失效。
     * 此时 `isActualTransactionActive()` 仍为真，所以不能用它与同步未激活的组合判断“看不见的事务”。
     */
    @Test
    fun registrationInAfterCompletionInvalidatesImmediately() {
        var actualTransactionActive = false
        tx.executeWithoutResult {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    actualTransactionActive = TransactionSynchronizationManager.isActualTransactionActive()
                    invalidator.invalidate(users, "late")
                    users.invalidations.assert().containsExactly(listOf("late"))
                }
            })
        }
        users.invalidations.assert().containsExactly(listOf("late"))
        actualTransactionActive.assert().isTrue()
    }

    /**
     * 只记录失效调用的缓存。
     */
    private class RecordingCache(
        override val name: String,
        val failure: RuntimeException? = null
    ) : Cache<String, String> {
        val invalidations = CopyOnWriteArrayList<List<String>>()

        override fun get(key: String): String? = throw UnsupportedOperationException()
        override fun get(
            key: String,
            loader: CacheLoader<String, String>
        ): String? = throw UnsupportedOperationException()
        override fun getAll(keys: Collection<String>): Map<String, String> = throw UnsupportedOperationException()
        override fun lookup(key: String): Lookup<String> = throw UnsupportedOperationException()
        override fun put(key: String, value: String) = throw UnsupportedOperationException()
        override fun put(key: String, value: String, ttl: Duration) = throw UnsupportedOperationException()
        override fun invalidate(key: String) = invalidateAll(listOf(key))
        override fun invalidateAll(keys: Collection<String>) {
            invalidations.add(keys.toList())
            failure?.let { throw it }
        }
    }

    /**
     * 内存中的事务管理器：写入先进入当前事务的缓冲，提交之后才对其它线程可见（读已提交）；
     * 支持挂起（用于 `REQUIRES_NEW`）。
     */
    private class InMemoryTransactionManager : AbstractPlatformTransactionManager() {
        private val committed = ConcurrentHashMap<String, String>()

        /**
         * 当前线程的事务中未提交的写入；没有事务时为 null。
         */
        private val current = ThreadLocal<MutableMap<String, String>?>()

        var failCommit = false

        fun write(key: String, value: String) {
            requireNotNull(current.get()) { "No transaction." }[key] = value
        }

        fun read(key: String): String? = current.get()?.get(key) ?: committed[key]

        private class Transaction(val writes: MutableMap<String, String>?)

        override fun doGetTransaction(): Any = Transaction(current.get())

        override fun isExistingTransaction(transaction: Any): Boolean = (transaction as Transaction).writes != null

        override fun doBegin(transaction: Any, definition: TransactionDefinition) = current.set(LinkedHashMap())

        override fun doSuspend(transaction: Any): Any = requireNotNull(current.get()).also { current.remove() }

        @Suppress("UNCHECKED_CAST")
        override fun doResume(
            transaction: Any?,
            suspendedResources: Any
        ) = current.set(suspendedResources as MutableMap<String, String>)

        override fun doCommit(status: DefaultTransactionStatus) {
            if (failCommit) {
                throw TransactionSystemException("Commit failed.")
            }
            committed.putAll(requireNotNull(current.get()))
        }

        override fun doRollback(status: DefaultTransactionStatus) = Unit

        override fun doSetRollbackOnly(status: DefaultTransactionStatus) = Unit

        override fun doCleanupAfterCompletion(transaction: Any) = current.remove()
    }
}
