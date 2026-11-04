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
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import java.time.Duration

/**
 * 可分层的策略字段（RFC 0005 §4.8）：配置键（[key]，kebab-case）、取值类型 [T] 与“字段 × 层”表中的类别（[categoryFor]）。
 * 声明写出了哪些字段见 `Spec.declaredFields`；`lock` / `constrain` 按字段锁定或约束（`CoCacheFactory`）。
 *
 * 取值经 [PolicyDraft] 读写：寿命用 [Ttl]、提前刷新窗口用 [RefreshWindow]、回源并发上限用 [Concurrency]，哨兵值显式。
 */
sealed class PolicyField<T : Any>(val key: String) {
    /**
     * 这个字段在缓存类型 [apiType]（用户 API 类型）上的类别，决定它在各个全局层中的语义。
     */
    open fun categoryFor(apiType: Class<*>): Category = Category.OTHER

    internal abstract fun read(draft: PolicyDraft): T

    internal abstract fun write(draft: PolicyDraft, value: T)

    /**
     * 生效值的展示（启动日志与端点）：哨兵写作 `forever`、`off`、`unlimited`，模式写作小写的配置值。
     */
    open fun describe(draft: PolicyDraft): String = when (val value = read(draft)) {
        is Enum<*> -> value.name.lowercase()
        else -> value.toString()
    }

    override fun toString(): String = key

    /**
     * “字段 × 层”表中的类别（S8、S9、S10）。
     */
    enum class Category {
        /**
         * 陈旧界限：`overrides` 取 min（只收紧）。
         */
        STALENESS_BOUND,

        /**
         * 有严格序的模式：`overrides` 取更严的值。
         */
        ORDERED_MODE,

        /**
         * 没有严格序的模式：不进 `overrides`。
         */
        MODE,

        /**
         * 等待界限：调小会放大回源，不进 `overrides`。
         */
        WAIT_BOUND,

        /**
         * 版本栅栏的寿命（带版本的类型的 `ttl`、`tombstone-ttl`）：不进 `overrides`。
         */
        VERSION_FENCE,

        /**
         * 寿命即语义（`Store`、`SetStore` 的 `ttl`）：只能由声明与 `caches.[name]` 设定。
         */
        SEMANTIC_LIFETIME,

        /**
         * 其余：`defaults` 与 `caches.[name]` 设定，不进 `overrides`。
         */
        OTHER,
    }

    object TTL : PolicyField<Ttl>("ttl") {
        override fun categoryFor(apiType: Class<*>): Category = when (apiType) {
            Cache::class.java -> Category.STALENESS_BOUND
            VersionedCache::class.java, VersionedStore::class.java -> Category.VERSION_FENCE
            else -> Category.SEMANTIC_LIFETIME
        }

        override fun read(draft: PolicyDraft): Ttl = Ttl.of(draft.policy.ttl)

        override fun write(draft: PolicyDraft, value: Ttl) {
            draft.policy.ttl = value.toDuration()
        }
    }

    object TTL_JITTER : PolicyField<Duration>("ttl-jitter") {
        override fun read(draft: PolicyDraft): Duration = draft.policy.ttlJitter

        override fun write(draft: PolicyDraft, value: Duration) {
            draft.policy.ttlJitter = value
        }
    }

    object ABSENT_TTL : PolicyField<Duration>("absent-ttl") {
        override fun categoryFor(apiType: Class<*>): Category = Category.STALENESS_BOUND

        override fun read(draft: PolicyDraft): Duration = draft.policy.absentTtl

        override fun write(draft: PolicyDraft, value: Duration) {
            draft.policy.absentTtl = value
        }
    }

    object LOCAL_MAX_AGE : PolicyField<Duration>("local-max-age") {
        override fun categoryFor(apiType: Class<*>): Category = Category.STALENESS_BOUND

        override fun read(draft: PolicyDraft): Duration = draft.policy.localMaxAge

        override fun write(draft: PolicyDraft, value: Duration) {
            draft.policy.localMaxAge = value
        }
    }

    object DEGRADED_LOCAL_MAX_AGE : PolicyField<Duration>("degraded-local-max-age") {
        override fun categoryFor(apiType: Class<*>): Category = Category.STALENESS_BOUND

        override fun read(draft: PolicyDraft): Duration = draft.policy.degradedLocalMaxAge

        override fun write(draft: PolicyDraft, value: Duration) {
            draft.policy.degradedLocalMaxAge = value
        }
    }

    object TOMBSTONE_TTL : PolicyField<Ttl>("tombstone-ttl") {
        override fun categoryFor(apiType: Class<*>): Category = Category.VERSION_FENCE

        /**
         * 未设置时是值的寿命（[CachePolicy.tombstoneTtl]）。
         */
        override fun read(draft: PolicyDraft): Ttl =
            Ttl.of((draft.policy.tombstoneTtl ?: draft.policy.ttl)?.takeUnless { it >= MAX_DURATION })

        override fun write(draft: PolicyDraft, value: Ttl) {
            draft.policy.tombstoneTtl = value.toDuration() ?: MAX_DURATION
        }
    }

    object REFRESH_WINDOW : PolicyField<RefreshWindow>("refresh-window") {
        override fun read(draft: PolicyDraft): RefreshWindow = RefreshWindow.of(draft.policy.refreshWindow)

        override fun write(draft: PolicyDraft, value: RefreshWindow) {
            draft.policy.refreshWindow = value.toDuration()
        }
    }

    object LEASE_TTL : PolicyField<Duration>("lease-ttl") {
        override fun read(draft: PolicyDraft): Duration = draft.policy.lease.ttl

        override fun write(draft: PolicyDraft, value: Duration) {
            draft.policy.lease = draft.policy.lease.copy(ttl = value)
        }
    }

    object LEASE_WAIT : PolicyField<Duration>("lease-wait") {
        override fun categoryFor(apiType: Class<*>): Category = Category.WAIT_BOUND

        override fun read(draft: PolicyDraft): Duration = draft.policy.lease.wait

        override fun write(draft: PolicyDraft, value: Duration) {
            draft.policy.lease = draft.policy.lease.copy(wait = value)
        }
    }

    object LOAD_MAX_CONCURRENT : PolicyField<Concurrency>("load-max-concurrent") {
        override fun read(draft: PolicyDraft): Concurrency = Concurrency.of(draft.policy.load.maxConcurrent)

        override fun write(draft: PolicyDraft, value: Concurrency) {
            draft.policy.load = draft.policy.load.copy(maxConcurrent = value.toPermits())
        }
    }

    object LOAD_ACQUIRE_TIMEOUT : PolicyField<Duration>("load-acquire-timeout") {
        override fun categoryFor(apiType: Class<*>): Category = Category.WAIT_BOUND

        override fun read(draft: PolicyDraft): Duration = draft.policy.load.acquireTimeout

        override fun write(draft: PolicyDraft, value: Duration) {
            draft.policy.load = draft.policy.load.copy(acquireTimeout = value)
        }
    }

    object LOAD_TIMEOUT : PolicyField<Duration>("load-timeout") {
        override fun categoryFor(apiType: Class<*>): Category = Category.WAIT_BOUND

        override fun read(draft: PolicyDraft): Duration = draft.policy.load.timeout

        override fun write(draft: PolicyDraft, value: Duration) {
            draft.policy.load = draft.policy.load.copy(timeout = value)
        }
    }

    /**
     * 按缓存的 L1 读取时限（D6）。`null` 即存储的命令超时；分层中显式写出的值总是一个时长。
     */
    object READ_TIMEOUT : PolicyField<Duration>("read-timeout") {
        override fun categoryFor(apiType: Class<*>): Category = Category.WAIT_BOUND

        override fun read(draft: PolicyDraft): Duration = draft.policy.readTimeout ?: MAX_DURATION

        override fun write(draft: PolicyDraft, value: Duration) {
            draft.policy.readTimeout = value.takeUnless { it >= MAX_DURATION }
        }

        override fun describe(draft: PolicyDraft): String = draft.policy.readTimeout?.toString() ?: "command-timeout"
    }

    object LOCAL_MAXIMUM_SIZE : PolicyField<Long>("local-maximum-size") {
        override fun read(draft: PolicyDraft): Long = draft.policy.local.maximumSize

        override fun write(draft: PolicyDraft, value: Long) {
            draft.policy.local = draft.policy.local.copy(maximumSize = value)
        }
    }

    object INVALIDATE_FAILURE : PolicyField<InvalidateFailureMode>("invalidate-failure") {
        override fun categoryFor(apiType: Class<*>): Category = Category.MODE

        override fun read(draft: PolicyDraft): InvalidateFailureMode =
            draft.policy.invalidateFailure ?: InvalidateFailureMode.RETRY

        override fun write(draft: PolicyDraft, value: InvalidateFailureMode) {
            draft.policy.invalidateFailure = value
        }
    }

    /**
     * `throw` 严于 `degrade`。
     */
    object READ_FAILURE : PolicyField<ReadFailureMode>("read-failure") {
        override fun categoryFor(apiType: Class<*>): Category = Category.ORDERED_MODE

        override fun read(draft: PolicyDraft): ReadFailureMode = draft.policy.readFailure ?: ReadFailureMode.DEGRADE

        override fun write(draft: PolicyDraft, value: ReadFailureMode) {
            draft.policy.readFailure = value
        }
    }

    /**
     * spec 的字段（不在 [CachePolicy] 中）：只能出现在声明与 `caches.[name]` 中（S16）。
     */
    object UNKNOWN_READ : PolicyField<UnknownReadMode>("unknown-read") {
        override fun categoryFor(apiType: Class<*>): Category = Category.MODE

        override fun read(draft: PolicyDraft): UnknownReadMode = draft.unknownRead ?: UnknownReadMode.THROW

        override fun write(draft: PolicyDraft, value: UnknownReadMode) {
            draft.unknownRead = value
        }
    }

    companion object {
        /**
         * [CachePolicy] 中的字段，按配置文档的顺序。延迟初始化：伴生对象先于各字段对象初始化时，直接引用它们会得到 `null`。
         */
        @JvmStatic
        val POLICY_FIELDS: List<PolicyField<*>> by lazy {
            listOf(
                TTL,
                TTL_JITTER,
                ABSENT_TTL,
                LOCAL_MAX_AGE,
                DEGRADED_LOCAL_MAX_AGE,
                TOMBSTONE_TTL,
                REFRESH_WINDOW,
                LEASE_TTL,
                LEASE_WAIT,
                LOAD_MAX_CONCURRENT,
                LOAD_ACQUIRE_TIMEOUT,
                LOAD_TIMEOUT,
                READ_TIMEOUT,
                LOCAL_MAXIMUM_SIZE,
                INVALIDATE_FAILURE,
                READ_FAILURE,
            )
        }

        /**
         * 全部字段：[POLICY_FIELDS] 加 [UNKNOWN_READ]。
         */
        @JvmStatic
        val ALL: List<PolicyField<*>> by lazy { POLICY_FIELDS + UNKNOWN_READ }

        /**
         * 按配置键查找；不认识时为 `null`。
         */
        @JvmStatic
        fun of(key: String): PolicyField<*>? = ALL.firstOrNull { it.key == key }

        /**
         * 适用于 [spec] 的字段（适用性表，RFC 0005 §4.8，U11）。不适用的字段出现在全局层时跳过，出现在 `caches.[name]` 时启动失败。
         * - `Cache`：除 [TOMBSTONE_TTL] 之外的策略字段；外部来源的另有 [UNKNOWN_READ]，没有 [REFRESH_WINDOW]（没有数据源可提前刷新）。
         *   [READ_FAILURE] 作用于带 loader 的读取（S11）。
         * - `VersionedCache`：全部策略字段。
         * - `Store`：寿命、L2 限龄与容量、[READ_TIMEOUT]、[UNKNOWN_READ]；没有回源，lease、回源、降级限龄、提前刷新、读失败与失效失败的模式
         *   都不适用。`VersionedStore` 另有 [TOMBSTONE_TTL]；`SetStore` 没有 [UNKNOWN_READ]。
         */
        @JvmStatic
        fun applicableTo(spec: me.ahoo.cocache.core.Spec<*, *>): List<PolicyField<*>> = when (spec) {
            is me.ahoo.cocache.core.CacheSpec<*, *> ->
                if (spec.externallySourced) {
                    POLICY_FIELDS - TOMBSTONE_TTL - REFRESH_WINDOW + UNKNOWN_READ
                } else {
                    POLICY_FIELDS - TOMBSTONE_TTL
                }
            is me.ahoo.cocache.core.VersionedCacheSpec<*, *> -> POLICY_FIELDS
            is me.ahoo.cocache.core.StoreSpec<*, *> -> STORE_FIELDS + UNKNOWN_READ
            is me.ahoo.cocache.core.VersionedStoreSpec<*, *> -> STORE_FIELDS + TOMBSTONE_TTL + UNKNOWN_READ
            is me.ahoo.cocache.core.SetStoreSpec<*, *> -> STORE_FIELDS
        }

        private val STORE_FIELDS: List<PolicyField<*>> by lazy {
            listOf(TTL, TTL_JITTER, ABSENT_TTL, LOCAL_MAX_AGE, READ_TIMEOUT, LOCAL_MAXIMUM_SIZE)
        }
    }
}

/**
 * 一次分层所用的可变策略：[CachePolicy.Builder] 加 spec 的 `unknownRead`（只有 `Cache`、`Store`、`VersionedStore` 有）。
 * 按字段读写取值（[get]、[set]），`constrain` 的约束也作用于它。
 */
class PolicyDraft(val policy: CachePolicy.Builder, var unknownRead: UnknownReadMode? = null) {
    operator fun <T : Any> get(field: PolicyField<T>): T = field.read(this)

    operator fun <T : Any> set(field: PolicyField<T>, value: T) {
        field.write(this, value)
    }
}
