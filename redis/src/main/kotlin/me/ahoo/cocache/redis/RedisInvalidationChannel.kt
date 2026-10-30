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
import io.lettuce.core.CommandListenerWriter
import io.lettuce.core.RedisChannelHandler
import io.lettuce.core.RedisChannelWriter
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.protocol.CommandExpiryWriter
import io.lettuce.core.protocol.DefaultEndpoint
import me.ahoo.cocache.api.spi.AsyncInvalidationChannel
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
 * - 订阅与退订串行执行（[subscriptionLock]），同一连接上的 SUBSCRIBE / UNSUBSCRIBE 因此与频道条目的增删同序。
 *   经典模式不处理服务端的 `unsubscribed`：Redis 不会主动退订经典频道，唯一的退订来自这里，且不会晚于之后的订阅生效；
 *   Sharded 模式的服务端退订（slot 迁移）见 [ShardedInvalidationChannel]。
 *
 * 子类提供具体的订阅、退订、发布与心跳命令，并在初始化末尾调用 [start]。
 */
internal abstract class RedisInvalidationChannel(
    internal val connections: RedisConnections,
    private val heartbeat: Duration,
    private val ackTimeout: Duration,
) : AsyncInvalidationChannel, AutoCloseable {
    protected val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<InvalidationListener>>()

    /**
     * 等待中的订阅确认：Lettuce 在命令完成之后才回调确认，订阅方需要等重置真正执行完再返回，
     * 否则迟到的重置会清掉订阅之后才填充的本地副本。
     */
    private val pendingAcks = ConcurrentHashMap<String, CountDownLatch>()

    /**
     * 串行化订阅与退订（含等待服务端应答）。否则多个运行时共享一个后端时：订阅者可能加入刚被最后一个订阅者移除的旧列表，
     * 随后的 UNSUBSCRIBE 让它永久收不到失效；首次订阅失败时会把并发加入的订阅者一并移除；退订的 UNSUBSCRIBE 也可能晚于
     * 随后的 SUBSCRIBE 生效。只在创建、关闭缓存时争用，不在读写路径上；确认、心跳与消息分发都不取这把锁。
     */
    private val subscriptionLock = Any()
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
        var joined = false
        val channelListeners = synchronized(subscriptionLock) {
            val existing = listeners[channel]
            if (existing == null) {
                subscribeFirst(channel, listener)
            } else {
                existing.add(listener)
                joined = true
                existing
            }
        }
        if (joined) {
            listener.onReset()
        }
        return Subscription { unsubscribe(channel, channelListeners, listener) }
    }

    /**
     * 频道的第一个订阅者：登记频道条目并 SUBSCRIBE；确认回调会重置该频道的全部订阅者，包括这一个，等它执行完再返回。
     * 订阅失败时撤销条目，否则该命名空间永久收不到失效，之后的订阅者也会跳过 SUBSCRIBE。持有 [subscriptionLock] 时调用。
     */
    private fun subscribeFirst(
        channel: String,
        listener: InvalidationListener
    ): CopyOnWriteArrayList<InvalidationListener> {
        val channelListeners = CopyOnWriteArrayList(listOf(listener))
        listeners[channel] = channelListeners
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
                listeners.remove(channel, channelListeners)
            }
        }
        return channelListeners
    }

    /**
     * 移除订阅者；频道的最后一个订阅者离开时移除条目并 UNSUBSCRIBE（在 [subscriptionLock] 内等待应答，之后的订阅才发出 SUBSCRIBE）。
     */
    private fun unsubscribe(
        channel: String,
        channelListeners: CopyOnWriteArrayList<InvalidationListener>,
        listener: InvalidationListener
    ) {
        synchronized(subscriptionLock) {
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

    /**
     * 心跳任务：任何异常都不能逃出去。`scheduleWithFixedDelay` 的任务抛出异常（包括 `Error`）后不再执行，节点从此失去自愈能力（G1）；
     * 例如 6.0–6.4.0 在 Lettuce 6 上强制重连时抛出 `NoClassDefFoundError`，心跳就此停止。`VirtualMachineError` 也不重新抛出：
     * 停掉心跳无助于 JVM 恢复，`OutOfMemoryError` 也可能只是暂时的。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun beat() {
        try {
            probe()
        } catch (e: Throwable) {
            lastHeartbeatFailure = e.toString()
            log.error(e) { "Invalidation channel heartbeat task failed - continue with the next heartbeat." }
        }
    }

    /**
     * PING 失败（包括抛出 `Error`）时重置全部订阅者并强制重连：重置不能因为 PING 抛出的类型被跳过，否则这一拍本地副本没有清。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun probe() {
        try {
            ping()
            lastHeartbeatFailure = null
        } catch (e: Throwable) {
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
        listeners.forEach { (channel, channelListeners) -> channelListeners.forEach { resetQuietly(channel, it) } }
    }

    protected fun reset(channel: String) {
        listeners[channel]?.forEach { resetQuietly(channel, it) }
    }

    /**
     * 某个订阅者的重置抛出异常（包括 `Error`）不能让其余订阅者错过重置，也不能跳过随后的强制重连或逃出心跳任务、Lettuce 的回调。
     * `VirtualMachineError` 同样不重新抛出（与 [dispatch] 不同）：重置是消息丢失之后的兜底，跳过它，其余订阅者的陈旧上界就从
     * 心跳周期退回 `localMaxAge`。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun resetQuietly(channel: String, listener: InvalidationListener) {
        try {
            listener.onReset()
        } catch (e: Throwable) {
            log.error(e) { "Invalidation listener reset failed on channel[$channel]." }
        }
    }

    /**
     * 收到消息：解码并分发给该频道的订阅者，畸形帧告警后丢弃。
     *
     * 某个订阅者抛出异常（包括 `Error`，以及 Kotlin 代码抛出的受检异常）记录后继续分发，不能让其余订阅者错过本次失效；
     * 6.9.1 及之前只兜住 `RuntimeException`，一个抛出 `Error` 的订阅者会让排在它之后的订阅者收不到这条消息。
     * `VirtualMachineError` 照常抛出，交给 Lettuce 与 Netty：消息本来就是至多一次投递，因此错过的订阅者由 `localMaxAge` 兜底。
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
        channelListeners.forEach { listener ->
            try {
                listener.onInvalidated(decoded.keys, decoded.origin)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
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
         *
         * 端点外可能包着命令超时（[CommandExpiryWriter]，Lettuce 7 的 `MaintenanceAwareExpiryWriter` 是它的子类）与命令监听
         * （[CommandListenerWriter]）的写入器，逐层拆开。不用 Lettuce 7 的 `Delegating` 接口：Spring Boot 4.0 管理的 Lettuce 6.8
         * 没有它，两个写入器的 `getDelegate()` 则在 6.8 与 7.x 上都有。
         */
        fun disconnect(connection: StatefulConnection<*, *>): Boolean {
            var writer: RedisChannelWriter? = (connection as? RedisChannelHandler<*, *>)?.channelWriter
            while (true) {
                writer = when (writer) {
                    is CommandExpiryWriter -> writer.delegate
                    is CommandListenerWriter -> writer.delegate
                    else -> break
                }
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
