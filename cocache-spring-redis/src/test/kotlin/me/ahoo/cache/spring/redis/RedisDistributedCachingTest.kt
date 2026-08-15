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
package me.ahoo.cache.spring.redis

import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor
import me.ahoo.cache.test.DistributedCacheSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.data.Offset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.*

/**
 * RedisDistributedCachingTest .
 *
 * @author ahoo wang
 */
internal class RedisDistributedCachingTest : DistributedCacheSpec<String>() {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var codecExecutor: StringToStringCodecExecutor
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory

    override fun createCache(): DistributedCache<String> {
        return RedisDistributedCache(
            stringRedisTemplate,
            codecExecutor,
        )
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }

    @BeforeEach
    override fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        stringRedisTemplate.afterPropertiesSet()
        codecExecutor = StringToStringCodecExecutor(stringRedisTemplate)
        super.setup()
    }

    @AfterEach
    fun destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy()
        }
    }

    /**
     * Redis 实现的 ttlAt 经 getExpire 重建，写读跨越秒边界时会有 ±1 秒漂移——
     * 覆盖 TCK 的精确相等断言为容差断言（与 CodecExecutorSpec 的既有做法一致）。
     */
    @Test
    override fun setWithTtl() {
        val (key, value) = createCacheEntry()
        cache[key].assert().isNull()
        val cacheValue = DefaultCacheValue.ttlAt(value, 5)
        cache.setCache(key, cacheValue)
        cache[key].assert().isEqualTo(value)
        cache.getTtlAt(key).assert().isCloseTo(cacheValue.ttlAt, Offset.offset(1))
    }

    @Test
    override fun setWithTtlAmplitude() {
        val (key, value) = createCacheEntry()
        cache[key].assert().isNull()
        val cacheValue = DefaultCacheValue.ttlAt(value, 5, 1)
        cache.setCache(key, cacheValue)
        cache[key].assert().isEqualTo(value)
        cache.getTtlAt(key).assert().isCloseTo(cacheValue.ttlAt, Offset.offset(1))
    }
}
