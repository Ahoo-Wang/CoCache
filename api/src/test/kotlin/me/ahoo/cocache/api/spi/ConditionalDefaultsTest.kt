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
 * SPI 默认实现（第三方后端不重写时）：
 * - `readIfChanged`：先读再在客户端比较摘要（正确，只是不省带宽）。未命中、lease、负缓存是没有值；格式低于 1 的内容与集合读不懂。
 * - `putIfMatch`：不提供（抛出 / 以 [UnsupportedOperationException] 完成），因为“先读后写”会丢失更新；不读、不写。
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

    @Test
    fun missesLeasesAndNegativeEntriesHoldNoValue() {
        val empty = listOf(
            RemoteRead.Miss(false),
            RemoteRead.Leased(Duration.ofSeconds(1)),
            RemoteRead.Hit(Envelope.Absent, 1, null),
        )
        for (read in empty) {
            RecordingStore(read).reads(digest).assert().containsOnly(RemoteConditionalRead.Absent)
        }
    }

    @Test
    fun valuesCompareByPayloadDigestAndReportTheirFormat() {
        val store = RecordingStore(RemoteRead.Hit(value, 1, null))
        store.reads(digest).assert().containsOnly(RemoteConditionalRead.Unchanged(1))
        store.reads(other).forEach { read ->
            val changed = read as RemoteConditionalRead.Changed
            changed.envelope.assert().isEqualTo(value)
            changed.format.assert().isEqualTo(1)
            changed.toString().assert().contains("format=1")
        }
        (store.readIfChanged("k", null) as RemoteConditionalRead.Changed).envelope.assert().isEqualTo(value)
        RecordingStore(
            RemoteRead.Hit(value, 2, null)
        ).reads(digest).assert().containsOnly(RemoteConditionalRead.Unchanged(2))
    }

    @Test
    fun unreadableContentIsReported() {
        RecordingStore(RemoteRead.Hit(Envelope.Scalar("{}".toByteArray()), 0, null)).reads(null)
            .assert().containsOnly(RemoteConditionalRead.Unreadable(0))
        RecordingStore(RemoteRead.Hit(Envelope.Members(listOf("m".toByteArray())), 1, null)).reads(null)
            .assert().containsOnly(RemoteConditionalRead.Unreadable(1))
    }

    /**
     * 条件写入没有默认实现：不原子的“先读后写”会让两个并发的写入方都成功，因此宁可失败，也不读、不写。
     */
    @Test
    fun conditionalWritesAreUnsupportedByDefault() {
        val store = RecordingStore(RemoteRead.Miss(false))
        runCatching { store.putIfMatch("k", null, value, null, frame, false) }.exceptionOrNull()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
        store.putIfMatchAsync("k", digest, value, null, frame, true).handle { _, error -> error }.join()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
        store.writes.assert().isEmpty()
    }
}
