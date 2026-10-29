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

import io.lettuce.core.ScriptOutputType
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.redis.RedisInvalidationChannel
import me.ahoo.cocache.redis.RedisTestSupport
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException

/**
 * 没有数据源的缓存（Redis 是唯一副本）在迁移之前的写入与混合版本读取：
 * - `SetCache` 的 `add` / `remove` 落在读不懂的内容上（5.x 原生 Set、没有 `!f` 的 Hash、5.x String、更高 `!f`）时默认失败，
 *   key 原样保留、不广播——此前一次 `add` 会用一个成员替换掉全部旧成员，且之后 key 看起来已经迁移，迁移工具会跳过它；
 * - 更高格式版本的标量值在没有数据源时默认失败，而不是读成不存在。
 */
class ForeignFormatWriteTest {
    private val run = UUID.randomUUID().toString()
    private val backend = RedisTestSupport.backend()
    private val runtime = CacheRuntime.builder().backend(backend).build()

    @AfterEach
    fun close() {
        runtime.close()
        backend.close()
        RedisTestSupport.deleteKeys("*$run*")
    }

    private fun index(name: String, mode: ForeignFormatMode? = null): SetCache<String, String> = runtime.create(
        setCacheSpec(name) {
            elementCodec = StringCodec
            mode?.let { policy { foreignFormat = it } }
        }
    )

    private fun assertForeignWrite(error: Throwable?, storageKey: String, operation: String, format: Int = 0) {
        error.assert().isInstanceOf(CacheWriteException::class.java)
        (error as CacheWriteException).operation.assert().isEqualTo(operation)
        val cause = requireNotNull(error.cause as? ForeignFormatException)
        cause.storageKey.assert().isEqualTo(storageKey)
        cause.format.assert().isEqualTo(format)
    }

    /**
     * CoSec 的全局 DENY 策略索引：5.x 原生 Set，没有迁移就升级。`add` / `remove`（同步与异步）都失败，成员一个不少。
     */
    @Test
    fun memberWritesOnUnmigratedNativeSetFailAndKeepIt() {
        val name = "deny-$run"
        val key = "cocache:$name:"
        Redis5x.set(key, listOf("p1", "p2"), ttlSeconds = 3600)
        val cache = index(name)

        assertForeignWrite(runCatching { cache.add("", listOf("p3")) }.exceptionOrNull(), key, "add")
        assertForeignWrite(runCatching { cache.remove("", listOf("p1")) }.exceptionOrNull(), key, "remove")
        val asyncError = runCatching { cache.async().add("", listOf("p3")).get() }.exceptionOrNull()
        asyncError.assert().isInstanceOf(ExecutionException::class.java)
        assertForeignWrite(asyncError?.cause, key, "add")
        val asyncRemove = runCatching { cache.async().remove("", listOf("p1")).get() }.exceptionOrNull()
        assertForeignWrite(asyncRemove?.cause, key, "remove")

        Redis5x.type(key).assert().isEqualTo("set")
        Redis5x.members(key).assert().containsExactlyInAnyOrder("p1", "p2")
        Redis5x.pttl(key).assert().isBetween(1L, 3_600_000L)
        requireNotNull(runtime.metrics(name)).unknownFormats.assert().isEqualTo(4L)

        // 迁移工具仍然认得它：迁移之后写入照常
        val report = RedisTestSupport.migrator().use { migrator ->
            migrator.migrate(CoCache5Migration.set(name, StringCodec).sourceKey(key, "").build())
        }
        report.cache(name)?.written.assert().isEqualTo(1L)
        cache.add("", listOf("p3"))
        cache[""].assert().containsExactlyInAnyOrder("p1", "p2", "p3")
    }

    @Test
    fun memberWritesOnOtherForeignContentFail() {
        val name = "other-$run"
        val cache = index(name)
        val hash = "cocache:$name:hash"
        val string = "cocache:$name:string"
        val list = "cocache:$name:list"
        Redis5x.hash(hash, mapOf("+a" to ""))
        Redis5x.string(string, "[\"a\"]")
        RedisTestSupport.withCommands { it.rpush(list, "a") }

        assertForeignWrite(runCatching { cache.add("hash", listOf("b")) }.exceptionOrNull(), hash, "add")
        assertForeignWrite(runCatching { cache.remove("hash", listOf("a")) }.exceptionOrNull(), hash, "remove")
        assertForeignWrite(runCatching { cache.add("string", listOf("b")) }.exceptionOrNull(), string, "add")
        assertForeignWrite(runCatching { cache.add("list", listOf("b")) }.exceptionOrNull(), list, "add")

        Redis5x.fields(hash).assert().isEqualTo(mapOf("+a" to ""))
        Redis5x.get(string).assert().isEqualTo("[\"a\"]")
        RedisTestSupport.withCommands { it.lrange(list, 0, -1) }.assert().containsExactly("a")
    }

    /**
     * 更高版本写入的集合（`!f` 大于本节点的格式）：本节点读不懂，也不按本版本写入成员。
     */
    @Test
    fun memberWritesOnNewerFormatSetFail() {
        val name = "newer-$run"
        val key = "cocache:$name:k"
        Redis5x.hash(key, mapOf("!f" to "2", "+a" to ""))
        val cache = index(name)

        assertForeignWrite(runCatching { cache.add("k", listOf("b")) }.exceptionOrNull(), key, "add", format = 2)
        assertForeignWrite(runCatching { cache.remove("k", listOf("a")) }.exceptionOrNull(), key, "remove", format = 2)
        Redis5x.fields(key).assert().isEqualTo(mapOf("!f" to "2", "+a" to ""))
    }

    /**
     * 拒绝的写入不广播（脚本原子地检查，什么都不做就返回）：之后的写入的帧是唯一收到的帧。
     */
    @Test
    fun refusedWriteDoesNotBroadcast() {
        val name = "silent-$run"
        val key = "cocache:$name:k"
        Redis5x.set(key, listOf("p1"))
        val cache = index(name)
        val frames = CopyOnWriteArrayList<ByteArray>()
        RedisTestSupport.subscribeRaw(RedisInvalidationChannel.channelOf(name)) { frames.add(it) }.use {
            runCatching {
                cache.add(
                    "k",
                    listOf("p2")
                )
            }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
            runCatching {
                cache.remove(
                    "k",
                    listOf("p1")
                )
            }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
            cache.add("marker", listOf("m"))
            awaitCondition("marker broadcast") { frames.isNotEmpty() }
            frames.map { InvalidationFrame.decode(it).keys }.assert().containsExactly(listOf("cocache:$name:marker"))
        }
    }

    /**
     * 显式 `RECLAIM`：6.5.0 的行为，`add` 整体覆盖读不懂的内容，`remove` 不修改它。
     */
    @Test
    fun memberWritesReplaceForeignContentWhenReclaimIsConfigured() {
        val name = "reclaim-$run"
        val key = "cocache:$name:k"
        Redis5x.set(key, listOf("p1"))
        val cache = index(name, ForeignFormatMode.RECLAIM)
        cache.remove("k", listOf("p1"))
        Redis5x.members(key).assert().containsExactly("p1")
        cache.add("k", listOf("p2"))
        cache["k"].assert().containsExactly("p2")
        Redis5x.type(key).assert().isEqualTo("hash")
    }

    /**
     * 整体覆盖（`put`）不检查旧内容：调用方给出的是全部内容。代价是迁移工具不会再为这个 key 留备份。
     */
    @Test
    fun putReplacesForeignContent() {
        val name = "put-$run"
        val key = "cocache:$name:k"
        Redis5x.set(key, listOf("p1"))
        val cache = index(name)
        cache.put("k", setOf("p2"))
        cache["k"].assert().containsExactly("p2")
    }

    /**
     * 混合版本滚动升级：更高版本的节点写入的标量值。没有数据源时默认失败（同步、批量、异步），key 原样保留。
     */
    @Test
    fun newerFormatValueWithoutLoaderFailsAndIsKept() {
        val name = "newer-value-$run"
        val key = "cocache:$name:k"
        // 36 字节头：magic、格式版本 2、状态 V、保留、24 字节令牌、8 字节签发时间；载荷 "v"
        RedisTestSupport.withCommands {
            it.eval<String>(
                "return redis.call('SET', KEYS[1], '\\192\\2V\\0' .. string.rep('\\0', 32) .. 'v')",
                ScriptOutputType.STATUS,
                key
            )
        }
        val cache = runtime.create(
            cacheSpec<String, String>(name) {
                codec = StringCodec
                policy { ttl = null }
            }
        )

        fun assertNewer(error: Throwable?) {
            error.assert().isInstanceOf(CacheReadException::class.java)
            val cause = requireNotNull(error?.cause as? ForeignFormatException)
            cause.storageKey.assert().isEqualTo(key)
            cause.format.assert().isEqualTo(2)
        }
        assertNewer(runCatching { cache["k"] }.exceptionOrNull())
        assertNewer(runCatching { cache.getAll(listOf("k", "absent")) }.exceptionOrNull())
        assertNewer(runCatching { cache.async().get("k").get() }.exceptionOrNull()?.cause)
        assertNewer(runCatching { cache.async().getAll(listOf("k", "absent")).get() }.exceptionOrNull()?.cause)
        requireNotNull(runtime.metrics(name)).unknownFormats.assert().isEqualTo(4L)
        Redis5x.exists(key).assert().isTrue()
        Redis5x.exists("cocache:$name:absent").assert().withFailMessage { "no lease left behind" }.isFalse()
    }

    /**
     * 存储层的判定（脚本内原子完成）：不存在、负缓存、lease 占位、本版本的集合可以写；其余拒绝并返回格式版本。
     */
    @Test
    fun storeChecksEveryShapeAtomically() {
        val namespace = "store-$run"
        val store = backend.store(namespace) as AsyncRemoteStore
        fun key(name: String) = "cocache:$namespace:$name"
        val members = listOf("m".toByteArray())
        val frame = InvalidationFrame.encode(NodeId.random(), emptyList())

        store.put(key("negative"), Envelope.Absent, null, frame)
        store.read(key("leased"), LeaseRequest(Token(NodeId.random(), 1), Duration.ofMinutes(1)), null)
        store.addMembers(key("set"), members, null, frame)
        store.put(key("scalar"), Envelope.Scalar("v".toByteArray()), null, frame)
        Redis5x.hash(key("invalid"), mapOf("!f" to "x", "+a" to ""))
        Redis5x.hash(key("zero"), mapOf("!f" to "0", "+a" to ""))
        // 本协议的 String 头，但状态字节不认识
        RedisTestSupport.withCommands {
            it.eval<String>(
                "return redis.call('SET', KEYS[1], '\\192\\1X\\0' .. string.rep('\\0', 32))",
                ScriptOutputType.STATUS,
                key("unknown-state")
            )
        }

        store.addMembersIfReadable(key("absent"), members, null, frame).assert().isNull()
        store.addMembersIfReadable(key("negative"), members, null, frame).assert().isNull()
        store.addMembersIfReadableAsync(key("leased"), members, Duration.ofMinutes(1), frame).join().assert().isNull()
        store.removeMembersIfReadableAsync(
            key("set"),
            listOf("x".toByteArray()),
            Duration.ofMinutes(1),
            frame
        ).join().assert().isNull()
        Redis5x.type(key("negative")).assert().isEqualTo("hash")
        Redis5x.type(key("leased")).assert().isEqualTo("hash")
        store.put(key("negative"), Envelope.Absent, null, frame)
        store.removeMembersIfReadable(key("negative"), members, null, frame).assert().isNull()
        Redis5x.type(key("negative")).assert().isEqualTo("string")
        Redis5x.pttl(key("set")).assert().isBetween(1L, 60_000L)
        store.removeMembersIfReadable(key("set"), members, null, frame).assert().isNull()
        Redis5x.exists(key("set")).assert().isFalse()

        store.addMembersIfReadable(key("scalar"), members, null, frame).assert().isEqualTo(1)
        store.removeMembersIfReadableAsync(key("scalar"), members, null, frame).join().assert().isEqualTo(1)
        store.addMembersIfReadable(key("invalid"), members, null, frame).assert().isEqualTo(0)
        store.addMembersIfReadable(key("zero"), members, null, frame).assert().isEqualTo(0)
        store.addMembersIfReadable(key("unknown-state"), members, null, frame).assert().isEqualTo(0)
        Redis5x.fields(key("invalid")).assert().isEqualTo(mapOf("!f" to "x", "+a" to ""))
        Redis5x.fields(key("zero")).assert().isEqualTo(mapOf("!f" to "0", "+a" to ""))
    }
}
