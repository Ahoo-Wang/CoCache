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

import me.ahoo.cache.api.CacheValue

/**
 * 集合值 L1 存储的原子元素增删 SPI，由支持 [SetElementsCache][me.ahoo.cache.api.SetElementsCache] 的
 * [DistributedCache] 实现。与 [me.ahoo.cache.api.CacheStore] 一样只存不判：到期时间 `ttlAt` 由编排层按 TTL 策略计算。
 *
 * 实现约定（每个操作必须原子完成）：
 * - [addElements]：key 为负缓存时先清除负缓存记录；加入元素；到期时间设为 `ttlAt`
 *   （[TtlAt.FOREVER][me.ahoo.cache.api.TtlAt.FOREVER] 表示移除到期时间）。
 * - [removeElements]：key 不存在或为负缓存时不修改；移除元素；集合变空时删除 key，否则到期时间设为 `ttlAt`。
 * - [addElements] / [removeElements] 失败时必须抛出异常（不得静默降级）：调用方需要知道写入是否生效。
 * - [fillCache] / [evictIfUnchanged]：回源写回及其撤销。L1 中的集合是元素增删的权威副本，
 *   无条件的写回或撤销（DEL）会吞没与之并发的增删，因此二者都是条件操作。
 *
 * @param E 集合元素
 */
interface SetElementsStore<E> {
    fun addElements(key: String, elements: Collection<E>, ttlAt: Long)

    fun removeElements(key: String, elements: Collection<E>, ttlAt: Long)

    /**
     * 回源写回：仅当 key 不存在或为负缓存时写入 [value]（已过期的 [value] 不写入）。
     *
     * @return 是否写入；`false` 表示 L1 已有集合（如并发的 [addElements]），调用方以 L1 为准
     */
    fun fillCache(key: String, value: CacheValue<Set<E>>): Boolean

    /**
     * 撤销 [fillCache]：仅当 key 仍保存与 [value] 相同的内容（负缓存或相同成员）时删除，不比较到期时间。
     */
    fun evictIfUnchanged(key: String, value: CacheValue<Set<E>>)
}
