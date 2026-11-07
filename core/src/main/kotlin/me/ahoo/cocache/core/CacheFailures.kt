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

package me.ahoo.cocache.core

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * 失败的分类：调用方据此决定稍后重试或降级服务，不必逐个捕获异常类型。现有的异常类型与它们的父类不变。
 *
 * ```kotlin
 * try {
 *     cache[key]
 * } catch (e: RuntimeException) {
 *     if (CacheFailures.isTransient(e)) serveStale(key) else throw e
 * }
 * ```
 */
object CacheFailures {
    /**
     * [error] 是否是暂时的失败：稍后重试或降级服务可能成功。先拆开 [CompletionException] 与 [ExecutionException]
     * （异步视图的 future 以原始异常完成，`join()` / `get()` 会再包一层）。
     *
     * - `true`：回源超时（[SourceLoadTimeoutException]）、回源被拒绝（[SourceLoadRejectedException]）、回源被取消
     *   （[SourceLoadCancelledException]）；L1 读取或写入失败（[CacheReadException]、[CacheWriteException]：故障、命令超时、断线），
     *   cause 是 [ForeignFormatException] 的除外：L1 中是读不懂的内容，重试读到的仍是同一份，要升级节点或处理那个 key。
     * - `false`：其余异常，包括后端缺少能力时的 [UnsupportedOperationException] 与数据源（loader）自己抛出的异常。
     *
     * 结果未知的写入（[CacheWriteException.outcomeUnknown]）也是暂时的失败，但重试之前要先确认它是否已经生效（条件写入见
     * `Store.putIfMatch`）。
     */
    @JvmStatic
    fun isTransient(error: Throwable): Boolean {
        var failure: Throwable? = error
        while ((failure is CompletionException || failure is ExecutionException) && failure.cause != null) {
            failure = failure.cause
        }
        return when (failure) {
            is SourceLoadTimeoutException, is SourceLoadRejectedException, is SourceLoadCancelledException -> true
            is CacheReadException, is CacheWriteException -> failure.cause !is ForeignFormatException
            else -> false
        }
    }
}
