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

package me.ahoo.cocache.spring.boot

import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.redis.RedisBackend
import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * 启动时检查 `SetCache` 的唯一副本会不会被 Redis 驱逐：任一主节点的 `maxmemory-policy` 为 `allkeys-*` 时，
 * 内存满后集合可能被整体删除，且无法从数据源恢复。只检查启动时已创建的集合缓存；`CONFIG` 不可用时跳过。
 */
internal class SetCacheEvictionCheck(
    private val runtime: CacheRuntime,
    private val backend: CoherenceBackend,
    private val mode: CoCacheProperties.EvictionCheck,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        if (mode == CoCacheProperties.EvictionCheck.OFF || backend !is RedisBackend) {
            return
        }
        val setCaches = runtime.cacheNames.filter { runtime.cache(it) is SetCache<*, *> }.sorted()
        if (setCaches.isEmpty()) {
            return
        }
        val policies = backend.maxmemoryPolicies() ?: return
        val evicting = policies.filterValues { it.startsWith(ALL_KEYS_PREFIX) }
        if (evicting.isEmpty()) {
            return
        }
        val message = "SetCache $setCaches keeps its only copy in Redis, but maxmemory-policy $evicting can evict it when " +
            "memory is full. Use noeviction or a volatile-* policy, or set " +
            "${CoCacheProperties.PREFIX}.redis.set-cache-eviction-check=off to accept the risk."
        check(mode != CoCacheProperties.EvictionCheck.FAIL) { message }
        log.warn(message)
    }

    private companion object {
        const val ALL_KEYS_PREFIX = "allkeys-"
    }
}

private val log = LogFactory.getLog(SetCacheEvictionCheck::class.java)
