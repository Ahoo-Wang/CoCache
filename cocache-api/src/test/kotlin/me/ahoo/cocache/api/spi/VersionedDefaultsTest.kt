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

import me.ahoo.cocache.api.Versioned
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 按版本有序的写入（6.8）在 SPI 上的默认值：后端默认不支持（`versionedWrites = false`），`putIfNewer` 不提供“先读后写”的实现
 * （抛出 / 以 [UnsupportedOperationException] 完成）；[Envelope.Scalar] 的版本默认是 [Versioned.NO_VERSION]，参与相等比较、不影响摘要。
 */
class VersionedDefaultsTest {
    private class PlainStore : AsyncRemoteStore {
        override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead = RemoteRead.Miss(
            false
        )
        override fun readAll(
            keys: Collection<String>,
            leases: Map<String, LeaseRequest>
        ): Map<String, RemoteRead> = emptyMap()
        override fun fill(fill: Fill): Boolean = false
        override fun fillAll(fills: List<Fill>): List<Boolean> = emptyList()
        override fun release(key: String, token: Token) = Unit
        override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) = Unit
        override fun invalidate(keys: Collection<String>, frame: ByteArray) = Unit
        override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean = false
        override fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) = Unit
        override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) = Unit
        override fun readAsync(
            key: String,
            lease: LeaseRequest?,
            refresh: RefreshRequest?
        ): CompletableFuture<RemoteRead> =
            CompletableFuture.completedFuture(RemoteRead.Miss(false))
        override fun readAllAsync(
            keys: Collection<String>,
            leases: Map<String, LeaseRequest>
        ): CompletableFuture<Map<String, RemoteRead>> =
            CompletableFuture.completedFuture(emptyMap())
        override fun fillAsync(fill: Fill): CompletableFuture<Boolean> = CompletableFuture.completedFuture(false)
        override fun fillAllAsync(
            fills: List<Fill>
        ): CompletableFuture<List<Boolean>> = CompletableFuture.completedFuture(
            emptyList()
        )
        override fun releaseAsync(
            key: String,
            token: Token
        ): CompletableFuture<Void?> = CompletableFuture.completedFuture(
            null
        )
        override fun putAsync(
            key: String,
            envelope: Envelope,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Void?> =
            CompletableFuture.completedFuture(null)
        override fun putAllAsync(
            puts: List<Put>,
            frame: ByteArray
        ): CompletableFuture<Void?> = CompletableFuture.completedFuture(
            null
        )
        override fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> =
            CompletableFuture.completedFuture(null)
        override fun reclaimAsync(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean> =
            CompletableFuture.completedFuture(false)
        override fun addMembersAsync(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Void?> =
            CompletableFuture.completedFuture(null)
        override fun removeMembersAsync(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)
    }

    private class PlainBackend : CoherenceBackend {
        override val atomicBroadcast: Boolean = true
        override val format: Int = 1
        override val channel: InvalidationChannel
            get() = throw UnsupportedOperationException()

        override fun store(namespace: String): RemoteStore = PlainStore()
    }

    @Test
    fun backendsDoNotSupportVersionsByDefault() {
        PlainBackend().versionedWrites.assert().isFalse()
        val store = PlainStore()
        runCatching { store.putIfNewer("k", Envelope.Absent, 1, null, ByteArray(0), false) }.exceptionOrNull()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
        store.putIfNewerAsync("k", Envelope.Absent, 1, null, ByteArray(0), false).handle { _, error -> error }.join()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun scalarVersionTakesPartInEqualityButNotInTheDigest() {
        val plain = Envelope.Scalar("v".toByteArray())
        val versioned = Envelope.Scalar("v".toByteArray(), 7)
        plain.version.assert().isEqualTo(Versioned.NO_VERSION)
        versioned.version.assert().isEqualTo(7L)
        plain.assert().isNotEqualTo(versioned)
        versioned.assert().isEqualTo(Envelope.Scalar("v".toByteArray(), 7))
        versioned.hashCode().assert().isEqualTo(Envelope.Scalar("v".toByteArray(), 7).hashCode())
        Digest.of(versioned).assert().isEqualTo(Digest.of(plain))
        plain.toString().assert().isEqualTo("Scalar(1 bytes)")
        versioned.toString().assert().isEqualTo("Scalar(1 bytes, version=7)")
        RemoteVersionedWrite.Stale(3).current.assert().isEqualTo(3L)
        RemoteVersionedWrite.Unreadable(2).format.assert().isEqualTo(2)
    }

    /**
     * [Fill.version]：四个参数的构造函数取标量的版本（负缓存没有版本）；墓碑经带版本的构造函数给出。
     * 版本参与相等比较；`copy` 按信封重新取值（不保留墓碑的版本，与 6.7 的二进制兼容）。
     */
    @Test
    fun fillCarriesTheVersionOfItsContent() {
        val token = Token(NodeId.random(), 1)
        Fill("k", token, Envelope.Scalar("v".toByteArray(), 4), null).version.assert().isEqualTo(4L)
        Fill("k", token, Envelope.Absent, null).version.assert().isEqualTo(Versioned.NO_VERSION)
        val tombstone = Fill("k", token, Envelope.Absent, null, 7)
        tombstone.version.assert().isEqualTo(7L)
        tombstone.assert().isEqualTo(Fill("k", token, Envelope.Absent, null, 7))
        tombstone.hashCode().assert().isEqualTo(Fill("k", token, Envelope.Absent, null, 7).hashCode())
        tombstone.assert().isNotEqualTo(Fill("k", token, Envelope.Absent, null))
        tombstone.assert().isNotEqualTo(Fill("other", token, Envelope.Absent, null, 7))
        tombstone.equals(tombstone).assert().isTrue()
        tombstone.equals("k").assert().isFalse()
        tombstone.toString().assert().contains("version=7")
        tombstone.copy().version.assert().isEqualTo(Versioned.NO_VERSION)
    }
}
