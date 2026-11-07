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
 * 以 [CacheRuntime] 中的缓存为来源的 Spring [CacheManager]。不会按名称自动创建未声明的缓存。
 * `retrieve` 的方法体在运行时的执行器（[CacheRuntime.executor]）上执行。
 *
 * 只暴露派生副本 [Cache]（RFC 0005 §4.2）：`Store`、`VersionedStore`、`SetStore` 与 `VersionedCache` 不接入 Spring Cache，
 * 按它们的名称查找得到 `null`，也不出现在 [getCacheNames] 中。
 *
 * @param createDeclared 名称不在运行时中时调用：创建以这个名称声明、尚未创建的缓存（如延迟初始化的缓存 bean，它没有注入点，
 * 只经 `@Cacheable` 使用时从不创建），返回之后再查一次运行时。默认什么也不做；starter 按缓存 bean 的定义创建。
 */
class CoCacheSpringCacheManager @JvmOverloads constructor(
    private val runtime: CacheRuntime,
    private val createDeclared: (name: String) -> Unit = {},
) : CacheManager {
    private val caches = ConcurrentHashMap<String, SpringCache>()

    override fun getCache(name: String): SpringCache? {
        caches[name]?.let { return it }
        val cache = derivedCache(name) ?: createdCache(name) ?: return null
        return caches.computeIfAbsent(name) { CoCacheSpringCache(cache, runtime.admin(name), runtime.executor) }
    }

    private fun createdCache(name: String): Cache<Any, Any>? {
        createDeclared(name)
        return derivedCache(name)
    }

    override fun getCacheNames(): Collection<String> = runtime.cacheNames.filter { derivedCache(it) != null }

    @Suppress("UNCHECKED_CAST")
    private fun derivedCache(name: String): Cache<Any, Any>? = runtime.cache(name) as? Cache<Any, Any>
}
