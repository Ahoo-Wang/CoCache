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
import org.springframework.data.redis.connection.Message

object EvictedEvents {
    private const val DELIMITER = "@@"

    /**
     * Decode a pub/sub message into an eviction event.
     *
     * The wire format is `key + "@@" + publisherId`. The publisherId is
     * framework-generated and never contains the "@@" delimiter, so splitting on
     * the LAST "@@" keeps the key intact even when the key itself contains "@@".
     */
    fun fromMessage(message: Message): CacheEvictedEvent {
        val cacheName = message.channel.decodeToString()
        val msgBody = message.body.decodeToString()
        val delimiterIndex = msgBody.lastIndexOf(DELIMITER)
        require(delimiterIndex >= 0) { "message illegal:[$msgBody]." }
        val key = msgBody.substring(0, delimiterIndex)
        val publisherId = msgBody.substring(delimiterIndex + DELIMITER.length)
        return CacheEvictedEvent(cacheName, key, publisherId)
    }

    /**
     * Encode a cache key and the publishing client's id into the pub/sub body.
     *
     * Contract: `clientId` MUST NOT contain the "@@" delimiter. The decoder
     * splits on the last "@@", so a `clientId` containing "@@" would be split
     * across the two fields and corrupt eviction. This is enforced here so a
     * non-conforming [me.ahoo.cache.util.ClientIdGenerator] fails fast at publish
     * time instead of silently producing an ambiguous message. The built-in
     * generators (UUID, HostClientIdGenerator) satisfy this contract.
     */
    fun asMessage(key: String, clientId: String): String {
        require(!clientId.contains(DELIMITER)) {
            "publisherId[$clientId] must not contain the delimiter[$DELIMITER]."
        }
        return key + DELIMITER + clientId
    }
}
