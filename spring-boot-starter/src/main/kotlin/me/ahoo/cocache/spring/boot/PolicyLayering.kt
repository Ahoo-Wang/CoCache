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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.Layered
import me.ahoo.cocache.core.LayoutField
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.PolicyReport
import me.ahoo.cocache.core.PolicyReport.Source
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.Spec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.policy.PolicyDraft
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.PolicyField.Category
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.RefreshWindow
import me.ahoo.cocache.core.policy.Ttl
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cocache.spring.boot.CoCacheProperties.PolicyProperties
import me.ahoo.cocache.spring.tx.FillInTransaction
import org.apache.commons.logging.LogFactory
import java.time.Duration

/**
 * 三层配置的逐字段合并（RFC 0005 §4.8）：类型默认值 → `defaults`（低于声明）→ 声明 → 定制器 → `overrides`（只收紧）→
 * `caches.[name]`，之后执行库的约束（[Locks.constrain]）与校验连锁，并为每个字段记下生效值的来源（[PolicyReport]）。
 *
 * - 全局层（`defaults`、`overrides`）跳过不适用、该层不允许与被锁定的字段，记入报告的 `skipped`。
 * - `caches.[name]` 设定不适用的字段时按 `cocache.caches-validation` 失败或告警；写出被锁定的字段时启动失败，除非它在 `unlock` 中。
 * - 定制器改变被锁定的字段、被锁定的布局或数据来源（U16）时抛出。
 * - 校验连锁（U10）：全局层让提前刷新窗口不再小于 `local-max-age` 或 `ttl` 时关闭窗口并告警；只由声明或 `caches.[name]` 引起的冲突照常失败。
 * - 写事务内回源的两个机制（`fill-in-transaction`、`invalidate-fills-on-rollback`，RFC 0005 §4.9）不是策略字段，没有声明层：
 *   按 `defaults` → `overrides`（取更严的值）→ `caches.[name]` 分层，登记到 [fillModes]（有 spring-tx 时）并列入报告。
 */
internal class PolicyLayering(
    private val properties: CoCacheProperties,
    private val customizers: List<CacheSpecCustomizer>,
    private val fillModes: TransactionalFillModes? = null,
) {
    fun <S : Spec<*, *>, B : Any> layer(spec: S, locks: Locks, adapter: SpecAdapter<S, B>): Layered<S> =
        Merge(spec, locks, adapter).run()

    private inner class Merge<S : Spec<*, *>, B : Any>(
        private val spec: S,
        private val locks: Locks,
        private val adapter: SpecAdapter<S, B>,
    ) {
        private val name = spec.name
        private val apiType = adapter.apiType
        private val applicable = PolicyField.applicableTo(spec)
        private val store = apiType != Cache::class.java && apiType != VersionedCache::class.java
        private val sources = linkedMapOf<PolicyField<*>, Pair<Source, String?>>()
        private val skipped = mutableListOf<PolicyReport.Skipped>()
        private val builder = adapter.toBuilder(spec)
        private var draft = adapter.draftOf(builder)

        // 这个缓存用到的各节只选一次（条目在绑定时已解析，见 CoCacheProperties.Section）
        private val defaults = properties.defaultsSection(store)
        private val overrides = properties.overridesSection(store)
        private val perCache = properties.cacheSection(name)

        fun run(): Layered<S> {
            applicable.forEach { field ->
                sources[field] = when {
                    // 被锁定的字段只能由声明决定（全局层跳过、定制器不能改、caches.[name] 须 unlock），报告标注锁定者（RFC 0005 §4.8）
                    locks.isPinned(field) -> Source.LOCKED to "pinned by ${locks.by}"
                    locks.isLocked(field) -> Source.LOCKED to "locked by ${locks.by}"
                    field in spec.declaredFields -> Source.DECLARATION to null
                    else -> Source.TYPE_DEFAULT to null
                }
            }
            applyDefaults(defaults)
            // 定制器之前要先构建一次策略：defaults 引起的冲突在此之前夹紧，否则构建失败、夹紧不会发生
            clampRefreshWindow()
            adapter.applyDraft(builder, draft)
            applyCustomizers()
            applyOverrides(overrides)
            val unlocked = applyCacheLayer()
            applyKeyPrefix(unlocked)
            val (fills, mode) = applyFillModes()
            locks.constrain(draft).forEach {
                sources[it] = Source.LOCKED to (if (locks.isPinned(it)) "pinned by ${locks.by}" else "constrained by ${locks.by}")
            }
            clampRefreshWindow()
            adapter.applyDraft(builder, draft)
            val effective = adapter.build(builder)
            val report = PolicyReport(
                applicable.map { field ->
                    val (source, detail) = sources.getValue(field)
                    PolicyReport.Entry(field.key, field.describe(draft), source, detail)
                } + fills,
                skipped
            )
            // 只暂存：这份报告所属的缓存创建成功之后才生效（TransactionalFillModes.commit，#842）
            mode?.let { fillModes?.stage(report, it) }
            return Layered(effective, report)
        }

        private fun skip(key: String, layer: String, reason: String) {
            skipped += PolicyReport.Skipped(key, layer, reason)
        }

        private fun notApplicable(): String = "not applicable to ${describeType()}"

        private fun describeType(): String =
            if (spec is CacheSpec<*, *> && spec.externallySourced) "an externally sourced Cache" else "a ${apiType.simpleName}"

        private fun skipGlobalOnly(section: CoCacheProperties.Section) {
            val (path, layer) = section.path to section.layer
            if (layer.keyPrefix != null) {
                skip("$path.key-prefix", path, "not allowed here; use ${CoCacheProperties.PREFIX}.key-prefix")
            }
            if (layer.unlock.isNotEmpty()) {
                skip("$path.unlock", path, "only allowed under ${CoCacheProperties.PREFIX}.caches.[name]")
            }
        }

        private fun applyDefaults(section: CoCacheProperties.Section) {
            skipGlobalOnly(section)
            val path = section.path
            val wholePolicy = spec.declaredFields.containsAll(PolicyField.POLICY_FIELDS)
            section.entries.forEach { entry ->
                when {
                    entry.field !in applicable -> skip(entry.key, path, notApplicable())
                    !allowedInDefaults(entry.field) ->
                        skip(entry.key, path, "not allowed in ${CoCacheProperties.PREFIX}.defaults")
                    locks.isLocked(entry.field) -> skip(entry.key, path, "locked by ${locks.by}")
                    entry.field in spec.declaredFields -> if (wholePolicy) {
                        skip(entry.key, path, "the declaration sets the whole policy, so defaults don't apply")
                    }
                    // VersionedStore 的墓碑必须有限（VersionedStoreSpec 校验）：全局层的 forever 跳过并报告，而不是让每个这样的缓存创建失败
                    entry.field == PolicyField.TOMBSTONE_TTL && entry.value == Ttl.Forever && apiType == VersionedStore::class.java ->
                        skip(entry.key, path, "a VersionedStore needs a finite tombstone-ttl")
                    else -> set(entry, Source.DEFAULTS, null)
                }
            }
        }

        private fun allowedInDefaults(field: PolicyField<*>): Boolean = when {
            field == PolicyField.UNKNOWN_READ -> false
            // 权威副本的寿命即语义：defaults.store.ttl 不允许（含 VersionedStore 的值寿命）
            field == PolicyField.TTL && store -> false
            else -> field.categoryFor(apiType) != Category.SEMANTIC_LIFETIME
        }

        private fun applyCustomizers() {
            customizers.filter { it.supports(name) }.forEach { customizer ->
                val before = adapter.draftOf(builder)
                val layoutBefore = adapter.layout(builder)
                adapter.customize(builder, customizer)
                val after = adapter.draftOf(builder)
                val who = customizer.javaClass.name
                applicable.filter { before[it] != after[it] }.forEach { field ->
                    check(!locks.isLocked(field)) {
                        "Cache[$name] - customizer $who changed ${field.key}, which is locked by ${locks.by}."
                    }
                    sources[field] = Source.CUSTOMIZER to who
                }
                val layoutAfter = adapter.layout(builder)
                LayoutField.entries.filter { !same(layoutBefore[it], layoutAfter[it]) }.forEach { field ->
                    check(field != LayoutField.SOURCE) {
                        "Cache[$name] - customizer $who changed the data source; customizers may change policy fields and " +
                            "fields that don't decide the type only."
                    }
                    check(!locks.isLocked(field)) {
                        "Cache[$name] - customizer $who changed ${field.name}, which is locked by ${locks.by}."
                    }
                }
            }
            draft = adapter.draftOf(builder)
        }

        private fun applyOverrides(section: CoCacheProperties.Section) {
            skipGlobalOnly(section)
            val path = section.path
            section.entries.forEach { entry ->
                val field = entry.field
                when {
                    field !in applicable -> skip(entry.key, path, notApplicable())
                    locks.isLocked(field) -> skip(entry.key, path, "locked by ${locks.by}")
                    else -> when (field.categoryFor(apiType)) {
                        Category.STALENESS_BOUND -> tighten(entry)
                        Category.ORDERED_MODE -> stricter(entry)
                        else -> skip(entry.key, path, NOT_ALLOWED_IN_OVERRIDES)
                    }
                }
            }
        }

        /**
         * 陈旧界限取 min；`Cache` 的 `ttl` 另钳制按调用给出的寿命（[me.ahoo.cocache.core.policy.CachePolicy.ttlCeiling]）。
         */
        private fun tighten(entry: CoCacheProperties.Entry) {
            val field = entry.field
            val current = draft[field]
            val tightened: Any = when (val value = entry.value) {
                is Ttl -> (current as Ttl).min(value)
                is Duration -> minOf(current as Duration, value)
                else -> error("Unexpected staleness bound ${field.key}.")
            }
            if (field == PolicyField.TTL) {
                draft.policy.ttlCeiling = (entry.value as Ttl).toDuration()
            }
            if (tightened != current) {
                set(field, tightened, Source.OVERRIDES, "tightened from $current")
            }
        }

        private fun stricter(entry: CoCacheProperties.Entry) {
            check(entry.field == PolicyField.READ_FAILURE) { "Unexpected ordered mode ${entry.field.key}." }
            val current = draft[PolicyField.READ_FAILURE]
            if (entry.value == ReadFailureMode.THROW && current != ReadFailureMode.THROW) {
                set(
                    PolicyField.READ_FAILURE,
                    ReadFailureMode.THROW,
                    Source.OVERRIDES,
                    "stricter than ${current.name.lowercase()}"
                )
            }
        }

        /**
         * `cocache.caches.[name]`：返回 `unlock` 列出的字段与布局。
         */
        private fun applyCacheLayer(): Set<Any> {
            val section = perCache ?: return emptySet()
            val path = section.path
            val unlocked = section.layer.unlock.mapNotNull { key ->
                PolicyField.of(key) ?: LayoutField.entries.firstOrNull { it.name.lowercase().replace('_', '-') == key }
                    ?: invalid("$path.unlock: $key is not a policy field or layout field").let { null }
            }.toSet()
            rejectPinned(path, unlocked, section.entries)
            section.entries.forEach { entry ->
                val field = entry.field
                when {
                    field !in applicable -> invalid("${entry.key} doesn't apply to ${describeType()}")
                    locks.isLocked(field) && field !in unlocked -> throw IllegalArgumentException(
                        "Cache[$name] - ${entry.key} is locked by ${locks.by}; list ${field.key} in $path.unlock to override it."
                    )
                    locks.isLocked(field) -> set(entry, Source.UNLOCKED, "was locked by ${locks.by}")
                    else -> set(entry, Source.CACHES, null)
                }
                if (field == PolicyField.TTL) {
                    raiseTtlCeiling(entry.value as Ttl)
                }
            }
            return unlocked
        }

        /**
         * `caches.[name].ttl` 高于 `overrides` 的上限时把上限抬到它（`forever` 时取消上限）：否则策略寿命比按调用给出的寿命还长。
         */
        private fun raiseTtlCeiling(ttl: Ttl) {
            val ceiling = draft.policy.ttlCeiling ?: return
            val duration = ttl.toDuration()
            draft.policy.ttlCeiling = if (duration == null || duration > ceiling) duration else ceiling
        }

        private fun applyKeyPrefix(unlocked: Set<Any>) {
            val section = perCache
            val configured = section?.layer?.keyPrefix
            if (configured != null) {
                val key = "${section.path}.key-prefix"
                require(!locks.isLocked(LayoutField.KEY_PREFIX) || LayoutField.KEY_PREFIX in unlocked) {
                    "Cache[$name] - $key: the key prefix is locked by ${locks.by}; list key-prefix in " +
                        "${section.path}.unlock to override it."
                }
                adapter.setKeyPrefix(builder, configured)
                return
            }
            val global = properties.keyPrefix ?: return
            if (locks.isLocked(LayoutField.KEY_PREFIX)) {
                skip(GLOBAL_KEY_PREFIX, CoCacheProperties.PREFIX, "the key prefix is locked by ${locks.by}")
                return
            }
            adapter.setKeyPrefix(builder, global)
        }

        /**
         * 校验连锁（U10）：全局层让提前刷新窗口不再小于 `local-max-age` 或 `ttl` 减抖动时，关闭窗口并告警。
         *
         * 窗口本身被库锁定、固定或约束（来源为 LOCKED）时不夹紧，启动失败并指出冲突：静默关掉它就违背了库的锁定（大声失败）。
         * 其它牵涉的字段被锁定、窗口来自全局层时照常夹紧：被关掉的是运维写下的全局值，库锁定的字段不变。
         */
        private fun clampRefreshWindow() {
            if (PolicyField.REFRESH_WINDOW !in applicable) {
                return
            }
            val window = (draft[PolicyField.REFRESH_WINDOW] as? RefreshWindow.Of)?.duration ?: return
            val localMaxAge = draft[PolicyField.LOCAL_MAX_AGE]
            val ttl = draft[PolicyField.TTL].toDuration()
            val jitter = ttl?.let { minOf(draft[PolicyField.TTL_JITTER], it.dividedBy(2)) }
            val conflicting = window >= localMaxAge || (ttl != null && window >= ttl - requireNotNull(jitter))
            val involved =
                listOf(PolicyField.REFRESH_WINDOW, PolicyField.LOCAL_MAX_AGE, PolicyField.TTL, PolicyField.TTL_JITTER)
            val layers = involved.mapNotNull { sources[it]?.first }
            val global = layers.any { it == Source.DEFAULTS || it == Source.OVERRIDES }
            // 冲突牵涉 caches.[name] 写出的字段时不夹紧：按缓存的层引起的冲突照常启动失败（U10）
            val perCache = layers.any { it == Source.CACHES || it == Source.UNLOCKED }
            if (conflicting && global && !perCache) {
                val (windowSource, windowDetail) = sources.getValue(PolicyField.REFRESH_WINDOW)
                require(windowSource != Source.LOCKED) {
                    "Cache[$name] - refresh-window[$window] is $windowDetail, so it can't be turned off, but a global layer " +
                        "made it not below local-max-age[$localMaxAge] or ttl[$ttl] minus ttl-jitter. Change the global layer " +
                        "for this cache (cocache.caches.[$name]) or the library's lock."
                }
                set(
                    PolicyField.REFRESH_WINDOW,
                    RefreshWindow.Off,
                    Source.CLAMPED,
                    "turned off: refresh-window[$window] is not below local-max-age[$localMaxAge] or ttl[$ttl] after a global layer"
                )
            }
        }

        /**
         * 写事务内回源的两个机制：返回报告的条目与生效值（没有 spring-tx 时都没有），生效值由调用方按报告暂存到 [fillModes]。它们作用于派生副本的回源，
         * 包括外部来源的 `Cache` 上按调用给出 loader 的 `get(key, loader)`（Spring Cache 的 `sync = true`）；权威副本没有回源，
         * 全局层跳过这两个键，`caches.[name]` 中的按 `caches-validation` 处理。
         */
        private fun applyFillModes(): Pair<List<PolicyReport.Entry>, TransactionalFillModes.Mode?> {
            if (store) {
                FILL_FIELDS.forEach { field ->
                    field.read(
                        defaults.layer
                    )?.let { skip("${defaults.path}.${field.key}", defaults.path, notApplicable()) }
                    field.read(
                        overrides.layer
                    )?.let { skip("${overrides.path}.${field.key}", overrides.path, notApplicable()) }
                    perCache?.let { section ->
                        field.read(
                            section.layer
                        )?.let { invalid("${section.path}.${field.key} doesn't apply to ${describeType()}") }
                    }
                }
                return emptyList<PolicyReport.Entry>() to null
            }
            val rollback = layerFill(INVALIDATE_FILLS_ON_ROLLBACK, defaults.layer, overrides.layer, perCache?.layer)
            val mode = layerFill(FILL_IN_TRANSACTION, defaults.layer, overrides.layer, perCache?.layer)
            if (fillModes == null) {
                return emptyList<PolicyReport.Entry>() to null
            }
            return listOf(rollback.second, mode.second) to TransactionalFillModes.Mode(rollback.first, mode.first)
        }

        private fun <T : Any> layerFill(
            field: FillField<T>,
            defaults: PolicyProperties,
            overrides: PolicyProperties,
            perCache: PolicyProperties?,
        ): Pair<T, PolicyReport.Entry> {
            var value = field.typeDefault
            var source: Pair<Source, String?> = Source.TYPE_DEFAULT to null
            field.read(defaults)?.let {
                value = it
                source = Source.DEFAULTS to null
            }
            field.read(overrides)?.let {
                val stricter = field.stricter(value, it)
                if (stricter != value) {
                    source = Source.OVERRIDES to "stricter than ${field.describe(value)}"
                    value = stricter
                }
            }
            perCache?.let(field.read)?.let {
                value = it
                source = Source.CACHES to null
            }
            return value to PolicyReport.Entry(field.key, field.describe(value), source.first, source.second)
        }

        /**
         * 固定的字段（`Locks.pin`）没有破窗开关：`caches.[name]` 写出它或在 `unlock` 中列出它都启动失败。否则约束会把解锁写入的值改回固定值，
         * 报告先显示被解锁、再被改回。
         */
        private fun rejectPinned(path: String, unlocked: Set<Any>, entries: List<CoCacheProperties.Entry>) {
            unlocked.filterIsInstance<PolicyField<*>>().firstOrNull { locks.isPinned(it) }?.let { field ->
                throw IllegalArgumentException(
                    "Cache[$name] - ${field.key} is pinned by ${locks.by} to ${pinnedValue(field)}; " +
                        "$path.unlock can't override a pinned field."
                )
            }
            entries.firstOrNull { it.field in applicable && locks.isPinned(it.field) }?.let { entry ->
                val value = pinnedValue(entry.field)
                throw IllegalArgumentException(
                    "Cache[$name] - ${entry.key} is pinned by ${locks.by} to $value and can't be overridden."
                )
            }
        }

        /**
         * 固定值按配置的写法给出，同策略报告（`PolicyField.describe`）：枚举写成小写的配置值（`throw`，不是 `THROW`）。
         */
        private fun pinnedValue(field: PolicyField<*>): String = when (val value = locks.pinned[field]) {
            is Enum<*> -> value.name.lowercase()
            else -> value.toString()
        }

        private fun invalid(message: String) {
            val full = "Cache[$name] - $message."
            if (properties.cachesValidation == CoCacheProperties.Validation.FAIL) {
                throw IllegalArgumentException(full)
            }
            log.warn(full)
        }

        private fun set(entry: CoCacheProperties.Entry, source: Source, detail: String?) {
            set(entry.field, entry.value, source, detail)
        }

        @Suppress("UNCHECKED_CAST")
        private fun set(field: PolicyField<*>, value: Any, source: Source, detail: String?) {
            draft[field as PolicyField<Any>] = value
            sources[field] = source to detail
        }

        private fun same(left: Any?, right: Any?): Boolean = left === right || left == right
    }

    /**
     * 写事务内回源的一个字段：在 spring-tx 的回源钩子中按缓存名生效（[TransactionalFillModes]），有严格序，`overrides` 取更严的值。
     */
    private class FillField<T : Any>(
        val key: String,
        val typeDefault: T,
        val read: (PolicyProperties) -> T?,
        val stricter: (T, T) -> T,
        val describe: (T) -> String,
    )

    private companion object {
        private val log = LogFactory.getLog(PolicyLayering::class.java)
        private const val GLOBAL_KEY_PREFIX = "${CoCacheProperties.PREFIX}.key-prefix"
        private const val NOT_ALLOWED_IN_OVERRIDES =
            "not allowed in ${CoCacheProperties.PREFIX}.overrides: only staleness bounds, read-failure, fill-in-transaction and invalidate-fills-on-rollback tighten"

        /**
         * `true` 严于 `false`。
         */
        private val INVALIDATE_FILLS_ON_ROLLBACK = FillField(
            "invalidate-fills-on-rollback",
            TransactionalFillModes.Mode.DEFAULT.invalidateFillsOnRollback,
            { it.invalidateFillsOnRollback },
            { current, override -> current || override },
            Boolean::toString,
        )

        /**
         * `private` 严于 `registered-keys` 严于 `allow`。
         */
        private val FILL_IN_TRANSACTION = FillField(
            "fill-in-transaction",
            TransactionalFillModes.Mode.DEFAULT.fillInTransaction,
            { it.fillInTransaction },
            { current, override -> maxOf(current, override, compareBy(::strictness)) },
            { it.name.lowercase().replace('_', '-') },
        )

        private val FILL_FIELDS = listOf(INVALIDATE_FILLS_ON_ROLLBACK, FILL_IN_TRANSACTION)

        private fun strictness(mode: FillInTransaction): Int = when (mode) {
            FillInTransaction.ALLOW -> 0
            FillInTransaction.REGISTERED_KEYS -> 1
            FillInTransaction.PRIVATE -> 2
        }
    }
}

/**
 * 五种 spec 在合并中的差异：构建器、策略草稿、布局与定制器的入口。
 */
internal interface SpecAdapter<S : Spec<*, *>, B : Any> {
    val apiType: Class<*>

    fun toBuilder(spec: S): B

    fun draftOf(builder: B): PolicyDraft

    fun applyDraft(builder: B, draft: PolicyDraft)

    fun layout(builder: B): Map<LayoutField, Any?>

    fun setKeyPrefix(builder: B, keyPrefix: String)

    fun customize(builder: B, customizer: CacheSpecCustomizer)

    fun build(builder: B): S
}

internal class CacheSpecAdapter<K : Any, V : Any> : SpecAdapter<CacheSpec<K, V>, CacheSpec.Builder<K, V>> {
    override val apiType: Class<*> = Cache::class.java
    override fun toBuilder(spec: CacheSpec<K, V>): CacheSpec.Builder<K, V> = spec.toBuilder()
    override fun draftOf(builder: CacheSpec.Builder<K, V>) =
        PolicyDraft(builder.policy.toBuilder(), builder.unknownRead)

    override fun applyDraft(builder: CacheSpec.Builder<K, V>, draft: PolicyDraft) {
        builder.policy = draft.policy.build()
        builder.unknownRead = draft.unknownRead ?: UnknownReadMode.THROW
    }

    override fun layout(builder: CacheSpec.Builder<K, V>): Map<LayoutField, Any?> = mapOf(
        LayoutField.NAMESPACE to builder.namespace,
        LayoutField.KEY_PREFIX to builder.keyPrefix,
        LayoutField.KEY_ENCODER to builder.keyEncoder,
        LayoutField.CODEC to builder.codec,
        LayoutField.KEY_FILTER to builder.keyFilter,
        LayoutField.TTL_OF to builder.ttlOf,
        LayoutField.SOURCE to listOf(builder.loader, builder.asyncLoader, builder.externallySourced),
    )

    override fun setKeyPrefix(builder: CacheSpec.Builder<K, V>, keyPrefix: String) {
        builder.keyPrefix = keyPrefix
    }

    override fun customize(builder: CacheSpec.Builder<K, V>, customizer: CacheSpecCustomizer) =
        customizer.customize(builder)

    override fun build(builder: CacheSpec.Builder<K, V>): CacheSpec<K, V> = builder.build()
}

internal class VersionedCacheSpecAdapter<K : Any, V : Any> :
    SpecAdapter<VersionedCacheSpec<K, V>, VersionedCacheSpec.Builder<K, V>> {
    override val apiType: Class<*> = VersionedCache::class.java
    override fun toBuilder(spec: VersionedCacheSpec<K, V>): VersionedCacheSpec.Builder<K, V> = spec.toBuilder()
    override fun draftOf(builder: VersionedCacheSpec.Builder<K, V>) = PolicyDraft(builder.policy.toBuilder())
    override fun applyDraft(builder: VersionedCacheSpec.Builder<K, V>, draft: PolicyDraft) {
        builder.policy = draft.policy.build()
    }

    override fun layout(builder: VersionedCacheSpec.Builder<K, V>): Map<LayoutField, Any?> = mapOf(
        LayoutField.NAMESPACE to builder.namespace,
        LayoutField.KEY_PREFIX to builder.keyPrefix,
        LayoutField.KEY_ENCODER to builder.keyEncoder,
        LayoutField.CODEC to builder.codec,
        LayoutField.KEY_FILTER to builder.keyFilter,
        LayoutField.TTL_OF to builder.ttlOf,
        LayoutField.SOURCE to listOf(builder.versionedLoader, builder.versionedAsyncLoader),
    )

    override fun setKeyPrefix(builder: VersionedCacheSpec.Builder<K, V>, keyPrefix: String) {
        builder.keyPrefix = keyPrefix
    }

    override fun customize(builder: VersionedCacheSpec.Builder<K, V>, customizer: CacheSpecCustomizer) =
        customizer.customize(builder)

    override fun build(builder: VersionedCacheSpec.Builder<K, V>): VersionedCacheSpec<K, V> = builder.build()
}

internal class StoreSpecAdapter<K : Any, V : Any> : SpecAdapter<StoreSpec<K, V>, StoreSpec.Builder<K, V>> {
    override val apiType: Class<*> = Store::class.java
    override fun toBuilder(spec: StoreSpec<K, V>): StoreSpec.Builder<K, V> = spec.toBuilder()
    override fun draftOf(builder: StoreSpec.Builder<K, V>) =
        PolicyDraft(builder.policy.toBuilder(), builder.unknownRead)

    override fun applyDraft(builder: StoreSpec.Builder<K, V>, draft: PolicyDraft) {
        builder.policy = draft.policy.build()
        builder.unknownRead = draft.unknownRead ?: UnknownReadMode.THROW
    }

    override fun layout(builder: StoreSpec.Builder<K, V>): Map<LayoutField, Any?> = mapOf(
        LayoutField.NAMESPACE to builder.namespace,
        LayoutField.KEY_PREFIX to builder.keyPrefix,
        LayoutField.KEY_ENCODER to builder.keyEncoder,
        LayoutField.CODEC to builder.codec,
        LayoutField.KEY_FILTER to builder.keyFilter,
        LayoutField.TTL_OF to builder.ttlOf,
    )

    override fun setKeyPrefix(builder: StoreSpec.Builder<K, V>, keyPrefix: String) {
        builder.keyPrefix = keyPrefix
    }

    override fun customize(builder: StoreSpec.Builder<K, V>, customizer: CacheSpecCustomizer) =
        customizer.customize(builder)

    override fun build(builder: StoreSpec.Builder<K, V>): StoreSpec<K, V> = builder.build()
}

internal class VersionedStoreSpecAdapter<K : Any, V : Any> :
    SpecAdapter<VersionedStoreSpec<K, V>, VersionedStoreSpec.Builder<K, V>> {
    override val apiType: Class<*> = VersionedStore::class.java
    override fun toBuilder(spec: VersionedStoreSpec<K, V>): VersionedStoreSpec.Builder<K, V> = spec.toBuilder()
    override fun draftOf(builder: VersionedStoreSpec.Builder<K, V>) =
        PolicyDraft(builder.policy.toBuilder(), builder.unknownRead)

    override fun applyDraft(builder: VersionedStoreSpec.Builder<K, V>, draft: PolicyDraft) {
        builder.policy = draft.policy.build()
        builder.unknownRead = draft.unknownRead ?: UnknownReadMode.THROW
    }

    override fun layout(builder: VersionedStoreSpec.Builder<K, V>): Map<LayoutField, Any?> = mapOf(
        LayoutField.NAMESPACE to builder.namespace,
        LayoutField.KEY_PREFIX to builder.keyPrefix,
        LayoutField.KEY_ENCODER to builder.keyEncoder,
        LayoutField.CODEC to builder.codec,
        LayoutField.KEY_FILTER to builder.keyFilter,
        LayoutField.TTL_OF to builder.ttlOf,
    )

    override fun setKeyPrefix(builder: VersionedStoreSpec.Builder<K, V>, keyPrefix: String) {
        builder.keyPrefix = keyPrefix
    }

    override fun customize(builder: VersionedStoreSpec.Builder<K, V>, customizer: CacheSpecCustomizer) =
        customizer.customize(builder)

    override fun build(builder: VersionedStoreSpec.Builder<K, V>): VersionedStoreSpec<K, V> = builder.build()
}

internal class SetStoreSpecAdapter<K : Any, E : Any> : SpecAdapter<SetStoreSpec<K, E>, SetStoreSpec.Builder<K, E>> {
    override val apiType: Class<*> = SetStore::class.java
    override fun toBuilder(spec: SetStoreSpec<K, E>): SetStoreSpec.Builder<K, E> = spec.toBuilder()
    override fun draftOf(builder: SetStoreSpec.Builder<K, E>) = PolicyDraft(builder.policy.toBuilder())
    override fun applyDraft(builder: SetStoreSpec.Builder<K, E>, draft: PolicyDraft) {
        builder.policy = draft.policy.build()
    }

    override fun layout(builder: SetStoreSpec.Builder<K, E>): Map<LayoutField, Any?> = mapOf(
        LayoutField.NAMESPACE to builder.namespace,
        LayoutField.KEY_PREFIX to builder.keyPrefix,
        LayoutField.KEY_ENCODER to builder.keyEncoder,
        LayoutField.CODEC to builder.elementCodec,
    )

    override fun setKeyPrefix(builder: SetStoreSpec.Builder<K, E>, keyPrefix: String) {
        builder.keyPrefix = keyPrefix
    }

    override fun customize(builder: SetStoreSpec.Builder<K, E>, customizer: CacheSpecCustomizer) =
        customizer.customize(builder)

    override fun build(builder: SetStoreSpec.Builder<K, E>): SetStoreSpec<K, E> = builder.build()
}
