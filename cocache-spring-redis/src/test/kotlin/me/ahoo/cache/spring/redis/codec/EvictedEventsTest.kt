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

package me.ahoo.cache.spring.redis.codec

import me.ahoo.cache.consistency.CacheEvictedEvent
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.Message

/**
 * EvictedEvents Test .
 *
 * @author ahoo wang
 */
internal class EvictedEventsTest {
    private fun messageOf(channel: String, body: String): Message {
        return object : Message {
            override fun getBody(): ByteArray = body.toByteArray()
            override fun getChannel(): ByteArray = channel.toByteArray()
        }
    }

    @Test
    fun roundTrip() {
        val cacheName = "userCache"
        val key = "user:1"
        val publisherId = "client-1"
        val message = EvictedEvents.asMessage(key, publisherId)
        val event = EvictedEvents.fromMessage(messageOf(cacheName, message))
        event.assert().isEqualTo(CacheEvictedEvent(cacheName, key, publisherId))
    }

    @Test
    fun roundTripWhenKeyContainsDelimiter() {
        val cacheName = "userCache"
        val key = "user@@42" // key contains the @@ delimiter
        val publisherId = "client-1"
        val message = EvictedEvents.asMessage(key, publisherId)
        val event = EvictedEvents.fromMessage(messageOf(cacheName, message))
        event.assert().isEqualTo(CacheEvictedEvent(cacheName, key, publisherId))
    }

    @Test
    fun roundTripWhenBothFieldsContainDelimiterAndPercent() {
        val cacheName = "userCache"
        val key = "user@@100%off"
        val publisherId = "cli@@ent@1"
        val message = EvictedEvents.asMessage(key, publisherId)
        val event = EvictedEvents.fromMessage(messageOf(cacheName, message))
        event.assert().isEqualTo(CacheEvictedEvent(cacheName, key, publisherId))
    }
}
