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

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

class TtlTest {
    @Test
    fun foreverTtlStaysForever() {
        TtlAt.at(TtlAt.FOREVER, 10).assert().isEqualTo(TtlAt.FOREVER)
        CacheValue.forever("value").isForever.assert().isTrue()
        CacheValue.forever("value").isExpired.assert().isFalse()
    }

    @Test
    fun at() {
        val now = TtlAt.currentTime()
        TtlAt.at(10).assert().isBetween(now + 10, now + 11)
    }

    @Test
    fun atWithAmplitude() {
        val now = TtlAt.currentTime()
        TtlAt.at(10, 5).assert()
            .isGreaterThanOrEqualTo(now + 5)
            .isLessThanOrEqualTo(now + 16)
    }

    @Test
    fun amplitudeExceedingTtlStaysPositive() {
        repeat(100) {
            TtlAt.at(5, 10).assert().isGreaterThan(TtlAt.currentTime())
        }
    }

    @Test
    fun expiresAtCurrentSecondIsExpired() {
        val cacheValue = CacheValue.of("value", TtlAt.currentTime())
        cacheValue.isExpired.assert().isTrue()
        cacheValue.expiredDuration.assert().isEqualTo(Duration.ZERO)
    }

    @Test
    fun expiredDuration() {
        CacheValue.of("value", TtlAt.at(10)).expiredDuration.seconds.assert().isBetween(9, 10)
    }

    @Test
    fun ttlPolicyToCacheValue() {
        val policy = TtlPolicy(ttl = 100, ttlAmplitude = 0, missingTtl = 10)
        val now = TtlAt.currentTime()
        policy.toCacheValue("value").ttlAt.assert().isBetween(now + 100, now + 101)
        val missing = policy.toCacheValue<String>(null)
        missing.isMissing.assert().isTrue()
        missing.ttlAt.assert().isBetween(now + 10, now + 11)
    }

    @Test
    fun ttlPolicyRejectsInvalidValues() {
        runCatching { TtlPolicy(ttl = 0) }.isFailure.assert().isTrue()
        runCatching { TtlPolicy(ttlAmplitude = -1) }.isFailure.assert().isTrue()
        runCatching { TtlPolicy(missingTtl = 0) }.isFailure.assert().isTrue()
    }

    @Test
    fun cacheValueOfNullIsMissing() {
        CacheValue.of<String>(null, 10).assert().isEqualTo(CacheValue.missing<String>(10))
    }

    @Test
    fun valueShapedLikeSentinelIsNotMissing() {
        CacheValue.forever("_nil_").isMissing.assert().isFalse()
        CacheValue.forever(setOf("_nil_")).isMissing.assert().isFalse()
    }
}
