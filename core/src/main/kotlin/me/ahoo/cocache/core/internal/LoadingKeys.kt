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
 * 正在由当前协程（及其子协程）加载的 key：经挂起视图对同一缓存同一 key 的重入读取立即失败，而不是等待自己的加载直到
 * `load-timeout`。引擎的同 key 重入检查基于线程，只覆盖数据源的同步部分；挂起之后在别的线程上继续时由这个元素覆盖。
 *
 * 每项的 owner 是调用级 loader 所在视图背后的异步视图实例（按实例匹配），或 spec 级 loader 的缓存名（声明时只知道名字）。
 */
internal class LoadingKeys private constructor(private val entries: List<Pair<Any, Any>>) :
    AbstractCoroutineContextElement(LoadingKeys) {
    private fun contains(async: Any, name: String, key: Any): Boolean =
        entries.any { (owner, loading) -> loading == key && (owner === async || owner == name) }

    companion object Key : CoroutineContext.Key<LoadingKeys> {
        fun adding(current: LoadingKeys?, owner: Any, keys: Collection<Any>): LoadingKeys =
            LoadingKeys(current?.entries.orEmpty() + keys.map { owner to it })

        fun check(context: CoroutineContext, async: Any, name: String, key: Any) {
            val loading = context[LoadingKeys] ?: return
            check(!loading.contains(async, name, key)) { "Recursive call on the same key[$key] is not supported." }
        }

        fun checkAll(context: CoroutineContext, async: Any, name: String, keys: Collection<Any>) {
            val loading = context[LoadingKeys] ?: return
            keys.forEach { key ->
                check(!loading.contains(async, name, key)) { "Recursive call on the same key[$key] is not supported." }
            }
        }
    }

    override fun toString(): String = "LoadingKeys($entries)"
}
