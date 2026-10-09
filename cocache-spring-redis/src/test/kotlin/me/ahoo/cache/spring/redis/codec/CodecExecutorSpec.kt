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

package me.ahoo.cache.spring.redis.codec

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.spring.redis.RedisTestSupport
import me.ahoo.test.asserts.assert
import org.assertj.core.data.Offset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.*

abstract class CodecExecutorSpec<V> {
    private lateinit var redis: RedisTestSupport
    protected val stringRedisTemplate: StringRedisTemplate
        get() = redis.redisTemplate
    protected lateinit var codecExecutor: CodecExecutor<V>

    abstract fun createCodecExecutor(): CodecExecutor<V>

    /** 自定义哨兵执行器：回归每个 codec 的哨兵属性化。 */
    abstract fun createCustomSentinelCodecExecutor(): CodecExecutor<V>
    abstract fun createCacheValue(): V

    /** 单元素且不等于哨兵的业务值（覆盖 size==1 && != sentinel 分支）。 */
    protected open fun createSingleNonSentinelValue(): V = createCacheValue()

    companion object {
        const val CUSTOM_SENTINEL = "\u0000cocache-test:nil"
    }

    protected fun newKey(): String = "codec-test:" + UUID.randomUUID()

    @BeforeEach
    fun setup() {
        redis = RedisTestSupport()
        codecExecutor = createCodecExecutor()
    }

    @AfterEach
    fun destroy() {
        redis.close()
    }

    @Test
    fun roundTripForever() {
        val key = newKey()
        val value = CacheValue.forever(createCacheValue())
        codecExecutor.executeAndEncode(key, value)
        codecExecutor.executeAndDecode(key).assert().isEqualTo(value)
        stringRedisTemplate.getExpire(key).assert().isEqualTo(-1L)
    }

    @Test
    fun roundTripWithTtlAt() {
        val key = newKey()
        val value = CacheValue.of(createCacheValue(), TtlAt.at(60))
        codecExecutor.executeAndEncode(key, value)
        val actual = requireNotNull(codecExecutor.executeAndDecode(key))
        actual.value.assert().isEqualTo(value.value)
        actual.ttlAt.assert().isCloseTo(value.ttlAt, Offset.offset(1))
        stringRedisTemplate.getExpire(key).assert().isBetween(58, 60)
    }

    @Test
    fun roundTripMissingForever() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.missing())
        codecExecutor.executeAndDecode(key).assert().isEqualTo(CacheValue.missing<V>())
    }

    @Test
    fun roundTripMissingWithTtlAt() {
        val key = newKey()
        val missing = CacheValue.missing<V>(TtlAt.at(100))
        codecExecutor.executeAndEncode(key, missing)
        val actual = requireNotNull(codecExecutor.executeAndDecode(key))
        actual.isMissing.assert().isTrue()
        actual.ttlAt.assert().isCloseTo(missing.ttlAt, Offset.offset(1))
    }

    /**
     * 回归：不存在的 key 必须是未命中（触发回源），不得推断为负缓存（否则真实数据会被长期读成不存在）。
     */
    @Test
    fun absentKeyIsMiss() {
        codecExecutor.executeAndDecode(newKey()).assert().isNull()
    }

    @Test
    fun expiredValueEvictsKey() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.forever(createCacheValue()))
        codecExecutor.executeAndEncode(key, CacheValue.of(createCacheValue(), TtlAt.at(-5)))
        stringRedisTemplate.hasKey(key).assert().isFalse()
    }

    /**
     * 剩余 TTL 不足 1 秒时钳为 1 秒，而不是写成永不过期或被 Redis 拒绝。
     */
    @Test
    fun subSecondTtlIsClampedToOneSecond() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.of(createCacheValue(), TtlAt.at(1)))
        stringRedisTemplate.getExpire(key).assert().isBetween(0, 1)
    }

    @Test
    fun singleEntryNonSentinelValueIsNotMissing() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.forever(createSingleNonSentinelValue()))
        requireNotNull(codecExecutor.executeAndDecode(key)).isMissing.assert().isFalse()
    }

    @Test
    fun customSentinelRoundTrip() {
        val executor = createCustomSentinelCodecExecutor()
        val key = newKey()
        executor.executeAndEncode(key, CacheValue.missing(TtlAt.at(100)))
        requireNotNull(executor.executeAndDecode(key)).isMissing.assert().isTrue()
        // 默认哨兵的执行器不识别自定义哨兵
        codecExecutor.executeAndDecode(key)?.isMissing.assert().isNotEqualTo(true)
    }
}
