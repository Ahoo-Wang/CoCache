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

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

class ExternalInvalidatorTest {
    private val database = ConcurrentHashMap<String, String>()

    private fun spec() = cacheSpec<String, String>("user") {
        codec = StringCodec
        loader = CacheLoader { database[it] }
    }

    @Test
    fun invalidatesRemoteAndEveryNodeLocalCopy() {
        val memory = InMemoryBackend()
        CacheRuntime.builder().backend(memory).build().use { node ->
            val cache = node.create(spec())
            database["1"] = "v0"
            cache["1"].assert().isEqualTo("v0")
            database["1"] = "v1"
            ExternalInvalidator(memory).use { invalidator ->
                invalidator.invalidate("user", "1")
            }
            memory.store("user").read("cocache:user:1", null, null).assert().isEqualTo(RemoteRead.Miss(false))
            awaitCondition { cache["1"] == "v1" }
        }
    }

    @Test
    fun nonAtomicBackendPublishesThroughTheChannel() {
        val backend = HookedBackend(InMemoryBackend()).apply { atomicBroadcastOverride = false }
        CacheRuntime.builder().backend(backend).build().use { node ->
            val cache = node.create(spec())
            database["1"] = "v0"
            cache["1"]
            database["1"] = "v1"
            val invalidator = ExternalInvalidator(backend)
            invalidator.invalidate("user", listOf("1", "1"))
            awaitCondition { cache["1"] == "v1" }
            // 自定义前缀：删除的是对应的存储 key
            invalidator.invalidate("user", listOf("2"), keyPrefix = "app:")
            invalidator.close()
        }
    }

    @Test
    fun emptyKeysDoNothing() {
        val backend = HookedBackend(InMemoryBackend())
        ExternalInvalidator(backend).use { it.invalidate("user", emptyList()) }
        backend.reads.get().assert().isZero()
    }
}
