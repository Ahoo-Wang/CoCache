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

package me.ahoo.cocache.core.engine

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.TtlFunction
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 写入 L1 的寿命（[TtlRules]）：`ttlOf` 与显式寿命一律经过上限；上限只在 [TtlRules.clamps] 时生效（D2：只有 `Cache`）。
 */
class TtlRulesTest {
    private val long = Duration.ofHours(1)
    private val ceiling = Duration.ofSeconds(10)
    private val policy = CachePolicy(
        ttl = Duration.ofSeconds(5),
        ttlJitter = Duration.ZERO,
        tombstoneTtl = Duration.ofSeconds(7),
        ttlCeiling = ceiling
    )

    private fun rules(clamps: Boolean, metrics: CacheMetrics = CacheMetrics()) = TtlRules(
        "rules",
        policy,
        TtlFunction<String, Versioned<String?>> { key, _ -> if (key == "long") long else null },
        VersionedFormat(StringCodec),
        metrics,
        KotlinLogging.logger {},
        clamps
    )

    @Test
    fun clampingRulesCapEveryPerCallLifetime() {
        val metrics = CacheMetrics()
        val rules = rules(clamps = true, metrics)
        rules.fillTtl("long", Versioned("v", 1)).assert().isEqualTo(ceiling)
        rules.putTtl("long", Versioned("v", 1)).assert().isEqualTo(ceiling)
        rules.versionedTtl("long", Versioned("v", 1)).assert().isEqualTo(ceiling)
        rules.perCall(long).assert().isEqualTo(ceiling)
        rules.perCall(Duration.ofSeconds(3)).assert().isEqualTo(Duration.ofSeconds(3))
        metrics.ttlClamped.assert().isEqualTo(4L)
    }

    /**
     * 不钳制的规则（`VersionedCache` 等其它类型）忽略上限：以前带版本的缓存回源时经 `ttlOf` 被钳制。
     */
    @Test
    fun nonClampingRulesIgnoreTheCeiling() {
        val metrics = CacheMetrics()
        val rules = rules(clamps = false, metrics)
        rules.fillTtl("long", Versioned("v", 1)).assert().isEqualTo(long)
        rules.putTtl("long", Versioned("v", 1)).assert().isEqualTo(long)
        rules.versionedTtl("long", Versioned("v", 1)).assert().isEqualTo(long)
        rules.perCall(long).assert().isEqualTo(long)
        metrics.ttlClamped.assert().isZero()
    }

    @Test
    fun policyLifetimesNeverPassTheCeiling() {
        val rules = rules(clamps = true)
        rules.fillTtl("k", null).assert().isEqualTo(policy.absentTtl)
        rules.fillTtl("k", Versioned(null, 1)).assert().isEqualTo(Duration.ofSeconds(7))
        rules.versionedTtl("k", null).assert().isEqualTo(Duration.ofSeconds(7))
        rules.putTtl("k", Versioned("v", 1)).assert().isEqualTo(Duration.ofSeconds(5))
    }
}
