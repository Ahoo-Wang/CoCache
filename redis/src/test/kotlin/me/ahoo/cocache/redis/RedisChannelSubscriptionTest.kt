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

import io.lettuce.core.RedisException
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 失效通道的订阅生命周期：订阅丢失后的重置、订阅与退订并发（多个运行时共享一个后端时）、订阅者之间的隔离。
 */
class RedisChannelSubscriptionTest {
    /**
     * 订阅丢失后迟迟得不到确认（如分区信息陈旧，`SSUBSCRIBE` 发到了旧节点而失败）：每次心跳都重置订阅者，不只在丢失时重置一次。
     * 未确认期间收不到远端事件，本地副本却会从 L1 重新填充；只重置一次时陈旧到 `localMaxAge`（经典模式心跳失败时每次都重置）。
     */
    @Test
    fun shardedChannelResetsUnconfirmedSubscribersOnEveryHeartbeat() {
        var adapter: RedisPubSubAdapter<ByteArray, ByteArray>? = null
        val subscribes = AtomicInteger()
        val stale = AtomicBoolean()
        val async = stub<RedisPubSubAsyncCommands<ByteArray, ByteArray>>(
            "ssubscribe" to {
                subscribes.incrementAndGet()
                if (stale.get()) failed<Void?>(RedisException("MOVED 1 127.0.0.1:7001")) else completed<Void?>(null)
            },
            "ping" to { completed("PONG") },
        )
        val pubSub = stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>(
            "addListener" to { args ->
                @Suppress("UNCHECKED_CAST")
                adapter = args[0] as RedisPubSubAdapter<ByteArray, ByteArray>
                null
            },
            "async" to { async },
        )
        val listener = RecordingListener()
        ShardedInvalidationChannel(connectionsOf(pubSub), Duration.ofHours(1), Duration.ofMillis(10)).use { channel ->
            channel.subscribe("ns", listener)
            val handler = requireNotNull(adapter)
            val ns = "cocache:inv:ns".toByteArray()
            stale.set(true)
            handler.sunsubscribed(ns, 0)
            listener.resets.get().assert().isEqualTo(1)
            awaitCondition("resubscribe attempted") { subscribes.get() == 2 }
            repeat(3) { channel.ping() }
            listener.resets.get().assert().isEqualTo(4)
            subscribes.get().assert().isEqualTo(5)
            // 确认之后不再重置
            stale.set(false)
            handler.ssubscribed(ns, 1)
            listener.resets.get().assert().isEqualTo(5)
            channel.ping()
            listener.resets.get().assert().isEqualTo(5)
            subscribes.get().assert().isEqualTo(5)
        }
    }

    /**
     * 首次订阅失败时，并发加入的订阅者不能随失败的频道条目一起被移除（否则它永久收不到失效）：它等首次订阅结束，再自己订阅。
     * 多个运行时共享一个后端时才会并发。
     */
    @Test
    fun concurrentSubscriberSurvivesAFailedFirstSubscribe() {
        var adapter: RedisPubSubAdapter<ByteArray, ByteArray>? = null
        val subscribing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val subscribes = AtomicInteger()
        val async = stub<RedisPubSubAsyncCommands<ByteArray, ByteArray>>(
            "subscribe" to {
                if (subscribes.incrementAndGet() == 1) {
                    subscribing.countDown()
                    proceed.awaitOrFail("first subscribe released")
                    failed<Void?>(RedisException("down"))
                } else {
                    completed<Void?>(null)
                }
            },
        )
        val pubSub = stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>(
            "addListener" to { args ->
                @Suppress("UNCHECKED_CAST")
                adapter = args[0] as RedisPubSubAdapter<ByteArray, ByteArray>
                null
            },
            "async" to { async },
        )
        PubSubInvalidationChannel(connectionsOf(pubSub), Duration.ofHours(1), Duration.ofMillis(10)).use { channel ->
            val first = fork("first-subscriber") { channel.subscribe("ns", RecordingListener()) }
            subscribing.awaitOrFail("first subscribe sent")
            val joining = RecordingListener()
            val second = fork("second-subscriber") { channel.subscribe("ns", joining) }
            awaitCondition("second subscriber joined or waiting") { second.joinedOrBlocked() }
            proceed.countDown()
            first.joinFailure().assert().hasRootCauseInstanceOf(RedisException::class.java)
            second.join()
            // 第二个订阅者自己发出了 SUBSCRIBE，并收到失效
            subscribes.get().assert().isEqualTo(2)
            requireNotNull(
                adapter
            ).message("cocache:inv:ns".toByteArray(), InvalidationFrame.encode(NodeId.random(), listOf("k")))
            joining.received.assert().containsExactly(listOf("k"))
        }
    }

    /**
     * 最后一个订阅者退订期间新的订阅者到来：退订的 UNSUBSCRIBE 不能晚于新订阅的 SUBSCRIBE 生效，否则新订阅者永久收不到失效。
     * 桩按命令发出的顺序维护服务端的订阅状态。
     */
    @Test
    fun subscribeDuringTheLastUnsubscribeStaysSubscribed() {
        val unsubscribing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val subscribed = AtomicBoolean()
        val async = stub<RedisPubSubAsyncCommands<ByteArray, ByteArray>>(
            "subscribe" to {
                subscribed.set(true)
                completed<Void?>(null)
            },
            "unsubscribe" to {
                unsubscribing.countDown()
                proceed.awaitOrFail("unsubscribe released")
                subscribed.set(false)
                completed<Void?>(null)
            },
        )
        val pubSub = stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>(
            "addListener" to { null },
            "async" to { async },
        )
        PubSubInvalidationChannel(connectionsOf(pubSub), Duration.ofHours(1), Duration.ofMillis(10)).use { channel ->
            val last = channel.subscribe("ns", RecordingListener())
            val closing = fork("last-unsubscribe") { last.close() }
            unsubscribing.awaitOrFail("unsubscribe sent")
            val second = fork("new-subscriber") { channel.subscribe("ns", RecordingListener()) }
            awaitCondition("new subscriber subscribed or waiting") { second.joinedOrBlocked() }
            proceed.countDown()
            closing.join()
            second.join()
            subscribed.get().assert().withFailMessage { "channel ended unsubscribed while it has a subscriber" }.isTrue()
        }
    }

    /**
     * 排在前面的订阅者抛出 `Error`：其后的订阅者照常收到失效与重置（6.9.1 及之前分发只兜住 `RuntimeException`，
     * 其后的订阅者收不到这条消息）。`VirtualMachineError` 照常抛出。
     */
    @Test
    fun listenerThrowingAnErrorDoesNotSkipOtherListeners() {
        var adapter: RedisPubSubAdapter<ByteArray, ByteArray>? = null
        val async = stub<RedisPubSubAsyncCommands<ByteArray, ByteArray>>("subscribe" to { completed<Void?>(null) })
        val pubSub = stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>(
            "addListener" to { args ->
                @Suppress("UNCHECKED_CAST")
                adapter = args[0] as RedisPubSubAdapter<ByteArray, ByteArray>
                null
            },
            "async" to { async },
        )
        val failing = ThrowingListener(AssertionError("listener bug"))
        val healthy = RecordingListener()
        PubSubInvalidationChannel(connectionsOf(pubSub), Duration.ofHours(1), Duration.ofMillis(10)).use { channel ->
            // 没有订阅确认：第一个订阅者等待超时后返回；后加入的订阅者直接重置一次
            channel.subscribe("ns", failing)
            channel.subscribe("ns", healthy)
            val handler = requireNotNull(adapter)
            val ns = "cocache:inv:ns".toByteArray()

            handler.message(ns, InvalidationFrame.encode(NodeId.random(), listOf("k")))
            healthy.received.assert().containsExactly(listOf("k"))
            handler.subscribed(ns, 1)
            healthy.resets.get().assert().isEqualTo(2)

            val fatal = OutOfMemoryError("listener")
            failing.error = fatal
            runCatching {
                handler.message(ns, InvalidationFrame.encode(NodeId.random(), listOf("k2")))
            }.exceptionOrNull().assert().isSameAs(fatal)
        }
    }

    /**
     * 后台线程已经结束，或在等待订阅锁。
     */
    private fun Concurrency.Forked<*>.joinedOrBlocked(): Boolean =
        !thread.isAlive || thread.state == Thread.State.BLOCKED
}
