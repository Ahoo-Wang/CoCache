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
 * 复现：Redis 是权威存储、没有数据源的缓存（CoSec 的用法）在 5.x 数据上直接升级到 6.x 时，旧数据被当作外来格式回收。
 *
 * 用 `foreignFormat = RECLAIM`（6.4 及以前对所有缓存的行为）固定旧行为，证明数据丢失确实发生；
 * 6.5 起没有数据源的读取默认 `FAIL`，见 [ForeignFormatFailTest]。
 */
class CoCache5DataLossTest {
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

    private fun policyCache(name: String) = runtime.create(
        cacheSpec<String, Policy>(name) {
            codec = jacksonCodec<Policy>(mapper)
            policy {
                ttl = null
                foreignFormat = ForeignFormatMode.RECLAIM
            }
        }
    )

    /**
     * 5.x `ObjectToJsonCodecExecutor` 写入的 JSON：读到格式 0 → 条件回收为 lease → 没有数据源 → 释放 lease，
     * Redis 中的权威数据被删除，读取返回“不存在”。
     */
    @Test
    fun jsonValueIsDeletedOnFirstRead() {
        val name = "policy-$run"
        val key = "cocache:$name:p1"
        Redis5x.string(key, mapper.writeValueAsString(Policy("p1", "deny")))
        val cache = policyCache(name)

        cache.get("p1").assert().isNull()
        Redis5x.exists(key).assert().withFailMessage { "the 5.x value is gone from Redis" }.isFalse()
        requireNotNull(runtime.metrics(name)).reclaims.assert().isEqualTo(1L)
    }

    /**
     * 5.x `StringToStringCodecExecutor` 写入的字符串：同样被删除。
     */
    @Test
    fun stringValueIsDeletedOnFirstRead() {
        val name = "string-$run"
        val key = "cocache:$name:k1"
        Redis5x.string(key, "v1")
        val cache = runtime.create(
            cacheSpec<String, String>(name) {
                codec = StringCodec
                policy {
                    ttl = null
                    foreignFormat = ForeignFormatMode.RECLAIM
                }
            }
        )

        cache.get("k1").assert().isNull()
        Redis5x.exists(key).assert().isFalse()
    }

    /**
     * 5.x `MapToHashCodecExecutor` 写入的 Hash：同样被删除。
     */
    @Test
    fun hashValueIsDeletedOnFirstRead() {
        val name = "hash-$run"
        val key = "cocache:$name:k1"
        Redis5x.hash(key, mapOf("a" to "1", "b" to "2"))
        val cache = runtime.create(
            cacheSpec<String, Map<String, String>>(name) {
                codec = jacksonCodec<Map<String, String>>(mapper)
                policy {
                    ttl = null
                    foreignFormat = ForeignFormatMode.RECLAIM
                }
            }
        )

        cache.get("k1").assert().isNull()
        Redis5x.exists(key).assert().isFalse()
    }

    /**
     * 5.x 原生 Set 读成 6.x 的标量缓存 `Cache<K, Set<String>>`：同样被删除。
     */
    @Test
    fun nativeSetUnderScalarCacheIsDeletedOnFirstRead() {
        val name = "set-scalar-$run"
        val key = "cocache:$name:k1"
        Redis5x.set(key, listOf("a", "b"))
        val cache = runtime.create(
            cacheSpec<String, Set<String>>(name) {
                codec = jacksonCodec<Set<String>>(mapper)
                policy {
                    ttl = null
                    foreignFormat = ForeignFormatMode.RECLAIM
                }
            }
        )

        cache.get("k1").assert().isNull()
        Redis5x.exists(key).assert().isFalse()
    }

    /**
     * 5.x 原生 Set（CoSec 的全局策略索引）读成 6.x 的 `SetCache`：读成空集合（key 还在），
     * 随后的第一次 `add` 整体覆盖，旧成员全部丢失。
     */
    @Test
    fun nativeSetUnderSetCacheReadsEmptyAndFirstAddDropsOldMembers() {
        val name = "index-$run"
        val key = "cocache:$name:"
        Redis5x.set(key, listOf("p1", "p2"))
        val cache = runtime.create(
            setCacheSpec<String, String>(name) {
                elementCodec = StringCodec
                policy { foreignFormat = ForeignFormatMode.RECLAIM }
            }
        )

        cache.get("").assert().isEmpty()
        Redis5x.type(key).assert().isEqualTo("set")
        requireNotNull(runtime.metrics(name)).unknownFormats.assert().isEqualTo(1L)

        cache.add("", listOf("p3"))
        cache.get("").assert().containsExactly("p3")
    }
}
