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
import me.ahoo.cocache.core.policy.UnknownReadMode

/**
 * [CoCacheFactory] 的装配层（RFC 0005 §4.8）：把声明与库的锁定（[Locks]）变成生效声明，并报告每个字段的生效值与来源。
 * 每种 spec 一个入口。
 *
 * starter 以配置文件与定制器实现它：`defaults` → 声明 → 定制器 → `overrides` → `caches.[name]` 逐字段合并，适用性、锁定与
 * 校验连锁都在这里。默认实现（[NONE]）只有声明一层：执行 [Locks] 的约束，报告声明写出的字段与类型默认值。
 *
 * 实现返回的必须是同一个缓存的声明：名称不变，类型由签名保证（定制器只能修改策略字段与不决定类型的字段，U16）。
 *
 * 不经 [CoCacheFactory]、直接经 [CacheRuntime.create] 创建的缓存不经过任何一层，标注为“未分层”（[CacheAdmin.layered]，S20）。
 */
interface CacheLayers {
    fun <K : Any, V : Any> apply(spec: CacheSpec<K, V>, locks: Locks): Layered<CacheSpec<K, V>> =
        declarationOnly(spec, locks, spec.unknownRead) { draft ->
            spec.toBuilder().apply {
                policy = draft.policy.build()
                draft.unknownRead?.let { unknownRead = it }
            }.build()
        }

    fun <K : Any, V : Any> apply(spec: VersionedCacheSpec<K, V>, locks: Locks): Layered<VersionedCacheSpec<K, V>> =
        declarationOnly(spec, locks, null) { draft -> spec.toBuilder().apply { policy = draft.policy.build() }.build() }

    fun <K : Any, V : Any> apply(spec: StoreSpec<K, V>, locks: Locks): Layered<StoreSpec<K, V>> =
        declarationOnly(spec, locks, spec.unknownRead) { draft ->
            spec.toBuilder().apply {
                policy = draft.policy.build()
                draft.unknownRead?.let { unknownRead = it }
            }.build()
        }

    fun <K : Any, V : Any> apply(spec: VersionedStoreSpec<K, V>, locks: Locks): Layered<VersionedStoreSpec<K, V>> =
        declarationOnly(spec, locks, spec.unknownRead) { draft ->
            spec.toBuilder().apply {
                policy = draft.policy.build()
                draft.unknownRead?.let { unknownRead = it }
            }.build()
        }

    fun <K : Any, E : Any> apply(spec: SetStoreSpec<K, E>, locks: Locks): Layered<SetStoreSpec<K, E>> =
        declarationOnly(spec, locks, null) { draft -> spec.toBuilder().apply { policy = draft.policy.build() }.build() }

    companion object {
        /**
         * 只有声明一层：执行约束，报告声明写出的字段与类型默认值。
         */
        @JvmField
        val NONE: CacheLayers = object : CacheLayers {
            override fun toString(): String = "CacheLayers.NONE"
        }
    }
}

/**
 * 只有声明一层的装配：约束改变了字段时以 [rebuild] 重建声明。
 */
private fun <S : Spec<*, *>> declarationOnly(
    spec: S,
    locks: Locks,
    unknownRead: UnknownReadMode?,
    rebuild: (PolicyDraft) -> S
): Layered<S> {
    val draft = PolicyDraft(spec.policy.toBuilder(), unknownRead)
    val constrained = locks.constrain(draft)
    val effective = if (constrained.isEmpty()) spec else rebuild(draft)
    return Layered(
        effective,
        PolicyReport.ofDeclaration(spec, draft, PolicyField.applicableTo(spec), locks, constrained)
    )
}
