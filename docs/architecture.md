# CoCache 架构与不变量（6.0）

本文是 CoCache 的**唯一架构事实来源**：设计目标、模块边界、协议、失败语义与必须保持的不变量。修改一致性、存储、编解码、代理或 Spring 装配相关代码前先读本文；行为变化时在同一个 PR 中更新本文。

设计推导、被否决的方案与对抗验证记录见 [RFC 0003](rfc/0003-target-architecture.md)。本文与 RFC 不一致时以代码为准，并修正本文；SPI 与线格式的定义以 `me.ahoo.cocache.api.spi` 与 `me.ahoo.cocache.redis.RedisScripts` 为准。

## 1. 目标

二级一致性缓存的本质是“用陈旧度换延迟”。缓存只做三件事：**复制**（填充）、**作废**（失效）、**限龄**（TTL）。所有正确性问题都出在一次开始得早的复制在作废之后才落地。

| # | 目标 | 可度量的表述 |
|---|------|--------------|
| G1 | 陈旧度有上界 | **L1**：协议内竞态造成的陈旧为 0；协议外（绕过缓存的写入、故障切换丢写、loader 读到从库旧数据）≤ `ttl`。**L2**：事件送达时 ≤ 事件延迟；事件丢失时 ≤ `min(localMaxAge, 心跳周期 + 重连时长)`；失效写 L1 失败且重试放弃（或运行时关闭时仍在排队）时 ≤ L1 剩余寿命（各节点 L2 到期后会从仍是旧值的 L1 重新填充；`ttl = null` 时没有上界，计入 `invalidations.dropped` 并告警，需再次失效）；降级填充 ≤ `degradedLocalMaxAge` |
| G2 | 失效不被吞没 | **L1**：读取开始于某次失效之前的填充，永远不会在该失效之后出现在 L1。**L2**：某节点处理完一次失效（本地调用返回、处理了远端事件或执行了重置）之后，该节点 L2 不会再出现读取开始于这次失效之前的填充 |
| G3 | 命中路径便宜 | L2 命中只是一次 Caffeine 查找加一次过期比较，随线程扩展；L1 命中一次往返；单 key 的 `put` / `invalidate` 默认（原子广播）各一次往返，`sharded` 或非原子模式下各两次往返；loader 耗时小于 `lease.wait` 时，全集群同一 key 并发未命中只回源一次 |

非目标：线性一致读。异步 API 是同一个引擎上的视图（§5.6，[RFC 0004](rfc/0004-async-api.md)），不是另一套协议；阻塞栈上同步 API 配合虚拟线程仍是首选。`SetCache` 把 L1 当作唯一副本（§6.3），是有意的例外。

TTL 是安全网，不是机制：它兜底协议之外的问题。协议之内的竞态由栅栏（§4）消除，不靠 TTL 收敛，所以 `ttl = FOREVER` 在协议内是安全的。

## 2. 模块与边界

```
cocache-api                    用户 API + 全部 SPI，零运行时依赖
  Cache / SetCache / Lookup / CacheLoader / Codec / CacheClock
  AsyncCache / AsyncSetCache / AsyncCacheLoader（异步视图与异步数据源，§5.6）
  CacheReadException / CacheWriteException
  annotation：@CoCache、@CaffeineCache、@JoinCacheable
  join：JoinCache、JoinValue、JoinKeyExtractor（两个缓存的只读组合视图）
  spi：CoherenceBackend、RemoteStore、InvalidationChannel、InvalidationListener、Subscription、
       Envelope、RemoteRead、LeaseRequest、RefreshRequest、Fill、Token、NodeId、Digest、InvalidationFrame、
       AsyncRemoteStore、AsyncInvalidationChannel（可选的原生异步子接口）
cocache-core                   引擎与运行时；依赖 Caffeine、kotlin-logging
  CacheRuntime、CacheSpec / SetCacheSpec（KeyEncoder、TtlFunction、KeyFilter）、CacheAdmin、CacheMetrics、RetryPolicy、ExternalInvalidator、
  LoadObserver、SourceLoadRejectedException、SourceLoadTimeoutException
  engine：AbstractEngine、CoherentEngine、SetEngine、InvalidationRetrier、AsyncContext、LoadPermits、BlockingAsyncStore
  internal：SingleFlight、InvalidationStamps、Futures
  policy：CachePolicy、LeasePolicy、LoadPolicy、LocalPolicy、InvalidateFailureMode、ReadFailureMode
  proxy：CacheProxies、JoinCacheProxies；join：SimpleJoinCache；memory：InMemoryBackend；clock：TickingClock、ManualClock；codec：StringCodec
cocache-redis                  Lettuce 原生后端，不依赖 Spring
  RedisBackend、RedisBackendOptions、RedisChannelMode、RedisScripts、RedisRemoteStore、
  PubSubInvalidationChannel、ShardedInvalidationChannel
cocache-codec-jackson          Jackson 3（tools.jackson）Codec：JacksonCodec、jacksonCodec<V>()
cocache-spring                 Spring 适配（不依赖 Boot）：CoCacheSpringCacheManager、CoCacheSpringCache、TransactionalInvalidator
cocache-spring-boot-starter    自动配置、@EnableCoCache（caches、joinCaches）、注解翻译、spec bean 注册、配置分层、actuator 端点与健康检查、Micrometer、Spring Cache sync 检查
cocache-test                   已发布的 TCK：BackendProtocolSpec、EngineSpec、SetEngineSpec、MultiNodeSpec、FaultSpec 及其 Async* 版本、CacheAccess、HookedBackend
cocache-bom / cocache-dependencies / cocache-example
```

依赖方向严格单向：`api ← core ← redis`，`api ← codec-jackson`，`core ← spring`，`core ← test`；starter 依赖 `spring`、`redis`、`codec-jackson`。core 不依赖 `spring-expression` 与 `kotlin-reflect`：SpEL 与注解解析只在 starter 中出现。新后端只需依赖 `cocache-api`（实现 SPI）与 `cocache-test`（通过 TCK）。

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

**集合（`SetCache`）：Hash**。字段 `!f` 为格式版本（`"1"`），成员字段为 `+` + 成员字节，值为空串。Hash 只表示“有成员的集合”；lease 与负缓存总是 String 形状。任何整体写入都先 `DEL` 再按新形状写入。

- **令牌**（`Token`，24 B）= `NodeId`（16 B，每个运行时随机生成）+ 进程内单调序号（8 B）。由客户端构造，全局唯一、永不重复签发，所以被销毁的令牌不可能再次匹配（无 ABA）。
- **到期时间**：一律 `PEXPIRE` / `PTTL`，毫秒，以相对时长传输，不受节点间时钟偏差影响。参数钳到至少 1 ms；`0` 表示永不过期。**每次状态转换都显式重设到期时间**，lease 的寿命和负缓存的寿命不得泄漏到新值上。
- **读取成本**：标量 `PTTL` + `GET`（一次 EVALSHA）；集合 `PTTL` + `HGETALL`。
- **无法识别的内容**（不以 magic 开头的 String、未知状态、没有 `!f` 的 Hash、其它 Redis 类型如原生 Set；其它程序或其它大版本写入的内容都属此类）以格式版本 `0` 返回，引擎按损坏处理（§5.4）。String 与 Hash 以外的类型以空载荷返回。
- **摘要**（`Digest`，供条件回收）：标量取载荷（无法识别的 String 取整个值，String 与 Hash 以外的类型取空串），负缓存取空串，集合按 L1 返回顺序拼接 `int32 长度 + 成员`，SHA-1。只在解码失败时计算。

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
```

每个转换都由一个 Lua 脚本原子完成（`RedisScripts`：`READ`、`FILL`、`RELEASE`、`PUT`、`PUT_ALL`、`INVALIDATE`、`RECLAIM`、`ADD_MEMBERS`、`REMOVE_MEMBERS`）。脚本**不带 shebang 标志**：带标志的脚本在 OOM 时被拒绝，而失效恰恰释放内存。写脚本含 `PUBLISH`，不能用 `EVAL_RO`。

- **lease**：读到不存在的 key 且请求了 lease 时，写入 `L` 占位，`PX = lease.ttl`，返回 `Miss(leaseGranted = true)`。读到 `L` 返回 `Leased(remaining)`。节点崩溃留下的 lease 由 `lease.ttl` 回收。
- **刷新令牌**：读到 `V`，且剩余寿命 `0 < pttl ≤ refreshWindow`，且头部令牌为零或签发超过 `lease.ttl` 时，用 `SETRANGE` 写入调用方令牌与服务端时间（保留原 TTL），返回 `refreshGranted = true`。签发过期的令牌可被抢占，被抢占者的 `fill` 因令牌不匹配失败。
- **fill**：仅当 key 是本协议的 String 且头部令牌等于 `Fill.token`（lease 或刷新令牌）时整体写入并重设 TTL。
- **release**：令牌匹配时，`L` 状态删除 key，`V` 状态只清零令牌。
- **reclaim**：仅当当前内容的摘要等于读到的那份、且不是 `L` 状态时，原子地转为 lease。

### 4.2 写入方顺序（`AbstractEngine.write`）

| 操作 | 顺序 |
|------|------|
| `invalidate` / `invalidateAll` | 戳 → 淘汰 L2 → Lua `DEL` + `PUBLISH` → 戳（无论成败）→ 淘汰 L2 |
| `put` | 戳 → 淘汰 L2 → Lua 覆盖 + `PUBLISH` → 戳（无论成败）→ 淘汰 L2。**不写 L2** |
| `putAll` | 同 `put`，一次 `write` 覆盖整批 key：全部 key 戳 → 淘汰 L2 → 批量覆盖 + `PUBLISH` → 全部 key 戳（无论成败）→ 淘汰 L2 |
| `SetCache.add` / `remove` / `clear` / `put` / `putAll` | 同上 |
| 收到远端事件 | 戳 → 淘汰 L2（`origin == 本节点 NodeId` 的事件被过滤） |
| `onReset` / `clearLocal` | 全部戳递增 → 清空 L2 |

后端 `atomicBroadcast = false` 时，引擎在存储操作成功后调用 `channel.publish`；发布失败按写入失败处理（§7）。

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

- **L2 副本的到期时间** = `min(now + L1 剩余寿命, now + maxAge)`；`maxAge` 是 `localMaxAge` 或 `degradedLocalMaxAge`。`refreshAt` = L1 到期时间 − `refreshWindow`（只对有限寿命的值）。时钟是 `TickingClock`（100 ms 刷新一次的 volatile 值），命中判断是一次 volatile 读加一次比较。
- **寿命**：值的寿命 = `ttlOf(key, value)`（非 null 时优先，不加抖动）否则 `ttl ± 抖动`（实际幅度 ≤ `ttl / 2`，至少 1 ms）；`ttl = null` 为永不过期。负缓存总是 `absentTtl`（不抖动）。所有寿命钳到 `[1 ms, 100 年]`。
- **线程模型**：同步 API 上 loader 总是在调用方（SingleFlight leader）线程上同步执行，事务、SecurityContext、MDC 等 ThreadLocal 可用。Lettuce 的 Future 只包装 L1 I/O，引擎在调用方线程上等待，不在事件循环线程上执行任何续接。异步视图的线程模型见 §5.6。
- **L1 未命中就是未命中**：key 不存在、处于 `L` 状态都不是负缓存；负缓存只有状态 `M`。

### 5.1 没有数据源的缓存

spec 没有 `loader` 时，未命中释放 lease，不向 L1 写负缓存（否则会挡住其它节点 `put` 之后的读取），只在 L2 缓存一个“未决的不存在”（`absentTtl`，受 `localMaxAge` 限龄）。`get(key, loader)` 不接受未决条目，会用调用方的 loader 回源；合并到不带数据源的在途加载时同样自己回源。

### 5.2 `get(key, loader)`

与 `get` 走同一条受 lease 与失效戳保护的路径，只是用调用方的 loader 代替 spec 中的数据源。供 Spring `@Cacheable(sync = true)` 使用。

### 5.3 批量读 `getAll`

- 逐个 key 查 L2（进入刷新窗口时同样触发后台刷新）、过 `KeyFilter`；剩余 key 按存储 key 去重后交给 `flights.executeAll`。
- 已在途的 key 等待现有调用；其余 key 由本线程作为 leader，**先完成自己负责的整批，再等待别人的**。
- leader 的一批：每个 key 注册之后取戳 → 一次 `readAll`（为每个 key 申请 lease；失效重试中的 key 不读 L1）→ 一次 `loader.loadAll` → 一次 `fillAll`。
- 批量路径**不等待**其它节点的 lease：`Leased` 与未知格式的 key 直接回源且不写 L1（L2 降级限龄）。格式 0 或解码失败的 key 与单 key 路径一样条件回收（见 §5.4），再凭新 lease 回源与 `fill`。
- 共享到的结果若开始于本次读取已观察到的失效之前，该 key 改走单 key 路径。
- Redis：每个 key 一条 EVALSHA，在共享连接上并发发出（约一次往返）；Cluster 按 key 路由，不会 CROSSSLOT。

### 5.4 损坏与格式演进

- **格式版本高于本节点**（滚动升级期间）：计入 `unknownFormats`，回源但**不回收、不写 L1**，L2 降级限龄。否则新旧节点互相覆盖。
- **格式 0 或同版本解码失败**：计入 `reclaims`，计算本次读到内容的摘要，调用 `reclaim`；成功则凭新 lease 正常回源与 `fill`，失败（内容已变或 L1 异常）则不写 L1 回源。每次读取至多回收一次。
- 读路径**从不无条件删除**。
- **`SetCache`**（L1 是唯一副本，没有数据源可回源）：格式高于本节点时计入 `unknownFormats` 并抛出，不写 L2；不是本协议写入的内容（格式 0，如原生 Set）计入 `unknownFormats`、按不存在处理（读到空集合）并以降级限龄进入 L2，不回收、不删除，下一次 `put` / `add` 整体覆盖；同版本内容的元素解码失败时读取直接抛出，不计为命中。

### 5.5 提前刷新

- `refreshWindow` 非 null、spec 有 loader、L2 副本到达 `refreshAt` 时，命中照常返回当前值，并把一次刷新提交到刷新执行器。每个节点每个 key 同一时刻至多一次（`refreshing` 集合），刷新经 SingleFlight 与并发未命中合并。
- 刷新先 `read(k, refresh = RefreshRequest(window, 新令牌, lease.ttl))`：取得令牌的节点回源并 `fill(r)`；没取得的节点只从 L1 重新填充 L2；L1 已无值时退回普通 `loadThrough`。
- 结果：全集群每个 key 一次刷新。`invalidate`、`put`、元素写入都会销毁刷新令牌。
- 执行器拒绝时计入 `refreshRejected` 并跳过；刷新失败只告警，值照常到期，G1 不变。默认执行器是有界守护线程池（≤ 4 线程，队列 1024，满时拒绝）。

### 5.6 异步视图（RFC 0004）

异步视图与同步 API 是同一个引擎：共享 L2、失效戳、SingleFlight、lease、回源许可与指标；每个协议分支在两条路径上一一对应，协议决策（读到什么就做什么：`hitOutcome`、`completeLoad`、`batchStep`、`refreshStep`、`putsOf` 等）由两条路径共用的函数给出，两条路径只在“如何等待”上不同。同步命中路径的代码不变。

- **命中**：与同步路径相同的 L2 查找与过期比较，返回已完成的 future（在调用方线程上）。
- **续接**（`Futures.handleNext` / `thenNext`）：上一步已完成时就地执行（结果已同步可得，没有 I/O 线程参与，例如内存后端），否则只在完成线程上把续接提交给**异步执行器**，续接本身从不在 L1 客户端的 I/O 线程上执行。因此返回的 future 在调用方线程或异步执行器上完成（调用方注册的依赖阶段按 JDK 规则也可能在随后调用 `get()` / `join()` 的线程上执行，TCK 断言完成线程时先等依赖阶段执行完再等待结果）；`AsyncCacheLoader.loadAsync` 同样在调用方线程或异步执行器上被调用。
- **数据源适配**：`asyncLoader` 在当前线程上调用，取其结果的副本再加 `LoadPolicy.asyncTimeout`（默认 30 s，由 JDK 的延迟线程以 `completeOnTimeout` 的私有哨兵值计时，不经 `cocache-timer`；数据源自己以 `TimeoutException` 失败时原样传递），超时以 `SourceLoadTimeoutException` 失败、计入 `loadTimeouts` 并释放 lease 与回源许可，数据源的 future 不被取消、迟到的结果被丢弃；同步 `loader` 在异步路径上**只在阻塞执行器上执行**，从不在异步执行器上执行，不加超时。同步 API 遇到 `asyncLoader` 时在调用方线程上调用 `loadAsync` 并等待（同样受 `asyncTimeout` 约束，异常解包）。
- **不挂起线程**：等待其它节点的 lease 经定时器调度重读（退避、截止时间、令牌与戳都与同步路径相同）；等不到回源许可时登记为等待者，许可释放时按登记顺序被唤醒，`acquireTimeout` 后被拒绝。等待期间不占任何线程。
- **SingleFlight**：同步与异步调用共用一张表。异步 leader 在注册之后、在调用方线程上开始（第一件事是取戳），其 future 完成时先注销、再发布；读己之写至多重试一次，与同步路径相同。同步调用合并到异步调用时只阻塞自己的线程；异步调用合并到同步调用时，结果经异步执行器送达（否则用户的续接会跑在同步 leader 的线程上）。每个调用方拿到自己的 future：取消只影响自己，不取消共享加载、不取消数据源的 future。
- **重入**：除同步调用的拥有线程外，引擎在调用数据源期间（同步数据源的整个执行、异步数据源 `loadAsync` 的同步部分）为这些 key 设置线程守卫，期间本线程对同一 key 的同步或异步调用快速失败。异步数据源在一次 I/O 之后再读同一 key 形成的环无法检测，由 `asyncTimeout` 打破。
- **写入**（`AbstractEngine.writeAsync`）：顺序同 §4.2；编码在第一次递增之前、在调用方线程上完成；第二次递增与淘汰挂在 L1 的 future 上（无论成败），在返回的 future 完成之前执行，取消返回的 future 不会跳过它。非原子广播的后端经 `AsyncInvalidationChannel.publishAsync` 发布。
- **提前刷新**：同步数据源照旧在刷新执行器上执行；异步数据源的刷新在刷新执行器上发起、以异步路径推进，不在刷新执行器的线程上等待。
- **执行器拒绝任务**：续接被拒绝时该步不执行，返回的 future 以 `RejectedExecutionException` 失败；必须执行的收尾不依赖执行器：写入方的第二次递增与淘汰（只涉及戳与 Caffeine）在 L1 future 的完成线程上就地执行，回源许可在加载链结束时恰好归还一次，取得的 lease / 刷新令牌在链因拒绝失败时尽力释放（令牌不匹配时 release 什么也不做，重复释放无害）。
- **`KeyFilter`** 在调用方线程上调用，必须是本地、不阻塞的判断。
- **异常**：返回的 future 以原始异常完成（不包装为 `CompletionException`）；异步方法从不同步抛出（包括 `KeyEncoder`、`Codec` 抛出、重入与 `SetCache` 的不支持操作）。
- **只实现同步 SPI 的后端**：引擎经 `BlockingAsyncStore` / `BlockingAsyncChannel` 把同步调用放到阻塞执行器上；原生异步的后端实现 `AsyncRemoteStore`（与 `AsyncInvalidationChannel`），内存后端是原生异步的（就地执行、返回已完成的 future）。

## 6. 写路径

| 操作 | L1 | 往返 |
|------|----|------|
| `put` | Lua：`DEL` + 按形状写入 + `PEXPIRE` + `PUBLISH` | 1 |
| `putAll(N)` | `RemoteStore.putAll`：分组方式同 `invalidateAll`，每组一个 `PUT_ALL` 脚本（每个 key 的转换与 `PUT` 相同）与一次 `PUBLISH`（只含本组的 key），并发执行；原子性只在单个组内成立 | 单机 ⌈N/512⌉，Cluster 约 N |
| `invalidate` | Lua：`DEL` + `PUBLISH` | 1 |
| `invalidateAll(N)` | 单机：按 `invalidateBatchSize`（默认 512）切分，每批一个脚本。Cluster：按 slot 分组再切分，每组一个脚本与一次 `PUBLISH`，并发执行；原子性只在单个 slot 内成立 | 单机 ⌈N/512⌉，Cluster 约 N |
| `add` / `remove` | Lua：成员写入 + 重设 TTL + 销毁令牌 + `PUBLISH` | 1 |

- `put` 的寿命：`ttlOf ?: 策略寿命`；`put(key, value, ttl)` 用给定时长。都钳到 `[1 ms, 100 年]`。
- `putAll` 每个 key 的寿命按 `put` 逐个计算（各自独立抖动）；`putAll(entries, ttl)` 用同一时长。空 map 不做任何事；编码后碰撞的存储 key 以后出现的为准，每个存储 key 只编码一次。失败抛出 `CacheWriteException`（`operation = "putAll"`），部分 key 可能已生效。非原子广播时引擎在批量写入成功后发布一帧（含全部 key）。`RemoteStore.putAll` 的默认实现逐个调用 `put`，帧按 key 拆分（每个 key 只广播自己；帧无法拆分时原样使用）。
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
| `add` | key 不是 Hash（不存在、负缓存、lease、标量）时先 `DEL` 再创建只含这些成员的集合；是 Hash 时原子加入。重设 TTL（永不过期时 `PERSIST`），`PUBLISH` |
| `remove` | 只在 key 是 Hash 时生效；移空则删除 key，否则重设 TTL。`PUBLISH` |
| 并发顺序 | 以 L1 的串行化顺序为准 |
| 读取 | 不取 lease；未命中返回空集合，L2 以戳保护缓存这个结果（`localMaxAge`）。L1 读失败直接抛出原始异常（没有可降级的数据源） |
| `put(key, set)` | 整体覆盖；空集合等价于 `clear` |
| `putAll(entries)` | 一次 `write` 覆盖整批 key：非空集合经 `RemoteStore.putAll` 整体覆盖，空集合经 `invalidate` 删除；两类都有时各自只广播自己的 key |
| `invalidate` / `invalidateAll` | 拒绝（`UnsupportedOperationException`），会销毁唯一副本；删除用 `clear(key)` |
| 提前刷新 | 不适用 |
| 默认 TTL | 永不过期（`SetCacheSpec.DEFAULT_POLICY`） |

前提（写在用户文档中）：Redis `maxmemory-policy` 不能是 `allkeys-*`；数据持久性等于 Redis 的持久性（异步复制在故障切换时可能丢失最近的写入）。启动时不检查这两点。

存储代价：与原生 Set 相比，非整数成员每个多约 16 B，整数成员失去 intset 编码。

## 7. 失败语义

| 类别 | 操作 | L1 故障时 |
|------|------|-----------|
| 填充 | 读、`fill`、`reclaim`、刷新 | 默认 `readFailure = DEGRADE`：按未命中处理，回源结果只进 L2 并以 `degradedLocalMaxAge` 限龄，计入 `remoteFailures` / `degradedFills`。`readFailure = THROW`：L1 读失败抛出 `CacheReadException`；`fill`、`release` 等尽力而为的操作仍然降级 |
| 失效 | `invalidate`、`invalidateAll` | 本地效果（两次递增戳、淘汰 L2）先生效。默认 `invalidateFailure = RETRY`：登记为“重试中”并交给有界重试队列（`RetryPolicy`：最多 8 次，退避 100 ms 起翻倍至 10 s，容量 10 000），**不抛出**。重试期间该 key 的未命中与刷新跳过 L1 直接回源（L2 降级限龄）；重试成功后本节点再递增一次戳并淘汰 L2。放弃（耗尽、溢出、运行时关闭）时记录错误、计入 `invalidationsDropped`，其它节点的陈旧上界为 L1 剩余寿命（L2 到期后从旧的 L1 重新填充）。`invalidateFailure = THROW` 时抛出 `CacheWriteException` |
| 显式写入 | `put`、`putAll`、`add`、`remove`、`clear` | 本地效果先生效，然后抛出 `CacheWriteException`。结果未知（超时）时 L1 可能已生效；这些操作幂等，可以安全重试 |

- `SetCache` 的读取失败总是抛出原始异常。
- **回源并发上限**（`LoadPolicy`，bulkhead）：只计 SingleFlight leader，批量回源占一个许可；在 `acquireTimeout` 内拿不到许可时抛出 `SourceLoadRejectedException`（被拒绝的回源会释放 lease）。L1 故障时由它保护数据源。
- 同线程对同一 key 的重入（loader 内再读同一个 key）快速失败（`IllegalStateException`）。
- loader 的异常原样抛给调用方与所有合并的等待者。
- **异步视图**：上表的语义不变，异常改为完成返回的 future（原始异常，不同步抛出）。此外：异步数据源超过 `asyncTimeout` 时以 `SourceLoadTimeoutException` 失败（释放 lease）；异步执行器拒绝任务时以 `RejectedExecutionException` 失败；运行时关闭时尚未触发的定时等待（lease 重读、回源许可）以 `IllegalStateException` 失败，不降级回源。

## 8. Redis 后端（`cocache-redis`）

- **客户端**：`RedisBackend.standalone(uri, options)`（单机或 Sentinel URI）、`RedisBackend.cluster(uris, options)`（自适应拓扑刷新）由后端自建 Lettuce 客户端；`RedisBackend.of(client, options)` 使用调用方的客户端，选项与生命周期由调用方负责。只支持 Lettuce；不支持 Jedis；**不支持静态主从（master-replica）拓扑**。
- **断线行为**：自建客户端设置 `DisconnectedBehavior.REJECT_COMMANDS` 与命令超时（`commandTimeout`，默认 2 s）。Lettuce 默认会缓冲断线期间的命令并在重连后重放，一个已经向调用方报错的 `put` 可能稍后执行并覆盖更新的值，所以必须拒绝。
- **只访问主节点**：读脚本会写 lease 与刷新令牌，不能在副本上执行。CoCache 自己的连接不继承应用的从库读取配置。
- **命令连接**：每个后端一条共享连接（Cluster 由 Lettuce 按节点管理），并发的异步命令在其上多路复用。大载荷会造成队头阻塞。
- **脚本缓存**：每条命令 `EVALSHA`，遇到 `NOSCRIPT`（故障切换、重启、resharding 后）改用 `EVAL` 重试。
- **原生异步**（RFC 0004 §4.3）：`RedisRemoteStore` 实现 `AsyncRemoteStore`，两种失效通道实现 `AsyncInvalidationChannel`。每个操作只构造一次命令，同步方法等待它，异步方法直接返回它（不占任何线程），同步与异步只有一份协议实现。异步结果的超时与异常类型与同步等待一致（`RedisConnections.bounded`）：超过 `commandTimeout` 时取消命令并以 `RedisException("Command timed out …")` 失败，执行异常解包为原始的运行时异常；超时作用于自己的副本（命令完成时 JDK 撤销超时任务）。`readAllAsync` 等全部读取完成再判定，任一失败时先尽力释放其余读取取得的 lease；`putAllAsync` / `invalidateAsync` 以按发出顺序的第一个异常失败。异步结果在 Lettuce 的 I/O 线程上完成，引擎切换到异步执行器（§5.6）。
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
| `cocacheRuntime`（`CacheRuntime`） | 存在 `CoherenceBackend` bean。有唯一的 `CacheClock` bean 时运行时使用它（内存后端同样使用它，测试推进 `ManualClock` 即可让两层一起到期）。`cocache.refresh-executor` 指定提前刷新执行器的 bean 名，未设置时运行时自建并管理内置执行器 |
| `cocacheAssembler`（`CacheAssembler`）、`cocacheAnnotatedCacheFactory` | 运行时存在 |
| `cocacheSpecRegistrar`（`CacheSpecRegistrar`） | `cocache.enabled` 未关闭（静态 `@Bean`，`BeanFactoryPostProcessor` 需在其它 bean 之前注册） |
| `cocacheRemovedPropertiesCheck`（`BeanFactoryPostProcessor`） | `cocache.enabled` 未关闭（静态 `@Bean`）。在容器后处理阶段、任何缓存创建之前检查已删除的配置项（§9.4） |
| `cocacheCacheManager`（`CoCacheSpringCacheManager`） | 没有其它 `CacheManager`；有 spring-tx 时包装为 `TransactionAwareCacheManagerProxy`。`cocache.spring-cache.retrieve-executor` 指定 retrieve 执行器的 bean 名，未设置时使用内置执行器（§9.5） |
| `cocacheTransactionalInvalidator` | 有 spring-tx |
| `cocacheSpringCacheSyncCheck`（`SpringCacheSyncCheck`） | 运行时存在（§9.5） |
| `cocacheEndpoint`（`CoCacheEndpoint`） | 有 actuator，运行时存在，且端点可用（`management.endpoints.web.exposure.include` 等暴露配置包含 `cocache`、访问未被关闭） |
| `cocacheHealthIndicator`（`CoCacheHealthIndicator`） | 有 `spring-boot-health`（actuator 自带），`management.health.cocache.enabled` 未关闭 |
| `cocacheMeterBinder`（`CoCacheMeterBinder`） | 有 Micrometer，运行时存在 |
| `cocacheMeterBindingPostProcessor`（`BeanPostProcessor`） | 有 Micrometer（静态 `@Bean`）。延迟创建的缓存 bean 初始化后补绑指标 |

执行器配置项（`cocache.refresh-executor`、`cocache.spring-cache.retrieve-executor`）只按 bean 名解析，不按类型：与 L2、L1、`KeyConverter` 等有状态组件同一规则，避免按类型匹配时意外共用或选错 bean。名称没有对应的 bean 或 bean 不是 `Executor` 时启动失败，错误信息指明配置项。这些执行器由容器管理，`CacheRuntime.close()` 不会关闭它们（只关闭自己创建的）。Spring Boot 的 `applicationTaskExecutor` 可以直接使用；不会因为开启虚拟线程而自动使用它，需要显式指定。

`cocache.enabled=false` 关闭全部自动配置。测试或单实例部署可以自行声明 `CoherenceBackend` bean（如 `InMemoryBackend`），Redis 后端随之退让。

### 9.3 两种声明方式，同一个引擎

- **接口式**：`@CoCache interface UserCache : Cache<String, User>`，由 `@EnableCoCache(caches = [UserCache::class])` 注册。启动时翻译为 spec，经 `CacheAssembler` 创建引擎，再由 `CacheProxies` 包上 JDK 代理。
  - bean 名 = 缓存名（`@CoCache.name`，默认接口简单类名），类型为接口本身；与已有 bean 重名时启动失败。
  - 泛型取自接口继承的 `Cache<K, V>` / `SetCache<K, E>`，必须是具体类型。
  - 数据源：`@CoCache(loader = "beanName")`，否则按 `CacheLoader<K, V>` 唯一匹配（多个候选时启动失败），找不到则没有数据源。
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
- 策略字段：`ttl`、`ttl-forever`、`ttl-jitter`、`absent-ttl`、`local-max-age`、`degraded-local-max-age`、`refresh-window`、`lease-ttl`、`lease-wait`、`load-max-concurrent`、`load-acquire-timeout`、`local-maximum-size`、`invalidate-failure`、`read-failure`。策略时长不写单位时按**秒**解析，也可以写 `500ms`、`2m`、`PT2M`；`refresh-window: 0` 表示关闭（可以关掉声明中的提前刷新）。`cocache.defaults.ttl` / `ttl-forever` 不作用于 `SetCache`（L1 是唯一副本，全局寿命会让集合过期丢失），`cocache.defaults.absent-ttl` / `refresh-window` 同样跳过 `SetCache`；只有 `cocache.caches.[name].ttl` / `ttl-forever` 能改变集合缓存的寿命。`cocache.redis.*`（`command-timeout`、`heartbeat`、`invalidate-batch-size`、`client-name`、`channel-mode`）的时长不写单位时按毫秒解析。
- 配置文件中的 `ttl` 不影响 `ttlOf`。
- 启动失败的情形：`cocache.caches` 中出现未声明的缓存名（所有单例创建后由 `CacheAssembler` 校验；缓存名不区分大小写匹配）；使用了已删除的配置项（`RemovedProperties`；错误信息给出替代项，如果有的话，例如 `cocache.redis.strict-failure` → `read-failure: throw`；`missing-guard-sentinel`、`client-side-hits` 没有替代项）；策略不合法。
- 策略约束（`CachePolicy` 构造时校验）：所有时长为正且 ≤ 100 年（`ttlJitter`、`lease.wait`、`load.acquireTimeout` 可以为 0）；抖动实际幅度不超过 `ttl / 2`；`refreshWindow < ttl − 实际抖动` 且 `refreshWindow < localMaxAge`，否则新副本一出生就在窗口内。

### 9.5 Spring Cache 与事务

- `CoCacheSpringCacheManager` 只暴露运行时中已创建的缓存，不按名称自动创建。
- 负缓存视为未命中（否则 `@Cacheable` 永远不调用方法）；`put(k, null)` 映射为 `invalidate`；`evict` = `invalidate`；`clear` 只清本节点 L2（L1 是共享存储）。
- `@Cacheable(sync = true)` 经引擎的 `get(key, loader)` 回源，受 lease 与失效戳保护。不带 `sync` 的 `@Cacheable` 由 Spring 执行“读未命中 → 调用方法 → `put`”，`put` 是无条件写入，没有这层保护；需要上述一致性保证时用 `sync = true`。
- **启动检查**（`SpringCacheSyncCheck`，`SmartInitializingSingleton`）：存在 `CacheOperationSource`（`@EnableCaching`）时，按 bean 类型（不实例化 bean；CGLIB 取父类，JDK 代理取已创建单例的目标类）解析缓存操作，含 `@CacheConfig` 的类级缓存名。指向 CoCache 缓存名（已创建与已声明）且 `sync = false` 的 `CacheableOperation` 记录告警（`类名#方法名` 与缓存名）；`cocache.spring-cache.require-sync=true` 时启动失败。只按缓存名判断；指定了其它 `cacheManager` 或自定义 `cacheResolver` 的操作跳过。没有 `@EnableCaching`，或不存在名为 `cocacheCacheManager` 的 bean（用户以其它名字声明了自己的 `CacheManager`，starter 的管理器退让）时不做任何事。
- `retrieve` 在 retrieve 执行器（默认执行器见下；starter 中可由 `cocache.spring-cache.retrieve-executor` 指定，或构造时注入）上执行阻塞查找；`retrieve(key, loader)`（响应式 / `CompletableFuture` 方法上的 `sync = true`）与 `get(key, loader)` 一样经引擎受 lease 保护的路径回源，在该执行器上等待异步 loader 完成（每个等待中的调用占一个执行器线程）。默认执行器：JDK 21+ 上每个任务一个虚拟线程（反射取得，基线仍为 JDK 17）；否则为不排队、按需增长的守护线程池（最多 512 个线程，超出时由调用方线程执行）：内层任务不会排在等待它的外层任务之后，嵌套的异步 `sync = true` 不会死锁。替换执行器时同样不能排队：固定线程数或有界队列的执行器（如默认的平台线程 `ThreadPoolTaskExecutor`，即未开启虚拟线程时的 `applicationTaskExecutor`）会把内层任务排在等待它的外层任务之后，嵌套调用可能死锁；虚拟线程执行器（`spring.threads.virtual.enabled=true` 时的 `applicationTaskExecutor`）没有这个问题。
- `TransactionalInvalidator`：事务内调用时登记到当前事务的 `afterCommit`，同一事务内对同一缓存的失效合并为一次 `invalidateAll`，回滚时不失效，没有事务时立即失效。登记按事务的同步列表划分作用域，`REQUIRES_NEW` 的内层事务在自己提交之后失效。有 spring-tx 时 CacheManager 的 `@CacheEvict` / `@CachePut` 同样推迟到提交之后。

### 9.6 Spring AOT 与原生镜像

AOT 处理时，`AnnotatedCacheRegistrar`、`JoinCacheRegistrar`（`ImportBeanDefinitionRegistrar`）与 `CacheSpecRegistrar`（`BeanFactoryPostProcessor`）在**构建期**运行，它们产生的 bean 定义被写成生成代码；运行时由生成的 `ApplicationContextInitializer` 注册 bean 定义，注册器不再运行（`CacheSpecRegistrar` 作为 bean 仍会运行，见下）。

- **缓存 bean 一律由 `CacheBeanDefinitions` 的静态工厂方法创建**（`annotatedCache(type, beanFactory)`、`joinCache(type, beanFactory)`、`specCache(specName, beanFactory)`）。bean 定义只携带一个可序列化的构造参数（接口 `Class` 或 spec bean 名）与目标类型（接口本身，或带 spec 泛型的 `Cache<K, V>` / `SetCache<K, E>`），`BeanFactory` 按类型自动注入。AOT 能为工厂方法生成直接调用的代码；lambda 实例提供者（`instanceSupplier`）不能生成，会让 AOT 处理失败。
- **识别缓存 bean 只看这个构造参数**（`cacheInterfaceOf` / `joinInterfaceOf` / `specBeanOf`），不使用 bean 定义属性：AOT 生成的 bean 定义不保留属性。`declaredCacheNames`、组合视图按缓存名查找 spec 缓存都依赖这一点。
- **`CacheSpecRegistrar` 幂等**：目标 bean 名已存在且是同一个 spec 的缓存定义时跳过（AOT 运行时生成代码已注册），其它重名仍启动失败。
- **运行时提示**：`CoCacheAotProcessor`（`BeanFactoryInitializationAotProcessor`，经 `META-INF/spring/aot.factories` 注册）读取上述 bean 定义，为每个接口式声明与组合视图登记 JDK 代理（接口列表与 `CacheProxies` / `JoinCacheProxies` 创建代理时一致，只有接口本身）、接口公共方法反射（注解、泛型、抽象方法校验、`invokeDefault`）、key 类型的 `toString` 反射（默认 `KeyEncoder` 的检查，spec 声明同样登记）、`keyExpression` / `joinKeyExpression` 求值对象（key 类型 / 主缓存值类型）的公共方法与字段、使用默认 `JacksonCodec` 时值类型的 Jackson 绑定。`CoCacheRuntimeHints`（`RuntimeHintsRegistrar`）登记与声明无关的固定提示：`Cache` / `SetCache` / `JoinCache` 的公共方法、`Executors.newVirtualThreadPerTaskExecutor`（Spring Cache 默认 retrieve 执行器经反射取得）、L2 用到的四个 Caffeine 生成类（`SSMS`、`SSMSA` 与节点类 `PSMS`、`PSAMS`，对应 `maximumSize` 加可选 `expireAfterAccess`）。
- spec 声明的编解码、数据源与 SpEL 以外的反射由使用者负责登记提示；`@EnableCoCache` 列表、spec bean 与 `@Conditional` 条件在构建期固定，策略配置项（`cocache.defaults.*`、`cocache.caches.*`）仍在运行时绑定。
- 验证：`CoCacheAotTest` 检查提示，`CoCacheAotRuntimeTest` 编译生成代码并在没有注册器的新容器中运行；`cocache-example` 的原生镜像由 `.github/workflows/native.yml` 构建并冒烟。新增反射、代理、资源访问或改变缓存 bean 的创建方式时，同时更新提示与这两个测试。

## 10. 可观测与运维

- **`CacheMetrics`**（每个缓存一份，`LongAdder` 累计值）全部记录在未命中、写入、失效路径上，**L2 命中路径不计数**（命中路径不承担计数开销，G3）。同步与异步路径计入同一组计数；`loadTimeouts` 只来自异步数据源。
- **Micrometer**（`CoCacheMeterBinder`，指标目录在 `CacheCounters`，tag `cache`）：`cocache.hits{level=l1}`、`cocache.misses`、`cocache.filtered`、`cocache.load.failures`、`cocache.load.rejections`、`cocache.lease.waits`、`cocache.lease.wait.timeouts`、`cocache.fill.rejections`、`cocache.degraded.fills`、`cocache.stale.discards`、`cocache.reclaims`、`cocache.unknown.formats`、`cocache.refreshes`、`cocache.refresh.rejections`、`cocache.remote.failures`、`cocache.invalidations.received`、`cocache.write.failures`、`cocache.invalidation.retries`、`cocache.invalidations.dropped`、`cocache.resets`；FunctionTimer `cocache.loads`；Gauge `cocache.local.size`。延迟创建的缓存在 bean 初始化后补绑。
- **回源观测 `LoadObserver`**（`cocache-core` 的 SPI，经 `CacheRuntime.Builder.loadObserver` 设置，存于 `EngineContext`，默认 `LoadObserver.NOOP`）：只包住 `guarded` 内的回源调用（`load` / `loadAll` / `refresh`，提前刷新经委托给 loader 的 `RefreshLoader` 识别）与确实发生的 lease 等待（`lease.wait`）。**L2 命中、L1 读取与填充都不经过它**（G3）；默认 NOOP 时引擎按引用识别并直接执行，不分配 lambda。被并发上限拒绝的回源不观测。实现必须在调用方线程上同步执行一次并原样抛出异常（异常透明），不得记录 key。异步路径调用 `observeAsync`（从调用 block 到其 future 完成）：默认实现不观测（直接返回 block 的 future），只覆盖了 `observe` 的观测器在异步路径上没有数据。starter 的 `CoCacheObservationAutoConfiguration`（`@ConditionalOnClass(ObservationRegistry)`、`@ConditionalOnBean(ObservationRegistry)`、`cocache.observation.enabled`）注册 `MicrometerLoadObserver`：Observation `cocache.load`，低基数标签 `cache`、`operation`、`outcome`（`success` / `error`），高基数标签只有 `key.count`；`cocacheRuntime` 经 `ObjectProvider` 取唯一的 `LoadObserver`，因此 `cocache-core` 不依赖 Micrometer，没有 Micrometer 时该自动配置整体跳过。
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
| `SetCacheSpec` 的 `ttl` | 永不过期 | L1 是唯一副本 |
| `keyPrefix` | `cocache:` | |
| Redis `commandTimeout` / `heartbeat` / `invalidateBatchSize` / `channelMode` | 2 s / 5 s / 512 / `pubsub` | |
| 时钟刻度 | 100 ms | `System.currentTimeMillis()` 在部分平台上不随线程扩展 |

## 12. TCK 与测试约定

`cocache-test` 是已发布的 TCK，`src/main` 下的一切都是第三方实现的 API。

| 你实现 | 继承 |
|--------|------|
| `CoherenceBackend`（L1 协议 + 失效通道） | `BackendProtocolSpec`（状态机、令牌、到期时间重设、release、reclaim、批量读、批量覆盖写入、集合、广播帧与订阅重置） |
| 后端在引擎下的端到端行为 | `EngineSpec`（两个运行时共享一个后端：跨节点单次回源、lease 等待超时、同节点失效与并发读、同节点并发 `put`、读己之写、失效重试、`localMaxAge`、批量、跨节点单次刷新、损坏回收、未知格式、`putAll` 的跨节点失效与在途加载栅栏等）与 `SetEngineSpec`（集合缓存） |
| 多节点交错（§4.3、§5、§7） | `MultiNodeSpec`（三个运行时共享一个后端：失效先于 lease、晚于 lease 且有节点在等待、晚于 fill 而早于写 L2；跨节点写入落在读 L1 与写 L2 之间；跨节点并发 `put`（非原子后端上事件逆序到达）；失效重试期间的未命中；全集群每个 key 单次回源与单次刷新的计数断言） |
| 异步视图（§5.6） | `AsyncEngineSpec`、`AsyncSetEngineSpec`、`AsyncMultiNodeSpec`、`AsyncFaultSpec`：经 `CacheAccess.ASYNC` 把对应规范的每个用例改由 `async()` 视图读写（同步数据源换成在调用线程上执行的异步数据源）重跑；`AsyncEngineSpec` 另有取消、完成线程、lease 等待与回源许可等待只用常数个线程（各 1000 个等待者）、原始异常、不同步抛出、`asyncTimeout` 释放 lease、同步与异步互相合并、重入、同步数据源只在阻塞执行器上执行、运行时关闭时的等待等用例 |
| 原生异步的后端（`AsyncRemoteStore`） | `AsyncBackendProtocolSpec`：`BackendProtocolSpec` 的每个用例经异步方法重跑（不得同步抛出，失败为原始异常），另加同步与异步交替调用的一致性 |
| 故障下的 G1 / G2 | `FaultSpec`（L1 整体失败时的降级限龄、fill 失败与 lease 寿命、重试耗尽与队列溢出的放弃、“已在 L1 生效却报错”的 `put` / `invalidate` 可安全重试；可选：断线期间被拒绝的写入在重连后不被重放，后端覆盖 `disconnect()` 才运行） |

- 异步视图的 TCK 在 `cocache-redis` 上与同步矩阵一样跑 `pubsub`、非原子与 `sharded` 三种后端（`RedisAsyncTckTest`：`AsyncBackendProtocolSpec`、`AsyncEngineSpec`、`AsyncSetEngineSpec`（`pubsub`）、`AsyncMultiNodeSpec`、`AsyncFaultSpec`，另有保留同步数据源的 `CacheAccess.ASYNC_WITH_SYNC_SOURCE`），设置 `SPRING_DATA_REDIS_CLUSTER_NODES` 时同样连接 Cluster。
- `HookedBackend` 包装任意后端以注入故障（`failAll`、`failWrites`、`failFill`、`failRelease`）、改写读取结果、在读取之后、`fill` 之后与写入前后编排竞态（`afterPut`、`afterInvalidate` 抛出即模拟“已生效但报错”），并能覆盖 `atomicBroadcast` 以验证非原子路径。被包装的存储原生异步时，同样的钩子与故障也作用于异步方法（故障以失败的 future 返回）；`ioExecutor` 让异步结果改在指定线程上送达（模拟 I/O 线程）。异步结果尚未完成且设置了钩子时，钩子在 `HookedBackend` 自己的线程上执行（钩子可能阻塞，不能在 L1 客户端的 I/O 线程上执行），`issuer()` 返回发起这次存储调用的线程名，按线程编排的钩子用它代替 `Thread.currentThread().name`。`close()` 关闭被包装的后端。
- 时间：内存后端用 `ManualClock` 推进；Redis 测试用 `RedisTestSupport.shiftTtl` 缩短 key 的剩余寿命来模拟时间流逝。到期时间是毫秒精度，不需要 ±1 s 偏差。
- `cocache-redis` 中 `BackendProtocolSpec`、`EngineSpec`、`MultiNodeSpec` 与 `FaultSpec` 在 `pubsub`、非原子（ACL 禁止探测频道）与 `sharded` 三种后端上各跑一遍，`SetEngineSpec` 只在 `pubsub` 后端上运行；设置 `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` 时连接其他单机实例（CI 的 Valkey 任务），设置 `SPRING_DATA_REDIS_CLUSTER_NODES` 时连接 Redis Cluster。`FaultSpec` 的断线用例只在单机 `pubsub` 上运行（经 `TcpProxy` 断开并拒绝重连）；`NOSCRIPT` 回退（每个操作之前 `SCRIPT FLUSH`）是 `RedisFaultTest` 自己的用例。
- 竞态用 latch 编排，禁止 sleep；等待 `finished` latch，让死掉的后台线程使用例失败；跨节点效果用带超时的轮询断言。
- 每个修复的缺陷都要有复现用例（`EngineSpec` 中的同节点竞态用例是范例）。

`MultiNodeSpec` 的节点是同一进程内共享一个后端的运行时，不覆盖真实的多进程与网络分区。

## 13. 性能验证

命中路径（L2 命中、L1 命中）的改动必须附带 JMH 数据：

```bash
./gradlew :cocache-redis:jmh --rerun -PjmhThreads=8 -PjmhIncludes=l2Hit   # 需要 localhost:6379；check 只编译不运行
```

- 基准：`cocache-redis/src/jmh` 的 `CoCacheBenchmark`，方法 `l2Hit`、`l2HitViaInterface`、`l2HitMixed`（轮流访问三个配置不同的缓存）、`l1Read`、`l1SequentialRead`、`l1BatchRead`、`missLoad`、`set`、`invalidate`。`-PjmhIncludes` 是正则，`l2Hit` 同时匹配三个 L2 基准。
- 必须加 `--rerun`：参数不变时 Gradle 判定 `jmh` 任务为最新，静默沿用上次的结果。
- 1 线程与 8 线程，与上一版本**至少交替运行 5 轮**，比较中位数与置信区间，包含 `l2HitMixed`。同一台机器上 8 线程的结果在多次运行之间波动约 ±4%，差异小于噪声时不作结论。

## 14. 不变量

1. **栅栏规则**：任何填充都必须持有读取之前取得的令牌（L1 lease 或刷新令牌；L2 失效戳）。L1 的失效通过销毁令牌完成；L2 的失效方（含 `put`、元素写入）在修改 L1 **之前和之后**各递增一次戳并淘汰 L2。**L2 只由读 L1 的路径填充**，写入方从不直接写 L2。
2. **L1 未命中就是未命中**：key 不存在或处于 `L` 状态都不代表负缓存；负缓存只有状态 `M` 一种形态。
3. **读路径从不无条件删除**：同版本的损坏只能通过 `reclaim`（比较摘要）转为 lease；更高的未知版本既不回收也不写回。
4. **能原子广播就原子广播**：后端以 `atomicBroadcast` 声明能力；为 `false` 时由引擎在写入成功后发布，TCK 按能力分别验证。
5. **SingleFlight 先注销调用、再发布结果**（读己之写依赖这个顺序）；同步与异步调用共用一张表，异步调用在注册之后取戳；每个异步调用方拿到自己的 future；同步调用合并到异步调用时只阻塞自己的线程，异步调用合并到同步调用时经异步执行器拿到结果。同线程对同一 key 的重入（同步调用的拥有线程，或正在为该 key 调用数据源的线程）快速失败；批量合并先注册全部 key、先完成自己负责的 key 再等待别人。
6. **同步 API 上 loader 在调用方线程上执行**；异步视图上异步数据源在调用方线程或异步执行器上调用，同步数据源只在阻塞执行器上执行，从不在异步执行器上执行。Future 只包装 I/O，L1 与数据源的 future 完成后先切换到异步执行器（或在已完成时就地）再执行续接，**事件循环线程上不执行续接**。
7. **令牌必须释放或过期**：loader、`ttlOf`、`Codec` 失败，回源被拒绝或超时，以及刷新令牌用不上时都要 `release`。
8. **每次 L1 状态转换都显式重设到期时间。**
9. **线格式在一个大版本内冻结**：key 布局、标量头、集合字段、消息帧、频道命名、摘要算法。格式演进通过版本号两阶段完成。
10. **集合元素增删只用于 L1 是唯一副本的 `SetCache`**，在 L1 中原子完成，且 `SetCache` 不得有 loader。数据源中的集合只能用 `invalidate` 维护。
11. **有状态组件只能由运行时创建**：用户只提供 spec 和无状态函数。
12. **CoCache 的 L1 连接只访问主节点**，断线时拒绝命令而不是缓冲重放。
13. **存储脚本只执行协议，不持有策略**：TTL、刷新窗口、令牌都由引擎作为参数传入。
14. **代理不包装异常**：接口式声明的代理按方法名直接调用引擎，异常原样抛出；spec bean 注入的就是引擎本身。
15. **命中路径的改动必须附带数据**：1 线程与 8 线程，至少 5 轮交替运行，报告中位数与置信区间，并包含配置不同的多个缓存（`l2HitMixed`）。
16. **starter 注册的缓存 bean 必须能由 AOT 生成代码重建**：只用静态工厂方法与可序列化的构造参数，不用实例提供者，不依赖 bean 定义属性（§9.6）。
17. **取消不传播**：取消调用方的 future 不取消共享加载、不取消数据源的 future、不跳过写入方的第二次递增（§5.6）。
18. **异步视图不挂起线程、不同步抛出**：等待 lease 与回源许可都不占线程；失败以原始异常（不包装为 `CompletionException`）完成返回的 future（§5.6）。
