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
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [SourceLoadLimit]：限制单个缓存同时进行的回源数，超时未获得许可时拒绝。
 */
internal class DefaultCoherentCacheLoadLimitTest {
    private val limit = SourceLoadLimit(maxConcurrentLoads = 1, acquireTimeout = Duration.ofMillis(50))

    /**
     * 回源 [blockedKey] 时阻塞，直到 [release] 被释放；其它 key 立即返回。
     */
    private inner class BlockingSource(private val blockedKey: String) : CacheSource<String, String> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun loadCacheValue(key: String): CacheValue<String> {
            if (key == blockedKey) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS).assert().isTrue()
            }
            return CacheValue.forever("value-$key")
        }
    }

    private fun createCache(
        cacheSource: CacheSource<String, String>,
        sourceLoadLimit: SourceLoadLimit = limit
    ): CoherentCache<String, String> {
        return DefaultCoherentCache(
            CoherentCacheConfiguration(
                cacheName = "DefaultCoherentCacheLoadLimitTest",
                clientId = "client",
                keyConverter = ToStringKeyConverter(""),
                clientSideCache = MapClientSideCache(),
                distributedCache = InMemoryDistributedCache(),
                cacheSource = cacheSource
            ),
            NoOpCacheEvictedEventBus,
            sourceLoadLimit = sourceLoadLimit
        )
    }

    /**
     * 在后台线程读取 [key]，返回等待其结束的 latch 与结果。
     */
    private fun CoherentCache<String, String>.readInBackground(
        key: String
    ): Pair<CountDownLatch, AtomicReference<Any?>> {
        val finished = CountDownLatch(1)
        val result = AtomicReference<Any?>()
        Thread {
            result.set(runCatching { this[key] }.fold({ it }, { it }))
            finished.countDown()
        }.start()
        return finished to result
    }

    @Test
    fun rejectsLoadBeyondLimitAndRecovers() {
        val source = BlockingSource("a")
        val cache = createCache(source)
        val (finished, result) = cache.readInBackground("a")
        source.entered.await(5, TimeUnit.SECONDS).assert().isTrue()

        val rejected = runCatching { cache["b"] }.exceptionOrNull()

        rejected.assert().isInstanceOf(SourceLoadRejectedException::class.java)
        cache.stats.loadRejectedCount.assert().isOne()
        source.release.countDown()
        finished.await(5, TimeUnit.SECONDS).assert().isTrue()
        result.get().assert().isEqualTo("value-a")
        cache["b"].assert().isEqualTo("value-b")
        cache.stats.loadCount.assert().isEqualTo(2)
    }

    @Test
    fun coalescedReadsDoNotTakePermits() {
        val source = BlockingSource("a")
        val cache = createCache(source)
        val (leaderFinished, leaderResult) = cache.readInBackground("a")
        source.entered.await(5, TimeUnit.SECONDS).assert().isTrue()
        val (followerFinished, followerResult) = cache.readInBackground("a")

        source.release.countDown()

        leaderFinished.await(5, TimeUnit.SECONDS).assert().isTrue()
        followerFinished.await(5, TimeUnit.SECONDS).assert().isTrue()
        leaderResult.get().assert().isEqualTo("value-a")
        followerResult.get().assert().isEqualTo("value-a")
        cache.stats.loadRejectedCount.assert().isZero()
    }

    @Test
    fun batchLoadTakesOnePermit() {
        val cache = createCache(CacheSource { CacheValue.forever("value-$it") })

        cache.getAll(listOf("a", "b", "c")).size.assert().isEqualTo(3)

        cache.stats.loadCount.assert().isOne()
        cache.stats.loadRejectedCount.assert().isZero()
    }

    @Test
    fun batchLoadIsRejectedBeyondLimit() {
        val source = BlockingSource("a")
        val cache = createCache(source)
        val (finished, _) = cache.readInBackground("a")
        source.entered.await(5, TimeUnit.SECONDS).assert().isTrue()

        runCatching { cache.getAll(listOf("b", "c")) }.exceptionOrNull()
            .assert().isInstanceOf(SourceLoadRejectedException::class.java)

        source.release.countDown()
        finished.await(5, TimeUnit.SECONDS).assert().isTrue()
    }

    @Test
    fun unlimitedByDefault() {
        SourceLoadLimit.UNLIMITED.isLimited.assert().isFalse()
        val cache = createCache(CacheSource { CacheValue.forever("value-$it") }, SourceLoadLimit.UNLIMITED)
        cache["a"].assert().isEqualTo("value-a")
    }

    @Test
    fun rejectsInvalidLimit() {
        runCatching { SourceLoadLimit(maxConcurrentLoads = -1) }.isFailure.assert().isTrue()
        runCatching { SourceLoadLimit(acquireTimeout = Duration.ofMillis(-1)) }.isFailure.assert().isTrue()
    }
}
