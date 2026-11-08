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

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 权威缓存记下本实例观察到的按条目寿命（[CacheAdmin.observedEntryTtl]，写入或读到）：驱逐检查据此把策略永不过期、却按条目给出寿命写入的权威缓存
 * 计入会过期的缓存（CoSec 在 rc.1 上试用发现：吊销表 `put(key, value, ttl)` 写出的 key 带过期时间，`volatile-*` 曾被判为安全）。
 */
class ObservedEntryTtlTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
    private val tenMinutes = Duration.ofMinutes(10)

    @AfterEach
    fun close() = runtime.close()

    private fun observedEntryTtl(name: String): Duration? = requireNotNull(runtime.admin(name)).observedEntryTtl

    @Test
    fun aStoreRecordsTheFirstTtlItWasWrittenWith() {
        val store = runtime.create(storeSpec<String, String>("revoked") { codec = StringCodec })
        store.put("plain", "v")
        observedEntryTtl("revoked").assert().isNull()
        store.put("jti-1", "revoked", tenMinutes)
        store.put("jti-2", "revoked", Duration.ofMinutes(1))
        observedEntryTtl("revoked").assert().isEqualTo(tenMinutes)
    }

    @Test
    fun everyPerCallWriteOfAStoreIsRecorded() {
        val batch = runtime.create(storeSpec<String, String>("batch") { codec = StringCodec })
        batch.putAll(mapOf("a" to "1"), tenMinutes)
        observedEntryTtl("batch").assert().isEqualTo(tenMinutes)

        val conditional = runtime.create(storeSpec<String, String>("conditional") { codec = StringCodec })
        conditional.putIfMatch("a", null, "1", tenMinutes)
        observedEntryTtl("conditional").assert().isEqualTo(tenMinutes)

        val async = runtime.create(storeSpec<String, String>("async") { codec = StringCodec })
        async.async().put("a", "1", tenMinutes).join()
        observedEntryTtl("async").assert().isEqualTo(tenMinutes)

        val suspending = runtime.create(storeSpec<String, String>("suspending") { codec = StringCodec })
        runBlocking { suspending.suspending().put("a", "1", tenMinutes) }
        observedEntryTtl("suspending").assert().isEqualTo(tenMinutes)
    }

    @Test
    fun aStoreWithTtlOfRecordsItsResult() {
        val store = runtime.create(
            storeSpec<String, String>("sessions") {
                codec = StringCodec
                ttlOf = TtlFunction { _, _ -> tenMinutes }
            }
        )
        observedEntryTtl("sessions").assert().isNull()
        store.put("s", "v")
        observedEntryTtl("sessions").assert().isEqualTo(tenMinutes)
    }

    /**
     * 墓碑的寿命不是值的寿命：墓碑被驱逐只让迟到的旧版本提前可写，不计入（同驱逐检查对墓碑寿命的规则）。
     */
    @Test
    fun aVersionedStoreRecordsValuesButNotTombstones() {
        val store = runtime.create(versionedStoreSpec<String, String>("ledger") { codec = StringCodec })
        store.deleteIfNewer("a", 1, tenMinutes)
        observedEntryTtl("ledger").assert().isNull()
        store.putIfNewer("b", "v", 1, tenMinutes)
        observedEntryTtl("ledger").assert().isEqualTo(tenMinutes)
    }

    @Test
    fun aSetStoreRecordsPerCallTtlButNotAnEmptySet() {
        val sets = runtime.create(setStoreSpec<String, String>("tags") { elementCodec = StringCodec })
        sets.put("empty", emptySet(), tenMinutes)
        sets.putAll(mapOf("empty" to emptySet()), tenMinutes)
        sets.put("policy", setOf("x"))
        sets.add("policy", listOf("y"))
        observedEntryTtl("tags").assert().isNull()
        sets.put("a", setOf("x"), tenMinutes)
        observedEntryTtl("tags").assert().isEqualTo(tenMinutes)

        val batch = runtime.create(setStoreSpec<String, String>("batch-tags") { elementCodec = StringCodec })
        batch.putAll(mapOf("a" to setOf("x")), tenMinutes)
        observedEntryTtl("batch-tags").assert().isEqualTo(tenMinutes)

        val views = runtime.create(setStoreSpec<String, String>("view-tags") { elementCodec = StringCodec })
        views.async().put("a", setOf("x"), tenMinutes).join()
        views.async().putAll(mapOf("b" to setOf("x")), Duration.ofMinutes(1)).join()
        runBlocking {
            views.suspending().put("c", setOf("x"), Duration.ofMinutes(2))
            views.suspending().putAll(mapOf("d" to setOf("x")), Duration.ofMinutes(3))
        }
        observedEntryTtl("view-tags").assert().isEqualTo(tenMinutes)
    }

    /**
     * 两个实例共享一个后端：[writer] 写入，[reader] 只读。返回只读实例中 [name] 的记录。
     */
    private fun readerSees(
        name: String,
        writer: (CacheRuntime) -> Unit,
        reader: (CacheRuntime) -> Unit,
    ): Duration? = InMemoryBackend().use { backend ->
        CacheRuntime.builder().backend(backend).build().use { writing ->
            CacheRuntime.builder().backend(backend).build().use { reading ->
                writer(writing)
                reader(reading)
                requireNotNull(reading.admin(name)).observedEntryTtl
            }
        }
    }

    private fun stores(runtime: CacheRuntime): Store<String, String> =
        runtime.create(storeSpec<String, String>("revoked") { codec = StringCodec })

    private fun ledger(runtime: CacheRuntime): VersionedStore<String, String> =
        runtime.create(versionedStoreSpec<String, String>("ledger") { codec = StringCodec })

    private fun tags(runtime: CacheRuntime): SetStore<String, String> =
        runtime.create(setStoreSpec<String, String>("tags") { elementCodec = StringCodec })

    /**
     * 只读的实例从 L1 读到带剩余寿命的值时同样记下（CoSec 在 rc.1 上试用指出：数据面实例只读吊销表，从不写入）：`get`、`lookup`、
     * `getAll` 与 `lookupRemote` 都读到 L1；条件读取不带寿命，不计。
     */
    @Test
    fun anInstanceThatOnlyReadsRecordsTheRemainingTtl() {
        val write: (CacheRuntime) -> Unit = { stores(it).put("jti-1", "revoked", tenMinutes) }
        listOf<(CacheRuntime) -> Unit>(
            { stores(it)["jti-1"] },
            { stores(it).lookup("jti-1") },
            { stores(it).lookupRemote("jti-1") },
            { stores(it).getAll(listOf("jti-1")) },
        ).forEach { read ->
            val seen = requireNotNull(readerSees("revoked", write, read))
            seen.assert().isPositive().isLessThanOrEqualTo(tenMinutes)
        }
        val conditional: (CacheRuntime) -> Unit = { stores(it).getIfChanged("jti-1", null) }
        readerSees("revoked", write, conditional).assert().isNull()
        // 读到永不过期的值、读不到值：不记
        val writeForever: (CacheRuntime) -> Unit = { stores(it).put("jti-1", "revoked") }
        val get: (CacheRuntime) -> Unit = { stores(it)["jti-1"] }
        readerSees("revoked", writeForever, get).assert().isNull()
        val writeOther: (CacheRuntime) -> Unit = { stores(it).put("other", "x", tenMinutes) }
        val lookupRemote: (CacheRuntime) -> Unit = { stores(it).lookupRemote("jti-1") }
        readerSees("revoked", writeOther, lookupRemote).assert().isNull()
    }

    /**
     * 只读的 `VersionedStore` 实例：读到带寿命的值时记下，读到墓碑时不记（墓碑的寿命不计）；`SetStore` 同样记下读到的剩余寿命。
     */
    @Test
    fun readersOfVersionedAndSetStoresRecordValuesButNotTombstones() {
        val tombstone: (CacheRuntime) -> Unit = { ledger(it).deleteIfNewer("a", 1, tenMinutes) }
        val readTombstone: (CacheRuntime) -> Unit = {
            val reading = ledger(it)
            reading["a"]
            reading.lookupRemote("a")
        }
        readerSees("ledger", tombstone, readTombstone).assert().isNull()
        val value: (CacheRuntime) -> Unit = { ledger(it).putIfNewer("a", "v", 1, tenMinutes) }
        val readValue: (CacheRuntime) -> Unit = { ledger(it)["a"] }
        readerSees("ledger", value, readValue).assert().isNotNull()

        val set: (CacheRuntime) -> Unit = { tags(it).put("a", setOf("x"), tenMinutes) }
        val foreverSet: (CacheRuntime) -> Unit = { tags(it).put("a", setOf("x")) }
        val readSet: (CacheRuntime) -> Unit = { tags(it)["a"] }
        readerSees("tags", set, readSet).assert().isNotNull()
        readerSees("tags", foreverSet, readSet).assert().isNull()
    }

    /**
     * 策略本身有限的权威缓存已经按策略计入：读到的剩余寿命不再记（记录只补策略看不到的）。
     */
    @Test
    fun aStoreWithAFinitePolicyTtlDoesNotRecordReads() {
        val spec = storeSpec<String, String>("expiring") {
            codec = StringCodec
            policy { ttl = tenMinutes }
        }
        val write: (CacheRuntime) -> Unit = { it.create(spec).put("a", "v") }
        val read: (CacheRuntime) -> Unit = {
            val reading = it.create(spec)
            reading["a"]
            reading.lookupRemote("a")
        }
        readerSees("expiring", write, read).assert().isNull()
    }

    /**
     * 运行时之外的实现（如测试替身）不必实现它：默认没有记录。
     */
    @Test
    fun anAdminOutsideTheRuntimeRecordsNothingByDefault() {
        val admin = mockk<CacheAdmin>()
        every { admin.observedEntryTtl } answers { callOriginal() }
        admin.observedEntryTtl.assert().isNull()
    }

    /**
     * 派生缓存的副本能从数据源重建，驱逐只是一次未命中：不记录。
     */
    @Test
    fun aDerivedCacheRecordsNothing() {
        val cache = runtime.create(
            cacheSpec<String, String>("views") {
                codec = StringCodec
                externallySourced()
            }
        )
        cache.put("a", "1", tenMinutes)
        observedEntryTtl("views").assert().isNull()
    }
}
