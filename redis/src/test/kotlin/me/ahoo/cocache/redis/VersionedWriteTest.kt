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

package me.ahoo.cocache.redis

import io.lettuce.core.ScriptOutputType
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException

/**
 * 按版本有序的写入在真实 Redis 上的线格式与兼容性（standalone、Cluster、Valkey）：
 * - 带版本的内容仍是格式 1 的 36 字节头：令牌位置是 `VERSION_MARK` + 版本，签发时间是 `+inf`，载荷不变；
 * - 6.x 的读脚本与本版本相同（SHA 不变），6.7.0 的 fill / release 不会匹配带版本的内容；
 * - 读不懂的内容总是拒绝，与条件写入相同（与 `foreignFormat` 无关）。
 */
class VersionedWriteTest {
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
     * `VersionedCache`（带版本的数据源，写入不带写入标识）。
     */
    private fun cache(name: String): VersionedCache<String, String> = runtime.create(
        versionedCacheSpec(name) {
            codec = StringCodec
            versionedLoader = CacheLoader { Versioned("loaded", 0) }
            policy {
                ttl = Duration.ofMinutes(10)
                ttlJitter = Duration.ZERO
            }
        }
    )

    /**
     * `VersionedStore`（没有数据源，每次标量写入带写入标识）。
     */
    private fun store(name: String): VersionedStore<String, String> = runtime.create(
        versionedStoreSpec(name) {
            codec = StringCodec
            policy {
                ttl = Duration.ofMinutes(10)
                ttlJitter = Duration.ZERO
            }
        }
    )

    private fun hexOf(key: String): String = RedisTestSupport.withCommands {
        it.eval(
            """
            local v = redis.call('GET', KEYS[1])
            local h = {}
            for i = 1, #v do h[i] = string.format('%02x', string.byte(v, i)) end
            return table.concat(h)
            """.trimIndent(),
            ScriptOutputType.VALUE,
            arrayOf(key)
        )
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * 带版本的值与墓碑的字节布局（格式 2，RFC 0005 §4.7）：版本在版本段（bit 0，字节 36 起），令牌位置是空的；
     * 权威标量（没有数据源）的值另带扩展区中的写入标识（bit 7，28 字节），墓碑与派生缓存的值不带。
     */
    @Test
    fun versionedContentUsesTheVersionSegment() {
        val emptyToken = "00".repeat(24) + "0000000000000000"
        val derived = "derived-$run"
        val sourced = cache(derived)
        sourced.putIfNewer("v", "payload", 0x0102030405060708L).assert().isEqualTo(VersionedPut.Written)
        hexOf("cocache:$derived:v").assert().isEqualTo(
            "c0" + "02" + "56" + "01" + emptyToken + "0102030405060708" + hex("payload".toByteArray())
        )
        sourced.invalidateIfNewer("v", 0x0102030405060709L).assert().isEqualTo(VersionedPut.Written)
        hexOf("cocache:$derived:v").assert().isEqualTo("c0" + "02" + "4d" + "01" + emptyToken + "0102030405060709")

        val authoritative = "authoritative-$run"
        val cache = store(authoritative)
        cache.putIfNewer("v", "payload", 7).assert().isEqualTo(VersionedPut.Written)
        val raw = hexOf("cocache:$authoritative:v")
        raw.substring(0, 88).assert().isEqualTo("c0" + "02" + "56" + "81" + emptyToken + "0000000000000007")
        // 扩展区：总长 26，TLV 类型 1、长度 24，写入标识的前 16 字节是本运行时的 NodeId
        raw.substring(88, 96).assert().isEqualTo("001a" + "01" + "18")
        raw.substring(96, 128).assert().isEqualTo(runtime.nodeId.toString())
        raw.substring(144).assert().isEqualTo(hex("payload".toByteArray()))
        cache.deleteIfNewer("v", 8).assert().isEqualTo(VersionedPut.Written)
        hexOf(
            "cocache:$authoritative:v"
        ).assert().isEqualTo("c0" + "02" + "4d" + "01" + emptyToken + "0000000000000008")
        cache.getVersioned("v").assert().isNull()
        cache.putIfNewer("v", "v9", 9).assert().isEqualTo(VersionedPut.Written)
        cache.getVersioned("v").assert().isEqualTo(Versioned("v9", 9))
    }

    /**
     * 不是本协议写入的内容（格式 0）总是拒绝（不修改、不广播），从不视为没有版本；读取会回收它的、有数据源的缓存也是如此。
     * 更高格式版本写入的值同样拒绝（滚动升级期间不能被旧节点降级覆盖）。
     */
    @Test
    fun foreignContentIsAlwaysRefused() {
        val failName = "fail-$run"
        val failKey = "cocache:$failName:k"
        RedisTestSupport.withCommands { it.set(failKey, "legacy") }
        val failing = store(failName)
        val error = runCatching { failing.putIfNewer("k", "v1", 1) }.exceptionOrNull()
        error.assert().isInstanceOf(CacheWriteException::class.java)
        (error as CacheWriteException).operation.assert().isEqualTo("putIfNewer")
        requireNotNull(error.cause as? ForeignFormatException).format.assert().isEqualTo(0)
        RedisTestSupport.withCommands { it.get(failKey) }.assert().isEqualTo("legacy")
        val asyncError = runCatching { failing.async().deleteIfNewer("k", 1).get() }.exceptionOrNull()
        asyncError.assert().isInstanceOf(ExecutionException::class.java)
        (asyncError?.cause as CacheWriteException).operation.assert().isEqualTo("deleteIfNewer")

        val reclaimName = "reclaim-$run"
        val reclaimKey = "cocache:$reclaimName:k"
        RedisTestSupport.withCommands { it.set(reclaimKey, "legacy") }
        val reclaiming = cache(reclaimName)
        val refused = runCatching { reclaiming.putIfNewer("k", "v1", 1) }.exceptionOrNull()
        refused.assert().isInstanceOf(CacheWriteException::class.java)
        requireNotNull(refused?.cause as? ForeignFormatException).format.assert().isEqualTo(0)
        RedisTestSupport.withCommands { it.get(reclaimKey) }.assert().isEqualTo("legacy")
        // 读取照常条件回收后回源（版本 0）；之后按版本写入照常进行
        reclaiming["k"].assert().isEqualTo("loaded")
        reclaiming.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        reclaiming.getVersioned("k").assert().isEqualTo(Versioned("v1", 1))

        val newerKey = "cocache:$reclaimName:newer"
        RedisTestSupport.withCommands {
            it.eval<String>(
                "return redis.call('SET', KEYS[1], '\\192\\3V\\0' .. string.rep('\\0', 32) .. 'v')",
                ScriptOutputType.STATUS,
                arrayOf(newerKey)
            )
        }
        val newer = runCatching { reclaiming.putIfNewer("newer", "v1", 1) }.exceptionOrNull()
        val cause = requireNotNull(newer?.cause as? ForeignFormatException)
        cause.format.assert().isEqualTo(3)
        cause.newer.assert().isTrue()
    }
}
