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

import me.ahoo.cocache.api.CoCache
import me.ahoo.cocache.core.CacheRuntime
import org.springframework.cache.CacheManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
        val cache = runtime.cache(name) as CoCache<Any, Any>? ?: return null
        return caches.computeIfAbsent(name) { CoCacheSpringCache(cache, runtime.admin(name), executor) }
    }

    override fun getCacheNames(): Collection<String> = runtime.cacheNames

    companion object {
        /**
         * `retrieve` 的默认执行器：守护线程，按需创建。
         */
        @JvmField
        val DEFAULT_EXECUTOR: Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "cocache-spring-retrieve").apply { isDaemon = true }
        }
    }
}
