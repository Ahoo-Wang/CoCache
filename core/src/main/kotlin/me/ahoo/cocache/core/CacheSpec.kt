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
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.UnknownReadMode
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
 *
 * 必须是本地、不阻塞的判断：异步视图在调用方线程（可能是事件循环）上调用它。
 */
fun interface KeyFilter {
    fun notExist(storageKey: String): Boolean
}

/**
 * 五种 spec 的公共部分（RFC 0005 §3.1）：缓存名、存储布局与策略。spec 与运行时创建的类型一一对应：
 * [CacheSpec] → `Cache`、[VersionedCacheSpec] → `VersionedCache`、[StoreSpec] → `Store`、[VersionedStoreSpec] → `VersionedStore`、
 * [SetStoreSpec] → `SetStore`。
 *
 * 存储 key 为 `{keyPrefix}{namespace}:{keyEncoder(key)}`（[keyspace]）。命名空间的字符集为 `[A-Za-z0-9._-]+`，
 * 不含 `:`：前缀写在 [keyPrefix] 中，写进命名空间时构建失败（RFC 0005 §4.7）。同一个 namespace 的所有声明必须是同一类型。
 *
 * spec 是不可变数据：所有有状态组件都由 [CacheRuntime] 按 spec 新建，不可能被多个缓存共享。
 */
sealed interface Spec<K : Any, V : Any> {
    val name: String
    val namespace: String
    val keyPrefix: String
    val keyEncoder: KeyEncoder<K>
    val policy: CachePolicy

    /**
     * 缓存占用的 keyspace（前缀与命名空间）：存储 key 的前缀与失效频道都由它决定。构建时校验命名空间的字符集。
     */
    val keyspace: Keyspace
}

/**
 * 派生副本 `Cache` 的声明（RFC 0005 §4.1）：必须声明数据来源，[loader]、[asyncLoader] 或 [externallySourced] 恰好一个。
 *
 * - [loader] / [asyncLoader]：未命中回源；L1 读失败默认降级回源。
 * - [externallySourced]：由调用方经 `put`、Spring Cache 或调用级 loader 提供。L1 中没有时是“未填充”；
 *   不带 loader 的读取遇到 L1 读失败为 `Lookup.Unknown`，按 [unknownRead] 返回。
 *
 * 写入 L1 的值不带任何段（头部 36 字节）。默认策略 [CachePolicy.DEFAULT]（`ttl` 1 h、`localMaxAge` 5 m、`absentTtl` 1 m）。
 */
class CacheSpec<K : Any, V : Any> private constructor(builder: Builder<K, V>) : Spec<K, V> {
    override val name: String = builder.name
    override val namespace: String = builder.namespace ?: builder.name
    override val keyPrefix: String = builder.keyPrefix
    override val keyEncoder: KeyEncoder<K> = builder.keyEncoder
    val codec: Codec<V> = requireNotNull(builder.codec) { "Cache[${builder.name}] - codec is required." }
    val loader: CacheLoader<K, V>? = builder.loader

    /**
     * 异步数据源（RFC 0004）；与 [loader] 至多设置一个，引擎在同步与异步路径之间自动适配。
     */
    val asyncLoader: AsyncCacheLoader<K, V>? = builder.asyncLoader

    /**
     * 外部来源（RFC 0005 §4.1）：没有声明的数据源，由调用方提供值。与 [loader]、[asyncLoader] 互斥。
     */
    val externallySourced: Boolean = builder.externallySourced
    val ttlOf: TtlFunction<K, V>? = builder.ttlOf
    val keyFilter: KeyFilter? = builder.keyFilter
    override val policy: CachePolicy = builder.policy

    /**
     * 不带 loader 的读取遇到 L1 读失败时返回什么（[UnknownReadMode]），只能用于 [externallySourced] 的缓存。
     */
    val unknownRead: UnknownReadMode = builder.unknownRead

    init {
        requireValidName(name, namespace)
        val sources = listOf(loader != null, asyncLoader != null, externallySourced).count { it }
        require(sources == 1) {
            "Cache[$name] - declare exactly one data source: loader, asyncLoader or externallySourced(). " +
                "Data with no source to rebuild it from belongs in a Store."
        }
        require(unknownRead == UnknownReadMode.THROW || externallySourced) {
            "Cache[$name] - unknownRead applies only to reads without a source: declare it on an externallySourced() cache or a Store."
        }
    }

    override val keyspace: Keyspace = keyspaceOf("Cache", name, keyPrefix, namespace)

    override fun toString(): String =
        "CacheSpec(name='$name', namespace='$namespace', keyPrefix='$keyPrefix', externallySourced=$externallySourced, policy=$policy)"

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
        it.externallySourced = externallySourced
        it.ttlOf = ttlOf
        it.keyFilter = keyFilter
        it.policy = policy
        it.unknownRead = unknownRead
    }

    class Builder<K : Any, V : Any> internal constructor(val name: String) {
        var namespace: String? = null
        var keyPrefix: String = DEFAULT_KEY_PREFIX
        var keyEncoder: KeyEncoder<K> = KeyEncoder.ofToString()
        var codec: Codec<V>? = null
        var loader: CacheLoader<K, V>? = null
        var asyncLoader: AsyncCacheLoader<K, V>? = null
        var externallySourced: Boolean = false
        var ttlOf: TtlFunction<K, V>? = null
        var keyFilter: KeyFilter? = null
        var policy: CachePolicy = CachePolicy.DEFAULT
        var unknownRead: UnknownReadMode = UnknownReadMode.THROW

        fun namespace(namespace: String) = apply { this.namespace = namespace }
        fun keyPrefix(keyPrefix: String) = apply { this.keyPrefix = keyPrefix }
        fun keyEncoder(keyEncoder: KeyEncoder<K>) = apply { this.keyEncoder = keyEncoder }
        fun codec(codec: Codec<V>) = apply { this.codec = codec }
        fun loader(loader: CacheLoader<K, V>) = apply { this.loader = loader }
        fun asyncLoader(asyncLoader: AsyncCacheLoader<K, V>) = apply { this.asyncLoader = asyncLoader }

        /**
         * 声明为外部来源（见 [CacheSpec.externallySourced]）。
         */
        fun externallySourced() = apply { this.externallySourced = true }
        fun ttlOf(ttlOf: TtlFunction<K, V>) = apply { this.ttlOf = ttlOf }
        fun keyFilter(keyFilter: KeyFilter) = apply { this.keyFilter = keyFilter }
        fun policy(policy: CachePolicy) = apply { this.policy = policy }
        fun unknownRead(unknownRead: UnknownReadMode) = apply { this.unknownRead = unknownRead }

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
 * Kotlin DSL：`cacheSpec<String, User>("user") { codec = …; loader = CacheLoader { … } }`。
 */
fun <K : Any, V : Any> cacheSpec(name: String, block: CacheSpec.Builder<K, V>.() -> Unit): CacheSpec<K, V> {
    return CacheSpec.builder<K, V>(name).apply(block).build()
}

internal fun requireValidName(name: String, namespace: String) {
    require(name.isNotBlank()) { "name must not be blank." }
    require(namespace.isNotBlank()) { "namespace must not be blank." }
}

/**
 * 校验命名空间的字符集（[Keyspace]），失败信息带上缓存名。
 */
internal fun keyspaceOf(kind: String, name: String, keyPrefix: String, namespace: String): Keyspace {
    try {
        return Keyspace(keyPrefix, namespace)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("$kind[$name] - ${e.message}", e)
    }
}

/**
 * 权威类型（`Store`、`VersionedStore`、`SetStore`）的默认策略（RFC 0005 §4.1，D7）：`ttl` 永不过期；L2 的寿命更短
 * （`localMaxAge` 30 s、`absentTtl` 10 s）：失效丢失时没有回源可以纠正，陈旧的上界只剩 L2。
 */
internal val AUTHORITATIVE_POLICY: CachePolicy = CachePolicy(
    ttl = null,
    localMaxAge = Duration.ofSeconds(30),
    absentTtl = Duration.ofSeconds(10),
)
