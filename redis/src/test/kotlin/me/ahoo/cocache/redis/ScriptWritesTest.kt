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

import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1SetWrite
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * 写脚本的调用形状（#908 §2.2 第 5 项）：标量写入是一条 `SET k v [PX ms]`，集合成员按块以多字段 `HSET` / `HDEL` 写入。
 * 线格式与到期时间不变：覆盖任何类型的旧值、清除旧的到期时间（I-8），跨块的集合完整写入与删除。
 */
class ScriptWritesTest {
    private val namespace = "writes-" + UUID.randomUUID()
    private val keyspace = Keyspace("cocache:", namespace)

    private fun key(name: String) = "cocache:$namespace:$name"

    private fun pttl(key: String): Long = RedisTestSupport.withCommands { it.pttl(key) }

    private fun type(key: String): String = RedisTestSupport.withCommands { it.type(key) }

    private fun members(count: Int, prefix: String = "m"): List<ByteArray> = (1..count).map { "$prefix$it".toByteArray() }

    /**
     * 标量写入覆盖集合（Hash）与旧的到期时间；永不过期的写入清除到期时间，带寿命的写入设置它。
     */
    @Test
    fun aScalarWriteReplacesAnyTypeAndItsExpiry() {
        RedisTestSupport.backend().use { backend ->
            val store = backend.store(keyspace)
            val k = key("scalar")
            Suspending.await { store.put(k, Envelope.Members(members(3)), Duration.ofSeconds(30), ByteArray(0)) }
            type(k).assert().isEqualTo("hash")
            Suspending.await { store.put(k, Envelope.Scalar("v".toByteArray()), null, ByteArray(0)) }
            type(k).assert().isEqualTo("string")
            pttl(k).assert().isEqualTo(-1L)
            Suspending.await { store.put(k, Envelope.Scalar("w".toByteArray()), Duration.ofSeconds(30), ByteArray(0)) }
            pttl(k).assert().isBetween(1L, 30_000L)
            Suspending.await { store.put(k, Envelope.Absent, null, ByteArray(0)) }
            pttl(k).assert().isEqualTo(-1L)
            val hit = Suspending.await { store.read(k, null, null, null) }
            (hit as L1Read.Hit).envelope.assert().isEqualTo(Envelope.Absent)
        }
    }

    /**
     * 多于一块（[RedisScripts.MEMBER_CHUNK]）的集合：整体写入、增加与删除都覆盖每一个成员，`!f` 只写一次。
     */
    @Test
    fun setsLargerThanAChunkAreWrittenCompletely() {
        val total = RedisScripts.MEMBER_CHUNK * 2 + 7
        RedisTestSupport.backend().use { backend ->
            val store = backend.store(keyspace)
            val sets = store.setSurface
            val k = key("large")
            Suspending.await { store.putAll(listOf(Put(k, Envelope.Members(members(total)), null)), ByteArray(0)) }
            readMembers(store, k).size.assert().isEqualTo(total)
            RedisTestSupport.withCommands { it.hlen(k) }.assert().isEqualTo(total + 1L)

            val added = Suspending.await { sets.addMembers(k, members(total, "n"), null, ByteArray(0)) }
            added.assert().isEqualTo(L1SetWrite.Written)
            readMembers(store, k).size.assert().isEqualTo(total * 2)

            // 新建的集合（不存在的 key）同样只在第一块前写入 `!f`
            val fresh = key("fresh")
            Suspending.await { sets.addMembers(fresh, members(total), Duration.ofSeconds(30), ByteArray(0)) }
            RedisTestSupport.withCommands { it.hlen(fresh) }.assert().isEqualTo(total + 1L)
            pttl(fresh).assert().isBetween(1L, 30_000L)

            Suspending.await { sets.removeMembers(k, members(total), null, ByteArray(0)) }
            readMembers(store, k).map { String(it) }.assert().allMatch { it.startsWith("n") }
            Suspending.await { sets.removeMembers(k, members(total, "n"), null, ByteArray(0)) }
            // 移空即删除
            type(k).assert().isEqualTo("none")
        }
    }

    private fun readMembers(store: me.ahoo.cocache.api.spi.L1Store, key: String): List<ByteArray> {
        val hit = Suspending.await { store.read(key, null, null, null) } as L1Read.Hit
        return (hit.envelope as Envelope.Members).members
    }
}
