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

package me.ahoo.cocache.spring.boot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class SetCacheEvictionCheckTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()

    @AfterEach
    fun destroy() = runtime.close()

    private fun redisWith(policies: Map<String, String>?): RedisBackend = mockk {
        every { maxmemoryPolicies() } returns policies
    }

    private fun withSetCache() = runtime.create(setCacheSpec<String, String>("index") { elementCodec = StringCodec })

    private fun warnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(SetCacheEvictionCheck::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun warnsWhenAPrimaryEvictsAnyKey() {
        withSetCache()
        val backend = redisWith(mapOf("10.0.0.1:6379" to "allkeys-lru", "10.0.0.2:6379" to "noeviction"))
        val logs = warnings {
            SetCacheEvictionCheck(runtime, backend, CoCacheProperties.EvictionCheck.WARN).afterSingletonsInstantiated()
        }
        logs.single().assert().contains(
            "[index]"
        ).contains("allkeys-lru").contains("10.0.0.1:6379").doesNotContain("10.0.0.2")
    }

    @Test
    fun failModeStopsStartup() {
        withSetCache()
        val backend = redisWith(mapOf("redis" to "allkeys-lfu"))
        runCatching {
            SetCacheEvictionCheck(runtime, backend, CoCacheProperties.EvictionCheck.FAIL).afterSingletonsInstantiated()
        }
            .exceptionOrNull().assert().isInstanceOf(
                IllegalStateException::class.java
            ).hasMessageContaining("allkeys-lfu")
    }

    @Test
    fun safePoliciesAndUnreadableConfigPass() {
        withSetCache()
        warnings {
            SetCacheEvictionCheck(
                runtime,
                redisWith(mapOf("redis" to "noeviction")),
                CoCacheProperties.EvictionCheck.FAIL
            )
                .afterSingletonsInstantiated()
            SetCacheEvictionCheck(
                runtime,
                redisWith(mapOf("redis" to "volatile-lru")),
                CoCacheProperties.EvictionCheck.FAIL
            )
                .afterSingletonsInstantiated()
            // CONFIG 不可用：跳过
            SetCacheEvictionCheck(
                runtime,
                redisWith(null),
                CoCacheProperties.EvictionCheck.FAIL
            ).afterSingletonsInstantiated()
        }.assert().isEmpty()
    }

    @Test
    fun skipsWithoutSetCachesOffModeOrNonRedisBackend() {
        runtime.create(cacheSpec<String, String>("user") { codec = StringCodec })
        val backend = redisWith(mapOf("redis" to "allkeys-lru"))
        // 只有普通缓存：不读取策略
        SetCacheEvictionCheck(runtime, backend, CoCacheProperties.EvictionCheck.FAIL).afterSingletonsInstantiated()
        verify(exactly = 0) { backend.maxmemoryPolicies() }
        withSetCache()
        SetCacheEvictionCheck(runtime, backend, CoCacheProperties.EvictionCheck.OFF).afterSingletonsInstantiated()
        verify(exactly = 0) { backend.maxmemoryPolicies() }
        val memory: CoherenceBackend = InMemoryBackend()
        SetCacheEvictionCheck(runtime, memory, CoCacheProperties.EvictionCheck.FAIL).afterSingletonsInstantiated()
    }
}
