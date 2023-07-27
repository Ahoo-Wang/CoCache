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

import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

interface ClientIdGenerator {
    fun generate(): String

    companion object {
        @JvmField
        val UUID = UUIDClientIdGenerator

        @JvmField
        val HOST = HostClientIdGenerator
    }
}

object UUIDClientIdGenerator : ClientIdGenerator {
    override fun generate(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "")
    }
}

object HostClientIdGenerator : ClientIdGenerator {
    private val currentProcessName: String by lazy {
        ManagementFactory.getRuntimeMXBean().name
    }

    @JvmStatic
    val currentProcessId: Long by lazy {
        val processName = currentProcessName
        val processIdStr = processName
            .split("@".toRegex())
            .filter { it.isNotBlank() }
            .toTypedArray()[0]
        processIdStr.toLong()
    }
    private val counter = AtomicLong()
    private val host: String by lazy {
        InetAddress.getLocalHost().hostAddress
    }

    override fun generate(): String {
        return "${counter.getAndIncrement()}:$currentProcessId@$host"
    }
}
