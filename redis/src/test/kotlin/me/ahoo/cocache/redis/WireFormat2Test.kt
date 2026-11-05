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

package me.ahoo.cocache.redis

import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
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
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.redis.foreign.ForeignContent
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 线格式 2（RFC 0005 §4.7，`docs/architecture.md` §4.2）在真实 Redis 上的字节级用例，以构造的原始值检验脚本与客户端共用的格式规则：
 * - 头部的每种组合（无段、版本段、摘要段、扩展区及其组合）：写入的字节，读取、条件读取、回收与刷新令牌都按同一个载荷偏移；
 * - 摘要段（M4c）：条件读写只读头部比较、与载荷大小无关，篡改的摘要段由引擎发现；
 * - 状态 `E`（M4d）：44 字节、保留到期时间的原地修改，`FILL` 只读头部的开头；
 * - 扩展区中未知的 TLV 被跳过；
 * - 未知的必须理解的位（bit 2–6）对每个脚本都等同于更高格式；
 * - 格式号低于本格式的内容（格式 1）是读不懂的内容：有数据源的缓存回收后回源，没有数据源的缓存失败；
 * - 集合的 `!f` 与 `!m`（K10）；
 * - `FILL` / `RELEASE` 只读核心头部（K19）；
 * - 版本段中的 `NO_VERSION` 被脚本拒绝（K15）。
 *
 * 单机与集群（[RedisTestSupport]）。
 */
class WireFormat2Test {
    private val run = UUID.randomUUID().toString().substring(0, 8)
    private val namespace = "wire-$run"
    private val node = NodeId.random()
    private val sequence = AtomicLong()
    private val closeables = ArrayList<AutoCloseable>()
    private val backend = RedisTestSupport.backend().closing()
    private val store: L1Store = backend.store(Keyspace("cocache:", namespace))

    @AfterEach
    fun destroy() {
        closeables.reversed().forEach { runCatching { it.close() } }
        RedisTestSupport.deleteKeys("*$run*")
    }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables.add(it) }

    private fun key(id: String) = "cocache:$namespace:$id"

    private fun token() = Token(node, sequence.incrementAndGet())

    private fun lease() = LeaseRequest(token(), Duration.ofMinutes(1))

    private fun refresh() = RefreshRequest(Duration.ofMinutes(5), token(), Duration.ofMinutes(1))

    private fun frame(): ByteArray = InvalidationFrame.encode(node, emptyList())

    private fun <T> l1(block: suspend () -> T): T = Suspending.await(block)

    private fun raw(key: String): ByteArray? = RedisTestSupport.withBinaryCommands { it.get(key.toByteArray()) }

    private fun write(key: String, value: ByteArray, ttl: Duration = Duration.ofMinutes(1)) {
        RedisTestSupport.withBinaryCommands { it.set(key.toByteArray(), value, SetArgs.Builder.px(ttl.toMillis())) }
    }

    private fun runtime(): CacheRuntime = CacheRuntime.builder().backend(backend).build().closing()

    private fun hit(read: L1Read): L1Read.Hit = read as? L1Read.Hit ?: throw AssertionError("Expected a hit but was $read.")

    /**
     * 写入方构造的每种头部（RFC 0005 §4.7“写入哪些段”）：没有段时 36 字节、标志位为 0；版本段（bit 0）、摘要段（bit 1）、扩展区
     * （bit 7，写入标识一个 TLV）依次在后。put、putAll、fill、putIfMatch、putIfNewer 的字节相同，读取原样返回版本、摘要与写入标识。
     */
    @Test
    fun writersLayOutEveryHeaderCombination(): Unit = l1 {
        val payload = "p".toByteArray()
        val writer = token()
        val digest = Digest.ofPayload(payload)
        val ext = extension(writerTlv(writer))
        val cases = listOf(
            Envelope.Scalar(payload) to header() + payload,
            Envelope.Scalar(payload, 7) to header(flags = 0x01) + version(7) + payload,
            Envelope.Scalar(payload, Versioned.NO_VERSION, writer) to header(flags = 0x80) + ext + payload,
            Envelope.Scalar(payload, 7, writer) to header(flags = 0x81) + version(7) + ext + payload,
            Envelope.Scalar(payload, Versioned.NO_VERSION, null, digest) to header(flags = 0x02) + digest.bytes + payload,
            // 权威标量（Store）：摘要段与写入标识，60 字节的段
            Envelope.Scalar(payload, Versioned.NO_VERSION, writer, digest) to header(flags = 0x82) + digest.bytes + ext + payload,
            Envelope.Scalar(payload, 7, writer, digest) to header(flags = 0x83) + version(7) + digest.bytes + ext + payload,
        )
        cases.forEachIndexed { index, (envelope, bytes) ->
            store.put(key("put-$index"), envelope, null, frame())
            store.putAll(listOf(Put(key("all-$index"), envelope, null)), frame())
            val lease = lease()
            store.read(key("fill-$index"), lease, null, null)
            store.fill(
                Fill(key("fill-$index"), lease.token, envelope, null)
            ).assert().isInstanceOf(L1Fill.Filled::class.java)
            listOf("put", "all", "fill").forEach {
                raw(key("$it-$index")).assert().isEqualTo(bytes)
                val read = hit(store.read(key("$it-$index"), null, null, null))
                read.envelope.assert().isEqualTo(envelope)
                read.format.assert().isEqualTo(RedisScripts.FORMAT)
            }
        }
        // 条件写入写入没有版本的值（带摘要段与写入标识）；按版本写入带版本段，墓碑没有载荷也不带写入标识
        store.conditionalSurface.putIfMatch(
            key("cas"),
            null,
            Envelope.Scalar(payload, 7, writer, digest),
            null,
            frame()
        )
            .assert().isEqualTo(L1ConditionalWrite.Written)
        raw(key("cas")).assert().isEqualTo(header(flags = 0x82) + digest.bytes + ext + payload)
        store.versionedSurface.putIfNewer(key("newer"), Envelope.Scalar(payload, 1, writer), 9, null, frame())
            .assert().isEqualTo(L1VersionedWrite.Written)
        raw(key("newer")).assert().isEqualTo(header(flags = 0x81) + version(9) + extension(writerTlv(writer)) + payload)
        store.versionedSurface.putIfNewer(key("newer"), Envelope.Absent, 10, null, frame())
            .assert().isEqualTo(L1VersionedWrite.Written)
        raw(key("newer")).assert().isEqualTo(header(state = 'M', flags = 0x01) + version(10))
        store.put(key("absent"), Envelope.Absent, null, frame())
        raw(key("absent")).assert().isEqualTo(header(state = 'M'))
        // lease 占位不带段：头部仍是 36 字节
        store.read(key("lease"), lease(), null, null).assert().isEqualTo(L1Read.Miss(true))
        raw(key("lease"))?.size.assert().isEqualTo(RedisScripts.HEADER_SIZE)
        raw(key("lease"))?.get(RedisScripts.FLAGS_OFFSET).assert().isEqualTo(0.toByte())
    }

    /**
     * 读者按标志位计算载荷偏移，脚本与客户端一致：读取、条件读取与回收（载荷的指纹）在每种组合上都取同一段载荷。
     * 刷新令牌授予没有摘要段的值（带版本段的照常授予，M4d），回收只针对没有版本段也没有摘要段的值（K15、D4）；
     * 条件读写只认带摘要段、不带版本段的值，比较头部中的摘要段。
     */
    @Test
    fun readersUseThePayloadOffsetOfTheFlags(): Unit = l1 {
        val payload = "payload".toByteArray()
        val digest = Digest.ofPayload(payload)
        for (case in payloadOffsetCases(payload, digest)) {
            val key = key(case.id)
            write(key, case.raw)
            val read = hit(store.read(key, null, refresh(), null))
            read.envelope.assert().isEqualTo(case.envelope)
            read.format.assert().isEqualTo(RedisScripts.FORMAT)
            read.refreshGranted.assert().withFailMessage { "${case.id}: refresh token" }.isEqualTo(case.refreshed)
            val conditional = store.conditionalSurface.readIfChanged(key, digest, null)
            if (case.conditional) {
                conditional.assert().isEqualTo(L1ConditionalRead.Unchanged(RedisScripts.FORMAT))
                store.conditionalSurface.readIfChanged(key, null, null)
                    .assert().isEqualTo(L1ConditionalRead.Changed(case.envelope, RedisScripts.FORMAT))
            } else {
                conditional.assert().withFailMessage { "${case.id}: conditional" }
                    .isEqualTo(L1ConditionalRead.Unreadable(RedisScripts.FORMAT))
            }
            store.reclaim(key, ContentFingerprint.of(Envelope.Scalar(payload)), lease())
                .assert().withFailMessage { "${case.id}: reclaim" }.isEqualTo(case.reclaimed)
        }
        // 段超出值的长度：头部损坏，按读不懂的内容（格式 0、整个值）处理，回收比较整个值的指纹
        val truncated = header(flags = 0x81) + version(1) + byteArrayOf(0, 40)
        write(key("truncated"), truncated)
        val corrupt = hit(store.read(key("truncated"), null, null, null))
        corrupt.format.assert().isEqualTo(RedisScripts.UNRECOGNIZED)
        corrupt.envelope.assert().isEqualTo(Envelope.Scalar(truncated))
        store.reclaim(key("truncated"), ContentFingerprint.of(Envelope.Scalar(truncated)), lease()).assert().isTrue()
    }

    /**
     * [readersUseThePayloadOffsetOfTheFlags] 的一种头部组合：读取的信封、能否取得刷新令牌、能否回收、条件读写能否比较。
     */
    private class OffsetCase(
        val id: String,
        val raw: ByteArray,
        val envelope: Envelope.Scalar,
        val refreshed: Boolean,
        val reclaimed: Boolean,
        val conditional: Boolean = false
    )

    private fun payloadOffsetCases(payload: ByteArray, digest: Digest): List<OffsetCase> {
        val writer = token()
        return listOf(
            OffsetCase("none", header() + payload, Envelope.Scalar(payload), true, true),
            OffsetCase(
                "version",
                header(flags = 0x01) + version(-3) + payload,
                Envelope.Scalar(payload, -3),
                true,
                false
            ),
            OffsetCase(
                "extension",
                header(flags = 0x80) + extension(writerTlv(writer)) + payload,
                Envelope.Scalar(payload, Versioned.NO_VERSION, writer),
                true,
                true
            ),
            OffsetCase(
                "both",
                header(flags = 0x81) + version(5) + extension(writerTlv(writer)) + payload,
                Envelope.Scalar(payload, 5, writer),
                true,
                false
            ),
            OffsetCase(
                "digest",
                header(flags = 0x02) + digest.bytes + payload,
                Envelope.Scalar(payload, Versioned.NO_VERSION, null, digest),
                false,
                false,
                conditional = true
            ),
            OffsetCase(
                "digest-extension",
                header(flags = 0x82) + digest.bytes + extension(writerTlv(writer)) + payload,
                Envelope.Scalar(payload, Versioned.NO_VERSION, writer, digest),
                false,
                false,
                conditional = true
            ),
            OffsetCase(
                "all-segments",
                header(flags = 0x83) + version(6) + digest.bytes + extension(writerTlv(writer)) + payload,
                Envelope.Scalar(payload, 6, writer, digest),
                false,
                false
            ),
        )
    }

    /**
     * 扩展区（RFC 0005 §4.7，D2）：读者逐个读 TLV，跳过未知的类型（含 2 字节长度的），取第一个写入标识；长度不对或超出扩展区的
     * TLV 不影响载荷（扩展区整体可以忽略）。
     */
    @Test
    fun unknownTlvsAreSkipped(): Unit = l1 {
        val payload = "v".toByteArray()
        val writer = token()
        val long = ByteArray(200) { 7 }
        val cases = mapOf(
            "skipped" to extension(tlv(9, "abc".toByteArray()), tlv(200, long), writerTlv(writer)) to writer,
            "first-wins" to extension(writerTlv(writer), writerTlv(token())) to writer,
            "wrong-length" to extension(tlv(1, ByteArray(10))) to null,
            "overrun" to extension(tlv(9, "abc".toByteArray()), byteArrayOf(1, 50, 1, 2, 3)) to null,
            "empty" to extension() to null,
        )
        val digest = Digest.ofPayload(payload)
        for ((case, expected) in cases) {
            val (id, area) = case
            write(key(id), header(flags = 0x82) + digest.bytes + area + payload)
            hit(store.read(key(id), null, null, null)).envelope.assert()
                .isEqualTo(Envelope.Scalar(payload, Versioned.NO_VERSION, expected, digest))
            store.conditionalSurface.readIfChanged(key(id), digest, null)
                .assert().isEqualTo(L1ConditionalRead.Unchanged(RedisScripts.FORMAT))
        }
    }

    /**
     * 未知的必须理解的位（bit 2–6）等同于更高格式（RFC 0005 §4.7，K15），对每个脚本都是如此：读取原样返回、报告为更高格式、
     * 不授予 lease 或刷新令牌（其中的 `L` 也不当作 lease）；填充与释放不匹配其中的令牌；回收、条件读写、按版本写入与集合元素写入
     * 都拒绝；内容一个字节都不变。整体覆盖（put）不依赖旧内容，照常写入。
     */
    @Test
    fun unknownMustUnderstandBitsAreAHigherFormatForEveryScript(): Unit = l1 {
        for (bit in 2..6) {
            val flags = 1 shl bit
            val held = token()
            val shapes = mapOf(
                "value-$bit" to header(flags = flags) + "p".toByteArray(),
                "held-$bit" to header(flags = flags, token = held.toBytes(), issued = System.currentTimeMillis().toDouble()) +
                    "p".toByteArray(),
                "lease-$bit" to header(state = 'L', flags = flags, token = held.toBytes(), issued = System.currentTimeMillis().toDouble()),
            )
            for ((id, bytes) in shapes) {
                val key = key(id)
                write(key, bytes)
                val read = hit(store.read(key, lease(), refresh(), null))
                read.format.assert().isEqualTo(RedisScripts.NEWER_FORMAT)
                read.refreshGranted.assert().isFalse()
                store.fill(Fill(key, held, Envelope.Scalar("w".toByteArray()), null))
                    .assert().isEqualTo(L1Fill.Rejected(null))
                store.release(key, held)
                store.reclaim(key, ContentFingerprint.of(Envelope.Scalar(bytes)), lease()).assert().isFalse()
                store.reclaim(
                    key,
                    ContentFingerprint.of(Envelope.Scalar("p".toByteArray())),
                    lease()
                ).assert().isFalse()
                val conditional = store.conditionalSurface
                conditional.readIfChanged(key, null, null)
                    .assert().isEqualTo(L1ConditionalRead.Unreadable(RedisScripts.NEWER_FORMAT))
                conditional.putIfMatch(key, null, Envelope.Scalar("w".toByteArray()), null, frame())
                    .assert().isEqualTo(L1ConditionalWrite.Unreadable(RedisScripts.NEWER_FORMAT))
                store.versionedSurface.putIfNewer(key, Envelope.Scalar("w".toByteArray()), 1, null, frame())
                    .assert().isEqualTo(L1VersionedWrite.Unreadable(RedisScripts.NEWER_FORMAT))
                store.setSurface.addMembers(
                    key,
                    listOf("m".toByteArray()),
                    null,
                    frame()
                ).assert().isEqualTo(RedisScripts.NEWER_FORMAT)
                store.setSurface.removeMembers(key, listOf("m".toByteArray()), null, frame())
                    .assert().isEqualTo(RedisScripts.NEWER_FORMAT)
                raw(key).assert().withFailMessage { "$id must stay unchanged" }.isEqualTo(bytes)
            }
            store.put(key("value-$bit"), Envelope.Scalar("w".toByteArray()), null, frame())
            hit(
                store.read(key("value-$bit"), null, null, null)
            ).envelope.assert().isEqualTo(Envelope.Scalar("w".toByteArray()))
        }
    }

    /**
     * 引擎按更高格式处理未知的必须理解的位：有数据源的缓存绕过 L1 回源（既不回收也不写回），没有数据源的缓存读取失败。
     */
    @Test
    fun cachesTreatUnknownMustUnderstandBitsAsAHigherFormat() {
        val bytes = header(flags = 0x04) + "p".toByteArray()
        val runtime = runtime()
        val derived = runtime.create(
            cacheSpec<String, String>(namespace) {
                codec = StringCodec
                loader = CacheLoader { "loaded-$it" }
            }
        )
        write(key("k"), bytes)
        derived["k"].assert().isEqualTo("loaded-k")
        raw(key("k")).assert().isEqualTo(bytes)
        requireNotNull(runtime.metrics(namespace)).reclaims.assert().isEqualTo(0L)

        val other = "$namespace-authoritative"
        val authoritative = runtime.create(storeSpec<String, String>(other) { codec = StringCodec })
        write("cocache:$other:k", bytes)
        val error = runCatching { authoritative["k"] }.exceptionOrNull()
        error.assert().isInstanceOf(CacheReadException::class.java)
        val cause = requireNotNull(error?.cause as? ForeignFormatException)
        cause.format.assert().isEqualTo(RedisScripts.NEWER_FORMAT)
        cause.newer.assert().isTrue()
        raw("cocache:$other:k").assert().isEqualTo(bytes)
    }

    /**
     * 格式 1 的值、lease 与令牌位置带版本标记的值。
     */
    private fun format1Shapes(sixToken: Token): Map<String, ByteArray> {
        val versionMark = byteArrayOf(0xC0.toByte()) + "cocache:version".toByteArray()
        val now = System.currentTimeMillis().toDouble()
        return mapOf(
            "value" to header(format = 1) + "six".toByteArray(),
            "lease" to header(format = 1, state = 'L', token = sixToken.toBytes(), issued = now),
            "versioned" to header(format = 1, token = versionMark + version(5), issued = Double.POSITIVE_INFINITY) +
                "six".toByteArray(),
        )
    }

    /**
     * 格式号低于本格式的内容（格式 1）是读不懂的内容，与格式 0 相同（RFC 0005 §4.7）：读取原样报告格式 1、不授予令牌，
     * 其中的 lease 不当作 lease、令牌不匹配；条件读写、按版本写入与集合元素写入拒绝；回收比较整个值的指纹。
     * 有数据源的缓存回收它并回源，写回本格式的值；`SetStore` 读到 `!f = '1'` 的集合时失败。
     */
    @Test
    fun derivedCacheReclaimsFormat1ContentAndReloads() {
        val sixToken = token()
        val shapes = format1Shapes(sixToken)
        l1 {
            for ((id, bytes) in shapes) {
                val key = key(id)
                write(key, bytes, Duration.ofMinutes(10))
                val read = hit(store.read(key, lease(), refresh(), null))
                read.format.assert().isEqualTo(1)
                read.refreshGranted.assert().isFalse()
                read.envelope.assert().isEqualTo(Envelope.Scalar(bytes))
                store.fill(Fill(key, sixToken, Envelope.Scalar("w".toByteArray()), null))
                    .assert().isEqualTo(L1Fill.Rejected(null))
                store.release(key, sixToken)
                store.conditionalSurface.readIfChanged(
                    key,
                    null,
                    null
                ).assert().isEqualTo(L1ConditionalRead.Unreadable(1))
                store.conditionalSurface.putIfMatch(key, null, Envelope.Scalar("w".toByteArray()), null, frame())
                    .assert().isEqualTo(L1ConditionalWrite.Unreadable(1))
                store.versionedSurface.putIfNewer(key, Envelope.Scalar("w".toByteArray()), 9, null, frame())
                    .assert().isEqualTo(L1VersionedWrite.Unreadable(1))
                store.setSurface.addMembers(key, listOf("m".toByteArray()), null, frame()).assert().isEqualTo(1)
                raw(key).assert().withFailMessage { "$id must stay unchanged" }.isEqualTo(bytes)
            }
        }

        val runtime = runtime()
        val derived = runtime.create(
            cacheSpec<String, String>(namespace) {
                codec = StringCodec
                loader = CacheLoader { "loaded-$it" }
            }
        )
        shapes.keys.forEach { id ->
            derived[id].assert().isEqualTo("loaded-$id")
            raw(key(id)).assert().isEqualTo(header() + "loaded-$id".toByteArray())
        }
        requireNotNull(runtime.metrics(namespace)).reclaims.assert().isEqualTo(shapes.size.toLong())

        val sets = "$namespace-set"
        val index = runtime.create(setStoreSpec<String, String>(sets) { elementCodec = StringCodec })
        ForeignContent.hash("cocache:$sets:k", mapOf("!f" to "1", "+a" to ""))
        runCatching { index["k"] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        runCatching {
            index.add(
                "k",
                listOf("b")
            )
        }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        ForeignContent.fields("cocache:$sets:k").assert().isEqualTo(mapOf("!f" to "1", "+a" to ""))
    }

    /**
     * `Store` 读到格式 1 时失败（回收就是删除唯一副本）：抛出 [CacheReadException]，`cause` 为 [ForeignFormatException]
     * （格式 1，不是更高的格式），值原样保留。批量读取同样如此。
     */
    @Test
    fun storeFailsOnFormat1Content() {
        val shapes = format1Shapes(token())
        val runtime = runtime()
        val authoritative = runtime.create(storeSpec<String, String>(namespace) { codec = StringCodec })
        shapes.forEach { (id, bytes) -> write(key(id), bytes, Duration.ofMinutes(10)) }
        for ((id, bytes) in shapes) {
            val error = runCatching { authoritative[id] }.exceptionOrNull()
            error.assert().isInstanceOf(CacheReadException::class.java)
            val cause = requireNotNull(error?.cause as? ForeignFormatException)
            cause.format.assert().isEqualTo(1)
            cause.newer.assert().isFalse()
            raw(key(id)).assert().isEqualTo(bytes)
        }
        runCatching {
            authoritative.getAll(
                shapes.keys
            )
        }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        shapes.forEach { (id, bytes) -> raw(key(id)).assert().isEqualTo(bytes) }
        requireNotNull(runtime.metrics(namespace)).reclaims.assert().isEqualTo(0L)
    }

    /**
     * 集合（RFC 0005 §4.7，K10）：写入 `!f = '2'`、不写 `!m`；`!m` 缺失或为 `'0'` 时可读，其余 `!` 字段忽略；`!m` 中有位（本版本没有
     * 已知的位）等同于更高格式；`!f` 为其它值时读不懂（无效为格式 0，其它数字原样报告）。
     */
    @Test
    fun setsCarryTheFormatAndAMustUnderstandBitmap(): Unit = l1 {
        val sets = store.setSurface
        sets.addMembers(key("written"), listOf("a".toByteArray()), null, frame()).assert().isNull()
        ForeignContent.fields(key("written")).assert().isEqualTo(mapOf("!f" to "2", "+a" to ""))

        ForeignContent.hash(key("zero"), mapOf("!f" to "2", "!m" to "0", "!x" to "ignored", "+a" to ""))
        val readable = hit(store.read(key("zero"), null, null, null))
        readable.format.assert().isEqualTo(RedisScripts.FORMAT)
        (readable.envelope as Envelope.Members).members.map { String(it) }.assert().containsExactly("a")
        sets.addMembers(key("zero"), listOf("b".toByteArray()), null, frame()).assert().isNull()

        ForeignContent.hash(key("bitmap"), mapOf("!f" to "2", "!m" to "1", "+a" to ""))
        hit(store.read(key("bitmap"), null, null, null)).format.assert().isEqualTo(RedisScripts.NEWER_FORMAT)
        sets.addMembers(
            key("bitmap"),
            listOf("b".toByteArray()),
            null,
            frame()
        ).assert().isEqualTo(RedisScripts.NEWER_FORMAT)
        sets.removeMembers(
            key("bitmap"),
            listOf("a".toByteArray()),
            null,
            frame()
        ).assert().isEqualTo(RedisScripts.NEWER_FORMAT)
        store.conditionalSurface.readIfChanged(key("bitmap"), null, null)
            .assert().isEqualTo(L1ConditionalRead.Unreadable(RedisScripts.NEWER_FORMAT))
        store.versionedSurface.putIfNewer(key("bitmap"), Envelope.Absent, 1, null, frame())
            .assert().isEqualTo(L1VersionedWrite.Unreadable(RedisScripts.NEWER_FORMAT))
        store.reclaim(
            key("bitmap"),
            ContentFingerprint.of(Envelope.Members(listOf("a".toByteArray()))),
            lease()
        ).assert().isFalse()
        ForeignContent.fields(key("bitmap")).assert().isEqualTo(mapOf("!f" to "2", "!m" to "1", "+a" to ""))

        mapOf("3" to 3, "x" to 0, "" to 0).forEach { (format, reported) ->
            val key = key("format-$format")
            ForeignContent.hash(key, mapOf("!f" to format, "+a" to ""))
            hit(store.read(key, null, null, null)).format.assert().isEqualTo(reported)
            sets.addMembers(key, listOf("b".toByteArray()), null, frame()).assert().isEqualTo(reported)
        }
        ForeignContent.hash(key("no-format"), mapOf("+a" to ""))
        hit(store.read(key("no-format"), null, null, null)).format.assert().isEqualTo(RedisScripts.UNRECOGNIZED)

        // 移空即删除
        sets.removeMembers(key("written"), listOf("a".toByteArray()), null, frame()).assert().isNull()
        ForeignContent.exists(key("written")).assert().isFalse()
    }

    /**
     * `FILL` 与 `RELEASE` 只读头部的开头（`FILL` 为 `GETRANGE key 0 43`，含版本段；`RELEASE` 为 `GETRANGE key 0 35`，K19）：以不能执行 `GET`
     * 的 ACL 用户对 8 MB 的值凭刷新令牌写回与释放，都成功（读取要 `GET`，同一个用户的读取失败，说明 ACL 生效）。
     */
    @Test
    fun fillAndReleaseReadOnlyTheCoreHeader() {
        val user = "cocache-no-get-$run"
        // 关闭顺序与登记相反：先关闭以该用户连接的后端，再删除用户
        closeables.add(AutoCloseable { RedisTestSupport.deleteAclUser(user) })
        val restricted = RedisTestSupport.aclBackend(user, listOf("~*", "&*", "+@all", "-get")).closing()
        val limited = restricted.store(Keyspace("cocache:", namespace))
        val large = Envelope.Scalar(ByteArray(8 shl 20) { 'x'.code.toByte() })
        l1 {
            for (id in listOf("fill", "release")) {
                store.put(key(id), large, Duration.ofMinutes(2), frame())
            }
            val filling = refresh()
            hit(store.read(key("fill"), null, filling, null)).refreshGranted.assert().isTrue()
            limited.fill(
                Fill(key("fill"), filling.token, Envelope.Scalar("small".toByteArray()), Duration.ofMinutes(1))
            )
                .assert().isInstanceOf(L1Fill.Filled::class.java)
            raw(key("fill")).assert().isEqualTo(header() + "small".toByteArray())

            val releasing = refresh()
            hit(store.read(key("release"), null, releasing, null)).refreshGranted.assert().isTrue()
            limited.release(key("release"), releasing.token)
            val header = RedisTestSupport.withBinaryCommands { it.getrange(key("release").toByteArray(), 0, 35) }
            header.assert().isEqualTo(header())
            RedisTestSupport.withBinaryCommands { it.strlen(key("release").toByteArray()) }
                .assert().isEqualTo((RedisScripts.HEADER_SIZE + (8 shl 20)).toLong())

            runCatching { limited.read(key("release"), null, null, null) }.exceptionOrNull()
                .assert().isInstanceOf(RedisCommandExecutionException::class.java)

            // 带版本的值：版本规则只多读版本段（GETRANGE key 0 43）
            store.put(key("versioned"), Envelope.Scalar(large.bytes, 5), Duration.ofMinutes(2), frame())
            val versioned = refresh()
            hit(store.read(key("versioned"), null, versioned, null)).refreshGranted.assert().isTrue()
            limited.fill(Fill(key("versioned"), versioned.token, Envelope.Scalar("v6".toByteArray(), 6), null))
                .assert().isEqualTo(L1Fill.Filled(true))
            raw(key("versioned")).assert().isEqualTo(header(flags = 0x01) + version(6) + "v6".toByteArray())
        }
    }

    /**
     * 条件读写只读头部（RFC 0005 §4.7）：`READ_IF_CHANGED` 与 `PUT_IF_MATCH` 先判断 `TYPE`，再以 `GETRANGE` 读最长的头部、以 `STRLEN`
     * 检查段的长度，Lua 不计算摘要。以不能执行 `GET` 的 ACL 用户对 8 MB 的权威标量做摘要未变的条件读取、冲突与写入，都成功；
     * 摘要变了的条件读取要返回正文（`GET`），同一个用户失败，说明 ACL 生效。成本因此与载荷大小无关。
     */
    @Test
    fun conditionalScriptsReadOnlyTheHeader() {
        val user = "cocache-cas-no-get-$run"
        closeables.add(AutoCloseable { RedisTestSupport.deleteAclUser(user) })
        val restricted = RedisTestSupport.aclBackend(user, listOf("~*", "&*", "+@all", "-get")).closing()
        val conditional = restricted.store(Keyspace("cocache:", namespace)).conditionalSurface
        val document = ByteArray(8 shl 20) { 'x'.code.toByte() }
        val digest = Digest.ofPayload(document)
        val writer = token()
        l1 {
            store.put(key("doc"), Envelope.Scalar(document, Versioned.NO_VERSION, writer, digest), null, frame())
            conditional.readIfChanged(
                key("doc"),
                digest,
                null
            ).assert().isEqualTo(L1ConditionalRead.Unchanged(RedisScripts.FORMAT))
            val next = Envelope.Scalar(
                "next".toByteArray(),
                Versioned.NO_VERSION,
                token(),
                Digest.ofPayload("next".toByteArray())
            )
            conditional.putIfMatch(key("doc"), Digest.ofPayload("stale".toByteArray()), next, null, frame())
                .assert().isEqualTo(L1ConditionalWrite.Conflict(digest))
            runCatching { conditional.readIfChanged(key("doc"), null, null) }.exceptionOrNull()
                .assert().isInstanceOf(RedisCommandExecutionException::class.java)
            conditional.putIfMatch(
                key("doc"),
                digest,
                next,
                null,
                frame()
            ).assert().isEqualTo(L1ConditionalWrite.Written)
            hit(store.read(key("doc"), null, null, null)).envelope.assert().isEqualTo(next)
        }
    }

    /**
     * 篡改的摘要段（RFC 0005 §4.7，K3）：存储原样返回段中的字节，摘要未变的条件读取只比较头部；拿到载荷的读取方重算并比较。
     * `Store` 的读取与条件读取都以 [CacheReadException] 失败（cause 为 [ForeignFormatException]，本格式），不修改 key；
     * 派生缓存照常读取（类型不符，D4），解码失败时也不回收（`RECLAIM` 拒绝带摘要段的内容）。
     */
    @Test
    fun tamperedDigestSegmentsAreCaughtByTheReader() {
        val payload = "actual".toByteArray()
        val claimed = Digest.ofPayload("claimed".toByteArray())
        val tampered = header(flags = 0x02) + claimed.bytes + payload
        write(key("k"), tampered)
        val runtime = runtime()
        val authoritative = runtime.create(storeSpec<String, String>(namespace) { codec = StringCodec })
        val readError = runCatching { authoritative["k"] }.exceptionOrNull()
        readError.assert().isInstanceOf(CacheReadException::class.java)
        requireNotNull(readError?.cause as? ForeignFormatException).format.assert().isEqualTo(RedisScripts.FORMAT)
        val conditionalError = runCatching { authoritative.getIfChanged("k", null) }.exceptionOrNull()
        conditionalError.assert().isInstanceOf(CacheReadException::class.java)
        authoritative.getIfChanged("k", claimed).assert().isEqualTo(me.ahoo.cocache.api.ConditionalGet.Unchanged)
        raw(key("k")).assert().isEqualTo(tampered)

        val other = runtime()
        val derived = other.create(
            cacheSpec<String, String>(namespace) {
                codec = StringCodec
                loader = CacheLoader { "loaded" }
            }
        )
        derived["k"].assert().isEqualTo("actual")
        requireNotNull(other.metrics(namespace)).typeMismatches.assert().isEqualTo(1L)
        raw(key("k")).assert().isEqualTo(tampered)
    }

    /**
     * 状态 `E`（RFC 0005 §4.7，P1）的字节：保留版本的失效写入 44 字节（核心头部，标志位只有 bit 0，版本段，没有载荷），令牌为零，
     * 到期时间为给出的寿命。在其上取得 lease 以 `SETRANGE 4` 写入令牌与签发时间、释放与被拒绝的填充清零令牌，都只触及字节 4–35，
     * 状态、版本段与到期时间不变。
     */
    @Test
    fun evictedStateKeepsItsBytesAndExpiry() {
        val payload = "payload".toByteArray()
        write(
            key("k"),
            header(flags = 0x81) + version(9) + extension(writerTlv(token())) + payload,
            Duration.ofMinutes(10)
        )
        val versioned = store.versionedSurface
        l1 {
            versioned.evictKeepingVersion(
                key("k"),
                Duration.ofMinutes(1),
                frame()
            )
        }.assert().isEqualTo(L1Eviction.Evicted)
        val evicted = header(state = 'E', flags = 0x01) + version(9)
        raw(key("k")).assert().isEqualTo(evicted)
        raw(key("k"))?.size.assert().isEqualTo(44)
        fun pttl() = RedisTestSupport.withBinaryCommands { it.pttl(key("k").toByteArray()) }
        pttl().assert().isBetween(50_000L, 60_000L)

        val lease = LeaseRequest(token(), Duration.ofMinutes(10))
        l1 { store.read(key("k"), lease, null, null) }.assert().isEqualTo(L1Read.Miss(true))
        val leased = requireNotNull(raw(key("k")))
        leased.copyOfRange(0, 4).assert().isEqualTo(evicted.copyOfRange(0, 4))
        leased.copyOfRange(4, 28).assert().isEqualTo(lease.token.toBytes())
        leased.copyOfRange(36, 44).assert().isEqualTo(version(9))
        pttl().assert().isBetween(1L, 60_000L)

        l1 {
            store.fill(
                Fill(key("k"), lease.token, Envelope.Scalar(payload, 8), null)
            )
        }.assert().isEqualTo(L1Fill.Rejected(9))
        raw(key("k")).assert().isEqualTo(evicted)
        pttl().assert().isBetween(1L, 60_000L)

        val released = LeaseRequest(token(), Duration.ofMinutes(10))
        l1 { store.read(key("k"), released, null, null) }.assert().isEqualTo(L1Read.Miss(true))
        l1 { store.release(key("k"), released.token) }
        raw(key("k")).assert().isEqualTo(evicted)
        pttl().assert().isBetween(1L, 60_000L)

        // E 上的填充只读头部的开头（GETRANGE key 0 43）：没有 GET 权限的用户也能写回
        val user = "cocache-e-no-get-$run"
        closeables.add(AutoCloseable { RedisTestSupport.deleteAclUser(user) })
        val limited = RedisTestSupport.aclBackend(user, listOf("~*", "&*", "+@all", "-get")).closing()
            .store(Keyspace("cocache:", namespace))
        val filling = LeaseRequest(token(), Duration.ofMinutes(10))
        l1 { store.read(key("k"), filling, null, null) }.assert().isEqualTo(L1Read.Miss(true))
        l1 { limited.fill(Fill(key("k"), filling.token, Envelope.Scalar(payload, 9), Duration.ofMinutes(5))) }
            .assert().isEqualTo(L1Fill.Filled(true))
        raw(key("k")).assert().isEqualTo(header(flags = 0x01) + version(9) + payload)
        pttl().assert().isBetween(290_000L, 300_000L)
    }

    /**
     * 版本段中不得是 `NO_VERSION`（RFC 0005 §4.7，K15）：客户端从不发出它，脚本在第一次写入之前以错误回复拒绝；
     * 按版本写入的形状必须带版本段。`PUT_ALL` 先检查全部形状，一个都不写。
     */
    @Test
    fun scriptsRefuseNoVersionInAVersionSegment() {
        val noVersion = byteArrayOf('V'.code.toByte(), 0x01) + version(Versioned.NO_VERSION)
        val good = byteArrayOf('V'.code.toByte(), 0x01) + version(1)
        val payload = "p".toByteArray()
        fun refused(script: RedisScripts.Script, keys: List<String>, vararg args: ByteArray) {
            val error = runCatching {
                RedisTestSupport.withBinaryCommands {
                    it.eval<Any>(
                        String(script.source),
                        ScriptOutputType.MULTI,
                        keys.map(String::toByteArray).toTypedArray(),
                        *args
                    )
                }
            }.exceptionOrNull()
            error.assert().isInstanceOf(RedisCommandExecutionException::class.java)
            keys.forEach { raw(it).assert().isNull() }
        }
        val empty = ByteArray(0)
        refused(RedisScripts.PUT, listOf(key("put")), noVersion, "0".toByteArray(), empty, frame(), payload)
        refused(RedisScripts.PUT_IF_NEWER, listOf(key("newer")), noVersion, "0".toByteArray(), empty, frame(), payload)
        refused(
            RedisScripts.PUT_IF_NEWER,
            listOf(key("newer")),
            byteArrayOf('V'.code.toByte(), 0),
            "0".toByteArray(),
            empty,
            frame(),
            payload
        )
        // 同一个 slot 的两个 key：第一个形状合规、第二个不合规
        refused(
            RedisScripts.PUT_ALL,
            listOf(key("{all}a"), key("{all}b")),
            empty,
            frame(),
            good,
            "0".toByteArray(),
            "1".toByteArray(),
            payload,
            noVersion,
            "0".toByteArray(),
            "1".toByteArray(),
            payload
        )
        // 填充：lease 仍在（拒绝发生在 DEL 之前）
        val lease = lease()
        l1 { store.read(key("fill"), lease, null, null) }.assert().isEqualTo(L1Read.Miss(true))
        val error = runCatching {
            RedisTestSupport.withBinaryCommands {
                it.eval<Any>(
                    String(RedisScripts.FILL.source),
                    ScriptOutputType.MULTI,
                    arrayOf(key("fill").toByteArray()),
                    lease.token.toBytes(),
                    noVersion,
                    "0".toByteArray(),
                    empty,
                    empty,
                    payload
                )
            }
        }.exceptionOrNull()
        error.assert().isInstanceOf(RedisCommandExecutionException::class.java)
        l1 { store.read(key("fill"), null, null, null) }.assert().isInstanceOf(L1Read.Leased::class.java)
    }

    private companion object {
        fun header(
            format: Int = RedisScripts.FORMAT,
            state: Char = 'V',
            flags: Int = 0,
            token: ByteArray = ByteArray(Token.SIZE),
            issued: Double = 0.0
        ): ByteArray = ByteBuffer.allocate(RedisScripts.HEADER_SIZE)
            .put(RedisScripts.MAGIC)
            .put(format.toByte())
            .put(state.code.toByte())
            .put(flags.toByte())
            .put(token)
            .putDouble(issued)
            .array()

        fun version(version: Long): ByteArray = ByteBuffer.allocate(RedisScripts.VERSION_SIZE).putLong(version).array()

        /**
         * 一个 TLV：长度小于 128 时 1 字节，否则 2 字节（`0x80 | 高 7 位`、低 8 位）。
         */
        fun tlv(type: Int, value: ByteArray): ByteArray {
            val length = if (value.size < RedisScripts.TLV_SHORT_LENGTH_LIMIT) {
                byteArrayOf(value.size.toByte())
            } else {
                byteArrayOf((0x80 or (value.size shr 8)).toByte(), value.size.toByte())
            }
            return byteArrayOf(type.toByte()) + length + value
        }

        fun writerTlv(writer: Token): ByteArray = tlv(1, writer.toBytes())

        /**
         * 扩展区：2 字节总长（其后 TLV 的字节数）+ TLV。
         */
        fun extension(vararg tlvs: ByteArray): ByteArray {
            val body = tlvs.fold(ByteArray(0)) { acc, tlv -> acc + tlv }
            return ByteBuffer.allocate(RedisScripts.EXTENSION_LENGTH_SIZE).putShort(body.size.toShort()).array() + body
        }
    }
}
