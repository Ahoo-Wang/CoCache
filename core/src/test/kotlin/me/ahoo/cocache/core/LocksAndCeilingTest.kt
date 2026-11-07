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
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.engine.CoherentEngine
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.Ttl
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.function.UnaryOperator

/**
 * 库的锁定与约束（[Locks]，RFC 0005 §4.8，U2、S12）在只有声明一层时的效果，以及 `overrides` 的 `ttl` 对按调用给出的寿命的钳制
 * （[me.ahoo.cocache.core.policy.CachePolicy.ttlCeiling]，S8）。
 */
class LocksAndCeilingTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()

    @AfterEach
    fun close() = runtime.close()

    private fun storeSpec(name: String = "revoked") = storeSpec<String, String>(name) { codec = StringCodec }

    /**
     * 约束的值域包含 `Forever`：`finiteOr` 让“条目一定过期”的意图成立；被约束改变的字段在报告中为 LOCKED，锁定者默认是调用方的类名。
     */
    @Test
    fun constraintsRunOnTheDeclarationAndAreReported() {
        val factory = CoCacheFactory(runtime)
        factory.create(storeSpec()) {
            lock(PolicyField.TTL)
            lockLayout(LayoutField.KEY_PREFIX)
            constrain(PolicyField.TTL) { it.finiteOr(Duration.ofMinutes(1)) }
        }
        runtime.policy("revoked")?.ttl.assert().isEqualTo(Duration.ofMinutes(1))
        val report = requireNotNull(runtime.admin("revoked")?.policyReport)
        val ttlEntry = requireNotNull(report.entry(PolicyField.TTL))
        ttlEntry.source.assert().isEqualTo(PolicyReport.Source.LOCKED)
        ttlEntry.value.assert().isEqualTo("PT1M")
        ttlEntry.detail.assert().isEqualTo("constrained by ${LocksAndCeilingTest::class.java.name}")
        report.entry(PolicyField.LOCAL_MAX_AGE)?.source.assert().isEqualTo(PolicyReport.Source.TYPE_DEFAULT)
        // 只锁定、没有被约束改变的字段同样标注锁定者
        CoCacheFactory(runtime).create(storeSpec("locked")) { lock(PolicyField.LOCAL_MAX_AGE) }
        val locked = requireNotNull(runtime.admin("locked")?.policyReport?.entry(PolicyField.LOCAL_MAX_AGE))
        locked.source.assert().isEqualTo(PolicyReport.Source.LOCKED)
        locked.detail.assert().isEqualTo("locked by ${LocksAndCeilingTest::class.java.name}")
        report.toString().assert().contains("ttl")

        // 有限的寿命原样通过
        factory.create(
            storeSpec<String, String>("finite") {
                codec = StringCodec
                policy { ttl = Duration.ofSeconds(5) }
            }
        ) { constrain(PolicyField.TTL) { it.finiteOr(Duration.ofMinutes(1)) } }
        runtime.policy("finite")?.ttl.assert().isEqualTo(Duration.ofSeconds(5))
        runtime.admin(
            "finite"
        )?.policyReport?.entry(PolicyField.TTL)?.source.assert().isEqualTo(PolicyReport.Source.DECLARATION)
    }

    @Test
    fun javaLocksAndVerifyChecks() {
        val locks = Locks.builder("cosec")
            .lock(PolicyField.UNKNOWN_READ)
            .lockLayout(LayoutField.KEY_PREFIX, LayoutField.NAMESPACE)
            .constrain(PolicyField.TTL) { Ttl.Finite(Duration.ofSeconds(30)).min(it) }
            .verify { spec -> require(spec is StoreSpec<*, *>) { "revoked tokens must be a Store" } }
            .build()
        locks.isLocked(PolicyField.UNKNOWN_READ).assert().isTrue()
        locks.isLocked(PolicyField.TTL).assert().isFalse()
        locks.isLocked(LayoutField.NAMESPACE).assert().isTrue()
        locks.isLocked(LayoutField.CODEC).assert().isFalse()
        locks.toString().assert().contains("cosec")
        Locks.NONE.fields.assert().isEmpty()

        CoCacheFactory(runtime).create(storeSpec(), locks)
        runtime.policy("revoked")?.ttl.assert().isEqualTo(Duration.ofSeconds(30))
        runtime.admin(
            "revoked"
        )?.policyReport?.entry(PolicyField.TTL)?.detail.assert().isEqualTo("constrained by cosec")

        // verify 在生效声明构建之后执行：失败时不创建任何东西
        val cacheSpec = cacheSpec<String, String>("loaded") {
            codec = StringCodec
            loader = CacheLoader { it }
        }
        runCatching { CoCacheFactory(runtime).create(cacheSpec as Spec<*, *>, locks) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be a Store")
        runtime.cache("loaded").assert().isNull()
    }

    interface Tokens : Store<String, String>

    /**
     * 只有声明一层时，五种 spec 的约束都会重建声明（含 `unknownRead`），DSL 与接口式声明的入口同样如此。
     */
    @Test
    fun constraintsRebuildEverySpecType() {
        val factory = CoCacheFactory(runtime)
        val oneSecond = UnaryOperator<Duration> { Duration.ofSeconds(1) }
        val external = cacheSpec<String, String>("external") {
            codec = StringCodec
            externallySourced()
            unknownRead = UnknownReadMode.ABSENT
        }
        val versionedCache = versionedCacheSpec<String, String>("doc") {
            codec = StringCodec
            versionedLoader = CacheLoader { Versioned(it, 1) }
        }
        factory.create(external) { constrain(PolicyField.LOCAL_MAX_AGE, oneSecond) }
        factory.create(versionedCache) { constrain(PolicyField.LOCAL_MAX_AGE, oneSecond) }
        factory.create(versionedStoreSpec<String, String>("aggregate") { codec = StringCodec }) {
            constrain(PolicyField.LOCAL_MAX_AGE, oneSecond)
        }
        factory.create(setStoreSpec<String, String>("tags") { elementCodec = StringCodec }) {
            constrain(PolicyField.LOCAL_MAX_AGE, oneSecond)
        }
        val tokens = factory.create(Tokens::class.java, storeSpec("tokens")) {
            constrain(PolicyField.LOCAL_MAX_AGE, oneSecond)
        }
        tokens.put("t", "v")
        tokens["t"].assert().isEqualTo("v")
        listOf("external", "doc", "aggregate", "tags", "tokens").forEach { name ->
            runtime.policy(name)?.localMaxAge.assert()
                .withFailMessage { "$name is constrained" }.isEqualTo(Duration.ofSeconds(1))
            val report = requireNotNull(runtime.admin(name)?.policyReport)
            report.entry(PolicyField.LOCAL_MAX_AGE)?.source.assert().isEqualTo(PolicyReport.Source.LOCKED)
        }
        val externalReport = requireNotNull(runtime.admin("external")?.policyReport)
        externalReport.entry(PolicyField.UNKNOWN_READ)?.value.assert().isEqualTo("absent")
    }

    /**
     * `overrides.cache.ttl` 设定的上限钳制按调用给出的寿命（`put(k, v, ttl)`、`putAll(…, ttl)`、`ttlOf`，同步、异步与挂起），
     * 计入 `cocache.ttl.clamped`；策略自身的寿命不经过它。
     */
    @Test
    fun theTtlCeilingClampsPerCallLifetimes() {
        val cache = runtime.create(
            cacheSpec<String, String>("user") {
                codec = StringCodec
                loader = CacheLoader { "loaded-$it" }
                ttlOf = TtlFunction { key, _ -> if (key == "long") Duration.ofHours(1) else null }
                policy {
                    ttl = Duration.ofSeconds(5)
                    ttlJitter = Duration.ZERO
                    ttlCeiling = Duration.ofSeconds(10)
                }
            }
        )
        val admin = requireNotNull(runtime.admin("user"))
        cache.put("explicit", "v", Duration.ofHours(1))
        cache.putAll(mapOf("batch" to "v"), Duration.ofHours(1))
        cache.async().put("async", "v", Duration.ofHours(1)).join()
        cache.async().putAll(mapOf("async-batch" to "v"), Duration.ofHours(1)).join()
        runBlocking {
            cache.suspending().put("suspending", "v", Duration.ofHours(1))
            cache.suspending().putAll(mapOf("suspending-batch" to "v"), Duration.ofHours(1))
        }
        cache["long"].assert().isEqualTo("loaded-long")
        cache.put("short", "v", Duration.ofSeconds(3))
        cache.put("policy", "v")

        listOf("explicit", "batch", "async", "async-batch", "suspending", "suspending-batch", "long").forEach { key ->
            requireNotNull(admin.inspect(key).remote.ttl).assert()
                .withFailMessage { "$key is clamped" }.isLessThanOrEqualTo(Duration.ofSeconds(10))
        }
        requireNotNull(admin.inspect("short").remote.ttl).assert().isLessThanOrEqualTo(Duration.ofSeconds(3))
        requireNotNull(admin.inspect("policy").remote.ttl).assert().isLessThanOrEqualTo(Duration.ofSeconds(5))
        admin.metrics.ttlClamped.assert().isEqualTo(7L)
    }

    /**
     * D2（GA 前的架构审查）：上限只对 `Cache` 有意义。其它四种类型的 spec 带着它时构建失败，而不是静默无效（以前 `Store` 的 `put` 经
     * `ttlOf` 被钳制，`VersionedCache` 的回源被钳制，显式寿命与按版本写入却不钳制）。带版本的类型的寿命是版本栅栏的寿命，
     * 权威类型的寿命就是数据的寿命：钳制它们会放进重投的旧版本、提前删掉唯一的副本。
     */
    @Test
    fun theTtlCeilingIsRejectedOnEveryTypeButCache() {
        val ceiling = CachePolicy(ttl = Duration.ofMinutes(5), ttlCeiling = Duration.ofSeconds(10))
        listOf<Pair<String, () -> Any>>(
            "Store" to {
                storeSpec<String, String>("s") {
                    codec = StringCodec
                    policy = ceiling
                }
            },
            "VersionedStore" to {
                versionedStoreSpec<String, String>("vs") {
                    codec = StringCodec
                    policy = ceiling
                }
            },
            "SetStore" to {
                setStoreSpec<String, String>("set") {
                    elementCodec = StringCodec
                    policy = ceiling
                }
            },
            "VersionedCache" to {
                versionedCacheSpec<String, String>("vc") {
                    codec = StringCodec
                    versionedLoader = CacheLoader { Versioned(it, 1) }
                    policy = ceiling
                }
            },
        ).forEach { (kind, build) ->
            val error = runCatching { build() }.exceptionOrNull()
            error.assert().withFailMessage { "$kind accepted a ttlCeiling" }.isInstanceOf(
                IllegalArgumentException::class.java
            )
            requireNotNull(error).message.assert().contains("$kind[", "only applies to a Cache")
        }
        cacheSpec<String, String>("c") {
            codec = StringCodec
            externallySourced()
            policy = ceiling
        }.policy.ttlCeiling.assert().isEqualTo(Duration.ofSeconds(10))
    }

    /**
     * D2：引擎只在 `Cache` 上钳制（[me.ahoo.cocache.core.engine.TtlRules.clamps]），即使带着上限的策略绕过 spec 到达其它类型的引擎。
     */
    @Test
    fun onlyACacheEngineClamps() {
        val cache = runtime.create(
            cacheSpec<String, String>("clamping") {
                codec = StringCodec
                externallySourced()
            }
        )
        val versioned = runtime.create(
            versionedCacheSpec<String, String>("not-clamping") {
                codec = StringCodec
                versionedLoader = CacheLoader { Versioned(it, 1) }
            }
        )
        val store = runtime.create(storeSpec<String, String>("not-clamping-store") { codec = StringCodec })
        (cache as CoherentEngine<*, *>).ttls.clamps.assert().isTrue()
        (versioned as CoherentEngine<*, *>).ttls.clamps.assert().isFalse()
        (store as CoherentEngine<*, *>).ttls.clamps.assert().isFalse()
    }

    @Test
    fun noCeilingKeepsPerCallLifetimes() {
        val cache = runtime.create(
            cacheSpec<String, String>("free") {
                codec = StringCodec
                loader = CacheLoader { it }
            }
        )
        cache.put("k", "v", Duration.ofHours(2))
        requireNotNull(runtime.admin("free")?.inspect("k")?.remote?.ttl).assert().isGreaterThan(Duration.ofHours(1))
        runtime.metrics("free")?.ttlClamped.assert().isEqualTo(0L)
    }
}
