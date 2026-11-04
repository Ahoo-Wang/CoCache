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

package me.ahoo.cocache.core

import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.PolicyField

/**
 * spec 构建器中的策略声明（字段三态，RFC 0005 §4.8，U11）：当前策略，以及声明写出了哪些字段。
 *
 * - `policy { … }` / `updatePolicy` 只把其中赋值的字段记为写出（[update]）；
 * - 整体赋值 `policy = …` 视为写出全部策略字段（[replace]）：用整体 [CachePolicy] 写的 spec，`cocache.defaults` 对它不再生效；
 * - `unknownRead` 的赋值记为写出 [PolicyField.UNKNOWN_READ]（[declare]）。
 */
internal class PolicyDeclaration(initial: CachePolicy) {
    var policy: CachePolicy = initial
        private set
    private val declared = linkedSetOf<PolicyField<*>>()
    private var whole = false

    fun replace(policy: CachePolicy) {
        this.policy = policy
        whole = true
    }

    fun update(block: CachePolicy.Builder.() -> Unit) {
        val builder = policy.toBuilder()
        builder.block()
        policy = builder.build()
        declared += builder.written
    }

    fun declare(field: PolicyField<*>) {
        declared += field
    }

    /**
     * `toBuilder` 用：恢复已有声明的策略与写出的字段，之后的修改照常记录。
     */
    fun restore(policy: CachePolicy, fields: Set<PolicyField<*>>) {
        this.policy = policy
        whole = false
        declared.clear()
        declared += fields
    }

    fun fields(): Set<PolicyField<*>> {
        if (!whole) {
            return LinkedHashSet(declared)
        }
        return LinkedHashSet(PolicyField.POLICY_FIELDS + declared)
    }
}
