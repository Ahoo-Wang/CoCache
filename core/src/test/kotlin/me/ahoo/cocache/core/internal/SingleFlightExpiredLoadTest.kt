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

package me.ahoo.cocache.core.internal

import me.ahoo.cocache.core.engine.AsyncContext
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 超过 `load.timeout` 的加载（RFC 0005 §4.4，P16）：计时源是手动的 [nanos]，截止时刻不依赖真实时间。
 * 同一线程再调用自己正在加载的 key 仍快速失败（重入检查先于接管，#816）；别的线程照常接管。
 */
class SingleFlightExpiredLoadTest {
    private val context = AsyncContext(ForkJoinPool.commonPool())
    private val scope = context.scope
    private val nanos = AtomicLong()
    private val bounded = SingleFlight<String, String>(TIMEOUT, { nanos.get() }) { LoadTimedOut() }

    @AfterEach
    fun tearDown() {
        context.close()
    }

    private class LoadTimedOut : RuntimeException("timed out")

    /**
     * #816：同步 leader 的加载已超过 `load.timeout`（手动的计时源推进到截止时刻之后）时，它在 block 中再调用同一个 key
     * （单 key 或批量，外层同样单 key 或批量）仍快速失败：重入检查先于过期接管，block 只执行一次，没有遗留的加载。
     */
    @Test
    fun reentryIntoAnExpiredOwnLoadStillFailsFast() {
        val enter: List<(suspend () -> String) -> String> = listOf(
            { block -> runSync { bounded.executeSuspending("k", scope, block) } },
            { block ->
                val loaded = runSync {
                    bounded.executeAllSuspending(listOf("k"), scope) { keys -> keys.associateWith { block() } }
                }
                loaded.getValue("k")
            }
        )
        for (outer in enter) {
            for (inner in enter) {
                val runs = AtomicInteger()
                val error = runCatching {
                    outer {
                        runs.incrementAndGet()
                        nanos.addAndGet(TIMEOUT + 1)
                        inner {
                            runs.incrementAndGet()
                            "nested"
                        }
                    }
                }.exceptionOrNull()
                error.assert().isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Recursive call")
                runs.get().assert().isEqualTo(1)
                bounded.inFlight.assert().isEqualTo(0)
            }
        }
    }

    /**
     * #816：别的线程仍接管超过 `load.timeout` 的加载；原 leader 迟到的完成按身份注销，不移走接管的加载，
     * 之后到来的跟随者合并到接管的加载、拿到它的结果。
     */
    @Test
    fun anotherThreadTakesOverAnExpiredLoadAndTheOldLeaderKeepsIt() {
        val started = CountDownLatch(1)
        val releaseStuck = CountDownLatch(1)
        val stuck = fork("stuck") {
            runSync {
                bounded.executeSuspending("k", scope) {
                    started.countDown()
                    releaseStuck.awaitOrFail("release stuck leader")
                    "stuck"
                }
            }
        }
        started.awaitOrFail("stuck leader started")
        nanos.addAndGet(TIMEOUT)
        val takenOver = CountDownLatch(1)
        val releaseFresh = CountDownLatch(1)
        val fresh = fork("take over") {
            runSync {
                bounded.executeSuspending("k", scope) {
                    takenOver.countDown()
                    releaseFresh.awaitOrFail("release fresh leader")
                    "fresh"
                }
            }
        }
        takenOver.awaitOrFail("taken over")
        releaseStuck.countDown()
        stuck.join().assert().isEqualTo("stuck")
        bounded.inFlight.assert().isEqualTo(1)
        val runs = AtomicInteger()
        val follower = fork("follower") {
            runSync { bounded.executeSuspending("k", scope) { "own".also { runs.incrementAndGet() } } }
        }
        awaitCondition("follower parked") { follower.parked }
        releaseFresh.countDown()
        fresh.join().assert().isEqualTo("fresh")
        follower.join().assert().isEqualTo("fresh")
        runs.get().assert().isEqualTo(0)
        bounded.inFlight.assert().isEqualTo(0)
    }

    private companion object {
        val TIMEOUT: Long = TimeUnit.SECONDS.toNanos(30)
    }
}
