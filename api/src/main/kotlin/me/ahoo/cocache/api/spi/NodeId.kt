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

package me.ahoo.cocache.api.spi

import java.security.SecureRandom

/**
 * 进程身份（16 字节），每个运行时一个：用于构造全局唯一的令牌，以及过滤自己发出的失效事件。
 */
class NodeId(bytes: ByteArray) {
    init {
        require(bytes.size == SIZE) { "NodeId must be $SIZE bytes, but was ${bytes.size}." }
    }

    private val value: ByteArray = bytes.copyOf()

    /**
     * 字节副本。
     */
    val bytes: ByteArray
        get() = value.copyOf()

    override fun equals(other: Any?): Boolean {
        return this === other || (other is NodeId && value.contentEquals(other.value))
    }

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = value.toHex()

    companion object {
        const val SIZE = 16
        private val random = SecureRandom()

        @JvmStatic
        fun random(): NodeId {
            val bytes = ByteArray(SIZE)
            random.nextBytes(bytes)
            return NodeId(bytes)
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
