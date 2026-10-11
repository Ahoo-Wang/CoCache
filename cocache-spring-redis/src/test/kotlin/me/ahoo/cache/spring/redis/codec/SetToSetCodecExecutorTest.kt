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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.*

internal class SetToSetCodecExecutorTest : CodecExecutorSpec<Set<String>>() {

    override fun createCodecExecutor(): CodecExecutor<Set<String>> = SetToSetCodecExecutor(stringRedisTemplate)

    override fun createCustomSentinelCodecExecutor(): CodecExecutor<Set<String>> {
        return SetToSetCodecExecutor(stringRedisTemplate, CUSTOM_SENTINEL)
    }

    override fun createCacheValue(): Set<String> = setOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())

    override fun createSingleNonSentinelValue(): Set<String> = setOf(UUID.randomUUID().toString())

    @Test
    fun emptySetEvictsKey() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.forever(createCacheValue()))
        codecExecutor.executeAndEncode(key, CacheValue.of(emptySet(), TtlAt.at(60)))
        stringRedisTemplate.hasKey(key).assert().isFalse()
    }

    private companion object {
        const val SENTINEL = AbstractCodecExecutor.DEFAULT_MISSING_GUARD_SENTINEL
    }

    private val setExecutor: SetToSetCodecExecutor
        get() = codecExecutor as SetToSetCodecExecutor

    private fun members(key: String): Set<String> = stringRedisTemplate.opsForSet().members(key).orEmpty()

    @Test
    fun addOntoAbsentKey() {
        val key = newKey()
        setExecutor.executeAddElements(key, listOf("a", "b"), TtlAt.FOREVER)
        members(key).assert().isEqualTo(setOf("a", "b"))
        stringRedisTemplate.getExpire(key).assert().isEqualTo(-1L)
    }

    @Test
    fun addOntoMissingGuardClearsSentinelAndPersists() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.missing(TtlAt.at(60)))
        stringRedisTemplate.getExpire(key).assert().isBetween(58, 60)
        setExecutor.executeAddElements(key, listOf("a"), TtlAt.FOREVER)
        members(key).assert().isEqualTo(setOf("a"))
        stringRedisTemplate.getExpire(key).assert().isEqualTo(-1L)
    }

    @Test
    fun addAppliesFiniteTtl() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.forever(setOf("a")))
        setExecutor.executeAddElements(key, listOf("b"), TtlAt.at(120))
        members(key).assert().isEqualTo(setOf("a", "b"))
        stringRedisTemplate.getExpire(key).assert().isBetween(118, 120)
    }

    @Test
    fun addForeverPersistsExpiringSet() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.of(setOf("a"), TtlAt.at(60)))
        setExecutor.executeAddElements(key, listOf("b"), TtlAt.FOREVER)
        stringRedisTemplate.getExpire(key).assert().isEqualTo(-1L)
    }

    @Test
    fun addKeepsSetContainingSentinelAmongOthers() {
        val key = newKey()
        // 非负缓存：哨兵只是多个成员之一
        codecExecutor.executeAndEncode(
            key,
            CacheValue.forever(setOf(SENTINEL, "a"))
        )
        setExecutor.executeAddElements(key, listOf("b"), TtlAt.FOREVER)
        members(key).assert().isEqualTo(setOf(SENTINEL, "a", "b"))
    }

    @Test
    fun addRejectsSentinelElement() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            setExecutor.executeAddElements(
                newKey(),
                listOf(SENTINEL),
                TtlAt.FOREVER
            )
        }
    }

    @Test
    fun customSentinelIsRespected() {
        val key = newKey()
        val executor = createCustomSentinelCodecExecutor() as SetToSetCodecExecutor
        executor.executeAndEncode(key, CacheValue.missing(TtlAt.at(60)))
        executor.executeAddElements(key, listOf(SENTINEL), TtlAt.FOREVER)
        members(key).assert().isEqualTo(setOf(SENTINEL))
    }

    @Test
    fun removeAppliesTtl() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.forever(setOf("a", "b")))
        setExecutor.executeRemoveElements(key, listOf("a"), TtlAt.at(120))
        members(key).assert().isEqualTo(setOf("b"))
        stringRedisTemplate.getExpire(key).assert().isBetween(118, 120)
    }

    @Test
    fun removeLastElementDeletesKey() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.forever(setOf("a")))
        setExecutor.executeRemoveElements(key, listOf("a"), TtlAt.FOREVER)
        stringRedisTemplate.hasKey(key).assert().isFalse()
        codecExecutor.executeAndDecode(key).assert().isNull()
    }

    @Test
    fun removeFromAbsentKeyCreatesNothing() {
        val key = newKey()
        setExecutor.executeRemoveElements(key, listOf("a"), TtlAt.FOREVER)
        stringRedisTemplate.hasKey(key).assert().isFalse()
    }

    @Test
    fun removeKeepsMissingGuardAndItsTtl() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.missing(TtlAt.at(60)))
        setExecutor.executeRemoveElements(
            key,
            listOf(SENTINEL, "a"),
            TtlAt.FOREVER
        )
        codecExecutor.executeAndDecode(key)!!.isMissing.assert().isTrue()
        stringRedisTemplate.getExpire(key).assert().isBetween(58, 60)
    }

    @Test
    fun emptyElementsAreNoOp() {
        val key = newKey()
        setExecutor.executeAddElements(key, emptyList(), TtlAt.FOREVER)
        setExecutor.executeRemoveElements(key, emptyList(), TtlAt.FOREVER)
        stringRedisTemplate.hasKey(key).assert().isFalse()
    }

    @Test
    fun fillWritesAbsentKey() {
        val key = newKey()
        setExecutor.executeFill(key, CacheValue.of(setOf("a"), TtlAt.at(60))).assert().isTrue()
        members(key).assert().isEqualTo(setOf("a"))
        stringRedisTemplate.getExpire(key).assert().isBetween(58, 60)
    }

    @Test
    fun fillReplacesMissingGuard() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.missing(TtlAt.at(60)))
        setExecutor.executeFill(key, CacheValue.forever(setOf("a"))).assert().isTrue()
        members(key).assert().isEqualTo(setOf("a"))
        stringRedisTemplate.getExpire(key).assert().isEqualTo(-1L)
    }

    @Test
    fun fillNeverOverwritesSet() {
        val key = newKey()
        setExecutor.executeAddElements(key, listOf("a"), TtlAt.FOREVER)
        setExecutor.executeFill(key, CacheValue.missing(TtlAt.at(60))).assert().isFalse()
        setExecutor.executeFill(key, CacheValue.forever(setOf("b"))).assert().isFalse()
        members(key).assert().isEqualTo(setOf("a"))
        stringRedisTemplate.getExpire(key).assert().isEqualTo(-1L)
    }

    @Test
    fun fillSkipsExpiredValue() {
        val key = newKey()
        setExecutor.executeFill(key, CacheValue.missing(TtlAt.currentTime() - 1)).assert().isFalse()
        stringRedisTemplate.hasKey(key).assert().isFalse()
    }

    @Test
    fun evictIfUnchanged() {
        val key = newKey()
        val filled = CacheValue.of(setOf("a", "b"), TtlAt.at(60))
        setExecutor.executeFill(key, filled)
        setExecutor.executeAddElements(key, listOf("c"), TtlAt.FOREVER)
        setExecutor.executeEvictIfUnchanged(key, filled)
        members(key).assert().isEqualTo(setOf("a", "b", "c"))

        setExecutor.executeRemoveElements(key, listOf("c"), TtlAt.FOREVER)
        setExecutor.executeEvictIfUnchanged(key, filled)
        stringRedisTemplate.hasKey(key).assert().isFalse()
    }

    @Test
    fun evictIfUnchangedMissingGuard() {
        val key = newKey()
        val missing = CacheValue.missing<Set<String>>(TtlAt.at(60))
        setExecutor.executeFill(key, missing)
        setExecutor.executeEvictIfUnchanged(key, missing)
        stringRedisTemplate.hasKey(key).assert().isFalse()

        setExecutor.executeFill(key, missing)
        setExecutor.executeAddElements(key, listOf("a"), TtlAt.FOREVER)
        setExecutor.executeEvictIfUnchanged(key, missing)
        members(key).assert().isEqualTo(setOf("a"))
    }
}
