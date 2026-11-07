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
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.join.JoinKeyExtractor
import me.ahoo.cocache.api.join.JoinValue
import me.ahoo.cocache.api.join.JoinView
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.proxy.JoinCacheProxies
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class SimpleJoinCacheTest {
    private val backend = HookedBackend(InMemoryBackend())
    private val runtime = CacheRuntime.builder().backend(backend).build()

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

    @Test
    fun invalidatingBothRunsEvenWhenOneSideFails() {
        val firstFailure = IllegalStateException("first down")
        val joinFailure = IllegalStateException("join down")
        val failingFirst = io.mockk.mockk<Cache<String, String>> {
            io.mockk.every { name } returns "failing-first"
            io.mockk.every { invalidate(any()) } throws firstFailure
        }
        val failingJoin = io.mockk.mockk<Cache<String, String>>(relaxed = true) {
            io.mockk.every { invalidate(any()) } throws joinFailure
        }
        // 主缓存失败时关联缓存仍然失效
        val view = SimpleJoinCache("view", failingFirst, second, extractor)
        second.put("u1", "user")
        runCatching { view.invalidate("k", "u1") }.exceptionOrNull().assert().isSameAs(firstFailure)
        runtime.admin("second")?.clearLocal()
        // 两边都失败：主缓存的异常带上关联缓存的异常
        val both = SimpleJoinCache("both", failingFirst, failingJoin, extractor)
        val error = runCatching { both.invalidate("k", "u1") }.exceptionOrNull()
        error.assert().isSameAs(firstFailure)
        firstFailure.suppressed.toList().assert().contains(joinFailure)
        // 只有关联缓存失败
        val joinOnly = SimpleJoinCache("joinOnly", first, failingJoin, extractor)
        runCatching { joinOnly.invalidate("k", "u1") }.exceptionOrNull().assert().isSameAs(joinFailure)
    }

    /**
     * 只读的 [SimpleJoinView]：两侧可以是任何 `ReadableCache`，这里关联侧是 `Store`。
     */
    @Test
    fun joinViewReadsAnyReadableCache() {
        val profiles = runtime.create(storeSpec<String, String>("profiles") { codec = StringCodec })
        profiles.put("u1", "profile-u1")
        val view = SimpleJoinView("view", first, profiles, extractor)
        view["u1"].assert().isEqualTo(JoinValue("u1", "u1", "profile-u1"))
        view["u2"].assert().isEqualTo(JoinValue("u2", "u2", null))
        view.getAll(listOf("u1", "none-1")).assert().isEqualTo(mapOf("u1" to JoinValue("u1", "u1", "profile-u1")))
        view.firstCache.assert().isSameAs(first)
        view.joinCache.assert().isSameAs(profiles)
        view.toString().assert().isEqualTo("SimpleJoinView(name='view', firstCache=first, joinCache=profiles)")
        join.toString().assert().startsWith("SimpleJoinCache(")
    }

    interface SetsFirstView : JoinView<String, Set<String>, String, String>

    interface SetsSecondView : JoinView<String, String, String, Set<String>>

    private val sets = runtime.create(setStoreSpec<String, String>("sets") { elementCodec = StringCodec })
    private val values = runtime.create(storeSpec<String, String>("values") { codec = StringCodec })

    /**
     * 存在与否以组件缓存的 `lookup` 判定（#870）：`SetStore` 的 `get` 对不存在的 key 返回空集合，`getAll` 却不含它；
     * 主值是不存在的集合时，单条读取与批量读取一样没有组合结果，也不调用提取器。经声明接口的代理读取。
     */
    @Test
    fun aMissingFirstSetHasNoJoinedValue() {
        var extractions = 0
        val target = SimpleJoinView(
            "sets-first",
            sets,
            values,
            JoinKeyExtractor<Set<String>, String> {
                extractions++
                it.firstOrNull()
            }
        )
        val view = JoinCacheProxies.create(SetsFirstView::class.java, target)
        sets.lookup("missing").assert().isInstanceOf(Lookup.Absent::class.java)
        view.getAll(listOf("missing")).assert().isEmpty()
        view["missing"].assert().isNull()
        extractions.assert().isEqualTo(0)
    }

    /**
     * 关联值是不存在的集合时，单条读取与批量读取的 `secondValue` 都是 `null`（#870）。
     */
    @Test
    fun aMissingSecondSetIsNullForSingleAndBatchReads() {
        values.put("first", "missing")
        val target = SimpleJoinView("sets-second", values, sets, JoinKeyExtractor<String, String> { it })
        val view = JoinCacheProxies.create(SetsSecondView::class.java, target)
        val expected = JoinValue<String, String, Set<String>>("missing", "missing", null)
        sets.lookup("missing").assert().isInstanceOf(Lookup.Absent::class.java)
        view.getAll(listOf("first")).assert().isEqualTo(mapOf("first" to expected))
        view["first"].assert().isEqualTo(expected)
    }

    @Test
    fun presentSetsJoinTheSameForSingleAndBatchReads() {
        sets.put("group", setOf("relation"))
        values.put("relation", "value")
        val view = SimpleJoinView("present", sets, values, JoinKeyExtractor<Set<String>, String> { it.single() })
        val expected = JoinValue(setOf("relation"), "relation", "value")
        view["group"].assert().isEqualTo(expected)
        view.getAll(listOf("group")).assert().isEqualTo(mapOf("group" to expected))
    }

    /**
     * 空集合本身不是“不存在”：标量缓存中存储的空集合是存在的主值。
     */
    @Test
    fun anEmptySetStoredAsAScalarIsAPresentValue() {
        val scalarSets = runtime.create(
            storeSpec<String, Set<String>>("scalar-sets") {
                codec = object : Codec<Set<String>> {
                    override fun encode(value: Set<String>): ByteArray = value.joinToString(",").toByteArray()

                    override fun decode(bytes: ByteArray): Set<String> =
                        String(bytes).takeUnless { it.isEmpty() }?.split(",")?.toSet() ?: emptySet()
                }
            }
        )
        scalarSets.put("empty", emptySet())
        val view = SimpleJoinView("scalar-empty", scalarSets, values, JoinKeyExtractor<Set<String>, String> { null })
        val expected = JoinValue<Set<String>, String, String>(emptySet(), null, null)
        view["empty"].assert().isEqualTo(expected)
        view.getAll(listOf("empty")).assert().isEqualTo(mapOf("empty" to expected))
    }

    /**
     * 判定存在与否不吞掉故障：任一侧的读取没有答案（L1 故障，`Lookup.Unknown`）时，单条读取照常以 [CacheReadException] 失败。
     */
    @Test
    fun aFaultOnEitherSideStillFailsTheRead() {
        // 主值先进入 L2：关联侧的读取之后只有关联侧访问 L1
        first["u1"].assert().isEqualTo("u1")
        val firstFault = SimpleJoinView("first-fault", values, sets, JoinKeyExtractor<String, String> { it })
        val secondFault = SimpleJoinView("second-fault", first, values, extractor)
        backend.failAll = true
        try {
            runCatching { firstFault["k"] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
            runCatching { secondFault["u1"] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        } finally {
            backend.failAll = false
        }
    }
}
