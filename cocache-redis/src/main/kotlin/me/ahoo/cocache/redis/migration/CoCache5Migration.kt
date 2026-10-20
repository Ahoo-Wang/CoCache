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

package me.ahoo.cocache.redis.migration

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import java.util.function.Function

/**
 * CoCache 5.x 的值格式，即 5.x 缓存所用的 `CodecExecutor`（`cocache-spring-redis`）。5.0–5.4 的线格式相同。
 *
 * 5.x 经 `StringRedisTemplate` 以 UTF-8 写入；负缓存为哨兵（[CoCache5MigrationOptions.missingGuardSentinel]），
 * 迁移为 6.x 的负缓存（保留剩余寿命）。
 */
sealed class CoCache5Format<V : Any> {
    internal abstract val redisType: String

    /**
     * 解码 5.x 的原始内容（`GET` 的字符串、`SMEMBERS` 的成员或 `HGETALL` 的字段）。解码失败时抛出，迁移立即中止。
     */
    internal abstract fun decode(raw: Any, codec: Codec<V>): V

    internal class StringFormat<V : Any>(private val decoder: Function<String, V>?) : CoCache5Format<V>() {
        override val redisType: String = "string"

        override fun decode(raw: Any, codec: Codec<V>): V {
            val text = raw as String
            return decoder?.apply(text) ?: codec.decode(text.toByteArray(Charsets.UTF_8))
        }
    }

    internal class SetFormat<V : Any>(private val decoder: Function<Set<String>, V>) : CoCache5Format<V>() {
        override val redisType: String = "set"

        @Suppress("UNCHECKED_CAST")
        override fun decode(raw: Any, codec: Codec<V>): V = decoder.apply(raw as Set<String>)
    }

    internal class HashFormat<V : Any>(private val decoder: Function<Map<String, String>, V>) : CoCache5Format<V>() {
        override val redisType: String = "hash"

        @Suppress("UNCHECKED_CAST")
        override fun decode(raw: Any, codec: Codec<V>): V = decoder.apply(raw as Map<String, String>)
    }

    companion object {
        /**
         * Redis String：5.x 的 `ObjectToJsonCodecExecutor`（默认）与 `StringToStringCodecExecutor`。
         * 用 6.x 缓存的 `Codec` 解码 5.x 写入的 UTF-8 字节：5.x 的 JSON 与 6.x 的 `JacksonCodec`（同一个 `ObjectMapper` 配置）、
         * 5.x 的字符串与 6.x 的 `StringCodec` 字节相同。
         */
        @JvmStatic
        fun <V : Any> string(): CoCache5Format<V> = StringFormat(null)

        /**
         * Redis String，以 [decoder] 解码（5.x 与 6.x 的序列化不同时）。
         */
        @JvmStatic
        fun <V : Any> string(decoder: Function<String, V>): CoCache5Format<V> = StringFormat(decoder)

        /**
         * 原生 Redis Set（5.x 的 `SetToSetCodecExecutor`）迁移为 6.x 的标量缓存，如 `Cache<K, Set<String>>`：`set { it }`。
         * 迁移为 `SetCache` 用 [CoCache5Migration.set]。
         */
        @JvmStatic
        fun <V : Any> set(decoder: Function<Set<String>, V>): CoCache5Format<V> = SetFormat(decoder)

        /**
         * Redis Hash：5.x 的 `MapToHashCodecExecutor`（`hash { it }`）或 `ObjectToHashCodecExecutor`（`hash(mapConverter::asValue)`）。
         */
        @JvmStatic
        fun <V : Any> hash(decoder: Function<Map<String, String>, V>): CoCache5Format<V> = HashFormat(decoder)
    }
}

/**
 * 5.x 存储 key 的后缀（去掉 5.x 前缀之后的部分）→ 6.x 编码后的 key（`KeyEncoder` 的输出）。
 */
fun interface CoCache5KeyMapper {
    fun map(suffix: String): String

    companion object {
        /**
         * 原样使用：5.x 的 `ToStringKeyConverter` / `ExpKeyConverter` 与 6.x 的 `KeyEncoder` 对同一个业务 key 输出相同文本时。
         */
        @JvmField
        val IDENTITY: CoCache5KeyMapper = CoCache5KeyMapper { it }
    }
}

/**
 * 一个缓存的迁移：从哪些 5.x key 读、按什么格式解码、写到哪个 6.x 缓存。
 *
 * - **来源**：默认扫描 5.x 前缀 `cocache:{name}:`（5.x 的默认前缀）下的全部 key（[Builder.sourcePrefix] 修改前缀）；
 *   或用 [Builder.sourceKey] 列出 key（如 5.x 中用自定义 `KeyConverter` 映射到固定 key 的缓存），两者不能同时使用。
 * - **目标**：6.x 存储 key `{keyPrefix}{namespace}:{编码后的 key}`，编码后的 key = [Builder.keyMapper] 作用于 5.x key 去掉前缀的部分。
 *   从 6.x 的 spec 创建时，前缀、命名空间与编解码都取自 spec，与应用使用的缓存一致。
 * - 目标 key 与来源 key 相同（默认前缀下即如此）时**原地迁移**：先把 5.x 原值备份到 `{backupPrefix}{5.x key}`；
 *   不同时写到新 key，5.x 的 key 保持不动（它本身就是备份）。
 */
class CoCache5Migration private constructor(builder: Builder) {
    val name: String = builder.name
    val keyPrefix: String = builder.keyPrefix
    val namespace: String = builder.namespace ?: builder.name
    val sourcePrefix: String = builder.sourcePrefix ?: "cocache:${builder.name}:"
    val sourceKeys: Map<String, String> = LinkedHashMap(builder.sourceKeys)
    internal val keyMapper: CoCache5KeyMapper = builder.keyMapper
    internal val converter: Converter = builder.converter()

    /**
     * 6.x 存储 key 中编码后的 key 之前的部分。
     */
    val targetPrefix: String = "$keyPrefix$namespace:"

    init {
        require(name.isNotBlank()) { "name must not be blank." }
        require(namespace.isNotBlank()) { "namespace must not be blank." }
        require(builder.sourcePrefix == null || sourceKeys.isEmpty()) {
            "Migration[$name] - use either sourcePrefix (scan) or sourceKey (explicit keys), not both."
        }
        require(sourceKeys.isNotEmpty() || sourcePrefix.isNotEmpty()) {
            "Migration[$name] - an empty sourcePrefix would scan the whole database; list the keys with sourceKey."
        }
    }

    /**
     * 是否扫描 [sourcePrefix]（没有列出 key 时）。
     */
    val scans: Boolean get() = sourceKeys.isEmpty()

    /**
     * 5.x key → 6.x 存储 key。
     */
    fun targetKeyOf(sourceKey: String): String {
        sourceKeys[sourceKey]?.let { return targetPrefix + it }
        require(
            sourceKey.startsWith(sourcePrefix)
        ) { "Migration[$name] - key[$sourceKey] is not under prefix[$sourcePrefix]." }
        return targetPrefix + keyMapper.map(sourceKey.substring(sourcePrefix.length))
    }

    override fun toString(): String =
        "CoCache5Migration(name='$name', source=${if (scans) "'$sourcePrefix*'" else sourceKeys.keys}, target='$targetPrefix*')"

    /**
     * 5.x 内容到 6.x 信封的转换（Absent 即负缓存）。
     */
    internal fun interface Converter {
        /**
         * @param type Redis 类型（string / set / hash）
         * @param raw 5.x 原始内容：String、`Set<String>` 或 `Map<String, String>`
         * @return 6.x 信封；类型不符或解码失败时抛出
         */
        fun convert(type: String, raw: Any, sentinel: String): Envelope
    }

    class Builder internal constructor(internal val name: String, private val converter: Converter) {
        internal var keyPrefix: String = CacheSpec.DEFAULT_KEY_PREFIX
        internal var namespace: String? = null
        internal var sourcePrefix: String? = null
        internal val sourceKeys = LinkedHashMap<String, String>()
        internal var keyMapper: CoCache5KeyMapper = CoCache5KeyMapper.IDENTITY

        /**
         * 6.x 的 key 前缀（默认 `cocache:`）。从 spec 创建时取自 spec。
         */
        fun keyPrefix(keyPrefix: String) = apply { this.keyPrefix = keyPrefix }

        /**
         * 6.x 的命名空间（默认等于缓存名）。从 spec 创建时取自 spec。
         */
        fun namespace(namespace: String) = apply { this.namespace = namespace }

        /**
         * 5.x 的 key 前缀（5.x `@CoCache.keyPrefix` 或 `KeyConverter` 的前缀；默认 `cocache:{name}:`），扫描其下全部 key。
         */
        fun sourcePrefix(sourcePrefix: String) = apply { this.sourcePrefix = sourcePrefix }

        /**
         * 只迁移列出的 5.x key（不扫描）。
         *
         * @param key 5.x 的完整存储 key
         * @param encodedKey 6.x 中编码后的 key（`KeyEncoder` 的输出，如 `toString()`）
         */
        fun sourceKey(key: String, encodedKey: String) = apply { sourceKeys[key] = encodedKey }

        /**
         * 5.x key 后缀 → 6.x 编码后的 key（默认原样）。
         */
        fun keyMapper(keyMapper: CoCache5KeyMapper) = apply { this.keyMapper = keyMapper }

        internal fun converter(): Converter = converter

        fun build(): CoCache5Migration = CoCache5Migration(this)
    }

    companion object {
        /**
         * 迁移为 6.x 的标量缓存 `Cache<K, V>`：key 前缀、命名空间、编解码取自 [spec]。
         */
        @JvmStatic
        fun <V : Any> scalar(spec: CacheSpec<*, V>, format: CoCache5Format<V>): Builder =
            scalar(spec.name, spec.codec, format).keyPrefix(spec.keyPrefix).namespace(spec.namespace)

        /**
         * 迁移为 6.x 的标量缓存 `Cache<K, V>`，值以 [codec] 编码（必须是 6.x 缓存使用的编解码）。
         */
        @JvmStatic
        fun <V : Any> scalar(name: String, codec: Codec<V>, format: CoCache5Format<V>): Builder = Builder(
            name,
            Converter { type, raw, sentinel ->
                if (isMissingGuard(type, raw, sentinel)) return@Converter Envelope.Absent
                requireType(format.redisType, type)
                Envelope.Scalar(codec.encode(format.decode(raw, codec)))
            }
        )

        /**
         * 原生 Redis Set（5.x 的 `SetToSetCodecExecutor`）迁移为 6.x 的 `SetCache<K, E>`：key 前缀、命名空间、元素编解码取自 [spec]。
         */
        @JvmStatic
        fun <E : Any> set(spec: SetCacheSpec<*, E>): Builder =
            set(spec.name, spec.elementCodec).keyPrefix(spec.keyPrefix).namespace(spec.namespace)

        /**
         * 原生 Redis Set 迁移为 6.x 的 `SetCache<K, E>`，成员以 [elementCodec] 从 5.x 的 UTF-8 字符串解码再编码。
         */
        @JvmStatic
        fun <E : Any> set(name: String, elementCodec: Codec<E>): Builder = Builder(
            name,
            Converter { type, raw, sentinel ->
                if (isMissingGuard(type, raw, sentinel)) return@Converter Envelope.Absent
                requireType("set", type)
                @Suppress("UNCHECKED_CAST")
                val members = (raw as Set<String>).map {
                    elementCodec.encode(
                        elementCodec.decode(it.toByteArray(Charsets.UTF_8))
                    )
                }
                Envelope.Members(members)
            }
        )

        /**
         * 5.x 的负缓存：String 为哨兵本身，Set 为只含哨兵的集合，Hash 为只含哨兵字段的 Hash。
         */
        private fun isMissingGuard(type: String, raw: Any, sentinel: String): Boolean = when (type) {
            "string" -> raw == sentinel
            "set" -> (raw as Set<*>).let { it.size == 1 && it.first() == sentinel }
            "hash" -> (raw as Map<*, *>).let { it.size == 1 && it.keys.first() == sentinel }
            else -> false
        }

        private fun requireType(expected: String, actual: String) {
            require(
                expected == actual
            ) { "expected a Redis $expected (as written by the configured 5.x format) but found a $actual." }
        }
    }
}
