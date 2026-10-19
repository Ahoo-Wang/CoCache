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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.core.policy.CachePolicy
import java.time.Duration
import java.util.function.Consumer

/**
 * 业务 key → 存储 key 的编码（不含前缀与命名空间）。
 */
fun interface KeyEncoder<K> {
    fun encode(key: K): String

    companion object {
        /**
         * 默认编码：`toString()`。每种 key 类型首次编码时检查它是否重写了 `toString()`：
         * 没有重写（`Object` 默认的“类名@哈希”）时每个实例都映射到不同的存储 key，缓存永远不命中，因此直接拒绝。
         */
        @JvmStatic
        fun <K : Any> ofToString(): KeyEncoder<K> = ToStringKeyEncoder()
    }
}

/**
 * [KeyEncoder.ofToString] 的实现。命中路径只多一次引用比较：`String` 直接通过，其它类型与上次校验过的类型比较，
 * 不同时才查 [DECLARES_TO_STRING]（按类型缓存的反射结果）。
 *
 * 只能识别“没有重写 toString()”这一种错误：重写了但对不同实例返回相同文本的类型（如以类方式编译的 Kotlin lambda）无法识别。
 */
private class ToStringKeyEncoder<K : Any> : KeyEncoder<K> {
    // 良性竞争：最坏情况是重复查一次按类型缓存的结果
    private var verified: Class<*>? = null

    override fun encode(key: K): String {
        val type = key.javaClass
        if (type !== String::class.java && type !== verified) {
            require(DECLARES_TO_STRING.get(type)) {
                "Key type [${type.name}] does not override toString(): every instance would map to a different storage key " +
                    "and the cache would never hit. Use a data class / record, override toString(), or provide a KeyEncoder."
            }
            // 只记住第一个通过校验的类型：混合类型的 key 不反复写这个字段（避免缓存行在核间来回）；其它类型走 ClassValue
            if (verified == null) {
                verified = type
            }
        }
        return key.toString()
    }

    private companion object {
        val DECLARES_TO_STRING: ClassValue<Boolean> = object : ClassValue<Boolean>() {
            override fun computeValue(
                type: Class<*>
            ): Boolean = type.getMethod("toString").declaringClass != Any::class.java
        }
    }
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

    /**
     * 异步数据源（RFC 0004）；与 [loader] 至多设置一个，引擎在同步与异步路径之间自动适配。
     */
    val asyncLoader: AsyncCacheLoader<K, V>? = builder.asyncLoader
    val ttlOf: TtlFunction<K, V>? = builder.ttlOf
    val keyFilter: KeyFilter? = builder.keyFilter
    val policy: CachePolicy = builder.policy

    init {
        requireValidName(name, namespace)
        require(loader == null || asyncLoader == null) { "Cache[$name] - set either loader or asyncLoader, not both." }
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
        it.asyncLoader = asyncLoader
        it.ttlOf = ttlOf
        it.keyFilter = keyFilter
        it.policy = policy
    }

    class Builder<K : Any, V : Any> internal constructor(val name: String) {
        var namespace: String? = null
        var keyPrefix: String = DEFAULT_KEY_PREFIX
        var keyEncoder: KeyEncoder<K> = KeyEncoder.ofToString()
        var codec: Codec<V>? = null
        var loader: CacheLoader<K, V>? = null
        var asyncLoader: AsyncCacheLoader<K, V>? = null
        var ttlOf: TtlFunction<K, V>? = null
        var keyFilter: KeyFilter? = null
        var policy: CachePolicy = CachePolicy.DEFAULT

        fun namespace(namespace: String) = apply { this.namespace = namespace }
        fun keyPrefix(keyPrefix: String) = apply { this.keyPrefix = keyPrefix }
        fun keyEncoder(keyEncoder: KeyEncoder<K>) = apply { this.keyEncoder = keyEncoder }
        fun codec(codec: Codec<V>) = apply { this.codec = codec }
        fun loader(loader: CacheLoader<K, V>) = apply { this.loader = loader }
        fun asyncLoader(asyncLoader: AsyncCacheLoader<K, V>) = apply { this.asyncLoader = asyncLoader }
        fun ttlOf(ttlOf: TtlFunction<K, V>) = apply { this.ttlOf = ttlOf }
        fun keyFilter(keyFilter: KeyFilter) = apply { this.keyFilter = keyFilter }
        fun policy(policy: CachePolicy) = apply { this.policy = policy }

        /**
         * 在当前策略基础上修改。
         */
        fun policy(
            block: CachePolicy.Builder.() -> Unit
        ) = apply { this.policy = policy.toBuilder().apply(block).build() }

        /**
         * 在当前策略基础上修改（Java 友好）。
         */
        fun updatePolicy(customizer: Consumer<CachePolicy.Builder>) = apply {
            this.policy = policy.toBuilder().also { customizer.accept(it) }.build()
        }

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
        var keyEncoder: KeyEncoder<K> = KeyEncoder.ofToString()
        var elementCodec: Codec<E>? = null
        var policy: CachePolicy = DEFAULT_POLICY

        fun namespace(namespace: String) = apply { this.namespace = namespace }
        fun keyPrefix(keyPrefix: String) = apply { this.keyPrefix = keyPrefix }
        fun keyEncoder(keyEncoder: KeyEncoder<K>) = apply { this.keyEncoder = keyEncoder }
        fun elementCodec(elementCodec: Codec<E>) = apply { this.elementCodec = elementCodec }

        /**
         * 整体替换策略。注意 [CachePolicy.DEFAULT] 的 `ttl` 是 1 小时：集合缓存的 L1 是唯一副本，
         * 应以 [DEFAULT_POLICY]`.toBuilder()` 为起点，或用 [updatePolicy] 在当前策略上修改。
         */
        fun policy(policy: CachePolicy) = apply { this.policy = policy }
        fun policy(
            block: CachePolicy.Builder.() -> Unit
        ) = apply { this.policy = policy.toBuilder().apply(block).build() }

        /**
         * 在当前策略（默认 [DEFAULT_POLICY]，永不过期）基础上修改（Java 友好）。
         */
        fun updatePolicy(customizer: Consumer<CachePolicy.Builder>) = apply {
            this.policy = policy.toBuilder().also { customizer.accept(it) }.build()
        }

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
