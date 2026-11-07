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

import me.ahoo.cocache.core.policy.PolicyDraft
import me.ahoo.cocache.core.policy.PolicyField

/**
 * 一个缓存每个策略字段的生效值及其来源层（RFC 0005 §4.8“生效值与来源”），以及全局层中被跳过的条目。
 * 启动日志与端点（`GET /actuator/cocache/{name}`）输出它；未分层的缓存没有报告（[CacheAdmin.policyReport] 为 `null`）。
 */
class PolicyReport(val entries: List<Entry>, val skipped: List<Skipped> = emptyList()) {
    /**
     * @param field 配置键（如 `local-max-age`）
     * @param value 生效值（哨兵写作 `forever`、`off`、`unlimited`）
     * @param detail 补充说明，如锁定者、被收紧之前的值、夹紧的原因
     */
    data class Entry(val field: String, val value: String, val source: Source, val detail: String? = null)

    /**
     * 被跳过的配置条目：键、所在的层与原因（不适用、该层不允许、被锁定）。
     */
    data class Skipped(val key: String, val layer: String, val reason: String)

    enum class Source {
        /**
         * 类型默认值（`DefaultPolicies`，D7）。
         */
        TYPE_DEFAULT,

        /**
         * `cocache.defaults.*`（低于声明）。
         */
        DEFAULTS,
        DECLARATION,
        CUSTOMIZER,

        /**
         * 被 `cocache.overrides.*` 收紧。
         */
        OVERRIDES,

        /**
         * `cocache.caches.[name].*`。
         */
        CACHES,

        /**
         * 全局层引起的冲突，被夹紧（校验连锁，U10）。
         */
        CLAMPED,

        /**
         * 被库锁定或约束（[Locks]）。
         */
        LOCKED,

        /**
         * 经 `cocache.caches.[name].unlock` 有意覆盖了锁定。
         */
        UNLOCKED,
    }

    fun entry(field: PolicyField<*>): Entry? = entries.firstOrNull { it.field == field.key }

    override fun toString(): String = "PolicyReport(entries=$entries, skipped=$skipped)"

    companion object {
        /**
         * 只有声明一层时的报告：声明写出的字段为 [Source.DECLARATION]，其余为 [Source.TYPE_DEFAULT]，被锁定、固定或约束改变的为 [Source.LOCKED]。
         */
        internal fun ofDeclaration(
            spec: Spec<*, *>,
            draft: PolicyDraft,
            fields: List<PolicyField<*>>,
            locks: Locks,
            constrained: Set<PolicyField<*>>
        ): PolicyReport = PolicyReport(
            fields.map { field ->
                val value = field.describe(draft)
                when {
                    locks.isPinned(field) -> Entry(field.key, value, Source.LOCKED, "pinned by ${locks.by}")
                    field in constrained -> Entry(field.key, value, Source.LOCKED, "constrained by ${locks.by}")
                    locks.isLocked(field) -> Entry(field.key, value, Source.LOCKED, "locked by ${locks.by}")
                    field in spec.declaredFields -> Entry(field.key, value, Source.DECLARATION)
                    else -> Entry(field.key, value, Source.TYPE_DEFAULT)
                }
            }
        )
    }
}

/**
 * 装配层的结果：生效声明与它的报告（[CacheLayers]）。
 */
class Layered<S : Spec<*, *>>(val spec: S, val report: PolicyReport)
