---
title: 扩展 CoCache
description: 实现 CoherenceBackend（L1Store 与 InvalidationChannel，Java 实现 FutureL1Store）或 Codec：SPI 的精确约定、可选能力、失败与取消的规则、cocache-test 的 TCK 套件及其运行方式、编解码器规则，以及不应定制的部分。
---

# 扩展 CoCache

| 你想要 | 实现 | 验证 |
|--------|------|------|
| 把 L1 存到 Redis 以外的地方，或者以不同方式访问 Redis | [`CoherenceBackend`](#backend) | [`BackendProtocolSpec`、`EngineSpec`、`SetEngineSpec`、`MultiNodeSpec`、`FaultSpec`、`VersionedSpec`](#tck) |
| 改变值转换为字节的方式 | [`Codec<V>`](#codecs) | 自己的往返测试 |
| 加载、编码 key、拒绝不可能存在的 key、按条目决定寿命 | [函数](#functions)：`CacheLoader`、`KeyEncoder`、`KeyFilter`、`TtlFunction` | 自己的测试 |

SPI 位于 `me.ahoo.cocache.api.spi`（`cocache-api`，零依赖）。后端运行时只需要 `cocache-api`，测试时需要 `cocache-test`。

在 Spring Boot 应用中，声明一个 `CoherenceBackend` bean 即可替换 Redis 后端（starter 会退让），Spring 会在关闭时关闭它。值类型为 `V` 的每个接口式缓存都会使用唯一的 `Codec<V>` bean，除非该缓存用 `@CoCache(codec = "beanName")` 指定了别的。

## 后端 {#backend}

```kotlin
interface CoherenceBackend : AutoCloseable {
    val atomicBroadcast: Boolean            // 写入能否在同一个原子步骤中发布它的帧？
    val format: Int                         // 本实现写入的线格式版本
    val channel: InvalidationChannel        // Java：FutureInvalidationChannel.adapt(…)
    fun store(keyspace: Keyspace): L1Store  // 每个缓存调用一次，可以阻塞；Java：FutureL1Store.adapt(…)
    override fun close() = Unit
}
```

L1 存储与失效通道是同一个对象的两个面：串行化写入的一方也应该发出失效。连接与线程属于后端，随它关闭：存储没有 `close`。

- **`Keyspace`**。`store`、`subscribe` 与 `publish` 接收 `Keyspace(keyPrefix, namespace)`。它的 `storagePrefix` 是 `{keyPrefix}{namespace}:`，构造时拒绝 `[A-Za-z0-9._-]+` 之外的命名空间。每个 keyspace 一个频道，名字由前缀与命名空间共同构成（Redis 后端是 `{keyPrefix}inv:{namespace}`）：命名空间相同、前缀不同的两个 keyspace 不能共用频道。有访问控制的后端在 `store` 中检查自己能否在该 keyspace 的频道上发布，不能时抛出，而不是让每次写入在修改 L1 之后才在广播处失败。Redis 后端向那里发布一个不带 key 的帧。
- **`atomicBroadcast`**。为 `true` 时，存储的每个写方法都必须在修改的同一个原子步骤中发布它的 `frame`。为 `false` 时，引擎在每次写入成功或结果未知之后调用 `channel.publish(keyspace, frame)`。原子更好：写入与发布之间的崩溃会丢失消息。只声明你能保证的能力，TCK 会分别检查两种情况。只广播的失效（`ExternalInvalidator.broadcast`、端点的 [`POST`](../operations/observability.md#broadcast-key)）在所有后端上都调用 `publish`，所以无论 `atomicBroadcast` 是什么，它都必须可用。
- **`format`**。引擎把它与 `L1Read.Hit.format` 比较：`0` 表示不是你的协议写入的内容，引擎按损坏处理。引擎只读与你的 `format` 完全相同的内容：更低的格式与 `0` 一样是读不懂的内容；更高的格式一律绕开，既不回收也不写回。读到带你不认识的特性（未知的标志位或状态）的内容时报告为更高格式，不能报成更低的格式，否则有 loader 的缓存会回收它。内置后端写入 `2`。你的格式只能通过两阶段升级递增。

### `L1Store` {#l1-store}

存储的操作都是 `suspend` 函数。Kotlin 后端直接实现 `L1Store`；Java 后端实现 [`FutureL1Store`](#java) 再适配。

```kotlin
interface L1Store {
    suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): L1Read
    suspend fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>, timeout: Duration?): Map<String, L1Read>
    suspend fun fill(fill: Fill): L1Fill
    suspend fun fillAll(fills: List<Fill>): List<L1Fill>
    suspend fun release(key: String, token: Token)
    suspend fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean
    suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray)
    suspend fun putAll(puts: List<Put>, frame: ByteArray)
    suspend fun invalidate(keys: Collection<String>, frame: ByteArray)
    fun conditional(): ConditionalL1Store?  // Store，或 null
    fun versioned(): VersionedL1Store?      // VersionedCache 与 VersionedStore，或 null
    fun sets(): SetL1Store?                 // SetStore，或 null
}
interface ConditionalL1Store {
    suspend fun readIfChanged(key: String, known: Digest?, timeout: Duration?): L1ConditionalRead
    suspend fun putIfMatch(key: String, expected: Digest?, envelope: Envelope.Scalar, ttl: Duration?, frame: ByteArray): L1ConditionalWrite
    suspend fun deleteIfMatch(key: String, expected: Digest, frame: ByteArray): L1ConditionalWrite
}
interface VersionedL1Store {
    suspend fun putIfNewer(key: String, envelope: Envelope, version: Long, ttl: Duration?, frame: ByteArray): L1VersionedWrite
    suspend fun evictKeepingVersion(key: String, ttl: Duration?, frame: ByteArray): L1Eviction
}
interface SetL1Store {
    suspend fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): L1SetWrite
    suspend fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): L1SetWrite
}
```

传入的 key 是完整的，已包含前缀与命名空间。存储只处理字节与令牌，不持有任何策略：每个 TTL、窗口、时限与令牌都是参数。每个操作都是抽象的，SPI 没有非原子的默认实现。涉及的类型：

| 类型 | 形状 |
|------|------|
| `Envelope` | `Scalar(bytes, version, writer, digest)`、`Members(字节列表)` 或 `Absent`（负缓存）。`version` 默认为 `Versioned.NO_VERSION`，`writer` 与 `digest` 默认为 `null`，三者都参与相等比较。`writer` 是与 `Token` 同形的写入标识，引擎在权威类型（`Store`、`VersionedStore`）的每次标量写入中给出它；`digest` 是载荷的 SHA-256，引擎在 `Store` 的每次写入中给出它，按版本的写入从不带它 |
| `L1Read` | `Hit(envelope, format, ttl, refreshGranted)`、`Miss(leaseGranted)` 或 `Leased(remaining)`。`ttl == null` 表示没有到期时间 |
| `Token` | 24 字节：`NodeId`（16）+ 序号（8）。唯一、永不重复。按字节比较 |
| `LeaseRequest(token, ttl)` | 未命中时放置的 lease，`ttl` 后到期 |
| `RefreshRequest(window, token, tokenTtl)` | 值在 `window` 内时放置的刷新令牌。签发早于 `tokenTtl` 的令牌可以被接管 |
| `Fill(key, token, envelope, ttl[, version])` | 仅当 `key` 持有 `token` 时，以 `ttl` 写入 `envelope`。`version`（不属于 `copy`）默认取标量的版本；带版本的 `Absent` 即墓碑 |
| `L1Fill` | 写入时为 `Filled(advanced)`，没有写入时为 `Rejected(currentVersion)`。填充让版本前进或替换了淘汰标记、并且你广播了时，`advanced` 为 `true`；版本规则拒绝填充时 `currentVersion` 为 key 中的版本，令牌不匹配时为 `null` |
| `Put(key, envelope, ttl)` | `putAll` 中的一项：按 `put` 覆盖 `key` |
| `ContentFingerprint` | `reclaim` 比较的 SHA-1，依次哈希 1 字节的内容类别、读取报告的格式（`L1Read.Hit.format`，大端 `int32`）与内容：标量 → `V` 与载荷字节；负缓存 → `M`，没有内容；集合 → `S` 与按返回顺序写成 `int32 长度 + 字节` 的每个成员。由 `ContentFingerprint.of(envelope, format)` 按一个 `Hit` 计算。字节相同、类别或格式不同的内容从不匹配 |
| `Digest` | 载荷的 SHA-256（`Digest(algorithm, bytes)`、`Digest.of(envelope)`、`Digest.ofPayload(bytes)`），条件读写比较它。由引擎计算；存储保存并返回它，从不重算 |
| `L1ConditionalRead` | `Unchanged(format)`、`Changed(envelope, format)`（标量值）、`Absent`（不存在、lease 或负缓存）或 `Unreadable(format)` |
| `L1ConditionalWrite` | `putIfMatch` 与 `deleteIfMatch` 的结果：`Written`（已写入或已删除）、`Conflict(current)`（`current == null`：没有值）或 `Unreadable(format)` |
| `L1VersionedWrite` | `Written`、`Stale(current)` 或 `Unreadable(format)` |
| `L1Eviction` | `Evicted`、`Authoritative`（key 中是带摘要的值）或 `Unreadable(format)` |
| `L1SetWrite` | `addMembers` 与 `removeMembers` 的结果：`Written`，或 `Unreadable(format)`（`format == 0` 表示不是你写入的内容） |

逐个方法的约定。状态机见[一致性](./consistency.md#l1-fence)。

| 方法 | 必须 |
|------|------|
| `read` | key 不存在且 `lease != null`：放置 lease（`lease.ttl` 后到期），返回 `Miss(true)`。不存在且没有 lease：返回 `Miss(false)`，不写入任何内容。处于 lease 状态：返回 `Leased(remaining)`。有值：返回带剩余 TTL 的 `Hit`。标量值的剩余 TTL `> 0` 且 `≤ refresh.window`，并且没有令牌或令牌签发早于 `refresh.tokenTtl`：写入刷新令牌（保留 TTL），返回 `refreshGranted = true`。永不过期的值、带摘要的值不授予令牌；带版本的值照常授予。淘汰标记（`evictKeepingVersion`）在没有 lease 请求时读作 `Miss(false)`；有 lease 请求、且没有令牌或令牌签发早于 `lease.ttl` 时，原地在其上放置 lease（保留版本与 TTL），返回 `Miss(true)`，否则返回 `Leased(lease.ttl 减去令牌已签发的时长)`。不是你写入的内容：返回 `Hit(…, format = 0)`。两个请求都为 `null` 时，`read` 是纯读取，不写入任何内容 |
| `readAll` | 对每个 key 执行 `read`，只为 `leases` 中的 key 申请 lease。返回全部 key。失败时释放这次调用可能取得的 lease（见[下文](#failures)） |
| `fill` / `fillAll` | 仅当 key 当前持有的正是 `fill.token`（作为 lease 或刷新令牌）时写入，并把到期时间重设为 `fill.ttl`。写入时返回 `Filled(advanced = false)`，令牌不匹配时返回 `Rejected(currentVersion = null)`；`fillAll` 按顺序为每一项返回一个结果。`versioned()` 非 `null` 的存储把 `fill.version` 与内容一起保存：带版本的 loader 返回的 `Versioned(null, n)` 以带版本 `n` 的 `Absent` 到达，必须写成与 `putIfNewer` 相同的墓碑。key 中带版本时（持有刷新令牌的带版本值、持有 lease 的淘汰标记），在同一个原子步骤中按版本规则：`fill.version` 更旧或为 `NO_VERSION` → 清零令牌，保留内容与到期时间，返回 `Rejected(当前版本)`；值上版本相等 → 写入，不广播，`Filled(false)`；更新，或淘汰标记上被接受的填充 → 写入，广播一帧（只含 `fill.key`，来源为 `fill.token.node`；仅当 `atomicBroadcast` 为 `true` 时，否则由引擎发布），`Filled(true)` |
| `release` | 令牌与 lease 匹配：删除 key。令牌与值上的刷新令牌、或淘汰标记上的 lease 匹配：清除令牌，保留内容及其 TTL。否则什么也不做 |
| `reclaim` | 仅当 key 当前内容的指纹（按 `read` 此刻会返回的信封与格式计算）等于 `observed`，且不是 lease、带版本的内容、淘汰标记或带摘要的值时，把它替换为 lease。返回是否替换。绝不删除已经变化的内容 |
| `put` | 覆盖任何状态，销毁任何令牌，设置到期时间，广播 `frame`。`Members` 会替换整个集合 |
| `putAll` | 对每一项执行 `put`，对每个 key 原子，不要求跨 key 原子。`frame` 携带全部 key：每个 key 恰好随其写入广播一次（可以拆分帧，例如按 slot 分组）。没有逐项调用 `put` 的默认实现：那样每次调用都会广播全部 key |
| `invalidate` | 删除每个 key，销毁任何令牌，广播 `frame`。对每个 key 原子。批次可以拆分 |
| `addMembers` | 一次原子的检查加写入。key 是本协议的集合：加入成员。key 不存在、负缓存或 lease：替换为只含这些成员的集合。两种情况都销毁任何令牌，设置到期时间（`null` 表示去掉到期时间），广播，返回 `Written`。不是本协议集合的内容（不是你写入的内容、标量值、比你的格式更高的集合）：什么都不改、不广播，返回带该内容格式版本的 `Unreadable`（不是你写入的内容为 `0`）。`SetStore` 没有数据源，覆盖这类内容就是删除唯一副本，因此没有不检查的元素写入 |
| `removeMembers` | 检查同 `addMembers`。key 是本协议的集合：移除成员，集合变空时删除 key，否则重设到期时间。key 不存在、负缓存或 lease：不修改。这两种情况都广播并返回 `Written`。读不懂的内容：什么都不改、不广播，返回 `Unreadable(format)` |
| `readIfChanged` | 纯读取，同不带请求的 `read`。本协议的标量值且保存的摘要等于 `known`：返回 `Unchanged(format)`，不回传载荷。其它带摘要的值：`Changed(envelope, format)`，信封带保存的摘要与写入标识。key 不存在、lease 或不带版本的负缓存：`Absent`。其余内容（不是你写入的内容、集合、更高的格式、没有摘要的值、带版本的内容、淘汰标记）：`Unreadable(format)`。比较保存的摘要，不重算，只读比较所需的部分，未变的值无论多大成本都相同 |
| `putIfMatch` | 一次原子的比较加写入。`expected == null` 且 key 中没有值（不存在、lease、不带版本的负缓存），或 key 中是保存的摘要等于 `expected` 的值时匹配。`readIfChanged` 视为 `Unreadable` 的内容：返回 `Unreadable(format)`。它从不视为没有值，否则 `expected == null` 会覆盖它，滚动升级期间旧实例还会把更新的值降级覆盖。`envelope.digest` 原样保存。匹配时按 `put` 写入（销毁令牌、设置到期时间、广播 `frame`），返回 `Written`；否则什么都不改、不广播，返回 `Conflict(当前值的摘要，或 null)`。先读后写会让两个并发的写入方都成功：存储不能原子地比较并写入时，让 `conditional()` 返回 `null` |
| `deleteIfMatch` | 一次原子的比较加删除。只匹配保存的摘要等于 `expected`（从不为 `null`）的值。匹配时删除 key、销毁任何令牌、广播 `frame`，返回 `Written`。否则什么都不改、不广播，返回 `Conflict(当前值的摘要)`；key 中没有值（不存在、lease、不带版本的负缓存）时返回 `Conflict(null)`。`readIfChanged` 视为 `Unreadable` 的内容：返回 `Unreadable(format)` |
| `putIfNewer` | 一次原子的按版本比较加写入。key 的版本是与标量值或负缓存一起保存的版本；没有版本的值或负缓存、lease、不存在的 key 都视为 `NO_VERSION`，总是写入。`version` 更大时：按 `put` 写入 `envelope`（标量值，或 `invalidateIfNewer`、`deleteIfNewer` 给出的表示墓碑的 `Absent`），与它一起保存 `version`，返回 `Written`。相等时：什么都不改，但在同一个原子步骤中广播 `frame`（受令牌保护的填充可能写入了这个版本而没有广播），返回 `Stale(current)`。更旧时：什么都不改、不广播，返回 `Stale(current)`。淘汰标记按它的版本比较。读不懂的内容按 `evictKeepingVersion` 的规则分类。`reclaim` 必须拒绝带版本的内容，因为 lease 放不下版本。`fill`、`put` 与 `putAll` 保存 `Envelope.Scalar.version`，`read` 原样返回；`putIfMatch` 写入没有版本的值。`version` 从不是 `Versioned.NO_VERSION`：在修改任何东西之前以 `IllegalArgumentException` 拒绝它 |
| `evictKeepingVersion` | 一个原子步骤。带版本的内容（值、墓碑、淘汰标记）：替换为带这个版本、没有令牌的淘汰标记，到期时间设为 `ttl`，广播 `frame`，返回 `Evicted`。既没有版本也没有摘要的内容（普通的值或负缓存、lease、不存在）：删除并广播，返回 `Evicted`。带摘要的值：什么都不改，返回 `Authoritative`。读不懂的内容（不是你写入的、集合、更高的格式）：什么都不改，返回 `Unreadable(format)` |

对所有方法：

- **保存写入标识与摘要**。每个写入标量的操作（`fill`、`fillAll`、`put`、`putAll`、`putIfMatch`、`putIfNewer`）把 `Envelope.Scalar.writer` 与 `Envelope.Scalar.digest` 与值一起保存，`read`、`readAll` 与 `readIfChanged` 原样返回。不带它们的整体写入不保存。不要校验摘要：引擎校验，对每个后端相同。
- **对每个 key 原子**。批量方法对每个 key 原子，不要求跨 key 原子。
- **每次状态转换都重设到期时间**，精度为毫秒。lease 或负缓存的寿命绝不能泄漏到新值上。
- **读取时从不删除。**
- **失败时抛出异常**。不要吞掉错误或在内部重试：降级、重试还是重新抛出由引擎决定，并由引擎计数。其余规则见[失败、时限与取消](#failures)。
- **断线时拒绝命令**，而不是缓冲后重放。

`InMemoryBackend` 在一个进程内实现了完整的协议，是参考实现。多个运行时共用一个实例就像多个应用实例，因此它也很适合作为应用测试的后端。

### 可选能力 {#capabilities}

条件读写、按版本写入与集合成员的增删都是可选的。存储为每种能力提供一个访问器，返回它的能力面，不支持时返回 `null`：

- **访问器从不抛出**，并且在存储的整个生命周期内返回同样的结果：每次都是同一个对象，或总是 `null`。引擎可以只取一次。
- **访问器是抽象的**，没有默认的 `null`：否则只包装基本操作的装饰器会静默地剥掉能力。
- **能力面是独立的接口**，不继承 `L1Store`，所以想拦截它们的[装饰器](#decorators)覆盖访问器即可，不会被绕过。
- `CacheRuntime.create` 检查[缓存类型](../guide/caches.md#types)需要的能力：`Store` 需要 `conditional()`，`VersionedCache` 与 `VersionedStore` 需要 `versioned()`，`SetStore` 需要 `sets()`。缺少时创建以 `UnsupportedOperationException` 失败，不注册任何东西。`Cache` 不需要其中任何一个。每个缓存创建时的日志列出它的存储提供的能力，例如 `L1 capabilities[conditional, versioned, sets]`。

TCK 检查存储提供的能力与你[声明](#expected-capabilities)的完全相同。

### 失败、时限与取消 {#failures}

- **两类失败**。命令在写出之前被拒绝（例如连接已断开），或在发出之前同步失败，是*确定未执行*，只有这类失败以 `L1RejectedException`（`me.ahoo.cocache.api.spi`）报告，客户端的异常作为它的 cause。其余失败都是*结果未知*：超时、断线时被排空的命令、脚本的错误回复、被客户端取消的命令、解码失败的应答，以其它异常报告。只有一批命令全部确定未执行时，这一批才算确定未执行；其中有命令已经发出时，整个调用结果未知。引擎据此给出 `CacheWriteException.outcomeUnknown`（`L1RejectedException` 为 `false`），不为被拒绝的命令释放令牌，没有原子广播的后端上也不在它之后补发广播。`RedisBackend` 把 Lettuce 没有写出的命令与建立失败的节点连接映射为 `L1RejectedException`。
- **失败之后释放令牌**。取得或交回令牌的命令（`read`、`readAll`、`fill`、`fillAll`、`release`、`reclaim`）以结果未知失败时，异步地释放这个令牌，不等待；释放失败时按退避重发，直到执行或令牌过期。令牌已不匹配的释放什么也不做，所以迟到、重复或重连之后才到达的释放都无害。释放通常在它清理的命令之后、同一条连接上执行，令牌因此在重连之后就被收回；连接被替换之后，新连接上的释放可能先于旧连接上被排空的命令执行，那条命令的令牌留到它的寿命（lease 为 `lease.ttl`）。否则应答丢失的命令取得的 lease 会让每个实例在这个 key 上等到 `lease.ttl`。
- **从不以 `CancellationException` 报告失败**。在 JVM 上它就是协程的取消：引擎会把它当作调用方自己被取消，不回退到数据源，等在这次加载上的读取方各自重新运行。客户端取消的命令（连接重置或关闭时）是结果未知的失败：抛出别的异常，可以把取消异常作为 cause。
- **每个调用都要有上限**。调用必须自己结束（例如由命令超时限定），不能永远挂起。读取带有这次调用的 `timeout`：`null` 为你的命令超时；给出时必须为正，以命令超时为上限，你以两者中较短的一个限定这次调用。超时要取消到底层命令：尚未写出的命令不再写出。
- **引擎从不取消调用**。它在 `NonCancellable` 中调用存储，线程中断也不到达存储，所以用可取消的 `await()` 等待是安全的。
- **写入方法发出的命令全部结束之后才返回或抛出**。否则引擎随后补发的广播可能先于仍在执行的命令到达。

### Java 后端：`FutureL1Store` {#java}

Kotlin 不允许同名的挂起方法与阻塞方法重载，所以 Java 后端实现 `FutureL1Store`：操作与语义完全相同，只是返回 `CompletableFuture`。能力面有 `FutureConditionalL1Store`、`FutureVersionedL1Store` 与 `FutureSetL1Store`，通道有 `FutureInvalidationChannel`。返回适配后的形式：

```java
public final class MyBackend implements CoherenceBackend {
    private final InvalidationChannel channel = FutureInvalidationChannel.adapt(new MyChannel());

    @Override
    public L1Store store(Keyspace keyspace) {
        return FutureL1Store.adapt(new MyStore(keyspace));
    }

    @Override
    public InvalidationChannel getChannel() {
        return channel;
    }
    // atomicBroadcast、format、close …
}
```

- future 可以在 I/O 线程上完成。只有阻塞客户端的后端在自己的执行器上完成 future；不要阻塞调用方线程。
- 让 future 失败与同步抛出都可以：引擎拿到原始异常（剥掉 `CompletionException` 与 `ExecutionException`）。
- 每个 future 都要有上限，同上。引擎从不取消返回的 future。
- 你自己取消的 future（例如用来实现超时）与同步抛出的 `CancellationException` 按结果未知的失败处理：适配器把它换成以取消异常为 cause 的 `IllegalStateException`。释放这类命令的令牌仍由你负责。
- 不得返回 `null`，结果类型非空的 future 也不得以 `null` 完成（`Void` 与集合写入的 `Integer` 除外）。
- `adapt` 对每个能力访问器只取一次、只适配一次，所以适配后的存储的访问器是稳定的。

### 装饰器：`ForwardingL1Store` {#decorators}

包装另一个后端的存储的装饰器（指标、追踪、故障注入）继承 `ForwardingL1Store`，只覆盖要拦截的部分。能力访问器原样返回被包装存储的能力面，能力面上的操作因此不经过装饰器。要拦截它们，就覆盖访问器，返回在构造时创建一次的 `ForwardingConditionalL1Store`、`ForwardingVersionedL1Store` 或 `ForwardingSetL1Store`；被包装的存储没有这个能力面时返回 `null`：

```kotlin
class CountingStore(delegate: L1Store) : ForwardingL1Store(delegate) {
    val conditionalWrites = AtomicInteger()

    private val conditional = delegate.conditional()?.let { surface ->
        object : ForwardingConditionalL1Store(surface) {
            override suspend fun putIfMatch(
                key: String,
                expected: Digest?,
                envelope: Envelope.Scalar,
                ttl: Duration?,
                frame: ByteArray
            ): L1ConditionalWrite {
                conditionalWrites.incrementAndGet()
                return super.putIfMatch(key, expected, envelope, ttl, frame)
            }
        }
    }

    override fun conditional(): ConditionalL1Store? = conditional
}
```

TCK 的 `HookedBackend` 就是这样实现的。

### `InvalidationChannel` {#invalidation-channel}

```kotlin
interface InvalidationChannel {
    fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription  // 阻塞，每个缓存一次
    suspend fun publish(keyspace: Keyspace, frame: ByteArray)  // 写入之后仅在 atomicBroadcast == false 时调用；只广播的失效在所有后端上都调用
}
interface InvalidationListener {
    fun onInvalidated(keys: List<String>, origin: NodeId)
    fun onReset()
}
fun interface Subscription : AutoCloseable
```

`publish` 的时限、取消与失败规则同存储。Java 后端实现 `FutureInvalidationChannel`，返回 `FutureInvalidationChannel.adapt(…)`。

- **帧**。`frame` 由引擎用 `InvalidationFrame.encode(origin, storageKeys)` 构造。用 `InvalidationFrame.decode` 解码、key 经 `keyspace.encodedKeys(…)` 转换后投递给该 keyspace 的每个订阅者：订阅者收到的是编码后的 key（存储 key 去掉 `{keyPrefix}{namespace}:`），不属于这个 keyspace 的 key 去掉。同一 keyspace 的订阅者按订阅的先后依次回调，帧与重置都是：缓存在创建时订阅，之后加入的订阅者被回调时，缓存已经丢弃了本地副本。`decode` 拒绝的帧（未知版本，包括预留的 `NAMESPACE_RESET_VERSION`、截断的帧、尾随字节）不要丢弃，而是对该频道的每个订阅者调用 `onReset()`：丢弃的帧就是丢失的失效。不带 key 的帧是合法帧，照常投递，订阅者会忽略它。不要按来源过滤：订阅者会忽略自己的 `NodeId`。
- **至多一次可以接受，沉默不行**。每次订阅建立或重新建立**并得到确认**时，以及任何消息可能丢失的时候（心跳失败、消费组重平衡、序号出现空洞），都要对该订阅的每个订阅者调用 `onReset()`。订阅者随后清空它的 L2。没有重置，丢失的消息会让 L2 陈旧到 `local-max-age`。
- **在 `subscribe` 返回之前完成重置**。第一个订阅者的重置必须在 `subscribe` 返回时已经执行，否则迟到的重置会清掉订阅之后才填充的副本。已有订阅上的后续订阅者立即收到一次 `onReset()`。调用方线程被中断时，Redis 后端照样等待确认，返回时保留中断标志。
- **隔离**。订阅新的 keyspace 不能重置其它 keyspace。某个订阅者抛出异常（即使是 `Error`），不能让其它订阅者错过消息或重置。Redis 与内存后端记录失败后继续；投递消息时只重新抛出 `VirtualMachineError`。`subscribe` 为新订阅者执行的重置同样如此：记录失败，照常返回 `Subscription`，否则这个订阅者已登记，却没有句柄可以关闭它。
- **失败**。`subscribe` 失败时抛出异常，不留下任何部分注册：运行时会回滚这个缓存。
- 关闭 `Subscription` 即取消该订阅者的订阅。`close()` 只保证在它返回之后到达的消息与重置不再投递；已在投递中的回调可能在 `close()` 返回之后才到达订阅者，所以订阅者必须容忍迟到的回调。引擎的订阅者可以：迟到的回调只淘汰 L2 副本。

## TCK {#tck}

`cocache-test` 是已发布的 TCK，每个后端都必须通过。

```kotlin
testImplementation("me.ahoo.cocache:cocache-test")
```

| 套件 | 覆盖 | 你提供 |
|------|------|--------|
| `BackendProtocolSpec` | 经 `Suspending.await` 直接调用的 `L1Store` 约定：授予 lease、令牌校验、`L1Fill` 结果、到期时间重设、释放、刷新令牌、回收、批量读写、集合更新、广播帧、订阅时重置，以及（可选）L1 卡顿时读取的 `timeout` 限定这次调用并释放它的 lease | `createBackend()`、`expectedCapabilities`、`elapse(duration)`。可选覆盖 `shortTtl`（默认 300 ms）与 `pause()` |
| `EngineSpec` | `Cache` 与 `Store`，两个运行时共用你的后端：跨实例单次加载、等待 lease 超时、加载期间的失效、同实例失效与读取的竞态、并发 `put`、读己之写、loader 失败、Redis 失败、失效重试、重连重置、`local-max-age`、批量读、提前刷新、回收、更新的格式、回源并发上限、（可选）L1 卡顿时缓存的 `readTimeout` 限定单 key、批量与条件读取、没有数据源的读取得到 `Lookup.Unknown`（含部分 key 未知的 `getAll`）、外部来源的缓存的未填充未命中与失效重试，以及声明了 `CONDITIONAL` 时的 `Store`：读取不申请 lease、写入标识与摘要（摘要被篡改时读取失败，派生副本计入类型不符）、同步删除，以及条件读、写与删除（含并发“读—改—写”：让所有写入方在同一个摘要上竞争，并断言恰好一个胜出） | `createBackend()`、`expectedCapabilities`、`clock`、`elapse(duration)`、`simulateReconnect()`。可选 `pause()` 与 `writeForeign(storageKey, raw)`。 |
| `VersionedSpec` | 需要 `VERSIONED`，覆盖 `VersionedCache` 与 `VersionedStore`。协议（整体写入保存版本、按有符号 64 位整数比较、按版本写入销毁被持有的 lease、带版本的内容不授予刷新令牌也不被回收、墓碑），以及两个运行时共用你的后端：有序、相等版本幂等、拒绝旧版本且不广播不改寿命（没有原子广播时相等的版本会再次广播，重放因此能补上丢失的发布）、墓碑挡住迟到的旧版本、按版本写入胜过持有 lease 的回源、回源保存 loader 给出的版本、并发乱序的写入方收敛到最高版本、结果未知后重放安全、`evict` 保留版本而 `purge` 丢掉版本、在 `Store` 的值上被拒绝的淘汰、落在带版本内容上被拒绝的不带版本的 `null`，以及 `VersionedStore` 读取不申请 lease、写入写入标识、墓碑到期 | `createBackend()`、`expectedCapabilities`、`clock`、`elapse(duration)` |
| `SetEngineSpec` | 需要 `SETS`。两个运行时上的 `SetStore`：原子的并发加入、空集合删除、`put` 与 `putAll` 替换集合、批量读取只发出一次 `readAll`、元素写入拒绝读不懂的内容、写入失败，以及读取失败时回答 `Unknown` | `createBackend()`、`expectedCapabilities`、`clock`、`elapse(duration)` |
| `MultiNodeSpec` | 三个运行时共用你的后端：失效先于 lease、晚于 lease 且另一实例正在等待、晚于 fill 而早于写 L2；其它实例的写入落在读取与 L2 填充之间；不同实例的并发 `put`（非原子后端上事件逆序到达）；失效重试期间的未命中；全部实例中每个 key 只回源一次、只刷新一次 | `createBackend()`、`expectedCapabilities`、`clock`、`elapse(duration)` |
| `FaultSpec` | L1 故障下的后端：存储失败期间副本以 `degraded-local-max-age` 限龄、fill 失败及其 lease、`Store.delete` 失败时抛出而不重试、放弃或溢出队列的失效重试、已生效却报错且可安全重试的 `put` 与 `invalidate`；原子广播的后端上，结果未知、迟到执行的 `put` 经自己的广播淘汰写入方节点的旧副本；（可选）断线期间被拒绝的写入在重连后不被重放；（可选）客户端取消的命令是 L1 故障，不是取消 | `createBackend()`、`expectedCapabilities`、`clock`、`elapse(duration)`；可选 `disconnect()` 与 `commandCancellation()` |

套件只有一套，适用于所有 API。每个引擎套件（`EngineSpec`、`VersionedSpec`、`SetEngineSpec`、`MultiNodeSpec`、`FaultSpec`）都有 `protected open val access: CacheAccess`，后端只需要默认的 `CacheAccess.SYNC`。`BackendProtocolSpec` 直接调用存储，没有 `access`。阻塞、[异步](../guide/async.md)与挂起三种 API 的行为（取消、future 在哪个线程上完成、等待不占线程等）属于核心，由 CoCache 自己的测试覆盖。CoCache 覆盖 `access`，经异步视图（`CacheAccess.ASYNC`）重跑这些套件；你也想在自己的后端上得到这部分覆盖时，可以同样覆盖它。

Java 后端对 `FutureL1Store.adapt` 返回的存储运行同样的套件；CoCache 也这样在内存后端上运行整套 TCK。

### 声明能力 {#expected-capabilities}

每个套件都有抽象的 `expectedCapabilities: Set<L1Capability>`，没有默认值：没有声明它的子类编译失败。声明你的存储提供的能力：

```kotlin
override val expectedCapabilities = setOf(L1Capability.CONDITIONAL, L1Capability.VERSIONED, L1Capability.SETS)
```

```java
@Override
protected Set<L1Capability> getExpectedCapabilities() {
    return EnumSet.of(L1Capability.CONDITIONAL, L1Capability.SETS);
}
```

- **TCK fail closed**。每个套件的 `storeProvidesExactlyTheDeclaredCapabilities` 在以下情形失败：存储提供了你没有声明的能力、缺少你声明的能力、访问器再次调用时返回另一个对象，或在 `null` 与能力面之间切换。它检查两个新的命名空间。
- **只有没有声明的能力才跳过用例**。`EngineSpec` 的 `Store` 用例需要 `CONDITIONAL`；`BackendProtocolSpec` 的集合用例与 `SetEngineSpec` 的全部用例需要 `SETS`；`VersionedSpec` 的全部用例需要 `VERSIONED`。声明了的能力必须通过它的用例，包括只有原子实现才能通过的并发用例。
- `L1Capability` 只存在于 TCK 中。在 SPI 里，访问器是存储表达能力的唯一方式。

### 接入套件 {#tck-hooks}

套件会用 `HookedBackend` 包装你的后端。它可以注入故障（`failAll`、`failWrites`、`failFill`、`failRelease`，以及在写入到达你的存储之前抛出 `L1RejectedException` 的 `rejectWrites`；`deferPuts` 让单 key put 以结果未知的故障失败并暂不执行，直到 `applyDeferredPuts()` 执行它们）、改写读取结果、在读取与 fill 之后以及写入前后执行钩子（从而用 latch 而不是 sleep 编排竞态；`afterPut` 或 `afterInvalidate` 抛出即模拟已生效却报错的写入）、为调用计数（`reads`、`readAlls`、`fills`、`puts`、`putAlls`），还能覆盖 `atomicBroadcast` 来验证非原子路径。你自己的测试也可以用它。

`elapse(duration)` 必须让你的存储与引擎的时间同时前进：

- **进程内后端**：推进后端与 `clock` 共用的 `ManualClock`。
- **真实服务端**：为引擎推进 `ManualClock`，同时在服务端把测试 key 的剩余 TTL 缩短 `duration`。`BackendProtocolSpec` 中的短时长也可以直接 sleep：令牌签发时间取自服务端时钟。

`simulateReconnect()` 必须断开订阅，让通道重新订阅，例如在服务端杀掉它的连接。

可选的钩子默认返回 `null`（或 `false`），对应的用例跳过：

- **`BackendProtocolSpec.pause()`** 与 **`EngineSpec.pause()`** 让后端的命令停住，直到关闭返回值（或持续一段固定的时间，至少是用例读取时限的 10 倍）：期间开始的命令不完成。CoCache 的 Redis 测试让后端经过一个暂存客户端数据的本机 TCP 代理；`InMemoryBackend.stall()` 在进程内做同样的事。
- **`EngineSpec.writeForeign(storageKey, raw)`** 不广播地写入格式低于你的格式的 `raw`（格式升级之前或其它程序写入的内容就是这样），并返回 `true`。读取必须把它返回为 `Envelope.Scalar(raw)`，并报告那个更低的格式。用例检查：有数据源的读取回收它并回源；暂停在它上面的读取不会回收之后写入的、字节相同的值。CoCache 的 Redis 测试直接 `SET` 原样的字节（格式 `0`）；`InMemoryBackend.writeForeign` 在进程内做同样的事。
- **`FaultSpec.disconnect()`** 断开命令连接，并保持断开直到关闭返回值。期间发出的命令必须失败，而不是被缓冲。对真实服务端，关闭监听端口的代理就可以。
- **`FaultSpec.commandCancellation()`** 返回一个 `CommandCancellation`：`hold()` 让之后的命令停在客户端中，`cancel()` 让客户端取消它们，`close()` 恢复正常（可以需要重连）。用例随后检查：命令被取消的读取回退到 loader，并把结果交给等在它上面的读取方；`put` 以 `CacheWriteException` 失败；没有调用方看到 `CancellationException`；L1 恢复之后，被取消的读取的 lease 已不存在。CoCache 的内存后端停住、再取消它的 `FutureL1Store` 返回的 future；Redis 测试在代理处丢弃应答，让 Lettuce 重置连接（取消在途的命令），再重连。

内存后端的 TCK 类就是一个最小模板：

```kotlin
private val CAPABILITIES = setOf(L1Capability.CONDITIONAL, L1Capability.VERSIONED, L1Capability.SETS)

class MyBackendProtocolTest : BackendProtocolSpec() {
    override val expectedCapabilities = CAPABILITIES
    private val clock = ManualClock()
    override fun createBackend(): CoherenceBackend = MyBackend(clock)
    override fun elapse(duration: Duration) = clock.advance(duration)
}

class MyEngineTest : EngineSpec() {
    override val expectedCapabilities = CAPABILITIES
    private val manualClock = ManualClock()
    private lateinit var backend: MyBackend
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock).also { backend = it }
    override fun elapse(duration: Duration) = manualClock.advance(duration)
    override fun simulateReconnect() = backend.dropSubscription()
}

class MySetEngineTest : SetEngineSpec() {
    override val expectedCapabilities = CAPABILITIES
    private val manualClock = ManualClock()
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock)
    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

// MultiNodeSpec 与 VersionedSpec 的成员与 SetEngineSpec 相同
class MyFaultTest : FaultSpec() {
    override val expectedCapabilities = CAPABILITIES
    private val manualClock = ManualClock()
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock)
    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
```

每个用例经 `createBackend()` 新建后端。你的后端会打开连接或线程时，请把 `closesBackend` 覆盖为 `true`，套件会在每个用例结束后关闭它；默认为 `false`，因为后端可能是共享的。`BackendProtocolSpec` 没有 `closesBackend`，总是关闭自己创建的后端。

用构建工具的常规测试任务运行（JUnit 6）。`cocache-redis` 把 `BackendProtocolSpec`、`EngineSpec`、`MultiNodeSpec`、`FaultSpec` 与 `VersionedSpec` 在三种模式下各跑一遍：经典 Pub/Sub、非原子（用 ACL 禁止探测频道）与 sharded；`SetEngineSpec` 只在经典 Pub/Sub 下运行，异步视图在经典 Pub/Sub 上把每个引擎套件重跑一遍。`FaultSpec` 的断线与取消用例在单机经典 Pub/Sub 上运行，`cocache-redis` 另有清空脚本缓存之后 `NOSCRIPT` 回退的用例。你的后端支持几种模式，就对每种模式都跑这些套件。

## 编解码器 {#codecs}

```kotlin
interface Codec<V> {
    fun encode(value: V): ByteArray
    fun decode(bytes: ByteArray): V
}
```

- **`decode` 遇到读不了的字节时抛出异常**。有数据源的读取随后按损坏处理：未命中、条件回收（从不回收带摘要的值）、重新加载。没有数据源的读取以 `CacheReadException` 失败。不要返回默认对象。
- **线程安全、无状态**。一个实例服务所有线程。
- **从不处理 `null`**。负缓存由引擎处理。
- **集合元素的编解码器必须是规范的**。`SetStore` 的成员按编码后的字节比较，所以相等的元素必须总是编码成相同的字节。字段顺序不稳定的 JSON 会破坏这一点。
- **字节格式是该缓存线格式的一部分**。在运行中的集群里改变它，会让新旧实例互相回收对方的值。不兼容的变更请使用新的命名空间或 key 前缀，或者在滚动期间让 `decode` 同时接受两种形式。
- **保持载荷小**。所有命令共用一条连接，一个大值会拖慢排在它后面的命令。

`StringCodec`（UTF-8）在 `cocache-core` 中，`JacksonCodec`（`jacksonCodec<V>(mapper)`）在 `cocache-codec-jackson` 中。没有编解码器 bean 的接口式缓存使用应用的 `ObjectMapper` 构造 `JacksonCodec`。`JacksonCodec` 按声明的类型写入与读取（`mapper.writerFor(type)`）：元素多态（`@JsonTypeInfo`）的 `List<Animal>` 保留元素的类型 id，读得回来；子类的值按声明的类型写入。

## 函数 {#functions}

| 函数 | 约定 |
|------|------|
| `CacheLoader<K, V>` | `load(key)` 返回值，或用 `null` 表示“不存在”（以负缓存存储 `absent-ttl`）。`loadAll(keys)` 批量加载，结果中缺少的 key 视为不存在。默认逐个调用 `load`，请覆盖为一次查询。在调用方线程上执行。不能读取自己缓存中的同一个 key（会快速失败）。必须读主库，而不是滞后的从库 |
| `KeyEncoder<K>` | 把 key 转换为 `{keyPrefix}{namespace}:` 之后的字符串。必须**单射**（不同的 key 得到不同的字符串）、确定，并且在每个实例与每个版本上都相同。输出必须是合法的 UTF-16：存储 key 与失效帧都按 UTF-8 编码，含未配对代理的 key 在到达 L1 之前以 `IllegalArgumentException` 失败（见 [key](../guide/caches.md#keys)）。默认是 `toString()`，它会拒绝没有重写 `toString()` 的类型 |
| `AsyncCacheLoader<K, V>` | `CacheLoader` 的异步形式：`loadAsync(key)` 返回 `CompletionStage`，以 `null` 完成表示“不存在”。必须很快返回、不阻塞：它在调用方线程或 CoCache 的线程上执行，看不到调用方的 ThreadLocal。受 `load-timeout` 约束。见[异步 API](../guide/async.md#loader) |
| `KeyFilter` | `notExist(storageKey)` 收到的是完整的存储 key。只有确定不存在的 key 才能返回 `true`：布隆过滤器必须包含所有存在的 key。返回 `true` 时跳过 Redis 与 loader，不缓存任何内容。必须是本地、不阻塞的判断：异步调用方在自己的线程上调用它，那可能是事件循环 |
| `TtlFunction<K, V>`（`ttlOf`） | 按条目决定的寿命。非 null 的结果替代 `ttl` 且不加抖动。收敛到 1 ms 与 100 年之间。不作用于负缓存 |

## 不应定制的部分 {#not-pluggable}

- **L2**。它始终是有界的 Caffeine 缓存，过期在读取时与 ticking clock 比较。可插拔的 L2 会让命中路径变成多态，并可能让两个缓存误共享同一个 L2。用 `local-maximum-size` 调整它。
- **栅栏与写入顺序**。lease、刷新令牌、失效戳、两次递增、只从 L1 填充 L2，这些就是协议本身。后端实现 L1 的一侧，其余由引擎负责。
- **存储中的策略**。抖动、负缓存寿命与刷新窗口属于引擎，存储只看到传给它的时长。
- **大版本内的 Redis 线格式**。其它代码可以为诊断读取 CoCache 的 key，但只能通过[失效协议](../operations/troubleshooting.md#external-invalidation)写入。
- **缓存之间共享状态**。每个有状态组件都由 `CacheRuntime.create` 按缓存创建。传入 spec 与无状态函数，绝不传入 L2 实例、存储或失效戳。
