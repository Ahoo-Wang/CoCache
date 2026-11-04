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
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.redis.RedisTestSupport
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID

/**
 * 有数据源的缓存回收读不懂的内容（不是本协议写入的，这里是 5.x 留下的），没有数据源的缓存失败（见 [ForeignFormatFailTest]）。
 * 8.0 删除了 `foreign-format`（RFC 0005 §4.7）：6.x 允许没有数据源的缓存配置 `reclaim`，代价是删除唯一副本。
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
     * 有数据源：5.x 写入的 JSON / 字符串、Hash 与原生 Set 都在第一次读取时按摘要条件回收、回源并写回 Redis。
     */
    @Test
    fun cacheWithSourceReclaimsAndReloads() {
        val name = "reload-$run"
        fun key(id: String) = "cocache:$name:$id"
        Redis5x.string(key("json"), mapper.writeValueAsString(Policy("p1", "deny")))
        Redis5x.string(key("string"), "v1")
        Redis5x.hash(key("hash"), mapOf("a" to "1", "b" to "2"))
        Redis5x.set(key("set"), listOf("a", "b"))
        val cache = runtime.create(
            cacheSpec<String, String>(name) {
                codec = StringCodec
                loader = CacheLoader { "loaded-$it" }
                policy { ttl = null }
            }
        )

        val ids = listOf("json", "string", "hash", "set")
        ids.forEach { cache[it].assert().isEqualTo("loaded-$it") }
        requireNotNull(runtime.metrics(name)).reclaims.assert().isEqualTo(ids.size.toLong())
        ids.forEach { Redis5x.type(key(it)).assert().isEqualTo("string") }
    }
}
