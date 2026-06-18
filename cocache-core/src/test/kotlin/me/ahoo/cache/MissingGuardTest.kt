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

package me.ahoo.cache

import me.ahoo.cache.MissingGuard.Companion.isMissingGuard
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class MissingGuardTest {
    @Test
    fun stringValueIsMissingGuard() {
        MissingGuard.STRING_VALUE.isMissingGuard.assert().isTrue()
    }

    @Test
    fun normalStringIsNotMissingGuard() {
        "hello".isMissingGuard.assert().isFalse()
    }

    @Test
    fun multiElementSetContainingSentinelIsNotMissingGuard() {
        // A real cached Set that legitimately contains "_nil_" as one element
        // (but not the whole sentinel) must NOT be mistaken for a missing guard.
        val realSet: Set<*> = setOf(MissingGuard.STRING_VALUE, "other")
        realSet.isMissingGuard.assert().isFalse()
    }

    @Test
    fun multiElementMapContainingSentinelKeyIsNotMissingGuard() {
        val realMap: Map<*, *> = mapOf(MissingGuard.STRING_VALUE to "v", "other" to "v2")
        realMap.isMissingGuard.assert().isFalse()
    }

    @Test
    fun singleElementSetSentinelIsMissingGuard() {
        val sentinelSet: Set<*> = setOf(MissingGuard.STRING_VALUE)
        sentinelSet.isMissingGuard.assert().isTrue()
    }

    @Test
    fun singleEntryMapSentinelIsMissingGuard() {
        val sentinelMap: Map<*, *> = mapOf(MissingGuard.STRING_VALUE to "1234567890")
        sentinelMap.isMissingGuard.assert().isTrue()
    }
}
