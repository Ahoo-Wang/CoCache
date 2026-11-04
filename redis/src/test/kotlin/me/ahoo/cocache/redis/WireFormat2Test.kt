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
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.redis.migration.Redis5x
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 线格式 2（RFC 0005 §4.7，`docs/architecture.md` §3.3）在真实 Redis 上的字节级用例，以构造的原始值检验脚本与客户端共用的格式规则：
 * - 头部的每种组合（无段、只有版本段、只有扩展区、两者都有，以及 M4c 才写入的摘要段）：写入的字节，读取、条件读取、回收与刷新令牌
 *   都按同一个载荷偏移；
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
     * 写入方构造的每种头部（RFC 0005 §4.7“写入哪些段”）：没有版本也没有写入标识时 36 字节、标志位为 0；版本段（bit 0）在前，
     * 扩展区（bit 7，写入标识一个 TLV）在后。put、putAll、fill、putIfMatch、putIfNewer 的字节相同，读取原样返回版本与写入标识。
     */
    @Test
    fun writersLayOutEveryHeaderCombination() = l1 {
        val payload = "p".toByteArray()
        val writer = token()
        val cases = listOf(
            Envelope.Scalar(payload) to header() + payload,
            Envelope.Scalar(payload, 7) to header(flags = 0x01) + version(7) + payload,
            Envelope.Scalar(payload, Versioned.NO_VERSION, writer) to header(flags = 0x80) + extension(writerTlv(writer)) + payload,
            Envelope.Scalar(payload, 7, writer) to header(flags = 0x81) + version(7) + extension(writerTlv(writer)) + payload,
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
        // 条件写入写入没有版本的值（只带写入标识）；按版本写入带版本段，墓碑没有载荷也不带写入标识
        store.conditionalSurface.putIfMatch(key("cas"), null, Envelope.Scalar(payload, 7, writer), null, frame())
            .assert().isEqualTo(L1ConditionalWrite.Written)
        raw(key("cas")).assert().isEqualTo(header(flags = 0x80) + extension(writerTlv(writer)) + payload)
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
     * 读者按标志位计算载荷偏移，脚本与客户端一致：读取、条件读取（载荷的摘要）与回收（载荷的指纹）在每种组合上都取同一段载荷。
     * 只有没有段或只有扩展区的值能取得刷新令牌、能被回收；带版本段（暂不授予，M4d 放开）或摘要段（D4）的都不能。
     */
    @Test
    fun readersUseThePayloadOffsetOfTheFlags() = l1 {
        val payload = "payload".toByteArray()
        val writer = token()
        val digest = ByteArray(RedisScripts.DIGEST_SIZE) { 0x5A }
        data class Case(val id: String, val raw: ByteArray, val envelope: Envelope.Scalar, val tokens: Boolean)
        val cases = listOf(
            Case("none", header() + payload, Envelope.Scalar(payload), true),
            Case("version", header(flags = 0x01) + version(-3) + payload, Envelope.Scalar(payload, -3), false),
            Case(
                "extension",
                header(flags = 0x80) + extension(writerTlv(writer)) + payload,
                Envelope.Scalar(payload, Versioned.NO_VERSION, writer),
                true
            ),
            Case(
                "both",
                header(flags = 0x81) + version(5) + extension(writerTlv(writer)) + payload,
                Envelope.Scalar(payload, 5, writer),
                false
            ),
            Case("digest", header(flags = 0x02) + digest + payload, Envelope.Scalar(payload), false),
            Case(
                "all-segments",
                header(flags = 0x83) + version(6) + digest + extension(writerTlv(writer)) + payload,
                Envelope.Scalar(payload, 6, writer),
                false
            ),
        )
        for (case in cases) {
            val key = key(case.id)
            write(key, case.raw)
            val read = hit(store.read(key, null, refresh(), null))
            read.envelope.assert().isEqualTo(case.envelope)
            read.format.assert().isEqualTo(RedisScripts.FORMAT)
            read.refreshGranted.assert().withFailMessage { "${case.id}: refresh token" }.isEqualTo(case.tokens)
            store.conditionalSurface.readIfChanged(key, Digest.of(Envelope.Scalar(payload)), null)
                .assert().isEqualTo(L1ConditionalRead.Unchanged(RedisScripts.FORMAT))
            store.reclaim(key, ContentFingerprint.of(Envelope.Scalar(payload)), lease())
                .assert().withFailMessage { "${case.id}: reclaim" }.isEqualTo(case.tokens)
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
     * 扩展区（RFC 0005 §4.7，D2）：读者逐个读 TLV，跳过未知的类型（含 2 字节长度的），取第一个写入标识；长度不对或超出扩展区的
     * TLV 不影响载荷（扩展区整体可以忽略）。
     */
    @Test
    fun unknownTlvsAreSkipped() = l1 {
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
        for ((case, expected) in cases) {
            val (id, area) = case
            write(key(id), header(flags = 0x80) + area + payload)
            hit(store.read(key(id), null, null, null)).envelope.assert()
                .isEqualTo(Envelope.Scalar(payload, Versioned.NO_VERSION, expected))
            store.conditionalSurface.readIfChanged(key(id), Digest.of(Envelope.Scalar(payload)), null)
                .assert().isEqualTo(L1ConditionalRead.Unchanged(RedisScripts.FORMAT))
        }
    }

    /**
     * 未知的必须理解的位（bit 2–6）等同于更高格式（RFC 0005 §4.7，K15），对每个脚本都是如此：读取原样返回、报告为更高格式、
     * 不授予 lease 或刷新令牌（其中的 `L` 也不当作 lease）；填充与释放不匹配其中的令牌；回收、条件读写、按版本写入与集合元素写入
     * 都拒绝；内容一个字节都不变。整体覆盖（put）不依赖旧内容，照常写入。
     */
    @Test
    fun unknownMustUnderstandBitsAreAHigherFormatForEveryScript() = l1 {
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
        val authoritative = runtime.create(cacheSpec<String, String>(other) { codec = StringCodec })
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
     * 有数据源的缓存回收它并回源，写回本格式的值；`SetCache` 读到 `!f = '1'` 的集合时失败。
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
        val index = runtime.create(setCacheSpec<String, String>(sets) { elementCodec = StringCodec })
        Redis5x.hash("cocache:$sets:k", mapOf("!f" to "1", "+a" to ""))
        runCatching { index["k"] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        runCatching {
            index.add(
                "k",
                listOf("b")
            )
        }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        Redis5x.fields("cocache:$sets:k").assert().isEqualTo(mapOf("!f" to "1", "+a" to ""))
    }

    /**
     * 没有数据源的缓存读到格式 1 时失败（回收就是删除唯一副本）：抛出 [CacheReadException]，`cause` 为 [ForeignFormatException]
     * （格式 1，不是更高的格式），值原样保留。批量读取同样如此。
     */
    @Test
    fun cacheWithoutASourceFailsOnFormat1Content() {
        val shapes = format1Shapes(token())
        val runtime = runtime()
        val authoritative = runtime.create(cacheSpec<String, String>(namespace) { codec = StringCodec })
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
    fun setsCarryTheFormatAndAMustUnderstandBitmap() = l1 {
        val sets = store.setSurface
        sets.addMembers(key("written"), listOf("a".toByteArray()), null, frame()).assert().isNull()
        Redis5x.fields(key("written")).assert().isEqualTo(mapOf("!f" to "2", "+a" to ""))

        Redis5x.hash(key("zero"), mapOf("!f" to "2", "!m" to "0", "!x" to "ignored", "+a" to ""))
        val readable = hit(store.read(key("zero"), null, null, null))
        readable.format.assert().isEqualTo(RedisScripts.FORMAT)
        (readable.envelope as Envelope.Members).members.map { String(it) }.assert().containsExactly("a")
        sets.addMembers(key("zero"), listOf("b".toByteArray()), null, frame()).assert().isNull()

        Redis5x.hash(key("bitmap"), mapOf("!f" to "2", "!m" to "1", "+a" to ""))
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
        Redis5x.fields(key("bitmap")).assert().isEqualTo(mapOf("!f" to "2", "!m" to "1", "+a" to ""))

        mapOf("3" to 3, "x" to 0, "" to 0).forEach { (format, reported) ->
            val key = key("format-$format")
            Redis5x.hash(key, mapOf("!f" to format, "+a" to ""))
            hit(store.read(key, null, null, null)).format.assert().isEqualTo(reported)
            sets.addMembers(key, listOf("b".toByteArray()), null, frame()).assert().isEqualTo(reported)
        }
        Redis5x.hash(key("no-format"), mapOf("+a" to ""))
        hit(store.read(key("no-format"), null, null, null)).format.assert().isEqualTo(RedisScripts.UNRECOGNIZED)

        // 移空即删除
        sets.removeMembers(key("written"), listOf("a".toByteArray()), null, frame()).assert().isNull()
        Redis5x.exists(key("written")).assert().isFalse()
    }

    /**
     * `FILL` 与 `RELEASE` 只读核心头部（`GETRANGE key 0 35`，K19）：以不能执行 `GET` 的 ACL 用户对 8 MB 的值凭刷新令牌写回与释放，
     * 两者都成功（读取要 `GET`，同一个用户的读取失败，说明 ACL 生效）。
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
        }
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
