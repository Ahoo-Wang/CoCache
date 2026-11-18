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
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 只读的权威缓存（`Store`、`VersionedStore`、`SetStore` 的 `readOnly`）：经后端的只读存储读取，写入在修改任何一层之前拒绝。
 * 读写节点与只读节点共享同一个内存后端：只读节点读到写入方的值，收到写入方的失效；它的每个写入（三种视图与运维接口）都以
 * [UnsupportedOperationException] 失败，L1 不变、不发布任何帧、它自己的 L2 副本保留。
 */
class ReadOnlyStoreTest {
    private val memory = InMemoryBackend()
    private val hooked = HookedBackend(memory)
    private val writer = CacheRuntime.builder().backend(memory).build()
    private val reader = CacheRuntime.builder().backend(hooked).build()

    /**
     * 频道上收到的非空帧（零 key 的帧是探测，不计）。
     */
    private val frames = CopyOnWriteArrayList<List<String>>()

    @AfterEach
    fun close() {
        reader.close()
        writer.close()
    }

    private fun watch(keyspace: Keyspace) {
        memory.channel.subscribe(
            keyspace,
            object : InvalidationListener {
                override fun onInvalidated(keys: List<String>, origin: NodeId) {
                    if (keys.isNotEmpty()) frames.add(keys)
                }

                override fun onReset() = Unit
            }
        )
    }

    private fun unwrap(error: Throwable?): Throwable? = if (error is CompletionException) error.cause ?: error else error

    private fun assertReadOnly(name: String, error: Throwable?) {
        unwrap(error).assert().isInstanceOf(UnsupportedOperationException::class.java)
        unwrap(error)?.message.assert().contains("[$name] is read-only")
    }

    @Test
    fun readOnlyStoreReadsHearsInvalidationsAndRefusesEveryWrite() {
        val spec = storeSpec<String, String>("doc") { codec = StringCodec }
        val written = writer.create(spec)
        val store = reader.create(spec.toBuilder().readOnly().build())
        watch(spec.keyspace)
        written.put("k", "v1")

        store["k"].assert().isEqualTo("v1")
        val readsAfterFill = hooked.reads.get()
        store["k"].assert().isEqualTo("v1")
        hooked.reads.get().assert().isEqualTo(readsAfterFill)
        store.lookup("missing").assert().isInstanceOf(Lookup.Absent::class.java)
        store.getAll(listOf("k", "missing")).assert().isEqualTo(mapOf("k" to "v1"))
        (store.lookupRemote("k") as Lookup.Present).value.assert().isEqualTo("v1")
        val present = store.getIfChanged("k", null)
        present.assert().isInstanceOf(ConditionalGet.Present::class.java)
        val digest = (present as ConditionalGet.Present).digest
        store.getIfChanged("k", digest).assert().isEqualTo(ConditionalGet.Unchanged)
        store.getIfChanged("missing", null).assert().isEqualTo(ConditionalGet.Absent)
        store.async().get("k").join().assert().isEqualTo("v1")
        Suspending.await { store.suspending().get("k") }.assert().isEqualTo("v1")

        written.put("k", "v2")
        store["k"].assert().isEqualTo("v2")

        val name = "doc"
        val frameCount = frames.size
        val writes = writesOf(store, digest)
        val readsBefore = hooked.reads.get()
        writes.forEach { write -> assertReadOnly(name, runCatching { write() }.exceptionOrNull()) }

        val admin = requireNotNull(reader.admin(name))
        admin.readOnly.assert().isTrue()
        requireNotNull(writer.admin(name)).readOnly.assert().isFalse()
        assertReadOnly(name, runCatching { admin.invalidateEncoded("k") }.exceptionOrNull())
        assertReadOnly(name, runCatching { admin.broadcastEncoded("k") }.exceptionOrNull())

        // 什么也没有改变：L1 仍是写入方的值，没有发布任何帧，只读节点的 L2 副本仍在（读取不访问 L1）
        (written.lookupRemote("k") as Lookup.Present).value.assert().isEqualTo("v2")
        frames.size.assert().isEqualTo(frameCount)
        hooked.puts.get().assert().isZero()
        store["k"].assert().isEqualTo("v2")
        hooked.reads.get().assert().isEqualTo(readsBefore)
        admin.clearLocal()
        store["k"].assert().isEqualTo("v2")
        hooked.reads.get().assert().isEqualTo(readsBefore + 1)
    }

    /**
     * `Store` 的全部写入：同步、异步与挂起视图。
     */
    private fun writesOf(store: Store<String, String>, digest: Digest): List<() -> Any?> = listOf(
        { store.put("k", "x") },
        { store.put("k", "x", Duration.ofMinutes(1)) },
        { store.putAll(mapOf("k" to "x")) },
        { store.putAll(mapOf("k" to "x"), Duration.ofMinutes(1)) },
        { store.delete("k") },
        { store.deleteIfMatch("k", digest) },
        { store.putIfMatch("k", null, "x") },
        { store.putIfMatch("k", null, "x", Duration.ofMinutes(1)) },
        { store.async().put("k", "x").join() },
        { store.async().put("k", "x", Duration.ofMinutes(1)).join() },
        { store.async().putAll(mapOf("k" to "x")).join() },
        { store.async().putAll(mapOf("k" to "x"), Duration.ofMinutes(1)).join() },
        { store.async().delete("k").join() },
        { store.async().deleteIfMatch("k", digest).join() },
        { store.async().putIfMatch("k", null, "x").join() },
        { store.async().putIfMatch("k", null, "x", Duration.ofMinutes(1)).join() },
        { Suspending.await { store.suspending().put("k", "x") } },
        { Suspending.await { store.suspending().put("k", "x", Duration.ofMinutes(1)) } },
        { Suspending.await { store.suspending().putAll(mapOf("k" to "x")) } },
        { Suspending.await { store.suspending().putAll(mapOf("k" to "x"), Duration.ofMinutes(1)) } },
        { Suspending.await { store.suspending().delete("k") } },
        { Suspending.await { store.suspending().deleteIfMatch("k", digest) } },
        { Suspending.await { store.suspending().putIfMatch("k", null, "x") } },
        { Suspending.await { store.suspending().putIfMatch("k", null, "x", Duration.ofMinutes(1)) } },
    )

    @Test
    fun readOnlyVersionedStoreAndSetStoreRefuseWrites() {
        val versioned = versionedStoreSpec<String, String>("versioned") { codec = StringCodec }
        writer.create(versioned).putIfNewer("k", "v", 3)
        val versionedReader = reader.create(versioned.toBuilder().readOnly().build())
        versionedReader.getVersioned("k").assert().isEqualTo(Versioned("v", 3))
        listOf<() -> Any?>(
            { versionedReader.putIfNewer("k", "x", 4) },
            { versionedReader.putIfNewer("k", "x", 4, null) },
            { versionedReader.deleteIfNewer("k", 4) },
            { versionedReader.deleteIfNewer("k", 4, Duration.ofMinutes(1)) },
            { versionedReader.purge("k") },
            { versionedReader.async().putIfNewer("k", "x", 4).join() },
            { versionedReader.async().purge("k").join() },
            { Suspending.await { versionedReader.suspending().deleteIfNewer("k", 4) } },
            { requireNotNull(reader.admin("versioned")).purgeEncoded("k") },
        ).forEach { assertReadOnly("versioned", runCatching { it() }.exceptionOrNull()) }
        versionedReader.getVersioned("k").assert().isEqualTo(Versioned("v", 3))

        val sets = setStoreSpec<String, String>("members") { elementCodec = StringCodec }
        writer.create(sets).add("k", listOf("a", "b"))
        val setReader = reader.create(sets.toBuilder().readOnly().build())
        setReader["k"].assert().isEqualTo(setOf("a", "b"))
        listOf<() -> Any?>(
            { setReader.add("k", listOf("c")) },
            { setReader.remove("k", listOf("a")) },
            { setReader.clear("k") },
            { setReader.put("k", setOf("c")) },
            { setReader.put("k", setOf("c"), Duration.ofMinutes(1)) },
            { setReader.putAll(mapOf("k" to setOf("c"))) },
            { setReader.putAll(mapOf("k" to setOf("c")), Duration.ofMinutes(1)) },
            { setReader.async().add("k", listOf("c")).join() },
            { Suspending.await { setReader.suspending().clear("k") } },
        ).forEach { assertReadOnly("members", runCatching { it() }.exceptionOrNull()) }
        setReader["k"].assert().isEqualTo(setOf("a", "b"))
    }

    /**
     * 后端不提供只读存储（[CoherenceBackend.readOnlyStore] 的默认实现返回 `null`）时创建失败，且不登记任何东西：同名的读写缓存随后照常创建。
     */
    @Test
    fun readOnlyNeedsBackendSupport() {
        // 只实现抽象成员：readOnlyStore 用接口的默认实现（类委托会连默认方法一起转交，所以不用 `by memory`）
        val plain = object : CoherenceBackend {
            override val atomicBroadcast: Boolean = memory.atomicBroadcast
            override val format: Int = memory.format
            override val channel = memory.channel

            override fun store(keyspace: Keyspace) = memory.store(keyspace)
        }
        plain.readOnlyStore(Keyspace("cocache:", "doc")).assert().isNull()
        CacheRuntime.builder().backend(plain).build().use { runtime ->
            val spec = storeSpec<String, String>("doc") { codec = StringCodec }
            val error = runCatching { runtime.create(spec.toBuilder().readOnly().build()) }.exceptionOrNull()
            error.assert().isInstanceOf(UnsupportedOperationException::class.java)
            error?.message.assert().contains("provides no read-only stores")
            runtime.cacheNames.assert().isEmpty()
            runtime.create(spec).put("k", "v")
        }
    }

    @Test
    fun readOnlySpecsRoundTripThroughToBuilder() {
        val store = storeSpec<String, String>("doc") {
            codec = StringCodec
            readOnly = true
        }
        store.readOnly.assert().isTrue()
        store.toBuilder().build().readOnly.assert().isTrue()
        store.toString().assert().contains("readOnly=true")
        storeSpec<String, String>("doc") { codec = StringCodec }.readOnly.assert().isFalse()
        val versioned = versionedStoreSpec<String, String>("v") { codec = StringCodec }.toBuilder().readOnly().build()
        versioned.toBuilder().build().readOnly.assert().isTrue()
        versioned.toString().assert().contains("readOnly=true")
        val sets = setStoreSpec<String, String>("s") { elementCodec = StringCodec }.toBuilder().readOnly().build()
        sets.toBuilder().build().readOnly.assert().isTrue()
        sets.toString().assert().contains("readOnly=true")
    }

    /**
     * 内存后端的只读存储：带令牌的读取以 [IllegalArgumentException] 拒绝，写入与能力面上的写入以 [UnsupportedOperationException] 拒绝，
     * 什么也不改变。
     */
    @Test
    fun inMemoryReadOnlyStoreRefusesTokensAndWrites() {
        val keyspace = Keyspace("cocache:", "raw")
        val store = memory.readOnlyStore(keyspace)
        val key = keyspace.storageKey("k")
        val token = Token(NodeId.random(), 1)
        runBlocking {
            runCatching { store.read(key, LeaseRequest(token, Duration.ofSeconds(1)), null, null) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
            runCatching {
                store.read(
                    key,
                    null,
                    RefreshRequest(Duration.ofSeconds(1), token, Duration.ofSeconds(1)),
                    null
                )
            }
                .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
            runCatching { store.readAll(listOf(key), mapOf(key to LeaseRequest(token, Duration.ofSeconds(1))), null) }
                .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
            val refused = listOf<suspend () -> Any?>(
                { store.release(key, token) },
                { store.fillAll(emptyList()) },
                { store.putAll(emptyList(), ByteArray(0)) },
                { store.invalidate(listOf(key), ByteArray(0)) },
                {
                    store.reclaim(
                        key,
                        ContentFingerprint.of(Envelope.Absent, 2),
                        LeaseRequest(token, Duration.ofSeconds(1))
                    )
                },
                {
                    requireNotNull(
                        store.conditional()
                    ).deleteIfMatch(key, Digest.ofPayload(ByteArray(0)), ByteArray(0))
                },
                { requireNotNull(store.versioned()).evictKeepingVersion(key, null, ByteArray(0)) },
                { requireNotNull(store.sets()).addMembers(key, emptyList(), null, ByteArray(0)) },
                { requireNotNull(store.sets()).removeMembers(key, emptyList(), null, ByteArray(0)) },
                { store.fill(Fill(key, token, Envelope.Absent, null)) },
                { store.put(key, Envelope.Absent, null, ByteArray(0)) },
                {
                    requireNotNull(
                        store.conditional()
                    ).putIfMatch(key, null, Envelope.Scalar(ByteArray(0)), null, ByteArray(0))
                },
                { requireNotNull(store.versioned()).putIfNewer(key, Envelope.Absent, 1, null, ByteArray(0)) },
            )
            refused.forEach { call ->
                runCatching { call() }.exceptionOrNull().assert().isInstanceOf(
                    UnsupportedOperationException::class.java
                )
            }
            store.toString().assert().contains("ReadOnlyL1Store")
        }
    }

    /**
     * 内存后端的只读存储照常转发不带令牌的读取与条件读取。
     */
    @Test
    fun inMemoryReadOnlyStoreForwardsReads() {
        val keyspace = Keyspace("cocache:", "raw-reads")
        val store = memory.readOnlyStore(keyspace)
        val key = keyspace.storageKey("k")
        runBlocking {
            store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
            store.readAll(listOf(key), emptyMap(), null).assert().isEqualTo(mapOf(key to L1Read.Miss(false)))
            requireNotNull(
                store.conditional()
            ).readIfChanged(key, null, null).assert().isEqualTo(L1ConditionalRead.Absent)
        }
    }
}
