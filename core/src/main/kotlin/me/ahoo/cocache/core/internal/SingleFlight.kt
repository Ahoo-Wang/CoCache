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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.coroutineContext

/**
 * 按 key 合并并发调用：同一 key 同一时刻只有一个 leader 执行，其余调用者等待并共享其结果或原始异常。
 * 同步调用（[execute]）与异步调用（[executeAsync]）共用一张表，互相合并（RFC 0004 §5.4）。
 *
 * - leader 先注销调用、再发布结果：被唤醒的等待者若立即重试，必然开始新的执行（读己之写依赖这一顺序）。
 *   两种 leader 都在注册之后才执行 block（block 的第一件事是取戳）。
 * - 同步调用合并到异步调用时只阻塞自己的线程；异步调用合并到同步调用时经 [Executor] 拿到结果，
 *   用户的续接不会跑在同步 leader 的线程上。每个异步调用方拿到自己的 future，取消只影响自己。
 * - 同线程对同一 key 的重入快速失败（否则自我等待死锁）：同步调用的拥有线程，或正在 [loading] 内为该 key 调用 loader 的线程。
 * - 批量调用先完成自己负责的 key，再等待其它调用者负责的 key，避免两个批量调用互相等待。
 * - 协程核心（RFC 0005 §4.4）经 [executeSuspending] 使用同一张表：leader 在调用方的协程中执行（同步变体即调用方线程），
 *   可能被单独取消的调用方改由运行时作用域执行；跟随者挂起等待，不占线程。
 */
internal class SingleFlight<K : Any, R> {
    /**
     * @param owner 同步 leader 的线程；异步调用为 `null`（没有拥有线程）
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

    @Suppress("TooGenericExceptionCaught")
    fun execute(key: K, block: () -> R): R {
        val call = Call<R>(Thread.currentThread())
        val existing = calls.putIfAbsent(key, call)
        if (existing != null) {
            checkNotReentrant(key, existing)
            return await(existing)
        }
        val result = try {
            // 本线程是 key 的同步 owner：其间完成的别的 future 的续接派发出去，不在 owner 的作用域内就地执行（Futures.withoutInline）
            Futures.withoutInline(block)
        } catch (error: Throwable) {
            calls.remove(key, call)
            call.future.completeExceptionally(error)
            throw error
        }
        calls.remove(key, call)
        call.future.complete(result)
        return result
    }

    /**
     * 异步执行：[block] 在注册之后、在调用方线程上被调用，返回的 future 完成时先注销、再发布。
     * 返回的 future 以原始异常失败（含重入），从不同步抛出。
     *
     * @param executor 合并到同步调用时用来送达结果的执行器
     */
    fun executeAsync(key: K, executor: Executor, block: () -> CompletableFuture<R>): CompletableFuture<R> {
        val call = Call<R>(null)
        val existing = calls.putIfAbsent(key, call)
        if (existing != null) {
            return Futures.attempt {
                checkNotReentrant(key, existing)
                joinAsync(existing, executor)
            }
        }
        Futures.attempt(block).whenComplete { result, error ->
            calls.remove(key, call)
            if (error != null) {
                call.future.completeExceptionally(
                    Futures.unwrap(error)
                )
            } else {
                call.future.complete(result)
            }
        }
        return Futures.relay(call.future)
    }

    /**
     * 批量执行：[block] 收到本调用负责的 key，必须为每个 key 返回结果。
     */
    @Suppress("TooGenericExceptionCaught")
    fun executeAll(keys: Collection<K>, block: (List<K>) -> Map<K, R>): Map<K, R> {
        val owner = Thread.currentThread()
        val led = LinkedHashMap<K, Call<R>>()
        val joined = LinkedHashMap<K, Call<R>>()
        try {
            register(keys, owner, led, joined)
        } catch (error: Throwable) {
            abandon(led, error)
            throw error
        }
        val results = LinkedHashMap<K, R>()
        if (led.isNotEmpty()) {
            val loaded = try {
                Futures.withoutInline { block(led.keys.toList()) }
            } catch (error: Throwable) {
                abandon(led, error)
                throw error
            }
            publish(led, loaded, results)
        }
        for ((key, call) in joined) {
            results[key] = await(call)
        }
        return results
    }

    /**
     * 异步批量执行：先为全部 key 注册，再启动本调用负责的整批，最后合并其它调用者的结果。
     */
    fun executeAllAsync(
        keys: Collection<K>,
        executor: Executor,
        block: (List<K>) -> CompletableFuture<Map<K, R>>
    ): CompletableFuture<Map<K, R>> {
        val led = LinkedHashMap<K, Call<R>>()
        val joined = LinkedHashMap<K, Call<R>>()
        try {
            register(keys, null, led, joined)
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            abandon(led, error)
            return CompletableFuture.failedFuture(error)
        }
        val own: CompletableFuture<Map<K, R>> = if (led.isEmpty()) {
            CompletableFuture.completedFuture(emptyMap())
        } else {
            val ownResult = CompletableFuture<Map<K, R>>()
            Futures.attempt { block(led.keys.toList()) }.whenComplete { loaded, error ->
                if (error != null) {
                    val cause = Futures.unwrap(error)
                    abandon(led, cause)
                    ownResult.completeExceptionally(cause)
                } else {
                    val results = LinkedHashMap<K, R>()
                    publish(led, loaded, results)
                    ownResult.complete(results)
                }
            }
            ownResult
        }
        val others = joined.mapValues { (_, call) -> joinAsync(call, executor) }
        val all = ArrayList<CompletableFuture<*>>(others.size + 1)
        all.add(own)
        all.addAll(others.values)
        val result = CompletableFuture<Map<K, R>>()
        CompletableFuture.allOf(*all.toTypedArray()).whenComplete { _, error ->
            if (error != null) {
                result.completeExceptionally(Futures.unwrap(error))
                return@whenComplete
            }
            val merged = LinkedHashMap<K, R>(own.join())
            others.forEach { (key, future) -> merged[key] = future.join() }
            result.complete(merged)
        }
        return result
    }

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
     * 协程核心的执行（RFC 0005 §4.4）：与 [execute] / [executeAsync] 共用一张表，[block] 在注册之后才执行（第一件事是取戳）。
     *
     * - 同步变体（[runSync]）：leader 在调用方线程上内联执行 [block]，loader 拿得到调用方线程的 ThreadLocal（不变量 6）。
     *   leader 被中断时这次加载随之结束，跟随者收到它的失败。
     * - 异步变体（`AsyncContext.runAsync`）：leader 同样在调用方的协程中执行，省去一次派发；用户取消的只是结果的副本，
     *   协程只在运行时关闭时被取消。
     * - 上下文中没有 [ExecutionMode] 的调用方可能被单独取消：leader 在 [scope] 中执行 [block]，调用方只等待结果，
     *   取消调用方不取消共享的加载。
     * - 跟随者挂起等待结果，不占线程；等待被取消（同步变体中即线程被中断）只结束它自己的等待，不影响 leader 与其它跟随者。
     * - 同线程对同一 key 的重入快速失败，规则与 [execute] / [executeAsync] 相同。
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun executeSuspending(key: K, scope: CoroutineScope, block: suspend () -> R): R {
        val mode = coroutineContext[ExecutionMode]
        val inline = mode?.inline == true
        val call = Call<R>(if (inline) Thread.currentThread() else null)
        val existing = calls.putIfAbsent(key, call)
        if (existing != null) {
            checkNotReentrant(key, existing)
            return awaitSuspending(existing)
        }
        if (mode == null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // 结果经 call.future 交给全部等待者（含发起的调用方）；这里不重新抛出，否则失败会作为未捕获的异常上报给作用域
                val outcome = runCatching { block() }
                calls.remove(key, call)
                outcome.fold({ call.future.complete(it) }, { call.future.completeExceptionally(it) })
            }
            return awaitSuspending(call)
        }
        val result = try {
            // 与 [execute] 相同：其间完成的别的 future 的续接派发出去，不在本 key 的重入守卫作用域内就地执行。
            // 续接标记是线程局部的，只有始终留在调用方线程上的同步变体可以这样做
            if (inline) Futures.withoutInlineSuspending(block) else block()
        } catch (error: Throwable) {
            calls.remove(key, call)
            call.future.completeExceptionally(error)
            throw error
        }
        calls.remove(key, call)
        call.future.complete(result)
        return result
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

    /**
     * 合并到异步调用：它本来就在引擎的线程上完成，直接转发；合并到同步调用：经 [executor] 送达。
     */
    private fun joinAsync(call: Call<R>, executor: Executor): CompletableFuture<R> {
        return if (call.owner == null) Futures.relay(call.future) else Futures.relayOn(executor, call.future)
    }

    /**
     * 同步跟随者等待 leader 的结果，响应中断（RFC 0005 附录 A.S1）：恢复中断标志并抛出 [InterruptedException]，只影响跟随者自己，
     * leader 照常执行、发布，其它跟随者照常拿到结果。已完成的结果照常返回，不论中断标志。
     */
    private fun await(call: Call<R>): R {
        try {
            return call.future.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
    }
}
