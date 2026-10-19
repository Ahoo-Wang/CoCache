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
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 事务感知的失效（RFC 0003 §4.12）：事务内调用时登记到提交之后执行，没有事务时立即执行。
 *
 * 失效必须在数据源提交之后发生。若在提交之前失效，并发的回源可能读到提交前的数据并写回，
 * 这本身就是一个失效被吞没的漏洞（G2）。
 *
 * - 同一事务内对同一缓存的多次失效合并为一次 [CoCache.invalidateAll]（去重，保持登记顺序）。
 * - 事务回滚时不失效：数据源没有变化。
 * - 嵌套的新事务（`REQUIRES_NEW`）在各自提交之后失效。
 * - 提交后的失效默认不会抛出（失败进入重试队列，见 `InvalidateFailureMode.RETRY`）；
 *   配置为 `THROW` 时，异常在提交之后抛给调用方，此时数据源已经提交。
 */
class TransactionalInvalidator {

    fun <K : Any> invalidate(cache: CoCache<K, *>, key: K) {
        invalidateAll(cache, listOf(key))
    }

    fun <K : Any> invalidateAll(cache: CoCache<K, *>, keys: Collection<K>) {
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
     * 内层登记的失效会混进外层、外层回滚时随之丢失。
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
     * 按缓存分组的待失效 key（保持登记顺序并去重）。
     */
    private class Pending {
        private val keysByCache = LinkedHashMap<CoCache<Any, *>, LinkedHashSet<Any>>()

        @Suppress("UNCHECKED_CAST")
        fun add(cache: CoCache<*, *>, keys: Collection<Any>) {
            keysByCache.getOrPut(cache as CoCache<Any, *>) { LinkedHashSet() }.addAll(keys)
        }

        fun flush() {
            keysByCache.forEach { (cache, keys) -> cache.invalidateAll(keys) }
        }
    }

    private class Synchronization(val owner: TransactionalInvalidator, val pending: Pending) : TransactionSynchronization {
        override fun afterCommit() {
            pending.flush()
        }
    }
}
