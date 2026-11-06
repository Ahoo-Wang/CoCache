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

package me.ahoo.cocache.core.internal

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class LosslessKeysTest {
    /**
     * 能按 UTF-8 无损往返的 key 没有未配对的代理：普通字符、中文、空串、补充平面字符（正确配对的代理）。
     */
    @Test
    fun wellFormedKeysHaveNoUnpairedSurrogate() {
        listOf("", "plain", "中文", "😀", "a\uD834\uDD1Eb", "\uDBFF\uDFFF").forEach { key ->
            LosslessKeys.unpairedSurrogateIndex(key).assert().withFailMessage { key }.isEqualTo(-1)
            LosslessKeys.require("Cache", "c", key).assert().isSameAs(key)
            String(key.toByteArray(Charsets.UTF_8), Charsets.UTF_8).assert().isEqualTo(key)
        }
    }

    /**
     * 未配对的代理：单独的低代理、在末尾的高代理、后面不是低代理的高代理（包括两个高代理）、颠倒的一对。
     * 它们按 UTF-8 编码时都被替换，不能往返。
     */
    @Test
    fun findsTheFirstUnpairedSurrogate() {
        mapOf(
            "\uDC00" to 0,
            "ab\uD800" to 2,
            "a\uD800b" to 1,
            "\uD800\uD800\uDC00" to 0,
            "😀\uDC00😀" to 2,
            "\uDC00\uD800" to 0,
        ).forEach { (key, index) ->
            val found = LosslessKeys.unpairedSurrogateIndex(key)
            found.assert().withFailMessage { LosslessKeys.escape(key) }.isEqualTo(index)
            String(key.toByteArray(Charsets.UTF_8), Charsets.UTF_8).assert().isNotEqualTo(key)
        }
        LosslessKeys.unpairedSurrogateIndex("\uD800😀\uDBFF", 1).assert().isEqualTo(3)
    }

    @Test
    fun escapesOnlyUnpairedSurrogates() {
        LosslessKeys.escape("plain").assert().isEqualTo("plain")
        LosslessKeys.escape("a\uD800b😀\uDC00").assert().isEqualTo("a\\uD800b😀\\uDC00")
        LosslessKeys.escape("\uD800\uD800\uDC00").assert().isEqualTo("\\uD800\uD800\uDC00")
    }

    /**
     * 信息给出主体、名字与下标，key 中未配对的代理已转义，不含原样的 key。
     */
    @Test
    fun rejectsWithTheOwnerAndTheIndex() {
        val key = "user-\uDBFF"
        val error = runCatching { LosslessKeys.require("Namespace", "user", key) }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java)
        requireNotNull(error).message.assert()
            .startsWith("Namespace[user] - key[user-\\uDBFF] has an unpaired UTF-16 surrogate at index[5]")
            .doesNotContain(key)
    }
}
