---
title: 扩展 CoCache
description: 实现 CoherenceBackend（RemoteStore 与 InvalidationChannel）或 Codec：SPI 的精确约定、cocache-test 的 TCK 套件及其运行方式、编解码器规则，以及不应定制的部分。
---

# 扩展 CoCache

| 你想要 | 实现 | 验证 |
|--------|------|------|
| 把 L1 存到 Redis 以外的地方，或者以不同方式访问 Redis | [`CoherenceBackend`](#backend) | [`BackendProtocolSpec`、`EngineSpec`、`SetEngineSpec`、`MultiNodeSpec`、`FaultSpec`](#tck) |
| 改变值转换为字节的方式 | [`Codec<V>`](#codecs) | 自己的往返测试 |
| 加载、编码 key、拒绝不可能存在的 key、按条目决定寿命 | [函数](#functions)：`CacheLoader`、`KeyEncoder`、`KeyFilter`、`TtlFunction` | 自己的测试 |

SPI 位于 `me.ahoo.cocache.api.spi`（`cocache-api`，零依赖）。后端运行时只需要 `cocache-api`，测试时需要 `cocache-test`。

在 Spring Boot 应用中，声明一个 `CoherenceBackend` bean 即可替换 Redis 后端（starter 会退让），Spring 会在关闭时关闭它。值类型为 `V` 的每个接口式缓存都会使用唯一的 `Codec<V>` bean，除非该缓存用 `@CoCache(codec = "beanName")` 指定了别的。

## 后端 {#backend}

```kotlin
interface CoherenceBackend : AutoCloseable {
    val atomicBroadcast: Boolean               // 写入能否在同一个原子步骤中发布它的帧？
    val format: Int                            // 本实现写入的线格式版本
    val channel: InvalidationChannel
    fun store(namespace: String): RemoteStore  // 每个缓存调用一次
}
```

L1 存储与失效通道是同一个对象的两个面：串行化写入的一方也应该发出失效。

- **`atomicBroadcast`**。为 `true` 时，存储的每个写方法都必须在修改的同一个原子步骤中发布它的 `frame`。为 `false` 时，引擎在每次写入成功之后调用 `channel.publish(namespace, frame)`。原子更好：写入与发布之间的崩溃会丢失消息。只声明你能保证的能力，TCK 会分别检查两种情况。
- **`format`**。引擎把它与 `RemoteRead.Hit.format` 比较：存储的格式更高时绕开且从不覆盖；`0` 表示不是你的协议写入的内容，引擎按损坏处理。从 `1` 开始，只能通过两阶段升级递增。

### `RemoteStore` {#remote-store}

```kotlin
interface RemoteStore : AutoCloseable {
    fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead
    fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead>
    fun fill(fill: Fill): Boolean
    fun fillAll(fills: List<Fill>): List<Boolean>
    fun release(key: String, token: Token)
    fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray)
    fun putAll(puts: List<Put>, frame: ByteArray)   // 默认：逐项调用 put
    fun invalidate(keys: Collection<String>, frame: ByteArray)
    fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean
    fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray)
    fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray)
}
```

传入的 key 是完整的，已包含前缀与命名空间。存储只处理字节与令牌，不持有任何策略：每个 TTL、窗口与令牌都是参数。涉及的类型：

| 类型 | 形状 |
|------|------|
| `Envelope` | `Scalar(bytes)`、`Members(字节列表)` 或 `Absent`（负缓存） |
| `RemoteRead` | `Hit(envelope, format, ttl, refreshGranted)`、`Miss(leaseGranted)` 或 `Leased(remaining)`。`ttl == null` 表示没有到期时间 |
| `Token` | 24 字节：`NodeId`（16）+ 序号（8）。唯一、永不重复。按字节比较 |
| `LeaseRequest(token, ttl)` | 未命中时放置的 lease，`ttl` 后到期 |
| `RefreshRequest(window, token, tokenTtl)` | 值在 `window` 内时放置的刷新令牌。签发早于 `tokenTtl` 的令牌可以被接管 |
| `Fill(key, token, envelope, ttl)` | 仅当 `key` 持有 `token` 时，以 `ttl` 写入 `envelope` |
| `Put(key, envelope, ttl)` | `putAll` 中的一项：按 `put` 覆盖 `key` |
| `Digest` | 内容的 SHA-1：标量 → 载荷字节；集合 → 按返回顺序把每个成员写成 `int32 长度 + 字节`；负缓存 → 空 |

逐个方法的约定。状态机见[一致性](./consistency.md#l1-fence)。

| 方法 | 必须 |
|------|------|
| `read` | key 不存在且 `lease != null`：放置 lease（`lease.ttl` 后到期），返回 `Miss(true)`。不存在且没有 lease：返回 `Miss(false)`，不写入任何内容。处于 lease 状态：返回 `Leased(remaining)`。有值：返回带剩余 TTL 的 `Hit`。标量值的剩余 TTL `> 0` 且 `≤ refresh.window`，并且没有令牌或令牌签发早于 `refresh.tokenTtl`：写入刷新令牌（保留 TTL），返回 `refreshGranted = true`。永不过期的值不授予令牌。不是你写入的内容：返回 `Hit(…, format = 0)`。两个请求都为 `null` 时，`read` 是纯读取，不写入任何内容 |
| `readAll` | 对每个 key 执行 `read`，只为 `leases` 中的 key 申请 lease。返回全部 key。任一 key 失败时，先释放本次调用中已经授予的 lease（否则所有实例都要等待 `lease-ttl`），再抛出 |
| `fill` / `fillAll` | 仅当 key 当前持有的正是 `fill.token`（作为 lease 或刷新令牌）时写入，并把到期时间重设为 `fill.ttl`。返回是否写入 |
| `release` | 令牌与 lease 匹配：删除 key。令牌与值上的刷新令牌匹配：清除令牌，保留值及其 TTL。否则什么也不做 |
| `put` | 覆盖任何状态，销毁任何令牌，设置到期时间，广播 `frame`。`Members` 会替换整个集合 |
| `putAll` | 对每一项执行 `put`，对每个 key 原子，不要求跨 key 原子。`frame` 携带全部 key：每个 key 恰好随其写入广播一次（可以拆分帧，例如按 slot 分组）。可选实现：默认逐项调用 `put`，每次只广播该 key |
| `invalidate` | 删除每个 key，销毁任何令牌，广播 `frame`。对每个 key 原子。批次可以拆分 |
| `reclaim` | 仅当 key 当前内容的摘要等于 `observed` 且不是 lease 时，把它替换为 lease。返回是否替换。绝不删除已经变化的内容 |
| `addMembers` | key 是集合：原子地加入成员。否则（不存在、负缓存、lease、标量）：替换为只含这些成员的集合。销毁任何令牌，设置到期时间（`null` 表示去掉到期时间），广播 |
| `removeMembers` | 仅当 key 是集合时：移除成员，集合变空时删除 key，否则重设到期时间。任何情况下都广播 |

对所有方法：

- **对每个 key 原子**。批量方法对每个 key 原子，不要求跨 key 原子。
- **每次状态转换都重设到期时间**，精度为毫秒。lease 或负缓存的寿命绝不能泄漏到新值上。
- **读取时从不删除。**
- **失败时抛出异常**。不要吞掉错误或在内部重试：降级、重试还是重新抛出由引擎决定，并由引擎计数。
- **断线时拒绝命令**，而不是缓冲后重放。

### `InvalidationChannel` {#invalidation-channel}

```kotlin
interface InvalidationChannel {
    fun subscribe(namespace: String, listener: InvalidationListener): Subscription
    fun publish(namespace: String, frame: ByteArray)   // 仅在 atomicBroadcast == false 时被调用
}
interface InvalidationListener {
    fun onInvalidated(keys: List<String>, origin: NodeId)
    fun onReset()
}
fun interface Subscription : AutoCloseable
```

- **帧**。`frame` 由引擎用 `InvalidationFrame.encode(origin, storageKeys)` 构造。用 `InvalidationFrame.decode` 解码后投递给该命名空间的每个订阅者，丢弃畸形帧。不要按来源过滤：订阅者会忽略自己的 `NodeId`。
- **至多一次可以接受，沉默不行**。每次订阅建立或重新建立**并得到确认**时，以及任何消息可能丢失的时候（心跳失败、消费组重平衡、序号出现空洞），都要对该订阅的每个订阅者调用 `onReset()`。订阅者随后清空它的 L2。没有重置，丢失的消息会让 L2 陈旧到 `local-max-age`。
- **在 `subscribe` 返回之前完成重置**。第一个订阅者的重置必须在 `subscribe` 返回时已经执行，否则迟到的重置会清掉订阅之后才填充的副本。已有订阅上的后续订阅者立即收到一次 `onReset()`。
- **隔离**。订阅新的命名空间不能重置其它命名空间。某个订阅者抛出异常，不能让其它订阅者错过消息。
- **失败**。`subscribe` 失败时抛出异常，不留下任何部分注册：运行时会回滚这个缓存。
- 关闭 `Subscription` 即取消该订阅者的订阅。

## TCK {#tck}

`cocache-test` 是已发布的 TCK，每个后端都必须通过。

```kotlin
testImplementation("me.ahoo.cocache:cocache-test")
```

| 套件 | 覆盖 | 你提供 |
|------|------|--------|
| `BackendProtocolSpec` | `RemoteStore` 约定：授予 lease、令牌校验、到期时间重设、释放、刷新令牌、回收、批量读写、集合更新、广播帧、订阅时重置 | `createBackend()`、`elapse(duration)`。可选覆盖 `shortTtl`（默认 300 ms） |
| `EngineSpec` | 两个运行时共用你的后端：跨实例单次加载、等待 lease 超时、加载期间的失效、同实例失效与读取的竞态、并发 `put`、读己之写、loader 失败、Redis 失败、失效重试、重连重置、`local-max-age`、批量读、提前刷新、回收、更新的格式、回源并发上限 | `createBackend()`、`clock`、`elapse(duration)`、`simulateReconnect()` |
| `SetEngineSpec` | 两个运行时上的 `SetCache`：原子的并发加入、空集合删除、拒绝 `invalidate`、写入与读取失败 | `createBackend()`、`clock`、`elapse(duration)` |
| `MultiNodeSpec` | 三个运行时共用你的后端：失效先于 lease、晚于 lease 且另一实例正在等待、晚于 fill 而早于写 L2；其它实例的写入落在读取与 L2 填充之间；不同实例的并发 `put`（非原子后端上事件逆序到达）；失效重试期间的未命中；全部实例中每个 key 只回源一次、只刷新一次 | `createBackend()`、`clock`、`elapse(duration)` |
| `FaultSpec` | L1 故障下的后端：存储失败期间副本以 `degraded-local-max-age` 限龄、fill 失败及其 lease、放弃或溢出队列的失效重试、已生效却报错且可安全重试的 `put` 与 `invalidate`，以及（可选）断线期间被拒绝的写入在重连后不被重放 | `createBackend()`、`clock`、`elapse(duration)`；可选 `disconnect()` |
| `AsyncEngineSpec`、`AsyncSetEngineSpec`、`AsyncMultiNodeSpec`、`AsyncFaultSpec` | 对应套件的每个用例经[异步视图](../guide/async.md)重跑；`AsyncEngineSpec` 另有异步专有的用例：取消、future 在哪个线程上完成、等待 lease 与回源许可不占线程、原始异常、不同步抛出、`load-async-timeout`、同步与异步调用合并为一次加载、重入 | 与对应套件相同的成员 |
| `AsyncBackendProtocolSpec` | `BackendProtocolSpec` 的每个用例经 `AsyncRemoteStore` 的方法重跑（不同步抛出、原始异常），另加同一 key 上同步与异步调用的一致性 | 与 `BackendProtocolSpec` 相同的成员。只用于实现了 `AsyncRemoteStore` 的后端 |

套件会用 `HookedBackend` 包装你的后端。它可以注入故障（`failAll`、`failWrites`、`failFill`、`failRelease`）、改写读取结果、在读取与 fill 之后以及写入前后执行钩子（从而用 latch 而不是 sleep 编排竞态；`afterPut` 或 `afterInvalidate` 抛出即模拟已生效却报错的写入），还能覆盖 `atomicBroadcast` 来验证非原子路径。你自己的测试也可以用它。

`elapse(duration)` 必须让你的存储与引擎的时间同时前进：

- **进程内后端**：推进后端与 `clock` 共用的 `ManualClock`。
- **真实服务端**：为引擎推进 `ManualClock`，同时在服务端把测试 key 的剩余 TTL 缩短 `duration`。`BackendProtocolSpec` 中的短时长也可以直接 sleep：令牌签发时间取自服务端时钟。

`simulateReconnect()` 必须断开订阅，让通道重新订阅，例如在服务端杀掉它的连接。

`FaultSpec` 的 `disconnect()` 是可选的。后端持有连接时，覆盖它以断开命令连接，并保持断开直到关闭返回值（CoCache 的 Redis 测试让后端经过一个本机 TCP 代理，断线时关闭代理的监听端口）。期间发出的命令必须失败，而不是被缓冲。默认返回 `null`，断线用例跳过。

内存后端的 TCK 类就是一个最小模板：

```kotlin
class MyBackendProtocolTest : BackendProtocolSpec() {
    private val clock = ManualClock()
    override fun createBackend(): CoherenceBackend = MyBackend(clock)
    override fun elapse(duration: Duration) = clock.advance(duration)
}

class MyEngineTest : EngineSpec() {
    private val manualClock = ManualClock()
    private lateinit var backend: MyBackend
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock).also { backend = it }
    override fun elapse(duration: Duration) = manualClock.advance(duration)
    override fun simulateReconnect() = backend.dropSubscription()
}

class MySetEngineTest : SetEngineSpec() {
    private val manualClock = ManualClock()
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock)
    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

// MultiNodeSpec 的成员与 SetEngineSpec 相同
class MyFaultTest : FaultSpec() {
    private val manualClock = ManualClock()
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock)
    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
```

每个用例经 `createBackend()` 新建后端。你的后端会打开连接或线程时，请把 `closesBackend` 覆盖为 `true`，套件会在每个用例结束后关闭它；默认为 `false`，因为后端可能是共享的。

用构建工具的常规测试任务运行（JUnit 6）。`cocache-redis` 把 `BackendProtocolSpec`、`EngineSpec`、`MultiNodeSpec` 与 `FaultSpec` 在三种模式下各跑一遍：经典 Pub/Sub、非原子（用 ACL 禁止探测频道）与 sharded；`SetEngineSpec` 只在经典 Pub/Sub 下运行。`FaultSpec` 的断线用例在单机经典 Pub/Sub 上运行，`cocache-redis` 另有清空脚本缓存之后 `NOSCRIPT` 回退的用例。你的后端支持几种模式，就对每种模式都跑这些套件。

## 异步后端 {#async}

只实现 `RemoteStore` 的后端不需要改动就能配合[异步视图](../guide/async.md)：引擎在阻塞执行器上调用它的同步方法。要不占这些线程地服务异步调用方，请实现可选的子接口：

- `AsyncRemoteStore extends RemoteStore` 增加 `readAsync`、`readAllAsync`、`fillAsync`、`fillAllAsync`、`releaseAsync`、`putAsync`、`putAllAsync`、`invalidateAsync`、`reclaimAsync`、`addMembersAsync` 与 `removeMembersAsync`，语义与对应的同步方法完全相同。
- `AsyncInvalidationChannel extends InvalidationChannel` 增加 `publishAsync`，引擎在非原子后端上使用它。
- **从不同步抛出。** 以失败的 future 代替，异常类型与同步方法相同，不是 `CompletionException`。
- **每个操作都要有上限。** 永不完成的 future 会让该实例上的这个 key 一直卡到 `load-async-timeout`。
- **可以在任何线程上完成。** 引擎在执行引擎代码或用户代码之前会切换到自己的执行器，所以在 I/O 线程上完成没有问题；但完成时的工作要轻，不要在那里调用编解码器或用户代码。

然后运行 `AsyncBackendProtocolSpec` 与四个异步引擎套件。

`InMemoryBackend` 在一个进程内实现了完整的协议。多个运行时共用一个实例就像多个应用实例，因此它也很适合作为应用测试的后端。

## 编解码器 {#codecs}

```kotlin
interface Codec<V> {
    fun encode(value: V): ByteArray
    fun decode(bytes: ByteArray): V
}
```

- **`decode` 遇到读不了的字节时抛出异常**。引擎随后按损坏处理：未命中、条件回收、重新加载。不要返回默认对象。
- **线程安全、无状态**。一个实例服务所有线程。
- **从不处理 `null`**。负缓存由引擎处理。
- **集合元素的编解码器必须是规范的**。`SetCache` 的成员按编码后的字节比较，所以相等的元素必须总是编码成相同的字节。字段顺序不稳定的 JSON 会破坏这一点。
- **字节格式是该缓存线格式的一部分**。在运行中的集群里改变它，会让新旧实例互相回收对方的值。不兼容的变更请使用新的命名空间或 key 前缀，或者在滚动期间让 `decode` 同时接受两种形式。
- **保持载荷小**。所有命令共用一条连接，一个大值会拖慢排在它后面的命令。

`StringCodec`（UTF-8）在 `cocache-core` 中，`JacksonCodec`（`jacksonCodec<V>(mapper)`）在 `cocache-codec-jackson` 中。没有编解码器 bean 的接口式缓存使用应用的 `ObjectMapper` 构造 `JacksonCodec`。

## 函数 {#functions}

| 函数 | 约定 |
|------|------|
| `CacheLoader<K, V>` | `load(key)` 返回值，或用 `null` 表示“不存在”（以负缓存存储 `absent-ttl`）。`loadAll(keys)` 批量加载，结果中缺少的 key 视为不存在。默认逐个调用 `load`，请覆盖为一次查询。在调用方线程上执行。不能读取自己缓存中的同一个 key（会快速失败）。必须读主库，而不是滞后的从库 |
| `KeyEncoder<K>` | 把 key 转换为 `{keyPrefix}{namespace}:` 之后的字符串。必须**单射**（不同的 key 得到不同的字符串）、确定，并且在每个实例与每个版本上都相同。默认是 `toString()`，它会拒绝没有重写 `toString()` 的类型 |
| `AsyncCacheLoader<K, V>` | `CacheLoader` 的异步形式：`loadAsync(key)` 返回 `CompletionStage`，以 `null` 完成表示“不存在”。必须很快返回、不阻塞：它在调用方线程或异步执行器上执行，看不到调用方的 ThreadLocal。受 `load-async-timeout` 约束。见[异步 API](../guide/async.md#loader) |
| `KeyFilter` | `notExist(storageKey)` 收到的是完整的存储 key。只有确定不存在的 key 才能返回 `true`：布隆过滤器必须包含所有存在的 key。返回 `true` 时跳过 Redis 与 loader，不缓存任何内容。必须是本地、不阻塞的判断：异步调用方在自己的线程上调用它，那可能是事件循环 |
| `TtlFunction<K, V>`（`ttlOf`） | 按条目决定的寿命。非 null 的结果替代 `ttl` 且不加抖动。收敛到 1 ms 与 100 年之间。不作用于负缓存 |

## 不应定制的部分 {#not-pluggable}

- **L2**。它始终是有界的 Caffeine 缓存，过期在读取时与 ticking clock 比较。可插拔的 L2 会让命中路径变成多态，并可能让两个缓存误共享同一个 L2。用 `local-maximum-size` 调整它。
- **栅栏与写入顺序**。lease、刷新令牌、失效戳、两次递增、只从 L1 填充 L2，这些就是协议本身。后端实现 L1 的一侧，其余由引擎负责。
- **存储中的策略**。抖动、负缓存寿命与刷新窗口属于引擎，存储只看到传给它的时长。
- **大版本内的 Redis 线格式**。其它代码可以为诊断读取 CoCache 的 key，但只能通过[失效协议](../operations/troubleshooting.md#external-invalidation)写入。
- **缓存之间共享状态**。每个有状态组件都由 `CacheRuntime.create` 按缓存创建。传入 spec 与无状态函数，绝不传入 L2 实例、存储或失效戳。
