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

import io.lettuce.core.RedisConnectionException
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubListener
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection
import io.lettuce.core.codec.StringCodec
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * 集群分支（[RedisClusterClient]）的单元测试：真实集群行为由 CI 的 Redis Cluster 任务覆盖，
 * 这里用 mock 验证连接创建、监听器回调与发布路径。
 */
internal class ShardedRedisCacheEvictedEventBusClusterTest {
    private val listener = slot<RedisClusterPubSubListener<String, String>>()
    private val pubSub = mockk<StatefulRedisClusterPubSubConnection<String, String>>(relaxed = true) {
        every { addListener(capture(listener)) } just runs
    }
    private val publisher = mockk<StatefulRedisClusterConnection<String, String>>(relaxed = true)
    private val client = mockk<RedisClusterClient> {
        every { connectPubSub(StringCodec.UTF8) } returns pubSub
        every { connect(StringCodec.UTF8) } returns publisher
    }
    private val node = mockk<RedisClusterNode>()

    private class RecordingSubscriber(override val cacheName: String) : CacheEvictedSubscriber {
        val events = mutableListOf<CacheEvictedEvent>()
        var resets = 0

        override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
            events.add(cacheEvictedEvent)
        }

        override fun onReset() {
            resets++
        }
    }

    @Test
    fun clusterListenerDeliversAndResets() {
        val bus = ShardedRedisCacheEvictedEventBus.create(client)
        verify { pubSub.setNodeMessagePropagation(true) }
        val subscriber = RecordingSubscriber("ClusterCache")
        bus.register(subscriber)
        verify { pubSub.sync().ssubscribe("ClusterCache") }
        subscriber.resets.assert().isOne()

        listener.captured.ssubscribed(node, "ClusterCache", 1)
        subscriber.resets.assert().isOne()
        listener.captured.ssubscribed(node, "ClusterCache", 1)
        subscriber.resets.assert().isEqualTo(2)

        listener.captured.smessage(node, "ClusterCache", "key@@peer")
        subscriber.events.assert().containsExactly(CacheEvictedEvent("ClusterCache", "key", "peer"))

        bus.publish(CacheEvictedEvent("ClusterCache", "key", "self"))
        verify { publisher.sync().spublish("ClusterCache", "key@@self") }

        bus.unregister(subscriber)
        verify { pubSub.sync().sunsubscribe("ClusterCache") }
        bus.close()
        verify { pubSub.close() }
        verify { publisher.close() }
    }

    @Test
    fun clusterPublishFailureIsCounted() {
        every { publisher.sync().spublish(any(), any()) } throws RedisConnectionException("down")
        val bus = ShardedRedisCacheEvictedEventBus.create(client)

        bus.publish(CacheEvictedEvent("ClusterCache", "key", "self"))

        bus.publishFailureCount.assert().isOne()
    }
}
