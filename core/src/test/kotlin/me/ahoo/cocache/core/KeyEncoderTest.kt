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
import java.util.UUID

class KeyEncoderTest {
    private data class UserKey(val tenant: String, val id: Long)

    private class Opaque

    @Test
    fun encodesKeysThatOverrideToString() {
        val encoder = KeyEncoder.ofToString<Any>()
        encoder.encode("plain").assert().isEqualTo("plain")
        encoder.encode(UserKey("t", 1)).assert().isEqualTo("UserKey(tenant=t, id=1)")
        encoder.encode(UserKey("t", 2)).assert().isEqualTo("UserKey(tenant=t, id=2)")
        encoder.encode(42L).assert().isEqualTo("42")
        val uuid = UUID.randomUUID()
        encoder.encode(uuid).assert().isEqualTo(uuid.toString())
    }

    @Test
    fun rejectsKeysWithObjectToString() {
        val encoder = KeyEncoder.ofToString<Any>()
        runCatching { encoder.encode(Opaque()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(Opaque::class.java.name)
        // 拒绝之后其它类型照常编码
        encoder.encode("ok").assert().isEqualTo("ok")
    }

    @Test
    fun specBuildersUseTheCheckedEncoder() {
        val spec = cacheSpec<Any, String>("checked") { codec = me.ahoo.cocache.core.codec.StringCodec }
        runCatching { spec.keyEncoder.encode(Opaque()) }.isFailure.assert().isTrue()
        val setSpec = setCacheSpec<Any, String>("checked-set") { elementCodec = me.ahoo.cocache.core.codec.StringCodec }
        runCatching { setSpec.keyEncoder.encode(Opaque()) }.isFailure.assert().isTrue()
    }
}
