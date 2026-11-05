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

package me.ahoo.cocache.api.spi

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/*
 * [FutureL1Store.adapt] 与 [FutureInvalidationChannel.adapt] 共用的等待规则（RFC 0005 §4.3）。
 */

/**
 * 同 [awaitNullable]，结果类型非空：以 `null` 完成时以指明 [owner] 与 [operation] 的 [IllegalStateException] 失败。
 */
internal suspend fun <T : Any> awaitValue(owner: Any, operation: String, call: () -> CompletableFuture<T>?): T =
    awaitNullable(owner, operation, call)
        ?: throw IllegalStateException("${owner.javaClass.name}.$operation() completed with null.")

/**
 * 调用 [call] 并等待它返回的 future，结果可以是 `null`（Java 实现者可能违反非空声明，因此 future 与结果都按可空处理）。
 * - 已完成的 future 直接取结果，不挂起；成功时也不登记回调。
 * - 未完成时挂起，经 `whenComplete` 恢复。标准库的 [suspendCoroutine] 不可取消：调用一直等到 future 完成，从不取消它。
 * - 失败时抛出 [failureOf] 的结果。恢复时总是以成功的 [Result] 恢复、在这里的栈帧中抛出：以异常恢复时，kotlinx 的调度器
 *   在调试模式（`-ea` 或 `kotlinx.coroutines.debug`）下派发续接会复制异常（stack trace recovery），调用方拿到副本、原始异常成了它的 cause。
 */
internal suspend fun <T> awaitNullable(owner: Any, operation: String, call: () -> CompletableFuture<T>?): T? {
    val future = start(owner, operation, call)
    if (future.isDone) {
        return completedResult(future, owner, operation)
    }
    val outcome = suspendCoroutine { continuation ->
        future.whenComplete { value, error ->
            continuation.resume(
                if (error == null) Result.success(value) else Result.failure(failureOf(error, owner, operation))
            )
        }
    }
    return outcome.getOrThrow()
}

/**
 * 调用 [call] 取得 future。同步抛出的异常原样传播，只有 [CancellationException] 换成以它为 cause 的 [IllegalStateException]
 * （理由见 [failureOf]）；返回 `null` 时以 [IllegalStateException] 失败。
 */
private fun <T> start(owner: Any, operation: String, call: () -> CompletableFuture<T>?): CompletableFuture<T> {
    val future = try {
        call()
    } catch (e: CancellationException) {
        throw IllegalStateException("${owner.javaClass.name}.$operation() threw a CancellationException.", e)
    }
    return future
        ?: throw IllegalStateException("${owner.javaClass.name}.$operation() returned null instead of a future.")
}

/**
 * 已完成的 future 的结果。失败时抛出 [failureOf] 的结果，异常取自 future 本身而不是 `join`：较新的 JDK（如 25）上
 * `join` 对被取消的 future 每次抛出新建的 [CancellationException]，不是 future 中保存的那一个。
 */
private fun <T> completedResult(future: CompletableFuture<T>, owner: Any, operation: String): T? {
    if (future.isCompletedExceptionally) {
        throw failureOf(future.handle<Throwable> { _, error -> error }.join(), owner, operation)
    }
    return future.join()
}

/**
 * future 失败时抛给调用方的异常：原始异常（[unwrap]）。被取消的 future 是 L1 故障（结果未知），不是调用方的取消：
 * 在 JVM 上 [CancellationException] 就是协程的取消，原样抛出会被引擎当作调用方被取消（跟随者自行运行、不降级），
 * 因此换成以原取消异常为 cause 的 [IllegalStateException]。
 */
private fun failureOf(error: Throwable, owner: Any, operation: String): Throwable {
    val original = error.unwrap()
    if (original is CancellationException) {
        return IllegalStateException("${owner.javaClass.name}.$operation() future was cancelled.", original)
    }
    return original
}

/**
 * 原始异常：剥掉 [CompletionException] 与 [ExecutionException] 的包装（依赖阶段的失败、`join` 与 `get` 都会加上它们）；
 * 没有原因的包装原样返回。
 */
private fun Throwable.unwrap(): Throwable {
    var error = this
    while (error is CompletionException || error is ExecutionException) {
        error = error.cause ?: break
    }
    return error
}
