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

package me.ahoo.cocache.core

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ForeignFormatExceptionTest {
    @Test
    fun messageTellsForeignContentFromADecodingFailure() {
        val foreign = ForeignFormatException("policy", "cocache:policy:p1", 0, null)
        foreign.message.assert().contains("format[0]: not written by CoCache 6, e.g. left by CoCache 5.x")
        foreign.message.assert().contains("CoCache5Migrator")
        foreign.cacheName.assert().isEqualTo("policy")

        val cause = IllegalArgumentException("bad json")
        val undecodable = ForeignFormatException("policy", "cocache:policy:p1", 1, cause)
        undecodable.message.assert().contains("format[1]: decoding failed")
        undecodable.cause.assert().isSameAs(cause)
    }
}
