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

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.core.policy.PolicyField
import java.util.function.Consumer

/**
 * 权威集合 `SetStore` 的声明（RFC 0005 §4.1）：没有数据源（按构造禁止），不允许 `unknownRead`：L1 读失败总是抛出。
 *
 * 默认策略 [DefaultPolicies.SET_STORE]：永不过期，L2 寿命更短（同 `Store`）。存储 key 与命名空间的规则同 [Spec]。
 */
class SetStoreSpec<K : Any, E : Any> private constructor(builder: Builder<K, E>) : Spec<K, Set<E>> {
    override val name: String = builder.name
    override val namespace: String = builder.namespace ?: builder.name
    override val keyPrefix: String = builder.keyPrefix
    override val keyEncoder: KeyEncoder<K> = builder.keyEncoder
    val elementCodec: Codec<E> = requireNotNull(
        builder.elementCodec
    ) { "SetStore[${builder.name}] - elementCodec is required." }
    override val policy: CachePolicy = builder.policy

    /**
     * 声明写出的策略字段（字段三态，RFC 0005 §4.8）：没有写出的字段由 `cocache.defaults` 或类型默认值决定。
     */
    override val declaredFields: Set<PolicyField<*>> = builder.declaration.fields()

    init {
        requireValidName(name, namespace)
        requireNoTtlCeiling("SetStore", name, policy)
    }

    override val keyspace: Keyspace = keyspaceOf("SetStore", name, keyPrefix, namespace)

    override fun toString(): String = "SetStoreSpec(name='$name', namespace='$namespace', keyPrefix='$keyPrefix', policy=$policy)"

    /**
     * 以本声明为基础的构建器（用于叠加定制与配置）。
     */
    fun toBuilder(): Builder<K, E> = Builder<K, E>(name).also {
        it.namespace = namespace
        it.keyPrefix = keyPrefix
        it.keyEncoder = keyEncoder
        it.elementCodec = elementCodec
        it.declaration.restore(policy, declaredFields)
    }

    class Builder<K : Any, E : Any> internal constructor(val name: String) {
        var namespace: String? = null
        var keyPrefix: String = CacheSpec.DEFAULT_KEY_PREFIX
        var keyEncoder: KeyEncoder<K> = KeyEncoder.ofToString()
        var elementCodec: Codec<E>? = null
        internal val declaration = PolicyDeclaration(DefaultPolicies.SET_STORE)

        /**
         * 整体赋值视为写出全部策略字段（`cocache.defaults` 对此缓存不再生效）；只修改部分字段用 `policy { … }` 或 [updatePolicy]。
         * 整体赋值应以 `DefaultPolicies.STORE.toBuilder()` 为起点：`CachePolicy(…)` 的构造器默认值是派生副本的（`ttl` 1 小时，S23），
         * 唯一的副本会在 1 小时后过期。
         */
        var policy: CachePolicy
            get() = declaration.policy
            set(value) {
                declaration.replace(value)
            }

        fun namespace(namespace: String) = apply { this.namespace = namespace }
        fun keyPrefix(keyPrefix: String) = apply { this.keyPrefix = keyPrefix }
        fun keyEncoder(keyEncoder: KeyEncoder<K>) = apply { this.keyEncoder = keyEncoder }
        fun elementCodec(elementCodec: Codec<E>) = apply { this.elementCodec = elementCodec }

        /**
         * 整体替换策略：应以 [DefaultPolicies.SET_STORE]`.toBuilder()` 为起点，或用 [updatePolicy] 在当前策略上修改。
         */
        fun policy(policy: CachePolicy) = apply { this.policy = policy }

        /**
         * 在当前策略（默认 [DefaultPolicies.SET_STORE]）基础上修改。
         */
        fun policy(
            block: CachePolicy.Builder.() -> Unit
        ) = apply { declaration.update(block) }

        /**
         * 在当前策略（默认 [DefaultPolicies.SET_STORE]）基础上修改（Java 友好）。
         */
        fun updatePolicy(customizer: Consumer<CachePolicy.Builder>) = apply {
            declaration.update { customizer.accept(this) }
        }

        fun build(): SetStoreSpec<K, E> = SetStoreSpec(this)
    }

    companion object {

        @JvmStatic
        fun <K : Any, E : Any> builder(name: String): Builder<K, E> = Builder(name)
    }
}

/**
 * Kotlin DSL：`setStoreSpec<String, String>("index") { elementCodec = … }`。
 */
fun <K : Any, E : Any> setStoreSpec(name: String, block: SetStoreSpec.Builder<K, E>.() -> Unit): SetStoreSpec<K, E> =
    SetStoreSpec.builder<K, E>(name).apply(block).build()
