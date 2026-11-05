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
import org.springframework.cache.Cache as SpringCache

/**
 * 以 [CacheRuntime] 中已创建的缓存为来源的 Spring [CacheManager]。不会按名称自动创建缓存。
 * `retrieve` 的方法体在运行时的执行器（[CacheRuntime.executor]）上执行。
 *
 * 只暴露派生副本 [Cache]（RFC 0005 §4.2）：`Store`、`VersionedStore`、`SetStore` 与 `VersionedCache` 不接入 Spring Cache，
 * 按它们的名称查找得到 `null`，也不出现在 [getCacheNames] 中。
 */
class CoCacheSpringCacheManager(
    private val runtime: CacheRuntime,
) : CacheManager {
    private val caches = ConcurrentHashMap<String, SpringCache>()

    override fun getCache(name: String): SpringCache? {
        caches[name]?.let { return it }
        val cache = derivedCache(name) ?: return null
        return caches.computeIfAbsent(name) { CoCacheSpringCache(cache, runtime.admin(name), runtime.executor) }
    }

    override fun getCacheNames(): Collection<String> = runtime.cacheNames.filter { derivedCache(it) != null }

    @Suppress("UNCHECKED_CAST")
    private fun derivedCache(name: String): Cache<Any, Any>? = runtime.cache(name) as? Cache<Any, Any>
}
