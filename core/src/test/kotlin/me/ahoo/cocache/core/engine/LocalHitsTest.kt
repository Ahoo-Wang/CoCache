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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.test.CacheAccess
import me.ahoo.cocache.test.EngineSpec.TestCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * L2 命中计数（`CacheMetrics.localHits`）：默认不记录；开启后每个由 L2 回答的读取恰好计一次。
 * [AsyncLocalHitsTest] 经异步视图重跑全部用例。
 */
open class LocalHitsTest {
    /**
     * 访问缓存的方式：同步 API，或经异步视图。
     */
    protected open val access: CacheAccess = CacheAccess.SYNC

    private val clock = ManualClock()
    private val backend = InMemoryBackend(clock)
    private val database = ConcurrentHashMap<String, String>()
    private val runtimes = mutableListOf<CacheRuntime>()

    private val loader = object : CacheLoader<String, String> {
        override fun load(key: String): String? = database[key]

        override fun loadAll(keys: Collection<String>): Map<String, String> =
            keys.mapNotNull { k -> database[k]?.let { k to it } }.toMap()
    }

    @AfterEach
    fun destroy() {
        runtimes.forEach { it.close() }
    }

    /**
     * 用例拿到的缓存（经 [access] 访问）及其计数。
     */
    private class Accessed(val cache: Cache<String, String>, val metrics: CacheMetrics)

    private fun cache(
        recordLocalHits: Boolean,
        policy: CachePolicy = CachePolicy(ttl = Duration.ofMinutes(10), ttlJitter = Duration.ZERO),
        block: CacheSpec.Builder<String, String>.() -> Unit = {}
    ): Accessed {
        val runtime = CacheRuntime.builder()
            .backend(backend)
            .clock(clock)
            .recordLocalHits(recordLocalHits)
            .build()
            .also { runtimes.add(it) }
        val spec = cacheSpec<String, String>("hits") {
            codec = TestCodec
            loader = this@LocalHitsTest.loader
            this.policy = policy
            block()
            access.source(this)
        }
        val engine = runtime.create(spec) as CoherentEngine<String, String>
        return Accessed(access.cache(engine), engine.metrics)
    }

    @Test
    fun localHitsAreNotRecordedByDefault() {
        val accessed = cache(recordLocalHits = false)
        database["k"] = "v"
        accessed.cache["k"].assert().isEqualTo("v")
        accessed.cache["k"].assert().isEqualTo("v")
        accessed.metrics.localHitsRecorded.assert().isFalse()
        accessed.metrics.localHits.assert().isZero()
        accessed.metrics.misses.assert().isEqualTo(1)
    }

    @Test
    fun localHitsCountEveryReadServedByL2() {
        val accessed = cache(
            recordLocalHits = true,
            policy = CachePolicy(
                ttl = Duration.ofMinutes(10),
                ttlJitter = Duration.ZERO,
                localMaxAge = Duration.ofSeconds(30)
            )
        )
        val cache = accessed.cache
        val metrics = accessed.metrics
        database["k"] = "v"
        cache["k"].assert().isEqualTo("v")
        metrics.localHitsRecorded.assert().isTrue()
        metrics.localHits.assert().isZero()
        metrics.misses.assert().isEqualTo(1)

        cache["k"].assert().isEqualTo("v")
        cache.lookup("k").assert().isInstanceOf(Lookup.Present::class.java)
        // 批量读取按 key 计数：命中的 key 计入 L2 命中，未命中的 key 计入未命中
        cache.getAll(listOf("k", "absent")).assert().containsExactlyEntriesOf(mapOf("k" to "v"))
        cache.get("k") { "unused" }.assert().isEqualTo("v")
        metrics.localHits.assert().isEqualTo(4)
        metrics.misses.assert().isEqualTo(2)

        // 负缓存同样由 L2 返回
        cache["absent"].assert().isNull()
        metrics.localHits.assert().isEqualTo(5)

        // 过期的 L2 副本不是命中：L2 未命中后由 L1 返回
        clock.advance(Duration.ofSeconds(31))
        cache["k"].assert().isEqualTo("v")
        metrics.localHits.assert().isEqualTo(5)
        metrics.misses.assert().isEqualTo(3)
        metrics.remoteHits.assert().isEqualTo(1)
    }

    @Test
    fun unresolvedLocalEntryIsNotALocalHitForCallerLoader() {
        val accessed = cache(recordLocalHits = true) { loader = null }
        val cache = accessed.cache
        val metrics = accessed.metrics
        cache["k"].assert().isNull()
        // 无数据源缓存的“不存在”由 L2 回答
        cache["k"].assert().isNull()
        metrics.localHits.assert().isEqualTo(1)

        // 调用方给出 loader 时，没有答案的 L2 副本不算命中
        cache.get("k") { "loaded" }.assert().isEqualTo("loaded")
        metrics.localHits.assert().isEqualTo(1)
        metrics.misses.assert().isEqualTo(2)
        cache.get("k") { "unused" }.assert().isEqualTo("loaded")
        metrics.localHits.assert().isEqualTo(2)
    }
}

class AsyncLocalHitsTest : LocalHitsTest() {
    override val access: CacheAccess = CacheAccess.ASYNC
}
