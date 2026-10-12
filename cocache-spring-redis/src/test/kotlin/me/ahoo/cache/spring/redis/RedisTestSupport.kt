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

import org.springframework.core.task.SyncTaskExecutor
import org.springframework.data.redis.connection.RedisClusterConfiguration
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * 集成测试的 Redis 连接：默认 localhost:6379；设置环境变量 `SPRING_DATA_REDIS_CLUSTER_NODES`
 * （如 `127.0.0.1:7000,127.0.0.1:7001,127.0.0.1:7002`）时连接 Redis Cluster，与 starter 测试一致。
 */
class RedisTestSupport : AutoCloseable {
    companion object {
        private const val CLUSTER_NODES_ENV = "SPRING_DATA_REDIS_CLUSTER_NODES"

        val clusterNodes: List<String>? = System.getenv(CLUSTER_NODES_ENV)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

        fun newConnectionFactory(): LettuceConnectionFactory {
            val nodes = clusterNodes
            val factory = if (nodes == null) {
                LettuceConnectionFactory(RedisStandaloneConfiguration())
            } else {
                LettuceConnectionFactory(RedisClusterConfiguration(nodes))
            }
            return factory.apply {
                afterPropertiesSet()
                start()
            }
        }
    }

    val connectionFactory = newConnectionFactory()
    val redisTemplate = StringRedisTemplate(connectionFactory).apply {
        afterPropertiesSet()
    }

    /**
     * 同步分发订阅通知：register() 返回前订阅已确认且 onReset 已执行完，测试不受迟到重置干扰。
     */
    val listenerContainer: RedisMessageListenerContainer by lazy {
        RedisMessageListenerContainer().apply {
            setConnectionFactory(this@RedisTestSupport.connectionFactory)
            setTaskExecutor(SyncTaskExecutor())
            afterPropertiesSet()
            start()
        }
    }

    fun newEventBus(): RedisCacheEvictedEventBus {
        return RedisCacheEvictedEventBus(redisTemplate, listenerContainer)
    }

    override fun close() {
        listenerContainer.destroy()
        connectionFactory.destroy()
    }
}
