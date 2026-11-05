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

package me.ahoo.cocache.api.spi

/**
 * 一个缓存在 L1 中占用的 keyspace：key 前缀与命名空间（RFC 0005 §4.3、§4.7）。后端按它创建存储（[CoherenceBackend.store]），
 * 失效通道按它订阅与发布（[InvalidationChannel]）；频道名由后端按两者构造（Redis 为 `{keyPrefix}inv:{namespace}`），
 * 前缀不同、命名空间相同的两个缓存因此既不共用 key，也收不到彼此的失效帧。
 *
 * 8.x 的布局（随 M4 冻结）：
 * - 存储 key 为 `{keyPrefix}{namespace}:{encodedKey}`，即 [storagePrefix] 加上编码后的 key。
 * - 内部 key 放在 `{keyPrefix}!…`（[INTERNAL_MARK]）：`!` 不在命名空间的字符集中，任何命名空间都产生不了这个位置。
 *   `{keyPrefix}!meta:{namespace}` 预留为元数据 key，8.0 不写。
 * - [namespace] 的字符集为 [NAMESPACE_CHARSET]，不含 `:`：同一前缀下，一个命名空间的存储 key 不会落进另一个命名空间的范围
 *   （如 `a` 与 `a:b`，RFC 0005 附录 A.L4），跨应用时也一样。[keyPrefix] 不受限制，可以为空。
 *
 * 前缀与命名空间都相同的两个 keyspace 相等。
 *
 * @throws IllegalArgumentException [namespace] 不符合字符集：前缀写在 [keyPrefix] 中，不写进命名空间
 */
class Keyspace(val keyPrefix: String, val namespace: String) {
    init {
        require(isValidNamespace(namespace)) { invalidNamespace(keyPrefix, namespace) }
    }

    /**
     * 存储 key 的前缀 `{keyPrefix}{namespace}:`，创建时计算一次。
     */
    val storagePrefix: String = "$keyPrefix$namespace:"

    /**
     * 编码后的 key 对应的存储 key：`{keyPrefix}{namespace}:{encodedKey}`。
     */
    fun storageKey(encodedKey: String): String = storagePrefix + encodedKey

    /**
     * 失效帧中的存储 key → 交给 [InvalidationListener] 的编码 key：去掉 [storagePrefix]（与引擎内部剥前缀的规则相同）。
     * 不以 [storagePrefix] 开头的 key 不属于这个 keyspace（外部写入方给错了前缀），丢弃；顺序不变。
     */
    fun encodedKeys(storageKeys: List<String>): List<String> =
        storageKeys.mapNotNull { if (it.startsWith(storagePrefix)) it.substring(storagePrefix.length) else null }

    override fun equals(other: Any?): Boolean {
        return this === other || (other is Keyspace && keyPrefix == other.keyPrefix && namespace == other.namespace)
    }

    override fun hashCode(): Int = 31 * keyPrefix.hashCode() + namespace.hashCode()

    override fun toString(): String = "Keyspace(keyPrefix='$keyPrefix', namespace='$namespace')"

    companion object {
        /**
         * 内部 key 的标记：内部 key 在前缀之后以它开头（如预留的 `{keyPrefix}!meta:{namespace}`）。它不在 [NAMESPACE_CHARSET] 中，
         * 也不是 `{` `}`（会改变 Cluster 的 slot）或 glob 元字符。
         */
        const val INTERNAL_MARK = "!"

        /**
         * 命名空间的字符集（正则）。
         */
        const val NAMESPACE_CHARSET = "[A-Za-z0-9._-]+"

        private val NAMESPACE = Regex(NAMESPACE_CHARSET)

        /**
         * [namespace] 是否符合 [NAMESPACE_CHARSET]。
         */
        @JvmStatic
        fun isValidNamespace(namespace: String): Boolean = NAMESPACE.matches(namespace)

        /**
         * 含 `:` 的命名空间多半是把前缀写进了命名空间：给出移到前缀之后的写法。
         */
        private fun invalidNamespace(keyPrefix: String, namespace: String): String {
            val message = "Namespace[$namespace] must match $NAMESPACE_CHARSET."
            val split = namespace.lastIndexOf(':')
            if (split < 0) {
                return message
            }
            val moved = "$message A namespace cannot contain ':': move the prefix into keyPrefix"
            val suggested = namespace.substring(split + 1)
            if (!isValidNamespace(suggested)) {
                return "$moved."
            }
            val prefix = keyPrefix + namespace.substring(0, split + 1)
            return "$moved, for example keyPrefix = \"$prefix\" and namespace = \"$suggested\"."
        }
    }
}
