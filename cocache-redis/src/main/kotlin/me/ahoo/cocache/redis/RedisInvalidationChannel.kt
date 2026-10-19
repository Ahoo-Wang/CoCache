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
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Subscription
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Redis 失效通道的公共部分：每个后端一条订阅连接，按频道（命名空间）分发给订阅者。
 *
 * - 订阅（含断线重连后 Lettuce 自动重新订阅）得到服务端确认时，重置该频道的所有订阅者。
 * - 心跳：周期性 PING 订阅连接，失败或超时即重置所有订阅者（半开连接期间的事件可能已丢失）。
 *
 * 子类提供具体的订阅、退订、发布与心跳命令，并在初始化末尾调用 [start]。
 */
internal abstract class RedisInvalidationChannel(
    protected val connections: RedisConnections,
    private val heartbeat: Duration,
    private val ackTimeout: Duration,
) : InvalidationChannel, AutoCloseable {
    protected val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<InvalidationListener>>()

    /**
     * 等待中的订阅确认：Lettuce 在命令完成之后才回调确认，订阅方需要等重置真正执行完再返回，
     * 否则迟到的重置会清掉订阅之后才填充的本地副本。
     */
    private val pendingAcks = ConcurrentHashMap<String, CountDownLatch>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cocache-redis-heartbeat").apply { isDaemon = true }
    }

    /**
     * 启动心跳。子类注册完连接监听器之后调用，避免心跳在子类初始化完成前触发。
     */
    protected fun start() {
        scheduler.scheduleWithFixedDelay(::beat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS)
    }

    protected abstract fun subscribeAsync(channel: ByteArray): CompletableFuture<Void>

    protected abstract fun unsubscribeAsync(channel: ByteArray): CompletableFuture<Void>

    /**
     * 心跳探测：失败或超时抛出异常。
     */
    internal abstract fun ping()

    override fun subscribe(namespace: String, listener: InvalidationListener): Subscription {
        val channel = channelOf(namespace)
        var created = false
        val channelListeners = listeners.computeIfAbsent(channel) {
            created = true
            CopyOnWriteArrayList()
        }
        channelListeners.add(listener)
        if (created) {
            // 确认回调会重置该频道的全部订阅者，包括刚加入的这个；等它执行完再返回
            val ack = CountDownLatch(1)
            pendingAcks[channel] = ack
            try {
                connections.await(subscribeAsync(channel.toByteArray(Charsets.UTF_8)))
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
                connections.await(unsubscribeAsync(channel.toByteArray(Charsets.UTF_8)))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun beat() {
        try {
            ping()
        } catch (e: Exception) {
            log.warn(e) { "Invalidation channel heartbeat failed - reset all subscribers." }
            resetAll()
        }
    }

    /**
     * 在心跳线程上执行（不阻塞 Lettuce 事件循环）；通道关闭后忽略。
     */
    protected fun execute(task: Runnable) {
        try {
            scheduler.execute(task)
        } catch (e: RejectedExecutionException) {
            log.debug(e) { "Invalidation channel closed - ignore task." }
        }
    }

    private fun resetAll() {
        listeners.values.forEach { channelListeners -> channelListeners.forEach { it.onReset() } }
    }

    protected fun reset(channel: String) {
        listeners[channel]?.forEach { it.onReset() }
    }

    /**
     * 收到消息：解码并分发给该频道的订阅者，畸形帧告警后丢弃。
     */
    protected fun dispatch(channel: ByteArray, message: ByteArray) {
        val channelListeners = listeners[String(channel, Charsets.UTF_8)] ?: return
        val decoded = try {
            InvalidationFrame.decode(message)
        } catch (e: IllegalArgumentException) {
            log.warn(e) { "Ignore malformed invalidation frame on channel[${String(channel, Charsets.UTF_8)}]." }
            return
        }
        channelListeners.forEach { it.onInvalidated(decoded.keys, decoded.origin) }
    }

    /**
     * 订阅得到服务端确认：重置该频道的订阅者，并唤醒等待确认的订阅方。
     */
    protected open fun acknowledged(channel: ByteArray) {
        val name = String(channel, Charsets.UTF_8)
        log.info { "Invalidation channel[$name] subscribed - reset subscribers." }
        reset(name)
        pendingAcks[name]?.countDown()
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    companion object {
        private val log = KotlinLogging.logger {}
        const val CHANNEL_PREFIX = "cocache:inv:"

        fun channelOf(namespace: String): String = CHANNEL_PREFIX + namespace

        fun create(connections: RedisConnections, options: RedisBackendOptions): RedisInvalidationChannel {
            val heartbeat = options.heartbeat
            val ackTimeout = options.commandTimeout
            return when (options.channelMode) {
                RedisChannelMode.PUBSUB -> PubSubInvalidationChannel(connections, heartbeat, ackTimeout)
                RedisChannelMode.SHARDED -> ShardedInvalidationChannel(connections, heartbeat, ackTimeout)
            }
        }
    }
}
