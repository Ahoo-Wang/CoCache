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

package me.ahoo.cache.concurrent

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 key 合并并发调用：同一 key 同一时刻只有一个调用者（leader）执行，其余调用者等待并共享其结果或异常。
 *
 * - 精确到 key，不存在分段锁的哈希碰撞阻塞。
 * - leader 执行期间同线程对同一 key 的重入会快速失败（否则将自我等待死锁）。
 * - 等待者被唤醒时该调用已注销：随后对同一 key 的调用总是开始新的执行。
 */
class SingleFlight<K : Any, R> {
    private class Call<R>(val owner: Thread) {
        val future = CompletableFuture<R>()
    }

    private val calls = ConcurrentHashMap<K, Call<R>>()

    /**
     * 当前正在执行的 key 数量。
     */
    val inFlight: Int
        get() = calls.size

    @Suppress("TooGenericExceptionCaught")
    fun execute(key: K, block: () -> R): R {
        val call = Call<R>(Thread.currentThread())
        val existing = calls.putIfAbsent(key, call)
        if (existing != null) {
            check(existing.owner !== call.owner) {
                "Recursive call on the same key[$key] is not supported."
            }
            return await(existing)
        }
        // 先注销再发布结果：被唤醒的等待者若立即重试，必须开始新的执行，而不是再次加入这个已结束的调用
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

    private fun await(call: Call<R>): R {
        try {
            return call.future.join()
        } catch (error: CompletionException) {
            throw error.cause ?: error
        }
    }
}
