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

package me.ahoo.cocache.core.policy

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

class CachePolicyTest {
    @Test
    fun defaultsAreFinite() {
        val policy = CachePolicy.DEFAULT
        policy.ttl.assert().isEqualTo(Duration.ofHours(1))
        policy.localMaxAge.assert().isEqualTo(Duration.ofMinutes(5))
        // 未配置：引擎按有没有数据源决定（有 RETRY，没有 THROW）
        policy.invalidateFailure.assert().isNull()
        policy.toString().assert().contains("ttl=PT1H")
    }

    @Test
    fun valueTtlAppliesJitterWithinBounds() {
        val policy = CachePolicy(ttl = Duration.ofSeconds(10), ttlJitter = Duration.ofSeconds(2))
        repeat(200) {
            requireNotNull(policy.valueTtl()).toMillis().assert().isBetween(8_000, 12_000)
        }
        CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO
        ).valueTtl().assert().isEqualTo(Duration.ofSeconds(10))
        CachePolicy(ttl = null).valueTtl().assert().isNull()
    }

    @Test
    fun jitterNeverProducesNonPositiveTtl() {
        val policy = CachePolicy(ttl = Duration.ofMillis(1), ttlJitter = Duration.ofSeconds(10))
        repeat(200) {
            requireNotNull(policy.valueTtl()).toMillis().assert().isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun builderRoundTrips() {
        val lease = LeasePolicy(Duration.ofSeconds(1), Duration.ofMillis(10))
        val load = LoadPolicy(2, Duration.ofMillis(5))
        val local = LocalPolicy(100, 10, Duration.ofMinutes(1))
        val policy = CachePolicy.builder()
            .ttl(Duration.ofMinutes(2))
            .ttlJitter(Duration.ofSeconds(3))
            .absentTtl(Duration.ofSeconds(4))
            .localMaxAge(Duration.ofMinutes(1))
            .degradedLocalMaxAge(Duration.ofSeconds(5))
            .refreshWindow(Duration.ofSeconds(30))
            .lease(lease)
            .load(load)
            .local(local)
            .invalidateFailure(InvalidateFailureMode.THROW)
            .readFailure(ReadFailureMode.THROW)
            .foreignFormat(ForeignFormatMode.FAIL)
            .build()
        val copy = policy.toBuilder().build()
        copy.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
        copy.toString().assert().contains("foreignFormat=FAIL")
        CachePolicy.DEFAULT.foreignFormat.assert().isNull()
        copy.ttl.assert().isEqualTo(Duration.ofMinutes(2))
        copy.ttlJitter.assert().isEqualTo(Duration.ofSeconds(3))
        copy.absentTtl.assert().isEqualTo(Duration.ofSeconds(4))
        copy.localMaxAge.assert().isEqualTo(Duration.ofMinutes(1))
        copy.degradedLocalMaxAge.assert().isEqualTo(Duration.ofSeconds(5))
        copy.refreshWindow.assert().isEqualTo(Duration.ofSeconds(30))
        copy.lease.assert().isEqualTo(lease)
        copy.load.assert().isEqualTo(load)
        copy.local.assert().isEqualTo(local)
        copy.invalidateFailure.assert().isEqualTo(InvalidateFailureMode.THROW)
        copy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        CachePolicy.DEFAULT.readFailure.assert().isEqualTo(ReadFailureMode.DEGRADE)
    }

    /**
     * Kotlin DSL 与 Java 调用方读写的构建器字段从策略初始化。
     */
    @Test
    fun builderStartsFromThePolicy() {
        val policy = CachePolicy(ttl = null, foreignFormat = ForeignFormatMode.RECLAIM)
        val builder = policy.toBuilder()
        builder.ttl.assert().isNull()
        builder.ttlJitter.assert().isEqualTo(policy.ttlJitter)
        builder.absentTtl.assert().isEqualTo(policy.absentTtl)
        builder.localMaxAge.assert().isEqualTo(policy.localMaxAge)
        builder.degradedLocalMaxAge.assert().isEqualTo(policy.degradedLocalMaxAge)
        builder.refreshWindow.assert().isNull()
        builder.lease.assert().isEqualTo(policy.lease)
        builder.load.assert().isEqualTo(policy.load)
        builder.local.assert().isEqualTo(policy.local)
        builder.invalidateFailure.assert().isEqualTo(policy.invalidateFailure)
        builder.readFailure.assert().isEqualTo(policy.readFailure)
        builder.foreignFormat.assert().isEqualTo(ForeignFormatMode.RECLAIM)
        builder.foreignFormat = null
        builder.build().foreignFormat.assert().isNull()
    }

    /**
     * 6.4 及以前编译的调用方使用的两个构造函数（11 个参数的版本与 Kotlin 默认参数的合成版本）仍然存在，`foreignFormat` 为未设置。
     */
    @Test
    fun constructorsOf64RemainBinaryCompatible() {
        val types = arrayOf(
            Duration::class.java, Duration::class.java, Duration::class.java, Duration::class.java, Duration::class.java,
            Duration::class.java, LeasePolicy::class.java, LoadPolicy::class.java, LocalPolicy::class.java,
            InvalidateFailureMode::class.java, ReadFailureMode::class.java
        )
        val full = CachePolicy::class.java.getConstructor(*types).newInstance(
            Duration.ofMinutes(2), Duration.ZERO, Duration.ofSeconds(4), Duration.ofMinutes(1), Duration.ofSeconds(5),
            null, LeasePolicy(), LoadPolicy(), LocalPolicy(), InvalidateFailureMode.THROW, ReadFailureMode.THROW
        )
        full.ttl.assert().isEqualTo(Duration.ofMinutes(2))
        full.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        full.foreignFormat.assert().isNull()

        // 旧调用方的 CachePolicy(ttl = null)：只传第一个参数，其余由默认值掩码（第 0 位以外全部置位）补齐
        val defaults = CachePolicy::class.java.getConstructor(
            *types,
            Int::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        ).newInstance(*arrayOfNulls<Any>(types.size), 0b111_1111_1110, null)
        defaults.ttl.assert().isNull()
        defaults.ttlJitter.assert().isEqualTo(CachePolicy.DEFAULT_TTL_JITTER)
        defaults.readFailure.assert().isEqualTo(ReadFailureMode.DEGRADE)
        defaults.foreignFormat.assert().isNull()
        // 6.9 起 invalidateFailure 的默认值也是未设置（参数类型在 JVM 上不变，旧调用方无需重新编译）
        defaults.invalidateFailure.assert().isNull()

        // 旧调用方的 CachePolicy()：全部参数取默认值
        val allDefaults = CachePolicy::class.java.getConstructor(
            *types,
            Int::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        ).newInstance(*arrayOfNulls<Any>(types.size), 0b111_1111_1111, null)
        allDefaults.toString().assert().isEqualTo(CachePolicy.DEFAULT.toString())
    }

    @Test
    fun rejectsInvalidDurations() {
        fun fails(block: () -> Any) = runCatching(
            block
        ).exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        fails { CachePolicy(ttl = Duration.ZERO) }
        fails { CachePolicy(ttlJitter = Duration.ofSeconds(-1)) }
        fails { CachePolicy(absentTtl = Duration.ZERO) }
        fails { CachePolicy(localMaxAge = Duration.ofSeconds(-1)) }
        fails { CachePolicy(degradedLocalMaxAge = Duration.ZERO) }
        fails { CachePolicy(refreshWindow = Duration.ZERO) }
        fails { CachePolicy(ttl = Duration.ofSeconds(10), refreshWindow = Duration.ofSeconds(10)) }
        fails { CachePolicy(localMaxAge = Duration.ofSeconds(10), refreshWindow = Duration.ofSeconds(10)) }
        fails { LeasePolicy(ttl = Duration.ZERO) }
        fails { LeasePolicy(wait = Duration.ofSeconds(-1)) }
        fails { LoadPolicy(maxConcurrent = -1) }
        fails { LoadPolicy(acquireTimeout = Duration.ofSeconds(-1)) }
        fails { LoadPolicy(asyncTimeout = Duration.ZERO) }
        fails { LocalPolicy(maximumSize = 0) }
        fails { LocalPolicy(initialCapacity = -1) }
        fails { LocalPolicy(expireAfterAccess = Duration.ZERO) }
    }

    @Test
    fun foreverTtlAllowsAnyRefreshWindowBelowLocalMaxAge() {
        CachePolicy(
            ttl = null,
            refreshWindow = Duration.ofSeconds(1)
        ).refreshWindow.assert().isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun rejectsDurationsBeyondTheCap() {
        val huge = Duration.ofSeconds(Long.MAX_VALUE / 1000)
        runCatching {
            CachePolicy(
                ttl = huge
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            CachePolicy(
                localMaxAge = huge
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            CachePolicy(
                absentTtl = huge
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            CachePolicy(
                ttlJitter = huge
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            LeasePolicy(
                wait = huge
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            LoadPolicy(
                acquireTimeout = huge
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        CachePolicy(ttl = MAX_DURATION, localMaxAge = MAX_DURATION).ttl.assert().isEqualTo(MAX_DURATION)
    }

    @Test
    fun boundedTtlClampsIntoRange() {
        Duration.ofSeconds(Long.MAX_VALUE).boundedTtl().assert().isEqualTo(MAX_DURATION)
        Duration.ZERO.boundedTtl().assert().isEqualTo(Duration.ofMillis(1))
        Duration.ofSeconds(-5).boundedTtl().assert().isEqualTo(Duration.ofMillis(1))
        Duration.ofSeconds(5).boundedTtl().assert().isEqualTo(Duration.ofSeconds(5))
    }

    @Test
    fun jitterIsCappedAtHalfTheTtl() {
        // 默认 60 秒抖动作用于 30 秒寿命：实际幅度只有 15 秒，不会产生 1 毫秒寿命
        val policy = CachePolicy(ttl = Duration.ofSeconds(30), ttlJitter = Duration.ofMinutes(1))
        repeat(500) {
            requireNotNull(policy.valueTtl()).toMillis().assert().isBetween(15_000, 45_000)
        }
    }

    @Test
    fun refreshWindowMustLeaveRoomForJitter() {
        runCatching {
            CachePolicy(
                ttl = Duration.ofSeconds(10),
                ttlJitter = Duration.ofSeconds(4),
                refreshWindow = Duration.ofSeconds(7)
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ofSeconds(4),
            refreshWindow = Duration.ofSeconds(5)
        )
            .refreshWindow.assert().isEqualTo(Duration.ofSeconds(5))
    }
}
