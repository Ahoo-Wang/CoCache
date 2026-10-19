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

package me.ahoo.cocache.spring.cache

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.core.CacheRuntime
import org.springframework.cache.CacheManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.cache.Cache as SpringCache

/**
 * 以 [CacheRuntime] 中已创建的缓存为来源的 Spring [CacheManager]。不会按名称自动创建缓存。
 */
class CoCacheSpringCacheManager @JvmOverloads constructor(
    private val runtime: CacheRuntime,
    private val executor: Executor = DEFAULT_EXECUTOR,
) : CacheManager {
    private val caches = ConcurrentHashMap<String, SpringCache>()

    override fun getCache(name: String): SpringCache? {
        caches[name]?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val cache = runtime.cache(name) as Cache<Any, Any>? ?: return null
        return caches.computeIfAbsent(name) { CoCacheSpringCache(cache, runtime.admin(name), executor) }
    }

    override fun getCacheNames(): Collection<String> = runtime.cacheNames

    companion object {
        /**
         * `retrieve` 的默认执行器。它在阻塞查找（以及 `sync = true` 时等待回源）上等待：
         * 运行在 JDK 21+ 时每个任务一个虚拟线程；否则是有上限的守护线程池（上限 [maxPlatformThreads]，多余任务排队），
         * 不会因为 Redis 或数据源变慢而无限创建平台线程。
         */
        @JvmField
        val DEFAULT_EXECUTOR: Executor = defaultExecutor()

        internal fun maxPlatformThreads(): Int = maxOf(
            MIN_PLATFORM_THREADS,
            Runtime.getRuntime().availableProcessors() * THREADS_PER_CPU
        )

        internal fun defaultExecutor(virtual: () -> Executor? = ::virtualThreadExecutor): Executor {
            return virtual() ?: platformExecutor()
        }

        /**
         * 基线为 JDK 17：经反射取得 `Executors.newVirtualThreadPerTaskExecutor()`，没有时返回 `null`。
         */
        internal fun virtualThreadExecutor(): Executor? {
            return runCatching {
                Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor").invoke(null) as Executor
            }.getOrNull()
        }

        internal fun platformExecutor(): ThreadPoolExecutor {
            val threads = maxPlatformThreads()
            val counter = AtomicInteger()
            return ThreadPoolExecutor(
                threads,
                threads,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                LinkedBlockingQueue()
            ) { runnable ->
                Thread(runnable, "cocache-spring-retrieve-${counter.incrementAndGet()}").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }
        }

        private const val MIN_PLATFORM_THREADS = 8
        private const val THREADS_PER_CPU = 4
        private const val KEEP_ALIVE_SECONDS = 60L
    }
}
