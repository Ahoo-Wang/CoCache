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

package me.ahoo.cocache.redis.migration

import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.codec.RedisCodec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * 迁移定义、5.x 格式解码规则、选项、报告与客户端归属（不需要 Redis）。
 */
class CoCache5MigrationApiTest {
    private val strings = CoCache5Migration.scalar("token", StringCodec, CoCache5Format.string()).build()

    private fun CoCache5Migration.convert(type: String, raw: Any, sentinel: String = "_nil_"): Envelope =
        converter.convert(type, raw, sentinel)

    private fun Envelope.text(): String = String((this as Envelope.Scalar).bytes, Charsets.UTF_8)

    @Test
    fun defaultsFollowThe5xAnd6xDefaultLayouts() {
        strings.name.assert().isEqualTo("token")
        strings.keyPrefix.assert().isEqualTo("cocache:")
        strings.namespace.assert().isEqualTo("token")
        strings.sourcePrefix.assert().isEqualTo("cocache:token:")
        strings.targetPrefix.assert().isEqualTo("cocache:token:")
        strings.scans.assert().isTrue()
        strings.sourceKeys.assert().isEmpty()
        // 默认布局下目标等于来源：原地迁移
        strings.targetKeyOf("cocache:token:t1").assert().isEqualTo("cocache:token:t1")
        strings.toString().assert().isEqualTo(
            "CoCache5Migration(name='token', source='cocache:token:*', target='cocache:token:*')"
        )
    }

    @Test
    fun explicitKeysAndKeyMapperDecideTheTargetKey() {
        val explicit = CoCache5Migration.set(
            "index",
            StringCodec
        ).namespace("idx").keyPrefix("v6:").sourceKey("app:index", "").build()
        explicit.scans.assert().isFalse()
        explicit.targetKeyOf("app:index").assert().isEqualTo("v6:idx:")
        explicit.toString().assert().isEqualTo("CoCache5Migration(name='index', source=[app:index], target='v6:idx:*')")

        val mapped = CoCache5Migration.scalar("token", StringCodec, CoCache5Format.string())
            .sourcePrefix("app:token:")
            .keyMapper { it.removePrefix("id-") }
            .build()
        mapped.targetKeyOf("app:token:id-42").assert().isEqualTo("cocache:token:42")
        runCatching {
            mapped.targetKeyOf(
                "other:42"
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun invalidDefinitionsAreRejected() {
        fun fails(
            block: () -> Any
        ) = runCatching(block).exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        fails { CoCache5Migration.scalar(" ", StringCodec, CoCache5Format.string()).build() }
        fails { CoCache5Migration.scalar("token", StringCodec, CoCache5Format.string()).namespace(" ").build() }
        // 空前缀会扫描整个库
        fails { CoCache5Migration.scalar("token", StringCodec, CoCache5Format.string()).sourcePrefix("").build() }
        fails {
            CoCache5Migration.scalar(
                "token",
                StringCodec,
                CoCache5Format.string()
            ).sourcePrefix("a:").sourceKey("a:1", "1").build()
        }
        // 列出 key 时前缀可以为空
        CoCache5Migration.set("index", StringCodec).sourceKey("index", "").build().scans.assert().isFalse()
    }

    @Test
    fun stringFormatDecodesWithTheTargetCodecOrACustomDecoder() {
        strings.convert("string", "v1").text().assert().isEqualTo("v1")
        val custom = CoCache5Migration.scalar("token", StringCodec, CoCache5Format.string { it.reversed() }).build()
        custom.convert("string", "abc").text().assert().isEqualTo("cba")
    }

    @Test
    fun missingGuardsBecomeAbsentForEveryRedisType() {
        strings.convert("string", "_nil_").assert().isEqualTo(Envelope.Absent)
        strings.convert("string", "custom-nil", sentinel = "custom-nil").assert().isEqualTo(Envelope.Absent)
        val sets = CoCache5Migration.scalar(
            "roles",
            StringCodec,
            CoCache5Format.set { it.sorted().joinToString() }
        ).build()
        sets.convert("set", setOf("_nil_")).assert().isEqualTo(Envelope.Absent)
        // 含哨兵但不止一个成员：是真实数据
        sets.convert("set", setOf("_nil_", "a")).text().assert().isEqualTo("_nil_, a")
        val hashes = CoCache5Migration.scalar(
            "attrs",
            StringCodec,
            CoCache5Format.hash { it.toSortedMap().toString() }
        ).build()
        hashes.convert("hash", mapOf("_nil_" to "1700000000")).assert().isEqualTo(Envelope.Absent)
        hashes.convert("hash", mapOf("_nil_" to "1", "a" to "2")).text().assert().isEqualTo("{_nil_=1, a=2}")
        val index = CoCache5Migration.set("index", StringCodec).build()
        index.convert("set", setOf("_nil_")).assert().isEqualTo(Envelope.Absent)
    }

    @Test
    fun redisTypeMustMatchTheConfiguredFormat() {
        fun mismatch(
            block: () -> Any
        ) = runCatching(block).exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        mismatch { strings.convert("hash", mapOf("a" to "1")) }
        mismatch { strings.convert("list", listOf("a")) }
        mismatch {
            CoCache5Migration.scalar(
                "roles",
                StringCodec,
                CoCache5Format.set { it.first() }
            ).build().convert("string", "a")
        }
        mismatch {
            CoCache5Migration.scalar(
                "attrs",
                StringCodec,
                CoCache5Format.hash { it.size.toString() }
            ).build().convert("set", setOf("a"))
        }
        mismatch { CoCache5Migration.set("index", StringCodec).build().convert("string", "a") }
    }

    @Test
    fun setMembersAreReencodedWithTheElementCodec() {
        val members = CoCache5Migration.set(
            "index",
            StringCodec
        ).build().convert("set", linkedSetOf("p1", "p2")) as Envelope.Members
        members.members.map { String(it, Charsets.UTF_8) }.assert().containsExactly("p1", "p2")
    }

    @Test
    fun optionsAreValidated() {
        CoCache5JavaUsage.defaultOptions().assert().isEqualTo(
            CoCache5MigrationOptions("cocache5-backup:", "_nil_", 1000)
        )
        CoCache5JavaUsage.backupOptions("bk:").backupPrefix.assert().isEqualTo("bk:")
        runCatching {
            CoCache5MigrationOptions(
                backupPrefix = ""
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            CoCache5MigrationOptions(
                scanCount = 0
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun reportAndException() {
        val cache = CoCache5CacheReport("token", 3, 2, 1, 0, 1, 2)
        val report = CoCache5MigrationReport(listOf(cache))
        report.cache("token")?.negatives.assert().isEqualTo(1L)
        report.cache("absent").assert().isNull()
        report.toString().assert().contains("CoCache5MigrationReport(CoCache5CacheReport(name=token")
        val error = CoCache5MigrationException(report, "token", "cocache:token:t1", "boom")
        error.message.assert().isEqualTo("Migration[token] - key[cocache:token:t1] - boom")
        error.cause.assert().isNull()
        error.report.assert().isEqualTo(report)
    }

    @Test
    fun unsupportedClientIsRejected() {
        runCatching { CoCache5Migrator.of(mockk<AbstractRedisClient>(relaxed = true)) }
            .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 调用方的客户端（这里是 Cluster 客户端）：迁移器只关闭自己的连接，不关闭客户端。
     */
    @Test
    fun callerOwnedClusterClientIsNotShutDown() {
        val connection = mockk<StatefulRedisClusterConnection<String, ByteArray>>(relaxed = true)
        val client = mockk<RedisClusterClient>(relaxed = true)
        every { client.connect(any<RedisCodec<String, ByteArray>>()) } returns connection

        val migrator = CoCache5JavaUsage.of(client)
        migrator.options.assert().isEqualTo(CoCache5MigrationOptions())
        migrator.close()

        verify(exactly = 1) { connection.close() }
        verify(exactly = 0) { client.shutdown() }
    }

    /**
     * 自建客户端连接失败时抛出（且关闭自建的客户端，不泄漏线程）。
     */
    @Test
    fun unreachableRedisFailsFast() {
        val unreachable = RedisURI.builder().withHost(
            "127.0.0.1"
        ).withPort(1).withTimeout(java.time.Duration.ofSeconds(2)).build()
        runCatching { CoCache5JavaUsage.standalone(unreachable).close() }.exceptionOrNull().assert().isNotNull()
        runCatching { CoCache5JavaUsage.cluster(listOf(unreachable)).close() }.exceptionOrNull().assert().isNotNull()
    }
}
