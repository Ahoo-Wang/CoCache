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

import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.consistency.GuavaCacheEvictedEventBus
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.mock.MockDistributedCache
import me.ahoo.cache.test.CoherentCacheSpec
import java.util.*

/**
 * Coherent Cache Test .
 *
 * @author ahoo wang
 */
internal class CoherentCacheTest : CoherentCacheSpec<String, String>() {

    override fun createKeyConverter(): KeyConverter<String> = ToStringKeyConverter("")

    override fun createClientSideCache(): ClientSideCache<String> = MapClientSideCache()

    override fun createDistributedCache(): DistributedCache<String> = MockDistributedCache()

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = GuavaCacheEvictedEventBus()
    override fun createCacheName(): String {
        return "CoherentCacheTest"
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }
}
