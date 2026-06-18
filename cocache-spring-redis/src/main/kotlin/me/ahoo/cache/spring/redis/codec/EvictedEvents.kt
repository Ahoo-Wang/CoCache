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
    private const val AT_SIGN_ENCODED = "%40"

    /**
     * Percent-encode only the `@` (the delimiter's constituent char) so a field
     * can never contain the `@@` delimiter. `%` itself is intentionally NOT
     * encoded: that keeps the wire format byte-for-byte identical to the legacy
     * format for any key/id without `@`, so older subscribers (which do no
     * unescaping) keep decoding such keys correctly during a rolling deploy.
     */
    private fun String.escape(): String = replace("@", AT_SIGN_ENCODED)

    private fun String.unescape(): String = replace(AT_SIGN_ENCODED, "@")

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
