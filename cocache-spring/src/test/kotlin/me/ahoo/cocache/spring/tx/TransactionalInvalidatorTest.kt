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

import me.ahoo.cocache.api.CoCache
import me.ahoo.cocache.api.Lookup
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
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
    fun rollbackDoesNotInvalidate() {
        tx.executeWithoutResult { status ->
            invalidator.invalidate(users, "a")
            status.setRollbackOnly()
        }
        users.invalidations.assert().isEmpty()
        invalidator.invalidate(users, "after")
        users.invalidations.assert().containsExactly(listOf("after"))
    }

    @Test
    fun innerNewTransactionInvalidatesOnItsOwnCommit() {
        tx.executeWithoutResult { outer ->
            invalidator.invalidate(users, "outer")
            newTx.executeWithoutResult { invalidator.invalidate(users, "inner") }
            users.invalidations.assert().containsExactly(listOf("inner"))
            outer.setRollbackOnly()
        }
        users.invalidations.assert().containsExactly(listOf("inner"))
    }

    @Test
    fun failureAfterCommitReachesTheCaller() {
        val failing = RecordingCache("failing", failure = IllegalStateException("remote down"))
        val error = runCatching { tx.executeWithoutResult { invalidator.invalidate(failing, "a") } }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalStateException::class.java)
        failing.invalidations.assert().containsExactly(listOf("a"))
    }

    /**
     * 只记录失效调用的缓存。
     */
    private class RecordingCache(
        override val name: String,
        private val failure: RuntimeException? = null
    ) : CoCache<String, String> {
        val invalidations = CopyOnWriteArrayList<List<String>>()

        override fun get(key: String): String? = throw UnsupportedOperationException()
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
     * 不访问任何资源的事务管理器，支持挂起（用于 REQUIRES_NEW）。
     */
    private class InMemoryTransactionManager : AbstractPlatformTransactionManager() {
        private val active = ThreadLocal.withInitial { false }

        private class Transaction(val existing: Boolean)

        override fun doGetTransaction(): Any = Transaction(active.get())

        override fun isExistingTransaction(transaction: Any): Boolean = (transaction as Transaction).existing

        override fun doBegin(transaction: Any, definition: TransactionDefinition) = active.set(true)

        override fun doSuspend(transaction: Any): Any {
            active.set(false)
            return transaction
        }

        override fun doResume(transaction: Any?, suspendedResources: Any) = active.set(true)

        override fun doCommit(status: DefaultTransactionStatus) = Unit

        override fun doRollback(status: DefaultTransactionStatus) = Unit

        override fun doSetRollbackOnly(status: DefaultTransactionStatus) = Unit

        override fun doCleanupAfterCompletion(transaction: Any) = active.set(false)
    }
}
