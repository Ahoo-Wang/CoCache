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

import me.ahoo.cache.CoherentCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.util.*

@State(Scope.Benchmark)
open class RedisCacheBenchmark {
    private lateinit var coherentCache: CoherentCache<String, String>
    private val cacheKey = UUID.randomUUID().toString()

    @Setup
    public fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        val lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        val stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        stringRedisTemplate.afterPropertiesSet()
        val codecExecutor = StringToStringCodecExecutor(stringRedisTemplate)
        val distributedCache = RedisDistributedCache(
            stringRedisTemplate,
            codecExecutor
        )
        val redisMessageListenerContainer = RedisMessageListenerContainer()
        redisMessageListenerContainer.setConnectionFactory(lettuceConnectionFactory)
        redisMessageListenerContainer.afterPropertiesSet()
        /*
         **** Very important ****
         */
        redisMessageListenerContainer.start()
        val cacheEvictedEventBus = RedisCacheEvictedEventBus(
            redisTemplate = StringRedisTemplate(lettuceConnectionFactory),
            listenerContainer = redisMessageListenerContainer
        )
        coherentCache = CoherentCache(
            "RedisCacheBenchmark",
            UUID.randomUUID().toString(),
            ToStringKeyConverter(""),
            distributedCache,
            MapClientSideCache(),
            cacheEvictedEventBus
        )

        coherentCache.set(cacheKey, UUID.randomUUID().toString())
    }

    @Benchmark
    fun get(): String? {
        return coherentCache[cacheKey]
    }
}
