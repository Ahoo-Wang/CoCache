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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 缺陷复现（#997）：`Codec` 把载荷解码为 `null`（如 Jackson 解码 JSON `null`）时，引擎曾把这个存在的值报告为不存在：`get` 为 `null`、
 * `lookup` 为 `Absent` 并进入本地副本、`getIfChanged` 为 `Present(null)`，条件创建却与已有的摘要冲突。现在按读不懂的内容处理。
 */
class NullDecodeTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()

    @AfterEach
    fun close() = runtime.close()

    private fun store(): Store<String, String> = runtime.create(
        storeSpec<String, String>("store") { codec = NullableCodec }
    )

    /**
     * `Store` 的四种读取都以 `CacheReadException` 失败；条目保留，条件创建仍与它冲突。
     */
    @ParameterizedTest
    @ValueSource(strings = ["get", "lookup", "lookupRemote", "getIfChanged"])
    fun aStoreFailsOnANullDecode(operation: String) {
        val cache = store()
        cache.put("k", NULL_PAYLOAD)
        val actual = runCatching {
            when (operation) {
                "get" -> cache["k"]
                "lookup" -> cache.lookup("k")
                "lookupRemote" -> cache.lookupRemote("k")
                else -> cache.getIfChanged("k", null)
            }
        }
        actual.exceptionOrNull().assert()
            .withFailMessage { "$operation returned ${actual.getOrNull()} for a value that decodes to null" }
            .isInstanceOf(CacheReadException::class.java)
        cache.putIfMatch("k", null, "replacement").assert().isInstanceOf(ConditionalPut.Conflict::class.java)
    }

    /**
     * 对照：正常的值与真正的不存在照常区分。
     */
    @Test
    fun valuesAndAbsenceStayDistinct() {
        val cache = store()
        cache.put("k", "value")
        cache["k"].assert().isEqualTo("value")
        (cache.lookupRemote("k") as Lookup.Present).value.assert().isEqualTo("value")
        (cache.getIfChanged("k", null) as ConditionalGet.Present).value.assert().isEqualTo("value")
        cache.delete("k")
        cache["k"].assert().isNull()
        cache.lookupRemote("k").assert().isInstanceOf(Lookup.Absent::class.java)
        cache.getIfChanged("k", null).assert().isEqualTo(ConditionalGet.Absent)
    }

    /**
     * `VersionedStore`：载荷解码为 `null` 不是墓碑，读取失败。
     */
    @Test
    fun aVersionedStoreFailsOnANullDecode() {
        val cache = runtime.create(versionedStoreSpec<String, String>("versioned") { codec = NullableCodec })
        cache.putIfNewer("k", NULL_PAYLOAD, 3)
        runCatching { cache.getVersioned("k") }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
    }

    /**
     * `SetStore`：解码为 `null` 的成员让读取失败，不把 `null` 放进集合。
     */
    @Test
    fun aSetStoreFailsOnANullMember() {
        val cache = runtime.create(setStoreSpec<String, String>("set") { elementCodec = NullableCodec })
        cache.add("k", listOf("a", NULL_PAYLOAD))
        runCatching { cache["k"] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
    }

    /**
     * 有数据源的缓存：读不懂的内容按损坏回收并回源，调用方拿到数据源的值，不是 `null`。
     */
    @Test
    fun aSourcedCacheReloads() {
        var payload = NULL_PAYLOAD
        val spec = cacheSpec<String, String>("sourced") {
            codec = NullableCodec
            loader = CacheLoader { payload }
        }
        runtime.create(spec)["k"].assert().isEqualTo(NULL_PAYLOAD)
        payload = "loaded"
        // 另一个运行时从 L1 读到这个值（没有本地副本），解码为 null
        CacheRuntime.builder().backend(runtime.backend).build().use { other ->
            other.create(spec)["k"].assert().isEqualTo("loaded")
        }
    }

    /**
     * 把 [NULL_PAYLOAD] 解码为 `null` 的编解码器（同 Jackson 对 JSON `null`）：Java 实现不受 Kotlin 空安全的约束。
     */
    private object NullableCodec : Codec<String> {
        override fun encode(value: String): ByteArray = value.toByteArray()

        override fun decode(bytes: ByteArray): String = String(bytes).takeUnless { it == NULL_PAYLOAD } ?: unsafeNull()
    }

    companion object {
        private const val NULL_PAYLOAD = "null"

        @Suppress("UNCHECKED_CAST")
        private fun <T> unsafeNull(): T = null as T
    }
}
