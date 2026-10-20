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
 * `addMembersIfReadable` / `removeMembersIfReadable` 的默认实现（第三方后端不重写时）：先读再写，
 * 命中不是本协议集合的内容（格式低于 1，或标量值）时返回其格式版本且不写入；负缓存、lease、不存在照常写入。
 */
class SetWriteDefaultsTest {
    private class RecordingStore(var current: RemoteRead) : AsyncRemoteStore {
        val writes = mutableListOf<String>()

        override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead = current

        override fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> = emptyMap()

        override fun fill(fill: Fill): Boolean = false

        override fun fillAll(fills: List<Fill>): List<Boolean> = emptyList()

        override fun release(key: String, token: Token) = Unit

        override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) = Unit

        override fun invalidate(keys: Collection<String>, frame: ByteArray) = Unit

        override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean = false

        override fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
            writes.add("add")
        }

        override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
            writes.add("remove")
        }

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

        override fun putAsync(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
            CompletableFuture.completedFuture(null)

        override fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = CompletableFuture.completedFuture(
            null
        )

        override fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> =
            CompletableFuture.completedFuture(null)

        override fun reclaimAsync(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean> =
            CompletableFuture.completedFuture(false)

        override fun addMembersAsync(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> {
            writes.add("addAsync")
            return CompletableFuture.completedFuture(null)
        }

        override fun removeMembersAsync(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Void?> {
            writes.add("removeAsync")
            return CompletableFuture.completedFuture(null)
        }
    }

    private val members = listOf("a".toByteArray())
    private val frame = ByteArray(0)

    private fun RecordingStore.all(): List<Int?> = listOf(
        addMembersIfReadable("k", members, null, frame),
        removeMembersIfReadable("k", members, null, frame),
        addMembersIfReadableAsync("k", members, null, frame).join(),
        removeMembersIfReadableAsync("k", members, null, frame).join(),
    )

    @Test
    fun writesWhenTheKeyIsAbsentNegativeLeasedOrASet() {
        val readable = listOf(
            RemoteRead.Miss(false),
            RemoteRead.Leased(Duration.ofSeconds(1)),
            RemoteRead.Hit(Envelope.Absent, 1, null),
            RemoteRead.Hit(Envelope.Members(members), 1, null),
        )
        for (read in readable) {
            val store = RecordingStore(read)
            store.all().assert().containsOnlyNulls()
            store.writes.assert().containsExactly("add", "remove", "addAsync", "removeAsync")
        }
    }

    @Test
    fun refusesForeignContentAndScalarsWithoutWriting() {
        val foreign = RecordingStore(RemoteRead.Hit(Envelope.Members(members), 0, null))
        foreign.all().assert().containsExactly(0, 0, 0, 0)
        foreign.writes.assert().isEmpty()

        val scalar = RecordingStore(RemoteRead.Hit(Envelope.Scalar("x".toByteArray()), 1, null))
        scalar.all().assert().containsExactly(1, 1, 1, 1)
        scalar.writes.assert().isEmpty()
    }
}
