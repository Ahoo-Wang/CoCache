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

package me.ahoo.cache.consistency

import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.SetElementsCache
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.distributed.SetElementsStore
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 元素增删与在途加载交错：写回/撤销不得吞没增删（每个用例复现一个修复前会丢失元素的交错）。
 */
internal class DefaultCoherentCacheSetElementsTest {
    private val key = "key"

    /**
     * L1：在条件写回前后、L1 读取后执行钩子（同步调用，由钩子内的增删制造交错）。
     */
    private class HookedStore(
        val delegate: InMemoryDistributedCache<Set<String>> = InMemoryDistributedCache()
    ) : DistributedCache<Set<String>> by delegate, SetElementsStore<Any?> by delegate {
        @Volatile
        var beforeFill: () -> Unit = {}

        @Volatile
        var afterFill: () -> Unit = {}

        @Volatile
        var afterGet: () -> Unit = {}

        override fun getCache(key: String): CacheValue<Set<String>>? {
            return delegate.getCache(key).also { afterGet().also { afterGet = {} } }
        }

        override fun fillCache(key: String, value: CacheValue<Set<Any?>>): Boolean {
            beforeFill().also { beforeFill = {} }
            return delegate.fillCache(key, value).also { afterFill().also { afterFill = {} } }
        }

        override fun close() = Unit
    }

    private val store = HookedStore()
    private val clientSideCache = MapClientSideCache<Set<String>>()
    private val cache = DefaultCoherentCache(
        CoherentCacheConfiguration<String, Set<String>>(
            cacheName = "DefaultCoherentCacheSetElementsTest",
            clientId = "test",
            keyConverter = ToStringKeyConverter<String>(""),
            distributedCache = store,
            clientSideCache = clientSideCache,
            ttlPolicy = TtlPolicy(ttl = TtlAt.FOREVER),
        ),
        NoOpCacheEvictedEventBus
    )

    @Suppress("UNCHECKED_CAST")
    private val elements = cache as SetElementsCache<String, String>

    @Test
    fun addBeforeNegativeWriteBackIsNotOverwritten() {
        // 回源（无数据源 → 负缓存）通过写前校验后、写入 L1 前，另一写者加入元素
        store.beforeFill = { elements.addElements(key, listOf("a")) }
        cache.getCache(key)
        store.delegate.getCache(key)?.value.assert().isEqualTo(setOf("a"))
        clientSideCache.getCache(key).assert().isNull()
        cache[key].assert().isEqualTo(setOf("a"))
    }

    @Test
    fun addAfterNegativeWriteBackIsNotUndone() {
        // 负缓存已写入 L1、写后复核前加入元素：复核失败的撤销不得删除新集合
        store.afterFill = { elements.addElements(key, listOf("a")) }
        cache.getCache(key)
        store.delegate.getCache(key)?.value.assert().isEqualTo(setOf("a"))
        clientSideCache.getCache(key).assert().isNull()
        cache[key].assert().isEqualTo(setOf("a"))
    }

    @Test
    fun staleL1ReadIsNotFilledIntoClientSide() {
        elements.addElements(key, listOf("a"))
        // L1 读到 {a} 之后、填充 L2 之前加入 b
        store.afterGet = { elements.addElements(key, listOf("b")) }
        cache.getCache(key)
        clientSideCache.getCache(key).assert().isNull()
        cache[key].assert().isEqualTo(setOf("a", "b"))
    }

    @Test
    fun negativeWriteBackOverwritesNothingButUndoesItself() {
        // 回源写入负缓存后发生淘汰以外的失效（如远端事件）：撤销仍删除本次写入的负缓存
        store.afterFill = {
            cache.onEvicted(CacheEvictedEvent(cache.cacheName, key, "other"))
        }
        cache.getCache(key)!!.isMissing.assert().isTrue()
        store.delegate.getCache(key).assert().isNull()
        clientSideCache.getCache(key).assert().isNull()
    }

    @Test
    fun unsupportedDistributedCache() {
        val backing = ConcurrentHashMap<String, CacheValue<Set<String>>>()
        val plain = object : DistributedCache<Set<String>> {
            override fun getCache(key: String): CacheValue<Set<String>>? = backing[key]
            override fun setCache(key: String, value: CacheValue<Set<String>>) {
                backing[key] = value
            }

            override fun evict(key: String) {
                backing.remove(key)
            }
        }
        val plainCache = DefaultCoherentCache(
            CoherentCacheConfiguration<String, Set<String>>(
                cacheName = "plain",
                clientId = "test",
                keyConverter = ToStringKeyConverter<String>(""),
                distributedCache = plain,
            ),
            NoOpCacheEvictedEventBus
        )
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            plainCache.addElements(key, listOf("a"))
        }
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            plainCache.removeElements(key, emptyList())
        }
        // 写回路径不受影响：仍为无条件写入
        plainCache.getCache(key)!!.isMissing.assert().isTrue()
        backing[key]!!.isMissing.assert().isTrue()
    }
}
