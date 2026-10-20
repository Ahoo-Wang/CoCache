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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.spi.AsyncInvalidationChannel
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.internal.Futures
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * 只实现同步 SPI 的后端在异步路径上的适配（RFC 0004 §4.1）：同步方法只在阻塞执行器上执行。
 */
internal class BlockingAsyncStore(
    private val store: RemoteStore,
    private val blocking: Executor,
) : AsyncRemoteStore, RemoteStore by store {
    private fun <T> offload(
        block: () -> T
    ): CompletableFuture<T> = Futures.attempt { CompletableFuture.supplyAsync(block, blocking) }

    private fun completing(block: () -> Unit): CompletableFuture<Void?> = offload {
        block()
        null
    }

    override fun readAsync(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead> =
        offload { store.read(key, lease, refresh) }

    override fun readAllAsync(keys: Collection<String>, leases: Map<String, LeaseRequest>): CompletableFuture<Map<String, RemoteRead>> =
        offload { store.readAll(keys, leases) }

    override fun fillAsync(fill: Fill): CompletableFuture<Boolean> = offload { store.fill(fill) }

    override fun fillAllAsync(fills: List<Fill>): CompletableFuture<List<Boolean>> = offload { store.fillAll(fills) }

    override fun releaseAsync(key: String, token: Token): CompletableFuture<Void?> = completing {
        store.release(key, token)
    }

    override fun putAsync(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
        completing { store.put(key, envelope, ttl, frame) }

    override fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = completing {
        store.putAll(puts, frame)
    }

    override fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> =
        completing { store.invalidate(keys, frame) }

    override fun reclaimAsync(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean> =
        offload { store.reclaim(key, observed, lease) }

    override fun addMembersAsync(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
        completing { store.addMembers(key, members, ttl, frame) }

    override fun removeMembersAsync(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<Void?> = completing { store.removeMembers(key, members, ttl, frame) }

    override fun addMembersIfReadableAsync(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<Int?> = offload { store.addMembersIfReadable(key, members, ttl, frame) }

    override fun removeMembersIfReadableAsync(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<Int?> = offload { store.removeMembersIfReadable(key, members, ttl, frame) }

    companion object {
        fun of(
            store: RemoteStore,
            blocking: Executor
        ): AsyncRemoteStore = store as? AsyncRemoteStore ?: BlockingAsyncStore(store, blocking)
    }
}

/**
 * 只实现同步发布的通道在异步路径上的适配：同步发布只在阻塞执行器上执行。
 */
internal class BlockingAsyncChannel(
    private val channel: InvalidationChannel,
    private val blocking: Executor,
) : AsyncInvalidationChannel {
    override fun subscribe(
        namespace: String,
        listener: InvalidationListener
    ): Subscription = channel.subscribe(namespace, listener)

    override fun publish(namespace: String, frame: ByteArray) = channel.publish(namespace, frame)

    override fun publishAsync(namespace: String, frame: ByteArray): CompletableFuture<Void?> = Futures.attempt {
        CompletableFuture.supplyAsync({
            channel.publish(namespace, frame)
            null
        }, blocking)
    }

    companion object {
        fun of(
            channel: InvalidationChannel,
            blocking: Executor
        ): AsyncInvalidationChannel = channel as? AsyncInvalidationChannel ?: BlockingAsyncChannel(channel, blocking)
    }
}
