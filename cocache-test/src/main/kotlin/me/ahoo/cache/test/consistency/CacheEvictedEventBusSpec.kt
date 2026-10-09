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

package me.ahoo.cache.test.consistency

import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class CacheEvictedEventBusSpec {

    protected abstract fun createCacheEvictedEventBus(): CacheEvictedEventBus

    private class RecordingSubscriber(override val cacheName: String) : CacheEvictedSubscriber {
        val subscribed = CountDownLatch(1)
        val events: MutableList<CacheEvictedEvent> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var received = CountDownLatch(1)

        override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
            events.add(cacheEvictedEvent)
            received.countDown()
        }

        override fun onReset() {
            subscribed.countDown()
        }
    }

    @Test
    fun registerResetsSubscriber() {
        val eventBus = createCacheEvictedEventBus()
        val subscriber = RecordingSubscriber("CacheEvictedEventBusSpec-reset-${UUID.randomUUID()}")
        eventBus.register(subscriber)
        try {
            subscriber.subscribed.await(5, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            eventBus.unregister(subscriber)
        }
    }

    @Test
    fun publish() {
        val eventBus = createCacheEvictedEventBus()
        val subscriber = RecordingSubscriber("CacheEvictedEventBusSpec-publish-${UUID.randomUUID()}")
        val event = CacheEvictedEvent(subscriber.cacheName, "publish@@key", UUID.randomUUID().toString())
        eventBus.register(subscriber)
        try {
            subscriber.subscribed.await(5, TimeUnit.SECONDS).assert().isTrue()
            eventBus.publish(event)
            subscriber.received.await(5, TimeUnit.SECONDS).assert().isTrue()
            subscriber.events.assert().containsExactly(event)
        } finally {
            eventBus.unregister(subscriber)
        }
    }

    @Test
    fun publishOnlyReachesSubscribersOfTheSameCache() {
        val eventBus = createCacheEvictedEventBus()
        val subscriber = RecordingSubscriber("CacheEvictedEventBusSpec-target-${UUID.randomUUID()}")
        val other = RecordingSubscriber("CacheEvictedEventBusSpec-other-${UUID.randomUUID()}")
        eventBus.register(subscriber)
        eventBus.register(other)
        try {
            subscriber.subscribed.await(5, TimeUnit.SECONDS).assert().isTrue()
            other.subscribed.await(5, TimeUnit.SECONDS).assert().isTrue()
            eventBus.publish(CacheEvictedEvent(subscriber.cacheName, "key", "publisher"))
            subscriber.received.await(5, TimeUnit.SECONDS).assert().isTrue()
            other.received.await(200, TimeUnit.MILLISECONDS).assert().isFalse()
        } finally {
            eventBus.unregister(subscriber)
            eventBus.unregister(other)
        }
    }

    @Test
    fun unregister() {
        val eventBus = createCacheEvictedEventBus()
        val subscriber = RecordingSubscriber("CacheEvictedEventBusSpec-unregister-${UUID.randomUUID()}")
        eventBus.register(subscriber)
        subscriber.subscribed.await(5, TimeUnit.SECONDS).assert().isTrue()
        eventBus.unregister(subscriber)
        eventBus.publish(CacheEvictedEvent(subscriber.cacheName, "key", "publisher"))
        subscriber.received.await(500, TimeUnit.MILLISECONDS).assert().isFalse()
    }
}
