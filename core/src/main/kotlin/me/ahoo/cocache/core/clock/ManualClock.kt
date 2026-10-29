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
import java.util.concurrent.atomic.AtomicLong

/**
 * 手动推进的时钟，供测试使用：TTL、刷新窗口、限龄相关的用例无需 sleep。
 */
class ManualClock @JvmOverloads constructor(startMillis: Long = System.currentTimeMillis()) : CacheClock {
    private val now = AtomicLong(startMillis)

    override fun nowMillis(): Long = now.get()

    fun advance(duration: Duration) {
        require(!duration.isNegative) { "duration[$duration] must not be negative." }
        now.addAndGet(duration.toMillis())
    }
}
