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

package me.ahoo.cocache.core.memory

import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.ForwardingConditionalL1Store
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.ForwardingSetL1Store
import me.ahoo.cocache.api.spi.ForwardingVersionedL1Store
import me.ahoo.cocache.api.spi.L1ConditionalWrite
import me.ahoo.cocache.api.spi.L1Eviction
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1SetWrite
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.api.spi.VersionedL1Store
import java.time.Duration

/**
 * [InMemoryBackend.readOnlyStore] 的存储：只转发不带令牌的读取与条件读取，其余操作（包括能力面上的写入）以
 * [UnsupportedOperationException] 拒绝、不触及被包装的存储。引擎在写入修改任何一层之前就拒绝它，这里的拒绝只是防御：
 * 测试据此确认只读的缓存从不把写入交给 L1。能力访问器与被包装者相同（有则有、无则无），每次返回同一个对象。
 */
internal class ReadOnlyL1Store(delegate: L1Store) : ForwardingL1Store(delegate) {
    private val conditional: ConditionalL1Store? = delegate.conditional()?.let { ReadOnlyConditional(it) }
    private val versioned: VersionedL1Store? = delegate.versioned()?.let { ReadOnlyVersioned(it) }
    private val sets: SetL1Store? = delegate.sets()?.let { ReadOnlySets(it) }

    override suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): L1Read {
        require(lease == null && refresh == null) { tokenRefused(key) }
        return delegate.read(key, null, null, timeout)
    }

    override suspend fun readAll(
        keys: Collection<String>,
        leases: Map<String, LeaseRequest>,
        timeout: Duration?
    ): Map<String, L1Read> {
        require(leases.isEmpty()) { tokenRefused(leases.keys.first()) }
        return delegate.readAll(keys, emptyMap(), timeout)
    }

    override suspend fun fill(fill: Fill): L1Fill = refuse("fill")

    override suspend fun fillAll(fills: List<Fill>): List<L1Fill> = refuse("fillAll")

    override suspend fun release(key: String, token: Token) = refuse("release")

    override suspend fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean = refuse(
        "reclaim"
    )

    override suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) = refuse("put")

    override suspend fun putAll(puts: List<Put>, frame: ByteArray) = refuse("putAll")

    override suspend fun invalidate(keys: Collection<String>, frame: ByteArray) = refuse("invalidate")

    override fun conditional(): ConditionalL1Store? = conditional

    override fun versioned(): VersionedL1Store? = versioned

    override fun sets(): SetL1Store? = sets

    override fun toString(): String = "ReadOnlyL1Store($delegate)"

    private class ReadOnlyConditional(delegate: ConditionalL1Store) : ForwardingConditionalL1Store(delegate) {
        override suspend fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray
        ): L1ConditionalWrite = refuse("putIfMatch")

        override suspend fun deleteIfMatch(key: String, expected: Digest, frame: ByteArray): L1ConditionalWrite =
            refuse("deleteIfMatch")
    }

    private class ReadOnlyVersioned(delegate: VersionedL1Store) : ForwardingVersionedL1Store(delegate) {
        override suspend fun putIfNewer(
            key: String,
            envelope: Envelope,
            version: Long,
            ttl: Duration?,
            frame: ByteArray
        ): L1VersionedWrite = refuse("putIfNewer")

        override suspend fun evictKeepingVersion(key: String, ttl: Duration?, frame: ByteArray): L1Eviction =
            refuse("evictKeepingVersion")
    }

    private class ReadOnlySets(delegate: SetL1Store) : ForwardingSetL1Store(delegate) {
        override suspend fun addMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): L1SetWrite =
            refuse("addMembers")

        override suspend fun removeMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): L1SetWrite = refuse("removeMembers")
    }

    private companion object {
        fun tokenRefused(key: String): String =
            "A read-only store reads without a lease or a refresh token: key[$key] asked for one."

        fun refuse(operation: String): Nothing =
            throw UnsupportedOperationException("$operation() on a read-only store: read-only stores never write.")
    }
}
