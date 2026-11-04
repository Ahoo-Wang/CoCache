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
import java.util.function.Consumer

/**
 * 权威副本 `Store` 的声明（RFC 0005 §4.1）：没有数据源（按构造禁止）。L1 是唯一副本：
 * - 读取不申请 lease，L1 中不存在就是 `Absent`；L1 读失败为 `Lookup.Unknown`，按 [unknownRead] 返回。
 * - 每次标量写入都带写入标识与载荷的摘要段；读取方重算摘要。
 * - `delete` / `deleteIfMatch` 同步完成或抛出，从不重试。
 *
 * 默认策略 [DEFAULT_POLICY]：永不过期，L2 寿命更短。寿命即语义的数据（会话、一次性验证码、幂等键）写出显式的 `ttl`，
 * 并使用不驱逐 key 的 Redis。存储 key 与命名空间的规则同 [Spec]。
 */
class StoreSpec<K : Any, V : Any> private constructor(builder: Builder<K, V>) : Spec<K, V> {
    override val name: String = builder.name
    override val namespace: String = builder.namespace ?: builder.name
    override val keyPrefix: String = builder.keyPrefix
    override val keyEncoder: KeyEncoder<K> = builder.keyEncoder
    val codec: Codec<V> = requireNotNull(builder.codec) { "Store[${builder.name}] - codec is required." }
    val ttlOf: TtlFunction<K, V>? = builder.ttlOf
    val keyFilter: KeyFilter? = builder.keyFilter
    override val policy: CachePolicy = builder.policy

    /**
     * L1 读失败时（`Lookup.Unknown`）读取返回什么（[UnknownReadMode]，默认抛出）。
     */
    val unknownRead: UnknownReadMode = builder.unknownRead

    init {
        requireValidName(name, namespace)
    }

    override val keyspace: Keyspace = keyspaceOf("Store", name, keyPrefix, namespace)

    override fun toString(): String =
        "StoreSpec(name='$name', namespace='$namespace', keyPrefix='$keyPrefix', unknownRead=$unknownRead, policy=$policy)"

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
         * 整体替换策略。注意 [CachePolicy.DEFAULT] 是派生副本的默认值（`ttl` 1 小时）：应以 [DEFAULT_POLICY]`.toBuilder()` 为起点，
         * 或用 [updatePolicy] 在当前策略上修改。
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

        fun build(): StoreSpec<K, V> = StoreSpec(this)
    }

    companion object {
        /**
         * 权威副本的默认策略（D7）：`ttl` 永不过期，`localMaxAge` 30 s，`absentTtl` 10 s。
         */
        @JvmField
        val DEFAULT_POLICY: CachePolicy = AUTHORITATIVE_POLICY

        @JvmStatic
        fun <K : Any, V : Any> builder(name: String): Builder<K, V> = Builder(name)
    }
}

/**
 * Kotlin DSL：`storeSpec<String, Policy>("policy-snapshot") { codec = … }`。
 */
fun <K : Any, V : Any> storeSpec(name: String, block: StoreSpec.Builder<K, V>.() -> Unit): StoreSpec<K, V> =
    StoreSpec.builder<K, V>(name).apply(block).build()
