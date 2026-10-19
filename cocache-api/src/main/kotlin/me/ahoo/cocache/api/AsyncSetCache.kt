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

import java.util.concurrent.CompletableFuture

/**
 * [SetCache] 的异步视图：语义与同名的同步方法完全相同。
 *
 * [get] 总以非 `null` 的集合完成（不存在为空集合）；[invalidate] / [invalidateAll] 总以 [UnsupportedOperationException] 完成。
 */
interface AsyncSetCache<K : Any, E : Any> : AsyncCache<K, Set<E>> {
    /**
     * 原子地加入元素，语义同 [SetCache.add]。
     */
    fun add(key: K, elements: Collection<E>): CompletableFuture<Void?>

    /**
     * 原子地移除元素，语义同 [SetCache.remove]。
     */
    fun remove(key: K, elements: Collection<E>): CompletableFuture<Void?>

    /**
     * 删除整个集合，语义同 [SetCache.clear]。
     */
    fun clear(key: K): CompletableFuture<Void?>

    override fun sync(): SetCache<K, E>
}
