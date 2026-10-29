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

import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * 只实现同步 SPI 的后端在异步路径上的适配：同步调用只在阻塞执行器上执行，失败与拒绝都以失败的 future 返回。
 */
class BlockingAsyncStoreTest {
    private val blocking = Executors.newSingleThreadExecutor { Thread(it, "tck-blocking") }
    private val memory = InMemoryBackend()

    @AfterEach
    fun destroy() {
        blocking.shutdownNow()
    }

    private fun syncOnly(): RemoteStore = object : RemoteStore by memory.store("ns") {}

    private fun frame(vararg keys: String) = InvalidationFrame.encode(NodeId.random(), keys.toList())

    @Test
    fun nativeStoresAndChannelsAreUsedDirectly() {
        val native = memory.store("ns")
        BlockingAsyncStore.of(native, blocking).assert().isSameAs(native)
        BlockingAsyncChannel.of(memory.channel, blocking).assert().isSameAs(memory.channel)
    }

    @Test
    fun everyOperationRunsOnTheBlockingExecutor() {
        val ran = mutableListOf<String>()
        val recording = Executor { task ->
            blocking.execute {
                ran.add(Thread.currentThread().name)
                task.run()
            }
        }
        val store: AsyncRemoteStore = BlockingAsyncStore.of(syncOnly(), recording)
        val token = Token(NodeId.random(), 1)
        fun <T> wait(future: java.util.concurrent.CompletableFuture<T>): T = future.get(10, TimeUnit.SECONDS)
        wait(
            store.readAsync("k", LeaseRequest(token, Duration.ofSeconds(10)), null)
        ).assert().isEqualTo(RemoteRead.Miss(true))
        wait(store.fillAsync(Fill("k", token, Envelope.Scalar(byteArrayOf(1)), null))).assert().isTrue()
        wait(store.readAllAsync(listOf("k"), emptyMap())).keys.assert().containsExactly("k")
        wait(store.fillAllAsync(emptyList())).assert().isEmpty()
        wait(store.releaseAsync("k", token))
        wait(
            store.reclaimAsync("k", Digest.of(Envelope.Absent), LeaseRequest(token, Duration.ofSeconds(1)))
        ).assert().isFalse()
        wait(store.putAsync("k", Envelope.Scalar(byteArrayOf(2)), null, frame("k")))
        wait(store.putAllAsync(listOf(Put("p", Envelope.Scalar(byteArrayOf(3)), null)), frame("p")))
        wait(store.addMembersAsync("s", listOf(byteArrayOf(1)), null, frame("s")))
        wait(store.removeMembersAsync("s", listOf(byteArrayOf(1)), null, frame("s")))
        wait(store.addMembersIfReadableAsync("s", listOf(byteArrayOf(1)), null, frame("s"))).assert().isNull()
        wait(store.removeMembersIfReadableAsync("s", listOf(byteArrayOf(1)), null, frame("s"))).assert().isNull()
        // 标量值不能当作集合：拒绝并返回其格式版本
        wait(
            store.addMembersIfReadableAsync("k", listOf(byteArrayOf(1)), null, frame("k"))
        ).assert().isEqualTo(memory.format)
        wait(
            store.removeMembersIfReadableAsync("k", listOf(byteArrayOf(1)), null, frame("k"))
        ).assert().isEqualTo(memory.format)
        wait(store.invalidateAsync(listOf("k", "p"), frame("k", "p")))
        store.read("k", null, null).assert().isEqualTo(RemoteRead.Miss(false))
        ran.size.assert().isEqualTo(15)
        ran.all { it == "tck-blocking" }.assert().isTrue()
    }

    @Test
    fun failuresAndRejectionsBecomeFailedFutures() {
        val failing = object : RemoteStore by syncOnly() {
            override fun read(
                key: String,
                lease: LeaseRequest?,
                refresh: me.ahoo.cocache.api.spi.RefreshRequest?
            ): RemoteRead =
                throw IllegalStateException("down")
        }
        BlockingAsyncStore.of(
            failing,
            blocking
        ).readAsync("k", null, null).handle { _, e -> e }.get(10, TimeUnit.SECONDS)
            .assert().isInstanceOf(java.util.concurrent.CompletionException::class.java)
        val rejecting = Executor { throw RejectedExecutionException("full") }
        BlockingAsyncStore.of(syncOnly(), rejecting).readAsync("k", null, null).handle { _, e -> e }.join()
            .assert().isInstanceOf(RejectedExecutionException::class.java)
    }

    @Test
    fun syncOnlyChannelPublishesOnTheBlockingExecutor() {
        val published = mutableListOf<String>()
        val channel = object : InvalidationChannel {
            override fun subscribe(namespace: String, listener: InvalidationListener): Subscription = Subscription { }

            override fun publish(namespace: String, frame: ByteArray) {
                published.add(Thread.currentThread().name)
            }
        }
        val adapted = BlockingAsyncChannel.of(channel, blocking)
        adapted.publishAsync("ns", frame("k")).get(10, TimeUnit.SECONDS)
        adapted.publish("ns", frame("k"))
        adapted.subscribe(
            "ns",
            object : InvalidationListener {
                override fun onInvalidated(keys: List<String>, origin: NodeId) = Unit
                override fun onReset() = Unit
            }
        ).close()
        published.assert().containsExactly("tck-blocking", Thread.currentThread().name)
        BlockingAsyncChannel.of(channel) { throw RejectedExecutionException("full") }.publishAsync("ns", frame("k"))
            .handle { _, e -> e }.join().assert().isInstanceOf(RejectedExecutionException::class.java)
    }
}
