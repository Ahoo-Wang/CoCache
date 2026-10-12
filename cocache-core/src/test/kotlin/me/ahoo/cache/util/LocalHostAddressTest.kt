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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.net.InetAddress

class LocalHostAddressTest {

    @Test
    fun hostAddress() {
        LocalHostAddress.hostAddress.assert().isNotBlank()
    }

    @Test
    fun resolveFirstNonLoopbackIpv4() {
        val candidates = listOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("0.0.0.0"),
            InetAddress.getByName("fe80::1"),
            InetAddress.getByName("10.0.0.1"),
            InetAddress.getByName("10.0.0.2"),
        )
        LocalHostAddress.resolve({ candidates }) { "fallback" }.assert().isEqualTo("10.0.0.1")
    }

    @Test
    fun resolveFallbackWhenNoCandidate() {
        val candidates = listOf(InetAddress.getByName("127.0.0.1"))
        LocalHostAddress.resolve({ candidates }) { "fallback" }.assert().isEqualTo("fallback")
    }

    @Test
    fun resolveFallbackWhenCandidatesFail() {
        LocalHostAddress.resolve({ error("no interfaces") }) { "fallback" }.assert().isEqualTo("fallback")
    }

    @Test
    fun resolveUnknownWhenFallbackFails() {
        LocalHostAddress.resolve({ emptyList() }) { error("no local host") }
            .assert().isEqualTo(LocalHostAddress.UNKNOWN)
    }
}
