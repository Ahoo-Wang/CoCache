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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.core.RetryPolicy
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 失效重试队列：有界、指数退避。操作必须幂等。
 */
internal class InvalidationRetrier(
    private val scheduler: ScheduledExecutorService,
    private val policy: RetryPolicy,
) {
    private val pending = AtomicInteger()

    val size: Int
        get() = pending.get()

    /**
     * @return 是否接受；队列已满或调度器已关闭时返回 `false`
     */
    fun submit(action: () -> Unit, onRetry: () -> Unit, onSuccess: () -> Unit, onGiveUp: (Throwable) -> Unit): Boolean {
        if (pending.incrementAndGet() > policy.capacity) {
            pending.decrementAndGet()
            return false
        }
        return schedule(Task(action, onRetry, onSuccess, onGiveUp), 1)
    }

    private inner class Task(
        val action: () -> Unit,
        val onRetry: () -> Unit,
        val onSuccess: () -> Unit,
        val onGiveUp: (Throwable) -> Unit,
    )

    private fun schedule(task: Task, attempt: Int): Boolean {
        val delay = policy.backoff(attempt).toMillis()
        return try {
            scheduler.schedule({ run(task, attempt) }, delay, TimeUnit.MILLISECONDS)
            true
        } catch (e: RejectedExecutionException) {
            pending.decrementAndGet()
            log.warn(e) { "Invalidation retry rejected by the scheduler." }
            false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run(task: Task, attempt: Int) {
        task.onRetry()
        try {
            task.action()
        } catch (e: Exception) {
            if (attempt < policy.maxAttempts && schedule(task, attempt + 1)) {
                return
            }
            if (attempt >= policy.maxAttempts) {
                pending.decrementAndGet()
            }
            task.onGiveUp(e)
            return
        }
        pending.decrementAndGet()
        task.onSuccess()
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
