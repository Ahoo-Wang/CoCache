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

package me.ahoo.cocache.api.spi

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Lookup
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 批量写入的默认实现：第三方实现不重写时逐个调用单 key 方法。
 */
class BatchWriteDefaultsTest {
    private class RecordingCache : Cache<String, String> {
        val puts = mutableListOf<Triple<String, String, Duration?>>()

        override val name: String = "recording"

        override fun get(key: String): String? = null

        override fun get(key: String, loader: CacheLoader<String, String>): String? = null

        override fun getAll(keys: Collection<String>): Map<String, String> = emptyMap()

        override fun lookup(key: String): Lookup<String> = Lookup.Absent(null)

        override fun put(key: String, value: String) {
            puts.add(Triple(key, value, null))
        }

        override fun put(key: String, value: String, ttl: Duration) {
            puts.add(Triple(key, value, ttl))
        }

        override fun invalidate(key: String) = Unit

        override fun invalidateAll(keys: Collection<String>) = Unit
    }

    private class RecordingStore : RemoteStore {
        val puts = mutableListOf<Pair<Put, ByteArray>>()

        override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead = RemoteRead.Miss(
            false
        )

        override fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> = emptyMap()

        override fun fill(fill: Fill): Boolean = false

        override fun fillAll(fills: List<Fill>): List<Boolean> = emptyList()

        override fun release(key: String, token: Token) = Unit

        override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
            puts.add(Put(key, envelope, ttl) to frame)
        }

        override fun invalidate(keys: Collection<String>, frame: ByteArray) = Unit

        override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean = false

        override fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) = Unit

        override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) = Unit
    }

    private val origin = NodeId.random()
    private val batch = listOf(
        Put("a", Envelope.Scalar("1".toByteArray()), null),
        Put("b", Envelope.Absent, Duration.ofSeconds(1)),
    )

    @Test
    fun cachePutAllCallsPutForEachEntry() {
        val cache = RecordingCache()
        cache.putAll(linkedMapOf("a" to "1", "b" to "2"))
        cache.putAll(mapOf("c" to "3"), Duration.ofSeconds(5))
        cache.puts.assert().containsExactly(
            Triple("a", "1", null),
            Triple("b", "2", null),
            Triple("c", "3", Duration.ofSeconds(5)),
        )
    }

    @Test
    fun storePutAllPutsEachKeyWithItsOwnFrame() {
        val store = RecordingStore()
        store.putAll(batch, InvalidationFrame.encode(origin, listOf("b", "a")))
        store.puts.map { it.first }.assert().isEqualTo(batch)
        store.puts.map { InvalidationFrame.decode(it.second) }.assert().containsExactly(
            InvalidationFrame.Decoded(origin, listOf("a")),
            InvalidationFrame.Decoded(origin, listOf("b")),
        )
    }

    @Test
    fun storePutAllKeepsFramesThatCannotBeSplit() {
        val store = RecordingStore()
        val foreign = InvalidationFrame.encode(origin, listOf("other"))
        store.putAll(batch, foreign)
        store.puts.forEach { it.second.assert().isSameAs(foreign) }
        val malformed = byteArrayOf(9)
        store.puts.clear()
        store.putAll(batch, malformed)
        store.puts.size.assert().isEqualTo(2)
        store.puts.forEach { it.second.assert().isSameAs(malformed) }
    }

    @Test
    fun putComparesByValue() {
        Put("a", Envelope.Absent, null).assert().isEqualTo(Put("a", Envelope.Absent, null))
        Put("a", Envelope.Absent, null).toString().assert().contains("a")
    }
}
