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
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.internal.Ticker
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 缺陷复现（#1000）：线程 A 的同步 loader 仍在执行时，它的加载超过 `load.timeout`，线程 B 接管并完成。A 的 loader 随后再读同一个 key
 * （`get` 或 `getAll`）时，这个 key 已没有登记的调用，只在“已有调用”时运行的重入检查不再运行：A 成为新的 leader、再回源一次。
 * 现在注册之前先查本线程正在回源的 key，与有没有接管无关地快速失败（I-5）。对照：没有接管时照旧快速失败（#816）。
 */
class TakenOverReentryTest {
    @ParameterizedTest
    @MethodSource("cases")
    fun theOriginalLoaderStillRejectsReentry(takeover: Boolean, outerBatch: Boolean, innerBatch: Boolean) {
        val nanos = AtomicLong()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val calls = AtomicInteger()
        val clock = ManualClock(0)
        InMemoryBackend(clock).use { backend ->
            val builder = CacheRuntime.builder().backend(backend)
            builder.ticker = Ticker { nanos.get() }
            builder.build().use { runtime ->
                lateinit var cache: Cache<String, String>
                cache = runtime.create(
                    cacheSpec<String, String>("reentry") {
                        codec = StringCodec
                        policy {
                            localMaxAge = Duration.ofMillis(1)
                            degradedLocalMaxAge = Duration.ofMillis(1)
                            load = LoadPolicy(timeout = Duration.ofSeconds(30))
                            lease = LeasePolicy(ttl = Duration.ofMinutes(5), wait = Duration.ZERO)
                        }
                        loader = CacheLoader { key ->
                            when (calls.incrementAndGet()) {
                                1 -> {
                                    entered.countDown()
                                    resume.awaitOrFail("the old loader may reenter")
                                    read(cache, key, innerBatch)
                                }
                                2 -> "replacement"
                                else -> "recursive"
                            }
                        }
                    }
                )
                val original = fork("original-loader") { runCatching { read(cache, "k", outerBatch) } }
                entered.awaitOrFail("the old loader started")
                nanos.set(Duration.ofSeconds(31).toNanos())
                clock.advance(Duration.ofSeconds(31))
                try {
                    if (takeover) {
                        // B 接管过期的加载，绕过 A 仍有效的 lease 回源；之后让 B 的降级副本到期，A 的 lease 仍有效
                        read(cache, "k", outerBatch).assert().isEqualTo("replacement")
                        clock.advance(Duration.ofMillis(2))
                        nanos.addAndGet(Duration.ofMillis(2).toNanos())
                    }
                } finally {
                    resume.countDown()
                }
                val result = original.join()
                result.exceptionOrNull().assert()
                    .withFailMessage { "The old loader reentered: result=$result, source calls=${calls.get()}" }
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("Recursive call")
                calls.get().assert().isEqualTo(if (takeover) 2 else 1)
            }
        }
    }

    private fun read(cache: Cache<String, String>, key: String, batch: Boolean): String? =
        if (batch) cache.getAll(listOf(key))[key] else cache[key]

    companion object {
        @JvmStatic
        fun cases(): List<Arguments> = listOf(false, true).flatMap { takeover ->
            listOf(false, true).flatMap { outer ->
                listOf(false, true).map { inner -> Arguments.of(takeover, outer, inner) }
            }
        }
    }
}
