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
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class TtlTest {
    @Test
    fun isForever() {
        val ttlAt = TtlAt.FOREVER
        assertThat(TtlAt.isForever(ttlAt), equalTo(true))
    }

    @Test
    fun at() {
        val ttlAt = TtlAt.at(10)
        assertThat(ttlAt, greaterThan(CacheSecondClock.INSTANCE.currentTime()))
    }

    @Test
    fun atWithAmplitude() {
        val ttlAt = TtlAt.at(10, 10)
        assertThat(ttlAt, greaterThan(CacheSecondClock.INSTANCE.currentTime()))
    }

    @Test
    fun jitterZero() {
        val jitterTtlAt = TtlAt.jitter(60, 0)
        assertThat(jitterTtlAt, equalTo(60))
    }

    @Test
    fun jitter() {
        val jitterTtlAt = TtlAt.jitter(60, 10)
        assertThat(jitterTtlAt, greaterThanOrEqualTo(50))
        assertThat(jitterTtlAt, lessThanOrEqualTo(70))
    }
}
