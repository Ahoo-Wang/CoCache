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

package me.ahoo.cache.spring.redis

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.spring.redis.codec.Json
import me.ahoo.cache.spring.redis.codec.Model
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * L1 中的损坏载荷按未命中处理，回源后的写回覆盖它（读取本身不修改 L1）。
 */
internal class CorruptedPayloadSelfHealTest {
    private val redis = RedisTestSupport()
    private val prefix = "corrupted-test:${UUID.randomUUID()}:"
    private val cache = DefaultCoherentCacheFactory(NoOpCacheEvictedEventBus).create(
        CoherentCacheConfiguration(
            cacheName = "corrupted-test",
            clientId = UUID.randomUUID().toString(),
            keyConverter = ToStringKeyConverter<String>(prefix),
            distributedCache = RedisDistributedCache(
                redis.redisTemplate,
                ObjectToJsonCodecExecutor(Model::class.java, redis.redisTemplate, Json),
            ),
            clientSideCache = MapClientSideCache(),
            cacheSource = { key -> CacheValue.forever(Model(key)) },
        ),
    )

    @AfterEach
    fun tearDown() {
        cache.close()
        redis.close()
    }

    @Test
    fun reloadOverwritesCorruptedPayload() {
        val key = UUID.randomUUID().toString()
        redis.redisTemplate.opsForValue()[prefix + key] = "{invalid-json"

        cache[key].assert().isEqualTo(Model(key))
        Json.readValue(
            redis.redisTemplate.opsForValue()[prefix + key],
            Model::class.java
        ).assert().isEqualTo(Model(key))
    }
}
