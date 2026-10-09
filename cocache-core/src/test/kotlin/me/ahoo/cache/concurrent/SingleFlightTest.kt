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

    /**
     * leader 线程第二次计算 hashCode（即注销调用时）阻塞到 [release]，用于在“注销”这一步暂停 leader。
     */
    private class PausingKey(private val leader: () -> Thread?, private val release: CountDownLatch) {
        private val leaderHashes = AtomicInteger()

        override fun hashCode(): Int {
            if (Thread.currentThread() === leader() && leaderHashes.incrementAndGet() == 2) {
                // 修复后结果在注销之后才发布，等待者无法在此期间重试：有界等待后放行
                release.await(500, TimeUnit.MILLISECONDS)
            }
            return 1
        }

        override fun equals(other: Any?): Boolean = other === this
    }

    @Test
    fun followerRetryAfterWakeStartsNewExecution() {
        val flight = SingleFlight<PausingKey, String>()
        val leaderStarted = CountDownLatch(1)
        val releaseLeader = CountDownLatch(1)
        val followerRetried = CountDownLatch(1)
        val leaderRef = AtomicReference<Thread>()
        val key = PausingKey(leaderRef::get, followerRetried)
        val leader = Thread {
            flight.execute(key) {
                leaderStarted.countDown()
                releaseLeader.await(5, TimeUnit.SECONDS)
                "stale"
            }
        }
        leaderRef.set(leader)
        leader.start()
        leaderStarted.await(5, TimeUnit.SECONDS).assert().isTrue()

        val first = AtomicReference<String>()
        val retried = AtomicReference<String>()
        val follower = Thread {
            first.set(flight.execute(key) { "unexpected" })
            // 被唤醒后立即重试：必须开始新的执行，而不是再次加入已结束的调用
            retried.set(flight.execute(key) { "fresh" })
            followerRetried.countDown()
        }
        follower.start()
        while (follower.state != Thread.State.WAITING) {
            Thread.onSpinWait()
        }
        releaseLeader.countDown()
        leader.join(5000)
        follower.join(5000)

        first.get().assert().isEqualTo("stale")
        retried.get().assert().isEqualTo("fresh")
        flight.inFlight.assert().isZero()
    }

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
