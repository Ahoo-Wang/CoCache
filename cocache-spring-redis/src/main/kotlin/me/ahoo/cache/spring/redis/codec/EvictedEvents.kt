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
     * Percent-encode the `@` (and reserve `%`) so that neither field can
     * contain the `@@` delimiter, making the wire format unambiguous.
     */
    private fun String.escape(): String {
        return replace("%", "%25").replace("@", "%40")
    }

    private fun String.unescape(): String {
        return replace("%40", "@").replace("%25", "%")
    }

    fun fromMessage(message: Message): CacheEvictedEvent {
        val cacheName = message.channel.decodeToString()
        val msgBody = message.body.decodeToString()
        val parts = msgBody.split(DELIMITER, limit = 2)
        require(2 == parts.size) { "message illegal:[$msgBody]." }
        return CacheEvictedEvent(cacheName, parts[0].unescape(), parts[1].unescape())
    }

    fun asMessage(key: String, clientId: String): String {
        return key.escape() + DELIMITER + clientId.escape()
    }
}
