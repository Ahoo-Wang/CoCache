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
import me.ahoo.cocache.core.policy.UnknownReadMode
import java.time.Duration
import java.util.function.Consumer

/**
 * 权威的带版本副本 `VersionedStore` 的声明（RFC 0005 §4.1、§4.2）：没有数据源，写入方按版本有序地写入。
 * 读取的规则同 [StoreSpec]；每次写入都带写入标识，不带摘要段（没有条件读写）。
 *
 * 默认策略 [DEFAULT_POLICY]：值永不过期，墓碑的寿命 `tombstoneTtl` 为 1 天。墓碑必须有有限的寿命，否则每个删除过的 key 都永远留在 L1 中：
 * 墓碑寿命（`tombstoneTtl`，未设置时为 `ttl`）为永不过期时构建失败。
 */
class VersionedStoreSpec<K : Any, V : Any> private constructor(builder: Builder<K, V>) : Spec<K, V> {
    override val name: String = builder.name
    override val namespace: String = builder.namespace ?: builder.name
    override val keyPrefix: String = builder.keyPrefix
    override val keyEncoder: KeyEncoder<K> = builder.keyEncoder
    val codec: Codec<V> = requireNotNull(builder.codec) { "VersionedStore[${builder.name}] - codec is required." }
    val ttlOf: TtlFunction<K, V>? = builder.ttlOf
    val keyFilter: KeyFilter? = builder.keyFilter
    override val policy: CachePolicy = builder.policy

    /**
     * L1 读失败时（`Lookup.Unknown`）读取返回什么（[UnknownReadMode]，默认抛出）。
     */
    val unknownRead: UnknownReadMode = builder.unknownRead

    init {
        requireValidName(name, namespace)
        require(policy.tombstoneTtl != null || policy.ttl != null) {
            "VersionedStore[$name] - tombstones need a finite lifetime: set policy.tombstoneTtl."
        }
    }

    override val keyspace: Keyspace = keyspaceOf("VersionedStore", name, keyPrefix, namespace)

    override fun toString(): String =
        "VersionedStoreSpec(name='$name', namespace='$namespace', keyPrefix='$keyPrefix', unknownRead=$unknownRead, policy=$policy)"

    /**
     * 以本声明为基础的构建器（用于叠加定制与配置）。
     */
    fun toBuilder(): Builder<K, V> = Builder<K, V>(name).also {
        it.namespace = namespace
        it.keyPrefix = keyPrefix
        it.keyEncoder = keyEncoder
        it.codec = codec
        it.ttlOf = ttlOf
        it.keyFilter = keyFilter
        it.policy = policy
        it.unknownRead = unknownRead
    }

    class Builder<K : Any, V : Any> internal constructor(val name: String) {
        var namespace: String? = null
        var keyPrefix: String = CacheSpec.DEFAULT_KEY_PREFIX
        var keyEncoder: KeyEncoder<K> = KeyEncoder.ofToString()
        var codec: Codec<V>? = null
        var ttlOf: TtlFunction<K, V>? = null
        var keyFilter: KeyFilter? = null
        var policy: CachePolicy = DEFAULT_POLICY
        var unknownRead: UnknownReadMode = UnknownReadMode.THROW

        fun namespace(namespace: String) = apply { this.namespace = namespace }
        fun keyPrefix(keyPrefix: String) = apply { this.keyPrefix = keyPrefix }
        fun keyEncoder(keyEncoder: KeyEncoder<K>) = apply { this.keyEncoder = keyEncoder }
        fun codec(codec: Codec<V>) = apply { this.codec = codec }
        fun ttlOf(ttlOf: TtlFunction<K, V>) = apply { this.ttlOf = ttlOf }
        fun keyFilter(keyFilter: KeyFilter) = apply { this.keyFilter = keyFilter }

        /**
         * 整体替换策略：应以 [DEFAULT_POLICY]`.toBuilder()` 为起点，或用 [updatePolicy] 在当前策略上修改。
         */
        fun policy(policy: CachePolicy) = apply { this.policy = policy }
        fun unknownRead(unknownRead: UnknownReadMode) = apply { this.unknownRead = unknownRead }

        /**
         * 在当前策略（默认 [DEFAULT_POLICY]）基础上修改。
         */
        fun policy(
            block: CachePolicy.Builder.() -> Unit
        ) = apply { this.policy = policy.toBuilder().apply(block).build() }

        /**
         * 在当前策略（默认 [DEFAULT_POLICY]）基础上修改（Java 友好）。
         */
        fun updatePolicy(customizer: Consumer<CachePolicy.Builder>) = apply {
            this.policy = policy.toBuilder().also { customizer.accept(it) }.build()
        }

        fun build(): VersionedStoreSpec<K, V> = VersionedStoreSpec(this)
    }

    companion object {
        /**
         * 默认墓碑寿命：1 天。
         */
        @JvmField
        val DEFAULT_TOMBSTONE_TTL: Duration = Duration.ofDays(1)

        /**
         * 权威的带版本副本的默认策略：同 [StoreSpec.DEFAULT_POLICY]，另有 [DEFAULT_TOMBSTONE_TTL]。
         */
        @JvmField
        val DEFAULT_POLICY: CachePolicy = AUTHORITATIVE_POLICY.toBuilder().tombstoneTtl(DEFAULT_TOMBSTONE_TTL).build()

        @JvmStatic
        fun <K : Any, V : Any> builder(name: String): Builder<K, V> = Builder(name)
    }
}

/**
 * Kotlin DSL：`versionedStoreSpec<String, Aggregate>("aggregate") { codec = … }`。
 */
fun <K : Any, V : Any> versionedStoreSpec(
    name: String,
    block: VersionedStoreSpec.Builder<K, V>.() -> Unit
): VersionedStoreSpec<K, V> = VersionedStoreSpec.builder<K, V>(name).apply(block).build()
