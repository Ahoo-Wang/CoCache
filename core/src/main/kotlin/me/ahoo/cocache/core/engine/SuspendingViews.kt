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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SuspendingReadableCache
import me.ahoo.cocache.core.internal.LoadingKeys
import kotlin.coroutines.coroutineContext

/**
 * 挂起视图（`suspending()`）的读取，四种由 [CoherentEngine] 实现的用户 API 类型共用；写入由各引擎的 `SuspendingView` 实现。
 * 挂起视图直接调用协程核心（RFC 0005 §4.4，K2、K6、K7）：L2 命中内联返回，未命中在运行时的调度器上运行核心，元素取自调用方
 * （[CoherentEngine.entrySuspending]、[CoherentEngine.resolveAllSuspending]）。
 *
 * 读取先检查同 key 重入（[LoadingKeys]）：在这个缓存的 loader 中经挂起视图读取同一 key 立即失败，而不是等待自己的加载。
 * 每个方法只有尾调用一个挂起点，编译器不为它们生成状态机：L2 命中不分配。
 *
 * @param E 引擎的值（带版本的类型是 `Versioned<V?>`）；[answer] 等把引擎的副本换算为用户的结果
 */
internal abstract class SuspendingReads<K : Any, V : Any, E : Any>(
    private val engine: CoherentEngine<K, E>,
    private val answer: (LocalEntry<E>) -> V?,
    private val lookupAnswer: (LocalEntry<E>) -> Lookup<V>,
    private val values: (Map<K, LocalEntry<E>?>) -> Map<K, V>,
    private val lookups: (Map<K, LocalEntry<E>?>) -> Map<K, Lookup<V>>,
) : SuspendingReadableCache<K, V> {
    override val name: String
        get() = engine.name

    override suspend fun get(key: K): V? {
        LoadingKeys.check(coroutineContext, engine, name, key)
        return engine.entrySuspending(key, answer)
    }

    override suspend fun getAll(keys: Collection<K>): Map<K, V> {
        LoadingKeys.checkAll(coroutineContext, engine, name, keys)
        return engine.resolveAllSuspending(keys, values)
    }

    override suspend fun lookup(key: K): Lookup<V> {
        LoadingKeys.check(coroutineContext, engine, name, key)
        return engine.entrySuspending(key, lookupAnswer)
    }

    override suspend fun lookupAll(keys: Collection<K>): Map<K, Lookup<V>> {
        LoadingKeys.checkAll(coroutineContext, engine, name, keys)
        return engine.resolveAllSuspending(keys, lookups)
    }

    override fun toString(): String = "Suspending($engine)"
}
