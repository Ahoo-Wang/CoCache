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

package me.ahoo.cocache.core.engine

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.FillKey
import me.ahoo.cocache.core.FillObserver
import me.ahoo.cocache.core.FillScope
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.proxy.CacheProxies
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 写事务内回源的钩子（RFC 0005 §4.9）：调用入口捕获一次（S7），私有回源（机制 2），记录进入共享层的 key（机制 1），
 * 写事务中的 leader 不共享结果（S15），按 (keyspace, 编码 key) 匹配（S24）。事务由线程局部的 [TransactionObserver.current] 模拟。
 */
class FillObserverTest {
    private val memory = InMemoryBackend()
    private val backend = HookedBackend(memory)
    private val database = ConcurrentHashMap<String, String>()
    private val loads = AtomicInteger()
    private val scope = RecordingScope()
    private val observer = TransactionObserver()
    private val runtime = CacheRuntime.builder().backend(backend).fillObserver(observer).build()
    private val loader = object : CacheLoader<String, String> {
        override fun load(key: String): String? {
            loads.incrementAndGet()
            return database[key]
        }

        override fun loadAll(keys: Collection<String>): Map<String, String> {
            loads.incrementAndGet()
            return keys.mapNotNull { k -> database[k]?.let { k to it } }.toMap()
        }
    }
    private val cache: Cache<String, String> = runtime.create(
        cacheSpec("tx") {
            codec = StringCodec
            loader = this@FillObserverTest.loader
            policy = CachePolicy(ttl = Duration.ofMinutes(10), lease = LeasePolicy(wait = Duration.ofMillis(20)))
        }
    )

    interface TxCache : Cache<String, String>

    /**
     * 只在设置了 [current] 的线程上返回事务。
     */
    class TransactionObserver : FillObserver {
        val current = ThreadLocal<FillScope?>()
        val entries = AtomicInteger()

        override fun enter(): FillScope? {
            entries.incrementAndGet()
            return current.get()
        }
    }

    class RecordingScope : FillScope {
        @Volatile
        var privateKeys: Set<String> = emptySet()

        @Volatile
        var allPrivate = false

        val filled = CopyOnWriteArrayList<FillKey>()

        override fun isPrivate(key: FillKey): Boolean = allPrivate || key.encodedKey in privateKeys

        override fun filled(key: FillKey) {
            filled += key
        }

        fun filledKeys(): List<String> = filled.map { it.encodedKey }
    }

    @AfterEach
    fun destroy() {
        observer.current.remove()
        runtime.close()
    }

    private fun inspect(key: String): KeyInspection = requireNotNull(runtime.admin("tx")).inspect(key)

    private fun gated(): Long = requireNotNull(runtime.admin("tx")).metrics.fillsGated

    /**
     * 同步变体只在 L2 未命中、且这次读取带数据源时于入口调用一次 `enter`；L2 命中、异步与挂起变体、不带 loader 的外部来源读取都不调用。
     */
    @Test
    fun enterIsCalledOncePerSyncMissWithASource() {
        database.putAll(mapOf("a" to "A", "b" to "B", "c" to "C", "d" to "D", "e" to "E"))
        cache["a"].assert().isEqualTo("A")
        observer.entries.get().assert().isEqualTo(1)
        cache["a"].assert().isEqualTo("A")
        cache.async().get("b").join().assert().isEqualTo("B")
        runBlocking { cache.suspending().get("c") }.assert().isEqualTo("C")
        observer.entries.get().assert().isEqualTo(1)
        cache.getAll(listOf("d", "e")).assert().isEqualTo(mapOf("d" to "D", "e" to "E"))
        observer.entries.get().assert().isEqualTo(2)
        cache.get("f") { "F" }.assert().isEqualTo("F")
        observer.entries.get().assert().isEqualTo(3)

        val external = runtime.create(
            cacheSpec<String, String>("external") {
                codec = StringCodec
                externallySourced()
            }
        )
        external["x"].assert().isNull()
        observer.entries.get().assert().isEqualTo(3)
        external.get("x") { "X" }.assert().isEqualTo("X")
        observer.entries.get().assert().isEqualTo(4)
    }

    /**
     * 机制 2：私有回源不读也不写 L1、不写 L2、不记录，计入 `fillsGated`；结果只交给这次调用（到期时间为现在）。事务之外照常填充。
     */
    @Test
    fun privateFillTouchesNoSharedLayer() {
        scope.privateKeys = setOf("p")
        database["p"] = "uncommitted"
        observer.current.set(scope)
        val reads = backend.reads.get()
        cache["p"].assert().isEqualTo("uncommitted")
        val lookup = cache.lookup("p")
        (lookup as Lookup.Present).value.assert().isEqualTo("uncommitted")
        backend.reads.get().assert().isEqualTo(reads)
        backend.fills.get().assert().isEqualTo(0)
        inspect("p").local.assert().isNull()
        inspect("p").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        gated().assert().isEqualTo(2)
        loads.get().assert().isEqualTo(2)
        scope.filled.assert().isEmpty()

        observer.current.remove()
        database["p"] = "committed"
        cache["p"].assert().isEqualTo("committed")
        inspect("p").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
        gated().assert().isEqualTo(2)
    }

    /**
     * 批量读取逐 key 门控：私有的 key 一次批量回源，其余 key 照常经 L1 并记录。
     */
    @Test
    fun batchReadGatesPerKey() {
        database.putAll(mapOf("p1" to "P1", "p2" to "P2", "s1" to "S1"))
        scope.privateKeys = setOf("p1", "p2")
        observer.current.set(scope)
        cache.getAll(listOf("p1", "s1", "p2")).assert().isEqualTo(mapOf("p1" to "P1", "s1" to "S1", "p2" to "P2"))
        inspect("p1").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        inspect("p2").local.assert().isNull()
        inspect("s1").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
        scope.filledKeys().assert().containsExactly("s1")
        gated().assert().isEqualTo(2)
        // 私有的两个 key 一次批量回源，共享的一个 key 一次
        loads.get().assert().isEqualTo(2)

        scope.allPrivate = true
        cache.lookupAll(listOf("q")).getValue("q").assert().isInstanceOf(Lookup.Absent::class.java)
        inspect("q").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
    }

    /**
     * 机制 1：数据源产生、进入任何共享层的 key 都记录（凭 lease 写回 L1、L1 读失败或 lease 等待超时之后只写 L2 的降级填充、批量写回），
     * 读到 L1 已有的值不记录。记录的 key 与经缓存对象得到的 [FillKey] 相等。
     */
    @Test
    fun recordsEveryKeyTheLoaderWritesToASharedLayer() {
        database.putAll(mapOf("a" to "A", "b" to "B", "c" to "C", "d" to "D", "e" to "E", "f" to "F"))
        cache.put("b", "B")
        observer.current.set(scope)
        cache["a"].assert().isEqualTo("A")
        cache["b"].assert().isEqualTo("B")

        backend.failAll = true
        cache["c"].assert().isEqualTo("C")
        backend.failAll = false

        Suspending.await {
            memory.store(Keyspace("cocache:", "tx"))
                .read("cocache:tx:d", LeaseRequest(Token(NodeId.random(), 1), Duration.ofMinutes(1)), null, null)
        }
        cache["d"].assert().isEqualTo("D")
        cache.getAll(listOf("e", "f")).assert().hasSize(2)

        scope.filledKeys().assert().containsExactly("a", "c", "d", "e", "f")
        scope.filled.first().assert().isEqualTo(FillKey.of(cache, "a"))
        inspect("c").local?.state.assert().isEqualTo(KeyInspection.LocalState.VALUE)
        inspect("c").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
    }

    /**
     * S15：写事务中的 leader 的结果不交给跟随者，跟随者自行回源。leader 的 L1 写回失败（只写 L2 的降级填充），
     * 跟随者因此只能从自己的 loader 得到值；共享 flight 的结果时它会拿到未提交的值。
     */
    @Test
    fun leaderInTransactionDoesNotShareItsResult() {
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val leader = fork("tx-leader") {
            observer.current.set(scope)
            cache.get("k") {
                loading.countDown()
                release.awaitOrFail("release")
                "uncommitted"
            }
        }
        loading.awaitOrFail("loading")
        backend.failFill = true
        val follower = fork("follower") { cache.get("k") { "committed" } }
        awaitCondition("follower parked") { follower.parked }
        release.countDown()
        leader.join().assert().isEqualTo("uncommitted")
        follower.join().assert().isEqualTo("committed")
        scope.filledKeys().assert().contains("k")
    }

    /**
     * S7：异步与挂起变体不门控、不记录，调用方线程上即使有事务也一样。
     */
    @Test
    fun asyncAndSuspendingVariantsAreNeitherGatedNorRecorded() {
        database.putAll(mapOf("a" to "A", "b" to "B"))
        scope.allPrivate = true
        observer.current.set(scope)
        cache.async().get("a").join().assert().isEqualTo("A")
        runBlocking { cache.suspending().get("b") }.assert().isEqualTo("B")
        inspect("a").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
        inspect("b").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
        gated().assert().isEqualTo(0)
        scope.filled.assert().isEmpty()
        observer.entries.get().assert().isEqualTo(0)
    }

    /**
     * 私有回源中对同一 key 的重入快速失败，而不是无限递归。
     */
    @Test
    fun reentryIntoAPrivateFillFailsFast() {
        scope.allPrivate = true
        observer.current.set(scope)
        val error = runCatching { cache.get("r") { cache["r"] } }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalStateException::class.java)
        error?.message.assert().contains("Recursive call")
        // 领头的共享回源中对同一 key 的私有回源同样快速失败
        scope.allPrivate = false
        val nested = runCatching {
            cache.get("s") {
                scope.allPrivate = true
                cache["s"]
            }
        }.exceptionOrNull()
        nested?.message.assert().contains("Recursive call")
    }

    /**
     * S24：经接口代理与经引擎得到的 key 相等，同一 keyspace 的另一个运行时中的缓存也相等；不是 CoCache 的单值引擎时为 `null`。
     */
    @Test
    fun fillKeysMatchByKeyspaceAndEncodedKey() {
        val proxy = CacheProxies.create(TxCache::class.java, cache)
        val key = requireNotNull(FillKey.of(cache, "k"))
        FillKey.of(proxy, "k").assert().isEqualTo(key)
        FillKey.of(proxy, "k").hashCode().assert().isEqualTo(key.hashCode())
        FillKey.of(cache, "j").assert().isNotEqualTo(key)
        key.cacheName.assert().isEqualTo("tx")
        key.keyspace.assert().isEqualTo(Keyspace("cocache:", "tx"))
        key.toString().assert().contains("tx", "k")

        CacheRuntime.builder().backend(InMemoryBackend()).build().use { other ->
            val same = other.create(
                cacheSpec<String, String>("other") {
                    codec = StringCodec
                    namespace = "tx"
                    loader = CacheLoader { it }
                }
            )
            FillKey.of(same, "k").assert().isEqualTo(key)
        }
        FillKey.of(mockk<Cache<String, String>>(), "k").assert().isNull()
        key.assert().isEqualTo(key)
        key.assert().isNotEqualTo("k")
        val elsewhere = runtime.create(
            cacheSpec<String, String>("elsewhere") {
                codec = StringCodec
                loader = CacheLoader { it }
            }
        )
        FillKey.of(elsewhere, "k").assert().isNotEqualTo(key)
        FillObserver.NONE.enter().assert().isNull()
        FillObserver.NONE.toString().assert().isEqualTo("FillObserver.NONE")
    }

    /**
     * 私有回源不合并、也不等待别的线程领头的同一 key 的加载。
     */
    @Test
    fun privateLoadDoesNotWaitForAnotherCallersFlight() {
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val other = fork("other") {
            cache.get("k") {
                loading.countDown()
                release.awaitOrFail("release")
                "shared"
            }
        }
        loading.awaitOrFail("loading")
        scope.allPrivate = true
        observer.current.set(scope)
        cache.get("k") { "private" }.assert().isEqualTo("private")
        release.countDown()
        other.join().assert().isEqualTo("shared")
    }

    /**
     * [FillKey.evictLocal] 只淘汰本节点的副本并递增戳；[FillKey.invalidateAll] 每个缓存一次走正常的失效路径。
     */
    @Test
    fun evictLocalAndInvalidateAll() {
        database.putAll(mapOf("a" to "A", "b" to "B"))
        cache.getAll(listOf("a", "b"))
        val a = requireNotNull(FillKey.of(cache, "a"))
        a.evictLocal()
        inspect("a").local.assert().isNull()
        inspect("a").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)

        FillKey.invalidateAll(listOf(a, requireNotNull(FillKey.of(cache, "b")), a))
        inspect("a").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        inspect("b").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        inspect("b").local.assert().isNull()
    }

    /**
     * 带版本的派生副本同样门控与记录；失效保留版本（同运维的按 key 删除），之后读到更新的版本。
     */
    @Test
    fun versionedCacheIsGatedAndRecordedToo() {
        val versions = ConcurrentHashMap(mapOf("v" to Versioned<String?>("V", 1), "w" to Versioned<String?>("W", 1)))
        val versioned = runtime.create(
            versionedCacheSpec<String, String>("doc") {
                codec = StringCodec
                versionedLoader = CacheLoader { versions[it] }
            }
        )
        observer.current.set(scope)
        scope.privateKeys = setOf("w")
        versioned["v"].assert().isEqualTo("V")
        versioned["w"].assert().isEqualTo("W")
        scope.filledKeys().assert().containsExactly("v")
        requireNotNull(runtime.admin("doc")).metrics.fillsGated.assert().isEqualTo(1)
        observer.current.remove()

        versions["v"] = Versioned("V2", 2)
        FillKey.invalidateAll(scope.filled)
        versioned["v"].assert().isEqualTo("V2")
    }

    /**
     * 缺陷复现（#823）：`""` 是合法的业务 key，事务内记录的填充失效不经运维入口的非空校验，与非空 key 混在一组时一次失效；
     * 运维入口照常拒绝空的编码 key。
     */
    @Test
    fun invalidateAllAcceptsAnEmptyBusinessKey() {
        database.putAll(mapOf("" to "E", "a" to "A"))
        cache.getAll(listOf("", "a")).assert().isEqualTo(mapOf("" to "E", "a" to "A"))
        val loaded = loads.get()
        val keys = listOf("", "a").map { requireNotNull(FillKey.of(cache, it)) }
        keys.first().encodedKey.assert().isEmpty()

        FillKey.invalidateAll(keys)
        inspect("a").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        database.putAll(mapOf("" to "E2", "a" to "A2"))
        cache[""].assert().isEqualTo("E2")
        cache["a"].assert().isEqualTo("A2")
        loads.get().assert().isEqualTo(loaded + 2)

        val admin = requireNotNull(runtime.admin("tx"))
        runCatching { admin.invalidateEncoded("") }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { admin.inspect("") }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 自定义 `KeyEncoder` 输出空串的 key 同样可以失效。
     */
    @Test
    fun invalidateAllAcceptsAKeyEncodedAsTheEmptyString() {
        val rows = ConcurrentHashMap(mapOf(0 to "zero"))
        val numbers = runtime.create(
            cacheSpec<Int, String>("numbers") {
                codec = StringCodec
                keyEncoder = KeyEncoder { if (it == 0) "" else it.toString() }
                loader = CacheLoader { rows[it] }
            }
        )
        numbers[0].assert().isEqualTo("zero")
        val key = requireNotNull(FillKey.of(numbers, 0))
        key.encodedKey.assert().isEmpty()
        rows[0] = "ZERO"
        numbers[0].assert().isEqualTo("zero")

        FillKey.invalidateAll(listOf(key))
        numbers[0].assert().isEqualTo("ZERO")
    }

    /**
     * 缺陷复现（#823）：带版本的派生副本的空业务 key 同样可以失效，且保留版本栅栏（不是无条件删除）：迟到的旧版本仍被拒绝。
     * 一个 key 失败不影响同一缓存的其余 key，最后抛出第一个异常。
     */
    @Test
    fun versionedInvalidateAllAcceptsAnEmptyBusinessKeyAndKeepsTheFence() {
        val versions = ConcurrentHashMap(mapOf("" to Versioned<String?>("E", 1), "w" to Versioned<String?>("W", 1)))
        val versioned = runtime.create(
            versionedCacheSpec<String, String>("doc") {
                codec = StringCodec
                versionedLoader = CacheLoader { versions[it] }
            }
        )
        versioned.getAll(listOf("", "w")).assert().isEqualTo(mapOf("" to "E", "w" to "W"))
        val keys = listOf("", "w").map { requireNotNull(FillKey.of(versioned, it)) }

        FillKey.invalidateAll(keys)
        versioned.putIfNewer("", "late", 1).assert().isEqualTo(VersionedPut.Stale(1))
        versioned.putIfNewer("w", "late", 1).assert().isEqualTo(VersionedPut.Stale(1))
        versions[""] = Versioned("E2", 2)
        versions["w"] = Versioned("W2", 2)
        versioned[""].assert().isEqualTo("E2")
        versioned["w"].assert().isEqualTo("W2")

        val failure = IllegalStateException("remote down")
        backend.beforeInvalidate = { storageKeys -> if (storageKeys.single().endsWith(":")) throw failure }
        versions["w"] = Versioned("W3", 3)
        val error = runCatching { FillKey.invalidateAll(keys) }.exceptionOrNull()
        backend.beforeInvalidate = null
        error.assert().isInstanceOf(CacheWriteException::class.java)
        error?.cause.assert().isSameAs(failure)
        versioned["w"].assert().isEqualTo("W3")
        versioned[""].assert().isEqualTo("E2")
    }

    /**
     * [FillKey.invalidateAll] 中一个缓存失败不影响其余缓存：全部执行之后抛出第一个异常，其余作为它的 suppressed。
     * 派生副本的失效默认进入重试队列而不抛出。
     */
    @Test
    fun invalidateAllAggregatesFailures() {
        val strict = (1..2).map { index ->
            runtime.create(
                cacheSpec<String, String>("strict$index") {
                    codec = StringCodec
                    loader = CacheLoader { it }
                    policy = CachePolicy(invalidateFailure = InvalidateFailureMode.THROW)
                }
            )
        }
        val keys = strict.map { requireNotNull(FillKey.of(it, "x")) } + requireNotNull(FillKey.of(cache, "x"))
        backend.failAll = true
        val error = runCatching { FillKey.invalidateAll(keys) }.exceptionOrNull()
        backend.failAll = false
        error.assert().isNotNull()
        error?.suppressed?.size.assert().isEqualTo(1)
        requireNotNull(runtime.admin("tx")).pendingInvalidationCount.assert().isEqualTo(1)
    }
}
