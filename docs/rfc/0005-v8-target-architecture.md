# RFC 0005：v8 目标架构（权威与派生分型、单一协议核心、可检验的栅栏）

- 状态：**草案 v4**（2026-10-31），待维护者审阅。
  - v1（2026-10-28）经第一轮对抗验证修订为 v2：一致性、迁移与运维两路，共 28 条发现。
  - v2 经第二轮修订为 v3：协议与格式 18 条、语义与迁移 20 条，共 38 条。两轮的发现全部已处理，见 §12。
  - v3 → v4：维护者的四项决定（2026-10-31，§12）：
    1. **跳过 v7**：6.x 之后直接发布 8.0，不发布 7.x，也没有桥接版本。
    2. **不考虑兼容与数据迁移**：8.x 不读、不写、不迁移 6.x 的数据；8.x 以与用户前缀无关的布局标记与 6.x 物理隔离（V13）。
    3. **基线升到 JDK 21**：协议只有一份同步实现，异步视图在虚拟线程上运行它（V4、V5）。
    4. **配置按 v3 完整实现**（V10）。

    本稿据此删去桥接版本 7.B、格式 1 与格式 2 的共存、配置别名与全部迁移机制，并改写内部结构（§3.2、§4.3、§4.4）。
  - 8.0 冻结线格式之前，再做一轮对抗验证（§12 末尾）。
  - 目标版本由维护者指定为 **v8**。
- 前提：v8 是大版本，API、SPI、配置项、线格式与 key、频道布局都重新设计；但三个目标（[architecture.md](../architecture.md) §1 的 G1–G3）与 6.0 的核心协议（lease 栅栏、两次递增的失效戳、原子广播）**不变**。本 RFC 不推翻 [RFC 0003](0003-target-architecture.md)，而是修正它在 6.4–6.8 增量演进中暴露的结构性问题。
- 关系：
  - 6.x 只修缺陷。6.10.0 是最后一个功能版本（#707、#708、#709、#711），之后在 `6.x` 维护分支上只做缺陷修复（§9）。
  - 不发布 7.x。
  - 规范仍以 architecture.md 为准，本 RFC 实施时同步修改它。
- 来源：
  - 2026-10-28 对 6.8.0 的架构审查（附录 A）。不破坏兼容的修复已在 6.9.0、6.9.1 与 6.10.0 发布（§9）。
  - 两轮对抗验证（§12）。第二轮发现 6.9.0 中仍有两个缺陷（附录 B），已在 6.9.1 修复（#704）。

---

## 0. 结论摘要

| # | 决定 | 一句话理由 |
|---|------|-----------|
| V1 | **权威与派生分成不同的类型，按“L1 丢了这个 key，本进程能否按需从别处重建它”判断**。能，则为 `Cache` / `VersionedCache`（派生副本）；不能，则为 `Store` / `VersionedStore` / `SetStore`（权威副本）。派生缓存**必须声明**数据来源：spec 的数据源，或 `externallySourced()`，即由调用方经 `put`、Spring Cache 或调用级 loader 提供。接口式声明既没有匹配到数据源、也没有声明外部来源时，启动失败 | 6.x 在至少 6 处按“这次读取有没有数据源”在运行时分支（A.D1、A.D2）。“L1 中没有是否等于不存在”对寿命即语义的数据、离线重建的投影、只读消费方都没有确定答案（§12 U7）；“能否按需重建”有 |
| V2 | **`Unknown` 只表示故障**。读取结果是 `Present` / `Absent` / `Unknown`。`Unknown` 只来自 L1 故障与等待超时；在有数据源的缓存上，只在显式 `readFailure = THROW` 时出现。`get` / `getAll` 遇到它抛出 `CacheReadException`，`lookup` 返回它，`Lookup` 另有不必模式匹配的 `valueOrThrow()` / `fold`。没有数据源的缓存可以在声明中写 `unknownRead = ABSENT`。外部来源的缓存在 L1 中未命中时是“未填充”：带标志进入 L2，带 loader 的调用方不接受它 | v1 把正常未命中与故障归为一类（M2）。删除 `unresolved` 标志会让 L2 命中路径分不清“未填充”与 `Absent`（P3）。`as? Lookup.Present` 一类写法会静默吞掉 `Unknown`（U5） |
| V3 | **能力即类型**。条件写入、按版本写入、元素写入只出现在声明了它的类型上；删除全部默认抛出 `UnsupportedOperationException` 的方法，`async()` 成为抽象方法。带版本的类型与无版本的类型是兄弟，带版本的类型上没有丢失版本的写入：派生的带版本副本用保留版本栅栏的 `evict`，编码为新状态 `E`。8.x 只以新的能力类型、或不抛出的默认方法演进 | `Cache` 有 9 个方法默认抛出，`SetCache` 拒绝约一半方法；带版本的类型上的 `put` / `invalidate` 撤掉版本栅栏（C6、M12）。复用 `L` 编码 `evict` 不可行（P1）。全部抽象方法之后，8.x 没有新增操作的规则（U13） |
| V4 | **SPI 只有阻塞形状**。`L1Store`（取代 `RemoteStore`，Q2）的方法同步返回，在调用方线程上执行与等待；没有异步 SPI。能力用抽象的访问器表达，`ForwardingL1Store` 是装饰器的基类。TCK 的 `expectedCapabilities` 没有默认值，断言“提供的能力 = 声明的能力”。SPI 契约写明“确定未执行 / 结果未知”的判定 | 6.x 用两处表达同一事实，还有非原子的默认实现（A.L5）。同步与异步两套 SPI、两套 TCK（`Async*Spec`）只因异步视图不能阻塞线程而存在；基线升到 JDK 21 之后，虚拟线程上的阻塞等待不占载体线程（维护者决定） |
| V5 | **一份协议实现，异步视图跑在虚拟线程上**。协议只有一份同步实现，在调用方线程上运行。`async()` 在调用方线程上查 L2，命中直接返回已完成的 future；未命中把同一个同步调用提交给虚拟线程执行器。挂起视图建立在异步视图上。flight 层逐 key 应用合并规则；注册超过 `load.timeout` 的 flight 被注销。执行协议期间不响应中断，只有跟随者的等待响应中断 | `CoherentEngine` 2405 行，同步与异步逐分支复制（C1、C7、C9）。v3 用 sans-IO 状态机 + 两个驱动消除复制；只剩一个驱动之后，状态机没有第二个使用者，直线代码更易读、更易审查（§4.4 的取舍，Q17） |
| V6 | **L2 填充在 key 锁内校验并写入，写后按身份复核**（Caffeine `compute` + `remove(k, entry)`） | 关闭 A.D4 对写入方与远端事件的窗口；Caffeine 的 `clear()` 看不到进行中的 `compute`，对重置要靠复核（C4） |
| V7 | **失败路径与成功路径一样确定**。按客户端能观察到的事实，区分“确定未执行”与“结果未知”：超时、断线时被排空、等待被中断都属于结果未知。结果未知的写入，安全只能来自顺序。补偿失效只用于失效失败模式为 `RETRY` 的派生缓存。令牌守恒覆盖 `fill` 与 `reclaim`。等待有界，只有跟随者的等待可中断。刷新的触发率与命中率无关 | 已写出的命令照常执行（C5）；中断路径绕过了 #701（P5）；超时与写出无法在客户端区分（P8）；`fill` 失败不释放令牌（P13） |
| V8 | **线格式 2：唯一的格式，按需扩展的头部**。v8 不读写格式 1。核心 36 字节布局沿用，标志位声明版本段（8 B）与摘要段（20 B 的 SHA-1）；bit 7 是可以整体跳过的扩展区；新增状态 `E`。未知的必须理解的标志位等同于更高格式，供 8.x 内部两阶段演进。全部段与状态在 8.0 发布之前定稿。`fill` 不让版本后退，版本拒绝不污染 L2 | 6.8 把版本塞进令牌位置；`getIfChanged` 每次在 Lua 中计算 SHA-1（8 MB 约 20 ms）；格式不预留就会冻结整个 8.x（P6） |
| V9 | **栅栏可被机器检验**：Lincheck（flight 层 + L2 + 失效戳）、TLA+ 模型、带种子的多节点故障仿真 | TCK 的竞态用例只覆盖人想到的交错；两轮验证的大部分发现都需要搜索交错才能稳定复现 |
| V10 | **配置分三层，字段级合并**：`defaults`（低于声明）→ 声明 → 定制器 → `overrides`（高于声明：界限类字段取上限，模式类字段直接设定）→ `caches.[name]`。全局层跳过不适用的字段与被锁定的字段，并记录下来；只有按缓存的层出现这类配置时才启动失败。字段有三态。时长必须带单位，注解仍按秒。库用 `CoCacheFactory` 装配；`lock` 可以锁定策略与存储布局、附加约束，`caches.[name].unlock` 是破窗开关。6.x 的配置键不保留别名，出现即启动失败 | 6.x 的 `defaults` 覆盖显式声明（M5），“设定”语义又会放宽更严的声明（U10）；`lock` 撑不起 CoSec 的约束与存储布局（U2） |
| V11 | **Spring**：<ul><li>写事务内凭 lease 写进 L1 的 key，在事务回滚或结果未知时默认失效。</li><li>starter 以自己的事务装饰器取代 `TransactionAwareCacheManagerProxy`，`@CacheEvict` 经 `TransactionalInvalidator`。</li><li>写事务内、已登记失效的 key，其回源结果私有。</li><li>`Unknown` 在适配器中以 `CacheReadException` 交给 `CacheErrorHandler`。</li></ul> | loader 在调用方的事务内执行（A.T1）；只挡 L1 不够（C2）；一律拦截会让 L1 填不满（M7）；`registered-keys` 对 `@CacheEvict` 用户不起作用（U3） |
| V12 | **规范只写现行行为**：architecture.md 去掉版本叙述，不变量编号稳定（`I-n`）；§6 以对照表逐条标注 architecture.md §14 的“取代 / 修改 / 保留” | v1 只写了“其余不变量保留”，其中 6 条与本 RFC 冲突（C13）；v2 漏了第 21 条（P18） |
| V13 | **与 6.x 物理隔离，不迁移数据**。存储 key 在用户前缀之后带布局标记 `v8:`，频道与探测的 key、频道换成 `cocache:v8:` 前缀；6.x 与 8.x 可以同时部署，互不读写。权威数据由应用在 8.x 上重建，6.x 的 key 由 TTL 或运维清除。同一 namespace 的全部读写方一起切到 8.x | 维护者决定不考虑兼容与迁移。共用 key 时，两个版本互相把对方的内容读作更高格式或读不懂的内容：6.x 绕过 L1 或 `FAIL`，8.x 回收或 `FAIL`，6.x 的整体写入还会覆盖 8.x 的内容；只改默认前缀隔离不了沿用自定义前缀的部署 |

---

## 1. 第一性原理

[RFC 0003 §1.1](0003-target-architecture.md) 的六条公理（串行化点、令牌先于数据、权威发出失效、TTL 是安全网、命中路径只做必要的事、声明与实例分离）全部保留。6.4–6.8 的演进与两轮验证补出下面八条（A13 在 v4 删除）。

- **A7 权威性决定失败语义**。副本可以丢、可以降级，因为它能从别处重建；唯一的副本不能丢，也不能降级成“不存在”。删除副本只在写入方之间没有顺序要求时才总是安全：按版本推送时，L1 中的副本本身就是顺序的仲裁者。一个默认值如果在两种数据上应当不同，它就不该是同一个类型上的同一个默认值。
- **A8 “没有答案”不是“不存在”**。`Absent` 是关于数据的事实，可以缓存、可以广播；`Unknown` 是关于这一次读取的事实，只能返回给这一次调用，也不能交给另一个本可以自己回源的调用方。“未填充”介于二者之间：它对不能回源的读取是答案，对能回源的读取不是。
- **A9 一个协议只写一次**。阻塞还是回调、在哪个线程上继续，是驱动的事，不是协议的事。
- **A10 失败路径也是协议**。超时、取消、拒绝、中断、崩溃之后，系统处于什么状态，要和成功、失败一样确定。
- **A11 不变量要能被机器检验**。
- **A12 “结果未知”不是失败**。已经写出的命令在任何时刻都可能生效，可能排在其它连接上更晚发出的写入之后。让迟到的执行无害的，只有顺序：令牌、版本、条件。而条件本身也有 ABA。
- ~~A13 迁移的单位是共享 namespace 的应用群~~：v4 删除。8.x 与 6.x 物理隔离（V13），不共享 namespace；8.x 内部的格式演进仍遵守“新写入等到所有读者都能读懂之后才出现”（I-L）。
- **A14 全局手段只能收紧，不能放宽**。运维在事故中改的一行全局配置，不应让声明了更严值的缓存变得更松，也不应让任何实例启动失败。

## 2. 现状审查（6.8.0，及 6.9.0 之后）

先说结论：6.0 的核心协议经受住了审查。L1 的 lease 与刷新令牌、Lua 状态机、带符号 64 位的版本比较、Cluster 分组、帧解码都没有发现缺陷；SingleFlight“先注销再发布”与读己之写的论证成立。问题集中在协议之外，可以归为下面七个结构性原因，每个原因都至少产生了一个缺陷（编号见附录 A）。6.9.0 修复了其中不破坏兼容的部分；§2.8 列出 6.9.0 之后仍然存在的问题。

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

漏掉的两处成了最严重的两个缺陷：A.D1（没有数据源的读取在 L1 故障时返回并缓存 `null`）与 A.D2（延迟重试的删除越过之后的写入）。6.9.0 以“没有数据源时读失败抛出、`invalidate` 失败默认抛出”修复了它们（#700）。但这是在类型无法区分权威与派生的前提下，给所有没有数据源的缓存定的默认值：Spring Cache 这类派生副本因此失去了重试（U4）。

而“有没有数据源”本身也不是声明：接口式声明按 bean 类型匹配 `CacheLoader`，泛型不匹配时静默地成为“没有数据源”（`AnnotatedCacheFactory.sourceOf`，M1）。

### 2.2 能力靠运行时异常表达

- `Cache` 的 9 个方法默认抛出 `UnsupportedOperationException`（`getIfChanged`、`putIfMatch` ×2、`putIfNewer` ×2、`invalidateIfNewer` ×2、`getVersioned`、`async`），`AsyncCache` 同样。
- `SetCache` 是 `Cache<K, Set<E>>` 的子类型，却拒绝 `invalidate`、`invalidateAll`、`get(key, loader)` 与全部条件、版本方法。
- 带版本的缓存仍然暴露 `put` / `invalidate`，它们静默丢掉版本顺序（architecture.md §6.5）。
- SPI 用两处表达同一事实：抛出的默认方法 + `CoherenceBackend.versionedWrites`；`RemoteStore.addMembersIfReadable` 的默认实现先读后写、不原子（A.L5）。

### 2.3 协议有两份实现

- `CoherentEngine` 2248 行、`SetEngine` 497 行：同步路径与异步路径“逐分支一一对应”（architecture.md §5.6），共用的只有判定函数。合并结果的取舍（`usable()`、#700 的 `Loaded.failure`）与读己之写重试也各写一遍。
- SPI 与 TCK 都有同步与异步两套；`Futures` 的就地续接是两份实现的代价，不是协议本身的需要。

### 2.4 失败路径不够确定

- A.R1（超时后命令仍被写出）、A.R4（非原子广播下重放不广播）、A.L1（读超时遗留 lease）、A.D3（刷新轮询与背靠背回源）：6.9.0 已修复。
- A.S1：同步 SingleFlight 的跟随者用 `join()` 等待，不可中断、无上限。可中断已在 6.10.0 修复（#711）；等待的上限属于 v8 的 `load.timeout`。
- 即使超时能取消到底层，已经写出的命令也照常执行，而且可以排在其它连接上更晚的写入之后（C5）。6.x 把这类写入当作“幂等，可以安全重试”（architecture.md §7），这对无条件写入不成立。

### 2.5 检验手段只覆盖想到的交错

TCK 以 latch 编排的交错是本项目的优势。但 A.D4、A.R1，以及第二轮的 P5、P13，都是读代码发现的，没有现成的用例能发现它们。

### 2.6 线格式的余量已经用完

- 标量头只有 1 个保留字节。6.8 的版本只能放进令牌位置，带版本的内容因此不能持有刷新令牌（architecture.md §6.5）。
- 摘要不在头部，条件读写每次把载荷读进 Lua 并计算 SHA-1：Redis 7.4 上 8 MB 的值一次约 20 ms（§6.4）。

### 2.7 配置分层与单位

- `cocache.defaults.*` 覆盖声明中显式写出的值（architecture.md §9.4），于是需要 `setPolicyFor` / `versionedPolicyFor` 逐类排除。
- 策略时长不写单位时按秒，`cocache.redis.*` 按毫秒（A.L8）。
- 库在 starter 之外装配缓存时，只能依赖兼容承诺之外的入口（CoSec 用 `CoCacheProperties.policyFor` + `CacheProxies.create`，Wow 用 `CacheAssembler`），也没有办法保护自己的不变量（M9、U2）。

### 2.8 6.9.0 之后仍然存在的问题

- **中断路径绕过了 #701**（附录 B.1）：`RedisConnections.await` 被中断时不取消命令，`releaseAfterTimeout` 只在超时时释放令牌。被中断的 `put` 在 Cluster 中仍可迟到执行；被中断的读取留下 lease。
- **`fill` 失败时不释放令牌**（附录 B.2）：被 `CommandCall` 完成、没有写出的 fill，会留下 lease 到 `lease.ttl`。
- **`@CacheEvict` 在回滚时不执行**：它经 Spring 的 `TransactionAwareCacheDecorator`，只实现 `afterCommit`。同一事务内的 `@Cacheable(sync = true)` 读到未提交的行时，回滚之后这个值留到 `ttl`（6.9.0 changelog 的“Not covered”，U3）。
- **没有数据源时的默认值**（§2.1）：Spring Cache 这类派生副本也得到了权威副本的失败语义（U4）。

## 3. 目标架构总览

### 3.1 用户 API 的类型

```
ReadableCache<K, V>             name、get、getAll、lookup、lookupAll、async()
├── Cache<K, V>                 派生副本：get(k, loader)、put、putAll、invalidate、invalidateAll
├── VersionedCache<K, V>        派生的带版本副本：getVersioned、putIfNewer、invalidateIfNewer、evict、purge
├── Store<K, V>                 权威副本：put、putAll、delete、deleteIfMatch、getIfChanged、putIfMatch
├── VersionedStore<K, V>        权威的带版本副本：getVersioned、putIfNewer、deleteIfNewer、purge
└── SetStore<K, E>              权威集合（ReadableCache<K, Set<E>>）：add、remove、clear、put、putAll
```

- 每个接口只声明它支持的操作，全部是抽象方法。异步视图与挂起视图有同构的层次，由引擎以同一个同步实现提供（§4.4）。带版本的类型不继承无版本的写入。
- `Lookup` = `Present` | `Absent` | `Unknown`（V2，§4.1），另有 `valueOrThrow(): V?`（`Unknown` 时抛出 `CacheReadException`）与 `fold(onPresent, onAbsent, onUnknown)`，供不便穷尽匹配的调用方使用（JDK 17 没有对 sealed 类型的穷尽 `switch`）。
- spec 与返回类型一一对应：
  - `cacheSpec` → `Cache`，必须声明 `loader`、`asyncLoader` 或 `externallySourced()` 之一；
  - `versionedCacheSpec` → `VersionedCache`，必须有带版本的数据源；没有数据源的带版本缓存就是 `VersionedStore`；
  - `storeSpec` → `Store`；
  - `versionedStoreSpec` → `VersionedStore`；
  - `setStoreSpec` → `SetStore`。
- 组合视图拆成两种：`JoinView`（只读，两侧是 `ReadableCache`）与 `JoinCache`（两侧都是 `Cache`，保留 6.x 的两个 `invalidate` 方法）（M14）。

### 3.2 内部结构

```
            ┌───────────── 命中路径（手写，同步与异步共用） ─────────────┐
用户调用 ──▶│ L2 查找 + 过期比较 + “未填充”标志 → 返回                     │
async() ───▶│ 命中：在调用方线程上返回已完成的 future                       │
            └───────────────┬─────────────────────────────────────────────┘
                            │ 未命中 / 写入（async()：提交给虚拟线程执行器）
                            ▼
            ┌───────────── flight 层 ───────────────────────────────────────┐
            │ 注册 → 取戳 → 运行协议 → 比较戳 → 至多重试一次                   │
            │ 逐 key 的合并规则；超过 load.timeout 注销；中断只作用于跟随者     │
            └───────────────┬─────────────────────────────────────────────┘
                            ▼
            ┌───────────── 协议（一份同步实现） ─────────────────────────────┐
            │ 单 key 未命中、批量、提前刷新、写入四条路径；                     │
            │ L2 填充与本节点淘汰是显式的步骤，令牌在 finally 中释放或填充      │
            └───────────────┬─────────────────────────────────────────────┘
                            ▼
              L1Store（阻塞，经 ForwardingL1Store 装饰）、InvalidationChannel、L2、失效戳
```

## 4. 核心设计

### 4.1 权威与派生（V1、V2）

**选择类型只问一个问题**：L1 丢了这个 key，本进程能否按需从别处重建它？

- **能**：`Cache`，或带版本的 `VersionedCache`。
  - 有数据源的业务缓存。
  - Spring Cache 背后的缓存：被注解的方法就是数据源，声明 `externallySourced()`。
  - 只经调用级 loader 读取的缓存，如 CoSec 的 `MembershipCache`，同样声明 `externallySourced()`。
  - Wow 的状态视图（`versionedLoader`）。
- **不能**：`Store` / `VersionedStore` / `SetStore`。
  - CoSec 的策略快照、控制面的唯一副本缓存、撤销表、影子缓存。
  - 寿命即语义的数据（会话、一次性验证码、幂等键）：需要显式的 `ttl` 与不驱逐的 Redis。
  - 只能离线重建的投影、只读消费另一个服务维护的数据：本进程无法按需重建，应声明为 `Store`。
- **不支持的用法**：write-behind（先写缓存、再异步落库）。未落库的部分，L1 是唯一副本，而落库之后它又是派生副本；没有一个类型能同时满足两者。
- 同一个 namespace 的所有声明必须是同一类型。类型不在线格式中，跨应用的不一致无法在协议层发现；端点输出每个缓存的类型，供人工核对（Q14）。

读失败时放行还是拒绝，**不是类型的属性**，由 `unknownRead` 在声明中写明（见下）。

| 方面 | `Cache`（派生副本） | `Store` / `VersionedStore` / `SetStore`（权威副本） |
|------|--------------------|----------------------------------|
| 数据来源 | 必须声明：spec 的 loader / asyncLoader，或 `externallySourced()` | 按构造没有 |
| L1 中不存在 | 有数据源：回源；外部来源：“未填充”，带标志、受戳保护进入 L2（限龄 `absentTtl` 与 `localMaxAge`）。不带 loader 的读取看到 `Absent`，带 loader 的调用方不接受它，用自己的 loader 回源 | `Absent`，受戳保护进入 L2（限龄 `absentTtl` 与 `localMaxAge`） |
| lease | 未命中时申请 | **不申请**；读到 lease 占位就是 `Absent`（C11） |
| L1 读失败 | 有数据源（含调用级 loader）：降级回源；显式 `readFailure = THROW` 时为 `Unknown`。没有数据源的读取：`Unknown` | `Unknown` |
| `Unknown` 的处理 | `get` / `getAll` 抛出 `CacheReadException`（`cause` 为原始异常），`lookup` 返回 `Unknown`；声明 `unknownRead = ABSENT` 时返回 `Absent`（见下） | 同左（`SetStore` 不允许 `unknownRead`） |
| 读不懂的内容 | 有数据源：`RECLAIM`；外部来源：`FAIL` | `FAIL`（`foreign-format: reclaim` 只能在 `caches.[name]` 中为清理格式 0 的遗留而设置） |
| 删除 | `invalidate`，失败默认 `RETRY`：删除副本在任何时刻都安全 | `delete` / `deleteIfMatch`：同步完成，失败抛出 `CacheWriteException`，**从不排队重试** |
| 写入 | `put`（后写者胜，不推荐；首选先提交数据源再 `invalidate`） | `put`、`putIfMatch`；带版本的类型只有按版本写入 |
| 负缓存 | 回源返回 `null` 时写入 `M` | 不适用：`Absent` 就是不存在 |
| 默认 `ttl` | 1 h | 永不过期；`VersionedStore` 的墓碑与 `E` 另有有限的 `tombstoneTtl` |
| 提前刷新 | 有数据源时支持 | 不适用 |
| 启动检查 | — | 驱逐策略（见下） |
| Spring Cache、`TransactionalInvalidator` | 可接入（`VersionedCache` 除外） | 不可接入 |

**外部来源的默认值**（U4）：
- `externallySourced()` 的缓存按定义是派生副本，所以得到派生副本的默认值：`invalidate` 失败 `RETRY`。读失败仍是 `Unknown`、默认抛出，交给 Spring 的 `CacheErrorHandler` 一类机制决定。
- 6.9.0 为所有没有数据源的缓存把 `invalidate` 的失败改成了 `THROW`（#700），v8 对声明为外部来源的缓存恢复 `RETRY`。这由显式声明触发：升级时必须写出 `externallySourced()`；启动日志的来源层标注“类型默认值”。

**误分类的后果**（替代 v2 §8 中过于乐观的说法）：

| 误分类 | 后果 | 发现方式 |
|--------|------|----------|
| 唯一副本声明成 `Cache` + `externallySourced()` | 默认 `ttl` 1 h，唯一副本一小时后过期（最严重的一条）；`invalidate` 默认 `RETRY`，结果未知的 `put` 登记补偿失效，二者都是延迟的无条件删除（A.D2）；端点与 `ExternalInvalidator` 的删除不受保护；不做驱逐策略检查 | 启动日志与端点逐缓存列出类型与数据来源；skill 的静态审计；文档的判定问题 |
| 可重建的副本声明成 `Store` | L1 丢失之后读成 `Absent`，并进入 L2，直到有人重写；`delete` 失败抛出、不重试 | 存在匹配的 `CacheLoader` bean 时启动告警 |
| 本该有数据源却没有匹配到 | — | 启动失败：既没有数据源，也没有声明外部来源 |

**`Unknown` 何时出现**（U5）：

| 情形 | 有数据源（含调用级 loader） | 没有数据源 |
|------|----------------------------|------------|
| L1 读失败，`readFailure` 未设置或 `DEGRADE` | 降级回源：`Present` / `Absent` | `Unknown` |
| L1 读失败，`readFailure = THROW` | `Unknown` | `Unknown` |
| 等待 lease 超时、失效重试中、更高格式 | 绕过 L1 回源 | “未填充”（外部来源）/ `FAIL`（权威，更高格式） |
| 跟随者等待超时（`load.timeout`）、回源被拒绝 | 抛出相应异常；`lookup` 同样抛出，不返回 `Unknown`：它们不是 L1 故障 | 同左 |

- **`getAll` 的部分 `Unknown`**：先完成其余 key（释放 lease 或写回），再抛出 `CacheReadException`。异常携带 `unknownKeys` 与已得到的部分结果。需要逐 key 处理的调用方用 `lookupAll(keys): Map<K, Lookup<V>>`。
- **`unknownRead = ABSENT` 的范围**（U9）：
  - 作用于 `get` / `getAll` / `lookup` / `lookupAll` 及其异步、挂起视图；不作用于 `getIfChanged`、`getVersioned` 与条件写入。
  - 只能用于没有数据源的缓存与权威缓存；`SetStore` 上不允许。
  - 只能出现在声明与 `cocache.caches.[name]` 中，不能出现在 `defaults` / `overrides`（否则一行配置就让全部权威缓存 fail open），可以被 `lock`。
  - 映射发生在调用方返回之前：flight 共享的结果仍是 `Unknown`，带 loader 的调用方不会接受它（P3）。
  - 计数：`cocache.reads.unknown` 与 `cocache.remote.failures` 都计。
- **合并规则**（C1、P10）：
  - flight 的共享结果是 `Present` / `Absent` / `Unfilled` / `Unknown` / `Private`（§4.9）之一。
  - 带数据源的调用方（含调用级 loader）不接受 `Unfilled`、`Unknown`、`Private`：按 6.x 的规则重试一次，仍不可用时绕过 SingleFlight，自己运行状态机。
  - 合并规则逐 key 应用：单 key 与批量、同步与异步都一样，批量中不可用的 key 改走单 key 路径。
- **`Store` 的删除不排队重试**（A7、A.D2）。需要可靠删除的写入方用 `deleteIfNewer`：版本严格递增，迟到执行不会越过之后的写入。`deleteIfMatch` 迟到执行时，可能删除之后重新写入的相同内容（P4、A12），文档写明。
- **驱逐策略检查**（M11、U19）：
  - 读 `INFO memory` 的 `maxmemory_policy`，托管 Redis 通常允许 `INFO`；读不到时按 `cocache.redis.assume-maxmemory-policy` 处理，没有配置则启动失败。
  - 按声明检查。`allkeys-*` 时启动失败；`volatile-*` 且存在 `ttl` 有限的权威缓存时告警。
  - 6.10.0 已改读 `INFO memory`、按声明检查，读不到时按 `set-cache-eviction-check` 告警或失败（#707）；v8 改为一律启动失败。

### 4.2 能力类型（V3）

- 删除全部抛出的默认方法；`async()` 是抽象方法；代理按接口的实际方法集分发，漏转发时编译失败。
- 接受缓存的组件按需要的能力声明参数类型：`TransactionalInvalidator` 与 Spring 的 `CacheManager` 只接受 `Cache`。
- **带版本的类型上没有丢失版本的写入**（C6、M12、P1）：
  - `VersionedCache`：`putIfNewer`、`invalidateIfNewer`（墓碑）、`evict`、`purge`。
    - `evict` 是保留版本栅栏的失效：L1 中的内容换成状态 `E(当前版本)`，读作未命中；之后的回源与 `putIfNewer` 都必须不低于这个版本。编码与逐脚本规则见 §4.7。失败默认抛出。
    - `purge` 是运维清除：丢掉版本，只经端点的显式开关或代码调用，默认不重试。
  - `VersionedStore`：`putIfNewer`、`deleteIfNewer`、`purge`。没有 `put`、`delete`、`putIfMatch`。
  - `VersionedCache` 的数据源只能是带版本的数据源（C3）。
  - 端点对 `VersionedCache` 的按 key 删除映射到 `evict`；`purge` 需要 `management.endpoint.cocache.allow-purge=true`（U19）。
- **8.x 的演进规则**（U13）：8.x 新增的用户操作只能是以下两种之一：
  - 新的能力类型上的方法；
  - 用已有抽象方法实现的、不抛出的默认方法。

  需要后端支持的新能力，走 SPI 的能力访问器。用户 API 的接口在文档中注明“不供用户实现，委托除外”（Kotlin 可以用 `@SubclassOptInRequired`）。否则按 8.0 编译的委托类（如 CoSec 的 `PolicySnapshotCache : Cache by delegate`）会在调用新方法时抛出 `AbstractMethodError`。

### 4.3 SPI（V4）

```
CoherenceBackend         store(namespace)、channel、format、atomicBroadcast
L1Store                  read、readAll、fill、fillAll、release、reclaim、put、putAll、invalidate（阻塞）
                         conditional(): ConditionalL1Store?   readIfChanged、putIfMatch、deleteIfMatch
                         versioned(): VersionedL1Store?       putIfNewer、evictKeepingVersion
                         sets(): SetL1Store?                  addMembers、removeMembers（只有“检查并写入”）
ForwardingL1Store        转发全部操作与能力，装饰器继承它，只覆盖要拦截的部分
InvalidationChannel      publish、subscribe（阻塞）
```

- **只有阻塞形状**（V4）：
  - 后端在调用方线程上执行并等待。调用方可能是平台线程，也可能是异步视图的虚拟线程（§4.4）；虚拟线程上等待 `CompletableFuture` 或 socket 不占载体线程。
  - Redis 后端照旧以 Lettuce 的异步命令发出、在调用方线程上等待。`readAll` / `fillAll` 先全部发出再逐个等待，仍是约一次往返；集群按 slot 分组同 6.x。
  - 删除 `AsyncRemoteStore`、`AsyncInvalidationChannel`、`BlockingAsyncStore` / `BlockingAsyncChannel` 与全部 `Async*` TCK 规范。第三方后端只实现一种形状。
- **能力用抽象的访问器表达**：不支持时返回 `null`，不抛出，也没有默认实现，否则只包装基本操作的装饰器会静默剥掉能力（U15）。`runtime.create` 在创建时检查所需的能力，缺失时抛出，不注册任何东西。SPI 不提供任何非原子的默认实现。
- **TCK fail closed**：`expectedCapabilities` 没有默认值，规范断言“提供的能力 = 声明的能力”，多出来的能力也算失败。启动日志输出每个 namespace 的能力。
- **“确定未执行 / 结果未知”的契约**（P8）：
  - **确定未执行**：在写出之前就被拒绝（如 Lettuce `REJECT_COMMANDS` 的 `Currently not connected`），或在 `dispatch` 之前同步失败。
  - **结果未知**：其余情形，包括超时、断线时被排空（`Connection disconnected`）、等待被中断，以及脚本的错误回复（除非逐个脚本证明错误只可能发生在第一次写入之前）。
  - 其它契约：
    - 超时与中断取消到底层命令：尚未写出的命令不再写出（6.9.0 / 6.9.1 的 `CommandCall`，#701、#704）。
    - 写入方法返回或抛出时，它发出的全部命令都已结束（否则补发的广播可能先于仍在执行的命令）。
    - 取得或交回令牌的命令结果未知时，尽力释放令牌（6.9.1 已实现，§4.6）。
  - 这些契约写进 SPI 的 KDoc，由 TCK 的 `FaultSpec` 覆盖。
- **摘要的公开入口**：`Digest.of(Envelope)` 与 `Envelope.Scalar(bytes)` 保留，另提供 `Digest.ofPayload(bytes)`。算法仍是 SHA-1（§4.7）。
- **命名**（Q2，v4 决定）：SPI 的 `RemoteStore` 改名为 `L1Store`，避免与用户 API 的 `Store` 混淆。

### 4.4 协议与 flight 层（V5）

- **一份同步实现**：协议的四条路径（单 key 未命中、批量、提前刷新、写入）各写一次，在调用方线程上运行。L2 填充（`fillLocal`）与本节点淘汰（版本前进的 `fill`，§4.7）是代码中显式的步骤；取得令牌之后的代码在 `try/finally` 中填充或释放，令牌守恒由结构保证（I-F）。
- **异步视图**：
  - `async()` 的读取先在调用方线程上查 L2：命中直接返回已完成的 future，不提交任何任务（G3）。
  - 未命中与写入把同一个同步调用提交给异步执行器，由执行它的线程完成返回的 future。默认执行器是 `Executors.newVirtualThreadPerTaskExecutor()`，可以替换；运行时关闭时只关闭自建的执行器。
  - 提交被拒绝时调用尚未开始，没有令牌，返回的 future 以 `RejectedExecutionException` 失败。
  - 取消返回的 future 不中断执行它的线程，不取消共享的加载（I-17）。
  - `AsyncCacheLoader` 保留：同步路径上调用它并在当前线程上等待，受 `load.timeout` 约束。
- **挂起视图**：建立在异步视图上（`await()`），与 6.4 相同。
- **等待**：lease 等待（`parkNanos`）与回源许可等待（限时 `tryAcquire`）在当前线程上阻塞，在虚拟线程上不占载体线程。取代 6.x 异步路径的定时器调度与许可等待队列。
- **虚拟线程的钉住**：JDK 21–23 上，虚拟线程在 `synchronized` 块内阻塞会钉住载体线程（JEP 491 在 JDK 24 消除）。
  - CoCache 自身在持锁时不阻塞：Caffeine 的 `compute` 内不做 I/O，SingleFlight 用 `CompletableFuture` 等待，Lettuce 的写出路径用 `ReentrantLock`。
  - 用户 loader 若在 `synchronized` 内做阻塞 I/O（如部分 JDBC 驱动），在 JDK 21–23 上可能耗尽载体线程。文档建议生产环境使用 JDK 24 及以上；需要时把异步执行器换成平台线程池。
- **flight 层**：读取 `observed` → 注册 → 取戳 → 运行协议 → 比较 `stamp ≥ observed` → 至多重试一次 → 按合并规则逐 key 决定是否自行运行（§4.1）。异步调用就是虚拟线程上的同步调用，不再区分同步与异步的 leader。另有三条：
  - 注册超过 `load.timeout` 的 flight 从表中注销，但不完成它（P16）。新调用方开始新的 flight；leader 迟到的结果只返回给它自己，写回仍受令牌与戳保护。注销早于发布，不破坏“先注销再发布”。
  - 同步 loader 本身无法被外部结束，文档要求它自带超时。
  - 由 Lincheck 检验（§4.10）。
- **中断**（P5、C12）：
  - 执行协议期间不响应中断：开始时以 `Thread.interrupted()` 清除并记住标志，结束（含释放令牌）之后再恢复。这样 leader 的释放在恢复了中断标志的线程上也会执行。
  - 只有跟随者等待 flight 时响应中断，且只影响它自己（6.10.0 已实现，#711）。v8 以非受检的 `SourceLoadInterruptedException` 取代 6.10.0 的 `InterruptedException`，经接口式代理时不再被 JDK 包装（A.P1）。
  - leader 的 loader 因中断失败时，flight 以 `SourceLoadInterruptedException` 失败，跟随者按合并规则自行运行。
- **`Private` 的读取不申请 lease**（P10）：未命中直接回源、不写任何一层，不让其它节点白等 `lease.wait`。
- **命中路径**留在引擎里手写，G3 不受影响。
- **性质测试**：以注入故障的内存后端（`HookedBackend`）在每一步之后注入失败、超时与中断，对任意的故障序列断言：
  - 取得的令牌最终被填充或释放；
  - 填充出示的令牌取得于读取之前；
  - L2 填充的戳取得于读取之前；
  - `Unknown` 与 `Private` 不进入 L2 与 L1；
  - 版本拒绝的刷新不进入 L2；
  - `Unfilled` 只以带标志的副本进入 L2。

**v3 的方案：sans-IO 状态机 + 两个驱动**。v4 不再作为主方案：基线升到 JDK 21 之后只剩一个驱动，状态机没有第二个使用者，每条路径却要拆成“指令”与“恢复”两半，审查与调试都更难。它余下的主要收益是确定性的多节点仿真（以带种子的调度器驱动状态机，可以精确重放）；v4 以带种子的故障仿真代替（§4.10），可以复现但不能逐步重放。是否为此保留状态机，见 Q17。

### 4.5 L2：栅栏、时钟与 key（V6）

```kotlin
local.asMap().compute(storageKey) { _, current -> if (stamps.isValid(storageKey, stamp)) entry else current }
if (!stamps.isValid(storageKey, stamp)) {
    local.asMap().remove(storageKey, entry)   // 按身份撤销自己写入的那一份
}
```

- **对写入方与远端事件，严格成立**：单个 key 的淘汰经 `computeIfPresent`，与 `compute` 在同一个桶的监视器上串行化（第一轮已对照 Caffeine 3.2.4 源码确认）。
- **对重置与 `clearLocal`，以填充方自己的这一步为界**：`clear()` 按快照删除，看不到空桶中进行中的 `compute`，这时由写后复核撤销。这是程序顺序上的界，不是时间上的界：填充线程被抢占时，旧副本可见的时长没有上界。需要严格成立时见 Q11。
- **身份比较是约束**（P18）：Caffeine 对强引用值的 `remove(key, value)` 以 `Objects.equals` 比较。`LocalEntry` 不得改为 data class，也不得重写 `equals`；KDoc 与测试写明这一点。
- 进入 `compute` 之前先做一次 `isValid`，失败就不进（省掉一次访问时间的刷新）。
- 只影响未命中路径。

**单调的时钟**：`TickingClock` 以启动时的墙钟为基准，加 `System.nanoTime()` 的增量（A.C1，6.10.0 已实现，#711）。

**L2 的 key 不含前缀（待 JMH）**：用编码后的 key 作为 L2、失效戳与 SingleFlight 的 key；收到失效帧时剥掉本缓存的前缀。只有 `scripts/jmh-compare.sh` 在 1 与 8 线程上（含 `l2HitMixed`）显示不退化时才采用。

### 4.6 失败路径（V7）

| 规则 | 反例 |
|------|------|
| 写入失败按客户端能观察到的事实分为“确定未执行”与“结果未知”（§4.3 的契约），以 `CacheWriteException.outcomeUnknown` 区分 | C5、P8 |
| 被中断的等待按“结果未知”处理，与超时相同：取消命令、尽力释放取得的令牌（6.9.1 已实现，附录 B.1） | P5 |
| 结果未知的写入在任何时刻都可能生效。迟到无害的只有有序的写入：`fill`（令牌）、按版本写入（版本）、条件写入（摘要，但有 ABA） | C5、A12、P4 |
| 派生 `put` / `putAll` 结果未知时，只在缓存的失效失败模式生效值为 `RETRY` 时登记补偿失效（复用 pending）；为 `THROW` 时以 `outcomeUnknown` 抛给调用方。陈旧上界：以这次 `put` 的 `ttl` 为界；在同一条连接上没有发生重连时，补偿失效完成即结束 | C5 5a、P9、U8 |
| pending 期间，外部来源的缓存上不带 loader 的读取返回“未填充”（对调用方是 `Absent`），不写 L2；带 loader 的调用方照常回源 | U8 |
| 写入结果未知时，本节点不再过滤关于这个 key 的自身帧，直到同一条连接上之后发出的一条命令完成，再加一个投递余量。在此之前发生重连时无法确认，这时退回 `onReset` 或保持到 `localMaxAge`。残余上界是 `localMaxAge`，与事件丢失相同 | C5 5b、P12 |
| `Store`：`deleteIfMatch` 与 `putIfMatch` 的摘要比较在迟到执行下有 ABA。“当前摘要等于自己写入的摘要”只能证明已写入，不能证明未写入（Q10） | C5 5c–5e、P4 |
| `SetStore` 的元素写入在结果未知时按“每个元素后写者胜”收敛，没有有序的重放方式 | C5 5e |
| 令牌守恒：`Read`、`ReadAll`、`Reclaim`、刷新的 `Read`、**`Fill` / `FillAll`** 在每个失败转移上都有释放（取得令牌之后的代码在 `try/finally` 中）。超时与中断也尽力释放：释放走同一条连接、排在原命令之后，令牌不匹配时无害 | A.L1、C7、P13 |
| 每个注册的 flight 在有限时间内完成，或在 `load.timeout` 之后被注销 | C7、P16 |
| 无条件的延迟写入只用于派生副本：有数据源的缓存，以及外部来源且失效失败模式为 `RETRY` 的缓存 | A.D2、U8 |
| 等待有上界：跟随者共用 `load.timeout`（默认 30 s）；只有跟随者的等待响应中断（§4.4） | A.S1、C12、P5 |
| 非原子广播时，下列重放同样广播（多余的广播只淘汰副本）：`Stale` 且当前版本等于请求的版本；冲突摘要等于本次载荷的摘要；`deleteIfMatch` 冲突且当前没有值；`evict` 遇到 `E(同一版本)` | A.R4、P17 |
| 提前刷新的触发率与命中率无关：没取得令牌的重新填充按 `now + min(max(lease.wait, 100 ms), 剩余寿命 / 2)` 退避（6.9 的实现，#698）；剩余寿命 ≤ 刷新窗口的副本不设刷新点 | A.D3、P18 |

### 4.7 线格式 2（V8）

**key 与频道的布局**（V13）：

| 用途 | 6.x | 8.x |
|------|-----|-----|
| 存储 key | `{keyPrefix}{namespace}:{encodedKey}` | `{keyPrefix}v8:{namespace}:{encodedKey}` |
| 失效频道 | `cocache:inv:{namespace}` | `cocache:v8:inv:{namespace}` |
| 探测 key / 频道（`pubsub` 模式） | `cocache:probe:{a}` / `cocache:probe:{b}` | `cocache:v8:probe:{a}` / `cocache:v8:probe:{b}` |

- 布局标记与用户前缀无关：沿用自定义 `key-prefix` 的部署同样与 6.x 隔离。
- 默认前缀仍是 `cocache:`，key 的 ACL 模式 `~cocache:*` 不变；频道的 ACL 改为 `&cocache:v8:inv:*`，`pubsub` 模式另加 `&cocache:v8:probe:*`。
- `ExternalInvalidator` 与运维文档按新布局构造 key 与频道。
- 标记的拼写在实施时定（Q19）。

**头部**：

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0–35 | 36 | 核心头部：magic、格式版本（`2`）、状态、**标志位**（6.x 的保留字节）、令牌、令牌签发时间 |
| 36 | 8 | 版本（bit 0），大端有符号 64 位 |
| 之后 | 20 | 载荷摘要（bit 1）：载荷的 SHA-1，与 `Digest` / `Digest.of` 相同，由写入方在客户端计算 |
| 之后 | 2 + n | 扩展区（bit 7）：2 字节长度 + 若干 TLV，读者可以整体跳过 |
| 之后 | n | 载荷 |

- **全部内容都是格式 2**：lease 占位 `L`、负缓存 `M` 与派生缓存的普通值不带任何段，头部仍是 36 字节，与 6.x 同样大小。格式号是 2，误读到它的 6.x 节点按“更高格式”处理，既不回收也不写回。
- **载荷偏移由标志位计算**：所有脚本与客户端共用一个函数，TCK 覆盖“只有版本段 / 只有摘要段 / 两者都有 / 带扩展区”。只有版本段时，载荷从字节 44 开始，Lua 中是 `string.sub(v, 45)`（P18）。
- **标志位分两类**：
  - bit 0–6 是必须理解的。未知的必须理解的位等同于更高格式：既不回收也不写回，有数据源的读取绕过 L1，权威缓存的读取 `FAIL`，条件写入与按版本写入拒绝，`READ` 不授予令牌。
  - bit 7 的扩展区只放“旧读者可以忽略、整体覆盖时丢失也无害”的信息（如写入标识）。
- **定稿时点**（P6）：以下段与状态在 8.0 发布之前全部定稿：
  - 版本段；
  - 摘要段；
  - 扩展区；
  - 状态 `E`；
  - Q10 的修订号或写入标识占用的位置。

  之后再新增必须理解的段，一律在 8.x 内两阶段发布：先发布能读懂它的版本，一个 namespace 的全部节点都升级之后才写入（I-L）。

**写入哪些段**：

- 派生缓存的普通值、`L`、没有版本的 `M`：不带段。
- 权威标量的值：带摘要段。`StoreSpec.digest = ON_DEMAND` 时不写摘要段，条件读写退回在 Lua 中现算（U20）。
- 带版本的内容：带版本段。`VersionedStore` 只带版本段（它没有条件读写）。
- 状态 `E`：带版本段，没有载荷。

**读不懂的内容**：v8 不读写格式 1。读到格式 1、格式 0（5.x 遗留）或其它不是本协议写入的内容时，按读不懂处理：派生缓存条件回收（`RECLAIM`），权威缓存 `FAIL`。在物理隔离之下（V13），这只在配置错误或外部工具写入时发生。删除 `foreign-format` 配置与 `VERSION_MARK` 的识别。

**摘要算法**：仍是 SHA-1，`Digest` 的类型、`sha1` 属性与 `Digest.of` 都不变。保留它不再是为了兼容，而是因为 Redis 的 Lua 只有 `redis.sha1hex`：`RECLAIM` 与 `digest = ON_DEMAND` 的条件读写要在脚本中计算摘要。代价：SHA-1 已有可行的选择前缀碰撞（Q6）。

**状态 `E`（保留版本栅栏的失效）**（P1）：

| 脚本 | 对 `E(v)` 的处理 |
|------|-----------------|
| `EVICT_KEEPING_VERSION` | 当前内容带版本段（`V` / `M` / `E`）：整体写为 `E(当前版本)`，令牌为零，到期时间由引擎传入（默认 `tombstoneTtl`），广播。当前内容没有版本：等同 `DEL` + 广播。读不懂的内容：拒绝 |
| `READ`（申请 lease） | 令牌为零或签发超过 `lease.ttl`：以 `SETRANGE 4` 写入令牌与 `TIME`（保留状态、版本段与到期时间），返回 `Miss(leaseGranted)`。否则返回 `Leased`，剩余时间为 `lease.ttl` 减去已签发时长 |
| `READ`（不申请 lease） | `Miss`：不是 `Absent`，也不是 `Leased` |
| `FILL` | 令牌匹配且 `fill.version ≥ v`：写入，并广播（淘汰其它节点降级填充的旧副本）。否则拒绝：脚本内清零令牌，返回 v |
| `RELEASE` | 令牌匹配：清零令牌，保留 `E` 与到期时间；**从不 `DEL`** |
| `RECLAIM` | 拒绝 |
| `PUT_IF_NEWER` / 墓碑 | 给出的版本 w > v 时写入，否则 `Stale(v)` |
| `PUT` / `PUT_ALL` / `INVALIDATE` / `purge` | 整体覆盖或删除（丢掉版本，见 I-K 的入口清单） |
| 条件脚本、集合脚本 | 读不懂，拒绝；不是“没有值” |

为什么不复用 `L`：现行的每个脚本都对 `L` 有特判（`READ` 直接返回 `Leased` 且不按签发时间抢占，`RELEASE` 见 `L` 即 `DEL`，`versioned_state` 与 `conditional_state` 把它当作没有值），复用就要逐个推翻它们，漏掉一处就是静默的栅栏丢失。附带的收益是：对解码失败的带版本内容，`RECLAIM` 可以改为转成 `E(v)`，去掉 6.8 的限制“带版本的内容不能回收”。

**带版本的内容与 `fill`**（C3、P2、P11）：

- 当前内容带版本时：
  - 填充的版本小于当前版本，或为 `NO_VERSION`：拒绝。脚本内清零匹配的令牌，返回当前版本，省一次 `RELEASE` 往返。
  - 相等：写入，相当于续期，不广播。
  - 更大：写入并广播。
- 版本拒绝之后，客户端：
  - 刷新路径：不写 L2，保留现有副本，刷新点按退避。否则回源得到的更旧的值会替换掉 L2 中的新值。
  - 未命中路径（lease 在 `E` 上）：回源值是唯一的答案，返回它，以降级限龄写入 L2，并计入 `cocache.fill.rejections`。文档写明：在 loader 追上之前，`evict` 之后读到的是 loader 的版本。
- 版本前进的 `fill` 是一次写入：`FILL` 返回“已前进”之后，本节点递增戳并淘汰本地副本，不填充 L2，下一次命中多一次 L1 读取。它的帧在本节点被过滤，没有这一步时，绕过 SingleFlight 的同节点读取者可以把旧版本写回 L2。
- 6.8 的结构性保证（“带版本的内容不持有令牌”）由 I-K 取代；带版本的内容也能持有刷新令牌，`VersionedCache` 支持提前刷新。
- 读到未知标志位或 `E` 的副本不设刷新点（P14）。

**逐个脚本的规则**（C8、P7、P18）：

| 脚本 | 不带段 | 版本段 | 摘要段 | 未知的必须理解的位 |
|------|--------|--------|--------|-------------------|
| `READ` | 照旧 | 照常授予刷新令牌；`E` 见上表 | 照常（权威缓存不申请 lease，§4.1） | 返回格式与头部，不授予令牌 |
| `FILL` | 令牌匹配即写入 | 令牌匹配 + 版本规则 | —（权威缓存没有 `fill`） | 拒绝 |
| `RELEASE` | 照旧（只触及字节 4–35） | 照旧；`E` 不 `DEL` | — | 不匹配 |
| `RECLAIM` | 现算载荷的摘要后条件回收 | 拒绝，或转为 `E(v)`（实施时定） | 现算载荷的摘要比较，不信任头部摘要 | 拒绝 |
| `PUT` / `PUT_ALL` / `INVALIDATE` | 整体写入或删除；权威缓存按 `digest` 设置写摘要段 | 同左 | 同左 | 同左（整体覆盖不依赖旧内容） |
| `PUT_IF_NEWER` / `EVICT_KEEPING_VERSION` | 当前版本为 `NO_VERSION` | 从版本段读版本 | 当前版本为 `NO_VERSION` | 拒绝 |
| `READ_IF_CHANGED` / `PUT_IF_MATCH` / `DELETE_IF_MATCH` | 在 Lua 中对载荷计算 SHA-1（`digest = ON_DEMAND`） | **拒绝**（带版本的内容不接受条件写入） | 先判断 `TYPE`，再以 `GETRANGE key 0 55` 只读头部比较 | 拒绝 |
| 集合脚本 | Hash 与 `!f` 不变 | — | — | — |

- **客户端先看格式号、再看状态**（P1、P18）。格式号 ≥ 2 的内容中出现未知状态时，报告为更高格式，不能报成格式 0。否则派生缓存会把它当作读不懂的内容而回收。
- **条件读取 O(1)**：只读头部是安全的。原地修改（授予刷新令牌、`release`）只触及字节 4–35，整体写入重写头部，所以头部中的摘要始终与载荷一致。
- **与现行规范**：architecture.md §3.3 改写为格式 2 与布局标记；§14 #9“线格式在一个大版本内冻结”保留，并加上 8.x 内两阶段演进的规则（I-L）。

### 4.8 配置（V10）

**分层**：

| 层（由低到高） | 作用 |
|------|------|
| 内置默认值 | 按类型与声明的数据来源（§4.1） |
| `cocache.defaults.cache.*` / `cocache.defaults.store.*` | 全局默认值，**低于**声明：只作用于声明中没有写出的字段 |
| 声明（注解或 spec DSL） | |
| `CacheSpecCustomizer` | 只能修改策略字段与不决定类型的字段（U16） |
| `cocache.overrides.cache.*` / `cocache.overrides.store.*` | 全局覆盖，**高于**声明。界限类字段（`ttl`、`local-max-age`、`degraded-local-max-age`、`absent-ttl`、`lease-wait`、`load-timeout`）取 `min(下层的值, override)`；模式类字段（`read-failure`、`invalidate-failure`、`fill-in-transaction`）直接设定（U10、A14） |
| `cocache.caches.[name].*` | 按缓存 |

- **字段三态**（U11）：未写出 / 永不过期或关闭（`forever`、`off`）/ 值。DSL 提供 `ttl = forever()`、`refreshWindow = off()`。用整体 `CachePolicy` 写的 spec 视为全部字段都已写出，启动日志提示“`defaults` 对此缓存无效”。
- **适用性**（U11）：
  - 全局层（`defaults`、`overrides`）按每个缓存的类型与数据来源，跳过不适用的字段，启动日志与端点列出被跳过的字段；按缓存的层出现不适用的键时启动失败。
  - 附一张“键 × 类型 × 数据来源”的适用性表。其中：`defaults.store.ttl` 与 `overrides.store.ttl` 都不允许；`absent-ttl` 适用于 `Store`；`unknown-read` 只能出现在声明与 `caches.[name]` 中。
- **校验连锁**（U10）：全局层引起的冲突（例如全局调低的 `local-max-age` 小于某个声明的 `refreshWindow`），夹紧依赖字段（缩短或关闭刷新窗口）并告警；只有按缓存的层引起的冲突才启动失败。
- **生效值与来源**：启动日志与 `GET /actuator/cocache/{name}` 输出每个字段的生效值，及其来源层（含“被 overrides 收紧”“被夹紧”“被锁定”）。
- **`key-prefix`** 不参与分层，仍是“配置优先于声明”。被 `lock` 锁定的除外。
- **`cocache.caches` 中的未知名称**：默认启动失败；`cocache.caches-validation: warn` 时只告警。

**单位**：

- 配置中的时长必须带单位（`500ms`、`5s`、`2m`、`PT2M`），不带单位的值启动失败，错误信息给出 6.x 的解释与等价写法。`refresh-window` 的“关闭”写作 `0s` 或 `off`。
- **注解中的时长仍按秒**（`Long`），文档写明“注解一律按秒”（U18）。A.L8 的不一致只在配置文件中，注解从来没有歧义；改成字符串会失去编译期检查，也换不来什么。

**库的装配入口与锁定**（M9、U2）：

- `CoCacheFactory.create(spec)` 与 `CoCacheFactory.create(type, spec)`（接口类型，取代 `CacheProxies.create`）：与 starter 同样分层，计入 `cocache.caches` 的名称校验，注册到端点与指标。
- `lock(...)` 可以锁定策略字段，也可以锁定存储布局：namespace、keyPrefix、keyEncoder、codec、keyFilter、ttlOf、数据来源。锁定了 keyPrefix 的 spec 不受 `key-prefix` 配置影响（CoSec 的撤销表若随应用的 `key-prefix` 改变存储 key，已撤销的 token 会重新可用）。
- `constrain(field) { … }` 在合并之后、校验之前执行约束，如 `constrain(ttl) { it ?: 1.minutes }`；`require(loader == null)` 一类的检查同样在此执行。CoSec 的 `expiringFailClosed` 与 `pinPolicySnapshot` 由它们表达。
- 全局层遇到锁定字段时**跳过**，并在启动日志与端点中标注“被 <库> 锁定”；只有 `cocache.caches.[name]` 明确指向锁定字段时才启动失败。
- 破窗开关：`cocache.caches.[name].unlock: [fields]`。启动时告警，并在端点中标注，供事故中有意识地覆盖。

**6.x 的键**（v4）：

- 不提供别名，也不做语义映射。6.x 的键在 v8 中出现时启动失败，错误信息给出 v8 的键与语义差异（与现有的 `RemovedProperties` 同一机制，键逐个列出；不能整个列出 `cocache.defaults`，它是新键的祖先）：
  - `cocache.defaults.<field>`：6.x 中它高于声明，语义对应 v8 的 `cocache.overrides.cache.<field>`；v8 的 `cocache.defaults.cache.<field>` 低于声明。错误信息说明两者的区别，由用户选择。
  - 不带单位的时长；`load-async-timeout` → `load-timeout`；`cocache.redis.set-cache-eviction-check` → `cocache.redis.store-eviction-check`；`foreign-format`（删除）；`read-failure` 出现在外部来源或权威缓存上 → `unknown-read`。
- 新增的键：`cocache.redis.assume-maxmemory-policy`（6.10.0 已有）、`management.endpoint.cocache.allow-store-delete`、`management.endpoint.cocache.allow-purge`、`fill-in-transaction`、`invalidate-fills-on-rollback`（§4.9）。

### 4.9 Spring（V11）

**写事务内的回源**（C2、M7、U3），两个相互独立的机制：

1. **回滚时失效事务内的填充**（`invalidate-fills-on-rollback`，默认开启）：
   - 在非只读的实际事务内、凭 lease 写进 L1 的 key，记入事务的同步（与 `TransactionalInvalidator` 的 `Pending` 同一机制）。
   - 事务回滚或提交结果未知时，在 `afterCompletion` 中失效这些 key；提交时什么也不做，因为读到的就是本事务提交的数据。
   - 代价只在回滚时出现，类级 `@Transactional` 的应用不受影响。它覆盖了“应用什么也没登记”的情形：未提交数据的残留从 `ttl` 收紧到事务时长。
   - core 为此提供一个只在未命中路径上调用的钩子（`FillObserver`），默认为空。
2. **私有回源**（`fill-in-transaction`）：

   | 取值 | 行为 |
   |------|------|
   | `registered-keys`（默认） | 当前线程处于非只读的实际事务中，且这个 key 已在本事务中登记失效（经 `TransactionalInvalidator` 或 starter 的事务装饰器）时，回源结果是**私有的**（`Private`）：不申请 lease、不写 L1、不写 L2、不经 SingleFlight 共享。登记时立即在本节点递增戳并淘汰 |
   | `private` | 写事务内的全部回源都是私有的。最严格，代价是写事务内的读取不经缓存 |
   | `allow` | 6.x 的行为（仍受机制 1 保护） |

   - `registered-keys` 不提供事务内的读己之写：登记之后，本节点其它线程照常从 L1 把已提交的旧值填回 L2，本事务下一次读取可能命中它。
   - 只读事务的快照陈旧不在覆盖范围内（与“loader 读到从库旧数据”同类）；非 Spring 管理的事务，闸门看不到。
   - 计入 `cocache.fills.gated`。

**starter 的事务装饰器**（U3）：starter 不再使用 Spring 的 `TransactionAwareCacheManagerProxy`，改用 CoCache 自己的事务感知装饰器：
- `evict` 登记到 `TransactionalInvalidator`：提交与回滚都失效，并进入 `registered-keys` 的门控；
- `put` 仍推迟到提交之后；
- `evictIfPresent` / `invalidate` 按 Spring 的契约立即执行。

这补上了 6.9.0 changelog 的“Not covered”。

**`TransactionalInvalidator`**：在 `afterCompletion` 中执行，提交与回滚都失效已登记的 key，迟到的登记立即失效，逐缓存执行、失败聚合（6.9.0 已实现，#697）。参数类型只接受 `Cache`（不含 `VersionedCache`）。

**Spring Cache**：

- **`Unknown` 的映射**（U5）：`CoCacheSpringCache` 的 `get` 与 `retrieve` 一致处理。`Unknown` 以 `CacheReadException` 失败，交给 `CacheErrorHandler`（Spring 7 对失败的 `retrieve` future 与响应式路径同样调用 `handleCacheGetError`）；`unknownRead = ABSENT` 时视为未命中。6.9 的 `retrieve` 用 `as? Lookup.Present`，在 v8 中会把 `Unknown` 静默当作未命中。同步、异步、响应式三条路径各加用例。
- `SpringCacheSyncCheck` 对指向 `Store`、`SetStore`、`VersionedCache` 的缓存操作启动失败。
- `@CacheEvict(allEntries = true)` 指向 CoCache 缓存时，v8 保持 `clearLocal` 并告警（U19）。等 Q12 有了全集群重置，再改为默认启动失败（可配置）。
- 响应式与协程事务：见 Q5。
- `keyExpression` 改用编译模式的 SpEL（待 JMH）。

**运维入口**（M13、U19）：

- actuator 端点 `cocache` 默认只读，写操作经 `management.endpoint.cocache.access` 开放；`Store` / `SetStore` 的删除需要 `allow-store-delete`，`VersionedCache` 的 `purge` 需要 `allow-purge`，按 key 删除对 `VersionedCache` 映射到 `evict`。运维文档的示例改为 `curl -fsS`。
- 新增只广播失效帧的操作（对所有类型都安全）。
- 未知缓存名的 `DELETE` 返回 404（6.10.0 已实现，#707）。
- `ExternalInvalidator` 增加只广播的方法；文档写明它的 `invalidate` 作用于权威缓存的 namespace 时会删除唯一副本。
- “引擎先淘汰本地副本，再通知外部监听者”成为 `InvalidationChannel` 的契约（CoSec 依赖它）。

### 4.10 检验（V9）

- **Lincheck**：对“flight 层 + L2 + 失效戳 + 内存存储”做模型检验。Lincheck 是新的测试依赖，须先经维护者批准（Q18）。
  - 操作：`get`、`get(key, loader)`（与 `get` 交替，P3）、`getAll`（合并到 `Private` 的单 key flight，P10）、`invalidate`、`put`、`onInvalidated`、`onReset`、`clearLocal`。
  - 顺序规格：“带版本的数据源 + 读己之写”与“带版本的写入方 + 读快照的 loader”。
- **TLA+**：`docs/spec/CoCache.tla` 建模以下内容：
  - N 个节点；一个 key 的 L1 状态机（`ABSENT` / `LEASED` / `VALUE` / `MISSING` / `E`，令牌，版本）。
  - 每个节点的 L2 与失效戳，绕过 SingleFlight 的读取者（P11）。
  - 可丢失、乱序、重复的失效通道，写入方节点对自身帧的过滤。
  - 带提交历史的数据源。
  - 失败与重放：超时与中断后仍执行的命令；连接替换（被排空的命令可能晚于新连接上的命令执行，P8、P9、P12）；版本拒绝之后的 L2 处理（P2）；持有令牌崩溃。

  性质：G2 的 L1 与 L2 条款、按类型陈述的版本单调（I-K）、令牌最终释放。CI 中以小规模运行 TLC。
- **带种子的多节点故障仿真**：多个运行时共享一个注入故障的内存后端（`HookedBackend`），以 `ManualClock` 推进时间，由带种子的随机调度注入事件丢失与乱序、超时与中断后仍执行的命令、leader 被中断、持有令牌崩溃，以数据源的提交历史为裁判检查 G1 的上界与 G2。失败时以种子复现；与 v3 基于状态机的确定性仿真相比，不能逐步重放（Q17）。
- **TCK 只有一套**：`BackendProtocolSpec`、`EngineSpec`、`SetEngineSpec`、`MultiNodeSpec`、`FaultSpec` 改为 `L1Store` 的形状，删除 `Async*` 规范。异步视图的语义（L2 命中不提交任务、完成的线程、取消不传播、提交被拒绝）由引擎级用例覆盖。手写交错继续作为回归用例。

### 4.11 文档（V12）

- architecture.md 只写现行行为，版本叙述移到 changelog 或 RFC；顺带修正 §6.3 关于启动检查的说法（A.L10）。
- 不变量以稳定编号 `I-n` 列出，代码注释与测试引用编号。
- 审查与对抗验证的记录放在 RFC 的附录与 §12 中。

## 5. 删除清单（v8）

- `Cache`、`AsyncCache` 与挂起视图上默认抛出的方法；`SetCache`、`AsyncSetCache`、`SetCacheSpec`（由 `SetStore` 一族取代）。
- 把没有数据源的 `Cache` 当作权威副本的用法（移到 `Store`）；没有声明数据来源的 `Cache`（必须声明 `externallySourced()`）。
- `LocalEntry.unresolved` 改名为 `unfilled`，语义不变（P3）；`readFailure` 对外部来源与权威缓存的含义（由 `unknownRead` 取代）。
- 带版本的类型上的 `put`、`putAll`、普通 `invalidate`、`putIfMatch`；`VersionedCache` 使用没有版本的数据源。
- `CoherenceBackend.versionedWrites`；异步 SPI（`AsyncRemoteStore`、`AsyncInvalidationChannel`）与 `BlockingAsyncStore` / `BlockingAsyncChannel`；抛出的默认方法与非原子的默认实现。`RemoteStore` 改名为 `L1Store`。
- 引擎中的异步分支（`CoherentEngine` / `SetEngine` 的 `*Async` 路径）、`Futures` 的续接与就地执行、定时器调度的 lease 等待与许可等待队列、`cocache-timer`；`asyncExecutor` 与 `blockingExecutor` 合并为一个异步执行器。
- `Async*` 平行 TCK 规范；`supportsConditionalWrites` 一类能力开关。
- 权威缓存上的 `InvalidateFailureMode.RETRY`；`LoadPolicy.asyncTimeout`（并入 `load.timeout`）。
- `cocache.defaults.*` 的旧语义与旧键；配置中不带单位的时长；`setPolicyFor` / `versionedPolicyFor`；starter 对 `TransactionAwareCacheManagerProxy` 的使用。
- 格式 1 的读写与 `VERSION_MARK`；`foreign-format`（`ForeignFormatMode`）；`CoCache5Migrator`。
- 6.x 的 key、频道与探测布局（V13）。

## 6. 不变量（v8 实施时与 architecture.md §14 的对照）

**新增**：

- **I-A 类型与声明决定默认值**：`Cache` / `VersionedCache` 是派生副本，`Store` / `VersionedStore` / `SetStore` 是权威副本；派生缓存声明数据来源。默认值与可用的操作由类型与声明决定；同一类型内，这次读取有没有数据源（含调用级 loader）只影响未命中与故障时能否回源。
- **I-B `Unknown` 只表示故障**：它不写入任何一层、不广播，也不作为带数据源的调用方的结果；`get` / `getAll` 遇到它抛出，`unknownRead = ABSENT` 的映射发生在调用方返回之前。
- **I-C L2 填充**：校验失效戳与写入 L2 在同一个 key 锁内完成，写后按身份复核；写入方与远端事件处理完成之后，读取开始于它们之前的副本不可见；重置之后，这样的副本至多存在到填充方自己的复核。
- **I-D 延迟的无条件写入只用于派生副本**：有数据源的缓存，以及外部来源且失效失败模式为 `RETRY` 的缓存；权威副本的删除与写入同步完成或抛出。
- **I-E 结果未知**：按客户端能观察到的事实判定（超时、断线排空、中断都是结果未知）；确定未执行的写入不会再执行；结果未知的写入在任何时刻都可能生效，它的安全只来自顺序，而摘要条件有 ABA。
- **I-F 令牌守恒**：取得令牌的每一步（`Read`、`ReadAll`、`Reclaim`、刷新的 `Read`、`Fill`、`FillAll`），在每个失败转移上、在超时与中断时，都有释放；每个 flight 在有限时间内完成或被注销。
- **I-G 刷新有界**：每个节点、每个 key 的刷新触发率有一个与命中率无关的上界；带未知标志位或 `E` 的副本不设刷新点。
- **I-H 等待有界**：跟随者、lease 等待、许可等待都有上界；只有跟随者的等待响应中断，且只影响它自己；执行协议期间不响应中断。
- **I-I 一份协议**：每个协议分支只有一份（同步）实现；异步视图与挂起视图在其上提供，不复制分支。
- **I-J 事务内的回源**（启用 Spring 集成时）：写事务内凭 lease 写进 L1 的 key，在回滚或结果未知时失效；已登记失效的 key 的回源结果只返回给本次调用。
- **I-K 版本单调（按类型）**：经 `VersionedCache` / `VersionedStore` 的写入与回源，不让 L1 的版本后退、不抹掉版本；版本拒绝的回源不写 L2。抹掉版本的入口只有：`purge`、到期、同一 namespace 上的其它类型（声明错误）、外部工具；条件脚本拒绝带版本的内容。
- **I-L 格式演进**：未知的格式版本、未知的必须理解的标志位、格式号 ≥ 2 中的未知状态，既不回收也不写回；新的必须理解的位，只能在一个 namespace 的全部节点都认识之后写入（8.x 内两阶段发布）。

**与 architecture.md §14 的对照**：

| §14 | 处理 | 说明 |
|-----|------|------|
| 1 栅栏规则 | 修改 | L2 的部分改为 I-C；“写入方从不写 L2”保留；版本前进的 `fill` 在本节点按写入处理（§4.7） |
| 2 L1 未命中就是未命中 | 修改 | 补上：外部来源的“未填充”不是负缓存；权威缓存的 L1 未命中是 `Absent`；`E` 读作未命中 |
| 3 读路径从不无条件删除 | 修改 | 按类型与声明（I-A）；加上 I-L |
| 4 能原子广播就原子广播 | 修改 | 补上：版本前进的 `fill` 广播；非原子广播下的四种重放也广播（§4.6） |
| 5 SingleFlight | 修改 | 合并规则逐 key 应用（I-B、`Private`）；flight 层共用（I-I）；超时注销（I-F） |
| 6 loader 的线程 | 修改 | 同步调用在调用方线程上执行 loader；异步视图在虚拟线程上执行同一个调用 |
| 7 令牌必须释放或过期 | 取代 | 由 I-F 取代 |
| 8 每次 L1 状态转换显式重设到期时间 | 保留 | `E` 的到期时间由引擎传入 |
| 9 线格式冻结 | 修改 | 格式 2 是唯一格式；8.x 内两阶段演进；与 6.x 以布局标记隔离（V13）；摘要算法不变；I-L |
| 10 集合元素增删 | 修改 | `SetCache` 改为 `SetStore` |
| 11 有状态组件只由运行时创建 | 保留 | 补上 `CoCacheFactory` |
| 12 L1 连接只访问主节点、断线拒绝 | 保留 | |
| 13 存储只执行协议 | 保留 | |
| 14 代理不包装异常 | 保留 | 受检异常的 JDK 限制写入文档（Q7） |
| 15 命中路径的改动必须附带数据 | 保留 | |
| 16 AOT | 保留 | 新的接口与目标类型登记提示 |
| 17 取消不传播 | 保留 | 取消返回的 future 不中断执行它的线程 |
| 18 异步视图不挂起线程、不同步抛出 | 修改 | L2 命中不挂起任何线程；未命中只挂起虚拟线程（默认执行器）；不同步抛出 |
| 19 条件读写只确认 L1 | 修改 | 移到 `Store`；加入 `deleteIfMatch`；摘要相等只证明已写入；带版本的内容拒绝 |
| 20 按版本有序的写入 | 重写 | 由 I-K 与 `E` 取代 |
| 21 副本出生时不在刷新窗口内（#698） | 保留，并入 I-G | |

## 7. 性能预算与验证

| 路径 | 预算 | 验证 |
|------|------|------|
| L2 命中 | 不退化（1 / 8 线程，含 `l2HitMixed`）；异步视图的 L2 命中在调用方线程上完成，不提交任务；`unfilled` 标志的检查与 6.9 相同 | `scripts/jmh-compare.sh -b l2Hit,l2HitViaInterface,l2HitMixed,l2HitAsync -t 1,8` |
| L1 命中、未命中回源（同步） | 重构后的退化 ≤ 3% | `l1HitViaEngine`、`missLoad` |
| 异步视图的 L1 命中、未命中 | 8 线程闭环吞吐不低于 6.10 异步视图的 90%（RFC 0004 §11 的口径） | `l1HitViaEngineAsync`、`missLoadAsync` |
| 虚拟线程 | 10 000 个并发的异步未命中只占用默认的载体线程，不随并发数增加平台线程 | 新用例 |
| L2 填充（`compute` + 条件复核） | 未命中路径的退化 ≤ 2% | `missLoad`、`l1Read` |
| 权威缓存的 `Absent` 与外部来源的“未填充” | 进入 L2，命中成本与普通 L2 命中相同 | 新基准 `l2HitAbsent` |
| 条件读取（摘要未变） | Redis 端成本与载荷大小无关 | 新基准 `conditionalUnchanged`（1 KB / 1 MB / 8 MB） |
| 内存 | 派生缓存每个 key +0 B；权威标量 +20 B（`digest = ON_DEMAND` 时 +0 B）；带版本 +8 B；`E` 为 44 B | `MEMORY USAGE` 对照 |

## 8. 切换与破坏性变更

**切换**（V13）：

- 8.x 与 6.x 的 key、频道与探测都不重叠，可以同时部署在同一个 Redis 上；共存期间 Redis 的内存约为两份。
- 一个 namespace 的全部读写方作为一组切换：
  - **派生缓存**：8.x 启动时 L1 为空，回源填充；6.x 的 key 由 TTL 回收，`ttl` 永不过期的由运维按前缀删除。
  - **权威缓存**（`Store` 一族）：没有迁移工具。应用在 8.x 上重建数据（从它的权威来源重新写入，或由写入方在切换前双写两套 key），再把读者切过去。
  - **跨服务共享的 namespace**（Wow 的状态视图、CoSec 的快照与撤销表）：所有者与读者在同一个发布窗口切换，或所有者先双写。
- 没有桥接版本、别名与降级工具。回退到 6.x 就是切回 6.x 的 key；6.x 不读 8.x 写入的内容。

**没有数据源的 `Cache` 在各版本上的行为**：

| 没有数据源的 `Cache` | 6.8 | 6.9 / 6.10 | v8：`externallySourced()` 的 `Cache` | v8：`Store` |
|------|-----|-----------|-----------------------------|------------|
| L1 读失败 | 返回 `null` 并缓存（A.D1） | 抛出；显式 `read-failure: degrade` 时返回 `null`、不缓存 | 抛出；`unknownRead = ABSENT` 时返回 `Absent`、不缓存 | 同左 |
| `invalidate` / `delete` 失败 | RETRY | THROW（显式 RETRY 照常生效） | **RETRY**（显式声明触发，§4.1） | 抛出，从不重试 |
| 读不懂的内容 | FAIL | FAIL | FAIL | FAIL |
| L1 未命中 | 未决的不存在，进入 L2 | 同左 | “未填充”，进入 L2 | `Absent`，进入 L2 |
| 默认 `ttl` | 1 h | 1 h | 1 h | 永不过期 |

**类型对照**（升级指南的素材）：

| 6.x 的用法 | v8 |
|------------|----|
| 有数据源的 `Cache` | `Cache`（不变） |
| 没有数据源、可以按需重建（Spring Cache、调用级 loader、调用方 `put`） | `Cache` + `externallySourced()`（接口式声明：`@CoCache(source = EXTERNAL)`） |
| 没有数据源、无法按需重建 | `Store`；`invalidate` → `delete`；故障时放行的写明 `unknownRead = ABSENT` |
| `CacheSpec.versioned` / `versionedLoader` | 有带版本的数据源：`VersionedCache`；没有：`VersionedStore`；运维删除 → `evict` / `purge` |
| `SetCache` / `SetCacheSpec` | `SetStore` / `SetStoreSpec` |
| `JoinCache` | 只读用法：`JoinView`；用到 `invalidate`：`JoinCache` |
| 第三方后端 | 实现 `L1Store`（阻塞形状）与能力访问器，通过新的 TCK |
| 库在 starter 之外装配缓存 | `CoCacheFactory`；约束与锁定用 `lock` / `constrain` |

**其余破坏性变更**（M15）：

- **基线**：JDK 21。
- **异步视图**：未命中在虚拟线程上执行；`cocache.async-executor` 与 `cocache.blocking-executor` 合并为一个异步执行器（默认每个任务一个虚拟线程）；`cocache-timer` 删除。
- **指标**：新增 `cocache.reads.unknown`、`cocache.fills.gated`、`cocache.refresh.skipped`；`cocache.load.timeouts` 包含同步跟随者的超时。端点的报告带上类型与能力。
- **`lookup`**：`readFailure = THROW`（含 6.9 没有数据源时的默认值）时，`lookup` 从抛出 `CacheReadException` 变为返回 `Unknown`。依赖它抛出来 fail closed、又用 `as?` / `else` 处理结果的调用方，会从拒绝变成放行（U5）。升级 skill 的审计脚本检查这类写法；JDK 21 的模式匹配 `switch` 能发现 `Unknown`。
- **`SetStore` 的读失败**：从 6.x 的原始异常变为 `CacheReadException(cause)`，`catch (e: RedisException)` 不再命中。
- **ACL**：频道模式改为 `&cocache:v8:inv:*`（`pubsub` 模式另加 `&cocache:v8:probe:*`）。
- **AOT**：`CoCacheRuntimeHints` 登记新的接口（`Store`、`VersionedCache`、`VersionedStore`、`SetStore`、`JoinView` 与各自的异步视图）；`CacheBeanDefinitions` 的目标类型随之改变；`CoCacheAotTest` 与 `CoCacheAotRuntimeTest` 同步更新。
- **`cocache-coroutines`**：挂起视图有同构的层次；`suspendingVersionedLoader` 保留；下游公开的工厂签名（如 Wow 的 `StateViewCaches.CacheFactory`）随之改变。
- **`CacheSpecCustomizer`**：为 5 种 spec 各提供一个重载，改为 overlay 语义（只设置写出的字段），不能修改类型与存储布局（§4.8）。
- **`load.timeout`**：同步跟随者从无限等待变为 30 s 后失败；注册超过它的 flight 被注销。
- **提前刷新**：`VersionedCache` 支持提前刷新，全局的 `refresh-window` 开始作用于它；不需要时在声明中写 `refreshWindow = off()`。
- **`skills/`**：新增升级 skill 与静态审计脚本，检查以下各项：
  - 没有数据源的 `Cache`；
  - 条件与版本方法的调用；
  - 配置中不带单位的时长；
  - `cocache.defaults.*`；
  - `Lookup` 的 `as?` / `else` 匹配；
  - `CacheAssembler` / `policyFor` / `CacheProxies`；
  - 运维脚本中的 `DELETE` 与 ACL 中的频道模式。
- 破坏性变更表写入 `wiki/guide/changelog.md`（en + zh）。

**下游**（第二轮读了两个下游的源码）：

- **CoSec**：
  - **分型**：策略快照、控制面 8 个缓存、两个撤销表（`unknownRead = ABSENT`）、三个影子缓存（带显式 `ttl`）→ `Store`；`MembershipCache` → `Cache` + `externallySourced()`；迁移工具中的 `GlobalPolicyIndexCache` → `SetStore`。
  - **装配**：`CoSecCacheAssembler` → `CoCacheFactory`；`pinnedPolicy` → `lock` + `constrain`，锁定 namespace、keyPrefix、keyEncoder、codec 与“拒绝 loader”。
  - **读取**：`RedisSnapshotDocumentStore` 的 `when (lookup)` 因 `Unknown` 编译失败，可以发现；频道订阅改为新 SPI；“先淘汰本地副本，再通知外部监听者”成为契约。
  - **摘要**：不受影响（`Digest.of`、`Envelope.Scalar` 保留，算法仍是 SHA-1）。
  - **切换**：控制面在 8.x 的 key 上重新发布策略快照；撤销表在切换窗口内双写，或按安全要求接受窗口。
  - **部署**：托管或代理型 Redis 上，确认 `INFO memory` 可读，或配置 `assume-maxmemory-policy`；ACL 的频道模式。
- **Wow**：
  - `cacheSpec { suspendingVersionedLoader(…) }` → `versionedCacheSpec`；`StarterCaches` → `CoCacheFactory`。
  - `StateViewCaches.CacheFactory` 改签名，Wow 需要大版本。
  - 全局的 `refresh-window` 开始作用于状态视图，要么在声明中写 `refreshWindow = off()`，要么接受经 HTTP 回源到所有者。
  - 运维手册中的删除改用 `evict`。
  - **切换**：所有者与全部读者服务在同一个发布窗口切换，或所有者先双写。

## 9. 6.x 的收尾

6.x 中不破坏兼容的修复已经发布或待发布。6.10.0 是最后一个功能版本，之后在 `6.x` 维护分支上只修缺陷。

| 项 | 来源 | 版本 |
|----|------|------|
| 没有数据源的 L1 读失败 fail closed；`invalidate` 重试不越过之后的写入；非原子广播下的重放广播；显式 `read-failure: degrade` 对没有数据源的读取生效 | A.D1、A.D2、A.R4、M3 | 6.9.0（#700） |
| 超时取消 Lettuce 命令；`sharded` 未确认频道每次心跳重置；读超时释放 lease；订阅生命周期竞态；格式字节按无符号解析 | A.R1、A.R2、A.L1、A.R3、A.L7 | 6.9.0（#701） |
| `TransactionalInvalidator` 回滚失效、迟到登记、逐缓存失败隔离 | A.T1、A.T2 | 6.9.0（#697） |
| 提前刷新退避；寿命 ≤ 刷新窗口不设刷新点 | A.D3 | 6.9.0（#698） |
| 中断时取消命令、释放令牌；`fill` / `fillAll` 超时或中断时释放令牌；`reclaim` 超时释放 lease | P5、P13、C7（附录 B） | 6.9.1（#704） |
| 驱逐策略检查改读 `INFO memory`、按声明检查；未知缓存名的端点 `DELETE` 返回 404；修正 architecture.md §6.3 | M11、M13、A.L10 | 6.10.0（#707，待合并） |
| 一个失效监听器抛出 `Error` 不影响其余监听器；`Subscription.close()` 的契约 | CoSec 反馈 | 6.10.0（#708，待合并） |
| 注册失败时释放 namespace；单调时钟；同步跟随者可中断；接口式代理与受检异常的文档 | A.L9、A.C1、A.S1、A.P1 | 6.10.0（#711，待合并） |

**移入 v8，6.x 不再做**：

| 项 | 来源 | v8 中的位置 |
|----|------|------------|
| L2 填充用 `compute` + 条件复核 | A.D4、C4 | §4.5、I-C |
| L2 key 不含前缀 | §4.5 | §4.5（待 JMH） |
| 同步 `SetStore.getAll` 批量化 | A.L2 | §4.4 的批量路径 |
| namespace 前缀重叠 | A.L4 | `runtime.create` 的布局校验 |
| Lincheck、TLA+ | V9 | §4.10 |
| starter 的事务装饰器 | U3 | §4.9 |

原定 7.x / 7.B 的其余项（sans-IO 重构、新旧 SPI 并存、读格式 2、配置别名、前向兼容声明、迁移报告）删除或并入 v8 的里程碑（§10）。

## 10. 实施顺序

0. **6.10.0**：合并 #707、#708、#709、#711 后发布；从发布点切出 `6.x` 维护分支。`main` 改为 8.0.0-SNAPSHOT，基线 JDK 21。
1. **M1 异步视图改在虚拟线程上**：异步视图改为“L2 命中直接返回 + 未命中提交同步调用”。现有的 `Async*` TCK 全绿作为等价性裁判，JMH 对比 6.10.0（§7）。然后删除引擎中的异步分支、`Futures` 的续接、定时器与许可等待队列。用户 API 与 SPI 此时不变。
2. **M2 flight 层与协议**：合并规则逐 key、`load.timeout`、中断语义、L2 `compute` 填充（I-C）、令牌在 `finally` 中守恒；性质测试与带种子的故障仿真接入。
3. **M3 SPI**：`L1Store`（阻塞）、能力访问器、`ForwardingL1Store`、TCK fail closed；删除异步 SPI 与 `Async*` 规范。
4. **M4 线格式 2 与布局**：布局标记、头部的段、状态 `E`、逐脚本规则。第三轮对抗验证在这一步合并之前完成；合并即冻结 8.x 的格式（§4.7 的定稿项）。
5. **M5 用户 API**：类型（V1–V3）、`Lookup.Unknown`、`CoCacheFactory`、挂起视图。
6. **M6 配置**（V10）与 **M7 Spring**（V11）。
7. **M8 检验与文档**：Lincheck（经批准后）、TLA+、architecture.md 重排（V12）、wiki 与升级指南、skills。
8. **8.0.0** 发布；8.x 按 §4.2 的演进规则新增能力，新的必须理解的段两阶段发布。

每个里程碑由若干 PR 组成，每个 PR 的 `./gradlew check` 全绿；触及命中路径或异步路径的 PR 附 `scripts/jmh-compare.sh` 的对比。

## 11. 风险与未决问题

- **Q1 范围放在 v8 还是 7.0**：维护者指定 v8，并跳过 v7（v4 决定）。
- **Q2 命名**：用户 API 的 `Store` 与 SPI 的 `RemoteStore` 容易混淆。SPI 改名为 `L1Store`（v4 默认采用）。
- **Q3 基线升到 JDK 21**：v4 决定升到 JDK 21，并以此简化 SPI 与异步视图（V4、V5）。生产建议 JDK 24 及以上（JEP 491，§4.4）。
- **Q4 `fill-in-transaction` 的默认值**：`registered-keys` + 默认开启的回滚失效（§4.9）。判定依据：默认值必须覆盖“应用什么也没做”时的残留（回滚），登记只用来进一步缩小窗口。第三轮可以结合下游的事务形态再复核。
- **Q5 响应式事务**：需要 `cocache-spring` 可选依赖 reactor，须先经维护者批准。
- **Q6 摘要的抗碰撞**：保留 SHA-1（v4 默认采用）。原因是 Redis 的 Lua 只有 `redis.sha1hex`，`RECLAIM` 与 `digest = ON_DEMAND` 要在脚本中计算摘要（§4.7）。若要更换，需要放弃脚本中的计算（`ON_DEMAND` 删除，`RECLAIM` 改为比较完整内容），并让 `Digest` 携带算法。
- **Q7 代理与受检异常**：JDK 动态代理的限制；构建期生成实现留到 8.x 评估。
- **Q8 派生 `Cache` 是否保留 `put`**：保留。
- **Q9 `Store` 的 L2**：是否默认更短的 `localMaxAge`，待用户反馈。
- **Q10 修订号或写入标识**：摘要比较在迟到执行下有 ABA（C5、P4）。它占用的头部位置（必须理解的段，或扩展区）**必须在 8.0 冻结格式之前决定**（P6，§10 的 M4）；倾向放进扩展区，能力本身按 §4.2 在 8.x 新增。
- **Q11 重置的严格线性化**：用读写锁把填充与“递增 + `clear`”分开，代价是未命中路径多一次 CAS。
- **Q12 全集群清空**：`@CacheEvict(allEntries = true)` 的全集群语义需要“按 namespace 重置”的帧，这是帧格式的变更。
- **Q13**（v2）：已关闭，由状态 `E` 取代（§4.7）。
- **Q14 类型元数据**：把每个 namespace 的类型写入 `cocache:v8:meta:{namespace}`，启动时比对跨应用的声明是否一致。这是线格式的新增；v8 没有兼容负担，可以在 8.0 加入，须维护者决定（M4 之前）。
- **Q15 `unknownRead = ABSENT` 的短暂缓存**：6.7 的放行把降级结果在 L2 中缓存 10 s，6.9 与 v8 不缓存。Redis 卡顿时，每次读取等满 `command-timeout`（默认 2 s），CoSec 的撤销检查会给每个请求加 2 s。是否在 `Store` 上以极短的限龄（如 1 s）缓存降级结果，待评估。`Store` 没有 loader 会被它挡住，A.D1 的前提不成立。
- ~~Q16 7.B 的前向兼容声明~~：v4 删除（没有 7.B）。
- **Q17 是否保留 sans-IO 状态机**：v4 以一份同步实现取代它（§4.4）。保留它的唯一理由是确定性的多节点仿真（逐步重放）；代价是每条路径都要指令化。默认不保留。
- **Q18 Lincheck 作为测试依赖**：须维护者批准（§4.10）。
- **Q19 布局标记的拼写**：`v8:` 是占位，实施时定（§4.7）。

## 12. 对抗验证记录

本节的编号（C、M、P、U）与附录 A、B 的编号相互独立；正文引用附录时一律带前缀（如 A.D1、B.1）。

### v1 → v2：第一轮

v1 由两路独立的只读会话验证：
- 一致性：对照 6.8 代码、#697 / #700 / #701 的 diff、Caffeine 3.2.4 与 JDK `ConcurrentHashMap` 源码。
- 迁移运维：对照本仓库代码与文档、下游 CoSec 与 Wow 的源码。

作者复核了其中的关键论断：
- `FILL` 只比较令牌；
- Caffeine 的 `clear()` 按快照删除；
- `Digest` 以 SHA-1 计算，且 KDoc 写明属于线格式；
- #700 让没有数据源的读取一律抛出；
- CoSec 的撤销读取依赖故障时返回 `null`。

28 条发现全部成立，全部处理。

**一致性**：

| ID | 严重度 | 发现 | 处理 |
|----|--------|------|------|
| C1 | 高 | 删除 `unresolved` 之后，`Unknown` 可以经 SingleFlight 交给带 loader 的调用方 | §4.1 合并规则、§4.4 flight 层、I-B |
| C2 | 中高 | `FillGate` 只挡住 L1，未提交数据仍进 L2、经 SingleFlight 共享 | §4.9 私有结果、I-J |
| C3 | 中高 | 带版本的内容持有刷新令牌之后，`fill` 可以让版本后退或抹掉版本 | §4.7 版本规则、§4.2、I-K |
| C4 | 中 | Caffeine 的 `clear()` 看不到进行中的 `compute` | §4.5 条件复核、I-C、Q11 |
| C5 | 中 | 已写出的命令可以排在更晚的写入之后执行 | A12、§4.6、I-E、Q10 |
| C6 | 中 | 带版本的类型上仍能调用丢失版本的写入 | A7、§3.1、§4.2 |
| C7 | 中 | 驱动放弃状态机时没有接口释放令牌；`reclaim` 超时不释放 | §4.4 `abandon`、§4.6、I-F、§9 |
| C8 | 中 | 两种格式共存时没有逐个脚本规定 | §4.7 脚本矩阵、§4.10 |
| C9 | 中低 | `fillLocal` 不是指令；批量的戳；`resume` 不带时间；读己之写重试留在驱动里 | §4.4 |
| C10 | 中低 | 没有规定读者遇到未知标志位怎么办 | §4.7、I-L |
| C11 | 中低 | `Store` 的读取是否申请 lease 没有规定 | §4.1、§9 |
| C12 | 低 | leader 被中断时，中断引起的失败传给跟随者 | §4.4、§4.6、I-H |
| C13 | 低 | “其余不变量保留”与 §14 的 6 条冲突 | §6 对照表 |

**迁移与运维**：

| ID | 严重度 | 发现 | 处理 |
|----|--------|------|------|
| M1 | 高 | 按“调用了哪些方法、有没有 loader”分型不可靠 | V1、§4.1、§8、I-A |
| M2 | 高 | `Unknown` 混淆了正常未命中与故障 | V2、§4.1、§7 |
| M3 | 高 | 迁移基线应含 #700；没有数据源的语义来回翻转；#700 让 CoSec 的故障放行无法表达 | §8、§9（#700 的补充已交付）。v3 对外部来源的 `invalidate` 默认值做了一次显式翻转（U4） |
| M4 | 高 | 换摘要算法会破坏 `Digest` 的使用者 | §4.7 摘要段存 SHA-1 |
| M5 | 高 | “声明覆盖全局默认值”让运维失去全局手段 | §4.8 `overrides`（v3 改为取上限，U10） |
| M6 | 高 | 配置键迁移会被静默忽略；滚动升级；`key-prefix` | §4.8 迁移机制（v3 修正了别名的语义，U1） |
| M7 | 中高 | 对全部写事务拦截 L1，L1 永远填不满 | §4.9 |
| M8 | 中高 | `is` 检测与装饰器不相容；TCK fail open | §4.3（v3 改为抽象访问器 + 转发基类，U15） |
| M9 | 中 | 库的装配入口在兼容承诺之外 | §4.8 `CoCacheFactory`（v3 扩展了 `lock`，U2） |
| M10 | 中 | 默认值随类型变化；回退；墓碑寿命 | §4.7 回退、§4.1 `tombstoneTtl`、§8 |
| M11 | 中 | 驱逐策略检查在托管 Redis 上静默跳过 | §4.1、§9 |
| M12 | 中 | `VersionedCache` 的删除撤掉版本栅栏 | 同 C6；§4.9 |
| M13 | 中 | 端点只读与运维手册；只广播的失效；`ExternalInvalidator` | §4.9 运维入口 |
| M14 | 中低 | `JoinCache` 与 `ReadableCache`；注解调用时才失败；`allEntries` | §3.1、§4.9、Q12 |
| M15 | 中低 | §8 的遗漏 | §8 其余变化 |

### v2 → v3：第二轮

v2 由两路独立的只读会话验证。
- 协议与格式：对照 6.9.0 代码、Caffeine 3.2.4 与 Lettuce 7.5.2 源码、JDK 17 语义。
- 语义与迁移：对照 6.9.0 代码与文档、spring-context 7.0.9 字节码，以及下游 CoSec（`1f4e80ea`）与 Wow（`33778a5022`）的源码。

作者复核了其中的关键论断：
- `RELEASE` 遇到 `L` 即 `DEL`；
- `RedisConnections.await` 的中断分支不取消命令，`releaseAfterTimeout` 只在超时时释放；
- `fillRemote` 失败不释放令牌；
- `CoCacheSpringCache.retrieve` 使用 `as? Lookup.Present`；
- starter 使用 `TransactionAwareCacheManagerProxy`；
- CoSec 的 `expiringFailClosed`（`ttl ?: 1m`）与 `CacheProxies.create`。

38 条发现全部成立，全部处理。其中两条是 6.9.0 中仍存在的缺陷（附录 B）。

**协议与格式**：

| ID | 严重度 | 发现 | 处理 |
|----|--------|------|------|
| P1 | 高 | Q13 的候选编码（格式 2 的 `L`）不可行：`RELEASE` 删除栅栏，持有者崩溃锁死 key | §4.7 新状态 `E` 与逐脚本规则；Q13 关闭 |
| P2 | 中 | 版本拒绝之后以更旧的回源值降级填充 L2 | §4.7 版本拒绝的处理；§4.4 性质测试 |
| P3 | 中 | 删除 `unresolved` 与“未填充进入 L2”矛盾；`unknownRead` 的共享结果 | §4.4 保留 `unfilled` 标志；§4.1 映射在调用方返回之前；§5 |
| P4 | 中 | `deleteIfMatch` 的 ABA 会删除重新写入的相同内容 | §4.1、§4.6、Q10 |
| P5 | 中 | 中断路径绕过了 #701（6.9 仍存在） | 附录 B.1、§4.4、§4.6、I-E、I-H、§9 |
| P6 | 中 | “未知即更高” + 回退下限冻结 8.x 的格式 | §4.7 扩展区与定稿时点、I-L、Q10 |
| P7 | 中低 | I-K 与矩阵矛盾 | I-K 按类型陈述；条件脚本拒绝带版本的内容 |
| P8 | 中低 | “确定未执行 / 结果未知”按原文无法区分 | §4.3 契约、§4.6、I-E |
| P9 | 中低 | 补偿失效作用于外部来源；重连后的上界 | §4.6、I-D |
| P10 | 中低 | 批量路径的合并规则；`Private` 持有 lease | §4.1 逐 key、§4.4 `Private` 不申请 lease |
| P11 | 低 | 版本前进的 `fill` 的自身帧被过滤 | §4.7 本节点按写入处理（`EvictLocal`） |
| P12 | 低 | `3 × commandTimeout` 是启发式窗口 | §4.6 以同一连接上之后的命令完成为界 |
| P13 | 低 | `fill` 失败不释放令牌（6.9 仍存在） | 附录 B.2、I-F、§9 |
| P14 | 低 | `VERSION_MARK` 内容与提前刷新 | §4.7、I-G：不设刷新点 |
| P15 | 低 | `abandon` 只返回释放 | §4.4 `abandon(cause, undelivered)` |
| P16 | 低 | 卡住的同步 loader 让 flight 永不结束 | §4.4 超过 `load.timeout` 注销 |
| P17 | 低 | 新写入的非原子重放 | §4.6 |
| P18 | 低 | 规范细节（§14 #21、退避措辞、`RECLAIM`、令牌、解析顺序、`TYPE`、偏移、`VersionedStore` 的段、身份比较、`evict` 的寿命、7.B 的写格式） | §4.5、§4.6、§4.7、§6 |

**语义与迁移**：

| ID | 严重度 | 发现 | 处理 |
|----|--------|------|------|
| U1 | 高 | 7.B 的别名按 7.x 语义，同一行配置在升级到 v8 时静默反转 | §4.8：旧 `defaults` 对应 `overrides`；7.B 只接受 `overrides` 别名 |
| U2 | 高 | `lock` 撑不起 CoSec 的约束与存储布局；全局 `overrides` 遇到锁定字段启动失败 | §4.8 `lock` 覆盖布局、`constrain`、全局层跳过、破窗开关 |
| U3 | 中高 | `registered-keys` 对 `@CacheEvict` 用户不起作用；回滚残留 | §4.9 回滚失效事务内的填充；starter 的事务装饰器 |
| U4 | 中高 | 外部来源沿用 #700 的默认值与 A7 相悖；误分类的后果被低估 | §4.1 派生默认值（RETRY）与误分类表 |
| U5 | 中高 | `Unknown` 在调用点难发现；适配器的 `as?`；`lookup` 语义变化；`getAll` 的部分 `Unknown` | §3.1 `valueOrThrow` / `fold` / `lookupAll`；§4.1；§4.9；§8 |
| U6 | 中高 | 混合部署以应用群为单位 | A13、V13、§4.7 升级顺序与 `write-format`、§10 |
| U7 | 中高 | 分型问题没有总是确定的答案 | V1 改为“能否按需重建”；§4.1 例子与不支持的用法；Q14 |
| U8 | 中 | 补偿失效与显式 RETRY 违反 I-D；pending 期间的读取 | I-D、§4.6 |
| U9 | 中 | `unknownRead` 与 `read-failure` 不一一对应 | §4.1 作用范围、§4.8 别名、Q15 |
| U10 | 中 | `overrides` 的设定语义放宽更严的声明；校验连锁 | A14、§4.8 取上限与夹紧 |
| U11 | 中 | 每节只允许适用的键；三态 | §4.8 全局层跳过、适用性表、三态 |
| U12 | 中 | 告警覆盖不到库创建的缓存；无法提前写出声明 | §8 告警移到 core、前向兼容的声明、迁移报告、Q16 |
| U13 | 中 | 全部抽象方法之后，8.x 无法新增操作 | §4.2 演进规则 |
| U14 | 中 | CoSec 演练遗漏 4 个缓存；6.7 → 6.9 的静默变化 | §8 下游演练 |
| U15 | 中低 | 能力访问器“不剥掉能力”需要额外条件 | §4.3 抽象访问器、`ForwardingRemoteStore`、`expectedCapabilities`、原生形状 |
| U16 | 低中 | 定制器能改数据来源 | §4.8 |
| U17 | 低中 | “7.x 末版”不是确定的版本 | V13 命名的桥接版本 7.B |
| U18 | 低 | 注解时长改字符串不值得 | 撤回，注解仍按秒 |
| U19 | 低 | `allEntries`、`/actuator/caches`、`VersionedCache` 的端点删除、驱逐检查读不到 `INFO` | §4.9、§4.2、§4.1 |
| U20 | 低 | `Store` 一律写摘要段 | §4.7 `digest` 取 `ON_WRITE` 或 `ON_DEMAND` |

**尝试攻破但成立的论断**（第二轮摘要）：
- **`GETRANGE` 的偏移**：两段都有时恰好覆盖 64 字节（v3 只读到摘要段末尾，即字节 0–55）。
- **Caffeine 的比较语义**：`remove(k, entry)` 对强引用值按 `Objects.equals` 比较，`LocalEntry` 不重写 `equals`，所以是身份比较。`compute` 返回 `current` 只刷新访问时间，不改变语义。
- **复核与驱逐、过期、维护任务**：交错无害。
- **fill 的广播**：与 6.x 的“`fill` 不广播”不冲突。
- **头部摘要**：始终与载荷一致。
- **同一 namespace 的不同声明**：其问题来自类型语义，不是格式。
- **补偿失效**：对有数据源的缓存无害。
- **`abandon` 与在途的 `Fill`**：令牌守恒。
- **v8.0 与 7.B**：v8.0 本身不会让 7.B 绕过 L1。
- **6.x 节点为格式 2 授予的刷新令牌**：会被释放。
- **自建客户端**：断线时不重放已写出的命令。
- **外部来源与 `Store`**：值得分开。
- **登记失效时立即递增戳**：不引入新问题。
- **只接受 `Cache` 的组件**：`TransactionalInvalidator` 只接受 `Cache` 是对的。
- **Spring 的 `CacheErrorHandler`**：能覆盖异步与响应式路径。
- **Java 与 `Unknown`**：Java 能在 JDK 21+ 发现 `Unknown`。
- **CoSec 的摘要**：在 v8 中不受影响。
- **`unknownRead = ABSENT`**：能表达 CoSec 撤销表的放行。
- **Wow**：分型与异常映射不变。
- **`key-prefix`**：对 starter 声明的缓存，不参与分层是对的。

### v3 → v4：维护者决定（2026-10-31）

| 决定 | 对本 RFC 的影响 |
|------|----------------|
| 跳过 v7 | 删除 6.x / 7.x 先行与桥接版本 7.B（旧 V13、§8、§9、§10 的 7.x 步骤）；原定 7.x 的工作并入 v8 的里程碑（§10） |
| 不考虑兼容与数据迁移 | 删除格式 1 的读写与共存矩阵、`write-format`、降级工具、跨版本 TCK、配置别名与迁移报告、`foreign-format`、`CoCache5Migrator`；删除公理 A13 |
| 8.x 与 6.x 物理隔离 | 新 V13：与用户前缀无关的布局标记（§4.7） |
| 基线升到 JDK 21 | V4 只有阻塞形状的 SPI；V5 一份同步实现，异步视图跑在虚拟线程上；sans-IO 状态机不再是主方案（Q17）；确定性仿真改为带种子的故障仿真（§4.10） |
| 配置按 v3 完整实现 | V10 不变，只删除迁移机制 |
| 默认采用（维护者可以改） | SPI 改名 `L1Store`（Q2）；摘要保留 SHA-1（Q6）；Q10 倾向扩展区；`main` 改为 8.0.0-SNAPSHOT，切出 `6.x` 维护分支 |

### v4 → v5：第三轮（8.0 冻结格式之前进行）

重点：
- **虚拟线程上的异步视图**：钉住、执行器替换、取消与拒绝、`AsyncCacheLoader` 的等待、L2 命中不提交任务的实现，以及与 RFC 0004 的语义差异。
- **去掉 sans-IO 之后**：`try/finally` 的令牌守恒与基于 `HookedBackend` 的性质测试，能否覆盖 v3 状态机上的同等性质（I-F）。
- **格式定稿**：§4.7 的段、`E`、扩展区、Q10 的位置、逐脚本规则与布局标记。
- **配置**：§4.8 三层配置的取上限与夹紧语义、`lock` / `constrain` 的表达力。
- **Spring**：§4.9 两个事务机制的组合，以及 starter 事务装饰器与 Spring 契约的差异。
- **切换**：§8 中权威数据的重建与双写对 CoSec、Wow 是否可行。

## 附录 A：6.8.0 架构审查发现（2026-10-28）

范围：`cocache-api`、`cocache-core`、`cocache-redis`、`cocache-spring`、`cocache-spring-boot-starter`。
- “复现”：用临时用例实际运行观察到。
- “读代码”：经代码与依赖库确认（spring-tx 7.0.9 字节码、JDK 语义）。

| 编号 | 严重度 | 发现 | 依据 | 位置 | 去向 |
|------|--------|------|------|------|------|
| D1 | 高 | 没有数据源的 `Cache` 在 L1 读失败（默认 `DEGRADE`）时返回 `null`、不报错，并作为已决的负缓存写入 L2；之后 `get(key, loader)` 命中它，不调用 loader | 复现 | `CoherentEngine.loadThrough`、`completeBypass` | 6.9.0 已修复（#700）；v8 §4.1 |
| D2 | 高（权威副本） | 默认 `RETRY` 的延迟重试会删除其它节点之后写入的值 | 复现 | `CoherentEngine.scheduleRetry` | 6.9.0 已修复（#700）；v8 §4.1 |
| D3 | 中 | 没取得刷新令牌的节点按往返频率轮询 L1；寿命 ≤ 刷新窗口时持有者背靠背回源 | 复现（前者）、读代码（后者） | `CoherentEngine.refill`、`AbstractEngine.localEntry` | 6.9.0 已修复（#698）；v8 §4.6 |
| D4 | 中低 | L2 填充“校验—写入—复核—撤销”不可线性化 | 读代码 | `AbstractEngine.fillLocal` | 6.x 待排期；v8 §4.5 |
| R1 | 中高 | 超时只取消派生的 future，Lettuce 命令不被取消 | 读代码 | `RedisConnections.bounded`、`await` | 6.9.0 已修复（#701）；中断路径见 B.1 |
| R2 | 中低 | `sharded` 频道未确认期间只重置一次 | 读代码 | `ShardedInvalidationChannel.ping` | 6.9.0 已修复（#701） |
| R3 | 低 | 订阅与退订并发时订阅者可能永久收不到失效 | 读代码 | `RedisInvalidationChannel.subscribe` | 6.9.0 已修复（#701） |
| R4 | 中 | 非原子广播时条件写入的重放永不广播 | 读代码 | `CoherentEngine.writeIfNewer` | 6.9.0 已修复（#700）；v8 §4.6 |
| T1 | 中高 | 事务回滚时不失效；事务内的回源可能把未提交的数据写进 L1 | 读代码 | `TransactionalInvalidator` | 6.9.0 已修复（#697）；`@CacheEvict` 的同类缺口见 v8 §4.9 |
| T2 | 中低 | 在另一个同步的 `afterCommit` 中登记的失效会丢失；`flush` 遇到第一个异常即停止 | 读代码（含 spring-tx 7.0.9 字节码） | `TransactionalInvalidator` | 6.9.0 已修复（#697） |
| P1 | 中低 | 接口式代理遇到受检异常时由 JDK 包装为 `UndeclaredThrowableException` | 读代码（JDK 语义） | `CacheProxies.Handler` | 文档；v8 Q7 |
| C1 | 低 | 时钟用墙钟，回拨时 L2 副本多活 | 读代码 | `TickingClock` | 6.x 待排期；v8 §4.5 |
| S1 | 低 | 同步跟随者用 `join()` 等待，不可中断、无上限 | 读代码 | `SingleFlight.await` | 6.x 待排期；v8 §4.4 |
| L1 | 低 | 读 L1 超时之后不释放读脚本可能已授予的 lease | 读代码 | `RedisRemoteStore.read` | 6.9.0 已修复（#701）；中断路径见 B.1 |
| L2 | 低 | 同步的 `SetCache.getAll` 逐个 key 串行访问 L1 | 读代码 | `SetEngine.getAll` | 6.x 待排期 |
| L3 | 低 | 策略 `ttl` 永不过期时，墓碑也永不过期 | 读代码 | `CoherentEngine.versionedTtl` | 文档；v8 `tombstoneTtl` |
| L4 | 低 | namespace 只校验非空：`a` 与 `a:b` 可能映射到相同的存储 key | 读代码 | `CacheSpec` 的 `requireValidName` | 6.x 待排期 |
| L5 | 低 | `addMembersIfReadable` / `removeMembersIfReadable` 的默认实现先读后写、不原子 | 读代码 | `RemoteStore` | v8 §4.3 |
| L6 | 低 | `@CacheEvict(allEntries = true)` 只清本节点 L2；端点的按 key 删除对 `SetCache` 会删除唯一副本 | 读代码 | `CoCacheSpringCache.clear`、`CoCacheEndpoint` | v8 §4.9 |
| L7 | 低 | 格式字节按有符号解析 | 读代码 | `RedisRemoteStore` | 6.9.0 已修复（#701） |
| L8 | 低 | 策略时长不带单位按秒、`cocache.redis.*` 按毫秒；`defaults` 覆盖显式声明 | 读代码 | `CoCacheProperties` | v8 §4.8 |
| L9 | 低 | 创建缓存时 `backend.store` 或工厂抛出会让 namespace 保持占用 | 读代码 | `CacheRuntime` 的注册流程 | 6.x 待排期 |
| L10 | 低 | architecture.md §6.3 说“启动时不检查这两点”，与 `SetCacheEvictionCheck` 不符 | 读代码 | `docs/architecture.md` §6.3 | 6.x 待排期 |

未发现缺陷的部分：
- Lua 脚本的状态转换与到期时间重设、令牌比较；
- `READ` 对 `pttl` 的边界处理；
- 带符号 64 位的版本比较；
- Cluster 分组与 CROSSSLOT；
- `InvalidationFrame` 解码的边界；
- `readAllAsync` 的完成与 lease 释放；
- `CacheRuntime.close` 的顺序；
- `invalidateEncoded` 经过栅栏；
- `REQUIRES_NEW` 的作用域。

## 附录 B：6.9.0 中仍存在的缺陷（第二轮发现，2026-10-29）

| 编号 | 严重度 | 发现 | 依据 | 位置 | 去向 |
|------|--------|------|------|------|------|
| B.1 | 中 | 中断路径绕过了 #701。`await` 被中断时不取消 `CommandCall`，被中断的 `put` 在 Cluster 中可以迟到执行（A.R1 同类）。`releaseAfterTimeout` 只在超时时释放，被中断的读取留下 lease 到 `lease.ttl`（A.L1 同类）；`readAll` 的第一个等待被中断之后，其余等待立即失败，它们取得的 lease 都不释放 | 读代码（作者复核） | `RedisConnections.await`、`RedisRemoteStore.releaseAfterTimeout` | 6.9.1 已修复（#704）：中断时与超时一样取消命令、释放令牌，并按结果未知处理；TCK 复现 |
| B.2 | 低 | `fill` / `fillAll` 超时或失败时不释放令牌。被 `CommandCall` 完成、没有写出的 fill 留下 lease 到 `lease.ttl`，全集群对该 key 的未命中都先等 `lease.wait` | 读代码（作者复核） | `CoherentEngine.fillRemote` 及其批量与异步版本 | 6.9.1 已修复（#704）：超时或中断时尽力释放（同一条连接，排在 fill 之后；令牌不匹配时无害） |
