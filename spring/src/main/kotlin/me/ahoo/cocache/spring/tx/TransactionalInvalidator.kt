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

/**
 * 事务感知的失效（RFC 0005 §4.9）：事务内调用时登记到事务结束之后执行，没有事务时立即执行。
 *
 * 失效必须在数据源提交之后发生。若在提交之前失效，并发的回源可能读到提交前的数据并写回，
 * 这本身就是一个失效被吞没的漏洞（G2）。
 *
 * 登记按事务划分，不按实例：同一事务内经任何 [TransactionalInvalidator] 实例、`CoCacheSpringCache.evictIfPresent`
 * 与 [TransactionAwareSpringCache] 登记的失效进入同一份登记（`TransactionalOperations`），规则如下：
 * - 提交之后在 `afterCommit` 中失效。同一事务内对同一缓存的多次失效合并为一次 [Cache.invalidateAll]（去重，保持登记顺序）。
 *   登记之前、经 [TransactionAwareSpringCache] 推迟的同一 key 的 `put` 被取代，提交之后不再写入。
 * - 回滚或提交结果未知时同样失效（在 `afterCompletion` 中）：同步 API 的 loader 在调用方线程上参与事务，
 *   事务内的读穿透可能已经把未提交的数据写入 L1。多余的失效无害。
 * - 嵌套的新事务（`REQUIRES_NEW`）在各自结束之后失效。
 * - 回滚到 savepoint（`NESTED`）时立即失效到目前为止登记的全部 key，登记保留，事务结束时照常再失效一次：
 *   Spring 此时不调用 `afterCompletion`，外层之后提交时报告 `STATUS_COMMITTED`，嵌套段内写入 L1 的未提交数据
 *   否则在外层结束之前对全集群（包括外层事务自己）可见。这里的失效不抛出（失败只记录日志），否则 Spring 会跳过数据库的回滚。
 * - 迟到的登记不丢失。在其它同步的 `afterCommit` 中登记时：本事务的失效已经执行过，立即失效；
 *   本事务第一次登记（Spring 不再对此时注册的同步调用 `afterCommit`），在 `afterCompletion` 中失效。
 *   排在前面的同步的 `afterCommit` 抛出时同样在 `afterCompletion` 中失效。
 *   `afterCompletion` 中（例如 `@TransactionalEventListener(AFTER_COMMIT)`）同步已经清除，立即失效。
 * - 本事务正在执行提交操作时（如 `CacheErrorHandler` 补偿一次失败，包括同步已经清除的 `afterCompletion` 中）立即失效，
 *   并取代同一事务更早登记、尚未执行的同 key 推迟写入。
 * - 一个缓存失败不影响其余缓存：全部执行之后抛出第一个异常，其余作为它的 suppressed。
 * - 失效默认不会抛出（失败进入重试队列，见 `InvalidateFailureMode.RETRY`；外部来源的 `Cache` 同样如此：删除派生副本总是安全的）。
 *   配置为 `THROW` 时失效失败会抛出：`afterCommit` 中按上一条聚合，
 *   在提交之后抛给调用方，此时数据源已经提交；`afterCompletion` 中的异常（回滚、兜底）由 Spring 记录日志，不抛给调用方。
 *   [TransactionAwareSpringCache] 登记的失效不在此列：它们的失败交给 `CacheErrorHandler`（S18）。
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
        val operations = TransactionalOperations.active()
        if (operations == null) {
            cache.invalidateAll(keys)
            return
        }
        operations.invalidate(cache, keys, onFailure = null)
    }

    /**
     * 事务内登记 [key]，与 [invalidate] 一样在事务结束之后失效；没有事务（也没有正在执行提交操作的登记）时什么也不做。
     * 供已经立即失效过的调用方在事务结束之后再失效一次（[me.ahoo.cocache.spring.cache.CoCacheSpringCache.evictIfPresent]）。
     * 失败交给 [onFailure]；为 `null` 时与 [invalidate] 一样聚合抛出。
     */
    internal fun <K : Any> invalidateAfterTransaction(
        cache: Cache<K, *>,
        key: K,
        onFailure: InvalidationFailureHandler? = null,
    ) {
        TransactionalOperations.active()?.invalidate(cache, listOf(key), onFailure)
    }
}
