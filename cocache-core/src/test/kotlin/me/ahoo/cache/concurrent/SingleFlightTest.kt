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

package me.ahoo.cache.concurrent

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SingleFlightTest {
    private val singleFlight = SingleFlight<String, String>()

    @Test
    fun followerSharesLeaderResult() {
        val calls = AtomicInteger()
        val leaderStarted = CountDownLatch(1)
        val releaseLeader = CountDownLatch(1)
        val leaderResult = AtomicReference<String>()
        val leader = Thread {
            leaderResult.set(
                singleFlight.execute("key") {
                    calls.incrementAndGet()
                    leaderStarted.countDown()
                    releaseLeader.await(5, TimeUnit.SECONDS)
                    "value"
                }
            )
        }
        leader.start()
        leaderStarted.await(5, TimeUnit.SECONDS).assert().isTrue()

        val followerResult = AtomicReference<String>()
        val follower = Thread {
            followerResult.set(singleFlight.execute("key") { calls.incrementAndGet().toString() })
        }
        follower.start()
        // follower 在 leader 结束前加入等待（in-flight 计数仍为 1）
        while (follower.state != Thread.State.WAITING) {
            Thread.onSpinWait()
        }
        releaseLeader.countDown()
        leader.join(5000)
        follower.join(5000)

        leaderResult.get().assert().isEqualTo("value")
        followerResult.get().assert().isEqualTo("value")
        calls.get().assert().isOne()
        singleFlight.inFlight.assert().isZero()
    }

    @Test
    fun followerReceivesLeaderException() {
        val failure = IllegalStateException("boom")
        val leaderStarted = CountDownLatch(1)
        val releaseLeader = CountDownLatch(1)
        val leader = Thread {
            runCatching {
                singleFlight.execute("key") {
                    leaderStarted.countDown()
                    releaseLeader.await(5, TimeUnit.SECONDS)
                    throw failure
                }
            }
        }
        leader.start()
        leaderStarted.await(5, TimeUnit.SECONDS).assert().isTrue()
        val followerError = AtomicReference<Throwable>()
        val follower = Thread {
            runCatching { singleFlight.execute("key") { "unexpected" } }.onFailure { followerError.set(it) }
        }
        follower.start()
        while (follower.state != Thread.State.WAITING) {
            Thread.onSpinWait()
        }
        releaseLeader.countDown()
        follower.join(5000)

        followerError.get().assert().isSameAs(failure)
        singleFlight.inFlight.assert().isZero()
    }

    @Test
    fun differentKeysDoNotBlockEachOther() {
        val result = singleFlight.execute("a") {
            singleFlight.execute("b") { "b" }
        }
        result.assert().isEqualTo("b")
    }

    @Test
    fun recursiveCallOnSameKeyFailsFast() {
        val error = runCatching {
            singleFlight.execute("key") {
                singleFlight.execute("key") { "inner" }
            }
        }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalStateException::class.java)
        singleFlight.inFlight.assert().isZero()
    }
}
