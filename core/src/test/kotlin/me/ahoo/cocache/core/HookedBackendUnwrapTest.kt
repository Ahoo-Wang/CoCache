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

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.unwrap
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `HookedBackend` 经 [CoherenceBackend.unwrap] 交出被包装的后端：需要具体后端的检查（如 Redis 的驱逐检查）看穿这层包装，
 * 多层包装同样逐层看穿。
 */
class HookedBackendUnwrapTest {
    @Test
    fun unwrapsToTheDelegate() {
        InMemoryBackend().use { memory ->
            val hooked = HookedBackend(memory)
            hooked.unwrap(InMemoryBackend::class.java).assert().isSameAs(memory)
            hooked.unwrap(HookedBackend::class.java).assert().isSameAs(hooked)
            hooked.unwrap(CoherenceBackend::class.java).assert().isSameAs(hooked)
            hooked.unwrap(String::class.java).assert().isNull()
            val twice = HookedBackend(hooked)
            twice.unwrap(InMemoryBackend::class.java).assert().isSameAs(memory)
            CacheRuntime.builder().backend(twice).build().use { runtime ->
                runtime.backend.unwrap(InMemoryBackend::class.java).assert().isSameAs(memory)
            }
            twice.unwrap<InMemoryBackend>().assert().isSameAs(memory)
        }
    }

    /**
     * Kotlin 的类委托（`by delegate`）自动转交 `unwrap`：看得穿被包装者，但按包装自身的类型取不到它（需要时显式覆盖）。
     */
    @Test
    fun classDelegationForwardsToTheDelegate() {
        InMemoryBackend().use { memory ->
            val delegating = StrippedBackend(memory)
            delegating.unwrap<InMemoryBackend>().assert().isSameAs(memory)
            delegating.unwrap<StrippedBackend>().assert().isNull()
        }
    }
}
