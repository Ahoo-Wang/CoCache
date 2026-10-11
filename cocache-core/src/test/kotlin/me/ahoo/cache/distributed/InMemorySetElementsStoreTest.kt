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

package me.ahoo.cache.distributed

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 过期条目的分支：[InMemoryDistributedCache] 不接受写入已过期条目，这里直接放入底层 Map。
 */
internal class InMemorySetElementsStoreTest {
    private val key = "key"
    private val expiredAt = TtlAt.currentTime() - 10

    @Suppress("UNCHECKED_CAST")
    private fun storeWith(
        current: CacheValue<Set<String>>
    ): Pair<InMemoryDistributedCache<Set<String>>, MutableMap<String, CacheValue<Set<String>>>> {
        val store = InMemoryDistributedCache<Set<String>>()
        val field = InMemoryDistributedCache::class.java.getDeclaredField("cacheMap").apply { isAccessible = true }
        val map = field.get(store) as ConcurrentHashMap<String, CacheValue<Set<String>>>
        map[key] = current
        return store to map
    }

    @Test
    fun addOntoExpiredValueStartsFresh() {
        val (store, _) = storeWith(CacheValue.of(setOf("old"), expiredAt))
        store.addElements(key, listOf("a"), TtlAt.FOREVER)
        store.getCache(key).assert().isEqualTo(CacheValue.forever(setOf("a")))
    }

    @Test
    fun removeFromExpiredValueDropsKey() {
        val (store, map) = storeWith(CacheValue.of(setOf("a", "b"), expiredAt))
        store.removeElements(key, listOf("a"), TtlAt.FOREVER)
        map.containsKey(key).assert().isFalse()
    }

    @Test
    fun fillSkipsExpiredValue() {
        val store = InMemoryDistributedCache<Set<String>>()
        @Suppress("UNCHECKED_CAST")
        store.fillCache(key, CacheValue.missing<Set<Any?>>(expiredAt) as CacheValue<Set<Any?>>).assert().isFalse()
        store.getCache(key).assert().isNull()
    }

    @Test
    fun fillReplacesExpiredOrMissingButNotPresent() {
        val filled = CacheValue.forever<Set<Any?>>(setOf("a"))

        val (expired, _) = storeWith(CacheValue.of(setOf("old"), expiredAt))
        expired.fillCache(key, filled).assert().isTrue()
        expired.getCache(key)?.value.assert().isEqualTo(setOf("a"))

        val (missing, _) = storeWith(CacheValue.missing(TtlAt.at(60)))
        missing.fillCache(key, filled).assert().isTrue()

        val (present, _) = storeWith(CacheValue.forever(setOf("b")))
        present.fillCache(key, filled).assert().isFalse()
        present.getCache(key)?.value.assert().isEqualTo(setOf("b"))
    }

    @Test
    fun evictIfUnchanged() {
        val (store, map) = storeWith(CacheValue.forever(setOf("a")))
        store.evictIfUnchanged(key, CacheValue.forever(setOf("b")))
        map.containsKey(key).assert().isTrue()
        store.evictIfUnchanged(key, CacheValue.missing(TtlAt.FOREVER))
        map.containsKey(key).assert().isTrue()
        store.evictIfUnchanged(key, CacheValue.forever(setOf("a")))
        map.containsKey(key).assert().isFalse()

        map[key] = CacheValue.missing(TtlAt.at(60))
        store.evictIfUnchanged(key, CacheValue.missing(TtlAt.FOREVER))
        map.containsKey(key).assert().isFalse()
    }
}
