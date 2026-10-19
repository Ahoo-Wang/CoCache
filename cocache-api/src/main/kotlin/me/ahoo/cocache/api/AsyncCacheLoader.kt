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
import java.util.concurrent.CompletionStage

/**
 * 异步数据源（RFC 0004）：只负责加载，寿命由缓存策略决定。
 *
 * - [loadAsync] 必须很快返回、不得阻塞：它在调用方线程（结果已同步可得时）或异步执行器上被调用，
 *   看不到调用方的线程上下文（ThreadLocal）。
 * - 同步抛出的异常与异常完成的 future 等价；返回的 future 可以在任何线程上完成，引擎会切换回自己的线程。
 * - 未完成的加载受 `LoadPolicy.asyncTimeout` 约束。
 */
fun interface AsyncCacheLoader<K : Any, V : Any> {
    /**
     * 加载单个 key；以 `null` 完成表示数据源中不存在（写入负缓存）。
     */
    fun loadAsync(key: K): CompletionStage<out V?>

    /**
     * 批量加载：结果中缺少的 key 视为不存在。默认对每个 key 调用 [loadAsync] 并合并；数据源应覆盖为一次批量查询。
     */
    @Suppress("TooGenericExceptionCaught")
    fun loadAllAsync(keys: Collection<K>): CompletionStage<out Map<K, V>> {
        val futures = LinkedHashMap<K, CompletableFuture<out V?>>()
        for (key in keys) {
            futures[key] = try {
                loadAsync(key).toCompletableFuture()
            } catch (e: Throwable) {
                CompletableFuture.failedFuture(e)
            }
        }
        return CompletableFuture.allOf(*futures.values.toTypedArray()).thenApply {
            val result = LinkedHashMap<K, V>()
            futures.forEach { (key, future) -> future.join()?.let { result[key] = it } }
            result
        }
    }
}
