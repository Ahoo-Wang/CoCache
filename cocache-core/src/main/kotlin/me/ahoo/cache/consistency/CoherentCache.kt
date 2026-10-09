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

import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.NamedCache

/**
 * 两级一致性缓存：L2（进程内）→ L1（分布式）→ 数据源。
 *
 * 只暴露缓存语义；组件构成通过只读的 [configuration] 查看（供运维端点使用）。
 */
interface CoherentCache<K, V> : Cache<K, V>, NamedCache, AutoCloseable {
    val configuration: CoherentCacheConfiguration<K, V>

    override val cacheName: String
        get() = configuration.cacheName

    val clientId: String
        get() = configuration.clientId
}
