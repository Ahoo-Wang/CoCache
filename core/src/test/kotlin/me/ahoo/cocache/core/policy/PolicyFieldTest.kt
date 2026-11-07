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

package me.ahoo.cocache.core.policy

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 分层的词汇（RFC 0005 §4.8）：哨兵值、字段的读写与类别、适用性表、声明写出的字段、每种类型的默认策略。
 */
class PolicyFieldTest {
    @Test
    fun ttlTreatsForeverAsInfinity() {
        val minute = Ttl.Finite(Duration.ofMinutes(1))
        Ttl.Forever.min(minute).assert().isEqualTo(minute)
        minute.min(Ttl.Forever).assert().isEqualTo(minute)
        minute.min(Ttl.Finite(Duration.ofSeconds(1))).assert().isEqualTo(Ttl.Finite(Duration.ofSeconds(1)))
        Ttl.Forever.finiteOr(Duration.ofMinutes(2)).assert().isEqualTo(Ttl.Finite(Duration.ofMinutes(2)))
        minute.finiteOr(Duration.ofMinutes(2)).assert().isSameAs(minute)
        Ttl.of(null).assert().isEqualTo(Ttl.Forever)
        Ttl.Forever.toDuration().assert().isNull()
        Ttl.Forever.toString().assert().isEqualTo("forever")
        minute.toString().assert().isEqualTo("PT1M")
        runCatching { Ttl.Finite(Duration.ZERO) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun refreshWindowAndConcurrencyHaveExplicitSentinels() {
        RefreshWindow.of(null).assert().isEqualTo(RefreshWindow.Off)
        RefreshWindow.Off.toString().assert().isEqualTo("off")
        RefreshWindow.of(Duration.ofSeconds(5)).toDuration().assert().isEqualTo(Duration.ofSeconds(5))
        RefreshWindow.Of(Duration.ofSeconds(5)).toString().assert().isEqualTo("PT5S")
        runCatching {
            RefreshWindow.Of(Duration.ZERO)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        Concurrency.of(null).assert().isEqualTo(Concurrency.Unlimited)
        Concurrency.Unlimited.toString().assert().isEqualTo("unlimited")
        Concurrency.of(4).toPermits().assert().isEqualTo(4)
        Concurrency.Max(4).toString().assert().isEqualTo("4")
        runCatching { Concurrency.Max(0) }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            LoadPolicy(maxConcurrent = 0)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun everyFieldReadsAndWritesItsDraft() {
        val draft = PolicyDraft(CachePolicy.builder(DefaultPolicies.CACHE))
        draft[PolicyField.TTL] = Ttl.Forever
        draft[PolicyField.TTL_JITTER] = Duration.ofSeconds(2)
        draft[PolicyField.ABSENT_TTL] = Duration.ofSeconds(3)
        draft[PolicyField.LOCAL_MAX_AGE] = Duration.ofSeconds(40)
        draft[PolicyField.DEGRADED_LOCAL_MAX_AGE] = Duration.ofSeconds(5)
        draft[PolicyField.TOMBSTONE_TTL] = Ttl.Finite(Duration.ofHours(2))
        draft[PolicyField.REFRESH_WINDOW] = RefreshWindow.Of(Duration.ofSeconds(10))
        draft[PolicyField.LEASE_TTL] = Duration.ofSeconds(6)
        draft[PolicyField.LEASE_WAIT] = Duration.ofMillis(7)
        draft[PolicyField.LOAD_MAX_CONCURRENT] = Concurrency.Max(8)
        draft[PolicyField.LOAD_ACQUIRE_TIMEOUT] = Duration.ofMillis(9)
        draft[PolicyField.LOAD_TIMEOUT] = Duration.ofSeconds(11)
        draft[PolicyField.READ_TIMEOUT] = Duration.ofMillis(12)
        draft[PolicyField.LOCAL_MAXIMUM_SIZE] = 13L
        draft[PolicyField.INVALIDATE_FAILURE] = InvalidateFailureMode.THROW
        draft[PolicyField.READ_FAILURE] = ReadFailureMode.THROW
        draft[PolicyField.UNKNOWN_READ] = UnknownReadMode.ABSENT

        val policy = draft.policy.build()
        policy.ttl.assert().isNull()
        policy.ttlJitter.assert().isEqualTo(Duration.ofSeconds(2))
        policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(3))
        policy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(40))
        policy.degradedLocalMaxAge.assert().isEqualTo(Duration.ofSeconds(5))
        policy.tombstoneTtl.assert().isEqualTo(Duration.ofHours(2))
        policy.refreshWindow.assert().isEqualTo(Duration.ofSeconds(10))
        policy.lease.assert().isEqualTo(LeasePolicy(Duration.ofSeconds(6), Duration.ofMillis(7)))
        policy.load.assert().isEqualTo(LoadPolicy(8, Duration.ofMillis(9), Duration.ofSeconds(11)))
        policy.readTimeout.assert().isEqualTo(Duration.ofMillis(12))
        policy.local.maximumSize.assert().isEqualTo(13L)
        policy.invalidateFailure.assert().isEqualTo(InvalidateFailureMode.THROW)
        policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        draft.unknownRead.assert().isEqualTo(UnknownReadMode.ABSENT)
        PolicyField.ALL.forEach { field -> draft[field].assert().withFailMessage { "${field.key} reads back" }.isNotNull() }

        PolicyField.TTL.describe(draft).assert().isEqualTo("forever")
        PolicyField.READ_FAILURE.describe(draft).assert().isEqualTo("throw")
        PolicyField.UNKNOWN_READ.describe(draft).assert().isEqualTo("absent")
        PolicyField.TTL.toString().assert().isEqualTo("ttl")
    }

    /**
     * 未设置的值：读超时为“命令超时”，墓碑寿命同值的寿命，模式为各自的默认值；显式写出的“永不过期”墓碑以上限保存。
     */
    @Test
    fun unsetValuesReadAsTheirMeaning() {
        val draft = PolicyDraft(CachePolicy.builder(DefaultPolicies.CACHE))
        PolicyField.READ_TIMEOUT.describe(draft).assert().isEqualTo("command-timeout")
        draft[PolicyField.READ_TIMEOUT] = draft[PolicyField.READ_TIMEOUT]
        draft.policy.readTimeout.assert().isNull()
        draft[PolicyField.TOMBSTONE_TTL].assert().isEqualTo(Ttl.Finite(CachePolicy.DEFAULT_TTL))
        draft[PolicyField.INVALIDATE_FAILURE].assert().isEqualTo(InvalidateFailureMode.RETRY)
        draft[PolicyField.READ_FAILURE].assert().isEqualTo(ReadFailureMode.DEGRADE)
        draft[PolicyField.UNKNOWN_READ].assert().isEqualTo(UnknownReadMode.THROW)
        draft[PolicyField.TOMBSTONE_TTL] = Ttl.Forever
        draft.policy.tombstoneTtl.assert().isEqualTo(MAX_DURATION)
        draft[PolicyField.TOMBSTONE_TTL].assert().isEqualTo(Ttl.Forever)
        PolicyField.TOMBSTONE_TTL.describe(draft).assert().isEqualTo("forever")
        PolicyField.of("local-max-age").assert().isEqualTo(PolicyField.LOCAL_MAX_AGE)
        PolicyField.of("unknown").assert().isNull()
    }

    @Test
    fun categoriesFollowTheFieldByLayerTable() {
        PolicyField.TTL.categoryFor(Cache::class.java).assert().isEqualTo(PolicyField.Category.STALENESS_BOUND)
        PolicyField.TTL.categoryFor(VersionedCache::class.java).assert().isEqualTo(PolicyField.Category.VERSION_FENCE)
        PolicyField.TTL.categoryFor(VersionedStore::class.java).assert().isEqualTo(PolicyField.Category.VERSION_FENCE)
        PolicyField.TTL.categoryFor(Store::class.java).assert().isEqualTo(PolicyField.Category.SEMANTIC_LIFETIME)
        PolicyField.TTL.categoryFor(SetStore::class.java).assert().isEqualTo(PolicyField.Category.SEMANTIC_LIFETIME)
        PolicyField.TOMBSTONE_TTL.categoryFor(
            VersionedStore::class.java
        ).assert().isEqualTo(PolicyField.Category.VERSION_FENCE)
        listOf(PolicyField.LOCAL_MAX_AGE, PolicyField.DEGRADED_LOCAL_MAX_AGE, PolicyField.ABSENT_TTL).forEach {
            it.categoryFor(Cache::class.java).assert().isEqualTo(PolicyField.Category.STALENESS_BOUND)
        }
        PolicyField.READ_FAILURE.categoryFor(Cache::class.java).assert().isEqualTo(PolicyField.Category.ORDERED_MODE)
        PolicyField.INVALIDATE_FAILURE.categoryFor(Cache::class.java).assert().isEqualTo(PolicyField.Category.MODE)
        PolicyField.UNKNOWN_READ.categoryFor(Store::class.java).assert().isEqualTo(PolicyField.Category.MODE)
        listOf(
            PolicyField.LEASE_WAIT,
            PolicyField.LOAD_TIMEOUT,
            PolicyField.LOAD_ACQUIRE_TIMEOUT,
            PolicyField.READ_TIMEOUT
        ).forEach {
            it.categoryFor(Cache::class.java).assert().isEqualTo(PolicyField.Category.WAIT_BOUND)
        }
        listOf(
            PolicyField.TTL_JITTER,
            PolicyField.REFRESH_WINDOW,
            PolicyField.LEASE_TTL,
            PolicyField.LOAD_MAX_CONCURRENT,
            PolicyField.LOCAL_MAXIMUM_SIZE
        ).forEach { it.categoryFor(Cache::class.java).assert().isEqualTo(PolicyField.Category.OTHER) }
    }

    @Test
    fun applicabilityFollowsTheTypeAndTheSource() {
        val loaded = cacheSpec<String, String>("loaded") {
            codec = StringCodec
            loader = CacheLoader { it }
        }
        val external = cacheSpec<String, String>("external") {
            codec = StringCodec
            externallySourced()
        }
        val versionedCache = versionedCacheSpec<String, String>("doc") {
            codec = StringCodec
            versionedLoader = CacheLoader { Versioned(it, 1) }
        }
        val store = storeSpec<String, String>("store") { codec = StringCodec }
        val versionedStore = versionedStoreSpec<String, String>("aggregate") { codec = StringCodec }
        val setStore = setStoreSpec<String, String>("tags") { elementCodec = StringCodec }

        PolicyField.applicableTo(
            loaded
        ).assert().containsExactlyElementsOf(PolicyField.POLICY_FIELDS - PolicyField.TOMBSTONE_TTL)
        PolicyField.applicableTo(external).assert().contains(PolicyField.UNKNOWN_READ, PolicyField.READ_FAILURE)
            .doesNotContain(PolicyField.REFRESH_WINDOW, PolicyField.TOMBSTONE_TTL)
        PolicyField.applicableTo(versionedCache).assert().containsExactlyElementsOf(PolicyField.POLICY_FIELDS)
        PolicyField.applicableTo(store).assert().contains(PolicyField.ABSENT_TTL, PolicyField.UNKNOWN_READ)
            .doesNotContain(
                PolicyField.READ_FAILURE,
                PolicyField.LEASE_WAIT,
                PolicyField.REFRESH_WINDOW,
                PolicyField.TOMBSTONE_TTL
            )
        PolicyField.applicableTo(versionedStore).assert().contains(PolicyField.TOMBSTONE_TTL, PolicyField.UNKNOWN_READ)
        PolicyField.applicableTo(setStore).assert().doesNotContain(PolicyField.UNKNOWN_READ, PolicyField.TOMBSTONE_TTL)
            .contains(PolicyField.TTL, PolicyField.READ_TIMEOUT)
    }

    /**
     * 字段三态（U11）：`policy { … }` 只记下赋值的字段，整体赋值视为全部写出，`unknownRead` 的赋值单独记下；`toBuilder` 保留它们。
     */
    @Test
    fun specsRecordTheFieldsTheirDeclarationWrites() {
        val partial = storeSpec<String, String>("partial") {
            codec = StringCodec
            policy { localMaxAge = Duration.ofSeconds(5) }
            unknownRead = UnknownReadMode.ABSENT
        }
        partial.declaredFields.assert().containsExactly(PolicyField.LOCAL_MAX_AGE, PolicyField.UNKNOWN_READ)
        partial.policy.ttl.assert().isNull()
        partial.toBuilder().build().declaredFields.assert().isEqualTo(partial.declaredFields)
        partial.toBuilder().apply { updatePolicy { it.absentTtl = Duration.ofSeconds(1) } }.build().declaredFields
            .assert().containsExactly(PolicyField.LOCAL_MAX_AGE, PolicyField.UNKNOWN_READ, PolicyField.ABSENT_TTL)

        val whole = storeSpec<String, String>("whole") {
            codec = StringCodec
            policy = DefaultPolicies.STORE
        }
        whole.declaredFields.assert().containsAll(PolicyField.POLICY_FIELDS)
        val untouched = cacheSpec<String, String>("untouched") {
            codec = StringCodec
            loader = CacheLoader { it }
        }
        untouched.declaredFields.assert().isEmpty()
        val lease = cacheSpec<String, String>("lease") {
            codec = StringCodec
            loader = CacheLoader { it }
            policy {
                lease = LeasePolicy(ttl = Duration.ofSeconds(5))
                load = LoadPolicy(maxConcurrent = 8)
                local = LocalPolicy(maximumSize = 5)
                ttl = forever()
                refreshWindow = off()
            }
        }
        // 子对象只记下取值改变了的字段：lease.wait、load 的两个时限没有改变，仍留给下层
        lease.declaredFields.assert().containsExactlyInAnyOrder(
            PolicyField.LEASE_TTL,
            PolicyField.LOAD_MAX_CONCURRENT,
            PolicyField.LOCAL_MAXIMUM_SIZE,
            PolicyField.TTL,
            PolicyField.REFRESH_WINDOW,
        )
        lease.policy.ttl.assert().isNull()
    }

    /**
     * 与当前值相同的“不限”：整体赋值 `load` 只记下取值改变了的字段，写不出它；经 `maxConcurrent` 赋值才写出（Kotlin 与 Java 两种写法）。
     */
    @Test
    fun unlimitedConcurrencyIsWrittenThroughMaxConcurrent() {
        val sameAsDefault = cacheSpec<String, String>("same") {
            codec = StringCodec
            loader = CacheLoader { it }
            policy { load = LoadPolicy(maxConcurrent = unlimited()) }
        }
        sameAsDefault.declaredFields.assert().isEmpty()
        val unlimited = cacheSpec<String, String>("unlimited") {
            codec = StringCodec
            loader = CacheLoader { it }
            policy {
                load = LoadPolicy(maxConcurrent = 8)
                maxConcurrent = unlimited()
            }
        }
        unlimited.declaredFields.assert().containsExactly(PolicyField.LOAD_MAX_CONCURRENT)
        unlimited.policy.load.maxConcurrent.assert().isNull()
        val java = cacheSpec<String, String>("java") {
            codec = StringCodec
            loader = CacheLoader { it }
            updatePolicy { it.maxConcurrent(null) }
        }
        java.declaredFields.assert().containsExactly(PolicyField.LOAD_MAX_CONCURRENT)
        CachePolicy.builder(DefaultPolicies.CACHE).maxConcurrent(4).maxConcurrent.assert().isEqualTo(4)
    }

    /**
     * 与当前值相同的 L2 条数上限：整体赋值 `local` 写不出它；经 `localMaximumSize` 赋值才写出，其余 `local` 字段保留。
     */
    @Test
    fun sameLocalMaximumSizeIsWrittenThroughLocalMaximumSize() {
        val sameAsDefault = cacheSpec<String, String>("same") {
            codec = StringCodec
            loader = CacheLoader { it }
            policy { local = LocalPolicy(maximumSize = 10_000) }
        }
        sameAsDefault.declaredFields.assert().isEmpty()
        val explicit = cacheSpec<String, String>("explicit") {
            codec = StringCodec
            loader = CacheLoader { it }
            policy {
                local = LocalPolicy(initialCapacity = 16)
                localMaximumSize = 10_000
            }
        }
        explicit.declaredFields.assert().containsExactly(PolicyField.LOCAL_MAXIMUM_SIZE)
        explicit.policy.local.assert().isEqualTo(LocalPolicy(maximumSize = 10_000, initialCapacity = 16))
        val java = cacheSpec<String, String>("java") {
            codec = StringCodec
            loader = CacheLoader { it }
            updatePolicy { it.localMaximumSize(10_000) }
        }
        java.declaredFields.assert().containsExactly(PolicyField.LOCAL_MAXIMUM_SIZE)
        CachePolicy.builder(DefaultPolicies.CACHE).localMaximumSize(4).localMaximumSize.assert().isEqualTo(4)
    }

    /**
     * 每种 spec 以自己的默认策略为起点（S23）：权威类型永不过期、L2 寿命更短，`VersionedStore` 的墓碑 1 天。
     */
    @Test
    fun eachSpecStartsFromItsTypeDefault() {
        cacheSpec<String, String>("c") {
            codec = StringCodec
            loader = CacheLoader { it }
        }.policy.assert().isSameAs(DefaultPolicies.CACHE)
        versionedCacheSpec<String, String>("v") {
            codec = StringCodec
            versionedLoader = CacheLoader { Versioned(it, 1) }
        }.policy.assert().isSameAs(DefaultPolicies.VERSIONED_CACHE)
        storeSpec<String, String>("s") { codec = StringCodec }.policy.assert().isSameAs(DefaultPolicies.STORE)
        versionedStoreSpec<String, String>("vs") {
            codec = StringCodec
        }.policy.assert().isSameAs(DefaultPolicies.VERSIONED_STORE)
        setStoreSpec<String, String>(
            "set"
        ) { elementCodec = StringCodec }.policy.assert().isSameAs(DefaultPolicies.SET_STORE)
        DefaultPolicies.STORE.ttl.assert().isNull()
        DefaultPolicies.STORE.localMaxAge.assert().isEqualTo(Duration.ofSeconds(30))
        DefaultPolicies.STORE.absentTtl.assert().isEqualTo(Duration.ofSeconds(10))
        DefaultPolicies.VERSIONED_STORE.tombstoneTtl.assert().isEqualTo(Duration.ofDays(1))
        DefaultPolicies.CACHE.ttl.assert().isEqualTo(Duration.ofHours(1))
        CachePolicy.builder(DefaultPolicies.STORE).build().ttl.assert().isNull()

        // 显式写出的“永不过期”墓碑同样不是有限的寿命
        runCatching {
            versionedStoreSpec<String, String>("forever") {
                codec = StringCodec
                policy { tombstoneTtl = MAX_DURATION }
            }
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }
}
