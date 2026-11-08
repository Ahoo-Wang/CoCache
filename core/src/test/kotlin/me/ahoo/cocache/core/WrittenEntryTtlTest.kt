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

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 权威缓存记下本实例写入过的按条目寿命（[CacheAdmin.writtenEntryTtl]）：驱逐检查据此把策略永不过期、却按条目给出寿命写入的权威缓存
 * 计入会过期的缓存（CoSec 在 rc.1 上试用发现：吊销表 `put(key, value, ttl)` 写出的 key 带过期时间，`volatile-*` 曾被判为安全）。
 */
class WrittenEntryTtlTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
    private val tenMinutes = Duration.ofMinutes(10)

    @AfterEach
    fun close() = runtime.close()

    private fun writtenEntryTtl(name: String): Duration? = requireNotNull(runtime.admin(name)).writtenEntryTtl

    @Test
    fun aStoreRecordsTheFirstTtlItWasWrittenWith() {
        val store = runtime.create(storeSpec<String, String>("revoked") { codec = StringCodec })
        store.put("plain", "v")
        writtenEntryTtl("revoked").assert().isNull()
        store.put("jti-1", "revoked", tenMinutes)
        store.put("jti-2", "revoked", Duration.ofMinutes(1))
        writtenEntryTtl("revoked").assert().isEqualTo(tenMinutes)
    }

    @Test
    fun everyPerCallWriteOfAStoreIsRecorded() {
        val batch = runtime.create(storeSpec<String, String>("batch") { codec = StringCodec })
        batch.putAll(mapOf("a" to "1"), tenMinutes)
        writtenEntryTtl("batch").assert().isEqualTo(tenMinutes)

        val conditional = runtime.create(storeSpec<String, String>("conditional") { codec = StringCodec })
        conditional.putIfMatch("a", null, "1", tenMinutes)
        writtenEntryTtl("conditional").assert().isEqualTo(tenMinutes)

        val async = runtime.create(storeSpec<String, String>("async") { codec = StringCodec })
        async.async().put("a", "1", tenMinutes).join()
        writtenEntryTtl("async").assert().isEqualTo(tenMinutes)

        val suspending = runtime.create(storeSpec<String, String>("suspending") { codec = StringCodec })
        runBlocking { suspending.suspending().put("a", "1", tenMinutes) }
        writtenEntryTtl("suspending").assert().isEqualTo(tenMinutes)
    }

    @Test
    fun aStoreWithTtlOfRecordsItsResult() {
        val store = runtime.create(
            storeSpec<String, String>("sessions") {
                codec = StringCodec
                ttlOf = TtlFunction { _, _ -> tenMinutes }
            }
        )
        writtenEntryTtl("sessions").assert().isNull()
        store.put("s", "v")
        writtenEntryTtl("sessions").assert().isEqualTo(tenMinutes)
    }

    /**
     * 墓碑的寿命不是值的寿命：墓碑被驱逐只让迟到的旧版本提前可写，不计入（同驱逐检查对墓碑寿命的规则）。
     */
    @Test
    fun aVersionedStoreRecordsValuesButNotTombstones() {
        val store = runtime.create(versionedStoreSpec<String, String>("ledger") { codec = StringCodec })
        store.deleteIfNewer("a", 1, tenMinutes)
        writtenEntryTtl("ledger").assert().isNull()
        store.putIfNewer("b", "v", 1, tenMinutes)
        writtenEntryTtl("ledger").assert().isEqualTo(tenMinutes)
    }

    @Test
    fun aSetStoreRecordsPerCallTtlButNotAnEmptySet() {
        val sets = runtime.create(setStoreSpec<String, String>("tags") { elementCodec = StringCodec })
        sets.put("empty", emptySet(), tenMinutes)
        sets.putAll(mapOf("empty" to emptySet()), tenMinutes)
        sets.put("policy", setOf("x"))
        sets.add("policy", listOf("y"))
        writtenEntryTtl("tags").assert().isNull()
        sets.put("a", setOf("x"), tenMinutes)
        writtenEntryTtl("tags").assert().isEqualTo(tenMinutes)

        val batch = runtime.create(setStoreSpec<String, String>("batch-tags") { elementCodec = StringCodec })
        batch.putAll(mapOf("a" to setOf("x")), tenMinutes)
        writtenEntryTtl("batch-tags").assert().isEqualTo(tenMinutes)

        val views = runtime.create(setStoreSpec<String, String>("view-tags") { elementCodec = StringCodec })
        views.async().put("a", setOf("x"), tenMinutes).join()
        views.async().putAll(mapOf("b" to setOf("x")), Duration.ofMinutes(1)).join()
        runBlocking {
            views.suspending().put("c", setOf("x"), Duration.ofMinutes(2))
            views.suspending().putAll(mapOf("d" to setOf("x")), Duration.ofMinutes(3))
        }
        writtenEntryTtl("view-tags").assert().isEqualTo(tenMinutes)
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
        writtenEntryTtl("views").assert().isNull()
    }
}
