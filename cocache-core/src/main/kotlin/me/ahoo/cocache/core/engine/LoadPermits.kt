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

package me.ahoo.cocache.core.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 回源并发许可（bulkhead），同步与异步共用同一组许可（RFC 0004 §5.3）。
 *
 * - 同步获取：在调用方线程上限时等待（现状）。
 * - 异步获取：拿不到时登记为等待者，不挂起线程；许可释放时按登记顺序交给等待者，超时由定时器触发。
 *   每次释放都会唤醒等待者，而不是让等待者轮询：轮询下 N 个等待者每轮只有一个能前进。
 * - 公平性只在异步等待者之间成立：同步获取（[tryAcquire]）直接争用信号量，不排在已登记的异步等待者之后；
 *   许可被释放的瞬间，正在等待的同步调用方可能先于队首的异步等待者拿到它。异步等待者仍在 `acquireTimeout` 内被唤醒或被拒绝，
 *   许可不会泄漏；只是同步与异步混用、许可长期饱和时，异步调用方被拒绝的比例可能更高。
 */
internal class LoadPermits(permits: Int, private val async: AsyncContext) {
    private val semaphore = Semaphore(permits)
    private val waiters = ConcurrentLinkedQueue<CompletableFuture<Void?>>()

    fun tryAcquire(timeoutNanos: Long): Boolean = semaphore.tryAcquire(timeoutNanos, TimeUnit.NANOSECONDS)

    /**
     * 异步获取：在 [timeoutNanos] 内拿到许可时完成；超时以 [onTimeout] 的异常失败。
     */
    fun acquireAsync(timeoutNanos: Long, onTimeout: () -> Throwable): CompletableFuture<Void?> {
        if (semaphore.tryAcquire()) {
            return CompletableFuture.completedFuture(null)
        }
        val waiter = CompletableFuture<Void?>()
        waiters.add(waiter)
        // 登记之后再交接一次：避免在“尝试失败”与“登记”之间发生的释放被错过
        handOff()
        if (!waiter.isDone) {
            val timeout = async.after(timeoutNanos) {
                if (waiters.remove(waiter)) {
                    waiter.completeExceptionally(onTimeout())
                }
                CompletableFuture.completedFuture(null)
            }
            timeout.whenComplete { _, error ->
                // 运行时关闭：定时器不再触发
                if (error != null && waiters.remove(waiter)) {
                    waiter.completeExceptionally(error)
                }
            }
            // 拿到许可后撤销超时任务
            waiter.whenComplete { _, _ -> timeout.cancel(false) }
        }
        return waiter
    }

    fun release() {
        semaphore.release()
        handOff()
    }

    /**
     * 把空闲的许可交给最早登记的等待者；等待者已被超时移除时归还许可并继续。
     */
    private fun handOff() {
        while (true) {
            val waiter = waiters.peek() ?: return
            if (!semaphore.tryAcquire()) {
                return
            }
            if (waiters.remove(waiter)) {
                waiter.complete(null)
            } else {
                semaphore.release()
            }
        }
    }
}
