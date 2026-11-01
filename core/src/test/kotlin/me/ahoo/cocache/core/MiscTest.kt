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

package me.ahoo.cocache.core

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.clock.TickingClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.engine.InvalidationRetrier
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MiscTest {
    @Test
    fun tickingClockAdvancesAndStops() {
        val clock = TickingClock(Duration.ofMillis(1))
        val start = clock.nowMillis()
        awaitCondition("clock ticks") { clock.nowMillis() > start }
        clock.close()
        runCatching {
            TickingClock(Duration.ZERO)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 单调（RFC 0005 附录 A.C1）：启动后只按单调时钟的增量前进，墙钟回拨或向前跳都不影响。
     */
    @Test
    fun tickingClockIgnoresWallClockSteps() {
        val nanos = AtomicLong()
        val wall = AtomicLong(1_000_000_000L)
        // tick 很长：后台线程启动时刷新一次后就停住，由用例显式刷新
        TickingClock(Duration.ofHours(1), nanos::get, wall::get).use { clock ->
            clock.nowMillis().assert().isEqualTo(1_000_000_000L)
            wall.addAndGet(-Duration.ofHours(1).toMillis())
            nanos.addAndGet(Duration.ofSeconds(5).toNanos())
            clock.refresh()
            clock.nowMillis().assert().isEqualTo(1_000_005_000L)
            wall.addAndGet(Duration.ofHours(2).toMillis())
            nanos.addAndGet(Duration.ofMillis(1).toNanos())
            clock.refresh()
            clock.nowMillis().assert().isEqualTo(1_000_005_001L)
        }
    }

    /**
     * 墙钟回拨时 L2 副本不多活（RFC 0005 附录 A.C1）：以墙钟计时的话，回拨一小时会让 `localMaxAge` 为 1 s 的副本多活一小时。
     */
    @Test
    fun localCopiesExpireOnTimeWhenTheWallClockStepsBack() {
        val nanos = AtomicLong()
        val wall = AtomicLong(System.currentTimeMillis())
        val loads = AtomicInteger()
        TickingClock(Duration.ofHours(1), nanos::get, wall::get).use { clock ->
            CacheRuntime.builder().backend(InMemoryBackend(clock)).clock(clock).build().use { runtime ->
                val cache = runtime.create(
                    cacheSpec<String, String>("clock") {
                        codec = StringCodec
                        loader = CacheLoader {
                            loads.incrementAndGet()
                            "v"
                        }
                        policy = CachePolicy(localMaxAge = Duration.ofSeconds(1))
                    }
                )
                cache["k"].assert().isEqualTo("v")
                // 墙钟回拨一小时，单调时钟前进 2 s：L2 副本已到期，再读 L1（L1 的寿命还长，不回源）
                wall.addAndGet(-Duration.ofHours(1).toMillis())
                nanos.addAndGet(Duration.ofSeconds(2).toNanos())
                clock.refresh()
                cache["k"].assert().isEqualTo("v")
                requireNotNull(runtime.metrics("clock")).remoteHits.assert().isEqualTo(1L)
                loads.get().assert().isEqualTo(1)
            }
        }
    }

    @Test
    fun manualClockOnlyMovesForward() {
        val clock = ManualClock(100)
        clock.advance(Duration.ofMillis(5))
        clock.nowMillis().assert().isEqualTo(105)
        runCatching {
            clock.advance(Duration.ofMillis(-1))
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        ManualClock().nowMillis().assert().isGreaterThan(0)
    }

    @Test
    fun stringCodecRoundTrips() {
        StringCodec.decode(StringCodec.encode("中文")).assert().isEqualTo("中文")
    }

    @Test
    fun rejectedExceptionDescribesPolicy() {
        val error = SourceLoadRejectedException("user", LoadPolicy(1))
        error.cacheName.assert().isEqualTo("user")
        error.loadPolicy.maxConcurrent.assert().isEqualTo(1)
        error.message.assert().contains("user")
    }

    @Test
    fun retryPolicyBacksOffExponentiallyUpToMax() {
        val policy = RetryPolicy(initialBackoff = Duration.ofMillis(100), maxBackoff = Duration.ofMillis(350))
        policy.backoff(1).assert().isEqualTo(Duration.ofMillis(100))
        policy.backoff(2).assert().isEqualTo(Duration.ofMillis(200))
        policy.backoff(3).assert().isEqualTo(Duration.ofMillis(350))
        policy.backoff(100).assert().isEqualTo(Duration.ofMillis(350))
        fun fails(
            block: () -> Any
        ) = runCatching(block).exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        fails { RetryPolicy(maxAttempts = 0) }
        fails { RetryPolicy(initialBackoff = Duration.ZERO) }
        fails { RetryPolicy(initialBackoff = Duration.ofSeconds(2), maxBackoff = Duration.ofSeconds(1)) }
        fails { RetryPolicy(capacity = 0) }
    }

    @Test
    fun retrierRetriesUntilSuccess() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val retrier = InvalidationRetrier(
                scheduler,
                RetryPolicy(initialBackoff = Duration.ofMillis(1), maxBackoff = Duration.ofMillis(2))
            )
            val attempts = AtomicInteger()
            val retries = AtomicInteger()
            val succeeded = AtomicInteger()
            retrier.submit(
                action = { if (attempts.incrementAndGet() < 3) throw IllegalStateException("not yet") },
                onRetry = { retries.incrementAndGet() },
                onSuccess = { succeeded.incrementAndGet() },
                onGiveUp = { throw AssertionError("gave up") },
            ).assert().isTrue()
            awaitCondition("success") { succeeded.get() == 1 }
            retries.get().assert().isEqualTo(3)
            retrier.size.assert().isEqualTo(0)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun retrierGivesUpAfterMaxAttempts() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val retrier = InvalidationRetrier(
                scheduler,
                RetryPolicy(maxAttempts = 2, initialBackoff = Duration.ofMillis(1), maxBackoff = Duration.ofMillis(1))
            )
            val gaveUp = AtomicReference<Throwable>()
            retrier.submit(
                { throw IllegalStateException("down") },
                {},
                { throw AssertionError("succeeded") },
                { gaveUp.set(it) }
            )
            awaitCondition("give up") { gaveUp.get() != null }
            retrier.size.assert().isEqualTo(0)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun retrierRejectsWhenFullOrShutDown() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val retrier = InvalidationRetrier(
            scheduler,
            RetryPolicy(capacity = 1, initialBackoff = Duration.ofHours(1), maxBackoff = Duration.ofHours(1))
        )
        retrier.submit({}, {}, {}, {}).assert().isTrue()
        retrier.submit({}, {}, {}, {}).assert().isFalse()
        scheduler.shutdownNow()
        val closed = InvalidationRetrier(scheduler, RetryPolicy())
        closed.submit({}, {}, {}, {}).assert().isFalse()
        closed.size.assert().isEqualTo(0)
    }

    @Test
    fun retrierGivesUpWhenReschedulingIsRejected() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val retrier = InvalidationRetrier(
            scheduler,
            RetryPolicy(initialBackoff = Duration.ofMillis(1), maxBackoff = Duration.ofMillis(1))
        )
        val gaveUp = AtomicReference<Throwable>()
        retrier.submit({
            scheduler.shutdown()
            throw IllegalStateException("down")
        }, {}, {}, { gaveUp.set(it) })
        awaitCondition("give up") { gaveUp.get() != null }
        retrier.size.assert().isEqualTo(0)
    }
}
