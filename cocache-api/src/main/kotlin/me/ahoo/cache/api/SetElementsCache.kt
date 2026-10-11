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

package me.ahoo.cache.api

/**
 * 集合值缓存的原子元素增删能力，与 `Cache<K, Set<E>>` 组合声明在缓存接口上：
 *
 * ```kotlin
 * @CoCache(ttl = TtlAt.FOREVER)
 * interface PolicyIndexCache : Cache<String, Set<String>>, SetElementsCache<String, String>
 * ```
 *
 * 与“读整个集合 → 修改 → 写回”不同，增删在分布式缓存（L1）中原子完成，跨实例的并发写者不会丢失更新；
 * 完成后淘汰本地副本（L2）并广播失效事件。
 *
 * 语义：L1 中的集合被视为该 key 的权威副本，增删**不访问** [CacheSource][me.ahoo.cache.api.source.CacheSource]。
 * 若集合的真实数据在数据源中，应先更新数据源再 [evict][CacheSetter.evict]，而不是使用本接口。
 *
 * @param K 业务 key
 * @param E 集合元素
 */
interface SetElementsCache<K, E> {
    /**
     * 原子地向 [key] 的集合加入 [elements]：key 不存在时创建；key 为负缓存时替换为仅含 [elements] 的集合。
     * 写入后按缓存的 TTL 策略重置到期时间（永不过期的策略会移除已有的到期时间）。[elements] 为空时不做任何事。
     */
    fun addElements(key: K, elements: Collection<E>)

    /**
     * 原子地从 [key] 的集合移除 [elements]：key 不存在或为负缓存时不做任何事；
     * 集合被移空时淘汰该 key（下次读取未命中、回源）；否则按缓存的 TTL 策略重置到期时间。[elements] 为空时不做任何事。
     */
    fun removeElements(key: K, elements: Collection<E>)
}
