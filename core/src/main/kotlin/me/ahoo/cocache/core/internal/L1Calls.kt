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

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * 不可取消地执行一次 L1 调用（RFC 0005 §4.3：核心从不取消 `L1Store` 与 `InvalidationChannel` 的调用）。协程核心调用 L1 一律经它，
 * 写入方顺序中的调用除外（`AbstractEngine.write` 整段已在 `NonCancellable` 中）。
 *
 * - 调用方的协程被取消（同步变体中线程被中断，异步变体中运行时关闭）时，调用照常执行到结束：取得令牌的读取不丢结果
 *   （否则令牌要到 `lease.ttl` 才回收），释放也不会抢在仍在途的读取之前到达 L1。取消在之后的第一个挂起点生效，持有令牌的步骤据此释放它。
 *   存储用可取消的 `await()` 等待底层命令也是安全的。调用何时结束由存储决定（命令超时）。
 * - 调度器不变：续接回到当前变体的调度器上（同步变体即调用方线程，异步变体即运行时作用域的调度器），从不在 L1 客户端的 I/O 线程上执行。
 *   执行器拒绝派发调用的续接时也不取消调用（[L1Call]，见 `EngineDispatcher`）。
 * - 失败时在这里的栈帧中抛出原始异常：结果经 [Result] 带出 `withContext`，kotlinx 在调试模式下恢复时不会复制它。
 */
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> uncancellable(crossinline call: suspend () -> T): T {
    val outcome = withContext(L1_CALL) {
        try {
            Result.success(call())
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
    return outcome.getOrThrow()
}

/**
 * 协程上下文中的标记：当前协程正在执行一次 L1 调用（[uncancellable]）。`EngineDispatcher` 在执行器拒绝派发时取消被派发的协程
 * （与 kotlinx 的执行器调度器相同），带有它的不取消：`withContext` 的作用域被取消会丢掉调用的结果（可能带着令牌），
 * 存储中可取消的等待也会被取消。续接照常在 `Dispatchers.IO` 上执行，调用方的协程在之后的派发被拒绝时才被取消。
 */
internal object L1Call : CoroutineContext.Element, CoroutineContext.Key<L1Call> {
    override val key: CoroutineContext.Key<*>
        get() = this

    override fun toString(): String = "L1Call"
}

/**
 * [uncancellable] 的上下文（预先组合，调用时不再分配）。
 */
internal val L1_CALL: CoroutineContext = NonCancellable + L1Call
