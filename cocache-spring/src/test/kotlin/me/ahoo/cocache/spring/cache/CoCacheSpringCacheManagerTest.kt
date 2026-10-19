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
    fun fallsBackToABoundedDaemonPool() {
        val pool = CoCacheSpringCacheManager.platformExecutor()
        try {
            pool.maximumPoolSize.assert().isEqualTo(CoCacheSpringCacheManager.maxPlatformThreads())
            CoCacheSpringCacheManager.maxPlatformThreads().assert().isGreaterThanOrEqualTo(8)
            val ran = CountDownLatch(1)
            var daemon = false
            pool.execute {
                daemon = Thread.currentThread().isDaemon
                ran.countDown()
            }
            ran.await(5, TimeUnit.SECONDS).assert().isTrue()
            daemon.assert().isTrue()
        } finally {
            pool.shutdownNow()
        }
        CoCacheSpringCacheManager.defaultExecutor {
            null
        }.assert().isInstanceOf(java.util.concurrent.ThreadPoolExecutor::class.java)
    }

    @Test
    fun virtualThreadLookupMatchesTheRuntime() {
        val hasVirtualThreads = Runtime.version().feature() >= 21
        (CoCacheSpringCacheManager.virtualThreadExecutor() != null).assert().isEqualTo(hasVirtualThreads)
        CoCacheSpringCacheManager.DEFAULT_EXECUTOR.assert().isNotNull()
    }
}
