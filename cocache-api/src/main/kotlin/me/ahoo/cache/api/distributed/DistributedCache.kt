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

package me.ahoo.cache.api.distributed

import me.ahoo.cache.api.CacheStore

/**
 * L1 分布式缓存存储（如 Redis），由所有实例共享。
 *
 * [getCache] 返回 `null` 必须表示“未命中”（触发回源）；仅当存储中确有负缓存记录时才返回负缓存。
 *
 * @author ahoo wang
 */
interface DistributedCache<V> : CacheStore<V>, AutoCloseable {
    override fun close() = Unit
}
