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

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.RedisURI
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.output.IntegerOutput
import io.lettuce.core.protocol.CommandArgs
import io.lettuce.core.protocol.CommandType
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setCacheSpec
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * `SetCache` 基准 `setRead`（RFC 0003 §8 Q1）：比较集合的两种 L1 编码。
 *
 * - Hash（现行线格式）：经生产的 [RemoteStore] 读写，即 `READ`（`PTTL` + `GET` 类型不符 + `HGETALL`）与 `ADD_MEMBERS` / `REMOVE_MEMBERS`。
 * - 备选（`*Alt`）：原生 Set + 同 slot 的控制 Hash（`{…}:c` 存 `!f`，`{…}:s` 存成员），只在本基准内用等价的 Lua 原型实现，
 *   不是生产代码。读取同样先试 `GET`，与真实读脚本的分派路径一致。
 *
 * 每个成员数（[size]）的 Redis 内存（`MEMORY USAGE … SAMPLES 0`）在 setup 时打印一次，含非整数成员与整数成员两种。
 *
 * 需要 localhost:6379 的 Redis：`./gradlew :cocache-redis:jmh --rerun -PjmhThreads=8 -PjmhIncludes=SetCacheBenchmark`。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class SetCacheBenchmark {
    /** 集合成员数。 */
    @Param("10", "100", "1000")
    var size: Int = 0

    private val run = UUID.randomUUID().toString().substring(0, 8)
    private lateinit var backend: RedisBackend
    private lateinit var runtime: CacheRuntime
    private lateinit var cache: SetCache<String, String>
    private lateinit var store: RemoteStore
    private lateinit var client: RedisClient
    private lateinit var connection: StatefulRedisConnection<ByteArray, ByteArray>
    private lateinit var hashKey: String
    private lateinit var altKeys: Array<ByteArray>
    private lateinit var frame: ByteArray
    private lateinit var channel: ByteArray
    private val churn = listOf(ENCODED_CHURN)

    @Setup
    fun setup() {
        backend = RedisBackend.standalone(RedisURI.create("localhost", 6379))
        runtime = CacheRuntime.builder().backend(backend).build()
        val name = "setbench-$run"
        cache = runtime.create(setCacheSpec<String, String>(name) { elementCodec = StringCodec })
        store = backend.store(name)
        frame = InvalidationFrame.encode(NodeId.random(), emptyList())
        channel = "cocache:inv:$name".toByteArray()
        client = RedisClient.create(RedisURI.create("localhost", 6379))
        connection = client.connect(ByteArrayCodec.INSTANCE)

        val members = (1..size).map(::stringMember)
        // L2 命中用的集合：经引擎写入后读一次，填入 L2
        cache.add("hot", members)
        cache["hot"]
        hashKey = "cocache:$name:l1"
        store.addMembers(hashKey, members.map { it.toByteArray() }, null, frame)
        altKeys = altKeysOf("$name:l1")
        altAdd(altKeys, members.map { it.toByteArray() })
        reportMemory(name)
    }

    @TearDown
    fun tearDown() {
        connection.sync().del(hashKey.toByteArray(), *altKeys)
        connection.close()
        client.shutdown()
        cache.clear("hot")
        runtime.close()
        backend.close()
    }

    /** L2 命中：进程内查找，返回已解码的不可变集合，与成员数无关。 */
    @Benchmark
    fun setL2Hit(): Set<String> = cache["hot"]

    /** 读 L1（Hash）：一次 EVALSHA（`PTTL` + `GET` 类型不符 + `HGETALL`），客户端去掉成员前缀。 */
    @Benchmark
    fun setL1Read(): RemoteRead = store.read(hashKey, null, null)

    /** 读 L1（备选：原生 Set + 控制 Hash）：一次 EVALSHA（`PTTL` + `GET` 类型不符 + `HGET !f` + `SMEMBERS`）。 */
    @Benchmark
    fun setL1ReadAlt(): List<Any> = alt(ALT_READ, ScriptOutputType.MULTI, altKeys)

    /** 加入并移除一个成员（Hash）：两次 Lua（各含 PUBLISH），集合大小保持不变。 */
    @Benchmark
    fun setAddRemove() {
        store.addMembers(hashKey, churn, null, frame)
        store.removeMembers(hashKey, churn, null, frame)
    }

    /** 加入并移除一个成员（备选）：两次 Lua（各含 PUBLISH），每次涉及两个 key。 */
    @Benchmark
    fun setAddRemoveAlt() {
        altAdd(altKeys, churn)
        alt<Long>(ALT_REMOVE, ScriptOutputType.INTEGER, altKeys, NO_TTL, channel, frame, *churn.toTypedArray())
    }

    private fun altKeysOf(base: String) = arrayOf("cocache:{$base}:c".toByteArray(), "cocache:{$base}:s".toByteArray())

    private fun altAdd(keys: Array<ByteArray>, members: List<ByteArray>) {
        alt<Long>(ALT_ADD, ScriptOutputType.INTEGER, keys, NO_TTL, channel, frame, *members.toTypedArray())
    }

    /** 与生产的脚本执行一致：先 EVALSHA，`NOSCRIPT`（脚本缓存被清空）时退回 EVAL。 */
    private fun <T> alt(script: AltScript, type: ScriptOutputType, keys: Array<ByteArray>, vararg args: ByteArray): T {
        val commands = connection.sync()
        return try {
            commands.evalsha(script.sha, type, keys, *args)
        } catch (_: RedisNoScriptException) {
            commands.eval(script.source, type, keys, *args)
        }
    }

    /** 打印本成员数下两种编码、两类成员的 `MEMORY USAGE`（备选为两个 key 之和）。 */
    private fun reportMemory(name: String) {
        val commands = connection.sync()
        val rows = listOf("string" to ::stringMember, "integer" to ::integerMember).map { (kind, member) ->
            val members = (1..size).map { member(it).toByteArray() }
            val hash = "cocache:$name:mem-$kind"
            store.addMembers(hash, members, null, frame)
            val alt = altKeysOf("$name:mem-$kind")
            altAdd(alt, members)
            val hashBytes = memoryUsage(hash.toByteArray())
            val altBytes = alt.sumOf(::memoryUsage)
            val hashEncoding = commands.objectEncoding(hash.toByteArray())
            val setEncoding = commands.objectEncoding(alt[1])
            commands.del(hash.toByteArray(), *alt)
            "[setRead memory] size=$size members=$kind hash=$hashBytes B ($hashEncoding) " +
                "alt=$altBytes B (set $setEncoding + control hash) hash/alt=${"%.2f".format(hashBytes.toDouble() / altBytes)}"
        }
        rows.forEach(::println)
    }

    private fun memoryUsage(key: ByteArray): Long = requireNotNull(
        connection.sync().dispatch(
            CommandType.MEMORY,
            IntegerOutput(ByteArrayCodec.INSTANCE),
            CommandArgs(ByteArrayCodec.INSTANCE).add("USAGE").addKey(key).add("SAMPLES").add(0)
        )
    )

    companion object {
        private val NO_TTL = "0".toByteArray()
        private val ENCODED_CHURN = "member-churn".toByteArray()

        /** 非整数成员（如业务 ID 字符串）。 */
        fun stringMember(i: Int) = "member-%06d".format(i)

        /** 整数成员：原生 Set 可用 intset 编码（≤ `set-max-intset-entries`）。 */
        fun integerMember(i: Int) = (10_000_000 + i).toString()

        private val ALT_READ = AltScript(
            """
local pttl = redis.call('PTTL', KEYS[1])
if pttl == -2 then return {0, 0} end
local v = redis.pcall('GET', KEYS[1])
if type(v) == 'table' then
  local f = redis.call('HGET', KEYS[1], '!f')
  return {3, pttl, f, redis.call('SMEMBERS', KEYS[2])}
end
return {2, pttl, v}
"""
        )

        private val ALT_ADD = AltScript(
            """
local ctl, data = KEYS[1], KEYS[2]
if redis.call('TYPE', ctl)['ok'] ~= 'hash' or redis.call('HEXISTS', ctl, '!f') == 0 then
  redis.call('DEL', ctl, data)
  redis.call('HSET', ctl, '!f', '1')
end
for i = 4, #ARGV do redis.call('SADD', data, ARGV[i]) end
if tonumber(ARGV[1]) > 0 then
  redis.call('PEXPIRE', ctl, ARGV[1])
  redis.call('PEXPIRE', data, ARGV[1])
else
  redis.call('PERSIST', ctl)
  redis.call('PERSIST', data)
end
redis.call('PUBLISH', ARGV[2], ARGV[3])
return 1
"""
        )

        private val ALT_REMOVE = AltScript(
            """
local ctl, data = KEYS[1], KEYS[2]
if redis.call('TYPE', ctl)['ok'] == 'hash' and redis.call('HEXISTS', ctl, '!f') == 1 then
  for i = 4, #ARGV do redis.call('SREM', data, ARGV[i]) end
  if redis.call('SCARD', data) == 0 then
    redis.call('DEL', ctl, data)
  elseif tonumber(ARGV[1]) > 0 then
    redis.call('PEXPIRE', ctl, ARGV[1])
    redis.call('PEXPIRE', data, ARGV[1])
  else
    redis.call('PERSIST', ctl)
    redis.call('PERSIST', data)
  end
end
redis.call('PUBLISH', ARGV[2], ARGV[3])
return 1
"""
        )
    }
}

/** 备选编码的原型脚本（只在基准内使用）。 */
private class AltScript(source: String) {
    val source: ByteArray = source.toByteArray()
    val sha: String = MessageDigest.getInstance("SHA-1").digest(this.source).joinToString("") { "%02x".format(it) }
}
