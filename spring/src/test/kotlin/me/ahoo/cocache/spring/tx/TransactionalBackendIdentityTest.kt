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

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.cache.interceptor.SimpleCacheErrorHandler
import org.springframework.transaction.support.TransactionTemplate

/**
 * 同名 keyspace 在两个独立后端中是两份数据，事务登记不能把它们合并（#977）；同一个后端上两个运行时中的同一 keyspace 仍是一份。
 */
class TransactionalBackendIdentityTest {
    @Test fun rollbackMustInvalidateFillsInBothIndependentBackends() = rollback(false, false)

    @Test fun rollbackWithDifferentNamespacesWorks() = rollback(false, true)

    @Test fun rollbackOfTwoViewsOfOneBackendStillWorks() = rollback(true, false)

    @Test fun evictionInAnotherBackendMustNotSuppressACommittedPut() = commit(false, false)

    @Test fun committedPutWithDifferentNamespacesWorks() = commit(false, true)

    @Test fun evictionOfTheSameSharedDataStillSuppressesTheEarlierPut() = commit(true, false)

    private fun rollback(sharedBackend: Boolean, distinctNames: Boolean) {
        val transactions = InMemoryTransactionManager()
        val tx = TransactionTemplate(transactions)
        tx.executeWithoutResult { transactions.write("row", "committed") }
        runtimes(sharedBackend, observed = true) { left, right ->
            fun cache(runtime: CacheRuntime, name: String) = runtime.create(
                cacheSpec<String, String>(name) {
                    codec = StringCodec
                    loader = CacheLoader { transactions.read("row") }
                }
            )
            val first = cache(left, "orders")
            val second = cache(right, if (distinctNames) "other-orders" else "orders")
            tx.executeWithoutResult { status ->
                transactions.write("row", "rolled-back")
                first["k"].assert().isEqualTo("rolled-back")
                second["k"].assert().isEqualTo("rolled-back")
                status.setRollbackOnly()
            }
            // 数据源已回滚，两个独立缓存都不应留下那次填充。
            transactions.read("row").assert().isEqualTo("committed")
            first["k"].assert().isEqualTo("committed")
            val secondValue = second["k"]
            secondValue.assert().withFailMessage {
                "Second backend value=$secondValue after source rollback; expected committed."
            }.isEqualTo("committed")
        }
    }

    private fun commit(sharedBackend: Boolean, distinctNames: Boolean) {
        val tx = TransactionTemplate(InMemoryTransactionManager())
        runtimes(sharedBackend, observed = false) { left, right ->
            fun cache(runtime: CacheRuntime, name: String) = runtime.create(
                cacheSpec<String, String>(name) {
                    codec = StringCodec
                    externallySourced()
                }
            )
            val first = cache(left, "orders")
            val rightName = if (distinctNames) "other-orders" else "orders"
            val second = cache(right, rightName)
            first.put("k", "old")
            if (!sharedBackend) second.put("k", "other")
            val leftManager = TransactionAwareSpringCacheManager(
                CoCacheSpringCacheManager(left)
            ) { SimpleCacheErrorHandler() }
            val rightManager = TransactionAwareSpringCacheManager(
                CoCacheSpringCacheManager(right)
            ) { SimpleCacheErrorHandler() }
            val leftCache = requireNotNull(leftManager.getCache("orders"))
            val rightCache = requireNotNull(rightManager.getCache(rightName))
            tx.executeWithoutResult {
                leftCache.put("k", "new")
                rightCache.evict("k")
            }
            second["k"].assert().isNull()
            val expected = if (sharedBackend && !distinctNames) null else "new"
            val firstValue = first["k"]
            firstValue.assert().withFailMessage {
                "First backend value=$firstValue, expected=$expected; evicting another backend suppressed its deferred put."
            }.isEqualTo(expected)
        }
    }

    private fun runtimes(shared: Boolean, observed: Boolean, block: (CacheRuntime, CacheRuntime) -> Unit) {
        InMemoryBackend().use { firstBackend ->
            InMemoryBackend().use { secondBackend ->
                fun create(backend: InMemoryBackend) = CacheRuntime.builder().backend(backend).apply {
                    if (observed) fillObserver(TransactionalFillObserver())
                }.build()
                create(firstBackend).use { first ->
                    create(if (shared) firstBackend else secondBackend).use { second -> block(first, second) }
                }
            }
        }
    }
}
