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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 协议核心的执行变体（RFC 0005 V5、§4.4）：协议只写一份 `suspend` 实现，由三种 API 以不同的方式运行。
 *
 * - 同步变体（[runSync]）：`runBlocking` 在调用方线程自己的事件循环上运行核心；阻塞的 loader 就地执行，在调用方线程上，
 *   事务、`SecurityContext` 等 ThreadLocal 可用（不变量 6）。L1 命令经异步 SPI 发出、挂起等待（RFC 0005 §4.3），
 *   调用方线程停在事件循环上，续接仍在它上面执行，从不在 L1 客户端的 I/O 线程上执行。
 * - 异步变体（`AsyncContext.runAsync`）：在运行时作用域中运行核心，执行到第一次挂起之前在调用方线程上；等待 L1 不占线程，
 *   阻塞调用交给阻塞执行器。用户拿到的是结果的副本，取消它不取消协程。
 *
 * 核心经协程上下文中的 [ExecutionMode] 判断当前是哪一种（[runsInline]）。两种变体的协程都不会被单独取消
 * （同步变体只在线程被中断时、异步变体只在运行时关闭时），SingleFlight 的 leader 因此可以直接在调用方的协程中执行；
 * 上下文中没有 [ExecutionMode] 的调用方（直接从用户的协程调用）可能被单独取消，共享加载改在运行时作用域中执行。
 */
internal class ExecutionMode private constructor(val inline: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExecutionMode> {
        /**
         * 同步变体：阻塞的 loader 在当前线程上就地执行。
         */
        val SYNC = ExecutionMode(true)

        /**
         * 异步变体：阻塞调用交给阻塞执行器，等待只挂起协程。
         */
        val ASYNC = ExecutionMode(false)
    }
}

/**
 * 当前协程是否运行在同步变体中（阻塞的 loader 就地执行）。不在任何变体中（直接从协程调用）时按异步变体处理。
 */
internal suspend fun runsInline(): Boolean = coroutineContext[ExecutionMode]?.inline ?: false

/**
 * 同步变体调用用户代码（数据源、回源观测）之前的检查（RFC 0005 §4.3）：调用方线程已被中断时不再调用，持有令牌的步骤据此释放令牌。
 * - `runBlocking` 已处理中断、取消了协程：在这里以取消结束；
 * - 中断落在两个挂起点之间，`runBlocking` 还没有处理它（中断标志仍置位）：抛出 [InterruptedException]，保留中断标志。
 *
 * 异步变体不检查：它的协程只在运行时关闭时被取消，关闭之后的规则不变（§5.6）。
 */
internal suspend fun ensureNotInterrupted() {
    if (!runsInline()) {
        return
    }
    coroutineContext.ensureActive()
    if (Thread.currentThread().isInterrupted) {
        throw InterruptedException("Interrupted before calling the source.")
    }
}

/**
 * 同步变体：在调用方线程上运行 [block] 并等待结果。
 *
 * 线程被中断时（RFC 0005 §4.3），`runBlocking`（kotlinx ≥ 1.10）清除中断标志、取消协程，并继续在调用方线程上运行它直到结束：
 * 在途的 L1 命令（不可取消地等待）先完成或超时，取消在之后的第一个挂起点生效，持有令牌的步骤按正常路径释放令牌，之后才返回。
 * 两种结局都恢复中断标志（#704、#711）：
 * - 取消生效（协程以取消结束）：抛出 [InterruptedException]；
 * - 取消之后协程没有再经过检查取消的挂起点、自己结束了：照常交出它的结果（值，或它自己抛出的异常）。被取消的协程总是以取消结束，
 *   `runBlocking` 对此仍抛出 [InterruptedException]，结果因此另经 [SyncOutcome] 带出。
 *
 * 协程没有被取消、[block] 自己抛出的 [InterruptedException]（loader 被中断、跟随者收到 leader 的这类失败）原样抛出，
 * 不碰中断标志：调用方线程并没有被中断。
 */
@Suppress("ThrowsCount", "TooGenericExceptionCaught")
internal fun <T> runSync(block: suspend CoroutineScope.() -> T): T {
    val outcome = SyncOutcome<T>()
    try {
        return runBlocking(ExecutionMode.SYNC) {
            try {
                block().also { outcome.succeed(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                outcome.fail(e)
                throw e
            }
        }
    } catch (e: InterruptedException) {
        if (e === outcome.failure) {
            throw e
        }
        Thread.currentThread().interrupt()
        return outcome.orThrow(e)
    }
}

/**
 * [runSync] 中 [block] 自己的结局（取消生效时没有）。只在调用方线程上读写：协程在 `runBlocking` 的事件循环上执行。
 */
private class SyncOutcome<T> {
    private var finished = false
    private var value: T? = null

    var failure: Throwable? = null
        private set

    fun succeed(value: T) {
        this.value = value
        finished = true
    }

    fun fail(error: Throwable) {
        failure = error
        finished = true
    }

    /**
     * 协程被中断取消之后：它自己结束了就交出它的结局，否则抛出 [interrupted]。
     */
    fun orThrow(interrupted: InterruptedException): T {
        failure?.let { throw it }
        if (!finished) {
            throw interrupted
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
