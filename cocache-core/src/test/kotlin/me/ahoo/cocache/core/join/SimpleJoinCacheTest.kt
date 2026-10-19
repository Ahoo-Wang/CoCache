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

package me.ahoo.cocache.core.join

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.join.JoinKeyExtractor
import me.ahoo.cocache.api.join.JoinValue
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class SimpleJoinCacheTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()

    /**
     * 主值 = 关联 key：`none-*` 没有主值，`nojoin-*` 的主值没有关联，`orphan-*` 关联到不存在的关联值。
     */
    private val first: Cache<String, String> = runtime.create(
        cacheSpec<String, String>("first") {
            codec = StringCodec
            loader = CacheLoader { key -> key.takeUnless { it.startsWith("none-") } }
        }
    )
    private val second: Cache<String, String> = runtime.create(
        cacheSpec<String, String>("second") {
            codec = StringCodec
            loader = CacheLoader { key -> key.takeUnless { it.startsWith("orphan-") }?.let { "user-$it" } }
        }
    )
    private val extractor = JoinKeyExtractor<String, String> { value ->
        value.takeUnless { it.startsWith("nojoin-") }?.substringBefore('#')
    }
    private val join = SimpleJoinCache("join", first, second, extractor)

    @AfterEach
    fun destroy() = runtime.close()

    @Test
    fun getJoinsTheSecondValue() {
        join.name.assert().isEqualTo("join")
        join["u1"].assert().isEqualTo(JoinValue("u1", "u1", "user-u1"))
        join["none-1"].assert().isNull()
        join["nojoin-1"].assert().isEqualTo(JoinValue<String, String, String>("nojoin-1", null, null))
        join["orphan-1"].assert().isEqualTo(JoinValue<String, String, String>("orphan-1", "orphan-1", null))
        join.toString().assert().contains("join").contains("first").contains("second")
    }

    @Test
    fun getAllJoinsInTwoBatchReads() {
        val result = join.getAll(listOf("u1#a", "u1#b", "u2", "none-1", "nojoin-1", "orphan-1"))
        result.assert().isEqualTo(
            mapOf(
                "u1#a" to JoinValue("u1#a", "u1", "user-u1"),
                "u1#b" to JoinValue("u1#b", "u1", "user-u1"),
                "u2" to JoinValue("u2", "u2", "user-u2"),
                "nojoin-1" to JoinValue("nojoin-1", null, null),
                "orphan-1" to JoinValue("orphan-1", "orphan-1", null),
            )
        )
        join.getAll(emptyList()).assert().isEmpty()
    }

    @Test
    fun getAllReadsEachDistinctJoinKeyOnce() {
        val firstCache = mockk<Cache<String, String>>()
        val secondCache = mockk<Cache<String, String>>()
        every { firstCache.getAll(any()) } returns mapOf("a" to "k", "b" to "k", "c" to "nojoin-c")
        every { secondCache.getAll(listOf("k").toSet()) } returns mapOf("k" to "v")
        val cache = SimpleJoinCache("mocked", firstCache, secondCache, extractor)

        cache.getAll(listOf("a", "b", "c", "d")).assert().isEqualTo(
            mapOf(
                "a" to JoinValue("k", "k", "v"),
                "b" to JoinValue("k", "k", "v"),
                "c" to JoinValue<String, String, String>("nojoin-c", null, null),
            )
        )
        verify(exactly = 1) { secondCache.getAll(setOf("k")) }
    }

    @Test
    fun getAllSkipsTheSecondReadWithoutJoinKeys() {
        val firstCache = mockk<Cache<String, String>>()
        val secondCache = mockk<Cache<String, String>>()
        every { firstCache.getAll(any()) } returns mapOf("a" to "nojoin-a")
        val cache = SimpleJoinCache("mocked", firstCache, secondCache, extractor)

        cache.getAll(
            listOf("a")
        ).assert().isEqualTo(mapOf("a" to JoinValue<String, String, String>("nojoin-a", null, null)))
        verify(exactly = 0) { secondCache.getAll(any()) }
    }

    @Test
    fun invalidateTouchesOnlyTheFirstCacheUnlessGivenTheJoinKey() {
        val firstCache = mockk<Cache<String, String>>(relaxed = true)
        val secondCache = mockk<Cache<String, String>>(relaxed = true)
        val cache = SimpleJoinCache("mocked", firstCache, secondCache, extractor)

        cache.invalidate("a")
        verify(exactly = 1) { firstCache.invalidate("a") }
        verify(exactly = 0) { secondCache.invalidate(any()) }

        cache.invalidate("b", "k")
        verify(exactly = 1) { firstCache.invalidate("b") }
        verify(exactly = 1) { secondCache.invalidate("k") }
    }

    @Test
    fun invalidateReloadsFromTheSources() {
        join["u1"]
        second.put("u1", "changed")
        first.put("u1", "u1#x")
        join["u1"].assert().isEqualTo(JoinValue("u1#x", "u1", "changed"))
        join.invalidate("u1")
        join["u1"].assert().isEqualTo(JoinValue("u1", "u1", "changed"))
        join.invalidate("u1", "u1")
        join["u1"].assert().isEqualTo(JoinValue("u1", "u1", "user-u1"))
    }
}
