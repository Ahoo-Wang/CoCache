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

import me.ahoo.cache.CacheManager
import me.ahoo.cache.CacheValue
import me.ahoo.cache.CoCache
import me.ahoo.cache.CoherentCache
import me.ahoo.cache.spring.boot.starter.CoCacheEndpoint.CacheReport.Companion.asReport
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector

@Endpoint(id = CoCache.COCACHE)
class CoCacheEndpoint(private val cacheManager: CacheManager) {

    @ReadOperation
    fun total(): List<CacheReport> {
        return cacheManager.getCacheNames().mapNotNull {
            cacheManager.getCache<String, Any>(it)?.asReport()
        }.toList()
    }

    @ReadOperation
    fun stat(@Selector name: String): CacheReport? {
        return cacheManager.getCache<String, Any>(name)?.asReport()
    }

    @DeleteOperation
    fun evict(@Selector name: String, @Selector key: String) {
        cacheManager.getCache<String, Any>(name)?.evict(key)
    }

    @ReadOperation
    fun get(@Selector name: String, @Selector key: String): CacheValue<*>? {
        return cacheManager.getCache<String, Any>(name)?.getCache(key)
    }

    data class CacheReport(
        val name: String,
        val clientId: String,
        val clientSize: Long,
        val keyConverter: String,
        val distributedCaching: String,
        val clientSideCaching: String,
        val cacheEvictedEventBus: String,
        val cacheSource: String,
        val keyFilter: String,
        val missingGuardTtl: Long
    ) {
        companion object {
            fun CoherentCache<*, *>.asReport(): CacheReport {
                return CacheReport(
                    name = cacheName,
                    clientId = clientId,
                    clientSize = clientSideCaching.size,
                    keyConverter = keyConverter.javaClass.name,
                    distributedCaching = distributedCaching.javaClass.name,
                    clientSideCaching = clientSideCaching.javaClass.name,
                    cacheEvictedEventBus = cacheEvictedEventBus.javaClass.name,
                    cacheSource = cacheSource.javaClass.name,
                    keyFilter = keyFilter.javaClass.name,
                    missingGuardTtl = missingGuardTtl
                )
            }
        }
    }
}
