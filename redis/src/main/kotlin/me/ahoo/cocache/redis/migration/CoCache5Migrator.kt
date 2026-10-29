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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.RedisURI
import io.lettuce.core.RestoreArgs
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanIterator
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.sync.RedisClusterCommands
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.redis.RedisScripts

/**
 * 把 CoCache 5.x 写入 Redis 的数据转换为 6.x 格式，供“Redis 是权威存储、没有数据源”的缓存升级使用
 * （有数据源的缓存不需要迁移：6.x 读到 5.x 的值时条件回收后回源）。
 *
 * **前提：迁移期间所有应用实例停止写入这些 key**（5.x 与 6.x 都不能运行）。原地迁移以 `DUMP` 比较防止覆盖并发写入，
 * 检测到改动时中止；但读取、备份与写入之间的改动无法全部检测，停写是正确性的前提。
 *
 * 步骤：[dryRun] 验证配置（只读）→ 停写 → [migrate] → 启动 6.x 并验证 → [dropBackups]；
 * 失败或验证不通过时 [rollback] 后重新部署 5.x。
 *
 * 每个 key：
 * 1. 一次 Lua 读取类型、剩余寿命、`DUMP` 与内容。已是 6.x 格式（重复执行）→ 跳过；读取期间已不存在 → 跳过。
 * 2. 按配置的 5.x 格式解码、以 6.x 编解码编码；负缓存哨兵迁移为 6.x 负缓存。类型不符或解码失败 → 立即中止（[CoCache5MigrationException]），不静默跳过。
 * 3. 原地迁移（目标 key 等于 5.x key）：`RESTORE … REPLACE` 把原值备份到 `{backupPrefix}{5.x key}`（类型、内容、剩余寿命不变）；
 *    写到新 key 时 5.x key 不动，目标 key 必须不存在。
 * 4. 一次 Lua 写入 6.x 格式（与 6.x `put` 的编码相同，不广播），保留剩余寿命；原地迁移时先比较 `DUMP`，内容已变则中止。
 *
 * 可以重复执行：已迁移的 key 被跳过，中途失败后修正原因再执行即可。
 *
 * 只用于离线迁移：每个 key 两到三次往返，按缓存顺序执行。
 */
class CoCache5Migrator private constructor(
    private val client: AbstractRedisClient,
    private val ownsClient: Boolean,
    val options: CoCache5MigrationOptions,
) : AutoCloseable {
    private val connection: StatefulConnection<String, ByteArray>
    private val commands: RedisClusterCommands<String, ByteArray>

    init {
        when (client) {
            is RedisClusterClient -> client.connect(CODEC).also {
                connection = it
                commands = it.sync()
            }

            is RedisClient -> client.connect(CODEC).also {
                connection = it
                commands = it.sync()
            }

            else -> throw IllegalArgumentException("Unsupported client[${client.javaClass.name}].")
        }
    }

    /**
     * 迁移。遇到第一个失败即中止并抛出 [CoCache5MigrationException]（携带到此为止的报告）。
     */
    fun migrate(migrations: List<CoCache5Migration>): CoCache5MigrationReport = run(migrations, dryRun = false)

    fun migrate(vararg migrations: CoCache5Migration): CoCache5MigrationReport = migrate(migrations.toList())

    /**
     * 只读取与解码，不备份、不写入：用来在停写之前验证 5.x 格式与 key 配置。计数与 [migrate] 相同（`written` 为将要写入的数）。
     */
    fun dryRun(migrations: List<CoCache5Migration>): CoCache5MigrationReport = run(migrations, dryRun = true)

    fun dryRun(vararg migrations: CoCache5Migration): CoCache5MigrationReport = dryRun(migrations.toList())

    /**
     * 回滚到迁移之前的 5.x 数据（应用仍须停写）：
     * - 原地迁移的 key：从备份恢复（类型、内容、剩余寿命），然后删除备份；
     * - 写到新 key 的：删除 6.x 目标 key（5.x key 从未改动）。
     *
     * 迁移之后 6.x 写入的数据会丢失（恢复的是迁移时的快照）。可以重复执行。
     */
    fun rollback(migrations: List<CoCache5Migration>): CoCache5MigrationReport {
        validate(migrations)
        return CoCache5MigrationReport(migrations.map { rollback(it) })
    }

    fun rollback(vararg migrations: CoCache5Migration): CoCache5MigrationReport = rollback(migrations.toList())

    /**
     * 验证 6.x 正常运行之后删除原地迁移留下的备份。之后不能再回滚。`written` 为删除的备份数。
     */
    fun dropBackups(migrations: List<CoCache5Migration>): CoCache5MigrationReport {
        validate(migrations)
        return CoCache5MigrationReport(
            migrations.map { migration ->
                val counts = Counts(migration.name)
                forEachBackup(migration) { _, backup ->
                    counts.read++
                    counts.written += commands.del(backup)
                }
                counts.toReport()
            }
        )
    }

    fun dropBackups(vararg migrations: CoCache5Migration): CoCache5MigrationReport = dropBackups(migrations.toList())

    private fun run(migrations: List<CoCache5Migration>, dryRun: Boolean): CoCache5MigrationReport {
        validate(migrations)
        val done = ArrayList<CoCache5CacheReport>(migrations.size)
        for (migration in migrations) {
            val counts = Counts(migration.name)
            forEachSource(migration) { key ->
                try {
                    migrateKey(migration, key, counts, dryRun)
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    throw failure(done, counts, migration, key, e.toString(), e)
                }
            }
            log.info { "Migration[${migration.name}]${if (dryRun) " (dry run)" else ""} - ${counts.toReport()}." }
            done += counts.toReport()
        }
        return CoCache5MigrationReport(done)
    }

    private fun migrateKey(migration: CoCache5Migration, key: String, counts: Counts, dryRun: Boolean) {
        counts.read++
        val source = inspect(key)
        if (source == null || source.ours) {
            counts.skipped++
            return
        }
        val envelope = try {
            migration.converter.convert(source.type, source.content, options.missingGuardSentinel)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            throw IllegalArgumentException("cannot decode the 5.x ${source.type}: ${e.message}", e)
        }
        val target = migration.targetKeyOf(key)
        val inPlace = target == key
        if (!inPlace) {
            val existing = inspect(target)
            if (existing != null) {
                if (existing.ours) {
                    counts.skipped++
                    return
                }
                throw IllegalStateException("target key[$target] already holds a non-CoCache-6 ${existing.type}.")
            }
        }
        counts.written++
        if (envelope === Envelope.Absent) {
            counts.negatives++
        }
        if (dryRun) {
            return
        }
        if (inPlace) {
            backup(key, source)
            counts.backedUp++
        }
        val written = write(target, if (inPlace) source.dump else null, envelope, source.pttl)
        check(written) {
            if (inPlace) "the key changed during migration; stop every writer and run again." else "target key[$target] was created during migration."
        }
    }

    private fun rollback(migration: CoCache5Migration): CoCache5CacheReport {
        val counts = Counts(migration.name)
        forEachBackup(migration) { key, backup ->
            counts.read++
            val dump = commands.dump(backup) ?: return@forEachBackup
            val pttl = commands.pttl(backup)
            val args = RestoreArgs().replace()
            if (pttl > 0) {
                args.ttl(pttl)
            }
            commands.restore(key, dump, args)
            commands.del(backup)
            counts.written++
        }
        // 写到新 key 的迁移：删除 6.x 目标，5.x key 从未改动
        forEachSource(migration) { key ->
            val target = migration.targetKeyOf(key)
            if (target != key && inspect(target)?.ours == true) {
                counts.read++
                counts.written += commands.del(target)
            }
        }
        log.info { "Rollback[${migration.name}] - ${counts.toReport()}." }
        return counts.toReport()
    }

    private fun backup(key: String, source: Snapshot) {
        val args = RestoreArgs().replace()
        if (source.pttl > 0) {
            args.ttl(source.pttl)
        }
        commands.restore(options.backupPrefix + key, source.dump, args)
    }

    /**
     * 一次 Lua 读取 key 的类型、剩余寿命、DUMP 与内容；key 不存在时返回 `null`。
     */
    private fun inspect(key: String): Snapshot? {
        val reply: List<Any?> = eval(INSPECT, key, emptyList(), ScriptOutputType.MULTI)
        val type = String(reply[0] as ByteArray, Charsets.UTF_8)
        if (type == "none") {
            return null
        }
        val pttl = reply[1] as Long
        val dump = reply[2] as ByteArray
        val elements = (reply[3] as List<*>).map { it as ByteArray }
        return when (type) {
            "string" -> {
                val bytes = elements.first()
                val ours = bytes.size >= RedisScripts.HEADER_SIZE && bytes[0] == RedisScripts.MAGIC && bytes[1] >= 1
                Snapshot(type, pttl, dump, String(bytes, Charsets.UTF_8), ours)
            }

            "set" -> Snapshot(type, pttl, dump, elements.mapTo(LinkedHashSet()) { String(it, Charsets.UTF_8) }, false)

            "hash" -> {
                val fields = LinkedHashMap<String, String>()
                elements.chunked(
                    2
                ).forEach { (field, value) -> fields[String(field, Charsets.UTF_8)] = String(value, Charsets.UTF_8) }
                Snapshot(type, pttl, dump, fields, fields.containsKey(FORMAT_FIELD))
            }

            else -> throw IllegalStateException(
                "unexpected Redis type[$type]: CoCache 5.x only writes strings, sets and hashes."
            )
        }
    }

    /**
     * 写入 6.x 格式。[expectedDump] 非空时只在当前内容的 DUMP 与之相同时写入，否则只在 key 不存在时写入。
     */
    private fun write(target: String, expectedDump: ByteArray?, envelope: Envelope, pttl: Long): Boolean {
        val args = ArrayList<ByteArray>()
        args += (if (expectedDump == null) "N" else "D").toByteArray()
        args += expectedDump ?: ByteArray(0)
        when (envelope) {
            is Envelope.Scalar -> {
                args += "V".toByteArray()
                args += ttlArg(pttl)
                args += envelope.bytes
            }

            is Envelope.Members -> {
                args += "S".toByteArray()
                args += ttlArg(pttl)
                args += envelope.members
            }

            Envelope.Absent -> {
                args += "M".toByteArray()
                args += ttlArg(pttl)
            }
        }
        val written: Long = eval(WRITE, target, args, ScriptOutputType.INTEGER)
        return written == 1L
    }

    private fun ttlArg(pttl: Long): ByteArray = (if (pttl > 0) pttl else 0L).toString().toByteArray()

    private fun <T> eval(script: RedisScripts.Script, key: String, args: List<ByteArray>, type: ScriptOutputType): T {
        val keys = arrayOf(key)
        val values = args.toTypedArray()
        return try {
            commands.evalsha(script.sha, type, keys, *values)
        } catch (_: RedisNoScriptException) {
            commands.eval(script.source, type, keys, *values)
        }
    }

    private fun forEachSource(migration: CoCache5Migration, visit: (String) -> Unit) {
        if (!migration.scans) {
            migration.sourceKeys.keys.forEach(visit)
            return
        }
        scan(migration.sourcePrefix, visit)
    }

    /**
     * 遍历原地迁移留下的备份：`(5.x key, 备份 key)`。
     */
    private fun forEachBackup(migration: CoCache5Migration, visit: (String, String) -> Unit) {
        if (!migration.scans) {
            migration.sourceKeys.keys.forEach { key ->
                val backup = options.backupPrefix + key
                if (commands.exists(backup) == 1L) {
                    visit(key, backup)
                }
            }
            return
        }
        scan(
            options.backupPrefix + migration.sourcePrefix
        ) { backup -> visit(backup.substring(options.backupPrefix.length), backup) }
    }

    private fun scan(prefix: String, visit: (String) -> Unit) {
        val args = ScanArgs.Builder.matches(glob(prefix) + "*").limit(options.scanCount)
        // SCAN 可能重复返回同一个 key
        val seen = HashSet<String>()
        ScanIterator.scan(commands, args).forEach { key ->
            if (seen.add(key)) {
                visit(key)
            }
        }
    }

    /**
     * 扫描的范围不能互相重叠，备份也不能落在任何扫描范围内：否则一个缓存的 key 会被另一个缓存（或备份被当作 5.x 数据）再次处理。
     */
    private fun validate(migrations: List<CoCache5Migration>) {
        val scanned = migrations.filter { it.scans }
        for (a in scanned) {
            for (b in scanned) {
                if (a !== b) {
                    require(!overlaps(a.sourcePrefix, b.sourcePrefix)) {
                        "Migration[${a.name}] and [${b.name}] - source prefixes[${a.sourcePrefix}] and [${b.sourcePrefix}] overlap; list keys with sourceKey."
                    }
                }
                require(!overlaps(options.backupPrefix + a.sourcePrefix, b.sourcePrefix)) {
                    "Migration[${b.name}] - backups of [${a.name}] (${options.backupPrefix}${a.sourcePrefix}*) would be scanned as 5.x data; change backupPrefix."
                }
            }
            for (explicit in migrations.filterNot { it.scans }) {
                explicit.sourceKeys.keys.forEach { key ->
                    require(!(options.backupPrefix + key).startsWith(a.sourcePrefix)) {
                        "Migration[${a.name}] - the backup of [$key] would be scanned as 5.x data; change backupPrefix."
                    }
                }
            }
        }
    }

    private fun overlaps(a: String, b: String): Boolean = a.startsWith(b) || b.startsWith(a)

    @Suppress("LongParameterList")
    private fun failure(
        done: List<CoCache5CacheReport>,
        counts: Counts,
        migration: CoCache5Migration,
        key: String,
        message: String,
        cause: Throwable,
    ): CoCache5MigrationException {
        counts.failed++
        val report = CoCache5MigrationReport(done + counts.toReport())
        log.error(cause) { "Migration[${migration.name}] - key[$key] failed, aborted: $report." }
        return CoCache5MigrationException(report, migration.name, key, message, cause)
    }

    override fun close() {
        connection.close()
        if (ownsClient) {
            client.shutdown()
        }
    }

    private class Snapshot(val type: String, val pttl: Long, val dump: ByteArray, val content: Any, val ours: Boolean)

    private class Counts(private val name: String) {
        var read = 0L
        var written = 0L
        var skipped = 0L
        var failed = 0L
        var negatives = 0L
        var backedUp = 0L

        fun toReport() = CoCache5CacheReport(name, read, written, skipped, failed, negatives, backedUp)
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private val CODEC: RedisCodec<String, ByteArray> = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)
        private const val FORMAT_FIELD = "!f"

        /**
         * 读取：`{类型, PTTL, DUMP, 内容}`；内容为 GET 的值、SMEMBERS 的成员或 HGETALL 的字段与值（其它类型为空）。
         */
        private val INSPECT = RedisScripts.Script(
            """
local key = KEYS[1]
local kind = redis.call('TYPE', key)['ok']
if kind == 'none' then return {kind} end
local content = {}
if kind == 'string' then content = {redis.call('GET', key)}
elseif kind == 'set' then content = redis.call('SMEMBERS', key)
elseif kind == 'hash' then content = redis.call('HGETALL', key) end
return {kind, redis.call('PTTL', key), redis.call('DUMP', key), content}
"""
        )

        /**
         * 写入 6.x 格式（与 `PUT` 的 `write` 相同，不广播）。ARGV：守卫（`N` key 必须不存在；`D` DUMP 必须等于 ARGV[2]）、DUMP、形状、TTL、载荷或成员…
         */
        private val WRITE = RedisScripts.Script(
            RedisScripts.PRELUDE + """
local key = KEYS[1]
if ARGV[1] == 'N' then
  if redis.call('EXISTS', key) == 1 then return 0 end
elseif redis.call('DUMP', key) ~= ARGV[2] then
  return 0
end
write(key, ARGV[3], ARGV[4], 5)
return 1
"""
        )

        private val GLOB_SPECIALS = setOf('*', '?', '[', ']', '\\')

        /**
         * 自建的客户端在连接失败时同样关闭（否则线程与事件循环泄漏）。
         */
        private fun owning(client: AbstractRedisClient, options: CoCache5MigrationOptions): CoCache5Migrator {
            return try {
                CoCache5Migrator(client, true, options)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                client.shutdown()
                throw e
            }
        }

        private fun glob(prefix: String): String = buildString {
            prefix.forEach { if (it in GLOB_SPECIALS) append('\\').append(it) else append(it) }
        }

        /**
         * 连接单机或 Sentinel（自建客户端，随 [close] 关闭）。
         */
        @JvmStatic
        @JvmOverloads
        fun standalone(
            uri: RedisURI,
            options: CoCache5MigrationOptions = CoCache5MigrationOptions()
        ): CoCache5Migrator =
            owning(RedisClient.create(uri), options)

        /**
         * 连接 Redis Cluster（自建客户端，随 [close] 关闭）。`SCAN` 遍历全部主节点。
         */
        @JvmStatic
        @JvmOverloads
        fun cluster(
            uris: List<RedisURI>,
            options: CoCache5MigrationOptions = CoCache5MigrationOptions()
        ): CoCache5Migrator =
            owning(RedisClusterClient.create(uris), options)

        /**
         * 使用调用方的客户端（`RedisClient` 或 `RedisClusterClient`），[close] 只关闭本迁移器的连接。
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            client: AbstractRedisClient,
            options: CoCache5MigrationOptions = CoCache5MigrationOptions()
        ): CoCache5Migrator =
            CoCache5Migrator(client, false, options)
    }
}
