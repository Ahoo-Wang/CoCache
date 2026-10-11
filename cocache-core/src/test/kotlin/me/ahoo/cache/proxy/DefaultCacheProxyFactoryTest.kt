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

package me.ahoo.cache.proxy

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.DefaultClientSideCacheFactory
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.CoherentCacheFactory
import me.ahoo.cache.consistency.DefaultCoherentCache
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.converter.DefaultKeyConverterFactory
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.cache.source.CacheSourceFactory
import me.ahoo.cache.util.ClientIdGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class DefaultCacheProxyFactoryTest {

    companion object {
        internal fun <CACHE> createProxyCache(
            metadata: CoCacheMetadata = coCacheMetadata<MockCache>(),
            cacheSource: CacheSource<Any, Any> = CacheSource.noOp(),
            distributedCache: () -> DistributedCache<*> = { InMemoryDistributedCache<Any>() },
            coherentCacheFactory: CoherentCacheFactory = DefaultCoherentCacheFactory(NoOpCacheEvictedEventBus)
        ): CACHE {
            val distributedCacheFactory = object : DistributedCacheFactory {
                @Suppress("UNCHECKED_CAST")
                override fun <V> create(cacheMetadata: CoCacheMetadata): DistributedCache<V> {
                    return distributedCache() as DistributedCache<V>
                }
            }
            val cacheSourceFactory = object : CacheSourceFactory {
                @Suppress("UNCHECKED_CAST")
                override fun <K, V> create(cacheMetadata: CoCacheMetadata): CacheSource<K, V> {
                    return cacheSource as CacheSource<K, V>
                }
            }
            val cacheProxyFactory = DefaultCacheProxyFactory(
                coherentCacheFactory = coherentCacheFactory,
                clientIdGenerator = ClientIdGenerator.UUID,
                clientSideCacheFactory = DefaultClientSideCacheFactory,
                distributedCacheFactory = distributedCacheFactory,
                cacheSourceFactory = cacheSourceFactory,
                keyConverterFactory = DefaultKeyConverterFactory.INSTANCE
            )
            return cacheProxyFactory.create(metadata)
        }
    }

    @Test
    fun create() {
        val cache = createProxyCache<MockCache>()
        cache["key"].assert().isNull()
        cache.toString().assert().startsWith(MockCache::class.java.simpleName)
        cache.delegate.cacheName.assert().isEqualTo(MockCache::class.java.simpleName)
        (cache as CoherentCache<*, *>).clientId.assert().isEqualTo(cache.delegate.clientId)
        cache.cacheMetadata.assert().isEqualTo(coCacheMetadata<MockCache>())
    }

    @Test
    fun proxyUsesIdentityEquality() {
        val cache = createProxyCache<MockCache>()
        val other = createProxyCache<MockCache>()
        (cache == cache).assert().isTrue()
        (cache == other).assert().isFalse()
        cache.hashCode().assert().isEqualTo(System.identityHashCode(cache))
    }

    @Test
    fun createWithKeyExpression() {
        val cache = createProxyCache<MockCacheWithKeyExpression>(coCacheMetadata<MockCacheWithKeyExpression>())
        cache["key"] = "value"
        cache["key"].assert().isEqualTo("value")
    }

    @Test
    fun defaultMethod() {
        val cache = createProxyCache<MockCacheWithKeyExpression>(coCacheMetadata<MockCacheWithKeyExpression>())
        cache.defaultMethod().assert().isEqualTo("defaultMethod")
    }

    @Test
    fun exceptionFromSourceIsNotWrapped() {
        val failure = IllegalStateException("source down")
        val cache = createProxyCache<MockCache>(cacheSource = CacheSource { throw failure })
        runCatching { cache["key"] }.exceptionOrNull().assert().isSameAs(failure)
    }

    @Test
    fun loadsFromSource() {
        val cache = createProxyCache<MockCache>(cacheSource = CacheSource { CacheValue.forever("value") })
        cache["key"].assert().isEqualTo("value")
    }

    @Test
    fun setElementsThroughProxy() {
        val cache = createProxyCache<MockSetCache>(coCacheMetadata<MockSetCache>())
        cache.addElements("key", listOf("a", "b"))
        cache.removeElements("key", listOf("a"))
        cache["key"].assert().isEqualTo(setOf("b"))
        cache.getTtlAt("key").assert().isEqualTo(TtlAt.FOREVER)
    }

    @Test
    fun setElementsRequiresSupportingDistributedCache() {
        val plain = object : DistributedCache<Set<String>> {
            override fun getCache(key: String): CacheValue<Set<String>>? = null
            override fun setCache(key: String, value: CacheValue<Set<String>>) = Unit
            override fun evict(key: String) = Unit
        }
        val error = Assertions.assertThrows(IllegalArgumentException::class.java) {
            createProxyCache<MockSetCache>(coCacheMetadata<MockSetCache>(), distributedCache = { plain })
        }
        error.message.assert().contains("SetElementsStore")
    }

    @Test
    fun setElementsRequiresSupportingCoherentCache() {
        // 自定义 CoherentCache 未实现 SetElementsCache：即使 L1 支持也拒绝，并关闭已创建的实例
        val closed = AtomicBoolean()
        val factory = object : CoherentCacheFactory {
            override fun <K, V> create(configuration: CoherentCacheConfiguration<K, V>): CoherentCache<K, V> {
                val default = DefaultCoherentCache(configuration, NoOpCacheEvictedEventBus)
                return object : CoherentCache<K, V> by default {
                    override fun close() {
                        closed.set(true)
                        default.close()
                    }
                }
            }
        }
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            createProxyCache<MockSetCache>(coCacheMetadata<MockSetCache>(), coherentCacheFactory = factory)
        }
        closed.get().assert().isTrue()
    }
}
