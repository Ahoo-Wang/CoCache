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

package me.ahoo.cocache.spring.tx

import me.ahoo.cocache.spring.SpringSupport
import me.ahoo.cocache.spring.cache.CoCacheSpringCache
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import org.springframework.cache.CacheManager
import org.springframework.cache.interceptor.CacheErrorHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import org.springframework.cache.Cache as SpringCache

/**
 * 把 [CoCacheSpringCacheManager] 的缓存包装为 [TransactionAwareSpringCache] 的 [CacheManager]（RFC 0005 §4.9 U3），
 * 取代 Spring 的 `TransactionAwareCacheManagerProxy`。starter 在 spring-tx 位于类路径上时使用它。
 * 装饰器按缓存名缓存，缓存通知每次调用时取缓存不再分配。
 *
 * @param errorHandler 事务结束之后才执行的操作失败时的处理器，每次失败时取得。默认是 Spring 的 [SimpleCacheErrorHandler]
 * （重新抛出，因此只记录日志）；应传入缓存通知所用的处理器（如 `{ cacheInterceptor.errorHandler }`），与非事务路径一致。
 */
class TransactionAwareSpringCacheManager @JvmOverloads constructor(
    val targetCacheManager: CoCacheSpringCacheManager,
    private val errorHandler: Supplier<CacheErrorHandler> = DEFAULT_ERROR_HANDLER,
) : CacheManager {

    private val caches = ConcurrentHashMap<String, SpringCache>()

    override fun getCache(name: String): SpringCache? {
        caches[name]?.let { return it }
        val target = targetCacheManager.getCache(name) as CoCacheSpringCache? ?: return null
        return caches.computeIfAbsent(name) { TransactionAwareSpringCache(target, errorHandler) }
    }

    override fun getCacheNames(): Collection<String> = targetCacheManager.cacheNames

    private companion object {
        private val DEFAULT_ERROR_HANDLER = Supplier<CacheErrorHandler> { SpringSupport.SIMPLE_ERROR_HANDLER }
    }
}
