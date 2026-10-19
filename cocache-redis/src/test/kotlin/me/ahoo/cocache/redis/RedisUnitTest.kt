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

import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.RedisFuture
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands
import io.mockk.every
import io.mockk.mockk
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class RedisUnitTest {
    @Test
    fun optionsAreValidated() {
        fun fails(
            block: () -> Any
        ) = runCatching(block).exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        fails { RedisBackendOptions(commandTimeout = Duration.ZERO) }
        fails { RedisBackendOptions(commandTimeout = Duration.ofSeconds(-1)) }
        fails { RedisBackendOptions(heartbeat = Duration.ZERO) }
        fails { RedisBackendOptions(heartbeat = Duration.ofSeconds(-1)) }
        fails { RedisBackendOptions(invalidateBatchSize = 0) }
    }

    @Test
    fun decodesUnrecognizedAndEdgeContent() {
        val foreign = RedisRemoteStore.decodeRead(
            listOf(2L, 1000L, "{\"legacy\":true}".toByteArray(), 0L)
        ) as RemoteRead.Hit
        foreign.format.assert().isEqualTo(RedisRemoteStore.UNRECOGNIZED)
        foreign.ttl.assert().isEqualTo(Duration.ofSeconds(1))

        val header = ByteArray(RedisScripts.HEADER_SIZE).also {
            it[0] = RedisScripts.MAGIC
            it[1] = 1
            it[2] = 'X'.code.toByte()
        }
        (
            RedisRemoteStore.decodeRead(
                listOf(2L, -1L, header + "p".toByteArray(), 0L)
            ) as RemoteRead.Hit
            ).format.assert().isEqualTo(RedisRemoteStore.UNRECOGNIZED)

        val set = RedisRemoteStore.decodeRead(
            listOf(3L, -1L, listOf("+a".toByteArray(), ByteArray(0), "other".toByteArray(), ByteArray(0)))
        ) as RemoteRead.Hit
        set.format.assert().isEqualTo(RedisRemoteStore.UNRECOGNIZED)
        (set.envelope as Envelope.Members).members.map { String(it) }.assert().containsExactly("a")
        set.ttl.assert().isNull()

        val badFormat = RedisRemoteStore.decodeRead(
            listOf(3L, 5L, listOf("!f".toByteArray(), "x".toByteArray()))
        ) as RemoteRead.Hit
        badFormat.format.assert().isEqualTo(RedisRemoteStore.UNRECOGNIZED)

        RedisRemoteStore.decodeRead(listOf(1L, -5L)).assert().isEqualTo(RemoteRead.Leased(Duration.ZERO))
        runCatching {
            RedisRemoteStore.decodeRead(
                listOf(9L)
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun connectionsRejectUnsupportedClients() {
        runCatching { RedisConnections(mockk<AbstractRedisClient>(), Duration.ofSeconds(1)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun awaitTranslatesFailures() {
        assumeStandalone()
        val client = RedisClient.create(RedisURI.create("localhost", 6379))
        try {
            RedisConnections(client, Duration.ofMillis(20)).use { connections ->
                runCatching {
                    connections.await(
                        CompletableFuture<Any>()
                    )
                }.exceptionOrNull().assert().isInstanceOf(RedisException::class.java)
                val checked = CompletableFuture.failedFuture<Any>(Exception("checked"))
                runCatching {
                    connections.await(
                        checked
                    )
                }.exceptionOrNull().assert().isInstanceOf(RedisException::class.java)
                val runtime = IllegalStateException("runtime")
                runCatching {
                    connections.await(
                        CompletableFuture.failedFuture<Any>(runtime)
                    )
                }.exceptionOrNull().assert().isSameAs(runtime)
                Thread.currentThread().interrupt()
                runCatching {
                    connections.await(
                        CompletableFuture<Any>()
                    )
                }.exceptionOrNull().assert().isInstanceOf(RedisException::class.java)
                Thread.interrupted().assert().isTrue()
                connections.groupBySlot(listOf("a".toByteArray(), "b".toByteArray())).size.assert().isEqualTo(1)
            }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun evalFallsBackToSourceAfterScriptFlush() {
        assumeStandalone()
        val client = RedisClient.create(RedisURI.create("localhost", 6379))
        try {
            client.connect().use { it.sync().scriptFlush() }
            RedisConnections(client, Duration.ofSeconds(2)).use { connections ->
                val result = connections.eval<List<Any?>>(
                    RedisScripts.PROBE,
                    listOf("cocache:probe".toByteArray()),
                    listOf("cocache:probe:channel".toByteArray())
                )
                result.assert().containsExactly(1L)
                val failure = runCatching {
                    connections.eval<List<Any?>>(RedisScripts.FILL, emptyList(), emptyList())
                }.exceptionOrNull()
                failure.assert().isInstanceOf(RedisException::class.java)
            }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun borrowedClientIsNotShutDown() {
        assumeStandalone()
        val client = RedisClient.create(RedisURI.create("localhost", 6379))
        try {
            RedisBackend.of(client).use { backend ->
                backend.atomicBroadcast.assert().isTrue()
                backend.format.assert().isEqualTo(RedisScripts.FORMAT)
                backend.clientName.assert().isNull()
            }
            client.connect().use { it.sync().ping().assert().isEqualTo("PONG") }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun backendWithSharedResourcesAndClientName() {
        assumeStandalone()
        val resources = io.lettuce.core.resource.DefaultClientResources.create()
        try {
            RedisBackend.standalone(
                RedisURI.create("localhost", 6379),
                RedisBackendOptions(clientResources = resources, clientName = "cocache-unit")
            ).use {
                it.clientName.assert().isEqualTo("cocache-unit")
            }
        } finally {
            resources.shutdown()
        }
    }

    @Test
    fun channelResetsOnHeartbeatFailureAndIgnoresMalformedFrames() {
        var adapter: RedisPubSubAdapter<ByteArray, ByteArray>? = null
        val async = proxy<RedisPubSubAsyncCommands<ByteArray, ByteArray>> { method, _ ->
            when (method.name) {
                "ping" -> failed<String>(RedisException("down"))
                else -> completed<Void?>(null)
            }
        }
        val pubSub = proxy<StatefulRedisPubSubConnection<ByteArray, ByteArray>> { method, args ->
            when (method.name) {
                "addListener" -> {
                    @Suppress("UNCHECKED_CAST")
                    adapter = args[0] as RedisPubSubAdapter<ByteArray, ByteArray>
                    null
                }

                "async" -> async
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        val connections = mockk<RedisConnections>()
        every { connections.pubSub } returns pubSub
        every { connections.await(any<CompletableFuture<Any?>>()) } answers { firstArg<CompletableFuture<Any?>>().get() }

        val resets = AtomicInteger()
        val received = CopyOnWriteArrayList<List<String>>()
        val listener = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                received.add(keys)
            }

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        RedisInvalidationChannel(connections, Duration.ofMillis(10), Duration.ofMillis(10)).use { channel ->
            // 没有收到订阅确认：等待超时后照常返回
            val subscription = channel.subscribe("ns", listener)
            val second = channel.subscribe("ns", listener)
            awaitCondition("heartbeat reset") { resets.get() >= 3 }

            val handler = requireNotNull(adapter)
            handler.message("cocache:inv:ns".toByteArray(), byteArrayOf(9))
            handler.message("cocache:inv:other".toByteArray(), InvalidationFrame.encode(NodeId.random(), listOf("k")))
            handler.subscribed("cocache:inv:other".toByteArray(), 1)
            handler.message("cocache:inv:ns".toByteArray(), InvalidationFrame.encode(NodeId.random(), listOf("k")))
            // 同一订阅者订阅了两次，收到两次
            received.assert().containsExactly(listOf("k"), listOf("k"))
            second.close()
            subscription.close()
        }
    }

    private inline fun <reified T> proxy(crossinline handler: (java.lang.reflect.Method, Array<out Any?>) -> Any?): T {
        return java.lang.reflect.Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
        ) { _, method, args ->
            handler(method, args ?: emptyArray())
        } as T
    }

    /**
     * 这些用例验证单机客户端的行为，连接 localhost:6379；集群环境（CI 的集群任务）下跳过。
     */
    private fun assumeStandalone() = Assumptions.assumeFalse(RedisTestSupport.isCluster, "standalone Redis only")

    private fun <T> completed(value: T): RedisFuture<T> = FutureAdapter(CompletableFuture.completedFuture(value))

    private fun <T> failed(error: Throwable): RedisFuture<T> = FutureAdapter(CompletableFuture.failedFuture(error))

    private class FutureAdapter<T>(private val future: CompletableFuture<T>) :
        RedisFuture<T>,
        java.util.concurrent.CompletionStage<T> by future,
        java.util.concurrent.Future<T> by future {
        override fun getError(): String? = null

        override fun await(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true

        override fun toCompletableFuture(): CompletableFuture<T> = future
    }
}
