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

package me.ahoo.cache.consistency

import me.ahoo.cache.KeyFilter
import me.ahoo.cache.api.NamedCache
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.DistributedClientId
import me.ahoo.cache.filter.NoOpKeyFilter

data class CoherentCacheConfiguration<K, V>(
    override val cacheName: String,
    override val clientId: String,
    val keyConverter: KeyConverter<K>,
    val distributedCache: DistributedCache<V>,
    val clientSideCache: ClientSideCache<V> = MapClientSideCache(),
    val cacheSource: CacheSource<K, V> = CacheSource.noOp(),
    val keyFilter: KeyFilter = NoOpKeyFilter
) : NamedCache, DistributedClientId
