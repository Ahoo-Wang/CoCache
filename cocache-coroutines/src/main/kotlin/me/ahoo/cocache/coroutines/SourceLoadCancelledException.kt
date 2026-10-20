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
import kotlinx.coroutines.isActive
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException

/**
 * 挂起数据源的一次加载以取消结束：数据源自己抛出了 `CancellationException`（如数据源内部的 `withTimeout` 超时），
 * 或加载协程被取消（如 spec 的 `context` 中的 Job 已被取消，包括加载开始之前就已取消）。
 *
 * 加载可能被多个调用方共享：原样传出的话，挂起的调用方会把它当作自己被取消（`launch` 会悄悄吞掉），因此转为普通异常，
 * 与同步、异步数据源的失败一样传给所有调用方（同步、异步与挂起的调用方都收到它）。原始异常是 [cause]。
 * 与 `SourceLoadTimeoutException`、`SourceLoadRejectedException` 一样是 [RuntimeException]。
 */
class SourceLoadCancelledException(val cacheName: String?, cause: CancellationException) :
    RuntimeException("Cache[${cacheName ?: "?"}] - the suspending source was cancelled: ${cause.message}", cause)

/**
 * 以取消结束的加载转为 [SourceLoadCancelledException]（挂起数据源的两个边界共用）。放在 object 里，Java 不会看到多余的 `*Kt` 门面类。
 */
internal object LoadCancellation {
    /**
     * 加载 future 的边界：以取消结束的加载（加载协程被取消，或在已取消的作用域中启动）改以 [SourceLoadCancelledException] 完成。
     */
    fun <T> CompletableFuture<T>.cancelledAsFailure(cacheName: String?): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        // `future {}` 以原始异常完成（不包装为 CompletionException）
        whenComplete { value, error ->
            when (error) {
                null -> result.complete(value)
                is CancellationException -> result.completeExceptionally(SourceLoadCancelledException(cacheName, error))
                else -> result.completeExceptionally(error)
            }
        }
        return result
    }

    /**
     * 执行数据源：加载协程仍处于活动状态时，数据源抛出的 `CancellationException` 转为 [SourceLoadCancelledException]；
     * 加载协程本身被取消（如 spec 的 `context` 中的 Job 被取消）时原样传出。
     */
    suspend inline fun <T> CoroutineScope.cancellationAsFailure(cacheName: String?, block: () -> T): T {
        return try {
            block()
        } catch (e: CancellationException) {
            if (isActive) throw SourceLoadCancelledException(cacheName, e) else throw e
        }
    }
}
