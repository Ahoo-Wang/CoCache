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
        // The key may itself contain the "@@" delimiter; the publisherId never does
        // (it is framework-generated). Split on the LAST "@@" so the whole key is
        // preserved.
        val cacheName = "userCache"
        val key = "user@@42"
        val publisherId = "client-1"
        val message = EvictedEvents.asMessage(key, publisherId)
        val event = EvictedEvents.fromMessage(messageOf(cacheName, message))
        event.assert().isEqualTo(CacheEvictedEvent(cacheName, key, publisherId))
    }

    @Test
    fun roundTripWhenKeyContainsMultipleDelimiters() {
        val cacheName = "userCache"
        val key = "user@@42@@extra"
        val publisherId = "0:1@10.0.0.1"
        val message = EvictedEvents.asMessage(key, publisherId)
        val event = EvictedEvents.fromMessage(messageOf(cacheName, message))
        event.assert().isEqualTo(CacheEvictedEvent(cacheName, key, publisherId))
    }

    @Test
    fun wireFormatIsUnchangedFromLegacy() {
        // No escaping is applied: the body is simply `key + "@@" + publisherId`,
        // byte-for-byte identical to the legacy format, so mixed-version deploys
        // keep working in both directions. This holds even when the key contains
        // `@` — the raw key bytes are preserved on the wire.
        val key = "discount@100%off"
        val publisherId = "client-1"
        val body = EvictedEvents.asMessage(key, publisherId)
        body.assert().isEqualTo("discount@100%off@@client-1")
    }
}
