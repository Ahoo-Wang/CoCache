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

import me.ahoo.cocache.core.FillKey
import me.ahoo.cocache.core.FillObserver
import me.ahoo.cocache.core.FillScope
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.function.Function

/**
 * 写事务内的私有回源（RFC 0005 §4.9 机制 2，`fill-in-transaction`）。只作用于非只读的实际事务内同步变体的回源。
 */
enum class FillInTransaction {
    /**
     * 默认：本事务已登记失效（经 [TransactionalInvalidator]、`@CacheEvict` 等）的 key 的回源是私有的。
     * 不提供事务内的读己之写：登记之后本节点其它线程照常从 L1 把已提交的旧值填回 L2，本事务之后的读取可能命中它。
     */
    REGISTERED_KEYS,

    /**
     * 写事务内的全部回源都是私有的：最严格，代价是写事务内 L2 未命中的读取不经 L1，每次回源。
     */
    PRIVATE,

    /**
     * 不门控：写事务内的回源照常写入 L1 与 L2（仍受机制 1 保护）。
     */
    ALLOW,
}

/**
 * Spring 事务的回源钩子（RFC 0005 §4.9）：同步变体在调用入口经 [enter] 捕获当前线程的写事务一次（S7），
 * 交给引擎的门控与记录。只认非只读的实际事务（线程绑定的事务同步已激活、`isActualTransactionActive`、不是只读）：
 * 只读事务的快照陈旧、非 Spring 管理的事务、响应式事务都看不到。
 *
 * 状态在每个事务自己的登记（`TransactionalOperations`，与 [TransactionalInvalidator] 同一份）中：
 * - **机制 1**（[invalidateFillsOnRollback]）：数据源在事务内产生、进入共享层的 key，在回滚、提交结果未知与回滚到 savepoint 时失效。
 * - **机制 2**（[fillInTransaction]）：见 [FillInTransaction]；“已登记”按 (keyspace, 编码 key) 匹配（S24）。私有回源计入 `cocache.fills.gated`。
 *
 * 处于写事务中的调用作为 leader 时，结果不交给事务外的跟随者（S15，由引擎保证）。
 *
 * @param invalidateFillsOnRollback 回滚时失效事务内的填充（`invalidate-fills-on-rollback`，默认开启）
 * @param fillInTransaction 每个缓存的 `fill-in-transaction`（参数是缓存名）：按缓存分层的配置（RFC 0005 §4.8）在此接入
 */
class TransactionalFillObserver(
    private val invalidateFillsOnRollback: Boolean,
    private val fillInTransaction: Function<String, FillInTransaction>,
) : FillObserver {

    /**
     * 所有缓存取同一个 [fillInTransaction]。
     */
    constructor(
        invalidateFillsOnRollback: Boolean = true,
        fillInTransaction: FillInTransaction = FillInTransaction.REGISTERED_KEYS,
    ) : this(invalidateFillsOnRollback, Function { fillInTransaction })

    override fun enter(): FillScope? {
        if (!TransactionSynchronizationManager.isSynchronizationActive() ||
            !TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ) {
            return null
        }
        return Scope(TransactionalOperations.current())
    }

    override fun toString(): String = "TransactionalFillObserver(invalidateFillsOnRollback=$invalidateFillsOnRollback)"

    /**
     * 一次调用捕获的事务：持有这个事务的登记，之后不再读线程绑定的状态。
     */
    private inner class Scope(private val operations: TransactionalOperations) : FillScope {
        override fun isPrivate(key: FillKey): Boolean = when (fillInTransaction.apply(key.cacheName)) {
            FillInTransaction.REGISTERED_KEYS -> operations.isRegistered(key)
            FillInTransaction.PRIVATE -> true
            FillInTransaction.ALLOW -> false
        }

        override fun filled(key: FillKey) {
            if (invalidateFillsOnRollback) {
                operations.filled(key)
            }
        }
    }
}
