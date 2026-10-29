# CoCache 架构与不变量（6.0）

本文是 CoCache 的**唯一架构事实来源**：设计目标、模块边界、协议、失败语义与必须保持的不变量。修改一致性、存储、编解码、代理或 Spring 装配相关代码前先读本文；行为变化时在同一个 PR 中更新本文。

设计推导、被否决的方案与对抗验证记录见 [RFC 0003](rfc/0003-target-architecture.md)。本文与 RFC 不一致时以代码为准，并修正本文；SPI 与线格式的定义以 `me.ahoo.cocache.api.spi` 与 `me.ahoo.cocache.redis.RedisScripts` 为准。

## 1. 目标

二级一致性缓存的本质是“用陈旧度换延迟”。缓存只做三件事：**复制**（填充）、**作废**（失效）、**限龄**（TTL）。所有正确性问题都出在一次开始得早的复制在作废之后才落地。

| # | 目标 | 可度量的表述 |
|---|------|--------------|
| G1 | 陈旧度有上界 | **L1**：协议内竞态造成的陈旧为 0；协议外（绕过缓存的写入、故障切换丢写、loader 读到从库旧数据）≤ `ttl`。**L2**：事件送达时 ≤ 事件延迟；事件丢失时 ≤ `min(localMaxAge, 心跳周期 + 重连时长)`；失效写 L1 失败且重试放弃（或运行时关闭时仍在排队）时 ≤ L1 剩余寿命（各节点 L2 到期后会从仍是旧值的 L1 重新填充；`ttl = null` 时没有上界，计入 `invalidations.dropped` 并告警，需再次失效）；降级填充 ≤ `degradedLocalMaxAge` |
| G2 | 失效不被吞没 | **L1**：读取开始于某次失效之前的填充，永远不会在该失效之后出现在 L1。**L2**：某节点处理完一次失效（本地调用返回、处理了远端事件或执行了重置）之后，该节点 L2 不会再出现读取开始于这次失效之前的填充 |
| G3 | 命中路径便宜 | L2 命中只是一次 Caffeine 查找加一次过期比较（开启 L2 命中计数时再加一次 `LongAdder` 自增），随线程扩展；L1 命中一次往返；单 key 的 `put` / `invalidate` 默认（原子广播）各一次往返，`sharded` 或非原子模式下各两次往返；loader 耗时小于 `lease.wait` 时，全集群同一 key 并发未命中只回源一次 |

非目标：线性一致读。异步 API 是同一个引擎上的视图（§5.6，[RFC 0004](rfc/0004-async-api.md)），不是另一套协议；阻塞栈上同步 API 配合虚拟线程仍是首选。`SetCache` 把 L1 当作唯一副本（§6.3），是有意的例外。

TTL 是安全网，不是机制：它兜底协议之外的问题。协议之内的竞态由栅栏（§4）消除，不靠 TTL 收敛，所以 `ttl = FOREVER` 在协议内是安全的。

## 2. 模块与边界

```
cocache-api                    用户 API + 全部 SPI，零运行时依赖
  Cache / SetCache / Lookup / CacheLoader / Codec / CacheClock
  Versioned / VersionedPut（按版本有序的写入，§6.5）
  AsyncCache / AsyncSetCache / AsyncCacheLoader（异步视图与异步数据源，§5.6）
  CacheReadException / CacheWriteException
  annotation：@CoCache、@CaffeineCache、@JoinCacheable
  join：JoinCache、JoinValue、JoinKeyExtractor（两个缓存的只读组合视图）
  spi：CoherenceBackend、RemoteStore、InvalidationChannel、InvalidationListener、Subscription、
       Envelope、RemoteRead、LeaseRequest、RefreshRequest、Fill、Token、NodeId、Digest、InvalidationFrame、RemoteVersionedWrite、
       AsyncRemoteStore、AsyncInvalidationChannel（可选的原生异步子接口）
cocache-core                   引擎与运行时；依赖 Caffeine、kotlin-logging
  CacheRuntime、CacheSpec / SetCacheSpec（KeyEncoder、TtlFunction、KeyFilter）、CacheAdmin、CacheMetrics、RetryPolicy、ExternalInvalidator、
  LoadObserver、SourceLoadRejectedException、SourceLoadTimeoutException、ForeignFormatException
  engine：AbstractEngine、CoherentEngine、SetEngine、VersionedCache、ValueFormat、InvalidationRetrier、AsyncContext、LoadPermits、BlockingAsyncStore
  internal：SingleFlight、InvalidationStamps、Futures
  policy：CachePolicy、LeasePolicy、LoadPolicy、LocalPolicy、InvalidateFailureMode、ReadFailureMode、ForeignFormatMode
  proxy：CacheProxies、JoinCacheProxies；join：SimpleJoinCache；memory：InMemoryBackend；clock：TickingClock、ManualClock；codec：StringCodec
cocache-redis                  Lettuce 原生后端，不依赖 Spring
  RedisBackend、RedisBackendOptions、RedisChannelMode、RedisScripts、RedisRemoteStore、
  PubSubInvalidationChannel、ShardedInvalidationChannel
  migration：CoCache5Migrator、CoCache5Migration、CoCache5Format（5.x 数据的离线迁移，§8.2）
cocache-codec-jackson          Jackson 3（tools.jackson）Codec：JacksonCodec、jacksonCodec<V>()
cocache-coroutines             可选的 Kotlin 协程挂起视图（6.4，RFC 0004 §7）；kotlinx-coroutines-core 只出现在这个模块
  SuspendingCache、SuspendingSetCache、SuspendingCacheLoader、suspending()、suspendingLoader()、asAsyncCacheLoader()
cocache-spring                 Spring 适配（不依赖 Boot）：CoCacheSpringCacheManager、CoCacheSpringCache、TransactionalInvalidator
cocache-spring-boot-starter    自动配置、@EnableCoCache（caches、joinCaches）、注解翻译、spec bean 注册、配置分层、actuator 端点与健康检查、Micrometer、Spring Cache sync 检查
cocache-test                   已发布的 TCK：BackendProtocolSpec、EngineSpec、SetEngineSpec、MultiNodeSpec、FaultSpec、VersionedSpec 及其 Async* 版本、CacheAccess、HookedBackend
cocache-bom / cocache-dependencies / cocache-example
```

依赖方向严格单向：`api ← core ← redis`，`api ← codec-jackson`，`core ← spring`，`core ← coroutines`，`core ← test`；starter 依赖 `spring`、`redis`、`codec-jackson`。core 不依赖 `spring-expression` 与 `kotlin-reflect`：SpEL 与注解解析只在 starter 中出现。新后端只需依赖 `cocache-api`（实现 SPI）与 `cocache-test`（通过 TCK）。

### 职责划分

- **存储（`RemoteStore`）只执行协议**：处理字节、信封与令牌，不知道值的类型，不持有任何策略。TTL、刷新窗口、令牌都由引擎作为参数传入，存储只看到相对时长（`Duration?`，`null` 为永不过期）。
- **策略（`CachePolicy`）**：寿命、抖动、负缓存、本地限龄、降级限龄、提前刷新、lease、回源并发、L2 容量、失败模式。由引擎持有。
- **引擎（`CoherentEngine` / `SetEngine`）**：读穿透、合并回源、两层栅栏、写入顺序、失败处理、提前刷新。
- **运行时（`CacheRuntime`）**：每个进程一个，持有 `NodeId`、时钟、后端、刷新执行器、失效重试调度器，按 spec 新建引擎并订阅失效。
- **L2 固定为 Caffeine**，不是 SPI。
- **JoinCache 只是组合视图**：`get` / `getAll` 先读主缓存，再按去重后的关联 key 批量读关联缓存（各自走完整的读路径与栅栏），自己不存任何数据、不写入。`invalidate(key)` 只失效主缓存，`invalidate(firstKey, joinKey)` 同时失效两者。两个缓存各自的 TTL 与失效互不影响，视图不提供跨缓存的原子快照。
- **默认 `KeyEncoder` 是 `toString()`**，每种 key 类型首次编码时检查是否重写了 `toString()`（没有重写时每个实例都映射到不同存储 key），不满足即拒绝；`String` key 只多一次引用比较。
- **`ExternalInvalidator`**：缓存之外的写入方按同一协议失效（删除 L1 key 并广播帧，原子广播的后端在同一脚本内完成），不需要创建缓存。

## 3. 数据模型与线格式

### 3.1 用户 API

- `Cache<K, V>`：`get`、`get(key, loader)`、`getAll`、`lookup`、`put(key, value)`、`put(key, value, ttl)`、`putAll(entries)`、`putAll(entries, ttl)`、`invalidate`、`invalidateAll`。`putAll` 是 default 方法（逐个调用 `put`），第三方实现无需改动；内置引擎重写为一次批量写入（§6）。
- `Lookup<V>` = `Present(value, expiresAt)` | `Absent(expiresAt)`，`expiresAt` 是返回的这份副本的到期时间（命中 L2 时是 L2 副本的时间）。
- 没有三态 `CacheValue?`，没有绝对时间 `ttlAt` 入参，不能显式写入负缓存：负缓存只在 loader 返回 `null`（或 `loadAll` 结果缺少该 key）时由引擎写入。
- API 与 SPI 的时间类型一律是 `java.time.Duration` / `Instant`；API 与 SPI 中不使用 `value class` 与 `kotlin.time.Duration`（JVM 名称改写会让 Java 调用方和第三方实现无法使用）。
- `CacheLoader<K, V>` 是 `fun interface`：`load(key): V?`，`loadAll` 默认逐个调用 `load`。数据源只负责加载，寿命由策略与 `CacheSpec.ttlOf` 决定。
- **异步视图**（§5.6）：`Cache.async(): AsyncCache<K, V>`、`SetCache.async(): AsyncSetCache<K, E>`，方法名与同步 API 相同，返回 `CompletableFuture`。`async()` 是 default 方法（默认抛出 `UnsupportedOperationException`），内置引擎与接口式声明的代理都支持，每次返回同一个对象，`async().sync()` 是引擎本身。
- **条件读写**（§6.4，6.7；挂起视图的同名方法同样有默认实现）：`getIfChanged(key, known: Digest?): ConditionalGet<V>`（`Unchanged` | `Present(value, digest)` | `Absent`）与 `putIfMatch(key, expected: Digest?, value[, ttl]): ConditionalPut`（`Written(digest)` | `Conflict(current: Digest?)`），异步视图与挂起视图有同名方法。都是 default 方法（默认抛出 `UnsupportedOperationException`，异步视图以它完成），内置的 `Cache` 引擎与接口式声明的代理支持，`SetCache` 不支持。
- **按版本有序的写入**（§6.5，6.8）：`putIfNewer(key, value, version[, ttl])`、`invalidateIfNewer(key, version[, ttl])`（`VersionedPut` = `Written` | `Stale(current)`）与 `getVersioned(key): Versioned<V>?`，异步视图与挂起视图有同名方法。都是 default 方法（默认抛出 `UnsupportedOperationException`），只有声明为带版本的缓存（`CacheSpec.versioned`，或设置了 `versionedLoader` / `versionedAsyncLoader`）支持。
- `AsyncCacheLoader<K, V>`：`loadAsync(key): CompletionStage<out V?>`，`loadAllAsync` 默认逐个调用并合并。`CacheSpec` 的 `loader` 与 `asyncLoader` 至多设置一个，引擎在两个方向上适配（§5.6）。

### 3.2 存储 key 与频道

- 存储 key：`{keyPrefix}{namespace}:{keyEncoder(key)}`。`keyPrefix` 默认 `cocache:`，`namespace` 默认等于缓存名。同一运行时内缓存名与 namespace 都不得重复。
- 失效频道：`cocache:inv:{namespace}`（不含 `keyPrefix`）。
- 失效帧（客户端构造，Lua 只负责发布）：`[版本:1B=0x01][nodeId:16B][count:varint]([len:varint][key UTF-8])*`，见 `InvalidationFrame`。解码不信任长度前缀；畸形帧告警后丢弃。

### 3.3 L1 线格式（Redis，格式版本 1）

**标量、负缓存、lease 占位：String = 36 字节定长头 + 载荷**

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0 | 1 | magic `0xC0` |
| 1 | 1 | 格式版本（`1`） |
| 2 | 1 | 状态：`V` 值、`M` 负缓存、`L` lease 占位 |
| 3 | 1 | 保留（`0`） |
| 4 | 24 | 令牌：`L` 状态下是 lease 令牌，`V` 状态下是刷新令牌，全零表示无 |
| 28 | 8 | 令牌签发时间：服务端 `TIME` 的纪元毫秒，大端 IEEE 754 double（Lua `struct.pack('>d')`） |
| 36 | n | 载荷：`Codec<V>` 的输出（`M`、`L` 无载荷） |

**带版本的值与负缓存（6.8，§6.5）**：同一个 36 字节头，格式版本仍是 `1`，状态仍是 `V` / `M`，载荷不变；只是令牌位置与签发时间换了内容：

| 偏移 | 长度 | 普通内容 | 带版本的内容 |
|------|------|----------|--------------|
| 4 | 16 | 令牌的 `NodeId` 部分（无令牌时全零） | 标记 `0xC0` + ASCII `cocache:version`（`RedisScripts.VERSION_MARK`） |
| 20 | 8 | 令牌的序号部分 | 版本，大端有符号 64 位 |
| 28 | 8 | 令牌签发时间 | `+inf`（大端 IEEE 754 double） |

带版本的内容**不持有令牌**：真实令牌以随机的 `NodeId` 开头，不会等于标记，所以任何节点（含 6.x）的 `fill` / `release` 都不会匹配它；签发时间为 `+inf`，6.x 与本版本的读脚本按“`now - 签发时间 > tokenTtl`”判断时永远不会抢占它，因此不授予刷新令牌（`READ` 脚本没有改动，与 6.7.0 的 SHA 相同）。6.x 的客户端解析不看第 3–35 字节，读到的是同一个载荷。线格式的变化只有这一处：令牌位置多了一种内容，key 布局、状态、格式版本、集合字段、失效帧、频道与摘要都不变。

**集合（`SetCache`）：Hash**。字段 `!f` 为格式版本（`"1"`），成员字段为 `+` + 成员字节，值为空串。Hash 只表示“有成员的集合”；lease 与负缓存总是 String 形状。任何整体写入都先 `DEL` 再按新形状写入。

- **令牌**（`Token`，24 B）= `NodeId`（16 B，每个运行时随机生成）+ 进程内单调序号（8 B）。由客户端构造，全局唯一、永不重复签发，所以被销毁的令牌不可能再次匹配（无 ABA）。
- **到期时间**：一律 `PEXPIRE` / `PTTL`，毫秒，以相对时长传输，不受节点间时钟偏差影响。参数钳到至少 1 ms；`0` 表示永不过期。**每次状态转换都显式重设到期时间**，lease 的寿命和负缓存的寿命不得泄漏到新值上。
- **读取成本**：标量 `PTTL` + `GET`（一次 EVALSHA）；集合 `PTTL` + `HKEYS` + `HGET !f`（只返回字段名，不返回成员的空值；与 `HGETALL` 的字段顺序相同，摘要不受影响）。读脚本与解析其回复的客户端随同一版本发布，混合版本部署按 SHA 各自加载脚本，可以逐台升级。
- **无法识别的内容**（不以 magic 开头的 String、未知状态、没有 `!f` 的 Hash、其它 Redis 类型如原生 Set；其它程序或其它大版本写入的内容都属此类）以格式版本 `0` 返回，引擎按 `foreignFormat` 处理（§5.4）。String 与 Hash 以外的类型以空载荷返回。
- **摘要**（`Digest`，供条件回收与条件读写）：标量取载荷（无法识别的 String 取整个值，String 与 Hash 以外的类型取空串），负缓存取空串，集合按 L1 返回顺序拼接 `int32 长度 + 成员`，SHA-1。普通读取只在解码失败时计算；条件读写只比较本协议的标量值（`V` 状态的载荷，不含头部，因此与令牌、到期时间无关），由脚本在 Redis 中计算（`redis.sha1hex`），客户端对写入的载荷计算同一个值。负缓存与载荷为空的标量摘要相同，条件读写因此把负缓存当作“没有值”，不按摘要比较。

线格式（key 布局、频道命名、标量头、集合字段、帧、摘要算法）在一个大版本内冻结。格式演进分两阶段：先发布能读 N+1、仍写 N 的版本，全集群升级后再发布写 N+1 的版本。不同大版本的节点不能共享同一个 Redis。

## 4. 栅栏

**规则**：任何填充都必须持有读取之前取得的令牌；失效必须在修改上游之前**和之后**各销毁一次令牌。

| 层 | 令牌 | 填充方 | 失效方 |
|----|------|--------|--------|
| L1 | lease（未命中时由读脚本原子取得）或刷新令牌 | Lua 比较令牌后写入（`fill`） | `DEL` / 整体覆盖 / 元素写入，销毁令牌 |
| L2 | 进程内失效戳（`InvalidationStamps`，4096 段 `AtomicLongArray`，key 哈希分段） | 取戳 → 读 L1 → 写前校验 → 写 L2 → 写后复核，失败则撤销 | 戳 → 淘汰 L2 → 修改 L1 → 戳 → 淘汰 L2 |

### 4.1 L1 状态机

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
  addMembers / removeMembers：见 §6.3
  putIfMatch：当前内容符合期望时同 put（只写标量），否则不修改；readIfChanged：不修改任何状态（§6.4）
  putIfNewer(v)：v 大于当前版本时同 put（VALUE / MISSING 带版本 v，整体覆盖、销毁令牌、重设 TTL）+ PUBLISH，否则不修改（§6.5）
  带版本的 VALUE / MISSING 不持有令牌：不授予刷新令牌，fill / release / reclaim 都不匹配；只有整体写入（put、putIfNewer 等）、DEL 与到期能改变它
```

每个转换都由一个 Lua 脚本原子完成（`RedisScripts`：`READ`、`FILL`、`RELEASE`、`PUT`、`PUT_ALL`、`INVALIDATE`、`RECLAIM`、`ADD_MEMBERS`、`REMOVE_MEMBERS`、`ADD_MEMBERS_IF_READABLE`、`REMOVE_MEMBERS_IF_READABLE`、`READ_IF_CHANGED`、`PUT_IF_MATCH`、`PUT_IF_NEWER`）。脚本**不带 shebang 标志**：带标志的脚本在 OOM 时被拒绝，而失效恰恰释放内存。写脚本含 `PUBLISH`，不能用 `EVAL_RO`。

- **lease**：读到不存在的 key 且请求了 lease 时，写入 `L` 占位，`PX = lease.ttl`，返回 `Miss(leaseGranted = true)`。读到 `L` 返回 `Leased(remaining)`。节点崩溃留下的 lease 由 `lease.ttl` 回收。
- **刷新令牌**：读到 `V`，且剩余寿命 `0 < pttl ≤ refreshWindow`，且头部令牌为零或签发超过 `lease.ttl` 时，用 `SETRANGE` 写入调用方令牌与服务端时间（保留原 TTL），返回 `refreshGranted = true`。签发过期的令牌可被抢占，被抢占者的 `fill` 因令牌不匹配失败。
- **fill**：仅当 key 是本协议的 String 且头部令牌等于 `Fill.token`（lease 或刷新令牌）时整体写入并重设 TTL。
- **release**：令牌匹配时，`L` 状态删除 key，`V` 状态只清零令牌。
- **reclaim**：仅当当前内容的摘要等于读到的那份、且不是 `L` 状态、也不是带版本的内容时，原子地转为 lease（带版本的内容转为 lease 就丢了版本，§6.5）。

### 4.2 写入方顺序（`AbstractEngine.write`）

| 操作 | 顺序 |
|------|------|
| `invalidate` / `invalidateAll` | 戳 → 淘汰 L2 → Lua `DEL` + `PUBLISH` → 戳（无论成败）→ 淘汰 L2 |
| `put` | 戳 → 淘汰 L2 → Lua 覆盖 + `PUBLISH` → 戳（无论成败）→ 淘汰 L2。**不写 L2** |
| `putAll` | 同 `put`，一次 `write` 覆盖整批 key：全部 key 戳 → 淘汰 L2 → 批量覆盖 + `PUBLISH` → 全部 key 戳（无论成败）→ 淘汰 L2 |
| `SetCache.add` / `remove` / `clear` / `put` / `putAll` | 同上 |
| `putIfMatch` | 同 `put`。冲突与拒绝（读不懂的内容）不修改 L1：脚本不 `PUBLISH`，非原子广播时引擎也不发布；两次戳与 L2 淘汰照常执行（只多淘汰一次本节点的副本） |
| `putIfNewer` / `invalidateIfNewer` | 同 `putIfMatch`：版本不更新与拒绝（读不懂的内容）不修改 L1，脚本不 `PUBLISH`，非原子广播时引擎也不发布；两次戳与 L2 淘汰照常执行 |
| 收到远端事件 | 戳 → 淘汰 L2（`origin == 本节点 NodeId` 的事件被过滤） |
| `onReset` / `clearLocal` | 全部戳递增 → 清空 L2 |

后端 `atomicBroadcast = false` 时，引擎在存储操作成功后调用 `channel.publish`；发布失败按写入失败处理（§7）。存储操作失败时引擎**也尽力发布同一帧**再报错：失败的写入可能已部分生效（`putAll` 跨多个 slot 组时有的组已写入，或已在 L1 生效却报错），不发布的话其它节点会一直读旧的 L2 直到 `localMaxAge`（G2）；这次发布的失败只告警（附为写入异常的 suppressed），调用方看到的仍是写入的原始异常，多余的广播只淘汰副本。因此存储的写入方法返回或抛出时，它发出的全部 L1 命令都必须已经结束（`RedisRemoteStore` 的同步 `putAll` / `invalidate` 等全部组结束后才抛出第一个异常，异步版本同样等全部组），否则广播可能先于仍在执行的命令。例外是命令超时：客户端放弃等待时已发出的命令可能仍在 Redis 端执行，补发的广播可能先于它生效，这时其它节点的旧副本由 `localMaxAge` 兜底（G1 的上界不变）。同步路径逐组等待、每组各受 `commandTimeout` 限制，失败时最坏等待为“组数 × `commandTimeout`”。不在失败路径上继续等超时的命令真正结束（6.3.1 评估后的结论）：超时后客户端取消了命令，没有应答就无从得知它是否、何时执行（Redis 无法中止已开始的脚本），有上界的追加等待只能缩小、不能关闭这个窗口，却会让失败路径的耗时翻倍、在 Redis 变慢时占住更多调用线程；而窗口内的陈旧本来就受 `localMaxAge` 限制。

### 4.3 为什么这样做

- **第二次递增**：只在修改 L1 之前递增一次时，一个在递增之后取戳、在修改之前读到旧值的读者，写 L2 时能通过两次校验，而本节点自己的事件又被过滤，L2 留下旧值直到 `localMaxAge`。修改 L1 之后再递增一次，这个读者要么复核失败自行撤销，要么在第二次递增之前已经写完，被随后的“淘汰 L2”删除。
- **`put` 不写 L2**：戳只能判断“写入期间有没有失效”，判断不了两次写入在 L1 中的先后。同节点两个并发 `put` 若各自写 L2，后执行 Lua 的一方可能先写 L2，L2 留下较旧的值。L2 只由“读 L1”这一个方向填充，才能保证与 L1 的顺序一致。代价是写后第一次读多一次 L1 往返；读己之写仍成立。
- **L1 的 G2**：失效（`DEL` / 覆盖）发生在取得 lease 之后，就销毁了该 lease；令牌不会重新签发，所以开始于失效之前的回源永远写不进 L1。失效发生在取得 lease 之前，回源也就晚于数据源提交，读到新值。
- **跨节点 L2**：处理远端事件 = 戳 + 淘汰，与第二次递增同构；处理之前返回旧值是合法的“事件延迟”。

分段碰撞不影响正确性，代价是多余地放弃一次填充。

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
  observed = stamps.current(k)
  r = flights.execute(k) { loadThrough }          // SingleFlight
  if r.stamp < observed: r = flights.execute(k) { loadThrough }   // 读己之写，至多重试一次

loadThrough:                                      // 在 leader（调用方）线程上执行
  stamp = stamps.current(k)                       // SingleFlight 注册之后取戳，整个调用只取这一次
  if k 的失效仍在重试中: return loadBypassingRemote
  rr = store.read(k, lease = 新令牌)
  while rr is Leased && 未超过 min(remaining, lease.wait): 退避 2→50 ms 后重读（沿用同一个 stamp）
  Hit    → 格式检查 / 解码（失败见 §5.4）→ fillLocal(k, entry, stamp)，限龄 localMaxAge
  Miss   → 回源；计算 ttl 与编码；有 lease 则 fill(k, lease, envelope, ttl)
           fill 成功：L2 限龄 localMaxAge；没有 lease 或 fill 被拒绝：L2 限龄 degradedLocalMaxAge
           回源、ttlOf、Codec 抛出时先 release lease 再抛出
  Leased → 等待超时：loadBypassingRemote
  L1 异常 → readFailure = DEGRADE：loadBypassingRemote；THROW：抛出 CacheReadException

loadBypassingRemote:                              // 回源，不写 L1；L2 限龄 degradedLocalMaxAge，仍受戳保护
```

- **L2 副本的到期时间** = `min(now + L1 剩余寿命, now + maxAge)`；`maxAge` 是 `localMaxAge` 或 `degradedLocalMaxAge`。`refreshAt` = L1 到期时间 − `refreshWindow`（只对有限寿命的值；L1 剩余寿命不长于窗口时见 §5.5）。时钟是 `TickingClock`（100 ms 刷新一次的 volatile 值），命中判断是一次 volatile 读加一次比较。
- **寿命**：值的寿命 = `ttlOf(key, value)`（非 null 时优先，不加抖动）否则 `ttl ± 抖动`（实际幅度 ≤ `ttl / 2`，至少 1 ms）；`ttl = null` 为永不过期。负缓存总是 `absentTtl`（不抖动）。所有寿命钳到 `[1 ms, 100 年]`。
- **线程模型**：同步 API 上 loader 总是在调用方（SingleFlight leader）线程上同步执行，事务、SecurityContext、MDC 等 ThreadLocal 可用。Lettuce 的 Future 只包装 L1 I/O，引擎在调用方线程上等待，不在事件循环线程上执行任何续接。异步视图的线程模型见 §5.6。
- **L1 未命中就是未命中**：key 不存在、处于 `L` 状态都不是负缓存；负缓存只有状态 `M`。

### 5.1 没有数据源的缓存

spec 没有 `loader` 时，未命中释放 lease，不向 L1 写负缓存（否则会挡住其它节点 `put` 之后的读取），只在 L2 缓存一个“未决的不存在”（`absentTtl`，受 `localMaxAge` 限龄）。这类缓存的 L1 是唯一副本：读到读不懂的内容时默认抛出而不是回收（§5.4）。`get(key, loader)` 不接受未决条目，会用调用方的 loader 回源；合并到不带数据源的在途加载时同样自己回源。

### 5.2 `get(key, loader)`

与 `get` 走同一条受 lease 与失效戳保护的路径，只是用调用方的 loader 代替 spec 中的数据源。供 Spring `@Cacheable(sync = true)` 使用。

### 5.3 批量读 `getAll`

- 逐个 key 查 L2（进入刷新窗口时同样触发后台刷新）、过 `KeyFilter`；剩余 key 按存储 key 去重后交给 `flights.executeAll`。
- 已在途的 key 等待现有调用；其余 key 由本线程作为 leader，**先完成自己负责的整批，再等待别人的**。
- leader 的一批：每个 key 注册之后取戳 → 一次 `readAll`（为每个 key 申请 lease；失效重试中的 key 不读 L1）→ 一次 `loader.loadAll` → 一次 `fillAll`。
- 批量路径**不等待**其它节点的 lease：`Leased` 与更高格式版本的 key 直接回源且不写 L1（L2 降级限龄）；没有数据源时更高格式版本的 key 按 `foreignFormat` 处理（§5.4）。格式 0 或解码失败的 key 与单 key 路径一样条件回收（见 §5.4），再凭新 lease 回源与 `fill`。
- 共享到的结果若开始于本次读取已观察到的失效之前，该 key 改走单 key 路径。
- Redis：每个 key 一条 EVALSHA，在共享连接上并发发出（约一次往返）；Cluster 按 key 路由，不会 CROSSSLOT。

### 5.4 损坏与格式演进

- **格式版本高于本节点**（滚动升级期间）：计入 `unknownFormats`，**不回收、不写 L1**，否则新旧节点互相覆盖。有数据源时绕过 L1 回源，L2 降级限龄。
  没有数据源时绕过 L1 只能得到“不存在”，于是同样按 `foreignFormat` 处理（未配置为 `FAIL`）：`FAIL` 抛出 `CacheReadException`（`cause` 为 `ForeignFormatException`，`newer = true`），不写 L2，批量读取与下面的格式 0 相同；`RECLAIM` 按不存在处理（6.5.0 及以前的行为）。理由同下：L1 是唯一副本时，混合版本滚动升级期间读成不存在就是悄悄丢数据。
- **格式 0 或同版本解码失败**（读不懂的内容）按策略 `foreignFormat`（`ForeignFormatMode`）处理。未配置时按**这次读取有没有数据源**决定：有数据源（spec 的 loader，或 `get(key, loader)` 的 loader）时 `RECLAIM`，没有时 `FAIL`。
  - `RECLAIM`：计入 `reclaims`，计算本次读到内容的摘要，调用 `reclaim`；成功则凭新 lease 正常回源与 `fill`，失败（内容已变或 L1 异常）则不写 L1 回源。每次读取至多回收一次。
  - `FAIL`：计入 `unknownFormats`，抛出 `CacheReadException`（`cause` 为 `ForeignFormatException`，带存储 key 与格式版本），不回收、不写 L2，与 `readFailure` 无关（降级模式下同样抛出）。批量读取先完成其余 key（释放 lease 或凭 lease 写回），再以第一个这样的 key 失败。理由：没有数据源时回收就是删除唯一副本，调用方会把“不存在”当作真实结果（6.4 及以前即如此：5.x 遗留的数据在第一次读取时被删除）。漏跑迁移（§8.2）的结果因此是读取失败（fail closed）。
  - 元素写入受这个策略约束（§6.3）：`SetCache` 没有数据源，`FAIL`（默认）时 `add` / `remove` 落在读不懂的内容上以 `CacheWriteException`（`cause` 为 `ForeignFormatException`）失败，原子地不修改 key、不广播，计入 `unknownFormats` 与 `writeFailures`；`RECLAIM` 时 `add` 整体覆盖它、`remove` 不修改它（6.5.0 及以前的行为）。否则漏跑迁移时一次 `add` 就会用一个成员替换掉 5.x 的全部成员，且之后 key 已是本格式，迁移工具会跳过它。
  - 整体覆盖写入（`put`、`putAll`，含 `SetCache.put`）**不受**这个策略约束，有意如此：调用方给出的是 key 的全部内容，覆盖不依赖旧内容。代价是：被覆盖的 5.x 内容不会再被迁移工具备份（key 已是本格式，迁移工具跳过它）；需要保留旧内容时先迁移再写。
- 读路径**从不无条件删除**。
- **`SetCache`**（L1 是唯一副本，没有数据源可回源）：格式高于本节点时计入 `unknownFormats` 并抛出（`IllegalStateException`，与 `foreignFormat` 无关），不写 L2；不是本协议写入的内容（格式 0，如原生 Set）计入 `unknownFormats`，默认（`FAIL`）抛出 `CacheReadException` 且不写 L2；`RECLAIM` 时按不存在处理（读到空集合）并以降级限龄进入 L2，不回收、不删除，下一次 `put` / `add` 整体覆盖；同版本内容的元素解码失败时读取直接抛出，不计为命中。

### 5.5 提前刷新

- `refreshWindow` 非 null、spec 有 loader、L2 副本到达 `refreshAt` 时，命中照常返回当前值，并把一次刷新提交到刷新执行器。每个节点每个 key 同一时刻至多一次（`refreshing` 集合），刷新经 SingleFlight 与并发未命中合并。取得 `refreshing` 之后再确认 L2 仍是触发时读到的那一份副本：命中路径“读副本”与“取守卫”之间上一次刷新可能已完成（它先替换副本、再释放守卫），这时的触发来自旧的观察，直接放弃，否则同一节点会对同一 key 多刷新一次。
- 刷新先 `read(k, refresh = RefreshRequest(window, 新令牌, lease.ttl))`：取得令牌的节点回源并 `fill(r)`；没取得的节点只从 L1 重新填充 L2；L1 已无值时退回普通 `loadThrough`。
- **没取得令牌时退避**（`CoherentEngine.refill`，同步与异步共用）：重新填充时 L1 若仍在窗口内，说明令牌在别的节点手里、它正在回源。按 L1 剩余寿命算出的刷新点这时不晚于现在，不退避的话本节点的每次命中都会再读一次 L1；持有者崩溃时这种轮询持续到令牌签发超过 `lease.ttl`，放大倍数是热点 key 数 × 节点数。因此这份副本的刷新点设为 `now + min(b, 剩余寿命 / 2)`，`b = max(lease.wait, 100 ms)`：`lease.wait` 是未命中等待别的节点回源的时长，刷新在同一时间尺度上再看一次；下限 100 ms 是默认时钟（`TickingClock`）的刷新间隔，`lease.wait` 可以为 0。
  - 刷新点总是早于 L1 到期（不足 1 ms 时不设）：持有者写回之后，非持有节点在旧值到期之前取到新值，不因刷新多出一次未命中。`fill` 不广播（广播会淘汰所有节点的副本，下一次命中变成未命中，抵消提前刷新），所以非持有节点只靠这次再读取到新值。再读时若令牌签发已超过 `lease.ttl`（持有者崩溃），它接管令牌回源。
  - 每个节点每个 key 在一个刷新周期内的 L1 读取次数与命中频率无关：约为“持有者回源耗时 / b”，加上临近到期时按剩余寿命减半的几次（至多约 log2(2b / 1 ms) 次；默认时钟每 100 ms 才前进一次，实际只有两三次）。
- **寿命不长于窗口的值不参与提前刷新**：除上面的重新填充之外（回源写回、读到 L1、降级填充），L1 剩余寿命不长于窗口时不设刷新点（`refreshAt = FOREVER`）。否则副本一出生就在窗口内：持有令牌的节点每次 `fill` 之后令牌被清零，下一次命中又取得新令牌、再回源一次，被放大的是数据源。策略 `ttl` 给出的寿命由 §9.4 的校验保证长于窗口；`ttlOf` 或 `put(k, v, ttl)` 给出的更短寿命不报错，这样的值不刷新，照常到期、下一次未命中时重新加载。代价：在一个值的最后一个窗口内才读到 L1 的节点（第一次读或 L2 刚被淘汰）也不发起刷新。其它节点的副本照常触发，全集群仍然只刷新一次；只有所有节点的副本都是这种副本时，key 才按普通未命中重新加载。
- 结果：全集群每个 key 一次刷新。`invalidate`、`put`、元素写入都会销毁刷新令牌。
- 执行器拒绝时计入 `refreshRejected` 并跳过；刷新失败只告警，值照常到期，G1 不变。默认执行器是有界守护线程池（≤ 4 线程，队列 1024，满时拒绝）。

### 5.6 异步视图（RFC 0004）

异步视图与同步 API 是同一个引擎：共享 L2、失效戳、SingleFlight、lease、回源许可与指标；每个协议分支在两条路径上一一对应，协议决策（读到什么就做什么：`hitOutcome`、`completeLoad`、`batchStep`、`refreshStep`、`putsOf` 等）由两条路径共用的函数给出，两条路径只在“如何等待”上不同。同步命中路径的代码不变。

- **命中**：与同步路径相同的 L2 查找与过期比较，返回已完成的 future（在调用方线程上）。
- **续接**（`Futures.handleNext` / `thenNext`）：上一步已完成时就地执行（结果已同步可得，没有 I/O 线程参与，例如内存后端）；上一步在**同一个异步执行器派发的续接中**完成时也就地执行（`Futures.continueOn`：派发的任务执行期间以线程局部变量标记该执行器，嵌套超过 16 层时改为派发；对在调用方线程上执行任务的执行器，如就地执行的执行器或 `CallerRunsPolicy`，改为派发也只是在同一个栈上继续，这个上限限制不住栈深，与 6.4.0 相同）；否则只在完成线程上把续接提交给**异步执行器**，续接本身从不在 L1 客户端的 I/O 线程上执行。本线程对某些 key 有重入守卫的作用域内清除标记（`Futures.withoutInline`）：调用数据源期间（`SingleFlight.loading`）与同步 leader 执行期间（`SingleFlight.execute` / `executeAll` 的 block，只在未命中路径上）。其间完成的其它 key 的续接（数据源同步完成的 future、同步 leader 归还回源许可时唤醒的异步等待者）派发出去，不在本 key 的作用域内执行，否则那些回调里再读本 key 会被误判为同一线程的重入（6.4.0 也是派发出去、正常合并）。标记只在执行派发的任务期间存在于线程上（不带初始值的 `ThreadLocal`，结束时移除），只读取的线程（I/O 线程、`commonPool` 等）上不留下对象。I/O 线程、定时器线程、阻塞执行器与数据源自己的线程上没有标记，在那里完成的结果总是派发。异步链在 L1 结果到达之前就装配好，没有第二条规则时每一级都会再派发一次（L1 命中 4 次、未命中 7 次），每一次都可能唤醒 `ForkJoinPool` 的一个空闲线程；现在每次 L1 命中只派发 1 次，未命中至多 3 次（L1 读、数据源、fill 之后各一次，见 [调查报告](investigations/async-8t-gap.md)）。因此返回的 future 在调用方线程或异步执行器上完成（调用方注册的依赖阶段按 JDK 规则也可能在随后调用 `get()` / `join()` 的线程上执行，TCK 断言完成线程时先等依赖阶段执行完再等待结果）；`AsyncCacheLoader.loadAsync` 同样在调用方线程或异步执行器上被调用。
- **数据源适配**：`asyncLoader` 在当前线程上调用，取其结果的副本再加 `LoadPolicy.asyncTimeout`（默认 30 s，由 JDK 的延迟线程以 `completeOnTimeout` 的私有哨兵值计时，不经 `cocache-timer`；数据源自己以 `TimeoutException` 失败时原样传递），超时以 `SourceLoadTimeoutException` 失败、计入 `loadTimeouts` 并释放 lease 与回源许可，数据源的 future 不被取消、迟到的结果被丢弃；同步 `loader` 在异步路径上**只在阻塞执行器上执行**，从不在异步执行器上执行，不加超时。同步 API 遇到 `asyncLoader` 时在调用方线程上调用 `loadAsync` 并等待（同样受 `asyncTimeout` 约束，异常解包）。
- **不挂起线程**：等待其它节点的 lease 经定时器调度重读（退避、截止时间、令牌与戳都与同步路径相同）；等不到回源许可时登记为等待者，许可释放时按登记顺序被唤醒，`acquireTimeout` 后被拒绝。等待期间不占任何线程。按登记顺序只在异步等待者之间成立：同步调用方的限时获取直接争用同一个信号量，不排在已登记的异步等待者之后（许可释放的瞬间可能先被同步调用方拿走）；同步与异步混用且许可长期饱和时，异步调用方被拒绝的比例可能更高，但不会泄漏许可或越过 `acquireTimeout`。
- **SingleFlight**：同步与异步调用共用一张表。异步 leader 在注册之后、在调用方线程上开始（第一件事是取戳），其 future 完成时先注销、再发布；读己之写至多重试一次，与同步路径相同。同步调用合并到异步调用时只阻塞自己的线程；异步调用合并到同步调用时，结果经异步执行器送达（否则用户的续接会跑在同步 leader 的线程上）。每个调用方拿到自己的 future：取消只影响自己，不取消共享加载、不取消数据源的 future。
- **重入**：除同步调用的拥有线程外，引擎在调用数据源期间（同步数据源的整个执行、异步数据源 `loadAsync` 的同步部分）为这些 key 设置线程守卫，期间本线程对同一 key 的同步或异步调用快速失败。异步数据源在一次 I/O 之后再读同一 key 形成的环无法检测，由 `asyncTimeout` 打破。
- **写入**（`AbstractEngine.writeAsync`）：顺序同 §4.2；编码在第一次递增之前、在调用方线程上完成；第二次递增与淘汰挂在 L1 的 future 上（无论成败），在返回的 future 完成之前执行，取消返回的 future 不会跳过它。非原子广播的后端经 `AsyncInvalidationChannel.publishAsync` 发布。
- **提前刷新**：同步数据源照旧在刷新执行器上执行；异步数据源的刷新在刷新执行器上发起、以异步路径推进，不在刷新执行器的线程上等待。
- **执行器拒绝任务**：续接被拒绝时该步不执行，返回的 future 以 `RejectedExecutionException` 失败；必须执行的收尾不依赖执行器：写入方的第二次递增与淘汰（只涉及戳与 Caffeine）在 L1 future 的完成线程上就地执行；非原子广播的派发被拒绝时同样在完成线程上就地广播（`publishAsync` 不阻塞），写入的结果不被替换：成功的写入不计为失败、`invalidate` 不登记重试，失败的写入保留原始异常（6.3.1 之前拒绝会跳过广播，并把写入的结果换成 `RejectedExecutionException`）。返回 future 的完成本身被拒绝时，调用方照常看到 `RejectedExecutionException`；回源许可在加载链结束时恰好归还一次，取得的 lease / 刷新令牌在链因拒绝失败时尽力释放（令牌不匹配时 release 什么也不做，重复释放无害）。
- **`KeyFilter`** 在调用方线程上调用，必须是本地、不阻塞的判断。
- **异常**：返回的 future 以原始异常完成（不包装为 `CompletionException`）；异步方法从不同步抛出（包括 `KeyEncoder`、`Codec` 抛出、重入与 `SetCache` 的不支持操作）。
- **只实现同步 SPI 的后端**：引擎经 `BlockingAsyncStore` / `BlockingAsyncChannel` 把同步调用放到阻塞执行器上；原生异步的后端实现 `AsyncRemoteStore`（与 `AsyncInvalidationChannel`），内存后端是原生异步的（就地执行、返回已完成的 future）。

### 5.7 挂起视图（`cocache-coroutines`，RFC 0004 §7）

挂起视图是异步视图的薄封装：每个方法调用异步视图的同名方法并等待其 future，不复制任何引擎逻辑，§5.6 的语义全部沿用。已成功完成的 future（L2 命中）直接取结果，不挂起；等待经 `kotlinx.coroutines.future.await`，失败时抛出 future 中的原始异常。读方法（`get`、`getAll`、`lookup`）唯一的挂起点在尾部，编译器不为它们生成状态机；重入检查先查上下文中有没有 `LoadingKeys`，没有就返回。因此 L2 命中除异步视图本身外不再分配（调用级 loader 的上下文与作用域在 `loadAsync` 内才构造）。

- **取消**：取消等待的协程会取消这次调用返回的那个 future；异步视图为每次调用新建 future，共享的加载不受影响（与 §5.6 相同）。
- **调用级 loader**（`get(key) { … }`）：以调用方的协程上下文去掉 `Job` 启动（`future {}`）。它在调用方的 dispatcher 上执行；没有 dispatcher 或 dispatcher 是 `runBlocking` 的事件循环时改用 `Dispatchers.Default`（加载可能比事件循环活得更久）。`ThreadContextElement` 随之传递。它脱离结构化并发：调用方被取消、其作用域结束或运行时关闭都不取消加载。
- **spec 级 loader**（`suspendingLoader(context) { … }` / `asAsyncCacheLoader(context)`）：在给定的 `context` 中启动（默认 `Dispatchers.Default`），作为 spec 的 `asyncLoader`，受 `LoadPolicy.asyncTimeout` 约束。超时不取消协程（与 `AsyncCacheLoader` 相同）。每个 loader 一个 `SupervisorJob`（父 Job 为 `context` 中的 Job）：一次失败不取消其它加载，取消父 Job 取消之后的加载。
- **以取消结束的加载**转为 `SourceLoadCancelledException`（`RuntimeException`，与 `SourceLoadTimeoutException` 一致）：在加载协程内，数据源自己抛出的 `CancellationException`（如数据源内部的 `withTimeout`）；在 loader future 的边界上，加载协程被取消或在已取消的作用域中启动（spec 的 `context` 中的 Job 已被取消）。加载可能被共享，原样传出会让挂起的调用方当作自己被取消而吞掉；转换后同步、异步与挂起的调用方都收到它。`SupervisorJob` 永不完成，因此 `context` 中的 Job 应是长生命周期的，loader 应创建一次、反复使用。
- **同 key 重入**：引擎基于线程的检查（§5.6“重入”）只覆盖数据源的同步部分；loader 挂起后在别的线程上继续时，由随协程上下文传递的元素 `LoadingKeys` 覆盖：经挂起视图读取正在加载的 key（`get`、`getAll`、`lookup`）立即以 `IllegalStateException` 失败，而不是等待自己直到 `asyncTimeout`。按 key（`K`）的相等性匹配，不按编码后的存储 key。调用级 loader 按视图背后的缓存实例匹配；spec 级 loader 按缓存名匹配（声明时不知道运行时），不继承调用方的记录（跨缓存循环要到 `asyncTimeout` 才失败）；单独使用的 `asAsyncCacheLoader` 没有这项检查。
- 不阻塞任何线程；loader 不在 Lettuce I/O 线程上执行（引擎在调用 `loadAsync` 之前已切换线程，§5.6）。

## 6. 写路径

| 操作 | L1 | 往返 |
|------|----|------|
| `put` | Lua：`DEL` + 按形状写入 + `PEXPIRE` + `PUBLISH` | 1 |
| `putAll(N)` | `RemoteStore.putAll`：分组方式同 `invalidateAll`，每组一个 `PUT_ALL` 脚本（每个 key 的转换与 `PUT` 相同）与一次 `PUBLISH`（只含本组的 key），并发执行；原子性只在单个组内成立 | 单机 ⌈N/512⌉，Cluster 约 N |
| `invalidate` | Lua：`DEL` + `PUBLISH` | 1 |
| `invalidateAll(N)` | 单机：按 `invalidateBatchSize`（默认 512）切分，每批一个脚本。Cluster：按 slot 分组再切分，每组一个脚本与一次 `PUBLISH`，并发执行；原子性只在单个 slot 内成立 | 单机 ⌈N/512⌉，Cluster 约 N |
| `putIfNewer` / `invalidateIfNewer` | Lua：比较版本，更新时 `DEL` + `SET`（带版本的头）+ `PEXPIRE` + `PUBLISH`（§6.5） | 1 |
| `add` / `remove` | Lua：（`foreignFormat = FAIL` 时先检查内容能否当作本协议的集合）成员写入 + 重设 TTL + 销毁令牌 + `PUBLISH` | 1 |

- `put` 的寿命：`ttlOf ?: 策略寿命`；`put(key, value, ttl)` 用给定时长。都钳到 `[1 ms, 100 年]`。
- `putAll` 每个 key 的寿命按 `put` 逐个计算（各自独立抖动）；`putAll(entries, ttl)` 用同一时长。空 map 不做任何事；编码后碰撞的存储 key 以后出现的为准，每个存储 key 只编码一次。失败抛出 `CacheWriteException`（`operation = "putAll"`），部分 key 可能已生效。非原子广播时引擎在批量写入结束后发布一帧（含全部 key），写入失败时同样发布（§4.2）。`RemoteStore.putAll` 的默认实现逐个调用 `put`，帧按 key 拆分（每个 key 只广播自己；帧无法拆分时原样使用）。
- 调用方必须**先提交数据源，再 `invalidate`**（事务内用 `TransactionalInvalidator`，§9.4）。在提交之前失效本身就是 G2 漏洞：并发回源可能读到提交前的数据。
- 原子广播消除的是 `DEL` 与 `PUBLISH` 之间的客户端窗口；投递仍是至多一次，丢失由 `onReset`、心跳与 `localMaxAge` 兜底。

### 6.1 `put` 与 `invalidate` 的取舍

首选 `invalidate`。`put` 只适合写入方算出最终值的场景，且不填充本节点 L2。

### 6.2 外部写入

绕过 CoCache 修改数据源时，外部系统必须对 `{keyPrefix}{namespace}:{key}` 执行 `DEL`，并向 `cocache:inv:{namespace}` 发布按 §3.2 编码的帧（任意 16 字节 nodeId）。不发布时，其它节点的 L2 陈旧至 `localMaxAge`。

### 6.3 `SetCache`：L1 是唯一副本

数据权威在数据源中的集合，声明为普通的 `Cache<K, Set<E>>`（集合作为一个标量由 `Codec` 编码），先更新数据源再 `invalidate`。增量操作与数据源中的写入顺序无关，无法保证正确。

`SetCache<K, E>`（`SetCacheSpec`）只用于 L1 本身就是权威的场景：

| 项 | 规则 |
|----|------|
| loader | 按构造禁止（`SetCacheSpec` 没有 loader 字段）；`get(key, loader)` 抛出 `UnsupportedOperationException` |
| `add` | key 不存在、为负缓存或 lease 时先 `DEL` 再创建只含这些成员的集合；是本协议的集合时原子加入。重设 TTL（永不过期时 `PERSIST`），`PUBLISH`。其它内容（其它 Redis 类型、不是本协议写入的 String、没有 `!f` 的 Hash、本协议的标量值、`!f` 高于本版本的集合）：`foreignFormat = FAIL`（默认）时拒绝（`RemoteStore.addMembersIfReadable` 返回其格式版本，Redis 为 `ADD_MEMBERS_IF_READABLE`），key 不变、不广播，引擎抛出 `CacheWriteException`（`cause` 为 `ForeignFormatException`）；`RECLAIM` 时整体覆盖（`addMembers`） |
| `remove` | 只在 key 是本协议的集合时生效；移空则删除 key，否则重设 TTL。`PUBLISH`。其它内容的处理同 `add`：`FAIL` 时拒绝（`removeMembersIfReadable`），`RECLAIM` 时不修改 |
| 并发顺序 | 以 L1 的串行化顺序为准 |
| 读取 | 不取 lease；未命中返回空集合，L2 以戳保护缓存这个结果（`localMaxAge`）。L1 读失败直接抛出原始异常（没有可降级的数据源） |
| `put(key, set)` | 整体覆盖；空集合等价于 `clear` |
| `putAll(entries)` | 一次 `write` 覆盖整批 key：非空集合经 `RemoteStore.putAll` 整体覆盖，空集合经 `invalidate` 删除；两类都有时各自只广播自己的 key |
| `invalidate` / `invalidateAll` | 拒绝（`UnsupportedOperationException`），会销毁唯一副本；删除用 `clear(key)` |
| 提前刷新 | 不适用 |
| 默认 TTL | 永不过期（`SetCacheSpec.DEFAULT_POLICY`） |

前提（写在用户文档中）：Redis `maxmemory-policy` 不能是 `allkeys-*`；数据持久性等于 Redis 的持久性（异步复制在故障切换时可能丢失最近的写入）。启动时不检查这两点。

存储代价：与原生 Set 相比，非整数成员每个多约 16 B，整数成员失去 intset 编码。

### 6.4 条件读写（按摘要比较，6.7）

供“L1 是权威副本”的缓存（没有数据源，如 CoSec 的策略快照文档）做乐观并发控制：一个 key 保存一整份文档，多个写入方各自“读—改—写”；每个节点按 `localMaxAge` 周期性确认已加载的文档仍是最新的。两个操作都**只针对 L1**：

| 项 | `getIfChanged(key, known)` | `putIfMatch(key, expected, value[, ttl])` |
|----|----|----|
| L1 | 一个脚本 `READ_IF_CHANGED`：`V` 状态且载荷摘要等于 `known` 时只回复结果码与格式版本 `{1, 格式}`，不传输正文（引擎再确认格式在本节点能读的范围内）；否则回复 `{2, 格式, 载荷}`、`{0}`（没有值）或 `{3, 格式}`（读不懂的内容，不传输正文） | 一个脚本 `PUT_IF_MATCH`：检查当前内容，符合期望时按 `PUT` 的规则写入（`DEL` + `SET` + 到期时间，销毁令牌）并 `PUBLISH`；不符合时不修改、不广播，回复当前摘要 |
| “没有值” | key 不存在、`L`（lease 占位）、`M`（负缓存）返回 `Absent` | `expected = null` 只匹配这三种状态；`expected = d` 只匹配载荷摘要为 `d` 的 `V` |
| L2 | 不读、不写、不取戳：确认的是 L1，L2 可能更旧（仍受 `localMaxAge` 约束） | 写入方顺序同 `put`（§4.2），不写 L2 |
| 回源 / lease | 从不回源，不申请 lease 或刷新令牌 | 覆盖 lease 占位时销毁它（与 `put` 相同，持有者的 `fill` 失败） |
| 读不懂的内容 | 计入 `unknownFormats`，抛出 `CacheReadException`（`cause` 为 `ForeignFormatException`，更高版本 `newer = true`），不修改 L1。例外只有不是本协议写入的内容（格式 0）：按 `foreignFormat` 处理，从不回源，未配置时按“没有数据源”即 `FAIL`（抛出）；`RECLAIM` 时视为没有值（`Absent`）。更高格式版本、集合（标量缓存上）、解码失败的值从不视为没有值 | 与读取同样分类（引擎以 `foreignAsAbsent` 告诉脚本格式 0 能否视为没有值）：更高格式版本与集合总是拒绝，格式 0 在 `FAIL`（默认）时拒绝、`RECLAIM` 时视为没有值（`expected = null` 时整体覆盖）。拒绝：不修改、不广播，计入 `unknownFormats` 与 `writeFailures`，抛出 `CacheWriteException`（`cause` 为 `ForeignFormatException`）。脚本看不到 `Codec`，同版本但解码失败的值对写入是一个普通的值：调用方拿不到它的摘要，写入只会冲突、从不覆盖，随后的读取抛出 |
| L1 故障 | 总是抛出 `CacheReadException`，与 `readFailure` 无关：没有可降级的答案 | 抛出 `CacheWriteException`。结果未知（超时）时先重读：`current` 等于自己写入的值的摘要即已写入 |
| 计数 | 摘要未变计入 `conditionalUnchanged` | 冲突计入 `conditionalConflicts`（不计入 `writeFailures`） |

- **分类为什么这样定**：读与写必须对同一份内容给出同一个答案，否则“读—改—条件写”循环会出错：读取说“没有值”而写入说“冲突”时循环永远不结束，调用方拿冲突里的摘要重试则会从空值出发覆盖它。`RECLAIM` 只放宽格式 0（6.x 之前或其它程序写入、迁移前的数据）；更高格式版本的值在滚动升级期间被旧节点当作没有值覆盖就是降级，因此总是拒绝。
- **为什么用摘要而不是版本号**：线格式冻结（§3.3），标量头里没有版本字段；载荷摘要只依赖 `Codec` 的输出，不需要新的 key 或字段，混合版本部署不受影响（旧节点的 `put` 照常改变摘要）。摘要只覆盖载荷，`Unchanged` 因此附带格式版本，由引擎确认。
- **为什么不在写入时保存摘要**（让条件读取成为 O(1) 的比较）：标量头只有 1 个保留字节，放不下 20 字节的摘要，加长头部就是格式升级（两阶段发布，§3.3）；同 slot 的旁路 key（hash tag）改变 key 布局，而且混合版本部署中 6.6 及以前的节点的 `put`、`fill`、`invalidate` 不维护它，会让条件读取对已经变化的值回答 `Unchanged`。两者都违反 G1/G2 或线格式冻结，留到下一次格式演进再考虑。代价是 Redis 每次条件读写都把载荷读进 Lua 并计算一次 SHA-1，期间不处理其它命令：在 Redis 7.4 上实测 8 MB 的值一次约 20 ms，其中读进 Lua 约 13 ms（普通的 L1 读取经 `READ` 脚本同样要付），SHA-1 约 7 ms。条件读取省下的是网络传输与客户端解码，不是 Redis 的 CPU；文档很大、实例很多时要按这个代价估算确认周期。
- **与 `put` 的关系**：`put` 不检查，并发的 `put` 仍是“后写者胜”；需要不丢更新的写入方全部改用 `putIfMatch`。`putIfMatch` 的写入使所有节点的在途填充失效，与 `put` 相同。
- **SPI**：`RemoteStore.readIfChanged` 与 `readIfChangedAsync` 的默认实现先 `read`、在客户端比较摘要：结果正确，只是总是传输正文。`putIfMatch` 与 `putIfMatchAsync` 的默认实现抛出（异步以它完成）`UnsupportedOperationException`，引擎原样抛给调用方、不广播：不原子的“先读后写”会让两个并发的写入方都得到 `Written`（丢失更新），宁可不支持。异步默认实现不委托给同步方法（不能在调用方线程上阻塞），原生异步的存储必须同时覆盖两者。Redis 与内存后端以一次原子操作覆盖它们。`foreignAsAbsent` 参数由引擎按 `foreignFormat` 传入（存储不持有策略）。
- `SetCache` 不支持（元素增删本身是原子的）；其它 `Cache` 实现默认抛出 `UnsupportedOperationException`。

### 6.5 按版本有序的写入（6.8）

供“单一写入方按版本提交、投递可能重复或乱序”的缓存使用（Wow 把聚合的状态视图写入共享的 L1：同一聚合只有一个写入方、版本严格递增，但订阅的重放与重投会再次送来旧版本）。`putIfMatch` 按摘要比较，用它保证顺序需要把版本放进值里，并且每次写入两次往返（先读再条件写）；按版本有序的写入把版本放在 L1 的头部，一次往返完成比较与写入。

| 项 | 规则 |
|----|------|
| 声明 | `CacheSpec.versioned = true`，或设置 `versionedLoader`（`CacheLoader<K, Versioned<V>>`）/ `versionedAsyncLoader`（挂起视图：`suspendingVersionedLoader`）。数据源至多设置一个；带版本的缓存也可以用普通 `loader`（回源的值没有版本）或没有数据源（只由写入方写入） |
| 后端检查 | `runtime.create` 在 `CoherenceBackend.versionedWrites = false` 时抛出 `UnsupportedOperationException`，不注册任何东西（5.1 的 `SetElementsStore` 同样在创建时失败，而不是第一次写入时） |
| `putIfNewer(key, value, version[, ttl])` | 一个脚本 `PUT_IF_NEWER`：`version` 大于 L1 中的版本时按 `PUT` 的规则写入（`DEL` + 带版本的头 + 载荷 + 到期时间，销毁令牌）并 `PUBLISH`，返回 `Written`；否则不修改、不广播、不改变到期时间，返回 `Stale(current)`（相等是重复投递，幂等的空操作；更小是迟到的旧版本），计入 `staleVersions`（不计入 `writeFailures`）。寿命同 `put`：`ttlOf` 或策略寿命加抖动，或显式 `ttl` |
| `invalidateIfNewer(key, version[, ttl])` | 同上，写入的是墓碑：状态 `M` + 版本。读取时就是负缓存（不回源）；挡住不更新的版本。寿命默认是值的寿命（策略 `ttl` 加抖动，不是 `absentTtl`：墓碑要覆盖重复投递的时间窗口）；到期之后迟到的旧版本可以再次写入 |
| L1 中的版本 | 带版本的 `V` / `M` 的版本。没有版本的值与负缓存（`put`、普通回源、6.x 写入）、lease 占位、不存在都视为 `Versioned.NO_VERSION`（`Long.MIN_VALUE`），任何版本都能写入。`NO_VERSION` 不能作为写入的版本（`IllegalArgumentException`）。比较是有符号 64 位整数比较：Lua 的数是 double，脚本把 8 字节拆成高 32 位（有符号）与低 32 位（无符号）分别比较，2^53 以上同样精确 |
| 回源 | 带版本的数据源（`CacheLoader<K, out Versioned<V?>>`）的结果连同版本凭 lease 写回（`Fill.version`）。返回 `Versioned(null, n)`（“在版本 n 时不存在”，如已删除的聚合）时写回与 `invalidateIfNewer(key, n)` 相同的墓碑，寿命同墓碑：否则重放的旧状态会以更高的版本越过没有版本的负缓存，让已删除的 key 重新出现。返回 `null`（数据源中从来没有这个 key）时写入没有版本的负缓存（`absentTtl`）。批量回源同样如此 |
| 栅栏 | 不变：按版本写入销毁 lease 与刷新令牌，持有 lease 的回源写回被拒绝，**哪怕它读到的版本更高**（写入方胜出，数据源随后的写入会带来更高的版本）。回源不会覆盖更新的版本是结构性的：`fill` 只在令牌匹配时写入，而带版本的内容不持有令牌 |
| `getVersioned(key)` | 与 `get` 同一条读路径（L2 → L1 → 数据源），返回值与版本；不存在与墓碑都为 `null`（墓碑存活期间更旧的版本仍被拒绝），没有版本的值的版本是 `NO_VERSION` |
| 寿命 | 版本顺序只在条目存活期间成立：值或墓碑到期之后 key 中没有版本，任何版本（包括更旧的）都能写入（TTL 是安全网，§1）。寿命应覆盖重复投递的时间窗口；重放到最新位置的写入方最终写入最新版本，到期之后的陈旧度以那次重放为界 |
| L2 | 写入方顺序同 `put`（§4.2），不写 L2。L2 副本与值一起保存版本（引擎的值是 `Versioned<V>`），`getVersioned` 命中 L2 时不访问 L1 |
| 读不懂的内容 | 与 `putIfMatch` 同样分类（引擎以 `foreignAsAbsent` 告诉脚本格式 0 能否视为没有版本）：更高格式版本与集合总是拒绝，格式 0 在 `FAIL`（默认）时拒绝、`RECLAIM` 时视为没有版本。拒绝：不修改、不广播，计入 `unknownFormats` 与 `writeFailures`，抛出 `CacheWriteException`（`cause` 为 `ForeignFormatException`）。版本在头部，与 `Codec` 无关：同版本但解码失败的值照常按版本比较，更高的版本覆盖它 |
| L1 故障 | 抛出 `CacheWriteException`。结果未知时重放同一个版本是安全的：已写入则返回 `Stale(version)` |

- **版本为什么放在头部的令牌位置**：线格式在大版本内冻结，而 6.x 的读者必须照常读出这些值（Wow 与其它服务可能共享同一个 Redis 并逐台升级）。可选的位置与否决理由：
  - 新的状态字节或更高的格式版本：6.x 的读者按“不是本协议写入”或“更高格式”处理（回收后回源、绕过 L1 或读取失败），不是“可读”；格式升级还要两阶段发布（§3.3）。
  - 放进载荷：6.x 的读者把版本当作载荷交给 `Codec`，解码失败。
  - 同 slot 的旁路 key：改变 key 布局（需要 hash tag），6.x 节点的 `put`、`fill`、`invalidate` 不维护它，每个值多一个 key 与一次写入。
  - 头部的保留字节只有 1 字节；签发时间字段会被 6.x 的读脚本在授予刷新令牌时覆盖（版本被当前时间替换，之后的比较是错的）。
  - 令牌位置在值上只用于刷新令牌：用标记占住它、签发时间设为 `+inf`，6.x 的读脚本就永远不会授予刷新令牌，也就永远不会覆盖它；fill、release 按完整的 24 字节比较令牌，不会匹配。代价是下一条。
- **不支持提前刷新**：头部放不下刷新令牌与版本，带版本的内容从不授予刷新令牌。没有令牌的提前刷新只能从 L1 重新填充 L2，带版本的值永远等不到写回，刷新窗口内只是按退避（§5.5）一次次重读 L1 直到到期，因此 `CacheSpec` 拒绝 `versioned` 与 `refreshWindow` 同时出现；starter 的 `cocache.defaults.refresh-window` 不作用于带版本的缓存（`CoCacheProperties.versionedPolicyFor`），`cocache.caches.[name].refresh-window` 仍然作用并因此启动失败。按版本推送的写入方本来就不需要提前刷新。
- **不回收**：同版本但解码失败的带版本值不经 `reclaim` 转为 lease（`RECLAIM` 脚本与内存后端都拒绝）：lease 占位放不下版本，回收之后迟到的旧版本会被写入。有数据源的读取绕过 L1 回源（L2 降级限龄），等更高的版本覆盖它；没有数据源时按 `foreignFormat`（§5.4）。
- **丢失版本的写入**：`put`、`putAll`、`putIfMatch`（写入没有版本的值）、`invalidate`（删除 key）与 L1 到期都会丢掉版本，之后任何版本都能写入。需要顺序保证的写入方全部使用 `putIfNewer` / `invalidateIfNewer`；`invalidate` 留给运维清除。
- **混合版本部署**：6.x 节点能读带版本的内容（见 §3.3），但它们的写入（`put`、`invalidate`、`putIfMatch`、对解码失败内容的 `reclaim`）写入没有版本的内容。只有全部写入方升级到 6.8 之后才有顺序保证；带版本的缓存只能在 6.8 上声明，这通常就是同一个条件。
- **实现**：`CacheRuntime.create` 为带版本的声明创建 `CoherentEngine<K, Versioned<V?>>`（`value` 为 `null` 只来自带版本的数据源，表示带版本的不存在：写回时是墓碑，对用户是不存在）（声明经 `versionedEngineSpec` 转换，`ValueFormat` 为 `VersionedFormat`：载荷是 `Codec<V>` 的输出，版本随 `Envelope.Scalar` 进出 L1），外面包一层 `VersionedCache` 门面在 `V` 与 `Versioned<V?>` 之间转换。读路径、栅栏、合并回源、批量与异步视图都沿用引擎，没有第二份协议实现。普通缓存的引擎与命中路径不变（`LocalEntry` 与 `READ` 脚本没有改动）；带版本的缓存的 L2 命中多一次委托与一次字段读取，`getVersioned` 不多。
- **SPI**：`CoherenceBackend.versionedWrites`（默认 `false`）、`RemoteStore.putIfNewer` / `AsyncRemoteStore.putIfNewerAsync`（默认抛出 / 以 `UnsupportedOperationException` 完成：不原子的“先读后写”会让迟到的旧版本覆盖新版本）、`RemoteVersionedWrite`、`Fill.version`（6.7 的构造函数、`copy`、`componentN` 不变，带版本的构造函数给出墓碑的版本）、`Envelope.Scalar.version`（默认 `NO_VERSION`，参与相等比较，不影响摘要；支持版本的后端在 `fill`、`put`、`putAll` 时保存它、读取时原样返回；`putIfMatch` 写入没有版本的值）。

## 7. 失败语义

| 类别 | 操作 | L1 故障时 |
|------|------|-----------|
| 填充 | 读、`fill`、`reclaim`、刷新 | 默认 `readFailure = DEGRADE`：按未命中处理，回源结果只进 L2 并以 `degradedLocalMaxAge` 限龄，计入 `remoteFailures` / `degradedFills`。`readFailure = THROW`：L1 读失败抛出 `CacheReadException`；`fill`、`release` 等尽力而为的操作仍然降级 |
| 失效 | `invalidate`、`invalidateAll` | 本地效果（两次递增戳、淘汰 L2）先生效。默认 `invalidateFailure = RETRY`：登记为“重试中”并交给有界重试队列（`RetryPolicy`：最多 8 次，退避 100 ms 起翻倍至 10 s，容量 10 000），**不抛出**。重试期间该 key 的未命中与刷新跳过 L1 直接回源（L2 降级限龄）；重试成功后本节点再递增一次戳并淘汰 L2。放弃（耗尽、溢出、运行时关闭）时记录错误、计入 `invalidationsDropped`，其它节点的陈旧上界为 L1 剩余寿命（L2 到期后从旧的 L1 重新填充）。`invalidateFailure = THROW` 时抛出 `CacheWriteException` |
| 显式写入 | `put`、`putAll`、`add`、`remove`、`clear` | 本地效果先生效，然后抛出 `CacheWriteException`。结果未知（超时）时 L1 可能已生效；这些操作幂等，可以安全重试 |
| 按版本写入 | `putIfNewer`、`invalidateIfNewer` | 同显式写入，抛出 `CacheWriteException`；按版本幂等：结果未知时重放同一个版本，已写入则返回 `Stale(version)`（§6.5） |
| 条件读写 | `getIfChanged`、`putIfMatch` | `getIfChanged` 总是抛出 `CacheReadException`（与 `readFailure` 无关）；`putIfMatch` 同显式写入，但不是幂等的：结果未知时先 `getIfChanged`，再决定是否重试（§6.4） |

- `SetCache` 的读取失败总是抛出原始异常。
- **回源并发上限**（`LoadPolicy`，bulkhead）：只计 SingleFlight leader，批量回源占一个许可；在 `acquireTimeout` 内拿不到许可时抛出 `SourceLoadRejectedException`（被拒绝的回源会释放 lease）。L1 故障时由它保护数据源。
- 同线程对同一 key 的重入（loader 内再读同一个 key）快速失败（`IllegalStateException`）。
- loader 的异常原样抛给调用方与所有合并的等待者。
- **异步视图**：上表的语义不变，异常改为完成返回的 future（原始异常，不同步抛出）。此外：异步数据源超过 `asyncTimeout` 时以 `SourceLoadTimeoutException` 失败（释放 lease）；异步执行器拒绝任务时以 `RejectedExecutionException` 失败；运行时关闭时尚未触发的定时等待（lease 重读、回源许可）以 `IllegalStateException` 失败，不降级回源。

## 8. Redis 后端（`cocache-redis`）

- **客户端**：`RedisBackend.standalone(uri, options)`（单机或 Sentinel URI）、`RedisBackend.cluster(uris, options)`（自适应拓扑刷新）由后端自建 Lettuce 客户端；`RedisBackend.of(client, options)` 使用调用方的客户端，选项与生命周期由调用方负责。只支持 Lettuce；不支持 Jedis；**不支持静态主从（master-replica）拓扑**。
- **断线行为**：自建客户端设置 `DisconnectedBehavior.REJECT_COMMANDS` 与命令超时（`commandTimeout`，默认 2 s，Lettuce 的 `TimeoutOptions`，见下文“原生异步”）。Lettuce 默认会缓冲断线期间的命令并在重连后重放，一个已经向调用方报错的 `put` 可能稍后执行并覆盖更新的值，所以必须拒绝。
- **只访问主节点**：读脚本会写 lease 与刷新令牌，不能在副本上执行。CoCache 自己的连接不继承应用的从库读取配置。
- **命令连接**：每个后端一条共享连接（Cluster 由 Lettuce 按节点管理），并发的异步命令在其上多路复用。大载荷会造成队头阻塞。
- **脚本缓存**：每条命令 `EVALSHA`，遇到 `NOSCRIPT`（故障切换、重启、resharding 后）改用 `EVAL` 重试。
- **原生异步**（RFC 0004 §4.3）：`RedisRemoteStore` 实现 `AsyncRemoteStore`，两种失效通道实现 `AsyncInvalidationChannel`。每个操作只构造一次命令（`xxxCommand`），同步方法等待它；异步方法返回它经 `RedisConnections.bounded` 与 `mapped` 包装后的新 future，等待期间不占线程，同步与异步只有一份协议实现。异步结果的超时与异常类型与同步等待一致：超过 `commandTimeout` 时不再等待应答并以 `RedisException("Command timed out …")` 失败，执行异常解包为原始的运行时异常（不是运行时异常的包装为 `RedisException`）。超时不经 JDK 的延迟调度器，也不经 `cocache-timer`（RFC 0004 §14）：自建客户端的 `TimeoutOptions` 固定为 `commandTimeout`（替换 Lettuce 默认的 60 s 连接超时，并关闭维护期间的放宽），Lettuce 以 Netty 时间轮为每条命令计时，超时以 `RedisCommandTimeoutException` 完成命令，`bounded` 把它映射为上述异常（同步等待与 Lettuce 谁先超时，异常都相同）。单机只有这一个计时器。集群中 Lettuce 在命令写入节点连接时才计时：**等待建立节点连接（首次访问某节点、拓扑变化后）的命令不计时**，只受连接建立的超时限制（`connectTimeout` 默认 10 s，握手受 `RedisURI` 超时限制，默认 60 s）。因此 `bounded` 另以客户端资源的时间轮兜底，命令完成时撤销，异步结果的上界仍是 `commandTimeout` 加至多一个刻度，与连接建立的耗时无关；`of` 传入的客户端选项未知，同样由时间轮计时，客户端自己的异常原样传递（`CommandTimeouts`）。时间轮的精度是一个刻度（默认 100 ms）。这项改动的收益是每条命令不再依赖 JDK 的单线程延迟调度器，不是吞吐：JMH 中 8 线程闭环与 6.4.0 持平（RFC 0004 §14）。`NOSCRIPT` 之后的 `EVAL` 是新命令，在单机异步路径上单独计时（同步等待仍以 `commandTimeout` 限制全程）。`mapped` 代替 `thenApply`，失败不包装为 `CompletionException`。`readAllAsync` 等全部读取完成再判定，任一失败时先尽力释放其余读取取得的 lease；`putAllAsync` / `invalidateAsync` 以按发出顺序的第一个异常失败。异步结果在 Lettuce 的 I/O 线程上完成，引擎切换到异步执行器（§5.6）。
- **命令超时对 Lettuce 内部命令的影响**：自建客户端的 `TimeoutOptions` 作用于经连接写入器发出的每条命令，也包括 Lettuce 自己发出的命令，它们的上限从默认的 60 s（`RedisURI` 超时）变为 `commandTimeout`（对照 Lettuce 7.5.2 源码核实）：
  - **重连后的自动重订阅**（`StatefulRedisPubSubConnectionImpl.resubscribe` 经 `async().subscribe/ssubscribe`）：超时只让 Lettuce 本地以异常完成这条命令（它只记录执行错误，不记录超时）；命令已经写出，服务端照常执行，应答到达时仍按序解码，`PubSubCommandHandler.afterDecode` 照常通知订阅确认，CoCache 在确认时重置订阅者（§8.1）。连接真的不可用时，心跳的 `PING`（同样受 `commandTimeout` 约束）失败，重置全部订阅者并强制重连，再次重订阅。
  - **集群拓扑刷新**（`DefaultClusterTopologyRefresh` 在 `connectToNodeAsync` 建立的节点连接上发 `CLUSTER NODES` 与 `INFO`）：节点连接同样包着 `CommandExpiryWriter`，超过 `commandTimeout` 的节点视图被排除；全部失败时这次刷新放弃并记录，之后由自适应触发器（`MOVED` / `ASK`、重连尝试等，受 `adaptiveRefreshTriggersTimeout` 限流，默认 30 s）再次触发。后端只开启自适应刷新（`enableAllAdaptiveRefreshTriggers`），没有周期刷新。很大或很忙的集群中 `CLUSTER NODES` 慢于 `commandTimeout` 时，应调大 `commandTimeout`。
  - 握手（`AUTH` / `HELLO` / `CLIENT SETNAME`）由 `RedisHandshake` 直接写入通道，不经写入器，不受影响（受 `RedisURI` 超时限制）。
  - 维护期间的放宽只有 Lettuce 7 才有（`relaxedTimeoutsDuringMaintenance`）。Spring Boot 4.0 管理的 Lettuce 6.8 没有这两个符号，后端在运行时跳过这一项（`RedisBackend.disableMaintenanceRelaxation`），6.x 上本来也不放宽。强制重连拆开写入器时同样不依赖 Lettuce 7 的 `Delegating` 接口（`RedisInvalidationChannel.disconnect`；6.0–6.4.0 用了它，在 Lettuce 6 上心跳失败后抛出 `NoClassDefFoundError`，心跳任务就此停止。心跳任务现在兜住任何 `Throwable`，记录后继续下一次），整个模块的测试在 Lettuce 6.8.2 与 7.5.2 上都通过。
- **原子广播探测**：`pubsub` 模式启动时在脚本内向与 key 不同 slot 的频道 `PUBLISH`。在把 `PUBLISH` 当作 `SPUBLISH` 执行的环境（如 ElastiCache Serverless）中报跨 slot 错误，后端以 `atomicBroadcast = false` 运行并告警。只有确定性的错误（跨 slot、ACL 拒绝）才降级；超时等其它错误使启动失败，而不是永久降级。

### 8.1 失效通道

| 模式（`RedisChannelMode`，配置 `cocache.redis.channel-mode`） | 订阅 / 发布 | 原子广播 |
|------|------|------|
| `pubsub`（默认） | `SUBSCRIBE` / 脚本内 `PUBLISH`；Cluster 中经集群总线发往全部节点 | 是（探测失败时否） |
| `sharded`（Redis 7+） | `SSUBSCRIBE` / 写入成功后单独 `SPUBLISH`，消息只在频道所属分片内传播 | 否（脚本内 `SPUBLISH` 要求频道与 key 同 slot） |

两种模式互不相通，**全集群必须一致**。

- **每个后端一条订阅连接**，按频道（namespace）分发给订阅者；注册新缓存不会重置已有缓存。
- **重置时机**：频道的第一个订阅者发出订阅后，等待服务端确认（最多 `commandTimeout`），确认回调对该频道的订阅者调用 `onReset`，所以 `subscribe` 返回时重置已经完成；同一频道的后续订阅者直接收到一次 `onReset`。断线重连后 Lettuce 自动重新订阅，确认时再次重置。
- **心跳**：订阅连接每隔 `heartbeat`（默认 5 s）`PING`，失败或超时即重置全部订阅者并强制断开该连接，由 Lettuce 重连、重新订阅（确认后再次重置），半开连接在秒级内被发现。最近一次心跳的结果以只读状态暴露（`RedisBackend.heartbeatHealthy` / `heartbeatFailure`，供健康检查使用），心跳成功即恢复正常。CoCache 自建的客户端另开启 TCP keepalive（有 epoll / io_uring 时再加 `TCP_USER_TIMEOUT`）。Cluster 的经典 Pub/Sub 无法取得底层连接，只告警并依赖 keepalive。Cluster 的 `sharded` 模式 `PING` 确认了订阅的节点；slot 的主节点不再是确认节点时（故障切换），重置订阅者、退订旧节点并向新主节点重新订阅。
- **slot 迁移**（`sharded`）：原节点让出 slot 时会主动发送 `sunsubscribe`，Lettuce 不会自动重新订阅。收到非主动退订时立即重置订阅者并重新订阅；失败由心跳重试，直到确认。
- **兼容性**：Redis 7.0+（用到 `TIME`、`SETRANGE`、`SPUBLISH`）。Valkey 7.2+ 实现了同样的命令；CI 在单机 Valkey 8 上运行 `cocache-redis` 与 starter 测试。

### 8.2 5.x 数据迁移

`CoCache5Migrator`（`me.ahoo.cocache.redis.migration`）把 5.x（5.0–5.4 线格式相同）写入的 key 离线转换为本格式，供 L1 是唯一副本的缓存（没有数据源的 `Cache`、`SetCache`）升级使用；有数据源的缓存不需要它（§5.4 的 `RECLAIM`）。它自建或使用调用方的 Lettuce 客户端，不经运行时与引擎，**要求迁移期间所有写入方停止**。

- **5.x 格式**（`CoCache5Format`）：String（JSON / 字符串，默认用目标缓存的 `Codec` 解码 UTF-8 字节）、原生 Set（到 `SetCache` 的成员，或到标量值）、Hash（`Map<String, String>` → 值）；负缓存哨兵（String 本身、单元素 Set、单字段 Hash）转为状态 `M`。其它 Redis 类型、类型与配置不符、解码失败都立即中止（`CoCache5MigrationException`，带 key 与到此为止的报告），不跳过。
- **key**：来源为 5.x 前缀下 `SCAN` 到的 key（默认 `cocache:{name}:`）或显式列出的 key；目标为 `{keyPrefix}{namespace}:` + 映射后的后缀，前缀、命名空间与编解码取自目标 spec。扫描范围互相重叠、或备份 key 落在扫描范围内时拒绝执行。
- **迁移之前的写入**：没有数据源的缓存在迁移之前，读取（§5.4）与 `SetCache` 的元素增删（§6.3）默认失败，key 原样保留，之后仍可迁移；`put` / `putAll` 整体覆盖 5.x 内容，该 key 之后已是本格式，不再迁移、也不留备份。
- **每个 key**：一次 Lua 读取 `TYPE`、`PTTL`、`DUMP` 与内容；已是本格式（String 以 magic 开头、Hash 有 `!f`）则跳过（可重复执行）。目标等于来源时为**原地迁移**：先 `RESTORE … REPLACE` 把原值备份到 `{backupPrefix}{5.x key}`（默认 `cocache5-backup:`，类型、内容、剩余寿命不变；单独的命令，备份 key 可以在任意 slot），再以脚本比较 `DUMP` 后按 `PRELUDE` 的 `write` 写入（内容已变则中止）。目标不同时 5.x key 不动，目标必须不存在（已是本格式则跳过，其它内容则中止）。剩余寿命原样保留；写入不广播。
- **回滚**（`rollback`）：从备份恢复原地迁移的 key 并删除备份，删除写到新 key 的目标。`dropBackups` 删除备份。`dryRun` 只读取与解码。
- 测试：`cocache-redis` 的 `CoCache5MigratorTest`（往返、重复执行、部分失败后重跑、回滚、备份清理）、`ForeignFormatFailTest`（漏跑迁移时读取 `FAIL`）、`ForeignFormatWriteTest`（漏跑迁移时 `SetCache` 的元素写入 `FAIL`、更高格式版本的值在没有数据源时 `FAIL`、存储脚本的判定）、`CoCache5DataLossTest`（`RECLAIM` 下的数据丢失复现），在单机、Cluster 与 Valkey 上运行。

## 9. 运行时与 Spring 集成

### 9.1 声明与运行时

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

- `CacheSpec` / `SetCacheSpec` 是不可变数据。所有有状态组件（L2、栅栏、SingleFlight、存储、订阅）都由 `runtime.create` 按 spec 新建，用户只提供 spec 与无状态函数（`Codec`、`CacheLoader`、`KeyEncoder`、`TtlFunction`、`KeyFilter`），所以两个缓存不可能共享一个 L2。
- 缓存名或 namespace 重复时 `create` 失败；订阅失败时回滚注册。
- `CacheRuntime.close()` 按创建的逆序关闭订阅、引擎与存储，再关闭运行时自己创建的时钟、执行器与重试调度器（未执行的重试计入丢弃）。后端由调用方关闭。
- **异步视图的执行资源**（`CacheRuntime.Builder`）：`asyncExecutor`（默认运行时自建的 `ForkJoinPool`：并行度为 CPU 数、守护线程 `cocache-async-*`；它的线程在 `join()` 上阻塞时会补偿线程，替换为固定大小的普通线程池时没有这个保证）；`blockingExecutor`（默认 JDK 21+ 每个任务一个虚拟线程，否则不排队的守护线程池 `cocache-blocking-*`，最多 512 线程，满时拒绝而不是由调用方执行：调用方是异步执行器的线程）；定时器 `cocache-timer`（单线程，只把工作提交给异步执行器）。调用方给出的执行器不随运行时关闭。关闭时先让尚未触发的定时等待失败，再停定时器，最后关闭自建的执行器。
- `CacheRuntime.cache(name)` / `metrics(name)` / `admin(name)` 按名称查找。

### 9.2 starter 的 bean

| bean | 条件 |
|------|------|
| `cocacheBackend`（`CoherenceBackend`，`RedisBackend`） | `cocache.backend=redis`（默认），classpath 中有 Spring Data Redis，没有其它 `CoherenceBackend` bean，且存在 `DataRedisConnectionDetails`。由连接信息构造（单机 / Sentinel / Cluster，用户名与密码，SSL bundle → Lettuce `SslOptions`），后端自建客户端，不复用应用的连接工厂 |
| `cocacheBackend`（`CoherenceBackend`，`InMemoryBackend`） | `cocache.backend=memory`，没有其它 `CoherenceBackend` bean。不需要 Spring Data Redis；启动时告警：只适用于单进程，没有跨实例一致性 |
| `cocacheRuntime`（`CacheRuntime`） | 存在 `CoherenceBackend` bean。有唯一的 `CacheClock` bean 时运行时使用它（内存后端同样使用它，测试推进 `ManualClock` 即可让两层一起到期）。`cocache.refresh-executor` 指定提前刷新执行器的 bean 名，未设置时运行时自建并管理内置执行器；`cocache.async-executor` / `cocache.blocking-executor` 指定异步视图的异步执行器与阻塞执行器（§5.6、§9.1），未设置时运行时自建 |
| `cocacheAssembler`（`CacheAssembler`）、`cocacheAnnotatedCacheFactory` | 运行时存在 |
| `cocacheSpecRegistrar`（`CacheSpecRegistrar`） | `cocache.enabled` 未关闭（静态 `@Bean`，`BeanFactoryPostProcessor` 需在其它 bean 之前注册） |
| `cocacheRemovedPropertiesCheck`（`BeanFactoryPostProcessor`） | `cocache.enabled` 未关闭（静态 `@Bean`）。在容器后处理阶段、任何缓存创建之前检查已删除的配置项（§9.4） |
| `cocacheCacheManager`（`CoCacheSpringCacheManager`） | 没有其它 `CacheManager`；有 spring-tx 时包装为 `TransactionAwareCacheManagerProxy`。`retrieve` 经缓存的异步视图（§9.5）；已弃用的 `cocache.spring-cache.retrieve-executor` 设置时只告警并被忽略（6.3 起，7.0 删除） |
| `cocacheTransactionalInvalidator` | 有 spring-tx |
| `cocacheSpringCacheSyncCheck`（`SpringCacheSyncCheck`） | 运行时存在（§9.5） |
| `cocacheEndpoint`（`CoCacheEndpoint`） | 有 actuator，运行时存在，且端点可用（`management.endpoints.web.exposure.include` 等暴露配置包含 `cocache`、访问未被关闭） |
| `cocacheHealthIndicator`（`CoCacheHealthIndicator`） | 有 `spring-boot-health`（actuator 自带），`management.health.cocache.enabled` 未关闭 |
| `cocacheMeterBinder`（`CoCacheMeterBinder`） | 有 Micrometer，运行时存在 |
| `cocacheMeterBindingPostProcessor`（`BeanPostProcessor`） | 有 Micrometer（静态 `@Bean`）。延迟创建的缓存 bean 初始化后补绑指标 |

执行器配置项（`cocache.refresh-executor`、`cocache.async-executor`、`cocache.blocking-executor`）只按 bean 名解析，不按类型：与 L2、L1、`KeyConverter` 等有状态组件同一规则，避免按类型匹配时意外共用或选错 bean。名称没有对应的 bean 或 bean 不是 `Executor` 时启动失败，错误信息指明配置项。这些执行器由容器管理，`CacheRuntime.close()` 不会关闭它们（只关闭自己创建的）。Spring Boot 的 `applicationTaskExecutor` 可以直接使用；不会因为开启虚拟线程而自动使用它，需要显式指定。

`cocache.enabled=false` 关闭全部自动配置。测试或单实例部署可以自行声明 `CoherenceBackend` bean（如 `InMemoryBackend`），Redis 后端随之退让。

### 9.3 两种声明方式，同一个引擎

- **接口式**：`@CoCache interface UserCache : Cache<String, User>`，由 `@EnableCoCache(caches = [UserCache::class])` 注册。启动时翻译为 spec，经 `CacheAssembler` 创建引擎，再由 `CacheProxies` 包上 JDK 代理。
  - bean 名 = 缓存名（`@CoCache.name`，默认接口简单类名），类型为接口本身；与已有 bean 重名时启动失败。
  - 泛型取自接口继承的 `Cache<K, V>` / `SetCache<K, E>`，必须是具体类型。
  - 数据源：`@CoCache(loader = "beanName")`（`CacheLoader` 或 `AsyncCacheLoader` 均可，其它类型启动失败），否则按 `CacheLoader<K, V>`、`AsyncCacheLoader<K, V>` 唯一匹配（同一种有多个候选、或两种各有一个候选时启动失败，需用 `loader` 指明；同时实现两者的 bean 按同步数据源使用），找不到则没有数据源。
  - 编解码：`@CoCache(codec = "beanName")`，否则按 `Codec<V>` 唯一匹配，找不到则用应用的 Jackson 3 `ObjectMapper` 按值类型构造 `JacksonCodec`。
  - key：`keyExpression` 为 SpEL 模板（以业务 key 为根对象），否则 `toString()`。
  - 注解时长单位为秒，`UNSET`（-1）表示交给默认值与配置文件；`FOREVER` 只用于 `@CoCache.ttl`（永不过期）与 `@CaffeineCache.expireAfterAccess`（不按空闲淘汰）；`refreshWindow = 0` 表示关闭。
  - 代理：`Cache` / `SetCache` 的方法按方法名直接调用引擎，不经 `Method.invoke`，**异常原样抛出**；用户接口的 default 方法经 `InvocationHandler.invokeDefault` 执行；其它抽象方法（名称、参数个数与类型、返回类型不与 `Cache` / `SetCache` 方法匹配）在创建缓存时即启动失败，并提示改为 default 方法；`equals` / `hashCode` 按代理身份。
- **spec bean**：`@Bean fun userSpec(): CacheSpec<String, User> = cacheSpec("user") { … }`。
  - `CacheSpecRegistrar`（`BeanFactoryPostProcessor`）从工厂方法**声明的**泛型返回类型解析 `K`、`V`，不实例化 spec；无法解析（原始类型、通配符）时启动失败。
  - 为每个 spec 注册一个 `Cache<K, V>` bean（`SetCacheSpec` → `SetCache<K, E>`）：bean 名为 spec bean 名去掉 `Spec` 后缀（`userSpec` → `user`），没有该后缀时加 `Cache`；与已有 bean 重名时启动失败。注入的就是引擎本身，没有代理层。
- 同一个缓存名既有接口声明又有 spec bean 时，第二个创建时因缓存名重复而启动失败。缓存在首次注入时创建。

### 9.4 策略分层与校验

优先级（后者覆盖前者）：内置默认值 → 声明（注解或 spec DSL）→ `CacheSpecCustomizer`（按 Spring 顺序）→ `cocache.defaults.*` → `cocache.caches.[name].*`。

- **`key-prefix`**（`cocache.defaults.key-prefix`、`cocache.caches.[name].key-prefix`）不是策略字段，但按同一规则分层：配置文件覆盖声明（包括注解或 spec 中显式写出的 `keyPrefix`）与定制器，`caches.[name]` 覆盖 `defaults`；都未设置时沿用声明（默认 `cocache:`）。配置项中的占位符（如 `${spring.application.name}:`）由 Spring 的配置解析处理；`@CoCache.keyPrefix` 是字面文本，不解析占位符。失效频道不含前缀（§3.2）。
- 策略字段：`ttl`、`ttl-forever`、`ttl-jitter`、`absent-ttl`、`local-max-age`、`degraded-local-max-age`、`refresh-window`、`lease-ttl`、`lease-wait`、`load-max-concurrent`、`load-acquire-timeout`、`load-async-timeout`、`local-maximum-size`、`invalidate-failure`、`read-failure`、`foreign-format`。策略时长不写单位时按**秒**解析，也可以写 `500ms`、`2m`、`PT2M`；`refresh-window: 0` 表示关闭（可以关掉声明中的提前刷新）。`cocache.defaults.ttl` / `ttl-forever` 不作用于 `SetCache`（L1 是唯一副本，全局寿命会让集合过期丢失），`cocache.defaults.absent-ttl` / `refresh-window` / `foreign-format` 同样跳过 `SetCache`（全局的 `reclaim` 会让未迁移的集合读成空集合）；只有 `cocache.caches.[name].ttl` / `ttl-forever` 能改变集合缓存的寿命。`cocache.redis.*`（`command-timeout`、`heartbeat`、`invalidate-batch-size`、`client-name`、`channel-mode`）的时长不写单位时按毫秒解析。
- 配置文件中的 `ttl` 不影响 `ttlOf`。
- 启动失败的情形：`cocache.caches` 中出现未声明的缓存名（所有单例创建后由 `CacheAssembler` 校验；缓存名不区分大小写匹配）；使用了已删除的配置项（`RemovedProperties`；错误信息给出替代项，如果有的话，例如 `cocache.redis.strict-failure` → `read-failure: throw`；`missing-guard-sentinel`、`client-side-hits` 没有替代项）；策略不合法。
- 策略约束（`CachePolicy` 构造时校验）：所有时长为正且 ≤ 100 年（`ttlJitter`、`lease.wait`、`load.acquireTimeout` 可以为 0）；抖动实际幅度不超过 `ttl / 2`；`refreshWindow < ttl − 实际抖动` 且 `refreshWindow < localMaxAge`，否则新副本一出生就在窗口内。校验只覆盖策略 `ttl`：`ttlOf` 或 `put(k, v, ttl)` 给出的不长于窗口的寿命不报错，这样的值不参与提前刷新（§5.5）。

### 9.5 Spring Cache 与事务

- `CoCacheSpringCacheManager` 只暴露运行时中已创建的缓存，不按名称自动创建。
- 负缓存视为未命中（否则 `@Cacheable` 永远不调用方法）；`put(k, null)` 映射为 `invalidate`；`evict` = `invalidate`；`clear` 只清本节点 L2（L1 是共享存储）。
- `@Cacheable(sync = true)` 经引擎的 `get(key, loader)` 回源，受 lease 与失效戳保护。不带 `sync` 的 `@Cacheable` 由 Spring 执行“读未命中 → 调用方法 → `put`”，`put` 是无条件写入，没有这层保护；需要上述一致性保证时用 `sync = true`。
- **启动检查**（`SpringCacheSyncCheck`，`SmartInitializingSingleton`）：存在 `CacheOperationSource`（`@EnableCaching`）时，按 bean 类型（不实例化 bean；CGLIB 取父类，JDK 代理取已创建单例的目标类）解析缓存操作，含 `@CacheConfig` 的类级缓存名。指向 CoCache 缓存名（已创建与已声明）且 `sync = false` 的 `CacheableOperation` 记录告警（`类名#方法名` 与缓存名）；`cocache.spring-cache.require-sync=true` 时启动失败。只按缓存名判断；指定了其它 `cacheManager` 或自定义 `cacheResolver` 的操作跳过。没有 `@EnableCaching`，或不存在名为 `cocacheCacheManager` 的 bean（用户以其它名字声明了自己的 `CacheManager`，starter 的管理器退让）时不做任何事。
- `retrieve` 经缓存的异步视图（RFC 0004 §6）：`retrieve(key)` 是异步 `lookup`，L2 命中返回已完成的 future（不切换线程），负缓存以 `null` 完成；`retrieve(key, loader)`（响应式 / `CompletableFuture` 方法上的 `sync = true`）是异步 `get(key, AsyncCacheLoader)`，与 `get(key, loader)` 一样经引擎受 lease 与失效戳保护的路径回源，**没有线程等待**异步 loader；方法体（`valueLoader.get()`，可能先阻塞再返回）派发到运行时的阻塞执行器（`CacheRuntime.blockingExecutor`）执行，从不在异步执行器上执行（6.1 中它在 retrieve 执行器上，同样不在调用方线程上，看不到调用方的 ThreadLocal）；`retrieve(key)` 与 `retrieve(key, loader)` 的失败都以原始异常完成。因此不再需要单独的 retrieve 执行器，嵌套的异步 `sync = true` 也不再有内层任务排在外层任务之后的死锁条件。`CoCacheSpringCacheManager(runtime, executor)` 构造函数、`CoCacheSpringCacheManager.DEFAULT_EXECUTOR` 与 `cocache.spring-cache.retrieve-executor` 自 6.3 弃用（执行器被忽略），7.0 删除；`CoCacheSpringCache(cache, admin, executor)` 的执行器执行方法体。
- 已知限制：Spring Cache 的 `put` / `evict` 只有同步签名，响应式方法上的 `@CachePut`、`@CacheEvict` 与不带 `sync` 的 `@Cacheable` 在完成回调中同步调用它们，各占用该线程一次 L1 往返；需要非阻塞失效时直接调用 `cache.async().invalidate(key)`。
- `TransactionalInvalidator`：事务内调用时登记到当前事务的 `afterCommit`，同一事务内对同一缓存的失效合并为一次 `invalidateAll`，回滚时不失效，没有事务时立即失效。登记按事务的同步列表划分作用域，`REQUIRES_NEW` 的内层事务在自己提交之后失效。有 spring-tx 时 CacheManager 的 `@CacheEvict` / `@CachePut` 同样推迟到提交之后。

### 9.6 Spring AOT 与原生镜像

AOT 处理时，`AnnotatedCacheRegistrar`、`JoinCacheRegistrar`（`ImportBeanDefinitionRegistrar`）与 `CacheSpecRegistrar`（`BeanFactoryPostProcessor`）在**构建期**运行，它们产生的 bean 定义被写成生成代码；运行时由生成的 `ApplicationContextInitializer` 注册 bean 定义，注册器不再运行（`CacheSpecRegistrar` 作为 bean 仍会运行，见下）。

- **缓存 bean 一律由 `CacheBeanDefinitions` 的静态工厂方法创建**（`annotatedCache(type, beanFactory)`、`joinCache(type, beanFactory)`、`specCache(specName, beanFactory)`）。bean 定义只携带一个可序列化的构造参数（接口 `Class` 或 spec bean 名）与目标类型（接口本身，或带 spec 泛型的 `Cache<K, V>` / `SetCache<K, E>`），`BeanFactory` 按类型自动注入。AOT 能为工厂方法生成直接调用的代码；lambda 实例提供者（`instanceSupplier`）不能生成，会让 AOT 处理失败。
- **识别缓存 bean 只看这个构造参数**（`cacheInterfaceOf` / `joinInterfaceOf` / `specBeanOf`），不使用 bean 定义属性：AOT 生成的 bean 定义不保留属性。`declaredCacheNames`、组合视图按缓存名查找 spec 缓存都依赖这一点。
- **`CacheSpecRegistrar` 幂等**：目标 bean 名已存在且是同一个 spec 的缓存定义时跳过（AOT 运行时生成代码已注册），其它重名仍启动失败。
- **运行时提示**：`CoCacheAotProcessor`（`BeanFactoryInitializationAotProcessor`，经 `META-INF/spring/aot.factories` 注册）读取上述 bean 定义，为每个接口式声明与组合视图登记 JDK 代理（接口列表与 `CacheProxies` / `JoinCacheProxies` 创建代理时一致，只有接口本身）、接口公共方法反射（注解、泛型、抽象方法校验、`invokeDefault`）、key 类型的 `toString` 反射（默认 `KeyEncoder` 的检查，spec 声明同样登记）、`keyExpression` / `joinKeyExpression` 求值对象（key 类型 / 主缓存值类型）的公共方法与字段、使用默认 `JacksonCodec` 时值类型的 Jackson 绑定。`CoCacheRuntimeHints`（`RuntimeHintsRegistrar`）登记与声明无关的固定提示：`Cache` / `SetCache` / `JoinCache` 与异步视图 `AsyncCache` / `AsyncSetCache` 的公共方法、`Executors.newVirtualThreadPerTaskExecutor`（运行时的默认阻塞执行器与已弃用的 Spring Cache 默认 retrieve 执行器经反射取得）、L2 用到的四个 Caffeine 生成类（`SSMS`、`SSMSA` 与节点类 `PSMS`、`PSAMS`，对应 `maximumSize` 加可选 `expireAfterAccess`）。
- spec 声明的编解码、数据源与 SpEL 以外的反射由使用者负责登记提示；`@EnableCoCache` 列表、spec bean 与 `@Conditional` 条件在构建期固定，策略配置项（`cocache.defaults.*`、`cocache.caches.*`）仍在运行时绑定。
- 验证：`CoCacheAotTest` 检查提示，`CoCacheAotRuntimeTest` 编译生成代码并在没有注册器的新容器中运行；`cocache-example` 的原生镜像由 `.github/workflows/native.yml` 构建并冒烟。新增反射、代理、资源访问或改变缓存 bean 的创建方式时，同时更新提示与这两个测试。

## 10. 可观测与运维

- **`CacheMetrics`**（每个缓存一份，`LongAdder` 累计值）除 `localHits` 外全部记录在未命中、写入、失效路径上。读取按层互斥计数：每次读取（批量读取按 key）计入 `localHits`（L2 命中）、`misses`（L2 未命中）或 `filtered`（key 过滤器）之一，未命中再由 L1 命中计入 `remoteHits`、由回源计入 `loads`。同步与异步路径计入同一组计数；`loadTimeouts` 只来自异步数据源。
  - **L2 命中计数默认关闭**（G3）：`CacheRuntime.Builder.recordLocalHits(true)`（starter：`cocache.metrics.local-hits=true`）对该运行时的全部缓存开启，`CacheMetrics.localHitsRecorded` 反映开关。开启时命中路径上唯一的记录是一次 `LongAdder.increment()`（不加锁、不分配、按线程分散单元）；关闭时命中路径只多一次对引擎 final 字段的判断，不触碰计数器。7 个命中点（`get`、`get(key, loader)`、`lookup`、`getAll` 逐 key，及异步视图的对应方法；`SetEngine` 的同步与异步 `entryOf`）都经 `AbstractEngine.countLocalHit()`；`get(key, loader)` 遇到没有答案（`unresolved`）的副本不算命中。开关存在的原因：命中路径吞吐对一次原子自增敏感（JMH：开启后 `l2HitViaInterface` 1/8 线程 −7.7%/−8.9%、`l2Hit` −5.0%/−4.4%；关闭时与未加计数的版本持平，见 #655），因此不能默认开启。
- **Micrometer**（`CoCacheMeterBinder`，指标目录在 `CacheCounters`，tag `cache`）：`cocache.hits{level=l2}`（只在开启 L2 命中计数时注册，端点同样只在开启时输出 `localHits`，避免把“未记录”读成 0）、`cocache.hits{level=l1}`、`cocache.misses`、`cocache.filtered`、`cocache.load.failures`、`cocache.load.rejections`、`cocache.load.timeouts`（异步数据源超过 `asyncTimeout`）、`cocache.lease.waits`、`cocache.lease.wait.timeouts`、`cocache.fill.rejections`、`cocache.degraded.fills`、`cocache.stale.discards`、`cocache.reclaims`、`cocache.unknown.formats`、`cocache.refreshes`、`cocache.refresh.rejections`、`cocache.remote.failures`、`cocache.invalidations.received`、`cocache.write.failures`、`cocache.invalidation.retries`、`cocache.invalidations.dropped`、`cocache.resets`、`cocache.conditional.unchanged`（`getIfChanged` 确认未变）、`cocache.conditional.conflicts`（`putIfMatch` 冲突）、`cocache.versioned.stale`（`putIfNewer` / `invalidateIfNewer` 遇到不更旧的版本）；FunctionTimer `cocache.loads`；Gauge `cocache.local.size`。延迟创建的缓存在 bean 初始化后补绑。
- **回源观测 `LoadObserver`**（`cocache-core` 的 SPI，经 `CacheRuntime.Builder.loadObserver` 设置，存于 `EngineContext`，默认 `LoadObserver.NOOP`）：只包住 `guarded` 内的回源调用（`load` / `loadAll` / `refresh`，提前刷新经委托给 loader 的 `RefreshLoader` 识别）与确实发生的 lease 等待（`lease.wait`）。**L2 命中、L1 读取与填充都不经过它**（G3）；默认 NOOP 时引擎按引用识别并直接执行，不分配 lambda。被并发上限拒绝的回源不观测。实现必须在调用方线程上同步执行一次并原样抛出异常（异常透明），不得记录 key。异步路径调用 `observeAsync`（从调用 block 到其 future 完成）：默认实现不观测（直接返回 block 的 future），只覆盖了 `observe` 的观测器在异步路径上没有数据。starter 的 `CoCacheObservationAutoConfiguration`（`@ConditionalOnClass(ObservationRegistry)`、`@ConditionalOnBean(ObservationRegistry)`、`cocache.observation.enabled`）注册 `MicrometerLoadObserver`：Observation `cocache.load`，低基数标签 `cache`、`operation`、`outcome`（`success` / `error`；异步路径上引擎超时为 `timeout`），高基数标签只有 `key.count`；`observeAsync` 在 `openScope()` 内调用 block（`loadAsync` 的同步部分能看到当前观测），future 完成时记录结果并停止；`cocacheRuntime` 经 `ObjectProvider` 取唯一的 `LoadObserver`，因此 `cocache-core` 不依赖 Micrometer，没有 Micrometer 时该自动配置整体跳过。
- **actuator 端点 `cocache`**（基于 `CacheAdmin`）：`GET /actuator/cocache` 列出缓存与计数，`GET /actuator/cocache/{name}` 查看单个缓存，`DELETE /actuator/cocache/{name}` 清空本节点 L2，`GET /actuator/cocache/{name}/{key}` 查看一个 key，`DELETE /actuator/cocache/{name}/{key}` 按 key 强制失效。`{key}` 是**编码后的 key**（存储 key 中 `{keyPrefix}{namespace}:` 之后的部分，可含 `/`，按剩余路径段匹配），适用于任意 key 类型；未知缓存的 GET 返回 404，DELETE 返回 204（不做任何事）；空 key 返回 400。
- **`CacheAdmin`**（运维接口，不属于用户 API）：`name`、`metrics`、`localSize`、`pendingInvalidationCount`、`clearLocal()`、`inspect(encodedKey)`、`invalidateEncoded(encodedKey)`。
  - `inspect` 是纯读取，**不触碰命中路径**：L2 用 `getIfPresentQuietly`（不影响淘汰统计），L1 用不带 lease 与刷新令牌的 `read(key, null, null)`（读脚本在参数为空时不写任何状态）；不经 SingleFlight、不取戳、不写任何一层、不计入指标、不回收损坏内容。值用缓存的 `Codec` 解码后以 `toString()` 展示，截断到 256 字符；解码失败、未知或更高格式返回 `UNREADABLE`，L1 读失败返回 `ERROR`。
  - `invalidateEncoded` 走正常的失效路径（§4.2，失败按 `invalidateFailure` 处理），幂等；`SetCache` 上等价于 `clear(key)`（L1 是唯一副本）。
- **健康检查 `cocache`**（`CoCacheHealthIndicator`）：只读内存状态，不访问 Redis。Redis 失效通道最近一次心跳失败（`RedisBackend.heartbeatHealthy`，只读状态）时 `DOWN`，其余为 `UP`；失效重试中不影响状态（重试队列自行收敛，放弃计入 `invalidations.dropped`）。详情：后端类型、格式版本、`atomicBroadcast`、Redis 的通道模式与心跳、每个缓存的失效重试数与本地副本数。
- L1 中的值带二进制头，不能再用 `redis-cli GET` 直接读出 JSON。

## 11. 默认值

| 项 | 默认 | 理由 |
|----|------|------|
| `ttl` | 1 h | 协议外不一致的上界 |
| `ttlJitter` | 1 min（实际 ≤ `ttl / 2`） | 打散批量过期 |
| `absentTtl` | 1 min | 数据新建后最多 1 分钟仍被视为不存在 |
| `localMaxAge` | 5 min | 事件丢失时 L2 陈旧的上界（G1），必须有限 |
| `degradedLocalMaxAge` | 10 s | 未写入 L1 的填充只短暂存在于 L2 |
| `refreshWindow` | 关闭 | |
| `lease.ttl` / `lease.wait` | 10 s / 200 ms | `lease.ttl` 应大于 loader 的 p99；loader 慢于 `lease.wait` 时跨节点去重失效 |
| `load.maxConcurrent` / `acquireTimeout` | 0（不限制）/ 1 s | |
| `load.asyncTimeout` | 30 s | 只作用于异步数据源：丢失回调的 key 在半分钟内恢复，而不是永久合并到一个不会结束的调用 |
| 异步执行器 / 阻塞执行器 | 自建 `ForkJoinPool`（CPU 数）/ JDK 21+ 虚拟线程，否则 ≤ 512 线程不排队 | §9.1 |
| `local.maximumSize` | 10 000 | L2 总是有界；不使用条目级 `Expiry`（每次读取都写节点元数据，热点 key 无法随线程扩展），过期在读取时判断 |
| `invalidateFailure` / `readFailure` | `RETRY` / `DEGRADE` | 失效通常在提交之后调用，抛出既不能也不该回滚 |
| `foreignFormat` | 未设置：有数据源的读取 `RECLAIM`，没有数据源的读取 `FAIL` | 没有数据源时回收就是删除唯一副本（§5.4） |
| `SetCacheSpec` 的 `ttl` | 永不过期 | L1 是唯一副本 |
| `keyPrefix` | `cocache:` | |
| Redis `commandTimeout` / `heartbeat` / `invalidateBatchSize` / `channelMode` | 2 s / 5 s / 512 / `pubsub` | |
| 时钟刻度 | 100 ms | `System.currentTimeMillis()` 在部分平台上不随线程扩展 |

## 12. TCK 与测试约定

`cocache-test` 是已发布的 TCK，`src/main` 下的一切都是第三方实现的 API。

| 你实现 | 继承 |
|--------|------|
| `CoherenceBackend`（L1 协议 + 失效通道） | `BackendProtocolSpec`（状态机、令牌、到期时间重设、release、reclaim、批量读、批量覆盖写入、集合、广播帧与订阅重置） |
| 后端在引擎下的端到端行为 | `EngineSpec`（两个运行时共享一个后端：跨节点单次回源、lease 等待超时、同节点失效与并发读、同节点并发 `put`、读己之写、失效重试、`localMaxAge`、批量、跨节点单次刷新、损坏回收、未知格式、读不懂的内容按 `foreignFormat` 失败或回收、没有数据源时更高格式版本按 `foreignFormat` 失败或按不存在处理、`putAll` 的跨节点失效与在途加载栅栏、条件读写（摘要未变、绕过 L2、负缓存与 lease 是没有值、冲突不广播、并发“读—改—条件写”不丢更新、到期时间、读不懂的内容失败（`RECLAIM` 也不把本协议的集合与解码失败的值当作没有值）、L1 故障；不支持条件写入的后端以 `supportsConditionalWrites = false` 跳过条件写入的用例；支持的必须原子，并发用例强制竞争、断言恰好一个写入方胜出）等）与 `SetEngineSpec`（集合缓存，含格式 0 的 `FAIL` / `RECLAIM`，元素写入落在读不懂的内容上时 `FAIL` 拒绝、`RECLAIM` 覆盖，负缓存上照常写入） |
| 支持版本的后端（`versionedWrites`，§6.5） | `VersionedSpec`（两个运行时共享一个后端。协议：整体写入保存版本、按版本写入只在版本更高时写入、有符号 64 位比较（跨越 2^32 与 2^53）、按版本写入销毁 lease 而持有者的 fill 被拒绝、带版本的内容不授予刷新令牌也不被回收、墓碑读作负缓存、带版本的负缓存经 fill 写成墓碑。引擎：有序、相等版本幂等、旧版本不修改不广播不改寿命、寿命同 `put`、墓碑挡住迟到的旧版本、lease 持有者回源期间落地的写入胜出（回源读到的版本更高也被拒绝）、回源保存版本、数据源返回的带版本的不存在写成墓碑、条目到期之后任何版本都能写入、回源写回 L1 之后落地的更新版本、多线程乱序写入收敛到最高版本、结果未知时重放安全、普通写入与 `null` 回源没有版本、读不懂的内容拒绝、解码失败的带版本值不回收）；`AsyncVersionedSpec` 经异步视图重跑 |
| 多节点交错（§4.3、§5、§7） | `MultiNodeSpec`（三个运行时共享一个后端：失效先于 lease、晚于 lease 且有节点在等待、晚于 fill 而早于写 L2；跨节点写入落在读 L1 与写 L2 之间；跨节点并发 `put`（非原子后端上事件逆序到达）；失效重试期间的未命中；全集群每个 key 单次回源与单次刷新的计数断言） |
| 异步视图（§5.6） | `AsyncEngineSpec`、`AsyncSetEngineSpec`、`AsyncMultiNodeSpec`、`AsyncFaultSpec`：经 `CacheAccess.ASYNC` 把对应规范的每个用例改由 `async()` 视图读写（同步数据源换成在调用线程上执行的异步数据源）重跑；`AsyncEngineSpec` 另有取消、完成线程、lease 等待与回源许可等待只用常数个线程（各 1000 个等待者）、原始异常、不同步抛出、`asyncTimeout` 释放 lease、同步与异步互相合并、重入、同步数据源只在阻塞执行器上执行、运行时关闭时的等待等用例 |
| 原生异步的后端（`AsyncRemoteStore`） | `AsyncBackendProtocolSpec`：`BackendProtocolSpec` 的每个用例经异步方法重跑（不得同步抛出，失败为原始异常），另加同步与异步交替调用的一致性 |
| 故障下的 G1 / G2 | `FaultSpec`（L1 整体失败时的降级限龄、fill 失败与 lease 寿命、重试耗尽与队列溢出的放弃、“已在 L1 生效却报错”的 `put` / `invalidate` 可安全重试；可选：断线期间被拒绝的写入在重连后不被重放，后端覆盖 `disconnect()` 才运行） |

- 异步视图的 TCK 在 `cocache-redis` 上与同步矩阵一样跑 `pubsub`、非原子与 `sharded` 三种后端（`RedisAsyncTckTest`：`AsyncBackendProtocolSpec`、`AsyncEngineSpec`、`AsyncSetEngineSpec`（`pubsub`）、`AsyncMultiNodeSpec`、`AsyncFaultSpec`，另有保留同步数据源的 `CacheAccess.ASYNC_WITH_SYNC_SOURCE`），设置 `SPRING_DATA_REDIS_CLUSTER_NODES` 时同样连接 Cluster。
- `HookedBackend` 包装任意后端以注入故障（`failAll`、`failWrites`、`failFill`、`failRelease`）、改写读取结果、在读取之后、`fill` 之后与写入前后编排竞态（`afterPut`、`afterInvalidate` 抛出即模拟“已生效但报错”），并能覆盖 `atomicBroadcast` 以验证非原子路径。被包装的存储原生异步时，同样的钩子与故障也作用于异步方法（故障以失败的 future 返回）；`ioExecutor` 让异步结果改在指定线程上送达（模拟 I/O 线程）。异步结果尚未完成且设置了钩子时，钩子在 `HookedBackend` 自己的线程上执行（钩子可能阻塞，不能在 L1 客户端的 I/O 线程上执行），`issuer()` 返回发起这次存储调用的线程名，按线程编排的钩子用它代替 `Thread.currentThread().name`。`close()` 只在 `closesDelegate = true` 时关闭被包装的后端（默认 `false`：被包装的后端可能是共享的）。
- 时间：内存后端用 `ManualClock` 推进；Redis 测试用 `RedisTestSupport.shiftTtl` 缩短 key 的剩余寿命来模拟时间流逝。到期时间是毫秒精度，不需要 ±1 s 偏差。
- `cocache-redis` 中 `BackendProtocolSpec`、`EngineSpec`、`MultiNodeSpec` 与 `FaultSpec` 在 `pubsub`、非原子（ACL 禁止探测频道）与 `sharded` 三种后端上各跑一遍，`SetEngineSpec` 只在 `pubsub` 后端上运行；设置 `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` 时连接其他单机实例（CI 的 Valkey 任务），设置 `SPRING_DATA_REDIS_CLUSTER_NODES` 时连接 Redis Cluster。`FaultSpec` 的断线用例只在单机 `pubsub` 上运行（经 `TcpProxy` 断开并拒绝重连）；`NOSCRIPT` 回退（每个操作之前 `SCRIPT FLUSH`）是 `RedisFaultTest` 自己的用例。
- 竞态用 latch 编排，禁止 sleep；等待 `finished` latch，让死掉的后台线程使用例失败；跨节点效果用带超时的轮询断言。
- 每个修复的缺陷都要有复现用例（`EngineSpec` 中的同节点竞态用例是范例）。

`MultiNodeSpec` 的节点是同一进程内共享一个后端的运行时，不覆盖真实的多进程与网络分区。

## 13. 性能验证

命中路径（L2 命中、L1 命中）的改动必须附带 JMH 数据：

```bash
scripts/jmh-compare.sh -b l2Hit,l2HitViaInterface,l2HitMixed -t 1,8   # 与 origin/main 对比；需要 localhost:6379
./gradlew :cocache-redis:jmh --rerun -PjmhThreads=8 -PjmhIncludes=l2Hit   # 单次试跑；check 只编译不运行
```

- 基准：`redis/src/jmh` 的 `CoCacheBenchmark`，方法 `l2Hit`、`l2HitViaInterface`、`l2HitMixed`（轮流访问三个配置不同的缓存）、`l1Read`、`l1SequentialRead`、`l1BatchRead`、`missLoad`、`set`、`invalidate`。`-PjmhIncludes` 是正则，`l2Hit` 同时匹配三个 L2 基准。
- 必须加 `--rerun`：参数不变时 Gradle 判定 `jmh` 任务为最新，静默沿用上次的结果。
- 前后对比用 `scripts/jmh-compare.sh`，它固化了下面的方法，输出可直接贴进 PR 的 Markdown 表格（原始 JSON 保留在输出目录）：
  - **每次只跑一个基准**（精确正则 `^.*\.<name>$`）：同一个 `jmh` 任务里排在后段的基准在 8 线程下会因长时间满载而明显变慢（实测低 20%–30%），单独运行时回到基线（RFC 0004 §14）。
  - **同轮交替、配对比值**：基线在临时 worktree 中构建，每轮两侧各跑一次且先后顺序逐轮轮换；结论看同轮配对比值 new/base 的中位数与 [min–max]，不看两侧各自的均值，因为数值会随轮次整体漂移。
  - **负载门槛与作废**：每次开跑前等待 1 分钟负载降到门槛以下（默认 max(2, 核数 × 0.4)）；迭代 CV 超过 3%，或运行期间负载升高超过 min(线程数, 核数) + 1.5（自身贡献之外还有别的负载），该次运行作废并自动重试。
  - **本机互斥**：全机共享的锁 `/tmp/cocache-jmh.lock`（macOS 的 `TMPDIR` 按用户隔离，不用它）保证同一时间只有一个基准任务（含构建），不经脚本启动的 JMH 进程也会等它结束；多个会话同时跑基准会互相污染。
  - 基线 ref 不会自动 fetch，需要最新基线时先 `git fetch origin`。
- 异步视图的基准：`l2HitAsync`、`l2HitAsyncMixed`、`l1ReadAsync`、`l1HitViaEngine` / `l1HitViaEngineAsync`、`missLoadAsync`、`setAsync`、`invalidateAsync`（闭环，每次操作后等待），以及在途请求基准 `l1HitInFlightAsync` / `setInFlightAsync` 与同步对照 `l1HitSequential` / `setSequential`（每次调用 32 个请求）。
- 1 线程与 8 线程，与上一版本**至少交替运行 5 轮**（脚本默认），比较配对比值的中位数与区间，包含 `l2HitMixed`。同一台机器上 8 线程的结果在多次运行之间波动约 ±4%，差异小于噪声时不作结论。

## 14. 不变量

1. **栅栏规则**：任何填充都必须持有读取之前取得的令牌（L1 lease 或刷新令牌；L2 失效戳）。L1 的失效通过销毁令牌完成；L2 的失效方（含 `put`、元素写入）在修改 L1 **之前和之后**各递增一次戳并淘汰 L2。**L2 只由读 L1 的路径填充**，写入方从不直接写 L2。
2. **L1 未命中就是未命中**：key 不存在或处于 `L` 状态都不代表负缓存；负缓存只有状态 `M` 一种形态。
3. **读路径从不无条件删除**：同版本的损坏只能通过 `reclaim`（比较摘要）转为 lease；更高的未知版本既不回收也不写回；没有数据源的读取默认（`foreignFormat` 未设置）不回收读不懂的内容、也不把更高格式版本当作不存在，而是抛出。
4. **能原子广播就原子广播**：后端以 `atomicBroadcast` 声明能力；为 `false` 时由引擎在写入成功后发布，TCK 按能力分别验证。
5. **SingleFlight 先注销调用、再发布结果**（读己之写依赖这个顺序）；同步与异步调用共用一张表，异步调用在注册之后取戳；每个异步调用方拿到自己的 future；同步调用合并到异步调用时只阻塞自己的线程，异步调用合并到同步调用时经异步执行器拿到结果。同线程对同一 key 的重入（同步调用的拥有线程，或正在为该 key 调用数据源的线程）快速失败；批量合并先注册全部 key、先完成自己负责的 key 再等待别人。
6. **同步 API 上 loader 在调用方线程上执行**；异步视图上异步数据源在调用方线程或异步执行器上调用，同步数据源只在阻塞执行器上执行，从不在异步执行器上执行。Future 只包装 I/O，L1 与数据源的 future 完成后先切换到异步执行器（或在已完成时就地）再执行续接，**事件循环线程上不执行续接**。唯一的例外是存储内部、不含用户代码的结果转换：Redis 后端在 Lettuce 的 I/O 线程上把脚本返回值转换为 `RemoteRead`（解析 36 字节头部并拷贝出载荷、集合成员），不调用 `Codec`、不调用任何用户代码；代价是大值（或大集合）的这一次拷贝在 I/O 线程上进行，会推迟同一连接上其它命令的完成（与同一连接上大载荷本身的队头阻塞同量级，§8）。
7. **令牌必须释放或过期**：loader、`ttlOf`、`Codec` 失败，回源被拒绝或超时，以及刷新令牌用不上时都要 `release`。
8. **每次 L1 状态转换都显式重设到期时间。**
9. **线格式在一个大版本内冻结**：key 布局、标量头、集合字段、消息帧、频道命名、摘要算法。格式演进通过版本号两阶段完成。
10. **集合元素增删只用于 L1 是唯一副本的 `SetCache`**，在 L1 中原子完成，且 `SetCache` 不得有 loader。默认（`foreignFormat` 未设置）不覆盖读不懂的内容：检查与写入在同一个原子操作中完成。数据源中的集合只能用 `invalidate` 维护。
11. **有状态组件只能由运行时创建**：用户只提供 spec 和无状态函数。
12. **CoCache 的 L1 连接只访问主节点**，断线时拒绝命令而不是缓冲重放。
13. **存储脚本只执行协议，不持有策略**：TTL、刷新窗口、令牌都由引擎作为参数传入。
14. **代理不包装异常**：接口式声明的代理按方法名直接调用引擎，异常原样抛出；spec bean 注入的就是引擎本身。
15. **命中路径的改动必须附带数据**：1 线程与 8 线程，至少 5 轮交替运行，报告中位数与置信区间，并包含配置不同的多个缓存（`l2HitMixed`）。
16. **starter 注册的缓存 bean 必须能由 AOT 生成代码重建**：只用静态工厂方法与可序列化的构造参数，不用实例提供者，不依赖 bean 定义属性（§9.6）。
17. **取消不传播**：取消调用方的 future 不取消共享加载、不取消数据源的 future、不跳过写入方的第二次递增（§5.6）。
18. **异步视图不挂起线程、不同步抛出**：等待 lease 与回源许可都不占线程；失败以原始异常（不包装为 `CompletionException`）完成返回的 future（§5.6）。
19. **条件读写只确认 L1**：`getIfChanged` 不读写 L2、不回源、不申请令牌、不修改 L1；`putIfMatch` 的比较与写入在一个原子操作中完成，冲突与拒绝不修改 L1、不广播，写入方顺序同 `put`；后端不能原子地完成时不支持它（不提供“先读后写”）。“没有值”是不存在、lease 占位与负缓存；读与写对读不懂的内容分类相同，只有格式 0 的内容能在 `RECLAIM` 下视为没有值；摘要只比较本协议标量值的载荷（§6.4）。
20. **按版本有序的写入**：比较与写入在一个原子操作中完成，版本不更新时不修改 L1、不广播、不改变到期时间；没有版本的内容排在任何版本之前；写入销毁令牌，持有令牌的填充永远写不回（版本更高也不行）；带版本的内容不持有令牌、不授予刷新令牌、不被回收；版本存放在格式 1 的头部，6.x 的读者照常读取（§3.3、§6.5）。后端不能原子地完成时不支持它，声明为带版本的缓存在创建时失败。
21. **副本出生时不在刷新窗口内**：L2 副本的刷新点要么晚于创建时刻，要么不设。没取得刷新令牌的重新填充把刷新点退避到 L1 到期之前，其余情形下 L1 剩余寿命不长于窗口时不设刷新点（§5.5）。否则每次命中都会触发刷新：没有令牌的节点按命中频率读 L1，持有令牌的节点按命中频率回源。
