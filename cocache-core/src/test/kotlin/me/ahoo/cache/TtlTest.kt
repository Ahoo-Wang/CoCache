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

package me.ahoo.cache

import me.ahoo.cache.util.CacheSecondClock
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class TtlTest {
    @Test
    fun isForever() {
        val ttlAt = ComputedTtlAt.FOREVER
        ComputedTtlAt.isForever(ttlAt).assert().isTrue()
    }

    @Test
    fun at() {
        val ttlAt = ComputedTtlAt.at(10)
        ttlAt.assert().isGreaterThan(CacheSecondClock.INSTANCE.currentTime())
    }

    @Test
    fun atWithAmplitude() {
        val ttlAt = ComputedTtlAt.at(10, 5)
        ttlAt.assert()
            .isGreaterThanOrEqualTo(CacheSecondClock.INSTANCE.currentTime() + 5)
            .isLessThanOrEqualTo(CacheSecondClock.INSTANCE.currentTime() + 15)
    }

    @Test
    fun jitterZero() {
        ComputedTtlAt.jitter(60, 0).assert().isEqualTo(60)
    }

    @Test
    fun jitter() {
        val jitterTtlAt = ComputedTtlAt.jitter(60, 10)
        jitterTtlAt.assert()
            .isGreaterThanOrEqualTo(50)
            .isLessThanOrEqualTo(70)
    }
}
