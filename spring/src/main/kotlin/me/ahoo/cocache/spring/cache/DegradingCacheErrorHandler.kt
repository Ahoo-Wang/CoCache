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
import org.apache.commons.logging.LogFactory
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.cache.interceptor.SimpleCacheErrorHandler
import org.springframework.cache.Cache as SpringCache

/**
 * 只对 CoCache 的缓存放行读写失败的 [CacheErrorHandler]（RFC 0005 §4.9 S18）。starter 经
 * `cocache.spring-cache.degrade-on-error=true` 启用。
 *
 * 被注解的方法本身就是数据源，CoCache 缓存是它的派生副本，可以降级（A7）：
 * - 读取失败（不带 `sync` 的 `@Cacheable` 在 L1 故障时抛出的 `CacheReadException`）记录告警，Spring 随后按未命中执行方法。
 *   带 `sync = true` 的读取有 loader，由引擎自己降级回源，不经过这里。
 * - 写入失败（`@CachePut`、不带 `sync` 的 `@Cacheable` 随后的 `put`）记录告警，方法的结果照常返回，缓存保持原样，陈旧以 `ttl` 为界。
 *
 * 其余交给 [delegate]：其它缓存的全部失败；CoCache 缓存的失效（`evict`、`evictIfPresent`、映射为失效的 `put(key, null)`）
 * 与清空失败。失效失败不放行：`invalidate-failure: retry` 时它们不会抛出（进入重试队列），配置为 `throw` 即要求调用方看到失败。
 *
 * 识别 CoCache 的缓存按 `nativeCache` 是 CoCache 的 [Cache]，因此同样识别包装了 [CoCacheSpringCache] 的装饰器。
 * 告警不带堆栈（L1 故障期间每次调用一条），堆栈在 DEBUG 级别输出；不记录 key。
 */
class DegradingCacheErrorHandler @JvmOverloads constructor(
    val delegate: CacheErrorHandler = SimpleCacheErrorHandler(),
) : CacheErrorHandler {

    override fun handleCacheGetError(exception: RuntimeException, cache: SpringCache, key: Any) {
        if (!isCoCache(cache)) {
            delegate.handleCacheGetError(exception, cache, key)
            return
        }
        degrade(exception, cache, "read failed; treated as a miss, so the method runs")
    }

    override fun handleCachePutError(exception: RuntimeException, cache: SpringCache, key: Any, value: Any?) {
        if (value == null || !isCoCache(cache)) {
            delegate.handleCachePutError(exception, cache, key, value)
            return
        }
        degrade(exception, cache, "write failed; the value is not cached")
    }

    override fun handleCacheEvictError(exception: RuntimeException, cache: SpringCache, key: Any) {
        delegate.handleCacheEvictError(exception, cache, key)
    }

    override fun handleCacheClearError(exception: RuntimeException, cache: SpringCache) {
        delegate.handleCacheClearError(exception, cache)
    }

    private fun isCoCache(cache: SpringCache): Boolean = cache.nativeCache is Cache<*, *>

    private fun degrade(exception: RuntimeException, cache: SpringCache, outcome: String) {
        val message = "Cache[${cache.name}]: $outcome"
        if (log.isDebugEnabled) {
            log.debug("$message.", exception)
        } else {
            log.warn("$message: $exception")
        }
    }

    private companion object {
        private val log = LogFactory.getLog(DegradingCacheErrorHandler::class.java)
    }
}
