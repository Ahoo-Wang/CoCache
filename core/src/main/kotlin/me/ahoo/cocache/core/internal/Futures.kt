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

import kotlinx.coroutines.future.await
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * future 工具（协程核心，RFC 0005 V5）：交给用户的异常总是解包后的原始异常（[unwrap]、[awaitOriginal]）；返回给用户的 future
 * 一律新建并显式完成（[relay]），不直接返回依赖阶段（依赖阶段会包装为 `CompletionException`）。
 *
 * 6.x 的续接标记与就地执行（在执行器派发的任务中就地继续 future 链）随异步分支删除（RFC 0005 §5）：协程核心的续接一律经运行时的调度器派发。
 */
internal object Futures {
    /**
     * 剥掉 JDK 加上的 [CompletionException] / [ExecutionException]。
     */
    fun unwrap(error: Throwable): Throwable {
        var current = error
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    /**
     * 调用 [block]，把同步抛出转为失败的 future。
     */
    @Suppress("TooGenericExceptionCaught")
    inline fun <R> attempt(block: () -> CompletableFuture<R>): CompletableFuture<R> {
        return try {
            block()
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
    }

    /**
     * 新建一个 future，以 [source] 的结果或解包后的原始异常完成。取消它不影响 [source]。
     */
    fun <T> relay(source: CompletableFuture<out T>): CompletableFuture<T> {
        val target = CompletableFuture<T>()
        source.whenComplete { value, error ->
            if (error != null) target.completeExceptionally(unwrap(error)) else target.complete(value)
        }
        return target
    }
}

/**
 * 挂起等待，失败时抛出解包后的原始异常（协程核心等待 future 一律用它）。
 *
 * kotlinx 的 `await()` 在调试模式（`-ea` 或 `kotlinx.coroutines.debug`）下恢复挂起时复制异常（stack trace recovery），
 * 调用方拿到副本、原始异常成了它的 cause，违反“不包装异常”。这里等待一个总是正常完成的派生 future，在自己的栈帧里抛出原始异常。
 * 取消等待只取消派生的 future，不影响本 future 与它的其它等待者。
 */
internal suspend fun <T> CompletableFuture<T>.awaitOriginal(): T {
    val outcome = handle { value, error ->
        if (error == null) Result.success(value) else Result.failure(Futures.unwrap(error))
    }.await()
    return outcome.getOrThrow()
}

/**
 * 阻塞等待，失败时抛出解包后的原始异常；被取消时抛出 `CancellationException`（运行时的执行器上等待协程核心的结果，如回源观测）。
 */
internal fun <T> CompletableFuture<T>.joinOriginal(): T = try {
    join()
} catch (e: CompletionException) {
    throw Futures.unwrap(e)
}

/**
 * 不可取消地等待，失败时抛出解包后的原始异常（RFC 0005 §4.4、I-H：L1 命令与令牌步骤不可取消）。协程核心等待 L1 命令一律用它。
 *
 * 协程被取消（运行时关闭）时仍等到命令完成：取得令牌的读取不会丢掉结果（否则令牌要到 `lease.ttl` 才回收），
 * 释放也不会抢在仍在途的读取之前到达 L1。等待由存储的命令超时限定；取消在之后的第一个挂起点生效，持有令牌的步骤据此释放它。
 * 与 [awaitOriginal] 一样以总是成功的结果恢复、在自己的栈帧里抛出原始异常，不受调试模式复制异常的影响。
 */
internal suspend fun <T> CompletableFuture<T>.awaitUncancellable(): T {
    val outcome = if (isDone) {
        outcomeNow()
    } else {
        suspendCoroutine { continuation ->
            whenComplete { value, error ->
                continuation.resume(if (error == null) Result.success(value) else Result.failure(Futures.unwrap(error)))
            }
        }
    }
    return outcome.getOrThrow()
}

@Suppress("TooGenericExceptionCaught")
private fun <T> CompletableFuture<T>.outcomeNow(): Result<T> = try {
    Result.success(join())
} catch (e: Throwable) {
    Result.failure(Futures.unwrap(e))
}
