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

import me.ahoo.cache.consistency.CacheEvictedEvent
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.consistency.CacheEvictedSubscriber
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class CacheEvictedEventBusSpec {

    protected abstract fun createCacheEvictedEventBus(): CacheEvictedEventBus

    @Test
    fun publish() {
        val cacheName = "CacheEvictedEventBusSpec-publish"
        val countDownLatch = CountDownLatch(1)
        val eventBus = createCacheEvictedEventBus()
        val evictedEvent = CacheEvictedEvent(cacheName, "publish", UUID.randomUUID().toString())
        val subscriber = object : CacheEvictedSubscriber {
            override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
                assertThat(evictedEvent, equalTo(cacheEvictedEvent))
                countDownLatch.countDown()
            }

            override val cacheName: String
                get() = cacheName
        }

        eventBus.register(subscriber)
        eventBus.publish(evictedEvent)
        assertThat(countDownLatch.await(1, TimeUnit.SECONDS), equalTo(true))
    }

    @Test
    fun unregister() {
        val cacheName = "CacheEvictedEventBusSpec-unregister"
        val countDownLatch = CountDownLatch(1)
        val countDownLatch2 = CountDownLatch(2)
        val eventBus = createCacheEvictedEventBus()
        val evictedEvent = CacheEvictedEvent(cacheName, "unregister", UUID.randomUUID().toString())
        val subscriber = object : CacheEvictedSubscriber {
            override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
                assertThat(evictedEvent, equalTo(cacheEvictedEvent))
                countDownLatch.countDown()
                countDownLatch2.countDown()
            }

            override val cacheName: String
                get() = cacheName
        }
        eventBus.register(subscriber)
        eventBus.publish(evictedEvent)
        assertThat(countDownLatch.await(1, TimeUnit.SECONDS), equalTo(true))
        eventBus.unregister(subscriber)
        eventBus.publish(evictedEvent)
        assertThat(countDownLatch2.await(2, TimeUnit.SECONDS), equalTo(false))
    }
}
