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

import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.codec.jackson.jacksonCodec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.redis.RedisTestSupport
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID

/**
 * [CoCache5Migrator] 在真实 Redis 上的往返、重复执行、部分失败后重跑、回滚与备份清理。
 * 迁移后的数据由 6.x 运行时以默认策略（没有数据源时 `FAIL`）读取，证明它们是 6.x 能读懂的格式。
 */
class CoCache5MigratorTest {
    data class Policy(val id: String, val effect: String)

    data class Profile(val name: String, val age: Int)

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val run = UUID.randomUUID().toString().substring(0, 8)
    private val migrator = RedisTestSupport.migrator()
    private val backend = RedisTestSupport.backend()
    private var runtime: CacheRuntime? = null

    @AfterEach
    fun close() {
        runtime?.close()
        backend.close()
        migrator.close()
        RedisTestSupport.deleteKeys("*$run*")
    }

    private fun runtime(): CacheRuntime = runtime ?: CacheRuntime.builder().backend(backend).build().also { runtime = it }

    private val policySpec: CacheSpec<String, Policy> = cacheSpec("policy-$run") {
        codec = jacksonCodec<Policy>(mapper)
        policy { ttl = null }
    }
    private val tokenSpec: CacheSpec<String, String> = cacheSpec("token-$run") { codec = StringCodec }
    private val attrsSpec: CacheSpec<String, Map<String, String>> = cacheSpec("attrs-$run") {
        codec = jacksonCodec<Map<String, String>>(mapper)
        policy { ttl = null }
    }
    private val profileSpec: CacheSpec<String, Profile> = cacheSpec("profile-$run") {
        codec = jacksonCodec<Profile>(mapper)
        policy { ttl = null }
    }
    private val rolesSpec: CacheSpec<String, Set<String>> = cacheSpec("roles-$run") {
        codec = jacksonCodec<Set<String>>(mapper)
        policy { ttl = null }
    }
    private val indexSpec: SetCacheSpec<String, String> = setCacheSpec("index-$run") { elementCodec = StringCodec }

    private val policies = CoCache5Migration.scalar(policySpec, CoCache5Format.string()).build()
    private val tokens = CoCache5Migration.scalar(tokenSpec, CoCache5Format.string()).build()
    private val attrs = CoCache5Migration.scalar(attrsSpec, CoCache5Format.hash { it }).build()
    private val profiles = CoCache5Migration.scalar(
        profileSpec,
        CoCache5Format.hash { Profile(requireNotNull(it["name"]), requireNotNull(it["age"]).toInt()) }
    ).build()
    private val roles = CoCache5Migration.scalar(rolesSpec, CoCache5Format.set { it }).build()

    /**
     * CoSec 的全局策略索引：5.x 用自定义 `KeyConverter` 映射到固定 key，6.x 中是 key 为 `""` 的 `SetCache`。
     */
    private val globalIndexKey = "cosec-$run:global-policy-index"
    private val index = CoCache5Migration.set(indexSpec).sourceKey(globalIndexKey, "").build()

    private fun json(policy: Policy) = mapper.writeValueAsString(policy)

    private fun seedAllTypes() {
        Redis5x.string("cocache:policy-$run:p1", json(Policy("p1", "allow")))
        Redis5x.string("cocache:policy-$run:p2", json(Policy("p2", "deny")))
        Redis5x.missingString("cocache:policy-$run:gone", ttlSeconds = 600)
        Redis5x.string("cocache:token-$run:t1", "revoked", ttlSeconds = 3600)
        Redis5x.hash("cocache:attrs-$run:a1", mapOf("x" to "1", "y" to "2"))
        Redis5x.missingHash("cocache:attrs-$run:gone")
        Redis5x.hash("cocache:profile-$run:u1", mapOf("name" to "ahoo", "age" to "18"))
        Redis5x.set("cocache:roles-$run:r1", listOf("admin", "dev"))
        Redis5x.missingSet("cocache:roles-$run:gone")
        Redis5x.set(globalIndexKey, listOf("p1", "p2"))
    }

    private val all get() = listOf(policies, tokens, attrs, profiles, roles, index)

    @Test
    fun everyValueTypeRoundTripsAndIsReadableBy6x() {
        seedAllTypes()

        val report = migrator.migrate(all)

        report.cache("policy-$run").assert().isEqualTo(CoCache5CacheReport("policy-$run", 3, 3, 0, 0, 1, 3))
        report.cache("token-$run").assert().isEqualTo(CoCache5CacheReport("token-$run", 1, 1, 0, 0, 0, 1))
        report.cache("attrs-$run").assert().isEqualTo(CoCache5CacheReport("attrs-$run", 2, 2, 0, 0, 1, 2))
        report.cache("profile-$run").assert().isEqualTo(CoCache5CacheReport("profile-$run", 1, 1, 0, 0, 0, 1))
        report.cache("roles-$run").assert().isEqualTo(CoCache5CacheReport("roles-$run", 2, 2, 0, 0, 1, 2))
        // 目标 key（cocache:index-…:）与 5.x key 不同：写到新 key，5.x key 不动，不需要备份
        report.cache("index-$run").assert().isEqualTo(CoCache5CacheReport("index-$run", 1, 1, 0, 0, 0, 0))

        val runtime = runtime()
        runtime.create(policySpec).let { cache ->
            cache["p1"].assert().isEqualTo(Policy("p1", "allow"))
            cache["p2"].assert().isEqualTo(Policy("p2", "deny"))
            // 5.x 负缓存迁移为 6.x 负缓存，保留剩余寿命
            (cache.lookup("gone") is Lookup.Absent).assert().isTrue()
        }
        runtime.create(tokenSpec)["t1"].assert().isEqualTo("revoked")
        runtime.create(attrsSpec).let { cache ->
            cache["a1"].assert().isEqualTo(mapOf("x" to "1", "y" to "2"))
            cache["gone"].assert().isNull()
        }
        runtime.create(profileSpec)["u1"].assert().isEqualTo(Profile("ahoo", 18))
        runtime.create(rolesSpec)["r1"].assert().isEqualTo(setOf("admin", "dev"))
        val indexCache = runtime.create(indexSpec)
        indexCache[""].assert().containsExactlyInAnyOrder("p1", "p2")
        indexCache.add("", listOf("p3"))
        indexCache[""].assert().containsExactlyInAnyOrder("p1", "p2", "p3")

        // 剩余寿命：永不过期的仍永不过期，有寿命的保留（不超过原寿命）
        Redis5x.pttl("cocache:policy-$run:p1").assert().isEqualTo(-1L)
        Redis5x.pttl("cocache:token-$run:t1").assert().isBetween(1L, 3_600_000L)
        Redis5x.pttl("cocache:policy-$run:gone").assert().isBetween(1L, 600_000L)
        // 原地迁移的备份：类型、内容、寿命与 5.x 原值相同
        Redis5x.get("cocache5-backup:cocache:policy-$run:p1").assert().isEqualTo(json(Policy("p1", "allow")))
        Redis5x.pttl("cocache5-backup:cocache:token-$run:t1").assert().isBetween(1L, 3_600_000L)
        Redis5x.members("cocache5-backup:cocache:roles-$run:r1").assert().containsExactlyInAnyOrder("admin", "dev")
        Redis5x.members(globalIndexKey).assert().containsExactlyInAnyOrder("p1", "p2")
    }

    @Test
    fun migrationIsRepeatable() {
        seedAllTypes()
        migrator.migrate(all)
        val backup = Redis5x.get("cocache5-backup:cocache:policy-$run:p1")

        val again = migrator.migrate(all)

        again.caches.forEach { cache ->
            cache.written.assert().isEqualTo(0L)
            cache.failed.assert().isEqualTo(0L)
            cache.skipped.assert().isEqualTo(cache.read)
        }
        again.cache("policy-$run")?.skipped.assert().isEqualTo(3L)
        Redis5x.get("cocache5-backup:cocache:policy-$run:p1").assert().isEqualTo(backup)
        runtime().create(policySpec)["p1"].assert().isEqualTo(Policy("p1", "allow"))
    }

    @Test
    fun decodeFailureAbortsAndRerunCompletesAfterTheFix() {
        Redis5x.string("cocache:policy-$run:p1", json(Policy("p1", "allow")))
        Redis5x.string("cocache:policy-$run:bad", "{not json")
        Redis5x.string("cocache:policy-$run:p2", json(Policy("p2", "deny")))

        val error = runCatching { migrator.migrate(policies) }.exceptionOrNull()

        error.assert().isInstanceOf(CoCache5MigrationException::class.java)
        val failure = error as CoCache5MigrationException
        failure.key.assert().isEqualTo("cocache:policy-$run:bad")
        failure.cacheName.assert().isEqualTo("policy-$run")
        val partial = requireNotNull(failure.report.cache("policy-$run"))
        partial.failed.assert().isEqualTo(1L)
        partial.read.assert().isEqualTo(partial.written + partial.skipped + 1)
        // 失败的 key 没有被修改，也没有备份
        Redis5x.get("cocache:policy-$run:bad").assert().isEqualTo("{not json")
        Redis5x.exists("cocache5-backup:cocache:policy-$run:bad").assert().isFalse()

        Redis5x.string("cocache:policy-$run:bad", json(Policy("bad", "deny")))
        val rerun = requireNotNull(migrator.migrate(policies).cache("policy-$run"))

        rerun.read.assert().isEqualTo(3L)
        rerun.failed.assert().isEqualTo(0L)
        // 第一次已迁移的 key 被跳过
        rerun.skipped.assert().isEqualTo(partial.written)
        rerun.written.assert().isEqualTo(3L - partial.written)
        val cache = runtime().create(policySpec)
        cache.getAll(listOf("p1", "bad", "p2")).assert().isEqualTo(
            mapOf("p1" to Policy("p1", "allow"), "bad" to Policy("bad", "deny"), "p2" to Policy("p2", "deny"))
        )
    }

    @Test
    fun typeMismatchAborts() {
        Redis5x.hash("cocache:policy-$run:p1", mapOf("id" to "p1"))

        val error = runCatching { migrator.migrate(policies) }.exceptionOrNull()

        error.assert().isInstanceOf(CoCache5MigrationException::class.java)
        error?.message.assert().contains("expected a Redis string")
        Redis5x.type("cocache:policy-$run:p1").assert().isEqualTo("hash")
    }

    @Test
    fun dryRunWritesNothingAndStillFailsFast() {
        seedAllTypes()

        val report = migrator.dryRun(all)

        report.cache("policy-$run")?.written.assert().isEqualTo(3L)
        report.cache("policy-$run")?.backedUp.assert().isEqualTo(0L)
        Redis5x.get("cocache:policy-$run:p1").assert().isEqualTo(json(Policy("p1", "allow")))
        Redis5x.exists("cocache5-backup:cocache:policy-$run:p1").assert().isFalse()
        Redis5x.exists("cocache:index-$run:").assert().isFalse()

        Redis5x.string("cocache:policy-$run:bad", "{not json")
        runCatching {
            migrator.dryRun(
                policies
            )
        }.exceptionOrNull().assert().isInstanceOf(CoCache5MigrationException::class.java)
    }

    @Test
    fun rollbackRestores5xDataAndAllowsMigratingAgain() {
        seedAllTypes()
        migrator.migrate(all)
        // 6.x 上线后又写入：回滚恢复的是迁移时的快照
        runtime().create(policySpec).put("p1", Policy("p1", "changed-in-6x"))
        runtime().close()
        runtime = null

        val report = migrator.rollback(all)

        report.cache("policy-$run")?.written.assert().isEqualTo(3L)
        report.cache("index-$run")?.written.assert().isEqualTo(1L)
        Redis5x.get("cocache:policy-$run:p1").assert().isEqualTo(json(Policy("p1", "allow")))
        Redis5x.get("cocache:policy-$run:gone").assert().isEqualTo(Redis5x.SENTINEL)
        Redis5x.pttl("cocache:policy-$run:gone").assert().isBetween(1L, 600_000L)
        Redis5x.get("cocache:token-$run:t1").assert().isEqualTo("revoked")
        Redis5x.pttl("cocache:token-$run:t1").assert().isBetween(1L, 3_600_000L)
        Redis5x.pttl("cocache:policy-$run:p1").assert().isEqualTo(-1L)
        Redis5x.fields("cocache:attrs-$run:a1").assert().isEqualTo(mapOf("x" to "1", "y" to "2"))
        Redis5x.members("cocache:roles-$run:r1").assert().containsExactlyInAnyOrder("admin", "dev")
        Redis5x.members(globalIndexKey).assert().containsExactlyInAnyOrder("p1", "p2")
        // 备份已删除，写到新 key 的目标已删除
        Redis5x.exists("cocache5-backup:cocache:policy-$run:p1").assert().isFalse()
        Redis5x.exists("cocache:index-$run:").assert().isFalse()

        // 回滚可以重复执行
        migrator.rollback(all).caches.forEach { it.written.assert().isEqualTo(0L) }

        val again = migrator.migrate(all)
        again.cache("policy-$run")?.written.assert().isEqualTo(3L)
        runtime().create(policySpec)["p1"].assert().isEqualTo(Policy("p1", "allow"))
    }

    /**
     * 写到新的 key 前缀：5.x 数据不动，不需要备份；回滚只删除 6.x 目标。
     */
    @Test
    fun migrationToANewPrefixLeaves5xKeysUntouched() {
        Redis5x.string("cocache:policy-$run:p1", json(Policy("p1", "allow")))
        val target = cacheSpec<String, Policy>("policy-$run") {
            codec = jacksonCodec<Policy>(mapper)
            keyPrefix = "v6-$run:"
            policy { ttl = null }
        }
        val migration = CoCache5Migration.scalar(target, CoCache5Format.string()).build()

        val report = requireNotNull(migrator.migrate(migration).cache("policy-$run"))

        report.written.assert().isEqualTo(1L)
        report.backedUp.assert().isEqualTo(0L)
        Redis5x.get("cocache:policy-$run:p1").assert().isEqualTo(json(Policy("p1", "allow")))
        runtime().create(target)["p1"].assert().isEqualTo(Policy("p1", "allow"))
        migrator.migrate(migration).cache("policy-$run")?.skipped.assert().isEqualTo(1L)

        migrator.rollback(migration).cache("policy-$run")?.written.assert().isEqualTo(1L)
        Redis5x.exists("v6-$run:policy-$run:p1").assert().isFalse()
        Redis5x.get("cocache:policy-$run:p1").assert().isEqualTo(json(Policy("p1", "allow")))
    }

    @Test
    fun customSourcePrefixAndKeyMapper() {
        Redis5x.string("cosec-$run:policy:P1", json(Policy("p1", "allow")))
        val migration = CoCache5Migration.scalar(policySpec, CoCache5Format.string())
            .sourcePrefix("cosec-$run:policy:")
            .keyMapper { it.lowercase() }
            .build()

        migrator.migrate(migration).cache("policy-$run")?.written.assert().isEqualTo(1L)

        runtime().create(policySpec)["p1"].assert().isEqualTo(Policy("p1", "allow"))
    }

    @Test
    fun targetHoldingForeignContentAborts() {
        Redis5x.string("cosec-$run:policy:p1", json(Policy("p1", "allow")))
        Redis5x.string("cocache:policy-$run:p1", "left over")
        val migration = CoCache5Migration.scalar(
            policySpec,
            CoCache5Format.string()
        ).sourcePrefix("cosec-$run:policy:").build()

        val error = runCatching { migrator.migrate(migration) }.exceptionOrNull()

        error.assert().isInstanceOf(CoCache5MigrationException::class.java)
        Redis5x.get("cocache:policy-$run:p1").assert().isEqualTo("left over")
    }

    @Test
    fun dropBackupsRemovesOnlyBackups() {
        seedAllTypes()
        migrator.migrate(all)

        val report = migrator.dropBackups(all)

        report.cache("policy-$run")?.written.assert().isEqualTo(3L)
        report.cache("index-$run")?.written.assert().isEqualTo(0L)
        Redis5x.exists("cocache5-backup:cocache:policy-$run:p1").assert().isFalse()
        runtime().create(policySpec)["p1"].assert().isEqualTo(Policy("p1", "allow"))
        // 没有备份之后回滚什么也不恢复
        migrator.rollback(policies).cache("policy-$run")?.written.assert().isEqualTo(0L)
    }

    @Test
    fun overlappingScansAreRejected() {
        val nested = CoCache5Migration.scalar(
            tokenSpec,
            CoCache5Format.string()
        ).sourcePrefix("cocache:policy-$run:t:").build()
        runCatching {
            migrator.migrate(
                policies,
                nested
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)

        RedisTestSupport.migrator(CoCache5MigrationOptions(backupPrefix = "cocache:policy-$run:")).use { bad ->
            runCatching {
                bad.migrate(
                    policies
                )
            }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        runCatching {
            CoCache5Migration.scalar(policySpec, CoCache5Format.string()).sourcePrefix("a").sourceKey("b", "b").build()
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }
}
