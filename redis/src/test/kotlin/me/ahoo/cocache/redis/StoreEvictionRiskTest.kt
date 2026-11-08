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

import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * 权威副本的驱逐风险（X2）：规则本身（[StoreEvictionRisk.assess]，不访问 Redis）与按运行时的检查（看穿包装的后端）。
 */
class StoreEvictionRiskTest {
    private val forever = mapOf("index" to null as Duration?)
    private val expiring = mapOf("index" to null, "recent" to Duration.ofHours(1))

    private fun assess(
        stores: Map<String, Duration?>,
        policies: Map<String, String>?,
        mode: StoreEvictionRisk.Mode = StoreEvictionRisk.Mode.WARN,
        assumed: String? = null,
    ) = StoreEvictionRisk.assess(stores, mode, assumed) { policies }

    @Test
    fun noStoresIsNotApplicableAndReadsNothing() {
        var reads = 0
        StoreEvictionRisk.assess(emptyMap(), StoreEvictionRisk.Mode.FAIL, null) {
            reads++
            mapOf("redis" to "allkeys-lru")
        }.assert().isEqualTo(StoreEvictionRisk.NotApplicable)
        reads.assert().isZero()
    }

    @Test
    fun allKeysIsFatalInEveryMode() {
        StoreEvictionRisk.Mode.entries.forEach { mode ->
            val risk = assess(
                expiring,
                mapOf("a:1" to "allkeys-lru", "b:1" to "noeviction"),
                mode
            ) as StoreEvictionRisk.Risk
            risk.kind.assert().isEqualTo(StoreEvictionRisk.Kind.EVICTS_ANY_KEY)
            risk.fatal.assert().isTrue()
            risk.caches.assert().containsExactly("index", "recent")
            risk.policies.assert().isEqualTo(mapOf("a:1" to "allkeys-lru"))
            runCatching { risk.requireSafe() }.exceptionOrNull().assert()
                .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("allkeys-lru")
        }
    }

    @Test
    fun unreadablePolicyIsFatalUnlessAssumed() {
        val risk = assess(forever, null) as StoreEvictionRisk.Risk
        risk.kind.assert().isEqualTo(StoreEvictionRisk.Kind.UNREADABLE_POLICY)
        risk.fatal.assert().isTrue()
        risk.message.assert().contains("INFO memory").contains("+info")
        assess(forever, null, assumed = "noeviction")
            .assert().isEqualTo(StoreEvictionRisk.Safe(mapOf(StoreEvictionRisk.ASSUMED to "noeviction")))
    }

    @Test
    fun volatileWithAFiniteTtlFollowsTheMode() {
        val volatile = mapOf("redis" to "volatile-lru")
        val warn = assess(expiring, volatile) as StoreEvictionRisk.Risk
        warn.kind.assert().isEqualTo(StoreEvictionRisk.Kind.EVICTS_EXPIRING_KEYS)
        warn.caches.assert().containsExactly("recent")
        warn.fatal.assert().isFalse()
        warn.requireSafe().assert().isSameAs(warn)
        (assess(expiring, volatile, StoreEvictionRisk.Mode.FAIL) as StoreEvictionRisk.Risk).fatal.assert().isTrue()
        assess(expiring, volatile, StoreEvictionRisk.Mode.OFF).assert().isEqualTo(StoreEvictionRisk.Safe(volatile))
        // 只有永不过期的权威缓存：volatile-* 驱逐不到它们
        assess(forever, volatile, StoreEvictionRisk.Mode.FAIL).assert().isEqualTo(StoreEvictionRisk.Safe(volatile))
        StoreEvictionRisk.Safe(volatile).requireSafe().assert().isEqualTo(StoreEvictionRisk.Safe(volatile))
    }

    @Test
    fun rejectsAnUnknownAssumedPolicy() {
        runCatching { assess(forever, null, assumed = "lru") }.exceptionOrNull().assert()
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("maxmemory-policy[lru]")
        StoreEvictionRisk.requireValidPolicy(null)
        StoreEvictionRisk.requireValidPolicy("volatile-ttl")
    }

    /**
     * 按运行时：只算权威缓存（派生缓存不在其中）；后端不是 Redis 时不适用；包装过的 Redis 后端（`HookedBackend`）照样检查。
     */
    @Test
    fun checksARuntimeThroughWrappers() {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            runtime.create(setStoreSpec<String, String>("index") { elementCodec = StringCodec })
            runtime.create(
                cacheSpec<String, String>("user") {
                    codec = StringCodec
                    externallySourced()
                }
            )
            StoreEvictionRisk.authoritativeStores(runtime).assert().isEqualTo(mapOf("index" to null))
            StoreEvictionRisk.check(runtime).assert().isEqualTo(StoreEvictionRisk.NotApplicable)
        }
        RedisTestSupport.backend().use { redis ->
            CacheRuntime.builder().backend(HookedBackend(redis)).build().use { runtime ->
                StoreEvictionRisk.check(runtime).assert().isEqualTo(StoreEvictionRisk.NotApplicable)
                runtime.create(storeSpec<String, String>("risk-" + UUID.randomUUID()) { codec = StringCodec })
                val verdict = StoreEvictionRisk.check(runtime, assumedPolicy = "noeviction")
                verdict.assert().isNotEqualTo(StoreEvictionRisk.NotApplicable)
            }
        }
    }

    /**
     * 缺陷复现（CoSec 在 rc.1 上试用发现）：策略永不过期、按条目给出寿命写入的 `Store`（吊销表的 `put(key, value, ttl)`）曾被当作不会过期，
     * `volatile-*` 在 `FAIL` 下也判为安全，而这些 key 带过期时间、可能在到期前被驱逐。本实例写入过按条目寿命之后，它计入会过期的权威缓存。
     */
    @Test
    fun aStoreWrittenWithItsOwnTtlCountsAsExpiring() {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            val revoked = runtime.create(storeSpec<String, String>("revoked") { codec = StringCodec })
            val volatile = mapOf("redis" to "volatile-lru")
            val policies: () -> Map<String, String> = { volatile }
            val fail = StoreEvictionRisk.Mode.FAIL
            StoreEvictionRisk.assess(StoreEvictionRisk.authoritativeStores(runtime), fail, null, policies)
                .assert().isEqualTo(StoreEvictionRisk.Safe(volatile))
            revoked.put("jti-1", "revoked", Duration.ofMinutes(10))
            val stores = StoreEvictionRisk.authoritativeStores(runtime)
            stores.assert().isEqualTo(mapOf("revoked" to Duration.ofMinutes(10)))
            val risk = StoreEvictionRisk.assess(stores, fail, null, policies) as StoreEvictionRisk.Risk
            risk.kind.assert().isEqualTo(StoreEvictionRisk.Kind.EVICTS_EXPIRING_KEYS)
            risk.caches.assert().containsExactly("revoked")
            risk.fatal.assert().isTrue()
            risk.message.assert().contains("entries written with their own ttl")
        }
    }
}
