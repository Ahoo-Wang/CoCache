# RFC 0005：v8 目标架构（权威与派生分型、单一协议核心、可检验的栅栏）

- 状态：**草案 v1**（2026-10-28），待维护者审阅与对抗验证（§12）。目标版本由维护者指定为 **v8**
- 前提：v8 是大版本，API、SPI、配置项与线格式都可以破坏；但三个目标（[architecture.md](../architecture.md) §1 的 G1–G3）与 6.0 的核心协议（lease 栅栏、两次递增的失效戳、原子广播）**不变**。本 RFC 不推翻 [RFC 0003](0003-target-architecture.md)，而是修正它在 6.4–6.8 增量演进中暴露的结构性问题
- 关系：7.0 维持既定范围（删除 6.x 的弃用项），本 RFC 不改变它；v8 的前置工作（内部重构、读格式 2、弃用告警）放在 7.x 小版本（§10）。§9 列出不破坏兼容、可在 6.x 先行的部分。规范仍以 architecture.md 为准，本 RFC 实施时同步修改它
- 来源：2026-10-28 对 6.8.0 的架构审查（附录 A）。其中 3 个缺陷用临时用例实际复现，其余经读代码确认；可先行的修复已在各自的 PR 中进行

---

## 0. 结论摘要

| # | 决定 | 一句话理由 |
|---|------|-----------|
| V1 | **权威与派生分成两个类型**：`Cache<K, V>` 是派生副本（权威在别处：spec 的数据源、调用级 loader，或调用方自己的数据库）；`Store<K, V>` 与 `SetStore<K, E>` 是权威副本（L1 就是数据）。失败语义、默认值、可用的写操作都由类型决定 | 6.x 在至少 6 处用“这次读取有没有数据源”在运行时分支出 fail-open 与 fail-closed；本次审查最严重的两个缺陷（A.D1、A.D2）都是漏掉了其中一处 |
| V2 | **“没有答案”是第三种结果**：派生缓存的读取结果是 `Present` / `Absent`（确知不存在，可缓存）/ `Unknown`（这次读取没有答案：没有数据源、降级、等待超时；不可缓存） | `Absent` 是关于数据的事实，`Unknown` 是关于这一次读取的事实。6.x 只用 L2 条目上的 `unresolved` 标志区分二者，降级路径没有设置它（A.D1） |
| V3 | **能力即类型**：条件写入、按版本写入、元素写入只出现在声明了它的类型上（`Store`、`VersionedCache` / `VersionedStore`、`SetStore`）；删除全部默认抛出 `UnsupportedOperationException` 的方法，`async()` 成为抽象方法；`SetStore` 不再是 `Cache` 的子类型 | `Cache` 有 9 个方法默认抛出，`SetCache` 继承 `Cache` 却拒绝其中约一半，能力只能靠捕获异常发现；装饰器（代理、`VersionedCache`、异步与挂起视图）漏转发一个方法，就静默继承抛出的默认实现 |
| V4 | **SPI 以异步为唯一形状，能力用可选接口表达**：`RemoteStore` 的方法返回 `CompletionStage`；`ConditionalRemoteStore`、`VersionedRemoteStore`、`SetRemoteStore` 是可选子接口，`runtime.create` 以 `is` 检测，缺失时创建失败；只有同步实现的后端用 `RemoteStore.blocking(...)` 适配。删除 `versionedWrites` 标志与非原子的默认实现 | 6.x 用两处表达同一事实（抛出的默认方法 + 布尔标志），还有一处违反自身原则的非原子默认实现（A.L5）；同步与异步两套 SPI 让 TCK 翻倍 |
| V5 | **一份协议、两个驱动**：读、批量读、刷新、写入的协议做成不含 I/O、线程与时钟的状态机（sans-IO），同步驱动在调用方线程上执行它，异步驱动以 future 链执行它；命中路径不经状态机 | `CoherentEngine` 2248 行，同步与异步逐分支一一对应，每次协议改动要改两遍、证两遍；状态机还能被确定性仿真与性质测试直接驱动（V9） |
| V6 | **L2 填充可线性化**：在 key 的锁内校验失效戳并写入（Caffeine `compute`），删除“写后复核、失败撤销” | 6.x 的复核只能事后撤销：写入方返回之后，旧值仍可能被其它线程短暂读到，违反读己之写（A.D4） |
| V7 | **失败路径与成功路径一样确定**：超时取消到底层命令；取得令牌的每一步都有对应的释放；无条件的延迟写入只用于派生副本的删除；等待都有上界且可中断；非原子广播下的重放也广播；刷新的触发率与命中率无关 | 本次审查的缺陷大多只在失败路径上出现（A.R1、A.R4、A.D2、A.D3、A.S1、A.L1） |
| V8 | **线格式 2：按需扩展的头部**：36 字节核心头不变（字段与偏移不变），标志位声明可选的版本段（8 B）与载荷摘要段（16 B，客户端计算）。派生缓存继续写格式 1；只有带版本或需要摘要的内容写格式 2 | 6.8 把版本塞进令牌位置，带版本的缓存因此不能提前刷新；`getIfChanged` 每次在 Lua 中读入载荷并计算 SHA-1（8 MB 约 20 ms）。按需扩展让多数缓存的内存占用与兼容性不变 |
| V9 | **栅栏可被机器检验**：Lincheck 检验 L2 栅栏与 SingleFlight 的线性化；TLA+ 模型检验 L1 状态机与不变量；在 sans-IO 核心上做确定性多节点仿真（丢失、乱序、重复的事件，超时后仍执行的命令，持有令牌崩溃） | TCK 的竞态用例只覆盖人想到的交错；A.D4、A.R1 都是读代码才发现的 |
| V10 | **配置按字段分层**：策略字段有“未设置”状态，优先级为内置 → `cocache.defaults.{cache,store}` → 声明 → 定制器 → `cocache.caches.[name]`；时长必须带单位 | 6.x 的 `cocache.defaults.*` 覆盖显式声明（`defaults.ttl-forever` 会抹掉 `@CoCache(ttl = 60)`），只能靠 `setPolicyFor` / `versionedPolicyFor` 逐类打补丁；策略时长默认按秒、`cocache.redis.*` 默认按毫秒（A.L8） |
| V11 | **Spring：事务内的回源不写 L1，事务结束时（提交或回滚）失效**；`@CacheEvict(allEntries = true)` 指向 CoCache 时启动失败；端点默认只读；`Store` 与 `SetStore` 不能接入 Spring Cache | loader 在调用方的事务内执行，会把未提交的数据凭 lease 写进 L1：提交之前其它节点就能读到，回滚之后一直留到 `ttl`（A.T1） |
| V12 | **规范只写现行行为**：architecture.md 去掉版本叙述，不变量编号稳定（`I-n`），代码与测试引用编号；历史进 changelog 与 RFC | 规范条文里混着“6.3.1 之前……”一类的叙述，读者要自己剥离出现行行为 |

---

## 1. 第一性原理

[RFC 0003 §1.1](0003-target-architecture.md) 的六条公理（串行化点、令牌先于数据、权威发出失效、TTL 是安全网、命中路径只做必要的事、声明与实例分离）全部保留。6.4–6.8 的演进补出下面五条。

- **A7 权威性决定失败语义**。副本可以丢、可以降级、删除总是安全的，因为权威还在别处；唯一的副本不能丢、不能降级成“不存在”，删除必须与其它写入有序。一个默认值如果在两种数据上应当不同，它就不该是同一个类型上的同一个默认值。
- **A8 “没有答案”不是“不存在”**。`Absent` 是关于数据的事实，可以缓存、可以广播；`Unknown` 是关于这一次读取的事实，只能返回给这一次调用。把后者写成前者，就是把一次故障缓存成了数据。
- **A9 一个协议只写一次**。协议的每个分支有两份实现，就有两倍的出错面和两倍的证明负担。阻塞还是回调、在哪个线程上继续，是驱动的事，不是协议的事。
- **A10 失败路径也是协议**。超时、取消、拒绝、崩溃之后系统处于什么状态，要和成功、失败一样确定：取得的令牌必须释放或过期；放弃等待的命令必须真的不再执行，或者按“结果未知”设计成幂等；延迟执行的写入不能越过之后的写入。
- **A11 不变量要能被机器检验**。手写的交错只覆盖想到的交错；能被模型检验或仿真搜索的不变量才是可信的不变量。

## 2. 现状审查（6.8.0）

先说结论：6.0 的核心协议经受住了审查。L1 的 lease 与刷新令牌、Lua 状态机、带符号 64 位的版本比较、Cluster 分组、帧解码都没有发现缺陷；SingleFlight“先注销再发布”与读己之写的论证成立。问题集中在协议之外，可以归为下面七个结构性原因，每个原因都至少产生了一个缺陷（编号见附录 A）。

### 2.1 权威与派生混在一个类型里

6.4–6.8 新增的能力（`foreignFormat`、`SetCache` 的 fail closed、`getIfChanged` / `putIfMatch`、`putIfNewer`）服务于“L1 是唯一副本”的用法：CoSec 的策略快照文档、Wow 的聚合状态视图、`SetCache`。它们都挂在 `Cache` 上，靠“这次读取有没有数据源”决定行为：

| 位置（architecture.md） | 有数据源 | 没有数据源 |
|------|----------|------------|
| `foreignFormat` 未配置时（§5.4） | `RECLAIM` | `FAIL` |
| 读到更高格式版本（§5.4） | 绕过 L1 回源 | `FAIL` |
| 条件读写遇到格式 0（§6.4） | — | `FAIL` |
| `SetCache` 的 L1 读失败（§6.3） | — | 抛出 |
| `getIfChanged` 的 L1 读失败（§6.4） | — | 抛出 |
| `get(key, loader)` 遇到 `unresolved` 副本（§5.1） | 不接受 | — |

漏掉的两处成了本次审查最严重的两个缺陷：

- **A.D1**：没有数据源的 `Cache` 在 L1 读失败时（默认 `readFailure = DEGRADE`）返回 `null`，并把它当作已决的负缓存写入 L2；之后 `get(key, loader)` 命中它，不调用 loader。
- **A.D2**：默认 `invalidateFailure = RETRY` 的延迟重试会删除其它节点之后写入的值。对派生副本，这只是多一次未命中；对唯一副本，这就是丢更新。

### 2.2 能力靠运行时异常表达

- `Cache` 的 9 个方法默认抛出 `UnsupportedOperationException`（`getIfChanged`、`putIfMatch` ×2、`putIfNewer` ×2、`invalidateIfNewer` ×2、`getVersioned`、`async`），`AsyncCache` 同样。
- `SetCache` 是 `Cache<K, Set<E>>` 的子类型，却拒绝 `invalidate`、`invalidateAll`、`get(key, loader)` 与全部条件、版本方法。接受 `Cache` 的组件（`TransactionalInvalidator`、Spring 的 `CacheManager`）因此能接收 `SetCache`，然后在运行时失败，有时是在事务提交之后。
- 带版本的缓存仍然暴露 `put` / `invalidate`，它们静默丢掉版本顺序（architecture.md §6.5“丢失版本的写入”）。
- SPI 用两处表达同一事实：抛出的默认方法 + `CoherenceBackend.versionedWrites`。`RemoteStore.addMembersIfReadable` 的默认实现先读后写、不原子（A.L5），与 `putIfMatch`“宁可不支持，也不提供先读后写”的原则相反。

### 2.3 协议有两份实现

- `CoherentEngine` 2248 行、`SetEngine` 497 行：同步路径与异步路径“逐分支一一对应”（architecture.md §5.6），共用的只有判定函数（`hitOutcome`、`batchStep`、`refreshStep`、`completeLoad`、`putsOf`）。
- SPI 有同步与异步两套（`RemoteStore` / `AsyncRemoteStore`、`InvalidationChannel` / `AsyncInvalidationChannel`），外加 `BlockingAsyncStore` 适配；TCK 有同步与 `Async*` 两套。
- 为了让异步链少派发几次，`Futures` 引入了基于线程局部变量的就地续接（`continueOn`、`withoutInline`、深度上限 16），重入守卫与 SingleFlight 都要与之配合。它是正确的，但它是两份实现带来的代价，不是协议本身的需要。

### 2.4 失败路径不够确定

- **A.R1**：Redis 命令超时时，引擎取消的是 `exceptionallyCompose` 派生的 future，不是 Lettuce 的命令。Cluster 中等待建立节点连接的命令不计时，超时后仍会被写出执行：调用方已经看到失败的 `put`，稍后却生效并广播。
- **A.R4**：非原子广播时，条件写入写 L1 成功、广播失败；调用方按文档重放，得到 `Stale` 或冲突，于是永不广播。
- **A.L1**：读 L1 超时时，读脚本可能已经授予了 lease，没有人释放它。
- **A.S1**：同步 SingleFlight 的跟随者用 `join()` 等待，不可中断、无上限。
- **A.D3**：提前刷新在没取得令牌的节点上按往返频率轮询 L1（实测：持有者回源期间，另一节点的 50 次命中触发了 25 次 L1 读取）；寿命 ≤ 刷新窗口时，持有者背靠背回源。

### 2.5 检验手段只覆盖想到的交错

TCK（`EngineSpec`、`MultiNodeSpec`、`FaultSpec`）以 latch 编排的交错是本项目的优势。但 **A.D4**（L2 填充的“复核—撤销”窗口）与 **A.R1**（超时之后的执行）都是读代码发现的，没有现成的用例能发现它们。

### 2.6 线格式的余量已经用完

- 标量头只有 1 个保留字节。6.8 的版本只能放进令牌位置（architecture.md §3.3），代价是带版本的内容不能持有刷新令牌，`CacheSpec` 因此拒绝 `versioned` 与 `refreshWindow` 同时出现（§6.5）。
- 摘要不在头部，`getIfChanged` / `putIfMatch` 每次都把载荷读进 Lua 并计算 SHA-1：Redis 7.4 上 8 MB 的值一次约 20 ms，期间不处理其它命令（§6.4）。§6.4 已把“写入时保存摘要”列为“留到下一次格式演进再考虑”。

### 2.7 配置分层与单位

- `cocache.defaults.*` 覆盖声明中显式写出的值（architecture.md §9.4 的优先级），于是需要 `setPolicyFor`（对 `SetCache` 跳过 5 个字段）与 `versionedPolicyFor` 逐类排除。
- 策略时长不写单位时按秒，`cocache.redis.*` 按毫秒（A.L8）：同样写 `200`，`lease-wait` 是 200 秒。

## 3. 目标架构总览

### 3.1 用户 API 的类型

```
ReadableCache<K, V>                 name、get、getAll、lookup、async()
├── Cache<K, V>                     派生副本：get(k, loader)、put、putAll、invalidate、invalidateAll
│   └── VersionedCache<K, V>        + putIfNewer、invalidateIfNewer、getVersioned
├── Store<K, V>                     权威副本：put、putAll、delete、getIfChanged、putIfMatch
│   └── VersionedStore<K, V>        + putIfNewer、deleteIfNewer、getVersioned、purge
└── SetStore<K, E>                  权威集合（ReadableCache<K, Set<E>>）：add、remove、clear、put、putAll
```

- 每个接口只声明它支持的操作，全部是抽象方法。异步视图与挂起视图有同构的层次（`AsyncCache`、`AsyncStore`……）。
- `Lookup` = `Present` | `Absent` | `Unknown`（V2）。`Store` 与 `SetStore` 从不返回 `Unknown`：它们的读失败抛出。
- `runtime.create` 按 spec 的类型返回精确的类型：`cacheSpec` → `Cache`，`versionedCacheSpec` → `VersionedCache`，`storeSpec` → `Store`，`versionedStoreSpec` → `VersionedStore`，`setStoreSpec` → `SetStore`。
- `JoinCache` 保持只读组合视图，两侧是 `ReadableCache`。

### 3.2 内部结构

```
            ┌───────────── 命中路径（手写，不经状态机） ─────────────┐
用户调用 ──▶│ L2 查找 + 过期比较 → 返回                              │
            └───────────────┬─────────────────────────────────────────┘
                            │ 未命中 / 写入
                            ▼
            ┌───────────── 协议核心（sans-IO 状态机） ───────────────┐
            │ MissMachine、BatchMachine、RefreshMachine、WriteMachine │
            │ 输入：上一条指令的结果；输出：下一条指令                │
            └───────────────┬─────────────────────────────────────────┘
                ┌───────────┴───────────┐
                ▼                       ▼
         BlockingDriver            AsyncDriver
   （调用方线程上执行指令）  （future 链执行指令，定时器等待）
                └───────────┬───────────┘
                            ▼
      RemoteStore（异步 SPI）、InvalidationChannel、L2、失效戳、SingleFlight
```

模块划分与依赖方向（architecture.md §2）不变。

## 4. 核心设计

### 4.1 权威与派生（V1、V2）

| 方面 | `Cache`（派生副本） | `Store` / `SetStore`（权威副本） |
|------|--------------------|----------------------------------|
| 数据源 | spec 的 loader、调用级 loader，或调用方自己（经 `put` 或 Spring Cache） | 按构造没有 |
| L1 中不存在 | `Unknown`：有数据源则回源 | `Absent`：确知不存在 |
| L1 读失败 | 有数据源：降级回源（`degradedLocalMaxAge`）；没有：返回 `Unknown`，**不写 L2** | 抛出 `CacheReadException`，不写 L2 |
| 删除 | `invalidate`：删除副本；失败默认 `RETRY`（删除副本在任何时刻都安全） | `delete`：同步完成，失败抛出 `CacheWriteException`，**从不排队重试** |
| 写入 | `put`（后写者胜，不推荐；首选先提交数据源再 `invalidate`） | `put`、`putIfMatch`（摘要比较）；带版本的类型另有按版本写入 |
| 读不懂的内容 | `RECLAIM`（条件回收后回源） | `FAIL` |
| 负缓存 | 回源返回 `null` 时写入 `M` | 不适用：`Absent` 就是不存在 |
| 默认 `ttl` | 1 h | 永不过期 |
| 提前刷新 | 支持 | 不适用 |
| 启动检查 | — | Redis `maxmemory-policy` 为 `allkeys-*` 时启动失败（6.1 起为告警） |
| Spring Cache | 可接入 | 不可接入 |

- `Unknown` 由协议核心产生（没有数据源的未命中、降级、lease 等待超时且没有数据源），驱动把它原样交给调用方；`LocalEntry` 只保存 `Present` 与 `Absent`，6.x 的 `unresolved` 标志删除。
- `Store` 的删除不排队重试，是 A7 的直接推论：延迟执行的无条件删除会越过之后的写入（A.D2）。需要可靠删除的写入方用 `deleteIfNewer`（带版本）或基于 `putIfMatch` 的条件删除，它们的重放是幂等且有序的。
- `Store` 的 L2 仍是只读副本，栅栏规则不变。需要跨节点读己之写的调用方用 `getIfChanged` 确认（用法同 architecture.md §6.4）。

### 4.2 能力类型（V3）

- 删除 `Cache`、`AsyncCache`、`SetCache` 与挂起视图上全部抛出的默认方法，`async()` 改为抽象方法。代理（`CacheProxies`）按接口的实际方法集分发；新增方法时，编译器与 `CacheMethods` 校验会同时报错，不再有“漏转发即继承抛出”的情况。
- 接受缓存的组件按需要的能力声明参数类型：`TransactionalInvalidator` 只接受 `Cache`，不再能接收 `SetStore`；Spring 的 `CacheManager` 只接收 `Cache`。
- 带版本的类型上，丢失版本的写入改为显式的名字：`VersionedCache.invalidate` 保留（删除的是副本）；`VersionedStore` 没有 `put` 与 `delete`，只有 `putIfNewer` / `deleteIfNewer` 与运维用的 `purge`。

### 4.3 SPI（V4）

```
CoherenceBackend         store(namespace): RemoteStore、channel、format、atomicBroadcast
RemoteStore              read、readAll、fill、fillAll、release、reclaim、put、putAll、invalidate（全部返回 CompletionStage）
  ConditionalRemoteStore   readIfChanged、putIfMatch
  VersionedRemoteStore     putIfNewer
  SetRemoteStore           addMembers、removeMembers（只有“检查并写入”这一种原子语义，格式 0 的处理由 foreignAsAbsent 参数决定）
InvalidationChannel      publish、subscribe（返回 CompletionStage）
```

- **异步是唯一形状**。同步驱动在调用方线程上等待返回的 future，不在 I/O 线程上执行任何续接，与 6.x 相同。只有同步实现的后端用 `RemoteStore.blocking(impl, executor)` 适配：同步驱动直接在调用方线程上调用 `impl`，没有线程切换；异步驱动把调用放到阻塞执行器上，与 6.x 的 `BlockingAsyncStore` 相同。[RFC 0004](0004-async-api.md) A5 当年选择“同步 SPI + 异步子接口”，理由是小版本里不能破坏第三方后端；大版本里这个理由不成立，而两套 SPI 的代价（TCK 翻倍、`Async*` 规范、适配层）一直存在。
- **能力在创建时检测**：`Store` 需要 `ConditionalRemoteStore`，带版本的类型需要 `VersionedRemoteStore`，`SetStore` 需要 `SetRemoteStore`；缺失时 `runtime.create` 抛出，不注册任何东西。这是把 6.8 对 `versionedWrites` 的检查推广到全部能力。SPI 不提供任何非原子的默认实现。
- **TCK**：`BackendProtocolSpec` 等规范按访问方式参数化（同步驱动 / 异步驱动），删除 `Async*` 平行规范；按后端实现的能力接口自动启用对应用例，取代 `supportsConditionalWrites` 一类开关。

### 4.4 协议核心（V5）

协议核心是一组只依赖值的状态机：

```kotlin
internal sealed interface Step {
    class Read(val key: String, val lease: LeaseRequest?, val refresh: RefreshRequest?) : Step
    class ReadAll(val keys: List<String>, val leases: Map<String, LeaseRequest>) : Step
    class Load(val keys: List<Any>, val operation: LoadOperation) : Step
    class Fill(val fills: List<me.ahoo.cocache.api.spi.Fill>) : Step
    class Release(val key: String, val token: Token) : Step
    class Reclaim(val key: String, val digest: Digest, val lease: LeaseRequest) : Step
    class Wait(val nanos: Long) : Step              // lease 退避：同步驱动 park，异步驱动交给定时器
    class AcquirePermit(val timeoutNanos: Long) : Step
    class Done(val result: Any?) : Step
    class Fail(val error: Throwable) : Step
}

internal interface Machine {
    fun start(nowMillis: Long, stamp: Long): Step
    fun resume(outcome: Outcome): Step              // Outcome = 上一条指令的结果或异常
}
```

- 状态机不知道线程、执行器、时钟与 SingleFlight：时间与失效戳作为输入，等待作为指令，合并发生在驱动进入状态机之前。
- 驱动只做三件事：执行指令、按自己的方式等待、在约定的位置调用 `fillLocal`（栅栏仍由引擎持有）。同步驱动是一个循环；异步驱动把每条指令映射为一个 future，并沿用 6.x 的派发规则（architecture.md §5.6）。
- 命中路径（`entryOf`、`localHit` 与异步命中）留在引擎里手写，不分配状态机，G3 不受影响。
- 每次未命中多分配一个状态机与若干指令对象。未命中路径的成本由一次以上的网络往返主导，预算见 §7。
- 状态机可以在没有任何 I/O 的情况下被性质测试驱动：对任意的结果序列，断言“取得的令牌最终被填充或释放”“填充出示的令牌取得于读取之前”“L2 填充使用的是读取之前取得的戳”（V9）。
- 迁移方式：7.x 内部重构，API 不变，每条路径一个 PR（单 key 读 → 批量 → 刷新 → 写入），现有同步与异步 TCK 证明行为等价，JMH 把关（§10）。

**备选：异步视图直接在虚拟线程上运行同步路径。** 否决为 v8 的主方案：基线仍是 JDK 17（Q3）；JDK 21–23 上 Caffeine 与 `ConcurrentHashMap` 内部的 `synchronized` 会钉住载体线程（JEP 491 在 JDK 24 才解决）；有了 sans-IO 核心之后，异步驱动本身很小，虚拟线程省不下多少代码。

### 4.5 L2：栅栏、时钟与 key（V6）

**可线性化的填充**：

```kotlin
local.asMap().compute(storageKey) { _, current -> if (stamps.isValid(storageKey, stamp)) entry else current }
```

论证：写入方的顺序是“戳 → 淘汰 → 修改 L1 → 戳 → 淘汰”，淘汰需要该 key 的锁；填充方在同一把锁内校验并写入。若校验发生在第二次递增之前，写入在锁内完成，写入方的第二次淘汰要等锁释放之后才执行，因此写入方返回之前副本已被淘汰；若校验发生在第二次递增之后，校验失败。两种情况下，写入方返回之后，读取开始于修改之前的副本都不再可见。远端事件与重置的“戳 + 淘汰”同理。这把 G2 的 L2 条款从“最终撤销”收紧为“处理完成之后不可见”，并删除复核与撤销（撤销还会误删别的线程刚写入的新副本）。只影响未命中路径，可在 6.x 先行（§9）。

**单调的时钟**：`TickingClock` 以启动时的墙钟为基准，加 `System.nanoTime()` 的增量。L2 的寿命因此不受墙钟回拨或跳变影响（6.x 中墙钟回拨 Δ，L2 副本就多活 Δ，`localMaxAge` 的上界失效，A.C1）。`CacheClock` 接口不变，`Lookup.expiresAt` 仍是纪元时间。可在 6.x 先行。

**L2 的 key 不含前缀（待 JMH）**：每次 L2 命中都要拼接 `storagePrefix + encode(key)`，分配一个新字符串，并在 Caffeine 中按全长计算哈希、比较内容。L2 属于单个缓存，前缀在 L2 中是冗余的。改用编码后的 key（`String` key 时就是调用方传入的实例，哈希已缓存），失效戳与 SingleFlight 同样按它索引；收到失效帧时剥掉本缓存的前缀，前缀不符的 key 忽略（与 6.x 中“匹配不到任何副本”的效果相同）。只有 `scripts/jmh-compare.sh` 在 1 与 8 线程上（含 `l2HitMixed`）显示不退化时才采用。它不破坏兼容，可在 6.x 先行。

### 4.6 失败路径（V7）

| 规则 | 6.x 中的反例 |
|------|-------------|
| 超时取消到底层命令。已写出、无法取消的命令按“结果未知”处理：派生写入由 `localMaxAge` 兜底，权威写入只能以幂等的条件写入或按版本写入重放 | A.R1 |
| 取得令牌的每条指令，在状态机的每个失败转移上都有对应的 `Release`（读超时也释放；令牌不匹配时 release 无害） | A.L1 |
| 无条件的延迟写入只有一种：派生副本的 `invalidate` 重试 | A.D2 |
| 等待有上界且可中断：同步跟随者以 `get()` 等待并响应中断，上界为 `load.timeout`（默认 30 s，与 6.x 的 `asyncTimeout` 合并为一个字段）；超时的跟随者以 `SourceLoadTimeoutException` 失败，leader 不受影响 | A.S1 |
| 非原子广播时，重放命中自己写入的版本或摘要（`Stale` 且当前版本等于请求的版本、冲突摘要等于本次载荷的摘要）同样广播；多余的广播只淘汰副本 | A.R4 |
| 提前刷新的触发率与命中率无关：没取得令牌的重新填充按 `lease.wait` 退避；剩余寿命 ≤ 刷新窗口的副本不设刷新点 | A.D3 |

这些规则写入 §6 的不变量，由状态机的性质测试与确定性仿真检验（V9）。其中大部分不破坏兼容，正在 6.x 修复（附录 A 的“去向”列）。

### 4.7 线格式 2（V8）

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0–35 | 36 | 与格式 1 相同：magic、格式版本（`2`）、状态、**标志位**（格式 1 的保留字节）、令牌、令牌签发时间 |
| 36 | 8 | 版本（标志位 bit 0），大端有符号 64 位 |
| 36 或 44 | 16 | 载荷摘要（标志位 bit 1），客户端计算：SHA-256 截断为 128 位（JDK 自带，不增加依赖） |
| 之后 | n | 载荷 |

- **按需写入**：派生缓存（没有版本、不需要摘要）继续写格式 1，与 6.x / 7.x 的字节相同；`Store` 写入时保存摘要；带版本的类型写入版本段。
- **带版本的内容回到正常的令牌语义**：版本有了自己的位置，令牌位置只放令牌，带版本的派生缓存也能提前刷新。`VERSION_MARK` 与 `+inf` 签发时间只在读取格式 1 的旧内容时识别。
- **条件读取 O(1)**：`getIfChanged` 用 `GETRANGE key 0 59` 只读头部比较摘要，不把载荷读进 Lua；`putIfMatch` 比较头部摘要后写入。摘要不变时，Redis 端的成本与载荷大小无关（6.x：8 MB 约 20 ms）。
- **摘要的语义不变**：只覆盖载荷，负缓存与空载荷相同（architecture.md §3.3）；写入方在客户端计算一次，发生在写入路径上，不在命中路径上。
- **格式演进按 §3.3 两阶段**：7.x 的最后一个小版本能读格式 2（含 Lua 脚本对版本段与摘要段的处理）、仍写格式 1；v8 读格式 1 与 2，按上面的规则写入。因此 7.x 末版与 v8 **可以共享同一个 Redis 滚动升级**，不同于 5.x → 6.0。格式 1 中的权威内容在下一次写入时改写为格式 2，期间条件读写对它临时计算摘要。v9 才删除格式 1 的读取支持，届时提供离线改写工具（与 `CoCache5Migrator` 同构）。
- 集合（Hash）、失效帧、频道命名不变。

### 4.8 配置（V10）

- 策略字段可以“未设置”（内部是每个字段都可空的 `PolicyOverlay`），逐层合并之后再校验并构造 `CachePolicy`。
- 优先级：内置默认值 → `cocache.defaults.cache.*` / `cocache.defaults.store.*` → 声明（注解或 spec DSL）→ `CacheSpecCustomizer` → `cocache.caches.[name].*`。全局默认值不再覆盖声明中显式写出的值；按类型分开的默认值取代 `setPolicyFor` / `versionedPolicyFor` 的逐字段排除。
- 所有时长必须带单位（`500ms`、`5s`、`2m`、`PT2M`）。不带单位的值启动失败，错误信息给出 6.x 的解释（秒或毫秒）与等价写法。
- `ttlOf` 给出的寿命无法静态校验，运行时按 §4.6 处理：剩余寿命 ≤ 刷新窗口的副本不设刷新点，并计数。

### 4.9 Spring（V11）

- **事务内的回源不写 L1**。core 增加一个只在未命中路径上调用的钩子 `FillGate`（默认总是允许）；`cocache-spring` 的实现在当前线程处于非只读的实际事务中时拒绝写 L1：回源结果只进 L2（降级限龄，受失效戳保护），lease 照常释放。理由：loader 在调用方的事务内执行，可能读到本事务未提交的写入；凭 lease 写进 L1 之后，提交之前其它节点就能读到（脏读跨进程泄漏），回滚之后一直留到 `ttl`（A.T1）。代价：写事务内的未命中不参与跨节点去重（Q4）。
- **`TransactionalInvalidator`**：在 `afterCompletion` 中执行，提交与回滚都失效已登记的 key（回滚时失效，是为了清除可能已经写入的副本；多余的失效无害）；在同步已经执行之后才登记的 key 立即失效；逐缓存执行，失败聚合之后再抛出（A.T2）；参数类型只接受 `Cache`。
- 响应式与协程事务（R2DBC、`@Transactional suspend`）：提供基于响应式 `TransactionSynchronizationManager` 的变体，或在检测到“有实际事务、却没有线程绑定的同步”时告警（Q5）。
- `@CacheEvict(allEntries = true)` 指向 CoCache 缓存时启动失败（扩展 `SpringCacheSyncCheck`）：它在 6.x 中只清本节点 L2，而 Spring 的语义是清空整个缓存。
- actuator 端点 `cocache` 默认只读（`Access.READ_ONLY`），写操作需要显式开放；`Store` / `SetStore` 的按 key 删除另需 `cocache.endpoint.allow-store-delete=true`。
- `keyExpression` 改用编译模式的 SpEL（`SpelCompilerMode.IMMEDIATE`，待 JMH）。

### 4.10 检验（V9）

- **Lincheck**（JetBrains，Kotlin 原生，只作为测试依赖）：对“L2 + 失效戳 + SingleFlight + 内存存储”做模型检验，操作为 `get`、`invalidate`、`put`、`onInvalidated`、`onReset`，顺序规格是“带版本的数据源 + 读己之写”。A.D4 是它应当能发现的典型问题。
- **TLA+**：`docs/spec/CoCache.tla` 建模 N 个节点、一个 key 的 L1 状态机（`ABSENT` / `LEASED` / `VALUE` / `MISSING`，令牌，版本）、每个节点的 L2 与失效戳、可丢失 / 乱序 / 重复的失效通道、带提交历史的数据源、超时后仍执行的命令与持有令牌崩溃。性质：G2 的 L1 与 L2 条款、版本单调、令牌最终释放。CI 中以小规模（2 节点、1 个 key、3 个值）运行 TLC。
- **确定性仿真**：sans-IO 核心不依赖线程与时钟，可以在单线程上由带种子的调度器驱动多个节点、模拟后端与模拟时钟，每次 CI 跑数千个随机交错（含故障注入），以数据源的提交历史为裁判，检查 G1 的上界与 G2。失败的种子可以复现。
- 已发布的 TCK 保留，手写交错继续作为回归用例。

### 4.11 文档（V12）

- architecture.md 只写现行行为。版本叙述（“6.3.1 之前……”“6.4.0 也是……”）移到 changelog 或对应的 RFC。
- 不变量以稳定编号 `I-n` 列出，代码注释与测试用例引用编号，取代“RFC 0003 §4.4”一类会随文档改版而失效的引用。
- 审查与对抗验证的记录放在 RFC 的附录中（如本文附录 A）。

## 5. 删除清单（v8）

- `Cache`、`AsyncCache` 与挂起视图上默认抛出的方法（移到能力类型）；`SetCache`、`AsyncSetCache`、`SetCacheSpec`（由 `SetStore`、`AsyncSetStore`、`SetStoreSpec` 取代）。
- 把没有数据源的 `Cache` 当作权威副本的用法（`putIfMatch` / `getIfChanged` 移到 `Store`）。
- `LocalEntry.unresolved`；`readFailure` 对没有数据源的读取的含义（由类型决定）。
- `CoherenceBackend.versionedWrites`；`RemoteStore` 与 `AsyncRemoteStore`、`InvalidationChannel` 与 `AsyncInvalidationChannel` 的双份接口；抛出的默认方法与非原子的默认实现；`BlockingAsyncStore` / `BlockingAsyncChannel`（由 `RemoteStore.blocking` 取代）。
- `Async*` 平行 TCK 规范；`supportsConditionalWrites` 一类能力开关。
- `Store` 上的 `InvalidateFailureMode.RETRY`；`LoadPolicy.asyncTimeout`（并入 `load.timeout`）。
- `cocache.defaults.*` 覆盖显式声明的语义；不带单位的时长；`setPolicyFor` / `versionedPolicyFor`。
- 以格式 1 写入带版本的内容（`VERSION_MARK` 只在读取时识别）。

## 6. 新不变量（v8 实施时取代 architecture.md §14 的对应条目）

- **I-A 权威性由类型决定**：`Cache` 是派生副本，`Store` / `SetStore` 是权威副本；任何默认值与失败处理都不按“这次读取有没有数据源”在运行时分支。
- **I-B `Unknown` 不进入任何一层**：没有答案的读取只返回给本次调用，不写 L2、不写 L1、不广播。
- **I-C L2 填充可线性化**：校验失效戳与写入 L2 在同一个 key 锁内完成；写入方、远端事件与重置处理完成之后，读取开始于它们之前的副本不可见。
- **I-D 权威副本的删除不延迟**：`Store` 的删除与写入同步完成或抛出；延迟执行的无条件写入只用于派生副本的 `invalidate`。
- **I-E 超时取消到底层**：调用方看到失败的写入，要么不会再执行，要么是幂等且有序的（条件写入、按版本写入、派生副本的删除）。
- **I-F 令牌守恒**：取得令牌的每一步，在每个失败转移上都有释放；由状态机的性质测试检验。
- **I-G 刷新有界**：每个节点、每个 key 的刷新触发率有一个与命中率无关的上界。
- **I-H 等待有界且可中断**：同步与异步的跟随者、lease 等待、许可等待都有上界；同步等待响应中断。
- **I-I 一份协议**：每个协议分支只有一份实现；同步与异步只在驱动上不同。
- **I-J 事务内的回源不写 L1**（启用 Spring 集成时）。

architecture.md §14 的其余不变量（第 1–20 条）保留，按 V12 重新编号。

## 7. 性能预算与验证

| 路径 | 预算 | 验证 |
|------|------|------|
| L2 命中 | 不退化（1 / 8 线程，含 `l2HitMixed`）；L2 key 不含前缀若采用，须为正收益 | `scripts/jmh-compare.sh -b l2Hit,l2HitViaInterface,l2HitMixed,l2HitAsync -t 1,8` |
| L1 命中、未命中回源 | sans-IO 核心的退化 ≤ 3%（同轮配对比值的中位数） | `l1HitViaEngine`、`l1HitViaEngineAsync`、`missLoad`、`missLoadAsync` |
| L2 填充（`compute`） | 未命中路径的退化 ≤ 2% | `missLoad`、`l1Read` |
| 条件读取（摘要未变） | Redis 端成本与载荷大小无关；8 MB 的值从约 20 ms 降到亚毫秒 | 新基准 `conditionalUnchanged`（1 KB / 1 MB / 8 MB） |
| 内存 | 派生缓存每个 key +0 B；`Store` +16 B（摘要）；带版本 +8 B | `MEMORY USAGE` 对照 |

## 8. 迁移与兼容

| 6.x 用法 | v8 |
|----------|----|
| 有数据源的 `Cache` | `Cache`（不变） |
| 没有数据源、经 Spring Cache 或 `put` 使用的 `Cache` | `Cache`；L1 故障时返回 `Unknown`，不再缓存为不存在 |
| 没有数据源、用 `putIfMatch` / `getIfChanged` 或当作唯一副本的 `Cache` | `Store`（`invalidate` → `delete`） |
| `CacheSpec.versioned` / `versionedLoader` | `VersionedCache`（有数据源）或 `VersionedStore`（没有数据源） |
| `SetCache` / `SetCacheSpec` | `SetStore` / `SetStoreSpec` |
| `cocache.defaults.*` | `cocache.defaults.cache.*` / `cocache.defaults.store.*`，不再覆盖显式声明 |
| 不带单位的时长 | 必须带单位 |
| 第三方后端 | 实现异步 SPI 与所需的能力接口，通过参数化的 TCK |

- starter 无法替用户判断一个没有数据源的缓存是不是权威副本。7.x 末版对“没有数据源，且调用了 `putIfMatch` / `getIfChanged` / `putIfNewer`”的缓存告警，提示在 v8 中声明为 `Store`。
- 数据：派生缓存不需要迁移；格式 1 的权威内容在 v8 中照常读取，下一次写入时改写为格式 2；7.x 末版与 v8 可以混合部署（§4.7）。
- 迁移表写入 `wiki/guide/changelog.md`（en + zh）。

## 9. 可在 6.x / 7.x 先行（不破坏兼容）

| 项 | 来源 | 状态 |
|----|------|------|
| 没有数据源的 L1 读失败 fail closed；`invalidate` 重试不越过之后的写入；非原子广播下的重放也广播 | A.D1、A.D2、A.R4 | 修复中（独立 PR） |
| 超时取消 Lettuce 命令；`sharded` 未确认频道每次心跳重置；读超时释放 lease；订阅生命周期竞态；格式字节按无符号解析 | A.R1、A.R2、A.L1、A.R3、A.L7 | 修复中（独立 PR） |
| `TransactionalInvalidator` 回滚失效、迟到登记、逐缓存失败隔离 | A.T1、A.T2 | 修复中（独立 PR） |
| 提前刷新退避；寿命 ≤ 刷新窗口不设刷新点 | A.D3 | 修复中（独立 PR） |
| L2 填充用 `compute`（§4.5） | A.D4 | 待排期 |
| 单调时钟（§4.5） | A.C1 | 待排期 |
| 同步跟随者可中断 | A.S1 | 待排期（上界属于 v8 的 `load.timeout`） |
| L2 key 不含前缀 | §4.5 | 待 JMH 验证 |
| Lincheck 用例、TLA+ 模型 | V9 | 待排期 |
| 协议核心 sans-IO 重构（内部，API 不变） | V5 | 7.x |
| 读格式 2 | V8 | 7.x 末版 |

先行之后，v8 本身只剩破坏兼容的部分：类型、SPI、配置、Spring 语义与格式 2 的写入。

## 10. 实施顺序

0. **6.x**：§9 中“修复中”与“待排期”的项；Lincheck 用例与 TLA+ 模型进入仓库与 CI。
1. **7.x**：sans-IO 重构，每条路径一个 PR（单 key 读 → 批量 → 刷新 → 写入），每个 PR 的同步与异步 TCK 全绿、JMH 达标；确定性仿真接到新核心上。
2. **7.x 末版**：读格式 2；§8 的弃用告警。
3. **8.0**：API 与 SPI 类型（V3、V4）、`Store` / `SetStore`（V1、V2）、配置（V10）、Spring（V11）、格式 2 的写入（V8）、文档重排（V12）。
4. **8.x**：按需的后续，如响应式事务、构建期生成接口实现（§11）。

## 11. 风险与未决问题

- **Q1 范围放在 v8 还是 7.0**：维护者指定 v8。7.0 保持“删除弃用项”的既定范围，v8 的前置工作分散在 7.x，降低一次性风险。
- **Q2 命名**：用户 API 的 `Store` 与 SPI 的 `RemoteStore` 容易混淆。备选：用户 API 用 `Store`、SPI 改名为 `L1Store`；或用户 API 用 `Register` / `Document`。倾向前者。
- **Q3 基线升到 JDK 21**：v8 是大版本，可以升级基线（CONTRIBUTING“Versioning and releases”）。升级之后可以把虚拟线程作为默认阻塞执行器，不再回退到线程池，但本 RFC 的设计不依赖它。需要先了解用户的 JDK 分布。
- **Q4 写事务内的回源不写 L1**：这些未命中会失去跨节点去重。如果某些应用在写事务里大量读缓存，需要允许按缓存关闭（`FillGate` 可配置）。
- **Q5 响应式事务**：需要 `cocache-spring` 可选依赖 reactor（经 spring-tx 的响应式包），依赖变更须先经维护者批准。
- **Q6 摘要算法**：SHA-256/128 不增加依赖；xxHash3-128 更快，但需要新依赖。摘要只在写入路径上计算，倾向前者。
- **Q7 代理与受检异常**：JDK 动态代理会把未声明的受检异常包装成 `UndeclaredThrowableException`（A.P1），这在 `InvocationHandler` 内无法避免。彻底的办法是构建期生成接口实现（与 AOT 一起），代价是一个构建插件。v8 先在文档中写明，生成实现留到 8.x 评估。
- **Q8 派生 `Cache` 是否保留 `put`**：`put` 是后写者胜，与数据源的提交顺序无关（architecture.md §6.1）。保留（Spring Cache 与 write-through 场景需要），文档继续首选 `invalidate`。
- **Q9 `Store` 的 L2**：权威副本的 L2 在事件延迟内仍可能陈旧。是否给 `Store` 默认更短的 `localMaxAge`，或提供不使用 L2 的选项，待用户反馈。

## 12. 对抗验证记录

- 草案 v1（本文）：尚未进行。建议与 RFC 0003 一样至少两轮：一轮从 G1 / G2 攻击 V5（状态机是否引入新的交错）、V6（`compute` 与 Caffeine 的驱逐、过期之间的交互）与 V8（格式 1 与 2 共存期间的脚本）；一轮从迁移与运维的角度攻击 V1（用户能否正确选择类型）、V10 与 §8。

## 附录 A：6.8.0 架构审查发现（2026-10-28）

范围：`cocache-api`、`cocache-core`、`cocache-redis`、`cocache-spring`、`cocache-spring-boot-starter`。“复现”表示用临时用例实际运行观察到；“读代码”表示经代码与依赖库确认（spring-tx 7.0.9 字节码、JDK 语义）。6.x 修复的复现用例在各自的 PR 中进入 TCK 或模块测试。

| 编号 | 严重度 | 发现 | 依据 | 位置 | 去向 |
|------|--------|------|------|------|------|
| D1 | 高 | 没有数据源的 `Cache` 在 L1 读失败（默认 `DEGRADE`）时返回 `null`、不报错，并作为已决的负缓存写入 L2（`degradedLocalMaxAge`）；之后 `get(key, loader)` 命中它，不调用 loader | 复现 | `CoherentEngine.loadThrough`、`completeBypass` | 6.x 修复；v8 §4.1 |
| D2 | 高（权威副本） | 默认 `RETRY` 的延迟重试会删除其它节点之后写入的值 | 复现 | `CoherentEngine.scheduleRetry` | 6.x 修复；v8 §4.1 |
| D3 | 中 | 没取得刷新令牌的节点按往返频率轮询 L1（持有者回源期间，另一节点的 50 次命中触发 25 次 L1 读取）；`ttlOf` 或显式 `ttl` ≤ 刷新窗口时，持有者背靠背回源 | 复现（前者）、读代码（后者） | `CoherentEngine.refill`、`AbstractEngine.localEntry`、`CachePolicy` 的校验 | 6.x 修复；v8 §4.6 |
| D4 | 中低 | L2 填充“校验—写入—复核—撤销”不可线性化：写入方返回之后，旧值仍可能短暂可见；撤销还会误删新副本 | 读代码 | `AbstractEngine.fillLocal` | 6.x 待排期；v8 §4.5 |
| R1 | 中高 | 超时只取消 `exceptionallyCompose` 派生的 future，Lettuce 命令不被取消；Cluster 中等待节点连接的命令在超时之后仍会执行 | 读代码 | `RedisConnections.bounded`、`await`、`evalAsync` | 6.x 修复；v8 §4.6 |
| R2 | 中低 | `sharded` 频道进入未确认状态后只重置一次，之后心跳只重试订阅；陈旧上界退化为 `localMaxAge` | 读代码 | `ShardedInvalidationChannel.ping` | 6.x 修复 |
| R3 | 低 | `subscribe` 与 `Subscription.close` 并发时，订阅者可能加入已被移除的列表，永久收不到失效；经典模式不处理服务端退订 | 读代码 | `RedisInvalidationChannel.subscribe` | 6.x 修复 |
| R4 | 中 | 非原子广播时，条件写入写 L1 成功、广播失败，重放得到 `Stale` 或冲突之后永不广播 | 读代码 | `CoherentEngine.writeIfNewer`、`AbstractEngine.write` | 6.x 修复；v8 §4.6 |
| T1 | 中高 | 事务回滚时不失效；事务内的回源可能把未提交的数据写进 L1 | 读代码 | `TransactionalInvalidator` | 6.x 修复；v8 §4.9 |
| T2 | 中低 | 在另一个同步的 `afterCommit` 中登记的失效会丢失；`flush` 遇到第一个异常即停止。`@TransactionalEventListener(AFTER_COMMIT)` 不受影响（它在 `afterCompletion` 中执行） | 读代码（含 spring-tx 7.0.9 字节码） | `TransactionalInvalidator` | 6.x 修复；v8 §4.9 |
| P1 | 中低 | 接口式代理遇到 Kotlin loader 抛出的受检异常时，由 JDK 包装为 `UndeclaredThrowableException`，与“代理不包装异常”不符 | 读代码（JDK 语义） | `CacheProxies.Handler` | 文档；v8 Q7 |
| C1 | 低 | 时钟用墙钟：墙钟回拨 Δ，L2 副本就多活 Δ | 读代码 | `TickingClock` | 6.x 待排期；v8 §4.5 |
| S1 | 低 | 同步跟随者用 `join()` 等待，不可中断、无上限 | 读代码 | `SingleFlight.await` | 6.x 待排期；v8 §4.6 |
| L1 | 低 | 读 L1 超时之后，不释放读脚本可能已授予的 lease；全集群该 key 在 `lease.ttl` 内每次未命中多等 `lease.wait` | 读代码 | `CoherentEngine.loadThrough`、`RedisRemoteStore.read` | 6.x 修复 |
| L2 | 低 | 同步的 `SetCache.getAll` 逐个 key 串行访问 L1（异步版本是并发的） | 读代码 | `SetEngine.getAll` | 6.x 待排期 |
| L3 | 低 | 策略 `ttl` 永不过期时，`invalidateIfNewer` 的墓碑也永不过期，Redis 内存随删除过的 key 单调增长 | 读代码 | `CoherentEngine.versionedTtl` | 文档；v8 §4.1 |
| L4 | 低 | namespace 只校验非空：`a` 与 `a:b` 可能映射到相同的存储 key，却监听不同的频道 | 读代码 | `CacheSpec` 的 `requireValidName` | 6.x 待排期 |
| L5 | 低 | `RemoteStore.addMembersIfReadable` / `removeMembersIfReadable` 的默认实现先读后写、不原子，也认不出更高的格式版本 | 读代码 | `RemoteStore` | v8 §4.3 |
| L6 | 低 | `@CacheEvict(allEntries = true)` 只清本节点 L2（文档已写明，但没有启动检查）；actuator 的按 key 删除默认可写，对 `SetCache` 会删除唯一副本 | 读代码 | `CoCacheSpringCache.clear`、`CoCacheEndpoint` | v8 §4.9 |
| L7 | 低 | 客户端以有符号的 `raw[1].toInt()` 解析格式字节，脚本用无符号的 `string.byte`（格式号 ≥ 128 时才有差异） | 读代码 | `RedisRemoteStore` | 6.x 修复 |
| L8 | 低 | 策略时长不带单位时按秒、`cocache.redis.*` 按毫秒；`cocache.defaults.*` 覆盖显式声明 | 读代码 | `CoCacheProperties` | v8 §4.8 |
| L9 | 低 | 创建缓存时，`backend.store` 或工厂抛出会让 namespace 保持占用 | 读代码 | `CacheRuntime` 的注册流程 | 6.x 待排期 |

未发现缺陷的部分：Lua 脚本的状态转换与到期时间重设、令牌比较、`READ` 对 `pttl = -1 / -2` 的处理、带符号 64 位的版本比较、Cluster 分组与 CROSSSLOT、`InvalidationFrame` 解码的边界、`readAllAsync` 的完成与 lease 释放、`CacheRuntime.close` 的顺序、`invalidateEncoded` 经过栅栏、`REQUIRES_NEW` 的作用域。
