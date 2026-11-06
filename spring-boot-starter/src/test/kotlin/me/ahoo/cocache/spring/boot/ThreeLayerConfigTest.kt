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

package me.ahoo.cocache.spring.boot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.LayoutField
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.PolicyReport
import me.ahoo.cocache.core.PolicyReport.Source
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 三层配置的规则（RFC 0005 §4.8，M6），每条规则一个用例：`defaults`（低于声明）→ 声明 → 定制器 → `overrides`（只收紧）→
 * `caches.[name]`，之后是库的约束与校验连锁；适用性、锁定、`key-prefix`、单位与哨兵、生效值的来源与端点。
 *
 * 大多直接经 [CacheAssembler] 与 [CoCacheFactory] 装配（配置以 [Binder] 绑定），需要容器的规则（未知缓存名、端点）用
 * [ApplicationContextRunner]。
 */
@Suppress("LargeClass") // 每条规则一个用例，按规则的顺序排列
class ThreeLayerConfigTest {
    private val clock = ManualClock()
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend(clock)).clock(clock).build()

    @AfterEach
    fun destroy() = runtime.close()

    private fun properties(vararg entries: Pair<String, String>): CoCacheProperties =
        Binder(MapConfigurationPropertySource(mapOf(*entries)))
            .bindOrCreate(CoCacheProperties.PREFIX, CoCacheProperties::class.java)

    private fun factory(
        vararg entries: Pair<String, String>,
        customizers: List<CacheSpecCustomizer> = emptyList()
    ): CoCacheFactory = CoCacheFactory(runtime, CacheAssembler(runtime, customizers, properties(*entries)))

    private fun policy(name: String): CachePolicy = requireNotNull(runtime.policy(name))

    private fun report(name: String): PolicyReport = requireNotNull(requireNotNull(runtime.admin(name)).policyReport)

    private fun PolicyReport.of(field: PolicyField<*>): PolicyReport.Entry = requireNotNull(entry(field))

    private fun storageKey(name: String): String = requireNotNull(runtime.admin(name)).inspect("k").storageKey

    private fun loaded(name: String, block: CacheSpec.Builder<String, String>.() -> Unit = {}) =
        cacheSpec<String, String>(name) {
            codec = StringCodec
            loader = CacheLoader { "v-$it" }
            block()
        }

    private fun external(name: String, block: CacheSpec.Builder<String, String>.() -> Unit = {}) =
        cacheSpec<String, String>(name) {
            codec = StringCodec
            externallySourced()
            block()
        }

    private fun locks(block: Locks.Builder.() -> Unit): Locks = Locks.builder(LIBRARY).apply(block).build()

    private fun failure(block: () -> Unit): Throwable? = runCatching(block).exceptionOrNull()

    private fun rootCauseOf(failure: Throwable?): Throwable =
        generateSequence(requireNotNull(failure)) { it.cause }.last()

    private fun warnings(type: Class<*>, block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(type) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    // ---- defaults：低于声明 ----

    @Test
    fun defaultsFillOnlyUndeclaredFields() {
        factory("cocache.defaults.cache.ttl" to "2m", "cocache.defaults.cache.local-max-age" to "1m")
            .create(loaded("user") { policy { ttl = Duration.ofMinutes(10) } })
        policy("user").ttl.assert().isEqualTo(Duration.ofMinutes(10))
        policy("user").localMaxAge.assert().isEqualTo(Duration.ofMinutes(1))
        val report = report("user")
        report.of(PolicyField.TTL).assert().isEqualTo(PolicyReport.Entry("ttl", "PT10M", Source.DECLARATION))
        report.of(PolicyField.LOCAL_MAX_AGE).assert()
            .isEqualTo(PolicyReport.Entry("local-max-age", "PT1M", Source.DEFAULTS))
        report.of(PolicyField.ABSENT_TTL).source.assert().isEqualTo(Source.TYPE_DEFAULT)
        // 声明写出的字段静默保留：defaults 本来就低于声明，不算跳过
        report.skipped.assert().isEmpty()
    }

    /**
     * 整体赋值 `policy = …` 视为写出全部字段：`defaults` 对它不生效，报告列出被跳过的条目。
     */
    @Test
    fun wholePolicyDeclarationSkipsDefaults() {
        factory("cocache.defaults.cache.local-max-age" to "1m")
            .create(loaded("user") { policy = CachePolicy(ttl = Duration.ofMinutes(10)) })
        policy("user").localMaxAge.assert().isEqualTo(DefaultPolicies.CACHE.localMaxAge)
        report("user").of(PolicyField.LOCAL_MAX_AGE).source.assert().isEqualTo(Source.DECLARATION)
        report("user").skipped.assert().containsExactly(
            PolicyReport.Skipped(
                "cocache.defaults.cache.local-max-age",
                "cocache.defaults.cache",
                "the declaration sets the whole policy, so defaults don't apply"
            )
        )
    }

    // ---- cache 与 store 两节 ----

    /**
     * `cache` 一节作用于 `Cache`、`VersionedCache`，`store` 一节作用于 `Store`、`VersionedStore`、`SetStore`，互不越界。
     */
    @Test
    fun cacheAndStoreSectionsReachTheirOwnTypes() {
        val factory = factory(
            "cocache.defaults.cache.local-max-age" to "1m",
            "cocache.defaults.cache.absent-ttl" to "3s",
            "cocache.defaults.store.local-max-age" to "10s",
            "cocache.overrides.store.absent-ttl" to "2s",
        )
        factory.create(loaded("user"))
        factory.create(
            versionedCacheSpec<String, String>("view") {
                codec = StringCodec
                versionedLoader = CacheLoader { null }
            }
        )
        factory.create(storeSpec<String, String>("record") { codec = StringCodec })
        factory.create(versionedStoreSpec<String, String>("ledger") { codec = StringCodec })
        factory.create(setStoreSpec<String, String>("index") { elementCodec = StringCodec })
        listOf("user", "view").forEach { name ->
            policy(name).localMaxAge.assert().isEqualTo(Duration.ofMinutes(1))
            policy(name).absentTtl.assert().isEqualTo(Duration.ofSeconds(3))
        }
        listOf("record", "ledger", "index").forEach { name ->
            policy(name).localMaxAge.assert().isEqualTo(Duration.ofSeconds(10))
            policy(name).absentTtl.assert().isEqualTo(Duration.ofSeconds(2))
            policy(name).ttl.assert().isNull()
            report(name).of(PolicyField.ABSENT_TTL).source.assert().isEqualTo(Source.OVERRIDES)
        }
    }

    /**
     * 权威副本的寿命即语义：`defaults.store.ttl` 与 `overrides.store.ttl` 都不允许；带版本类型的 `ttl` 是版本栅栏的寿命，
     * 不进 `overrides`；`unknown-read` 不进任何全局层。
     */
    @Test
    fun globalLayersSkipFieldsTheyMayNotSet() {
        val factory = factory(
            "cocache.defaults.store.ttl" to "1h",
            "cocache.overrides.store.ttl" to "1m",
            "cocache.defaults.store.unknown-read" to "absent",
            "cocache.overrides.cache.ttl" to "1m",
        )
        factory.create(storeSpec<String, String>("record") { codec = StringCodec })
        policy("record").ttl.assert().isNull()
        report("record").skipped.assert().containsExactly(
            PolicyReport.Skipped(
                "cocache.defaults.store.ttl",
                "cocache.defaults.store",
                "not allowed in cocache.defaults"
            ),
            PolicyReport.Skipped(
                "cocache.defaults.store.unknown-read",
                "cocache.defaults.store",
                "not allowed in cocache.defaults"
            ),
            PolicyReport.Skipped(
                "cocache.overrides.store.ttl",
                "cocache.overrides.store",
                "not allowed in cocache.overrides: only staleness bounds, read-failure, fill-in-transaction and invalidate-fills-on-rollback tighten"
            ),
        )
        factory.create(
            versionedCacheSpec<String, String>("view") {
                codec = StringCodec
                versionedLoader = CacheLoader { null }
            }
        )
        policy("view").ttl.assert().isEqualTo(DefaultPolicies.VERSIONED_CACHE.ttl)
        policy("view").ttlCeiling.assert().isNull()
        report("view").skipped.map { it.key }.assert().containsExactly("cocache.overrides.cache.ttl")
    }

    // ---- overrides：只收紧 ----

    @Test
    fun overridesTakeTheMinimumOfStalenessBounds() {
        val factory = factory(
            "cocache.overrides.cache.ttl" to "5m",
            // 比声明宽松：不改变
            "cocache.overrides.cache.local-max-age" to "2m",
            "cocache.overrides.cache.absent-ttl" to "10s",
            "cocache.overrides.cache.degraded-local-max-age" to "1s",
        )
        factory.create(
            loaded("user") {
                policy {
                    ttl = Duration.ofMinutes(10)
                    localMaxAge = Duration.ofMinutes(1)
                    absentTtl = Duration.ofSeconds(30)
                }
            }
        )
        val policy = policy("user")
        policy.ttl.assert().isEqualTo(Duration.ofMinutes(5))
        policy.localMaxAge.assert().isEqualTo(Duration.ofMinutes(1))
        policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(10))
        policy.degradedLocalMaxAge.assert().isEqualTo(Duration.ofSeconds(1))
        val report = report("user")
        report.of(PolicyField.TTL).assert()
            .isEqualTo(PolicyReport.Entry("ttl", "PT5M", Source.OVERRIDES, "tightened from PT10M"))
        report.of(PolicyField.LOCAL_MAX_AGE).source.assert().isEqualTo(Source.DECLARATION)
        report.of(PolicyField.ABSENT_TTL).source.assert().isEqualTo(Source.OVERRIDES)
        report.of(PolicyField.DEGRADED_LOCAL_MAX_AGE).source.assert().isEqualTo(Source.OVERRIDES)
        // forever 让位于任何有限寿命
        factory.create(external("forever") { policy { ttl = forever() } })
        policy("forever").ttl.assert().isEqualTo(Duration.ofMinutes(5))
    }

    /**
     * `overrides.cache.ttl` 另钳制按调用给出的寿命（`put(k, v, ttl)`、`putAll(…, ttl)`），计入 `cocache.ttl.clamped`；
     * 策略自身的寿命与不超过上限的寿命不计。
     */
    @Test
    fun overridesTtlClampsPerCallLifetimes() {
        val cache = factory("cocache.overrides.cache.ttl" to "5m").create(
            external("profile") {
                policy {
                    ttl = Duration.ofHours(1)
                    ttlJitter = Duration.ZERO
                }
            }
        )
        policy("profile").ttlCeiling.assert().isEqualTo(Duration.ofMinutes(5))
        val admin = requireNotNull(runtime.admin("profile"))
        cache.put("long", "v", Duration.ofHours(2))
        admin.metrics.ttlClamped.assert().isEqualTo(1L)
        cache.put("short", "v", Duration.ofMinutes(1))
        cache.put("plain", "v")
        admin.metrics.ttlClamped.assert().isEqualTo(1L)
        cache.putAll(mapOf("a" to "v", "b" to "v"), Duration.ofHours(3))
        admin.metrics.ttlClamped.assert().isEqualTo(2L)
        admin.inspect("long").remote.ttl.assert().isEqualTo(Duration.ofMinutes(5))
        admin.inspect("short").remote.ttl.assert().isEqualTo(Duration.ofMinutes(1))
        admin.inspect("plain").remote.ttl.assert().isEqualTo(Duration.ofMinutes(5))
        admin.inspect("a").remote.ttl.assert().isEqualTo(Duration.ofMinutes(5))
        admin.inspect("b").remote.ttl.assert().isEqualTo(Duration.ofMinutes(5))
        // caches.[name].ttl 是对这个缓存的明确意图：上限抬到它，按调用的寿命至少可以到它；forever 取消上限
        val raised = factory("cocache.overrides.cache.ttl" to "5m", "cocache.caches.raised.ttl" to "1h")
            .create(external("raised"))
        policy("raised").ttlCeiling.assert().isEqualTo(Duration.ofHours(1))
        raised.put("k", "v", Duration.ofMinutes(30))
        requireNotNull(runtime.admin("raised")).inspect("k").remote.ttl.assert().isEqualTo(Duration.ofMinutes(30))
        factory(
            "cocache.overrides.cache.ttl" to "5m",
            "cocache.caches.endless.ttl" to "forever"
        ).create(external("endless"))
        policy("endless").ttlCeiling.assert().isNull()
        // 没有 overrides.cache.ttl 时不钳制
        val unclamped = factory().create(external("unclamped"))
        unclamped.put("k", "v", Duration.ofHours(2))
        requireNotNull(runtime.admin("unclamped")).inspect("k").remote.ttl.assert().isEqualTo(Duration.ofHours(2))
    }

    @Test
    fun overridesTakeTheStricterReadFailure() {
        val strict = factory("cocache.overrides.cache.read-failure" to "throw")
        strict.create(loaded("lenient"))
        policy("lenient").readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        val lenient = report("lenient").of(PolicyField.READ_FAILURE)
        lenient.value.assert().isEqualTo("throw")
        lenient.source.assert().isEqualTo(Source.OVERRIDES)
        lenient.detail.assert().isEqualToIgnoringCase("stricter than degrade")
        // degrade 不能放宽更严的声明
        factory("cocache.overrides.cache.read-failure" to "degrade")
            .create(loaded("strict") { policy { readFailure = ReadFailureMode.THROW } })
        policy("strict").readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        report("strict").of(PolicyField.READ_FAILURE).source.assert().isEqualTo(Source.DECLARATION)
    }

    /**
     * 等待界限、没有严格序的模式与“其余”字段不进 `overrides`：跳过并记录，生效值不变。
     */
    @Test
    fun overridesSkipFieldsThatDoNotTighten() {
        factory(
            "cocache.overrides.cache.lease-wait" to "1ms",
            "cocache.overrides.cache.load-timeout" to "1s",
            "cocache.overrides.cache.refresh-window" to "1s",
            "cocache.overrides.cache.invalidate-failure" to "throw",
            "cocache.overrides.cache.local-maximum-size" to "1",
        ).create(loaded("user"))
        val policy = policy("user")
        policy.lease.wait.assert().isEqualTo(DefaultPolicies.CACHE.lease.wait)
        policy.load.timeout.assert().isEqualTo(DefaultPolicies.CACHE.load.timeout)
        policy.refreshWindow.assert().isNull()
        policy.invalidateFailure.assert().isNull()
        policy.local.maximumSize.assert().isEqualTo(DefaultPolicies.CACHE.local.maximumSize)
        val skipped = report("user").skipped
        skipped.map { it.key }.assert().containsExactly(
            "cocache.overrides.cache.refresh-window",
            "cocache.overrides.cache.lease-wait",
            "cocache.overrides.cache.load-timeout",
            "cocache.overrides.cache.local-maximum-size",
            "cocache.overrides.cache.invalidate-failure",
        )
        skipped.forEach { it.reason.assert().startsWith("not allowed in cocache.overrides") }
    }

    // ---- caches.[name]：按缓存设定 ----

    /**
     * `caches.[name]` 是最高的一层：设定字段，可以放宽声明与 `overrides`。
     */
    @Test
    fun perCacheEntriesSetFields() {
        val factory = factory(
            "cocache.overrides.cache.local-max-age" to "30s",
            "cocache.caches.user.local-max-age" to "2m",
            "cocache.caches.user.ttl" to "20m",
            "cocache.caches.user.lease-wait" to "50ms",
            "cocache.caches.profile.unknown-read" to "absent",
            "cocache.caches.record.unknown-read" to "throw",
            "cocache.caches.record.ttl" to "1d",
        )
        factory.create(
            loaded("user") {
                policy {
                    ttl = Duration.ofMinutes(10)
                    localMaxAge = Duration.ofMinutes(1)
                }
            }
        )
        val policy = policy("user")
        policy.localMaxAge.assert().isEqualTo(Duration.ofMinutes(2))
        policy.ttl.assert().isEqualTo(Duration.ofMinutes(20))
        policy.lease.wait.assert().isEqualTo(Duration.ofMillis(50))
        listOf(PolicyField.LOCAL_MAX_AGE, PolicyField.TTL, PolicyField.LEASE_WAIT).forEach {
            report("user").of(it).source.assert().isEqualTo(Source.CACHES)
        }
        // unknown-read 只能写在 caches.[name]，可以设定任一个值
        factory.create(external("profile"))
        report("profile").of(PolicyField.UNKNOWN_READ).assert()
            .isEqualTo(PolicyReport.Entry("unknown-read", "absent", Source.CACHES))
        factory.create(
            storeSpec<String, String>("record") {
                codec = StringCodec
                unknownRead = UnknownReadMode.ABSENT
            }
        )
        report("record").of(PolicyField.UNKNOWN_READ).value.assert().isEqualTo("throw")
        // 权威副本的 ttl 只能由声明与 caches.[name] 设定
        policy("record").ttl.assert().isEqualTo(Duration.ofDays(1))
    }

    /**
     * `caches.[name]` 设定不适用于该缓存的字段：默认启动失败（不创建缓存），`caches-validation: warn` 时告警并忽略该条目。
     */
    @Test
    fun perCacheFieldThatDoesNotApplyFailsOrWarns() {
        val set = setStoreSpec<String, String>("index") { elementCodec = StringCodec }
        failure { factory("cocache.caches.index.refresh-window" to "10s").create(set) }.assert()
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cache[index] - cocache.caches.index.refresh-window doesn't apply to a SetStore.")
        runtime.cacheNames.assert().doesNotContain("index")
        failure { factory("cocache.caches.user.unknown-read" to "absent").create(loaded("user")) }.assert()
            .hasMessage("Cache[user] - cocache.caches.user.unknown-read doesn't apply to a Cache.")
        failure { factory("cocache.caches.profile.refresh-window" to "1s").create(external("profile")) }.assert()
            .hasMessage(
                "Cache[profile] - cocache.caches.profile.refresh-window doesn't apply to an externally sourced Cache."
            )
        val warnings = warnings(PolicyLayering::class.java) {
            factory(
                "cocache.caches-validation" to "warn",
                "cocache.caches.index.refresh-window" to "10s",
                "cocache.caches.index.local-max-age" to "5s",
            ).create(set)
        }
        warnings.assert().containsExactly(
            "Cache[index] - cocache.caches.index.refresh-window doesn't apply to a SetStore."
        )
        policy("index").localMaxAge.assert().isEqualTo(Duration.ofSeconds(5))
        policy("index").refreshWindow.assert().isNull()
    }

    @Configuration(proxyBeanMethods = false)
    class UserConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader { "user-$it" }
            policy { ttl = Duration.ofMinutes(10) }
        }
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withUserConfiguration(UserConfig::class.java)

    /**
     * `cocache.caches` 中没有对应缓存的名称（多半是拼写错误）：默认启动失败，`caches-validation: warn` 时只告警。
     */
    @Test
    fun unknownCacheNamesFailOrWarn() {
        runner.withPropertyValues("cocache.caches.usr.ttl=1m").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("cocache.caches configures undeclared caches [usr]")
                .hasMessageContaining("declared caches: [user]")
        }
        val warnings = warnings(CacheAssembler::class.java) {
            runner.withPropertyValues("cocache.caches.usr.ttl=1m", "cocache.caches-validation=warn").run { context ->
                assertThat(context).hasNotFailed()
                context.getBean("user")
            }
        }
        warnings.single().assert().contains("undeclared caches [usr]")
    }

    // ---- 锁定与约束 ----

    /**
     * 全局层跳过被锁定的字段并标注锁定者；报告中被锁定的字段来源为 LOCKED。未被锁定的字段照常分层。
     */
    @Test
    fun globalLayersSkipLockedFields() {
        factory(
            "cocache.defaults.cache.local-max-age" to "1m",
            "cocache.overrides.cache.ttl" to "1m",
            "cocache.overrides.cache.absent-ttl" to "5s",
        ).create(
            loaded("tokens") { policy { ttl = Duration.ofMinutes(10) } },
            locks { lock(PolicyField.TTL, PolicyField.LOCAL_MAX_AGE) }
        )
        val policy = policy("tokens")
        policy.ttl.assert().isEqualTo(Duration.ofMinutes(10))
        policy.ttlCeiling.assert().isNull()
        policy.localMaxAge.assert().isEqualTo(DefaultPolicies.CACHE.localMaxAge)
        policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(5))
        val report = report("tokens")
        report.skipped.assert().containsExactly(
            PolicyReport.Skipped(
                "cocache.defaults.cache.local-max-age",
                "cocache.defaults.cache",
                "locked by $LIBRARY"
            ),
            PolicyReport.Skipped("cocache.overrides.cache.ttl", "cocache.overrides.cache", "locked by $LIBRARY"),
        )
        report.of(PolicyField.TTL).assert()
            .isEqualTo(PolicyReport.Entry("ttl", "PT10M", Source.LOCKED, "locked by $LIBRARY"))
        report.of(PolicyField.LOCAL_MAX_AGE).source.assert().isEqualTo(Source.LOCKED)
        report.of(PolicyField.ABSENT_TTL).source.assert().isEqualTo(Source.OVERRIDES)
        // DSL 的锁定者默认是调用 create 的类
        factory("cocache.defaults.cache.ttl" to "1m").create(loaded("dsl")) { lock(PolicyField.TTL) }
        report("dsl").skipped.single().reason.assert().isEqualTo("locked by ${ThreeLayerConfigTest::class.java.name}")
    }

    @Test
    fun perCacheEntryOnALockedFieldFails() {
        listOf("fail", "warn").forEach { validation ->
            failure {
                factory("cocache.caches-validation" to validation, "cocache.caches.tokens.ttl" to "1m")
                    .create(loaded("tokens"), locks { lock(PolicyField.TTL) })
            }.assert().isInstanceOf(IllegalArgumentException::class.java).hasMessage(
                "Cache[tokens] - cocache.caches.tokens.ttl is locked by $LIBRARY; " +
                    "list ttl in cocache.caches.tokens.unlock to override it."
            )
        }
        runtime.cacheNames.assert().isEmpty()
    }

    /**
     * 破窗开关：`caches.[name].unlock` 列出的字段可以覆盖锁定，来源标注为 UNLOCKED；不认识的键启动失败。
     */
    @Test
    fun unlockLetsAPerCacheEntryThrough() {
        factory("cocache.caches.tokens.ttl" to "1m", "cocache.caches.tokens.unlock" to "ttl")
            .create(loaded("tokens") { policy { ttl = Duration.ofMinutes(10) } }, locks { lock(PolicyField.TTL) })
        policy("tokens").ttl.assert().isEqualTo(Duration.ofMinutes(1))
        report("tokens").of(PolicyField.TTL).assert()
            .isEqualTo(PolicyReport.Entry("ttl", "PT1M", Source.UNLOCKED, "was locked by $LIBRARY"))
        failure {
            factory("cocache.caches.other.ttl" to "1m", "cocache.caches.other.unlock" to "tll")
                .create(loaded("other"), locks { lock(PolicyField.TTL) })
        }.assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cache[other] - cocache.caches.other.unlock: tll is not a policy field or layout field.")
    }

    @Test
    fun customizerWritingALockedFieldThrows() {
        val ttlCustomizer = object : CacheSpecCustomizer {
            override fun customize(spec: CacheSpec.Builder<*, *>) {
                spec.policy { ttl = Duration.ofMinutes(1) }
            }
        }
        failure {
            factory(customizers = listOf(ttlCustomizer)).create(loaded("tokens"), locks { lock(PolicyField.TTL) })
        }.assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Cache[tokens] - customizer ${ttlCustomizer.javaClass.name} changed ttl, which is locked by $LIBRARY."
            )
        val prefixCustomizer = object : CacheSpecCustomizer {
            override fun customize(spec: CacheSpec.Builder<*, *>) {
                spec.keyPrefix = "custom:"
            }
        }
        failure {
            factory(customizers = listOf(prefixCustomizer))
                .create(loaded("tokens"), locks { lockLayout(LayoutField.KEY_PREFIX) })
        }.assert().isInstanceOf(IllegalStateException::class.java).hasMessage(
            "Cache[tokens] - customizer ${prefixCustomizer.javaClass.name} changed KEY_PREFIX, which is locked by $LIBRARY."
        )
        runtime.cacheNames.assert().isEmpty()
        // 未被锁定的字段照常修改，来源为定制器
        factory(customizers = listOf(ttlCustomizer)).create(loaded("tokens"), locks { lock(PolicyField.LOCAL_MAX_AGE) })
        report("tokens").of(PolicyField.TTL).assert()
            .isEqualTo(PolicyReport.Entry("ttl", "PT1M", Source.CUSTOMIZER, ttlCustomizer.javaClass.name))
    }

    /**
     * 定制器只能修改策略字段与不决定类型的字段（U16）：改变数据来源时抛出，无论是否锁定。
     */
    @Test
    fun customizerChangingTheDataSourceThrows() {
        val customizer = object : CacheSpecCustomizer {
            override fun customize(spec: CacheSpec.Builder<*, *>) {
                spec.loader = null
                spec.externallySourced()
            }
        }
        failure { factory(customizers = listOf(customizer)).create(loaded("user")) }.assert()
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageStartingWith("Cache[user] - customizer ${customizer.javaClass.name} changed the data source")
        runtime.cacheNames.assert().isEmpty()
    }

    /**
     * `constrain` 在合并之后执行，值域包含哨兵：`finiteOr` 让 `forever`（包括 `caches.[name]` 写出的）变为有限寿命。
     */
    @Test
    fun constrainRunsAfterTheMerge() {
        val expiring = locks { constrain(PolicyField.TTL) { it.finiteOr(Duration.ofMinutes(1)) } }
        val factory = factory("cocache.caches.tokens.ttl" to "forever")
        factory.create(external("tokens") { policy { ttl = Duration.ofMinutes(10) } }, expiring)
        policy("tokens").ttl.assert().isEqualTo(Duration.ofMinutes(1))
        report("tokens").of(PolicyField.TTL).assert()
            .isEqualTo(PolicyReport.Entry("ttl", "PT1M", Source.LOCKED, "constrained by $LIBRARY"))
        // 有限寿命原样通过
        factory.create(external("finite") { policy { ttl = Duration.ofMinutes(10) } }, expiring)
        policy("finite").ttl.assert().isEqualTo(Duration.ofMinutes(10))
        report("finite").of(PolicyField.TTL).source.assert().isEqualTo(Source.DECLARATION)
    }

    // ---- key-prefix ----

    /**
     * `cocache.key-prefix` 不参与分层，配置优先于声明；`caches.[name].key-prefix` 优先于它；锁定了 `KEY_PREFIX` 的缓存
     * 跳过全局前缀，按缓存的前缀启动失败（除非 `unlock`）。`defaults` 中的 `key-prefix` 不允许。
     */
    @Test
    fun keyPrefixFromConfigurationUnlessLocked() {
        val pinned = locks { lockLayout(LayoutField.KEY_PREFIX) }
        val factory = factory(
            "cocache.key-prefix" to "app:",
            "cocache.defaults.cache.key-prefix" to "ignored:",
            "cocache.caches.own.key-prefix" to "own:",
            "cocache.caches.unlocked.key-prefix" to "x:",
            "cocache.caches.unlocked.unlock" to "key-prefix",
        )
        factory.create(external("declared") { keyPrefix = "declared:" })
        storageKey("declared").assert().isEqualTo("app:declared:k")
        report("declared").skipped.assert().containsExactly(
            PolicyReport.Skipped(
                "cocache.defaults.cache.key-prefix",
                "cocache.defaults.cache",
                "not allowed here; use cocache.key-prefix"
            )
        )
        factory.create(external("own") { keyPrefix = "declared:" })
        storageKey("own").assert().isEqualTo("own:own:k")
        factory.create(external("pinned") { keyPrefix = "lib:" }, pinned)
        storageKey("pinned").assert().isEqualTo("lib:pinned:k")
        report("pinned").skipped.assert().contains(
            PolicyReport.Skipped("cocache.key-prefix", "cocache", "the key prefix is locked by $LIBRARY")
        )
        factory.create(external("unlocked") { keyPrefix = "lib:" }, pinned)
        storageKey("unlocked").assert().isEqualTo("x:unlocked:k")
        failure {
            factory("cocache.caches.locked.key-prefix" to "x:")
                .create(external("locked") { keyPrefix = "lib:" }, pinned)
        }.assert().isInstanceOf(IllegalArgumentException::class.java).hasMessage(
            "Cache[locked] - cocache.caches.locked.key-prefix: the key prefix is locked by $LIBRARY; " +
                "list key-prefix in cocache.caches.locked.unlock to override it."
        )
    }

    // ---- 校验连锁 ----

    /**
     * 全局层让声明的提前刷新窗口不再小于 `local-max-age`：关闭窗口（来源 CLAMPED）而不是启动失败。
     */
    @Test
    fun globalLocalMaxAgeBelowTheDeclaredWindowTurnsItOff() {
        factory("cocache.defaults.cache.local-max-age" to "1m")
            .create(loaded("user") { policy { refreshWindow = Duration.ofMinutes(2) } })
        policy("user").refreshWindow.assert().isNull()
        policy("user").localMaxAge.assert().isEqualTo(Duration.ofMinutes(1))
        val window = report("user").of(PolicyField.REFRESH_WINDOW)
        window.value.assert().isEqualTo("off")
        window.source.assert().isEqualTo(Source.CLAMPED)
        requireNotNull(window.detail).assert().startsWith("turned off: refresh-window[PT2M]")
        factory("cocache.overrides.cache.local-max-age" to "1m").create(
            loaded("other") {
                policy {
                    localMaxAge = Duration.ofMinutes(5)
                    refreshWindow = Duration.ofMinutes(2)
                }
            }
        )
        policy("other").refreshWindow.assert().isNull()
        report("other").of(PolicyField.REFRESH_WINDOW).source.assert().isEqualTo(Source.CLAMPED)
        report("other").of(PolicyField.LOCAL_MAX_AGE).source.assert().isEqualTo(Source.OVERRIDES)
    }

    /**
     * 只由声明或 `caches.[name]` 引起的冲突不夹紧，启动失败。
     */
    /**
     * 冲突牵涉 caches.[name] 写出的字段时不夹紧：全局的 ttl 加上按缓存的提前刷新窗口照常失败（U10）。
     */
    @Test
    fun aConflictInvolvingThePerCacheLayerFails() {
        failure {
            factory("cocache.defaults.cache.ttl" to "10m", "cocache.caches.user.refresh-window" to "20m")
                .create(loaded("user"))
        }.assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("refreshWindow[PT20M] must be less than ttl[PT10M]")
        runtime.cacheNames.assert().isEmpty()
    }

    @Test
    fun perCacheLocalMaxAgeBelowTheWindowFails() {
        failure {
            factory("cocache.caches.user.local-max-age" to "1m")
                .create(loaded("user") { policy { refreshWindow = Duration.ofMinutes(2) } })
        }.assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cache[user] - refreshWindow[PT2M] must be less than localMaxAge[PT1M].")
        runtime.cacheNames.assert().isEmpty()
    }

    // ---- 单位与哨兵 ----

    /**
     * 时长必须带单位：不带单位的值在绑定时失败，错误信息只给出问题与写法，不提以前的版本。
     */
    @Test
    fun durationsNeedAUnit() {
        listOf(
            "cocache.defaults.cache.ttl" to "60",
            "cocache.overrides.store.absent-ttl" to "5",
            "cocache.caches.user.refresh-window" to "0",
            "cocache.caches.user.lease-wait" to "1.5",
            "cocache.redis.command-timeout" to "1500",
            "cocache.redis.heartbeat" to "5",
        ).forEach { (key, value) ->
            // redis 的时长是命令级的，示例单位用毫秒
            val unit = if (key.startsWith("cocache.redis.")) "ms" else "s"
            rootCauseOf(failure { properties(key to value) }).assert()
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("$key: \"$value\" needs a unit, e.g. $value$unit, 5m, PT2M.")
                .hasMessageNotContaining("6.x")
        }
        runner.withPropertyValues("cocache.defaults.cache.ttl=60").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert()
                .hasStackTraceContaining("cocache.defaults.cache.ttl: \"60\" needs a unit, e.g. 60s, 5m, PT2M.")
        }
    }

    @Test
    fun invalidValuesNameTheKey() {
        mapOf(
            ("cocache.caches.user.ttl" to "never") to "cocache.caches.user.ttl: \"never\" is not a duration",
            ("cocache.caches.user.absent-ttl" to "0s") to "cocache.caches.user.absent-ttl: \"0s\" must be positive.",
            ("cocache.caches.user.lease-wait" to "-1s") to "cocache.caches.user.lease-wait: \"-1s\" must not be negative.",
            ("cocache.caches.user.load-max-concurrent" to "0") to
                "cocache.caches.user.load-max-concurrent: \"0\" must be positive; write unlimited for no limit.",
            ("cocache.caches.user.load-max-concurrent" to "many") to
                "cocache.caches.user.load-max-concurrent: \"many\" must be a positive number of permits or unlimited.",
            ("cocache.caches.user.local-maximum-size" to "0") to "cocache.caches.user.local-maximum-size: 0 must be positive.",
        ).forEach { (entry, message) ->
            // 解析失败带完整的键（"is not a duration" 另以 Spring 的解析错误为原因）
            generateSequence(failure { properties(entry) }) { it.cause }.mapNotNull { it.message }.toList()
                .assert().withFailMessage { "No cause of ${entry.first}=${entry.second} starts with: $message" }
                .anyMatch { it.startsWith(message) }
        }
    }

    /**
     * 哨兵显式：`forever`（寿命）、`off`（提前刷新窗口）、`unlimited`（回源并发上限），报告中照样写出。
     */
    @Test
    fun sentinelsAreExplicit() {
        factory(
            "cocache.caches.view.ttl" to "forever",
            "cocache.caches.view.refresh-window" to "off",
            "cocache.caches.view.load-max-concurrent" to "unlimited",
        ).create(
            versionedCacheSpec<String, String>("view") {
                codec = StringCodec
                versionedLoader = CacheLoader { null }
                policy {
                    ttl = Duration.ofMinutes(10)
                    refreshWindow = Duration.ofSeconds(30)
                    load = LoadPolicy(maxConcurrent = 4)
                }
            }
        )
        val policy = policy("view")
        policy.ttl.assert().isNull()
        policy.refreshWindow.assert().isNull()
        policy.load.maxConcurrent.assert().isNull()
        val report = report("view")
        report.of(PolicyField.TTL).value.assert().isEqualTo("forever")
        report.of(PolicyField.REFRESH_WINDOW).value.assert().isEqualTo("off")
        report.of(PolicyField.LOAD_MAX_CONCURRENT).value.assert().isEqualTo("unlimited")
        // 未设置的模式字段写作小写的配置值
        val retry = InvalidateFailureMode.RETRY.name.lowercase()
        report.of(PolicyField.INVALIDATE_FAILURE).assert()
            .isEqualTo(PolicyReport.Entry("invalidate-failure", retry, Source.TYPE_DEFAULT))
    }

    // ---- 端点 ----

    /**
     * 端点列出每个适用字段的生效值与来源：未经任何一层改变的字段为 TYPE_DEFAULT；以及全局层中被跳过的条目。
     */
    @Test
    fun endpointReportsEveryFieldWithItsSource() {
        runner.withConfiguration(AutoConfigurations.of(CoCacheEndpointAutoConfiguration::class.java))
            .withPropertyValues(
                "management.endpoints.web.exposure.include=cocache",
                "cocache.defaults.cache.local-max-age=1m",
                "cocache.defaults.cache.unknown-read=absent",
                "cocache.overrides.cache.absent-ttl=20s",
                "cocache.caches.user.lease-wait=50ms",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean("user")
                val report = requireNotNull(context.getBean(CoCacheEndpoint::class.java).cache("user"))
                report.layered.assert().isTrue()
                // 策略字段之后是写事务内回源的两个字段（有 spring-tx 时）
                report.policy.map { it.field }.assert().containsExactlyElementsOf(
                    (PolicyField.POLICY_FIELDS - PolicyField.TOMBSTONE_TTL).map { it.key } +
                        listOf("invalidate-fills-on-rollback", "fill-in-transaction")
                )
                val entries = report.policy.associateBy { it.field }
                entries["ttl"].assert().isEqualTo(PolicyReport.Entry("ttl", "PT10M", Source.DECLARATION))
                entries["local-max-age"].assert().isEqualTo(
                    PolicyReport.Entry("local-max-age", "PT1M", Source.DEFAULTS)
                )
                entries["absent-ttl"].assert()
                    .isEqualTo(PolicyReport.Entry("absent-ttl", "PT20S", Source.OVERRIDES, "tightened from PT1M"))
                entries["lease-wait"].assert().isEqualTo(PolicyReport.Entry("lease-wait", "PT0.05S", Source.CACHES))
                entries["lease-ttl"].assert().isEqualTo(
                    PolicyReport.Entry("lease-ttl", DefaultPolicies.CACHE.lease.ttl.toString(), Source.TYPE_DEFAULT)
                )
                entries["read-timeout"].assert()
                    .isEqualTo(PolicyReport.Entry("read-timeout", "command-timeout", Source.TYPE_DEFAULT))
                report.skippedConfiguration.assert().containsExactly(
                    PolicyReport.Skipped(
                        "cocache.defaults.cache.unknown-read",
                        "cocache.defaults.cache",
                        "not applicable to a Cache"
                    )
                )
            }
    }

    private companion object {
        const val LIBRARY = "my-lib"
    }
}
