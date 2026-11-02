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
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * 按 key 合并并发调用（协程核心，RFC 0005 §4.4）：同一 key 同一时刻只有一个 leader 执行，其余调用者挂起等待并共享其结果或原始异常。
 *
 * - leader 先注销调用、再发布结果：被唤醒的等待者若立即重试，必然开始新的执行（读己之写依赖这一顺序）。
 *   leader 在注册之后才执行 block（block 的第一件事是取戳）。
 * - leader 在调用方的协程中执行：同步变体即调用方线程，异步变体省去一次派发；上下文中没有执行变体的调用方改由运行时作用域执行。
 *   跟随者挂起等待，不占线程；取消只结束它自己的等待。
 * - 同线程对同一 key 的重入快速失败（否则自我等待死锁）：同步 leader 的拥有线程，或正在 [loading] 内为该 key 调用数据源的线程。
 * - 批量调用先完成自己负责的 key，再等待其它调用者负责的 key，避免两个批量调用互相等待。
 */
internal class SingleFlight<K : Any, R> {
    /**
     * @param owner 同步 leader 的线程；其它调用为 `null`（没有拥有线程）
     */
    private class Call<R>(val owner: Thread?) {
        val future = CompletableFuture<R>()
    }

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
            // 数据源可能同步完成别的 key 的 future：那些续接派发出去，不在这些 key 的重入检查作用域内执行（Futures.withoutInline）
            return Futures.withoutInline(block)
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
            val call = Call<R>(owner)
            val existing = calls.putIfAbsent(key, call)
            if (existing == null) {
                led[key] = call
            } else {
                checkNotReentrant(key, existing)
                joined[key] = existing
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
     *   取消调用方不取消共享的加载。
     * - 跟随者挂起等待结果，不占线程；等待被取消（同步变体中即线程被中断）只结束它自己的等待，不影响 leader 与其它跟随者。
     * - 合并规则：leader 因中断或取消失败时（[sharedFailure]），leader 自己收到原始异常，跟随者不接受这个失败，自行运行：
     *   重新注册一次（leader 已注销，通常由它自己领头）；仍合并到被中断的 leader 时绕过 SingleFlight 直接执行 [block]。
     * - 同线程对同一 key 的重入快速失败。
     */
    suspend fun executeSuspending(key: K, scope: CoroutineScope, block: suspend () -> R): R {
        val mode = coroutineContext[ExecutionMode]
        val owner = if (mode?.inline == true) Thread.currentThread() else null
        var retried = false
        while (true) {
            val call = Call<R>(owner)
            val existing = calls.putIfAbsent(key, call) ?: return lead(key, call, mode, scope, block)
            checkNotReentrant(key, existing)
            try {
                return awaitSuspending(existing)
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
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // 结果经 call.future 交给全部等待者（含发起的调用方）；这里不重新抛出，否则失败会作为未捕获的异常上报给作用域
                val outcome = runCatching { block() }
                calls.remove(key, call)
                outcome.fold(
                    { call.future.complete(it) },
                    { call.future.completeExceptionally(sharedFailure(it, false)) }
                )
            }
            // 发起的调用方是这次加载的拥有者：被中断或取消时交出原始异常，而不是跟随者看到的那个
            return try {
                awaitSuspending(call)
            } catch (e: LeaderInterruptedException) {
                throw e.cause ?: e
            }
        }
        val inline = mode.inline
        val result = try {
            // 其间完成的别的 future 的续接派发出去，不在本 key 的重入守卫作用域内就地执行。
            // 续接标记是线程局部的，只有始终留在调用方线程上的同步变体可以这样做
            if (inline) Futures.withoutInlineSuspending(block) else block()
        } catch (error: Throwable) {
            calls.remove(key, call)
            call.future.completeExceptionally(sharedFailure(error, inline))
            throw error
        }
        calls.remove(key, call)
        call.future.complete(result)
        return result
    }

    /**
     * 交给跟随者的失败：leader 因中断或取消失败时换成 [LeaderInterruptedException]（跟随者据此自行运行），其余原样。
     * 同步变体的 leader 被中断时，失败不一定是 [InterruptedException]（L1 命令被中断时恢复中断标志并抛出后端的异常，
     * loader 也可能包装它），因此同时以调用方线程的中断标志为准。
     */
    private fun sharedFailure(error: Throwable, inline: Boolean): Throwable {
        val interrupted = error is InterruptedException || error is CancellationException ||
            (inline && Thread.currentThread().isInterrupted)
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
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { block(ledKeys) }.fold(
                        { loaded -> own.complete(LinkedHashMap<K, R>().also { publish(led, loaded, it) }) },
                        { error ->
                            abandon(led, sharedFailure(error, false))
                            own.completeExceptionally(error)
                        }
                    )
                }
                results.putAll(own.awaitOriginal())
            } else {
                val loaded = try {
                    if (inline) Futures.withoutInlineSuspending { block(ledKeys) } else block(ledKeys)
                } catch (error: Throwable) {
                    abandon(led, sharedFailure(error, inline))
                    throw error
                }
                publish(led, loaded, results)
            }
        }
        for ((key, call) in joined) {
            try {
                results[key] = awaitSuspending(call)
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

    private fun checkNotReentrant(key: K, existing: Call<R>) {
        val current = Thread.currentThread()
        check(existing.owner !== current && loadingKeys.get()?.contains(key) != true) {
            "Recursive call on the same key[$key] is not supported."
        }
    }
}

/**
 * flight 的 leader 因中断或取消失败（RFC 0005 §4.4 的 `SourceLoadInterruptedException`）：只在 [SingleFlight] 内部交给跟随者，
 * 跟随者据此按合并规则自行运行，从不交给调用方。[cause] 是 leader 收到的原始异常。
 */
private class LeaderInterruptedException(cause: Throwable) :
    RuntimeException("The leader of this load was interrupted or cancelled.", cause)
