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

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.core.policy.CachePolicy
import java.time.Duration

/**
 * 业务 key → 存储 key 的编码（不含前缀与命名空间）。
 */
fun interface KeyEncoder<K> {
    fun encode(key: K): String
}

/**
 * 按 key 与值决定寿命：返回非 `null` 时优先于策略寿命且不加抖动；返回 `null` 时按策略。
 */
fun interface TtlFunction<K, V> {
    fun ttlOf(key: K, value: V): Duration?
}

/**
 * 存储 key 过滤器（如布隆过滤器）：确定不存在的 key 直接返回不存在，不访问 L1 与数据源，也不写入任何层。
 */
fun interface KeyFilter {
    fun notExist(storageKey: String): Boolean
}

/**
 * 缓存声明（不可变数据）。所有有状态组件都由 [CacheRuntime] 按声明新建，不可能被多个缓存共享。
 *
 * 存储 key 为 `{keyPrefix}{namespace}:{keyEncoder(key)}`。
 */
class CacheSpec<K : Any, V : Any> private constructor(builder: Builder<K, V>) {
    val name: String = builder.name
    val namespace: String = builder.namespace ?: builder.name
    val keyPrefix: String = builder.keyPrefix
    val keyEncoder: KeyEncoder<K> = builder.keyEncoder
    val codec: Codec<V> = requireNotNull(builder.codec) { "Cache[${builder.name}] - codec is required." }
    val loader: CacheLoader<K, V>? = builder.loader
    val ttlOf: TtlFunction<K, V>? = builder.ttlOf
    val keyFilter: KeyFilter? = builder.keyFilter
    val policy: CachePolicy = builder.policy

    init {
        requireValidName(name, namespace)
    }

    override fun toString(): String = "CacheSpec(name='$name', namespace='$namespace', keyPrefix='$keyPrefix', policy=$policy)"

    /**
     * 以本声明为基础的构建器（用于叠加定制与配置）。
     */
    fun toBuilder(): Builder<K, V> = Builder<K, V>(name).also {
        it.namespace = namespace
        it.keyPrefix = keyPrefix
        it.keyEncoder = keyEncoder
        it.codec = codec
        it.loader = loader
        it.ttlOf = ttlOf
        it.keyFilter = keyFilter
        it.policy = policy
    }

    class Builder<K : Any, V : Any> internal constructor(val name: String) {
        var namespace: String? = null
        var keyPrefix: String = DEFAULT_KEY_PREFIX
        var keyEncoder: KeyEncoder<K> = KeyEncoder { it.toString() }
        var codec: Codec<V>? = null
        var loader: CacheLoader<K, V>? = null
        var ttlOf: TtlFunction<K, V>? = null
        var keyFilter: KeyFilter? = null
        var policy: CachePolicy = CachePolicy.DEFAULT

        fun namespace(namespace: String) = apply { this.namespace = namespace }
        fun keyPrefix(keyPrefix: String) = apply { this.keyPrefix = keyPrefix }
        fun keyEncoder(keyEncoder: KeyEncoder<K>) = apply { this.keyEncoder = keyEncoder }
        fun codec(codec: Codec<V>) = apply { this.codec = codec }
        fun loader(loader: CacheLoader<K, V>) = apply { this.loader = loader }
        fun ttlOf(ttlOf: TtlFunction<K, V>) = apply { this.ttlOf = ttlOf }
        fun keyFilter(keyFilter: KeyFilter) = apply { this.keyFilter = keyFilter }
        fun policy(policy: CachePolicy) = apply { this.policy = policy }

        /**
         * 在当前策略基础上修改。
         */
        fun policy(
            block: CachePolicy.Builder.() -> Unit
        ) = apply { this.policy = policy.toBuilder().apply(block).build() }

        fun build(): CacheSpec<K, V> = CacheSpec(this)
    }

    companion object {
        const val DEFAULT_KEY_PREFIX = "cocache:"

        @JvmStatic
        fun <K : Any, V : Any> builder(name: String): Builder<K, V> = Builder(name)
    }
}

/**
 * L1 是唯一副本的集合缓存声明：没有数据源（按构造禁止 loader）。默认永不过期。
 */
class SetCacheSpec<K : Any, E : Any> private constructor(builder: Builder<K, E>) {
    val name: String = builder.name
    val namespace: String = builder.namespace ?: builder.name
    val keyPrefix: String = builder.keyPrefix
    val keyEncoder: KeyEncoder<K> = builder.keyEncoder
    val elementCodec: Codec<E> = requireNotNull(
        builder.elementCodec
    ) { "SetCache[${builder.name}] - elementCodec is required." }
    val policy: CachePolicy = builder.policy

    init {
        requireValidName(name, namespace)
    }

    override fun toString(): String = "SetCacheSpec(name='$name', namespace='$namespace', keyPrefix='$keyPrefix', policy=$policy)"

    /**
     * 以本声明为基础的构建器（用于叠加定制与配置）。
     */
    fun toBuilder(): Builder<K, E> = Builder<K, E>(name).also {
        it.namespace = namespace
        it.keyPrefix = keyPrefix
        it.keyEncoder = keyEncoder
        it.elementCodec = elementCodec
        it.policy = policy
    }

    class Builder<K : Any, E : Any> internal constructor(val name: String) {
        var namespace: String? = null
        var keyPrefix: String = CacheSpec.DEFAULT_KEY_PREFIX
        var keyEncoder: KeyEncoder<K> = KeyEncoder { it.toString() }
        var elementCodec: Codec<E>? = null
        var policy: CachePolicy = DEFAULT_POLICY

        fun namespace(namespace: String) = apply { this.namespace = namespace }
        fun keyPrefix(keyPrefix: String) = apply { this.keyPrefix = keyPrefix }
        fun keyEncoder(keyEncoder: KeyEncoder<K>) = apply { this.keyEncoder = keyEncoder }
        fun elementCodec(elementCodec: Codec<E>) = apply { this.elementCodec = elementCodec }
        fun policy(policy: CachePolicy) = apply { this.policy = policy }
        fun policy(
            block: CachePolicy.Builder.() -> Unit
        ) = apply { this.policy = policy.toBuilder().apply(block).build() }

        fun build(): SetCacheSpec<K, E> = SetCacheSpec(this)
    }

    companion object {
        /**
         * L1 是唯一副本，默认永不过期。
         */
        @JvmField
        val DEFAULT_POLICY: CachePolicy = CachePolicy(ttl = null)

        @JvmStatic
        fun <K : Any, E : Any> builder(name: String): Builder<K, E> = Builder(name)
    }
}

/**
 * Kotlin DSL：`cacheSpec<String, User>("user") { codec = …; loader = CacheLoader { … } }`。
 */
fun <K : Any, V : Any> cacheSpec(name: String, block: CacheSpec.Builder<K, V>.() -> Unit): CacheSpec<K, V> {
    return CacheSpec.builder<K, V>(name).apply(block).build()
}

/**
 * Kotlin DSL：`setCacheSpec<String, String>("index") { elementCodec = … }`。
 */
fun <K : Any, E : Any> setCacheSpec(name: String, block: SetCacheSpec.Builder<K, E>.() -> Unit): SetCacheSpec<K, E> {
    return SetCacheSpec.builder<K, E>(name).apply(block).build()
}

private fun requireValidName(name: String, namespace: String) {
    require(name.isNotBlank()) { "name must not be blank." }
    require(namespace.isNotBlank()) { "namespace must not be blank." }
}
