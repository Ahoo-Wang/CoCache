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
    fun valueOrThrowAnswersPresentAndAbsentAndThrowsUnknown() {
        Lookup.Present("v", null).valueOrThrow().assert().isEqualTo("v")
        val absent: Lookup<String> = Lookup.Absent(null)
        absent.valueOrThrow().assert().isNull()
        val error = CacheReadException("user", "read", IllegalStateException("down"))
        val unknown: Lookup<String> = Lookup.Unknown(error)
        val thrown = runCatching { unknown.valueOrThrow() }.exceptionOrNull()
        thrown.assert().isSameAs(error)
        unknown.toString().assert().contains("down")
    }

    @Test
    fun readExceptionCarriesUnknownKeysAndPartialResult() {
        val cause = IllegalStateException("down")
        val single = CacheReadException("user", "read", cause)
        single.unknownKeys.assert().isEmpty()
        single.partialResult.assert().isEmpty()
        single.message.assert().isEqualTo("Cache[user] - read failed on the remote store: IllegalStateException: down")
        val partial = CacheReadException("user", "getAll", cause, setOf("a"), mapOf("b" to 1))
        partial.unknownKeys.assert().containsExactly("a")
        partial.partialResult.assert().containsEntry("b", 1)
        partial.message.assert().contains("1 key(s): IllegalStateException: down")
        partial.cause.assert().isSameAs(cause)
    }

    @Test
    fun writeExceptionDescribesOperationAndOutcome() {
        val cause = IllegalStateException("down")
        val error = CacheWriteException("user", "put", cause)
        error.cacheName.assert().isEqualTo("user")
        error.operation.assert().isEqualTo("put")
        error.cause.assert().isSameAs(cause)
        error.outcomeUnknown.assert().isTrue()
        error.message.assert().isEqualTo(
            "Cache[user] - put failed on the remote store (outcome unknown): IllegalStateException: down"
        )
        val rejected = CacheWriteException("user", "put", cause, outcomeUnknown = false)
        rejected.outcomeUnknown.assert().isFalse()
        rejected.message.assert().contains("not executed")
        CacheWriteException("user", "put", IllegalStateException(), outcomeUnknown = false).message.assert()
            .isEqualTo("Cache[user] - put failed on the remote store (not executed): IllegalStateException")
    }

    @Test
    fun conditionalDeleteIsAValueType() {
        ConditionalDelete.Conflict(null).assert().isEqualTo(ConditionalDelete.Conflict(null))
        ConditionalDelete.Deleted.toString().assert().isEqualTo("Deleted")
    }

    @Test
    fun clockIsAFunctionalInterface() {
        CacheClock { 42 }.nowMillis().assert().isEqualTo(42)
    }
}
