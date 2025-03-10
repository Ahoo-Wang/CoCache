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

package me.ahoo.cache.test

import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.consistency.CacheEvictedEvent
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.consistency.CacheEvictedSubscriber
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.CoherentCacheFactory
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.distributed.DistributedCache
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

abstract class MultipleInstanceSyncSpec<K, V> {
    companion object {
        private val log = LoggerFactory.getLogger(MultipleInstanceSyncSpec::class.java)
    }

    private lateinit var keyConverter: KeyConverter<K>
    private lateinit var distributedCaching: DistributedCache<V>
    private lateinit var cacheEvictedEventBus: CacheEvictedEventBus
    protected lateinit var cacheName: String
    protected val currentClientId: String = "currentClientId"
    protected val otherClientId: String = "otherClientId"
    private lateinit var currentCache: CoherentCache<K, V>
    private lateinit var otherCache: CoherentCache<K, V>
    private lateinit var coherentCacheFactory: CoherentCacheFactory

    @BeforeEach
    open fun setup() {
        keyConverter = createKeyConverter()
        distributedCaching = createDistributedCache()
        cacheEvictedEventBus = createCacheEvictedEventBus()
        coherentCacheFactory = DefaultCoherentCacheFactory(cacheEvictedEventBus)
        cacheName = createCacheName()

        currentCache = coherentCacheFactory.create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = currentClientId,
                keyConverter = keyConverter,
                distributedCache = distributedCaching,
                clientSideCache = createClientSideCache(),
            ),
        )

        otherCache = coherentCacheFactory.create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = otherClientId,
                keyConverter = keyConverter,
                distributedCache = distributedCaching,
                clientSideCache = createClientSideCache(),
            ),
        )
    }

    protected abstract fun createKeyConverter(): KeyConverter<K>
    protected abstract fun createClientSideCache(): ClientSideCache<V>
    protected abstract fun createDistributedCache(): DistributedCache<V>
    protected abstract fun createCacheEvictedEventBus(): CacheEvictedEventBus
    protected abstract fun createCacheName(): String
    protected abstract fun createCacheEntry(): Pair<K, V>

    @Test
    fun multipleInstanceSync() {
        val (key, value) = createCacheEntry()
        val cacheKey = keyConverter.toStringKey(key)

        val latch1 = CountDownLatch(1)
        val latch2 = CountDownLatch(2)
        val latch3 = CountDownLatch(3)
        val subscriber = object : CacheEvictedSubscriber {
            override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
                if (cacheEvictedEvent.cacheName != cacheName) {
                    return
                }
                if (cacheEvictedEvent.key == cacheKey &&
                    cacheEvictedEvent.publisherId == currentClientId
                ) {
                    log.info("onEvicted - Current - {}", cacheEvictedEvent)
                    latch1.countDown()
                    latch2.countDown()
                    latch3.countDown()
                }
            }

            override val cacheName: String
                get() = this@MultipleInstanceSyncSpec.cacheName
        }
        cacheEvictedEventBus.register(subscriber)

        assertThat(otherCache, not(currentCache))
        //region init
        assertThat(currentCache.clientSideCache[cacheKey], nullValue())
        currentCache[key] = value
        assertThat(currentCache.clientSideCache[cacheKey], equalTo(value))
        assertThat(latch1.await(1, TimeUnit.SECONDS), equalTo(true))
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100))
        assertThat(otherCache.clientSideCache[cacheKey], nullValue())
        assertThat(otherCache[key], equalTo(value))
        assertThat(otherCache.clientSideCache[cacheKey], equalTo(value))
        //endregion

        //region set
        val nextValue = createCacheEntry().second
        currentCache[key] = nextValue
        assertThat(latch2.await(1, TimeUnit.SECONDS), equalTo(true))
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100))
        assertThat(otherCache.clientSideCache[cacheKey], nullValue())
        //endregion

        currentCache.evict(key)
        assertThat(currentCache[key], nullValue())
        assertThat(latch3.await(1, TimeUnit.SECONDS), equalTo(true))
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100))
        assertThat(otherCache.clientSideCache[cacheKey], nullValue())
        assertThat(otherCache[key], nullValue())
    }
}
