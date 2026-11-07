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

import kotlinx.coroutines.Job
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 在当前线程上开始 [block]，开始时不装上 [context] 中的 `ThreadContextElement`（#876）；返回的 future 在 block 自己结束
 * （返回或抛出）之后完成。
 *
 * `CoroutineStart.UNDISPATCHED` 开始一个协程时装上它上下文中的全部 `ThreadContextElement`。回源观测（`LoadObserver`）中，
 * 调用方的元素已由外层协程装上，观测器又在其上设置了自己的线程局部状态（Micrometer 打开新建的 `cocache.load` 的 scope）；
 * 用同一个上下文再开始一个协程，会再装一次调用方的元素、把观测器的设置盖回去（当前观测变回调用方传播的父观测）。
 * 这里直接开始 block 的续接：第一次挂起之前的部分在当前线程的状态下执行，看到调用方的元素加上观测器的设置；
 * 之后的恢复经 [context] 的调度器派发，照常装上 [context] 中的元素（MDC、追踪上下文）。
 *
 * - block 的 `Job` 是 [context] 中 `Job` 的子 Job：后者被取消（运行时关闭）时，block 在之后检查取消的挂起点以取消结束。
 * - future 只在 block 自己结束之后完成，不随取消提前完成：不可取消地执行的步骤（如取得令牌的读取）的结果不会丢。
 * - block 在第一次挂起之前抛出的异常与之后抛出的一样以失败完成 future，不在调用方的栈上抛出。
 */
@Suppress("TooGenericExceptionCaught")
internal fun <R> startInPlace(context: CoroutineContext, block: suspend () -> R): CompletableFuture<R> {
    val job = Job(context[Job])
    val result = CompletableFuture<R>()
    val completion = Continuation<R>(context + job) { outcome ->
        outcome.fold(
            onSuccess = {
                job.complete()
                result.complete(it)
            },
            onFailure = {
                job.cancel()
                result.completeExceptionally(it)
            }
        )
    }
    val value = try {
        block.startCoroutineUninterceptedOrReturn(completion)
    } catch (e: Throwable) {
        completion.resumeWithException(e)
        return result
    }
    if (value !== COROUTINE_SUSPENDED) {
        @Suppress("UNCHECKED_CAST")
        completion.resume(value as R)
    }
    return result
}
