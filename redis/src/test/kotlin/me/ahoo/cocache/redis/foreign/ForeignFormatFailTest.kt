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

package me.ahoo.cocache.redis.foreign

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.codec.jackson.jacksonCodec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.redis.RedisTestSupport
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import java.util.concurrent.ExecutionException

/**
 * 没有数据源的读取读到不是本协议写入的内容时失败（fail closed，`CacheReadException` 的原因是 [ForeignFormatException]）：
 * 内容原样保留，不回收、不留下 lease，计入 `unknownFormats`。
 */
class ForeignFormatFailTest {
    data class Policy(val id: String, val effect: String)

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val run = UUID.randomUUID().toString()
    private val backend = RedisTestSupport.backend()
    private val runtime = CacheRuntime.builder().backend(backend).build()

    @AfterEach
    fun close() {
        runtime.close()
        backend.close()
        RedisTestSupport.deleteKeys("*$run*")
    }

    private fun assertForeign(error: Throwable?, storageKey: String) {
        error.assert().isInstanceOf(CacheReadException::class.java)
        val cause = requireNotNull(error?.cause as? ForeignFormatException)
        cause.storageKey.assert().isEqualTo(storageKey)
        cause.format.assert().isEqualTo(0)
    }

    @Test
    fun foreignJsonFailsAndIsKept() {
        val name = "policy-$run"
        val key = "cocache:$name:p1"
        val json = mapper.writeValueAsString(Policy("p1", "deny"))
        ForeignContent.string(key, json)
        val cache = runtime.create(
            storeSpec<String, Policy>(name) {
                codec = jacksonCodec<Policy>(mapper)
            }
        )

        assertForeign(runCatching { cache["p1"] }.exceptionOrNull(), key)
        assertForeign(runCatching { cache.getAll(listOf("p1", "p2")) }.exceptionOrNull(), key)
        val asyncError = runCatching { cache.async().get("p1").get() }.exceptionOrNull()
        asyncError.assert().isInstanceOf(ExecutionException::class.java)
        assertForeign(asyncError?.cause, key)
        ForeignContent.get(key).assert().isEqualTo(json)
        requireNotNull(runtime.metrics(name)).unknownFormats.assert().isEqualTo(3L)
        requireNotNull(runtime.metrics(name)).reclaims.assert().isEqualTo(0L)
        ForeignContent.exists("cocache:$name:p2").assert().withFailMessage { "no lease left behind" }.isFalse()
    }

    @Test
    fun foreignValueFailsForAnExternallySourcedRead() {
        val name = "strict-$run"
        val key = "cocache:$name:k"
        ForeignContent.hash(key, mapOf("a" to "1"))
        // 外部来源的缓存上不带 loader 的读取没有数据源：读不懂的内容不回收，读取失败
        val cache = runtime.create(
            cacheSpec<String, Map<String, String>>(name) {
                codec = jacksonCodec<Map<String, String>>(mapper)
                externallySourced()
            }
        )

        assertForeign(runCatching { cache["k"] }.exceptionOrNull(), key)
        ForeignContent.fields(key).assert().isEqualTo(mapOf("a" to "1"))
    }

    @Test
    fun foreignNativeSetFailsAndIsKept() {
        val name = "index-$run"
        val key = "cocache:$name:"
        ForeignContent.set(key, listOf("p1", "p2"))
        val cache = runtime.create(setStoreSpec<String, String>(name) { elementCodec = StringCodec })

        assertForeign(runCatching { cache[""] }.exceptionOrNull(), key)
        ForeignContent.members(key).assert().containsExactlyInAnyOrder("p1", "p2")
    }

    /**
     * 有数据源的缓存不受影响：按摘要条件回收后回源。
     */
    @Test
    fun cacheWithLoaderStillReclaimsAndReloads() {
        val name = "loaded-$run"
        val key = "cocache:$name:k"
        ForeignContent.string(key, "old")
        val cache = runtime.create(
            cacheSpec<String, String>(name) {
                codec = StringCodec
                loader = CacheLoader { "reloaded" }
            }
        )

        cache["k"].assert().isEqualTo("reloaded")
        requireNotNull(runtime.metrics(name)).reclaims.assert().isEqualTo(1L)
    }
}
