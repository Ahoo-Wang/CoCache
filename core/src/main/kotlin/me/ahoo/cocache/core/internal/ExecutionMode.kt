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
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 协议核心的执行变体（RFC 0005 V5、§4.4）：协议只写一份 `suspend` 实现，由三种 API 以不同的方式运行。
 *
 * - 同步变体（[runSync]）：`runBlocking` 在调用方线程自己的事件循环上运行核心；阻塞调用（同步 loader、阻塞的 SPI）就地执行，
 *   loader 在调用方线程上，事务、`SecurityContext` 等 ThreadLocal 可用（不变量 6）。
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
         * 同步变体：阻塞调用在当前线程上就地执行。
         */
        val SYNC = ExecutionMode(true)

        /**
         * 异步变体：阻塞调用交给阻塞执行器，等待只挂起协程。
         */
        val ASYNC = ExecutionMode(false)
    }
}

/**
 * 当前协程是否运行在同步变体中（阻塞调用就地执行）。不在任何变体中（直接从协程调用）时按异步变体处理。
 */
internal suspend fun runsInline(): Boolean = coroutineContext[ExecutionMode]?.inline ?: false

/**
 * 同步变体：在调用方线程上运行 [block] 并等待结果。
 *
 * 线程被中断时，`runBlocking` 取消协程、清除中断标志并抛出 [InterruptedException]；这里恢复中断标志再抛出，
 * 与 6.10 的约定一致（被中断的调用方保留中断标志，#704、#711）。
 * [block] 自己抛出的 [InterruptedException]（loader 或阻塞的 L1 命令被中断、跟随者收到 leader 的这类失败）原样抛出，
 * 不碰中断标志：跟随者并没有被中断。
 */
@Suppress("ThrowsCount")
internal fun <T> runSync(block: suspend CoroutineScope.() -> T): T {
    try {
        return runBlocking(ExecutionMode.SYNC) {
            try {
                block()
            } catch (e: InterruptedException) {
                throw Rethrown(e)
            }
        }
    } catch (e: Rethrown) {
        throw e.cause
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    }
}

/**
 * 把 [block] 抛出的 [InterruptedException] 带出 `runBlocking`，与它自己检测到的中断区分开。
 */
private class Rethrown(override val cause: InterruptedException) : RuntimeException(cause)
