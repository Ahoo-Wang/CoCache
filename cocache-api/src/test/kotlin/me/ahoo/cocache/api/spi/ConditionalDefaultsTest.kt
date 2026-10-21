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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * `readIfChanged` / `putIfMatch` 的默认实现（第三方后端不重写时）：先读再比较摘要，摘要相符时再 `put`（不原子）。
 * 未命中、lease、负缓存是没有值；格式低于 1 的内容与集合读不懂。
 */
class ConditionalDefaultsTest {
    private class RecordingStore(var current: RemoteRead) : AsyncRemoteStore {
        val writes = mutableListOf<String>()

        override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead = current

        override fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> = emptyMap()

        override fun fill(fill: Fill): Boolean = false

        override fun fillAll(fills: List<Fill>): List<Boolean> = emptyList()

        override fun release(key: String, token: Token) = Unit

        override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
            writes.add("put")
        }

        override fun invalidate(keys: Collection<String>, frame: ByteArray) = Unit

        override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean = false

        override fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) = Unit

        override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) = Unit

        override fun readAsync(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead> =
            CompletableFuture.completedFuture(current)

        override fun readAllAsync(keys: Collection<String>, leases: Map<String, LeaseRequest>): CompletableFuture<Map<String, RemoteRead>> =
            CompletableFuture.completedFuture(emptyMap())

        override fun fillAsync(fill: Fill): CompletableFuture<Boolean> = CompletableFuture.completedFuture(false)

        override fun fillAllAsync(fills: List<Fill>): CompletableFuture<List<Boolean>> = CompletableFuture.completedFuture(
            emptyList()
        )

        override fun releaseAsync(key: String, token: Token): CompletableFuture<Void?> = CompletableFuture.completedFuture(
            null
        )

        override fun putAsync(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> {
            writes.add("putAsync")
            return CompletableFuture.completedFuture(null)
        }

        override fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = CompletableFuture.completedFuture(
            null
        )

        override fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> =
            CompletableFuture.completedFuture(null)

        override fun reclaimAsync(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean> =
            CompletableFuture.completedFuture(false)

        override fun addMembersAsync(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
            CompletableFuture.completedFuture(null)

        override fun removeMembersAsync(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)
    }

    private val value = Envelope.Scalar("v".toByteArray())
    private val digest = Digest.of(value)
    private val other = Digest.of(Envelope.Scalar("other".toByteArray()))
    private val frame = ByteArray(0)

    private fun RecordingStore.reads(known: Digest?): List<RemoteConditionalRead> = listOf(
        readIfChanged("k", known),
        readIfChangedAsync("k", known).join(),
    )

    private fun RecordingStore.writes(expected: Digest?, unreadableAsAbsent: Boolean = false): List<RemoteConditionalWrite> = listOf(
        putIfMatch("k", expected, value, null, frame, unreadableAsAbsent),
        putIfMatchAsync("k", expected, value, null, frame, unreadableAsAbsent).join(),
    )

    @Test
    fun missesLeasesAndNegativeEntriesHoldNoValue() {
        val empty = listOf(
            RemoteRead.Miss(false),
            RemoteRead.Leased(Duration.ofSeconds(1)),
            RemoteRead.Hit(Envelope.Absent, 1, null),
        )
        for (read in empty) {
            val store = RecordingStore(read)
            store.reads(digest).assert().containsOnly(RemoteConditionalRead.Absent)
            store.writes(digest).assert().containsOnly(RemoteConditionalWrite.Conflict(null))
            store.writes.assert().isEmpty()
            store.writes(null).assert().containsOnly(RemoteConditionalWrite.Written)
            store.writes.assert().containsExactly("put", "putAsync")
        }
    }

    @Test
    fun valuesCompareByPayloadDigest() {
        val store = RecordingStore(RemoteRead.Hit(value, 1, null))
        store.reads(digest).assert().containsOnly(RemoteConditionalRead.Unchanged)
        store.reads(other).forEach { read ->
            val changed = read as RemoteConditionalRead.Changed
            changed.envelope.assert().isEqualTo(value)
            changed.format.assert().isEqualTo(1)
            changed.toString().assert().contains("format=1")
        }
        (store.readIfChanged("k", null) as RemoteConditionalRead.Changed).envelope.assert().isEqualTo(value)
        store.writes(null).assert().containsOnly(RemoteConditionalWrite.Conflict(digest))
        store.writes(other).assert().containsOnly(RemoteConditionalWrite.Conflict(digest))
        store.writes.assert().isEmpty()
        store.writes(digest).assert().containsOnly(RemoteConditionalWrite.Written)
        store.writes.assert().containsExactly("put", "putAsync")
    }

    @Test
    fun unreadableContentIsRefusedUnlessTreatedAsAbsent() {
        val foreign = RecordingStore(RemoteRead.Hit(Envelope.Scalar("{}".toByteArray()), 0, null))
        foreign.reads(null).assert().containsOnly(RemoteConditionalRead.Unreadable(0))
        foreign.writes(null).assert().containsOnly(RemoteConditionalWrite.Unreadable(0))
        val set = RecordingStore(RemoteRead.Hit(Envelope.Members(listOf("m".toByteArray())), 1, null))
        set.reads(null).assert().containsOnly(RemoteConditionalRead.Unreadable(1))
        set.writes(null).assert().containsOnly(RemoteConditionalWrite.Unreadable(1))
        set.writes.assert().isEmpty()
        set.writes(digest, unreadableAsAbsent = true).assert().containsOnly(RemoteConditionalWrite.Conflict(null))
        set.writes(null, unreadableAsAbsent = true).assert().containsOnly(RemoteConditionalWrite.Written)
        set.writes.assert().containsExactly("put", "putAsync")
    }
}
