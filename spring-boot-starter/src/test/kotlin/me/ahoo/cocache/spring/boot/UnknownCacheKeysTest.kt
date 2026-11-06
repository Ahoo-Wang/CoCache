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

package me.ahoo.cocache.spring.boot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * [UnknownCacheKeys] 的判定本身（启动时的行为见 `ThreeLayerConfigTest.unknownPerCacheKeysFailOrWarn`）。
 */
class UnknownCacheKeysTest {
    private fun environment(vararg entries: Pair<String, Any>) = StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("test", mapOf(*entries)))
    }

    @Test
    fun listsTheKeysThatBindToNothing() {
        UnknownCacheKeys.of(environment()).assert().isEmpty()
        UnknownCacheKeys.of(
            environment(
                "cocache.caches.user.ttl" to "1m",
                "cocache.caches.user.ttl-forever" to "true",
                "cocache.caches.order.max-concurrent" to "4",
            )
        ).assert().containsExactly("cocache.caches.order.max-concurrent", "cocache.caches.user.ttl-forever")
    }

    /**
     * 不认识的键之外的绑定错误原样抛出（启动时 `CoCacheProperties` 先绑定同样的值，已经因它失败）。
     */
    @Test
    fun otherBindErrorsAreRethrown() {
        runCatching { UnknownCacheKeys.of(environment("cocache.caches.user.invalidate-failure" to "sometimes")) }
            .exceptionOrNull().assert().isInstanceOf(BindException::class.java)
    }
}
