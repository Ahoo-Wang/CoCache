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

/**
 * 单调的纳秒计时源，引擎的截止时刻用它计算（等待 lease、`load.timeout`）。
 *
 * 生产中是 [SYSTEM]（`System.nanoTime()`）。确定性多节点仿真（RFC 0005 §4.10）换成测试调度器的虚拟时间，
 * 让截止时刻与 `delay`、`withTimeout` 走同一个时钟，同一个种子可以重放。
 */
internal fun interface Ticker {
    fun nanoTime(): Long

    companion object {
        val SYSTEM: Ticker = Ticker { System.nanoTime() }
    }
}
