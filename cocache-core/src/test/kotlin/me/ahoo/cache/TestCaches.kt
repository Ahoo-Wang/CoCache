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

package me.ahoo.cache

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import java.util.*

/**
 * 测试用的纯内存一致性缓存。
 */
fun <V> inMemoryCoherentCache(
    cacheSource: CacheSource<String, V> = CacheSource.noOp(),
    ttlPolicy: TtlPolicy = TtlPolicy()
): CoherentCache<String, V> {
    return DefaultCoherentCacheFactory(NoOpCacheEvictedEventBus).create(
        CoherentCacheConfiguration(
            cacheName = "test-" + UUID.randomUUID(),
            clientId = "test",
            keyConverter = ToStringKeyConverter(""),
            distributedCache = InMemoryDistributedCache(),
            clientSideCache = MapClientSideCache(),
            cacheSource = cacheSource,
            ttlPolicy = ttlPolicy
        )
    )
}

fun <V> foreverSource(vararg entries: Pair<String, V>): CacheSource<String, V> {
    val values = entries.toMap()
    return CacheSource { key -> values[key]?.let { CacheValue.forever(it) } }
}
