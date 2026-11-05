# CoCache 架构与不变量

本文是 CoCache 8.x 的**规范**：设计目标、类型、模块边界、线格式、读写与失败路径、栅栏、配置、Spring 集成、检验方法，以及必须保持的不变量（§15）。它只写现行行为：版本之间的变化见 [changelog](../wiki/guide/changelog.md)；设计推导、被否决的方案与对抗验证记录见 RFC，核心协议（lease 栅栏、失效戳）的推导在 [RFC 0003](rfc/0003-target-architecture.md)，8.x 的设计在 [RFC 0005](rfc/0005-v8-target-architecture.md)。RFC 是设计记录，不是现行规范：与本文不一致时以本文为准。

修改一致性、存储、编解码、代理或 Spring 装配相关代码前先读本文；行为变化时在同一个 PR 中更新本文。本文与代码不一致时以代码为准，并修正本文；SPI 与线格式的定义以 `me.ahoo.cocache.api.spi` 与 `me.ahoo.cocache.redis.RedisScripts` 为准。

- 不变量有稳定编号 `I-n`（§15），代码注释、测试与文档按编号引用（如 `I-6`、`I-K`）。
- 文中 K14、D6、S8、P12、A.L4 一类的编号是 RFC 0005 中的审查发现与反例（RFC 0005 §12 与附录），供追溯设计理由；F1–F4 是 TLA+ 模型的发现（[docs/spec](spec/README.md)）。

## 1. 目标

二级一致性缓存的本质是“用陈旧度换延迟”。缓存只做三件事：**复制**（填充）、**作废**（失效）、**限龄**（TTL）。所有正确性问题都出在一次开始得早的复制在作废之后才落地。

| # | 目标 | 可度量的表述 |
|---|------|--------------|
| G1 | 陈旧度有上界 | **L1**：协议内竞态造成的陈旧为 0；协议外（绕过缓存的写入、故障切换丢写、loader 读到从库旧数据）≤ `ttl`。**L2**：事件送达时 ≤ 事件延迟；事件丢失时 ≤ `min(localMaxAge, 心跳周期 + 重连时长)`；失效写 L1 失败且重试放弃（或运行时关闭时仍在排队）时 ≤ L1 剩余寿命（各节点 L2 到期后会从仍是旧值的 L1 重新填充；`ttl = null` 时没有上界，计入 `invalidations.dropped` 并告警，需再次失效）；降级填充 ≤ `degradedLocalMaxAge` |
| G2 | 失效不被吞没 | **L1**：读取开始于某次失效之前的填充，永远不会在该失效之后出现在 L1。**L2**：某节点处理完一次失效（本地调用返回、处理了远端事件或执行了重置）之后，该节点 L2 不会再出现读取开始于这次失效之前的填充 |
| G3 | 命中路径便宜 | L2 命中只是一次 Caffeine 查找加一次过期比较（开启 L2 命中计数时再加一次 `LongAdder` 自增），随线程扩展；L1 命中一次往返；单 key 的 `put` / `invalidate` 默认（原子广播）各一次往返，`sharded` 或非原子模式下各两次往返；loader 耗时小于 `lease.wait` 时，全集群同一 key 并发未命中只回源一次 |

非目标：线性一致读。异步与挂起 API 是同一个引擎上的视图（§5.6、§5.7，[RFC 0004](rfc/0004-async-api.md)），不是另一套协议；阻塞栈上同步 API 配合虚拟线程仍是首选。权威副本（`Store`、`VersionedStore`、`SetStore`）把 L1 当作唯一副本（§2），是有意的例外：它们的陈旧上界只剩 L2 的限龄。

TTL 是安全网，不是机制：它兜底协议之外的问题。协议之内的竞态由栅栏（§8）消除，不靠 TTL 收敛，所以 `ttl = FOREVER` 在协议内是安全的。

## 2. 类型：派生副本与权威副本

### 2.1 用户 API

**选择类型只问一个问题**（RFC 0005 §4.1）：L1 丢了这个 key，本进程能否按需从别处重建它？能，则是派生副本 `Cache` / `VersionedCache`；不能，则是权威副本 `Store` / `VersionedStore` / `SetStore`。同一个 namespace 的所有声明必须是同一类型；类型不在线格式中，`Store` 的摘要段是唯一的协议层标记（派生读者读到它时计入 `typeMismatches`，§5.4）。

| 类型 | 副本 | 操作（另有 `ReadableCache` 的 `name`、`get`、`getAll`、`lookup`、`lookupAll`、`async()`、`suspending()`） | spec |
|------|------|------|------|
| `Cache<K, V>` | 派生 | `get(key, loader)`、`put` / `putAll`（可带 `ttl`）、`invalidate` / `invalidateAll` | `CacheSpec`：`loader`、`asyncLoader` 或 `externallySourced()` 恰好一个 |
| `VersionedCache<K, V>` | 派生，带版本 | `getVersioned`、`putIfNewer`、`invalidateIfNewer`（可带 `ttl`）、`evict`、`purge` | `VersionedCacheSpec`：`versionedLoader` 或 `versionedAsyncLoader` |
| `Store<K, V>` | 权威 | `put` / `putAll`（可带 `ttl`）、`delete`、`deleteIfMatch`、`getIfChanged`、`putIfMatch`（可带 `ttl`） | `StoreSpec`（没有数据源） |
| `VersionedStore<K, V>` | 权威，带版本 | `getVersioned`、`putIfNewer`、`deleteIfNewer`（可带 `ttl`）、`purge` | `VersionedStoreSpec` |
| `SetStore<K, E>` | 权威集合（`ReadableCache<K, Set<E>>`） | `add`、`remove`、`clear`、`put` / `putAll`（整体覆盖，可带 `ttl`） | `SetStoreSpec` |

- **全部是抽象方法**，没有默认抛出的方法：类型没有的操作在编译期不可调用（RFC 0005 V3）。带版本的类型不继承无版本的写入。`runtime.create(spec)` 返回与 spec 对应的类型，并检查类型需要的 L1 能力（§3.3）。
- **不供用户实现，委托除外**（U13，S21）：API 只以新的能力类型、或用已有抽象方法实现的默认方法演进，从不给已有类型加抽象方法。继承这些接口、由代理实现的声明接口（`@CoCache` 接口）不受影响。
- **三种视图同构**：`async()` 返回 `AsyncReadableCache` 一族（`AsyncCache`、`AsyncVersionedCache`、`AsyncStore`、`AsyncVersionedStore`、`AsyncSetStore`），`suspending()` 返回 `SuspendingReadableCache` 一族；方法名与同步 API 相同，每次返回同一个对象，`async().sync()` 是引擎本身，`suspending().async()` 是异步视图（§5.6、§5.7）。
- `Lookup<V>` = `Present(value, expiresAt)` | `Absent(expiresAt)` | `Unknown(error)`：`expiresAt` 是返回的这份副本的到期时间（命中 L2 时是 L2 副本的时间）；`Unknown` 只表示这一次读取没有答案（L1 故障，§5.1、§7），`error` 是 `get` 会抛出的 `CacheReadException`。`valueOrThrow()` 供不便穷尽匹配的调用方使用（JDK 21 的 Java 调用方对密封类型穷尽 `switch`）。
- 没有三态 `CacheValue?`，没有绝对时间 `ttlAt` 入参，不能显式写入负缓存：负缓存只在 loader 返回 `null`（或 `loadAll` 结果缺少该 key）时由引擎写入。
- API 与 SPI 的时间类型一律是 `java.time.Duration` / `Instant`；API 与 SPI 中不使用 `value class` 与 `kotlin.time.Duration`（JVM 名称改写会让 Java 调用方和第三方实现无法使用）。
- `CacheLoader<K, V>` 是 `fun interface`：`load(key): V?`，`loadAll` 默认逐个调用 `load`。数据源只负责加载，寿命由策略与 spec 的 `ttlOf` 决定。`AsyncCacheLoader<K, V>`：`loadAsync(key): CompletionStage<out V?>`，`loadAllAsync` 默认逐个调用并合并；引擎在两个方向上适配（§5.6）。挂起的数据源 `SuspendingCacheLoader` 在 core 中（它的默认 `loadAll` 并发调用 `load`，需要 kotlinx），经 `suspendingLoader` / `suspendingVersionedLoader` 设为 spec 的异步数据源。
- **条件读写**（§6.5，只在 `Store` 上）：`Digest` 是载荷的 SHA-256，带算法标识（`Digest.algorithm`）。`getIfChanged(key, known: Digest?): ConditionalGet<V>`（`Unchanged` | `Present(value, digest)` | `Absent`）、`putIfMatch(key, expected: Digest?, value[, ttl]): ConditionalPut`（`Written(digest)` | `Conflict(current: Digest?)`）与 `deleteIfMatch(key, expected: Digest): ConditionalDelete`（`Deleted` | `Conflict(current: Digest?)`）。
- **按版本有序的写入**（§6.6，只在带版本的类型上）：`putIfNewer(key, value, version[, ttl])`、`invalidateIfNewer` / `deleteIfNewer(key, version[, ttl])`（`VersionedPut` = `Written` | `Stale(current)`）与 `getVersioned(key): Versioned<V>?`。
- **`CacheReadException`**：读取没有答案（`Unknown`），或读到读不懂的内容而没有数据源可以纠正。批量读取部分 key 为 `Unknown` 时，异常携带 `unknownKeys` 与其余 key 的 `partialResult`。**`CacheWriteException.outcomeUnknown`** 区分“确定未执行”（`false`：存储以 `L1RejectedException` 报告的写出之前的拒绝，或后端拒绝了写入、没有修改 L1）与“结果未知”（`true`，§7）。

### 2.2 两类副本的区别

权威副本的 L1 是唯一副本，本进程无法按需重建它（RFC 0005 §4.1）。与派生副本的区别全部来自这一点：

| 方面 | `Cache` / `VersionedCache`（派生） | `Store` / `VersionedStore` / `SetStore`（权威） |
|------|------|------|
| 数据来源 | 必须声明：spec 的数据源，或 `externallySourced()`（只有 `Cache`） | 按构造没有 |
| L1 中不存在 | 有数据源：回源；外部来源：“未填充” | `Absent`，受戳保护进入 L2 |
| lease | 未命中时申请 | 不申请；lease 占位读作 `Absent` |
| L1 读失败 | 有数据源：降级回源（显式 `readFailure = THROW` 时 `Unknown`）；没有数据源的读取：`Unknown` | `Unknown`（`unknownRead` 换算，`SetStore` 总是抛出） |
| 读不懂的内容 | 有数据源：`RECLAIM`；外部来源：`FAIL` | `FAIL` |
| 删除 | `invalidate`，失败默认 `RETRY`；`VersionedCache` 用保留版本的 `evict`，失败抛出 | `delete` / `deleteIfMatch` / `purge` / `clear`：同步完成，失败抛出，从不重试 |
| 写入的段 | 不带写入标识与摘要段 | 写入标识；`Store` 另带摘要段，读取方校验 |
| 默认寿命 | `ttl` 1 h，`localMaxAge` 5 m，`absentTtl` 1 m | `ttl` 永不过期，`localMaxAge` 30 s，`absentTtl` 10 s（D7：失效丢失时没有回源可以纠正，陈旧的上界只剩 L2）；`VersionedStore` 的墓碑寿命 `tombstoneTtl` 1 天 |
| 提前刷新、Spring Cache、`TransactionalInvalidator` | 可用（`VersionedCache` 不接入 Spring） | 不适用 |
| 启动检查 | — | 驱逐策略（§6.4） |

**误分类**：唯一副本声明成 `Cache` + `externallySourced()`，默认 `ttl` 1 h 会让唯一副本一小时后过期，`invalidate` 的延迟重试还会越过之后的写入；可重建的副本声明成 `Store`，L1 丢失之后读成不存在。接口式声明在权威类型上有匹配的 `CacheLoader` bean 时启动告警（§11.2）。

## 3. 模块与职责

### 3.1 模块

```
cocache-api                    用户 API + 全部 SPI，零运行时依赖（挂起视图只用标准库）
  ReadableCache；派生副本 Cache / VersionedCache；权威副本 Store / VersionedStore / SetStore（§2.1）
  各自的异步视图（AsyncReadableCache、AsyncCache …，§5.6）与挂起视图（SuspendingReadableCache、SuspendingCache …，§5.7）
  Lookup（Present / Absent / Unknown）/ CacheLoader / AsyncCacheLoader / Codec / CacheClock
  Versioned / VersionedPut（按版本有序的写入，§6.6）、ConditionalGet / ConditionalPut / ConditionalDelete（条件读写，§6.5）
  CacheReadException（unknownKeys、partialResult）/ CacheWriteException（outcomeUnknown）
  annotation：@CoCache（source）、@CaffeineCache、@JoinCacheable
  join：JoinView（只读）、JoinCache（两侧都是 Cache）、JoinValue、JoinKeyExtractor
  spi：CoherenceBackend、L1Store（挂起；能力面 ConditionalL1Store、VersionedL1Store、SetL1Store）、InvalidationChannel、
       InvalidationListener、Subscription、FutureL1Store / FutureInvalidationChannel 与 adapt（Java 实现者）、ForwardingL1Store（装饰器）、
       Envelope、L1Read、L1Fill、L1ConditionalRead / L1ConditionalWrite、L1VersionedWrite、L1Eviction、L1RejectedException、
       LeaseRequest、RefreshRequest、Fill、Put、Token、NodeId、Digest、ContentFingerprint、InvalidationFrame
cocache-core                   引擎与运行时；依赖 Caffeine、kotlin-logging、kotlinx-coroutines-core（协程核心，§5.6，api 依赖）
  CacheRuntime；Spec 与五种 spec：CacheSpec、VersionedCacheSpec、StoreSpec、VersionedStoreSpec、SetStoreSpec（KeyEncoder、TtlFunction、KeyFilter）；
  SuspendingCacheLoader、suspendingLoader()、suspendingVersionedLoader()、asAsyncCacheLoader()；CacheAdmin、CacheMetrics、RetryPolicy、
  ExternalInvalidator、LoadObserver、FillObserver / FillScope / FillKey（写事务内的回源，§5.8）、SourceLoadRejectedException、SourceLoadTimeoutException、SourceLoadCancelledException、ForeignFormatException
  engine：AbstractEngine、CoherentEngine（标量协议）与 CacheEngine / StoreEngine / VersionedCacheEngine / VersionedStoreEngine、SetEngine、
          EngineSpec / Role、挂起视图的实现（SuspendingViews）、ValueFormat、InvalidationRetrier、AsyncContext、EngineDispatcher
  internal：SingleFlight、InvalidationStamps、Futures、ExecutionMode、LoadingKeys、uncancellable（L1 调用不可取消）
  policy：CachePolicy、LeasePolicy、LoadPolicy、LocalPolicy、InvalidateFailureMode、ReadFailureMode、UnknownReadMode、
          DefaultPolicies（每种类型的默认策略）、PolicyField（可分层的字段）、PolicyDraft、Ttl / RefreshWindow / Concurrency（哨兵值）
  CoCacheFactory（装配入口）与 CacheLayers（装配层）、Locks / LayoutField（库的锁定）、PolicyReport / Layered（生效值与来源）；
  proxy：CacheProxies、CacheMethods、JoinCacheProxies；join：SimpleJoinView、SimpleJoinCache；memory：InMemoryBackend；clock：TickingClock、ManualClock；
  codec：StringCodec
cocache-redis                  Lettuce 原生后端，不依赖 Spring
  RedisBackend、RedisBackendOptions、RedisChannelMode、RedisScripts、RedisL1Store（原生挂起的 L1Store）、RedisConnections、
  PubSubInvalidationChannel、ShardedInvalidationChannel
cocache-codec-jackson          Jackson 3（tools.jackson）Codec：JacksonCodec、jacksonCodec<V>()
cocache-spring                 Spring 适配（不依赖 Boot）：CoCacheSpringCacheManager、CoCacheSpringCache、TransactionalInvalidator、TransactionalFillObserver
cocache-spring-boot-starter    自动配置、@EnableCoCache（caches、joinCaches）、注解翻译、spec bean 注册、配置分层、actuator 端点与健康检查、Micrometer、Spring Cache sync 检查
cocache-test                   已发布的 TCK：BackendProtocolSpec、EngineSpec、SetEngineSpec、MultiNodeSpec、FaultSpec、VersionedSpec、L1Capability、CommandCancellation、CacheAccess、HookedBackend、Suspending、Concurrency
cocache-bom / cocache-dependencies / cocache-example
```

依赖方向严格单向：`api ← core ← redis`，`api ← codec-jackson`，`core ← spring`，`core ← test`；starter 依赖 `spring`、`redis`、`codec-jackson`。`kotlinx-coroutines-core` 只由 `cocache-core` 直接依赖。core 不依赖 `spring-expression` 与 `kotlin-reflect`：SpEL 与注解解析只在 starter 中出现。新后端只需依赖 `cocache-api`（实现 SPI）与 `cocache-test`（通过 TCK）。

### 3.2 职责划分

- **存储（`L1Store`）只执行协议**：处理字节、信封与令牌，不知道值的类型，不持有任何策略。TTL、刷新窗口、令牌都由引擎作为参数传入，存储只看到相对时长（`Duration?`，`null` 为永不过期）。
- **策略（`CachePolicy`）**：寿命、抖动、负缓存、本地限龄、降级限龄、提前刷新、lease、回源并发、L2 容量、失败模式。由引擎持有。
- **引擎（`CoherentEngine` / `SetEngine`）**：读穿透、合并回源、两层栅栏、写入顺序、失败处理、提前刷新。四种标量类型共用 `CoherentEngine`：它的副本类型（`Role`：派生、`Store`、`VersionedStore`）决定写入哪些段、读取是否申请 lease、读取方是否校验摘要；子类只把用户 API 换算过去，命中路径与引擎是同一个对象。
- **运行时（`CacheRuntime`）**：每个进程一个，持有 `NodeId`、时钟、后端、运行时的执行器（§10.1）、刷新执行器、失效重试调度器，按 spec 新建引擎并订阅失效。
- **L2 固定为 Caffeine**，不是 SPI。
- **组合视图只读**：`JoinView`（两侧是任何 `ReadableCache`）的 `get` / `getAll` 先读主缓存，再按去重后的关联 key 批量读关联缓存（各自走完整的读路径与栅栏），自己不存任何数据、不写入。`JoinCache`（两侧都是 `Cache`）另有 `invalidate(key)`（只失效主缓存）与 `invalidate(firstKey, joinKey)`（同时失效两者）。两个缓存各自的 TTL 与失效互不影响，视图不提供跨缓存的原子快照。
- **默认 `KeyEncoder` 是 `toString()`**，每种 key 类型首次编码时检查是否重写了 `toString()`（没有重写时每个实例都映射到不同存储 key），不满足即拒绝；`String` key 只多一次引用比较。
- **`ExternalInvalidator`**：缓存之外的写入方按同一协议失效（删除 L1 key 并广播帧，原子广播的后端在同一脚本内完成），不需要创建缓存。

### 3.3 L1 SPI 与能力

- **形状**（RFC 0005 §4.3）：`CoherenceBackend.store` 返回挂起的 `L1Store`，`InvalidationChannel.publish` 挂起（`subscribe` 仍阻塞，只在 `runtime.create` 时调用）。Java 实现者实现 `FutureL1Store` / `FutureInvalidationChannel`，经 `adapt` 适配：已完成的 future 不挂起；被取消的 future 与同步抛出的 `CancellationException` 换成 `IllegalStateException`，按 L1 故障处理，不当作调用方的取消；返回 `null` 时以指明操作的异常失败。只有阻塞客户端的后端在自己的执行器上完成这些 future。内存后端是原生的 `L1Store`（就地执行、从不挂起；TCK 用的 `stall` 期间除外），Redis 是原生挂起的 `RedisL1Store`（§9）。
  - 结果类型（K12）：`fill` 返回 `L1Fill`：`Filled(advanced)` 或 `Rejected(currentVersion)`，`fillAll` 逐项返回它。令牌不匹配为 `Rejected(currentVersion = null)`；版本规则拒绝为 `Rejected(当前版本)`，让版本前进的写入为 `Filled(advanced = true)`（§4.2 `FILL` 的版本规则，§5）。`reclaim` 比较 SPI 自己的 `ContentFingerprint`（SHA-1，算法与脚本中的现算相同，§4.2），与 `Digest`（SHA-256）无关：回收的指纹只判断读不懂的内容是否仍是读到的那一份，不涉及安全。
  - 读取的时限（D6）：`read`、`readAll` 与 `ConditionalL1Store.readIfChanged` 带可选的 `timeout`：`null` 为存储自己的命令超时，给出时必须为正、以命令超时为上限，存储以两者中较短的一个限定这次调用，超时之后照常释放令牌。它与引擎传入的其它时长一样是相对的。引擎传入缓存的 `CachePolicy.readTimeout`（配置键 `read-timeout`，§7、§10.2），未设置时为 `null`。
  - 取消（RFC 0005 §4.3 的契约，写进 `L1Store` 的 KDoc）：故障不得以 `CancellationException` 报告；底层客户端取消的命令（如连接重置、关闭）是结果未知的故障。
  - 存储没有 `close`：连接等资源属于后端。
- **能力**：条件读写、按版本有序的写入与集合成员的增删由存储的访问器表达（`conditional()`、`versioned()`、`sets()`，不支持时为 `null`），引擎在创建时各取一次。`runtime.create` 检查类型需要的能力：`Store` 要求 `conditional()`，`VersionedCache` 与 `VersionedStore` 要求 `versioned()`，`SetStore` 要求 `sets()`，`Cache` 不要求；缺失时抛出 `UnsupportedOperationException`，不注册任何东西。每个命名空间创建时在启动日志中输出类型与存储提供的能力（`created as Store … L1 capabilities[conditional, versioned, sets]`）。引擎绕过运行时创建时（测试），缺失的能力在调用时、修改任何一层之前以 `UnsupportedOperationException` 失败，从不退回“先读后写”。

## 4. 线格式与 L1 协议

### 4.1 存储 key 与频道

布局（RFC 0005 §4.7）在一个大版本内冻结（I-9）。一个缓存在 L1 中占用一个 keyspace：前缀加 namespace（SPI 的 `Keyspace`，`CacheSpec.keyspace`）。

| 用途 | 格式 |
|------|------|
| 存储 key | `{keyPrefix}{namespace}:{encodedKey}` |
| 失效频道 | `{keyPrefix}inv:{namespace}` |
| 探测 key / 频道（`pubsub` 模式，§9） | `cocache:!probe:{a}` / `cocache:!probe:{b}` |
| 内部 key | `{keyPrefix}!…`；元数据 key `{keyPrefix}!meta:{namespace}` 只预留，不写入 |

- `keyPrefix` 默认 `cocache:`，可以为空；`namespace` 默认等于缓存名。存储 key 的前缀 `{keyPrefix}{namespace}:` 由 `Keyspace.storagePrefix` 在创建时算好，命中路径不拼接它。`keyPrefix` 为空时创建缓存告警：key 与频道没有公共前缀，ACL 与按前缀的运维只能逐个 namespace 写出（RFC 0005 §4.7，S4）。
- **内部 key** 放在 `{keyPrefix}!…`（`Keyspace.INTERNAL_MARK`）：`!` 不在 namespace 的字符集中，任何 namespace 都产生不了这个位置，也不是 `{` `}`（会改变 Cluster 的 slot）或 glob 元字符。默认前缀下 namespace 为 `probe` 或 `meta` 的存储 key 因此不会与它们相撞。
- **namespace 的字符集**为 `[A-Za-z0-9._-]+`，不含 `:`。同一前缀下，一个 namespace 的存储 key 因此不会落进另一个 namespace 的范围（如 `a` 与 `a:b`，RFC 0005 附录 A.L4），跨应用时也一样（`runtime.create` 的校验看不到别的应用）。不符合的 namespace 在构建声明时失败（`IllegalArgumentException`）；含 `:` 的，信息给出把前缀移到 `keyPrefix` 的写法（`app:user` → `keyPrefix = "{keyPrefix}app:"`、`namespace = "user"`）。
- **频道带上前缀**：每个 keyspace 一个频道，频道名由后端构造（Redis 见上表），前缀不同、namespace 相同的两个应用各自只收到自己的帧。
- 同一运行时内缓存名与 keyspace 都不得重复：namespace 相同、前缀不同的两个缓存可以共存。
- L2 的 key 是编码后的 key，不含 `{keyPrefix}{namespace}:`（RFC 0005 §4.5）：每个缓存有自己的 L2，前缀只区分 L1 中的 key。命中路径因此不拼接字符串（`String` key 经默认的 `KeyEncoder` 原样返回，哈希已缓存；JMH 中单个缓存的 L2 命中吞吐约为拼接前缀时的 2.6 倍）。失效戳与 SingleFlight 只在未命中与写入路径上使用，仍按存储 key。
- **订阅者收到编码 key**（RFC 0005 §4.9，S4）：帧中是存储 key，通道以 `Keyspace.encodedKeys` 去掉 `{keyPrefix}{namespace}:` 再交给 `InvalidationListener`，不以该前缀开头的 key（外部写入方给错了前缀）不属于这个 keyspace，去掉。引擎收到的就是 L2 的 key，按它淘汰 L2、按 `{keyPrefix}{namespace}:` + key 递增戳；外部监听者（如 CoSec）收到的与 `KeyEncoder` 的输出、`ExternalInvalidator` 的参数相同，不必反解存储 key。
- **订阅者按订阅的先后依次回调**（帧与重置都是，前一个返回之后才回调下一个）：缓存在 `runtime.create` 时订阅，之后订阅同一 keyspace 的外部监听者收到通知时，引擎已经递增戳并淘汰了 L2（“引擎先淘汰本地副本，再通知外部监听者”，`InvalidationChannel` 的契约）。
- 失效帧（客户端构造，Lua 只负责发布）：`[版本:1B=0x01][nodeId:16B][count:varint]([len:varint][key UTF-8])*`，见 `InvalidationFrame`。解码不信任长度前缀。
  - **读不懂的帧按重置处理**（RFC 0005 §4.7，K1、D3）：未知版本、未知类型、解码失败（截断、尾随字节、长度越界），通道一律按该频道的 `onReset` 处理：清空 L2、递增全部戳，只会多淘汰，不会丢失效（I-L）。丢弃它丢的就是一次失效（G2），之后新增的帧也就只能两阶段发布。
  - 首字节是帧的版本，也是帧的类型。帧版本 2 预留为“namespace 重置”（`InvalidationFrame.NAMESPACE_RESET_VERSION`，Q12），目前不发送：不认识它的节点把它当作读不懂的帧重置，恰好做了正确的事，所以加入它不需要两阶段发布。
  - **零 key 的帧**是存储创建时的频道探测（§9，K8），合法帧，通道照常投递，引擎什么也不做（不计入收到的失效）。

### 4.2 L1 线格式（Redis，格式 2）

全部内容都是格式 2（RFC 0005 §4.7），lease 占位 `L`、负缓存 `M` 与集合也是。格式号低于本节点格式的内容（格式 0、1，或不是本协议写入的内容）都按读不懂处理（见“读不懂的内容”）。

**标量、负缓存、lease 占位、保留版本的失效：String = 36 字节的核心头部 + 标志位声明的段 + 载荷**

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0 | 1 | magic `0xC0` |
| 1 | 1 | 格式版本（`2`），无符号字节：Lua 的 `string.byte` 与客户端的解析一致，`0x80` 以上的更高版本不会被读成负数 |
| 2 | 1 | 状态：`V` 值、`M` 负缓存、`L` lease 占位、`E` 保留版本的失效（只有版本段，没有载荷） |
| 3 | 1 | 标志位，见下 |
| 4 | 24 | 令牌：`L` 状态下是 lease 令牌，`V` 状态下是刷新令牌，`E` 状态下是 lease 令牌，全零表示无 |
| 28 | 8 | 令牌签发时间：服务端 `TIME` 的纪元毫秒，大端 IEEE 754 double（Lua `struct.pack('>d')`） |
| 36 | 8 | 版本段（bit 0）：大端有符号 64 位 |
| 之后 | 32 | 摘要段（bit 1）：载荷的 SHA-256（`Digest`），由写入方在客户端计算，读取方重算校验（K3） |
| 之后 | 2 + n | 扩展区（bit 7）：2 字节总长 n（大端无符号，其后 TLV 的字节数，不含它自己）+ TLV |
| 之后 | 其余 | 载荷：`Codec<V>` 的输出（`M`、`L` 没有载荷） |

- **标志位**：bit 0–6 是必须理解的，bit 7 的扩展区只放旧读者可以忽略、整体覆盖时丢失也无害的信息。本版本认识 bit 0、1、7；bit 2–6 是未知的必须理解的位，带有它们的内容等同于更高格式。
- **载荷偏移只有一个定义**：核心头部之后按标志位的顺序依次跳过版本段、摘要段与扩展区。Lua 的 `payload_start`（`RedisScripts` 的 `FORMAT_RULES`）与客户端的 `RedisScripts.payloadOffset` 逐行对应，需要载荷的脚本（回收、条件读写）与客户端的解码都用它。只有版本段时载荷从字节 44 开始，Lua 中是 `string.sub(v, 45)`（P18）。段超出值的长度时头部损坏，按不是本协议写入的内容（格式 0）处理。
- **扩展区的 TLV**（D2）：`[类型:1B][长度:1–2B][值]`。长度 0–127 用 1 字节；128–32767 用 2 字节，首字节为 `0x80 | (长度 >> 8)`、次字节为长度的低 8 位。写入方用最短的形式，读者两种都接受。读者逐个读 TLV、跳过未知的类型，取每个类型的第一个；TLV 超出扩展区时读者忽略其余部分，不把值当作损坏（扩展区整体可以忽略）。整体覆盖时扩展区丢失，原地修改（字节 4–35）保留它。
  - 类型 1 是**写入标识**：24 字节，与令牌同形（`NodeId` + 序号），每次写入新取一个，扩展区共 28 字节（总长 26、类型 1、长度 24）。长度不是 24 时读者按没有处理。它预留给按标识比较的条件写入，以解决摘要比较的现值 ABA（RFC 0005 §4.2、§4.6）；引擎目前只写入、读出它，不使用它。
- **写入哪些段**（RFC 0005 §4.7，按类型）：
  - 派生副本（`Cache`，有数据源或外部来源）的值、`L`、没有版本的 `M` 不带段，头部是 36 字节；
  - `Store` 的每次标量写入（`put`、`putAll`、`putIfMatch`）带写入标识（`Envelope.Scalar.writer`，扩展区 28 字节）与摘要段（`Envelope.Scalar.digest`，32 字节）：载荷的 SHA-256，由引擎计算（`Digest.ofPayload`），存储原样写成段、读取原样返回，从不重算。头部因此是 36 + 32 + 28 = 96 字节；
  - `VersionedStore` 的 `putIfNewer` 带版本段与写入标识，不带摘要段（带版本的内容没有条件读写）；`VersionedCache` 的值（`putIfNewer`、带版本的回源）只带版本段；
  - 墓碑（带版本的 `M`）只有版本段。`E` 只有版本段（44 字节），由 `EVICT_KEEPING_VERSION` 写入。
- **版本段中不得是 `NO_VERSION`**（`Long.MIN_VALUE`，K15）：客户端不发出（`putIfNewer` 以 `IllegalArgumentException` 拒绝它），写脚本在第一次写入之前以错误回复拒绝（`PUT_ALL` 先检查全部形状）；`PUT_IF_NEWER` 的形状必须带版本段。
- **令牌**（`Token`，24 B）= `NodeId`（16 B，每个运行时随机生成）+ 进程内单调序号（8 B）。由客户端构造，全局唯一、永不重复签发，所以被销毁的令牌不可能再次匹配（无 ABA）。版本在版本段，带版本的内容同样持有令牌：带版本的值取得刷新令牌，`E` 上取得 lease。
- **授予令牌之前先看格式与标志位**（K15）：`READ` 只对格式 2、没有未知的必须理解的位、状态已知的内容授予 lease 或刷新令牌；刷新令牌授予没有摘要段的 `V`（带版本段的照常授予），带摘要段的值是权威标量，从不授予（D4）。格式不是 2 或带未知位的内容原样返回，其中的 `L` 也不当作 lease。
- **`FILL` / `RELEASE` 只读头部的开头**（K19）：`FILL` 以 `GETRANGE key 0 43` 读核心头部与版本段（版本规则要比较版本），`RELEASE` 以 `GETRANGE key 0 35` 只读核心头部，不为比较 24 字节的令牌把整个值读进 Lua（带刷新令牌的 8 MB 值每次写回与释放都会整个读入）。只有格式 2、没有未知的必须理解的位、状态已知的内容中的令牌能匹配。
- **状态 `E`：保留版本栅栏的失效**（P1，RFC 0005 §4.7）：`VersionedL1Store.evictKeepingVersion` 把带版本的内容（`V` / `M` / `E`）整体写为 `E(当前版本)`：令牌为零，到期时间由引擎给出，广播。`E` 读作未命中，不是负缓存：
  - `READ` 不申请 lease 时返回 `Miss(false)`；申请 lease、且其上没有令牌或令牌签发已超过 lease 寿命时，以 `SETRANGE 4` 原地写入令牌与 `TIME`（保留状态、版本段与到期时间），返回 `Miss(true)`；否则返回 `Leased`，剩余时间为 lease 寿命减去已签发时长；
  - `FILL` 的版本不小于 `E` 的版本时写入并广播（`advanced`），否则拒绝：脚本内清零令牌，返回 `E` 的版本；`RELEASE` 只清零令牌，从不删除 `E`；`RECLAIM`、条件脚本与集合脚本都拒绝它；`PUT_IF_NEWER` 与墓碑按它的版本比较；`PUT` / `PUT_ALL` / `INVALIDATE` 整体覆盖或删除它。
  - 不带版本段的 `E` 是未知的状态，报告为更高格式。
- **`FILL` 的版本规则**（C3、P2、P11）：key 中带版本（持有刷新令牌的带版本值、持有 lease 的 `E`）且令牌匹配时，填充的版本更小或为 `NO_VERSION`：不写入，脚本内清零令牌（只触及字节 4–35），返回当前版本（`L1Fill.Rejected(v)`，省一次 `RELEASE` 往返）；相等：写入（续期），不广播（`Filled(false)`）；更大，或 key 处于 `E`：写入并在同一个脚本中广播（`Filled(true)`，`advanced`）。广播的帧由存储构造：来源是填充令牌的 `NodeId`（令牌由引擎签发，就是引擎的节点），只含这个 key。key 中没有版本（lease 占位、没有版本的值）时令牌匹配即写入，不广播。

**集合（`SetStore`）：Hash**（K10）。字段 `!f` 为格式版本（`"2"`），`!m` 为必须理解的位图（十进制，缺省为 0；本版本没有已知的位，也不写它），成员字段为 `+` + 成员字节，值为空串；其余以 `!` 开头的字段预留给元数据，读者忽略。给集合加信息因此不必改格式号。`!f` 不是 `2` 的集合读不懂（缺失或不是数字为格式 0，其它数字原样报告），`!m` 存在且不是 `0` 的集合等同于更高格式。Hash 只表示“有成员的集合”；lease 与负缓存总是 String 形状。任何整体写入都先 `DEL` 再按新形状写入。

- **到期时间**：一律 `PEXPIRE` / `PTTL`，毫秒，以相对时长传输，不受节点间时钟偏差影响。参数钳到至少 1 ms；`0` 表示永不过期。**每次状态转换都显式重设到期时间**，lease 的寿命和负缓存的寿命不得泄漏到新值上。只触及字节 4–35 的原地修改（授予刷新令牌、`E` 上授予 lease、`RELEASE` 清零令牌、被版本规则拒绝的 `FILL` 清零令牌）保留原到期时间（K15）。
- **读取成本**：标量 `PTTL` + `GET`（一次 EVALSHA）；集合 `PTTL` + `HKEYS` + `HMGET !f !m`（只返回字段名，不返回成员的空值；与 `HGETALL` 的字段顺序相同，摘要不受影响）；条件读写 `TYPE` + `GETRANGE key 0 77` + `STRLEN`，只在摘要变了时 `GET`。读脚本与解析其回复的客户端随同一版本发布，混合版本部署按 SHA 各自加载脚本，可以逐台升级。最小 ACL 因此要加 `+getrange +strlen +hmget`（运维文档）。
- **读不懂的内容**（RFC 0005 §4.7）。客户端先看格式号、再看标志位与状态（P1、P18），分类与脚本的 `scalar_format` / `set_format` 相同：
  - **低于本格式**（不是本格式写入的内容）：格式 0，即不以 magic 开头或不足 36 字节的 String、格式字节为 `0` 或段超出长度的 String、`!f` 缺失或无效的 Hash、其它 Redis 类型（如原生 Set；以空载荷返回）；以及格式号低于 2 的值、lease 与集合（格式号原样报告）。有数据源的读取条件回收（比较整个值的指纹）后回源，没有数据源的读取失败（§5.4）；`READ` 不授予令牌、不把其中的 `L` 当作 lease，`FILL` / `RELEASE` 不匹配，条件写入、按版本写入与集合元素写入拒绝。
  - **更高的格式**：格式号高于 2，以及格式 2 中未知的必须理解的位、未知的状态与 `!m` 中有位的集合（存储报告为格式 3，头部中的格式字节仍是 `2`）。既不回收也不写回：有数据源的读取绕过 L1 回源，外部来源的 `Cache` 上不带 loader 的读取得到“未填充”，权威副本的读取失败（§5.4），条件写入、按版本写入与集合元素写入拒绝，`READ` 不授予令牌，`FILL` / `RELEASE` 不匹配。整体覆盖（`put`、`putAll`、`invalidate`）不依赖旧内容，照常写入。
- **摘要**（`Digest`，D1）：载荷的 SHA-256（32 字节），带算法标识（`Digest.algorithm`，目前只有 `SHA-256`）。写入方在客户端计算（`Digest.of`、`Digest.ofPayload`），写在摘要段中；条件读写的脚本只比较头部中的这 32 个字节，Lua 不计算摘要，因此不受 Redis 的 Lua 只有 `redis.sha1hex` 的限制。摘要只覆盖载荷，与令牌、其它段与到期时间无关；它防的是“攻击者控制经合法写入方写入的内容”（借 SHA-1 的选择前缀碰撞让 `getIfChanged` 对已变化的内容回答 `Unchanged`），不防能直接写 Redis 的攻击者（K3，那要靠 ACL）。
- **读取方校验**（K3）：客户端拿到载荷时重算 SHA-256 并与摘要段比较，不一致按损坏处理：权威副本（`Store`、`VersionedStore`）的 L1 读取（单 key、批量），以及条件读取返回的值。权威副本读到不带版本、也不带摘要段的值同样按损坏处理。校验在引擎中（`CoherentEngine.verifyDigest`），对所有后端相同；成本只在 L1 读取上，不在 L2 命中上。摘要未变的回答（`Unchanged`）只比较头部，不重算。
- **内容指纹**（`ContentFingerprint`，条件回收用，SPI 自己的类型）：本格式的标量取载荷（从载荷偏移起），读不懂的 String 取整个值，String 与 Hash 以外的类型取空串，负缓存取空串，集合按 L1 返回顺序拼接 `int32 长度 + 成员`，SHA-1，由 `RECLAIM` 在 Lua 中现算（`redis.sha1hex`）。它只判断读不懂的内容是否仍是读到的那一份，与安全无关。普通读取只在解码失败时计算。

线格式（key 布局、频道命名、标量头与段、状态 `E`、扩展区的 TLV、集合字段、帧、摘要算法 SHA-256）在一个大版本内冻结（I-9）。新增必须理解的段、状态或集合的位，一律两阶段发布：先发布能读懂它的版本，一个 namespace 的全部节点都升级之后才写入（I-L）；扩展区的新 TLV 与新的 `!` 字段旧读者可以忽略，不需要两阶段。

### 4.3 L1 状态机

```
            read(miss, lease=t)                 fill(t 匹配)
  ABSENT ───────────────────────▶ LEASED(t) ────────────────────▶ VALUE / MISSING
    ▲                               │  │                            │   │
    │  release(t) / lease 到期 /     │  │                            │   │ read(进入刷新窗口, r)
    │  invalidate                   │  │                            │   ▼
    └───────────────────────────────┘  │                     VALUE + 刷新令牌 r
    ▲                                  │                            │ fill(r 匹配) → VALUE（重设 TTL）
    │ invalidate（DEL + PUBLISH）       │                            │ release(r) / r 过期 → VALUE
    └──────────────────────────────────┴────────────────────────────┘
  put：任意状态 → VALUE / SET（整体覆盖，销毁令牌，重设 TTL）+ PUBLISH
  addMembers / removeMembers（SetL1Store，Redis 脚本为 *_IF_READABLE）：见 §6.4
  putIfMatch：当前内容符合期望时同 put（只写标量），否则不修改；deleteIfMatch：符合期望时同 invalidate，否则不修改；
  readIfChanged：不修改任何状态（§6.5）
  putIfNewer(v)：v 大于当前版本（VALUE / MISSING / EVICTED 的版本）时同 put（带版本 v，整体覆盖、销毁令牌、重设 TTL）+ PUBLISH，否则不修改（§6.6）
  evictKeepingVersion：带版本的 VALUE / MISSING / EVICTED → EVICTED(v)（令牌为零，重设 TTL）+ PUBLISH；没有版本的内容 → DEL + PUBLISH

  VALUE(v) + 刷新令牌 r ── fill(r, w)：w < v 或没有版本 → 拒绝，清零令牌（Rejected(v)）
                                       w = v → VALUE(w)，不广播；w > v → VALUE(w) + PUBLISH（advanced）
  EVICTED(v) ── read(lease=t)：令牌为零或签发超过 lease.ttl → EVICTED(v) + lease t（SETRANGE，保留 TTL），Miss(true)；否则 Leased
             ── fill(t, w)：w ≥ v → VALUE(w) / MISSING(w) + PUBLISH（advanced）；否则拒绝，清零令牌（Rejected(v)）
             ── release(t)：清零令牌，仍是 EVICTED(v)；read（不申请 lease）：Miss(false)
```

每个转换都由一个 Lua 脚本原子完成（`RedisScripts`：`READ`、`FILL`、`RELEASE`、`PUT`、`PUT_ALL`、`INVALIDATE`、`RECLAIM`、`ADD_MEMBERS_IF_READABLE`、`REMOVE_MEMBERS_IF_READABLE`、`READ_IF_CHANGED`、`PUT_IF_MATCH`、`DELETE_IF_MATCH`、`PUT_IF_NEWER`、`EVICT_KEEPING_VERSION`；另有启动时的探测脚本 `PROBE`，§9）。脚本**不带 shebang 标志**：带标志的脚本在 OOM 时被拒绝，而失效恰恰释放内存。写脚本含 `PUBLISH`，不能用 `EVAL_RO`。

- **lease**：读到不存在的 key 且请求了 lease 时，写入 `L` 占位，`PX = lease.ttl`，返回 `Miss(leaseGranted = true)`。读到格式 2 的 `L` 返回 `Leased(remaining)`。节点崩溃留下的 lease 由 `lease.ttl` 回收。
- **刷新令牌**：读到格式 2、没有摘要段与未知的必须理解的位（K15、D4）的 `V`（带版本段的照常授予），且剩余寿命 `0 < pttl ≤ refreshWindow`，且头部令牌为零或签发超过 `lease.ttl` 时，用 `SETRANGE` 写入调用方令牌与服务端时间（保留原 TTL），返回 `refreshGranted = true`。签发过期的令牌可被抢占，被抢占者的 `fill` 因令牌不匹配失败。
- **fill**：仅当 key 是 String、头部的开头（`GETRANGE key 0 43`，K19）是格式 2、没有未知的必须理解的位、状态已知，且其中的令牌等于 `Fill.token`（lease 或刷新令牌）时，按版本规则（§4.2）写入并重设 TTL。
- **release**：只读核心头部，令牌匹配时，`L` 状态删除 key，`V` 与 `E` 状态只清零令牌。
- **reclaim**：仅当当前内容的指纹等于读到的那份、且是格式号低于本格式的内容（含格式 0）或格式 2 中没有段的 `V` / `M`（不是 `L`、`E`、带版本段、带摘要段或更高格式的内容）时，原子地转为 lease（带版本的内容转为 lease 就丢了版本，§6.6；带摘要段的是权威标量，D4；更高格式的内容从不回收，§4.2）。

## 5. 读路径

```
get(key):                                         // CoherentEngine.entryOf
  k = storageKey(key)
  e = local.getIfPresent(k)
  if e != null && e.expiresAt > clock.nowMillis():
      if e.refreshAt <= now: refreshAsync(key, k)
      return e.value
  return miss(key, k)                             // 独立方法，保证命中路径小、可内联

miss:
  if keyFilter?.notExist(k): return 负缓存（absentTtl，不写任何层）
  tx = 有数据源时 fillObserver.enter(name)        // 调用入口捕获写事务一次（§5.8），作为协程上下文元素
  if tx?.isPrivate(k): return 私有回源               // 不读写 L1、不写 L2、不经 SingleFlight（§5.8）
  observed = stamps.current(k)
  r = flights.execute(k) { loadThrough }          // SingleFlight；leader 在写事务中时结果只给自己
  if r.stamp < observed: r = flights.execute(k) { loadThrough }   // 读己之写，至多重试一次

loadThrough:                                      // 在 leader（调用方）线程上执行
  stamp = stamps.current(k)                       // SingleFlight 注册之后取戳，整个调用只取这一次
  if k 的失效仍在重试中: return loadBypassingRemote
  rr = store.read(k, lease = 派生副本：新令牌；权威副本：不申请, timeout = readTimeout)   // 策略的读取时限，null 为后端的命令超时（D6）
  while rr is Leased && 申请了 lease && 未超过 min(remaining, lease.wait): 退避 2→50 ms 后重读（沿用同一个 stamp）
  Hit    → 格式检查 / 摘要校验（权威副本，K3）/ 解码（失败见 §5.4）→ fillLocal(k, entry, stamp)，限龄 localMaxAge
  Miss   → 回源；计算 ttl 与编码；有 lease 则 fill(k, lease, envelope, ttl)（lease 可能在 E 上）
           Filled(false)：L2 限龄 localMaxAge
           Filled(true)（版本前进或填充了 E）：按写入处理——递增戳、淘汰 L2、不填 L2，非原子广播时由引擎发布
           带版本的 fill 失败（结果未知；存储在写出之前拒绝的除外）：同上，按写入处理、不填 L2，计入 fillRejected（F2）
           没有 lease、令牌不匹配、版本拒绝或不带版本的 fill 失败：L2 限龄 degradedLocalMaxAge（版本拒绝的副本不设刷新点）
           回源、ttlOf、Codec 抛出时先 release lease 再抛出
  Leased → 派生副本等待超时：loadBypassingRemote；权威副本（没有申请 lease）：不存在（§5.1）
  L1 异常 → 没有数据源，或 readFailure = THROW：Unknown（不写任何一层，调用方返回之前换算，§5.1）
           有数据源且 readFailure 未设置或 DEGRADE：loadBypassingRemote

loadBypassingRemote:                              // 回源，不写 L1；L2 限龄 degradedLocalMaxAge，仍受戳保护
                                                  // 没有数据源时只得到“未填充”（§5.1）
```

- **L2 副本的到期时间** = `min(now + L1 剩余寿命, now + maxAge)`；`maxAge` 是 `localMaxAge` 或 `degradedLocalMaxAge`。`refreshAt` = L1 到期时间 − `refreshWindow`（只对有限寿命的值；L1 剩余寿命不长于窗口时见 §5.5）。时钟是 `TickingClock`（100 ms 刷新一次的 volatile 值），命中判断是一次 volatile 读加一次比较。
- **寿命**：值的寿命 = `ttlOf(key, value)`（非 null 时优先，不加抖动）否则 `ttl ± 抖动`（实际幅度 ≤ `ttl / 2`，至少 1 ms）；`ttl = null` 为永不过期。负缓存总是 `absentTtl`（不抖动）。所有寿命钳到 `[1 ms, 100 年]`。
- **线程模型**：同步 API 上 loader 总是在调用方（SingleFlight leader）线程上同步执行，事务、SecurityContext、MDC 等 ThreadLocal 可用。Lettuce 的 Future 只包装 L1 I/O，引擎在调用方线程上等待，不在事件循环线程上执行任何续接。异步视图的线程模型见 §5.6。
- **L1 未命中就是未命中**：key 不存在、处于 `L` 或 `E` 状态都不是负缓存；负缓存只有状态 `M`。
- **版本前进的填充是一次写入**（RFC 0005 §4.7）：`FILL` 返回 `Filled(advanced = true)`（版本更大，或填充了 `E`）时存储已广播，而这一帧的来源是本节点、在本节点被过滤。引擎因此自己递增戳、淘汰本地副本（`AbstractEngine.afterAdvancedFill`，同写入方修改 L1 之后的那一步），不填充 L2，返回回源值；下一次读取多一次 L1 往返。没有这一步，绕过 SingleFlight 的同节点读取者可以把旧版本写回 L2。批量回源同样如此（整批一次）。
- **结果未知的带版本填充按写入处理**（TLA+ F2）：带版本的 `FILL` 失败、且不是确定未执行（存储在写出之前的拒绝等，`AbstractEngine.notExecuted`）时，引擎按结果未知处理（`FillOutcome.UNKNOWN`）：超时或连接替换时被排空的 `FILL` 可能稍后才执行、让版本前进并广播，那一帧的来源是本节点。所以引擎同样做上面那一步——递增戳、淘汰本地副本、不填 L2（非原子广播时发布）——返回回源值，计入 `fillRejected`。这一步挡住已经读过 L1 的同节点读取者（`load.timeout` 注销卡住的加载之后领头的读取）；这之后、迟到的 `FILL` 执行之前开始的读取仍可能填入旧版本：
  - 原子广播时，引擎在这一步的递增之前登记这个 key（P12，§6.1）：迟到的 `FILL` 在失败之后 `localMaxAge` 之内广播的帧，本节点照常处理，淘汰这样填入的旧版本；窗口之后的帧被过滤，残余以 `localMaxAge` 为界。
  - 非原子广播时，迟到的 `FILL` 不带广播，这里的发布可能早于它执行，各节点（含本节点）的界都是写入方的 `putIfNewer`（版本相等时广播，F1）或 `localMaxAge`。

  多做的一次淘汰只多一次 L1 往返。不带版本的填充从不广播，失败时照常降级填充。
- **版本拒绝**：lease 取在 `E` 上、回源得到的版本更旧（数据源落后）时 `FILL` 拒绝并清零令牌（`Rejected(v)`）：回源值是唯一的答案，照常返回，以降级限龄写入 L2，计入 `fillRejected`；`E` 保留，迟到的旧版本仍被拒绝。在 loader 追上之前，`evictKeepingVersion` 之后读到的是 loader 的版本（RFC 0005 §4.7）。刷新路径的版本拒绝见 §5.5。
- **不设刷新点的副本**（P14）：刷新只会再读到同样内容的副本，`refreshAt = FOREVER`：读到更高格式（未知的必须理解的位）而绕过 L1 的副本、lease 在 `E` 上而版本被拒绝的副本、读到带摘要段的值（`Store` 写入的，`READ` 从不为它授予刷新令牌）的副本。
- **K16**（RFC 0005 §4.1）：带版本的数据源以 `Versioned(null, w)` 表达删除（写成带版本的墓碑）；返回不带版本的 `null`（“从来没有这个 key”）而 L1 中是带版本的内容（`E` 或刷新令牌所在的带版本的值）时，`FILL` 以版本拒绝（`NO_VERSION` 低于任何版本）。回源值（不存在）照常返回，以降级限龄进入 L2，计入 `unversionedAbsentRefusals`（`cocache.fill.unversioned.absent`）与 `fillRejected`，每个缓存第一次告警：已删除的实体在 `E` 到期之前每次未命中都回源，刷新路径保留旧副本到 L1 的 `ttl`。

### 5.1 没有数据源的读取与 `Unknown`

没有数据源按**这次读取**判断（读不懂的内容同样如此，§5.4）：权威副本（`Store`、`VersionedStore`、`SetStore`）与外部来源的 `Cache`（`externallySourced()`）上不带 loader 的读取没有数据源；`get(key, loader)` 的 loader 是数据源。

- **L1 中没有值**：不向 L1 写负缓存（否则会挡住其它节点 `put` 之后的读取），只在 L2 中记下（`absentTtl`，受 `localMaxAge` 限龄）。
  - 权威副本：L1 是唯一副本，这就是**已决的不存在**（`Absent`）。权威副本的读取**不申请 lease**（C11）：没有数据源可以回源，lease 只会让其它节点白等 `lease.wait`；L1 中是 lease 占位（派生缓存的读取留下的）同样是不存在。
  - 外部来源的 `Cache`：**未填充**（`LocalEntry.unfilled`，运维查看为 `UNFILLED`）：不带 loader 的读取看到 `Absent`，`get(key, loader)` 不接受它，用调用方的 loader 回源。未命中照常申请 lease，没有数据源时随即释放。
- **L1 读失败**（单 key 的 `read`、批量的 `readAll`）：这一次读取没有答案，结果是 **`Unknown`**（RFC 0005 §4.1，V2）。它不写入任何一层、不广播，计入 `remoteFailures`。调用方返回之前按声明的 `unknownRead` 换算（`UnknownReadMode`，只作用于不带 loader 的 `get` / `getAll` / `lookup` / `lookupAll` 及其视图，计入 `readsUnknown` / `cocache.reads.unknown`）：
  - `THROW`（默认，fail closed）：`get` / `getAll` 抛出 `CacheReadException`（`cause` 为原始异常），`lookup` 返回 `Lookup.Unknown`，`lookupAll` 中受影响的 key 为 `Unknown`。批量读取先完成其余 key，再抛出携带 `unknownKeys` 与 `partialResult` 的 `CacheReadException`。
  - `ABSENT`：当作不存在（`get` 返回 `null`，`getAll` 中缺席，`lookup` 返回到期时间为现在的 `Absent`），**不写入任何一层**，每次故障都重新访问 L1。供把“读不到”当作安全答案的数据使用（如 CoSec 的撤销表：Redis 故障即视为未撤销）；代价是故障期间每次读取都打到 L1。
  - `unknownRead` 是 spec 的字段（`CacheSpec` 只在 `externallySourced()` 时可设、`StoreSpec`、`VersionedStoreSpec`），不在 `CachePolicy` 中，`defaults` 一类的全局配置改不了它；`SetStore` 不允许，总是抛出。接口式声明写在注解上（`@CoCache(unknownRead = ABSENT)`，`CoCache.UnknownRead`）：只用于 `Store`、`VersionedStore` 与 `source = EXTERNAL` 的 `Cache`，其它接口声明 `ABSENT` 时启动失败。配置键 `unknown-read` 只能写在 `cocache.caches.[name]` 中，全局层跳过它（§10.2）。
- 带数据源的读取遇到 L1 读失败按 `readFailure` 处理：未设置或 `DEGRADE` 时绕过 L1 回源（以降级限龄进入 L2），显式 `THROW` 时同样是 `Unknown`，`get` / `get(key, loader)` 抛出、`lookup` 返回它；`unknownRead` 不作用于带 loader 的读取。
- **合并规则**（RFC 0005 §4.1，逐 key 应用）：flight 的共享结果可以是“未填充”或 `Unknown`（`Unanswered`，从不写入 L2）。带数据源的调用方不接受它们（它们来自不带数据源的读取，这次调用本可以自己回源）：重试一次，仍不可用时绕过 SingleFlight 自己运行状态机。例外是 `readFailure = THROW` 时的 `Unknown`：带数据源的读取遇到同样的故障也只能得到它。leader 处于写事务中时（§5.8，`Loaded.owner`），它的结果任何跟随者都不接受，规则相同。
- 跟随者等待超时（`load.timeout`）、回源被拒绝不是 L1 故障：抛出相应异常，`lookup` 同样抛出，不返回 `Unknown`。
- 其余绕过 L1 的分支（等待 lease 超时、失效重试中、更高格式）：有数据源时回源；外部来源时以降级限龄写成未填充的不存在；权威副本读到更高格式时失败（§5.4）。

### 5.2 `get(key, loader)`

与 `get` 走同一条受 lease 与失效戳保护的路径，只是用调用方的 loader 代替 spec 中的数据源。供 Spring `@Cacheable(sync = true)` 使用。

### 5.3 批量读 `getAll`

- 逐个 key 查 L2（进入刷新窗口时同样触发后台刷新）、过 `KeyFilter`；剩余 key 按存储 key 去重后交给 `flights.executeAllSuspending`（协程核心，§5.6）。
- 已在途的 key 等待现有调用；其余 key 由本线程作为 leader，**先完成自己负责的整批，再等待别人的**。
- leader 的一批：每个 key 注册之后取戳 → 一次 `readAll`（为每个 key 申请 lease；失效重试中的 key 不读 L1）→ 一次 `loader.loadAll` → 一次 `fillAll`。
- 批量路径**不等待**其它节点的 lease：`Leased` 与更高格式（§4.2）的 key 直接回源且不写 L1（L2 降级限龄）；权威副本的批量读取不申请 lease，更高格式的 key 失败（§5.4）。格式号低于本格式（含格式 0）或解码失败的 key 与单 key 路径一样条件回收（见 §5.4），再凭新 lease 回源与 `fill`。
- 顺序：先整批回源并写回，再逐个回收损坏的 key。读不懂的 key（`FAIL`）或回收之后的重新加载失败，都不中断其余 key：先完成其余 key（凭 lease 写回或释放），再抛出第一个失败。回源失败（含 `ttlOf` 与 `Codec` 抛出）或没有数据源时释放整批 lease，等释放完成之后才继续；异步变体并发发出全部释放。
- 合并规则逐 key 应用（RFC 0005 §4.1），与单 key 路径相同。以下三种情况，该 key 改走单 key 路径：
  - 共享到的结果开始于本次读取已观察到的失效之前；
  - 结果不可用（带数据源的读取不接受没有数据源的读取留下的“未填充”或 `Unknown`，§5.1）；
  - 合并到的 leader 被中断或取消。
- Redis：每个 key 一条 EVALSHA，在共享连接上并发发出（约一次往返）；Cluster 按 key 路由，不会 CROSSSLOT。

### 5.4 损坏与格式演进

- **更高的格式**（§4.2：格式号高于本节点，滚动升级期间；格式 2 中未知的必须理解的位或状态，存储报告为更高格式）：计入 `unknownFormats`，**不回收、不写 L1**，否则新旧节点互相覆盖。派生副本绕过 L1：有数据源时回源，L2 降级限龄；外部来源时只得到未填充的不存在。
  权威副本绕过 L1 只能得到“不存在”，于是抛出 `CacheReadException`（`cause` 为 `ForeignFormatException`，`newer = true`），不写 L2，批量读取与下面的格式 0 相同。理由同下：L1 是唯一副本时，混合版本滚动升级期间读成不存在就是悄悄丢数据。
- **摘要校验失败**（K3，§4.2）：权威副本读到摘要段与载荷不一致的值、或不带版本也不带摘要段的值，按同格式的损坏处理（见“格式号低于本格式或同格式解码失败”一条）：抛出 `CacheReadException`（`cause` 为 `ForeignFormatException`，格式为本节点的格式），`lookup` 同样抛出，L1 中的唯一副本保留。
- **类型不符**（D4）：派生副本读到带摘要段的值，说明同一个 namespace 上还有 `Store`。照常读取，计入 `typeMismatches`（`cocache.type.mismatch`），每个缓存第一次告警；解码失败时也不回收它（`RECLAIM` 拒绝带摘要段的内容），绕过 L1 回源。
- **格式号低于本格式（含格式 0）或同格式解码失败**（读不懂的内容）按**这次读取有没有数据源**决定，不可配置（RFC 0005 §4.7）：有数据源（spec 的 loader，或 `get(key, loader)` 的 loader）时条件回收，没有时失败。没有数据源时回收就是删除唯一副本（RFC 0005 §12）。这只决定读取：元素写入（§6.4）、条件写入（§6.5）与按版本写入（§6.6）落在读不懂的内容上时总是拒绝。
  - 回收：计入 `reclaims`，计算本次读到内容的指纹（SPI 自己的 `ContentFingerprint`，K12），调用 `reclaim`；成功则凭新 lease 正常回源与 `fill`，失败（内容已变、脚本拒绝或 L1 异常）则不写 L1 回源；回收失败时（确定未执行除外）后端尽力释放这次请求的 lease（§9，K14）。每次读取至多回收一次。
  - 失败：计入 `unknownFormats`，抛出 `CacheReadException`（`cause` 为 `ForeignFormatException`，带存储 key、格式版本与本节点的格式版本），不回收、不写 L2，与 `readFailure` 无关（降级模式下同样抛出）。批量读取先完成其余 key（释放 lease 或凭 lease 写回），再以第一个这样的 key 失败。理由：没有数据源时回收就是删除唯一副本，调用方会把“不存在”当作真实结果。
  - 元素写入（§6.4）：`SetStore` 的 `add` / `remove` 落在读不懂的内容上总是以 `CacheWriteException`（`cause` 为 `ForeignFormatException`）失败，原子地不修改 key、不广播，计入 `unknownFormats` 与 `writeFailures`。否则一次 `add` 就会用一个成员替换掉读不懂的集合的全部成员。SPI 因此只有“检查并写入”（`SetL1Store.addMembers` / `removeMembers`），没有整体覆盖的元素写入。
  - 整体覆盖写入（`put`、`putAll`，含 `SetStore.put`）**不检查**旧内容，有意如此：调用方给出的是 key 的全部内容，覆盖不依赖旧内容。
- 读路径**从不无条件删除**。
- **`SetStore`**（L1 是唯一副本，没有数据源可回源）：更高的格式（`!f` 大于 `2`、`!m` 中有位，§4.2）计入 `unknownFormats` 并抛出 `ForeignFormatException`（一个 `IllegalStateException`），不写 L2；不是本格式写入的内容（格式 0，如原生 Set；`!f` 低于 `2` 的集合）计入 `unknownFormats`，抛出 `CacheReadException` 且不写 L2、不删除（集合缓存从不回收）；同格式内容的元素解码失败时读取直接抛出，不计为命中。

### 5.5 提前刷新

- `refreshWindow` 非 null、spec 有 loader、L2 副本到达 `refreshAt` 时，命中照常返回当前值，并把一次刷新提交到刷新执行器。每个节点每个 key 同一时刻至多一次（`refreshing` 集合），刷新经 SingleFlight 与并发未命中合并。取得 `refreshing` 之后再确认 L2 仍是触发时读到的那一份副本：命中路径“读副本”与“取守卫”之间上一次刷新可能已完成（它先替换副本、再释放守卫），这时的触发来自旧的观察，直接放弃，否则同一节点会对同一 key 多刷新一次。
- 刷新先 `read(k, refresh = RefreshRequest(window, 新令牌, lease.ttl))`：取得令牌的节点回源并 `fill(r)`；没取得的节点只从 L1 重新填充 L2；L1 已无值时退回普通 `loadThrough`。
- **没取得令牌时退避**（`CoherentEngine.refill`，同步与异步共用）：重新填充时 L1 若仍在窗口内，说明令牌在别的节点手里、它正在回源。按 L1 剩余寿命算出的刷新点这时不晚于现在，不退避的话本节点的每次命中都会再读一次 L1；持有者崩溃时这种轮询持续到令牌签发超过 `lease.ttl`，放大倍数是热点 key 数 × 节点数。因此这份副本的刷新点设为 `now + min(b, 剩余寿命 / 2)`，`b = max(lease.wait, 100 ms)`：`lease.wait` 是未命中等待别的节点回源的时长，刷新在同一时间尺度上再看一次；下限 100 ms 是默认时钟（`TickingClock`）的刷新间隔，`lease.wait` 可以为 0。
  - 刷新点总是早于 L1 到期（不足 1 ms 时不设）：持有者写回之后，非持有节点在旧值到期之前取到新值，不因刷新多出一次未命中。`fill` 不广播（广播会淘汰所有节点的副本，下一次命中变成未命中，抵消提前刷新），所以非持有节点只靠这次再读取到新值。再读时若令牌签发已超过 `lease.ttl`（持有者崩溃），它接管令牌回源。
  - 每个节点每个 key 在一个刷新周期内的 L1 读取次数与命中频率无关：约为“持有者回源耗时 / b”，加上临近到期时按剩余寿命减半的几次（至多约 log2(2b / 1 ms) 次；默认时钟每 100 ms 才前进一次，实际只有两三次）。
- **寿命不长于窗口的值不参与提前刷新**：除上面的重新填充之外（回源写回、读到 L1、降级填充），L1 剩余寿命不长于窗口时不设刷新点（`refreshAt = FOREVER`）。否则副本一出生就在窗口内：持有令牌的节点每次 `fill` 之后令牌被清零，下一次命中又取得新令牌、再回源一次，被放大的是数据源。策略 `ttl` 给出的寿命由 §10.2 的校验保证长于窗口；`ttlOf` 或 `put(k, v, ttl)` 给出的更短寿命不报错，这样的值不刷新，照常到期、下一次未命中时重新加载。代价：在一个值的最后一个窗口内才读到 L1 的节点（第一次读或 L2 刚被淘汰）也不发起刷新。其它节点的副本照常触发，全集群仍然只刷新一次；只有所有节点的副本都是这种副本时，key 才按普通未命中重新加载。
- **带版本的值照常提前刷新**（RFC 0005 §4.7）：版本在版本段中，令牌位置是空的，`READ` 照常授予刷新令牌，填充按版本规则写入。回源得到更大的版本时写回是一次写入（§5“版本前进的填充”）：广播，其它节点的副本被淘汰，本节点不填 L2。版本相等时是续期，不广播。回源得到的版本更旧（数据源落后于按版本写入）时 `FILL` 拒绝并清零令牌：刷新不写 L2，保留副本——以取得令牌时读到的那个更新的值重新填充 L2，刷新点按上面的退避——否则回源得到的旧值会替换掉 L2 中的新值（`CoherentEngine.keepRefreshedCopy`，计入 `fillRejected`，不计 `degradedFills`）。
- **带版本的缓存的 L2 不按版本后退**（I-K 的 L2 条款，F3）：版本拒绝只覆盖 `FILL` 回复了版本的情形；令牌被抢占（回复不带版本）时刷新照常降级填充，落后的 loader 读到的旧版本会替换 L2 中的新副本。所以填充 L2 时（`fillLocal` 的锁内，`CoherentEngine.keptCopy`），已有的未过期副本的版本大于要填充的值的版本就保留它，计入 `staleFillsDiscarded`；保留的副本已过刷新点时刷新点按上面的退避，否则每次命中都再读一次落后的数据源。能走到这一步的填充，其戳在读取之后没有失效：本节点没有处理过这个 key 的写入或失效，已有的副本不是被失效的内容。没有版本的值（`NO_VERSION`，负缓存与从 L1 读到的墓碑解码为 `null`）排在任何版本之前，所以协议之外抹掉版本的变化（L1 到期、`purge`）不经刷新替换版本更新的副本，由它自己的限龄兜底。结果未知的刷新填充按写入处理（§5“结果未知的带版本填充”），不填 L2。
- 结果：全集群每个 key 一次刷新。`invalidate`、`put`、`putIfNewer`、`evictKeepingVersion`、元素写入都会销毁刷新令牌。
- 执行器拒绝时计入 `refreshRejected` 并跳过；刷新失败只告警，值照常到期，G1 不变。
- **到期却没有运行的刷新计入 `refreshSkipped`**（`cocache.refresh.skipped`，RFC 0005 §8）：刷新读没有取得令牌、只从 L1 重新填充并退避（`refill`）；或提前刷新本该覆盖的副本（有值、有限寿命、缓存设了窗口）没有刷新点：P14 的内容（未知标志位、`E`、摘要段、版本被拒绝），或 L1 剩余寿命不长于窗口（`AbstractEngine.localEntry`）。默认执行器是有界守护线程池（≤ 4 线程，队列 1024，满时拒绝）。

### 5.6 三种变体与协程核心

协议只有一份 `suspend` 实现（协程核心，RFC 0005 V5，I-I）：单 key 与批量读取（含等待 lease、条件回收、回源与写回）、提前刷新、全部写入（`put`、`putAll`、`invalidate`、条件写入、按版本写入、集合的元素增删与 `clear`）、条件读取（`getIfChanged`）与数据源适配都只写一次。同步 API、异步视图（`async()`，[RFC 0004](rfc/0004-async-api.md)）与挂起视图（`suspending()`，§5.7）是它的三种运行方式，共享 L2、失效戳、SingleFlight、lease、回源许可与指标；引擎中没有成对的同步与异步实现。

- **命中**：L2 命中不启动协程。同步 API 内联返回；异步视图做同样的 L2 查找与过期比较，返回已完成的 future（在调用方线程上）；挂起视图见 §5.7。
- **同步变体**（`runSync`）：未命中与写入时以 `runBlocking` 在调用方线程上运行核心。L1 命令与异步变体一样直接调用挂起的 `L1Store`，在 `NonCancellable` 中执行（`uncancellable`，RFC 0005 §4.3）：调用方线程停在 `runBlocking` 的事件循环上，续接仍在它上面执行，从不在 L1 客户端的 I/O 线程上执行；阻塞的 loader 在调用方线程上调用（I-6）。写入修改 L1 之后的步骤在 `NonCancellable` 中执行，调度器不变。
- **同步变体被中断**（RFC 0005 §4.3）：中断不到达存储。`runBlocking`（kotlinx ≥ 1.10，`RunSyncTest` 固定这一行为）清除中断标志、取消协程，并在调用方线程上把协程运行到结束才返回：
  - 在途的 L1 命令先完成或超时，取消在之后的第一个挂起点生效（跟随者的等待、回源许可、lease 的退避、等待异步数据源），持有令牌的步骤按正常路径释放令牌。调用阻塞的 loader 与回源观测之前另外检查（`ensureNotInterrupted`）：取消之后不再调用它们；中断落在两个挂起点之间、`runBlocking` 尚未处理时（中断标志仍置位）同样不再调用。写入修改 L1 之后的步骤照常执行。
  - 取消生效时恢复中断标志并抛出 `InterruptedException`。取消之后协程没有再经过检查取消的挂起点、自己结束了的（如 L1 命中、在途的 `put`），照常交出结果（值或它自己的异常），同样恢复中断标志：被取消的协程总是以取消结束，结果由 `runSync` 另行带出。
  - 协程没有被取消、核心自己抛出的 `InterruptedException`（loader 被中断，或跟随者收到 leader 的这类失败）原样抛出，不置中断标志。
  - 代价：被中断的调用方至多多等一个命令超时（默认 2 s）。让 `runBlocking` 立即返回、协程在别的线程上收尾的做法被否决：命令可能在调用方看到失败之后才写出。
- **异步变体**（`AsyncContext.runAsync`）：在运行时作用域中以 `future(start = UNDISPATCHED)` 运行，第一次挂起之前在调用方线程上，之后在运行时的执行器上继续（作用域的调度器），从不在 L1 客户端的 I/O 线程上执行。用户拿到结果的副本：取消它只影响自己，协程照常运行（它可能是别的调用方等待的 leader）。执行器拒绝派发时 future 以那个 `RejectedExecutionException` 失败；运行时关闭取消作用域，进行中的调用在下一个挂起点以 `IllegalStateException("CacheRuntime closed.")` 失败；正在等待的 L1 命令先完成，不被放弃（见下面的“令牌”），`close` 有界地等这些收尾结束才返回（§10.1）。
- **调度**（`EngineDispatcher`，RFC 0005 §4.4、§5）：协程在调用方线程上开始，执行到第一次挂起；之后运行时作用域的调度器把**每次**恢复都派发给**运行时的执行器**（`CacheRuntime.executor`，§10.1），从不在完成上一步的线程（L1 客户端的 I/O 线程、数据源自己的线程）上就地继续。没有就地恢复，也没有续接标记：
  - 每个在执行器之外完成的步骤派发一次（`AsyncDispatchTest`）：L1 命中 1 次；经同步 loader 的未命中至多 4 次（L1 读之后、在执行器上执行 loader、loader 完成之后、fill 之后），经异步 loader 的未命中至多 3 次（L1 读之后、数据源的 future 完成之后、fill 之后）；loader 的 future 在协程等待它之前就完成时，等待不挂起，少一次；写入 1 次（回到调用方，见下面的“写入”）。
  - 数据源在调用期间同步完成的其它 key 的 future、同步 leader 归还回源许可时唤醒的等待者，都派发出去恢复，不在本线程对本 key 的重入守卫作用域内执行，因此不会被误判为同一线程的重入。
  - 派发的任务执行期间，线程带有本调度器的标记（`EngineDispatcher.onDispatchedThread`），只供 `close` 判断调用方是不是作用域中的协程（§10.1）。
  - 返回的 future 在调用方线程或运行时的执行器上完成（调用方注册的依赖阶段按 JDK 规则也可能在随后调用 `get()` / `join()` 的线程上执行，TCK 断言完成线程时先等依赖阶段执行完再等待结果）。
  - 执行器拒绝时以携带拒绝的取消结束协程，再交给 `Dispatchers.IO` 跑完收尾。正在执行的 L1 调用（上下文带有 `L1Call` 标记）被拒绝时不取消，只交给 `Dispatchers.IO`：取消会让 `withContext` 的作用域丢掉调用的结果（可能带着令牌），存储中可取消的等待也会被取消；调用方的协程在之后的派发被拒绝时才被取消（运行时关闭时它已随作用域取消）。
- **数据源适配**（三种变体共用，`CoherentEngine.invoke`）：
  - `asyncLoader` 在当前线程上调用（同步变体即调用方线程），等待其结果的副本并加 `LoadPolicy.timeout`（默认 30 s，由 JDK 的延迟线程以 `completeOnTimeout` 的私有哨兵值计时；数据源自己以 `TimeoutException` 失败时原样传递）。超时以 `SourceLoadTimeoutException` 失败、计入 `loadTimeouts` 并释放 lease 与回源许可，数据源的 future 不被取消、迟到的结果被丢弃。
  - 同步 `loader`：同步变体在调用方线程上调用；异步与挂起变体在运行时的执行器上执行（与续接是同一个执行器，所以它必须容得下阻塞的任务，§10.1）。不加超时（阻塞的 loader 无法被打断）。
  - 数据源自己以取消结束（抛出或以 `CancellationException` 完成，如数据源内部的 `withTimeout`、被 HTTP 客户端取消的 future）时，当前协程仍处于活动状态，就转为 `SourceLoadCancelledException`（`cocache-core`，`RuntimeException`，`cause` 为原始异常），与其它回源失败一样交给合并到这次加载的全部调用方（同步、异步与挂起）。原样传出的话，引擎各处（`rethrowIfFatal`、批量读的逐 key 收尾）会把它当作本协程被取消，挂起的调用方会把它当作自己被取消。当前协程已被取消时，它就是这次取消，原样传出（K5）。
  - 回源观测只经 `LoadObserver.observe`（§13）：同步变体在调用方线程上调用它；异步与挂起变体在观测专用的线程上调用它（每次被观测的回源一个虚拟线程，`cocache-observe-N`，与运行时的执行器无关），这个线程等待整个回源结束，block 从它上面开始。不在运行时的执行器上等待：有界的执行器上，等待中的观测会占满线程，回源的续接排在同一个执行器上，形成死锁。
- **等待不占线程**：
  - 等待其它节点的 lease：退避、截止时间、令牌与戳在各变体中相同，都以 `delay` 挂起退避：同步变体停在调用方线程的事件循环上，线程被中断时立即结束；异步变体由 kotlinx 的延迟线程计时，不占执行器线程。
  - 回源许可：各变体共用一个 kotlinx 信号量，按到达顺序交接；`acquireTimeout` 内拿不到时以 `SourceLoadRejectedException` 拒绝。等待挂起协程（同步变体停在调用方线程上，异步变体不占线程），等待被取消（运行时关闭、线程中断）时不取得、也不泄漏许可。
- **SingleFlight**：各变体共用一张表（单 key `executeSuspending`，批量 `executeAllSuspending`）。leader 在调用方的协程中执行：同步变体即调用方线程，异步变体省去一次派发；上下文中没有执行变体的调用方（直接从用户的协程调用）可能被单独取消，共享加载改在运行时作用域中执行。leader 在注册之后开始（第一件事是取戳），先注销、再发布；读己之写至多重试一次。同步调用合并到异步调用时只阻塞自己的线程；跟随者挂起等待，不占线程。每个调用方拿到自己的结果：取消只结束它自己的等待，不取消共享加载、不取消数据源的 future。
  - 合并规则（RFC 0005 §4.4）：leader 被中断或取消时，leader 自己收到原始异常，跟随者不接受这个失败，自行运行。按 leader 自己的状态判断，不按异常类型：
    - leader 的协程已被取消（运行时关闭、执行器拒绝派发、同步变体的 `runBlocking` 处理了中断）；
    - 同步 leader 的调用方线程被中断：loader 在调用方线程上抛出 `InterruptedException`，或带着中断标志失败（loader 包装了中断并恢复中断标志）。

    跟随者先重新注册一次（leader 已注销，通常由它自己领头）；仍合并到被中断的 leader 时，绕过 SingleFlight 直接加载，所以重试有上界。批量合并到的这类 key 改走单 key 路径。

    其余失败都是普通的回源失败，与其它失败一样交给跟随者：数据源自己的取消（已在数据源边界转为 `SourceLoadCancelledException`，见上面的“数据源适配”）、block 中其它代码抛出的 `CancellationException`、运行时执行器线程上的 `InterruptedException`。按异常类型判断的话（K5），数据源内部的 `withTimeout` 或被取消的 future 会让每个跟随者各自回源，源头慢的时候回源次数随并发放大（实验 E4：8 个调用方回源 8 次）。
  - 有上界（`load.timeout`，默认 30 s）：每次加载的截止时刻是它登记时加上 `load.timeout`。
    - 跟随者（各变体，含批量合并到的 key）等到截止时刻仍没有结果时，以 `SourceLoadTimeoutException` 失败，计入 `loadTimeouts`。
    - 截止时刻之后到来的调用方把这次加载注销（不完成它），自己开始新的加载，不再合并到卡住的同步 loader 后面（P16）。注销是惰性的，不需要定时器。
    - 原 leader 迟到的结果只交给它自己，写回仍受令牌与戳保护。注销早于发布，不破坏“先注销再发布”。
- **重入**：除同步调用的拥有线程外，引擎在调用数据源期间（同步数据源的整个执行、异步数据源 `loadAsync` 的同步部分）为这些 key 设置线程守卫，期间本线程对同一 key 的同步或异步调用快速失败。异步数据源在一次 I/O 之后再读同一 key 形成的环无法检测，由 `load.timeout` 打破。挂起变体另有协程上下文中的守卫（§5.7）。
- **写入**（`AbstractEngine.write`）：顺序同 §6.1；编码在第一次递增之前、在调用方线程上完成。修改 L1 之后的步骤（非原子广播的后端经挂起的 `InvalidationChannel.publish` 发布、失败时的 `onFailure`、第二次递增与淘汰）不可取消。同步变体在 `NonCancellable` 中执行它们，调度器不变（见上）；异步与挂起变体不经执行器，在 L1 命令的完成线程上就地执行（`ASYNC_WRITE`，即 `NonCancellable + Dispatchers.Unconfined`，这些步骤都不阻塞、不调用用户代码），在返回的 future 完成之前执行，取消返回的 future、运行时关闭、执行器拒绝都不会跳过它们，之后只派发一次：回到调用方。被取消的写入见 §7。
- **提前刷新**：同步数据源在刷新执行器上执行（`runSync`）；异步数据源的刷新在刷新执行器上发起、以异步路径推进，不在刷新执行器的线程上等待。
- **执行器拒绝任务**：续接被拒绝时该步不执行，返回的 future 以 `RejectedExecutionException` 失败；必须执行的收尾不依赖执行器：写入方的广播、第二次递增与淘汰在 L1 命令的完成线程上就地执行（见上），写入的结果不被替换：成功的写入不计为失败、`invalidate` 不登记重试。回到调用方的那次派发被拒绝时，成功的写入以 `RejectedExecutionException` 完成，失败的写入仍以原始异常完成（kotlinx 在协程同时被取消与失败时保留失败的异常）；回源许可在加载链结束时恰好归还一次，取得的 lease / 刷新令牌在链因拒绝失败时由持有它的步骤释放（见下面的“令牌”）。
- **`KeyFilter`** 在调用方线程上调用，必须是本地、不阻塞的判断。
- **异常**：返回的 future 以原始异常完成（不包装为 `CompletionException`）；异步方法从不同步抛出（包括 `KeyEncoder`、`Codec` 抛出与重入）。
- **令牌**（I-F、I-H：L1 命令与令牌步骤不可取消）：
  - 回源失败（含 `ttlOf` 与 `Codec` 抛出）时先释放 lease 再抛出原始异常。
  - 引擎从不取消 L1 调用：每个调用在 `NonCancellable` 中执行（`uncancellable`；写入方顺序整段已在 `NonCancellable` 中，其中的调用直接执行），存储用可取消的 `await()` 也安全（`NonCancellableL1Test`）。协程被取消（同步变体的线程被中断；异步变体的运行时关闭、执行器拒绝派发）时仍等到调用结束：取得令牌的读取不丢结果，释放也不会抢在仍在途的读取之前到达 L1（SPI 不保证命令按发出的顺序生效）。调用何时结束由存储决定（命令超时）。被取消时立即放弃等待、即发即弃地释放令牌的做法不可行：释放先于在途的读取生效时，读取随后取得的令牌要到 `lease.ttl` 才回收（`TokenConservationPropertyTest` 复现）。
  - 取消在之后的第一个挂起点生效（等待数据源、回源许可、lease 的退避）。持有令牌的步骤据此释放它，并等释放完成。
  - 有观测器时，回源与等待 lease 在运行时作用域的另一个协程中执行。它的结果经局部变量交回，不经协程的返回值：被取消的协程即使 block 已经返回，也以取消结束、丢掉返回值，而等待 lease 的重读可能已为本调用取得令牌。
- **等待 future 经 `awaitOriginal`**：kotlinx 的 `await()` 在调试模式（`-ea` 或 `kotlinx.coroutines.debug`）下恢复挂起时复制异常，原始异常成了副本的 cause；这里等待一个总是正常完成的派生 future，在自己的栈帧里抛出原始异常。L1 调用同理：`uncancellable` 把结果包成 `Result` 带出 `withContext`，`FutureL1Store.adapt` 与 `FutureInvalidationChannel.adapt` 总是以成功的结果恢复续接，都在自己的栈帧里抛出原始异常（`FutureAdapterTest`）。调试模式下线程名在协程运行期间带有 `@coroutine#n` 后缀（同步读的 loader 也看得到它），TCK 按线程名编排竞态时用 `Concurrency.currentThreadName()` 去掉它。

### 5.7 挂起视图（`suspending()`）

挂起视图的接口在 `cocache-api`（只用标准库的 `suspend`），每种类型有同构的一个（`SuspendingCache`、`SuspendingStore` …），由各类型的抽象方法 `suspending()` 返回，实现在 `cocache-core`：读取共用 `SuspendingReads`（`engine/SuspendingViews.kt`），写入在各引擎的 `SuspendingView` 中。挂起视图直接调用协程核心（RFC 0005 §4.4 K2、K6、K7），不经异步视图。

- **命中路径**：L2 命中内联返回，不挂起、不切换调度器、不分配：读方法与命中判断只在尾部调用下一步，编译器不为它们生成状态机；重入检查先查上下文中有没有 `LoadingKeys`，没有就返回。
- **进入核心**（`AsyncContext.runSuspending`，K2、K6）：未命中、写入与条件读写在运行时作用域中运行同一份协程核心，按异步变体执行（`ExecutionMode.ASYNC`，阻塞的 loader 在运行时的执行器上执行），再等待结果（`awaitOriginal`，抛出原始异常）。协程上下文的元素取自调用方（`ThreadContextElement`、`CoroutineName`、`LoadingKeys`），去掉 `Job` 与调度器。核心第一次挂起之前在调用方线程上执行（与异步变体相同，同线程的重入守卫因此照常生效），之后经运行时的调度器继续，结果在运行时的执行器线程上交出：调用方从不在 L1 客户端的完成线程上恢复。拦截器是 `Unconfined` 的调用方（Spring WebFlux 的挂起处理器默认如此）在运行时的执行器线程上恢复，不会在 Lettuce 的事件循环上继续它自己的代码；否则它在那里再调用同步 API，会卡住事件循环直到命令超时。
- **没有 `ExecutionMode` 的调用方**：直接从协程调用核心时，SingleFlight 的 leader 在运行时作用域中执行，元素同样取自调用方、去掉 `Job` 与调度器（`SingleFlight.callerElements`）。进入核心的规则只有这一条。
- **取消**：调用方被取消只结束它自己的等待；共享的加载与写入在运行时作用域中照常完成，只在运行时关闭时被取消（与 §5.6 相同）。运行时关闭之后的调用以 `IllegalStateException` 失败，执行器拒绝派发时抛出那个 `RejectedExecutionException`。
- **调用级 loader**（`get(key) { … }`）：在这次加载的协程中直接调用，不切换调度器，不另起协程：第一次挂起之前在调用方线程上执行，之后在运行时的调度器上继续。`ThreadContextElement` 随之传递。它是共享加载的一部分，不随调用方取消；超过 `load.timeout` 时被取消（协作式），合并到这次加载的调用方以 `SourceLoadTimeoutException` 失败。
- **spec 级 loader**（`suspendingLoader(context) { … }` / `asAsyncCacheLoader(context)`）：在给定的 `context` 中启动（默认 `Dispatchers.Default`），作为 spec 的 `asyncLoader`，受 `LoadPolicy.timeout` 约束。超时不取消协程（与 `AsyncCacheLoader` 相同）。每个 loader 一个 `SupervisorJob`（父 Job 为 `context` 中的 Job）：一次失败不取消其它加载，取消父 Job 取消之后的加载。声明的 `AsyncCacheLoader` 的 `loadAsync` 在共享加载的协程中调用，看得到调用方的 `ThreadContextElement`；阻塞的 `CacheLoader` 在运行时执行器的线程上调用，看不到。
- **以取消结束的加载**转为 `SourceLoadCancelledException`（`cocache-core`，`RuntimeException`，与 `SourceLoadTimeoutException` 一致）：数据源自己抛出的 `CancellationException`（如数据源内部的 `withTimeout`）；在 spec 级 loader future 的边界上，加载协程被取消或在已取消的作用域中启动（spec 的 `context` 中的 Job 已被取消）。引擎对全部数据源同样转换（§5.6“数据源适配”）。加载可能被共享，原样传出会让挂起的调用方当作自己被取消而吞掉；转换后同步、异步与挂起的调用方都收到它。`SupervisorJob` 永不完成，因此 `context` 中的 Job 应是长生命周期的，loader 应创建一次、反复使用。
- **同 key 重入**（K7）：在 §5.6 的守卫之上增加，不替换它们。同步变体的拥有线程检测与异步变体在 `loading` 的同步调用段的 `ThreadLocal` 都保留：协程上下文元素穿不过 `runBlocking` 与 `future {}`，只换成元素会让同步变体的同线程重入退化为等待 `load.timeout`。调用级 loader 的协程上下文带 `LoadingKeys`（引擎实例与 key），loader 挂起之后在别的线程上继续时，经挂起视图读取正在加载的 key（`get`、`getAll`、`lookup`、`getVersioned`）立即以 `IllegalStateException` 失败，而不是等待自己直到 `load.timeout`。按 key（`K`）的相等性匹配，不按编码后的存储 key。spec 级挂起 loader 按缓存名匹配（声明时不知道运行时），不继承调用方的记录（跨缓存循环要到 `load.timeout` 才失败）；单独使用的 `asAsyncCacheLoader` 没有这项检查。loader 经 future 或 `runBlocking` 换线程之后的重入检测不到，以 `load.timeout` 为界。
- 不阻塞任何线程；loader 与调用方的代码都不在 Lettuce I/O 线程上执行。

### 5.8 写事务内的回源（`FillObserver`）

`cocache-core` 只提供钩子，事务语义由集成方给出（Spring 的实现见 §11.3）。`FillObserver` 经 `CacheRuntime.Builder.fillObserver` 设置，存于 `EngineContext`，默认 `FillObserver.NONE`。

- **调用入口捕获一次**（S7）：同步变体（`get`、`lookup`、`getAll`、`lookupAll`、`get(key, loader)`）在 L2 未命中、且这次读取带数据源时，在调用方线程上调用 `enter(cacheName)` 一次（默认同 `enter()`；按缓存配置的实现对两个机制都关闭的缓存返回 `null`），返回的 `FillScope` 作为协程上下文元素（`FillContext`，与 `ExecutionMode` 分开）随 `runSync` 交给未命中路径；之后的门控与记录只用它，不在填充时读线程绑定的状态。L2 命中、没有数据源的读取、异步与挂起变体、提前刷新都不调用它，一律不门控、不记录：它们的 loader 不在调用方线程上，不在调用方的事务内。
- **私有回源**（机制 2）：`isPrivate(key)` 为真的 key 不申请 lease、不读也不写 L1、不写 L2、不注册 SingleFlight，回源结果只交给这次调用（`Lookup.Present` 的到期时间为现在），计入 `fillsGated`（`cocache.fills.gated`）。回源照常受许可与观测约束；本线程对这些 key 的重入快速失败（`SingleFlight.loadingPrivately`，与领头的同步调用共用重入标记）。批量读取逐 key 判断，私有的 key 一次批量回源。
- **记录填充**（机制 1）：数据源产生的值写入 L1 或 L2 之前（凭 lease 的 `FILL`、只写 L2 的降级填充、批量写回），经 `filled(key)` 记入事务；读到 L1 已有的值不记录。写入失败时多一次失效，无害。
- **leader 不共享**（S15）：带 `FillScope` 的调用作为 leader 时，`Loaded` 带上它（`owner`），跟随者（不在它的事务内）按合并规则不接受、自行回源：否则其它调用方经 flight 拿到未提交的值，回滚之后撤不回。
- **`FillKey`** 是 (keyspace, 编码 key)（S24），与经哪个对象访问无关（引擎、接口代理、`JoinCache` 的一侧、另一个运行时中同一 keyspace 的缓存相等）。`FillKey.of(cache, key)` 解开接口代理，不是 CoCache 的单值引擎时为 `null`；`evictLocal()` 只在本节点递增戳、淘汰 L2；`FillKey.invalidateAll(keys)` 按缓存分组，每个缓存一次走正常的失效路径（同 `CacheAdmin.invalidateEncoded`：派生副本失效、带版本的派生副本保留版本），一个缓存失败不影响其余缓存，最后抛出第一个异常。
- 命中路径不变：`enter(cacheName)` 只在未命中路径上调用，`NONE` 时没有上下文元素、没有分配。

## 6. 写路径

每个写入都按写入方顺序执行（§6.1），从不写 L2（I-1）。Redis 上各写入的 L1 命令与往返次数：

| 操作 | L1 | 往返 |
|------|----|------|
| `put` | Lua：`DEL` + 按形状写入 + `PEXPIRE` + `PUBLISH` | 1 |
| `putAll(N)` | `L1Store.putAll`：分组方式同 `invalidateAll`，每组一个 `PUT_ALL` 脚本（每个 key 的转换与 `PUT` 相同）与一次 `PUBLISH`（只含本组的 key），并发执行；原子性只在单个组内成立 | 单机 ⌈N/512⌉，Cluster 约 N |
| `invalidate` | Lua：`DEL` + `PUBLISH` | 1 |
| `invalidateAll(N)` | 单机：按 `invalidateBatchSize`（默认 512）切分，每批一个脚本。Cluster：按 slot 分组再切分，每组一个脚本与一次 `PUBLISH`，并发执行；原子性只在单个 slot 内成立 | 单机 ⌈N/512⌉，Cluster 约 N |
| `putIfNewer` / `invalidateIfNewer` | Lua：比较版本，更新时 `DEL` + `SET`（带版本的头）+ `PEXPIRE` + `PUBLISH`（§6.6） | 1 |
| `add` / `remove` | Lua：检查内容能否当作本协议的集合，能则成员写入 + 重设 TTL + 销毁令牌 + `PUBLISH` | 1 |

- `put` 的寿命：`ttlOf ?: 策略寿命`；`put(key, value, ttl)` 用给定时长。都钳到 `[1 ms, 100 年]`。
- `putAll` 每个 key 的寿命按 `put` 逐个计算（各自独立抖动）；`putAll(entries, ttl)` 用同一时长。空 map 不做任何事；编码后碰撞的存储 key 以后出现的为准，每个存储 key 只编码一次。失败抛出 `CacheWriteException`（`operation = "putAll"`），部分 key 可能已生效。非原子广播时引擎在批量写入结束后发布一帧（含全部 key），写入失败时同样发布（§6.1）。`L1Store.putAll` 没有逐个调用 `put` 的默认实现：那样每个 key 都会广播全部 key。
- 调用方必须**先提交数据源，再 `invalidate`**（事务内用 `TransactionalInvalidator`，§11.3）。在提交之前失效本身就是 G2 漏洞：并发回源可能读到提交前的数据。
- 原子广播消除的是 `DEL` 与 `PUBLISH` 之间的客户端窗口；投递仍是至多一次，丢失由 `onReset`、心跳与 `localMaxAge` 兜底。

### 6.1 写入方顺序（`AbstractEngine.write`）

| 操作 | 顺序 |
|------|------|
| `invalidate` / `invalidateAll` | 戳 → 淘汰 L2 → Lua `DEL` + `PUBLISH` → 戳（无论成败）→ 淘汰 L2 |
| `put` | 戳 → 淘汰 L2 → Lua 覆盖 + `PUBLISH` → 戳（无论成败）→ 淘汰 L2。**不写 L2** |
| `putAll` | 同 `put`，一次 `write` 覆盖整批 key：全部 key 戳 → 淘汰 L2 → 批量覆盖 + `PUBLISH` → 全部 key 戳（无论成败）→ 淘汰 L2 |
| `Store.delete`、`VersionedCache` / `VersionedStore` 的 `purge` | 同 `invalidate`，失败时抛出，从不登记重试 |
| `SetStore.add` / `remove` / `clear` / `put` / `putAll` | 同上 |
| `putIfMatch` / `deleteIfMatch` | 同 `put` / `invalidate`。冲突与拒绝（读不懂的内容）不修改 L1：脚本不 `PUBLISH`，非原子广播时引擎只在可能是重放时发布（§6.5）；两次戳与 L2 淘汰照常执行（只多淘汰一次本节点的副本） |
| `putIfNewer` / `invalidateIfNewer` / `deleteIfNewer` | 同 `putIfMatch`：版本更旧与拒绝（读不懂的内容）不修改 L1、不广播；版本相等时不写入但广播（§6.6）；两次戳与 L2 淘汰照常执行 |
| `evict`（`VersionedCache`） | 同 `put`：`EVICT_KEEPING_VERSION` 转为 `E` 并广播；拒绝（`Store` 的值、读不懂的内容）不修改 L1、不广播，失败时抛出，从不登记重试 |
| 收到远端事件 | 戳 → 淘汰 L2（`origin == 本节点 NodeId` 的事件被过滤；结果未知的写入之后的窗口内例外，见下面的 P12） |
| `onReset` / `clearLocal` | 全部戳递增 → 清空 L2 |

后端 `atomicBroadcast = false` 时，引擎在存储操作成功后调用 `channel.publish`；发布失败按写入失败处理（§7）。存储操作失败时引擎**也尽力发布同一帧**再报错：失败的写入可能已部分生效（`putAll` 跨多个 slot 组时有的组已写入，或已在 L1 生效却报错），不发布的话其它节点会一直读旧的 L2 直到 `localMaxAge`（G2）；这次发布的失败只告警（附为写入异常的 suppressed），调用方看到的仍是写入的原始异常，多余的广播只淘汰副本。因此存储的写入方法返回或抛出时，它发出的全部 L1 命令都必须已经结束（`RedisL1Store` 的 `putAll` / `invalidate` 等全部组结束后才抛出第一个异常；某组发出时同步失败也先等已发出的组），否则广播可能先于仍在执行的命令。例外是命令超时：客户端放弃等待时已发出的命令可能仍在 Redis 端执行，补发的广播可能先于它生效，这时其它节点的旧副本由 `localMaxAge` 兜底（G1 的上界不变）。各组并发发出、各自受 `commandTimeout` 限制，失败路径上的等待约为一个 `commandTimeout`。不在失败路径上继续等超时的命令真正结束：超时时后端以异常完成这条 Lettuce 命令，尚未写出的命令不再写出、不会在调用方看到失败之后才生效（§9“超时后不再写出”）；已写出的命令无从撤回，没有应答就无从得知它是否、何时执行（Redis 无法中止已开始的脚本），有上界的追加等待只能缩小、不能关闭这个窗口，却会让失败路径的耗时翻倍、在 Redis 变慢时占住更多调用线程；而窗口内的陈旧本来就受 `localMaxAge` 限制。

**结果未知的写入之后不再过滤本节点的帧**（RFC 0005 §4.6 P12，`AbstractEngine.unfilterOwnFrames`）：
- 写入（`put`、`putAll`、`invalidate`、集合元素增删、按版本写入等）失败、且不是确定未执行（`L1RejectedException`、读不懂的内容的拒绝、权威内容的拒绝、`UnsupportedOperationException`，`AbstractEngine.notExecuted`）时，命令可能稍后才执行并在脚本内广播，那一帧的来源是本节点；失败之后、迟到执行之前，本节点的读取可能已把旧值填进 L2。
- 所以从失败时刻起 `localMaxAge` 之内，本节点对这些 key 不再过滤自己的帧：按远端事件处理（递增戳、淘汰 L2），不计入 `invalidationsReceived`。结果未知的带版本 `FILL`（`FillOutcome.UNKNOWN`，TLA+ F2，§5）同样登记。
- 登记先于第二次递增（`FILL` 先于 `afterAdvancedFill` 的递增）：登记之前到达的迟到帧被过滤，随后的递增仍挡住已经读过 L1 的读取者。
- 窗口之后照常过滤，残余的陈旧上界是 `localMaxAge`，与丢失的帧相同。RFC 的提前结束（同一条连接上之后发出的一条命令完成，再加投递余量）没有实现：`L1Store` SPI 不暴露连接与重连，引擎无从确认，总是保留整个窗口；多付的只是这些 key 上本节点帧的一次淘汰。
- 只对原子广播的后端：非原子广播时（`sharded`，或原子广播探测失败）迟到执行的命令不带广播，不登记，本节点的旧副本由 `localMaxAge` 兜底。
- 登记表是编码 key → 截止时刻，只在失败时写入；超过 1024 条时，登记新 key 的调用删除已过期的登记；收到本节点的帧时，帧中已过期的登记也被删除。

### 6.2 `put` 与 `invalidate` 的取舍

首选 `invalidate`。`put` 只适合写入方算出最终值的场景，且不填充本节点 L2。

### 6.3 外部写入

绕过 CoCache 修改数据源时，外部系统必须对 `{keyPrefix}{namespace}:{key}` 执行 `DEL`，并向 `{keyPrefix}inv:{namespace}` 发布按 §4.1 编码的帧（任意 16 字节 nodeId）。不发布时，其它节点的 L2 陈旧至 `localMaxAge`。`ExternalInvalidator` 按这个布局构造 key 与频道（`keyPrefix` 须与目标缓存相同）：

- `invalidate` 删除 L1 中的 key 并广播。用在权威类型（`Store`、`VersionedStore`、`SetStore`：L1 是唯一副本）的 namespace 上，删除的就是唯一的一份数据。
- `broadcast` 只广播帧、不修改 L1（RFC 0005 §4.9）：各节点淘汰 L2 副本，之后从 L1 重新填充，对任何类型的缓存都安全。用于 L1 已是正确内容、只有其它节点的 L2 可能陈旧的场合。它不创建存储句柄，因此不经存储创建时的频道探测；ACL 不放行频道时发布失败。

### 6.4 `SetStore` 与权威副本的部署前提

数据权威在数据源中的集合，声明为普通的 `Cache<K, Set<E>>`（集合作为一个标量由 `Codec` 编码），先更新数据源再 `invalidate`。增量操作与数据源中的写入顺序无关，无法保证正确。

`SetStore<K, E>`（`SetStoreSpec`）只用于 L1 本身就是权威的场景：

| 项 | 规则 |
|----|------|
| loader | 按构造禁止（`SetStoreSpec` 没有 loader 字段，`SetStore` 没有 `get(key, loader)`） |
| `add` | key 不存在、为负缓存或 lease 时先 `DEL` 再创建只含这些成员的集合；是本协议的集合时原子加入。重设 TTL（永不过期时 `PERSIST`），`PUBLISH`。其它内容（其它 Redis 类型、不是本协议写入的 String、本格式的标量值、`!f` 不是 `2` 或 `!m` 中有位的集合、格式号低于或高于本格式的内容，§4.2）总是拒绝（`SetL1Store.addMembers` 返回其格式版本，Redis 为 `ADD_MEMBERS_IF_READABLE`），key 不变、不广播，引擎抛出 `CacheWriteException`（`cause` 为 `ForeignFormatException`）。SPI 没有整体覆盖读不懂的内容的元素写入：覆盖它就是删除唯一副本 |
| `remove` | 只在 key 是本协议的集合时生效；移空则删除 key，否则重设 TTL。`PUBLISH`。其它内容的处理同 `add`：拒绝（`removeMembersIfReadable`） |
| 并发顺序 | 以 L1 的串行化顺序为准 |
| 读取 | 不取 lease；未命中返回空集合，L2 以戳保护缓存这个结果（`absentTtl`，受 `localMaxAge` 限龄）。批量读取（三种变体）的未命中经 SingleFlight 合并（`executeAllSuspending`），本调用负责的 key 一次 `readAll`（不申请 lease），不逐个读 L1（RFC 0005 §9 A.L2，`SetEngine.loadEntries`）；合并规则逐 key 同单 key 路径；某个 key 读不懂时先完成其余 key 再抛出。L1 读失败为 `Unknown`：`get` / `getAll` 抛出 `CacheReadException`（`cause` 为原始异常），`lookup` 返回 `Unknown`；不允许 `unknownRead` |
| `put(key, set)` | 整体覆盖；空集合等价于 `clear` |
| `putAll(entries)` | 一次 `write` 覆盖整批 key：非空集合经 `L1Store.putAll` 整体覆盖，空集合经 `invalidate` 删除；两类都有时各自只广播自己的 key |
| 删除 | 没有 `invalidate`（会销毁唯一副本，类型上就没有）；删除用 `clear(key)` |
| 提前刷新 | 不适用 |
| 默认 TTL | 永不过期（`DefaultPolicies.SET_STORE`） |

前提（写在用户文档中）：Redis `maxmemory-policy` 不能是 `allkeys-*`，有限 `ttl` 的集合也不能用 `volatile-*`；数据持久性等于 Redis 的持久性（异步复制在故障切换时可能丢失最近的写入）。

- **启动检查驱逐策略**（starter 的 `StoreEvictionCheck`，RFC 0005 §12 M11、S22，只在后端是 `RedisBackend` 时）：有权威副本（`Store`、`VersionedStore`、`SetStore`）时读取各主节点 `INFO memory` 的 `maxmemory_policy`（托管 Redis 通常禁用 `CONFIG`，但允许 `INFO`）；读不到时按 `cocache.redis.assume-maxmemory-policy` 判断（必须是 `noeviction`、`allkeys-*` 或 `volatile-*`，否则启动失败）。
  - 一律启动失败，与 `store-eviction-check` 无关：任一主节点为 `allkeys-*`（任何 key 都可能被驱逐，唯一副本无法恢复）；读不到策略又没有配置 `assume-maxmemory-policy`（不静默跳过）。
  - `cocache.redis.store-eviction-check`（`warn` 默认、`fail`、`off`）只管剩下的一种：`volatile-*` 且存在 `ttl` 有限的权威缓存（它们可能在到期之前被驱逐）。`warn` 告警，`fail` 启动失败，`off` 接受。`VersionedStore` 的墓碑总有有限的寿命，被驱逐只让迟到的旧版本提前可写，不计入。
  - 检查的是启动时已创建的加上已声明但尚未创建的（延迟初始化的 bean），后者的 `ttl` 按创建时同样的分层（§10.2，不含库的锁定）得到，不创建缓存。
- **持久性**：启动时不检查。

存储代价：与原生 Set 相比，非整数成员每个多约 16 B，整数成员失去 intset 编码。

### 6.5 条件读写（按摘要比较）

`Store` 的操作（如 CoSec 的策略快照文档）：做乐观并发控制，一个 key 保存一整份文档，多个写入方各自“读—改—写”；每个节点按 `localMaxAge` 周期性确认已加载的文档仍是最新的。条件读写只认 `Store` 写入的值：带摘要段、不带版本段（§4.2）。三个操作都**只针对 L1**：

| 项 | `getIfChanged(key, known)` | `putIfMatch(key, expected, value[, ttl])` |
|----|----|----|
| L1 | 一个脚本 `READ_IF_CHANGED`：先判断 `TYPE`，再只读最长的头部（`GETRANGE key 0 77`）与 `STRLEN`。`V` 状态、带摘要段且摘要段等于 `known` 时只回复 `{1, 格式}`，不传输正文；否则回复 `{2, 格式, 整个值}`（客户端按载荷偏移解析，带摘要段与写入标识）、`{0}`（没有值）或 `{3, 格式}`（读不懂的内容，不传输正文） | 一个脚本 `PUT_IF_MATCH`：同样只读头部，符合期望时按 `PUT` 的规则写入（`DEL` + `SET` + 到期时间，销毁令牌，写入的摘要段由引擎计算）并 `PUBLISH`；不符合时不修改、不广播，回复当前摘要段 |
| “没有值” | key 不存在、`L`（lease 占位）、不带版本的 `M`（负缓存）返回 `Absent` | `expected = null` 只匹配这三种状态；`expected = d` 只匹配摘要段为 `d` 的 `V` |
| 校验（K3） | 拿到正文时重算 SHA-256 并与摘要段比较：不一致按读不懂的内容失败（见下），`Present` 的摘要就是校验过的摘要段。`Unchanged` 只比较头部，不重算 | 写入的摘要由引擎对载荷计算（`Digest.ofPayload`），存储原样写成段 |
| L2 | 不读、不写、不取戳：确认的是 L1，L2 可能更旧（仍受 `localMaxAge` 约束） | 写入方顺序同 `put`（§6.1），不写 L2 |
| 回源 / lease | 从不回源，不申请 lease 或刷新令牌 | 覆盖 lease 占位时销毁它（与 `put` 相同，持有者的 `fill` 失败） |
| 读不懂的内容 | 计入 `unknownFormats`，抛出 `CacheReadException`（`cause` 为 `ForeignFormatException`，更高的格式 `newer = true`），不修改 L1，从不回源。不是本格式写入的内容（格式号低于本格式，含格式 0）、更高的格式（含未知的必须理解的位，§4.2）、集合、**没有摘要段的值**（派生缓存写入的）、**带版本的内容**（值、墓碑与 `E`）、摘要校验失败或解码失败的值都从不视为没有值；后四者报告为本节点的格式 | 与读取同样分类，总是拒绝：不修改、不广播，计入 `unknownFormats` 与 `writeFailures`，抛出 `CacheWriteException`（`cause` 为 `ForeignFormatException`）。脚本看不到 `Codec`，带摘要段但解码失败的值对写入是一个普通的值：调用方拿不到它的摘要，写入只会冲突、从不覆盖，随后的读取抛出 |
| L1 故障 | 总是抛出 `CacheReadException`，与 `unknownRead` 无关：没有可降级的答案 | 抛出 `CacheWriteException`。结果未知（超时）时先重读：`current` 等于自己写入的值的摘要即已写入 |
| 计数 | 摘要未变计入 `conditionalUnchanged` | 冲突计入 `conditionalConflicts`（不计入 `writeFailures`） |

`deleteIfMatch(key, expected)`：一个脚本 `DELETE_IF_MATCH`（`ConditionalL1Store.deleteIfMatch`），只读头部，摘要段等于 `expected` 时 `DEL` 并 `PUBLISH`，返回 `Deleted`；不符合时不修改、不广播，返回 `Conflict(current)`（没有值时 `current = null`）；读不懂的内容与 `putIfMatch` 同样分类，总是拒绝。写入方顺序同 `invalidate`，同步完成，失败时抛出 `CacheWriteException`，从不重试。结果未知的 `deleteIfMatch` 迟到执行时，可能删除之后重新写入的相同内容（ABA，RFC 0005 §4.6）：需要顺序的写入方用 `VersionedStore`。

- **分类为什么这样定**：读与写必须对同一份内容给出同一个答案，否则“读—改—条件写”循环会出错：读取说“没有值”而写入说“冲突”时循环永远不结束，调用方拿冲突里的摘要重试则会从空值出发覆盖它。读不懂的内容从不视为没有值：`expected = null` 的写入会整体覆盖它（在 L1 是唯一副本的缓存上就是删除数据），更高格式版本的值在滚动升级期间被旧节点当作没有值覆盖就是降级。SPI 因此没有“把格式 0 视为没有值”的开关（RFC 0005 §12）。
- **只认带摘要段的值**：摘要段是 `Store` 的标记（D4）。派生缓存写入的值没有摘要段，它可能因为回源随时被替换，不是条件读写确认的对象；带版本的内容由版本排序（§6.6），不接受条件写入。所以同一 namespace 上派生缓存的值与带版本的值，条件读写总是拒绝（`putIfMatch(key, null, …)` 写入不存在的 key 除外）。
- **只读头部**（RFC 0005 §4.7、§7“条件读取”）：摘要由写入方写在头部，脚本只比较这 32 个字节，Lua 不计算摘要、也不把载荷读进 Lua。`GETRANGE key 0 77` 是最长的头部（核心头部、版本段、摘要段、扩展区的总长字段），加上 `STRLEN`，载荷偏移的检查与读整个值的脚本完全相同（段超出值的长度时是格式 0）。摘要未变的条件读取在 Redis 端的成本因此与载荷大小无关（`AuthoritativeBenchmark.conditionalUnchanged`）；摘要变了时才 `GET` 整个值回复。原地修改（授予刷新令牌、`release`）只触及字节 4–35，整体写入重写头部，所以头部中的摘要始终与写入时的载荷一致；绕过协议的写入留下的不一致由读取方校验发现。
- **非原子广播**（`sharded` 通道模式，或原子广播探测失败，§9.1）：脚本不广播，由引擎在脚本之后经通道广播。写入成功时广播；`putIfMatch` 冲突且当前摘要等于这次写入的载荷摘要时、`deleteIfMatch` 冲突且当前没有值时**也广播**。后者可能是重放：上一次同样的写入已在 L1 生效，单独的广播却失败了（调用方得到 `CacheWriteException`，按上表先重读或直接重放）。不广播的话其它节点的 L2 陈旧至 `localMaxAge`；多余的广播只淘汰副本，无害。其余冲突与拒绝不广播。
- **与 `put` 的关系**：`put` 不检查，并发的 `put` 仍是“后写者胜”；需要不丢更新的写入方全部改用 `putIfMatch`。`putIfMatch` 的写入使所有节点的在途填充失效，与 `put` 相同。`Store` 的 `put` 同样写入摘要段，之后可以条件读写。
- **SPI**：条件读写是存储的能力面 `ConditionalL1Store`（`L1Store.conditional()`，不支持时为 `null`），`readIfChanged`、`putIfMatch` 与 `deleteIfMatch` 都是抽象的，SPI 不提供任何默认实现：不原子的“先读后写”会让两个并发的写入方都得到 `Written`（丢失更新），宁可不支持。存储比较保存的摘要段（`Envelope.Scalar.digest`），不重算；返回的值带摘要段与写入标识。存储没有这个能力面时 `runtime.create` 拒绝创建 `Store`（§3.3“能力”）。读不懂的内容总是返回 `Unreadable`，SPI 没有“视为没有值”的开关。

### 6.6 按版本有序的写入

`VersionedCache`（派生，带版本的数据源）与 `VersionedStore`（权威，没有数据源）的操作，供“单一写入方按版本提交、投递可能重复或乱序”的数据使用（Wow 把聚合的状态视图写入共享的 L1：同一聚合只有一个写入方、版本严格递增，但订阅的重放与重投会再次送来旧版本）。这两个类型上没有丢失版本的写入（没有 `put`、`putIfMatch`、普通的 `invalidate` / `delete`）。`putIfMatch` 按摘要比较，用它保证顺序需要把版本放进值里，并且每次写入两次往返（先读再条件写）；按版本有序的写入把版本放在 L1 的版本段，一次往返完成比较与写入。

| 项 | 规则 |
|----|------|
| 声明 | `VersionedCacheSpec`：`versionedLoader`（`CacheLoader<K, Versioned<V>>`）或 `versionedAsyncLoader`（挂起：`suspendingVersionedLoader`）恰好一个；没有数据源的带版本数据是 `VersionedStoreSpec` |
| 后端检查 | `runtime.create` 在存储的 `versioned()` 为 `null` 时抛出 `UnsupportedOperationException`，不注册任何东西：在创建时失败，而不是第一次写入时 |
| `putIfNewer(key, value, version[, ttl])` | 一个脚本 `PUT_IF_NEWER`：`version` 大于 L1 中的版本时按 `PUT` 的规则写入（`DEL` + 带版本段的头 + 载荷 + 到期时间，销毁令牌）并 `PUBLISH`，返回 `Written`；否则不修改、不改变到期时间，返回 `Stale(current)`（相等是重复投递，幂等的空操作；更小是迟到的旧版本），计入 `staleVersions`（不计入 `writeFailures`）。相等时脚本仍然 `PUBLISH`（不写入，见下面的“相等的版本广播”），更小时不广播。寿命同 `put`：`ttlOf` 或策略寿命加抖动，或显式 `ttl`。带版本的值不带摘要段；`VersionedStore` 的值带写入标识 |
| `invalidateIfNewer` / `deleteIfNewer(key, version[, ttl])` | 同上，写入的是墓碑：状态 `M` + 版本。读取时就是不存在（不回源）；挡住不更新的版本。寿命默认是墓碑寿命 `tombstoneTtl`（未设置时同值的寿命，不是 `absentTtl`：墓碑要覆盖重复投递的时间窗口；`VersionedStore` 默认 1 天，它的值默认永不过期，墓碑寿命为永不过期时构建失败）；到期之后迟到的旧版本可以再次写入 |
| L1 中的版本 | 带版本的 `V` / `M` 与 `E` 的版本。没有版本的值与负缓存（派生缓存的回源、`Store` 写入的值）、lease 占位、不存在都视为 `Versioned.NO_VERSION`（`Long.MIN_VALUE`），任何版本都能写入。`NO_VERSION` 不能作为写入的版本（`IllegalArgumentException`）。比较是有符号 64 位整数比较：Lua 的数是 double，脚本把 8 字节拆成高 32 位（有符号）与低 32 位（无符号）分别比较，2^53 以上同样精确 |
| 回源 | 带版本的数据源（`CacheLoader<K, out Versioned<V?>>`）的结果连同版本凭 lease 写回（`Fill.version`）。返回 `Versioned(null, n)`（“在版本 n 时不存在”，如已删除的聚合）时写回与 `invalidateIfNewer(key, n)` 相同的墓碑，寿命同墓碑：否则重放的旧状态会以更高的版本越过没有版本的负缓存，让已删除的 key 重新出现。返回 `null`（数据源中从来没有这个 key）时写入没有版本的负缓存（`absentTtl`）；落在带版本的内容上时被拒绝（K16，§5）。批量回源同样如此 |
| 栅栏 | 按版本写入销毁 lease 与刷新令牌，持有令牌的回源写回被拒绝，**哪怕它读到的版本更高**（写入方胜出，数据源随后的写入会带来更高的版本）。持有令牌的写回按 `FILL` 的版本规则（§4.2）：版本更小或没有版本时拒绝并在脚本内清零令牌，相等时续期不广播，更大时写入并广播，引擎按写入处理（§5） |
| 保留版本的失效 | `VersionedCache.evict` → `VersionedL1Store.evictKeepingVersion`（脚本 `EVICT_KEEPING_VERSION`）：带版本的内容转为 `E(v)`（只有版本，令牌为零，寿命同墓碑），没有版本的内容删除，都广播；带摘要段的值（`Store` 的值，D4，计入 `typeMismatches`）与读不懂的内容拒绝，抛出 `CacheWriteException`（确定未执行）。`E` 上的读取是未命中，取得 lease 回源，填充的版本不小于 v 才写入（§4.2）；迟到的旧版本仍被 `putIfNewer` 拒绝。运维的按 key 删除（`CacheAdmin.invalidateEncoded`，端点 `DELETE /actuator/cocache/{name}/{key}`）在 `VersionedCache` 上用它，不进入失效重试（重试的 `DEL` 会丢掉版本），失败时抛出 `CacheWriteException` |
| 运维清除 | `purge(key)`（两个带版本的类型都有）：删除 key、丢掉版本栅栏，之后任何版本都能写入；同步完成，失败抛出，从不重试。`VersionedStore` 上运维的按 key 删除等价于它 |
| `getVersioned(key)` | 与 `get` 同一条读路径（L2 → L1 → 数据源），返回值与版本；不存在与墓碑都为 `null`（墓碑存活期间更旧的版本仍被拒绝），没有版本的值的版本是 `NO_VERSION` |
| 寿命 | 版本顺序只在条目存活期间成立：值、墓碑或 `E` 到期之后 key 中没有版本，任何版本（包括更旧的）都能写入（TTL 是安全网，§1）。寿命应覆盖重复投递的时间窗口；重放到最新位置的写入方最终写入最新版本，到期之后的陈旧度以那次重放为界 |
| L2 | 写入方顺序同 `put`（§6.1），不写 L2。L2 副本与值一起保存版本（引擎的值是 `Versioned<V>`），`getVersioned` 命中 L2 时不访问 L1 |
| 提前刷新 | 照常（§5.5）：带版本的值取得刷新令牌，持有者回源；版本更大的写回广播并按写入处理，版本更旧的写回被拒绝，刷新保留副本并退避 |
| 读不懂的内容 | 与 `putIfMatch` 同样分类：低于或高于本格式的内容与集合总是拒绝，从不视为没有版本。拒绝：不修改、不广播，计入 `unknownFormats` 与 `writeFailures`，抛出 `CacheWriteException`（`cause` 为 `ForeignFormatException`）。版本在头部，与 `Codec` 无关：同版本但解码失败的值照常按版本比较，更高的版本覆盖它 |
| L1 故障 | 抛出 `CacheWriteException`。结果未知时重放同一个版本是安全的：已写入则返回 `Stale(version)`。非原子广播时这次重放也会广播（见下），补上可能丢失的那次广播 |

- **版本放在版本段**（§4.2）：标志位的 bit 0 声明它，8 字节紧随核心头部。版本段中不得是 `NO_VERSION`（K15）。
- **相等的版本广播**（RFC 0005 §4.7，TLA+ 模型 F1）：`putIfNewer` / `invalidateIfNewer` 遇到与给出的版本相等的当前版本时不写入、返回 `Stale(current)`，但广播（原子广播时在脚本中，否则由引擎发布）。这个版本可能是令牌保护的填充写入的：节点 A 在不存在的 key 上取得 lease、回源得到 v1 并填充，在 `L` 上的填充不广播，而节点 B 的 L2 中还可能是 v0（L1 中的 key 曾到期或被驱逐，或者是等待 lease 超时之后的降级副本）。写入方随后的 `putIfNewer(1)` 就是补上这次广播的时机，否则 B 陈旧到 `localMaxAge`。相等的版本也可能是重复投递，或已在 L1 生效而单独的 `SPUBLISH` 失败、调用方重放的写入（理由同 §6.5），多余的广播只淘汰副本。当前版本严格更大时不广播：持有那个版本的写入方自己的 `putIfNewer` 会遇到相等的情形。`L` 上带版本的填充不广播：否则每次带版本的未命中都多一次 Pub/Sub 扇出。
- **非原子广播**（`sharded` 通道模式，或原子广播探测失败，§9.1）：引擎在脚本之后经通道广播：写入成功时，以及返回 `Stale(current)` 且 `current` 等于给出的版本时；版本更旧时不广播。版本前进的填充与保留版本的失效同样由引擎发布。
- **不回收**：同格式但解码失败的带版本值与 `E` 不经 `reclaim` 转为 lease（`RECLAIM` 脚本见到版本段即拒绝，内存后端同样拒绝）：lease 占位放不下版本，回收之后迟到的旧版本会被写入。有数据源的读取绕过 L1 回源（L2 降级限龄），等更高的版本覆盖它；`VersionedStore` 的读取失败（§5.4）。
- **丢失版本的入口**（I-K）：`purge`、L1 到期、同一 namespace 上的其它类型（声明错误）与外部工具。需要顺序保证的写入方全部使用 `putIfNewer` / `invalidateIfNewer` / `deleteIfNewer`；`VersionedCache` 的删除用保留版本的 `evict`。
- **实现**：两个带版本的类型的引擎是 `CoherentEngine<K, Versioned<V?>>` 的子类（`VersionedCacheEngine`、`VersionedStoreEngine`，`value` 为 `null` 表示带版本的不存在：写回时是墓碑，对用户是不存在），`ValueFormat` 为 `VersionedFormat`（载荷是 `Codec<V>` 的输出，版本随 `Envelope.Scalar` 进出 L1），子类在 `V` 与 `Versioned<V?>` 之间转换。读路径、栅栏、合并回源、批量与异步视图都沿用引擎，没有第二份协议实现；L2 命中只多一次字段读取。
- **SPI**：存储的能力面 `VersionedL1Store`（`L1Store.versioned()`，不支持时为 `null`）的 `putIfNewer` 与 `evictKeepingVersion`（都是抽象的，没有不原子的“先读后写”：它会让迟到的旧版本覆盖新版本）、`L1VersionedWrite`、`L1Eviction`、`Fill.version`（带版本的构造函数给出墓碑的版本）、`Envelope.Scalar.version`（默认 `NO_VERSION`，参与相等比较，不影响摘要；`versioned()` 非 `null` 的存储在 `fill`、`put`、`putAll` 时保存它、读取时原样返回；`putIfMatch` 写入没有版本的值）、`L1Fill`（`Filled(advanced)` / `Rejected(currentVersion)`，版本规则的结果）。`putIfNewer` 的 `version` 不得是 `NO_VERSION`，存储以 `IllegalArgumentException` 拒绝它（K15）。

## 7. 失败路径

| 类别 | 操作 | L1 故障时 |
|------|------|-----------|
| 读取 | 读、`fill`、`reclaim`、刷新 | 带数据源的读取默认 `readFailure = DEGRADE`：按未命中处理，回源结果只进 L2 并以 `degradedLocalMaxAge` 限龄，计入 `remoteFailures` / `degradedFills`。`readFailure = THROW`：L1 读失败为 `Unknown`；`fill`、`release` 等尽力而为的操作仍然降级。**没有数据源的读取**（权威副本，外部来源的 `Cache` 上不带 loader 的读取）L1 读失败为 `Unknown`，按 `unknownRead` 换算（§5.1） |
| 派生副本的失效 | `invalidate`、`invalidateAll` | 本地效果（两次递增戳、淘汰 L2）先生效。`invalidateFailure` 默认 `RETRY`（有数据源与外部来源都是：删除副本在任何时刻都安全）：登记为“重试中”并交给有界重试队列（`RetryPolicy`：最多 8 次，退避 100 ms 起翻倍至 10 s，容量 10 000），**不抛出**。重试期间该 key 的未命中与刷新跳过 L1（有数据源时直接回源，L2 降级限龄；外部来源时得到未填充的不存在）；重试成功后本节点再递增一次戳并淘汰 L2。放弃（耗尽、溢出、运行时关闭）时记录错误、计入 `invalidationsDropped`，其它节点的陈旧上界为 L1 剩余寿命（L2 到期后从旧的 L1 重新填充）。`invalidateFailure = THROW` 时抛出 `CacheWriteException` |
| 权威副本的删除 | `delete`、`deleteIfMatch`、`purge`、`clear`，`VersionedCache.evict` | 同步完成：抛出 `CacheWriteException`，**从不排队重试**（RFC 0005 §4.1，A.D2）：唯一副本上延迟的删除会删掉失败之后写入的值，`evict` / `purge` 的重试会丢掉或越过版本。需要可靠删除的写入方用 `deleteIfNewer`：版本严格递增，迟到执行不会越过之后的写入 |
| 显式写入 | `put`、`putAll`、`add`、`remove` | 本地效果先生效，然后抛出 `CacheWriteException`。结果未知（命令超时）时 L1 可能已生效；整体覆盖写入幂等，可以安全重试。调用方被中断时等命令结束（§5.6），不放弃它。派生副本（`Cache`）的 `put` / `putAll` 结果未知、失效失败模式为 RETRY（默认）时不抛出，改为登记补偿失效（见下） |
| 按版本写入 | `putIfNewer`、`invalidateIfNewer`、`deleteIfNewer` | 同显式写入，抛出 `CacheWriteException`；按版本幂等：结果未知时重放同一个版本，已写入则返回 `Stale(version)`（§6.6） |
| 条件读写 | `getIfChanged`、`putIfMatch`、`deleteIfMatch` | `getIfChanged` 总是抛出 `CacheReadException`（与 `unknownRead` 无关）；`putIfMatch` 同显式写入，但不是幂等的：结果未知时先 `getIfChanged`，再决定是否重试（§6.5） |

- **确定未执行与结果未知**（RFC 0005 §4.3、§4.6，V7）：`CacheWriteException.outcomeUnknown` 按客户端能观察到的事实区分。`false`：存储在写出之前就拒绝了命令（以 SPI 的 `L1RejectedException` 报告：Redis 把 Lettuce 断线时的拒绝、连接建立不起来映射为它，§9），或后端拒绝了这次写入、没有修改 L1（读不懂的内容、`evict` 遇到 `Store` 的值）。`true`：其余情形，包括超时、断线时被排空、脚本的错误回复。确定未执行的写入在非原子广播的后端上也不补发广播。批量写入只有全部命令都被拒绝时才是确定未执行：有的组已发出时，整个调用结果未知。
- **派生副本的 `put` 结果未知时登记补偿失效**（RFC 0005 §4.6，V7、U8，`CoherentEngine.putFailure`）：`Cache` 的 `put` / `putAll` 结果未知、失效失败模式为 RETRY（默认）时，引擎把这些 key 登记为失效重试（与失败的 `invalidate` 共用重试队列与 pending），不抛出：L1 中可能已经、也可能稍后出现这次写入的值，补偿的删除让 key 收敛到“从数据源重新加载”；重试期间本节点的未命中绕过 L1。迟到的写入晚于补偿的删除执行时，陈旧以这次写入的 `ttl` 为界；同一条连接上没有发生重连时，补偿失效完成即结束。确定未执行的写入（`L1RejectedException`）不会迟到生效，不补偿，照常以 `outcomeUnknown = false` 抛出；失效失败模式为 THROW 时以 `outcomeUnknown = true` 抛出，由调用方决定。权威副本（`Store`、`SetStore`）从不补偿：唯一副本上延迟的删除会删掉之后写入的值。
- **回源并发上限**（`LoadPolicy`，bulkhead）：只计 SingleFlight leader，批量回源占一个许可；在 `acquireTimeout` 内拿不到许可时抛出 `SourceLoadRejectedException`（被拒绝的回源会释放 lease）。L1 故障时由它保护数据源。
- 同线程对同一 key 的重入（loader 内再读同一个 key）快速失败（`IllegalStateException`）。
- loader 的异常原样抛给调用方与所有合并的等待者。
- **同步跟随者的等待响应中断**（RFC 0005 附录 A.S1）：合并到别人加载的同步调用方（单 key 与批量）被中断时，恢复中断标志并抛出 `InterruptedException`，只影响它自己；leader 照常加载、发布，其它等待者照常拿到结果。等待开始时中断标志已置位、结果尚未就绪的，同样立即失败；结果已经就绪的照常返回。
- **被中断的同步调用方**（RFC 0005 §4.3，§5.6）：在途的 L1 命令先完成或超时，取得的令牌按正常路径释放，取消之后不再调用 loader，然后抛出 `InterruptedException`；取消生效之前已经完成的调用照常返回结果。两种情形都保留中断标志。被中断的调用方因此至多多等一个命令超时（默认 2 s）。正在运行的阻塞 loader 自己看到中断（协作式取消）。
- **跟随者的等待有上界**（`load.timeout`，§5.6 SingleFlight）：合并到别人加载的调用方（同步与异步，单 key 与批量）等到这次加载的截止时刻（登记时加上 `load.timeout`）仍没有结果时，以 `SourceLoadTimeoutException` 失败，计入 `loadTimeouts`；leader 照常加载。截止时刻之后到来的读取开始新的加载。
- **读取的时限**（`CachePolicy.readTimeout`，RFC 0005 §4.3，D6）：引擎把它传给每次 L1 读取（`read`、`readAll`、`readIfChanged`，不含运维的 `inspect`），存储以它与命令超时中较短的一个为限；默认 `null`，即命令超时。超时与其它 L1 读失败相同：按 `readFailure` 降级或抛出，条件读取总是抛出，取得的令牌由存储释放（K14）。供不能降级回源、又要限制 L1 卡顿时每次读取多等多久的缓存使用（如 CoSec 的撤销检查）。配置键 `read-timeout`（§10.2），代码中经 `CachePolicy.Builder.readTimeout` 设置。
- **被取消的写入**（RFC 0005 §4.6，K18）：写入从修改 L1 到第二次递增（含广播、失效重试的登记）在 `NonCancellable` 中执行，协程被取消（运行时关闭）时这些步骤照常完成；挂起变体的调用方被取消只结束它自己的等待，写入在运行时作用域中照常完成（§5.7）。异步与挂起变体在 `ASYNC_WRITE`（`NonCancellable` + `Unconfined`）中执行它，`withContext` 结束时按 kotlinx 的即时取消保证把结果换成 `CancellationException`：写入已经生效，调用方看到的是取消，结果未知（I-E），与超时相同，按版本写入可以重放、`putIfMatch` 先 `getIfChanged` 再决定。不把整个写入放进同调度器的 `NonCancellable` 的理由：
  - 写入的效果不受影响：丢失的只是返回给已被取消的调用方的结果，而 L1、广播与第二次递增都已完成。
  - 被取消的协程从挂起调用正常返回，会让调用方在已取消的协程中继续执行后续代码，违反 kotlinx 的取消约定；调用方本来就要按“结果未知”处理取消。
  - 异步变体中只有运行时关闭会取消写入的协程（调用方取消 future 只结束它自己的等待），关闭之后结果也无从使用。
  - 同调度器的写入在每次 L1 命令完成之后都要经执行器派发，执行器拒绝派发时还要另行保证收尾步骤照常执行；`Unconfined` 让这些步骤在命令的完成线程上就地继续，没有这层风险。
- **异步视图**：上表的语义不变，异常改为完成返回的 future（原始异常，不同步抛出）。此外：异步数据源超过 `load.timeout` 时以 `SourceLoadTimeoutException` 失败（释放 lease）；运行时的执行器拒绝任务时以 `RejectedExecutionException` 失败；运行时关闭时进行中的异步读取（含等待 lease、等待回源许可）以 `IllegalStateException` 失败，不降级回源。

## 8. 栅栏：L1 令牌与 L2 失效戳

**规则**：任何填充都必须持有读取之前取得的令牌；失效必须在修改上游之前**和之后**各销毁一次令牌。

| 层 | 令牌 | 填充方 | 失效方 |
|----|------|--------|--------|
| L1 | lease（未命中时由读脚本原子取得）或刷新令牌 | Lua 比较令牌后写入（`fill`） | `DEL` / 整体覆盖 / 元素写入，销毁令牌 |
| L2 | 进程内失效戳（`InvalidationStamps`，4096 段 `AtomicLongArray`，key 哈希分段） | 取戳 → 读 L1 → 在 key 的锁内校验并写入（Caffeine `compute`）→ 写后复核，失败则按身份撤销自己写入的那一份 | 戳 → 淘汰 L2 → 修改 L1 → 戳 → 淘汰 L2 |

L2 填充（`fillGuarded`，RFC 0005 §4.5）：

- 写入方的单个 key 淘汰与填充的 `compute` 在同一个桶的锁上串行化。写入方的递增若早于填充在锁内的校验，填充不写；若晚于它，写入方随后的淘汰删掉这一份。所以写入方返回之后，旧值不会再出现在本节点的 L2 中。
- 校验不能与写入分开（先校验、再写入、复核失败才撤销）：校验刚通过时整个写入方完成，旧值随后写入，撤销之前对读取可见，写入方返回之后本节点仍可能读到写入之前的值（RFC 0005 附录 A.D4）。
- 重置与 `clearLocal` 按快照清空，看不到进行中的 `compute`，由写后复核撤销。这是程序顺序上的界，不是时间上的界；严格的线性化（读写锁）不采用，它会在 L2 命中路径上加锁（RFC 0005 Q11）。
- 复核按身份撤销（`remove(key, entry)`），不删掉其间别的填充写入的副本。因此 `LocalEntry` 按身份比较：不得改为 data class，也不得重写 `equals`。
- 进入 `compute` 之前先校验一次，失败就不进。

### 8.1 为什么这样做

- **第二次递增**：只在修改 L1 之前递增一次时，一个在递增之后取戳、在修改之前读到旧值的读者，写 L2 时能通过两次校验，而本节点自己的事件又被过滤，L2 留下旧值直到 `localMaxAge`。修改 L1 之后再递增一次，这个读者要么复核失败自行撤销，要么在第二次递增之前已经写完，被随后的“淘汰 L2”删除。
- **`put` 不写 L2**：戳只能判断“写入期间有没有失效”，判断不了两次写入在 L1 中的先后。同节点两个并发 `put` 若各自写 L2，后执行 Lua 的一方可能先写 L2，L2 留下较旧的值。L2 只由“读 L1”这一个方向填充，才能保证与 L1 的顺序一致。代价是写后第一次读多一次 L1 往返；读己之写仍成立。
- **L1 的 G2**：失效（`DEL` / 覆盖）发生在取得 lease 之后，就销毁了该 lease；令牌不会重新签发，所以开始于失效之前的回源永远写不进 L1。失效发生在取得 lease 之前，回源也就晚于数据源提交，读到新值。
- **跨节点 L2**：处理远端事件 = 戳 + 淘汰，与第二次递增同构；处理之前返回旧值是合法的“事件延迟”。

分段碰撞不影响正确性，代价是多余地放弃一次填充。

## 9. Redis 后端（`cocache-redis`）

- **客户端**：`RedisBackend.standalone(uri, options)`（单机或 Sentinel URI）、`RedisBackend.cluster(uris, options)`（自适应拓扑刷新）由后端自建 Lettuce 客户端；`RedisBackend.of(client, options)` 使用调用方的客户端，选项与生命周期由调用方负责。只支持 Lettuce；不支持 Jedis；**不支持静态主从（master-replica）拓扑**。
- **断线行为**：自建客户端设置 `DisconnectedBehavior.REJECT_COMMANDS` 与命令超时（`commandTimeout`，默认 2 s，Lettuce 的 `TimeoutOptions`，见下文“原生挂起”）。Lettuce 默认会缓冲断线期间的命令并在重连后重放：一个已经向调用方报错的 `put` 可能稍后执行并覆盖更新的值，断线期间每条命令也要等满 `commandTimeout` 才失败，所以拒绝。`of` 传入的缓冲客户端上，超时的脚本命令被完成、重连后不再重放（见下文“超时后不再写出”），但断线期间仍要等满超时，后端照常告警。
- **只访问主节点**：读脚本会写 lease 与刷新令牌，不能在副本上执行。CoCache 自己的连接不继承应用的从库读取配置。
- **命令连接**：每个后端一条共享连接（Cluster 由 Lettuce 按节点管理），并发的异步命令在其上多路复用。大载荷会造成队头阻塞。
- **脚本缓存**：每条命令 `EVALSHA`，遇到 `NOSCRIPT`（故障切换、重启、resharding 后）改用 `EVAL` 重试。
- **原生挂起**（RFC 0005 §4.3）：`RedisBackend.store` 返回 `RedisL1Store`，它直接实现 `L1Store` 与三个能力面：以 Lettuce 的异步命令发出脚本（`RedisConnections.evalAsync`），以标准库的 `suspendCoroutine` 挂起等待（`awaitCommand`），等待期间不占线程（`cocache-redis` 不直接依赖 kotlinx）。续接经调用方的调度器恢复：同步变体回到调用方线程自己的事件循环，异步变体回到运行时的调度器，脚本返回值的转换（解析头部、拷贝载荷与集合成员）因此不在 Lettuce 的 I/O 线程上执行。以总是成功的 `Result` 恢复、在自己的栈帧里抛出，不受 kotlinx 调试模式复制异常的影响。失效通道的 `publish` 同样挂起（`RedisConnections.publish` / `spublish`）。只有本身阻塞的路径阻塞等待（`RedisConnections.join`）：启动时的原子广播探测与 `INFO`、订阅与退订、心跳；它不响应中断（保留中断标志），由命令超时限定。
  - **限时**：每条命令受 `commandTimeout` 限制（`RedisConnections.bounded`）；读取另可带这次调用的 `timeout`，以两者中较短的一个为限（D6，`readTimeout`）。超过时不再等待应答，以 `RedisException("Command timed out after …")` 失败；执行异常解包为原始的运行时异常（不是运行时异常的包装为 `RedisException`）。超时不经 JDK 的延迟调度器（RFC 0004 §14）：自建客户端的 `TimeoutOptions` 固定为 `commandTimeout`（替换 Lettuce 默认的 60 s 连接超时，并关闭维护期间的放宽），Lettuce 以 Netty 时间轮为每条命令计时，超时以 `RedisCommandTimeoutException` 完成命令，`bounded` 把它映射为上述异常。单机只有这一个计时器；短于 `commandTimeout` 的读取时限另由客户端资源的时间轮计时（Lettuce 只按 `commandTimeout` 计时）。集群中 Lettuce 在命令写入节点连接时才计时：**等待建立节点连接（首次访问某节点、拓扑变化后）的命令不计时**，只受连接建立的超时限制（`connectTimeout` 默认 10 s，握手受 `RedisURI` 超时限制，默认 60 s）。因此 `bounded` 另以客户端资源的时间轮兜底，命令完成时撤销，调用的上界仍是 `commandTimeout` 加至多一个刻度，与连接建立的耗时无关；`of` 传入的客户端选项未知，同样由时间轮计时，客户端自己的异常原样传递（`CommandTimeouts`）。时间轮的精度是一个刻度（默认 100 ms）。这样做是为了让每条命令不依赖 JDK 的单线程延迟调度器，与吞吐无关（RFC 0004 §14）。`NOSCRIPT` 之后的 `EVAL` 是新命令，在单机上由 Lettuce 单独计时。
  - **批量**：`readAll`、`fillAll` 先发出全部命令再一起等待（`awaitCommands`，只挂起一次，共享连接上多路复用，约一次往返）；`putAll`、`invalidate` 按 slot 分组（Cluster）再按 `invalidateBatchSize` 切分，各组并发执行，等全部组结束才返回或抛出按发出顺序的第一个异常，其余附为 suppressed。Lettuce 断线时以同一个异常实例排空应答栈中的全部命令，这个实例不附给自己（对自己 `addSuppressed` 会抛出 `IllegalArgumentException`，`readAll` 就释放不了其余读取取得的 lease）。某条命令发出时同步失败（确定未执行）就不再发出其后的命令，但仍等已发出的命令结束、照常处理它们。
  - **取消**：Lettuce 取消的命令（连接重置时取消应答栈中的命令、关闭时取消缓冲的命令）以 `RedisException`（cause 为原来的取消）报告，是结果未知的 L1 故障，从不以 `CancellationException` 抛出：在 JVM 上它就是协程的取消，引擎会把它当作调用方被取消（RFC 0005 §4.3）。
- **超时后不再写出**：后端放弃等待（`bounded` 的时间轮）时，以异常完成脚本当前的 Lettuce 命令（`EVALSHA`，NOSCRIPT 之后的 `EVAL`；`RedisConnections` 的 `CommandCall`）。Lettuce 写出前跳过已完成的命令（`CommandHandler` 的 `isWriteable`；断线期间缓冲的命令重连后同样跳过），所以尚未写出的命令——集群中等待建立节点连接的命令、`of` 传入的客户端断线期间缓冲的命令——不会在调用方看到失败之后才执行，覆盖更新的值（权威副本永不过期时，这样的错误会永久保留）。完成的必须是 `StatefulConnection.dispatch` 返回的最外层命令：集群中它是 `ClusterCommand`，`isDone` 只看它自己的完成标记，取消或完成内层的 `AsyncCommand` 不影响它，节点连接建立后照常写出（对照 Lettuce 6.8.2 与 7.5.2 源码核实，`RedisCommandTimeoutTest` 在集群上复现）。因此脚本以 `dispatch` 发出，参数与输出与 Lettuce 的 `evalsha` / `eval` 相同，不经 `RedisScriptingAsyncCommands`。已写出的命令无从撤回：Redis 照常执行，应答被丢弃（§6.1）。`PUBLISH` / `SPUBLISH` 不在此列：迟到的广播只多淘汰副本。中断不到达存储（§5.6、§7）：被中断的同步调用方等它的命令完成或超时，存储侧没有“等待被中断”这一类。
- **失败时释放令牌**（K14）：取得或交回令牌的命令失败时，令牌可能已经没人会再释放，`lease.ttl` 内全集群对该 key 的未命中都要先等 `lease.wait`。`RedisL1Store` 因此除确定未执行外都尽力释放这些令牌。确定未执行（`RedisConnections.isNotExecuted`）只有 Lettuce 在写出之前的拒绝（`Currently not connected`、`Connection is closed`、请求队列或命令缓冲已满）与连接建立不起来（`RedisConnectionException`）：这时命令没有取得令牌，释放同样会被拒绝。超时、断线时被排空（`Connection disconnected` / `Connection closed`）、被 Lettuce 取消、脚本的错误回复、解码失败都按结果未知。释放异步发出、不等待；释放失败时（如断线期间以 `REJECT_COMMANDS` 被拒绝）在客户端资源的时间轮上按退避重发（100 ms 起倍增，至多 2 s），每次受 `commandTimeout` 限制，直到执行、超过令牌的寿命（lease 为 `lease.ttl`，刷新令牌为它的寿命，请求中没有寿命的填充与释放为 1 分钟）或连接关闭：断线时在途的读取取得的 lease 因此通常在重连之后就被收回（`RedisL1StoreTest` 以丢弃应答的代理复现）。只在放弃时告警。令牌不匹配时 `RELEASE` 什么也不做：fill 已经执行、令牌已被释放或抢占，都不影响已写入的值；迟到、重复或重连之后才到达的释放都无害。释放与原命令按 key 路由，同一条连接上排在它之后执行；原命令未写出就被完成时，释放同样无害。连接被替换之后不再有这个顺序：新连接上的 `RELEASE` 可能先于旧连接上被排空的原命令执行，令牌不匹配、什么也不做，被排空的 `READ` 随后取得的 lease 无人释放，留到 `lease.ttl`（TLA+ F4）。所以收回的上界是：通常在重连之后，释放与被排空的命令竞争时至多 `lease.ttl`。逐类命令：
  - 读取与回收（`read`、`readAll`、`reclaim`）：脚本可能在 Redis 端授予了令牌（未命中的 lease、刷新窗口内的刷新令牌、回收得到的 lease），引擎随后却绕过 L1（§5、§7）。`readAll` 任一读取失败时，其余读取取得的 lease 也释放。
  - 填充（`fill`、`fillAll`）：被完成、没有写出的 fill 永远不会执行，lease 仍在。`fillAll` 等全部 fill 结束再判定，结果未知的各自释放（引擎按整批失败处理）。
  - 释放（`release`）：被完成、没有写出的释放永远不会执行，因此再发一次（同样按退避重发）；已执行时重复的释放什么也不做。
- **命令超时对 Lettuce 内部命令的影响**：自建客户端的 `TimeoutOptions` 作用于经连接写入器发出的每条命令，也包括 Lettuce 自己发出的命令，它们的上限从默认的 60 s（`RedisURI` 超时）变为 `commandTimeout`（对照 Lettuce 7.5.2 源码核实）：
  - **重连后的自动重订阅**（`StatefulRedisPubSubConnectionImpl.resubscribe` 经 `async().subscribe/ssubscribe`）：超时只让 Lettuce 本地以异常完成这条命令（它只记录执行错误，不记录超时）；命令已经写出，服务端照常执行，应答到达时仍按序解码，`PubSubCommandHandler.afterDecode` 照常通知订阅确认，CoCache 在确认时重置订阅者（§9.1）。连接真的不可用时，心跳的 `PING`（同样受 `commandTimeout` 约束）失败，重置全部订阅者并强制重连，再次重订阅。
  - **集群拓扑刷新**（`DefaultClusterTopologyRefresh` 在 `connectToNodeAsync` 建立的节点连接上发 `CLUSTER NODES` 与 `INFO`）：节点连接同样包着 `CommandExpiryWriter`，超过 `commandTimeout` 的节点视图被排除；全部失败时这次刷新放弃并记录，之后由自适应触发器（`MOVED` / `ASK`、重连尝试等，受 `adaptiveRefreshTriggersTimeout` 限流，默认 30 s）再次触发。后端只开启自适应刷新（`enableAllAdaptiveRefreshTriggers`），没有周期刷新。很大或很忙的集群中 `CLUSTER NODES` 慢于 `commandTimeout` 时，应调大 `commandTimeout`。
  - 握手（`AUTH` / `HELLO` / `CLIENT SETNAME`）由 `RedisHandshake` 直接写入通道，不经写入器，不受影响（受 `RedisURI` 超时限制）。
  - 维护期间的放宽只有 Lettuce 7 才有（`relaxedTimeoutsDuringMaintenance`）。Spring Boot 4.0 管理的 Lettuce 6.8 没有这两个符号，后端在运行时跳过这一项（`RedisBackend.disableMaintenanceRelaxation`），Lettuce 6 本来也不放宽。强制重连拆开写入器时同样不依赖 Lettuce 7 的 `Delegating` 接口（`RedisInvalidationChannel.disconnect`；依赖它的话，在 Lettuce 6 上心跳失败后抛出 `NoClassDefFoundError`，心跳任务就此停止。心跳任务兜住任何 `Throwable`，记录后继续下一次），整个模块的测试在 Lettuce 6.8.2 与 7.5.2 上都通过。
- **原子广播探测**：`pubsub` 模式启动时在脚本内（`KEYS[1]` 为 `cocache:!probe:{a}`）向与 key 不同 slot 的频道 `cocache:!probe:{b}` `PUBLISH`。在把 `PUBLISH` 当作 `SPUBLISH` 执行的环境（如 ElastiCache Serverless）中报跨 slot 错误，后端以 `atomicBroadcast = false` 运行并告警。只有确定性的错误（跨 slot、ACL 拒绝）才降级；超时等其它错误使启动失败，而不是永久降级。探测的 key 与频道与前缀无关，默认前缀的 `~cocache:*` 已包含探测 key，频道要另外放行 `&cocache:!probe:*`；前缀不是 `cocache:` 时 key 也要另外放行（`~cocache:!probe:*`），否则以非原子广播运行。
- **按存储探测实际频道**（RFC 0005 §4.7，K8）：固定的探测频道代表不了带前缀的实际频道，ACL 是否放行实际频道因前缀而异。漏配时每个写脚本都在 `PUBLISH` 处报错，而 Redis 脚本出错不回滚，之前的 `SET` / `DEL` 已经生效，其它节点陈旧到 `localMaxAge`。所以每次 `store(keyspace)` 都向该 keyspace 的实际频道发布一个零 key 的帧（来源为全零的 nodeId，监听者什么也不做），阻塞一次往返：原子广播时经探测脚本，以该 keyspace 的存储前缀 `{keyPrefix}{namespace}:` 为 `KEYS[1]`（与写入脚本受同样的 key 与频道 ACL 约束），否则是普通的 `PUBLISH` / `SPUBLISH`。ACL 拒绝（`NOPERM`，7.0 的 “can't publish to the channel”）时创建失败（`IllegalStateException`，信息给出要放行的频道模式与 Redis 的回复：前缀非空时为 `&{keyPrefix}inv:*`，前缀为空时没有公共前缀，为这一个频道 `&inv:{namespace}`），不降级为非原子广播：这是配置错误，不是环境属性。超时、断线等其它错误照常抛出。`ExternalInvalidator` 第一次用到一个 keyspace 时同样探测。

### 9.1 失效通道

| 模式（`RedisChannelMode`，配置 `cocache.redis.channel-mode`） | 订阅 / 发布 | 原子广播 |
|------|------|------|
| `pubsub`（默认） | `SUBSCRIBE` / 脚本内 `PUBLISH`；Cluster 中经集群总线发往全部节点 | 是（探测失败时否） |
| `sharded`（Redis 7+） | `SSUBSCRIBE` / 写入成功后单独 `SPUBLISH`，消息只在频道所属分片内传播 | 否（脚本内 `SPUBLISH` 要求频道与 key 同 slot） |

两种模式互不相通，**全集群必须一致**。

- **每个后端一条订阅连接**，按频道（每个 keyspace 一个，`{keyPrefix}inv:{namespace}`）分发给订阅者；注册新缓存不会重置已有缓存。Sharded 模式按完整的频道名计算 slot，前缀中的 hash tag 因此也决定频道落在哪个分片。
- **读不懂的帧重置该频道的全部订阅者**（§4.1），在 Lettuce 的回调中执行，与订阅确认时的重置相同；内存后端相同。
- **订阅者互不影响**：某个订阅者的 `onInvalidated` 或 `onReset` 抛出异常（包括 `Error`）时记录并继续，其余订阅者照常收到这条消息或重置（内存后端相同）。分发时 `VirtualMachineError` 照常抛出，交给 Lettuce 与 Netty，排在后面的订阅者错过这条消息（至多一次投递，由 `localMaxAge` 兜底）；重置时不重新抛出，因为重置是消息丢失之后的兜底，不能跳过。
- **关闭订阅不等待正在投递的回调**（`Subscription.close`）：`close()` 只保证之后到达的消息与重置不再投递，返回时已在投递中的回调可能在它返回之后才到达。引擎的订阅者只递增戳、淘汰 L2，关闭之后迟到的回调无害。
- **重置时机**：频道的第一个订阅者发出订阅后，等待服务端确认（最多 `commandTimeout`），确认回调对该频道的订阅者调用 `onReset`，所以 `subscribe` 返回时重置已经完成；同一频道的后续订阅者直接收到一次 `onReset`。断线重连后 Lettuce 自动重新订阅，确认时再次重置。
- **订阅与退订串行执行**（含等待服务端应答，`RedisInvalidationChannel` 的 `subscriptionLock`；确认、心跳与消息分发不取这把锁）。否则多个运行时共享一个后端时：订阅者可能加入刚被最后一个订阅者移除的旧列表，随后的 `UNSUBSCRIBE` 让它永久收不到失效；首次订阅失败时会把并发加入的订阅者一并移除；退订的 `UNSUBSCRIBE` 也可能晚于随后的 `SUBSCRIBE` 生效。经典模式不处理服务端的 `unsubscribed`：Redis 不会主动退订经典频道，唯一的退订来自 CoCache 自己，串行执行后不会晚于之后的订阅生效。
- **心跳**：订阅连接每隔 `heartbeat`（默认 5 s）`PING`，失败或超时即重置全部订阅者并强制断开该连接，由 Lettuce 重连、重新订阅（确认后再次重置），半开连接在秒级内被发现。最近一次心跳的结果以只读状态暴露（`RedisBackend.heartbeatHealthy` / `heartbeatFailure`，供健康检查使用），心跳成功即恢复正常。CoCache 自建的客户端另开启 TCP keepalive（有 epoll / io_uring 时再加 `TCP_USER_TIMEOUT`）。Cluster 的经典 Pub/Sub 无法取得底层连接，只告警并依赖 keepalive。Cluster 的 `sharded` 模式 `PING` 确认了订阅的节点；slot 的主节点不再是确认节点时（故障切换），重置订阅者、退订旧节点并向新主节点重新订阅，确认之前同下条每次心跳再重置。
- **slot 迁移**（`sharded`）：原节点让出 slot 时会主动发送 `sunsubscribe`，Lettuce 不会自动重新订阅。收到非主动退订时立即重置订阅者并重新订阅；迟迟得不到确认（如分区信息陈旧，`SSUBSCRIBE` 发到了旧节点而失败）时，每次心跳都重置订阅者并重试，直到确认。未确认期间收不到远端事件，本地副本却会从 L1 重新填充，只在丢失时重置一次的话会陈旧到 `localMaxAge`；每次心跳都重置与经典模式“心跳失败即重置”一致，陈旧上界回到心跳周期加重新订阅的耗时（G1）。未确认的频道不参与 `PING`。
- **兼容性**：Redis 7.0+（用到 `TIME`、`SETRANGE`、`SPUBLISH`）。Valkey 7.2+ 实现了同样的命令；CI 在单机 Valkey 8 上运行 `cocache-redis` 与 starter 测试。

## 10. 运行时与配置

### 10.1 声明与运行时

```kotlin
val runtime = CacheRuntime.builder().backend(RedisBackend.standalone(RedisURI.create("localhost", 6379))).build()
val users: Cache<String, User> = runtime.create(
    cacheSpec<String, User>("user") {
        codec = jacksonCodec<User>(mapper)
        loader = CacheLoader { id -> repository.findById(id) }
        policy { ttl = Duration.ofMinutes(2); local = LocalPolicy(maximumSize = 100_000) }
    }
)
```

- 五种 spec（`CacheSpec`、`VersionedCacheSpec`、`StoreSpec`、`VersionedStoreSpec`、`SetStoreSpec`，公共部分为 `Spec`）都是不可变数据。`runtime.create(spec)` 有五个重载，各自返回与 spec 对应的类型（另有按 `Spec` 分派的 `create(spec: Spec)`），并检查类型需要的 L1 能力（§3.3“能力”）；`runtime.cache(name)` 返回 `ReadableCache`。所有有状态组件（L2、栅栏、SingleFlight、存储、订阅）都由 `runtime.create` 按 spec 新建，用户只提供 spec 与无状态函数（`Codec`、`CacheLoader`、`KeyEncoder`、`TtlFunction`、`KeyFilter`），所以两个缓存不可能共享一个 L2。
- **装配入口**（RFC 0005 §4.8）：`CoCacheFactory(runtime, layers)` 有与 `runtime.create` 相同的五个重载与 `create(spec: Spec)`，另有接口式声明的 `create(type, spec)`；每个都有带锁定的重载（`create(spec, locks)`、Kotlin 的 `create(spec) { … }`）。声明与库的锁定（`Locks`）先经 `CacheLayers.apply(spec, locks)`（每种 spec 一个）变成 `Layered`：生效声明与它的 `PolicyReport`；再执行 `Locks.verify`，交给运行时。默认的 `CacheLayers.NONE` 只有声明一层：执行约束，报告声明写出的字段（`DECLARATION`）、类型默认值（`TYPE_DEFAULT`）、被锁定的字段（`LOCKED`，“locked by <by>”）与被约束改变的字段（`LOCKED`，“constrained by <by>”）。装配层不得改名（否则 `IllegalArgumentException`）。
  - **锁定**（U2、S12）：`lock(vararg PolicyField)` 锁定策略字段，`lockLayout(vararg LayoutField)` 锁定存储布局（`NAMESPACE`、`KEY_PREFIX`、`KEY_ENCODER`、`CODEC`、`KEY_FILTER`、`TTL_OF`、`SOURCE`），`constrain(field) { … }` 在合并之后、校验之前约束生效值（值域含哨兵：`constrain(PolicyField.TTL) { it.finiteOr(…) }`，不能写成判空），`verify { spec -> … }` 检查生效声明。锁定者 `by` 默认是调用 `create` 的类（栈上 `CoCacheFactory` 之外的第一个类），Java 经 `Locks.builder(by)`。锁定在分层中的规则见 §10.2。
  - `create(type, spec)` 在创建缓存之前校验（S21）：`type` 是接口（手写实现的类被拒绝，错误信息指向 `create(spec)` 加委托），恰好继承一个可声明的类型（`CacheProxies.DECLARABLE_TYPES`：五种用户 API 类型与它们的 `Async*`、`Suspending*` 视图），它对应的用户 API 类型与 spec 的类型一致（`IllegalArgumentException`），接口中重新声明的抽象方法都能转发（`CacheMethods`，`IllegalStateException`）。校验失败时不创建任何东西。代理（`CacheProxies.create`，模块内部）只经这里取得。
  - **已分层与未分层**（S20）：经工厂创建的缓存 `CacheAdmin.layered` 为 `true`，`CacheAdmin.policyReport` 给出每个字段的生效值与来源；直接经 `runtime.create` 创建的为 `false`（`policyReport` 为 `null`，也无法锁定），启动日志在能力之后标注 `unlayered: …`，端点的报告输出 `layered: false`。starter 中 `cocache.caches.[name]` 指向未分层的缓存时只告警一次、指向 `CoCacheFactory`（`UnlayeredConfigurationCheck`，经 `CacheRuntime.addCreationListener` 看到启动之后创建的缓存），端点的 `ignoredConfiguration` 列出被忽略的条目：不让启动失败，因为运维常在事故中写下按缓存的配置，库也可能在启动之后才创建缓存。`CacheAdmin.apiType` 是缓存的用户 API 类型（启动日志与端点的 `type`）。
  - `CacheRuntime.addCreationListener(CacheCreationListener)`：之后创建的每个缓存（无论是否经工厂）注册之后回调，监听者的异常只告警；starter 用它为每个缓存绑定指标、核对未分层的缓存。
  - 已分层的缓存创建时，运行时按报告记录一行 INFO（来源不是类型默认值的字段与来源，如 `Cache[x] - policy: ttl=PT10M (caches), …`），每个被跳过的条目、被夹紧与被解锁的字段各一行 WARN。
  - starter 的 `CoCacheFactory` bean 以 `CacheAssembler`（三层配置与定制器的逐字段合并，`PolicyLayering`，§10.2）为装配层。
- 缓存名或 keyspace（前缀加 namespace，§4.1）重复时 `create` 失败；创建存储（含 Redis 的频道探测，§9）、构造引擎或订阅失败时回滚注册：释放 keyspace，关闭已创建的引擎与存储，同名、同 keyspace 可以重试（RFC 0005 附录 A.L9）。
- **时钟**：默认的 `TickingClock` 由守护线程每 100 ms 刷新一个 volatile 字段，命中路径只做一次 volatile 读。它是单调的：启动时取一次墙钟，之后按 `System.nanoTime()` 的增量推进，墙钟回拨或向前跳都不会让 L2 副本多活或提前到期（每次刷新都读墙钟的话，回拨 Δ 时 L2 副本多活 Δ，RFC 0005 附录 A.C1）。L2 只用时钟计算相对寿命，到期时间跨网络只以相对时长传输，因此与墙钟的累计偏差没有影响。
- `CacheRuntime.close()` 按创建的逆序关闭订阅与引擎，再关闭运行时自己创建的时钟、执行器与重试调度器（未执行的重试计入丢弃）。后端由调用方关闭，**必须晚于运行时**：
  - 取消运行时作用域之后、关闭执行器之前，`close` 至多等待 `closeTimeout`（`CacheRuntime.Builder.closeTimeout`，默认 5 s；starter 取 `cocache.redis.command-timeout` 的两倍与 5 s 中较大的一个：在途的那条命令，加上随后的释放或补发广播，K17），让作用域中的协程结束：在途的 L1 命令、令牌的释放、写入之后非原子广播的补发与第二次递增，以及取消之后的收尾。这些步骤由存储的命令超时限定，上限只防止卡住的命令让关闭无限期等待；超过上限时告警，照常关闭。不等待的话（K17），随后关闭的后端让这些步骤失败，其它节点读旧的 L2 直到 `localMaxAge`，lease 留到 `lease.ttl`：与节点崩溃同一上界，但本可以避免。
  - 在运行时的调度器派发的任务中调用时不等待（`EngineDispatcher.onDispatchedThread`）：调用方可能正是作用域中仍在收尾的协程（如在异步结果的回调里关闭），等待它自己只会等满上限。
  - 不等待的：同步调用（在调用方线程上运行，不在作用域中，应先停止发起调用）；刷新执行器上的同步刷新与重试线程上的失效重试（运行时自建的执行器在关闭时中断它们，收尾可能在 `close` 返回之后）。
- **运行时的执行器**（RFC 0005 §4.4、§5，`CacheRuntime.Builder.executor`，Kotlin DSL `executor = …`；访问器 `CacheRuntime.executor`）：异步与挂起变体只有这一个执行器（阻塞调度器）。
  - 在它上面执行的：运行时作用域中协程的每一步（L1 命令或数据源的 future 完成之后的每次恢复都派发到这里，§5.6）、共享加载、异步路径上的阻塞 `CacheLoader`、Spring `@Cacheable(sync = true)` 的 `retrieve` 方法体（§11.3）。回源观测不在这里（§5.6，观测专用的虚拟线程）。同步变体不经过它。
  - 它必须容得下阻塞的任务，不应排队，也不应只有少量线程：阻塞的数据源与方法体占住线程时，续接就排不上。
  - 默认运行时自建：每个任务一个虚拟线程，线程名 `cocache-worker-N`。JDK 21–23 上数据源在 `synchronized` 块内阻塞会钉住载体线程（JEP 491 在 JDK 24 消除），生产环境建议 JDK 24 及以上，或换成平台线程池。
  - 调用方给出的执行器不随运行时关闭。关闭时先取消运行时作用域（进行中的异步调用以 `IllegalStateException("CacheRuntime closed.")` 失败，含等待 lease 与等待回源许可），有界地等待其中在途的步骤结束（见上面的 `CacheRuntime.close()`），再关闭自建的执行器。没有专门的定时器线程：等待 lease 的退避与等待回源许可都是协程挂起。
- `CacheRuntime.cache(name)` / `metrics(name)` / `admin(name)` 按名称查找。

### 10.2 策略分层与校验

starter 的装配层（`CacheAssembler`，合并在 `PolicyLayering`）逐字段合并（RFC 0005 §4.8，U10、U11、S8–S12、S17）。由低到高：

| 层 | 来源（`PolicyReport.Source`） | 规则 |
|----|------|------|
| 类型默认值 | `TYPE_DEFAULT` | `DefaultPolicies` 中每种类型一份（S23，§10.3） |
| `cocache.defaults.cache.*` / `cocache.defaults.store.*` | `DEFAULTS` | **低于声明**：只作用于声明没有写出的字段（`Spec.declaredFields`）。整体赋值 `policy` 的声明写出全部字段，每个条目以 “the declaration sets the whole policy” 跳过 |
| 声明（注解或 spec DSL） | `DECLARATION` | 写出的字段：设置了的注解属性（不是 `UNSET`；设置了 `maximumSize` 的 `@CaffeineCache` 写出 `local-maximum-size`）；`policy { … }` / `updatePolicy` 中赋值的字段，含 `forever()`、`off()`、`unlimited()` 等哨兵，`lease` / `load` / `local` 的整体赋值只写出值有变化的字段；赋值的 `unknownRead` |
| `CacheSpecCustomizer`（按 Spring 顺序） | `CUSTOMIZER`（detail 为类名） | 看到的是叠加了 `defaults` 的声明。只能修改策略字段与不决定类型的字段（U16）：修改数据来源、被锁定的字段或布局时抛出 `IllegalStateException` |
| `cocache.overrides.cache.*` / `cocache.overrides.store.*` | `OVERRIDES`（detail 为收紧之前的值） | **只收紧**：陈旧界限取 `min(下层的值, override)`，`read-failure`、`fill-in-transaction` 与 `invalidate-fills-on-rollback` 取更严的值；其余字段跳过 |
| `cocache.caches.[name].*` | `CACHES`；越过锁定时 `UNLOCKED` | 设定任何适用的字段，另有 `unknown-read`、`key-prefix`、`unlock` |

被锁定的字段从一开始就标注为 `LOCKED`（detail “locked by <by>”）。之后依次执行库的约束（`Locks.constrain`，被改变的字段为 `LOCKED`，detail “constrained by <by>”）、校验连锁（`CLAMPED`）、构建生效声明（`CachePolicy` 与 spec 的构造校验），最后在工厂中执行 `Locks.verify`。`cache` 一节作用于派生副本（`Cache`、`VersionedCache`），`store` 一节作用于权威副本（`Store`、`VersionedStore`、`SetStore`）。

- **字段 × 层**（类别由 `PolicyField.categoryFor(apiType)` 给出）：

  | 字段 | 类别 | `defaults` | `overrides` | `caches.[name]` |
  |------|------|-----------|-------------|-----------------|
  | `ttl`（`Cache`） | 陈旧界限 | 设定 | 取 min，并设定按调用寿命的上限（见下） | 设定 |
  | `local-max-age`、`degraded-local-max-age`、`absent-ttl` | 陈旧界限 | 设定 | 取 min | 设定 |
  | `read-failure` | 有严格序的模式，`throw` 严于 `degrade` | 设定 | 取更严的值 | 设定 |
  | `fill-in-transaction`、`invalidate-fills-on-rollback`（不是策略字段，§11.3） | 有严格序的模式，`private` 严于 `registered-keys` 严于 `allow`；`true` 严于 `false` | 设定 | 取更严的值 | 设定 |
  | `invalidate-failure` | 没有严格序的模式 | 设定 | 不允许 | 设定 |
  | `lease-wait`、`load-timeout`、`load-acquire-timeout`、`read-timeout` | 等待界限 | 设定 | 不允许 | 设定 |
  | `ttl`（`VersionedCache`）、`tombstone-ttl` | 版本栅栏的寿命 | 设定 | 不允许 | 设定 |
  | `ttl`（`Store`、`VersionedStore`、`SetStore`） | 寿命即语义 | 不允许 | 不允许 | 设定 |
  | `ttl-jitter`、`refresh-window`、`lease-ttl`、`load-max-concurrent`、`local-maximum-size` | 其余 | 设定 | 不允许 | 设定 |
  | `unknown-read` | 模式（§5.1） | 不允许 | 不允许 | 设定 |

  `store` 一节的 `ttl` 一律不允许：`VersionedStore` 的值寿命虽属版本栅栏，它同样是权威副本的寿命（RFC 0005 §4.8“适用性”）；`defaults.store.tombstone-ttl` 允许。“不允许”与不适用一样只是跳过。取 min 只对陈旧界限是收紧：调小等待界限会让更多节点绕过 lease 自行回源，在数据源变慢的事故中放大负载；带版本类型的 `ttl` 与 `tombstone-ttl` 同时限定重复投递的旧事件被拒绝多久；直接设定模式会放宽更严的声明。
- **适用性**（`PolicyField.applicableTo(spec)`，U11）：
  - `Cache`：除 `tombstone-ttl` 之外的策略字段；外部来源的另有 `unknown-read`，没有 `refresh-window`（没有数据源可提前刷新）。lease、回源与 `read-failure` 作用于带调用级 loader 的读取（S11）。
  - `VersionedCache`：全部策略字段，没有 `unknown-read`。
  - `Store`：`ttl`、`ttl-jitter`、`absent-ttl`、`local-max-age`、`read-timeout`、`local-maximum-size`、`unknown-read`；`VersionedStore` 另有 `tombstone-ttl`；`SetStore` 没有 `unknown-read`。
  - 全局层中不适用、该层不允许或被锁定的条目跳过，记入 `PolicyReport.skipped`（键、层、原因），启动日志各一行 WARN，端点的 `skippedConfiguration` 列出。全局层中的 `key-prefix`、`unlock` 同样跳过。
  - `cocache.caches.[name]` 中不适用的键、`unlock` 中既不是策略字段也不是布局字段的键：`cocache.caches-validation: fail`（默认）创建缓存失败，`warn` 告警并忽略。
- **按调用寿命的上限**（S8）：`overrides.cache.ttl` 除了取 min，还设定 `CachePolicy.ttlCeiling`（即使声明的 `ttl` 已经更短；`forever` 不设上限）。`caches.[name].ttl` 更长时把这个缓存的上限抬到它，为 `forever` 时取消上限：否则策略寿命比按调用给出的寿命上限还长。只作用于 `Cache`：引擎把 `ttlOf` 的结果与 `put(k, v, ttl)`、`putAll(…, ttl)` 的寿命钳制到它（`CoherentEngine.perCallTtl`），计入 `cocache.ttl.clamped`，每个缓存第一次钳制时告警。策略自身的寿命（含抖动）不经过它。除此之外配置文件中的 `ttl` 不影响 `ttlOf`。
- **锁定**（U2、S12，§10.1）：全局层跳过被锁定的字段（原因 “locked by <by>”）；`cocache.caches.[name]` 写出被锁定的字段时创建失败（`IllegalArgumentException`，与 `caches-validation` 无关），除非它的 `unlock` 列出该字段（配置键，如 `ttl`、`key-prefix`），此时来源为 `UNLOCKED` 并告警（破窗开关）；定制器改变被锁定的字段或布局时抛出。被锁定的字段报告为 `LOCKED`（“locked by <by>”），被约束改变的为 “constrained by <by>”。
- **`key-prefix`**（S17）：不参与分层。`cocache.caches.[name].key-prefix` 优先于 `cocache.key-prefix`，二者都优先于声明（包括注解或 spec 中显式写出的 `keyPrefix`）与定制器；都未设置时沿用声明（默认 `cocache:`）。锁定了 `KEY_PREFIX` 时 `cocache.key-prefix` 跳过（记入 skipped），按缓存的 `key-prefix` 让创建失败，除非 `unlock: [key-prefix]`。配置项中的占位符（如 `${spring.application.name}:`）由 Spring 的配置解析处理；`@CoCache.keyPrefix` 是字面文本，不解析占位符。失效频道带前缀（§4.1）。
- **取值**（`ConfigValues`，RFC 0005 §4.8“单位”）：策略时长与 `cocache.redis.command-timeout`、`heartbeat` 以字符串绑定，由 CoCache 解析（Spring 的时长转换会按默认单位接受不带单位的数字）。
  - 必须带单位（`500ms`、`5s`、`2m`、`PT2M`），不带单位时启动失败：`<key>: "60" needs a unit, e.g. 60s, 5m, PT2M.`；`cocache.redis.*` 的示例单位是 `ms`（`cocache.redis.command-timeout: "1500" needs a unit, e.g. 1500ms, 5m, PT2M.`）。信息只给出问题与写法。
  - 哨兵值显式（S8），不区分大小写：`forever`（`ttl`、`tombstone-ttl`）、`off` 或 `0s`（`refresh-window`）、`unlimited`（`load-max-concurrent`）。没有隐式的 `0` 哨兵：`load-max-concurrent: 0` 启动失败。`tombstone-ttl: forever` 存为时长上限（100 年），读回与报告为 `forever`；`VersionedStore` 上因此构建失败。
  - 只有 `ttl-jitter`、`lease-wait`、`load-acquire-timeout` 可以为 `0s`（`refresh-window` 的 `0s` 是关闭的哨兵）；其余为正。
  - `CoCacheProperties` 构造时解析全部条目，写错的值在绑定时失败，即使没有缓存用到它。
  - 注解中的时长仍按秒（`Long`，U18）。
  - 不认识的键（如不带 `.cache` / `.store` 的 `cocache.defaults.<field>`、`ttl-forever`）按 Spring Boot 的规则忽略，不检测、不告警。
- **校验连锁**（U10）：在 `defaults` 之后（定制器之前要构建一次策略）与全部层和约束之后各检查一次。若提前刷新窗口不小于 `local-max-age` 或 `ttl − 实际抖动`，且相关字段（`refresh-window`、`local-max-age`、`ttl`、`ttl-jitter`）中有来自全局层（`DEFAULTS`、`OVERRIDES`）的、没有来自 `caches.[name]` 的（`CACHES`、`UNLOCKED`），关闭窗口（来源 `CLAMPED`，detail 给出原因，告警）。其余冲突（只由声明引起，或牵涉 `caches.[name]` 写出的字段，如 `defaults.cache.ttl: 10m` 加 `caches.[name].refresh-window: 20m`）由 `CachePolicy` 的构造校验让启动失败。
- **缓存名校验**：所有单例创建完成后，`CacheAssembler` 校验 `cocache.caches` 的名称（不区分大小写；“已声明”含尚未创建的缓存 bean，由 `declaredCacheNames` 提供）：未知名称按 `caches-validation` 启动失败或告警。指向未分层的缓存（直接经 `runtime.create` 创建）的条目总是只告警（`UnlayeredConfigurationCheck`，S20），并列在端点的 `ignoredConfiguration` 中：它可能在启动之后才创建，事故中写下的覆盖不应让应用起不来。名称校验之后才创建的缓存不为它所知。
- **生效值与来源**：`PolicyReport.entries` 是适用字段的 `{field, value, source, detail}`（按 `PolicyField.POLICY_FIELDS` 的顺序，`unknown-read` 在最后；哨兵写作 `forever` / `off` / `unlimited`，模式小写，时长为 ISO-8601，未设置的 `read-timeout` 写作 `command-timeout`），`skipped` 是 `{key, layer, reason}`。启动日志与端点都输出它（§10.1、§13）。
- 策略约束（`CachePolicy` 构造时校验）：所有时长为正且 ≤ 100 年（`ttlJitter`、`lease.wait`、`load.acquireTimeout` 可以为 0）；`load.maxConcurrent` 为 `null`（不限）或正数；抖动的实际幅度钳到 `ttl / 2`（`ttlJitter` 更大时不报错，§5“寿命”）；`refreshWindow < ttl − 实际抖动` 且 `refreshWindow < localMaxAge`，否则新副本一出生就在窗口内。校验只覆盖策略 `ttl`：`ttlOf` 或 `put(k, v, ttl)` 给出的不长于窗口的寿命不报错，这样的值不参与提前刷新（§5.5）。

### 10.3 默认值

类型默认值在 `DefaultPolicies`（`CACHE`、`VERSIONED_CACHE`、`STORE`、`VERSIONED_STORE`、`SET_STORE`，S23）：spec 的构建器以对应的常量为起点，端点把来自这里的值标注为 `TYPE_DEFAULT`。`CachePolicy.builder()` 以 `DefaultPolicies.CACHE` 为起点。

| 项 | 默认 | 理由 |
|----|------|------|
| `ttl` | 派生副本 1 h；权威副本永不过期 | 派生副本：协议外不一致的上界；权威副本：L1 是唯一副本，寿命即语义的数据写出显式的 `ttl` |
| `ttlJitter` | 1 min（实际 ≤ `ttl / 2`） | 打散批量过期 |
| `absentTtl` | 派生副本 1 min；权威副本 10 s | 数据新建后最多这么久仍被视为不存在 |
| `localMaxAge` | 派生副本 5 min；权威副本 30 s | 事件丢失时 L2 陈旧的上界（G1），必须有限；权威副本没有回源可以纠正，陈旧的上界只剩 L2（D7） |
| `tombstoneTtl` | 未设置（同值的寿命）；`VersionedStore` 1 天 | 墓碑与 `E` 要覆盖重复投递的时间窗口，`VersionedStore` 的值永不过期而墓碑不能 |
| `degradedLocalMaxAge` | 10 s | 未写入 L1 的填充只短暂存在于 L2 |
| `refreshWindow` | 关闭（`off`） | |
| `lease.ttl` / `lease.wait` | 10 s / 200 ms | `lease.ttl` 应大于 loader 的 p99；loader 慢于 `lease.wait` 时跨节点去重失效 |
| `load.maxConcurrent` / `acquireTimeout` | `null`（不限制，配置写作 `unlimited`）/ 1 s | 没有隐式的 `0` 哨兵（S8） |
| `load.timeout` | 30 s | 一次加载的上限（RFC 0005 §4.4）：异步数据源的一次加载；跟随者等待共享加载（截止时刻是加载登记时加上它）；加载在 SingleFlight 中的登记。丢失回调或卡住的同步 loader 让这个 key 在半分钟内恢复，而不是永久合并到一个不会结束的调用 |
| 运行时的执行器（`cocache.executor`） | 自建：每个任务一个虚拟线程（`cocache-worker-N`） | 异步与挂起变体的续接与阻塞的数据源都在这里，必须容得下阻塞的任务（§10.1） |
| `local.maximumSize` | 10 000 | L2 总是有界；不使用条目级 `Expiry`（每次读取都写节点元数据，热点 key 无法随线程扩展），过期在读取时判断 |
| `invalidateFailure` | 未设置，等同 `RETRY`（派生副本） | 删除副本总是安全的，失效通常在提交之后调用，抛出既不能也不该回滚；权威副本的删除总是同步抛出（§7） |
| `readFailure` | 未设置，等同 `DEGRADE`（带数据源的读取） | 带数据源的读取可以降级回源 |
| `unknownRead` | `THROW`（没有数据源的读取） | 没有可降级的答案，fail closed；显式 `ABSENT` 时读到不存在且不缓存（§5.1） |
| `readTimeout` | 未设置（存储的命令超时） | 只给不能降级回源、又要限制 L1 卡顿的缓存（D6，§7） |
| `ttlCeiling` | 未设置 | 只由 `overrides.cache.ttl` 设定（§10.2） |
| `keyPrefix` | `cocache:` | `cocache.key-prefix` 与 `cocache.caches.[name].key-prefix` 优先于声明，库锁定了 `KEY_PREFIX` 时除外（§10.2） |
| Redis `commandTimeout` / `heartbeat` / `invalidateBatchSize` / `channelMode` | 2 s / 5 s / 512 / `pubsub` | 配置中的时长必须带单位（§10.2） |
| `cocache.caches-validation` / `cocache.redis.store-eviction-check` | `fail` / `warn` | §10.2、§6.4 |
| 时钟刻度 | 100 ms | `System.currentTimeMillis()` 在部分平台上不随线程扩展 |

## 11. Spring 集成

### 11.1 starter 的 bean

| bean | 条件 |
|------|------|
| `cocacheBackend`（`CoherenceBackend`，`RedisBackend`） | `cocache.backend=redis`（默认），classpath 中有 Spring Data Redis，没有其它 `CoherenceBackend` bean，且存在 `DataRedisConnectionDetails`。由连接信息构造（单机 / Sentinel / Cluster，用户名与密码，SSL bundle → Lettuce `SslOptions`），后端自建客户端，不复用应用的连接工厂 |
| `cocacheBackend`（`CoherenceBackend`，`InMemoryBackend`） | `cocache.backend=memory`，没有其它 `CoherenceBackend` bean。不需要 Spring Data Redis；启动时告警：只适用于单进程，没有跨实例一致性 |
| `cocacheRuntime`（`CacheRuntime`） | 存在 `CoherenceBackend` bean。有唯一的 `CacheClock` bean 时运行时使用它（内存后端同样使用它，测试推进 `ManualClock` 即可让两层一起到期）。`cocache.refresh-executor` 指定提前刷新执行器的 bean 名，未设置时运行时自建并管理内置执行器；`cocache.executor` 指定运行时的执行器（§5.6、§10.1），未设置时运行时自建 |
| `cocacheAssembler`（`CacheAssembler`，starter 的装配层）、`cocacheFactory`（`CoCacheFactory`，以它为装配层；没有其它 `CoCacheFactory` bean 时）、`cocacheAnnotatedCacheFactory` | 运行时存在 |
| `cocacheUnlayeredConfigurationCheck`（`UnlayeredConfigurationCheck`） | 运行时存在。`cocache.caches.[name]` 指向未分层的缓存时告警（§10.1） |
| `cocacheStoreEvictionCheck`（`StoreEvictionCheck`，`SmartInitializingSingleton`） | 运行时存在。有权威副本（含尚未创建的声明）、且后端是 `RedisBackend` 时检查驱逐策略（§6.4） |
| `cocacheSpecRegistrar`（`CacheSpecRegistrar`） | `cocache.enabled` 未关闭（静态 `@Bean`，`BeanFactoryPostProcessor` 需在其它 bean 之前注册） |
| `cocacheCacheManager`（`CoCacheSpringCacheManager`） | 没有其它 `CacheManager`；有 spring-tx 时包装为 CoCache 的事务装饰器 `TransactionAwareSpringCacheManager`（§11.3），事务结束之后的失败交给缓存通知（`CacheAspectSupport`）的 `CacheErrorHandler`。`retrieve` 经缓存的异步视图（§11.3） |
| `cocacheTransactionalInvalidator` | 有 spring-tx |
| `cocacheFillObserver`（`FillObserver`，`TransactionalFillObserver`） | 有 spring-tx，没有其它 `FillObserver` bean。`cocacheRuntime` 经 `ObjectProvider` 取唯一的 `FillObserver`（§5.8、§11.3）。两个机制按缓存名读 `cocacheTransactionalFillModes`（`TransactionalFillModes`，条件相同，`CacheAssembler` 在分层时登记）；对一个缓存都关闭时 `enter(cacheName)` 返回 `null` |
| `cocacheSpringCacheSyncCheck`（`SpringCacheSyncCheck`） | 运行时存在（§11.3）。同时检查缓存通知与事务通知的 order、指向不是 `Cache` 的缓存的操作 |
| `cocacheDegradingCacheErrorHandler`（`SmartInitializingSingleton`） | 运行时存在，且 `cocache.spring-cache.degrade-on-error=true`。全部单例创建之后把每个缓存通知的处理器换成 `DegradingCacheErrorHandler`（§11.3） |
| `cocacheEndpoint`（`CoCacheEndpoint`） | 有 actuator，运行时存在，且端点可用（`management.endpoints.web.exposure.include` 等暴露配置包含 `cocache`、访问未被关闭） |
| `cocacheEndpointWebExtension`（`CoCacheEndpointWebExtension`） | 端点存在且经 HTTP 暴露。把被拒绝的按 key 删除映射为 403、未知缓存的 `DELETE` 映射为 404（§13） |
| `cocacheHealthIndicator`（`CoCacheHealthIndicator`） | 有 `spring-boot-health`（actuator 自带），`management.health.cocache.enabled` 未关闭 |
| `cocacheMeterBinder`（`CoCacheMeterBinder`） | 有 Micrometer，运行时存在 |
| `cocacheMeterBindingPostProcessor`（`BeanPostProcessor`） | 有 Micrometer（静态 `@Bean`）。延迟创建的缓存 bean 初始化后补绑指标 |
| `cocacheLoadObserver`（`LoadObserver`，`MicrometerLoadObserver`） | 有 `ObservationRegistry` bean，没有其它 `LoadObserver` bean，`cocache.observation.enabled` 未关闭（§13） |

执行器配置项（`cocache.refresh-executor`、`cocache.executor`）只按 bean 名解析，不按类型，避免按类型匹配时意外共用或选错 bean。名称没有对应的 bean 或 bean 不是 `Executor` 时启动失败，错误信息指明配置项。这些执行器由容器管理，`CacheRuntime.close()` 不会关闭它们（只关闭自己创建的）；它们登记为 `cocacheRuntime` 的依赖，在运行时之后才销毁。Spring Boot 的 `applicationTaskExecutor` 可以直接使用（`cocache.executor=applicationTaskExecutor`）；不会因为开启虚拟线程而自动使用它，需要显式指定。

`cocache.enabled=false` 关闭全部自动配置。测试或单实例部署可以自行声明 `CoherenceBackend` bean（如 `InMemoryBackend`），Redis 后端随之退让。

### 11.2 两种声明方式，同一个引擎

- **接口式**：`@CoCache interface UserCache : Cache<String, User>`，由 `@EnableCoCache(caches = [UserCache::class])` 注册。接口继承的可声明类型（五种用户 API 类型与它们的异步、挂起视图，恰好一个，`CacheProxies.declaredTypeOf`）所属的用户 API 类型（`CacheProxies.apiTypeOf`：`SuspendingStore` → `Store`）决定翻译成哪种 spec，类型参数按继承的那个类型解析；bean 的类型是接口本身。经 `CoCacheFactory.create(type, spec)` 创建缓存与 JDK 代理（叠加定制器与配置文件）。
  - bean 名 = 缓存名（`@CoCache.name`，默认接口简单类名），类型为接口本身；与已有 bean 重名时启动失败。
  - 泛型取自接口继承的那个类型（`Cache<K, V>`、`SetStore<K, E>` 等），必须是具体类型。
  - 数据来源（RFC 0005 §4.1）：
    - `Cache`：`@CoCache(loader = "beanName")`（`CacheLoader` 或 `AsyncCacheLoader` 均可，其它类型启动失败），否则按 `CacheLoader<K, V>`、`AsyncCacheLoader<K, V>` 唯一匹配（同一种有多个候选、或两种各有一个候选时启动失败，需用 `loader` 指明；同时实现两者的 bean 按同步数据源使用）。`@CoCache(source = EXTERNAL)` 即 `externallySourced()`，不能与 `loader` 同时设置。**既没有匹配到数据源、也没有声明外部来源时启动失败**（RFC 0005 M1）：泛型不匹配因此不会静默成为“没有数据源”。
    - `VersionedCache`：带版本的数据源，按 `CacheLoader<K, Versioned<V>>` / `AsyncCacheLoader<K, Versioned<V>>` 匹配，规则同上；必须有。
    - 权威类型：设置 `loader` 或 `source = EXTERNAL` 时启动失败；存在匹配 `CacheLoader<K, V>` / `AsyncCacheLoader<K, V>` 的 bean 时告警（可重建的副本误声明成权威类型）。
  - 策略以类型的默认策略为起点（`DefaultPolicies`，§10.3）。注解中设置了的属性（不是 `UNSET`）记为声明写出的字段（§10.2），设置了 `maximumSize` 的 `@CaffeineCache` 记为写出 `local-maximum-size`；其余字段交给 `cocache.defaults`。
  - 编解码：`@CoCache(codec = "beanName")`，否则按 `Codec<V>` 唯一匹配，找不到则用应用的 Jackson 3 `ObjectMapper` 按值类型构造 `JacksonCodec`。
  - key：`keyExpression` 为 SpEL 模板（以业务 key 为根对象），否则 `toString()`。
  - 注解时长单位为秒，`UNSET`（-1）表示交给默认值与配置文件；`FOREVER` 只用于 `@CoCache.ttl`（永不过期）与 `@CaffeineCache.expireAfterAccess`（不按空闲淘汰）；`refreshWindow = 0` 表示关闭。
  - 视图接口的代理（`CacheProxies.ViewHandler`）：继承 `Async*` 或 `Suspending*` 的接口转发给引擎的 `async()` / `suspending()` 视图，以同一个 `Method`、同样的参数调用（挂起方法的 `Continuation` 原样传过去），所以挂起视图走协程核心的原生路径，L2 命中不挂起、直接返回，重入守卫（K7）照常生效；用户接口重新声明的抽象方法调用匹配的视图方法（`CacheMethods.counterpartOf`）。挂起视图中 L2 命中可以直接返回的读取（`get`、带 loader 的 `get`、`getAll`、`lookup`、`lookupAll`、`getVersioned`）经创建代理时绑定到视图的方法句柄调用（`ReadHandles`），其余方法经 `Method.invoke`：方法句柄是实例字段，C2 不把视图内联进调用方，视图的命中路径编译在自己的方法里，内联预算与调用方的深度无关，处理器本身仍内联进代理方法、参数数组不分配。经 `Method.invoke`（`Method` 是代理类的常量，会一路内联到视图）时，挂起调用经协程的启动与状态机进入代理，代理多出的帧把 Caffeine 的查找推过 C2 的内联深度上限（`MaxInlineLevel`），命中多约 10 ns；写入总要访问 L1，这项成本可以忽略（`docs/investigations/jmh-8.0-ga.md`“挂起视图接口的代理”）。视图的异常原样抛出；挂起方法在挂起之前同步抛出的受检异常经调用方的 `Continuation` 交还（`resumeWith(failure)` 后返回 `COROUTINE_SUSPENDED`），不被 JDK 包装。`targetOf` 对两种代理都返回引擎。`ViewProxiesTest` 逐个调用十个视图类型的全部方法。
  - 同步接口的代理：按接口继承的用户 API 类型分发（RFC 0005 §4.2），每个类型一张转发表，按方法名与参数个数直接调用引擎，不经 `Method.invoke`，**异常原样抛出**；`CacheProxiesTest` 逐个调用五个接口的全部方法，确认没有漏转发。例外是 JDK 代理自身的规则：接口方法没有声明的受检异常（Kotlin loader 抛出的 `IOException`、跟随者被中断时的 `InterruptedException` 等）由 JDK 包装为 `UndeclaredThrowableException`，原始异常是它的 `cause`（RFC 0005 附录 A.P1）；spec 声明注入的是引擎本身，不受影响。用户接口的 default 方法经 `InvocationHandler.invokeDefault` 执行；其它抽象方法（名称、参数个数与类型、返回类型不与接口继承的用户 API 类型的方法匹配）在创建缓存时即启动失败，并提示改为 default 方法；`equals` / `hashCode` 按代理身份。
- **spec bean**：`@Bean fun userSpec(): CacheSpec<String, User> = cacheSpec("user") { … }`。
  - `CacheSpecRegistrar`（`BeanFactoryPostProcessor`）从工厂方法**声明的**泛型返回类型解析 `K`、`V`，不实例化 spec；无法解析（原始类型、通配符）时启动失败。
  - 为每个 spec 注册一个对应类型的 bean（`CacheSpec` → `Cache<K, V>`、`VersionedCacheSpec` → `VersionedCache<K, V>`、`StoreSpec` → `Store<K, V>`、`VersionedStoreSpec` → `VersionedStore<K, V>`、`SetStoreSpec` → `SetStore<K, E>`）：bean 名为 spec bean 名去掉 `Spec` 后缀（`userSpec` → `user`），没有该后缀时加 `Cache`；与已有 bean 重名时启动失败。注入的就是引擎本身，没有代理层。缓存在首次注入时经 `CoCacheFactory.create(spec)` 创建。
- 同一个缓存名既有接口声明又有 spec bean 时，第二个创建时因缓存名重复而启动失败。缓存在首次注入时创建。

### 11.3 Spring Cache 与事务

- `CoCacheSpringCacheManager` 只暴露运行时中已创建的缓存，不按名称自动创建。
- 负缓存视为未命中（否则 `@Cacheable` 永远不调用方法）；`put(k, null)` 映射为 `invalidate`；`evict` = `invalidate`；`evictIfPresent`（`@CacheEvict(beforeInvocation = true)`）立即 `invalidate`，事务内同时登记到 `TransactionalInvalidator`（见本节的事务装饰器）；`clear`（`@CacheEvict(allEntries = true)`，以及经 Spring 默认实现调用它的 `invalidate`）只清本节点 L2（L1 是共享存储），每个缓存第一次调用时告警一次（RFC 0005 U19）：没有全集群清空：那需要“namespace 重置”帧（预留的帧版本 2，§4.1，RFC 0005 Q12），目前不发送。
- `CoCacheSpringCache` 自己不推迟任何操作；事务内的推迟与登记由事务装饰器执行。
- `@Cacheable(sync = true)` 经引擎的 `get(key, loader)` 回源，受 lease 与失效戳保护。不带 `sync` 的 `@Cacheable` 由 Spring 执行“读未命中 → 调用方法 → `put`”，`put` 是无条件写入，没有这层保护；需要上述一致性保证时用 `sync = true`。
- **启动检查**（`SpringCacheSyncCheck`，`SmartInitializingSingleton`）：存在 `CacheOperationSource`（`@EnableCaching`）时，按 bean 类型（不实例化 bean；CGLIB 取父类，JDK 代理取已创建单例的目标类）解析缓存操作，含 `@CacheConfig` 的类级缓存名。指向 CoCache 缓存名（已创建与已声明）且 `sync = false` 的 `CacheableOperation` 记录告警（`类名#方法名` 与缓存名）；`cocache.spring-cache.require-sync=true` 时启动失败。只按缓存名判断；指定了其它 `cacheManager` 或自定义 `cacheResolver` 的操作跳过。没有 `@EnableCaching`，或不存在名为 `cocacheCacheManager` 的 bean（用户以其它名字声明了自己的 `CacheManager`，starter 的管理器退让）时不做任何事。同一前提下还检查通知的嵌套（见本节的“缓存通知与事务通知的嵌套”），并且**指向不是派生副本 `Cache` 的缓存**（`Store`、`VersionedStore`、`SetStore`、`VersionedCache`；已创建的按运行时中的缓存判断，已声明但尚未创建的按声明的接口类型或 spec 类型判断）**的任何缓存操作**（`@Cacheable`、`@CachePut`、`@CacheEvict`，不论 `sync`）一律启动失败：`cocacheCacheManager` 不暴露它们，否则要到第一次调用时才以“找不到缓存”失败。
- `retrieve` 经缓存的异步视图（RFC 0004 §6）：`retrieve(key)` 是异步 `lookup`，L2 命中返回已完成的 future（不切换线程），负缓存以 `null` 完成；`retrieve(key, loader)`（响应式 / `CompletableFuture` 方法上的 `sync = true`）是异步 `get(key, AsyncCacheLoader)`，与 `get(key, loader)` 一样经引擎受 lease 与失效戳保护的路径回源，**没有线程等待**异步 loader；方法体（`valueLoader.get()`，可能先阻塞再返回）派发到运行时的执行器（`CacheRuntime.executor`，§10.1）执行，不在调用方线程上（看不到调用方的 ThreadLocal）；`retrieve(key)` 与 `retrieve(key, loader)` 的失败都以原始异常完成。因此没有单独的 retrieve 执行器，嵌套的异步 `sync = true` 也没有内层任务排在外层任务之后的死锁条件。`CoCacheSpringCache(cache, admin, executor)` 的执行器执行方法体，`CoCacheSpringCacheManager` 传入 `CacheRuntime.executor`。
- 已知限制：Spring Cache 的 `put` / `evict` 只有同步签名，响应式方法上的 `@CachePut`、`@CacheEvict` 与不带 `sync` 的 `@Cacheable` 在完成回调中同步调用它们，各占用该线程一次 L1 往返；需要非阻塞失效时直接调用 `cache.async().invalidate(key)`。
- 只有派生副本 `Cache` 接入 Spring Cache 与 `TransactionalInvalidator`（RFC 0005 §4.2）。暴露给 Spring Cache 的缓存通常是外部来源（`externallySourced()` / `@CoCache(source = EXTERNAL)`：数据源是被注解的方法）：不带 `sync` 的 `@Cacheable` 经 `lookup` 读取，L1 读失败为 `Unknown`，适配器以它的 `CacheReadException` 抛出（§5.1，交给 Spring 的 `CacheErrorHandler`，默认重新抛出），声明 `unknownRead = ABSENT` 时读到未命中且不缓存、随后执行方法；`sync = true` 带 loader，按 `readFailure` 照常降级。`@CacheEvict` 失败默认登记后台重试（派生副本的 `invalidateFailure` 默认 `RETRY`，§7），显式 `THROW` 时抛出 `CacheWriteException`。
  - **`Unknown` 的映射**（RFC 0005 U5）：`get` 与 `retrieve` 一致。`Lookup.Unknown` 以它的 `CacheReadException` 失败（`get` 抛出，`retrieve` 的 future 以它完成），Spring 交给 `CacheErrorHandler`：Spring 7 对失败的 `retrieve` future（`CompletableFuture` 方法）与响应式路径（`Mono` / `Flux` 方法）同样调用 `handleCacheGetError`，处理器放行时按未命中执行方法。声明 `unknownRead = ABSENT` 时引擎已换算为不存在，三条路径都按未命中处理、不经处理器。
  - **不对称**（RFC 0005 S18）：同一个缓存上，不带 `sync` 的 `@Cacheable` 在 L1 故障期间全部失败（`SimpleCacheErrorHandler` 重新抛出 `CacheReadException`），带 `sync = true` 的降级回源。`unknownRead = ABSENT` 也换不来可用性：随后的 `put` 在 L1 故障时同样失败。
  - **可选的 `DegradingCacheErrorHandler`**（`cocache-spring`；starter：`cocache.spring-cache.degrade-on-error=true`）：只对 CoCache 的缓存（`nativeCache` 是 CoCache 的 `Cache`，因此也识别装饰器）放行读取失败与非 `null` 的写入失败，记录不带堆栈、不含 key 的告警（堆栈在 DEBUG 级别），Spring 随后按未命中执行方法、不缓存结果。被注解的方法本身就是数据源，派生副本可以降级（RFC 0005 A7）。其余交给原来的处理器：其它缓存的全部失败；CoCache 缓存的失效（`evict`、`evictIfPresent`、映射为失效的 `put(k, null)`）与清空失败，因为失效失败在 `RETRY` 时本来就不抛出，配置为 `THROW` 即要求调用方看到失败。starter 在全部单例创建之后替换每个 `CacheAspectSupport` 的处理器，原来的处理器（`CachingConfigurer.errorHandler()` 给出的，或 `SimpleCacheErrorHandler`）成为它的委托；提前取得会提前创建 `CachingConfigurer`。已经是 `DegradingCacheErrorHandler` 的不再包装。
- `TransactionalInvalidator`：事务内调用时登记到当前事务，没有事务时立即失效。登记按事务的同步列表划分作用域，`REQUIRES_NEW` 的内层事务在自己结束之后失效。**每个事务一份登记**（`TransactionalOperations`，一个 `TransactionSynchronization`），不按实例：经任何 `TransactionalInvalidator` 实例、`CoCacheSpringCache.evictIfPresent` 与事务装饰器登记的失效，以及事务装饰器推迟的写入，在同一个同步中处理，写入与失效的先后因此可以比较。同一事务内对同一缓存的失效合并为一次 `invalidateAll`（去重，保持登记顺序）。
  - **提交**：在 `afterCommit` 中失效，异常（`invalidate-failure: throw`，§7）抛给调用方。事务装饰器登记的失效例外：失败交给 `CacheErrorHandler`（见下）。
  - **回滚或提交结果未知**：在 `afterCompletion` 中同样失效。同步 API 的 loader 在调用方线程上参与事务，事务内的读穿透可能已经凭 lease 把未提交的数据写入 L1；不失效的话，全集群在 `ttl` 内读到从未提交的数据。多余的失效无害。
  - **回滚到 savepoint**（`NESTED`）：Spring 只调用同步的 `savepointRollback`（Spring 6.2 起），不调用 `afterCompletion`，外层之后提交时报告 `STATUS_COMMITTED`。嵌套段内读穿透凭 lease 写入 L1 的未提交数据因此在外层结束之前对全集群（包括外层事务自己）可见。`savepointRollback` 立即失效到目前为止登记的全部 key（不按 savepoint 分段：外层登记的 key 多失效一次，无害），不标记为已执行，登记保留，事务结束时照常再失效。Spring 在把数据库回滚到 savepoint **之前**调用它，抛出会跳过数据库的回滚，所以这里的失效失败只记录日志、不影响其余缓存，由事务结束时的失效兜底并照常报告。
  - **迟到的登记不丢失**。Spring 对同步列表的快照调用 `afterCommit`：在其它同步的 `afterCommit` 中登记时，本事务的失效已经执行过则立即失效（`flushed` 标记），本事务第一次登记（新注册的同步收不到 `afterCommit`）则在 `afterCompletion` 中失效。排在前面的同步的 `afterCommit` 抛出、后面的 `afterCommit` 都不再调用时，同样在 `afterCompletion` 中失效。`afterCompletion` 中（`@TransactionalEventListener(AFTER_COMMIT)` 在这里执行）同步已经清除，立即失效。每个事务只执行一次，`afterCompletion` 是兜底。
  - **一个缓存失败不影响其余缓存**：逐个缓存执行，全部执行之后抛出第一个异常，其余作为它的 suppressed。`afterCompletion` 中的异常由 Spring 记录日志，不抛给调用方，也不掩盖回滚的原因。
  - **只支持线程绑定的同步**（`PlatformTransactionManager`）。响应式事务（`ReactiveTransactionManager`，如 R2DBC，包括 `@Transactional` 挂起函数）的同步在 Reactor 上下文中，`TransactionalInvalidator` 看不到，会立即失效，即在提交之前；这类事务由调用方在事务结束之后失效。不告警：同步调用无法识别响应式事务（它们不设置线程绑定的标记），而“`isActualTransactionActive()` 为真但同步未激活”正是 `afterCompletion` 中的状态，会误报。
  - 测试：`cocache-spring` 的 `TransactionalInvalidatorTest`、`TransactionAwareSpringCacheTest` 与 `CoCacheSpringCacheTest`，事务管理器是 `AbstractPlatformTransactionManager` 的内存实现（`InMemoryTransactionManager`：写入在提交之前只对本事务可见，支持挂起、`NESTED` 与编程式 savepoint）；starter 的 `TransactionalCacheTest` 经 Spring Cache 注解与装配的 `cocacheCacheManager`、`cocacheTransactionalInvalidator` 验证提交、回滚、回滚到 savepoint、`afterCommit` 失败的去向、`degrade-on-error`、通知 order 的告警与 `allEntries` 的告警。
- **写事务内的回源**（`TransactionalFillObserver`，`cocache-spring`，RFC 0005 §4.9 C2、S7、S15、S24；core 的钩子见 §5.8）：`enter(cacheName)` 先判断是否处于线程绑定的、非只读的实际事务，再按缓存名取两个机制的值，都关闭时返回 `null`。只认线程绑定的、非只读的实际事务（同步已激活、`isActualTransactionActive()`、不是只读），返回持有这个事务的登记（`TransactionalOperations`，与 `TransactionalInvalidator` 同一份）的视图。只读事务的快照陈旧、非 Spring 管理的事务与响应式事务都看不到。两个相互独立的机制：
  - **机制 1：回滚时失效事务内的填充**（`invalidate-fills-on-rollback`，默认开启）：数据源在事务内产生、写入 L1 或 L2 的 key（含 L1 读失败、等待 lease 超时、失效重试中之后只写本节点 L2 的降级填充）记入登记；回滚与提交结果未知时在 `afterCompletion` 中、于登记的失效之后失效（已登记失效的 key 跳过），回滚到 savepoint 时在 `savepointRollback` 中与登记的 key 一起立即失效（失败只记录日志），提交时什么也不做（读到的就是本事务提交的数据）；本事务完成之后的回源不再记录。代价只在回滚时出现：未提交数据的残留从 `ttl` 收紧到事务时长，覆盖“应用什么也没登记”的情形。
  - **机制 2：私有回源**（`fill-in-transaction`：`registered-keys` 默认、`private`、`allow`）：`registered-keys` 下本事务已登记失效的 key（经 `TransactionalInvalidator`、`@CacheEvict`、`evictIfPresent`、`put(k, null)`）的回源是私有的；`private` 下写事务内的全部回源都是私有的（写事务内 L2 未命中的读取每次回源）；`allow` 不门控（仍受机制 1 保护）。登记失效时立即在本节点递增戳、淘汰本地副本（`FillKey.evictLocal`，三种取值都一样：登记即“本事务改了它”，提前淘汰无害），本事务接着读到自己的写入；`registered-keys` 仍不提供事务内的读己之写：登记之后本节点其它线程照常从 L1 把已提交的旧值填回 L2，本事务之后的读取可能命中它。“已登记”按 (keyspace, 编码 key) 匹配（S24）。
  - 两个机制都按缓存分层（§10.2）：`cocache.defaults.cache.*` → `cocache.overrides.cache.*`（取更严的值：`private` 严于 `registered-keys` 严于 `allow`，`true` 严于 `false`）→ `cocache.caches.[name].*`，没有声明层；只作用于 `Cache` 与 `VersionedCache`（含 `get(key, loader)`），权威副本上全局层跳过、`caches.[name]` 按 `caches-validation` 处理；未分层的缓存取类型默认值（`true`、`registered-keys`）。有 spring-tx 时报告列出两者及来源。`TransactionalFillObserver` 的主构造函数按缓存名取两个值（`Predicate<String>`、`Function<String, FillInTransaction>`）。
  - 两个机制对一个缓存都关闭时，`enter(cacheName)` 对它返回 `null`：与 `FillObserver.NONE` 相同，不门控、不记录，事务中的 leader 也照常共享结果。
  - 测试：`cocache-core` 的 `FillObserverTest`（入口捕获、私有回源、记录、leader 不共享、重入、`FillKey`），`cocache-spring` 的 `TransactionalFillObserverTest`（回滚、结果未知、savepoint、降级填充、三种取值、只读事务），starter 的 `TransactionalFillTest`（经 `@Cacheable(sync = true)` 与 `@CacheEvict`，及两种通知嵌套）。
- **事务装饰器**（`TransactionAwareSpringCacheManager` / `TransactionAwareSpringCache`，`cocache-spring`，RFC 0005 §4.9 U3）：有 spring-tx 时 starter 用它包装 `CoCacheSpringCacheManager`，取代 Spring 的 `TransactionAwareCacheManagerProxy`。装饰器按缓存名缓存，缓存通知每次取缓存不再分配。事务同步激活时（与 Spring 的装饰器相同，按 `isSynchronizationActive()` 判断）：
  - **`evict`**（`@CacheEvict`）登记到当前事务的登记：提交、回滚、回滚到 savepoint 时都失效。Spring 的装饰器只在提交之后失效：同一事务内先 `@CacheEvict` 再 `@Cacheable(sync = true)` 读穿透，回滚之后未提交的数据留在 L1 直到 `ttl`。
  - **`evictIfPresent`**（`@CacheEvict(beforeInvocation = true)`）委托 `CoCacheSpringCache.evictIfPresent`，按 Spring 的契约立即执行。只在提交之前失效会丢失失效（G2）：提交之前并发的读穿透把已提交的旧值经 lease 写回 L1，提交之后再没有失效。因此它在事务内先登记，再立即失效：提交、回滚、回滚到 savepoint 时各再失效一次，多余的失效无害；先登记，立即失效抛出并被 `CacheErrorHandler` 吞掉时，事务结束之后的失效照常执行。没有事务、或 spring-tx 不在类路径上时只立即失效一次。
  - **`put`**（`@CachePut`）推迟到提交之后，回滚与提交结果未知时丢弃。之后对同一缓存、同一 key 登记的失效（任何入口）**取代**它：提交时先执行全部失效，再按登记顺序执行没有被取代的写入。先 `@CachePut` 再 `@CacheEvict` 的结果因此是失效，先 `@CacheEvict` 再 `@CachePut` 的结果是写入的值；Spring 的装饰器按注册顺序逐个执行，也是这个结果，但 CoCache 的失效登记合并在一个同步中，没有这条规则时顺序会错。`put(k, null)` 映射为失效，按 `evict` 登记（失败交给 `handleCachePutError`）。CoCache 的缓存按 (keyspace, 编码 key)（`FillKey`，S24）匹配：经接口代理与经引擎登记同一个 key 时同样相互取代；其它 `Cache` 实现按对象身份匹配。
  - **回滚到 savepoint**（`NESTED`、编程式 savepoint，Spring 6.2 起的 `savepoint` / `savepointRollback` 回调）：丢弃 savepoint 之后登记的写入，之前登记的照常在提交之后写入；登记创建于 savepoint 之后（嵌套段内第一次登记）时，回滚到它丢弃全部写入。登记的失效保留并立即执行（见上）。嵌套段内的 `@CachePut` 因此不会在回滚到 savepoint 之后仍于外层提交时写入。
  - **`clear`**（`@CacheEvict(allEntries = true)`）推迟到提交之后，回滚时丢弃；**`invalidate`**（`allEntries` 且 `beforeInvocation = true`）按 Spring 的契约立即执行。两者都只清本节点 L2。读取与 `putIfAbsent` 直接委托。
  - **迟到的登记**：本事务已经完成之后（其它同步的 `afterCommit` 中）登记的写入立即执行；第一次登记发生在其它同步的 `afterCommit` 中、或排在前面的同步的 `afterCommit` 抛出时，写入在 `afterCompletion(STATUS_COMMITTED)` 中执行。
  - **事务结束之后的失败**（RFC 0005 S18）：推迟的 `put` / `clear` 与登记的失效在 `afterCommit`（以及回滚时的 `afterCompletion`）中失败时，交给缓存通知的 `CacheErrorHandler`（`handleCachePutError`、`handleCacheEvictError`、`handleCacheClearError`，缓存参数是装饰器本身），与非事务路径一致，而不是在提交之后抛给以为操作失败、准备重试的调用方。处理器抛出（默认的 `SimpleCacheErrorHandler` 重新抛出）时只记录错误日志，不再抛出：数据源已经提交或回滚，抛出还会让 Spring 跳过其余同步的 `afterCommit`。starter 在失败时才经 `ObjectProvider<CacheAspectSupport>` 取得唯一缓存通知的处理器（此时已配置完成，也看得到 `degrade-on-error` 换上的处理器），没有缓存通知时用 `SimpleCacheErrorHandler`；自行装配时 `TransactionAwareSpringCacheManager(manager, errorHandler)` 的默认处理器是 `SimpleCacheErrorHandler`。非运行时异常包装为 `IllegalStateException`（`CacheErrorHandler` 只接受运行时异常）。
  - 没有事务同步时一律直接执行，失败照常抛给调用方，由缓存通知交给 `CacheErrorHandler`。
- **缓存通知与事务通知的嵌套**（RFC 0005 S19）：`@EnableCaching` 与 `@EnableTransactionManagement` 的 order 默认都是 `LOWEST_PRECEDENCE`，同一个方法上谁在外层取决于 advisor 的注册顺序。
  - **事务通知在外层**（推荐，本节的描述以此为前提）：缓存操作在事务内执行，经事务装饰器推迟与登记；`sync = true` 的 loader 在事务内运行，读到本事务未提交的数据，回滚时已登记的 key 失效。
  - **缓存通知在外层**：缓存操作在方法自己的事务结束之后（或开始之前）执行，看不到事务。`@CacheEvict` 在提交之后直接失效，不登记，回滚（方法抛出）时不执行；`@CacheEvict(beforeInvocation = true)` 在事务开始之前失效一次，提交之后不再失效，提交之前并发的读穿透写回的旧值留到 `ttl`（G2）；`sync = true` 的 loader 在方法自己开启的事务中运行：调用入口还没有事务，不门控、不记录，填充发生在事务结束之后，方法回滚时它填入的值留到 `ttl`。
  - 响应式方法上的 `@CacheEvict` 在 `doOnSuccess` 中执行，事务位于外层时早于提交（响应式事务的同步不绑定线程，装饰器看不到，见上）。
  - `SpringCacheSyncCheck` 在两者都以代理模式注册（bean `org.springframework.cache.config.internalCacheAdvisor` 与 `org.springframework.transaction.config.internalTransactionAdvisor`）且 order 相同时告警，建议 `@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)`（Spring Boot 的事务自动配置随之退让）。AspectJ 模式没有 advisor，不检查。

### 11.4 Spring AOT 与原生镜像

AOT 处理时，`AnnotatedCacheRegistrar`、`JoinCacheRegistrar`（`ImportBeanDefinitionRegistrar`）与 `CacheSpecRegistrar`（`BeanFactoryPostProcessor`）在**构建期**运行，它们产生的 bean 定义被写成生成代码；运行时由生成的 `ApplicationContextInitializer` 注册 bean 定义，注册器不再运行（`CacheSpecRegistrar` 作为 bean 仍会运行，见下）。

- **缓存 bean 一律由 `CacheBeanDefinitions` 的静态工厂方法创建**（`annotatedCache(type, beanFactory)`、`joinCache(type, beanFactory)`、`specCache(specName, beanFactory)`）。bean 定义只携带一个可序列化的构造参数（接口 `Class` 或 spec bean 名）与目标类型（接口本身，或带 spec 泛型的用户 API 类型，如 `Cache<K, V>`、`SetStore<K, E>`），`BeanFactory` 按类型自动注入。AOT 能为工厂方法生成直接调用的代码；lambda 实例提供者（`instanceSupplier`）不能生成，会让 AOT 处理失败。
- **识别缓存 bean 只看这个构造参数**（`cacheInterfaceOf` / `joinInterfaceOf` / `specBeanOf`），不使用 bean 定义属性：AOT 生成的 bean 定义不保留属性。`declaredCacheNames`、组合视图按缓存名查找 spec 缓存都依赖这一点。
- **`CacheSpecRegistrar` 幂等**：目标 bean 名已存在且是同一个 spec 的缓存定义时跳过（AOT 运行时生成代码已注册），其它重名仍启动失败。
- **运行时提示**：`CoCacheAotProcessor`（`BeanFactoryInitializationAotProcessor`，经 `META-INF/spring/aot.factories` 注册）读取上述 bean 定义，为每个接口式声明与组合视图登记 JDK 代理（接口列表与 `CacheProxies` / `JoinCacheProxies` 创建代理时一致，只有接口本身）、接口公共方法反射（注解、泛型、抽象方法校验、`invokeDefault`）、key 类型的 `toString` 反射（默认 `KeyEncoder` 的检查，spec 声明同样登记）、`keyExpression` / `joinKeyExpression` 求值对象（key 类型 / 主缓存值类型）的公共方法与字段、使用默认 `JacksonCodec` 时值类型的 Jackson 绑定。`CoCacheRuntimeHints`（`RuntimeHintsRegistrar`）登记与声明无关的固定提示：五种用户 API 类型与 `ReadableCache`、它们的异步与挂起视图、`JoinView` / `JoinCache` 的公共方法（`CoCacheRuntimeHints.API_TYPES`）、L2 用到的四个 Caffeine 生成类（`SSMS`、`SSMSA` 与节点类 `PSMS`、`PSAMS`，对应 `maximumSize` 加可选 `expireAfterAccess`）。
- spec 声明的编解码、数据源与 SpEL 以外的反射由使用者负责登记提示；`@EnableCoCache` 列表、spec bean 与 `@Conditional` 条件在构建期固定，策略配置项（`cocache.defaults.*`、`cocache.overrides.*`、`cocache.caches.*`、`cocache.key-prefix`）仍在运行时绑定。
- 验证：`CoCacheAotTest` 检查提示，`CoCacheAotRuntimeTest` 编译生成代码并在没有注册器的新容器中运行；`cocache-example` 的原生镜像由 `.github/workflows/native.yml` 构建并冒烟。新增反射、代理、资源访问或改变缓存 bean 的创建方式时，同时更新提示与这两个测试。

## 12. 检验

### 12.1 TCK

`cocache-test` 是已发布的 TCK，`src/main` 下的一切都是第三方实现的 API。

| 你实现 | 继承 |
|--------|------|
| `CoherenceBackend`（L1 协议 + 失效通道） | `BackendProtocolSpec`（挂起形状：用例经 `Suspending.await` 直接调用 `L1Store`；状态机、令牌、到期时间重设、release、reclaim、批量读、批量覆盖写入、版本、摘要与写入标识随每个标量写入保存并原样读回（存储不重算摘要）、带摘要段的值不授予刷新令牌也不被回收、条件读写比较保存的摘要段并拒绝没有摘要段的值、`E` 上的 lease 按 lease 寿命被抢占、`putIfNewer` 拒绝 `NO_VERSION`、集合、广播帧与订阅重置；读不懂的帧（未知版本、预留的帧版本 2、截断、尾随字节）重置订阅者而零 key 的帧照常投递；频道按 keyspace 划分（namespace 相同、前缀不同的两个 keyspace 收不到彼此的帧）；订阅者收到编码 key、不属于这个 keyspace 的 key 被去掉，同一 keyspace 的订阅者按订阅先后依次回调（S4）；读取的时限：后端实现 `pause` 钩子时，L1 卡顿下带 `timeout` 的读取在大约这么长的时间内以 L1 故障失败，它请求的 lease 在恢复之后被释放，Redis 经 `pausable()`、内存后端经 `stall()`，D6 / K14） |
| 后端在引擎下的端到端行为 | `EngineSpec`（`Cache` 与 `Store`，两个运行时共享一个后端：缓存的读取时限 `readTimeout` 在 L1 卡顿时限定单 key、批量与条件读取（后端实现 `pause` 钩子时，D6）、跨节点单次回源、含 `:` 的 namespace 创建失败、零 key 的帧什么也不做、namespace 相同而前缀不同的两个缓存在 key 与失效上都隔离、lease 等待超时、同节点失效与并发读、同节点并发 `put`、读己之写、失效重试、`localMaxAge`、批量、跨节点单次刷新、损坏回收、未知格式、读不懂的内容按有没有数据源失败或回收、外部来源读到更高格式时未填充而 `Store` 失败、只有 `Store` 的写入带写入标识与摘要段、`Store` 读到篡改或缺失的摘要段时失败、派生缓存读到带摘要段的值计入类型不符且不回收、L1 读失败时没有数据源的读取为 `Unknown`（`get` 抛出、`lookup` / `lookupAll` 返回它、批量读取的部分 `Unknown` 先完成其余 key 再抛出）、`unknownRead = ABSENT` 时读到不存在且不写任何一层、`readFailure = THROW` 时带数据源的读取为 `Unknown`、外部来源绕过 L1 的不存在是未填充的、外部来源的失效默认重试、`Store` 的读取不申请 lease、`Store.delete` 同步完成且不重试、写入失败的 `outcomeUnknown`（存储拒绝的写入确定未执行）、`putAll` 的跨节点失效与在途加载栅栏、条件读写（摘要未变、绕过 L2、负缓存与 lease 是没有值、冲突不广播（非原子广播时重放的冲突广播）、`deleteIfMatch`、并发“读—改—条件写”不丢更新、到期时间、读不懂的内容失败（不把本协议的集合与解码失败的值当作没有值、不回收）、L1 故障；只在没有声明 `CONDITIONAL` 时跳过；声明了的必须原子，并发用例强制竞争、断言恰好一个写入方胜出）等）与 `SetEngineSpec`（`SetStore`，要求 `SETS`，含格式 0 的 `FAIL`，L1 读失败为 `Unknown`，元素写入落在读不懂的内容上时拒绝，负缓存上照常写入） |
| 支持版本的后端（`versioned()`，§6.6） | `VersionedSpec`（两个运行时共享一个后端。协议：整体写入保存版本、按版本写入只在版本更高时写入、有符号 64 位比较（跨越 2^32 与 2^53）、按版本写入销毁 lease 而持有者的 fill 被拒绝、条件读写拒绝带版本的内容、带版本的内容授予刷新令牌但不被回收、填充的版本规则（更小或没有版本拒绝并清零令牌、相等续期不广播、更大写入并广播且帧来自令牌的节点）、`E` 的每个脚本（读作未命中、在其上取得与等待 lease、释放保留 `E`、填充的版本规则、按版本写入与墓碑按它的版本比较、回收与集合、条件脚本拒绝）、`E` 保留自己的到期时间、保留版本的失效删除没有版本的内容而拒绝带摘要段与读不懂的内容、墓碑读作负缓存、带版本的负缓存经 fill 写成墓碑。引擎：有序、相等版本幂等、旧版本不修改不广播不改寿命、相等的版本不写入但广播（原子与非原子一致，淘汰别的节点比填充的版本更旧的副本，F1）、寿命同 `put`、墓碑挡住迟到的旧版本、lease 持有者回源期间落地的写入胜出（回源读到的版本更高也被拒绝）、回源保存版本、数据源返回的带版本的不存在写成墓碑、条目到期之后任何版本都能写入、回源写回 L1 之后落地的更新版本、多线程乱序写入收敛到最高版本、结果未知时重放安全、普通写入与 `null` 回源没有版本、读不懂的内容拒绝、解码失败的带版本值不回收、提前刷新与版本前进的填充按写入处理、版本拒绝的刷新保留新副本并退避、`evict` 与运维的按 key 删除保留版本栅栏（`E` 上的回源被拒绝时照常返回、追上之后写入）、`evict` 遇到 `Store` 的值时拒绝、`purge` 丢掉版本栅栏、不带版本的 `null` 落在带版本的内容上被拒绝（K16）；`VersionedStore` 的写入标识、读取不申请 lease、墓碑寿命与 `Unknown`） |
| 多节点交错（§8.1、§5、§7） | `MultiNodeSpec`（三个运行时共享一个后端：失效先于 lease、晚于 lease 且有节点在等待、晚于 fill 而早于写 L2；跨节点写入落在读 L1 与写 L2 之间；跨节点并发 `put`（非原子后端上事件逆序到达）；失效重试期间的未命中；全集群每个 key 单次回源与单次刷新的计数断言） |
| 故障下的 G1 / G2 | `FaultSpec`（L1 整体失败时的降级限龄、fill 失败与 lease 寿命、`Store.delete` 失败时抛出而不重试（重试会删掉之后的写入）、重试耗尽与队列溢出的放弃、“已在 L1 生效却报错”的 `put` / `invalidate` 可安全重试且结果未知；可选：断线期间被拒绝的写入在重连后不被重放、结果确定未执行（存储报告 `L1RejectedException`），后端覆盖 `disconnect()` 才运行；可选：底层客户端取消的命令是 L1 故障而不是调用方的取消（读取降级回源、合并在它上面的跟随者照常得到结果、`put` 以 `CacheWriteException` 失败、调用方看不到 `CancellationException`、恢复之后被取消的读取的 lease 已释放），后端覆盖 `commandCancellation()` 才运行；只在原子广播的后端上：结果未知的 `put` 报错之后才在 L1 执行时，写入方节点自己的帧淘汰它在其间填入的旧副本（P12，`lateOutcomeUnknownPutEvictsTheWritersOwnStaleCopy`）） |

`MultiNodeSpec` 的节点是同一进程内共享一个后端的运行时，不覆盖真实的多进程与网络分区。

- 能力声明（RFC 0005 §4.3）：每个规范都有抽象的 `expectedCapabilities: Set<L1Capability>`（`CONDITIONAL`、`VERSIONED`、`SETS`），没有默认值，忘记声明的后端编译失败。每个规范的 `storeProvidesExactlyTheDeclaredCapabilities` 在两个新的命名空间上断言存储提供的能力（访问器非 `null`）与声明完全相同（多出来的也算失败），且每个访问器稳定（同一个对象或总是 `null`）。需要某个能力的用例只在它没有声明时跳过：`EngineSpec` 的 `Store` 用例要求 `CONDITIONAL`（读不懂的内容那个另要求 `SETS`），`BackendProtocolSpec` 的集合用例与 `SetEngineSpec` 全部要求 `SETS`，`VersionedSpec` 全部要求 `VERSIONED`。`L1Capability` 只是 TCK 的声明，不进入 SPI；`CacheRuntime` 另有一个私有的 `Capability` 枚举（只用于创建时的检查与启动日志），两者不共用：核心不能依赖 TCK，公开核心的枚举又会让能力在核心的公开 API 里多一处表达。`cocache-core` 的 `StrippedTckTest` 在没有任何可选能力的存储上跑整套 TCK（声明为空，需要能力的用例跳过、其余通过），`CapabilityDeclarationTest` 检验声明检查本身（多声明、少声明、访问器每次新建、时有时无都失败）。
- 变体（§5.6、§5.7，RFC 0005 §4.10）：TCK 只有一套，没有 `Async*` 平行版本（L1 SPI 只有挂起一种形状，Java 实现者的 `FutureL1Store` 经 `adapt` 接受同样的规范；`cocache-core` 的 `InMemoryFutureTckTest` 以经 `FutureBackend` 的内存后端跑整套 TCK，另有经异步视图的 `FaultSpec`）。每个规范都有 `protected open val access: CacheAccess`，默认 `CacheAccess.SYNC`，后端实现者只需要它；同步、异步、挂起三种变体的语义属于核心，由核心自己的测试覆盖。仓库内覆盖 `access`，在变体下重跑同一组用例：`CacheAccess.ASYNC` 经 `async()` 视图读写（同步数据源换成在调用线程上执行的异步数据源），`CacheAccess.ASYNC_WITH_SYNC_SOURCE` 保留同步数据源（在运行时的执行器上执行），`cocache-core` 的 `SuspendingAccess` 经挂起视图读写。`CacheAccess` 为五种类型各有一个入口（`cache`、`versionedCache`、`store`、`versionedStore`、`setStore`）与两个数据源调整（`CacheSpec.Builder`、`VersionedCacheSpec.Builder`）。
- 异步变体专有的引擎用例在 `cocache-core` 的 `AsyncVariantEngineCases`（继承 `EngineSpec`，不在 TCK 中）：取消一个或全部等待者、future 在哪个线程上完成、执行器拒绝收尾时照常第二次递增戳并归还回源许可与 lease、lease 等待与回源许可等待只用常数个线程（各 1000 个等待者）、原始异常、不同步抛出、`load.timeout` 释放 lease、数据源自己的 `TimeoutException` 不是引擎超时、同步与异步互相合并、重入、同步数据源在运行时的执行器上执行、运行时关闭时的等待。它以内存后端在四种配置下运行（原生的 `L1Store`、保留同步数据源、每个 L1 结果换线程送达、经 `FutureL1Store.adapt` 与 `FutureInvalidationChannel.adapt` 的 `FutureBackend`）；`SetStore` 的异步专有用例在 `InMemoryAsyncSetEngineTest`。断言真实 L1 客户端线程的三个用例（future 不在后端 I/O 线程上完成、lease 等待与回源许可等待只用常数个线程）在 `cocache-redis` 的 `RedisAsyncThreadCases` 中另有一份。
- 异步变体在 `cocache-redis` 上只是冒烟：`RedisAsyncTckTest` 在 `pubsub` 后端上经 `CacheAccess.ASYNC` 把每个引擎规范族各跑一份（`EngineSpec` 经 `RedisAsyncThreadCases`、`SetEngineSpec`、`MultiNodeSpec`、`FaultSpec`、`VersionedSpec`），设置 `SPRING_DATA_REDIS_CLUSTER_NODES` 时同样连接 Cluster。`RedisL1Store` 只有一种挂起形状，两种变体调用同一组存储方法，差别只在续接在哪个调度器上恢复：非原子与 `sharded` 后端的差别在存储与通道的代码里，由同步矩阵覆盖；保留同步数据源（`ASYNC_WITH_SYNC_SOURCE`）只是引擎的执行器选择，由 `cocache-core` 覆盖。
- `HookedBackend` 包装任意后端以注入故障（`failAll`、`failWrites`、`failFill`、`failRelease`；`rejectWrites` 让写入在发出之前以 `L1RejectedException` 被拒绝；`deferPuts` 让单 key `put` 以结果未知的故障失败、先不执行，`applyDeferredPuts` 再执行并照常广播）、改写读取结果、在读取之后、`fill` 之后与写入前后编排竞态（`afterPut`、`afterInvalidate` 抛出即模拟“已生效但报错”），并能覆盖 `atomicBroadcast` 以验证非原子路径。存储继承 `ForwardingL1Store`，能力面继承对应的 `Forwarding*` 基类（被包装的存储没有某个能力面时这里也没有）；钩子在存储调用所在的协程里、被包装的存储返回之后就地执行（同步变体即调用方线程，异步变体即运行时的执行器线程，从不在 L1 客户端的 I/O 线程上）；`ioExecutor` 让每个调用的结果改在指定线程上送达（模拟 I/O 线程）。`issuer()` 返回发起这次存储调用的线程名，按线程编排的钩子用它代替 `Thread.currentThread().name`。`close()` 只在 `closesDelegate = true` 时关闭被包装的后端（默认 `false`：被包装的后端可能是共享的）。
- 时间：内存后端用 `ManualClock` 推进；Redis 测试用 `RedisTestSupport.shiftTtl` 缩短 key 的剩余寿命来模拟时间流逝。到期时间是毫秒精度，不需要 ±1 s 偏差。
- `cocache-redis` 的 `RedisLayoutTest` 覆盖频道布局与按存储探测实际频道（§9）：频道名、三种广播方式下 `store` 发布的零 key 帧、ACL 不放行实际频道时 `runtime.create` 失败（每个用例建立唯一命名的 ACL 用户、结束时删除）、直接向文档给出的频道名发布的帧送达缓存（前缀为空时同样）、前缀为空时按运维文档的 ACL 运行。
- `cocache-redis` 中读不懂的内容的用例：`ForeignFormatFailTest`（权威副本的读取 `FAIL`）、`ForeignFormatWriteTest`（`SetStore` 的元素写入拒绝、更高格式的值在 `Store` 上失败、存储脚本的判定）、`ForeignFormatReclaimTest`（有数据源时回收后回源）；线格式 2 的字节级用例在 `WireFormat2Test`（§4.2）。它们在单机、Cluster 与 Valkey 上运行。
- `cocache-redis` 中 `BackendProtocolSpec`、`EngineSpec`、`MultiNodeSpec` 与 `FaultSpec` 在 `pubsub`、非原子（ACL 禁止探测频道）与 `sharded` 三种后端上各跑一遍，`SetEngineSpec` 只在 `pubsub` 后端上运行；设置 `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` 时连接其他单机实例（CI 的 Valkey 任务），设置 `SPRING_DATA_REDIS_CLUSTER_NODES` 时连接 Redis Cluster。`FaultSpec` 的断线用例与底层命令被取消的用例只在单机 `pubsub` 上运行（经 `TcpProxy` 断开并拒绝重连；取消：代理丢弃应答，经 Netty 管道向 Lettuce 的 `CommandHandler` 发出 `ConnectionEvents.Reset`，它以 `cancel()` 结束在途的命令，再断开重连）；`NOSCRIPT` 回退（每个操作之前 `SCRIPT FLUSH`）是 `RedisFaultTest` 自己的用例。

### 12.2 测试约定

- 竞态用 latch 编排，禁止 sleep；等待 `finished` latch，让死掉的后台线程使用例失败；跨节点效果用带超时的轮询断言。
- 每个修复的缺陷都要有复现用例（`EngineSpec` 中的同节点竞态用例是范例）。
- 发现阶段的问题让构建失败（根构建脚本的 `junit.platform.discovery.issue.severity.critical=WARNING`，对每个模块）：JUnit 静默跳过返回值不是 `Unit` 的 `@Test` 方法（`fun x() = runBlocking { … }` 的最后一个表达式不是 `Unit` 时就是这样，写作 `fun x(): Unit = runBlocking { … }`）与私有的测试类，这里改为报错。只由用例自己驱动的规范子类写成匿名对象（`CapabilityDeclarationTest`），JUnit 不把它当作测试类发现。

### 12.3 令牌守恒与中断

- 令牌守恒的性质测试（`cocache-core` 的 `TokenConservationPropertyTest`，不在 TCK 中）：异步变体的协程全部在手动执行器上推进，L1 命令由脚本化的后端挂起；每个种子按随机顺序执行任务、以随机结局（成功、未生效的失败、已生效但超时：存储按约定释放令牌）完成命令、完成或失败数据源、取消调用方的 future、关闭运行时。命令完成的顺序任意（SPI 不保证顺序）。排空之后断言每个调用都已完成、L1 授予的每个令牌之后都以 fill 或 release 交还过。同一个种子可以复现。
- 同步变体被中断（`cocache-core` 的 `SyncInterruptTest`、`RunSyncTest`，§5.6）：上面的性质测试只驱动异步变体（同步变体的调用方线程停在 `runBlocking` 中，测试线程无法按种子推进它），中断规则由定向用例覆盖。L1 命令由 `ScriptedBackend` 挂起，中断分别落在取得 lease 的读取、批量读取、L1 命中、`put`（非原子广播）与等待 lease 的重读（含有观测器时嵌套的 `runSync`）期间，断言调用方在命令完成之后才返回、令牌全部交还、loader 没有运行、中断标志已置。`RunSyncTest` 固定 kotlinx `runBlocking` 被中断时等协程结束的行为，依赖降到 1.8.0 时失败。Redis 上的对应用例是 `RedisCommandTimeoutTest.interruptedSyncPutThroughTheEngineWaitsForItsCommandAndIsNeverWritten`。

### 12.4 确定性多节点仿真

- 确定性多节点仿真（`cocache-core` 的 `MultiNodeSimulationTest`，RFC 0005 §4.10，不在 TCK 中）：三个运行时共享同一个挂起式内存后端（`ScriptedBackend`，与上面的性质测试共用），全部协程运行在 `kotlinx-coroutines-test` 的单线程测试调度器上。
  - 虚拟时间：等待 lease 的退避、`withTimeout`（回源许可、跟随者的 `load.timeout`）、缓存时钟与 L1 到期都走虚拟时间。截止时刻（等待 lease、`load.timeout`）经运行时内部的计时源（`Ticker`）计算，生产为 `System.nanoTime()`，仿真为虚拟时间；运行时作用域的调度器在仿真中换成测试调度器（`CacheRuntime.Builder` 的模块内部属性）。异步数据源的 `completeOnTimeout` 仍由 JDK 的延迟线程按真实时间计时，仿真中的加载总在它触发之前完成。
  - 每个种子随机执行：读取（`get`、`getAll`）与写入（数据源提交新版本，再在某个节点上 `invalidate`）；以任意顺序、随机结局完成 L1 命令；以任意顺序投递、重复或丢失失效事件（丢失之后补一个订阅重建，与 Redis 后端以心跳与重新订阅发现丢失一致）；完成或失败加载（快照取于调用时或完成时）；推进虚拟时间；让一个节点崩溃（待完成的命令随机生效但不再送回，之后的命令既不生效也不完成，持有的令牌由 `lease.ttl` 回收）。
  - 以数据源的提交历史为裁判。G2：读取开始时节点对每个 key 有一个已确认的下限（本节点 `invalidate` 成功返回时那次写入的版本；别的节点的失效事件投递时，那次失效在 L1 上生效时数据源的版本；订阅重建投递时，至今已在 L1 上生效的全部失效），读到的版本不得低于它。G1：排空之后越过 `ttl + lease.ttl + localMaxAge`，每个存活的节点都读到最新版本。另断言令牌守恒、调用都已完成。
  - 失败消息带种子与最近的步骤；`-PsimSeed=<n>` 只重放这个种子并打印全部步骤（`aSeedReplaysTheSameSteps` 保证同一个种子总是同一条执行）。去掉收到失效时的 L2 淘汰、读己之写的复核、L2 填充的戳校验、重置时的 L2 清空或写入方的第二次淘汰，都会在前 100 个种子内被发现。

### 12.5 Lincheck

- Lincheck 模型检验（`cocache-core` 的 `*LincheckTest`，RFC 0005 §4.10，[调查](investigations/lincheck-feasibility.md)）：对真实的“SingleFlight + Caffeine L2 + 失效戳 + 内存存储”经同步与挂起 API 检验，每个类一种缓存与一个顺序规格：
  - `CoherentEngineLincheckTest` 与 `EngineOperationsLincheckTest`：有数据源的 `Cache`，规格“带版本的数据源 + 读己之写”（写入 = 提交新版本再 `invalidate` 或 `put`，读取 = 当前版本）。后者是 RFC 的完整操作集：`get`、`get(key, loader)`、`getAll`（与单 key 的 flight 合并）、`invalidate`、`put`、`onInvalidated`、`onReset`、`clearLocal`、写事务中的读取与私有回源（§5.8），以及调用方取消（同步变体被中断、挂起变体的跟随者被取消，事后 L1 上不留 lease）。
  - `ExternalSourceLincheckTest`：外部来源的 `Cache` 上 `get` 与 `get(key, loader)` 交替（P3）。检验不变式：读到的版本不低于读取开始之前完成的写入，带 loader 的读取总有值；“没有”何时出现取决于交错，写不成确定的顺序规格。
  - `StoreLincheckTest`：`Store` 是按 key 的寄存器，读取从不申请 lease（存储记录每次读取的 lease 请求）。
  - `VersionedCacheLincheckTest`：规格“带版本的写入方 + 读快照的 loader”：`putIfNewer`、`invalidateIfNewer`（墓碑）、`evict`（状态 `E`）与 `clearLocal`，写入方之间不加锁。

  Caffeine 的调用与 `InvalidationStamps.invalidateAll` 当作原子步骤，计数与日志忽略。有随机场景，也有对已知竞态穷举交错的定向场景。JaCoCo 的探针与 kotlinx 调试模式的协程编号会让执行无法重放，所以它在单独的 `./gradlew :cocache-core:lincheck` 任务中运行（关闭两者，每个类一个 JVM，至多 4 个并行），不在 `test` 与 `check` 中。默认是 CI 的预算（场景相同，执行次数少，本地约 1 分钟）；改动 flight 层、L2 或失效戳时以 `-Plincheck.full` 运行完整的预算（本地约 5 分钟）。

### 12.6 TLA+ 模型

- TLA+ 模型（`docs/spec/CoCache.tla`，RFC 0005 §4.10，[说明](spec/README.md)）：一个 key 的多节点协议，L1 状态机（`A` / `L` / `V` / `M` / `E`、令牌与版本）、每个节点的 L2 与失效戳（含 `fillGuarded` 的三步与重置的竞态）、不经 SingleFlight 的读取者、可丢失、乱序、重复的失效通道，以数据源的提交历史为裁判。故障：超时之后仍执行的命令、连接替换之后旧连接上的命令晚于新命令执行、令牌的异步释放（K14）、结果未知的写入与带版本的 `FILL`（P12）、回源失败、L1 与 L2 到期、持有令牌崩溃。TLC 在小规模下穷举全部交错，检验 G2 的 L1 与 L2 条款、读取方看到的 G2、失效不丢失、I-K 的版本单调、P2，以及令牌最终释放与收敛（活性）。CI 的非阻塞作业 `TLC` 运行 `scripts/tlc.sh`（下载固定版本的 `tla2tools.jar` 并校验 SHA-256）；尚未改正的反例在 `Findings*.cfg` 中（`scripts/tlc.sh Findings`），不在 CI 中运行。

## 13. 可观测与运维

- **`CacheMetrics`**（每个缓存一份，`LongAdder` 累计值）除 `localHits` 外全部记录在未命中、写入、失效路径上。读取按层互斥计数：每次读取（批量读取按 key）计入 `localHits`（L2 命中）、`misses`（L2 未命中）或 `filtered`（key 过滤器）之一，未命中再由 L1 命中计入 `remoteHits`、由回源计入 `loads`。三种变体计入同一组计数；`loadTimeouts` 来自三处：异步数据源或调用级挂起 loader 超过 `load.timeout`，以及跟随者等到共享加载的截止时刻。
  - **L2 命中计数默认关闭**（G3）：`CacheRuntime.Builder.recordLocalHits(true)`（starter：`cocache.metrics.local-hits=true`）对该运行时的全部缓存开启，`CacheMetrics.localHitsRecorded` 反映开关。开启时命中路径上唯一的记录是一次 `LongAdder.increment()`（不加锁、不分配、按线程分散单元）；关闭时命中路径只多一次对引擎 final 字段的判断，不触碰计数器。7 个命中点（`get`、`get(key, loader)`、`lookup`、`getAll` 逐 key，及异步视图的对应方法；`SetEngine` 的同步与异步 `entryOf`）都经 `AbstractEngine.countLocalHit()`；`get(key, loader)` 遇到“未填充”（`unfilled`）的副本不算命中。开关存在的原因：命中路径吞吐对一次原子自增敏感（JMH：开启后 `l2HitViaInterface` 1/8 线程 −7.7%/−8.9%、`l2Hit` −5.0%/−4.4%；关闭时与未加计数的版本持平，见 #655），因此不能默认开启。
- **Micrometer**（`CoCacheMeterBinder`，指标目录在 `CacheCounters`，tag `cache`）：`cocache.hits{level=l2}`（只在开启 L2 命中计数时注册，端点同样只在开启时输出 `localHits`，避免把“未记录”读成 0）、`cocache.hits{level=l1}`、`cocache.misses`、`cocache.filtered`、`cocache.load.failures`、`cocache.load.rejections`、`cocache.load.timeouts`（异步数据源或调用级挂起 loader 的一次加载、或等待共享加载的读取超过 `load.timeout`）、`cocache.lease.waits`、`cocache.lease.wait.timeouts`、`cocache.fill.rejections`、`cocache.degraded.fills`、`cocache.stale.discards`、`cocache.reclaims`、`cocache.unknown.formats`、`cocache.refreshes`、`cocache.refresh.rejections`、`cocache.refresh.skipped`（到期却没有运行的提前刷新，§5.5）、`cocache.remote.failures`、`cocache.invalidations.received`、`cocache.write.failures`、`cocache.invalidation.retries`、`cocache.invalidations.dropped`、`cocache.resets`、`cocache.conditional.unchanged`（`getIfChanged` 确认未变）、`cocache.conditional.conflicts`（`putIfMatch` 冲突）、`cocache.versioned.stale`（`putIfNewer` / `invalidateIfNewer` / `deleteIfNewer` 遇到不更旧的版本）、`cocache.type.mismatch`（读到别的类型的缓存写入的内容：派生缓存读到带摘要段的值，或 `evict` 遇到它，D4）、`cocache.reads.unknown`（读取的结果为 `Unknown`，按调用方计，含按 `unknownRead = ABSENT` 换算的，§5.1）、`cocache.fill.unversioned.absent`（带版本的数据源返回不带版本的 `null`、落在带版本的内容上被拒绝，K16）、`cocache.fills.gated`（写事务内的私有回源，按 key 计，§5.8）、`cocache.ttl.clamped`（按调用给出的寿命超过 `overrides.cache.ttl` 的上限、被钳制，S8，§10.2；端点字段 `ttlClamped`）；FunctionTimer `cocache.loads`；Gauge `cocache.local.size`。延迟创建的缓存在 bean 初始化后补绑。
- **回源观测 `LoadObserver`**（`cocache-core` 的 SPI，经 `CacheRuntime.Builder.loadObserver` 设置，存于 `EngineContext`，默认 `LoadObserver.NOOP`）：只包住 `guarded` 内的回源调用（`load` / `loadAll` / `refresh`，提前刷新经委托给 loader 的 `RefreshLoader` 识别）与确实发生的 lease 等待（`lease.wait`）。**L2 命中、L1 读取与填充都不经过它**（G3）；默认 NOOP 时引擎按引用识别并直接执行，不分配 lambda。被并发上限拒绝的回源不观测。SPI 只有 `observe(cacheName, operation, keyCount, block: Supplier<T>): T`。实现必须在调用 `observe` 的线程上同步执行 block 恰好一次，原样返回结果、原样抛出异常（异常透明），不得记录 key。
  - 同步变体在调用方线程上调用它。
  - 异步与挂起变体在观测专用的线程上调用它（每次被观测的回源一个虚拟线程，`cocache-observe-N`，与 `cocache.executor` 无关，从不是调用方线程，§5.6）：这个线程等待整个回源结束，观测因此覆盖整个回源，直到数据源的 future 完成。调用观测器时已装上调用方协程上下文中的 `ThreadContextElement`（挂起调用方的 MDC、追踪上下文），被观测的回源因此接在调用方的观测之下；异步视图（`CompletableFuture`）的调用方没有这类元素。block 从这个线程上开始，`loadAsync` 的同步部分看得到观测器设置的线程局部状态（如 Micrometer 的当前观测，追踪上下文可以传下去）。引擎超时记为结果 `timeout`。

  starter 的 `CoCacheObservationAutoConfiguration`（`@ConditionalOnClass(ObservationRegistry)`、`@ConditionalOnBean(ObservationRegistry)`、`cocache.observation.enabled`）注册 `MicrometerLoadObserver`：Observation `cocache.load`，低基数标签 `cache`、`operation`、`outcome`（`success` / `error` / `timeout`：引擎的超时 `SourceLoadTimeoutException`），高基数标签只有 `key.count`；block 在 `openScope()` 内执行，`loadAsync` 的同步部分能看到当前观测；`cocacheRuntime` 经 `ObjectProvider` 取唯一的 `LoadObserver`，因此 `cocache-core` 不依赖 Micrometer，没有 Micrometer 时该自动配置整体跳过。
- **actuator 端点 `cocache`**（基于 `CacheAdmin`）：`GET /actuator/cocache` 列出缓存与计数，`GET /actuator/cocache/{name}` 查看单个缓存，`DELETE /actuator/cocache/{name}` 清空本节点 L2，`GET /actuator/cocache/{name}/{key}` 查看一个 key，`DELETE /actuator/cocache/{name}/{key}` 按 key 删除（按类型映射，见下面的 `invalidateEncoded`；响应带 `deletion`，即 `CacheAdmin.KeyDeletion`），`DELETE /actuator/cocache/{name}/{key}?purge=true` 清除并丢掉版本（只用于带版本的类型，其它类型 400），`POST /actuator/cocache/{name}/{key}` 只广播这个 key 的失效帧、不修改 L1（`broadcastEncoded`，对任何类型都安全，不需要开关）。写操作（DELETE 与 POST）受 `management.endpoint.cocache.access` 约束。删除唯一副本的按 key 删除要显式开关（`CoCacheEndpointProperties`，RFC 0005 §4.9 运维入口，默认都关闭）：`Store` 与 `SetStore` 要 `management.endpoint.cocache.allow-store-delete=true`，`VersionedStore` 的删除与 `?purge=true` 要 `management.endpoint.cocache.allow-purge=true`；没有开启时不修改任何东西，HTTP 上返回 403，响应体指出要开启的开关（经 JMX 时抛出 `DeletionNotAllowedException`）。`Cache` 的失效与 `VersionedCache` 保留版本的 `evict` 不需要开关。`{key}` 是**编码后的 key**（存储 key 中 `{keyPrefix}{namespace}:` 之后的部分，可含 `/`，按剩余路径段匹配），适用于任意 key 类型；未知缓存的 GET、DELETE 与 POST 都返回 404（不做任何事；DELETE 与 POST 的状态码由 web 扩展 `CoCacheEndpointWebExtension` 映射，拼错的缓存名因此不会静默成功）；空 key 返回 400。
- **`CacheAdmin`**（运维接口，不属于用户 API）：`name`、`apiType`、`layered`、`policyReport`（未分层时为 `null`）、`metrics`、`localSize`、`pendingInvalidationCount`、`clearLocal()`、`inspect(encodedKey)`、`invalidateEncoded(encodedKey)`、`broadcastEncoded(encodedKey)`（只广播失效帧：本节点先递增戳、淘汰 L2，因为自己的帧被过滤；发布失败时抛出）、`keyDeletion`（`invalidateEncoded` 对这个类型做什么：`INVALIDATE`、`EVICT`、`DELETE`、`PURGE`、`CLEAR`）、`purgeEncoded(encodedKey)`（只有带版本的类型支持）。
  - `inspect` 是纯读取，**不触碰命中路径**：L2 用 `getIfPresentQuietly`（不影响淘汰统计），L1 用不带 lease 与刷新令牌的 `read(key, null, null)`（读脚本在参数为空时不写任何状态）；不经 SingleFlight、不取戳、不写任何一层、不计入指标、不回收损坏内容。值用缓存的 `Codec` 解码后以 `toString()` 展示，截断到 256 字符；解码失败、未知或更高格式返回 `UNREADABLE`，L1 读失败返回 `ERROR`。
  - `invalidateEncoded` 按类型：`Cache` 走正常的失效路径（§6.1，失败按 `invalidateFailure` 处理），幂等；`VersionedCache` 是保留版本的失效（`evict`，§6.6），带版本的内容转为 `E`；`Store` 等价于 `delete`、`VersionedStore` 等价于 `purge`、`SetStore` 等价于 `clear(key)`；后四者失败时抛出，不进入失效重试。端点据 `keyDeletion` 决定要不要开关（见上）。
  - `inspect` 在权威副本上同样校验摘要段（不计数），不一致时展示为 `UNREADABLE`；外部来源的缓存未填充的 L2 副本展示为 `UNFILLED`。
- **健康检查 `cocache`**（`CoCacheHealthIndicator`）：只读内存状态，不访问 Redis。Redis 失效通道最近一次心跳失败（`RedisBackend.heartbeatHealthy`，只读状态）时 `DOWN`，其余为 `UP`；失效重试中不影响状态（重试队列自行收敛，放弃计入 `invalidations.dropped`）。详情：后端类型、格式版本、`atomicBroadcast`、Redis 的通道模式与心跳、失效重试的总数，以及每个缓存的失效重试数与本地副本数。
- L1 中的值带二进制头，不能再用 `redis-cli GET` 直接读出 JSON。

## 14. 性能验证

命中路径（L2 命中、L1 命中）的改动必须附带 JMH 数据：

```bash
scripts/jmh-compare.sh -b l2Hit,l2HitViaInterface,l2HitMixed -t 1,8   # 与 origin/main 对比；需要 localhost:6379
./gradlew :cocache-redis:jmh --rerun -PjmhThreads=8 -PjmhIncludes=l2Hit   # 单次试跑；check 只编译不运行
```

- 基准：`redis/src/jmh` 的 `CoCacheBenchmark`，方法 `l2Hit`、`l2HitViaInterface`、`l2HitMixed`（轮流访问三个配置不同的缓存）、`l2HitAbsent`（权威缓存的 `Absent`）、`l2HitUnfilled`（外部来源的“未填充”）、`l1Read`、`l1SequentialRead`、`l1BatchRead`、`missLoad`、`set`、`invalidate`。`-PjmhIncludes` 是正则，`l2Hit` 同时匹配全部 L2 基准。接口式声明经 starter 装配、带 `keyExpression` 的命中在 starter 的 `AnnotatedCacheBenchmark`（`l2HitAnnotated`、`l2HitKeyExpression`，`-m :cocache-spring-boot-starter`）。
- 必须加 `--rerun`：参数不变时 Gradle 判定 `jmh` 任务为最新，静默沿用上次的结果。
- 前后对比用 `scripts/jmh-compare.sh`，它固化了下面的方法，输出可直接贴进 PR 的 Markdown 表格（原始 JSON 保留在输出目录）：
  - **每次只跑一个基准**（精确正则 `^.*\.<name>$`）：同一个 `jmh` 任务里排在后段的基准在 8 线程下会因长时间满载而明显变慢（实测低 20%–30%），单独运行时回到基线（RFC 0004 §14）。
  - **同轮交替、配对比值**：基线在临时 worktree 中构建，每轮两侧各跑一次且先后顺序逐轮轮换；结论看同轮配对比值 new/base 的中位数与 [min–max]，不看两侧各自的均值，因为数值会随轮次整体漂移。
  - **负载门槛与作废**：每次开跑前等待 1 分钟负载降到门槛以下（默认 max(2, 核数 × 0.4)）；迭代 CV 超过 3%，或运行期间负载升高超过 min(线程数, 核数) + 1.5（自身贡献之外还有别的负载），该次运行作废并自动重试。
  - **本机互斥**：全机共享的锁 `/tmp/cocache-jmh.lock`（macOS 的 `TMPDIR` 按用户隔离，不用它）保证同一时间只有一个基准任务（含构建），不经脚本启动的 JMH 进程也会等它结束；多个会话同时跑基准会互相污染。
  - 基线 ref 不会自动 fetch，需要最新基线时先 `git fetch origin`。
- 异步视图与经引擎的基准：`l2HitAsync`、`l2HitAsyncMixed`、`l1HitViaEngine` / `l1HitViaEngineAsync`、`l1BatchViaEngine` / `l1BatchViaEngineAsync`、`missLoadAsync`、`setAsync`、`invalidateAsync`（闭环，每次操作后等待），以及在途请求基准 `l1HitInFlightAsync` / `setInFlightAsync` 与同步对照 `l1HitSequential` / `setSequential`（每次调用 32 个请求）。
- 1 线程与 8 线程，与上一版本**至少交替运行 5 轮**（脚本默认），比较配对比值的中位数与区间，包含 `l2HitMixed`。同一台机器上 8 线程的结果在多次运行之间波动约 ±4%，差异小于噪声时不作结论。

## 15. 不变量

每条不变量都保护 §1 的一个目标；削弱其中一条的改动就是错的，无论看起来多整洁。

编号是稳定的：代码注释、测试与文档都按 `I-n` 引用。数字编号与字母编号（I-A–I-M，编号沿用 RFC 0005 §6，内容以本节为准）同样稳定，按主题分组列出；编号从不复用、从不重排，新增的不变量从 I-26 起编号。并入其它条目的编号见本节末尾。

### 15.1 类型与读取结果

- **I-A 类型与声明决定默认值**：`Cache` / `VersionedCache` 是派生副本，`Store` / `VersionedStore` / `SetStore` 是权威副本（§2）；派生缓存必须声明数据来源（spec 的数据源，或 `externallySourced()`）。默认值与可用的操作由类型与声明决定；同一类型内，这次读取有没有数据源（含调用级 loader）只影响未命中与故障时能否回源。
- **I-B `Unknown` 只表示故障**：它只来自 L1 读取失败（含 `readTimeout` 超时），不写入任何一层、不广播，也不作为带数据源的调用方的结果（合并规则，I-5），显式 `readFailure = THROW` 时除外：带数据源的读取遇到同样的故障也只能得到它；`get` / `getAll` 遇到它抛出 `CacheReadException`，`unknownRead = ABSENT` 只在调用方返回之前把它换算为不存在（§5.1）。
- **I-2 L1 未命中就是未命中**：key 不存在或处于 `L`、`E` 状态都不代表负缓存；负缓存只有状态 `M` 一种形态。外部来源的“未填充”不是负缓存（带 loader 的读取不接受它）；权威副本的 L1 未命中是 `Absent`（L1 是唯一副本）。
- **I-3 读路径从不无条件删除**：读不懂的内容（同格式的损坏与摘要校验失败，以及格式号低于本格式的内容，含格式 0）只能通过 `reclaim`（比较指纹）转为 lease，而且只在这次读取有数据源时（I-A）：没有数据源的读取不回收，抛出 `CacheReadException`。`RECLAIM` 拒绝带版本段或摘要段的内容与 `E`（I-K、I-M）；更高的格式既不回收也不写回（I-L），权威副本也不把它当作不存在（§5.4）。

### 15.2 栅栏与 L2

- **I-1 栅栏规则**：任何填充都必须持有读取之前取得的令牌（L1 lease 或刷新令牌；L2 失效戳）。L1 的失效通过销毁令牌完成；L2 的失效方（含 `put`、元素写入）在修改 L1 **之前和之后**各递增一次戳并淘汰 L2（§6.1、§8）。**L2 只由读 L1 的路径填充**，写入方从不直接写 L2。版本前进的填充（`Filled(advanced = true)`）与结果未知的带版本填充（存储在写出之前拒绝的除外）在本节点按写入处理：递增戳、淘汰本地副本、不填 L2，非原子广播时由引擎发布（§5）。
- **I-C L2 填充**：校验失效戳与写入 L2 在同一个 key 锁内完成（Caffeine `compute`），写后按身份复核（`remove(key, entry)`，`LocalEntry` 按身份比较）；写入方与远端事件处理完成之后，读取开始于它们之前的副本不可见；重置与 `clearLocal` 之后，这样的副本至多存在到填充方自己的复核（§8）。
- **I-G 刷新有界**：每个节点、每个 key 的刷新触发率有一个与命中率无关的上界：同一时刻至多一次刷新，没取得刷新令牌的重新填充把刷新点退避到 `now + min(max(lease.wait, 100 ms), 剩余寿命 / 2)`。副本出生时不在刷新窗口内：L2 副本的刷新点要么晚于创建时刻，要么不设；除上面的重新填充之外，L1 剩余寿命不长于窗口时不设刷新点，读到更高格式、在 `E` 上被版本拒绝、带摘要段的副本也不设（§5、§5.5）。否则每次命中都会触发刷新：没有令牌的节点按命中频率读 L1，持有令牌的节点按命中频率回源。

### 15.3 写入、广播与失败

- **I-4 能原子广播就原子广播**：后端以 `atomicBroadcast` 声明能力。原子广播时写入与广播在同一个脚本中完成，版本前进的 `FILL` 与版本相等的 `Stale`（F1）也在脚本中广播。为 `false` 时由引擎在写入之后发布，写入失败时也尽力发布同一帧（§6.1）；可能是重放的结果同样发布：`Stale` 且当前版本等于给出的版本、`putIfMatch` 冲突且当前摘要等于这次写入的摘要、`deleteIfMatch` 冲突且当前没有值（§6.5、§6.6）。每个存储按实际频道探测，ACL 拒绝时创建失败，不降级为非原子广播（§9）。TCK 按能力分别验证。
- **I-D 延迟的无条件写入只用于派生副本**：失效重试与补偿失效只用于 `Cache`（有数据源或外部来源），且失效失败模式为 `RETRY`（默认）。权威副本的删除与写入，以及保留或丢掉版本的删除（`evict`、`purge`），同步完成或抛出，从不排队重试（§7）。
- **I-E 结果未知**：写入的结果按客户端能观察到的事实判定。存储在写出之前的拒绝（`L1RejectedException`）与后端拒绝了写入是确定未执行；其余（超时、断线时被排空、脚本的错误回复、被取消的写入，K18）都是结果未知（`CacheWriteException.outcomeUnknown`，§7）。引擎不取消 L1 调用，中断不到达 L1 命令。确定未执行的写入不会再执行；结果未知的写入在任何时刻都可能生效，它的安全只来自顺序（令牌、版本），摘要条件有 ABA（§6.5）。原子广播的后端上，结果未知的写入之后 `localMaxAge` 之内，本节点不再过滤关于这些 key 的自身帧（P12，§6.1）。
- **I-F 令牌守恒**：取得令牌的每一步（`read`、`readAll`、`reclaim`、刷新的 `read`、`fill`、`fillAll`），在每个失败转移上、在超时、取消与中断时，都有释放：loader、`ttlOf`、`Codec` 失败，回源被拒绝或超时，以及刷新令牌用不上时都要 `release`。取得或交回令牌的 L1 命令失败时，除确定未执行外，存储都异步释放这些令牌、不等待，释放失败时按退避重发（K14，§9）。引擎从不取消 L1 调用：各变体都在 `NonCancellable` 中调用 `L1Store`（`uncancellable`），中断不到达存储，执行器拒绝派发也不取消正在执行的调用；协程被取消时，持有令牌的步骤在之后的挂起点释放它，同步变体取消之后不再调用 loader；带着令牌的结果不经可能被取消的协程的返回值传递（§5.6）。每个 flight 在有限时间内完成，或在 `load.timeout` 之后被注销。
- **I-H 等待有界**：跟随者（`load.timeout`）、lease 等待（`lease.wait`）、回源许可（`acquireTimeout`）都有上界。调用方的等待响应取消与中断，且只影响它自己；唯一的例外是同步 leader 被中断时取消这一次加载，跟随者至多多回源一次（K13）。L1 命令与令牌步骤不可取消，被中断的调用方至多多等一个命令超时；loader 照常可以被取消（§5.6）。
- **I-17 取消不传播**：调用方的取消（协程取消、future 的 `cancel`、线程中断）只结束它自己的等待：不取消共享加载（异步与挂起变体的共享加载在运行时作用域中执行）、不取消数据源的 future、不跳过写入方修改 L1 之后的步骤（广播、第二次递增与淘汰，§5.6）。同步 leader 内联执行，被中断时取消这一次加载（I-H）。
- **I-8 每次 L1 状态转换都显式重设到期时间**：lease 与负缓存的寿命不得泄漏到新值上，`E` 的到期时间由引擎传入。只触及字节 4–35 的原地修改（授予刷新令牌、`READ` 在 `E` 上授予 lease、`RELEASE`、被版本规则拒绝的 `FILL` 清零令牌）保留原到期时间，是写明的例外（K15，§4.2）。
- **I-12 CoCache 的 L1 连接只访问主节点**，断线时拒绝命令而不是缓冲重放；超时而放弃的脚本命令若尚未写出，就不再写出（完成 `dispatch` 返回的最外层命令，集群中是 `ClusterCommand`，§9）。

### 15.4 并发与执行

- **I-5 SingleFlight 先注销调用、再发布结果**（读己之写依赖这个顺序）：各变体共用一张表（I-I），leader 在注册之后取戳；每个调用方拿到自己的结果，同步调用合并到异步调用时只阻塞自己的线程，异步调用合并到同步调用时经运行时的执行器拿到结果。合并规则逐 key 应用（§5.1）：带数据源的调用方不接受“未填充”或 `Unknown`（`readFailure = THROW` 时的 `Unknown` 除外，I-B）、不接受写事务内 leader 的结果（I-J）、不接受被中断或取消的 leader 的失败，而是自行运行。同线程对同一 key 的重入（同步调用的拥有线程，或正在为该 key 调用数据源的线程；挂起变体另按协程上下文）快速失败；批量合并先注册全部 key、先完成自己负责的 key 再等待别人。跟随者的等待以加载的截止时刻（登记时加上 `load.timeout`）为上限；登记超过它的加载由之后的调用方按身份注销（不完成它），原 leader 的注销不会移走新的加载。
- **I-6 loader 的线程**：同步变体在调用方线程上执行阻塞的 loader（事务、SecurityContext、MDC 等 ThreadLocal 可用）；异步与挂起变体在运行时的执行器（默认虚拟线程）上执行它。异步数据源（`loadAsync`）在调用方线程或 CoCache 的线程（运行时的执行器；有观测器时为观测线程）上调用，调用级挂起 loader 在这次加载的协程中执行；spec 级挂起 loader 在它自己的 `context` 中执行。Future 只包装 I/O：L1 与数据源的 future 完成之后，续接回到调用方线程（同步变体）或派发到运行时的执行器（异步与挂起变体），从不在完成它的线程上就地继续，**事件循环线程上不执行续接**；挂起变体的调用方从不在 L1 客户端的完成线程上恢复；Redis 后端在恢复之后、调用方的调度器上转换脚本返回值（§9）。
- **I-18 异步视图不挂起线程、不同步抛出**：未命中挂起协程，等待 lease 与回源许可都不占线程（阻塞的 loader 在运行时的执行器上执行）；失败以原始异常（不包装为 `CompletionException`）完成返回的 future，异步方法从不同步抛出（§5.6）。
- **I-I 一份协议**：每个协议分支只有一份（`suspend`）实现；同步、异步、挂起三种变体在其上提供，不复制分支（§5.6）。
- **I-J 事务内的回源**（启用 Spring 集成、按默认配置时；按缓存的 `invalidate-fills-on-rollback: false` 关闭第一条，`fill-in-transaction: allow` 关闭第三条，两者都关闭时也不再有第二条，§11.3）：写事务内 loader 产生、进入任何共享层的 key，在回滚、回滚到 savepoint 或结果未知时失效；事务内 leader 的结果不与事务外的跟随者共享；已登记失效的 key（按 keyspace 与编码后的 key 匹配）的回源结果只返回给本次调用。事务上下文在调用入口捕获，异步与挂起变体不在此列（§5.8、§11.3）。

### 15.5 按类型的写入

- **I-10 集合元素增删只用于 L1 是唯一副本的 `SetStore`**，在 L1 中原子完成，且 `SetStore` 没有数据源。从不覆盖读不懂的内容：检查与写入在同一个原子操作中完成，SPI 没有不检查的元素写入。数据源中的集合只能用 `invalidate` 维护（§6.4）。
- **I-19 条件读写只确认 L1**（只在 `Store` 上）：`getIfChanged` 不读写 L2、不回源、不申请令牌、不修改 L1；`putIfMatch` 与 `deleteIfMatch` 的比较与写入在一个原子操作中完成，冲突与拒绝不修改 L1、不广播（非原子广播时的重放除外，I-4），写入方顺序同 `put` / `invalidate`；后端不能原子地完成时不支持它（不提供“先读后写”）。“没有值”是不存在、lease 占位与不带版本的负缓存；读与写对读不懂的内容分类相同，从不把它视为没有值；只认带摘要段、不带版本段的值，脚本只读头部比较写入方计算的摘要段，Lua 不计算摘要。结果未知之后，当前摘要等于自己写入的摘要说明已经写入，不等不能说明没有写入（I-E，§6.5）。
- **I-K 版本单调（按类型）**：经 `VersionedCache` / `VersionedStore` 的写入与回源，不让 L1 的版本后退、不抹掉版本（§4.2、§6.6）：
  - 按版本写入的比较与写入在一个原子操作中完成，版本不更新时不修改 L1、不改变到期时间，版本相等时仍然广播（F1），更旧时不广播；没有版本的内容排在任何版本之前。
  - 写入销毁令牌，被销毁的令牌的填充永远写不回（版本更高也不行）；令牌仍在时填充按版本规则写入：版本更小或没有版本时拒绝并在脚本内清零令牌，相等时续期不广播，更大或 key 处于 `E` 时写入并广播。
  - 带版本的内容取得刷新令牌、从不被回收；版本存放在版本段，不得是 `NO_VERSION`；条件脚本拒绝带版本的内容。
  - L2 同样不后退：刷新被版本拒绝时保留触发它的副本，不以回源值替换（未命中路径上被拒绝的回源值是唯一的答案，以降级限龄进入 L2，§5）；戳仍有效的填充遇到版本更大的未过期副本时保留副本、不写入（计入 `staleFillsDiscarded`），过了刷新点的副本按退避推迟刷新（§5.5，F3）。
  - 抹掉版本的入口只有 `purge`、到期、同一 namespace 上的其它类型（声明错误）与外部工具。后端不能原子地完成按版本写入时不支持它，带版本的类型在创建时失败。
- **I-M 派生路径不改写权威标量**：带摘要段的内容（`Store` 写入的）不被授予 lease 或刷新令牌，`RECLAIM` 与 `EVICT_KEEPING_VERSION` 拒绝它，派生缓存读到它计入 `typeMismatches`（D4）。读取方校验摘要：权威副本从 L1 拿到不带版本的值、以及条件读取拿到值时，重算 SHA-256 并与摘要段比较，不一致或缺摘要段按损坏处理；校验在引擎中，对所有后端相同，成本只在 L1 读取上（K3，§4.2）。

### 15.6 线格式

- **I-9 线格式在一个大版本内冻结**：key 布局（内部 key 的位置 `!`、namespace 字符集）、标量头与段（标志位、版本段、摘要段、扩展区的 TLV 与写入标识）、状态（`V`、`M`、`L`、`E`）、集合字段（`!f`、`!m`）、失效帧及其演进规则、频道命名、摘要算法（带算法标识的 SHA-256）。格式 2 是唯一的格式（§4）。
- **I-L 格式演进**：
  - 未知的格式版本、未知的必须理解的标志位、格式 2 中的未知状态，既不回收也不写回（更高的格式）；格式号更低的内容（格式 0、1）是读不懂的内容，按 I-3 处理。
  - 新的必须理解的位、段或状态只能两阶段发布：先发布能读懂它的版本，一个 namespace 的全部节点都认识之后才写入。扩展区的新 TLV 与新的 `!` 字段旧读者可以忽略，不需要两阶段。
  - 读不懂的失效帧（未知版本、未知类型、解码失败）按该频道的 `onReset` 处理（清空 L2、递增全部戳），从不丢弃；零 key 的帧是合法帧，引擎什么也不做。新增帧（预留的帧版本 2 是“namespace 重置”）因此不需要两阶段发布（D3，§4.1）。

### 15.7 组件、代理与装配

- **I-11 有状态组件只能由运行时创建**：用户只提供 spec 和无状态函数；缓存经 `runtime.create` 或 `CoCacheFactory` 创建（§10.1），两个缓存不可能共享一个 L2。
- **I-13 存储只执行协议，不持有策略**：TTL、刷新窗口、令牌都由引擎作为参数传入，存储只看到相对时长（§3.2）。
- **I-14 代理不包装异常**：接口式声明的代理按方法名直接调用引擎，异常原样抛出（接口方法没有声明的受检异常由 JDK 包装为 `UndeclaredThrowableException`，这是 JDK 代理的规则，§11.2）；spec bean 注入的就是引擎本身。
- **I-16 starter 注册的缓存 bean 必须能由 AOT 生成代码重建**：只用静态工厂方法与可序列化的构造参数，不用实例提供者，不依赖 bean 定义属性；新的接口与目标类型登记运行时提示（§11.4）。
- **I-15 命中路径的改动必须附带数据**：1 线程与 8 线程，按 §14 的方法（每次一个基准，同轮交替至少 5 轮，看同轮配对比值的中位数与区间），并包含配置不同的多个缓存（`l2HitMixed`）。

### 15.8 并入其它条目的编号

这些编号不再单独列出，也不复用：

| 编号 | 内容 | 见 |
|------|------|----|
| I-7 | 令牌必须释放或过期 | I-F |
| I-20 | 按版本有序的写入 | I-K |
| I-21 | 副本出生时不在刷新窗口内 | I-G |
| I-22 | 读不懂的失效帧按重置处理 | I-L |
| I-23 | 版本前进的填充是一次写入；`E` 读作未命中 | I-1；I-2、I-8 |
| I-24 | 读取方校验摘要 | I-M |
| I-25 | 带版本的缓存的 L2 不按版本后退 | I-K |
