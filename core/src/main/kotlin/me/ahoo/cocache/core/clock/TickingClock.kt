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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * 定时刷新的时钟：守护线程每 [tick] 刷新一次 volatile 值，读取只是一次 volatile 读。
 *
 * 直接调用 `System.currentTimeMillis()` 在部分平台（如 macOS）上无法随线程数扩展。由运行时启动与关闭。
 *
 * 单调（RFC 0005 附录 A.C1）：启动时取一次墙钟，之后以 `System.nanoTime()` 的增量推进。墙钟回拨（NTP 校正、人工调整）
 * 不会让时间倒退，L2 副本也就不会多活回拨的那段时间；墙钟向前跳同样不会让副本提前到期。L2 只用它计算相对的寿命，
 * 到期时间跨网络时只以相对时长传输，因此与墙钟的累计偏差没有影响（`CacheAdmin.inspect` 展示的到期时刻以启动时的墙钟为基准）。
 */
class TickingClock internal constructor(
    private val tick: Duration,
    private val nanoTime: () -> Long,
    wallMillis: () -> Long,
) : CacheClock, AutoCloseable {

    @JvmOverloads
    constructor(tick: Duration = DEFAULT_TICK) : this(tick, System::nanoTime, System::currentTimeMillis)

    init {
        require(!tick.isNegative && !tick.isZero) { "tick[$tick] must be positive." }
    }

    private val originNanos: Long = nanoTime()
    private val originMillis: Long = wallMillis()

    @Volatile
    private var now: Long = originMillis

    @Volatile
    private var running = true

    // 先完成字段初始化再启动线程：start() 建立 happens-before
    private val thread = Thread(::run, "cocache-clock").apply {
        isDaemon = true
        start()
    }

    override fun nowMillis(): Long = now

    /**
     * 以启动时的墙钟加上单调时钟的增量刷新当前时间。
     */
    internal fun refresh() {
        now = originMillis + TimeUnit.NANOSECONDS.toMillis(nanoTime() - originNanos)
    }

    private fun run() {
        val period = tick.toNanos()
        while (running) {
            refresh()
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
