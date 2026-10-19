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
import io.lettuce.core.Delegating
import io.lettuce.core.RedisChannelHandler
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.protocol.DefaultEndpoint
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
 * - 心跳：周期性 PING 订阅连接，失败或超时即重置所有订阅者（半开连接期间的事件可能已丢失），并强制断开订阅连接，由 Lettuce 自动重连并重新订阅。
 * - 订阅失败时撤销本次创建的频道条目；单个订阅者抛出异常不影响其余订阅者。
 *
 * 子类提供具体的订阅、退订、发布与心跳命令，并在初始化末尾调用 [start]。
 */
internal abstract class RedisInvalidationChannel(
    internal val connections: RedisConnections,
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
            var subscribed = false
            try {
                connections.await(subscribeAsync(channel.toByteArray(Charsets.UTF_8)))
                subscribed = true
                if (!ack.await(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn { "Invalidation channel[$channel] - subscribe acknowledgement not received in $ackTimeout." }
                }
            } finally {
                pendingAcks.remove(channel, ack)
                if (!subscribed) {
                    // 订阅失败：撤销本次创建的频道条目，否则该命名空间永久收不到失效，之后的订阅者也会跳过 SUBSCRIBE
                    channelListeners.remove(listener)
                    listeners.remove(channel, channelListeners)
                }
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

    /**
     * 最近一次心跳失败的原因；最近一次心跳成功（或尚未执行心跳）时为 `null`。只读状态，供健康检查查看。
     */
    @Volatile
    internal var lastHeartbeatFailure: String? = null
        private set

    @Suppress("TooGenericExceptionCaught")
    private fun beat() {
        try {
            ping()
            lastHeartbeatFailure = null
        } catch (e: Exception) {
            lastHeartbeatFailure = e.toString()
            log.warn(e) { "Invalidation channel heartbeat failed - reset all subscribers and force reconnect." }
            resetAll()
            forceReconnect()
        }
    }

    /**
     * 心跳失败后强制重连订阅连接：半开 TCP 连接上 Lettuce 仍认为已连接，不会自行重连。
     * 断开底层通道后由 Lettuce 的自动重连恢复连接并重新订阅，确认回调再重置订阅者。
     */
    protected open fun forceReconnect() {
        if (!disconnect(connections.pubSub)) {
            log.warn { "Invalidation channel - cannot force reconnect of the subscription connection, rely on TCP keepalive." }
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
    @Suppress("TooGenericExceptionCaught")
    protected fun dispatch(channel: ByteArray, message: ByteArray) {
        val channelListeners = listeners[String(channel, Charsets.UTF_8)] ?: return
        val decoded = try {
            InvalidationFrame.decode(message)
        } catch (e: IllegalArgumentException) {
            log.warn(e) { "Ignore malformed invalidation frame on channel[${String(channel, Charsets.UTF_8)}]." }
            return
        }
        // 某个订阅者抛出异常不能让其余订阅者错过本次失效
        channelListeners.forEach { listener ->
            try {
                listener.onInvalidated(decoded.keys, decoded.origin)
            } catch (e: RuntimeException) {
                log.error(e) { "Invalidation listener failed on channel[${String(channel, Charsets.UTF_8)}]." }
            }
        }
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

        /**
         * 断开连接的底层通道（Lettuce 公开的 [DefaultEndpoint.disconnect]），触发自动重连；
         * 无法定位到端点（如 Cluster 经典 Pub/Sub 的默认连接）时返回 `false`。
         */
        fun disconnect(connection: StatefulConnection<*, *>): Boolean {
            var writer: Any? = (connection as? RedisChannelHandler<*, *>)?.channelWriter
            while (writer is Delegating<*>) {
                writer = writer.delegate
            }
            val endpoint = writer as? DefaultEndpoint ?: return false
            endpoint.disconnect()
            return true
        }

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
