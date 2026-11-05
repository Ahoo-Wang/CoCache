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
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * `cocache.refresh.skipped`（RFC 0005 §8）：到期却没有运行的提前刷新。寿命 10 s，窗口 5 s，L2 限龄 8 s；刷新在调用方线程上执行。
 */
class RefreshSkippedTest {
    private val clock = ManualClock()
    private val memory = InMemoryBackend(clock)
    private val runtime = CacheRuntime.builder().backend(memory).clock(clock).refreshExecutor { it.run() }.build()
    private val database = ConcurrentHashMap<String, String>()

    @AfterEach
    fun destroy() = runtime.close()

    private fun cache(): Cache<String, String> = runtime.create(
        cacheSpec<String, String>(NAME) {
            codec = StringCodec
            loader = CacheLoader { database[it] }
            policy {
                ttl = Duration.ofSeconds(10)
                ttlJitter = Duration.ZERO
                localMaxAge = Duration.ofSeconds(8)
                refreshWindow = Duration.ofSeconds(5)
            }
        }
    )

    private fun metrics(): CacheMetrics = requireNotNull(runtime.metrics(NAME))

    /**
     * 刷新令牌在别的节点手里：刷新读只从 L1 重新填充 L2，不回源，计一次跳过。
     */
    @Test
    fun aRefreshWithoutTheTokenIsSkipped() {
        val cache = cache()
        database["k"] = "v1"
        cache["k"].assert().isEqualTo("v1")
        metrics().refreshSkipped.assert().isZero()

        clock.advance(Duration.ofSeconds(6))
        val foreign = Token(NodeId.random(), 1)
        val held = Suspending.await {
            memory.store(Keyspace(CacheSpec.DEFAULT_KEY_PREFIX, NAME)).read(
                "${CacheSpec.DEFAULT_KEY_PREFIX}$NAME:k",
                null,
                RefreshRequest(Duration.ofSeconds(5), foreign, Duration.ofSeconds(30)),
                null
            )
        }
        (held as L1Read.Hit).refreshGranted.assert().withFailMessage { "the foreign token was not granted" }.isTrue()
        database["k"] = "v2"
        cache["k"].assert().isEqualTo("v1")
        metrics().refreshes.assert().isEqualTo(1)
        metrics().refreshSkipped.assert().isEqualTo(1)
        metrics().loads.assert().isEqualTo(1)
    }

    /**
     * L1 剩余寿命不长于刷新窗口的值：副本一出生就在窗口内，不设刷新点，计一次跳过；寿命长于窗口的副本不计。
     */
    @Test
    fun aCopyWithoutARefreshPointIsSkipped() {
        val cache = cache()
        database["k"] = "v"
        cache["k"].assert().isEqualTo("v")
        cache.put("short", "s", Duration.ofSeconds(2))
        cache["short"].assert().isEqualTo("s")
        metrics().refreshSkipped.assert().isEqualTo(1)
        clock.advance(Duration.ofSeconds(1))
        cache["short"].assert().isEqualTo("s")
        metrics().refreshes.assert().isZero()
        metrics().refreshSkipped.assert().isEqualTo(1)
    }

    private companion object {
        const val NAME = "refresh-skip"
    }
}
