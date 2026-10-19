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

package me.ahoo.cocache.api

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

class ApiTypesTest {
    @Test
    fun loadAllDefaultsToSingleLoadsAndSkipsAbsent() {
        val loader = CacheLoader<String, String> { if (it == "absent") null else it.uppercase() }
        loader.loadAll(listOf("a", "absent", "b")).assert().isEqualTo(mapOf("a" to "A", "b" to "B"))
    }

    @Test
    fun lookupCarriesExpiry() {
        val now = Instant.now()
        Lookup.Present("v", now).expiresAt.assert().isEqualTo(now)
        Lookup.Absent(null).expiresAt.assert().isNull()
    }

    @Test
    fun writeExceptionDescribesOperation() {
        val cause = IllegalStateException("down")
        val error = CacheWriteException("user", "put", cause)
        error.cacheName.assert().isEqualTo("user")
        error.operation.assert().isEqualTo("put")
        error.cause.assert().isSameAs(cause)
        error.message.assert().contains("put")
    }

    @Test
    fun clockIsAFunctionalInterface() {
        CacheClock { 42 }.nowMillis().assert().isEqualTo(42)
    }
}
