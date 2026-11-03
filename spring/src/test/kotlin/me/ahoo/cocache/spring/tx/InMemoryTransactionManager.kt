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

import org.springframework.transaction.SavepointManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存中的事务管理器：写入先进入当前事务的缓冲，提交之后才对其它线程可见（读已提交）；
 * 支持挂起（用于 `REQUIRES_NEW`）与 savepoint（用于 `NESTED`）。
 */
internal class InMemoryTransactionManager : AbstractPlatformTransactionManager() {
    private val committed = ConcurrentHashMap<String, String>()

    /**
     * 当前线程的事务中未提交的写入；没有事务时为 null。
     */
    private val current = ThreadLocal<MutableMap<String, String>?>()

    var failCommit = false

    init {
        isNestedTransactionAllowed = true
    }

    fun write(key: String, value: String) {
        requireNotNull(current.get()) { "No transaction." }[key] = value
    }

    fun read(key: String): String? = current.get()?.get(key) ?: committed[key]

    /**
     * savepoint 是当时未提交写入的快照，回滚到它时恢复快照。
     */
    private class Transaction(val writes: MutableMap<String, String>?) : SavepointManager {
        override fun createSavepoint(): Any = LinkedHashMap(requireNotNull(writes))

        @Suppress("UNCHECKED_CAST")
        override fun rollbackToSavepoint(savepoint: Any) {
            requireNotNull(writes).apply {
                clear()
                putAll(savepoint as Map<String, String>)
            }
        }

        override fun releaseSavepoint(savepoint: Any) = Unit
    }

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
