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
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.spring.redis.codec.AbstractCodecExecutor
import me.ahoo.cache.test.SetElementsCacheSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException

/**
 * 每个实例各自的 [RedisSetDistributedCache] 客户端，共享同一 Redis 与 Pub/Sub 失效通道。
 */
class RedisSetElementsCacheTest : SetElementsCacheSpec() {
    private lateinit var redis: RedisTestSupport

    @BeforeEach
    override fun setup() {
        redis = RedisTestSupport()
        super.setup()
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
        redis.close()
    }

    override fun createDistributedCache(): DistributedCache<Set<String>> = RedisSetDistributedCache(redis.redisTemplate)

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = redis.newEventBus()

    override fun createCacheName(): String = "RedisSetElementsCacheTest"

    /**
     * 回源写入的负缓存（哨兵 + missingTtl）上加入元素：集合不含哨兵，且按 FOREVER 策略移除到期时间。
     */
    @Test
    fun addOntoNegativeCacheLeavesCleanPersistentSet() {
        val key = newKey()
        val cacheKey = keyConverter.toStringKey(key)
        currentCache[key].assert().isNull()
        redis.redisTemplate.opsForSet().members(cacheKey)
            .assert().isEqualTo(setOf(AbstractCodecExecutor.DEFAULT_MISSING_GUARD_SENTINEL))
        redis.redisTemplate.getExpire(cacheKey).assert().isGreaterThan(0)

        currentCache.elements.addElements(key, listOf("policy-1"))

        redis.redisTemplate.opsForSet().members(cacheKey).assert().isEqualTo(setOf("policy-1"))
        redis.redisTemplate.getExpire(cacheKey).assert().isEqualTo(-1L)
        otherCache[key].assert().isEqualTo(setOf("policy-1"))
        otherCache.getTtlAt(key).assert().isEqualTo(TtlAt.FOREVER)
    }

    @Test
    fun strictFailureRethrows() {
        val wrongType = newKey()
        val cacheKey = keyConverter.toStringKey(wrongType)
        redis.redisTemplate.opsForValue().set(cacheKey, "string")
        try {
            val strict = RedisSetDistributedCache(redis.redisTemplate, strictFailure = true)
            Assertions.assertThrows(DataAccessException::class.java) {
                strict.addElements(cacheKey, listOf("a"), TtlAt.FOREVER)
            }
            // 默认降级：仅告警，条件写回视为未写入
            currentCache.elements.addElements(wrongType, listOf("a"))
            currentCache.elements.removeElements(wrongType, listOf("a"))
            val lenient = RedisSetDistributedCache(redis.redisTemplate)
            lenient.fillCache(cacheKey, CacheValue.forever(setOf("a"))).assert().isFalse()
            lenient.evictIfUnchanged(cacheKey, CacheValue.forever(setOf("a")))
            redis.redisTemplate.opsForValue().get(cacheKey).assert().isEqualTo("string")
        } finally {
            redis.redisTemplate.delete(cacheKey)
        }
    }
}
