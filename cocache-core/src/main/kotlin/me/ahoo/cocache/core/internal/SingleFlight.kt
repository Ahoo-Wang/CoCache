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

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 key 合并并发调用：同一 key 同一时刻只有一个 leader 执行，其余调用者等待并共享其结果或原始异常。
 *
 * - leader 先注销调用、再发布结果：被唤醒的等待者若立即重试，必然开始新的执行（读己之写依赖这一顺序）。
 * - 同线程对同一 key 的重入快速失败（否则自我等待死锁）。
 * - 批量调用先完成自己负责的 key，再等待其它调用者负责的 key，避免两个批量调用互相等待。
 */
internal class SingleFlight<K : Any, R> {
    private class Call<R>(val owner: Thread) {
        val future = CompletableFuture<R>()
    }

    private val calls = ConcurrentHashMap<K, Call<R>>()

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
            block()
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
     * 批量执行：[block] 收到本调用负责的 key，必须为每个 key 返回结果。
     */
    @Suppress("TooGenericExceptionCaught")
    fun executeAll(keys: Collection<K>, block: (List<K>) -> Map<K, R>): Map<K, R> {
        val owner = Thread.currentThread()
        val led = LinkedHashMap<K, Call<R>>()
        val joined = LinkedHashMap<K, Call<R>>()
        try {
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
        } catch (error: Throwable) {
            abandon(led, error)
            throw error
        }
        val results = LinkedHashMap<K, R>()
        if (led.isNotEmpty()) {
            val loaded = try {
                block(led.keys.toList())
            } catch (error: Throwable) {
                abandon(led, error)
                throw error
            }
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
        for ((key, call) in joined) {
            results[key] = await(call)
        }
        return results
    }

    private fun abandon(led: Map<K, Call<R>>, error: Throwable) {
        for ((key, call) in led) {
            calls.remove(key, call)
            call.future.completeExceptionally(error)
        }
    }

    private fun checkNotReentrant(key: K, existing: Call<R>) {
        check(existing.owner !== Thread.currentThread()) {
            "Recursive call on the same key[$key] is not supported."
        }
    }

    private fun await(call: Call<R>): R {
        try {
            return call.future.join()
        } catch (error: CompletionException) {
            throw error.cause ?: error
        }
    }
}
