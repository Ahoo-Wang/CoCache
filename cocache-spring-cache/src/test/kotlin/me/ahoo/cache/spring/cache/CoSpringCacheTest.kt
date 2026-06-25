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

package me.ahoo.cache.spring.cache

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.CacheFactory
import me.ahoo.cache.ComputedTtlAt
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.join.JoinValue
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.join.DefaultJoinValue
import me.ahoo.cache.join.JoinKeyExtractorFactory
import me.ahoo.cache.join.SimpleJoinCache
import me.ahoo.cache.join.proxy.DefaultJoinCacheProxyFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import org.springframework.cache.Cache as SpringCache

class CoSpringCacheTest {
    @Suppress("UNCHECKED_CAST")
    private val coSpringCache = CoSpringCache("test", MapClientSideCache<Any?>() as Cache<Any, Any?>)

    @Test
    fun getName() {
        coSpringCache.cacheName.assert().isEqualTo("test")
    }

    @Test
    fun getNativeCache() {
        coSpringCache.nativeCache.assert().isEqualTo(coSpringCache.delegate)
    }

    @Test
    fun get() {
        coSpringCache.get("test").assert().isNull()
    }

    @Test
    fun testGet() {
        coSpringCache.get("test", String::class.java).assert().isNull()
    }

    @Test
    fun testGet1() {
        coSpringCache.get("test", {
            "test"
        }).assert().isEqualTo("test")
    }

    @Test
    fun put() {
        coSpringCache.put("putTest", "test")
        coSpringCache.get("putTest")!!.get().assert().isEqualTo("test")
    }

    @Test
    fun getWhenMissingGuardReturnsMiss() {
        coSpringCache.delegate.setCache("missing", DefaultCacheValue.missingGuard())

        coSpringCache.get("missing").assert().isNull()
    }

    @Test
    fun getWhenExpiredEvictsDelegate() {
        val expiredCache = RawSpringCache(DefaultCacheValue("expired", ComputedTtlAt.at(-5)))
        val expiredSpringCache = CoSpringCache("expired", expiredCache)

        expiredSpringCache.get("expired").assert().isNull()
        expiredCache.evicted.assert().isTrue()
    }

    @Test
    fun getWithMatchingTypeReturnsValue() {
        coSpringCache.put("typedString", "value")

        coSpringCache.get("typedString", String::class.java).assert().isEqualTo("value")
    }

    @Test
    fun getWithNullTypeReturnsValue() {
        coSpringCache.put("typedAny", "value")

        coSpringCache.get<Any>("typedAny", null).assert().isEqualTo("value")
    }

    @Test
    fun getWithLoaderKeepsCachedNull() {
        var loadCount = 0
        coSpringCache.put("cachedNull", null)

        val actual = coSpringCache.get("cachedNull") {
            loadCount++
            "loaded"
        }

        actual.assert().isNull()
        loadCount.assert().isZero()
    }

    @Test
    fun getWithTypeMismatchThrowsIllegalStateException() {
        coSpringCache.put("typed", "value")

        assertThrows<IllegalStateException> {
            val actual: Int? = coSpringCache.get("typed", Int::class.javaObjectType)
            actual.assert().isNull()
        }
    }

    @Test
    fun getWithLoaderWrapsException() {
        assertThrows<SpringCache.ValueRetrievalException> {
            coSpringCache.get("loaderException") {
                throw IllegalStateException("boom")
            }
        }
    }

    @Test
    fun evict() {
        coSpringCache.put("evictTest", "test")
        coSpringCache.evict("evictTest")
        coSpringCache.get("evictTest").assert().isNull()
    }

    @Test
    fun clear() {
        coSpringCache.put("clearTest", "test")
        coSpringCache.clear()
        coSpringCache.get("clearTest").assert().isNull()
    }

    @Test
    fun clearCoherentCache() {
        val coherentCache = mockk<CoherentCache<Any, Any?>> {
            every {
                clientSideCache
            } returns MapClientSideCache()
        }
        val coSpringCache = CoSpringCache("test", coherentCache)
        coSpringCache.clear()
    }

    @Test
    fun clearPlainDelegateCompletes() {
        CoSpringCache("plain", RawSpringCache()).clear()
    }

    @Test
    fun clearJoinCacheIsNotSilentNoOp() {
        // A JoinCache proxy implements neither ClientSideCache nor CoherentCache,
        // so the old clear() silently dropped the call. Spring's Cache.clear()
        // contract requires the mapping to be removed. Wrap a SimpleJoinCache and
        // assert clear() actually evicts the entries it holds.
        val firstCache = MapClientSideCache<String>()
        val joinCache = MapClientSideCache<String>()
        val joinDelegate = SimpleJoinCache<String, String, String, String>(
            firstCache,
            joinCache,
        ) { firstValue -> firstValue }
        val joinValue: JoinValue<String, String, String> = DefaultJoinValue("first", "first", "second")
        joinDelegate.setCache("k", DefaultCacheValue(joinValue, Long.MAX_VALUE))
        joinDelegate.getCache("k").assert().isNotNull // entry present

        @Suppress("UNCHECKED_CAST")
        val coSpringCache = CoSpringCache("join", joinDelegate as Cache<Any, Any?>)
        coSpringCache.clear()

        // BUG: clear() was a silent no-op for the JoinCache delegate.
        joinDelegate.getCache("k").assert().isNull()
    }

    @Test
    fun clearJoinCacheProxyIsNotSilentNoOp() {
        // Production join caches are JDK proxies from DefaultJoinCacheProxyFactory,
        // whose interface list is [proxyInterface, JoinCache, JoinCacheMetadataCapable]
        // — NOT CacheDelegated and NOT SimpleJoinCache. The fix above only handled a
        // bare SimpleJoinCache; the proxied path must also clear its local tiers.
        val firstCache = MapClientSideCache<String>()
        val joinCache = MapClientSideCache<String>()
        val cacheFactory = mockk<CacheFactory> {
            every { getCache<Cache<String, String>>("First") } returns firstCache
            every { getCache<Cache<String, String>>("Join") } returns joinCache
        }
        val metadata = me.ahoo.cache.annotation.joinCacheMetadata<TestJoinCache>()
        val joinKeyExtractorFactory = mockk<JoinKeyExtractorFactory> {
            every { create<String, String>(metadata) } returns me.ahoo.cache.api.join.JoinKeyExtractor { it }
        }
        val proxy = DefaultJoinCacheProxyFactory(cacheFactory, joinKeyExtractorFactory)
            .create<TestJoinCache>(metadata)

        val joinValue: JoinValue<String, String, String> = DefaultJoinValue("first", "first", "second")
        proxy.setCache("k", DefaultCacheValue(joinValue, Long.MAX_VALUE))
        firstCache.size.assert().isEqualTo(1L) // first tier populated
        joinCache.size.assert().isEqualTo(1L) // join tier populated

        @Suppress("UNCHECKED_CAST")
        val coSpringCache = CoSpringCache("join", proxy as Cache<Any, Any?>)
        coSpringCache.clear()

        // BUG: the proxied join cache's local tiers were not cleared.
        firstCache.size.assert().isEqualTo(0L)
        joinCache.size.assert().isEqualTo(0L)
    }

    @Test
    fun retrieve() {
        coSpringCache.put("retrieveTest", "test")
        coSpringCache.retrieve("retrieveTest")!!.get().assert().isEqualTo("test")
    }

    @Test
    fun retrieveWhenMissingReturnsNull() {
        coSpringCache.retrieve("retrieveMissing").assert().isNull()
    }

    @Test
    fun testRetrieve() {
        coSpringCache.put("testRetrieve", "test")
        coSpringCache.retrieve("testRetrieveNotFound", {
            CompletableFuture.completedFuture("test")
        }).get().assert().isEqualTo("test")
    }

    @Test
    fun retrieveWithLoaderKeepsCachedValue() {
        var loadCount = 0
        coSpringCache.put("asyncCached", "cached")

        val actual = coSpringCache.retrieve("asyncCached") {
            loadCount++
            CompletableFuture.completedFuture("loaded")
        }

        actual.get().assert().isEqualTo("cached")
        loadCount.assert().isZero()
    }

    @Test
    fun retrieveWithLoaderKeepsCachedNull() {
        var loadCount = 0
        coSpringCache.put("asyncCachedNull", null)

        val actual = coSpringCache.retrieve("asyncCachedNull") {
            loadCount++
            CompletableFuture.completedFuture("loaded")
        }

        actual.get().assert().isNull()
        loadCount.assert().isZero()
    }

    @Test
    fun getCacheName() {
        coSpringCache.cacheName.assert().isEqualTo("test")
    }

    @Test
    fun getDelegate() {
        coSpringCache.delegate.assert().isNotNull
    }
}

private class RawSpringCache(
    private var cacheValue: CacheValue<Any?>? = null
) : Cache<Any, Any?> {
    var evicted: Boolean = false
        private set

    override fun getCache(key: Any): CacheValue<Any?>? {
        return cacheValue
    }

    override fun get(key: Any): Any? {
        return cacheValue?.takeUnless {
            it.isMissingGuard || it.isExpired
        }?.value
    }

    override fun getTtlAt(key: Any): Long? {
        return cacheValue?.takeUnless {
            it.isMissingGuard
        }?.ttlAt
    }

    override fun set(key: Any, ttlAt: Long, value: Any?) {
        cacheValue = DefaultCacheValue(value, ttlAt)
    }

    override fun set(key: Any, value: Any?) {
        cacheValue = DefaultCacheValue.forever(value)
    }

    override fun setCache(key: Any, value: CacheValue<Any?>) {
        cacheValue = value
    }

    override fun evict(key: Any) {
        evicted = true
        cacheValue = null
    }
}

@me.ahoo.cache.api.annotation.JoinCacheable(firstCacheName = "First", joinCacheName = "Join")
interface TestJoinCache : me.ahoo.cache.api.join.JoinCache<String, String, String, String>
