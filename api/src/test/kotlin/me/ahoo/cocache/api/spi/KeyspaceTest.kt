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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [Keyspace]：8.x 的存储 key 布局与命名空间字符集（RFC 0005 §4.7）。
 */
class KeyspaceTest {
    @Test
    fun storageKeysAreThePrefixAndTheNamespace() {
        val keyspace = Keyspace("cocache:", "user")
        keyspace.storagePrefix.assert().isEqualTo("cocache:user:")
        keyspace.storageKey("1").assert().isEqualTo("cocache:user:1")
        Keyspace("", "user").storageKey("a:b").assert().isEqualTo("user:a:b")
    }

    /**
     * 订阅者收到的编码 key（S4）：去掉存储前缀，不属于这个 keyspace 的 key（别的前缀、比前缀还短）被去掉，其余顺序不变。
     */
    @Test
    fun encodedKeysStripThePrefixAndDropForeignKeys() {
        val keyspace = Keyspace("cocache:", "user")
        keyspace.encodedKeys(listOf("cocache:user:2", "cocache:user:1")).assert().containsExactly("2", "1")
        keyspace.encodedKeys(listOf("cocache:user:a:b", "app:user:1", "x", "cocache:user:3"))
            .assert().containsExactly("a:b", "3")
        keyspace.encodedKeys(emptyList()).assert().isEmpty()
        Keyspace("", "user").encodedKeys(listOf("user:1", "cocache:user:1")).assert().containsExactly("1")
    }

    /**
     * 内部 key 的标记 `!` 不在命名空间的字符集中：`{keyPrefix}!…` 不会是任何命名空间的存储 key。
     */
    @Test
    fun internalMarkIsOutsideTheNamespaceCharset() {
        Keyspace.INTERNAL_MARK.assert().isEqualTo("!")
        Keyspace.isValidNamespace(Keyspace.INTERNAL_MARK).assert().isFalse()
        Keyspace.isValidNamespace("${Keyspace.INTERNAL_MARK}meta").assert().isFalse()
    }

    @Test
    fun equalityCoversPrefixAndNamespace() {
        Keyspace("p1:", "n").assert().isEqualTo(Keyspace("p1:", "n"))
        Keyspace("p1:", "n").hashCode().assert().isEqualTo(Keyspace("p1:", "n").hashCode())
        Keyspace("p1:", "n").assert().isNotEqualTo(Keyspace("p2:", "n"))
        Keyspace("p1:", "n").assert().isNotEqualTo(Keyspace("p1:", "m"))
        Keyspace("p1:", "n").assert().isNotEqualTo("p1:n")
        Keyspace("p1:", "n").toString().assert().isEqualTo("Keyspace(keyPrefix='p1:', namespace='n')")
    }

    @ParameterizedTest
    @ValueSource(strings = ["user", "User_1", "a.b-c", "0", "probe", "meta", "inv"])
    fun acceptsTheCharset(namespace: String) {
        Keyspace.isValidNamespace(namespace).assert().isTrue()
        Keyspace("cocache:", namespace).namespace.assert().isEqualTo(namespace)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "a b", "a*", "{a}", "用户", "a!b", "a/b"])
    fun rejectsNamespacesOutsideTheCharset(namespace: String) {
        Keyspace.isValidNamespace(namespace).assert().isFalse()
        val error = runCatching { Keyspace("cocache:", namespace) }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Namespace[$namespace] must match [A-Za-z0-9._-]+.")
    }

    /**
     * 把前缀写进命名空间（如 `app:user`）的声明：创建失败，提示把前缀移到 keyPrefix（RFC 0005 附录 A.L4 因此不再可能）。
     */
    @Test
    fun namespaceWithColonSuggestsMovingThePrefixIntoKeyPrefix() {
        val error = runCatching { Keyspace("cocache:", "app:user") }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java).hasMessage(
            "Namespace[app:user] must match [A-Za-z0-9._-]+. A namespace cannot contain ':': move the prefix into keyPrefix, " +
                "for example keyPrefix = \"cocache:app:\" and namespace = \"user\"."
        )
        runCatching {
            Keyspace(
                "",
                "app:"
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Namespace[app:] must match [A-Za-z0-9._-]+. A namespace cannot contain ':': move the prefix into keyPrefix."
            )
    }
}
