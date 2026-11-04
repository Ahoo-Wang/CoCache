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
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.policy.CachePolicy
import java.util.function.Consumer

/**
 * 派生的带版本副本 `VersionedCache` 的声明（RFC 0005 §4.1、§4.2）：必须有带版本的数据源，[versionedLoader] 或
 * [versionedAsyncLoader] 恰好一个。没有数据源的带版本数据是 `VersionedStore`（[VersionedStoreSpec]）。
 *
 * 数据源返回值与它的版本，回源写入 L1 时一起保存。以 `Versioned(null, n)` 表达“在版本 n 时已删除”（如已删除的聚合），写入与
 * `invalidateIfNewer(key, n)` 相同的墓碑；返回 `null` 表示数据源中从来没有这个 key，写入没有版本的负缓存，它落在带版本的内容上时
 * 被拒绝（K16）。
 *
 * 照常提前刷新（`refreshWindow`）：回源得到的版本更旧时 L1 拒绝写回，刷新路径保留现有副本并退避（RFC 0005 §4.7）。
 * 默认策略 [CachePolicy.DEFAULT]；墓碑与 `E` 的寿命为 `tombstoneTtl`（未设置时同值的寿命）。
 */
class VersionedCacheSpec<K : Any, V : Any> private constructor(builder: Builder<K, V>) : Spec<K, V> {
    override val name: String = builder.name
    override val namespace: String = builder.namespace ?: builder.name
    override val keyPrefix: String = builder.keyPrefix
    override val keyEncoder: KeyEncoder<K> = builder.keyEncoder
    val codec: Codec<V> = requireNotNull(builder.codec) { "VersionedCache[${builder.name}] - codec is required." }
    val versionedLoader: CacheLoader<K, out Versioned<V?>>? = builder.versionedLoader
    val versionedAsyncLoader: AsyncCacheLoader<K, out Versioned<V?>>? = builder.versionedAsyncLoader
    val ttlOf: TtlFunction<K, V>? = builder.ttlOf
    val keyFilter: KeyFilter? = builder.keyFilter
    override val policy: CachePolicy = builder.policy

    init {
        requireValidName(name, namespace)
        require((versionedLoader != null) != (versionedAsyncLoader != null)) {
            "VersionedCache[$name] - declare exactly one versioned data source: versionedLoader or versionedAsyncLoader. " +
                "Versioned data with no source to rebuild it from belongs in a VersionedStore."
        }
    }

    override val keyspace: Keyspace = keyspaceOf("VersionedCache", name, keyPrefix, namespace)

    override fun toString(): String =
        "VersionedCacheSpec(name='$name', namespace='$namespace', keyPrefix='$keyPrefix', policy=$policy)"

    /**
     * 以本声明为基础的构建器（用于叠加定制与配置）。
     */
    fun toBuilder(): Builder<K, V> = Builder<K, V>(name).also {
        it.namespace = namespace
        it.keyPrefix = keyPrefix
        it.keyEncoder = keyEncoder
        it.codec = codec
        it.versionedLoader = versionedLoader
        it.versionedAsyncLoader = versionedAsyncLoader
        it.ttlOf = ttlOf
        it.keyFilter = keyFilter
        it.policy = policy
    }

    class Builder<K : Any, V : Any> internal constructor(val name: String) {
        var namespace: String? = null
        var keyPrefix: String = CacheSpec.DEFAULT_KEY_PREFIX
        var keyEncoder: KeyEncoder<K> = KeyEncoder.ofToString()
        var codec: Codec<V>? = null
        var versionedLoader: CacheLoader<K, out Versioned<V?>>? = null
        var versionedAsyncLoader: AsyncCacheLoader<K, out Versioned<V?>>? = null
        var ttlOf: TtlFunction<K, V>? = null
        var keyFilter: KeyFilter? = null
        var policy: CachePolicy = CachePolicy.DEFAULT

        fun namespace(namespace: String) = apply { this.namespace = namespace }
        fun keyPrefix(keyPrefix: String) = apply { this.keyPrefix = keyPrefix }
        fun keyEncoder(keyEncoder: KeyEncoder<K>) = apply { this.keyEncoder = keyEncoder }
        fun codec(codec: Codec<V>) = apply { this.codec = codec }
        fun versionedLoader(
            versionedLoader: CacheLoader<K, out Versioned<V?>>
        ) = apply { this.versionedLoader = versionedLoader }
        fun versionedAsyncLoader(
            versionedAsyncLoader: AsyncCacheLoader<K, out Versioned<V?>>
        ) = apply { this.versionedAsyncLoader = versionedAsyncLoader }
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

        fun build(): VersionedCacheSpec<K, V> = VersionedCacheSpec(this)
    }

    companion object {
        @JvmStatic
        fun <K : Any, V : Any> builder(name: String): Builder<K, V> = Builder(name)
    }
}

/**
 * Kotlin DSL：`versionedCacheSpec<String, Order>("order") { codec = …; versionedLoader = CacheLoader { … } }`。
 */
fun <K : Any, V : Any> versionedCacheSpec(
    name: String,
    block: VersionedCacheSpec.Builder<K, V>.() -> Unit
): VersionedCacheSpec<K, V> = VersionedCacheSpec.builder<K, V>(name).apply(block).build()
