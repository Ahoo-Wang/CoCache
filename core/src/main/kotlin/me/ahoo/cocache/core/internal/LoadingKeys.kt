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

package me.ahoo.cocache.core.internal

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 正在由当前协程（及其子协程）加载的 key（RFC 0005 §4.4，K7）：经挂起视图对同一缓存同一 key 的重入读取立即失败，而不是等待自己的加载直到
 * `load-timeout`。它在现有的守卫之上增加，不替换它们：同步变体的拥有线程检测与异步变体在 `loading` 的同步调用段的 `ThreadLocal`
 * 都保留，因为协程上下文元素穿不过 `runBlocking` 与 `future {}`。挂起之后在别的线程上继续时由这个元素覆盖；loader 经 future
 * 换线程之后的重入检测不到，以 `load-timeout` 为界。
 *
 * 每项的 owner 是调用级挂起 loader 所属的引擎（按实例匹配），或 spec 级挂起 loader 的缓存名（声明时只知道名字）。
 */
internal class LoadingKeys private constructor(private val entries: List<Pair<Any, Any>>) :
    AbstractCoroutineContextElement(LoadingKeys) {
    private fun contains(owner: Any, name: String, key: Any): Boolean =
        entries.any { (registered, loading) -> loading == key && (registered === owner || registered == name) }

    companion object Key : CoroutineContext.Key<LoadingKeys> {
        fun adding(current: LoadingKeys?, owner: Any, keys: Collection<Any>): LoadingKeys =
            LoadingKeys(current?.entries.orEmpty() + keys.map { owner to it })

        /**
         * 挂起视图的读取入口：[owner]（引擎）名为 [name] 的缓存正在当前协程中加载 [key] 时抛出 [IllegalStateException]。
         */
        fun check(context: CoroutineContext, owner: Any, name: String, key: Any) {
            val loading = context[LoadingKeys] ?: return
            check(!loading.contains(owner, name, key)) { "Recursive call on the same key[$key] is not supported." }
        }

        fun checkAll(context: CoroutineContext, owner: Any, name: String, keys: Collection<Any>) {
            val loading = context[LoadingKeys] ?: return
            keys.forEach { key ->
                check(!loading.contains(owner, name, key)) { "Recursive call on the same key[$key] is not supported." }
            }
        }
    }

    override fun toString(): String = "LoadingKeys($entries)"
}
