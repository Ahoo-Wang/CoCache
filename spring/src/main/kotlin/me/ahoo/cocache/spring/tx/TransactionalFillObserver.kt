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
import java.util.function.Predicate

/**
 * 写事务内的私有回源（RFC 0005 §4.9 机制 2，`fill-in-transaction`）。只作用于非只读的实际事务内同步变体的回源。
 */
enum class FillInTransaction {
    /**
     * 默认：本事务已登记失效（经 [TransactionalInvalidator]、`@CacheEvict` 等）的 key 的回源是私有的；回滚到 savepoint 时本事务
     * 已经填充过的 key 同样如此（直到本事务结束）。
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
 * - **机制 1**（[invalidateFillsOnRollback]）：数据源在事务内产生、进入共享层的 key，在回滚、提交结果未知与回滚到 savepoint 时失效；
 *   回滚过 savepoint 的事务提交时同样失效。
 * - **机制 2**（[fillInTransaction]）：见 [FillInTransaction]；“已登记”按 (后端, keyspace, 编码 key) 匹配（S24）。私有回源计入 `cocache.fills.gated`。
 *
 * 两个机制都按缓存名取值：按缓存分层的配置（RFC 0005 §4.8）在此接入。两者对一个缓存都关闭时，这个缓存的调用不进入事务
 * （[enter] 返回 `null`），与 [FillObserver.NONE] 相同。处于写事务中的调用作为 leader 时，结果不交给事务外的跟随者（S15，由引擎保证）。
 * 两个函数在写事务内的每次同步未命中上调用，应当便宜。
 *
 * 一次调用只取一次两个值（[enter] 时，或不带缓存名的入口在第一次用到这个缓存时），整次调用（门控、回源、写回之前的记录）都用它们，
 * 不在回源之后再取：取值在回源期间改变时（starter 中缓存创建期间开始的加载，模式在创建监听中才生效），混用两组值会让两个机制都失效：
 * 旧值放行共享填充，新值又跳过回滚失效，回滚之后未提交的值留在共享层（#868）。
 *
 * @param invalidateFillsOnRollback 每个缓存的 `invalidate-fills-on-rollback`（参数是缓存名）
 * @param fillInTransaction 每个缓存的 `fill-in-transaction`（参数是缓存名）
 */
class TransactionalFillObserver(
    private val invalidateFillsOnRollback: Predicate<String>,
    private val fillInTransaction: Function<String, FillInTransaction>,
) : FillObserver {

    /**
     * 所有缓存取同样的两个值。
     */
    constructor(
        invalidateFillsOnRollback: Boolean = true,
        fillInTransaction: FillInTransaction = FillInTransaction.REGISTERED_KEYS,
    ) : this(Predicate { invalidateFillsOnRollback }, Function { fillInTransaction })

    /**
     * 先看是否处于写事务（线程绑定的状态，便宜），再按缓存名取两个值（各一次，交给这次调用）：事务之外的未命中不查找。
     * 两者都关闭时不取事务的登记。
     */
    override fun enter(cacheName: String): FillScope? {
        if (!inWriteTransaction()) {
            return null
        }
        val mode = modeOf(cacheName)
        if (!mode.invalidateFillsOnRollback && mode.fillInTransaction == FillInTransaction.ALLOW) {
            return null
        }
        return Scope(TransactionalOperations.current(), cacheName, mode)
    }

    override fun enter(): FillScope? = if (inWriteTransaction()) Scope(TransactionalOperations.current()) else null

    /**
     * 先取机制 1：两个值来自取值之间改变的两组配置时，机制 1 取自较早的一组。
     */
    private fun modeOf(cacheName: String): Mode {
        val invalidate = invalidateFillsOnRollback.test(cacheName)
        return Mode(invalidate, fillInTransaction.apply(cacheName))
    }

    private fun inWriteTransaction(): Boolean = TransactionSynchronizationManager.isSynchronizationActive() &&
        TransactionSynchronizationManager.isActualTransactionActive() &&
        !TransactionSynchronizationManager.isCurrentTransactionReadOnly()

    override fun toString(): String = "TransactionalFillObserver"

    /**
     * 一个缓存的两个值，一次调用取一次。
     */
    private data class Mode(val invalidateFillsOnRollback: Boolean, val fillInTransaction: FillInTransaction)

    /**
     * 一次调用捕获的事务：持有这个事务的登记与每个缓存的两个值（[enter] 时取的，或在第一次用到时取），之后不再读线程绑定的状态与配置。
     * 只在这次调用的调用方线程上被调用（[FillScope]）。
     */
    private inner class Scope(private val operations: TransactionalOperations) : FillScope {
        private val modes = HashMap<String, Mode>(1)

        constructor(operations: TransactionalOperations, cacheName: String, mode: Mode) : this(operations) {
            modes[cacheName] = mode
        }

        private fun modeOf(key: FillKey): Mode = modes.getOrPut(key.cacheName) {
            this@TransactionalFillObserver.modeOf(key.cacheName)
        }

        override fun isPrivate(key: FillKey): Boolean = when (modeOf(key).fillInTransaction) {
            FillInTransaction.REGISTERED_KEYS -> operations.isGated(key)
            FillInTransaction.PRIVATE -> true
            FillInTransaction.ALLOW -> false
        }

        override fun filled(key: FillKey) {
            if (modeOf(key).invalidateFillsOnRollback) {
                operations.filled(key)
            }
        }
    }
}
