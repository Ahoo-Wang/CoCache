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

package me.ahoo.cache.spring.cache

import me.ahoo.cache.CacheFactory
import me.ahoo.cache.api.Cache
import org.springframework.cache.support.AbstractCacheManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.cache.Cache as SpringCache

/**
 * 以 CoCache 缓存实现 Spring CacheManager。
 *
 * @param asyncExecutor 执行 [SpringCache.retrieve] 中阻塞查找的线程池
 */
class CoCacheManager(
    private val cacheFactory: CacheFactory,
    private val asyncExecutor: Executor = DEFAULT_ASYNC_EXECUTOR
) : AbstractCacheManager() {
    companion object {
        private val threadIndex = AtomicInteger()
        private const val QUEUE_CAPACITY = 1024

        /**
         * 默认异步查找线程池：有界（线程数与队列均有上限），队列满时由调用方线程执行（背压），
         * 不使用 ForkJoin 公共池，也不会在突发流量下无限创建线程。
         */
        val DEFAULT_ASYNC_EXECUTOR: Executor by lazy {
            val threads = (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(4)
            ThreadPoolExecutor(
                threads,
                threads,
                60,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(QUEUE_CAPACITY),
                { Thread(it, "cocache-retrieve-${threadIndex.incrementAndGet()}").apply { isDaemon = true } },
                ThreadPoolExecutor.CallerRunsPolicy()
            ).apply { allowCoreThreadTimeOut(true) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun loadCaches(): Collection<SpringCache> {
        return cacheFactory.caches.map { (cacheName, cache) ->
            CoSpringCache(cacheName, cache as Cache<Any, Any?>, asyncExecutor)
        }
    }

    override fun getMissingCache(name: String): SpringCache? {
        val cache = cacheFactory.getCache<Cache<Any, Any?>>(name) ?: return null
        return CoSpringCache(name, cache, asyncExecutor)
    }
}
