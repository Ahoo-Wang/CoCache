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

package me.ahoo.cocache.redis

import io.lettuce.core.cluster.SlotHash
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Suspending
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sharded 通道在 Redis Cluster 上的 slot 迁移（#629）：原节点让出 slot 时会主动退订，通道必须重置并在新节点上重新订阅。
 */
class RedisShardedChannelTest {
    @Test
    fun slotMigrationResetsAndKeepsReceivingEvents() {
        Assumptions.assumeTrue(RedisTestSupport.isCluster, "Redis Cluster only")
        val namespace = "sharded-migration-" + UUID.randomUUID()
        val slot = SlotHash.getSlot(
            RedisInvalidationChannel.channelOf(Keyspace("cocache:", namespace)).toByteArray(Charsets.UTF_8)
        )
        val resets = AtomicInteger()
        val received = CopyOnWriteArrayList<List<String>>()
        val listener = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                received.add(keys)
            }

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        RedisTestSupport.shardedBackend().use { subscriber ->
            RedisTestSupport.shardedBackend().use { publisher ->
                val subscription = subscriber.channel.subscribe(Keyspace("cocache:", namespace), listener)
                val frame = InvalidationFrame.encode(NodeId.random(), listOf("cocache:$namespace:before"))
                Suspending.await { publisher.channel.publish(Keyspace("cocache:", namespace), frame) }
                awaitCondition("event before migration") { received.contains(listOf("before")) }
                val resetsBefore = resets.get()

                val source = RedisTestSupport.migrateSlot(slot)
                try {
                    // 迁移期间发布的事件可能丢失，由重置兜底；重新订阅之后的事件必须送达
                    awaitCondition("reset after migration") { resets.get() > resetsBefore }
                    val after = InvalidationFrame.encode(NodeId.random(), listOf("cocache:$namespace:after"))
                    awaitCondition("event after migration") {
                        Suspending.await { publisher.channel.publish(Keyspace("cocache:", namespace), after) }
                        received.contains(listOf("after"))
                    }
                } finally {
                    RedisTestSupport.migrateSlot(slot, source)
                }
                subscription.close()
            }
        }
    }
}
