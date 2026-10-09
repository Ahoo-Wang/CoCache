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
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor
import me.ahoo.cache.test.DistributedCacheSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.data.Offset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.*

internal class RedisDistributedCachingTest : DistributedCacheSpec<String>() {
    private val redis = RedisTestSupport()

    override fun createCacheStore(): DistributedCache<String> {
        return RedisDistributedCache(redis.redisTemplate, StringToStringCodecExecutor(redis.redisTemplate))
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }

    @AfterEach
    fun destroy() {
        redis.close()
    }

    /**
     * ttlAt 经 Redis 剩余 TTL 重建，写读跨越秒边界时有 ±1 秒漂移。
     */
    @Test
    override fun setWithTtlAt() {
        val (key, value) = createCacheEntry()
        val cacheValue = CacheValue.of(value, TtlAt.at(10))
        cacheStore.setCache(key, cacheValue)
        val actual = requireNotNull(cacheStore.getCache(key))
        actual.value.assert().isEqualTo(value)
        actual.ttlAt.assert().isCloseTo(cacheValue.ttlAt, Offset.offset(1))
    }

    @Test
    override fun setMissingWithTtlAt() {
        val (key, _) = createCacheEntry()
        val missing = CacheValue.missing<String>(TtlAt.at(10))
        cacheStore.setCache(key, missing)
        val actual = requireNotNull(cacheStore.getCache(key))
        actual.isMissing.assert().isTrue()
        actual.ttlAt.assert().isCloseTo(missing.ttlAt, Offset.offset(1))
    }
}
