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

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.codec.jackson.jacksonCodec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.redis.RedisTestSupport
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID

/**
 * `foreignFormat = RECLAIM` 只用于有数据源的缓存。
 *
 * 6.x 允许没有数据源的缓存配置它，代价是格式 0 的内容被回收，就是删除唯一副本：Redis 是权威存储、没有数据源的缓存
 * （CoSec 的用法）在 5.x 数据上直接升级时，旧数据在第一次读取时被删除；`SetCache` 读成空集合，第一次 `add` 整体覆盖、
 * 旧成员全部丢失。8.0 起这样的声明在创建时失败，Redis 中的数据一个字节都不动（RFC 0005 §12 “M3 之前”决定 6）；
 * 没有数据源的读取总是 `FAIL`，见 [ForeignFormatFailTest]。有数据源的缓存照常条件回收后回源。
 */
class ForeignFormatReclaimTest {
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

    /**
     * 5.x `ObjectToJsonCodecExecutor` 写入的 JSON、没有数据源的缓存：声明 `RECLAIM` 即创建失败，不注册缓存，5.x 的值原样保留。
     */
    @Test
    fun cacheWithoutSourceRejectsReclaimAndKeepsTheData() {
        val name = "policy-$run"
        val key = "cocache:v8:$name:p1"
        val json = mapper.writeValueAsString(Policy("p1", "deny"))
        Redis5x.string(key, json)

        val error = runCatching {
            runtime.create(
                cacheSpec<String, Policy>(name) {
                    codec = jacksonCodec<Policy>(mapper)
                    policy {
                        ttl = null
                        foreignFormat = ForeignFormatMode.RECLAIM
                    }
                }
            )
        }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java)
        error?.message.assert().startsWith("Cache[$name] - ").contains("RECLAIM")
        runtime.cacheNames.assert().doesNotContain(name)
        Redis5x.get(key).assert().isEqualTo(json)
    }

    /**
     * 5.x 原生 Set（CoSec 的全局策略索引）、`SetCache`：声明 `RECLAIM` 即创建失败，旧成员原样保留。
     */
    @Test
    fun setCacheRejectsReclaimAndKeepsTheData() {
        val name = "index-$run"
        val key = "cocache:v8:$name:"
        Redis5x.set(key, listOf("p1", "p2"))

        val error = runCatching {
            runtime.create(
                setCacheSpec<String, String>(name) {
                    elementCodec = StringCodec
                    policy { foreignFormat = ForeignFormatMode.RECLAIM }
                }
            )
        }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java)
        error?.message.assert().startsWith("SetCache[$name] - ").contains("RECLAIM")
        runtime.cacheNames.assert().doesNotContain(name)
        Redis5x.type(key).assert().isEqualTo("set")
        Redis5x.members(key).assert().containsExactlyInAnyOrder("p1", "p2")
    }

    /**
     * 有数据源：5.x 写入的 JSON / 字符串、Hash 与原生 Set 都在第一次读取时按摘要条件回收、回源并写回 Redis。
     * 默认（按数据源决定）与显式 `RECLAIM` 相同。
     */
    @Test
    fun cacheWithSourceReclaimsAndReloads() {
        for (mode in listOf(null, ForeignFormatMode.RECLAIM)) {
            val name = "reload-${mode ?: "default"}-$run"
            fun key(id: String) = "cocache:v8:$name:$id"
            Redis5x.string(key("json"), mapper.writeValueAsString(Policy("p1", "deny")))
            Redis5x.string(key("string"), "v1")
            Redis5x.hash(key("hash"), mapOf("a" to "1", "b" to "2"))
            Redis5x.set(key("set"), listOf("a", "b"))
            val cache = runtime.create(
                cacheSpec<String, String>(name) {
                    codec = StringCodec
                    loader = CacheLoader { "loaded-$it" }
                    policy {
                        ttl = null
                        mode?.let { foreignFormat = it }
                    }
                }
            )

            val ids = listOf("json", "string", "hash", "set")
            ids.forEach { cache[it].assert().isEqualTo("loaded-$it") }
            requireNotNull(runtime.metrics(name)).reclaims.assert().isEqualTo(ids.size.toLong())
            ids.forEach { Redis5x.type(key(it)).assert().isEqualTo("string") }
        }
    }
}
