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
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.cache.Cache as SpringCache

/**
 * 以 [CacheRuntime] 中已创建的缓存为来源的 Spring [CacheManager]。不会按名称自动创建缓存。
 */
class CoCacheSpringCacheManager(
    private val runtime: CacheRuntime,
) : CacheManager {
    /**
     * 6.3 起 `retrieve` 经缓存的异步视图，不再使用执行器：[executor] 被忽略，7.0 删除此构造函数。
     */
    @Deprecated(
        "Since 6.3, retrieve uses the async view and no executor; the executor is ignored.",
        ReplaceWith("CoCacheSpringCacheManager(runtime)")
    )
    @Suppress("UNUSED_PARAMETER")
    constructor(runtime: CacheRuntime, executor: Executor) : this(runtime)

    private val caches = ConcurrentHashMap<String, SpringCache>()

    override fun getCache(name: String): SpringCache? {
        caches[name]?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val cache = runtime.cache(name) as Cache<Any, Any>? ?: return null
        return caches.computeIfAbsent(name) { CoCacheSpringCache(cache, runtime.admin(name)) }
    }

    override fun getCacheNames(): Collection<String> = runtime.cacheNames

    companion object {
        /**
         * `retrieve` 的默认执行器。它在阻塞查找（以及 `sync = true` 时等待回源）上等待：
         * 运行在 JDK 21+ 时每个任务一个虚拟线程；否则是可增长的守护线程池：空闲线程不足时新建（上限 [MAX_PLATFORM_THREADS]），
         * 不排队，因此嵌套的异步 `@Cacheable(sync = true)` 不会因内层任务排在外层任务之后而死锁；达到上限时由调用方线程执行。
         *
         * 6.3 起 `retrieve` 不再使用执行器，保留它只为二进制兼容，7.0 删除。
         */
        @Deprecated("Since 6.3, retrieve uses the async view and no executor.")
        @JvmField
        val DEFAULT_EXECUTOR: Executor = defaultExecutor()

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
            val counter = AtomicInteger()
            return ThreadPoolExecutor(
                0,
                MAX_PLATFORM_THREADS,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                SynchronousQueue(),
                { runnable ->
                    Thread(
                        runnable,
                        "cocache-spring-retrieve-${counter.incrementAndGet()}"
                    ).apply { isDaemon = true }
                },
                ThreadPoolExecutor.CallerRunsPolicy()
            )
        }

        internal const val MAX_PLATFORM_THREADS = 512
        private const val KEEP_ALIVE_SECONDS = 60L
    }
}
