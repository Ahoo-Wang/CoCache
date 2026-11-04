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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.nanoseconds

/**
 * 按 key 合并并发调用（协程核心，RFC 0005 §4.4）：同一 key 同一时刻只有一个 leader 执行，其余调用者挂起等待并共享其结果或原始异常。
 *
 * - leader 先注销调用、再发布结果：被唤醒的等待者若立即重试，必然开始新的执行（读己之写依赖这一顺序）。
 *   leader 在注册之后才执行 block（block 的第一件事是取戳）。
 * - leader 在调用方的协程中执行：同步变体即调用方线程，异步变体省去一次派发；上下文中没有执行变体的调用方改由运行时作用域执行。
 *   跟随者挂起等待，不占线程；取消只结束它自己的等待。
 * - 同线程对同一 key 的重入快速失败（否则自我等待死锁）：同步 leader 的拥有线程，或正在 [loading] 内为该 key 调用数据源的线程。
 * - 批量调用先完成自己负责的 key，再等待其它调用者负责的 key，避免两个批量调用互相等待。
 * - 有上界（`load.timeout`，RFC 0005 §4.4）：每次加载的截止时刻是它登记时加上 [timeoutNanos]。跟随者等到截止时刻仍没有结果时，
 *   以 [onTimeout] 的异常失败；截止时刻之后到来的调用方把它注销（不完成它），自己开始新的加载（P16）。原 leader 迟到的结果只交给它自己：
 *   注销早于发布，不破坏“先注销再发布”。
 *
 * @param timeoutNanos 一次加载的上限；[NO_TIMEOUT] 表示不限
 * @param ticker 登记时刻与截止时刻的计时源
 * @param onTimeout 跟随者等待超时时抛出的异常
 */
internal class SingleFlight<K : Any, R : Any>(
    private val timeoutNanos: Long = NO_TIMEOUT,
    private val ticker: Ticker = Ticker.SYSTEM,
    private val onTimeout: () -> Throwable = { IllegalStateException("Load timed out.") },
) {
    /**
     * @param owner 同步 leader 的线程；其它调用为 `null`（没有拥有线程）
     */
    private class Call<R>(val owner: Thread?, val registeredAt: Long) {
        val future = CompletableFuture<R>()
    }

    /**
     * 登记已超过上限的加载（如卡住的同步 loader）：新的调用方注销它，自己开始新的加载。
     */
    private fun expired(call: Call<R>): Boolean = ticker.nanoTime() - call.registeredAt >= timeoutNanos

    private val calls = ConcurrentHashMap<K, Call<R>>()

    /**
     * 本线程正在调用 loader 的 key（重入守卫）。
     */
    private val loadingKeys = ThreadLocal<MutableSet<K>?>()

    val inFlight: Int
        get() = calls.size

    /**
     * 在本线程上为 [keys] 调用 loader 期间执行 [block]：其间本线程对这些 key 的同步或异步调用快速失败。
     */
    fun <T> loading(keys: Collection<K>, block: () -> T): T {
        var current = loadingKeys.get()
        if (current == null) {
            current = HashSet(keys.size)
            loadingKeys.set(current)
        }
        val added = keys.filter { current.add(it) }
        try {
            return block()
        } finally {
            current.removeAll(added.toSet())
            if (current.isEmpty()) {
                loadingKeys.remove()
            }
        }
    }

    /**
     * 不经合并的加载（私有回源，RFC 0005 §4.9 机制 2）：不注册调用，其间本线程对这些 key 的调用快速失败（同 [loading]）。
     * 本线程已在为其中的 key 加载（领头的同步调用，或外层的私有加载）时立即失败，而不是无限递归。
     * 只供同步变体使用：[block] 的每一步都在当前线程上执行（`runBlocking` 的事件循环），线程局部的标记因此成立。
     */
    suspend fun <T> loadingPrivately(keys: Collection<K>, block: suspend () -> T): T {
        val current = loadingKeys.get() ?: HashSet<K>(keys.size).also { loadingKeys.set(it) }
        val added = ArrayList<K>(keys.size)
        try {
            for (key in keys) {
                calls[key]?.let { checkNotReentrant(key, it) }
                check(current.add(key)) { "Recursive call on the same key[$key] is not supported." }
                added += key
            }
            return block()
        } finally {
            current.removeAll(added.toSet())
            if (current.isEmpty()) {
                loadingKeys.remove()
            }
        }
    }

    private fun register(keys: Collection<K>, owner: Thread?, led: MutableMap<K, Call<R>>, joined: MutableMap<K, Call<R>>) {
        for (key in keys) {
            if (led.containsKey(key) || joined.containsKey(key)) {
                continue
            }
            while (true) {
                val call = Call<R>(owner, ticker.nanoTime())
                val existing = calls.putIfAbsent(key, call)
                if (existing == null) {
                    led[key] = call
                    break
                }
                if (expired(existing)) {
                    calls.remove(key, existing)
                    continue
                }
                checkNotReentrant(key, existing)
                joined[key] = existing
                break
            }
        }
    }

    private fun publish(led: Map<K, Call<R>>, loaded: Map<K, R>, results: MutableMap<K, R>) {
        for ((key, call) in led) {
            calls.remove(key, call)
            if (loaded.containsKey(key)) {
                @Suppress("UNCHECKED_CAST")
                val result = loaded[key] as R
                call.future.complete(result)
                results[key] = result
            } else {
                call.future.completeExceptionally(IllegalStateException("No result for key[$key]."))
            }
        }
    }

    private fun abandon(led: Map<K, Call<R>>, error: Throwable) {
        for ((key, call) in led) {
            calls.remove(key, call)
            call.future.completeExceptionally(error)
        }
    }

    /**
     * 协程核心的执行（RFC 0005 §4.4）：[block] 在注册之后才执行（第一件事是取戳）。
     *
     * - 同步变体（[runSync]）：leader 在调用方线程上内联执行 [block]，loader 拿得到调用方线程的 ThreadLocal（不变量 6）。
     * - 异步变体（`AsyncContext.runAsync`）：leader 同样在调用方的协程中执行，省去一次派发；用户取消的只是结果的副本，
     *   协程只在运行时关闭时被取消。
     * - 上下文中没有 [ExecutionMode] 的调用方可能被单独取消：leader 在 [scope] 中执行 [block]，调用方只等待结果，
     *   取消调用方不取消共享的加载。协程上下文的元素取自调用方（[callerElements]，RFC 0005 §4.4 K6）：loader 看得到调用方的
     *   `ThreadContextElement`（MDC、追踪）。
     * - 跟随者挂起等待结果，不占线程；等待被取消（同步变体中即线程被中断）只结束它自己的等待，不影响 leader 与其它跟随者。
     * - 合并规则：leader 被中断或取消时（[sharedFailure]，按 leader 自己的状态判断，不按异常类型），leader 自己收到原始异常，
     *   跟随者不接受这个失败，自行运行：重新注册一次（leader 已注销，通常由它自己领头）；仍合并到被中断的 leader 时绕过 SingleFlight
     *   直接执行 [block]。其余失败（含 [block] 自己抛出的 `CancellationException`）与结果一样交给全部等待者。
     * - 同线程对同一 key 的重入快速失败。
     */
    suspend fun executeSuspending(key: K, scope: CoroutineScope, block: suspend () -> R): R {
        val mode = coroutineContext[ExecutionMode]
        val owner = if (mode?.inline == true) Thread.currentThread() else null
        var retried = false
        while (true) {
            val call = Call<R>(owner, ticker.nanoTime())
            val existing = calls.putIfAbsent(key, call) ?: return lead(key, call, mode, scope, block)
            if (expired(existing)) {
                // 登记超过上限的加载：注销（不完成它），本调用重新注册、开始新的加载
                calls.remove(key, existing)
                continue
            }
            checkNotReentrant(key, existing)
            try {
                return awaitFollower(existing)
            } catch (_: LeaderInterruptedException) {
                if (retried) {
                    return block()
                }
                retried = true
            }
        }
    }

    /**
     * 本调用注册成功，作为 leader 执行 [block]：先注销、再发布。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun lead(
        key: K,
        call: Call<R>,
        mode: ExecutionMode?,
        scope: CoroutineScope,
        block: suspend () -> R
    ): R {
        if (mode == null) {
            scope.launch(callerElements(), CoroutineStart.UNDISPATCHED) {
                // 结果经 call.future 交给全部等待者（含发起的调用方）；这里不重新抛出，否则失败会作为未捕获的异常上报给作用域
                val outcome = runCatching { block() }
                calls.remove(key, call)
                outcome.fold(
                    { call.future.complete(it) },
                    { call.future.completeExceptionally(sharedFailure(it, isActive, false)) }
                )
            }
            // 发起的调用方是这次加载的拥有者：被中断或取消时交出原始异常，而不是跟随者看到的那个
            return try {
                awaitFollower(call)
            } catch (e: LeaderInterruptedException) {
                throw e.cause ?: e
            }
        }
        val inline = mode.inline
        val result = try {
            block()
        } catch (error: Throwable) {
            calls.remove(key, call)
            call.future.completeExceptionally(sharedFailure(error, coroutineContext.isActive, inline))
            throw error
        }
        calls.remove(key, call)
        call.future.complete(result)
        return result
    }

    /**
     * 交给跟随者的失败：leader 被中断或取消时换成 [LeaderInterruptedException]（跟随者据此自行运行），其余原样。
     *
     * 按 leader 自己的状态判断，不按异常类型（RFC 0005 第三轮 K5）：只有 leader 的协程已被取消（[active] 为 `false`：
     * 运行时关闭、执行器拒绝派发、同步变体的 `runBlocking` 处理了中断），或同步变体（[inline]）的调用方线程被中断时，才是被中断。
     * 同步变体的 loader 在调用方线程上执行，它抛出的 [InterruptedException] 即调用方线程被中断；loader 也可能包装它并恢复中断标志，
     * 因此同时以中断标志为准。其余失败是普通的回源失败，与其它失败一样共享：否则 loader 自己的 `CancellationException`
     * （其中的 `withTimeout`、被取消的 future）或阻塞执行器线程上的中断会让每个跟随者各自回源，回源次数随并发放大。
     */
    private fun sharedFailure(error: Throwable, active: Boolean, inline: Boolean): Throwable {
        val interrupted = !active || (inline && (error is InterruptedException || Thread.currentThread().isInterrupted))
        return if (interrupted) LeaderInterruptedException(error) else error
    }

    /**
     * [executeSuspending] 的批量版本（协程核心的批量读取）：先为全部 key 注册，再运行本调用负责的整批，最后等待其它调用者负责的 key，
     * 避免两个批量调用互相等待。[block] 收到本调用负责的 key，必须为每个 key 返回结果，缺少的 key 的等待者以 [IllegalStateException] 失败。
     * 合并到的 leader 被中断或取消时，结果中不含那个 key（合并规则，见 [executeSuspending]），调用方改走单 key 路径。
     * leader 的位置与 [executeSuspending] 相同：同步变体在调用方线程上内联执行，异步变体在调用方的协程中执行，
     * 上下文中没有 [ExecutionMode] 的调用方改由 [scope] 执行。
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun executeAllSuspending(
        keys: Collection<K>,
        scope: CoroutineScope,
        block: suspend (List<K>) -> Map<K, R>
    ): Map<K, R> {
        val mode = coroutineContext[ExecutionMode]
        val inline = mode?.inline == true
        val led = LinkedHashMap<K, Call<R>>()
        val joined = LinkedHashMap<K, Call<R>>()
        try {
            register(keys, if (inline) Thread.currentThread() else null, led, joined)
        } catch (error: Throwable) {
            abandon(led, error)
            throw error
        }
        val results = LinkedHashMap<K, R>()
        if (led.isNotEmpty()) {
            val ledKeys = led.keys.toList()
            if (mode == null) {
                // 整批的结果经 own 交给发起的调用方（与内联执行相同：缺少的 key 不在结果中），各 key 的结果经 call.future 交给其它等待者；
                // 这里不重新抛出，否则失败会作为未捕获的异常上报给作用域
                val own = CompletableFuture<Map<K, R>>()
                scope.launch(callerElements(), CoroutineStart.UNDISPATCHED) {
                    runCatching { block(ledKeys) }.fold(
                        { loaded -> own.complete(LinkedHashMap<K, R>().also { publish(led, loaded, it) }) },
                        { error ->
                            abandon(led, sharedFailure(error, isActive, false))
                            own.completeExceptionally(error)
                        }
                    )
                }
                results.putAll(own.awaitOriginal())
            } else {
                val loaded = try {
                    block(ledKeys)
                } catch (error: Throwable) {
                    abandon(led, sharedFailure(error, coroutineContext.isActive, inline))
                    throw error
                }
                publish(led, loaded, results)
            }
        }
        for ((key, call) in joined) {
            try {
                results[key] = awaitFollower(call)
            } catch (_: LeaderInterruptedException) {
                // 合并规则：合并到的 leader 被中断或取消，结果中不含这个 key，调用方改走单 key 路径
            }
        }
        return results
    }

    /**
     * 挂起等待一次调用的结果，失败时抛出原始异常（[awaitOriginal]）。取消这次等待不会取消共享的结果，其它等待者照常拿到它。
     */
    private suspend fun awaitSuspending(call: Call<R>): R = call.future.awaitOriginal()

    /**
     * 没有 [ExecutionMode] 的调用方的共享加载在 [scope] 中执行时取自调用方的元素（RFC 0005 §4.4，K2、K6）：去掉 `Job`
     * （调用方被取消不取消共享加载）与调度器（调度器取自运行时，从不在 L1 客户端的完成线程上继续），其余（`ThreadContextElement`、
     * `CoroutineName`、重入记录）原样带入。
     */
    private suspend fun callerElements(): CoroutineContext =
        coroutineContext.minusKey(Job).minusKey(ContinuationInterceptor)

    /**
     * 跟随者等待另一个调用者执行的加载，到这次加载的截止时刻为止；超时以 [onTimeout] 的异常失败。
     * 已经完成的加载照常交出结果，即使截止时刻已过（批量调用领头自己的 key、在作用域中同步执行完的 leader 都可能越过它）。
     * 同步变体在调用方线程上限时停顿（`runBlocking` 的事件循环计时），异步变体不占线程。
     */
    private suspend fun awaitFollower(call: Call<R>): R {
        if (timeoutNanos == NO_TIMEOUT || call.future.isDone) {
            return awaitSuspending(call)
        }
        val remaining = call.registeredAt + timeoutNanos - ticker.nanoTime()
        if (remaining <= 0) {
            throw onTimeout()
        }
        return withTimeoutOrNull(remaining.nanoseconds) { awaitSuspending(call) } ?: throw onTimeout()
    }

    private fun checkNotReentrant(key: K, existing: Call<R>) {
        val current = Thread.currentThread()
        check(existing.owner !== current && loadingKeys.get()?.contains(key) != true) {
            "Recursive call on the same key[$key] is not supported."
        }
    }

    companion object {
        /**
         * 不限制加载的时长（测试与内部使用）。
         */
        const val NO_TIMEOUT: Long = Long.MAX_VALUE
    }
}

/**
 * flight 的 leader 被中断或取消（RFC 0005 §4.4 的 `SourceLoadInterruptedException`；按 leader 自己的状态判断，见 `sharedFailure`）：只在 [SingleFlight] 内部交给跟随者，
 * 跟随者据此按合并规则自行运行，从不交给调用方。[cause] 是 leader 收到的原始异常。
 */
private class LeaderInterruptedException(cause: Throwable) :
    RuntimeException("The leader of this load was interrupted or cancelled.", cause)
