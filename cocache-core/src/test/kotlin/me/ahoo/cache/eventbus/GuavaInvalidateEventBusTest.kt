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
package me.ahoo.cache.eventbus

import me.ahoo.cache.consistency.GuavaInvalidateEventBus
import me.ahoo.cache.consistency.InvalidateEvent
import me.ahoo.cache.consistency.InvalidateSubscriber
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SimpleInvalidateEventBusTest .
 *
 * @author ahoo wang
 */
internal class GuavaInvalidateEventBusTest {
    @Test
    fun publish() {
        val countDownLatch = CountDownLatch(1)
        val eventBus = GuavaInvalidateEventBus(CLIENT_ID)
        val publishedEvent = InvalidateEvent("publish", "")
        val subscriber = InvalidateSubscriber { invalidateEvent ->
            Assertions.assertEquals(publishedEvent, invalidateEvent)
            countDownLatch.countDown()
        }
        eventBus.register(subscriber)
        eventBus.publish(publishedEvent)
        Assertions.assertTrue(countDownLatch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun publishWhenNoLoop() {
        val countDownLatch = CountDownLatch(1)
        val eventBus = GuavaInvalidateEventBus(CLIENT_ID)
        val publishedEvent = InvalidateEvent("publishWhenNoLoop", CLIENT_ID)
        val subscriber = InvalidateSubscriber { invalidateEvent: InvalidateEvent ->
            Assertions.assertEquals(publishedEvent, invalidateEvent)
            countDownLatch.countDown()
        }
        eventBus.register(subscriber)
        eventBus.publish(publishedEvent)
        Assertions.assertFalse(countDownLatch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun unregister() {
        val countDownLatch = CountDownLatch(1)
        val eventBus = GuavaInvalidateEventBus(CLIENT_ID)
        val publishedEvent = InvalidateEvent("unregister", "")
        val subscriber = InvalidateSubscriber { invalidateEvent: InvalidateEvent ->
            Assertions.assertEquals(publishedEvent, invalidateEvent)
            countDownLatch.countDown()
        }
        eventBus.register(subscriber)
        eventBus.unregister(subscriber)
        eventBus.publish(publishedEvent)
        Assertions.assertFalse(countDownLatch.await(1, TimeUnit.SECONDS))
    }

    companion object {
        private const val CLIENT_ID = "GuavaInvalidateEventBusTest"
    }
}
