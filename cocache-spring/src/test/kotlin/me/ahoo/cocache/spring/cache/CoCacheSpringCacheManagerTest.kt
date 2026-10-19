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

package me.ahoo.cocache.spring.cache

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class CoCacheSpringCacheManagerTest {
    @Test
    fun defaultExecutorPrefersVirtualThreads() {
        val virtual = Executor { it.run() }
        CoCacheSpringCacheManager.defaultExecutor { virtual }.assert().isSameAs(virtual)
    }

    @Test
    fun fallsBackToAGrowableDaemonPoolThatNeverQueues() {
        val pool = CoCacheSpringCacheManager.platformExecutor()
        try {
            pool.maximumPoolSize.assert().isEqualTo(CoCacheSpringCacheManager.MAX_PLATFORM_THREADS)
            // 外层任务占住线程等待内层任务：内层任务必须拿到新线程（不排在外层之后），否则就会死锁
            val inner = CountDownLatch(1)
            val outer = CountDownLatch(1)
            var daemon = false
            pool.execute {
                daemon = Thread.currentThread().isDaemon
                pool.execute { inner.countDown() }
                inner.await(5, TimeUnit.SECONDS)
                outer.countDown()
            }
            outer.await(5, TimeUnit.SECONDS).assert().isTrue()
            daemon.assert().isTrue()
        } finally {
            pool.shutdownNow()
        }
        CoCacheSpringCacheManager.defaultExecutor { null }.assert().isInstanceOf(
            java.util.concurrent.ThreadPoolExecutor::class.java
        )
    }

    @Test
    fun saturatedPoolRunsOnTheCaller() {
        val pool = CoCacheSpringCacheManager.platformExecutor()
        try {
            val release = CountDownLatch(1)
            repeat(
                CoCacheSpringCacheManager.MAX_PLATFORM_THREADS
            ) { pool.execute { release.await(10, TimeUnit.SECONDS) } }
            var ranOn: Thread? = null
            pool.execute { ranOn = Thread.currentThread() }
            ranOn.assert().isSameAs(Thread.currentThread())
            release.countDown()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun virtualThreadLookupMatchesTheRuntime() {
        val hasVirtualThreads = Runtime.version().feature() >= 21
        (CoCacheSpringCacheManager.virtualThreadExecutor() != null).assert().isEqualTo(hasVirtualThreads)
        CoCacheSpringCacheManager.DEFAULT_EXECUTOR.assert().isNotNull()
    }
}
