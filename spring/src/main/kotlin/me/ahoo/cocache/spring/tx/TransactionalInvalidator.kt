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
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 事务感知的失效（RFC 0003 §4.12）：事务内调用时登记到事务结束之后执行，没有事务时立即执行。
 *
 * 失效必须在数据源提交之后发生。若在提交之前失效，并发的回源可能读到提交前的数据并写回，
 * 这本身就是一个失效被吞没的漏洞（G2）。
 *
 * - 提交之后在 `afterCommit` 中失效。同一事务内对同一缓存的多次失效合并为一次 [Cache.invalidateAll]（去重，保持登记顺序）。
 * - 回滚或提交结果未知时同样失效（在 `afterCompletion` 中）：同步 API 的 loader 在调用方线程上参与事务，
 *   事务内的读穿透可能已经把未提交的数据写入 L1。多余的失效无害。
 * - 嵌套的新事务（`REQUIRES_NEW`）在各自结束之后失效。
 * - 迟到的登记不丢失。在其它同步的 `afterCommit` 中登记时：本事务的失效已经执行过，立即失效；
 *   本事务第一次登记（Spring 不再对此时注册的同步调用 `afterCommit`），在 `afterCompletion` 中失效。
 *   排在前面的同步的 `afterCommit` 抛出时同样在 `afterCompletion` 中失效。
 *   `afterCompletion` 中（例如 `@TransactionalEventListener(AFTER_COMMIT)`）同步已经清除，立即失效。
 * - 一个缓存失败不影响其余缓存：全部执行之后抛出第一个异常，其余作为它的 suppressed。
 * - 有数据源的缓存上，失效默认不会抛出（失败进入重试队列，见 `InvalidateFailureMode.RETRY`）。没有数据源的缓存默认、
 *   或配置为 `THROW` 时，`afterCommit` 中的异常在提交之后抛给调用方，此时数据源已经提交；`afterCompletion` 中的异常
 *   （回滚、兜底）由 Spring 记录日志，不抛给调用方。
 * - 只支持线程绑定的事务同步（`PlatformTransactionManager`）。响应式事务（`ReactiveTransactionManager`，
 *   如 R2DBC，包括 `@Transactional` 挂起函数）的同步不绑定线程，本类看不到，会立即失效，即在提交之前。
 *   这类事务请在事务结束之后、事务边界之外失效。
 *   “有实际事务但同步未激活”不能用来识别它们：响应式事务不设置这两个线程绑定的标记，
 *   而 `afterCompletion` 中正是这种组合（同步已经清除，`isActualTransactionActive()` 仍为真）。
 */
class TransactionalInvalidator {

    fun <K : Any> invalidate(cache: Cache<K, *>, key: K) {
        invalidateAll(cache, listOf(key))
    }

    fun <K : Any> invalidateAll(cache: Cache<K, *>, keys: Collection<K>) {
        if (keys.isEmpty()) {
            return
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.invalidateAll(keys)
            return
        }
        pending().add(cache, keys)
    }

    /**
     * 当前事务的待失效集合：在当前事务自己的同步列表中查找，第一次登记时注册同步。
     *
     * 不用线程绑定的资源：`REQUIRES_NEW` 挂起外层事务时只挂起同步列表，资源仍然绑定，
     * 内层登记的失效会混进外层、随外层的结束才执行。
     */
    private fun pending(): Pending {
        TransactionSynchronizationManager.getSynchronizations()
            .firstOrNull { it is Synchronization && it.owner === this }
            ?.let { return (it as Synchronization).pending }
        val synchronization = Synchronization(this, Pending())
        TransactionSynchronizationManager.registerSynchronization(synchronization)
        return synchronization.pending
    }

    /**
     * 按缓存分组的待失效 key（保持登记顺序并去重）。只在事务所在的线程上使用。
     */
    private class Pending {
        private val keysByCache = LinkedHashMap<Cache<Any, *>, LinkedHashSet<Any>>()

        /**
         * 已经执行过：之后（其它同步的 `afterCommit` 中）登记的 key 不会再有回调执行，立即失效。
         */
        private var flushed = false

        @Suppress("UNCHECKED_CAST")
        fun add(cache: Cache<*, *>, keys: Collection<Any>) {
            cache as Cache<Any, *>
            if (flushed) {
                cache.invalidateAll(keys)
                return
            }
            keysByCache.getOrPut(cache) { LinkedHashSet() }.addAll(keys)
        }

        /**
         * 逐个缓存失效，只执行一次。一个缓存失败不影响其余缓存：全部执行之后抛出第一个异常，其余作为它的 suppressed。
         */
        @Suppress("TooGenericExceptionCaught")
        fun flush() {
            if (flushed) {
                return
            }
            flushed = true
            var failure: Exception? = null
            for ((cache, keys) in keysByCache) {
                try {
                    cache.invalidateAll(keys)
                } catch (e: Exception) {
                    val first = failure
                    if (first == null) failure = e else first.addSuppressed(e)
                }
            }
            failure?.let { throw it }
        }
    }

    private class Synchronization(val owner: TransactionalInvalidator, val pending: Pending) : TransactionSynchronization {
        /**
         * 提交之后失效，异常抛给调用方。
         */
        override fun afterCommit() {
            pending.flush()
        }

        /**
         * 兜底，已经执行过时什么也不做：回滚或提交结果未知（事务内的读穿透可能把未提交的数据写入了 L1），
         * 在其它同步的 `afterCommit` 中才注册（收不到 `afterCommit`），排在前面的 `afterCommit` 抛出。
         */
        override fun afterCompletion(status: Int) {
            pending.flush()
        }
    }
}
