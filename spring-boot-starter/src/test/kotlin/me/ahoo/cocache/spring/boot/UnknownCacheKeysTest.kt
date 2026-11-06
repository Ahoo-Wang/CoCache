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
import org.springframework.boot.context.properties.bind.BindHandler
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * [UnknownCacheKeys] 的判定本身（启动时的行为见 `ThreeLayerConfigTest.unknownPerCacheKeysFailOrWarn`）：它接在正式绑定的处理器链上，
 * 只记录，不改变绑定。
 */
class UnknownCacheKeysTest {
    private fun bind(
        advisor: UnknownCacheKeys,
        handler: BindHandler,
        vararg entries: Pair<String, String>
    ): CoCacheProperties =
        Binder(MapConfigurationPropertySource(mapOf(*entries)))
            .bindOrCreate(CoCacheProperties.PREFIX, Bindable.of(CoCacheProperties::class.java), advisor.apply(handler))

    @Test
    fun recordsOnlyThePerCacheKeysThatBindToNothing() {
        val advisor = UnknownCacheKeys()
        val properties = bind(
            advisor,
            BindHandler.DEFAULT,
            "cocache.caches.user.ttl" to "1m",
            "cocache.caches.user.ttl-forever" to "true",
            "cocache.caches.order.max-concurrent" to "4",
            "cocache.defaults.cache.ttl-forever" to "true",
        )
        properties.caches.getValue("user").ttl.assert().isEqualTo("1m")
        advisor.keys.assert().containsExactly("cocache.caches.order.max-concurrent", "cocache.caches.user.ttl-forever")

        bind(advisor, BindHandler.DEFAULT, "cocache.caches.user.ttl" to "1m")
        advisor.keys.assert().withFailMessage { "each binding of cocache replaces the record" }.isEmpty()
    }

    /**
     * 外层处理器的回调照常执行：这里外层是 Spring Boot 的 [NoUnboundElementsBindHandler]（`ignoreUnknownFields = false` 时的处理器），
     * 它在 `onFinish` 中照样发现不认识的键并让绑定失败。
     */
    @Test
    fun theOuterHandlersStillRun() {
        val advisor = UnknownCacheKeys()
        val failure = runCatching {
            bind(advisor, NoUnboundElementsBindHandler(BindHandler.DEFAULT), "cocache.redis.heartbeat-period" to "5s")
        }.exceptionOrNull()
        failure.assert().isNotNull()
        advisor.keys.assert().isEmpty()
    }
}
