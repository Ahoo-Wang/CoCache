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

import io.github.oshai.kotlinlogging.KLogger
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.TtlFunction
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个标量引擎写入 L1 的寿命（RFC 0005 §4.8），由 [CoherentEngine] 持有：回源填充（[fillTtl]）、无条件写入的默认寿命（[putTtl]）、
 * 按版本写入的默认寿命（[versionedTtl]），以及按调用给出的寿命的上限（[perCall]）。
 *
 * 按调用给出的寿命是 `ttlOf` 的结果与写入时显式给出的 `ttl`：它们优先于策略寿命、不加抖动，且一律经过 [perCall]。上限
 * （[CachePolicy.ttlCeiling]，`cocache.overrides.cache.ttl`，S8）按类型生效（D2，[clamps]）：派生副本的寿命是陈旧度的上界，钳制它；
 * 权威副本的寿命就是数据的寿命，从不钳制。策略自身的寿命（含抖动）不经过上限。
 *
 * @param ttlOf spec 的按条目寿命；`null` 时用策略寿命
 * @param clamps 是否按 [CachePolicy.ttlCeiling] 钳制按调用给出的寿命（只有派生副本）
 * @param log 引擎的日志（告警沿用引擎的日志名）
 */
@Suppress("LongParameterList")
internal class TtlRules<K : Any, V : Any>(
    private val name: String,
    private val policy: CachePolicy,
    private val ttlOf: TtlFunction<K, V>?,
    private val format: ValueFormat<V>,
    private val metrics: CacheMetrics,
    private val log: KLogger,
    clamps: Boolean,
) {
    private val ceiling: Duration? = if (clamps) policy.ttlCeiling else null
    private val clampWarned = AtomicBoolean(false)

    /**
     * 回源结果写入 L1（以及绕过 L1 时 L2 副本）的寿命，已加界（[boundedTtl]）：负缓存为 `absentTtl`；墓碑为墓碑寿命；
     * 其余同 [putTtl]。
     */
    fun fillTtl(key: K, value: V?): Duration? {
        if (value == null) {
            return policy.absentTtl
        }
        if (format.isTombstone(value)) {
            return policy.tombstoneTtl()?.boundedTtl()
        }
        return putTtl(key, value)?.boundedTtl()
    }

    /**
     * 无条件写入（`put`、`putAll`、`putIfMatch`）的默认寿命，未加界（写入时加界）：`ttlOf` 优先（[perCall]），否则策略寿命加抖动；
     * `null` 为永不过期。
     */
    fun putTtl(key: K, value: V): Duration? = ttlOf?.ttlOf(key, value)?.let(::perCall) ?: policy.valueTtl()

    /**
     * 按版本写入的默认寿命，未加界：墓碑（[value] 为 `null`）为墓碑寿命（`tombstoneTtl`，未设置时同值），值同 [putTtl]。
     */
    fun versionedTtl(key: K, value: V?): Duration? = if (value == null) policy.tombstoneTtl() else putTtl(key, value)

    /**
     * 按调用给出的寿命（`ttlOf` 的结果、写入时显式给出的 `ttl`）不超过上限：超过时钳制到它，计入 `cocache.ttl.clamped`，
     * 每个缓存第一次告警。没有上限（未设置，或这个类型不钳制）时原样返回。
     */
    fun perCall(ttl: Duration): Duration {
        val ceiling = ceiling ?: return ttl
        if (ttl <= ceiling) {
            return ttl
        }
        metrics.ttlClamped()
        if (clampWarned.compareAndSet(false, true)) {
            log.warn {
                "Cache[$name] - per-call ttl[$ttl] exceeds the ceiling[$ceiling] from cocache.overrides and was clamped " +
                    "(further clamps are counted in cocache.ttl.clamped)."
            }
        }
        return ceiling
    }
}
