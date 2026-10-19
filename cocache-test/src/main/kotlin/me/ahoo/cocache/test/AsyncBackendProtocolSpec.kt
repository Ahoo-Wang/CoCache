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

package me.ahoo.cocache.test

import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Token
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 原生异步后端的协议 TCK（RFC 0004 §4.4）：[BackendProtocolSpec] 的每个用例都经 [AsyncRemoteStore] 的异步方法重跑
 * （异步方法不得同步抛出，失败以原始异常完成），另加同步与异步交替调用同一个 key 的一致性。
 */
abstract class AsyncBackendProtocolSpec : BackendProtocolSpec() {
    /**
     * 被测的原生异步存储（用例经 [store] 以等待的方式调用它的异步方法）。
     */
    protected lateinit var asyncStore: AsyncRemoteStore

    @BeforeEach
    override fun setup() {
        super.setup()
        val raw = store
        check(
            raw is AsyncRemoteStore
        ) { "${raw.javaClass.name} does not implement ${AsyncRemoteStore::class.java.simpleName}." }
        asyncStore = raw
        store = AsyncBackedStore(raw)
    }

    @Test
    fun syncAndAsyncCallsShareOneProtocolState() {
        val key = key()
        val lease = lease()
        CacheAccess.await { asyncStore.readAsync(key, lease, null) }.assert().isEqualTo(RemoteRead.Miss(true))
        asyncStore.read(key, lease(), null).assert().isInstanceOf(RemoteRead.Leased::class.java)
        asyncStore.fill(Fill(key, lease.token, scalar("v"), null)).assert().isTrue()
        val hit = CacheAccess.await { asyncStore.readAsync(key, null, null) } as RemoteRead.Hit
        hit.envelope.assert().isEqualTo(scalar("v"))
        CacheAccess.await { asyncStore.invalidateAsync(listOf(key), frame(key)) }
        asyncStore.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }
}

/**
 * 经异步方法实现的同步 [RemoteStore]（TCK 用）：每次调用等待异步结果，异常解包为原始异常。
 */
internal class AsyncBackedStore(private val store: AsyncRemoteStore) : RemoteStore {
    override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead =
        CacheAccess.await { store.readAsync(key, lease, refresh) }

    override fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> =
        CacheAccess.await { store.readAllAsync(keys, leases) }

    override fun fill(fill: Fill): Boolean = CacheAccess.await { store.fillAsync(fill) }

    override fun fillAll(fills: List<Fill>): List<Boolean> = CacheAccess.await { store.fillAllAsync(fills) }

    override fun release(key: String, token: Token) {
        CacheAccess.await { store.releaseAsync(key, token) }
    }

    override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
        CacheAccess.await { store.putAsync(key, envelope, ttl, frame) }
    }

    override fun putAll(puts: List<Put>, frame: ByteArray) {
        CacheAccess.await { store.putAllAsync(puts, frame) }
    }

    override fun invalidate(keys: Collection<String>, frame: ByteArray) {
        CacheAccess.await { store.invalidateAsync(keys, frame) }
    }

    override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean =
        CacheAccess.await { store.reclaimAsync(key, observed, lease) }

    override fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
        CacheAccess.await { store.addMembersAsync(key, members, ttl, frame) }
    }

    override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
        CacheAccess.await { store.removeMembersAsync(key, members, ttl, frame) }
    }

    override fun close() = store.close()
}
