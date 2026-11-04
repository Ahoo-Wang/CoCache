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

package me.ahoo.cocache.test

import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1ConditionalWrite
import me.ahoo.cocache.api.spi.L1Eviction
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * L1 协议 TCK（RFC 0003 §4.4，挂起形状见 RFC 0005 §4.3）：每个 [CoherenceBackend] 实现都必须通过。
 * 用例直接调用 [L1Store] 的挂起方法（经 [Suspending.await]）；Java 实现者的 `FutureL1Store` 经 `FutureL1Store.adapt` 接受同样的检验。
 * 后端以 [expectedCapabilities] 声明存储的可选能力；集合成员的用例只在没有声明 [L1Capability.SETS] 时跳过。
 *
 * 时间相关用例通过 [elapse] 推进时间：内存后端推进手动时钟，真实后端可以 sleep（用例只使用很短的 TTL）。
 *
 * 订阅帧的用例忽略零 key 的帧（或只记录本用例发出的帧）：它们是创建存储时的频道探测（RFC 0005 §4.7，K8），异步投递的通道
 * （如 Redis Cluster 的经典 Pub/Sub）可能在订阅之后才送达 [setup] 中创建存储时发出的那一帧。
 */
@Suppress("LargeClass")
abstract class BackendProtocolSpec {
    protected abstract fun createBackend(): CoherenceBackend

    /**
     * 后端的存储提供的可选能力（RFC 0005 §4.3），没有默认值：规范断言存储提供的能力与它完全相同（多出来的也算失败）、
     * 每个访问器稳定；需要某个能力的用例只在它没有声明时跳过。
     */
    protected abstract val expectedCapabilities: Set<L1Capability>

    /**
     * 让 L1 的时间前进 [duration]。
     */
    protected abstract fun elapse(duration: Duration)

    protected lateinit var backend: CoherenceBackend
    protected lateinit var store: L1Store
    protected lateinit var namespace: String

    /**
     * 用例的 keyspace：默认前缀 `cocache:` 加 [namespace]。
     */
    protected lateinit var keyspace: Keyspace
    private val node = NodeId.random()
    private val sequence = AtomicLong()

    protected open val shortTtl: Duration = Duration.ofMillis(300)

    /**
     * 暂停 [createBackend] 所建后端的 L1 命令（如暂停连接），关闭返回值即恢复（时长固定的暂停可以等它自己结束）。暂停期间开始的命令
     * 不完成，可以在暂停结束之后执行；暂停至少持续 [READ_TIMEOUT] 的 10 倍。读取时限的用例需要它（RFC 0005 round 3 D6），
     * 不支持时返回 `null`（默认），该用例跳过。
     */
    protected open fun pause(): AutoCloseable? = null

    @BeforeEach
    open fun setup() {
        backend = createBackend()
        namespace = "tck-" + UUID.randomUUID().toString().substring(0, 8)
        keyspace = Keyspace("cocache:", namespace)
        store = backend.store(keyspace)
    }

    @AfterEach
    open fun destroy() {
        backend.close()
    }

    /**
     * 用例体：其中直接调用存储与通道的挂起方法（[Suspending.await]）。
     */
    protected fun l1(block: suspend () -> Unit) = Suspending.await(block)

    /**
     * 用例需要的能力：[expectedCapabilities] 缺少其中任何一个时跳过这个用例。
     */
    protected fun assumeDeclared(vararg capabilities: L1Capability) =
        CapabilityDeclaration.assume(expectedCapabilities, capabilities)

    /**
     * 存储的集合能力面：集合成员的用例要求它（先以 [assumeDeclared] 检查声明）。
     */
    protected val sets: SetL1Store
        get() = requireNotNull(store.sets()) { "${store.javaClass.name} does not provide the sets capability." }

    protected fun key(): String = keyspace.storageKey(UUID.randomUUID().toString())

    /**
     * 订阅者收到的编码 key（S4）：存储 key 去掉 keyspace 的前缀。
     */
    protected fun encoded(storageKey: String): String = storageKey.removePrefix(keyspace.storagePrefix)

    protected fun token(): Token = Token(node, sequence.incrementAndGet())

    protected fun lease(token: Token = token(), ttl: Duration = Duration.ofSeconds(30)) = LeaseRequest(token, ttl)

    protected fun frame(vararg keys: String): ByteArray = InvalidationFrame.encode(node, keys.toList())

    protected fun scalar(text: String) = Envelope.Scalar(text.toByteArray())

    /**
     * 像没有数据源的缓存那样写入的值：带载荷的摘要（RFC 0005 §4.7，D1）。
     */
    protected fun digested(text: String, writer: Token? = null) =
        Envelope.Scalar(text.toByteArray(), Versioned.NO_VERSION, writer, Digest.ofPayload(text.toByteArray()))

    private fun L1Read.hit(): L1Read.Hit = this as? L1Read.Hit ?: throw AssertionError("Expected a hit but was $this.")

    /**
     * 存储提供的能力与 [expectedCapabilities] 完全相同（多出来的也算失败），每个访问器每次返回同一个对象或总是 `null`。
     */
    @Test
    fun storeProvidesExactlyTheDeclaredCapabilities() {
        CapabilityDeclaration.verify(backend, expectedCapabilities)
    }

    /**
     * 读取的时限（RFC 0005 round 3 D6）：L1 卡顿时，带 `timeout` 的读取在大约这么长的时间内以 L1 故障失败（不是取消），不等命令超时。
     * 结果未知，这次读取请求的 lease 由存储释放（K14）：恢复之后 key 不再是 lease 占位，不必等到 `lease.ttl`。
     */
    @Test
    fun readTimeoutBoundsTheCallAndReleasesItsLease() {
        val single = key()
        val batch = key()
        val paused = pause()
        assumeTrue(paused != null, "${backend.javaClass.name} cannot be paused.")
        val reads = mutableListOf<suspend () -> Unit>(
            { store.read(single, lease(ttl = Duration.ofMinutes(10)), null, READ_TIMEOUT) },
            { store.readAll(listOf(batch), mapOf(batch to lease(ttl = Duration.ofMinutes(10))), READ_TIMEOUT) },
        )
        if (L1Capability.CONDITIONAL in expectedCapabilities) {
            val conditional = requireNotNull(store.conditional())
            reads.add { conditional.readIfChanged(single, null, READ_TIMEOUT) }
        }
        requireNotNull(paused).use {
            reads.forEachIndexed { index, read ->
                val started = System.nanoTime()
                val failure = runCatching { l1 { read() } }.exceptionOrNull()
                val elapsed = Duration.ofNanos(System.nanoTime() - started)
                failure.assert().withFailMessage { "read #$index did not time out" }.isNotNull()
                failure.assert().isNotInstanceOf(java.util.concurrent.CancellationException::class.java)
                elapsed.assert().withFailMessage { "read #$index failed after $elapsed" }
                    .isGreaterThanOrEqualTo(READ_TIMEOUT.dividedBy(2))
                    .isLessThan(READ_TIMEOUT_LIMIT)
            }
        }
        awaitCondition("leases released after the pause") {
            val outcome = runCatching { Suspending.await { store.readAll(listOf(single, batch), emptyMap(), null) } }
            outcome.getOrNull()?.values?.all { it == L1Read.Miss(false) } == true
        }
    }

    @Test
    fun missWithoutLeaseRequestGrantsNothing() = l1 {
        val key = key()
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun missGrantsLeaseAtomicallyAndOthersSeeLeased() = l1 {
        val key = key()
        store.read(key, lease(), null, null).assert().isEqualTo(L1Read.Miss(true))
        val other = store.read(key, lease(), null, null)
        other.assert().isInstanceOf(L1Read.Leased::class.java)
        (other as L1Read.Leased).remaining.isNegative.assert().isFalse()
    }

    /**
     * 凭 lease 填充写入值。lease 占位不带版本，这次填充没有让版本前进：`advanced` 为 `false`（RFC 0005 §4.7 的定义，round 3 K12），
     * 否则引擎会按写入处理本节点、不填充 L2。
     */
    @Test
    fun fillWithLeaseTokenWritesValue() = l1 {
        val key = key()
        val lease = lease()
        store.read(key, lease, null, null)
        store.fill(Fill(key, lease.token, scalar("v"), null)).assert().isEqualTo(L1Fill.Filled(advanced = false))
        val hit = store.read(key, lease(), null, null).hit()
        hit.envelope.assert().isEqualTo(scalar("v"))
        hit.ttl.assert().isNull()
        hit.format.assert().isEqualTo(backend.format)
    }

    @Test
    fun fillWithForeignTokenIsRejected() = l1 {
        val key = key()
        store.read(key, lease(), null, null)
        // 令牌不匹配：没有当前版本
        store.fill(Fill(key, token(), scalar("v"), null)).assert().isEqualTo(L1Fill.Rejected(currentVersion = null))
        store.read(key, null, null, null).assert().isInstanceOf(L1Read.Leased::class.java)
    }

    @Test
    fun fillWithoutLeaseIsRejected() = l1 {
        val key = key()
        store.fill(Fill(key, token(), scalar("v"), null)).assert().isInstanceOf(L1Fill.Rejected::class.java)
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun invalidateDestroysLeaseSoStaleFillFails() = l1 {
        val key = key()
        val lease = lease()
        store.read(key, lease, null, null)
        store.invalidate(listOf(key), frame(key))
        store.fill(Fill(key, lease.token, scalar("stale"), null)).assert().isInstanceOf(L1Fill.Rejected::class.java)
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun invalidateAfterFillDeletesValue() = l1 {
        val key = key()
        val lease = lease()
        store.read(key, lease, null, null)
        store.fill(Fill(key, lease.token, scalar("v"), null))
        store.invalidate(listOf(key), frame(key))
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun putDestroysLeaseAndOverwrites() = l1 {
        val key = key()
        val lease = lease()
        store.read(key, lease, null, null)
        store.put(key, scalar("new"), null, frame(key))
        store.fill(Fill(key, lease.token, scalar("stale"), null)).assert().isInstanceOf(L1Fill.Rejected::class.java)
        store.read(key, null, null, null).hit().envelope.assert().isEqualTo(scalar("new"))
    }

    @Test
    fun releaseDeletesOwnLeaseOnly() = l1 {
        val key = key()
        val lease = lease()
        store.read(key, lease, null, null)
        store.release(key, token())
        store.read(key, null, null, null).assert().isInstanceOf(L1Read.Leased::class.java)
        store.release(key, lease.token)
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun releaseOnAbsentKeyIsNoOp() = l1 {
        val key = key()
        store.release(key, token())
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun expiredLeaseCanBeTakenAgainAndOldTokenFails() = l1 {
        val key = key()
        val first = lease(ttl = shortTtl)
        store.read(key, first, null, null)
        elapse(shortTtl.multipliedBy(2))
        val second = lease()
        store.read(key, second, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.fill(Fill(key, first.token, scalar("stale"), null)).assert().isInstanceOf(L1Fill.Rejected::class.java)
        store.fill(Fill(key, second.token, scalar("fresh"), null)).assert().isInstanceOf(L1Fill.Filled::class.java)
    }

    @Test
    fun fillResetsExpirySoLeaseTtlDoesNotLeak() = l1 {
        val key = key()
        val lease = lease(ttl = shortTtl)
        store.read(key, lease, null, null)
        store.fill(Fill(key, lease.token, scalar("v"), Duration.ofHours(1)))
        elapse(shortTtl.multipliedBy(2))
        val hit = store.read(key, null, null, null).hit()
        requireNotNull(hit.ttl).assert().isGreaterThan(Duration.ofMinutes(59))
    }

    @Test
    fun putWithTtlExpires() = l1 {
        val key = key()
        store.put(key, scalar("v"), shortTtl, frame(key))
        requireNotNull(store.read(key, null, null, null).hit().ttl).assert().isLessThanOrEqualTo(shortTtl)
        elapse(shortTtl.multipliedBy(2))
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun putWithoutTtlRemovesPreviousExpiry() = l1 {
        val key = key()
        store.put(key, scalar("v1"), shortTtl, frame(key))
        store.put(key, scalar("v2"), null, frame(key))
        elapse(shortTtl.multipliedBy(2))
        store.read(key, null, null, null).hit().ttl.assert().isNull()
    }

    @Test
    fun absentEnvelopeIsANegativeEntry() = l1 {
        val key = key()
        val lease = lease()
        store.read(key, lease, null, null)
        store.fill(
            Fill(key, lease.token, Envelope.Absent, Duration.ofMinutes(1))
        ).assert().isInstanceOf(L1Fill.Filled::class.java)
        store.read(key, lease(), null, null).hit().envelope.assert().isEqualTo(Envelope.Absent)
    }

    /**
     * 段（RFC 0005 §4.3“keyspace 与段”、§4.7，D1、D2）随每个写入标量的操作保存、读取原样返回：写入标识与摘要经 put、putAll、fill、
     * fillAll，声明了条件读写时的 putIfMatch（写入没有版本的值，标识与摘要照样保存），声明了按版本写入时的 putIfNewer 与带版本的
     * 整体写入（版本、标识与摘要同时保存）；readAll 同样返回它们，readIfChanged 返回的值也带它们。没有段的写入读出 `null`。
     * 存储不校验、不重算摘要：给出什么就保存什么（这里的摘要都是载荷的摘要，校验见引擎的用例）。
     */
    @Test
    fun segmentsRoundTripThroughEveryScalarWrite() = l1 {
        val versioned = L1Capability.VERSIONED in expectedCapabilities
        val version = if (versioned) 7L else Versioned.NO_VERSION
        fun written(text: String, version: Long = Versioned.NO_VERSION) =
            Envelope.Scalar(text.toByteArray(), version, token(), Digest.ofPayload(text.toByteArray()))

        val expected = LinkedHashMap<String, Envelope.Scalar>()
        val put = written("put", version)
        store.put(key().also { expected[it] = put }, put, null, frame())
        val all = listOf(written("all-1"), written("all-2", version))
        store.putAll(all.map { Put(key().also { key -> expected[key] = it }, it, null) }, frame())
        val fill = written("fill", version)
        val fillKey = key()
        val fillLease = lease()
        store.read(fillKey, fillLease, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.fill(Fill(fillKey, fillLease.token, fill, null)).assert().isInstanceOf(L1Fill.Filled::class.java)
        expected[fillKey] = fill
        val batch = written("fill-all")
        val batchKey = key()
        val batchLease = lease()
        store.read(batchKey, batchLease, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.fillAll(listOf(Fill(batchKey, batchLease.token, batch, null))).single()
            .assert().isInstanceOf(L1Fill.Filled::class.java)
        expected[batchKey] = batch
        store.conditional()?.let { conditional ->
            // 条件写入写入没有版本的值：信封自带的版本被忽略，写入标识与摘要照样保存
            val cas = written("cas", version)
            val casKey = key()
            conditional.putIfMatch(casKey, null, cas, null, frame()).assert()
                .isEqualTo(L1ConditionalWrite.Written)
            expected[casKey] = Envelope.Scalar(cas.bytes, Versioned.NO_VERSION, cas.writer, cas.digest)
        }
        store.versioned()?.let { versionedStore ->
            val newer = written("newer")
            val newerKey = key()
            versionedStore.putIfNewer(newerKey, newer, 9, null, frame()).assert()
                .isEqualTo(L1VersionedWrite.Written)
            expected[newerKey] = Envelope.Scalar(newer.bytes, 9, newer.writer, newer.digest)
        }
        val plain = key()
        store.put(plain, scalar("plain"), null, frame())

        expected.forEach { (key, envelope) ->
            store.read(key, null, null, null).hit().envelope.assert().isEqualTo(envelope)
        }
        val batched = store.readAll(expected.keys + plain, emptyMap(), null)
        expected.forEach { (key, envelope) -> batched.getValue(key).hit().envelope.assert().isEqualTo(envelope) }
        (store.read(plain, null, null, null).hit().envelope as Envelope.Scalar).writer.assert().isNull()
        (batched.getValue(plain).hit().envelope as Envelope.Scalar).writer.assert().isNull()
        (batched.getValue(plain).hit().envelope as Envelope.Scalar).digest.assert().isNull()
        store.conditional()?.let { conditional ->
            expected.filterValues { it.version == Versioned.NO_VERSION }.forEach { (key, envelope) ->
                conditional.readIfChanged(
                    key,
                    null,
                    null
                ).assert().isEqualTo(L1ConditionalRead.Changed(envelope, backend.format))
            }
        }
    }

    /**
     * 带摘要段的值是权威标量（RFC 0005 §4.7，D4）：读取从不为它授予刷新令牌，条件回收从不回收它（即使指纹相同）。
     */
    @Test
    fun digestContentGetsNoRefreshTokenAndIsNeverReclaimed() = l1 {
        val key = key()
        store.put(key, digested("v"), Duration.ofSeconds(60), frame(key))
        val refresh = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, refresh, null).hit().refreshGranted.assert().isFalse()
        store.reclaim(key, ContentFingerprint.of(scalar("v")), lease()).assert().isFalse()
        store.read(key, null, null, null).hit().envelope.assert().isEqualTo(digested("v"))
    }

    /**
     * 条件读写比较存储中保存的摘要段（RFC 0005 §4.7）：不重算载荷，摘要段等于已知摘要即 `Unchanged`；返回的值带摘要段；
     * 条件写入的冲突附摘要段。
     */
    @Test
    fun conditionalOperationsCompareTheStoredDigestSegment() = l1 {
        assumeDeclared(L1Capability.CONDITIONAL)
        val conditional = requireNotNull(store.conditional())
        val key = key()
        val claimed = Digest.ofPayload("claimed".toByteArray())
        val stored = Envelope.Scalar("actual".toByteArray(), Versioned.NO_VERSION, null, claimed)
        store.put(key, stored, null, frame(key))
        conditional.readIfChanged(key, claimed, null).assert().isEqualTo(L1ConditionalRead.Unchanged(backend.format))
        val actual = Digest.ofPayload("actual".toByteArray())
        conditional.readIfChanged(
            key,
            actual,
            null
        ).assert().isEqualTo(L1ConditionalRead.Changed(stored, backend.format))
        conditional.putIfMatch(
            key,
            actual,
            digested("x"),
            null,
            frame(key)
        ).assert().isEqualTo(L1ConditionalWrite.Conflict(claimed))
        conditional.putIfMatch(
            key,
            claimed,
            digested("next"),
            null,
            frame(key)
        ).assert().isEqualTo(L1ConditionalWrite.Written)
        store.read(key, null, null, null).hit().envelope.assert().isEqualTo(digested("next"))
    }

    /**
     * 条件删除（`ConditionalL1Store.deleteIfMatch`）比较存储中保存的摘要段：相等时删除，返回 `Written`；不相等时冲突，附当前摘要段，
     * 不修改；没有值（不存在、lease 占位、不带版本的负缓存）时冲突，当前摘要为 `null`；没有摘要段的值与带版本的内容是读不懂的内容，
     * 拒绝且不修改 key。
     */
    @Test
    fun deleteIfMatchComparesTheStoredDigestSegment() = l1 {
        assumeDeclared(L1Capability.CONDITIONAL)
        val conditional = requireNotNull(store.conditional())
        val key = key()
        val stored = digested("v")
        store.put(key, stored, null, frame(key))
        val wrong = Digest.ofPayload("other".toByteArray())
        conditional.deleteIfMatch(key, wrong, frame(key)).assert().isEqualTo(L1ConditionalWrite.Conflict(stored.digest))
        store.read(key, null, null, null).hit().envelope.assert().isEqualTo(stored)
        val digest = requireNotNull(stored.digest)
        conditional.deleteIfMatch(key, digest, frame(key)).assert().isEqualTo(L1ConditionalWrite.Written)
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
        conditional.deleteIfMatch(key, digest, frame(key)).assert().isEqualTo(L1ConditionalWrite.Conflict(null))

        val leased = key()
        store.read(leased, lease(), null, null)
        conditional.deleteIfMatch(leased, wrong, frame(leased)).assert().isEqualTo(L1ConditionalWrite.Conflict(null))
        store.read(leased, null, null, null).assert().isInstanceOf(L1Read.Leased::class.java)

        val bare = key()
        store.put(bare, scalar("bare"), null, frame(bare))
        conditional.deleteIfMatch(bare, Digest.ofPayload("bare".toByteArray()), frame(bare))
            .assert().isEqualTo(L1ConditionalWrite.Unreadable(backend.format))
        store.read(bare, null, null, null).hit().envelope.assert().isEqualTo(scalar("bare"))

        if (L1Capability.VERSIONED in expectedCapabilities) {
            val versioned = key()
            requireNotNull(store.versioned()).putIfNewer(versioned, digested("x"), 3, null, frame(versioned))
            conditional.deleteIfMatch(versioned, Digest.ofPayload("x".toByteArray()), frame(versioned))
                .assert().isEqualTo(L1ConditionalWrite.Unreadable(backend.format))
            store.read(versioned, null, null, null).assert().isInstanceOf(L1Read.Hit::class.java)
        }
    }

    /**
     * 条件读写只认带摘要段、不带版本的值（RFC 0005 §4.7）：没有摘要段的值（不是 `Store` 写入的）是读不懂的内容，
     * 条件读取返回 `Unreadable`、条件写入拒绝且不修改 key；不存在、lease 占位与不带版本的负缓存是“没有值”。
     */
    @Test
    fun conditionalOperationsRefuseValuesWithoutADigest() = l1 {
        assumeDeclared(L1Capability.CONDITIONAL)
        val conditional = requireNotNull(store.conditional())
        val bare = key()
        store.put(bare, scalar("bare"), null, frame(bare))
        conditional.readIfChanged(bare, null, null).assert().isEqualTo(L1ConditionalRead.Unreadable(backend.format))
        conditional.putIfMatch(bare, null, digested("x"), null, frame(bare))
            .assert().isEqualTo(L1ConditionalWrite.Unreadable(backend.format))
        conditional.putIfMatch(bare, Digest.ofPayload("bare".toByteArray()), digested("x"), null, frame(bare))
            .assert().isEqualTo(L1ConditionalWrite.Unreadable(backend.format))
        store.read(bare, null, null, null).hit().envelope.assert().isEqualTo(scalar("bare"))

        val negative = key()
        val negativeLease = lease()
        store.read(negative, negativeLease, null, null)
        store.fill(Fill(negative, negativeLease.token, Envelope.Absent, Duration.ofMinutes(1)))
        val leased = key()
        store.read(leased, lease(), null, null)
        listOf(key(), negative, leased).forEach {
            conditional.readIfChanged(it, null, null).assert().isEqualTo(L1ConditionalRead.Absent)
        }
    }

    /**
     * 版本段中不得是 `Versioned.NO_VERSION`（RFC 0005 §4.7，K15）：按版本写入以 [IllegalArgumentException] 拒绝它，不修改 key、
     * 不广播（值与墓碑都是如此）。
     */
    @Test
    fun putIfNewerRejectsNoVersion() = l1 {
        assumeDeclared(L1Capability.VERSIONED)
        val versionedStore = requireNotNull(store.versioned())
        val key = key()
        store.put(key, scalar("kept"), null, frame())
        listOf(scalar("v"), Envelope.Absent).forEach { envelope ->
            runCatching {
                versionedStore.putIfNewer(key, envelope, Versioned.NO_VERSION, null, frame())
            }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        store.read(key, null, null, null).hit().envelope.assert().isEqualTo(scalar("kept"))
    }

    /**
     * 保留版本的失效（`E`，RFC 0005 §4.7）上签发超过 lease 寿命的 lease 可以被抢占（同刷新令牌），被抢占者的填充因令牌不匹配被拒绝；
     * 版本不小于 `E` 的填充写入并广播（`advanced`）。
     */
    @Test
    fun leaseOnEvictedContentIsPreemptedAfterItsTtl() = l1 {
        assumeDeclared(L1Capability.VERSIONED)
        val versionedStore = requireNotNull(store.versioned())
        val key = key()
        versionedStore.putIfNewer(key, scalar("v5"), 5, null, frame(key))
        versionedStore.evictKeepingVersion(
            key,
            Duration.ofMinutes(1),
            frame(key)
        ).assert().isEqualTo(L1Eviction.Evicted)
        val first = lease(ttl = shortTtl)
        store.read(key, first, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.read(key, lease(), null, null).assert().isInstanceOf(L1Read.Leased::class.java)
        elapse(shortTtl.multipliedBy(2))
        // 抢占按申请者的 lease 寿命判断（同一个缓存的节点用同样的 lease 策略）
        val second = lease(ttl = shortTtl)
        store.read(key, second, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.fill(
            Fill(key, first.token, Envelope.Scalar("late".toByteArray(), 6), null)
        ).assert().isEqualTo(L1Fill.Rejected(null))
        store.fill(
            Fill(key, second.token, Envelope.Scalar("v6".toByteArray(), 6), null)
        ).assert().isEqualTo(L1Fill.Filled(true))
        (store.read(key, null, null, null).hit().envelope as Envelope.Scalar).version.assert().isEqualTo(6L)
    }

    @Test
    fun refreshTokenGrantedOnlyInsideWindowAndOnce() = l1 {
        val key = key()
        store.put(key, scalar("v"), Duration.ofSeconds(60), frame(key))
        val outside = RefreshRequest(Duration.ofSeconds(1), token(), Duration.ofSeconds(30))
        store.read(key, null, outside, null).hit().refreshGranted.assert().isFalse()

        val first = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, first, null).hit().refreshGranted.assert().isTrue()
        val second = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, second, null).hit().refreshGranted.assert().isFalse()

        store.fill(Fill(key, second.token, scalar("other"), null)).assert().isInstanceOf(L1Fill.Rejected::class.java)
        store.fill(
            Fill(key, first.token, scalar("refreshed"), Duration.ofSeconds(60))
        ).assert().isInstanceOf(L1Fill.Filled::class.java)
        store.read(key, null, null, null).hit().envelope.assert().isEqualTo(scalar("refreshed"))
    }

    @Test
    fun refreshTokenIsNotGrantedOnForeverValues() = l1 {
        val key = key()
        store.put(key, scalar("v"), null, frame(key))
        val refresh = RefreshRequest(Duration.ofDays(1), token(), Duration.ofSeconds(30))
        store.read(key, null, refresh, null).hit().refreshGranted.assert().isFalse()
    }

    @Test
    fun expiredRefreshTokenCanBePreempted() = l1 {
        val key = key()
        store.put(key, scalar("v"), Duration.ofSeconds(60), frame(key))
        val first = RefreshRequest(Duration.ofSeconds(120), token(), shortTtl)
        store.read(key, null, first, null).hit().refreshGranted.assert().isTrue()
        elapse(shortTtl.multipliedBy(2))
        val second = RefreshRequest(Duration.ofSeconds(120), token(), shortTtl)
        store.read(key, null, second, null).hit().refreshGranted.assert().isTrue()
        store.fill(Fill(key, first.token, scalar("late"), null)).assert().isInstanceOf(L1Fill.Rejected::class.java)
    }

    @Test
    fun putDestroysRefreshToken() = l1 {
        val key = key()
        store.put(key, scalar("v"), Duration.ofSeconds(60), frame(key))
        val refresh = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, refresh, null)
        store.put(key, scalar("written"), Duration.ofSeconds(60), frame(key))
        store.fill(
            Fill(key, refresh.token, scalar("refreshed"), null)
        ).assert().isInstanceOf(L1Fill.Rejected::class.java)
        store.read(key, null, null, null).hit().envelope.assert().isEqualTo(scalar("written"))
    }

    @Test
    fun releaseClearsRefreshTokenButKeepsValue() = l1 {
        val key = key()
        store.put(key, scalar("v"), Duration.ofSeconds(60), frame(key))
        val refresh = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, refresh, null)
        store.release(key, refresh.token)
        store.read(key, null, null, null).hit().envelope.assert().isEqualTo(scalar("v"))
        val again = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, again, null).hit().refreshGranted.assert().isTrue()
    }

    @Test
    fun reclaimConvertsUnchangedContentToLease() = l1 {
        val key = key()
        store.put(key, scalar("corrupt"), null, frame(key))
        val lease = lease()
        store.reclaim(key, ContentFingerprint.of(scalar("other")), lease).assert().isFalse()
        store.reclaim(key, ContentFingerprint.of(scalar("corrupt")), lease).assert().isTrue()
        store.read(key, null, null, null).assert().isInstanceOf(L1Read.Leased::class.java)
        store.fill(Fill(key, lease.token, scalar("v"), null)).assert().isInstanceOf(L1Fill.Filled::class.java)
    }

    @Test
    fun reclaimOnAbsentKeyFails() = l1 {
        store.reclaim(key(), ContentFingerprint.of(scalar("x")), lease()).assert().isFalse()
    }

    @Test
    fun readAllReturnsEveryKeyAndGrantsRequestedLeases() = l1 {
        val hitKey = key()
        val leasedKey = key()
        val missKey = key()
        val plainKey = key()
        store.put(hitKey, scalar("v"), null, frame(hitKey))
        store.read(leasedKey, lease(), null, null)
        val missLease = lease()
        val reads = store.readAll(
            listOf(hitKey, leasedKey, missKey, plainKey),
            mapOf(missKey to missLease, leasedKey to lease()),
            null
        )
        reads.keys.assert().containsExactly(hitKey, leasedKey, missKey, plainKey)
        reads.getValue(hitKey).hit().envelope.assert().isEqualTo(scalar("v"))
        reads.getValue(leasedKey).assert().isInstanceOf(L1Read.Leased::class.java)
        reads.getValue(missKey).assert().isEqualTo(L1Read.Miss(true))
        reads.getValue(plainKey).assert().isEqualTo(L1Read.Miss(false))

        val outcomes = store.fillAll(
            listOf(Fill(missKey, missLease.token, scalar("m"), null), Fill(plainKey, token(), scalar("p"), null))
        )
        outcomes.size.assert().isEqualTo(2)
        outcomes[0].assert().isInstanceOf(L1Fill.Filled::class.java)
        // 令牌不匹配：没有当前版本
        outcomes[1].assert().isEqualTo(L1Fill.Rejected(null))
    }

    @Test
    fun membersAreAddedAtomicallyOverLeases() = l1 {
        assumeDeclared(L1Capability.SETS)
        val key = key()
        val lease = lease()
        store.read(key, lease, null, null)
        sets.addMembers(key, listOf("a".toByteArray()), null, frame(key)).assert().isNull()
        store.fill(
            Fill(key, lease.token, Envelope.Members(listOf("seed".toByteArray())), null)
        ).assert().isInstanceOf(L1Fill.Rejected::class.java)
        sets.addMembers(
            key,
            listOf("b".toByteArray(), "a".toByteArray()),
            null,
            frame(key)
        ).assert().isNull()
        val members = (store.read(key, null, null, null).hit().envelope as Envelope.Members).members.map { String(it) }
        members.assert().containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun addMembersReplacesNegativeEntry() = l1 {
        assumeDeclared(L1Capability.SETS)
        val key = key()
        val lease = lease()
        store.read(key, lease, null, null)
        store.fill(Fill(key, lease.token, Envelope.Absent, Duration.ofMinutes(1)))
        sets.addMembers(key, listOf("a".toByteArray()), null, frame(key)).assert().isNull()
        val hit = store.read(key, null, null, null).hit()
        (hit.envelope as Envelope.Members).members.map { String(it) }.assert().containsExactly("a")
        hit.ttl.assert().isNull()
    }

    @Test
    fun removeMembersOnlyAffectsSetsAndDeletesWhenEmpty() = l1 {
        assumeDeclared(L1Capability.SETS)
        val key = key()
        sets.removeMembers(key, listOf("a".toByteArray()), null, frame(key)).assert().isNull()
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))

        sets.addMembers(key, listOf("a".toByteArray(), "b".toByteArray()), null, frame(key))
        sets.removeMembers(
            key,
            listOf("a".toByteArray()),
            Duration.ofMinutes(1),
            frame(key)
        ).assert().isNull()
        val hit = store.read(key, null, null, null).hit()
        (hit.envelope as Envelope.Members).members.map { String(it) }.assert().containsExactly("b")
        hit.ttl.assert().isNotNull()

        sets.removeMembers(key, listOf("b".toByteArray()), null, frame(key)).assert().isNull()
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 集合成员的增删只有“检查并写入”：key 中是不能当作本协议集合的内容（这里是本协议的标量值）时拒绝，返回它的格式版本，
     * 不修改 key（内容与到期时间都不变），从不整体覆盖它。
     */
    @Test
    fun memberWritesRefuseUnreadableContentAndKeepIt() = l1 {
        assumeDeclared(L1Capability.SETS)
        val key = key()
        store.put(key, scalar("v"), Duration.ofMinutes(1), frame(key))
        sets.addMembers(key, listOf("a".toByteArray()), null, frame(key)).assert().isEqualTo(backend.format)
        sets.removeMembers(
            key,
            listOf("v".toByteArray()),
            null,
            frame(key)
        ).assert().isEqualTo(backend.format)
        val hit = store.read(key, null, null, null).hit()
        hit.envelope.assert().isEqualTo(scalar("v"))
        hit.ttl.assert().isNotNull()
    }

    /**
     * 订阅时回调 `onReset`；每个写入恰好广播它的帧（声明了 [L1Capability.SETS] 时含集合成员的增删）；关闭订阅之后不再收到。
     */
    @Test
    fun subscribeResetsAndWritesBroadcastFrames() = l1 {
        val received = CopyOnWriteArrayList<Pair<List<String>, NodeId>>()
        val resets = AtomicInteger()
        val listener = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                if (keys.isNotEmpty()) received.add(keys to origin)
            }

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        val subscription = backend.channel.subscribe(keyspace, listener)
        awaitCondition("reset on subscribe") { resets.get() >= 1 }
        val key = key()
        val keys = mutableListOf<String>()
        store.put(key, scalar("v"), null, frame(key))
        keys.add(key)
        store.invalidate(listOf(key), frame(key))
        keys.add(key)
        if (L1Capability.SETS in expectedCapabilities) {
            sets.addMembers(key, listOf("a".toByteArray()), null, frame(key))
            keys.add(key)
            sets.removeMembers(key, listOf("a".toByteArray()), null, frame(key))
            keys.add(key)
        }
        if (L1Capability.CONDITIONAL in expectedCapabilities) {
            val conditional = requireNotNull(store.conditional())
            val stored = digested("cas")
            conditional.putIfMatch(key, null, stored, null, frame(key))
            keys.add(key)
            conditional.deleteIfMatch(key, requireNotNull(stored.digest), frame(key))
            keys.add(key)
        }
        if (!backend.atomicBroadcast) {
            keys.forEach { backend.channel.publish(keyspace, frame(it)) }
        }
        awaitCondition("${keys.size} broadcasts") { received.size >= keys.size }
        received.all { it.first == listOf(encoded(key)) && it.second == node }.assert().isTrue()

        subscription.close()
        val countAfterClose = received.size
        store.invalidate(listOf(key), frame(key))
        if (!backend.atomicBroadcast) {
            backend.channel.publish(keyspace, frame(key))
        }
        Thread.sleep(50)
        received.size.assert().isEqualTo(countAfterClose)
    }

    /**
     * 读不懂的帧（未知版本、预留的帧版本 2、截断、尾随字节）按该频道的 `onReset` 处理，不丢弃（RFC 0005 §4.7，D3）：
     * 丢弃的就是一次失效。零 key 的帧是合法帧（存储创建时的频道探测），照常投递，不重置；之后的帧照常投递。
     */
    @Test
    fun unreadableFramesResetTheChannelSubscribers() = l1 {
        val received = CopyOnWriteArrayList<List<String>>()
        val resets = AtomicInteger()
        val listener = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                if (origin == node) received.add(keys)
            }

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        backend.channel.subscribe(keyspace, listener).use {
            awaitCondition("reset on subscribe") { resets.get() >= 1 }
            val key = key()
            val unknownVersion = frame(key).also { it[0] = 9 }
            val reserved = frame(key).also { it[0] = InvalidationFrame.NAMESPACE_RESET_VERSION }
            val truncated = frame(key).let { it.copyOf(it.size - 1) }
            val trailing = frame(key) + byteArrayOf(1)
            val unreadable = listOf(unknownVersion, reserved, truncated, trailing)
            unreadable.forEachIndexed { index, frame ->
                backend.channel.publish(keyspace, frame)
                awaitCondition("reset by unreadable frame #$index") { resets.get() >= 2 + index }
            }
            backend.channel.publish(keyspace, frame())
            backend.channel.publish(keyspace, frame(key))
            awaitCondition("frames after the unreadable ones") { received.size >= 2 }
            received.assert().containsExactly(emptyList(), listOf(encoded(key)))
            resets.get().assert().isEqualTo(1 + unreadable.size)
        }
    }

    /**
     * 订阅者收到编码 key（RFC 0005 §4.9，S4）：帧中的存储 key 去掉 `{keyPrefix}{namespace}:`，不属于这个 keyspace 的 key 被去掉，
     * 其余顺序不变。同一 keyspace 的订阅者按订阅的先后依次回调，前一个返回之后才回调下一个：缓存在创建时订阅，
     * 之后订阅的外部监听者收到通知时，引擎已经淘汰了本地副本。
     */
    @Test
    fun listenersReceiveEncodedKeysInSubscriptionOrder() = l1 {
        val calls = CopyOnWriteArrayList<String>()
        val resets = AtomicInteger()
        fun recording(name: String) = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                if (origin == node) {
                    calls.add("$name:$keys")
                    calls.add("$name:returned")
                }
            }

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        backend.channel.subscribe(keyspace, recording("first")).use {
            backend.channel.subscribe(keyspace, recording("second")).use {
                awaitCondition("reset on subscribe") { resets.get() >= 2 }
                val first = UUID.randomUUID().toString()
                val second = UUID.randomUUID().toString()
                val foreign = Keyspace("tck-other:", namespace).storageKey(first)
                backend.channel.publish(
                    keyspace,
                    frame(keyspace.storageKey(first), foreign, "x", keyspace.storageKey(second))
                )
                awaitCondition("both listeners") { calls.size >= 4 }
                calls.assert().containsExactly(
                    "first:[$first, $second]",
                    "first:returned",
                    "second:[$first, $second]",
                    "second:returned"
                )
            }
        }
    }

    /**
     * 频道按 keyspace 划分（RFC 0005 §4.7）：命名空间相同、前缀不同的两个 keyspace 收不到彼此的帧。
     */
    @Test
    fun channelsAreSeparatedByKeyspace() = l1 {
        val other = Keyspace("tck-other:", namespace)
        val ours = CopyOnWriteArrayList<List<String>>()
        val theirs = CopyOnWriteArrayList<List<String>>()
        val resets = AtomicInteger()
        fun recording(into: MutableList<List<String>>) = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                if (keys.isNotEmpty()) into.add(keys)
            }

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        backend.channel.subscribe(keyspace, recording(ours)).use {
            backend.channel.subscribe(other, recording(theirs)).use {
                awaitCondition("reset on subscribe") { resets.get() >= 2 }
                val ourKey = key()
                val theirKey = other.storageKey(UUID.randomUUID().toString())
                backend.channel.publish(keyspace, frame(ourKey))
                backend.channel.publish(other, frame(theirKey))
                awaitCondition("both frames") { ours.isNotEmpty() && theirs.isNotEmpty() }
                // 同一频道上的帧有序：两者共用频道时，对方的订阅者先收到我们的帧，断言确定失败
                ours.assert().containsExactly(listOf(encoded(ourKey)))
                theirs.assert().containsExactly(listOf(theirKey.removePrefix(other.storagePrefix)))
            }
        }
    }

    /**
     * 批量覆盖写入：每个 key 的转换同 put（任意状态 → 新内容，销毁 lease 与刷新令牌，重设到期时间）。
     */
    @Test
    fun putAllOverwritesEveryKeyAndDestroysTokens() = l1 {
        val leased = key()
        val refreshed = key()
        val absent = key()
        val set = key()
        val expiring = key()
        val lease = lease()
        store.read(leased, lease, null, null)
        store.put(refreshed, scalar("v"), Duration.ofSeconds(60), frame(refreshed))
        val refresh = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(refreshed, null, refresh, null).hit().refreshGranted.assert().isTrue()
        store.put(expiring, scalar("old"), shortTtl, frame(expiring))

        store.putAll(
            listOf(
                Put(leased, scalar("a"), null),
                Put(refreshed, scalar("b"), Duration.ofMinutes(1)),
                Put(absent, Envelope.Absent, Duration.ofMinutes(1)),
                Put(set, Envelope.Members(listOf("x".toByteArray(), "y".toByteArray())), null),
                Put(expiring, scalar("e"), null),
            ),
            frame(leased, refreshed, absent, set, expiring)
        )

        store.fill(Fill(leased, lease.token, scalar("stale"), null)).assert().isInstanceOf(L1Fill.Rejected::class.java)
        store.fill(
            Fill(refreshed, refresh.token, scalar("stale"), null)
        ).assert().isInstanceOf(L1Fill.Rejected::class.java)
        elapse(shortTtl.multipliedBy(2))
        store.read(leased, null, null, null).hit().let {
            it.envelope.assert().isEqualTo(scalar("a"))
            it.ttl.assert().isNull()
        }
        store.read(refreshed, null, null, null).hit().let {
            it.envelope.assert().isEqualTo(scalar("b"))
            requireNotNull(it.ttl).assert().isLessThanOrEqualTo(Duration.ofMinutes(1))
        }
        store.read(absent, null, null, null).hit().envelope.assert().isEqualTo(Envelope.Absent)
        store.read(set, null, null, null).hit().let {
            (it.envelope as Envelope.Members).members.map { member -> String(member) }
                .assert().containsExactlyInAnyOrder("x", "y")
            it.ttl.assert().isNull()
        }
        store.read(expiring, null, null, null).hit().let {
            it.envelope.assert().isEqualTo(scalar("e"))
            it.ttl.assert().isNull()
        }
    }

    /**
     * 批量覆盖写入的广播：每个 key 恰好随其写入广播一次（实现可以拆成若干帧，但不重复广播）。
     */
    @Test
    fun putAllBroadcastsEveryKeyExactlyOnce() = l1 {
        val received = CopyOnWriteArrayList<Pair<List<String>, NodeId>>()
        val resets = AtomicInteger()
        val listener = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                if (keys.isNotEmpty()) received.add(keys to origin)
            }

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        backend.channel.subscribe(keyspace, listener).use {
            awaitCondition("reset on subscribe") { resets.get() >= 1 }
            val keys = (1..5).map { key() }
            val frame = InvalidationFrame.encode(node, keys)
            store.putAll(keys.map { Put(it, scalar(it), null) }, frame)
            if (!backend.atomicBroadcast) {
                backend.channel.publish(keyspace, frame)
            }
            val encodedKeys = keys.map { encoded(it) }
            awaitCondition("every key broadcast") { received.flatMap { it.first }.toSet() == encodedKeys.toSet() }
            Thread.sleep(50)
            received.flatMap { it.first }.sorted().assert().isEqualTo(encodedKeys.sorted())
            received.all { it.second == node }.assert().isTrue()
            keys.forEach { store.read(it, null, null, null).hit().envelope.assert().isEqualTo(scalar(it)) }
        }
    }

    protected companion object {
        /**
         * 读取时限用例的 `timeout`。
         */
        val READ_TIMEOUT: Duration = Duration.ofMillis(200)

        /**
         * 读取失败所用时间的上限：远小于默认的命令超时（2 s），证明时限是这次调用的 `timeout`，不是命令超时。
         */
        val READ_TIMEOUT_LIMIT: Duration = Duration.ofMillis(1500)
    }
}
