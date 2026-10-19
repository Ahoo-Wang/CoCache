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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.pubsub.RedisPubSubAdapter
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Subscription
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Redis Pub/Sub 失效通道：每个后端一条订阅连接，按频道（命名空间）分发给订阅者。
 *
 * - 订阅（含断线重连后 Lettuce 自动重新订阅）得到服务端确认时，重置该频道的所有订阅者。
 * - 心跳：周期性 PING 订阅连接，失败或超时即重置所有订阅者（半开连接期间的事件可能已丢失）。
 */
internal class RedisInvalidationChannel(
    private val connections: RedisConnections,
    heartbeat: Duration,
    private val ackTimeout: Duration,
) : InvalidationChannel, AutoCloseable {
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<InvalidationListener>>()

    /**
     * 等待中的订阅确认：Lettuce 在命令完成之后才回调 `subscribed`，订阅方需要等重置真正执行完再返回，
     * 否则迟到的重置会清掉订阅之后才填充的本地副本。
     */
    private val pendingAcks = ConcurrentHashMap<String, CountDownLatch>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cocache-redis-heartbeat").apply { isDaemon = true }
    }

    init {
        connections.pubSub.addListener(Adapter())
        scheduler.scheduleWithFixedDelay(::heartbeat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun subscribe(namespace: String, listener: InvalidationListener): Subscription {
        val channel = channelOf(namespace)
        var created = false
        val channelListeners = listeners.computeIfAbsent(channel) {
            created = true
            CopyOnWriteArrayList()
        }
        channelListeners.add(listener)
        if (created) {
            // 确认回调（subscribed）会重置该频道的全部订阅者，包括刚加入的这个；等它执行完再返回
            val ack = CountDownLatch(1)
            pendingAcks[channel] = ack
            try {
                connections.await(
                    connections.pubSub.async().subscribe(channel.toByteArray(Charsets.UTF_8)).toCompletableFuture()
                )
                if (!ack.await(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn { "Invalidation channel[$channel] - subscribe acknowledgement not received in $ackTimeout." }
                }
            } finally {
                pendingAcks.remove(channel, ack)
            }
        } else {
            listener.onReset()
        }
        return Subscription {
            channelListeners.remove(listener)
            if (channelListeners.isEmpty() && listeners.remove(channel, channelListeners)) {
                connections.await(
                    connections.pubSub.async().unsubscribe(channel.toByteArray(Charsets.UTF_8)).toCompletableFuture()
                )
            }
        }
    }

    override fun publish(namespace: String, frame: ByteArray) {
        connections.publish(channelOf(namespace).toByteArray(Charsets.UTF_8), frame)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun heartbeat() {
        try {
            connections.await(connections.pubSub.async().ping().toCompletableFuture())
        } catch (e: Exception) {
            log.warn(e) { "Invalidation channel heartbeat failed - reset all subscribers." }
            resetAll()
        }
    }

    private fun resetAll() {
        listeners.values.forEach { channelListeners -> channelListeners.forEach { it.onReset() } }
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    private inner class Adapter : RedisPubSubAdapter<ByteArray, ByteArray>() {
        override fun message(channel: ByteArray, message: ByteArray) {
            val channelListeners = listeners[String(channel, Charsets.UTF_8)] ?: return
            val decoded = try {
                InvalidationFrame.decode(message)
            } catch (e: IllegalArgumentException) {
                log.warn(e) { "Ignore malformed invalidation frame on channel[${String(channel, Charsets.UTF_8)}]." }
                return
            }
            channelListeners.forEach { it.onInvalidated(decoded.keys, decoded.origin) }
        }

        override fun subscribed(channel: ByteArray, count: Long) {
            val name = String(channel, Charsets.UTF_8)
            log.info { "Invalidation channel[$name] subscribed - reset subscribers." }
            listeners[name]?.forEach { it.onReset() }
            pendingAcks[name]?.countDown()
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}
        const val CHANNEL_PREFIX = "cocache:inv:"

        fun channelOf(namespace: String): String = CHANNEL_PREFIX + namespace
    }
}
