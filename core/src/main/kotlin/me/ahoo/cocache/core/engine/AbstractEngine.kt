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

package me.ahoo.cocache.core.engine

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1RejectedException
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.VersionedL1Store
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.FillScope
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.internal.InvalidationStamps
import me.ahoo.cocache.core.internal.SingleFlight
import me.ahoo.cocache.core.internal.fillGuarded
import me.ahoo.cocache.core.internal.runSync
import me.ahoo.cocache.core.internal.runsInline
import me.ahoo.cocache.core.internal.uncancellable
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.proxy.CacheProxies
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * L2 副本：[value] 为 `null` 表示负缓存。到期与刷新时间均为纪元毫秒，[FOREVER] 表示没有。
 *
 * 按身份比较：不得改为 data class，也不得重写 `equals`。L2 填充的写后复核按身份撤销自己写入的那一份（[fillGuarded]），
 * 按值比较会删掉其间别的填充写入的、内容相同的副本。
 *
 * @param unfilled 外部来源的缓存在 L1 中未命中（或绕过 L1）时的“未填充”（RFC 0005 §4.1）：对不带 loader 的读取是不存在，
 *   带 loader 的读取不接受它
 */
internal open class LocalEntry<V>(
    val value: V?,
    val expiresAt: Long,
    val refreshAt: Long = FOREVER,
    val unfilled: Boolean = false
)

/**
 * 这一次读取没有答案（`Lookup.Unknown`，RFC 0005 §4.1）：只来自 L1 故障。它从不写入 L2，只作为这次加载的结果经 SingleFlight 共享：
 * 它是“未填充”的，带数据源的调用方不接受它（合并规则）。调用方返回之前换算为 `Unknown`、`Absent`（`unknownRead = ABSENT`）或抛出 [error]。
 */
internal class Unanswered<V>(val error: CacheReadException) : LocalEntry<V>(null, 0, unfilled = true)

/**
 * 一次加载的结果及其开始时的失效戳。
 *
 * @param owner leader 所在的写事务（RFC 0005 §4.9 S15）：非 `null` 时结果不交给跟随者，见 `CoherentEngine.usable`
 */
internal class Loaded<V>(val entry: LocalEntry<V>, val stamp: Long, val owner: FillScope? = null) {
    /**
     * 这次加载的答案（可能是 [Unanswered]）。
     */
    fun answer(): LocalEntry<V> = entry

    /**
     * 由 [owner] 领头的这次加载；[owner] 为 `null` 时就是本身。
     */
    fun ownedBy(owner: FillScope?): Loaded<V> = if (owner == null) this else Loaded(entry, stamp, owner)
}

internal const val FOREVER = Long.MAX_VALUE

/**
 * 两种引擎共享的部分：L2、L2 栅栏、存储 key、写入顺序、失效订阅。
 */
internal abstract class AbstractEngine<K : Any, V : Any>(
    override val name: String,
    internal val keyspace: Keyspace,
    private val keyEncoder: KeyEncoder<K>,
    internal val policy: CachePolicy,
    protected val store: L1Store,
    protected val context: EngineContext,
) : InvalidationListener, CacheAdmin, AutoCloseable {
    override val metrics = CacheMetrics(context.recordLocalHits)

    final override val apiType: Class<*> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CacheProxies.API_TYPES.first { it.isInstance(this) }
    }

    /**
     * 运行时在注册之前设置（经 [me.ahoo.cocache.core.CoCacheFactory] 创建时为 `true`），之后不变。
     */
    final override var layered: Boolean = false
        internal set

    final override var policyReport: me.ahoo.cocache.core.PolicyReport? = null
        internal set

    /**
     * 与 [metrics] 分开保存：命中路径只读引擎自身的一个 final 字段，关闭时不触碰计数器。
     */
    private val recordLocalHits = context.recordLocalHits

    protected val clock = context.clock
    protected val stamps = InvalidationStamps()

    /**
     * 合并回源：跟随者的等待与加载的登记都以 `load.timeout` 为上限（RFC 0005 §4.4）。
     */
    protected val flights = SingleFlight<String, Loaded<V>>(policy.load.timeout.toNanos(), context.ticker) {
        metrics.loadTimeout()
        SourceLoadTimeoutException(name, policy.load.timeout)
    }

    /**
     * L2 命中计数（开启 `recordLocalHits` 时）。
     */
    protected fun countLocalHit() {
        if (recordLocalHits) {
            metrics.localHit()
        }
    }
    protected val local: Cache<String, LocalEntry<V>> = buildLocal(policy)
    protected val closed = AtomicBoolean(false)
    private val nodeId: NodeId = context.nodeId

    /**
     * 结果未知的写入涉及的 key（编码 key → 截止时刻，纪元毫秒；RFC 0005 §4.6，P12）：截止之前，本节点自己的帧对这些 key 不再被过滤。
     *
     * 结果未知的写入（超时、连接替换时被排空）可能在任何时刻才执行并广播，那一帧的来源是本节点，平常在本节点被过滤；
     * 而写入失败之后、迟到执行之前，本节点的读取可能已把旧值填进 L2。不过滤这一帧，本节点就照常递增戳、淘汰 L2（[onInvalidated]）。
     * 截止时刻是 `localMaxAge`（[unfilterOwnFrames]）：RFC 原文在“同一条连接上之后发出的一条命令完成，再加一个投递余量”时提前结束，
     * 但存储的 SPI 不暴露连接与重连，无法确认，所以直接取残余上界，与丢失的帧相同。多付的只是这些 key 上本节点帧的一次淘汰。
     */
    private val ownFrames = ConcurrentHashMap<String, Long>()
    private val atomicBroadcast = context.backend.atomicBroadcast

    /**
     * 本节点的格式版本（`CoherenceBackend.format`）。
     */
    protected val backendFormat: Int = context.backend.format

    /**
     * 存储 key 的前缀 `{keyPrefix}{namespace}:`（[Keyspace.storagePrefix]，创建时已算好）。
     */
    private val storagePrefix = keyspace.storagePrefix

    /**
     * 非原子广播的后端上，写入之后经它发布失效帧。
     */
    private val channel: InvalidationChannel = context.backend.channel

    /**
     * 存储的能力面（RFC 0005 §4.3）：访问器在存储的生命周期内返回同样的结果，创建时各取一次。
     * `runtime.create` 已按类型检查需要的能力（`Store` 要求 `conditional()`，带版本的类型要求 `versioned()`，`SetStore` 要求 `sets()`）；
     * [requireConditional]、[requireVersioned]、[requireSets] 只是防御性检查，缺少时在调用时以 [UnsupportedOperationException] 失败。
     */
    private val conditional: ConditionalL1Store? = store.conditional()
    private val versioned: VersionedL1Store? = store.versioned()
    private val sets: SetL1Store? = store.sets()

    /**
     * 协程核心的运行时作用域（RFC 0005 §4.4）：异步变体的共享加载在这里执行。
     */
    protected val scope: CoroutineScope = context.async.scope

    /**
     * 协程核心读取 L1（RFC 0005 §4.3）：不可取消（[uncancellable]），续接回到当前变体的调度器上
     * （同步变体即调用方线程，异步变体即运行时作用域的调度器），从不在 L1 客户端的 I/O 线程上执行。
     * 协程被取消时仍等到读取完成或超时：取得令牌的读取不丢结果，取消在之后的第一个挂起点生效。
     * 时限是策略的 `readTimeout`（D6），`null` 为存储的命令超时；超时与其它 L1 读失败相同，取得的令牌由存储释放。
     */
    protected suspend fun readRemote(storageKey: String, lease: LeaseRequest?, refresh: RefreshRequest?): L1Read =
        uncancellable { store.read(storageKey, lease, refresh, policy.readTimeout) }

    /**
     * [operation] 需要的条件读写能力面；存储没有它时抛出 [UnsupportedOperationException]（[unsupported]）。
     */
    protected fun requireConditional(
        operation: String
    ): ConditionalL1Store = conditional ?: throw unsupported(operation, "conditional")

    /**
     * [operation] 需要的按版本写入能力面；存储没有它时抛出 [UnsupportedOperationException]（[unsupported]）。
     */
    protected fun requireVersioned(
        operation: String
    ): VersionedL1Store = versioned ?: throw unsupported(operation, "versioned")

    /**
     * [operation] 需要的集合能力面；存储没有它时抛出 [UnsupportedOperationException]（[unsupported]）。
     */
    protected fun requireSets(operation: String): SetL1Store = sets ?: throw unsupported(operation, "sets")

    /**
     * 存储没有 [operation] 需要的能力面（RFC 0005 §4.3）：以 [UnsupportedOperationException] 失败，
     * 在调用时、修改任何一层之前抛出。
     */
    private fun unsupported(operation: String, capability: String): UnsupportedOperationException =
        UnsupportedOperationException(
            "Cache[$name] - $operation() needs the $capability capability, but ${store.javaClass.name} does not provide it."
        )

    protected fun storageKey(key: K): String = storagePrefix + keyEncoder.encode(key)

    /**
     * L2 的 key：编码后的 key，不含存储前缀（RFC 0005 §4.5）。每个缓存有自己的 L2，前缀只用来区分 L1 中的 key，
     * 命中路径因此不拼接字符串（`String` 的 key 经 `ToStringKeyEncoder` 原样返回，哈希已缓存）。失效戳与 SingleFlight 只在未命中与写入路径上使用，仍按存储 key。
     */
    protected fun localKey(key: K): String = keyEncoder.encode(key)

    /**
     * L2 的 key → 存储 key（未命中路径）。
     */
    protected fun storageKeyOfLocal(localKey: String): String = storagePrefix + localKey

    /**
     * 本缓存的存储 key → L2 的 key。
     */
    protected fun localKeyOf(storageKey: String): String = storageKey.substring(storagePrefix.length)

    /**
     * 编码后的 key → 存储 key（运维接口使用）。
     */
    protected fun storageKeyOf(encodedKey: String): String {
        require(encodedKey.isNotEmpty()) { "Cache[$name] - encodedKey must not be empty." }
        return storagePrefix + encodedKey
    }

    protected fun frameOf(keys: Collection<String>): ByteArray = InvalidationFrame.encode(nodeId, keys)

    /**
     * 戳保护的 L2 填充：在 key 的锁内校验并写入，写后按身份复核（[fillGuarded]，RFC 0005 §4.5）。没有写入（戳已失效，
     * 或保留了版本更新的副本，[keptCopy]）时计入 `staleFillsDiscarded`。
     */
    protected fun fillLocal(storageKey: String, entry: LocalEntry<V>, stamp: Long) {
        val filled = local.asMap().fillGuarded(
            localKeyOf(storageKey),
            entry,
            { current -> keptCopy(current, entry) }
        ) { stamps.isValid(storageKey, stamp) }
        if (!filled) {
            metrics.staleFillDiscarded()
        }
    }

    /**
     * L2 中已有的副本 [current] 比要填充的 [entry] 更新、应当保留时，返回要留下的副本；否则为 `null`，照常填充（在 L2 的锁内调用，
     * 不得有副作用）。只有带版本的缓存覆盖它：它的 L2 不按版本后退（RFC 0005 I-K 的 L2 条款）。
     */
    protected open fun keptCopy(current: LocalEntry<V>, entry: LocalEntry<V>): LocalEntry<V>? = null

    /**
     * L2 副本：到期时间不晚于 L1 剩余寿命，也不晚于 [maxAge]。
     *
     * 刷新点在 L1 到期前 `refreshWindow`（只对有限寿命的值）。L1 剩余寿命不长于窗口时，这样算出的刷新点不晚于现在，
     * 副本一出生就在窗口内，每次命中都会触发刷新，因此（§5.5）：
     * - [backoff] 为 0（默认）：不设刷新点。否则持有刷新令牌的节点每次写回之后（`fill` 清零令牌）都能再取得令牌，
     *   一次接一次地回源。寿命不长于窗口的值（`ttlOf`、`put(k, v, ttl)` 给出的短寿命）因此不参与提前刷新，
     *   在窗口内才读到 L1 的副本也不发起刷新。
     * - [backoff] 为正（没取得刷新令牌的重新填充：令牌在别的节点手里）：刷新点退避到 `now + min(backoff, 剩余寿命 / 2)`，
     *   到时再读一次 L1，取持有者写回的值（`fill` 不广播）。刷新点总是早于 L1 到期；不足 1 毫秒时不设刷新点。
     *
     * @param backoff 毫秒
     * @param refreshable 为 `false` 时不设刷新点：刷新取不到令牌、或只会再读到同样内容的副本（读到 `E`、未知标志位或摘要段，P14、D4）
     */
    protected fun localEntry(
        value: V?,
        ttl: Duration?,
        maxAge: Duration,
        backoff: Long = 0,
        refreshable: Boolean = true
    ): LocalEntry<V> {
        val now = clock.nowMillis()
        val remoteExpiresAt = ttl?.let { now + it.toMillis() } ?: FOREVER
        val expiresAt = minOf(remoteExpiresAt, now + maxAge.toMillis())
        val refreshAt = if (value != null && refreshable) refreshAtOf(now, remoteExpiresAt, backoff) else FOREVER
        return LocalEntry(value, expiresAt, refreshAt)
    }

    private fun refreshAtOf(now: Long, remoteExpiresAt: Long, backoff: Long): Long {
        val window = policy.refreshWindow
        if (window == null || remoteExpiresAt == FOREVER) {
            return FOREVER
        }
        val refreshAt = remoteExpiresAt - window.toMillis()
        if (refreshAt > now) {
            return refreshAt
        }
        val delay = minOf(backoff, (remoteExpiresAt - now) / 2)
        return if (delay > 0) now + delay else FOREVER
    }

    protected fun lookupOf(entry: LocalEntry<V>): Lookup<V> {
        // L2 副本总是受 localMaxAge 限龄，因此到期时间总是有限的
        val expiresAt = Instant.ofEpochMilli(entry.expiresAt)
        return entry.value?.let { Lookup.Present(it, expiresAt) } ?: Lookup.Absent(expiresAt)
    }

    /**
     * 写入方顺序（RFC 0003 §4.4；协程核心，同步与异步共用，RFC 0005 V5）：戳 → 淘汰 L2 → 修改 L1（并广播）→ 戳 → 淘汰 L2。
     * 写入方从不直接写 L2。
     *
     * 第二次递增使“在第一次递增之后取戳、在修改 L1 之前读到旧值”的并发填充失效；
     * 本节点自己的广播会被过滤，不能依赖它清除本地副本。
     *
     * 非原子广播时 L1 失败也尽力广播：失败的写入可能已部分生效（`putAll` 跨多个 slot 组时有的组已写入，
     * 或写入已在 L1 生效却报错），不广播的话其它节点会一直读旧的 L2 直到 `localMaxAge`（G2）。多余的广播只淘汰副本，无害。
     * 前提是 [mutate] 返回或抛出时它的全部 L1 命令都已结束（否则广播可能先于仍在执行的命令）。
     * 例外是 [ForeignFormatException] 与 [UnsupportedOperationException]：后端拒绝了写入（或不支持这个操作），L1 原样未动，不广播。
     *
     * 从修改 L1 到第二次递增（含广播与 [onFailure]）不可取消（RFC 0005 §4.3），[mutate] 因此直接调用存储：
     * 协程被取消（同步变体中线程被中断，异步变体中运行时关闭）也要等 L1 命令结束再第二次递增，否则在两次递增之间开始的填充
     * 会把旧值写进 L2（G2），非原子广播的后端上其它节点也收不到广播。
     * - 同步变体（[SYNC_WRITE]）：续接仍在调用方线程（`runBlocking` 的事件循环）上执行，不在 L1 客户端的 I/O 线程上执行。
     * - 异步变体（[ASYNC_WRITE]）：不经执行器，在 L1 命令的完成线程上就地继续（这些步骤都不阻塞、不调用用户代码）。
     *   执行器拒绝派发不会跳过这些步骤，也不会把写入的结果换成取消（成功的写入因此计为失败、`invalidate` 因此登记多余的重试）。
     *   只有回到调用方的那一次派发可能被拒绝，调用方看到 `RejectedExecutionException`。
     *
     * @param mutate 修改 L1（直接调用 [store] 或它的能力面：整段已在 `NonCancellable` 中）
     * @param changed [mutate] 正常返回后是否需要广播：L1 被这次写入修改，或可能已被之前同样的写入修改（条件写入的重放：
     * 上一次已在 L1 生效、单独的广播却失败了）。不需要时非原子广播的后端不广播（原子广播的后端由存储自己决定）。
     * 戳与 L2 淘汰照常执行，只多淘汰一次本地副本
     * @param onFailure L1 失败时调用（在第二次递增之前，因此登记的状态对随后的读取可见），可以抛出
     */
    protected suspend fun write(
        keys: Collection<String>,
        mutate: suspend (ByteArray) -> Unit,
        changed: () -> Boolean = ALWAYS_CHANGED,
        onFailure: (Exception) -> Unit
    ) {
        keys.forEach {
            stamps.invalidate(it)
            local.invalidate(localKeyOf(it))
        }
        withContext(if (runsInline()) SYNC_WRITE else ASYNC_WRITE) { mutateAndBump(keys, mutate, changed, onFailure) }
    }

    private suspend fun mutateAndBump(
        keys: Collection<String>,
        mutate: suspend (ByteArray) -> Unit,
        changed: () -> Boolean,
        onFailure: (Exception) -> Unit
    ) {
        try {
            mutateAndPublish(keys, mutate, changed, onFailure)
        } finally {
            keys.forEach {
                stamps.invalidate(it)
                local.invalidate(localKeyOf(it))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun mutateAndPublish(
        keys: Collection<String>,
        mutate: suspend (ByteArray) -> Unit,
        changed: () -> Boolean,
        onFailure: (Exception) -> Unit
    ) {
        try {
            val frame = frameOf(keys)
            try {
                mutate(frame)
            } catch (e: Exception) {
                if (!notExecuted(e)) {
                    // 结果未知：原子广播的写入可能迟到执行并广播，那一帧在本节点不再被过滤（P12）
                    unfilterOwnFrames(keys)
                    if (!atomicBroadcast) {
                        publishAfterFailure(frame, e)
                    }
                }
                throw e
            }
            if (!atomicBroadcast && changed()) {
                publish(frame)
            }
        } catch (e: Exception) {
            metrics.writeFailure()
            onFailure(e)
        }
    }

    /**
     * 非原子广播的后端上发布失效帧（在 [write] 的 `NonCancellable` 中，同 L1 命令不可取消）。
     */
    private suspend fun publish(frame: ByteArray) {
        channel.publish(keyspace, frame)
    }

    /**
     * 版本前进的填充是一次写入（[me.ahoo.cocache.api.spi.L1Fill.Filled.advanced]，RFC 0005 §4.7）：存储在填充的同一次原子操作中
     * 已广播（原子广播的后端），而这一帧在本节点被过滤，所以本节点自己递增戳并淘汰本地副本（同写入方修改 L1 之后的那一步），
     * 调用方不填 L2：否则绕过 SingleFlight 的同节点读取者可以把旧版本写回 L2。下一次读取多一次 L1 往返。
     * 非原子广播的后端上由这里发布（不可取消，同 [write]）；发布失败只计数告警，其它节点的旧副本由 `localMaxAge` 兜底。
     *
     * 结果未知的带版本填充同样走这里（TLA+ F2）：它可能稍后才执行。现在递增戳只挡住已经读过 L1 的同节点读取者；这之后、
     * 迟到的 `FILL` 执行之前开始的读取仍可能填入旧版本。原子广播时迟到的 `FILL` 带着广播，调用方随后登记 [unfilterOwnFrames]（P12），
     * 本节点在 `localMaxAge` 之内照常处理这一帧；非原子广播时这里的发布可能早于迟到的执行，各节点（含本节点）的界都是写入方的
     * `putIfNewer`（版本相等时广播）或 `localMaxAge`。
     */
    @Suppress("TooGenericExceptionCaught")
    protected suspend fun afterAdvancedFill(keys: Collection<String>) {
        keys.forEach {
            stamps.invalidate(it)
            local.invalidate(localKeyOf(it))
        }
        if (atomicBroadcast) {
            return
        }
        try {
            uncancellable { publish(frameOf(keys)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            metrics.remoteFailure()
            log.warn(e) { "Cache[$name] - failed to broadcast the invalidation of an advancing fill $keys." }
        }
    }

    /**
     * 写入失败之后尽力广播（见 [write]）：广播的失败只告警并附在写入的异常上，调用方看到的仍是写入的原始异常。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun publishAfterFailure(frame: ByteArray, error: Exception) {
        try {
            publish(frame)
        } catch (e: Exception) {
            error.addSuppressed(e)
            log.warn(e) { "Cache[$name] - failed to broadcast the invalidation after a failed write." }
        }
    }

    /**
     * 异步变体的写入入口：在运行时作用域中运行 [block]，返回结果的副本（`AsyncContext.runAsync`）。
     */
    protected fun runAsyncCommand(block: suspend () -> Unit): CompletableFuture<Void?> = context.async.runAsync<Void?> {
        block()
        null
    }

    /**
     * 写入失败：以 [CacheWriteException] 抛出，[CacheWriteException.outcomeUnknown] 按客户端能观察到的事实区分（RFC 0005 §4.3、§4.6，
     * [notExecuted]）。后端以 [UnsupportedOperationException] 拒绝时原样抛出，与不支持的操作一致。
     */
    protected fun throwWriteFailure(operation: String): (Exception) -> Unit = { error ->
        if (error is UnsupportedOperationException) {
            throw error
        }
        throw CacheWriteException(name, operation, error, outcomeUnknown = !notExecuted(error))
    }

    /**
     * [keys] 是编码 key（通道已去掉本缓存的存储前缀与不属于它的 key，S4），也就是 L2 的 key；失效戳仍按存储 key。
     * 零 key 的帧是存储创建时的频道探测（RFC 0005 §4.7，K8）：什么也不做，不计入收到的失效。
     */
    override fun onInvalidated(keys: List<String>, origin: NodeId) {
        if (keys.isEmpty()) {
            return
        }
        if (origin == nodeId) {
            if (ownFrames.isNotEmpty()) {
                applyOwnFrame(keys)
            }
            return
        }
        metrics.invalidationReceived()
        keys.forEach {
            stamps.invalidate(storageKeyOfLocal(it))
            local.invalidate(it)
        }
    }

    /**
     * 本节点自己的帧（P12）：只处理结果未知的写入仍在窗口内的 key（[ownFrames]），过期的登记顺手删除。不计入收到的失效。
     */
    private fun applyOwnFrame(keys: List<String>) {
        val now = clock.nowMillis()
        keys.forEach { key ->
            val until = ownFrames[key] ?: return@forEach
            if (until > now) {
                stamps.invalidate(storageKeyOfLocal(key))
                local.invalidate(key)
            } else {
                ownFrames.remove(key, until)
            }
        }
    }

    /**
     * 写入结果未知（P12）：[storageKeys] 上本节点自己的帧在 `localMaxAge` 之内不再被过滤（[ownFrames]）。
     * 只对原子广播的后端：非原子广播时迟到执行的写入不带广播，没有帧可等，本节点的旧副本由 `localMaxAge` 兜底。
     * 登记很少（只在失败时），超过 [MAX_OWN_FRAMES] 时删掉过期的登记。
     */
    protected fun unfilterOwnFrames(storageKeys: Collection<String>) {
        if (!atomicBroadcast) {
            return
        }
        val now = clock.nowMillis()
        val until = now + policy.localMaxAge.toMillis()
        storageKeys.forEach { ownFrames[localKeyOf(it)] = until }
        if (ownFrames.size > MAX_OWN_FRAMES) {
            ownFrames.entries.removeIf { it.value <= now }
        }
    }

    /**
     * 只在本节点递增失效戳并淘汰 L2 副本，不碰 L1、不广播（[me.ahoo.cocache.core.FillKey.evictLocal]）。
     */
    internal fun evictLocal(encodedKey: String) {
        stamps.invalidate(storageKeyOfLocal(encodedKey))
        local.invalidate(encodedKey)
    }

    override fun onReset() {
        log.info { "Cache[$name] - reset: clear local copies." }
        metrics.reset()
        stamps.invalidateAll()
        local.invalidateAll()
    }

    /**
     * 本地副本数（估计值），供运维查看。
     */
    override val localSize: Long
        get() = local.estimatedSize()

    override fun clearLocal() {
        stamps.invalidateAll()
        local.invalidateAll()
    }

    /**
     * 只广播失效帧（[CacheAdmin.broadcastEncoded]）：本节点自己的帧被过滤，所以先在本地递增戳并淘汰 L2（同写入方的第一步），再发布。
     * L1 不变，因此不需要写入方的第二次递增：在这之后取戳的填充读到的就是 L1 的当前内容。发布不可取消，失败时原样抛出。
     */
    final override fun broadcastEncoded(encodedKey: String) {
        val storageKey = storageKeyOf(encodedKey)
        stamps.invalidate(storageKey)
        local.invalidate(encodedKey)
        runSync { uncancellable { publish(frameOf(listOf(storageKey))) } }
    }

    override val pendingInvalidationCount: Int
        get() = 0

    /**
     * 该存储 key 的失效是否仍在重试中。
     */
    protected open fun isInvalidationPending(storageKey: String): Boolean = false

    /**
     * 把 L1 内容解码为展示用的值；形状不符或解码失败时抛出。
     */
    protected abstract fun describe(envelope: Envelope): Any?

    /**
     * 只读快照：L2 用 `getIfPresentQuietly`（不影响淘汰统计），L1 用不带 lease 与刷新令牌的 `read`（纯读取，在调用方线程上等待，
     * 规则同同步变体）。不经过 SingleFlight，不取戳，不写任何一层，不计入指标：与命中路径完全无关。
     */
    override fun inspect(encodedKey: String): KeyInspection {
        val storageKey = storageKeyOf(encodedKey)
        val localCopy = local.policy().getIfPresentQuietly(encodedKey)?.let { localCopyOf(it) }
        return KeyInspection(name, storageKey, localCopy, remoteCopyOf(storageKey), isInvalidationPending(storageKey))
    }

    private fun localCopyOf(entry: LocalEntry<V>): KeyInspection.LocalCopy {
        val state = when {
            entry.value != null -> KeyInspection.LocalState.VALUE
            entry.unfilled -> KeyInspection.LocalState.UNFILLED
            else -> KeyInspection.LocalState.ABSENT
        }
        return KeyInspection.LocalCopy(
            state = state,
            value = entry.value?.let { KeyInspection.display(it) },
            expiresAt = Instant.ofEpochMilli(entry.expiresAt),
            refreshAt = entry.refreshAt.takeIf { it != FOREVER }?.let { Instant.ofEpochMilli(it) },
            expired = entry.expiresAt <= clock.nowMillis(),
        )
    }

    /**
     * 更高的格式（RFC 0005 §4.7）：格式版本高于本节点，以及后端报告为更高格式的内容（本节点不认识的必须理解的位或状态）。
     * 既不回收也不写回：有数据源时绕过 L1 回源，没有时读取失败。格式号低于本节点格式的内容（含格式 0，即不是本协议写入的内容）不在此列：
     * 它们是读不懂的内容，有数据源时回收后回源。
     */
    protected fun isNewerFormat(format: Int): Boolean = format > backendFormat

    @Suppress("TooGenericExceptionCaught")
    private fun remoteCopyOf(storageKey: String): KeyInspection.RemoteCopy {
        val read = try {
            runSync { uncancellable { store.read(storageKey, null, null, null) } }
        } catch (e: Exception) {
            return KeyInspection.RemoteCopy(KeyInspection.RemoteState.ERROR, error = e.toString())
        }
        return when (read) {
            is L1Read.Miss -> KeyInspection.RemoteCopy(KeyInspection.RemoteState.MISS)
            is L1Read.Leased -> KeyInspection.RemoteCopy(KeyInspection.RemoteState.LEASED, ttl = read.remaining)
            is L1Read.Hit -> remoteHitOf(read)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun remoteHitOf(read: L1Read.Hit): KeyInspection.RemoteCopy {
        val unreadable = { reason: String ->
            KeyInspection.RemoteCopy(
                KeyInspection.RemoteState.UNREADABLE,
                ttl = read.ttl,
                format = read.format,
                error = reason
            )
        }
        if (isNewerFormat(read.format)) {
            return unreadable("Written by a newer format[${read.format}] than this node[$backendFormat].")
        }
        if (read.format != backendFormat) {
            return unreadable(
                "Unrecognized remote content (format[${read.format}], not written in this node's format)."
            )
        }
        val value = try {
            describe(read.envelope)
        } catch (e: Exception) {
            return unreadable(e.toString())
        }
        val state = when (read.envelope) {
            is Envelope.Scalar -> KeyInspection.RemoteState.VALUE
            is Envelope.Members -> KeyInspection.RemoteState.SET
            Envelope.Absent -> KeyInspection.RemoteState.ABSENT
        }
        return KeyInspection.RemoteCopy(
            state = state,
            value = value?.let { KeyInspection.display(it) },
            ttl = read.ttl,
            format = read.format,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            log.info { "Cache[$name] - close." }
        }
    }

    override fun toString(): String =
        "${javaClass.simpleName}(name='$name', keyPrefix='${keyspace.keyPrefix}', namespace='${keyspace.namespace}')"

    protected companion object {
        val log = KotlinLogging.logger {}

        /**
         * 格式版本 `0` 表示不是本协议写入的内容（如其它程序写入的值）。它与其它低于本节点的格式一样按读不懂处理。
         */
        const val UNRECOGNIZED_FORMAT = 0

        /**
         * [ownFrames] 的登记超过这个数时，登记新 key 的调用顺手删除过期的登记。
         */
        const val MAX_OWN_FRAMES = 1024

        /**
         * 无条件写入：总是修改 L1。
         */
        val ALWAYS_CHANGED: () -> Boolean = { true }

        /**
         * 同步变体的 [write]：不可取消，调度器不变（续接仍在调用方线程上）。
         */
        val SYNC_WRITE: CoroutineContext = NonCancellable

        /**
         * 异步变体的 [write]：不可取消，不经执行器（在 L1 命令的完成线程上就地继续）。
         */
        val ASYNC_WRITE: CoroutineContext = NonCancellable + Dispatchers.Unconfined

        /**
         * 写入确定没有执行（RFC 0005 §4.3）：存储在写出之前就拒绝了命令（[L1RejectedException]），或后端没有修改 L1 就拒绝了写入
         * （读不懂的内容、保留版本的失效遇到权威标量，或后端以 [UnsupportedOperationException] 拒绝了这个操作）。
         * 其余失败都是结果未知：命令可能已经生效，也可能在之后的任何时刻生效。
         */
        fun notExecuted(error: Throwable): Boolean =
            error is L1RejectedException || error is ForeignFormatException || error is AuthoritativeContentException ||
                error is UnsupportedOperationException

        fun <V> buildLocal(policy: CachePolicy): Cache<String, LocalEntry<V>> {
            val local = policy.local
            val builder = Caffeine.newBuilder().maximumSize(local.maximumSize)
            local.initialCapacity?.let { builder.initialCapacity(it) }
            local.expireAfterAccess?.let { builder.expireAfterAccess(it) }
            return builder.build()
        }
    }
}

/**
 * 保留版本的失效（`VersionedL1Store.evictKeepingVersion`）遇到带摘要段的值（权威标量，RFC 0005 §4.7，D4）：后端拒绝，L1 原样未动、不广播
 * （[AbstractEngine.notExecuted]）。作为 [CacheWriteException] 的 `cause` 抛出。
 */
internal class AuthoritativeContentException(cacheName: String, storageKey: String) : IllegalStateException(
    "Cache[$cacheName] - key[$storageKey] holds a value written by a Store (digest segment): " +
        "a versioned eviction does not touch it, and it is kept as is. The namespace is shared with an authoritative cache."
)
