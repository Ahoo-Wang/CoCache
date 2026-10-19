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

import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {
    private val flights = SingleFlight<String, String>()

    @Test
    fun followersShareTheLeaderResult() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val leader = fork {
            flights.execute("k") {
                calls.incrementAndGet()
                started.countDown()
                release.awaitOrFail()
                "v"
            }
        }
        started.awaitOrFail()
        val follower = fork {
            flights.execute("k") {
                calls.incrementAndGet()
                "other"
            }
        }
        awaitCondition { follower.thread.state == Thread.State.WAITING }
        flights.inFlight.assert().isEqualTo(1)
        release.countDown()
        leader.join().assert().isEqualTo("v")
        follower.join().assert().isEqualTo("v")
        calls.get().assert().isEqualTo(1)
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun followersReceiveTheOriginalException() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IllegalStateException("boom")
        val leader = fork {
            flights.execute("k") {
                started.countDown()
                release.awaitOrFail()
                throw failure
            }
        }
        started.awaitOrFail()
        val follower = fork { flights.execute("k") { "other" } }
        awaitCondition { follower.thread.state == Thread.State.WAITING }
        release.countDown()
        leader.joinFailure().assert().isSameAs(failure)
        follower.joinFailure().assert().isSameAs(failure)
    }

    @Test
    fun reentrantCallFailsFast() {
        val error = runCatching { flights.execute("k") { flights.execute("k") { "inner" } } }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalStateException::class.java)
        val batchError = runCatching {
            flights.execute("k") { flights.executeAll(listOf("k")) { mapOf("k" to "v") }.getValue("k") }
        }.exceptionOrNull()
        batchError.assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun executeAllLeadsFreeKeysAndJoinsInFlightOnes() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val single = fork {
            flights.execute("a") {
                started.countDown()
                release.awaitOrFail()
                "a1"
            }
        }
        started.awaitOrFail()
        val led = mutableListOf<String>()
        val batch = fork {
            flights.executeAll(listOf("a", "b", "b", "c")) { keys ->
                led.addAll(keys)
                keys.associateWith { "${it}2" }
            }
        }
        awaitCondition { batch.thread.state == Thread.State.WAITING }
        release.countDown()
        single.join()
        batch.join().assert().isEqualTo(mapOf("b" to "b2", "c" to "c2", "a" to "a1"))
        led.assert().containsExactly("b", "c")
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun executeAllPropagatesFailureToJoinedCallers() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IllegalStateException("batch failed")
        val batch = fork {
            flights.executeAll(listOf("a")) {
                started.countDown()
                release.awaitOrFail()
                throw failure
            }
        }
        started.awaitOrFail()
        val follower = fork { flights.execute("a") { "other" } }
        awaitCondition { follower.thread.state == Thread.State.WAITING }
        release.countDown()
        batch.joinFailure().assert().isSameAs(failure)
        follower.joinFailure().assert().isSameAs(failure)
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun executeAllMissingResultFailsJoinedCallers() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val batch = fork {
            flights.executeAll(listOf("a", "b")) {
                started.countDown()
                release.awaitOrFail()
                mapOf("a" to "v")
            }
        }
        started.awaitOrFail()
        val follower = fork { flights.execute("b") { "other" } }
        awaitCondition { follower.thread.state == Thread.State.WAITING }
        release.countDown()
        batch.join().assert().isEqualTo(mapOf("a" to "v"))
        follower.joinFailure().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun executeAllWithOnlyJoinedKeysDoesNotCallBlock() {
        flights.executeAll(emptyList<String>()) { throw AssertionError("not called") }.assert().isEmpty()
    }

    @Test
    fun stampsDetectInvalidation() {
        val stamps = InvalidationStamps(16)
        val stamp = stamps.current("k")
        stamps.isValid("k", stamp).assert().isTrue()
        stamps.invalidate("k")
        stamps.isValid("k", stamp).assert().isFalse()
        val other = stamps.current("other")
        stamps.invalidateAll()
        stamps.isValid("other", other).assert().isFalse()
        runCatching {
            InvalidationStamps(
                3
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            InvalidationStamps(
                0
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }
}
