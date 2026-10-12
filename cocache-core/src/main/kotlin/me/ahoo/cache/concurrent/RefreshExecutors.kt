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

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 提前刷新（`refreshAhead`）使用的执行器。
 */
object RefreshExecutors {
    private const val QUEUE_CAPACITY = 1024
    private const val KEEP_ALIVE_SECONDS = 60L
    private const val MIN_THREADS = 4

    /**
     * 默认执行器：有界守护线程池（线程数取 CPU 数与 4 的较大者，空闲回收）。
     * 队列满时拒绝（抛出 RejectedExecutionException），调用方放弃本次刷新，值照常到期。
     */
    @JvmStatic
    val DEFAULT: Executor by lazy {
        val threads = maxOf(Runtime.getRuntime().availableProcessors(), MIN_THREADS)
        ThreadPoolExecutor(
            threads,
            threads,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(QUEUE_CAPACITY),
            DaemonThreadFactory("cocache-refresh-"),
            ThreadPoolExecutor.AbortPolicy()
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private class DaemonThreadFactory(private val prefix: String) : ThreadFactory {
        private val counter = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread {
            return Thread(runnable, prefix + counter.incrementAndGet()).apply { isDaemon = true }
        }
    }
}
