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

package me.ahoo.cache.util

import java.util.concurrent.atomic.AtomicLong

/**
 * Generator of the per-instance client id used to tag cache-eviction events.
 *
 * Contract: the generated id MUST NOT contain the "@@" substring. It is used as
 * the publisherId of eviction messages (see
 * `me.ahoo.cache.spring.redis.codec.EvictedEvents`), whose wire format splits the
 * body on the last "@@"; an id containing "@@" would break that split and
 * corrupt cross-instance eviction. The built-in generators below satisfy this.
 *
 * Contract: the generated id MUST be unique across all instances. Subscribers
 * ignore events whose publisherId equals their own clientId, so two instances
 * sharing an id silently drop each other's evictions.
 */
interface ClientIdGenerator {
    fun generate(): String

    companion object {
        @JvmField
        val UUID = UUIDClientIdGenerator

        @JvmField
        val HOST = HostClientIdGenerator.DEFAULT
    }
}

object UUIDClientIdGenerator : ClientIdGenerator {
    override fun generate(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "")
    }
}

class HostClientIdGenerator(private val hostProvider: () -> String) : ClientIdGenerator {
    companion object {
        @JvmStatic
        val DEFAULT = HostClientIdGenerator { LocalHostAddress.hostAddress }

        private const val NONCE_LENGTH = 8
    }

    private val counter = AtomicLong()
    private val host: String by lazy {
        hostProvider()
    }

    /**
     * 每个生成器实例的随机后缀。host + pid 并不唯一（例如 host 网络模式下同机的两个容器 pid 都为 1），
     * clientId 相同的实例会把对方的失效事件当作自发事件忽略，因此需要随机后缀保证唯一。
     */
    private val nonce: String = java.util.UUID.randomUUID().toString().substring(0, NONCE_LENGTH)

    override fun generate(): String {
        return "${counter.getAndIncrement()}:${ProcessHandle.current().pid()}:$nonce@$host"
    }
}
