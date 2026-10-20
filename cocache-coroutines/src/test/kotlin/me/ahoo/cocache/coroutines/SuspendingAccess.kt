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

package me.ahoo.cocache.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.AsyncSetCache
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.test.CacheAccess
import java.time.Duration

/**
 * 经挂起视图运行 TCK：用例的每次调用在调用线程上启动一次挂起调用（`Dispatchers.Unconfined`，在第一个挂起点之前就地执行），
 * 再像 [CacheAccess.ASYNC] 一样等待其结果（线程处于 WAITING，异常为原始异常）；声明中的同步数据源换成在调用线程上启动的
 * [SuspendingCacheLoader]（`Dispatchers.Unconfined`），用例中按线程名编排的钩子因此仍作用于发起调用的线程。
 */
internal object SuspendingAccess : CacheAccess {
    override fun <K : Any, V : Any> cache(cache: Cache<K, V>): Cache<K, V> = SuspendingBackedCache(cache.suspending())

    override fun <K : Any, E : Any> setCache(cache: SetCache<K, E>): SetCache<K, E> =
        SuspendingBackedSetCache(cache.suspending())

    override fun <K : Any, V : Any> source(builder: CacheSpec.Builder<K, V>) {
        val loader = builder.loader ?: return
        builder.loader = null
        builder.suspendingLoader(Dispatchers.Unconfined, BlockingSuspendingLoader(loader))
    }

    override fun toString(): String = "SuspendingAccess"
}

private class BlockingSuspendingLoader<K : Any, V : Any>(private val delegate: CacheLoader<K, V>) : SuspendingCacheLoader<K, V> {
    override suspend fun load(key: K): V? = delegate.load(key)

    override suspend fun loadAll(keys: Collection<K>): Map<K, V> = delegate.loadAll(keys)
}

/**
 * 在调用线程上就地启动 [block]，等待其结果（`CacheAccess.await` 解包并断言没有 `CompletionException`）。
 */
private fun <T> call(block: suspend CoroutineScope.() -> T): T =
    CacheAccess.await {
        CoroutineScope(
            Dispatchers.Unconfined
        ).future(start = CoroutineStart.UNDISPATCHED, block = block)
    }

internal open class SuspendingBackedCache<K : Any, V : Any>(private val view: SuspendingCache<K, V>) : Cache<K, V> {
    override val name: String
        get() = view.name

    override fun get(key: K): V? = call { view.get(key) }

    override fun get(key: K, loader: CacheLoader<K, V>): V? = call { view.get(key) { loader.load(it) } }

    override fun getAll(keys: Collection<K>): Map<K, V> = call { view.getAll(keys) }

    override fun lookup(key: K): Lookup<V> = call { view.lookup(key) }

    override fun put(key: K, value: V) = call { view.put(key, value) }

    override fun put(key: K, value: V, ttl: Duration) = call { view.put(key, value, ttl) }

    override fun putAll(entries: Map<K, V>) = call { view.putAll(entries) }

    override fun putAll(entries: Map<K, V>, ttl: Duration) = call { view.putAll(entries, ttl) }

    override fun invalidate(key: K) = call { view.invalidate(key) }

    override fun invalidateAll(keys: Collection<K>) = call { view.invalidateAll(keys) }

    override fun async(): AsyncCache<K, V> = view.async()
}

internal class SuspendingBackedSetCache<K : Any, E : Any>(private val view: SuspendingSetCache<K, E>) :
    SuspendingBackedCache<K, Set<E>>(view),
    SetCache<K, E> {
    override fun get(key: K): Set<E> = requireNotNull(call { view.get(key) })

    override fun add(key: K, elements: Collection<E>) = call { view.add(key, elements) }

    override fun remove(key: K, elements: Collection<E>) = call { view.remove(key, elements) }

    override fun clear(key: K) = call { view.clear(key) }

    override fun async(): AsyncSetCache<K, E> = view.async()
}
