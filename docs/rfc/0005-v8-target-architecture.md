# RFC 0005：v8 目标架构（权威与派生分型、单一协议核心、可检验的栅栏）

- 状态：**草案 v2**（2026-10-29）。v1（2026-10-28）经第一轮对抗验证（一致性、迁移与运维两路，共 28 条发现，全部处理，见 §12）修订为本稿；待第二轮对抗验证与维护者审阅。目标版本由维护者指定为 **v8**
- 前提：v8 是大版本，API、SPI、配置项与线格式都可以破坏；但三个目标（[architecture.md](../architecture.md) §1 的 G1–G3）与 6.0 的核心协议（lease 栅栏、两次递增的失效戳、原子广播）**不变**。本 RFC 不推翻 [RFC 0003](0003-target-architecture.md)，而是修正它在 6.4–6.8 增量演进中暴露的结构性问题
- 关系：7.0 维持既定范围（删除 6.x 的弃用项），本 RFC 不改变它；v8 的前置工作（内部重构、v8 形状的 SPI、读格式 2、配置别名与弃用告警）放在 7.x 小版本（§10）。§9 列出不破坏兼容、可在 6.x 先行的部分。**迁移的基线是含 6.x 修复（#697、#698、#700、#701）的 7.x 末版**，不是 6.8（§8）。规范仍以 architecture.md 为准，本 RFC 实施时同步修改它
- 来源：2026-10-28 对 6.8.0 的架构审查（附录 A）。其中 3 个缺陷用临时用例实际复现，其余经读代码确认；可先行的修复已在各自的 PR 中进行

---

## 0. 结论摘要

| # | 决定 | 一句话理由 |
|---|------|-----------|
| V1 | **权威与派生分成不同的类型，按“L1 中没有这个 key，是否就等于数据不存在”判断**：是，则为 `Store` / `VersionedStore` / `SetStore`（权威副本）；否（权威在别处），则为 `Cache` / `VersionedCache`（派生副本）。派生缓存**必须声明**数据从哪里来：spec 的数据源，或 `callerPopulated()`（由调用方经 `put` 或 Spring Cache 写入）；接口式声明既没有匹配到数据源、也没有声明 `populatedBy = CALLER` 时启动失败。默认值、失败语义与可用的写操作由类型与声明决定 | 6.x 在至少 6 处按“这次读取有没有数据源”在运行时分支（A.D1、A.D2 都是漏掉了其中一处）；v1 按“调用了哪些方法”分型，会把语义相同的缓存分到不同类型，而 starter 对“没有数据源”的判断来自 bean 类型匹配，不是声明（§12 M1） |
| V2 | **`Unknown` 只表示故障**：读取结果是 `Present` / `Absent` / `Unknown`。`Unknown` 只来自 L1 故障、等待超时这类“这次读取没有答案”的情形；`get` / `getAll` 遇到它默认抛出 `CacheReadException`（与 #700 一致），`lookup` 返回它；需要故障放行的缓存显式声明 `unknownRead = ABSENT`。`callerPopulated` 的缓存在 L1 中未命中是“未填充”：对不带 loader 的读取就是 `Absent`，可以受戳保护进入 L2，带 loader 的调用方不接受它 | v1 把“正常未命中”与“故障”归为一类：前者不能再进 L2，以未命中为主的热路径每次都要一次往返；后者经 `get(): V?` 变成 `null`，在调用点上重现 A.D1（M2） |
| V3 | **能力即类型**：条件写入、按版本写入、元素写入只出现在声明了它的类型上；删除全部默认抛出 `UnsupportedOperationException` 的方法，`async()` 成为抽象方法。`SetStore` 不是 `Cache` 的子类型；`VersionedCache` / `VersionedStore` 与 `Cache` / `Store` 是兄弟类型，**带版本的类型上没有丢失版本的写入**：派生的带版本副本用保留版本栅栏的 `evict`，运维清除用 `purge` | `Cache` 有 9 个方法默认抛出，`SetCache` 继承 `Cache` 却拒绝其中约一半；v1 让 `VersionedCache.invalidate` 默认重试、让 `VersionedStore` 继承 `Store.put`，都会撤掉版本栅栏（C6、M12） |
| V4 | **SPI：异步为主形状、阻塞为显式的第二形状，能力用访问器表达**：`RemoteStore` 返回 `CompletionStage`；只有阻塞实现的后端实现 `BlockingRemoteStore`。可选能力由 `conditional()` / `versioned()` / `sets()` 返回可空的子接口，装饰器可以逐个包装。TCK 要求后端声明期望的能力并断言它们存在。删除 `versionedWrites` 标志与非原子的默认实现；7.x 末版先提供 v8 形状的 SPI 与参数化 TCK | 6.x 用两处表达同一事实（抛出的默认方法 + 布尔标志），还有一处违反自身原则的非原子默认实现（A.L5）；v1 的 `is` 检测与装饰器不相容，“实现了接口才测”的 TCK 是 fail open 的（M8） |
| V5 | **一份协议、两个驱动**：协议做成不含 I/O、线程与时钟的状态机（sans-IO），产出包括 `FillLocal` 在内的全部指令，接受逐 key 的戳与当前时间；驱动放弃状态机时调用 `abandon` 取得要释放的令牌。合并、读己之写重试与结果的合并规则放在两个驱动共用的 flight 层 | `CoherentEngine` 2248 行，同步与异步逐分支复制；v1 的状态机把 `fillLocal` 与合并留在驱动里，那恰好是性质测试看不到、6.x 同步与异步各写一遍的部分（C1、C7、C9） |
| V6 | **L2 填充在 key 锁内校验并写入，写后按身份复核**：在 Caffeine `compute` 内校验失效戳并写入；返回后戳若已变化，以 `remove(k, entry)` 撤销自己写入的那一份 | 关闭 A.D4 对写入方与远端事件的窗口；Caffeine 的 `clear()` 按快照删除，看不到进行中的 `compute`，只靠 `compute` 对重置不成立（C4） |
| V7 | **失败路径与成功路径一样确定**：写入失败区分“确定未执行”与“结果未知”；结果未知的写入随时可能生效，安全只能来自顺序（令牌、版本、条件）；派生 `put` 结果未知时登记补偿失效；令牌守恒覆盖 `reclaim` 与驱动放弃状态机；每个 flight 在有限时间内完成；等待有界，只有跟随者的等待可中断；刷新的触发率与命中率无关 | v1 的“超时取消到底层”对已写出的命令不成立（C5）；A.R1、A.R4、A.D2、A.D3、A.S1、A.L1；C7、C12 |
| V8 | **线格式 2：按需扩展的头部**：核心 36 字节的字段与偏移不变；标志位声明版本段（8 B）与摘要段（20 B 的 SHA-1，与 `Digest` 相同）；未知标志位等同于更高格式。lease 占位、负缓存与派生缓存的普通值继续写格式 1。带版本的内容可以持有刷新令牌，此时 `fill` 比较版本：不后退、不抹掉。逐个脚本规定两种格式的处理（§4.7）；回退下限是 7.x 末版，v8.0 提供格式 2 → 1 的降级工具 | 6.8 把版本塞进令牌位置，带版本的缓存因此不能提前刷新；`getIfChanged` 每次在 Lua 中读入载荷并计算 SHA-1（8 MB 约 20 ms）。v1 换摘要算法会破坏 `Digest` 的使用者（M4），`fill` 只比较令牌会让版本后退（C3），脚本没有逐个规定（C8、C10） |
| V9 | **栅栏可被机器检验**：Lincheck（操作集含 `onReset` 与 `clearLocal`）、TLA+（含跨连接的重排、本节点帧的过滤、刷新令牌 + 版本）、在 sans-IO 核心上做确定性多节点仿真（含驱动放弃状态机、leader 被中断） | TCK 的竞态用例只覆盖人想到的交错；A.D4、A.R1，以及第一轮的 C4、C5 都需要搜索才能稳定发现 |
| V10 | **配置分三层，字段级合并**：`cocache.defaults.{cache,store}`（低于声明）→ 声明 → 定制器 → `cocache.overrides.{cache,store}`（高于声明，供运维全局生效）→ `cocache.caches.[name]`。每节只允许适用的键；时长必须带单位（注解改为带单位的字符串）；`key-prefix` 不参与分层，仍是配置优先。端点给出每个字段的生效值与来源；库用受支持的 `CoCacheFactory` 装配，并可锁定字段 | 6.x 的 `cocache.defaults.*` 覆盖显式声明；v1 反过来又拿走了运维只改配置就能全局生效的手段（M5）；配置键迁移会被 Spring Boot 静默忽略（M6）；CoSec 与 Wow 依赖兼容承诺之外的装配入口（M9） |
| V11 | **Spring：写事务内、已登记失效的 key 的回源结果是私有的**（不写 L1、不写 L2、不经 SingleFlight 共享），登记时立即在本节点递增戳并淘汰；可选的严格模式让写事务内的全部回源都私有。`TransactionalInvalidator` 在事务结束时（提交或回滚）失效；指向 `Store` / `SetStore` / `VersionedCache` 的 Spring 缓存操作启动失败；端点默认只读，并提供只广播的失效 | loader 在调用方的事务内执行（A.T1）；v1 只挡住 L1，脏数据仍进 L2 并被共享（C2）；对全部写事务一律拦截，会让类级 `@Transactional` 的应用 L1 永远填不满（M7） |
| V12 | **规范只写现行行为**：architecture.md 去掉版本叙述，不变量编号稳定（`I-n`），代码与测试引用编号；§6 以对照表逐条标注 architecture.md §14 的“取代 / 修改 / 保留” | 规范条文里混着“6.3.1 之前……”一类的叙述；v1 只写了“其余不变量保留”，而其中 6 条与本 RFC 冲突（C13） |

---

## 1. 第一性原理

[RFC 0003 §1.1](0003-target-architecture.md) 的六条公理（串行化点、令牌先于数据、权威发出失效、TTL 是安全网、命中路径只做必要的事、声明与实例分离）全部保留。6.4–6.8 的演进与第一轮验证补出下面六条。

- **A7 权威性决定失败语义**。副本可以丢、可以降级，因为权威还在别处；唯一的副本不能丢，也不能降级成“不存在”。删除副本只在写入方之间没有顺序要求时才总是安全：按版本推送时，L1 中的副本本身就是顺序的仲裁者，删除它就撤掉了版本栅栏（C6）。一个默认值如果在两种数据上应当不同，它就不该是同一个类型上的同一个默认值。
- **A8 “没有答案”不是“不存在”**。`Absent` 是关于数据的事实，可以缓存、可以广播；`Unknown` 是关于这一次读取的事实，只能返回给这一次调用，也不能交给另一个本可以自己回源的调用方。把后者写成前者，就是把一次故障缓存成了数据。
- **A9 一个协议只写一次**。协议的每个分支有两份实现，就有两倍的出错面和两倍的证明负担。阻塞还是回调、在哪个线程上继续，是驱动的事，不是协议的事。
- **A10 失败路径也是协议**。超时、取消、拒绝、崩溃之后系统处于什么状态，要和成功、失败一样确定：取得的令牌必须释放或过期；延迟执行的写入不能越过之后的写入。
- **A11 不变量要能被机器检验**。手写的交错只覆盖想到的交错；能被模型检验或仿真搜索的不变量才是可信的不变量。
- **A12 “结果未知”不是失败**。已经写出的命令在任何时刻都可能生效，可能排在其它连接上更晚发出的写入之后。重试、补发与幂等只能缩小窗口；让迟到的执行无害的，只有顺序：令牌、版本、条件（C5）。

## 2. 现状审查（6.8.0）

先说结论：6.0 的核心协议经受住了审查。L1 的 lease 与刷新令牌、Lua 状态机、带符号 64 位的版本比较、Cluster 分组、帧解码都没有发现缺陷；SingleFlight“先注销再发布”与读己之写的论证成立。问题集中在协议之外，可以归为下面七个结构性原因，每个原因都至少产生了一个缺陷（编号见附录 A）。6.x 的修复（§9）已经在各自的 PR 中处理了其中可以不破坏兼容的部分。

### 2.1 权威与派生混在一个类型里

6.4–6.8 新增的能力（`foreignFormat`、`SetCache` 的 fail closed、`getIfChanged` / `putIfMatch`、`putIfNewer`）服务于“L1 是唯一副本”的用法：CoSec 的策略快照文档与撤销表、Wow 的聚合状态视图、`SetCache`。它们都挂在 `Cache` 上，靠“这次读取有没有数据源”决定行为：

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

而“有没有数据源”本身也不是声明：接口式声明按 bean 类型匹配 `CacheLoader`，泛型不匹配时静默地成为“没有数据源”（`AnnotatedCacheFactory.sourceOf`，M1）。

### 2.2 能力靠运行时异常表达

- `Cache` 的 9 个方法默认抛出 `UnsupportedOperationException`（`getIfChanged`、`putIfMatch` ×2、`putIfNewer` ×2、`invalidateIfNewer` ×2、`getVersioned`、`async`），`AsyncCache` 同样。
- `SetCache` 是 `Cache<K, Set<E>>` 的子类型，却拒绝 `invalidate`、`invalidateAll`、`get(key, loader)` 与全部条件、版本方法。接受 `Cache` 的组件（`TransactionalInvalidator`、Spring 的 `CacheManager`）因此能接收 `SetCache`，然后在运行时失败，有时是在事务提交之后。
- 带版本的缓存仍然暴露 `put` / `invalidate`，它们静默丢掉版本顺序（architecture.md §6.5“丢失版本的写入”）。
- SPI 用两处表达同一事实：抛出的默认方法 + `CoherenceBackend.versionedWrites`。`RemoteStore.addMembersIfReadable` 的默认实现先读后写、不原子（A.L5），与 `putIfMatch`“宁可不支持，也不提供先读后写”的原则相反。

### 2.3 协议有两份实现

- `CoherentEngine` 2248 行、`SetEngine` 497 行：同步路径与异步路径“逐分支一一对应”（architecture.md §5.6），共用的只有判定函数（`hitOutcome`、`batchStep`、`refreshStep`、`completeLoad`、`putsOf`）。合并结果的取舍（`usable()`、#700 的 `Loaded.failure`）与读己之写重试也各写一遍。
- SPI 有同步与异步两套（`RemoteStore` / `AsyncRemoteStore`、`InvalidationChannel` / `AsyncInvalidationChannel`），外加 `BlockingAsyncStore` 适配；TCK 有同步与 `Async*` 两套。
- 为了让异步链少派发几次，`Futures` 引入了基于线程局部变量的就地续接（`continueOn`、`withoutInline`、深度上限 16），重入守卫与 SingleFlight 都要与之配合。它是正确的，但它是两份实现带来的代价，不是协议本身的需要。

### 2.4 失败路径不够确定

- **A.R1**：Redis 命令超时时，引擎取消的是 `exceptionallyCompose` 派生的 future，不是 Lettuce 的命令。Cluster 中等待建立节点连接的命令不计时，超时后仍会被写出执行：调用方已经看到失败的 `put`，稍后却生效并广播。
- **A.R4**：非原子广播时，条件写入写 L1 成功、广播失败；调用方按文档重放，得到 `Stale` 或冲突，于是永不广播。
- **A.L1**：读 L1 超时时，读脚本可能已经授予了 lease，没有人释放它；`reclaim` 超时同理（C7）。
- **A.S1**：同步 SingleFlight 的跟随者用 `join()` 等待，不可中断、无上限。
- **A.D3**：提前刷新在没取得令牌的节点上按往返频率轮询 L1（实测：持有者回源期间，另一节点的 50 次命中触发了 25 次 L1 读取）；寿命 ≤ 刷新窗口时，持有者背靠背回源。
- 即使超时能取消到底层，已经写出的命令也照常执行，而且可以排在其它连接上更晚的写入之后（C5）。6.x 把这类写入当作“幂等，可以安全重试”（architecture.md §7），这对无条件写入不成立。

### 2.5 检验手段只覆盖想到的交错

TCK（`EngineSpec`、`MultiNodeSpec`、`FaultSpec`）以 latch 编排的交错是本项目的优势。但 **A.D4**（L2 填充的“复核—撤销”窗口）与 **A.R1**（超时之后的执行）都是读代码发现的，没有现成的用例能发现它们。

### 2.6 线格式的余量已经用完

- 标量头只有 1 个保留字节。6.8 的版本只能放进令牌位置（architecture.md §3.3），代价是带版本的内容不能持有刷新令牌，`CacheSpec` 因此拒绝 `versioned` 与 `refreshWindow` 同时出现（§6.5）。
- 摘要不在头部，`getIfChanged` / `putIfMatch` 每次都把载荷读进 Lua 并计算 SHA-1：Redis 7.4 上 8 MB 的值一次约 20 ms，期间不处理其它命令（§6.4）。§6.4 已把“写入时保存摘要”列为“留到下一次格式演进再考虑”。

### 2.7 配置分层与单位

- `cocache.defaults.*` 覆盖声明中显式写出的值（architecture.md §9.4 的优先级），于是需要 `setPolicyFor`（对 `SetCache` 跳过 5 个字段）与 `versionedPolicyFor` 逐类排除。
- 策略时长不写单位时按秒，`cocache.redis.*` 按毫秒（A.L8）：同样写 `200`，`lease-wait` 是 200 秒。
- 库在 starter 之外装配缓存时，只能依赖兼容承诺之外的入口（CoSec 用 `CoCacheProperties.policyFor` + `CacheProxies.create`，Wow 用 `CacheAssembler`），也没有办法保护库自己的不变量（M9）。

## 3. 目标架构总览

### 3.1 用户 API 的类型

```
ReadableCache<K, V>             name、get、getAll、lookup、async()
├── Cache<K, V>                 派生副本：get(k, loader)、put、putAll、invalidate、invalidateAll
├── VersionedCache<K, V>        派生的带版本副本：getVersioned、putIfNewer、invalidateIfNewer、evict、purge
├── Store<K, V>                 权威副本：put、putAll、delete、deleteIfMatch、getIfChanged、putIfMatch
├── VersionedStore<K, V>        权威的带版本副本：getVersioned、putIfNewer、deleteIfNewer、purge
└── SetStore<K, E>              权威集合（ReadableCache<K, Set<E>>）：add、remove、clear、put、putAll
```

- 每个接口只声明它支持的操作，全部是抽象方法。异步视图与挂起视图有同构的层次（`AsyncCache`、`AsyncStore`……）。带版本的类型不继承无版本的写入。
- `Lookup` = `Present` | `Absent` | `Unknown`（V2，§4.1）。Kotlin 中以表达式使用的 `when` 会因新增的 `Unknown` 编译失败，这是有意的；Java 调用方见 §8。
- spec 与返回类型一一对应：`cacheSpec` → `Cache`（必须声明 `loader`、`asyncLoader` 或 `callerPopulated()` 之一），`versionedCacheSpec` → `VersionedCache`（必须有带版本的数据源；没有数据源的带版本缓存就是 `VersionedStore`），`storeSpec` → `Store`，`versionedStoreSpec` → `VersionedStore`，`setStoreSpec` → `SetStore`。
- 组合视图拆成两种：`JoinView`（只读，两侧是 `ReadableCache`）与 `JoinCache`（两侧都是 `Cache`，保留 6.x 的 `invalidate(key)` 与 `invalidate(firstKey, joinKey)`）（M14）。

### 3.2 内部结构

```
            ┌───────────── 命中路径（手写，不经状态机） ─────────────┐
用户调用 ──▶│ L2 查找 + 过期比较 → 返回                              │
            └───────────────┬─────────────────────────────────────────┘
                            │ 未命中 / 写入
                            ▼
            ┌───────────── flight 层（两个驱动共用） ─────────────────┐
            │ 注册 → 取戳 → 运行状态机 → 比较戳 → 至多重试一次           │
            │ 合并规则：Unfilled / Unknown / Private 不交给带数据源的调用方 │
            └───────────────┬─────────────────────────────────────────┘
                            ▼
            ┌───────────── 协议核心（sans-IO 状态机） ───────────────┐
            │ MissMachine、BatchMachine、RefreshMachine、WriteMachine │
            │ 输入：上一条指令的结果与当前时间；输出：下一条指令        │
            └───────────────┬─────────────────────────────────────────┘
                ┌───────────┴───────────┐
                ▼                       ▼
         BlockingDriver            AsyncDriver
   （调用方线程上执行指令）  （future 链执行指令，定时器等待）
                └───────────┬───────────┘
                            ▼
      RemoteStore（异步）/ BlockingRemoteStore、InvalidationChannel、L2、失效戳
```

模块划分与依赖方向（architecture.md §2）不变。

## 4. 核心设计

### 4.1 权威与派生（V1、V2）

**选择类型只问一个问题**：L1 中没有这个 key，是否就等于数据不存在？

- 是：`Store`（标量）、`SetStore`（集合）、`VersionedStore`（按版本写入）。例：CoSec 的策略快照与控制面的唯一副本缓存，以及两个撤销表（`RevokedTokenCache`、`RevokedSubjectCache`：L1 中没有就是“未撤销”，这是确定的答案）。
- 否，权威在别处：`Cache`、`VersionedCache`。例：有数据源的业务缓存；Spring Cache 背后的缓存（被注解的方法就是数据源，`callerPopulated()`）；Wow 的状态视图（`versionedLoader` 从事件存储加载）。

读失败时放行还是拒绝，**不是类型的属性**，由 `unknownRead` 在声明中写明（见下）。

| 方面 | `Cache`（派生副本） | `Store` / `VersionedStore` / `SetStore`（权威副本） |
|------|--------------------|----------------------------------|
| 数据来源 | 必须声明：spec 的 loader / asyncLoader，或 `callerPopulated()` | 按构造没有 |
| L1 中不存在 | 有数据源：回源；`callerPopulated`：“未填充”（对不带 loader 的读取是 `Absent`，受戳保护进入 L2，限龄 `absentTtl` 与 `localMaxAge`；带 loader 的调用方不接受它，用自己的 loader 回源） | `Absent`，受戳保护进入 L2（限龄 `absentTtl` 与 `localMaxAge`） |
| lease | 未命中时申请 | **不申请**；读到 lease 占位就是 `Absent`（与 `putIfMatch(expected = null)` 把 `L` 当作“没有值”一致，C11） |
| L1 读失败 | 有数据源：降级回源（`degradedLocalMaxAge`）；`callerPopulated`：`Unknown` | `Unknown` |
| `Unknown` 的处理 | `get` / `getAll` 默认抛出 `CacheReadException`（`cause` 为原始异常），`lookup` 返回 `Unknown`；声明 `unknownRead = ABSENT` 时返回 `Absent`，不缓存、不广播，计入 `cocache.reads.unknown` | 同左 |
| 读不懂的内容 | 有数据源：`RECLAIM`（条件回收后回源）；`callerPopulated`：`FAIL` | `FAIL` |
| 删除 | `invalidate`：删除副本。失败时，有数据源默认 `RETRY`；`callerPopulated` 默认 `THROW`（与 #700 一致，显式配置 `RETRY` 照常生效） | `delete` / `deleteIfMatch`：同步完成，失败抛出 `CacheWriteException`，**从不排队重试** |
| 写入 | `put`（后写者胜，不推荐；首选先提交数据源再 `invalidate`） | `put`、`putIfMatch`（摘要比较）；带版本的类型只有按版本写入 |
| 负缓存 | 回源返回 `null` 时写入 `M` | 不适用：`Absent` 就是不存在 |
| 默认 `ttl` | 1 h | 永不过期；`VersionedStore` 的墓碑另有有限的 `tombstoneTtl`（默认覆盖重投的时间窗口，M10、A.L3） |
| 提前刷新 | 有数据源时支持 | 不适用 |
| 启动检查 | — | 驱逐策略：见下 |
| Spring Cache、`TransactionalInvalidator` | 可接入（`VersionedCache` 除外） | 不可接入 |

- `callerPopulated` 的默认值（读失败抛出、`invalidate` 失败抛出、读不懂的内容 `FAIL`）沿用 #700 在 6.x 中的决定。没有数据源的语义只决定一次，从 6.9 到 v8 不再翻转（M3）。
- `unknownRead = ABSENT` 是 CoSec 撤销表这类“故障时放行”的唯一表达方式：它在声明中、可以审计、计数，并且不缓存。6.8 的放行（同时缓存成不存在）与 #700 的“总是抛出”都不能表达它（§9 列出 6.x 中的对应调整）。
- **合并规则**（C1）：flight 的共享结果是 `Present` / `Absent` / `Unfilled` / `Unknown` / `Private`（§4.9）之一。带数据源的调用方（含调用级 loader）不接受 `Unfilled`、`Unknown`、`Private`：按 6.x 的规则重试一次，仍不可用时绕过 SingleFlight 自己运行状态机。`Unknown` 不写入任何一层、不广播。这把 6.x 的 `usable()` 与 #700 的 `Loaded.failure` 写成了一条规则。
- `Store` 的删除不排队重试，是 A7 的直接推论：延迟执行的无条件删除会越过之后的写入（A.D2）。需要可靠删除的写入方用 `deleteIfMatch` 或 `deleteIfNewer`，它们迟到执行也不会越过之后的写入（A12）。
- `Store` 的 L2 仍是只读副本，栅栏规则不变；需要跨节点读己之写的调用方用 `getIfChanged` 确认（用法同 architecture.md §6.4）。
- **驱逐策略检查**：改读 `INFO memory` 的 `maxmemory_policy`（托管 Redis 通常禁用 `CONFIG`，但允许 `INFO`）；读不到时按 `cocache.redis.assume-maxmemory-policy` 处理，没有配置则启动失败，不再静默跳过。按声明（bean 定义）检查，而不只检查启动时已创建的缓存。`allkeys-*` 时启动失败；`volatile-*` 且存在 `ttl` 有限的权威缓存时告警（M11）。

### 4.2 能力类型（V3）

- 删除 `Cache`、`AsyncCache`、`SetCache` 与挂起视图上全部抛出的默认方法，`async()` 改为抽象方法。代理（`CacheProxies`）按接口的实际方法集分发；新增方法时，编译器与 `CacheMethods` 校验会同时报错，不再有“漏转发即继承抛出”的情况。
- 接受缓存的组件按需要的能力声明参数类型：`TransactionalInvalidator` 与 Spring 的 `CacheManager` 只接受 `Cache`，不再能接收 `SetStore`、`Store` 或 `VersionedCache`。
- **带版本的类型上没有丢失版本的写入**（C6、M12）：
  - `VersionedCache`：`putIfNewer`、`invalidateIfNewer`（墓碑）、`evict`（保留版本栅栏的失效：L1 中的内容换成“带同一版本、没有值”的状态，读作未命中；之后的回源与 `putIfNewer` 都必须不低于这个版本；编码见 §4.7，细节留给第二轮，Q13）、`purge`（运维清除，丢掉版本，只经端点或显式调用，默认不重试）。`evict` 失败默认抛出。
  - `VersionedStore`：`putIfNewer`、`deleteIfNewer`、`purge`；没有 `put`、`delete`、`putIfMatch`。
- `VersionedCache` 的数据源只能是带版本的数据源（`versionedLoader` / `versionedAsyncLoader`）：没有版本的回源值会抹掉版本（C3）。

### 4.3 SPI（V4）

```
CoherenceBackend         store(namespace): RemoteStore、channel、format、atomicBroadcast
RemoteStore              read、readAll、fill、fillAll、release、reclaim、put、putAll、invalidate（返回 CompletionStage）
                         conditional(): ConditionalRemoteStore?   readIfChanged、putIfMatch、deleteIfMatch
                         versioned(): VersionedRemoteStore?       putIfNewer、evictKeepingVersion
                         sets(): SetRemoteStore?                  addMembers、removeMembers（只有“检查并写入”一种原子语义）
BlockingRemoteStore      同样的操作与能力访问器，阻塞返回；RemoteStore.of(blocking, executor) 适配
InvalidationChannel      publish、subscribe（返回 CompletionStage）
```

- **两种形状，都是一等的**。异步形状是主形状：同步驱动在调用方线程上等待返回的 future，不在 I/O 线程上执行任何续接（与 6.x 相同）。只有阻塞实现的后端实现 `BlockingRemoteStore`：同步驱动直接在调用方线程上调用它（没有线程切换），异步驱动把调用放到阻塞执行器上。阻塞调用一旦开始就无法取消，所以阻塞形状的超时一律按“结果未知”处理（§4.6），这是它相对 I-E 的豁免。TCK 按“形状 × 驱动”参数化，两种形状都覆盖。
- **能力用访问器表达**（M8）：不支持时返回 `null`，不抛出。装饰器（故障注入、追踪、路由）可以逐个能力包装，不需要为每种组合写一个类，也不会静默剥掉能力。`runtime.create` 在创建时检查所需的能力（`Store` 需要 `conditional()`，带版本的类型需要 `versioned()`，`SetStore` 需要 `sets()`），缺失时抛出，不注册任何东西。SPI 不提供任何非原子的默认实现。
- **TCK fail closed**：后端在规范中声明 `expectedCapabilities`，规范先断言存储确实提供了它们，再运行对应的用例；声明了却没有提供，用例失败而不是跳过（取代 6.x 的 `supportsConditionalWrites` 开关）。
- **异步实现的契约**写进 SPI 的 KDoc 与 TCK：不同步抛出；写入的 future 在它发出的全部命令结束之后才完成（architecture.md §4.2）；超时取消到底层命令，已写出的命令按“结果未知”完成（`outcomeUnknown`）。
- **过渡**：7.x 末版同时提供 v8 形状的 SPI 接口与参数化的 TCK，旧接口与 `Async*` 规范标记弃用。后端作者可以先在 7.x 上迁移实现与测试，再升级到 v8，避免 SPI 与 TCK 在同一天一起变。
- [RFC 0004](0004-async-api.md) A5 当年选择“同步 SPI + 异步子接口”，理由是小版本里不能破坏第三方后端；大版本里这个理由不成立。

### 4.4 协议核心与 flight 层（V5）

```kotlin
internal sealed interface Step {
    class Read(val key: String, val lease: LeaseRequest?, val refresh: RefreshRequest?) : Step
    class ReadAll(val keys: List<String>, val leases: Map<String, LeaseRequest>) : Step
    class Load(val keys: List<Any>, val operation: LoadOperation) : Step
    class Fill(val fills: List<me.ahoo.cocache.api.spi.Fill>) : Step
    class Release(val key: String, val token: Token) : Step
    class Reclaim(val key: String, val digest: Digest, val lease: LeaseRequest) : Step
    class FillLocal(val fills: List<LocalFill>) : Step        // 每个 key 的副本与读取之前取得的戳；驱动只执行
    class Wait(val untilNanos: Long) : Step                   // lease 退避：同步驱动 park，异步驱动交给定时器
    class AcquirePermit(val timeoutNanos: Long) : Step
    class Done(val result: FlightResult) : Step               // Present | Absent | Unfilled | Unknown | Private
    class Fail(val error: Throwable) : Step
}

internal interface Machine {
    fun start(nowNanos: Long, stamps: Map<String, Long>): Step     // 逐 key 的戳：批量读的每个 key 各自注册、各自取戳
    fun resume(outcome: Outcome, nowNanos: Long): Step             // 截止时间按当前时间判断，往返时间计入等待上界
    fun abandon(cause: Throwable): List<Step.Release>              // 驱动不再调用 resume 时，取得需要释放的令牌
}
```

- **状态机**不知道线程、执行器、时钟与 SingleFlight：时间与失效戳作为输入，等待与 L2 填充都是指令。“L2 填充使用读取之前取得的戳”因此可以在状态机上检验（C9）。
- **flight 层**由两个驱动共用，包含 6.x 中同步与异步各写一遍的全部逻辑：读取 `observed` → 注册 → 取戳 → 运行状态机 → 比较 `stamp ≥ observed` → 至多重试一次 → 按合并规则（§4.1）决定是否自行运行。它由 Lincheck 检验（§4.10）。
- **驱动**只做两件事：执行指令、按自己的方式等待。驱动在任何不再调用 `resume` 的路径上（执行器拒绝、运行时关闭、取消之外的放弃）调用 `abandon`，并在完成线程上就地执行返回的释放，不经执行器（与 6.x 写入方的第二次递增同理，C7）。驱动在这些路径上同样完成 SingleFlight 的 future：每个注册的 flight 都在有限时间内完成，否则跟随者超时之后，新来的调用仍会合并到一个永不完成的调用上。
- **命中路径**（`entryOf`、`localHit` 与异步命中）留在引擎里手写，不分配状态机，G3 不受影响。每次未命中多分配一个状态机与若干指令对象，预算见 §7。
- **性质测试**：对任意的结果序列，并在任意一步调用 `abandon`，断言“取得的令牌最终被填充或释放”“填充出示的令牌取得于读取之前”“`FillLocal` 的戳取得于读取之前”“`Unknown` 不出现在 `FillLocal` 与 `Fill` 中”。
- **迁移方式**：7.x 内部重构，API 不变，每条路径一个 PR（单 key 读 → 批量 → 刷新 → 写入），现有同步与异步 TCK 证明行为等价，JMH 把关（§10）。

**备选：异步视图直接在虚拟线程上运行同步路径。** 否决为 v8 的主方案：基线仍是 JDK 17（Q3）；JDK 21–23 上 Caffeine 与 `ConcurrentHashMap` 内部的 `synchronized` 会钉住载体线程（JEP 491 在 JDK 24 才解决）；有了 sans-IO 核心之后，异步驱动本身很小，虚拟线程省不下多少代码。

### 4.5 L2：栅栏、时钟与 key（V6）

**填充**：

```kotlin
local.asMap().compute(storageKey) { _, current -> if (stamps.isValid(storageKey, stamp)) entry else current }
if (!stamps.isValid(storageKey, stamp)) {
    local.asMap().remove(storageKey, entry)   // LocalEntry 不重写 equals：按身份撤销自己写入的那一份
}
```

- **对写入方与远端事件，严格成立**（第一轮已对照 Caffeine 3.2.4 源码确认，§12 的 C4 与“成立的论断”）：单个 key 的淘汰经 `computeIfPresent`，与 `compute` 在同一个桶的监视器上串行化。若校验发生在第二次递增之前，写入在锁内完成，写入方的第二次淘汰等锁释放之后执行，写入方返回之前副本已被淘汰；若校验发生在第二次递增之后，校验失败。写入方返回之后，读取开始于修改之前的副本不再可见，A.D4 的窗口关闭。
- **对重置与 `clearLocal`，以填充方自己的这一步为界**：`invalidateAll()` 经 `clear()` 对 `data.values()` 的快照逐个删除，看不到空桶中进行中的 `compute`（`ReservationNode`）。所以重置之后，一个在重置之前完成校验的旧副本可能短暂可见，直到填充方的写后复核撤销它。需要严格成立时，用读写锁把“`compute`”与“递增 + `clear`”分开（Q11）。
- 条件撤销只删除自己写入的那一份，不再误删别的线程刚写入的新副本。驱逐、过期与维护任务都按节点身份删除，不会让被淘汰的旧副本重新可见。
- 只影响未命中路径，可在 6.x 先行（§9）。

**单调的时钟**：`TickingClock` 以启动时的墙钟为基准，加 `System.nanoTime()` 的增量。L2 的寿命因此不受墙钟回拨或跳变影响（6.x 中墙钟回拨 Δ，L2 副本就多活 Δ，A.C1）。`CacheClock` 接口不变，`Lookup.expiresAt` 仍是纪元时间。可在 6.x 先行。

**L2 的 key 不含前缀（待 JMH）**：每次 L2 命中都要拼接 `storagePrefix + encode(key)`，分配一个新字符串，并在 Caffeine 中按全长计算哈希、比较内容。改用编码后的 key（`String` key 时就是调用方传入的实例，哈希已缓存），失效戳与 SingleFlight 同样按它索引；收到失效帧时剥掉本缓存的前缀，前缀不符的 key 忽略（与 6.x 中“匹配不到任何副本”的效果相同；帧中仍是完整的存储 key）。只有 `scripts/jmh-compare.sh` 在 1 与 8 线程上（含 `l2HitMixed`）显示不退化时才采用。不破坏兼容，可在 6.x 先行。

### 4.6 失败路径（V7）

| 规则 | 反例 |
|------|------|
| 写入失败区分“确定未执行”（命令在写出前被完成、连接拒绝）与“结果未知”（已写出之后超时、阻塞形状的超时）：`CacheWriteException.outcomeUnknown` | A.R1、C5 |
| 结果未知的写入在任何时刻都可能生效，可能排在其它连接上更晚的写入之后。迟到无害的只有有序的写入：`fill`（令牌）、按版本写入（版本）、条件写入（摘要）。无条件写入的重放只缩小窗口，不保证顺序 | C5、A12 |
| 派生的 `put` / `putAll` 结果未知时，为这些 key 登记补偿失效：复用 `RETRY` 的 pending 机制（期间本节点的未命中绕过 L1），重试的 `DEL` 走同一条连接，排在被推迟的写入之后。陈旧上界：补偿失效完成之前，以这次 `put` 的 `ttl` 为界 | C5 5a |
| 结果未知的 key，在 `3 × commandTimeout` 内不过滤本节点关于它的帧：迟到生效的写入广播的帧会淘汰本节点的副本 | C5 5b |
| `Store`：提供 `deleteIfMatch`；文档写明“当前摘要等于自己写入的摘要”只能证明已写入，不能证明未写入；摘要比较在迟到执行下有 ABA，需要不可重复的修订号时见 Q10 | C5 5c–5e |
| `SetStore` 的元素写入在结果未知时，按“每个元素后写者胜”收敛，没有有序的重放方式；文档写明 | C5 5e |
| 令牌守恒：`Read`、`ReadAll`、`Reclaim`、刷新的 `Read` 在每个失败转移上都有释放；超时也尽力释放（令牌不匹配时 release 无害）；驱动放弃状态机时执行 `abandon` 返回的释放 | A.L1、C7 |
| 每个注册的 flight 在有限时间内完成 | C7 |
| 无条件的延迟写入只有一种：有数据源的派生副本的 `invalidate` 重试 | A.D2 |
| 等待有上界：同步与异步的跟随者共用 `load.timeout`（默认 30 s，与 6.x 的 `asyncTimeout` 合并为一个字段），超时以 `SourceLoadTimeoutException` 失败，leader 不受影响 | A.S1 |
| 只有跟随者的等待响应中断，且只影响它自己；leader 被中断时，flight 以 `SourceLoadInterruptedException` 失败，跟随者按合并规则自行运行，leader 恢复中断标志并释放令牌 | C12 |
| 非原子广播时，重放命中自己写入的版本或摘要（`Stale` 且当前版本等于请求的版本、冲突摘要等于本次载荷的摘要）同样广播；多余的广播只淘汰副本 | A.R4 |
| 提前刷新的触发率与命中率无关：没取得令牌的重新填充按 `lease.wait` 退避；剩余寿命 ≤ 刷新窗口的副本不设刷新点 | A.D3 |

这些规则写入 §6 的不变量，由状态机的性质测试与确定性仿真检验（V9）。其中一部分不破坏兼容，已在 6.x 修复或排期（§9）。

### 4.7 线格式 2（V8）

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0–35 | 36 | 与格式 1 相同：magic、格式版本（`2`）、状态、**标志位**（格式 1 的保留字节）、令牌、令牌签发时间 |
| 36 | 8 | 版本（标志位 bit 0），大端有符号 64 位 |
| 36 或 44 | 20 | 载荷摘要（标志位 bit 1）：载荷的 SHA-1，与 `Digest` / `Digest.of` 相同，由写入方在客户端计算 |
| 之后 | n | 载荷 |

**写入哪种格式**：

- 格式 1：lease 占位 `L`、没有版本的负缓存 `M`、派生缓存（没有版本）的普通值。它们与 6.x / 7.x 的字节相同，7.x 末版与 v8 的 `fill`、`release`、`putIfMatch(expected = null)` 对它们的判定不变。
- 格式 2：权威标量的值（带摘要段）、带版本的内容（带版本段）。

**摘要算法不变**（M4）：仍是 SHA-1，`Digest` 的类型、`sha1` 属性与 `Digest.of` 都不变。客户端持久化的摘要（CoSec 的修订记录、`SnapshotDigest`）在 v8 中照常有效；格式 1 的内容由脚本现算的摘要与格式 2 头部中的摘要对同一份载荷相等，混合部署期间不会出现“同一内容两个摘要”。代价：SHA-1 已有可行的选择前缀碰撞，能写入文档的攻击者可以构造两份摘要相同的内容，让 `getIfChanged` 对已变化的文档回答 `Unchanged`（Q6）。

**未知标志位等同于更高格式**（C10）：读者遇到任何未知的标志位，按“更高的格式版本”处理：既不回收也不写回；有数据源的读取绕过 L1；权威缓存的读取 `FAIL`；条件写入与按版本写入拒绝。新增头部段时同时提升格式号或声明新的标志位，旧节点永远不会按错位的偏移读载荷。

**带版本的内容持有刷新令牌时，`fill` 比较版本**（C3）：

- 当前内容带版本时：填充的版本小于当前版本，或为 `NO_VERSION`，拒绝（调用方释放令牌，L2 按降级限龄填充）；相等，写入（相当于续期）；更大，写入并广播（这次填充本身就是一次版本前进，写入方随后的 `putIfNewer` 只会得到 `Stale`，广播必须由填充发出）。
- 6.8 的结构性保证（“带版本的内容不持有令牌”）由一条更强的规则取代：**任何写入都不让 L1 的版本后退，也不抹掉版本**（I-K）。

**逐个脚本的规则**（C8；7.x 末版与 v8 相同，除非注明）：

| 脚本 | 格式 1 无版本 | 格式 1 带 `VERSION_MARK` | 格式 2 | 未知标志位 |
|------|--------------|--------------------------|--------|-----------|
| `READ` | 照旧 | 永不授予刷新令牌（保留“签发时间 `+inf`”的判断，`SETRANGE 4` 会覆盖版本） | 照常授予刷新令牌（令牌位置只放令牌）；客户端按标志位解析各段 | 返回格式与头部，客户端按更高格式处理 |
| `FILL` | 令牌匹配即写入 | 不匹配（不持有令牌） | 令牌匹配 + 上面的版本规则；写入格式 2 | 拒绝 |
| `RELEASE` | 照旧（只触及字节 4–35） | 不匹配 | 照旧 | 不匹配 |
| `RECLAIM` | 按摘要条件回收 | 拒绝 | 有版本段：拒绝；只有摘要段：比较头部摘要 | 拒绝 |
| `PUT` / `PUT_ALL` / `INVALIDATE` | 整体写入或删除；权威缓存写格式 2（带摘要），派生缓存写格式 1 | 同左 | 同左 | 同左（整体覆盖不依赖旧内容） |
| `PUT_IF_NEWER` / `EVICT_KEEPING_VERSION` | 当前版本为 `NO_VERSION` | 从令牌位置读版本 | 从版本段读版本；写格式 2 | 拒绝 |
| `READ_IF_CHANGED` / `PUT_IF_MATCH` / `DELETE_IF_MATCH` | 在 Lua 中对载荷计算 SHA-1（6.x 的做法） | 同左 | 有摘要段：`GETRANGE key 0 63` 只读头部比较；没有：现算 | 拒绝 |
| 集合脚本 | Hash 与 `!f` 不变 | — | — | — |

- **条件读取 O(1)**：有摘要段时，`getIfChanged` 只读头部；摘要不变时 Redis 端的成本与载荷大小无关（6.x：8 MB 约 20 ms）。只读头部是安全的：原地修改（授予刷新令牌、`release`）只触及字节 4–35，整体写入重写头部，头部中的摘要始终与载荷一致。
- **混合部署**：7.x 末版能读格式 2、仍写格式 1（含上表全部脚本），v8 读格式 1 与 2、按上面的规则写入。因此 7.x 末版与 v8 **可以共享同一个 Redis 滚动升级**，不同于 5.x → 6.0；帧、频道命名与 `NodeId` 不变，失效互通。
- **回退**：下限是 7.x 末版。回退到 7.x 末版时，内容照常可读，下一次写入改写回格式 1（版本移回令牌位置，摘要丢弃）。回退到更早的版本时，格式 2 是“更高的格式版本”，权威内容读取失败；为此 v8.0 提供离线的格式 2 → 1 降级工具（与 `CoCache5Migrator` 同构），v9 再删除格式 1 的读取支持（M10）。
- **与现行规范的冲突**：architecture.md §3.3 的“不同大版本的节点不能共享同一个 Redis”与 §14 #9 改写为“7.x 末版与 8.x 例外”；摘要算法不变。

### 4.8 配置（V10）

**分层**（M5）：

| 层（由低到高） | 作用 |
|------|------|
| 内置默认值 | 按类型与声明的数据来源（§4.1） |
| `cocache.defaults.cache.*` / `cocache.defaults.store.*` | 全局默认值，**低于**声明：只作用于声明中没有写出的字段 |
| 声明（注解或 spec DSL） | |
| `CacheSpecCustomizer`（按 Spring 顺序） | |
| `cocache.overrides.cache.*` / `cocache.overrides.store.*` | 全局强制值，**高于**声明：运维只改配置就能全局生效（例如切换 `channel-mode` 时调低 `local-max-age`，事故中全局改 `read-failure`） |
| `cocache.caches.[name].*` | 按缓存 |

- 字段级合并：spec 携带每个字段都可空的 `PolicyOverlay`。用整体的 `CachePolicy` 写的 spec（`policy(CachePolicy)`）视为全部字段都已写出，启动日志提示“`defaults` 对此缓存无效”。
- 覆盖层引起策略校验失败时，错误信息指明是哪一层、哪个字段（例如全局调低的 `local-max-age` 小于某个声明的 `refreshWindow`）。
- 启动日志与 `GET /actuator/cocache/{name}` 输出每个字段的生效值及其来源层。
- 每一节只允许适用的键，出现不适用的键时启动失败，而不是像 6.x 那样静默跳过（如 `store` 节中的 `refresh-window`、`absent-ttl` 之外的派生专用键）。`defaults.store.ttl` 默认不允许：全局寿命会让唯一副本过期。
- `key-prefix` 不参与分层，仍是“配置优先于声明”（与 6.x 相同）。它的变更不能滚动（运维文档已列出），反转优先级会让升级时存储 key 改变，`Store` 读成 `Absent`。
- `cocache.caches` 中出现未声明的名称时默认启动失败；`cocache.caches-validation: warn` 时只告警，供多个服务共用的 Config Server / ConfigMap。

**单位**：

- 所有时长必须带单位（`500ms`、`5s`、`2m`、`PT2M`）。不带单位的值启动失败，错误信息给出 6.x 的解释（秒或毫秒）与等价写法。`refresh-window` 的“关闭”写作 `0s` 或 `off`。
- 注解中的时长（`@CoCache.ttl` 等 5 个属性与 `@CaffeineCache.expireAfterAccess`）由 `Long` 秒改为带单位的字符串（`ttl = "60s"`），旧写法编译失败，可以发现。

**迁移机制**（M6）：

- Spring Boot 的 `@ConfigurationProperties` 默认忽略未知字段，所以旧的 `cocache.defaults.<field>` 必须逐个列入 `RemovedProperties`（不能整个列出 `cocache.defaults`：它是新键的祖先），并在 `additional-spring-configuration-metadata.json` 中声明 `deprecation.level=error` 与 `replacement`。现有的替代提示（如 `cocache.redis.strict-failure → cocache.defaults.read-failure`）同步改为指向新键。
- 7.x 末版接受新键（`defaults.cache.*`、`defaults.store.*`、`overrides.*`、`load-timeout`）作为别名（按 7.x 的语义），对旧键与不带单位的值告警。迁移顺序：先在 7.x 末版上改配置，再升级程序；多个版本共用一份配置时始终有两版都接受的写法。
- 其它改名：`load-async-timeout` → `load-timeout`；`cocache.redis.set-cache-eviction-check` → `cocache.redis.store-eviction-check`；新增 `cocache.redis.assume-maxmemory-policy`、`management.endpoint.cocache.allow-store-delete`、`fill-in-transaction`（§4.9）。

**库的装配入口**（M9）：提供受支持的 `CoCacheFactory.create(spec)`：与 starter 同样分层，计入 `cocache.caches` 的名称校验，注册到端点与指标。spec 可以锁定字段（`lock(ttl, readFailure)`），任何配置层试图覆盖锁定字段时启动失败。CoSec 的 `pinnedPolicy`（防止配置让策略快照过期、让全局 DENY 消失）由它表达。

### 4.9 Spring（V11）

**事务内的回源**（C2、M7）：按缓存配置 `fill-in-transaction`：

| 取值 | 行为 |
|------|------|
| `registered-keys`（默认） | 当前线程处于非只读的实际事务中、且这个 key 已在本事务中登记失效（`TransactionalInvalidator`）时，回源结果是**私有的**（`Private`）：不写 L1、不写 L2、不经 SingleFlight 共享（合并到它的调用方按 §4.1 的合并规则自行运行），lease 照常释放。登记失效时立即在本节点递增戳并淘汰，丢弃本事务之前对这个 key 的 L2 填充 |
| `private` | 写事务内的全部回源都是私有的。最严格，代价是写事务内的读取不经缓存 |
| `allow` | 6.x 的行为 |

- 应用本来就必须为修改过的 key 登记失效，否则提交之后缓存是旧的；所以默认规则只在应用遵守协议时生效，也只在这些 key 上有代价，类级 `@Transactional` 的应用不会因此“L1 永远填不满”。
- 默认规则的残余窗口：在登记之前、修改之后的事务内读取，仍可能把未提交的数据写进 L1，直到提交或回滚时的失效（#697）清除它；`private` 关闭这个窗口。
- 计入 `cocache.fills.gated`。只读事务的快照陈旧（如 MySQL REPEATABLE READ 下，事务开始后才取得 lease、却读到旧快照）不在覆盖范围内，与“loader 读到从库旧数据”同类，属于 G1 的协议外情形。非 Spring 管理的事务（jOOQ、Exposed、直接使用 JTA）闸门看不到。

**`TransactionalInvalidator`**：在 `afterCompletion` 中执行，提交与回滚都失效已登记的 key；在同步已经执行之后才登记的 key 立即失效；逐缓存执行，失败聚合之后再抛出（A.T2）。这些已在 6.x 修复（#697）。参数类型只接受 `Cache`。

**Spring Cache**：

- `SpringCacheSyncCheck` 对指向 `Store`、`SetStore`、`VersionedCache` 的缓存操作启动失败（而不是在方法调用时才失败）。
- `@CacheEvict(allEntries = true)` 指向 CoCache 缓存时：7.x 末版告警，v8 启动失败。替代：本节点用 `CacheAdmin.clearLocal`；全集群的“按 namespace 重置”需要新的帧类型（Q12）。
- 响应式与协程事务（R2DBC、`@Transactional suspend`）：提供基于响应式 `TransactionSynchronizationManager` 的变体，或在检测到“有实际事务、却没有线程绑定的同步”时告警（Q5）。
- `keyExpression` 改用编译模式的 SpEL（`SpelCompilerMode.IMMEDIATE`，待 JMH）。

**运维入口**（M13）：

- actuator 端点 `cocache` 默认只读（`@Endpoint(defaultAccess = Access.READ_ONLY)`），写操作经 `management.endpoint.cocache.access` 开放；`Store` / `SetStore` 的按 key 删除另需 `management.endpoint.cocache.allow-store-delete=true`。7.x 末版在写操作被调用时告警，提示 v8 需要的配置；运维文档的示例改为 `curl -fsS`。
- 新增只广播失效帧的操作（不改 L1，对所有类型都安全）：事件丢失时让各节点丢弃 L2 副本，不删除唯一副本。
- 未知缓存名的 `DELETE` 返回 404（6.x 返回 204，拼错名字时静默）。
- `ExternalInvalidator` 增加只广播的方法；文档写明它的 `invalidate` 作用于权威缓存的 namespace 时会删除唯一副本。
- 外部监听失效频道的代码（CoSec 直接订阅缓存的频道）依赖“引擎先淘汰本地副本，再通知外部监听者”这一顺序：v8 把它写成 `InvalidationChannel` 的契约。

### 4.10 检验（V9）

- **Lincheck**（JetBrains，Kotlin 原生，只作为测试依赖）：对“flight 层 + L2 + 失效戳 + 内存存储”做模型检验，操作为 `get`、`get(key, loader)`、`invalidate`、`put`、`onInvalidated`、`onReset`、`clearLocal`；顺序规格是“带版本的数据源 + 读己之写”，另有“带版本的写入方 + 读快照的 loader”。A.D4、C1、C4 是它应当能发现的典型问题。
- **TLA+**：`docs/spec/CoCache.tla` 建模 N 个节点、一个 key 的 L1 状态机（`ABSENT` / `LEASED` / `VALUE` / `MISSING`，令牌，版本，格式 1 与 2）、每个节点的 L2 与失效戳、可丢失 / 乱序 / 重复的失效通道、带提交历史的数据源、超时后仍执行的命令（允许排在其它节点此后发出的命令之后）、写入方节点对自身帧的过滤、刷新令牌 + 带版本的内容、持有令牌崩溃。性质：G2 的 L1 与 L2 条款、版本单调（I-K）、令牌最终释放。CI 中以小规模（2 节点、1 个 key、3 个值）运行 TLC。
- **确定性仿真**：sans-IO 核心不依赖线程与时钟，可以在单线程上由带种子的调度器驱动多个节点、模拟后端与模拟时钟，每次 CI 跑数千个随机交错，故障包括事件丢失与乱序、超时后仍执行的命令、驱动放弃状态机、leader 被中断、持有令牌崩溃；以数据源的提交历史为裁判，检查 G1 的上界与 G2。失败的种子可以复现。
- **跨版本**：`BackendProtocolSpec` 按脚本集参数化，用 7.x 末版与 v8 的两套脚本交替操作同一个 key，覆盖 §4.7 的矩阵。
- 已发布的 TCK 保留，手写交错继续作为回归用例。

### 4.11 文档（V12）

- architecture.md 只写现行行为。版本叙述（“6.3.1 之前……”“6.4.0 也是……”）移到 changelog 或对应的 RFC。顺带修正现行文档中的不一致：§6.3 写着“启动时不检查这两点”，而 6.1 起已有 `SetCacheEvictionCheck`（附录 A.L10）。
- 不变量以稳定编号 `I-n` 列出，代码注释与测试用例引用编号，取代“RFC 0003 §4.4”一类会随文档改版而失效的引用。
- 审查与对抗验证的记录放在 RFC 的附录与 §12 中。

## 5. 删除清单（v8）

- `Cache`、`AsyncCache` 与挂起视图上默认抛出的方法（移到能力类型）；`SetCache`、`AsyncSetCache`、`SetCacheSpec`（由 `SetStore`、`AsyncSetStore`、`SetStoreSpec` 取代）。
- 把没有数据源的 `Cache` 当作权威副本的用法（移到 `Store`）；没有声明数据来源的 `Cache`（必须声明 `callerPopulated()`）。
- `LocalEntry.unresolved`（由 flight 层的 `Unfilled` 与合并规则取代）；`readFailure` 对 `callerPopulated` 缓存与权威缓存的含义（由 `unknownRead` 取代）。
- 带版本的类型上的 `put`、`putAll`、普通 `invalidate`、`putIfMatch`；`VersionedCache` 使用没有版本的数据源。
- `CoherenceBackend.versionedWrites`；`RemoteStore` 与 `AsyncRemoteStore`、`InvalidationChannel` 与 `AsyncInvalidationChannel` 的双份接口（由异步形状 + `BlockingRemoteStore` 取代）；抛出的默认方法与非原子的默认实现；`BlockingAsyncStore` / `BlockingAsyncChannel`。
- `Async*` 平行 TCK 规范；`supportsConditionalWrites` 一类能力开关（由 `expectedCapabilities` 取代）。
- 权威缓存上的 `InvalidateFailureMode.RETRY`；`LoadPolicy.asyncTimeout`（并入 `load.timeout`）。
- `cocache.defaults.*` 的旧语义与旧键；不带单位的时长；注解中以秒计的 `Long` 时长；`setPolicyFor` / `versionedPolicyFor`。
- 以格式 1 写入带版本的内容（`VERSION_MARK` 只在读取时识别）。
- `CoCache5Migrator`：5.x 用户先升级到 7.x 完成迁移。

## 6. 不变量（v8 实施时与 architecture.md §14 的对照）

**新增**：

- **I-A 类型与声明决定默认值**：`Cache` / `VersionedCache` 是派生副本，`Store` / `VersionedStore` / `SetStore` 是权威副本；派生缓存声明数据来源。默认值与可用的操作由类型与声明决定，运行时不再按“这次读取有没有数据源”分支；同一类型内，有没有数据源只影响未命中时能否回源。
- **I-B `Unknown` 只表示故障**：它不写入任何一层、不广播，也不作为带数据源的调用方的结果；`get` / `getAll` 遇到它默认抛出。
- **I-C L2 填充**：校验失效戳与写入 L2 在同一个 key 锁内完成，写后按身份复核；写入方与远端事件处理完成之后，读取开始于它们之前的副本不可见；重置之后，这样的副本至多存在到填充方自己的复核。
- **I-D 权威副本的无条件删除不延迟**：延迟执行的无条件写入只用于有数据源的派生副本的 `invalidate`。
- **I-E 结果未知**：写入失败区分“确定未执行”与“结果未知”；确定未执行的写入不会再执行；结果未知的写入在任何时刻都可能生效，它的安全只来自顺序（令牌、版本、条件），派生 `put` 由补偿失效限界。
- **I-F 令牌守恒**：取得令牌的每一步（`Read`、`ReadAll`、`Reclaim`、刷新的 `Read`），在每个失败转移上、在驱动放弃状态机时，都有释放；每个 flight 在有限时间内完成。
- **I-G 刷新有界**：每个节点、每个 key 的刷新触发率有一个与命中率无关的上界。
- **I-H 等待有界**：跟随者、lease 等待、许可等待都有上界；只有跟随者的等待响应中断，且只影响它自己。
- **I-I 一份协议**：每个协议分支只有一份实现，flight 层由两个驱动共用；同步与异步只在驱动上不同。
- **I-J 事务内的私有回源**（启用 Spring 集成时）：写事务内、已登记失效的 key 的回源结果只返回给本次调用。
- **I-K 版本单调**：任何写入（含 `fill`）都不让 L1 的版本后退，也不抹掉版本；只有 `purge` 与到期例外。
- **I-L 未知即更高**：未知的格式版本与未知的标志位，既不回收也不写回。

**与 architecture.md §14 的对照**：

| §14 | 处理 | 说明 |
|-----|------|------|
| 1 栅栏规则 | 修改 | L2 的部分改为 I-C；“L2 只由读 L1 的路径填充，写入方从不写 L2”保留 |
| 2 L1 未命中就是未命中 | 修改 | 补上：`callerPopulated` 缓存的“未填充”不是负缓存，带数据源的调用方不接受它；权威缓存的 L1 未命中是 `Absent` |
| 3 读路径从不无条件删除 | 修改 | “没有数据源的读取”改为按类型与声明（I-A）；加上 I-L |
| 4 能原子广播就原子广播 | 修改 | 补上：版本前进的 `fill` 也广播；非原子广播下重放命中自己的写入也广播 |
| 5 SingleFlight | 修改 | 加入合并规则（I-B、`Private`）、flight 层由两个驱动共用（I-I）、每个 flight 有限时间内完成（I-F） |
| 6 loader 的线程 | 保留 | 由驱动实现 |
| 7 令牌必须释放或过期 | 取代 | 由 I-F 取代（加入 `reclaim` 与驱动放弃状态机） |
| 8 每次 L1 状态转换显式重设到期时间 | 保留 | |
| 9 线格式冻结 | 修改 | 格式 2 的两阶段演进；7.x 末版与 8.x 可以共享同一个 Redis；摘要算法不变 |
| 10 集合元素增删 | 修改 | `SetCache` 改为 `SetStore`，“不得有 loader”保留 |
| 11 有状态组件只由运行时创建 | 保留 | 补上受支持的库装配入口 `CoCacheFactory` |
| 12 L1 连接只访问主节点、断线拒绝 | 保留 | |
| 13 存储只执行协议 | 保留 | |
| 14 代理不包装异常 | 保留 | 受检异常的 JDK 限制写入文档（Q7） |
| 15 命中路径的改动必须附带数据 | 保留 | |
| 16 AOT | 保留 | 新的接口与目标类型登记提示（§8） |
| 17 取消不传播 | 保留 | 由驱动实现 |
| 18 异步视图不挂起线程、不同步抛出 | 保留 | 由驱动实现 |
| 19 条件读写只确认 L1 | 修改 | 移到 `Store`；加入 `deleteIfMatch`；摘要相等只证明已写入 |
| 20 按版本有序的写入 | 重写 | 由 I-K 取代“带版本的内容不持有令牌”：带版本的内容可以持有刷新令牌，`fill` 比较版本；保留版本栅栏的 `evict`；版本存放在格式 2 的版本段 |

## 7. 性能预算与验证

| 路径 | 预算 | 验证 |
|------|------|------|
| L2 命中 | 不退化（1 / 8 线程，含 `l2HitMixed`）；L2 key 不含前缀若采用，须为正收益 | `scripts/jmh-compare.sh -b l2Hit,l2HitViaInterface,l2HitMixed,l2HitAsync -t 1,8` |
| L1 命中、未命中回源 | sans-IO 核心与 flight 层的退化 ≤ 3%（同轮配对比值的中位数） | `l1HitViaEngine`、`l1HitViaEngineAsync`、`missLoad`、`missLoadAsync` |
| L2 填充（`compute` + 条件复核） | 未命中路径的退化 ≤ 2% | `missLoad`、`l1Read` |
| 权威缓存的 `Absent` 与 `callerPopulated` 的“未填充” | 进入 L2，命中成本与普通 L2 命中相同（第一轮 M2 指出 v1 会让它们每次一次往返） | 新基准 `l2HitAbsent` |
| 条件读取（摘要未变） | Redis 端成本与载荷大小无关；8 MB 的值从约 20 ms 降到亚毫秒 | 新基准 `conditionalUnchanged`（1 KB / 1 MB / 8 MB） |
| 内存 | 派生缓存每个 key +0 B；权威标量 +20 B（摘要）；带版本 +8 B | `MEMORY USAGE` 对照 |

## 8. 迁移与兼容

**基线是 7.x 末版**（含 #697、#698、#700、#701），不是 6.8（M3）。没有数据源的 `Cache` 的行为在三个版本上的对照：

| 没有数据源的 `Cache`（经 `put` 或 Spring Cache 使用） | 6.8 | 7.x 末版 | v8（声明为 `callerPopulated()` 的 `Cache`） |
|------|-----|---------|----|
| L1 读失败 | 返回 `null` 并缓存（A.D1） | 抛出 `CacheReadException` | 抛出（`unknownRead = ABSENT` 时返回 `Absent`、不缓存） |
| `invalidate` 失败 | RETRY | THROW（显式配置 RETRY 照常生效） | THROW（同左） |
| 读不懂的内容 | FAIL | FAIL | FAIL |
| L1 未命中 | 未决的不存在，进入 L2 | 同左 | “未填充”，进入 L2（同左） |

**类型与声明**：

| 7.x 末版的用法 | v8 |
|----------------|----|
| 有数据源的 `Cache` | `Cache`（不变） |
| 没有数据源、经 Spring Cache 或 `put` 维护、权威在别处 | `Cache` + `callerPopulated()`（接口式声明：`@CoCache(populatedBy = CALLER)`） |
| 没有数据源、L1 就是数据（不论是否调用了 `putIfMatch` / `getIfChanged`） | `Store`；`invalidate` → `delete`；故障时放行的写明 `unknownRead = ABSENT` |
| `CacheSpec.versioned` / `versionedLoader` | 有带版本的数据源：`VersionedCache`；没有数据源：`VersionedStore`；`invalidate` → `evict` / `purge` |
| `SetCache` / `SetCacheSpec` | `SetStore` / `SetStoreSpec` |
| `JoinCache` | 只读用法：`JoinView`；用到 `invalidate`：`JoinCache`（两侧必须是 `Cache`） |
| 第三方后端 | 先在 7.x 末版上实现 v8 形状的 SPI 与能力访问器、通过参数化的 TCK，再升级 |
| 库在 starter 之外装配缓存（`CoCacheProperties.policyFor`、`CacheProxies.create`、`CacheAssembler`） | `CoCacheFactory.create(spec)`；需要保护的字段用 `lock(...)` |

- starter 无法替用户判断一个没有数据源的缓存是不是权威副本。7.x 末版在启动时对**每个**没有数据源的 `Cache` 按声明告警，要求在 v8 中二选一（`callerPopulated()` 或 `Store`），不再按运行时调用了哪些方法判断；接口式声明没有匹配到数据源时告警中指出原因（例如泛型不匹配）。
- 选错类型的后果与发现方式：唯一副本声明成 `Cache` + `callerPopulated()`，`invalidate` 默认 THROW、读不懂的内容默认 FAIL，与 `Store` 的差别只剩驱逐策略检查与可用的条件写入；有外部权威的副本声明成 `Store`，存在匹配的 `CacheLoader` bean 时启动告警。

**配置**：见 §4.8 的迁移机制（`defaults` 拆分、新增 `overrides`、单位、注解时长改为字符串、改名的键、7.x 末版的别名与告警）。

**其余变化**（M15）：

- **指标**：新增 `cocache.reads.unknown`、`cocache.fills.gated`、`cocache.refresh.skipped`（剩余寿命 ≤ 刷新窗口而不设刷新点）；`cocache.load.timeouts` 改为包含同步跟随者的超时。端点的报告带上缓存的类型。
- **端点**：默认只读；写操作的开放方式；只广播的操作；`purge` 的入口；未知缓存名返回 404（§4.9）。
- **AOT**：`CoCacheRuntimeHints` 登记新的接口（`Store`、`VersionedCache`、`VersionedStore`、`SetStore`、`JoinView` 与各自的异步视图）；`CacheBeanDefinitions` 的目标类型随之改变；`CoCacheAotTest` 与 `CoCacheAotRuntimeTest` 同步更新。
- **`cocache-coroutines`**：挂起视图有同构的层次；`suspendingVersionedLoader` 保留；下游公开的工厂签名（如 Wow 的 `StateViewCaches.CacheFactory`）随之改变。
- **`load.timeout`**：同步跟随者从无限等待变为 30 s 后以 `SourceLoadTimeoutException` 失败。`@Cacheable(sync = true)` 上回源超过 30 s 的方法，其并发调用方需要调大它。
- **提前刷新**：`VersionedCache` 支持提前刷新之后，`cocache.defaults.cache.refresh-window` 开始作用于它（例如 Wow 的状态视图），刷新时经数据源回源；不需要时在声明中关闭。
- **`Lookup`**：Kotlin 的 `when` 表达式因 `Unknown` 编译失败；JDK 17 下 Java 没有对 sealed 类型的穷尽 `switch`，`instanceof` 链会把 `Unknown` 落进 `else`。迁移文档提示 Java 调用方改用 `get` 的抛出语义，或显式处理 `Unknown`。
- **`Digest`**：不变（SHA-1，§4.7）。
- **`CacheSpecCustomizer`**：为 5 种 spec 各提供一个重载，并改为 overlay 语义（只设置写出的字段）。
- **`InvalidationChannel` 的监听顺序**成为契约（§4.9）。
- **`skills/`**：新增 7 → 8 的迁移 skill 与静态审计脚本，检查：没有数据源的 `Cache`、条件与版本方法的调用、不带单位的时长、`cocache.defaults.*`、`Digest.of` / `.sha1`、`Lookup` 的匹配、`CacheAssembler` / `policyFor`、运维脚本中的 `DELETE`。
- **数据**：派生缓存不需要迁移；格式 1 的权威内容在 v8 中照常读取，下一次写入时改写为格式 2；混合部署与回退见 §4.7。混合部署与回退窗口内，权威缓存应在声明中显式写出 `ttl`：7.x 末版的没有数据源的 `Cache` 默认 1 h，v8 的 `Store` 默认永不过期（M10）。
- 迁移表写入 `wiki/guide/changelog.md`（en + zh）。

**下游演练**（第一轮读了两个下游的源码，§12）：

- **CoSec**（依赖 6.7.0）：
  - 策略快照、控制面的 8 个唯一副本缓存、两个撤销表 → `Store`；撤销表写明 `unknownRead = ABSENT`。
  - 摘要与持久化的 `SnapshotDigest` 不受影响（§4.7）。
  - `CoSecCacheAssembler` 改用 `CoCacheFactory`，`pinnedPolicy` 改为 `lock(...)`。
  - 全局策略索引 → `SetStore`。
  - 托管 Redis 上的驱逐策略检查改由 `INFO` 读取，或配置 `assume-maxmemory-policy`。
- **Wow**（6.8 的按版本写入）：
  - 状态视图 → `VersionedCache`（有 `versionedLoader`）。
  - `putIfNewer` / `invalidateIfNewer` 语义不变。
  - 运维删除改用 `evict`，保留版本栅栏。
  - `StarterCaches` 改用 `CoCacheFactory`。
  - 注意全局的 `refresh-window` 开始作用于状态视图。

## 9. 可在 6.x / 7.x 先行（不破坏兼容）

| 项 | 来源 | 状态 |
|----|------|------|
| 没有数据源的 L1 读失败 fail closed；`invalidate` 重试不越过之后的写入；非原子广播下的重放也广播 | A.D1、A.D2、A.R4 | 修复中（#700） |
| #700 的补充：允许显式配置的 `read-failure: degrade` 对没有数据源的读取生效（返回 `null`、不缓存、计数）。否则 CoSec 撤销表在 Redis 故障时的放行（CoSec 有用例守着它）在 6.9 中无法表达 | M3 | 修复中（#700，维护者已批准） |
| 超时取消 Lettuce 命令；`sharded` 未确认频道每次心跳重置；读超时释放 lease；订阅生命周期竞态；格式字节按无符号解析 | A.R1、A.R2、A.L1、A.R3、A.L7 | 修复中（#701） |
| `reclaim` 超时释放 lease | C7 | 待排期 |
| `TransactionalInvalidator` 回滚失效、迟到登记、逐缓存失败隔离 | A.T1、A.T2 | 修复中（#697） |
| 提前刷新退避；寿命 ≤ 刷新窗口不设刷新点 | A.D3 | 修复中（#698） |
| L2 填充用 `compute` + 条件复核（§4.5） | A.D4、C4 | 待排期 |
| 单调时钟（§4.5） | A.C1 | 待排期 |
| 同步跟随者可中断 | A.S1 | 待排期（上界属于 v8 的 `load.timeout`） |
| L2 key 不含前缀 | §4.5 | 待 JMH 验证 |
| 驱逐策略检查改读 `INFO memory`、按声明检查 | M11 | 待排期 |
| 未知缓存名的端点 `DELETE` 返回 404；修正 architecture.md §6.3 关于启动检查的说法 | M13、A.L10 | 待排期 |
| Lincheck 用例、TLA+ 模型 | V9 | 待排期 |
| 协议核心与 flight 层的 sans-IO 重构（内部，API 不变） | V5 | 7.x |
| v8 形状的 SPI、能力访问器与参数化 TCK（与旧接口并存） | V4 | 7.x |
| 读格式 2（§4.7 的全部脚本）；没有数据源的 `Cache` 停止申请 lease | V8、C11 | 7.x 末版 |
| 配置别名与告警；按声明对没有数据源的 `Cache` 告警；端点写操作告警；`allEntries = true` 告警 | V10、M1、M6、M13、M14 | 7.x 末版 |

先行之后，v8 本身只剩破坏兼容的部分：类型、SPI 的切换、配置语义、Spring 语义与格式 2 的写入。

## 10. 实施顺序

0. **6.x**：§9 中“修复中”与“待排期”的项；Lincheck 用例与 TLA+ 模型进入仓库与 CI。
1. **7.x**：sans-IO 重构，每条路径一个 PR（单 key 读 → 批量 → 刷新 → 写入），每个 PR 的同步与异步 TCK 全绿、JMH 达标；flight 层接受 Lincheck；确定性仿真接到新核心上；v8 形状的 SPI 与参数化 TCK 与旧接口并存。
2. **7.x 末版**：读格式 2；§9 中列出的配置别名与各项告警。
3. **8.0**：API 类型（V1–V3）、SPI 切换（V4）、配置语义（V10）、Spring（V11）、格式 2 的写入与降级工具（V8）、文档重排（V12）。
4. **8.x**：按需的后续，如响应式事务、构建期生成接口实现、修订号（§11）。

## 11. 风险与未决问题

- **Q1 范围放在 v8 还是 7.0**：维护者指定 v8。7.0 保持“删除弃用项”的既定范围，v8 的前置工作分散在 7.x，降低一次性风险。
- **Q2 命名**：用户 API 的 `Store` 与 SPI 的 `RemoteStore` 容易混淆。备选：用户 API 用 `Store`、SPI 改名为 `L1Store`；或用户 API 用 `Register` / `Document`。倾向前者。
- **Q3 基线升到 JDK 21**：v8 是大版本，可以升级基线（CONTRIBUTING“Versioning and releases”）。升级之后可以把虚拟线程作为默认阻塞执行器，不再回退到线程池，但本 RFC 的设计不依赖它。需要先了解用户的 JDK 分布。
- **Q4 `fill-in-transaction` 的默认值**：`registered-keys` 只在应用遵守协议时生效，残余窗口见 §4.9；`private` 最严格，但写事务内的读取不经缓存。第二轮应结合下游的事务形态复核默认值。
- **Q5 响应式事务**：需要 `cocache-spring` 可选依赖 reactor（经 spring-tx 的响应式包），依赖变更须先经维护者批准。
- **Q6 摘要的抗碰撞**：保留 SHA-1 是为了与 `Digest` 的使用者兼容（§4.7）。若维护者认为选择前缀碰撞不可接受（能写入文档的攻击者构造两份摘要相同的内容），需要在标志位中编码算法、让 `Digest` 携带算法、提供 `Digest.of(envelope, algorithm)`，并给出“已持久化的摘要要重算”的迁移步骤。
- **Q7 代理与受检异常**：JDK 动态代理会把未声明的受检异常包装成 `UndeclaredThrowableException`（A.P1），这在 `InvocationHandler` 内无法避免。彻底的办法是构建期生成接口实现（与 AOT 一起），代价是一个构建插件。v8 先在文档中写明，生成实现留到 8.x 评估。
- **Q8 派生 `Cache` 是否保留 `put`**：`put` 是后写者胜，与数据源的提交顺序无关（architecture.md §6.1）。保留（Spring Cache 与 write-through 场景需要），文档继续首选 `invalidate`；结果未知时由补偿失效限界（§4.6）。
- **Q9 `Store` 的 L2**：权威副本的 L2 在事件延迟内仍可能陈旧。是否给 `Store` 默认更短的 `localMaxAge`，或提供不使用 L2 的选项，待用户反馈。
- **Q10 修订号**：摘要比较在迟到执行下有 ABA（C5 5c）。格式 2 的头部放得下服务端递增的修订号，可以作为 `putIfMatch` 的另一种比较依据；但它改变 `getIfChanged` / `putIfMatch` 的 API，也影响按摘要寻址的下游。倾向 8.x 作为新增能力评估，v8.0 先写明限制。
- **Q11 重置的严格线性化**：§4.5 的条件复核让重置之后的窗口以填充方自己的一步为界。若要求严格成立，用读写锁（如 `StampedLock`）把填充与“递增 + `clear`”分开，代价是未命中路径多一次 CAS。
- **Q12 全集群清空**：`@CacheEvict(allEntries = true)` 的全集群语义需要“按 namespace 重置”的帧，这是帧格式的变更；v8 是否加入，待需求。
- **Q13 保留版本栅栏的 `evict` 的编码**：候选是格式 2 的 `L` 状态（零令牌 + 版本段）：`READ` 把它当作“不存在”授予 lease 并保留版本段，`FILL` 与 `PUT_IF_NEWER` 按版本段比较。它与 lease 的语义交叠，需要第二轮逐脚本验证。

## 12. 对抗验证记录

### v1 → v2：第一轮

v1 由两路独立的只读会话验证：一致性（G1 / G2，对照 6.8 代码、#697 / #700 / #701 的 diff、Caffeine 3.2.4 与 JDK `ConcurrentHashMap` 源码）与迁移运维（对照本仓库代码与文档、下游 CoSec 与 Wow 的源码）。作者复核了其中的关键论断：`FILL` 只比较令牌（`RedisScripts.FILL`）、Caffeine `clear()` 按 `data.values()` 的快照删除、`Digest` 以 SHA-1 计算且 KDoc 写明属于线格式、#700 对没有数据源的读取“不论 `readFailure`”都抛出、CoSec 的撤销读取依赖故障时返回 `null`（`CoCacheRevocationReader`，有用例守着）。28 条发现全部成立，全部处理；下表“处理”一列指向 v2 中修改的位置。本节的编号（C1–C13、M1–M15）与附录 A 的编号相互独立，正文引用附录 A 时一律带前缀（如 A.C1、A.D1）。

**一致性**：

| ID | 严重度 | 发现 | 处理 |
|----|--------|------|------|
| C1 | 高 | 删除 `unresolved` 之后，`Unknown` 可以经 SingleFlight 交给带 loader 的调用方，`get(k, loader)` 不调用 loader（A.D1 同类） | §4.1 合并规则、§4.4 flight 层、I-B |
| C2 | 中高 | `FillGate` 只挡住 L1，事务内读到的未提交数据仍进 L2、经 SingleFlight 共享 | §4.9 私有结果（`Private`）、I-J |
| C3 | 中高 | 带版本的内容持有刷新令牌之后，`fill` 只比较令牌，刷新可以让版本后退或抹掉版本；版本前进的填充不广播 | §4.7 版本规则、§4.2 只允许带版本的数据源、I-K |
| C4 | 中 | Caffeine 的 `clear()` 看不到进行中的 `compute`，只靠 `compute` 对重置不成立 | §4.5 条件复核、I-C、Q11 |
| C5 | 中 | 已写出的命令可以排在更晚的写入之后执行：派生 `put` 迟到让旧值进 L1（上界是 `ttl`）、写入方节点过滤自身帧、摘要 CAS 的 ABA 与误判、`Store` / `SetStore` 没有有序的重放 | A12、§4.6、I-E、Q10 |
| C6 | 中 | 带版本的类型上仍能调用丢失版本的写入；`VersionedStore` 继承 `Store.put` | A7 限定、§3.1 兄弟类型、§4.2 `evict` / `purge`、Q13 |
| C7 | 中 | 驱动放弃状态机时没有接口释放令牌；`reclaim` 超时不释放 | §4.4 `abandon`、§4.6、I-F、§9 |
| C8 | 中 | 两种格式共存时没有逐个脚本规定：`RECLAIM` 回收带版本的格式 2、`PUT_IF_NEWER` 只认一处版本、`READ` 为格式 1 的带版本内容授予令牌、摘要种类 | §4.7 脚本矩阵、§4.10 跨版本 TCK |
| C9 | 中低 | `fillLocal` 不是指令；一个 `Long` 装不下批量的戳；`resume` 不带时间；读己之写重试留在驱动里 | §4.4 `FillLocal`、逐 key 的戳、`resume(outcome, nowNanos)`、flight 层 |
| C10 | 中低 | 没有规定读者遇到未知标志位怎么办 | §4.7 未知即更高、I-L |
| C11 | 中低 | `Store` 的读取是否申请 lease、读到 `Leased` 怎么办没有规定 | §4.1 不申请 lease、§9 7.x 末版 |
| C12 | 低 | leader 被中断时，中断引起的失败传给没有被中断的跟随者 | §4.6、I-H |
| C13 | 低 | “其余不变量保留”与 §14 的 6 条冲突 | §6 对照表 |

**迁移与运维**：

| ID | 严重度 | 发现 | 处理 |
|----|--------|------|------|
| M1 | 高 | 按“调用了哪些方法、有没有 loader”分型，会把语义相同的缓存分到不同类型（CoSec 的两个撤销表）；“没有数据源”由 bean 类型匹配推断；选错类型在启动时发现不了 | V1 判断准则、§4.1、`callerPopulated()` 必须声明、§8 按声明告警、I-A |
| M2 | 高 | `Unknown` 混淆了“正常未命中”与“故障”：前者不进 L2 让热路径每次一次往返，后者经 `get` 变成 `null` | V2、§4.1、§7 `l2HitAbsent`、指标 `cocache.reads.unknown` |
| M3 | 高 | 迁移基线应为含 #700 的 7.x 末版；v1 让没有数据源的语义从 6.8 → 6.9 → v8 翻转两次；#700 让 CoSec 的故障放行无法表达 | §8 三版对照、§4.1 沿用 #700 的默认值、`unknownRead`、§9 对 #700 的补充 |
| M4 | 高 | 换摘要算法会破坏 `Digest` 的公开语义与 CoSec 持久化的摘要，混合部署期间同一内容两个摘要 | §4.7 摘要段存 SHA-1、Q6 |
| M5 | 高 | “声明覆盖全局默认值”让运维失去全局手段；整体策略对象让 `defaults` 静默失效 | §4.8 三层（`overrides`）、字段级合并与提示、生效值来源 |
| M6 | 高 | 配置键迁移会被 Spring Boot 静默忽略；滚动升级找不到两版都接受的写法；`key-prefix` 的优先级 | §4.8 迁移机制、7.x 末版别名、`key-prefix` 不参与分层、注解时长 |
| M7 | 中高 | 对全部写事务拦截 L1，类级 `@Transactional` 的应用 L1 永远填不满（约 360N 倍回源）；L2 仍泄漏；只读快照不在范围内 | §4.9 `fill-in-transaction` 三档、默认 `registered-keys`、指标、Q4 |
| M8 | 中高 | `is` 检测与装饰器不相容；“实现了才测”的 TCK 是 fail open 的；阻塞适配实际是第二种形状 | §4.3 能力访问器、`expectedCapabilities`、`BlockingRemoteStore`、7.x 先行 |
| M9 | 中 | 库在 starter 之外装配缓存的入口都在兼容承诺之外；没有锁定字段的位置 | §4.8 `CoCacheFactory` 与 `lock(...)` |
| M10 | 中 | 默认值随类型变化，混合部署与回退时权威内容会过期；回退到 7.x 末版之前时格式 2 读不出；墓碑默认永不过期 | §4.7 回退下限与降级工具、§4.1 `tombstoneTtl`、§8 显式写出 `ttl` |
| M11 | 中 | `maxmemory-policy` 检查在禁用 `CONFIG` 的托管 Redis 上静默跳过；只检查已创建的缓存；`volatile-*` | §4.1 驱逐策略检查、§9 |
| M12 | 中 | `VersionedCache` 仍是 `Cache`：`invalidate` 默认重试、可接入 Spring Cache 与事务失效，删除会撤掉版本栅栏 | 同 C6；§4.9 Spring 不接受 `VersionedCache` |
| M13 | 中 | 端点默认只读让手册中的 `DELETE` 静默失效；`Store` 需要只广播的失效；`ExternalInvalidator` 会删掉唯一副本 | §4.9 运维入口 |
| M14 | 中低 | “`JoinCache` 两侧是 `ReadableCache`”与现有的 `invalidate` 矛盾；指向 `Store` 的注解调用时才失败；`allEntries` 没有替代 | §3.1 `JoinView` / `JoinCache`、§4.9、Q12 |
| M15 | 中低 | §8 遗漏了指标、端点、AOT、协程、`CoCache5Migrator`、`skills/`、`CacheSpecCustomizer`、通道的监听顺序、`load.timeout`、`refresh-window`、注解时长、Java 的 `Lookup` | §8 其余变化、§5 |

**尝试攻破但成立的论断**（摘要）：取戳在 SingleFlight 注册之前或之后都不影响读己之写，只要 `observed` 先于合并、戳先于 L1 读取、先注销再发布；V5 不改动 SingleFlight 的发布顺序与批量“先完成自己负责的 key”；V6 对单个 key 的写入方与远端事件严格成立，与驱逐、过期、维护任务的交互无害；格式 2 的令牌偏移稳定，7.x 末版之前的节点为格式 2 授予的刷新令牌会被其客户端释放；只读头部的 `getIfChanged` 安全；`Store.delete` 不重试在命令未执行、原子广播、非原子广播三种情形下都有上界（C5 的 5b 除外，已处理）；迟到执行的有序写入安全；单调时钟与不含前缀的 L2 key 不影响 G1 / G2；7.x 末版与 v8 的失效互通（帧、频道、`NodeId` 不变）；Kotlin 的 `when` 对新增的 `Unknown` 编译失败；强制单位存在“先改配置再升级”的路径；`REQUIRES_NEW` 与 `readOnly` 的事务传播下闸门的判定正确；权威缓存不接入 Spring Cache 与 `TransactionalInvalidator` 是对的；V10 不影响 AOT；Wow 的分型正确；Spring Boot 4 的端点只读机制符合生态惯例。

### v2 → v3：第二轮（待进行）

重点：§4.7 的脚本矩阵逐条推演（含 Q13 的 `evict` 编码）、§4.5 条件复核与 Caffeine 的交互、§4.9 `registered-keys` 的残余窗口与默认值（Q4）、§4.8 三层配置的语义与校验、§4.3 能力访问器下的装饰器与 TCK、§8 对 CoSec 与 Wow 的逐步迁移。

## 附录 A：6.8.0 架构审查发现（2026-10-28）

范围：`cocache-api`、`cocache-core`、`cocache-redis`、`cocache-spring`、`cocache-spring-boot-starter`。“复现”表示用临时用例实际运行观察到；“读代码”表示经代码与依赖库确认（spring-tx 7.0.9 字节码、JDK 语义）。6.x 修复的复现用例在各自的 PR 中进入 TCK 或模块测试。

| 编号 | 严重度 | 发现 | 依据 | 位置 | 去向 |
|------|--------|------|------|------|------|
| D1 | 高 | 没有数据源的 `Cache` 在 L1 读失败（默认 `DEGRADE`）时返回 `null`、不报错，并作为已决的负缓存写入 L2（`degradedLocalMaxAge`）；之后 `get(key, loader)` 命中它，不调用 loader | 复现 | `CoherentEngine.loadThrough`、`completeBypass` | 6.x 修复（#700）；v8 §4.1 |
| D2 | 高（权威副本） | 默认 `RETRY` 的延迟重试会删除其它节点之后写入的值 | 复现 | `CoherentEngine.scheduleRetry` | 6.x 修复（#700）；v8 §4.1 |
| D3 | 中 | 没取得刷新令牌的节点按往返频率轮询 L1（持有者回源期间，另一节点的 50 次命中触发 25 次 L1 读取）；`ttlOf` 或显式 `ttl` ≤ 刷新窗口时，持有者背靠背回源 | 复现（前者）、读代码（后者） | `CoherentEngine.refill`、`AbstractEngine.localEntry`、`CachePolicy` 的校验 | 6.x 修复（#698）；v8 §4.6 |
| D4 | 中低 | L2 填充“校验—写入—复核—撤销”不可线性化：写入方返回之后，旧值仍可能短暂可见；撤销还会误删新副本 | 读代码 | `AbstractEngine.fillLocal` | 6.x 待排期；v8 §4.5 |
| R1 | 中高 | 超时只取消 `exceptionallyCompose` 派生的 future，Lettuce 命令不被取消；Cluster 中等待节点连接的命令在超时之后仍会执行 | 读代码 | `RedisConnections.bounded`、`await`、`evalAsync` | 6.x 修复（#701）；v8 §4.6 |
| R2 | 中低 | `sharded` 频道进入未确认状态后只重置一次，之后心跳只重试订阅；陈旧上界退化为 `localMaxAge` | 读代码 | `ShardedInvalidationChannel.ping` | 6.x 修复（#701） |
| R3 | 低 | `subscribe` 与 `Subscription.close` 并发时，订阅者可能加入已被移除的列表，永久收不到失效；经典模式不处理服务端退订 | 读代码 | `RedisInvalidationChannel.subscribe` | 6.x 修复（#701） |
| R4 | 中 | 非原子广播时，条件写入写 L1 成功、广播失败，重放得到 `Stale` 或冲突之后永不广播 | 读代码 | `CoherentEngine.writeIfNewer`、`AbstractEngine.write` | 6.x 修复（#700）；v8 §4.6 |
| T1 | 中高 | 事务回滚时不失效；事务内的回源可能把未提交的数据写进 L1 | 读代码 | `TransactionalInvalidator` | 6.x 修复（#697）；v8 §4.9 |
| T2 | 中低 | 在另一个同步的 `afterCommit` 中登记的失效会丢失；`flush` 遇到第一个异常即停止。`@TransactionalEventListener(AFTER_COMMIT)` 不受影响（它在 `afterCompletion` 中执行） | 读代码（含 spring-tx 7.0.9 字节码） | `TransactionalInvalidator` | 6.x 修复（#697）；v8 §4.9 |
| P1 | 中低 | 接口式代理遇到 Kotlin loader 抛出的受检异常时，由 JDK 包装为 `UndeclaredThrowableException`，与“代理不包装异常”不符 | 读代码（JDK 语义） | `CacheProxies.Handler` | 文档；v8 Q7 |
| C1 | 低 | 时钟用墙钟：墙钟回拨 Δ，L2 副本就多活 Δ | 读代码 | `TickingClock` | 6.x 待排期；v8 §4.5 |
| S1 | 低 | 同步跟随者用 `join()` 等待，不可中断、无上限 | 读代码 | `SingleFlight.await` | 6.x 待排期；v8 §4.6 |
| L1 | 低 | 读 L1 超时之后，不释放读脚本可能已授予的 lease；全集群该 key 在 `lease.ttl` 内每次未命中多等 `lease.wait` | 读代码 | `CoherentEngine.loadThrough`、`RedisRemoteStore.read` | 6.x 修复（#701） |
| L2 | 低 | 同步的 `SetCache.getAll` 逐个 key 串行访问 L1（异步版本是并发的） | 读代码 | `SetEngine.getAll` | 6.x 待排期 |
| L3 | 低 | 策略 `ttl` 永不过期时，`invalidateIfNewer` 的墓碑也永不过期，Redis 内存随删除过的 key 单调增长 | 读代码 | `CoherentEngine.versionedTtl` | 文档；v8 §4.1 `tombstoneTtl` |
| L4 | 低 | namespace 只校验非空：`a` 与 `a:b` 可能映射到相同的存储 key，却监听不同的频道 | 读代码 | `CacheSpec` 的 `requireValidName` | 6.x 待排期 |
| L5 | 低 | `RemoteStore.addMembersIfReadable` / `removeMembersIfReadable` 的默认实现先读后写、不原子，也认不出更高的格式版本 | 读代码 | `RemoteStore` | v8 §4.3 |
| L6 | 低 | `@CacheEvict(allEntries = true)` 只清本节点 L2（文档已写明，但没有启动检查）；actuator 的按 key 删除默认可写，对 `SetCache` 会删除唯一副本 | 读代码 | `CoCacheSpringCache.clear`、`CoCacheEndpoint` | v8 §4.9 |
| L7 | 低 | 客户端以有符号的 `raw[1].toInt()` 解析格式字节，脚本用无符号的 `string.byte`（格式号 ≥ 128 时才有差异） | 读代码 | `RedisRemoteStore` | 6.x 修复（#701） |
| L8 | 低 | 策略时长不带单位时按秒、`cocache.redis.*` 按毫秒；`cocache.defaults.*` 覆盖显式声明 | 读代码 | `CoCacheProperties` | v8 §4.8 |
| L9 | 低 | 创建缓存时，`backend.store` 或工厂抛出会让 namespace 保持占用 | 读代码 | `CacheRuntime` 的注册流程 | 6.x 待排期 |
| L10 | 低 | architecture.md §6.3 写着“启动时不检查这两点”（`maxmemory-policy` 与持久性），而 6.1 起已有 `SetCacheEvictionCheck`（第一轮验证 M11 顺带发现） | 读代码 | `docs/architecture.md` §6.3、`SetCacheEvictionCheck` | 6.x 待排期 |

未发现缺陷的部分：Lua 脚本的状态转换与到期时间重设、令牌比较、`READ` 对 `pttl = -1 / -2` 的处理、带符号 64 位的版本比较、Cluster 分组与 CROSSSLOT、`InvalidationFrame` 解码的边界、`readAllAsync` 的完成与 lease 释放、`CacheRuntime.close` 的顺序、`invalidateEncoded` 经过栅栏、`REQUIRES_NEW` 的作用域。
