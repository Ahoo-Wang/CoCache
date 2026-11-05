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

import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SuspendingCache
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
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
    private val nestedTx = TransactionTemplate(manager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
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

    /**
     * 登记按事务划分，不按实例：同一事务内经不同实例登记的失效进入同一个同步，同一缓存仍合并为一次 `invalidateAll`。
     */
    @Test
    fun instancesShareOneRegistrationPerTransaction() {
        val other = TransactionalInvalidator()
        tx.executeWithoutResult {
            invalidator.invalidate(users, "a")
            other.invalidate(users, "b")
            TransactionSynchronizationManager.getSynchronizations().assert().hasSize(1)
        }
        users.invalidations.assert().containsExactly(listOf("a", "b"))
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

    /**
     * 缺陷复现：NESTED 段内读穿透把未提交的值写入 L1，随后回滚到 savepoint。Spring 不对外层的同步调用 `afterCompletion`，
     * 只调用 `savepointRollback`；外层事务之后提交，这个从未提交的值不应在外层结束之前一直对全集群（以及外层事务自己）可见。
     */
    @Test
    fun savepointRollbackRemovesUncommittedValueLoadedInsideTheNestedTransaction() {
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
            tx.executeWithoutResult {
                nestedTx.executeWithoutResult { nested ->
                    manager.write("u1", "draft")
                    invalidator.invalidate(cacheA, "u1")
                    cacheA["u1"].assert().isEqualTo("draft")
                    nested.setRollbackOnly()
                }
                manager.read("u1").assert().isNull()
                // 外层事务仍在进行：另一个节点（事务之外）与外层事务自己都不应再读到 draft
                fork { cacheB["u1"] }.join().assert().isNull()
                cacheA["u1"].assert().isNull()
            }
            cacheB["u1"].assert().isNull()
            cacheA["u1"].assert().isNull()
        } finally {
            nodeA.close()
            nodeB.close()
            backend.close()
        }
    }

    /**
     * 回滚到 savepoint 时立即失效到目前为止登记的全部 key（包括外层在 savepoint 之前登记的）；登记保留，外层结束时照常再失效一次。
     * 嵌套段正常结束（释放 savepoint）时什么也不做。
     */
    @Test
    fun savepointRollbackInvalidatesEveryRegisteredKeyAndKeepsThem() {
        tx.executeWithoutResult {
            invalidator.invalidate(users, "outer")
            nestedTx.executeWithoutResult { invalidator.invalidate(users, "released") }
            users.invalidations.assert().isEmpty()
            nestedTx.executeWithoutResult { nested ->
                invalidator.invalidate(users, "nested")
                invalidator.invalidate(orders, "o1")
                nested.setRollbackOnly()
            }
            users.invalidations.assert().containsExactly(listOf("outer", "released", "nested"))
            orders.invalidations.assert().containsExactly(listOf("o1"))
            invalidator.invalidate(users, "after")
        }
        users.invalidations.assert().containsExactly(
            listOf("outer", "released", "nested"),
            listOf("outer", "released", "nested", "after")
        )
        orders.invalidations.assert().containsExactly(listOf("o1"), listOf("o1"))
    }

    /**
     * Spring 在回滚数据库到 savepoint 之前调用 `savepointRollback`，回调抛出会跳过数据库的回滚。
     * 失效失败因此不抛出：嵌套段的写入照常回滚，其余缓存照常失效，外层结束时的失效兜底并照常报告失败。
     */
    @Test
    fun failingInvalidationOnSavepointRollbackDoesNotSkipTheDatabaseRollback() {
        val failing = RecordingCache("failing", failure = IllegalStateException("remote down"))
        val business = IllegalArgumentException("business")
        val error = runCatching {
            tx.executeWithoutResult {
                runCatching {
                    nestedTx.executeWithoutResult {
                        manager.write("u1", "draft")
                        invalidator.invalidate(failing, "f")
                        invalidator.invalidate(users, "a")
                        throw business
                    }
                }.exceptionOrNull().assert().isSameAs(business)
                manager.read("u1").assert().isNull()
                failing.invalidations.assert().containsExactly(listOf("f"))
                users.invalidations.assert().containsExactly(listOf("a"))
            }
        }.exceptionOrNull()
        error.assert().isSameAs(failing.failure)
        manager.read("u1").assert().isNull()
        failing.invalidations.assert().containsExactly(listOf("f"), listOf("f"))
        users.invalidations.assert().containsExactly(listOf("a"), listOf("a"))
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
        override fun lookupAll(keys: Collection<String>): Map<String, Lookup<String>> =
            throw UnsupportedOperationException()
        override fun putAll(entries: Map<String, String>) = throw UnsupportedOperationException()
        override fun putAll(entries: Map<String, String>, ttl: Duration) = throw UnsupportedOperationException()
        override fun invalidate(key: String) = invalidateAll(listOf(key))
        override fun invalidateAll(keys: Collection<String>) {
            invalidations.add(keys.toList())
            failure?.let { throw it }
        }
        override fun async(): AsyncCache<String, String> = throw UnsupportedOperationException()
        override fun suspending(): SuspendingCache<String, String> = throw UnsupportedOperationException()
    }
}
