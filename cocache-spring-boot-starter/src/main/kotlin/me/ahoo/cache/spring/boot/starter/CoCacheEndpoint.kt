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

package me.ahoo.cache.spring.boot.starter

import me.ahoo.cache.CacheFactory
import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.consistency.CoherentCache
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector

/**
 * 缓存运维端点：查看缓存构成、读取与淘汰条目。
 */
@Endpoint(id = CoCache.COCACHE)
class CoCacheEndpoint(override val cacheFactory: CacheFactory) : AbstractCoCacheEndpoint() {

    @ReadOperation
    fun total(): List<CacheReport> {
        return cacheFactory.caches.mapNotNull { (name, cache) ->
            (cache as? CoherentCache<*, *>)?.asReport(name)
        }
    }

    @ReadOperation
    fun stat(@Selector name: String): CacheReport? {
        return name.coherentCache()?.asReport(name)
    }

    @DeleteOperation
    fun evict(@Selector name: String, @Selector key: String) {
        name.coherentCache()?.evict(key)
    }

    /**
     * 读取条目（未命中时会回源加载）。
     */
    @ReadOperation
    fun get(@Selector name: String, @Selector key: String): CacheValue<*>? {
        return name.coherentCache()?.getCache(key)
    }

    data class CacheReport(
        val name: String,
        val clientId: String,
        val clientSize: Long,
        val ttlPolicy: TtlPolicy,
        val keyConverter: String,
        val distributedCache: String,
        val clientSideCache: String,
        val cacheSource: String,
        val keyFilter: String
    )

    private fun CoherentCache<*, *>.asReport(name: String): CacheReport {
        return CacheReport(
            name = name,
            clientId = clientId,
            clientSize = configuration.clientSideCache.size,
            ttlPolicy = configuration.ttlPolicy,
            keyConverter = configuration.keyConverter.toString(),
            distributedCache = configuration.distributedCache.javaClass.name,
            clientSideCache = configuration.clientSideCache.javaClass.name,
            cacheSource = configuration.cacheSource.javaClass.name,
            keyFilter = configuration.keyFilter.javaClass.name
        )
    }
}
