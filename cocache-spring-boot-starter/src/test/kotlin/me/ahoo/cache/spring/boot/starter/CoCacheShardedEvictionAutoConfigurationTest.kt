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

package me.ahoo.cache.spring.boot.starter

import io.mockk.mockk
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.example.cache.UserCache
import me.ahoo.cache.spring.boot.starter.auto.EnableCoCacheConfiguration
import me.ahoo.cache.spring.redis.RedisCacheEvictedEventBus
import me.ahoo.cache.spring.redis.ShardedRedisCacheEvictedEventBus
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import java.util.UUID

internal class CoCacheShardedEvictionAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            JacksonAutoConfiguration::class.java,
            DataRedisAutoConfiguration::class.java,
            CoCacheShardedEvictionAutoConfiguration::class.java,
            CoCacheAutoConfiguration::class.java,
            EnableCoCacheConfiguration::class.java
        )

    @Test
    fun shardedChannel() {
        contextRunner
            .withPropertyValues("cocache.redis.eviction-channel=sharded")
            .run { context ->
                assertThat(context).hasSingleBean(CacheEvictedEventBus::class.java)
                context.getBean(CacheEvictedEventBus::class.java)
                    .assert().isInstanceOf(ShardedRedisCacheEvictedEventBus::class.java)
                val userCache = context.getBean(UserCache::class.java)
                val key = UUID.randomUUID().toString()
                userCache[key]
                userCache.evict(key)
            }
    }

    @Test
    fun pubSubChannelByDefault() {
        contextRunner.run { context ->
            context.getBean(CacheEvictedEventBus::class.java)
                .assert().isInstanceOf(RedisCacheEvictedEventBus::class.java)
        }
    }

    @Test
    fun shardedChannelRequiresLettuce() {
        ApplicationContextRunner()
            .withPropertyValues("cocache.redis.eviction-channel=sharded")
            .withBean(RedisConnectionFactory::class.java, { mockk<RedisConnectionFactory>(relaxed = true) })
            .withUserConfiguration(CoCacheShardedEvictionAutoConfiguration::class.java)
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().hasStackTraceContaining("requires a LettuceConnectionFactory")
            }
    }
}
