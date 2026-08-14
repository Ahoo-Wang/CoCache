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

package me.ahoo.cache.spring.redis.codec

import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.util.CacheSecondClock
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 原子写入冒烟：并发交替写两个版本，最终值必须是完整单一版本（无字段混合）。
 */
internal class AtomicWriteConsistencyTest {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    lateinit var codecExecutor: MapToHashCodecExecutor

    @BeforeEach
    fun setup() {
        lettuceConnectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration())
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        stringRedisTemplate.afterPropertiesSet()
        codecExecutor = MapToHashCodecExecutor(stringRedisTemplate)
    }

    @AfterEach
    fun destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy()
        }
    }

    @Test
    fun concurrentWritesProduceCompleteSingleVersion() {
        val key = "atomic-write:" + UUID.randomUUID().toString()
        val ttlAt = CacheSecondClock.INSTANCE.currentTime() + 60
        val versionA = mapOf("fa" to "va1", "extra-a" to "va2")
        val versionB = mapOf("fb" to "vb1")
        val iterations = 50
        val errors = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        try {
            repeat(2) { thread ->
                val version = if (thread == 0) versionA else versionB
                executor.submit {
                    try {
                        startLatch.await()
                        repeat(iterations) {
                            codecExecutor.executeAndEncode(key, DefaultCacheValue(version, ttlAt))
                        }
                    } catch (e: Throwable) {
                        errors.incrementAndGet()
                    }
                }
            }
            startLatch.countDown()
        } finally {
            executor.shutdown()
            executor.awaitTermination(30, TimeUnit.SECONDS).assert().isTrue()
        }
        errors.get().assert().isZero()

        val finalFields = stringRedisTemplate.opsForHash<String, String>().keys(key)
        val isCompleteA = finalFields == versionA.keys
        val isCompleteB = finalFields == versionB.keys
        (isCompleteA || isCompleteB).assert()
            .withFailMessage { "field-union corruption detected: $finalFields" }
            .isTrue()
    }

    @Test
    fun concurrentSetWritesProduceCompleteSingleVersion() {
        val setCodecExecutor = SetToSetCodecExecutor(stringRedisTemplate)
        val key = "atomic-write-set:" + UUID.randomUUID().toString()
        val ttlAt = CacheSecondClock.INSTANCE.currentTime() + 60
        val versionA = setOf("sa1", "sa2", "extra-a")
        val versionB = setOf("sb1")
        val iterations = 50
        val errors = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        try {
            repeat(2) { thread ->
                val version = if (thread == 0) versionA else versionB
                executor.submit {
                    try {
                        startLatch.await()
                        repeat(iterations) {
                            setCodecExecutor.executeAndEncode(key, DefaultCacheValue(version, ttlAt))
                        }
                    } catch (e: Throwable) {
                        errors.incrementAndGet()
                    }
                }
            }
            startLatch.countDown()
        } finally {
            executor.shutdown()
            executor.awaitTermination(30, TimeUnit.SECONDS).assert().isTrue()
        }
        errors.get().assert().isZero()

        val finalMembers = stringRedisTemplate.opsForSet().members(key).orEmpty()
        val isCompleteA = finalMembers == versionA
        val isCompleteB = finalMembers == versionB
        (isCompleteA || isCompleteB).assert()
            .withFailMessage { "member-union corruption detected: $finalMembers" }
            .isTrue()
    }
}
