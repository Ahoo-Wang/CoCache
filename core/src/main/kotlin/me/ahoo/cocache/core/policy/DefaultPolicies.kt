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

import java.time.Duration

/**
 * 每种 spec 的默认策略（RFC 0005 §4.8，S23，D7）：spec 的构建器以对应的常量为起点，端点把来自这里的值标注为“类型默认值”。
 *
 * 各类型分开：库常从默认策略派生自己的策略，共用一个常量会把派生缓存的 1 h `ttl` 带进 `Store`，即误分类中最严重的一种。
 * - 派生副本（[CACHE]、[VERSIONED_CACHE]）：`ttl` 1 h（抖动 1 min）、`localMaxAge` 5 min、`absentTtl` 1 min；
 *   带版本的墓碑与 `E` 默认与值同寿命。
 * - 权威副本（[STORE]、[VERSIONED_STORE]、[SET_STORE]）：L1 是唯一副本，`ttl` 永不过期；L2 的陈旧没有回源可以纠正，
 *   `localMaxAge` 30 s、`absentTtl` 10 s；[VERSIONED_STORE] 的墓碑寿命 1 天（必须有限，要覆盖重复投递的时间窗口）。
 */
object DefaultPolicies {
    @JvmField
    val CACHE: CachePolicy = CachePolicy()

    @JvmField
    val VERSIONED_CACHE: CachePolicy = CachePolicy()

    @JvmField
    val STORE: CachePolicy = CachePolicy(
        ttl = null,
        localMaxAge = Duration.ofSeconds(30),
        absentTtl = Duration.ofSeconds(10),
    )

    @JvmField
    val VERSIONED_STORE: CachePolicy = CachePolicy(
        ttl = null,
        localMaxAge = Duration.ofSeconds(30),
        absentTtl = Duration.ofSeconds(10),
        tombstoneTtl = Duration.ofDays(1),
    )

    @JvmField
    val SET_STORE: CachePolicy = CachePolicy(
        ttl = null,
        localMaxAge = Duration.ofSeconds(30),
        absentTtl = Duration.ofSeconds(10),
    )
}
