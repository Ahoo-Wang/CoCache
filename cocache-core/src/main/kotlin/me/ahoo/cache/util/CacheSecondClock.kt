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
package me.ahoo.cache.util

import java.time.Duration
import java.util.concurrent.locks.LockSupport

/**
 * Cache Second Clock .
 *
 * @author ahoo wang
 */
enum class CacheSecondClock(private val actual: SecondClock) : SecondClock, Runnable {
    INSTANCE(SystemSecondClock);

    private val secondTimer: Thread

    @Volatile
    private var lastTime: Long = actual.currentTime()

    init {
        secondTimer = startTimer()
    }

    private fun startTimer(): Thread {
        val timer = Thread(this)
        timer.name = "CacheSecondClock"
        timer.isDaemon = true
        timer.start()
        return timer
    }

    override fun currentTime(): Long {
        return lastTime
    }

    override fun run() {
        while (!secondTimer.isInterrupted) {
            lastTime = actual.currentTime()
            LockSupport.parkNanos(this, ONE_SECOND_PERIOD)
        }
    }

    companion object {
        /**
         * Tolerate a one-second time limit.
         */
        @JvmStatic
        val ONE_SECOND_PERIOD = Duration.ofSeconds(1).toNanos()
    }
}
