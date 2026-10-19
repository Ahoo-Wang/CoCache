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

package me.ahoo.cocache.api

/**
 * L1 是唯一副本的集合缓存：元素增删在 L1 中原子完成，并由 L1 串行化。
 *
 * - 没有数据源：未命中即空集合，[get] 返回空集合，[lookup] 返回 [Lookup.Absent]。
 * - [invalidate] 会销毁唯一副本，因此总是抛出 [UnsupportedOperationException]；删除数据用 [clear]。
 * - 数据持久性等于 L1 的持久性；L1 不得使用会淘汰无 TTL key 的内存策略（如 Redis `allkeys-*`）。
 *
 * 数据权威在数据源中的集合，应声明为普通的 `Cache<K, Set<E>>` 并用 [invalidate] 维护。
 */
interface SetCache<K : Any, E : Any> : Cache<K, Set<E>> {
    /**
     * 读取集合；不存在时返回空集合（空集合与不存在等价）。
     */
    override fun get(key: K): Set<E>

    /**
     * 原子地加入元素；key 不存在时创建。[elements] 为空时不做任何事。
     *
     * @throws CacheWriteException L1 写入失败
     */
    fun add(key: K, elements: Collection<E>)

    /**
     * 原子地移除元素；移空时删除 key。key 不存在时不做任何事。
     *
     * @throws CacheWriteException L1 写入失败
     */
    fun remove(key: K, elements: Collection<E>)

    /**
     * 删除整个集合并广播。
     *
     * @throws CacheWriteException L1 写入失败
     */
    fun clear(key: K)

    /**
     * 同一个集合缓存的异步视图（RFC 0004）。其它实现默认抛出 [UnsupportedOperationException]。
     */
    override fun async(): AsyncSetCache<K, E> = throw UnsupportedOperationException(
        "${javaClass.name} does not support async()."
    )
}
