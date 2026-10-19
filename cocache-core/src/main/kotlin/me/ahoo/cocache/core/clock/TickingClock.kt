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

package me.ahoo.cocache.core.clock

import me.ahoo.cocache.api.CacheClock
import java.time.Duration
import java.util.concurrent.locks.LockSupport

/**
 * 定时刷新的时钟：守护线程每 [tick] 刷新一次 volatile 值，读取只是一次 volatile 读。
 *
 * 直接调用 `System.currentTimeMillis()` 在部分平台（如 macOS）上无法随线程数扩展。由运行时启动与关闭。
 */
class TickingClock @JvmOverloads constructor(
    private val tick: Duration = DEFAULT_TICK,
) : CacheClock, AutoCloseable {
    init {
        require(!tick.isNegative && !tick.isZero) { "tick[$tick] must be positive." }
    }

    @Volatile
    private var now: Long = System.currentTimeMillis()

    @Volatile
    private var running = true

    // 先完成字段初始化再启动线程：start() 建立 happens-before
    private val thread = Thread(::run, "cocache-clock").apply {
        isDaemon = true
        start()
    }

    override fun nowMillis(): Long = now

    private fun run() {
        val period = tick.toNanos()
        while (running) {
            now = System.currentTimeMillis()
            LockSupport.parkNanos(this, period)
        }
    }

    override fun close() {
        running = false
        LockSupport.unpark(thread)
    }

    companion object {
        @JvmField
        val DEFAULT_TICK: Duration = Duration.ofMillis(100)
    }
}
