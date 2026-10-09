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

package me.ahoo.cache.api

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * 缓存使用的秒级时钟：由一个守护线程每 [TICK_MILLIS] 毫秒刷新，读取只是一次 volatile 读。
 *
 * 缓存命中路径每次都要判断是否过期；直接调用 `System.currentTimeMillis()` 在部分平台（如 macOS）上
 * 无法随线程数扩展。秒级 TTL 下，最多 [TICK_MILLIS] 毫秒的滞后可以忽略。
 */
object CacheClock {
    const val TICK_MILLIS = 100L

    @Volatile
    private var currentSecond: Long = System.currentTimeMillis() / 1000

    init {
        // 先完成字段初始化再启动线程：start() 建立 happens-before，线程可见已初始化的状态
        Thread(::tick, "cocache-clock").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 当前纪元秒（最多滞后 [TICK_MILLIS] 毫秒）。
     */
    @JvmStatic
    fun currentTime(): Long = currentSecond

    private fun tick() {
        val period = TimeUnit.MILLISECONDS.toNanos(TICK_MILLIS)
        while (!Thread.currentThread().isInterrupted) {
            currentSecond = System.currentTimeMillis() / 1000
            LockSupport.parkNanos(this, period)
        }
    }
}
