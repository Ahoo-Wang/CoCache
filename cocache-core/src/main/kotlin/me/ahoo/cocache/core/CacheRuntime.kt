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

package me.ahoo.cocache.core

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.core.clock.TickingClock
import me.ahoo.cocache.core.engine.AbstractEngine
import me.ahoo.cocache.core.engine.CoherentEngine
import me.ahoo.cocache.core.engine.EngineContext
import me.ahoo.cocache.core.engine.InvalidationRetrier
import me.ahoo.cocache.core.engine.SetEngine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 缓存运行时（每个进程一个）：持有节点身份、时钟、后端与执行器，并按声明创建缓存。
 *
 * 关闭时按创建的逆序关闭所有缓存与订阅，并关闭运行时自己创建的时钟与执行器；后端由调用方管理。
 */
class CacheRuntime private constructor(builder: Builder) : AutoCloseable {
    val nodeId: NodeId = builder.nodeId ?: NodeId.random()
    val clock: CacheClock
    val backend: CoherenceBackend = requireNotNull(builder.backend) { "backend is required." }

    private val owned = ArrayList<AutoCloseable>()
    private val context: EngineContext
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val names = ConcurrentHashMap<String, Registration>()
    private val namespaces = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean(false)

    private class Registration(val engine: AbstractEngine<*, *>, val store: RemoteStore, var subscription: Subscription? = null)

    init {
        clock = builder.clock ?: TickingClock().also { owned.add(it) }
        val refreshExecutor = builder.refreshExecutor ?: defaultRefreshExecutor().also {
            owned.add(AutoCloseable { it.shutdownNow() })
        }
        val scheduler = defaultScheduler().also { owned.add(AutoCloseable { it.shutdownNow() }) }
        val retrier = InvalidationRetrier(scheduler, builder.retryPolicy)
        // 先于调度器关闭：未执行的重试计入丢弃并告警
        owned.add(AutoCloseable { retrier.close() })
        context = EngineContext(nodeId, clock, backend, refreshExecutor, retrier)
    }

    /**
     * 按声明创建缓存。同一运行时内缓存名与命名空间都不得重复。
     */
    fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V> {
        return register(spec.name, spec.namespace) { store -> CoherentEngine(spec, store, context) }
    }

    /**
     * 按声明创建集合缓存。
     */
    fun <K : Any, E : Any> create(spec: SetCacheSpec<K, E>): SetCache<K, E> {
        return register(spec.name, spec.namespace) { store -> SetEngine(spec, store, context) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <C> register(
        name: String,
        namespace: String,
        factory: (RemoteStore) -> C
    ): C where C : AbstractEngine<*, *> {
        check(!closed.get()) { "CacheRuntime is closed." }
        require(names[name] == null) { "Cache[$name] already exists." }
        require(namespaces.add(namespace)) { "Namespace[$namespace] is already used by another cache." }
        val store = backend.store(namespace)
        val engine = factory(store)
        val registration = Registration(engine, store)
        if (names.putIfAbsent(name, registration) != null) {
            namespaces.remove(namespace)
            throw IllegalArgumentException("Cache[$name] already exists.")
        }
        registrations.add(registration)
        // 订阅建立时通道会回调 onReset（清空空的 L2，无害）
        registration.subscription = try {
            backend.channel.subscribe(namespace, engine)
        } catch (e: Exception) {
            // 回滚：否则同名重试会因“已存在”失败，而该命名空间永远收不到失效
            registrations.remove(registration)
            names.remove(name, registration)
            namespaces.remove(namespace)
            closeQuietly("cache $name") { engine.close() }
            closeQuietly("store of $name") { store.close() }
            throw e
        }
        log.info { "Cache[$name] - created on namespace[$namespace], node[$nodeId]." }
        return engine
    }

    /**
     * 按名称查找已创建的缓存。
     */
    fun cache(name: String): Cache<*, *>? = names[name]?.engine as Cache<*, *>?

    /**
     * 按名称查看缓存的运行统计。
     */
    fun metrics(name: String): CacheMetrics? = names[name]?.engine?.metrics

    /**
     * 按名称取得运维接口。
     */
    fun admin(name: String): CacheAdmin? = names[name]?.engine

    val cacheNames: Set<String>
        get() = names.keys.toSet()

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        registrations.reversed().forEach { registration ->
            closeQuietly("subscription of ${registration.engine.name}") { registration.subscription?.close() }
            closeQuietly("cache ${registration.engine.name}") { registration.engine.close() }
            closeQuietly("store of ${registration.engine.name}") { registration.store.close() }
        }
        owned.reversed().forEach { resource -> closeQuietly(resource.toString()) { resource.close() } }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun closeQuietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn(e) { "Failed to close $what." }
        }
    }

    class Builder internal constructor() {
        var backend: CoherenceBackend? = null
        var clock: CacheClock? = null
        var nodeId: NodeId? = null
        var refreshExecutor: Executor? = null
        var retryPolicy: RetryPolicy = RetryPolicy()

        fun backend(backend: CoherenceBackend) = apply { this.backend = backend }

        /**
         * 不指定时运行时创建并管理一个 [TickingClock]。
         */
        fun clock(clock: CacheClock) = apply { this.clock = clock }
        fun nodeId(nodeId: NodeId) = apply { this.nodeId = nodeId }

        /**
         * 提前刷新执行器，由调用方管理。不指定时使用有界守护线程池（队列满时拒绝，刷新被跳过并计数）。
         */
        fun refreshExecutor(refreshExecutor: Executor) = apply { this.refreshExecutor = refreshExecutor }
        fun retryPolicy(retryPolicy: RetryPolicy) = apply { this.retryPolicy = retryPolicy }

        fun build(): CacheRuntime = CacheRuntime(this)
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private const val REFRESH_QUEUE_CAPACITY = 1024

        @JvmStatic
        fun builder(): Builder = Builder()

        private fun daemonThreads(prefix: String): ThreadFactory {
            val counter = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }

        private fun defaultRefreshExecutor(): ExecutorService {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            return ThreadPoolExecutor(
                threads,
                threads,
                1,
                TimeUnit.MINUTES,
                ArrayBlockingQueue(REFRESH_QUEUE_CAPACITY),
                daemonThreads("cocache-refresh"),
                ThreadPoolExecutor.AbortPolicy(),
            ).apply { allowCoreThreadTimeOut(true) }
        }

        private fun defaultScheduler(): ScheduledExecutorService {
            return Executors.newSingleThreadScheduledExecutor(daemonThreads("cocache-retry"))
        }
    }
}

/**
 * Kotlin DSL：`cacheRuntime { backend = …; clock = … }`。
 */
fun cacheRuntime(block: CacheRuntime.Builder.() -> Unit): CacheRuntime = CacheRuntime.builder().apply(block).build()
