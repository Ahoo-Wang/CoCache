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

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 本机地址：取第一个已启用、非回环网卡上的 IPv4 地址，否则回退到 [InetAddress.getLocalHost]。
 *
 * 仅用于提高 clientId 的可读性，clientId 的唯一性由 [HostClientIdGenerator] 的随机后缀保证。
 */
internal object LocalHostAddress {
    const val UNKNOWN = "unknown"

    val hostAddress: String by lazy {
        resolve(::interfaceAddresses) { InetAddress.getLocalHost().hostAddress }
    }

    fun resolve(candidates: () -> List<InetAddress>, fallback: () -> String): String {
        return runCatching { candidates().firstOrNull { it.isSiteCandidate() }?.hostAddress }.getOrNull()
            ?: runCatching(fallback).getOrDefault(UNKNOWN)
    }

    private fun InetAddress.isSiteCandidate(): Boolean {
        return this is Inet4Address && !isLoopbackAddress && !isAnyLocalAddress
    }

    private fun interfaceAddresses(): List<InetAddress> {
        return NetworkInterface.networkInterfaces()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses() }
            .toList()
    }
}
