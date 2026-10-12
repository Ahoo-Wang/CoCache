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

import io.lettuce.core.KillArgs
import io.lettuce.core.RedisClient
import io.lettuce.core.cluster.RedisClusterClient
import io.mockk.mockk
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.cache.test.consistency.CacheEvictedEventBusSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class ShardedRedisCacheEvictedEventBusTest : CacheEvictedEventBusSpec() {
    private lateinit var redis: RedisTestSupport

    @BeforeEach
    fun setup() {
        redis = RedisTestSupport()
    }

    @AfterEach
    fun destroy() {
        redis.close()
    }

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = redis.newShardedEventBus()

    private class CountingSubscriber(override val cacheName: String) : CacheEvictedSubscriber {
        val resets = AtomicInteger()

        @Volatile
        var received = CountDownLatch(1)

        override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
            received.countDown()
        }

        override fun onReset() {
            resets.incrementAndGet()
        }
    }

    /**
     * 断开所有 Pub/Sub 客户端连接：Lettuce 自动重连并重新订阅，重新订阅的确认必须重置订阅者。
     */
    private fun killPubSubClients() {
        val killPubSub = KillArgs.Builder.typePubsub()
        when (val client = redis.connectionFactory.nativeClient) {
            is RedisClusterClient -> client.connect().use { it.sync().masters().commands().clientKill(killPubSub) }
            is RedisClient -> client.connect().use { it.sync().clientKill(killPubSub) }
            else -> error("Unsupported client: $client")
        }
    }

    @Test
    fun resubscribeAfterDisconnectResetsSubscribers() {
        val eventBus = redis.newShardedEventBus()
        val subscriber = CountingSubscriber("ShardedResubscribe-${UUID.randomUUID()}")
        eventBus.register(subscriber)
        subscriber.resets.get().assert().isOne()

        killPubSubClients()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (subscriber.resets.get() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        subscriber.resets.get().assert().isGreaterThanOrEqualTo(2)
        eventBus.publish(CacheEvictedEvent(subscriber.cacheName, "key", "publisher"))
        subscriber.received.await(5, TimeUnit.SECONDS).assert().isTrue()
        eventBus.unregister(subscriber)
    }

    @Test
    fun secondSubscriberOnSameChannelIsReset() {
        val eventBus = redis.newShardedEventBus()
        val cacheName = "ShardedSameChannel-${UUID.randomUUID()}"
        val first = CountingSubscriber(cacheName)
        val second = CountingSubscriber(cacheName)
        eventBus.register(first)
        eventBus.register(second)

        second.resets.get().assert().isOne()
        eventBus.publish(CacheEvictedEvent(cacheName, "key", "publisher"))
        first.received.await(5, TimeUnit.SECONDS).assert().isTrue()
        second.received.await(5, TimeUnit.SECONDS).assert().isTrue()

        eventBus.unregister(first)
        eventBus.unregister(second)
        eventBus.unregister(second)
    }

    @Test
    fun publishFailureIsCounted() {
        val eventBus = redis.newShardedEventBus()
        eventBus.close()

        eventBus.publish(CacheEvictedEvent("ShardedClosed", "key", "publisher"))

        eventBus.publishFailureCount.assert().isOne()
    }

    @Test
    fun rejectsUnsupportedClient() {
        runCatching { ShardedRedisCacheEvictedEventBus.create(mockk()) }
            .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }
}
