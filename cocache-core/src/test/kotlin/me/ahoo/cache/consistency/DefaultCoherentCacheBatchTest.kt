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

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.converter.KeyConverter
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.filter.KeyFilter
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [DefaultCoherentCache.getCaches] 的批量路径：一次批量回源、重复 key、key 映射冲突、过期条目与统计。
 */
internal class DefaultCoherentCacheBatchTest {

    private class BatchSource(private val values: Map<String, String>) : CacheSource<String, String> {
        val batchCalls = AtomicInteger()
        val loadedKeys = mutableListOf<String>()

        override fun loadCacheValue(key: String): CacheValue<String>? = error("batch reads must use loadCacheValues")

        override fun loadCacheValues(keys: Collection<String>): Map<String, CacheValue<String>> {
            batchCalls.incrementAndGet()
            loadedKeys.addAll(keys)
            return keys.mapNotNull { key -> values[key]?.let { key to CacheValue.forever(it) } }.toMap()
        }
    }

    private fun createCache(
        cacheSource: CacheSource<String, String> = CacheSource.noOp(),
        distributedCache: DistributedCache<String> = InMemoryDistributedCache(),
        keyConverter: KeyConverter<String> = ToStringKeyConverter(""),
        keyFilter: KeyFilter = KeyFilter.NO_OP,
        recordClientSideHits: Boolean = false
    ): CoherentCache<String, String> {
        return DefaultCoherentCacheFactory(NoOpCacheEvictedEventBus, recordClientSideHits).create(
            CoherentCacheConfiguration(
                cacheName = "DefaultCoherentCacheBatchTest",
                clientId = "client",
                keyConverter = keyConverter,
                clientSideCache = MapClientSideCache(),
                distributedCache = distributedCache,
                cacheSource = cacheSource,
                keyFilter = keyFilter
            )
        )
    }

    @Test
    fun loadsMissingKeysInOneBatch() {
        val source = BatchSource(mapOf("a" to "A", "b" to "B"))
        val cache = createCache(source)

        cache.getAll(listOf("a", "b", "c", "a")).assert().isEqualTo(mapOf("a" to "A", "b" to "B"))

        source.batchCalls.get().assert().isOne()
        source.loadedKeys.assert().containsExactly("a", "b", "c")
        cache.stats.loadCount.assert().isOne()
        cache.stats.missCount.assert().isEqualTo(3)
        requireNotNull(cache.configuration.distributedCache.getCache("c")).isMissing.assert().isTrue()

        cache.getAll(listOf("a", "b")).assert().isEqualTo(mapOf("a" to "A", "b" to "B"))
        source.batchCalls.get().assert().isOne()
    }

    @Test
    fun emptyKeysSkipAllLevels() {
        val source = BatchSource(emptyMap())
        createCache(source).getCaches(emptyList()).assert().isEmpty()
        source.batchCalls.get().assert().isZero()
    }

    @Test
    fun keysMappedToSameCacheKeyShareOneLoad() {
        val source = BatchSource(mapOf("a" to "A"))
        val lowerCase = KeyConverter<String> { it.lowercase() }
        val cache = createCache(source, keyConverter = lowerCase)

        cache.getAll(listOf("a", "A")).assert().isEqualTo(mapOf("a" to "A", "A" to "A"))

        source.loadedKeys.assert().containsExactly("a")
    }

    @Test
    fun filteredKeysReturnMissingWithoutLoading() {
        val source = BatchSource(mapOf("a" to "A"))
        val cache = createCache(source, keyFilter = { it == "blocked" })

        val actual = cache.getCaches(listOf("a", "blocked"))

        requireNotNull(actual["blocked"]).isMissing.assert().isTrue()
        source.loadedKeys.assert().containsExactly("a")
        cache.stats.filteredCount.assert().isOne()
    }

    @Test
    fun expiredClientSideAndDistributedEntriesReload() {
        val distributedCache = object : DistributedCache<String> {
            override fun getCache(key: String): CacheValue<String>? = CacheValue.of("old", TtlAt.currentTime() - 1)
            override fun setCache(key: String, value: CacheValue<String>) = Unit
            override fun evict(key: String) = Unit
        }
        val source = BatchSource(mapOf("a" to "A"))
        val cache = createCache(source, distributedCache = distributedCache)
        cache.configuration.clientSideCache.setCache("a", CacheValue.of("old", TtlAt.currentTime() - 1))

        cache.getAll(listOf("a")).assert().isEqualTo(mapOf("a" to "A"))

        source.batchCalls.get().assert().isOne()
    }

    @Test
    fun expiredSourceValueIsReturnedButNotWritten() {
        val expired = CacheValue.of("A", TtlAt.currentTime() - 1)
        val cache = createCache(
            object : CacheSource<String, String> {
                override fun loadCacheValue(key: String): CacheValue<String>? = expired
            }
        )

        cache.getCaches(listOf("a")).assert().isEqualTo(mapOf("a" to expired))

        cache.configuration.distributedCache.getCache("a").assert().isNull()
    }

    @Test
    fun distributedHitsFillClientSideAndCount() {
        val distributedCache = InMemoryDistributedCache<String>()
        distributedCache.setCache("a", CacheValue.forever("A"))
        val cache = createCache(distributedCache = distributedCache, recordClientSideHits = true)

        cache.getAll(listOf("a")).assert().isEqualTo(mapOf("a" to "A"))
        cache.getAll(listOf("a")).assert().isEqualTo(mapOf("a" to "A"))

        cache.stats.distributedHitCount.assert().isOne()
        cache.stats.clientSideHitCount.assert().isOne()
        cache.stats.loadCount.assert().isZero()
    }

    @Test
    fun sourceFailureIsCountedAndRethrown() {
        val cache = createCache(
            object : CacheSource<String, String> {
                override fun loadCacheValue(key: String): CacheValue<String>? = null
                override fun loadCacheValues(keys: Collection<String>): Map<String, CacheValue<String>> =
                    error("source down")
            }
        )

        runCatching { cache.getAll(listOf("a")) }.exceptionOrNull()?.message.assert().isEqualTo("source down")

        cache.stats.loadFailureCount.assert().isOne()
    }
}
