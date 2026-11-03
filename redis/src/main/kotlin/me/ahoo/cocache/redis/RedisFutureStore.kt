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

import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.FutureConditionalL1Store
import me.ahoo.cocache.api.spi.FutureL1Store
import me.ahoo.cocache.api.spi.FutureSetL1Store
import me.ahoo.cocache.api.spi.FutureVersionedL1Store
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteConditionalRead
import me.ahoo.cocache.api.spi.RemoteConditionalWrite
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteVersionedWrite
import me.ahoo.cocache.api.spi.Token
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * [RedisRemoteStore] 的异步方法以 [FutureL1Store] 的形状暴露（RFC 0005 §4.3，M3c 的桥接），由 [RedisBackend.store] 经
 * [FutureL1Store.adapt] 交给引擎。超时、令牌的释放与异常类型都由 [RedisRemoteStore] 决定；Lettuce 取消的命令（连接重置、关闭）
 * 以被取消的 future 完成，适配器把它换成 L1 故障（`IllegalStateException`），不当作调用方的取消。M3d 把 Redis 改为原生的 `L1Store`。
 *
 * 提供全部三个能力面：条件读写、按版本有序的写入（[RedisScripts.PUT_IF_NEWER]）与集合成员的增删。
 */
@Suppress("TooManyFunctions")
internal class RedisFutureStore(private val store: RedisRemoteStore) : FutureL1Store {
    private val conditional = Conditional()
    private val versioned = Versioned()
    private val sets = Sets()

    override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead> =
        store.readAsync(key, lease, refresh)

    override fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): CompletableFuture<Map<String, RemoteRead>> =
        store.readAllAsync(keys, leases)

    override fun fill(fill: Fill): CompletableFuture<Boolean> = store.fillAsync(fill)

    override fun fillAll(fills: List<Fill>): CompletableFuture<List<Boolean>> = store.fillAllAsync(fills)

    override fun release(key: String, token: Token): CompletableFuture<Void?> = store.releaseAsync(key, token)

    override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean> =
        store.reclaimAsync(key, observed, lease)

    override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
        store.putAsync(key, envelope, ttl, frame)

    override fun putAll(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = store.putAllAsync(puts, frame)

    override fun invalidate(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> =
        store.invalidateAsync(keys, frame)

    override fun conditional(): FutureConditionalL1Store = conditional

    override fun versioned(): FutureVersionedL1Store = versioned

    override fun sets(): FutureSetL1Store = sets

    private inner class Conditional : FutureConditionalL1Store {
        override fun readIfChanged(key: String, known: Digest?): CompletableFuture<RemoteConditionalRead> =
            store.readIfChangedAsync(key, known)

        override fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<RemoteConditionalWrite> = store.putIfMatchAsync(key, expected, envelope, ttl, frame)
    }

    private inner class Versioned : FutureVersionedL1Store {
        override fun putIfNewer(
            key: String,
            envelope: Envelope,
            version: Long,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<RemoteVersionedWrite> = store.putIfNewerAsync(key, envelope, version, ttl, frame)
    }

    private inner class Sets : FutureSetL1Store {
        override fun addMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Int?> =
            store.addMembersIfReadableAsync(key, members, ttl, frame)

        override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Int?> =
            store.removeMembersIfReadableAsync(key, members, ttl, frame)
    }
}
