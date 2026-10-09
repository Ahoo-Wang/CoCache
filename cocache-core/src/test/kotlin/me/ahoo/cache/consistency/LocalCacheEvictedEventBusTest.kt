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

package me.ahoo.cache.consistency

import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.cache.test.consistency.CacheEvictedEventBusSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class LocalCacheEvictedEventBusTest : CacheEvictedEventBusSpec() {
    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = LocalCacheEvictedEventBus()
}

internal class LocalCacheEvictedEventBusIsolationTest {
    private val eventBus = LocalCacheEvictedEventBus()

    private class Recorder(override val cacheName: String, private val failure: Exception? = null) : CacheEvictedSubscriber {
        val events = mutableListOf<CacheEvictedEvent>()
        var resets = 0

        override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
            failure?.let { throw it }
            events.add(cacheEvictedEvent)
        }

        override fun onReset() {
            resets++
        }
    }

    @Test
    fun failingSubscriberDoesNotBlockOthers() {
        val failing = Recorder("cache", IllegalStateException("boom"))
        val healthy = Recorder("cache")
        eventBus.register(failing)
        eventBus.register(healthy)

        eventBus.publish(CacheEvictedEvent("cache", "key", "publisher"))

        healthy.events.size.assert().isOne()
    }

    @Test
    fun publishWithoutSubscribersIsNoOp() {
        eventBus.publish(CacheEvictedEvent("nobody", "key", "publisher"))
    }

    @Test
    fun registeringTwiceResetsOnce() {
        val subscriber = Recorder("cache")
        eventBus.register(subscriber)
        eventBus.register(subscriber)
        subscriber.resets.assert().isOne()
    }

    @Test
    fun unregisterUnknownSubscriberIsNoOp() {
        eventBus.unregister(Recorder("unknown"))
    }
}
