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
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.ForeignFormatMode
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
 * - 读不懂的内容按 `foreignFormat` 分类，与条件写入相同。
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

    private fun cache(name: String, mode: ForeignFormatMode? = null): Cache<String, String> = runtime.create(
        cacheSpec(name) {
            codec = StringCodec
            versioned = true
            policy {
                ttl = Duration.ofMinutes(10)
                ttlJitter = Duration.ZERO
                mode?.let { foreignFormat = it }
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
     * 6.x 的读者执行的就是这个读脚本：它没有改变，带版本的内容按原来的规则读取（不授予刷新令牌见 TCK）。
     */
    @Test
    fun readScriptIsUnchangedSince67() {
        RedisScripts.READ.sha.assert().isEqualTo(READ_SHA_6_7)
    }

    /**
     * 带版本的值与墓碑的字节布局：头部的前 4 字节、载荷与 6.7 相同，版本在令牌位置。
     */
    @Test
    fun versionedContentKeepsTheFormat1Layout() {
        val name = "layout-$run"
        val cache = cache(name)
        cache.putIfNewer("v", "payload", 0x0102030405060708L).assert().isEqualTo(VersionedPut.Written)
        val mark = hex(RedisScripts.VERSION_MARK)
        hexOf("cocache:$name:v").assert().isEqualTo(
            "c0" + "01" + "56" + "00" + mark + "0102030405060708" + "7ff0000000000000" + hex("payload".toByteArray())
        )
        cache.invalidateIfNewer("v", 0x0102030405060709L).assert().isEqualTo(VersionedPut.Written)
        hexOf("cocache:$name:v").assert().isEqualTo(
            "c0" + "01" + "4d" + "00" + mark + "0102030405060709" + "7ff0000000000000"
        )
        cache.put("v", "plain")
        hexOf("cocache:$name:v").assert().isEqualTo(
            "c0" + "01" + "56" + "00" + "00".repeat(24) + "0000000000000000" + hex("plain".toByteArray())
        )
    }

    /**
     * 6.7.0 的 fill 与 release（原样复制）认不出带版本的内容里的任何令牌：不修改它。6.7 的解析（载荷从第 36 字节开始）读到原值。
     */
    @Test
    fun legacyFillAndReleaseLeaveVersionedContentAlone() {
        val name = "legacy-$run"
        val key = "cocache:$name:k"
        val cache = cache(name)
        cache.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Written)
        val before = hexOf(key)
        val token = "t".repeat(24)
        RedisTestSupport.withCommands {
            it.eval<Long>(FILL_6_7, ScriptOutputType.INTEGER, arrayOf(key), token, "V", "60000", "other")
        }.assert().isEqualTo(0L)
        RedisTestSupport.withCommands {
            it.eval<Long>(RELEASE_6_7, ScriptOutputType.INTEGER, arrayOf(key), token)
        }.assert().isEqualTo(0L)
        hexOf(key).assert().isEqualTo(before)
        before.substring(RedisScripts.HEADER_SIZE * 2).assert().isEqualTo(hex("v5".toByteArray()))
        cache.getVersioned("k").assert().isEqualTo(Versioned("v5", 5))
    }

    /**
     * 不是本协议写入的内容（格式 0）：默认 `FAIL` 拒绝（不修改、不广播），`RECLAIM` 时视为没有版本、整体覆盖。
     * 更高格式版本写入的值总是拒绝（滚动升级期间不能被旧节点降级覆盖）。
     */
    @Test
    fun foreignContentFollowsForeignFormat() {
        val failName = "fail-$run"
        val failKey = "cocache:$failName:k"
        RedisTestSupport.withCommands { it.set(failKey, "legacy") }
        val failing = cache(failName)
        val error = runCatching { failing.putIfNewer("k", "v1", 1) }.exceptionOrNull()
        error.assert().isInstanceOf(CacheWriteException::class.java)
        (error as CacheWriteException).operation.assert().isEqualTo("putIfNewer")
        requireNotNull(error.cause as? ForeignFormatException).format.assert().isEqualTo(0)
        RedisTestSupport.withCommands { it.get(failKey) }.assert().isEqualTo("legacy")
        val asyncError = runCatching { failing.async().invalidateIfNewer("k", 1).get() }.exceptionOrNull()
        asyncError.assert().isInstanceOf(ExecutionException::class.java)
        (asyncError?.cause as CacheWriteException).operation.assert().isEqualTo("invalidateIfNewer")

        val reclaimName = "reclaim-$run"
        RedisTestSupport.withCommands { it.set("cocache:$reclaimName:k", "legacy") }
        val reclaiming = cache(reclaimName, ForeignFormatMode.RECLAIM)
        reclaiming.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        reclaiming.getVersioned("k").assert().isEqualTo(Versioned("v1", 1))

        val newerKey = "cocache:$reclaimName:newer"
        RedisTestSupport.withCommands {
            it.eval<String>(
                "return redis.call('SET', KEYS[1], '\\192\\2V\\0' .. string.rep('\\0', 32) .. 'v')",
                ScriptOutputType.STATUS,
                arrayOf(newerKey)
            )
        }
        val newer = runCatching { reclaiming.putIfNewer("newer", "v1", 1) }.exceptionOrNull()
        val cause = requireNotNull(newer?.cause as? ForeignFormatException)
        cause.format.assert().isEqualTo(2)
        cause.newer.assert().isTrue()
    }

    private companion object {
        /**
         * 6.7.0 的读脚本 SHA。
         */
        const val READ_SHA_6_7 = "8cd4afd0546e40eba7afd3637468ae5bc5531b0e"

        /**
         * 6.7.0 的 `CORE` + `PRELUDE`（原样复制，用来验证旧节点的写脚本遇到带版本的内容时的行为）。
         */
        const val PRELUDE_6_7 = """
local ZERO_TOKEN = string.rep('\0', 24)
local function now_ms()
  local t = redis.call('TIME')
  return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end
local function header(state, token, issued)
  return '\192\1' .. state .. '\0' .. token .. struct.pack('>d', issued)
end
local function is_ours(v)
  return string.len(v) >= 36 and string.byte(v, 1) == 192
end
local function expire(key, ttl)
  local ms = tonumber(ttl)
  if ms > 0 then redis.call('PEXPIRE', key, ms) end
end
local function write(key, kind, ttl, first)
  redis.call('DEL', key)
  if kind == 'S' then
    redis.call('HSET', key, '!f', '1')
    for i = first, #ARGV do redis.call('HSET', key, '+' .. ARGV[i], '') end
  elseif kind == 'V' then
    redis.call('SET', key, header('V', ZERO_TOKEN, 0) .. ARGV[first])
  else
    redis.call('SET', key, header('M', ZERO_TOKEN, 0))
  end
  expire(key, ttl)
end
"""

        const val FILL_6_7 = PRELUDE_6_7 + """
local key = KEYS[1]
if redis.call('TYPE', key)['ok'] ~= 'string' then return 0 end
local v = redis.call('GET', key)
if not is_ours(v) or string.sub(v, 5, 28) ~= ARGV[1] then return 0 end
write(key, ARGV[2], ARGV[3], 4)
return 1
"""

        const val RELEASE_6_7 = PRELUDE_6_7 + """
local key = KEYS[1]
if redis.call('TYPE', key)['ok'] ~= 'string' then return 0 end
local v = redis.call('GET', key)
if not is_ours(v) or string.sub(v, 5, 28) ~= ARGV[1] then return 0 end
if string.sub(v, 3, 3) == 'L' then
  redis.call('DEL', key)
else
  redis.call('SETRANGE', key, 4, ZERO_TOKEN .. struct.pack('>d', 0))
end
return 1
"""
    }
}
