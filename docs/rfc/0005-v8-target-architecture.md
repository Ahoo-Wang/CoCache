# RFC 0005：v8 目标架构（权威与派生分型、单一协议核心、可检验的栅栏）

- 状态：**草案 v6**（2026-11-03），待维护者审阅。
  - v1（2026-10-28）经第一轮对抗验证修订为 v2：一致性、迁移与运维两路，共 28 条发现。
  - v2 经第二轮修订为 v3：协议与格式 18 条、语义与迁移 20 条，共 38 条。两轮的发现全部已处理，见 §12。
  - v3 → v4：维护者的四项决定（2026-10-31）：跳过 v7；不考虑兼容与数据迁移，8.x 以布局标记与 6.x 物理隔离；基线升到 JDK 21；配置按 v3 完整实现。
  - v4 → v5：维护者审阅 v4 后的决定（2026-11-01，§12）：
    1. **协议核心用协程实现**：协议只写一次，写成 `suspend` 函数；同步、异步、挂起三种 API 都是它的变体（V5）。`kotlinx-coroutines-core` 进入 `cocache-core`，`cocache-coroutines` 并入 core。
    2. **JDK 基线保持 21**：虚拟线程是阻塞 loader 的默认调度器。
    3. **权威数据（`Store` / `SetStore`）提供一次性拷贝工具**：只把唯一副本从 6.x 布局拷到 8.x 布局；不做运行时兼容（§8）。
    4. **权威数据的摘要改用 SHA-256**：由客户端计算，存在头部；去掉 `digest = ON_DEMAND`（§4.7）。
    5. **频道名带上 key 前缀**（§4.7）。
    6. **8.0 的用户 API 类型按 v4 全部实现**。

    另外修正了 v4 的三处问题：M1 的等价性裁判、跟随者不该占线程、中断与取消的范围（§12）。
  - 2026-11-02，M3 之前：维护者确认 SPI 的命名（Q2 关闭）、`FutureL1Store` 的形态、L1 调用的取消契约，并删除没有数据源的缓存上的 foreign RECLAIM（§4.3、§12）。
  - v5 → v6（2026-11-03）：M4 冻结线格式之前的第三轮对抗验证，协议与格式 20 条（K）、语义与迁移 24 条（S），全部处理；其中八项决定 D1–D8 由维护者确认“全部按推荐”（§12）。主要变化：
    1. **M4 冻结的范围扩大**：失效帧的演进规则、扩展区的 TLV 与写入标识、集合的 `!f` / `!m`、namespace 字符集与内部 key；带摘要段内容的脚本规则（§4.7）。
    2. **摘要**：保留 SHA-256，`Digest` 带算法标识，读取方重算并比较；撤回第二轮“CoSec 的摘要不受影响”的结论（§4.7、§8）。
    3. **SPI**：`fill` 返回 `L1Fill`，`reclaim` 比较 SPI 自己的内容指纹，除确定未执行外的失败都释放令牌，读取可带截止时间；随 M3d 实施（§4.3）。
    4. **协程核心**：挂起变体的元素取自调用方、调度器取自运行时；重入守卫在 `ThreadLocal` 之上增加上下文元素；同步 leader 内联执行（§4.4）。
    5. **拷贝工具**：以 8.x 的 spec 为输入，转换表写全，增加 follow 模式与双向失效中继；写入方切到 8.x 之后不能回退（§8）。
    6. **配置与 Spring**：overrides 只收紧陈旧界限；事务上下文在调用入口捕获，记录进入任何共享层的 key（§4.8、§4.9）。
  - 2026-11-03，M4 期间：维护者决定**不考虑兼容、不背历史债务**（§12）：不提供迁移工具，6.x 与 8.x 不共存，升级之后有数据源的缓存回源重建；布局去掉版本标记 `v8:`；格式 1 与格式 0 一样按读不懂处理；不检测 6.x 的配置键；删除只为旧版本保留的代码与注释（§4.7、§4.8、§5、§8）。
  - 目标版本由维护者指定为 **v8**。
- 前提：v8 是大版本，API、SPI、配置项、线格式与 key、频道布局都重新设计；但三个目标（[architecture.md](../architecture.md) §1 的 G1–G3）与 6.0 的核心协议（lease 栅栏、两次递增的失效戳、原子广播）**不变**。本 RFC 不推翻 [RFC 0003](0003-target-architecture.md)，而是修正它在 6.4–6.8 增量演进中暴露的结构性问题。
- 关系：
  - 6.x 只修缺陷。6.10.0 是最后一个功能版本（#707、#708、#709、#711），之后在 `6.x` 维护分支上只做缺陷修复（§9）。
  - 不发布 7.x。
  - 规范仍以 architecture.md 为准，本 RFC 实施时同步修改它。
- 来源：
  - 2026-10-28 对 6.8.0 的架构审查（附录 A）。不破坏兼容的修复已在 6.9.0、6.9.1 与 6.10.0 发布（§9）。
  - 三轮对抗验证（§12）。第二轮发现 6.9.0 中仍有两个缺陷（附录 B），已在 6.9.1 修复（#704）；第三轮发现的现行代码缺陷（K5、K17、S5、S6）不等本 RFC，直接修复。

---

## 0. 结论摘要

| # | 决定 | 一句话理由 |
|---|------|-----------|
| V1 | **权威与派生分成不同的类型，按“L1 丢了这个 key，本进程能否按需从别处重建它”判断**。能，则为 `Cache` / `VersionedCache`（派生副本）；不能，则为 `Store` / `VersionedStore` / `SetStore`（权威副本）。派生缓存**必须声明**数据来源：spec 的数据源，或 `externallySourced()`，即由调用方经 `put`、Spring Cache 或调用级 loader 提供。接口式声明既没有匹配到数据源、也没有声明外部来源时，启动失败 | 6.x 在至少 6 处按“这次读取有没有数据源”在运行时分支（A.D1、A.D2）。“L1 中没有是否等于不存在”对寿命即语义的数据、离线重建的投影、只读消费方都没有确定答案（§12 U7）；“能否按需重建”有 |
| V2 | **`Unknown` 只表示故障**。读取结果是 `Present` / `Absent` / `Unknown`。`Unknown` 只来自 L1 故障与等待超时；在有数据源的缓存上，只在显式 `readFailure = THROW` 时出现。`get` / `getAll` 遇到它抛出 `CacheReadException`，`lookup` 返回它，`Lookup` 另有不必模式匹配的 `valueOrThrow()` / `fold`。没有数据源的缓存可以在声明中写 `unknownRead = ABSENT`。外部来源的缓存在 L1 中未命中时是“未填充”：带标志进入 L2，带 loader 的调用方不接受它 | v1 把正常未命中与故障归为一类（M2）。删除 `unresolved` 标志会让 L2 命中路径分不清“未填充”与 `Absent`（P3）。`as? Lookup.Present` 一类写法会静默吞掉 `Unknown`（U5） |
| V3 | **能力即类型**。条件写入、按版本写入、元素写入只出现在声明了它的类型上；删除全部默认抛出 `UnsupportedOperationException` 的方法，`async()` 成为抽象方法。带版本的类型与无版本的类型是兄弟，带版本的类型上没有丢失版本的写入：派生的带版本副本用保留版本栅栏的 `evict`，编码为新状态 `E`。8.x 只以新的能力类型、或不抛出的默认方法演进 | `Cache` 有 9 个方法默认抛出，`SetCache` 拒绝约一半方法；带版本的类型上的 `put` / `invalidate` 撤掉版本栅栏（C6、M12）。复用 `L` 编码 `evict` 不可行（P1）。全部抽象方法之后，8.x 没有新增操作的规则（U13） |
| V4 | **SPI：`suspend` 为主，Java 用 `CompletableFuture` 适配**。`L1Store`（取代 `RemoteStore`，Q2）的方法是 `suspend` 函数；内置的 Redis 后端以 Lettuce 的异步命令加 `await()` 实现，不占线程；Java 实现者实现返回 `CompletableFuture` 的 `FutureL1Store` 接口，经 `FutureL1Store.adapt` 适配。引擎从不取消 L1 调用，中断不到达存储。`fill` 返回 `L1Fill`，`reclaim` 比较 SPI 自己的内容指纹；除确定未执行外的失败都释放令牌；读取可带截止时间。能力用抽象的访问器表达，`ForwardingL1Store` 是装饰器的基类。TCK 的 `expectedCapabilities` 没有默认值，断言“提供的能力 = 声明的能力”。SPI 契约写明“确定未执行 / 结果未知”的判定 | 6.x 用两处表达同一事实，还有非原子的默认实现（A.L5）；同步与异步两套 SPI、两套 TCK（`Async*Spec`）。协程核心只需要一种形状。`fill: Boolean` 表达不了版本拒绝（K12） |
| V5 | **一份协程实现，三种变体**。协议只写一次，写成 `suspend` 函数。L2 命中在三种 API 中都是普通函数、内联返回；未命中与写入：同步变体在调用方线程上以 `runBlocking` 运行核心，loader 在调用方线程上执行；异步变体以 `future(start = UNDISPATCHED)` 运行，不占线程；挂起变体直接调用核心，元素取自调用方、调度器取自运行时。异步与挂起变体的共享加载在运行时的作用域中执行，调用方取消只结束它自己的等待，同步 leader 内联执行；取得令牌之后的步骤不可取消。flight 层逐 key 应用合并规则；注册超过 `load.timeout` 的 flight 被注销 | `CoherentEngine` 2405 行，同步与异步逐分支复制（C1、C7、C9）。协程让一份直线代码同时服务三种调用方：跟随者挂起在加载的 `Deferred` 上、不占线程；虚拟时间与测试调度器让多节点仿真可以确定性重放（§4.4、§4.10） |
| V6 | **L2 填充在 key 锁内校验并写入，写后按身份复核**（Caffeine `compute` + `remove(k, entry)`） | 关闭 A.D4 对写入方与远端事件的窗口；Caffeine 的 `clear()` 看不到进行中的 `compute`，对重置要靠复核（C4） |
| V7 | **失败路径与成功路径一样确定**。按客户端能观察到的事实，区分“确定未执行”与“结果未知”：超时、断线时被排空、等待被中断、被取消的写入都属于结果未知。结果未知的写入，安全只能来自顺序。补偿失效只用于失效失败模式为 `RETRY` 的派生缓存。令牌守恒覆盖 `fill` 与 `reclaim`，除确定未执行外的失败都释放令牌。等待有界，只有跟随者的等待可中断。刷新的触发率与命中率无关 | 已写出的命令照常执行（C5）；中断路径绕过了 #701（P5）；超时与写出无法在客户端区分（P8）；`fill` 失败不释放令牌（P13）；断线排空时 lease 留到 `lease.ttl`（K14） |
| V8 | **线格式 2：唯一的格式，按需扩展的头部**。v8 不读写格式 1，读到它与其它不是本协议写入的内容一样按读不懂处理。核心 36 字节布局沿用，标志位声明版本段（8 B）与摘要段（32 B 的 SHA-256，客户端计算、读取方校验）；bit 7 是可以整体跳过的 TLV 扩展区，权威写入带写入标识；新增状态 `E`。未知的必须理解的标志位等同于更高格式，读不懂的失效帧按 `onReset` 处理，供 8.x 内部演进。带摘要段的内容不授予令牌、不被回收。段、状态、扩展区、帧的演进规则、集合格式与 namespace 字符集在 M4 冻结。`fill` 不让版本后退，版本拒绝不污染 L2 | 6.8 把版本塞进令牌位置；`getIfChanged` 每次在 Lua 中计算 SHA-1（8 MB 约 20 ms）；格式不预留就会冻结整个 8.x（P6），丢弃读不懂的帧就是丢失效（K1）；SHA-1 已有可行的选择前缀碰撞，M4 之后会在 8.x 中固定多年（D1） |
| V9 | **栅栏可被机器检验**：Lincheck（flight 层 + L2 + 失效戳，含 `suspend` 操作）、TLA+ 模型、以测试调度器与虚拟时间驱动的确定性多节点仿真 | TCK 的竞态用例只覆盖人想到的交错；两轮验证的大部分发现都需要搜索交错才能稳定复现 |
| V10 | **配置分三层，字段级合并**：`defaults`（低于声明）→ 声明 → 定制器 → `overrides`（高于声明，只收紧：陈旧界限取上限，有严格序的模式字段取更严的值，其余字段不进 overrides）→ `caches.[name]`。全局层跳过不适用的字段与被锁定的字段，并记录下来；只有按缓存的层出现这类配置时才启动失败。字段有三态，哨兵值显式。时长必须带单位，注解仍按秒。库用 `CoCacheFactory` 装配；`lock` 可以锁定策略与存储布局、附加约束，`caches.[name].unlock` 是破窗开关。6.x 的配置键不保留别名，按规则检测，出现即启动失败 | 6.x 的 `defaults` 覆盖显式声明（M5），“设定”语义又会放宽更严的声明（U10、S10）；调小等待界限会放大事故中的回源（S9）；`lock` 撑不起 CoSec 的约束与存储布局（U2） |
| V11 | **Spring**：<ul><li>写事务内进入任何共享层的 key，在事务回滚（含回滚到 savepoint）或结果未知时默认失效；事务上下文在调用入口捕获，异步与挂起变体不门控、不记录。</li><li>starter 以自己的事务装饰器取代 `TransactionAwareCacheManagerProxy`，`@CacheEvict` 经 `TransactionalInvalidator`，`evictIfPresent` 立即失效并登记。</li><li>写事务内、已登记失效的 key，其回源结果私有。</li><li>`Unknown` 在适配器中以 `CacheReadException` 交给 `CacheErrorHandler`，`afterCommit` 中的失败同样交给它。</li></ul> | loader 在调用方的事务内执行（A.T1）；只挡 L1 不够（C2）；一律拦截会让 L1 填不满（M7）；`registered-keys` 对 `@CacheEvict` 用户不起作用（U3）；只进 L2 的降级填充与回滚到 savepoint 不在记录里（S15、S6） |
| V12 | **规范只写现行行为**：architecture.md 去掉版本叙述，不变量编号稳定（`I-n`）；§6 以对照表逐条标注 architecture.md §14 的“取代 / 修改 / 保留” | v1 只写了“其余不变量保留”，其中 6 条与本 RFC 冲突（C13）；v2 漏了第 21 条（P18） |
| V13 | **不兼容 6.x，不提供迁移工具**（维护者决定，2026-11-03）。存储 key 为 `{keyPrefix}{namespace}:{encodedKey}`，频道为 `{keyPrefix}inv:{namespace}`，namespace 不含 `:`，布局中没有版本标记；升级时停掉 6.x、删除没有数据源的缓存的旧 key，再启动 8.x，有数据源的缓存回源重建（§8） | 不背历史债务：8.x 读到 6.x 的内容按读不懂处理（派生缓存条件回收后回源，没有数据源的缓存 `FAIL`），不需要任何兼容代码。布局标记、拷贝工具与共存期的失效中继都只为一次升级存在，却要随 8.x 一直维护 |

---

## 1. 第一性原理

[RFC 0003 §1.1](0003-target-architecture.md) 的六条公理（串行化点、令牌先于数据、权威发出失效、TTL 是安全网、命中路径只做必要的事、声明与实例分离）全部保留。6.4–6.8 的演进与两轮验证补出下面八条（A13 在 v4 删除）。

- **A7 权威性决定失败语义**。副本可以丢、可以降级，因为它能从别处重建；唯一的副本不能丢，也不能降级成“不存在”。删除副本只在写入方之间没有顺序要求时才总是安全：按版本推送时，L1 中的副本本身就是顺序的仲裁者。一个默认值如果在两种数据上应当不同，它就不该是同一个类型上的同一个默认值。
- **A8 “没有答案”不是“不存在”**。`Absent` 是关于数据的事实，可以缓存、可以广播；`Unknown` 是关于这一次读取的事实，只能返回给这一次调用，也不能交给另一个本可以自己回源的调用方。“未填充”介于二者之间：它对不能回源的读取是答案，对能回源的读取不是。
- **A9 一个协议只写一次**。阻塞还是回调、在哪个线程上继续，是驱动的事，不是协议的事。
- **A10 失败路径也是协议**。超时、取消、拒绝、中断、崩溃之后，系统处于什么状态，要和成功、失败一样确定。
- **A11 不变量要能被机器检验**。
- **A12 “结果未知”不是失败**。已经写出的命令在任何时刻都可能生效，可能排在其它连接上更晚发出的写入之后。让迟到的执行无害的，只有顺序：令牌、版本、条件。而条件本身也有 ABA。
- ~~A13 迁移的单位是共享 namespace 的应用群~~：v4 删除。8.x 不兼容 6.x，两者不共存（V13）；8.x 内部的格式演进仍遵守“新写入等到所有读者都能读懂之后才出现”（I-L）。
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
ReadableCache<K, V>             name、get、getAll、lookup、lookupAll、async()、suspending()
├── Cache<K, V>                 派生副本：get(k, loader)、put、putAll、invalidate、invalidateAll
├── VersionedCache<K, V>        派生的带版本副本：getVersioned、putIfNewer、invalidateIfNewer、evict、purge
├── Store<K, V>                 权威副本：put、putAll、delete、deleteIfMatch、getIfChanged、putIfMatch
├── VersionedStore<K, V>        权威的带版本副本：getVersioned、putIfNewer、deleteIfNewer、purge
└── SetStore<K, E>              权威集合（ReadableCache<K, Set<E>>）：add、remove、clear、put、putAll
```

- 每个接口只声明它支持的操作，全部是抽象方法。异步视图与挂起视图有同构的层次，与同步 API 一起由同一份协程实现提供（§4.4）。挂起视图的接口（`SuspendingCache`、`SuspendingStore` 等）在 `cocache-api` 中，经抽象的 `suspending()` 取得，与 `async()` 对称：`suspend` 只依赖标准库，`cocache-api` 仍没有运行时依赖；实现在 core，`cocache-coroutines` 模块删除（维护者，2026-11-03）。带版本的类型不继承无版本的写入。
- `Lookup` = `Present` | `Absent` | `Unknown`（V2，§4.1），另有 `valueOrThrow(): V?`（`Unknown` 时抛出 `CacheReadException`）。基线 JDK 21 的 Java 可以对 sealed 类型穷尽 `switch`，所以没有 `fold`（维护者，2026-11-03）。
- spec 与返回类型一一对应：
  - `cacheSpec` → `Cache`，必须声明 `loader`、`asyncLoader` 或 `externallySourced()` 之一；
  - `versionedCacheSpec` → `VersionedCache`，必须有带版本的数据源；没有数据源的带版本缓存就是 `VersionedStore`；
  - `storeSpec` → `Store`；
  - `versionedStoreSpec` → `VersionedStore`；
  - `setStoreSpec` → `SetStore`。
- 组合视图拆成两种：`JoinView`（只读，两侧是 `ReadableCache`）与 `JoinCache`（两侧都是 `Cache`，保留 6.x 的两个 `invalidate` 方法）（M14）。

### 3.2 内部结构

```
              ┌───────────── 命中路径（普通函数，三种变体共用） ─────────────┐
用户调用 ────▶│ L2 查找 + 过期比较 + “未填充”标志 → 返回（异步：已完成的 future）│
              └───────────────┬─────────────────────────────────────────────────┘
                              │ 未命中 / 写入
        ┌─────────────────────┼──────────────────────┐
        ▼                     ▼                      ▼
  同步：runBlocking     异步：future(UNDISPATCHED)  挂起：直接调用
  （调用方线程）         （运行时作用域，不占线程）   （调用方协程，运行时调度器）
        └─────────────────────┼──────────────────────┘
                              ▼
              ┌───────────── flight 层（suspend） ───────────────────────────────┐
              │ 注册 → 取戳 → 运行协议 → 比较戳 → 至多重试一次                     │
              │ 跟随者挂起在 Deferred 上；逐 key 的合并规则；超过 load.timeout 注销 │
              └───────────────┬─────────────────────────────────────────────────┘
                              ▼
              ┌───────────── 协议（一份 suspend 实现） ───────────────────────────┐
              │ 单 key 未命中、批量、提前刷新、写入；取得令牌之后的步骤 NonCancellable │
              └───────────────┬─────────────────────────────────────────────────┘
                              ▼
          L1Store（suspend，经 ForwardingL1Store 装饰）、InvalidationChannel、L2、失效戳
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
- 同一个 namespace 的所有声明必须是同一类型。类型不在线格式中，跨应用的不一致只能部分地在协议层发现：权威标量的摘要段本身就是标记，派生缓存的脚本不对它授予令牌、不回收它，派生读者读到它时计数告警（§4.7，D4）；`VersionedCache` 与 `VersionedStore` 都只有版本段，分不出来。端点输出每个缓存的类型，供人工核对。

读失败时放行还是拒绝，**不是类型的属性**，由 `unknownRead` 在声明中写明（见下）。

| 方面 | `Cache`（派生副本） | `Store` / `VersionedStore` / `SetStore`（权威副本） |
|------|--------------------|----------------------------------|
| 数据来源 | 必须声明：spec 的 loader / asyncLoader，或 `externallySourced()` | 按构造没有 |
| L1 中不存在 | 有数据源：回源；外部来源：“未填充”，带标志、受戳保护进入 L2（限龄 `absentTtl` 与 `localMaxAge`）。不带 loader 的读取看到 `Absent`，带 loader 的调用方不接受它，用自己的 loader 回源 | `Absent`，受戳保护进入 L2（限龄 `absentTtl` 与 `localMaxAge`） |
| lease | 未命中时申请 | **不申请**；读到 lease 占位就是 `Absent`（C11） |
| L1 读失败 | 有数据源（含调用级 loader）：降级回源；显式 `readFailure = THROW` 时为 `Unknown`。没有数据源的读取：`Unknown` | `Unknown` |
| `Unknown` 的处理 | `get` / `getAll` 抛出 `CacheReadException`（`cause` 为原始异常），`lookup` 返回 `Unknown`；声明 `unknownRead = ABSENT` 时返回 `Absent`（见下） | 同左（`SetStore` 不允许 `unknownRead`） |
| 读不懂的内容 | 有数据源：`RECLAIM`；外部来源：`FAIL` | `FAIL` |
| 写入落在读不懂的内容上 | 条件写入、按版本写入拒绝，不把它当作没有值 | 同左；`SetStore` 的元素写入同样拒绝 |
| 删除 | `invalidate`，失败默认 `RETRY`：删除副本在任何时刻都安全 | `delete` / `deleteIfMatch`：同步完成，失败抛出 `CacheWriteException`，**从不排队重试** |
| 写入 | `put`（后写者胜，不推荐；首选先提交数据源再 `invalidate`） | `put`、`putIfMatch`；带版本的类型只有按版本写入 |
| 负缓存 | 回源返回 `null` 时写入 `M` | 不适用：`Absent` 就是不存在 |
| 默认 `ttl` | 1 h | 永不过期；`VersionedStore` 的墓碑与 `E` 另有有限的 `tombstoneTtl` |
| L2 的默认寿命 | `localMaxAge` 5 m、`absentTtl` 1 m | `localMaxAge` 30 s、`absentTtl` 10 s，端点标注“类型默认值”：失效丢失时没有回源可以纠正，陈旧的上界只剩 L2（D7） |
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
- **`unknownRead` 的取值与范围**（U9、S11、S16）：
  - 取值 `THROW | ABSENT`，默认 `THROW`。
  - 作用于不带 loader 的 `get` / `getAll` / `lookup` / `lookupAll` 及其异步、挂起视图；不作用于 `getIfChanged`、`getVersioned` 与条件写入。带 loader 的读取由 `readFailure` 决定，外部来源缓存上的调用级 loader 也是（CoSec 的 `MembershipCache`：外部来源 + 调用级异步 loader + `readFailure = THROW`）。
  - 只能用于没有数据源的缓存与权威缓存；`SetStore` 上不允许。
  - 只能出现在声明与 `cocache.caches.[name]` 中，不能出现在 `defaults` / `overrides`（否则一行配置就让全部权威缓存 fail open），可以被 `lock`。`caches.[name]` 可以把声明的 `ABSENT` 收紧为 `THROW`：CoSec 撤销表“默认放行、运维可改为拒绝”的开关由此表达。
  - 映射发生在调用方返回之前：flight 共享的结果仍是 `Unknown`，带 loader 的调用方不会接受它（P3）。
  - 计数：`cocache.reads.unknown` 与 `cocache.remote.failures` 都计。
- **合并规则**（C1、P10）：
  - flight 的共享结果是 `Present` / `Absent` / `Unfilled` / `Unknown` / `Private`（§4.9）之一。
  - 带数据源的调用方（含调用级 loader）不接受 `Unfilled`、`Unknown`、`Private`：按 6.x 的规则重试一次，仍不可用时绕过 SingleFlight，自己运行状态机。
  - 合并规则逐 key 应用：单 key 与批量、同步与异步都一样，批量中不可用的 key 改走单 key 路径。
- **`Store` 的删除不排队重试**（A7、A.D2）。需要可靠删除的写入方用 `deleteIfNewer`：版本严格递增，迟到执行不会越过之后的写入。`deleteIfMatch` 迟到执行时，可能删除之后重新写入的相同内容（P4、A12），文档写明。
- **带版本数据源的删除**（K16）：`VersionedCache` 的数据源必须以 `Versioned(null, w)` 表达删除，写成带版本的墓碑。不带版本的 `null` 落在带版本的内容上一律被拒绝：已删除的实体在 `E` 到期之前每次未命中都回源，没有负缓存；刷新路径保留旧副本到 L1 的 `ttl`。这种情形计数、首次告警，KDoc 与文档写明，TCK 覆盖（M5）。
- **驱逐策略检查**（M11、U19、S22）：
  - 读 `INFO memory` 的 `maxmemory_policy`，托管 Redis 通常允许 `INFO`；读不到时按 `cocache.redis.assume-maxmemory-policy` 处理。
  - 按声明检查。一律启动失败的情形：存在权威缓存时的 `allkeys-*`；读不到策略又没有配置 `assume-maxmemory-policy`。
  - `cocache.redis.store-eviction-check` 只管剩下的一种情形，即 `volatile-*` 且存在 `ttl` 有限的权威缓存：`warn`（默认）、`fail`、`off`，沿用 6.x 的三个取值。
  - 6.10.0 已改读 `INFO memory`、按声明检查，读不到时按 `set-cache-eviction-check` 告警或失败（#707）。

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

  需要后端支持的新能力，走 SPI 的能力访问器。用户 API 的接口区分两种用法（S21）：
  - **声明接口**：继承用户 API 接口、由代理实现的接口（`@CoCache` 接口、CoSec 的 `RevokedTokenCache : Cache`），允许继承，新增的方法由代理实现。`CoCacheFactory.create(type, spec)` 校验 `type` 是接口。
  - **手写实现**：实现这些接口的类，需要 opt-in，文档注明“不供用户实现，委托除外”。否则按 8.0 编译的委托类（如 CoSec 的 `PolicySnapshotCache : Cache by delegate`）会在调用新方法时抛出 `AbstractMethodError`。Kotlin 的 `@SubclassOptInRequired` 对继承的接口同样要求 opt-in，Java 也看不到它。决定（维护者，2026-11-03）：不加 opt-in 机制，靠 U13 保证委托类不会遇到 `AbstractMethodError`，KDoc 与文档注明“不供用户实现，委托除外”。

### 4.3 SPI（V4）

```
CoherenceBackend         store(keyspace): L1Store、channel、format、atomicBroadcast（M4a 起收 Keyspace）
L1Store                  read、readAll、fill、fillAll、release、reclaim、put、putAll、invalidate（suspend，全部抽象）
                         conditional(): ConditionalL1Store?   readIfChanged、putIfMatch、deleteIfMatch（M5）
                         versioned(): VersionedL1Store?       putIfNewer、evictKeepingVersion（M4）
                         sets(): SetL1Store?                  addMembers、removeMembers（只有“检查并写入”）
FutureL1Store            同名的操作与能力面，返回 CompletableFuture（供 Java 实现者）；FutureL1Store.adapt(store): L1Store
ForwardingL1Store        转发全部操作与能力，装饰器继承它，只覆盖要拦截的部分；三个能力面各有 Forwarding 基类
InvalidationChannel      publish(keyspace)（suspend）、subscribe(keyspace)（阻塞）；Java 实现者实现 FutureInvalidationChannel，经 adapt 适配
```

- **`suspend` 是主形状**（V4）：
  - 内置的 Redis 后端以 Lettuce 的异步命令发出、`await()` 结果，等待期间不占线程。`readAll` / `fillAll` 先全部发出再一起等待，仍是约一次往返；集群按 slot 分组同 6.x。
  - Java 实现者实现 `FutureL1Store` 接口（方法与 `L1Store` 同名，返回 `CompletableFuture`），`CoherenceBackend.store` 返回 `FutureL1Store.adapt(…)`；三个能力面各有 `Future*` 版本。只有阻塞客户端的后端在自己的执行器上完成这些 future。
  - `FutureL1Store` 不做成基类：Kotlin 不允许同名的 `suspend` 与非 `suspend` 重载，基类只能把方法命名为 `readAsync` 一类，Java 子类还会看到一组 final 的挂起方法。适配器只用标准库的 `suspendCoroutine`，`cocache-api` 仍然没有运行时依赖。
  - `subscribe` 保持阻塞：它只在 `runtime.create` 时调用一次，挂起只会让创建路径多一层 `runBlocking`。`publish` 在写路径上，挂起。
  - 删除 `AsyncRemoteStore`、`AsyncInvalidationChannel`、`BlockingAsyncStore` / `BlockingAsyncChannel` 与全部 `Async*` TCK 规范。
- **取消与中断**：
  - 引擎从不取消 `L1Store` 的调用：核心在 `NonCancellable` 中调用它们，实现用可取消的 `await()` 也安全。调用的结束只由实现决定，每个调用都由实现自己限时（命令超时）；超时时未写出的命令不再写出（6.9.1 的规则），令牌的释放见下面的契约。
  - 故障不得以 `CancellationException` 报告：在 JVM 上它就是协程的取消，核心会把它当作调用方被取消（不降级、跟随者自行运行）。底层客户端取消的命令（如 Lettuce 在连接重置、关闭时取消的命令）按结果未知的 L1 故障报告；`FutureL1Store.adapt` 把被取消的 future 换成 `IllegalStateException`（#732）。6.x 的两条路径都把 Lettuce 的取消原样交给核心，M3d 改正。
  - 中断不到达存储。同步变体的线程被中断时，`runBlocking`（kotlinx ≥ 1.10）取消协程并等它结束：跟随者、lease 等待与许可等待立即结束；在途的 L1 命令先完成或超时，取消在之后的第一个挂起点生效，令牌由核心按正常路径释放；调用方随后得到 `InterruptedException`，调用在取消生效之前已经完成的，照常返回结果。两种情形都保留中断标志。
  - 代价：被中断的同步调用方至多多等一个命令超时（默认 2 s）。6.10 中它立即以 `RedisException("Interrupted while waiting for Redis.")` 失败、由存储在结果未知时释放令牌。被否决的做法是让 `runBlocking` 立即返回、协程在别的线程上收尾：命令可能在调用方看到失败之后才写出，违反“超时或被中断的脚本不再写出”。
- **能力用抽象的访问器表达**：不支持时返回 `null`，不抛出，也没有默认实现，否则只包装基本操作的装饰器会静默剥掉能力（U15）。能力面是独立的接口、不继承 `L1Store`：继承时 `ForwardingL1Store` 转发出去的能力面就是被装饰者本身，绕过装饰器。同一个存储的访问器在其生命周期内返回同样的结果。`runtime.create` 在创建时检查所需的能力，缺失时抛出，不注册任何东西；M5 之前 `Cache` 仍有条件写入，`conditional()` 为 `null` 时与 6.x 一样在调用时抛出 `UnsupportedOperationException`。SPI 不提供任何非原子的默认实现。
- **读不懂的内容不能当作没有值写入**：SPI 没有 `foreignAsAbsent`，集合只有“检查并写入”。6.x 中这两者只服务于 5.x 留在同一 key 中的内容，8.x 的 key 与之隔离（V13）。
- **TCK fail closed**：`expectedCapabilities: Set<L1Capability>`（在 `cocache-test` 中）没有默认值，规范断言“提供的能力 = 声明的能力”，多出来的能力也算失败。能力只由访问器表达，枚举只是 TCK 的声明，不进入 SPI（否则同一个事实又有两处表达）。启动日志输出每个 namespace 的能力。TCK 同时覆盖 `FutureL1Store` 的适配。
- **“确定未执行 / 结果未知”的契约**（P8）：
  - **确定未执行**：在写出之前就被拒绝（如 Lettuce `REJECT_COMMANDS` 的 `Currently not connected`），或在 `dispatch` 之前同步失败。
  - **结果未知**：其余情形，包括超时、断线时被排空（`Connection disconnected`），以及脚本的错误回复（除非逐个脚本证明错误只可能发生在第一次写入之前）。引擎不取消调用、中断不到达存储，存储侧不再有“等待被取消或中断”这一类。
  - 其它契约：
    - 超时取消到底层命令：尚未写出的命令不再写出（6.9.0 / 6.9.1 的 `CommandCall`，#701、#704）。
    - 写入方法返回或抛出时，它发出的全部命令都已结束（否则补发的广播可能先于仍在执行的命令）。
    - 取得或交回令牌的命令（读、回收、填充、释放），除“确定未执行”外的任何失败，包括超时、中断、断线排空、脚本错误回复、底层客户端的取消与解码失败，都由存储异步释放令牌，不等待（K14）。令牌不匹配时释放无害，重连之后才到达的那次也无害。6.x 只在超时与中断时释放，引擎也不释放：断线排空时 lease 留到 `lease.ttl`，全集群对该 key 先等 `lease.wait`。
  - 这些契约写进 SPI 的 KDoc，由 TCK 的 `FaultSpec` 覆盖。区分两者的异常类型与 `CacheWriteException.outcomeUnknown` 随 M5 的用户 API 一起实现。
- **填充的结果**（K12，M3d）：`fill` 返回 `L1Fill = Filled(advanced: Boolean) | Rejected(currentVersion: Long?)`，`fillAll` 逐 key 返回它。`advanced` 表示版本前进，本节点按写入处理（§4.7）；`Rejected` 带当前版本（版本拒绝，刷新路径据此保留副本），令牌不匹配时为 `null`。`Boolean` 区分不了“已前进”“相等”与“版本拒绝”，而 lease 在 `E` 上时客户端读到的是 `Miss`，并不知道版本。结果类型随 M3d 的改名一起定，M4 不再改 SPI。
- **读取的截止时间**（D6，M3d）：`read` / `readAll` 增加可选的截止时间参数，存储以它与命令超时中较早的一个为限，超时之后照常释放令牌。引擎按缓存的读超时传入（上限为 `command-timeout`，M5 / M6），供不能降级回源、又要限制故障延迟的缓存使用：Redis 卡顿时，CoSec 的撤销检查每个请求最多多等它自己选的界限，而不是 2 s。参数随 M3d 的改名一起加，避免再改 SPI。后端级的快速失败（连续超时之后在冷却期内直接按“确定未执行”失败）涉及所有缓存的故障语义，8.x 另立 RFC。
- **摘要**（D1）：`Digest` 改为载荷的 SHA-256（32 字节），带算法标识（如 `algorithm`），下游不再以 `.sha1` 一类的名字持有它；由客户端计算（`Digest.of(Envelope)`、`Digest.ofPayload(bytes)`），用于条件读写，读取方拿到载荷时重算校验（§4.7）。条件回收（`reclaim`）比较 SPI 自己的内容指纹类型（K12）：它属于公开的 SPI，但与用户的 `Digest` 是两个类型，算法（脚本中的 SHA-1）与用途（判断读不懂的内容是否仍是读到的那一份）都不同。
- **keyspace 与段**（M4，架构会话 2026-11-03，由 §4.7 推出）：
  - 频道带上前缀（§4.7）之后，后端要分别知道前缀与 namespace：`store`、`subscribe`、`publish` 收 SPI 的 `Keyspace(keyPrefix, namespace)`。它校验 namespace 的字符集，给出存储 key 的前缀 `{keyPrefix}{namespace}:`；频道名由后端按 §4.7 的布局构造。namespace 按 keyspace 登记。
  - `Envelope.Scalar` 带写入标识与摘要：由引擎给出，存储按 §4.7 写成段，读取时原样返回；摘要的校验（K3）因此在引擎中，对所有后端相同。
  - 具体形状由 M4a–M4c 在 PR 中定稿（[todo.md](../todo.md) §2）。M4c / M4d 定下的形状：
    - `Digest(algorithm, bytes)`：`algorithm` 目前只有 `Digest.SHA_256`，`bytes` 为 32 字节，构造时校验两者；`Digest(bytes)` 即 SHA-256。`Digest.of(envelope)`、`Digest.ofPayload(bytes)` 由客户端计算，`toString()` 为 `SHA-256:<hex>`。
    - `Envelope.Scalar(bytes, version, writer, digest)`：`digest` 与 `writer` 一样由引擎给出（`@JvmOverloads`，默认 `null`），参与相等比较。存储在写入标量的操作中把它原样写成摘要段，读取（`read`、`readAll`、`readIfChanged`）原样返回段中的字节，从不校验或重算；校验（K3）在引擎中。
    - `VersionedL1Store.evictKeepingVersion(key, ttl, frame): L1Eviction`，结果为 `Evicted` / `Authoritative`（带摘要段的内容）/ `Unreadable(format)`；`FutureVersionedL1Store`、`ForwardingVersionedL1Store` 有同名方法。
    - `L1Fill` 不变；`advanced` 的含义见 §4.7“带版本的内容与 `fill`”。
- **命名**（Q2，2026-11-02 确认）：

  | 6.x | v8 |
  |-----|----|
  | `RemoteStore` | `L1Store`（避免与用户 API 的 `Store` 混淆） |
  | `RemoteRead` / `RemoteConditionalRead` / `RemoteConditionalWrite` / `RemoteVersionedWrite` | `L1Read` / `L1ConditionalRead` / `L1ConditionalWrite` / `L1VersionedWrite` |
  | 条件、版本、集合方法与 `CoherenceBackend.versionedWrites` | `ConditionalL1Store`、`VersionedL1Store`、`SetL1Store` 与它们的访问器 |
  | `AsyncRemoteStore` / `AsyncInvalidationChannel` | 删除；Java 用 `FutureL1Store` / `FutureInvalidationChannel` |
  | 无 | `ForwardingL1Store`、`ForwardingConditionalL1Store`、`ForwardingVersionedL1Store`、`ForwardingSetL1Store` |
  | `RedisRemoteStore`（internal） | `RedisL1Store` |
  | TCK 的 `supportsConditionalWrites` | `expectedCapabilities` |

  `CoherenceBackend`、`InvalidationChannel`、`Envelope`、`Token`、`Fill`、`Put`、`LeaseRequest`、`RefreshRequest`、`Digest`、`InvalidationFrame`、`NodeId` 不改名。

### 4.4 协议与 flight 层（V5）

**一份协程实现，三种变体**：协议的四条路径（单 key 未命中、批量、提前刷新、写入）各写一次，写成 `suspend` 函数。L2 填充与本节点淘汰（版本前进的 `fill`，§4.7）是显式的步骤。

| 变体 | L2 命中 | 未命中、写入 | 阻塞的 `CacheLoader` |
|------|---------|-------------|---------------------|
| 同步（`get`） | 普通函数，内联返回 | `runBlocking { … }`：协程在调用方线程自己的事件循环上运行 | 在调用方线程上直接调用：事务、`SecurityContext`、MDC 等 ThreadLocal 可用（不变量 6） |
| 异步（`async().get`） | 返回已完成的 future，不启动协程 | 运行时作用域中的 `future(start = UNDISPATCHED)`：在调用方线程上执行到第一次挂起，之后在运行时的调度器上继续；等待 L1 不占线程 | `withContext(阻塞调度器)`，默认每个调用一个虚拟线程（JDK 21） |
| 挂起（`suspending().get`） | 普通函数，内联返回 | 直接调用核心：元素取自调用方（去掉 `Job`），调度器取自运行时（见下） | 同异步变体 |

- `AsyncCacheLoader` 以 `await()` 调用，`SuspendingCacheLoader` 直接调用；二者在三种变体中都不切换调度器。
- **执行上下文**：核心经协程上下文中的元素（`ExecutionMode`）知道当前是哪种变体，据此决定阻塞 loader 是否切换调度器。
- **没有 `ExecutionMode` 的调用方**（K2、K6，M5）：进入核心时，元素取自调用方（去掉 `Job`），调度器取自运行时。
  - 挂起变体因此从不在 L1 客户端的完成线程上恢复。调用方的拦截器是 `Unconfined` 时（Spring WebFlux 的挂起处理器默认如此），核心剩下的步骤（解码、L2 填充）与调用方的后续代码不会跑在 Lettuce 的事件循环上；否则用户代码在那里再调用同步 API，就会卡住事件循环直到命令超时。
  - 共享加载继承调用方的 `ThreadContextElement`（MDC、追踪、`ReactorContext`），与 RFC 0004 的 loader 规则一致。
  - L2 命中不受影响，G3 不变。引擎用例：`Unconfined` 调用方从不在后端的完成线程上恢复；loader 看得到调用方的 `ThreadContextElement`。
- **同 key 重入的守卫**（K7，M5）：在现有守卫之上**增加**，不是替换。同步变体保留拥有线程的检测，异步变体在 `loading` 的同步调用段保留 `ThreadLocal`；挂起变体增加协程上下文元素（沿用 RFC 0004 的 `LoadingKeys`）。协程上下文元素穿不过 `runBlocking` 与 `future {}`，只换成它会让同步变体的同线程重入退化为等待 `load.timeout`。异步 loader 经 future 换线程之后的重入检测不到，以 `load.timeout` 为界，文档写明。
- **共享加载与取消**（不变量 17）：
  - 异步与挂起变体的 flight 加载在运行时自己的 `SupervisorJob` 作用域中执行；调用方（leader 或跟随者）只等待这次加载的 `Deferred`。调用方被取消只结束它自己的等待，不取消共享加载。
  - 同步变体的 leader 在调用方的 `runBlocking` 中内联执行，阻塞 loader 因此拿得到调用方线程的 ThreadLocal（K13）。线程被中断时 `runBlocking` 取消它的协程，等协程结束后抛出 `InterruptedException`：在途的 L1 命令先完成（§4.3），这一次加载随之取消，跟随者按合并规则重跑，至多多回源一次。同步跟随者被中断只结束它自己的等待。
  - 取得令牌之后的释放与填充一律在 `withContext(NonCancellable)` 中执行；运行时关闭时取消作用域，加载在下一次挂起点结束，令牌照常释放或由 `lease.ttl` 回收。`close` 在取消作用域之后，以 `commandTimeout` 为上限等待子协程结束，再关闭执行器；后端要晚于运行时关闭，否则在途的补发广播、释放与填充会失败（K17，现在修）。
  - loader 运行期间照常看得到取消与中断（协作式取消）；只有 L1 命令与令牌步骤不可取消。v4 在整个协议期间清除中断标志，会让 loader 收不到取消，v5 改正。
- **跟随者不占线程**：跟随者挂起在 `Deferred` 上。异步变体中一万个调用等待同一次加载，不创建任何线程。
- **等待**：lease 等待用 `delay`，回源许可用协程的 `Semaphore` 加 `withTimeout`，等待期间不占线程；取代 6.x 异步路径的定时器调度与许可等待队列（`cocache-timer`）。测试中它们走虚拟时间（§4.10）。
- **flight 层**：读取 `observed` → 注册 → 取戳 → 运行协议 → 比较 `stamp ≥ observed` → 至多重试一次 → 按合并规则逐 key 决定是否自行运行（§4.1）。另有三条：
  - 注册超过 `load.timeout` 的 flight 从表中注销，但不完成它（P16）。新调用方开始新的 flight；leader 迟到的结果只返回给它自己，写回仍受令牌与戳保护。注销早于发布，不破坏“先注销再发布”。
  - 同步 loader 本身无法被外部结束，文档要求它自带超时。
  - 由 Lincheck 检验（§4.10）。
- **中断与取消的异常**：跟随者的等待被取消或中断时，异步与挂起变体以 `CancellationException` 结束，同步变体抛出 `InterruptedException`（6.10.0 已实现同步跟随者可中断，#711）。“leader 被中断”按 leader 自己的状态判断，不按异常类型（K5，现在修）：只有 leader 的 `Job` 已取消，或同步变体的调用方线程被中断时，flight 才以非受检的 `SourceLoadInterruptedException` 失败，跟随者按合并规则自行运行。loader 自己抛出的 `CancellationException`（内部的 `withTimeout`、被 HTTP 客户端取消的 future）是普通的回源失败，与其它失败一样共享，按 RFC 0004 包装为 `SourceLoadCancelledException`；否则源头慢的时候，回源次数随并发放大。
- **`Private` 的读取不申请 lease**（P10）：未命中直接回源、不写任何一层，不让其它节点白等 `lease.wait`。
- **命中路径**留在引擎里手写，三种变体共用，G3 不受影响。
- **虚拟线程的钉住**：阻塞 loader 在虚拟线程上执行时，JDK 21–23 上若在 `synchronized` 块内阻塞会钉住载体线程（JEP 491 在 JDK 24 消除）。文档建议生产环境使用 JDK 24 及以上；需要时把阻塞调度器换成平台线程池。同步变体不受影响（loader 在调用方线程上）。
- **性质测试**：在 `kotlinx-coroutines-test` 的测试调度器上，以注入故障的内存后端在每一步之后注入失败、超时、取消与中断，对任意序列断言：
  - 取得的令牌最终被填充或释放；
  - 填充出示的令牌取得于读取之前；
  - L2 填充的戳取得于读取之前；
  - `Unknown` 与 `Private` 不进入 L2 与 L1；
  - 版本拒绝的刷新不进入 L2；
  - `Unfilled` 只以带标志的副本进入 L2；
  - 调用方取消不改变共享加载的结果。

**被否决的方案**：
- **v4 的同步实现 + 虚拟线程上的异步视图**：异步调用的跟随者占线程（需要专门优化），多节点仿真不能确定性重放（v4 的 Q17）。
- **v3 的 sans-IO 状态机 + 两个驱动**：可以确定性重放，但每条路径都要拆成“指令”与“恢复”两半；协程以直线代码得到同样的可重放性。
- **以 `CompletableFuture` 链为唯一实现**：即 6.x 异步路径的写法，失败路径上的令牌守恒最难写对。

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
| 中断不到达 L1 命令：在途的命令先完成或超时，取消在之后的第一个挂起点生效，令牌由核心释放（§4.3）。6.9.1 在存储中把被中断的等待按“结果未知”处理（附录 B.1） | P5 |
| 调用方的取消（协程取消、future 的 `cancel`、线程中断）只结束它自己的等待；共享加载在运行时作用域中继续，同步 leader 被中断时例外：取消这一次加载，跟随者至多多回源一次；取得令牌之后的步骤不可取消（§4.4） | C12、v4 审查、K13 |
| 结果未知的写入在任何时刻都可能生效。迟到无害的只有有序的写入：`fill`（令牌）、按版本写入（版本）、条件写入（摘要，但有 ABA） | C5、A12、P4 |
| 协程已被取消时（运行时关闭；M5 之后还有挂起变体中调用方的取消），异步变体的写入经 `withContext` 的慢路径以取消报告，已生效的写入与 `ConditionalPut` / `VersionedPut` 的结果都丢失：被取消的写入结果未知（I-E）。M5 评估把写入整体放进同调度器的 `NonCancellable`，只把 I/O 完成后的步骤留在 `Unconfined` 中。同步变体走快路径，不受影响 | K18 |
| 派生 `put` / `putAll` 结果未知时，只在缓存的失效失败模式生效值为 `RETRY` 时登记补偿失效（复用 pending）；为 `THROW` 时以 `outcomeUnknown` 抛给调用方。陈旧上界：以这次 `put` 的 `ttl` 为界；在同一条连接上没有发生重连时，补偿失效完成即结束 | C5 5a、P9、U8 |
| pending 期间，外部来源的缓存上不带 loader 的读取返回“未填充”（对调用方是 `Absent`），不写 L2；带 loader 的调用方照常回源 | U8 |
| 写入结果未知时，本节点不再过滤关于这个 key 的自身帧，直到同一条连接上之后发出的一条命令完成，再加一个投递余量。在此之前发生重连时无法确认，这时退回 `onReset` 或保持到 `localMaxAge`。残余上界是 `localMaxAge`，与事件丢失相同 | C5 5b、P12 |
| `Store`：`deleteIfMatch` 与 `putIfMatch` 的摘要比较在迟到执行下有 ABA。“当前摘要等于自己写入的摘要”只能证明已写入，不能证明未写入。8.0 起权威写入带写入标识（§4.7），按标识比较的条件写入在 8.x 加入，解决现值 ABA；不存在态的 ABA（迟到的“没有值才写”在 `delete` 之后复活数据）要靠墓碑，需要顺序的写入方用 `VersionedStore`（D2） | C5 5c–5e、P4、D2 |
| `SetStore` 的元素写入在结果未知时按“每个元素后写者胜”收敛，没有有序的重放方式 | C5 5e |
| 令牌守恒：`Read`、`ReadAll`、`Reclaim`、刷新的 `Read`、**`Fill` / `FillAll`** 在每个失败转移上都有释放（取得令牌之后的代码在 `try/finally` 中）。L1 命令除确定未执行外的任何失败，都由存储异步释放（§4.3）：释放走同一条连接、排在原命令之后，令牌不匹配时无害 | A.L1、C7、P13、K14 |
| 每个注册的 flight 在有限时间内完成，或在 `load.timeout` 之后被注销 | C7、P16 |
| 无条件的延迟写入只用于派生副本：有数据源的缓存，以及外部来源且失效失败模式为 `RETRY` 的缓存 | A.D2、U8 |
| 等待有上界：跟随者共用 `load.timeout`（默认 30 s）；只有跟随者的等待响应中断（§4.4） | A.S1、C12、P5 |
| 非原子广播时，下列重放同样广播（多余的广播只淘汰副本）：`Stale` 且当前版本等于请求的版本；冲突摘要等于本次载荷的摘要；`deleteIfMatch` 冲突且当前没有值；`evict` 遇到 `E(同一版本)` | A.R4、P17 |
| 提前刷新的触发率与命中率无关：没取得令牌的重新填充按 `now + min(max(lease.wait, 100 ms), 剩余寿命 / 2)` 退避（6.9 的实现，#698）；剩余寿命 ≤ 刷新窗口的副本不设刷新点 | A.D3、P18 |

### 4.7 线格式 2（V8）

**key 与频道的布局**（V13、D5、K9，M4 冻结）：

| 用途 | 8.x |
|------|-----|
| 存储 key | `{keyPrefix}{namespace}:{encodedKey}` |
| 失效频道 | `{keyPrefix}inv:{namespace}` |
| 探测 key / 频道（`pubsub` 模式） | `cocache:!probe:{a}` / `cocache:!probe:{b}` |
| 元数据 key | `{keyPrefix}!meta:{namespace}`，只预留，8.0 不写（D4） |

- **频道名带上 key 前缀**（v5）：前缀不同、namespace 相同的两个应用各自只收到自己的帧。
- **namespace 与内部 key**：
  - namespace 的字符集为 `[A-Za-z0-9._-]+`，禁止 `:`。同一前缀下 `a` 与 `a:b` 的重叠（A.L4）因此不再可能，跨应用时也一样（`runtime.create` 的校验看不到别的应用）。
  - 内部 key 放在 `{keyPrefix}!…`：`!` 不在 namespace 的字符集中，namespace 产生不了这个位置，默认前缀下 namespace `probe` 或 `meta` 的存储 key 不会与它们相撞。
  - 布局中没有版本标记（维护者决定，2026-11-03）：内容的版本只由头部的格式号表达。
  - namespace 含 `:` 的应用把前缀移到 `keyPrefix`（如 CoSec 的 spec 用 `keyPrefix("")`、把前缀放在 namespace 中）：存储 key 的字符串不变，频道变为 `{keyPrefix}inv:{namespace}`。`keyPrefix` 为空时启动告警：ACL 与按前缀的运维无法按前缀限定（S4）。
- **ACL**：默认前缀下 key 为 `~cocache:*`，频道为 `&cocache:inv:*`，`pubsub` 模式另加 `&cocache:!probe:*`。一般地，频道为 `&{keyPrefix}inv:*`；探测 key 与探测频道固定，不随前缀变化。`keyPrefix` 为空时按 namespace 写出（`~{namespace}:*`、`&inv:{namespace}`），`pubsub` 模式另加 `~cocache:!probe:*` 与 `&cocache:!probe:*`；漏配探测时原子广播的探测被拒绝，后端退回非原子广播并告警（S4）。
- **按存储探测实际频道**（K8，M4）：原子广播的探测发布到固定的探测频道，它不代表带前缀的实际频道，ACL 是否放行实际频道因前缀而异。漏配时每个写脚本都在 `PUBLISH` 处报错，而 Redis 脚本出错不回滚，之前的 `SET` / `DEL` 已经生效，其它节点陈旧到 `localMaxAge`。所以 `store(keyspace)` 创建时再向实际频道探测一次：以该前缀下的 key 为 `KEYS[1]`，发布一个零 key 的合法帧（监听者什么也不做）。ACL 拒绝时启动失败，不降级为非原子广播：这是配置错误，不是环境属性。运维文档的 ACL 段落同步修改。
- `ExternalInvalidator` 与运维文档按这个布局构造 key 与频道。

**头部**：

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0–35 | 36 | 核心头部：magic、格式版本（`2`）、状态、**标志位**、令牌、令牌签发时间 |
| 36 | 8 | 版本（bit 0），大端有符号 64 位 |
| 之后 | 32 | 载荷摘要（bit 1）：载荷的 SHA-256，与 `Digest` / `Digest.of` 相同，由写入方在客户端计算，读取方校验 |
| 之后 | 2 + n | 扩展区（bit 7）：2 字节总长 + 若干 TLV，读者可以整体跳过（见下） |
| 之后 | n | 载荷 |

- **全部内容都是格式 2**：lease 占位 `L`、负缓存 `M` 与派生缓存的普通值不带任何段，头部仍是 36 字节。
- **载荷偏移由标志位计算**：所有脚本与客户端共用一个函数，TCK 覆盖“只有版本段 / 只有摘要段 / 两者都有 / 带扩展区”。只有版本段时，载荷从字节 44 开始，Lua 中是 `string.sub(v, 45)`（P18）。
- **标志位分两类**：
  - bit 0–6 是必须理解的。未知的必须理解的位等同于更高格式：既不回收也不写回，有数据源的读取绕过 L1，权威缓存的读取 `FAIL`，条件写入与按版本写入拒绝，`READ` 不授予令牌。
  - bit 7 的扩展区只放“旧读者可以忽略、整体覆盖时丢失也无害”的信息（如写入标识）。
- **扩展区的 TLV**（D2，M4 冻结）：2 字节总长加若干 TLV（类型 1 B，长度 1–2 B）。未知类型跳过；整体覆盖时丢失，原地修改（字节 4–35）保留。
  - **编码**（M4b 定稿）：总长是其后 TLV 的字节数（大端无符号 16 位，不含它自己），扩展区共 2 + 总长字节。TLV 为 `[类型:1B][长度:1–2B][值]`：长度 0–127 用 1 字节；128–32767 用 2 字节，首字节为 `0x80 | (长度 >> 8)`、次字节为长度的低 8 位。写入方用最短的形式，读者两种都接受。类型 0 预留。
  - 读者逐个读 TLV，取每个类型的第一个；TLV 超出扩展区时忽略其余部分，不把值当作损坏（扩展区整体可以忽略）。载荷偏移只由总长决定，脚本从不解析 TLV。
  - TLV 类型 1 是**写入标识**（24 B，与 `Token` 同形：`NodeId` + 序号）。8.0 起 `Store` 与 `VersionedStore` 的每次标量写入都写它，每个值多 28 B。8.x 加入按标识比较的条件写入（§4.2）时，旧内容大都已经带标识，不需要过渡期；扩展区可以忽略，所以也不需要两阶段发布。
  - 它解决现值 ABA（迟到的 `putIfMatch(d)` / `deleteIfMatch(d)` 匹配上之后重新写入的同一内容），也能证明“已写入”；解决不了不存在态的 ABA，文档写明并指向 `VersionedStore`（§4.6）。
  - 不做必须理解的修订号段：它要给 `Store` 的删除加墓碑，删除语义与内存都要变，而且必须在 M4 冻结。
- **定稿时点**（P6）：
  - **M4 冻结**（线格式，8.0 发布之后不再改）：版本段、摘要段与 SHA-256；状态 `E`；扩展区的 TLV 帧格式与写入标识（D2）；失效帧的演进规则与预留的帧版本（D3）；集合的 `!f = '2'` 与 `!` 元数据字段（K10）；布局标记、namespace 字符集与内部 key 的位置（D5）。
  - **随 M4 实施**（脚本、探测与客户端行为，不属于格式，8.x 内可以增补）：带摘要段内容的脚本规则（D4）、`RECLAIM` 与格式号的规则（K15）、`FILL` / `RELEASE` 只读核心头部（K19）、读取方校验摘要（K3）、按存储探测实际频道（K8）。8.0 节点要按这些规则拒绝，所以它们必须在 8.0 中就有。

  之后再新增必须理解的段，一律在 8.x 内两阶段发布：先发布能读懂它的版本，一个 namespace 的全部节点都升级之后才写入（I-L）。

**失效帧**（K1、D3，M4 冻结）：
- 现行的帧只有版本字节，解码要求版本相等、拒绝尾随字节，通道遇到解码失败只告警并丢弃。照此，8.x 新增任何帧（namespace 重置），8.0 节点都会丢掉它，丢的就是一次失效（G2），只能两阶段发布。
- 规则：读不懂的帧（未知版本、未知类型、解码失败）一律按该频道的 `onReset` 处理：清 L2、递增全部戳，只会多淘汰、不会丢失效。TCK：未知帧版本触发 `onReset`。
- 帧版本 2 预留为“namespace 重置”（Q12）。重置帧本身在 8.x 加入：8.0 节点把它当作读不懂的帧，恰好做了正确的事，不需要两阶段发布。

**写入哪些段**：

- 派生缓存的普通值、`L`、没有版本的 `M`：不带段。
- 权威标量的值：`Store` 一律带摘要段（v5 去掉 v4 的 `digest = ON_DEMAND`，U20）与写入标识（扩展区，D2）。
- 带版本的内容：带版本段。`VersionedStore` 带版本段与写入标识，不带摘要段（它没有条件读写）。
- 状态 `E`：带版本段，没有载荷。
- 版本段中不得写 `NO_VERSION`（`Long.MIN_VALUE`）：客户端不发出，脚本拒绝（K15）。

**读不懂的内容**：
- 格式号 2 是唯一的格式。格式号为 0、1，或不是本协议写入的内容，都按读不懂处理：派生缓存条件回收（`RECLAIM`）后回源，没有数据源的缓存 `FAIL`。升级之后读到的 6.x 内容走的就是这条路，不需要兼容代码（V13）。
- 格式号大于 2，或带未知的必须理解的位的内容，按更高格式处理（见“标志位”）：既不回收也不写回。
- 删除 `foreign-format` 配置与 `VERSION_MARK` 的识别。

**摘要算法**（v5；v6 改写威胁模型，D1）：权威数据的摘要为 **SHA-256**（32 字节），`Digest` 带算法标识。
- 摘要由写入方在客户端计算并写入头部，条件读写的脚本只比较头部的字节，Lua 不需要计算它。所以算法不受 Redis 的 Lua 只有 `redis.sha1hex` 的限制。
- **威胁模型**（K3）：SHA-256 防的是“攻击者控制经合法写入方写入的内容”：借 SHA-1 的选择前缀碰撞，让 `getIfChanged` 对已变化的内容回答 `Unchanged`；权威数据的使用者（CoSec 的策略快照）是安全产品。它不防“攻击者能写 Redis”：能写的人直接把旧头部的摘要拷进新值就行，不需要碰撞，这要靠 ACL。v5 的说法（“能写入内容的攻击者”）混淆了两者。
- **读取方校验**（K3）：客户端在拿到载荷时（`getIfChanged` 的 `Value`、`Store` 的 L1 读取）重算 SHA-256 并与头部比较，不一致按损坏 `FAIL`；`Store` 读到不带摘要段的值同样 `FAIL`。否则写入方或外部工具的缺陷留下的不一致，在永不过期的 `Store` 上会让 `getIfChanged` 对已知摘要一直回答 `Unchanged`。成本只在 L1 读取上，不在 L2 命中上。校验是客户端行为，不改格式，但在 M4 写进规范与 TCK。
- 保留 SHA-256、不退回 SHA-1 的理由（D1）：M4 冻结之后，摘要算法会在 8.x 的线格式中固定多年。
- 只有条件回收仍在脚本中计算指纹：它比较派生缓存的“读不懂的内容是否仍是读到的那一份”，与安全无关，继续用 SHA-1，类型是 SPI 自己的内容指纹（§4.3）。

**状态 `E`（保留版本栅栏的失效）**（P1）：

| 脚本 | 对 `E(v)` 的处理 |
|------|-----------------|
| `EVICT_KEEPING_VERSION` | 当前内容带版本段（`V` / `M` / `E`）：整体写为 `E(当前版本)`，令牌为零，到期时间由引擎传入（默认 `tombstoneTtl`），广播。当前内容既没有版本也没有摘要段：等同 `DEL` + 广播。带摘要段的内容（权威标量，D4）或读不懂的内容：拒绝 |
| `READ`（申请 lease） | 令牌为零或签发超过 `lease.ttl`：以 `SETRANGE 4` 写入令牌与 `TIME`（保留状态、版本段与到期时间），返回 `Miss(leaseGranted)`。否则返回 `Leased`，剩余时间为 `lease.ttl` 减去已签发时长 |
| `READ`（不申请 lease） | `Miss`：不是 `Absent`，也不是 `Leased` |
| `FILL` | 令牌匹配且 `fill.version ≥ v`：写入，并广播（淘汰其它节点降级填充的旧副本）。否则拒绝：脚本内清零令牌，返回 v |
| `RELEASE` | 令牌匹配：清零令牌，保留 `E` 与到期时间；**从不 `DEL`** |
| `RECLAIM` | 拒绝 |
| `PUT_IF_NEWER` / 墓碑 | 给出的版本 w > v 时写入；w = v 时不写入但广播（F1，见逐脚本表下的说明），返回 `Stale(v)`；w < v 时 `Stale(v)`，不广播 |
| `PUT` / `PUT_ALL` / `INVALIDATE` / `purge` | 整体覆盖或删除（丢掉版本，见 I-K 的入口清单） |
| 条件脚本、集合脚本 | 读不懂，拒绝；不是“没有值” |

为什么不复用 `L`：现行的每个脚本都对 `L` 有特判（`READ` 直接返回 `Leased` 且不按签发时间抢占，`RELEASE` 见 `L` 即 `DEL`，`versioned_state` 与 `conditional_state` 把它当作没有值），复用就要逐个推翻它们，漏掉一处就是静默的栅栏丢失。`E` 也给“回收解码失败的带版本内容”留了去处（转成 `E(v)`），但 8.0 不做：转换后的令牌与到期时间都要另行定义（`RECLAIM` 只收 lease 寿命，`E` 需要 `tombstoneTtl`），8.0 的 `RECLAIM` 遇到带版本的内容一律拒绝。转为 `E(v)` 是脚本行为、不是格式，可以在 8.x 内加入（K15）。

**带版本的内容与 `fill`**（C3、P2、P11）：

- 当前内容带版本时：
  - 填充的版本小于当前版本，或为 `NO_VERSION`：拒绝。脚本内清零匹配的令牌，返回当前版本，省一次 `RELEASE` 往返。
  - 相等：写入，相当于续期，不广播。
  - 更大：写入并广播。
- 版本拒绝之后，客户端：
  - 刷新路径：不写 L2，保留现有副本，刷新点按退避。否则回源得到的更旧的值会替换掉 L2 中的新值。
  - 未命中路径（lease 在 `E` 上）：回源值是唯一的答案，返回它，以降级限龄写入 L2，并计入 `cocache.fill.rejections`。文档写明：在 loader 追上之前，`evict` 之后读到的是 loader 的版本。
- 版本前进的 `fill` 是一次写入：`FILL` 返回“已前进”（`L1Fill.Filled(advanced = true)`，§4.3）之后，本节点递增戳并淘汰本地副本，不填充 L2，下一次命中多一次 L1 读取。它的帧在本节点被过滤，没有这一步时，绕过 SingleFlight 的同节点读取者可以把旧版本写回 L2。
- 6.8 的结构性保证（“带版本的内容不持有令牌”）由 I-K 取代；带版本的内容也能持有刷新令牌，`VersionedCache` 支持提前刷新。
- 读到未知标志位或 `E` 的副本不设刷新点（P14）。
- **M4c / M4d 定稿的细节**（脚本与客户端行为，不改格式）：
  - **哪些写入带摘要段**：M5 之前没有数据源的缓存的不带版本的标量写入（`put`、`putAll`、调用方给出 loader 时的填充、`putIfMatch`），没有数据源的 `VersionedCache` 的不带版本的写入也是；带版本的写入（`putIfNewer`、带版本的回源）不带。读取方校验因此只要求不带版本的值带摘要段。
  - **条件脚本读 78 字节**：`READ_IF_CHANGED` / `PUT_IF_MATCH` 以 `GETRANGE key 0 77`（最长的头部：核心头部、版本段、摘要段与扩展区的总长字段）加 `STRLEN` 读头部，而不是 `GETRANGE 0 67`：载荷偏移的检查（段超出值的长度时是格式 0）与读整个值的脚本完全相同，仍是 O(1)。摘要变了时 `READ_IF_CHANGED` 回复整个值，客户端按载荷偏移解析出摘要段与写入标识。
  - **`FILL` 读 44 字节**：`GETRANGE key 0 43`（核心头部与版本段）；`RELEASE` 仍是 `GETRANGE key 0 35`。最小 ACL 因此要加 `+strlen`。
  - **`advanced` 覆盖 `E`**：版本更大的填充，以及 `E` 上版本不小于 `E` 的填充（两者都广播）都返回 `Filled(advanced = true)`，引擎都按写入处理：非原子广播的后端上由引擎发布，它看不到“填充了 `E`”，所以由 `advanced` 表达。版本相等的续期为 `false`。
  - **版本规则的帧**：`fill` 没有帧参数，存储自己构造：来源为 `Fill.token` 的 `NodeId`（令牌由引擎签发，就是引擎的节点，本节点因此过滤它），只含 `Fill.key`。只有带版本的填充可能广播，Redis 只为它们发送频道与帧。
  - **`E` 上 lease 的抢占**按申请者的 lease 寿命判断（与刷新令牌相同；同一个缓存的节点用同样的 lease 策略），`Leased` 的剩余时间为申请者的 lease 寿命减去已签发时长。
  - **`E` 的寿命**：M6 加入 `tombstone-ttl` 之前，引擎传入与 `invalidateIfNewer` 的墓碑相同的寿命（策略 `ttl` 加抖动）。
  - **运维的按 key 删除**：带版本的缓存用 `evictKeepingVersion`，不进入失效重试（重试的 `DEL` 会丢掉版本），失败时抛出。遇到带摘要段的值时，有数据源的缓存拒绝（计入 `cocache.type.mismatch`），没有数据源的缓存改走正常的失效路径：那是它自己 `put` 的值，没有版本可保留。
  - **不设刷新点的副本**（P14）还包括：lease 在 `E` 上、版本被拒绝的降级副本，以及有数据源的缓存读到的带摘要段的值（`READ` 从不为它授予刷新令牌）。刷新路径的版本拒绝以取得令牌时读到的值重新填充 L2，刷新点按没取得令牌时的退避。
  - **类型不符**（D4）：有数据源的缓存读到带摘要段的值时照常读取，计入 `cocache.type.mismatch`，每个缓存第一次告警，之后只记录调试日志。

**逐个脚本的规则**（C8、P7、P18）：

| 脚本 | 不带段 | 版本段 | 摘要段 | 未知的必须理解的位 |
|------|--------|--------|--------|-------------------|
| `READ` | 照旧 | 照常授予刷新令牌；`E` 见上表 | **不授予 lease 或刷新令牌**（D4）；权威缓存本来就不申请 lease（§4.1） | 返回格式与头部，不授予令牌 |
| `FILL` | 令牌匹配即写入 | 令牌匹配 + 版本规则 | —（权威缓存没有 `fill`，`READ` 也不授予令牌） | 拒绝 |
| `RELEASE` | 照旧（只触及字节 4–35） | 照旧；`E` 不 `DEL` | — | 不匹配 |
| `RECLAIM` | 现算载荷的 SHA-1 指纹后条件回收 | 拒绝（转为 `E(v)` 留给 8.x，K15） | **拒绝**（D4） | 拒绝 |
| `PUT` / `PUT_ALL` / `INVALIDATE` | 整体写入或删除；权威缓存写摘要段与写入标识 | 同左 | 同左 | 同左（整体覆盖不依赖旧内容） |
| `PUT_IF_NEWER` / `EVICT_KEEPING_VERSION` | 当前版本为 `NO_VERSION` | 从版本段读版本；`PUT_IF_NEWER` 遇到相等的版本不写入但广播（F1） | `PUT_IF_NEWER`：当前版本为 `NO_VERSION`；`EVICT_KEEPING_VERSION`：**拒绝**（D4） | 拒绝 |
| `READ_IF_CHANGED` / `PUT_IF_MATCH` / `DELETE_IF_MATCH` | `L` / `M` / 不存在按“没有值”；有载荷却没有摘要段（不是权威缓存写入的值）拒绝 | **拒绝**（带版本的内容不接受条件写入） | 先判断 `TYPE`，再以 `GETRANGE key 0 67` 只读头部比较 | 拒绝 |
| 集合脚本 | `!f = '2'`；`!f` 为其它值、`!m` 中有未知位：读不懂，拒绝（K10） | — | — | — |

- **相等的版本广播**（TLA+ 模型 F1，架构会话 2026-11-03 决定 a）：`PUT_IF_NEWER`（含墓碑）遇到与给出的版本相等的当前版本时不写入、返回 `Stale(v)`，但在同一个脚本中广播帧。反例：节点 2 的 L2 中是 v0（L1 中的 key 到期或被驱逐，或者是等待 lease 超时后的降级副本）；节点 1 在不存在的 key 上取得 lease，回源得到 v1 并填充——在 `L` 上的填充不广播；写入方随后的 `putIfNewer(1)` 返回 `Stale(1)`。原子广播时 `Stale` 若不广播，节点 2 陈旧到 `localMaxAge`；非原子广播的引擎路径本来就在 `Stale.current == version` 时发布，两种模式因此不一致。当前版本严格更大时照常静默：持有那个版本的写入方自己的 `putIfNewer` 会遇到相等的情形。没有选择让 `L` 上带版本的填充广播（方案 b）：它会让每一次带版本的未命中都多一次 Pub/Sub 扇出。
- **客户端先看格式号、再看状态**（P1、P18）。格式号 ≥ 2 的内容中出现未知状态时，报告为更高格式，不能报成格式 0。否则派生缓存会把它当作读不懂的内容而回收。
- **授予令牌之前先检查格式号**（K15）：`READ` 只对格式 2、没有未知的必须理解的位、没有摘要段的内容授予 lease 或刷新令牌。6.x 的 `READ` 授予刷新令牌时不看格式字节。
- **带摘要段的内容**（D4、K4）：摘要段本身就是权威标量的标记。同一 namespace 上误声明成带 loader 的 `Cache` 的应用，只要解码失败就会回收它（删除唯一副本），或凭刷新令牌以不带摘要段的派生值覆盖它；上表的三处“拒绝”与“不授予”堵住这两条路。派生读者读到摘要段时计数（`cocache.type.mismatch`）并告警。`Store` 从不回收、不申请令牌，这些规则对它没有代价。这是 Q14 的零成本部分；元数据 key 不进 8.0，只预留位置（见布局表）。`VersionedCache` 与 `VersionedStore` 都只有版本段，这条规则分不出它们。
- **`FILL` / `RELEASE` 只读核心头部**（K19）：格式 2 的 `FILL` 与 `RELEASE` 以 `GETRANGE key 0 35` 读魔数、格式、状态、标志位与令牌，不再为比较 24 字节的令牌 `GET` 整个值（带刷新令牌的 8 MB 值每次写回与释放都会整个读进 Lua），与条件脚本的 O(1) 头部读取一致。
- **保留到期时间的原地修改**（K15）：授予刷新令牌、`READ` 在 `E` 上以 `SETRANGE` 授予 lease、`RELEASE` 清零令牌、被拒绝的 `FILL` 在脚本内清零令牌，都只触及字节 4–35，保留原到期时间。这是“每次 L1 状态转换显式重设到期时间”的例外（§6）。
- **条件读取 O(1)**：只读头部是安全的。原地修改（授予刷新令牌、`release`）只触及字节 4–35，整体写入重写头部，所以头部中的摘要始终与载荷一致。
- **集合**（K10，M4 冻结）：Hash 的 `!f` 为 `'2'`，8.x 的集合脚本把 `!f = 1` 当作读不懂（与标量一致）。成员都以 `+` 开头，以 `!` 开头的字段预留给元数据：`!m` 是必须理解的位图（缺省为 0，未知的位等同于更高格式），其余 `!` 字段读者可以忽略。8.x 内给集合加信息因此不必改格式号，而 8.x 只有格式 2。
- **与现行规范**：architecture.md §3.3 改写为格式 2 与布局标记；§14 #9“线格式在一个大版本内冻结”保留，并加上 8.x 内两阶段演进的规则（I-L）。

### 4.8 配置（V10）

**分层**：

| 层（由低到高） | 作用 |
|------|------|
| 内置默认值 | 按类型与声明的数据来源（§4.1） |
| `cocache.defaults.cache.*` / `cocache.defaults.store.*` | 全局默认值，**低于**声明：只作用于声明中没有写出的字段 |
| 声明（注解或 spec DSL） | |
| `CacheSpecCustomizer` | 只能修改策略字段与不决定类型的字段（U16） |
| `cocache.overrides.cache.*` / `cocache.overrides.store.*` | 全局覆盖，**高于**声明，只收紧（U10、A14）：陈旧界限取 `min(下层的值, override)`，有严格序的模式字段取两者中更严的值；其余字段不进 overrides（见下表） |
| `cocache.caches.[name].*` | 按缓存 |

**字段 × 层**（S8、S9、S10）：A14 要求全局手段只能收紧。取 min 只对陈旧界限是收紧；等待界限调小会让更多节点绕过 lease 自行回源，在数据源变慢的事故中放大负载；带版本类型的 `ttl` 同时是版本栅栏的寿命，调小它会让重复投递的旧事件更早写回旧状态；模式字段直接设定会放宽更严的声明（CoSec 的 `expiringFailClosed` 依赖 `read-failure: throw`）。

| 字段 | 类别 | `defaults` | `overrides` | `caches.[name]` |
|------|------|-----------|-------------|-----------------|
| `ttl`（`Cache`） | 陈旧界限 | 设定 | 取 min，并钳制按调用给出的寿命（`ttlOf`、`put(k, v, ttl)`），被钳制时告警 | 设定 |
| `local-max-age`、`degraded-local-max-age`、`absent-ttl` | 陈旧界限 | 设定 | 取 min | 设定 |
| `read-failure` | 模式，`throw` 严于 `degrade` | 设定 | 取更严的值 | 设定 |
| `fill-in-transaction` | 模式，`private` 严于 `registered-keys` 严于 `allow` | 设定 | 取更严的值 | 设定 |
| `invalidate-failure` | 模式，没有严格序 | 设定 | 不允许 | 设定 |
| `lease-wait`、`load-timeout`、`load-acquire-timeout`、读超时（D6） | 等待界限 | 设定 | 不允许 | 设定 |
| `ttl`、`tombstone-ttl`（`VersionedCache`、`VersionedStore`） | 版本栅栏的寿命 | 设定 | 不允许 | 设定 |
| `ttl`（`Store`、`SetStore`） | 寿命即语义 | 不允许 | 不允许 | 设定 |
| `ttl-jitter`、`refresh-window`、`lease-ttl`、`load-max-concurrent`、`local-maximum-size` | 其余 | 设定 | 不允许 | 设定 |
| `unknown-read` | 模式（§4.1） | 不允许 | 不允许 | 设定 |

- 表中“不允许”的字段出现在全局层时，与不适用的字段一样跳过并记录（见“适用性”）。
- **哨兵值显式**（S8）：`ttl: forever`、`refresh-window: off`、`load-max-concurrent: unlimited`，比较时按 +∞ 或“关闭”的语义参与，不再有 6.x 中 `0` 表示不限的隐式哨兵。
- **字段三态**（U11）：未写出 / 哨兵值 / 值。DSL 提供 `ttl = forever()`、`refreshWindow = off()`。用整体 `CachePolicy` 写的 spec 视为全部字段都已写出，启动日志提示“`defaults` 对此缓存无效”。每种 spec 有自己的默认策略常量（如 `StorePolicy.DEFAULT`，`ttl` 为 `forever`）：库常从默认策略派生自己的策略，共用一个常量会把派生缓存的 1 h `ttl` 带进 `Store`，即误分类表中最严重的一条（S23）。
- **适用性**（U11）：
  - 全局层（`defaults`、`overrides`）按每个缓存的类型与数据来源，跳过不适用的字段，启动日志与端点列出被跳过的字段；按缓存的层出现不适用的键时启动失败。
  - 附一张“键 × 类型 × 数据来源”的适用性表。其中：`defaults.store.ttl` 与 `overrides.store.ttl` 都不允许；`absent-ttl` 适用于 `Store`；`unknown-read` 只能出现在声明与 `caches.[name]` 中；`read-failure` 适用于有数据源与外部来源的缓存，作用于带 loader 的读取（S11）。
- **校验连锁**（U10）：全局层引起的冲突（例如全局调低的 `local-max-age` 小于某个声明的 `refreshWindow`），夹紧依赖字段（缩短或关闭刷新窗口）并告警；只有按缓存的层引起的冲突才启动失败。
- **生效值与来源**：启动日志与 `GET /actuator/cocache/{name}` 输出每个字段的生效值，及其来源层（含“被 overrides 收紧”“被夹紧”“被锁定”）。
- **`key-prefix`** 移到 `cocache.key-prefix`（S17），不参与分层，仍是“配置优先于声明”。被 `lock` 锁定的除外。留在 `cocache.defaults` 下时，它的语义会与同一前缀下的其它键（低于声明）正好相反。
- **`cocache.caches` 中的未知名称**：默认启动失败；`cocache.caches-validation: warn` 时只告警。

**单位**：

- 配置中的时长必须带单位（`500ms`、`5s`、`2m`、`PT2M`），不带单位的值启动失败，错误信息给出 6.x 的解释与等价写法。`refresh-window` 的“关闭”写作 `0s` 或 `off`。
- **注解中的时长仍按秒**（`Long`），文档写明“注解一律按秒”（U18）。A.L8 的不一致只在配置文件中，注解从来没有歧义；改成字符串会失去编译期检查，也换不来什么。

**库的装配入口与锁定**（M9、U2）：

- `CoCacheFactory.create(spec)` 与 `CoCacheFactory.create(type, spec)`（接口类型，取代 `CacheProxies.create`）：与 starter 同样分层，计入 `cocache.caches` 的名称校验，注册到端点与指标。
- `lock(...)` 可以锁定策略字段，也可以锁定存储布局：namespace、keyPrefix、keyEncoder、codec、keyFilter、ttlOf、数据来源。锁定了 keyPrefix 的 spec 不受 `key-prefix` 配置影响（CoSec 的撤销表若随应用的 `key-prefix` 改变存储 key，已撤销的 token 会重新可用）。
- `constrain(field) { … }` 在合并之后、校验之前执行约束；`require(loader == null)` 一类的检查同样在此执行。CoSec 的 `expiringFailClosed` 与 `pinPolicySnapshot` 由它们表达。约束的值域显式包含 `Forever`（如 `sealed Ttl`），示例写作 `constrain(ttl) { it.finiteOr(1.minutes) }`：三态之下 `it ?: 1.minutes` 会让 `forever` 穿过约束，“条目一定过期”的意图落空（S12）。
- 全局层遇到锁定字段时**跳过**，并在启动日志与端点中标注“被 <库> 锁定”；只有 `cocache.caches.[name]` 明确指向锁定字段时才启动失败。`CacheSpecCustomizer` 写锁定字段时抛出（S12）：CoSec 现在靠执行顺序压过 customizer，v8 由规则保证。
- 破窗开关：`cocache.caches.[name].unlock: [fields]`。启动时告警，并在端点中标注，供事故中有意识地覆盖。
- **未分层的缓存**（S20）：不经 `CoCacheFactory` 创建的缓存（如直接 `runtime.create`）不受 `defaults` / `overrides` / `caches.[name]` 影响，端点与启动日志标注“未分层”，否则运维在事故中写下的 overrides 对它静默不生效。库应经 `CoCacheFactory` 创建缓存、用 `lock` 固定策略，而不是绕开装配入口。

**6.x 的键**（维护者决定，2026-11-03，取代 v4 与 S17 的检测）：不提供别名、语义映射或检测，8.x 不认识的键按 Spring Boot 的默认规则忽略。6.x 与 8.x 的键与语义差异只写进 changelog 的破坏性变更表与升级说明（其中 `cocache.defaults.<field>` 的语义从高于声明变为低于声明，要特别写明）。

- 新增的键：`cocache.key-prefix`、`cocache.redis.assume-maxmemory-policy`、`management.endpoint.cocache.allow-store-delete`、`management.endpoint.cocache.allow-purge`、`fill-in-transaction`、`invalidate-fills-on-rollback`（§4.9）、按缓存的读超时（D6，键名随 M6 定）。

### 4.9 Spring（V11）

**写事务内的回源**（C2、M7、U3），两个相互独立的机制：

1. **回滚时失效事务内的填充**（`invalidate-fills-on-rollback`，默认开启）：
   - 在非只读的实际事务内，loader 产生、进入任何共享层（L1、L2、flight）的 key，记入事务的同步（与 `TransactionalInvalidator` 的 `Pending` 同一机制）。只记录凭 lease 写进 L1 的 key 会漏掉只进本节点 L2 的降级填充（L1 读失败、lease 等待超时、失效重试中、`E` 上的版本拒绝），回滚之后它们留到 `degradedLocalMaxAge`（S15）。
   - 事务回滚或提交结果未知时，在 `afterCompletion` 中失效这些 key；回滚到 savepoint 时，在 `savepointRollback`（Spring 6.2 起）中失效到目前为止记录的全部 key。外层同步看不到回滚到 savepoint：Spring 不为它调用 `afterCompletion`，外层提交时以 `STATUS_COMMITTED` 调用，NESTED 段内读穿透写进 L1 的未提交值会一直留下（S6，main 与 6.x 现在修）。提交时什么也不做，因为读到的就是本事务提交的数据。
   - leader 处于非只读的实际事务中时，结果不与事务外的跟随者共享，跟随者按合并规则自行回源：否则其它事务经 flight 读到未提交的值，机制 1 撤不回（S15）。
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
   - **事务上下文在调用入口捕获一次**（S7）：两个机制都依赖调用方线程上的事务同步（`TransactionSynchronizationManager` 是 ThreadLocal）。同步变体在调用入口（调用方线程）读取是否处于非只读的实际事务、登记表的引用，作为协程上下文元素交给门控与 `FillObserver`，不在填充时读 ThreadLocal。异步与挂起变体一律不门控、不记录：它们的 loader 不在事务内。调用方线程上即使有线程绑定的事务（`@Transactional` 方法中调用 `async().get`，或事务内对 `CompletableFuture` 方法的 `@Cacheable(sync = true)`，Spring 走 `retrieve`），也不归它们管；否则门控放在 flight 注册时看得到事务，放在填充时已经换了线程，该记录的没有记录。
   - **“已登记”按 (namespace, 编码后的 key) 匹配**（S24）：同一 namespace 可以经不同的 `Cache` 对象访问（Spring 适配器持有的缓存、接口代理、`JoinCache` 的一侧），按对象身份匹配会漏判。

**starter 的事务装饰器**（U3）：starter 不再使用 Spring 的 `TransactionAwareCacheManagerProxy`，改用 CoCache 自己的事务感知装饰器：
- `evict` 登记到 `TransactionalInvalidator`：提交与回滚都失效，并进入 `registered-keys` 的门控；
- `evictIfPresent`（`@CacheEvict(beforeInvocation = true)`）在事务中**立即失效，同时登记**，提交与回滚时各再失效一次，并进入门控。这仍满足 Spring“立即”的契约，多余的失效无害。只立即失效时，提交之前并发的读穿透会把已提交的旧值经 lease 填回 L1，提交之后再没有失效（S5，main 与 6.x 现在修）；
- `put` 仍推迟到提交之后；`afterCommit` 中 put 与 evict 的异常交给 `CacheErrorHandler`，与非事务路径一致，而不是在提交之后抛给以为操作失败、准备重试的调用方（S18）；
- `invalidate` 按 Spring 的契约立即执行。

这补上了 6.9.0 changelog 的“Not covered”。

**`TransactionalInvalidator`**：在 `afterCompletion` 中执行，提交与回滚都失效已登记的 key，迟到的登记立即失效，逐缓存执行、失败聚合（6.9.0 已实现，#697）。参数类型只接受 `Cache`（不含 `VersionedCache`）。

**Spring Cache**：

- **`Unknown` 的映射**（U5）：`CoCacheSpringCache` 的 `get` 与 `retrieve` 一致处理。`Unknown` 以 `CacheReadException` 失败，交给 `CacheErrorHandler`（Spring 7 对失败的 `retrieve` future 与响应式路径同样调用 `handleCacheGetError`）；`unknownRead = ABSENT` 时视为未命中。6.9 的 `retrieve` 用 `as? Lookup.Present`，在 v8 中会把 `Unknown` 静默当作未命中。同步、异步、响应式三条路径各加用例。
- **`Unknown` 的不对称**（S18）：不带 `sync` 的 `@Cacheable` 经 `get(key)` 读到 `Unknown` 时抛出 `CacheReadException`，Spring 默认的 `SimpleCacheErrorHandler` 重新抛出，Redis 故障期间这类调用全部失败；同一个缓存上带 `sync = true` 的调用却降级回源。`unknownRead = ABSENT` 也换不来可用性：随后的 `put` 在 L1 故障时同样失败。文档写明这一点；starter 提供一个可选的 `CacheErrorHandler`，只对 CoCache 的缓存放行读写失败（被注解的方法本身就是数据源，派生副本可以降级，A7）。
- **缓存通知与事务通知的嵌套**（S19）：`@EnableCaching` 与 `@EnableTransactionManagement` 的 order 默认都是 `LOWEST_PRECEDENCE`，同一个方法上谁在外层取决于 advisor 的注册顺序。本节的描述在事务通知位于外层时成立。缓存通知在外层时：`@CacheEvict` 在提交之后执行，不登记、不门控；`sync = true` 的 loader 在方法自己开启的事务中运行，填充发生在提交之后。响应式方法上的 `@CacheEvict` 在 `doOnSuccess` 中执行，事务位于外层时早于提交。文档建议显式设置 order，让事务通知在外层；两者 order 相同时，`SpringCacheSyncCheck` 告警。
- `SpringCacheSyncCheck` 对指向 `Store`、`SetStore`、`VersionedCache` 的缓存操作启动失败。
- `@CacheEvict(allEntries = true)` 指向 CoCache 缓存时，8.0 保持 `clearLocal` 并告警（U19）。namespace 重置在 8.x 加入（Q12，帧版本 2 已在 M4 预留，§4.7）之后，改为全集群清空。
- 响应式与协程事务：见 Q5。
- `keyExpression` 改用编译模式的 SpEL（待 JMH）。

**运维入口**（M13、U19）：

- actuator 端点 `cocache` 默认只读，写操作经 `management.endpoint.cocache.access` 开放；`Store` / `SetStore` 的删除需要 `allow-store-delete`，`VersionedCache` 的 `purge` 需要 `allow-purge`，按 key 删除对 `VersionedCache` 映射到 `evict`。运维文档的示例改为 `curl -fsS`。
- 新增只广播失效帧的操作（对所有类型都安全）。
- 未知缓存名的 `DELETE` 返回 404（6.10.0 已实现，#707）。
- `ExternalInvalidator` 增加只广播的方法；文档写明它的 `invalidate` 作用于权威缓存的 namespace 时会删除唯一副本。
- “引擎先淘汰本地副本，再通知外部监听者”成为 `InvalidationChannel` 的契约（CoSec 依赖它）。
- `InvalidationListener` 收到的是去掉 `{keyPrefix}{namespace}:` 之后的编码 key（与引擎内部剥前缀的规则一致），不是存储 key（S4，M5）。否则下游按 `"$namespace:"` 前缀反解存储 key 的代码（CoSec 的 `snapshotKeyOf`）在 v8 中会静默丢掉每一条通知，编译发现不了。

### 4.10 检验（V9）

- **Lincheck**：对“flight 层 + L2 + 失效戳 + 内存存储”做模型检验，操作可以是 `suspend` 函数（Lincheck 支持协程）。Lincheck 是新的测试依赖，已经维护者批准（Q18：先做可行性实验 #726，可行后加入；CI 自 2026-11-03 起以非阻塞的 `Lincheck` 作业运行，#738）。
  - 操作：`get`、`get(key, loader)`（与 `get` 交替，P3）、`getAll`（合并到 `Private` 的单 key flight，P10）、`invalidate`、`put`、`onInvalidated`、`onReset`、`clearLocal`，以及调用方取消。
  - 顺序规格：“带版本的数据源 + 读己之写”与“带版本的写入方 + 读快照的 loader”。
  - 可行性先做小实验：真实的 Caffeine 内部状态多，模型检验可能爆炸。不可行时，flight 层用 `ConcurrentHashMap` 替身检验，Caffeine 的 `compute` 与 `clear` 语义另以定向用例覆盖（C4）。
- **TLA+**：`docs/spec/CoCache.tla` 建模以下内容：
  - N 个节点；一个 key 的 L1 状态机（`ABSENT` / `LEASED` / `VALUE` / `MISSING` / `E`，令牌，版本）。
  - 每个节点的 L2 与失效戳，绕过 SingleFlight 的读取者（P11）。
  - 可丢失、乱序、重复的失效通道，写入方节点对自身帧的过滤。
  - 带提交历史的数据源。
  - 失败与重放：超时、取消与中断后仍执行的命令；连接替换（被排空的命令可能晚于新连接上的命令执行，P8、P9、P12）；版本拒绝之后的 L2 处理（P2）；持有令牌崩溃。

  性质：G2 的 L1 与 L2 条款、按类型陈述的版本单调（I-K）、令牌最终释放。CI 中以小规模运行 TLC。
- **确定性多节点仿真**：多个运行时共享一个注入故障的挂起式内存后端，全部协程运行在 `kotlinx-coroutines-test` 的单线程测试调度器上，lease 等待、超时与时钟都走虚拟时间。带种子的调度器选择交错，注入事件丢失与乱序、超时与取消后仍执行的命令、leader 被中断、持有令牌崩溃，以数据源的提交历史为裁判检查 G1 的上界与 G2。失败时以种子逐步重放（取代 v4 的 Q17）。
- **TCK 只有一套**：`BackendProtocolSpec`、`EngineSpec`、`SetEngineSpec`、`MultiNodeSpec`、`FaultSpec` 改为 `suspend` 的 `L1Store` 形状，并覆盖 `FutureL1Store` 的适配；删除 `Async*` 规范。三种变体的语义（L2 命中不启动协程、loader 执行的线程、取消不传播、`runBlocking` 中的中断、挂起变体不在后端的完成线程上恢复）由引擎级用例覆盖。手写交错继续作为回归用例。第三轮新增的协议用例：未知的帧版本触发 `onReset`（K1）、带摘要段内容的脚本规则（D4）、读取方的摘要校验（K3）、除确定未执行外的失败都释放令牌（K14，`FaultSpec`）。

### 4.11 文档（V12）

- architecture.md 只写现行行为，版本叙述移到 changelog 或 RFC；顺带修正 §6.3 关于启动检查的说法（A.L10）。
- 不变量以稳定编号 `I-n` 列出，代码注释与测试引用编号。
- 审查与对抗验证的记录放在 RFC 的附录与 §12 中。

## 5. 删除清单（v8）

- `Cache`、`AsyncCache` 与挂起视图上默认抛出的方法；`SetCache`、`AsyncSetCache`、`SetCacheSpec`（由 `SetStore` 一族取代）。
- 把没有数据源的 `Cache` 当作权威副本的用法（移到 `Store`）；没有声明数据来源的 `Cache`（必须声明 `externallySourced()`）。
- `LocalEntry.unresolved` 改名为 `unfilled`，语义不变（P3）；`readFailure` 对权威缓存与不带 loader 的读取的含义（由 `unknownRead` 取代；外部来源缓存上带 loader 的读取仍由它决定，S11）。
- 带版本的类型上的 `put`、`putAll`、普通 `invalidate`、`putIfMatch`；`VersionedCache` 使用没有版本的数据源。
- `CoherenceBackend.versionedWrites`；异步 SPI（`AsyncRemoteStore`、`AsyncInvalidationChannel`）与 `BlockingAsyncStore` / `BlockingAsyncChannel`；抛出的默认方法与非原子的默认实现。`RemoteStore` 改名为 `L1Store`，`Remote*` 结果类型改名为 `L1*`（§4.3）。
- SPI 的 `foreignAsAbsent` 与整体覆盖的集合写入；没有数据源的缓存上的 `foreign-format: reclaim`（M3 删除，其余的 `foreign-format` 随 M5 / M6 由类型决定）。
- 引擎中的异步分支（`CoherentEngine` / `SetEngine` 的 `*Async` 路径）、`Futures` 的续接与就地执行、定时器调度的 lease 等待与许可等待队列、`cocache-timer`；`asyncExecutor` 与 `blockingExecutor` 合并为一个阻塞调度器（默认虚拟线程）。
- `cocache-coroutines` 模块（并入 core）；`LoadObserver.observeAsync`；条件读写在 Lua 中计算的 SHA-1 摘要。重入守卫的拥有线程与 `ThreadLocal` **不删除**，挂起变体另加上下文元素（K7，§4.4）。
- `Async*` 平行 TCK 规范；`supportsConditionalWrites` 一类能力开关。
- 权威缓存上的 `InvalidateFailureMode.RETRY`；`LoadPolicy.asyncTimeout`（并入 `load.timeout`）。
- `cocache.defaults.*` 的旧语义与旧键（`key-prefix` 移到 `cocache.key-prefix`）；配置中不带单位的时长与 `0` 一类的隐式哨兵；`setPolicyFor` / `versionedPolicyFor`；starter 对 `TransactionAwareCacheManagerProxy` 的使用。
- 格式 1 的读写与 `VERSION_MARK`；`foreign-format`（`ForeignFormatMode`）；`CoCache5Migrator`。
- 6.x 的频道与探测布局；只为旧版本保留的代码：二进制兼容的成员、已删除配置项的检测（`RemovedProperties`）、描述 6.x 历史的注释与文档（V13，不背历史债务）。

## 6. 不变量（v8 实施时与 architecture.md §14 的对照）

**新增**：

- **I-A 类型与声明决定默认值**：`Cache` / `VersionedCache` 是派生副本，`Store` / `VersionedStore` / `SetStore` 是权威副本；派生缓存声明数据来源。默认值与可用的操作由类型与声明决定；同一类型内，这次读取有没有数据源（含调用级 loader）只影响未命中与故障时能否回源。
- **I-B `Unknown` 只表示故障**：它不写入任何一层、不广播，也不作为带数据源的调用方的结果；`get` / `getAll` 遇到它抛出，`unknownRead = ABSENT` 的映射发生在调用方返回之前。
- **I-C L2 填充**：校验失效戳与写入 L2 在同一个 key 锁内完成，写后按身份复核；写入方与远端事件处理完成之后，读取开始于它们之前的副本不可见；重置之后，这样的副本至多存在到填充方自己的复核。
- **I-D 延迟的无条件写入只用于派生副本**：有数据源的缓存，以及外部来源且失效失败模式为 `RETRY` 的缓存；权威副本的删除与写入同步完成或抛出。
- **I-E 结果未知**：按客户端能观察到的事实判定（超时、断线排空是结果未知；引擎不取消 L1 调用，中断不到达 L1 命令，§4.3）；被取消的写入同样是结果未知（K18）；确定未执行的写入不会再执行；结果未知的写入在任何时刻都可能生效，它的安全只来自顺序，而摘要条件有 ABA。
- **I-F 令牌守恒**：取得令牌的每一步（`Read`、`ReadAll`、`Reclaim`、刷新的 `Read`、`Fill`、`FillAll`），在每个失败转移上、在超时、取消与中断时，都有释放；L1 命令除确定未执行外的任何失败，都由存储异步释放（K14）；取得令牌之后的步骤不可取消；每个 flight 在有限时间内完成或被注销。
- **I-G 刷新有界**：每个节点、每个 key 的刷新触发率有一个与命中率无关的上界；带未知标志位或 `E` 的副本不设刷新点。
- **I-H 等待有界**：跟随者、lease 等待、许可等待都有上界；调用方的等待响应取消与中断，且只影响它自己，唯一的例外是同步 leader 被中断时取消这一次加载，跟随者至多多回源一次（K13）；L1 命令与令牌步骤不可取消，loader 照常可以被取消。
- **I-I 一份协议**：每个协议分支只有一份（`suspend`）实现；同步、异步、挂起三种变体在其上提供，不复制分支。
- **I-J 事务内的回源**（启用 Spring 集成时）：写事务内 loader 产生、进入任何共享层的 key，在回滚、回滚到 savepoint 或结果未知时失效；事务内 leader 的结果不与事务外的跟随者共享；已登记失效的 key（按 namespace 与编码后的 key 匹配）的回源结果只返回给本次调用。事务上下文在调用入口捕获，异步与挂起变体不在此列。
- **I-K 版本单调（按类型）**：经 `VersionedCache` / `VersionedStore` 的写入与回源，不让 L1 的版本后退、不抹掉版本；版本拒绝的回源不写 L2。抹掉版本的入口只有：`purge`、到期、同一 namespace 上的其它类型（声明错误）、外部工具；条件脚本拒绝带版本的内容。
- **I-L 格式演进**：未知的格式版本、未知的必须理解的标志位、格式号 ≥ 2 中的未知状态，既不回收也不写回，格式 1 同样处理；读不懂的失效帧按该频道的 `onReset` 处理（D3）；新的必须理解的位，只能在一个 namespace 的全部节点都认识之后写入（8.x 内两阶段发布）。
- **I-M 派生路径不改写权威标量**：带摘要段的内容不被授予 lease 或刷新令牌，`RECLAIM` 与 `EVICT_KEEPING_VERSION` 拒绝它；读取方重算摘要，与头部不一致时按损坏处理（D1、D4）。

**与 architecture.md §14 的对照**：

| §14 | 处理 | 说明 |
|-----|------|------|
| 1 栅栏规则 | 修改 | L2 的部分改为 I-C；“写入方从不写 L2”保留；版本前进的 `fill` 在本节点按写入处理（§4.7） |
| 2 L1 未命中就是未命中 | 修改 | 补上：外部来源的“未填充”不是负缓存；权威缓存的 L1 未命中是 `Absent`；`E` 读作未命中 |
| 3 读路径从不无条件删除 | 修改 | 按类型与声明（I-A）；加上 I-L、I-M（`RECLAIM` 拒绝带摘要段与带版本的内容） |
| 4 能原子广播就原子广播 | 修改 | 补上：版本前进的 `fill` 广播；非原子广播下的四种重放也广播（§4.6）；按存储探测实际频道，ACL 拒绝时启动失败（§4.7） |
| 5 SingleFlight | 修改 | 合并规则逐 key 应用（I-B、`Private`）；flight 层共用（I-I）；超时注销（I-F） |
| 6 loader 的线程 | 修改 | 同步变体在调用方线程上执行阻塞 loader；异步与挂起变体在阻塞调度器（默认虚拟线程）上执行它；异步与挂起的 loader 不切换调度器；挂起变体从不在 L1 客户端的完成线程上恢复（§4.4） |
| 7 令牌必须释放或过期 | 取代 | 由 I-F 取代 |
| 8 每次 L1 状态转换显式重设到期时间 | 修改 | `E` 的到期时间由引擎传入；只触及字节 4–35 的原地修改（授予刷新令牌、`READ` 在 `E` 上授予 lease、`RELEASE`、被拒绝的 `FILL` 清零令牌）保留原到期时间，是写明的例外（K15） |
| 9 线格式冻结 | 修改 | 格式 2 是唯一格式；8.x 内两阶段演进；冻结的范围加上扩展区的 TLV、失效帧的演进规则、集合的 `!f` / `!m`、namespace 字符集与内部 key（§4.7）；摘要改为带算法标识的 SHA-256（D1）；I-L、I-M |
| 10 集合元素增删 | 修改 | `SetCache` 改为 `SetStore` |
| 11 有状态组件只由运行时创建 | 保留 | 补上 `CoCacheFactory` |
| 12 L1 连接只访问主节点、断线拒绝 | 保留 | |
| 13 存储只执行协议 | 保留 | |
| 14 代理不包装异常 | 保留 | 受检异常的 JDK 限制写入文档（Q7） |
| 15 命中路径的改动必须附带数据 | 保留 | |
| 16 AOT | 保留 | 新的接口与目标类型登记提示 |
| 17 取消不传播 | 保留 | 异步与挂起变体的共享加载在运行时作用域中，调用方取消只结束自己的等待；同步 leader 内联执行，被中断时取消这一次加载（K13） |
| 18 异步视图不挂起线程、不同步抛出 | 保留 | 未命中挂起协程，不占线程（阻塞 loader 在阻塞调度器上执行）；不同步抛出 |
| 19 条件读写只确认 L1 | 修改 | 移到 `Store`；加入 `deleteIfMatch`；摘要相等只证明已写入；带版本的内容拒绝；读取方校验摘要（I-M） |
| 20 按版本有序的写入 | 重写 | 由 I-K 与 `E` 取代 |
| 21 副本出生时不在刷新窗口内（#698） | 保留，并入 I-G | |

## 7. 性能预算与验证

验证时点：GA 之前一次完成（§10 M8）。开发期间各切片不跑 JMH（维护者，2026-11-03）：M3a–M4b 每个切片的最差中位数都在 −2.1% 以内。

| 路径 | 预算 | 验证 |
|------|------|------|
| L2 命中 | 不退化（1 / 8 线程，含 `l2HitMixed`）；异步与挂起变体的 L2 命中不启动协程；`unfilled` 标志的检查与 6.9 相同 | `scripts/jmh-compare.sh -b l2Hit,l2HitViaInterface,l2HitMixed,l2HitAsync -t 1,8` |
| L1 命中、未命中回源（同步变体，含 `runBlocking`） | 退化 ≤ 3% | `l1HitViaEngine`、`missLoad` |
| 异步视图的 L1 命中、未命中 | 8 线程闭环吞吐不低于 6.10 异步视图的 90%（RFC 0004 §11 的口径） | `l1HitViaEngineAsync`、`missLoadAsync` |
| 跟随者 | 10 000 个异步调用等待同一次加载时不创建线程 | 新用例 |
| L2 填充（`compute` + 条件复核） | 未命中路径的退化 ≤ 2% | `missLoad`、`l1Read` |
| 权威缓存的 `Absent` 与外部来源的“未填充” | 进入 L2，命中成本与普通 L2 命中相同 | 新基准 `l2HitAbsent` |
| 条件读取（摘要未变） | Redis 端成本与载荷大小无关 | 新基准 `conditionalUnchanged`（1 KB / 1 MB / 8 MB） |
| `Store` 的 L1 读取（读取方重算 SHA-256） | 成本只在 L1 读取上，L2 命中不变 | 在 `conditionalUnchanged` 旁加 `Store` 的 L1 读取（1 KB / 1 MB / 8 MB） |
| 内存 | 派生缓存每个 key +0 B；`Store` 的标量 +60 B（摘要段 32 B、写入标识 28 B）；`VersionedStore` +36 B（版本段 8 B、写入标识 28 B）；`VersionedCache` +8 B；`E` 为 44 B | `MEMORY USAGE` 对照 |

## 8. 切换与破坏性变更

**升级**（V13；维护者决定，2026-11-03：不考虑兼容、不背历史债务）：

- 8.x 不读写 6.x 的内容，不提供迁移工具，6.x 与 8.x 不共存于同一组 namespace。步骤：
  1. 停掉使用这些 namespace 的全部 6.x 写入方与读者，包括 CLI、控制面的定时维护、影子上报一类不是服务的写入方。
  2. 删除没有数据源的缓存的旧 key（`{keyPrefix}{namespace}:*`），否则 8.x 读到它们按读不懂 `FAIL`。有数据源的缓存可以不删：旧内容被条件回收后回源。
  3. 启动 8.x。有数据源的缓存从数据源回源重建；没有数据源的缓存从空开始，由应用重新写入（控制面重新发布、上报方重新上报）。
- **没有数据源的数据不随升级保留**：它们在 Redis 中是唯一副本。需要保留的，由应用在升级前导出、升级后重新写入，或给缓存配上数据源。
- **回退**：停掉 8.x、删除 8.x 写入的 key、启动 6.x，6.x 的缓存同样回源重建。
- **冷启动回源**：升级后派生缓存全空，读者会一起回源；按数据源的承受能力分批启动（Wow 的读者经 HTTP 回源到属主）。
- **为什么不做兼容**：6.x 与 8.x 的包名都是 `me.ahoo.cocache`，一个 JVM 里装不了两个版本，兼容只能是运行时之外的工具或共存期的中继。它们只为一次升级存在，却要随 8.x 一直维护。

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
| 第三方后端 | 实现 `L1Store`（`suspend`；Java 实现 `FutureL1Store`，经 `FutureL1Store.adapt` 适配）与能力访问器，通过新的 TCK |
| 库在 starter 之外装配缓存 | `CoCacheFactory`；约束与锁定用 `lock` / `constrain` |

**其余破坏性变更**（M15）：

- **基线**：JDK 21。
- **依赖**：`cocache-core` 依赖 `kotlinx-coroutines-core`（版本由 Spring Boot 的 BOM 管理）；Java 用户会间接引入它。
- **异步与挂起视图**：由协程核心提供，未命中不占线程；`cocache.async-executor` 与 `cocache.blocking-executor` 合并为一个阻塞调度器（默认每个调用一个虚拟线程）；`cocache-timer` 删除。
- **指标**：新增 `cocache.reads.unknown`、`cocache.fills.gated`、`cocache.refresh.skipped`、`cocache.type.mismatch`（派生读者读到摘要段，D4）；`cocache.load.timeouts` 包含同步跟随者的超时。端点的报告带上类型与能力，未经 `CoCacheFactory` 创建的缓存标注“未分层”。
- **`Digest`**：SHA-256，带算法标识；读取方重算校验，与头部不一致按损坏 `FAIL`（D1）。
- **namespace 与前缀**：namespace 只能是 `[A-Za-z0-9._-]+`，含 `:` 的启动失败；`key-prefix` 移到 `cocache.key-prefix`，为空时启动告警（D5、S4、S17）。
- **`InvalidationListener`**：收到去掉 `{keyPrefix}{namespace}:` 之后的编码 key，不是存储 key（S4）。
- **`Store` 的 L2 默认寿命**：`localMaxAge` 30 s、`absentTtl` 10 s，比 `Cache` 短（D7）；需要更长的在声明中写出。
- **`unknownRead`**：取值 `THROW` / `ABSENT`，默认 `THROW`（S16）。
- **overrides**：只收紧陈旧界限与有严格序的模式字段；等待界限、`invalidate-failure`、带版本类型的 `ttl` 不进 overrides；`overrides.cache.ttl` 钳制按调用给出的寿命（§4.8）。
- **`lookup`**：`readFailure = THROW`（含 6.9 没有数据源时的默认值）时，`lookup` 从抛出 `CacheReadException` 变为返回 `Unknown`。依赖它抛出来 fail closed、又用 `as?` / `else` 处理结果的调用方，会从拒绝变成放行（U5）。升级 skill 的审计脚本检查这类写法；JDK 21 的模式匹配 `switch` 能发现 `Unknown`。
- **`SetStore` 的读失败**：从 6.x 的原始异常变为 `CacheReadException(cause)`，`catch (e: RedisException)` 不再命中。
- **ACL**：频道模式为 `&{keyPrefix}inv:*`（默认前缀即 `&cocache:inv:*`）；`pubsub` 模式的探测为 `~cocache:!probe:*` 与 `&cocache:!probe:*`；`keyPrefix` 为空时按 namespace 写出（§4.7，S4）。`store(keyspace)` 创建时探测实际频道，ACL 拒绝时启动失败（K8）。
- **AOT**：`CoCacheRuntimeHints` 登记新的接口（`Store`、`VersionedCache`、`VersionedStore`、`SetStore`、`JoinView` 与各自的异步视图）；`CacheBeanDefinitions` 的目标类型随之改变；`CoCacheAotTest` 与 `CoCacheAotRuntimeTest` 同步更新。
- **`cocache-coroutines` 并入 core**：挂起视图是 core 的原生 API，有同构的层次；`suspendingVersionedLoader` 保留；依赖 `cocache-coroutines` 的下游改为依赖 core；下游公开的工厂签名（如 Wow 的 `StateViewCaches.CacheFactory`）随之改变。
- **`CacheSpecCustomizer`**：为 5 种 spec 各提供一个重载，改为 overlay 语义（只设置写出的字段），不能修改类型与存储布局（§4.8）。
- **`load.timeout`**：同步跟随者从无限等待变为 30 s 后失败；注册超过它的 flight 被注销。
- **提前刷新**：`VersionedCache` 支持提前刷新，全局的 `refresh-window` 开始作用于它；不需要时在声明中写 `refreshWindow = off()`。
- **`skills/`**：按 8.x 改写，不提供升级 skill 与静态审计脚本。语义变化（如 `Lookup` 从抛出变为返回 `Unknown`，U5）写进破坏性变更表与升级说明。
- 破坏性变更表写入 `wiki/guide/changelog.md`（en + zh）。

**下游**（第二轮、第三轮读了两个下游的源码）：

- **CoSec**（尚未发布正式版，没有兼容负担，直接按 8.x 适配；维护者，2026-11-03）：
  - **分型**：策略快照、控制面 8 个缓存、两个撤销表（`unknownRead = ABSENT`）、三个影子缓存（带显式 `ttl`）→ `Store`；`MembershipCache` → `Cache` + `externallySourced()`（带调用级 loader，`readFailure = THROW` 照常作用，S11）；迁移工具中的 `GlobalPolicyIndexCache` → `SetStore`，或随 7.x 转换工具一起删除。
  - **装配**：`CoSecCacheAssembler` → `CoCacheFactory`；`pinnedPolicy` → `lock` + `constrain`，锁定 namespace、keyPrefix、keyEncoder、codec 与“拒绝 loader”；`expiringFailClosed` 写作 `constrain(ttl) { it.finiteOr(1.minutes) }`（S12）。控制面缓存与影子缓存现在经 `runtime.create` 直接创建，改经 `CoCacheFactory`，否则不受分层配置影响（S20）。
  - **布局**（D5、S4）：CoSec 的全部 spec 用 `keyPrefix("")`，把前缀放进 namespace，namespace 因此含 `:`，在 8.x 中启动失败。前缀（连同其中的 `:`）移到 `keyPrefix`、namespace 只留不含 `:` 的部分之后，存储 key 的字符串不变，按 `~cosec:*` 写的 ACL 照常覆盖；频道变为 `{keyPrefix}inv:{namespace}`。`PolicySnapshotCache.snapshotKeyOf` 按 `"$namespace:"` 反解存储 key，v8 中 `InvalidationListener` 收到编码 key，须改写，否则每一条策略变更通知都被静默丢弃，只剩 `revalidate-interval` 兜底。
  - **读取**：`RedisSnapshotDocumentStore` 的 `when (lookup)` 因 `Unknown` 编译失败，可以发现；频道订阅改为新 SPI；“先淘汰本地副本，再通知外部监听者”成为契约。撤销表声明 `unknownRead = ABSENT`，运维可经 `caches.[name]` 收紧为 `THROW`（S16）；按缓存的读超时把 Redis 卡顿时每个请求多等的时间压到它自己选的界限（D6）。
  - **摘要**（D1）：CoSec 计算并持久化摘要的地方（修订记录的 key、修订索引、文档体中的 `parent`、待审批变更的 `base.digest` / `checkedWith`）改用带算法标识的 SHA-256 `Digest`，不再以 `.sha1` 的名义持有它；`getIfChanged` 的用法不变。CoSec `roadmap.md` 中“摘要算法仍是 SHA-1”的说法需要更正。
  - **部署**：托管或代理型 Redis 上，确认 `INFO memory` 可读，或配置 `assume-maxmemory-policy`；`allkeys-*` 在存在权威缓存时一律启动失败，CoSec 自己的 `eviction-check=warn` 只作用于它自己的检查（S22）；ACL 的频道模式。
- **Wow**：
  - `cacheSpec { suspendingVersionedLoader(…) }` → `versionedCacheSpec`；`StarterCaches` → `CoCacheFactory`。
  - `StateViewCaches.CacheFactory` 改签名，Wow 需要大版本。
  - 全局的 `refresh-window` 开始作用于状态视图，要么在声明中写 `refreshWindow = off()`，要么接受经 HTTP 回源到所有者。
  - 运维手册中的删除改用 `evict`。
  - **升级**：状态视图是派生缓存，在 8.x 上从事件存储回源重建；所有者与全部读者服务在同一个发布窗口切换；8.x 读者冷启动时一起回源到属主。

## 9. 6.x 的收尾

6.x 中不破坏兼容的修复已经发布或待发布。6.10.0 是最后一个功能版本，之后在 `6.x` 维护分支上只修缺陷。

| 项 | 来源 | 版本 |
|----|------|------|
| 没有数据源的 L1 读失败 fail closed；`invalidate` 重试不越过之后的写入；非原子广播下的重放广播；显式 `read-failure: degrade` 对没有数据源的读取生效 | A.D1、A.D2、A.R4、M3 | 6.9.0（#700） |
| 超时取消 Lettuce 命令；`sharded` 未确认频道每次心跳重置；读超时释放 lease；订阅生命周期竞态；格式字节按无符号解析 | A.R1、A.R2、A.L1、A.R3、A.L7 | 6.9.0（#701） |
| `TransactionalInvalidator` 回滚失效、迟到登记、逐缓存失败隔离 | A.T1、A.T2 | 6.9.0（#697） |
| 提前刷新退避；寿命 ≤ 刷新窗口不设刷新点 | A.D3 | 6.9.0（#698） |
| 中断时取消命令、释放令牌；`fill` / `fillAll` 超时或中断时释放令牌；`reclaim` 超时释放 lease | P5、P13、C7（附录 B） | 6.9.1（#704） |
| 驱逐策略检查改读 `INFO memory`、按声明检查；未知缓存名的端点 `DELETE` 返回 404；修正 architecture.md §6.3 | M11、M13、A.L10 | 6.10.0（#707） |
| 一个失效监听器抛出 `Error` 不影响其余监听器；`Subscription.close()` 的契约 | CoSec 反馈 | 6.10.0（#708） |
| 注册失败时释放 namespace；单调时钟；同步跟随者可中断；接口式代理与受检异常的文档 | A.L9、A.C1、A.S1、A.P1 | 6.10.0（#711） |

**移入 v8，6.x 不再做**：

| 项 | 来源 | v8 中的位置 |
|----|------|------------|
| L2 填充用 `compute` + 条件复核 | A.D4、C4 | §4.5、I-C；8.0 已实现（M2a） |
| L2 key 不含前缀 | §4.5 | §4.5（待 JMH） |
| 同步 `SetStore.getAll` 批量化 | A.L2 | §4.4 的批量路径 |
| namespace 前缀重叠 | A.L4 | namespace 字符集禁止 `:`，重叠不再可能（§4.7，D5） |
| Lincheck、TLA+ | V9 | §4.10 |
| starter 的事务装饰器 | U3 | §4.9 |

原定 7.x / 7.B 的其余项（sans-IO 重构、新旧 SPI 并存、读格式 2、配置别名、前向兼容声明、迁移报告）删除或并入 v8 的里程碑（§10）。

## 10. 实施顺序

0. **6.10.0**（已发布）之后切换分支（维护者决定 `main` 直接开发 v8）：
   - 从 `v6.10.0` 切出 `6.x` 维护分支：6.x 的缺陷修复与 6.10.x 的发版都在这个分支上进行。推送触发的 CI 与 wiki 的发布改由 `6.x` 进行，直到 8.0 发布；GitHub Pages 的部署环境与分支保护规则相应调整（仓库设置）。
   - `main` 开发 v8：基线 JDK 21；停止从 `main` 发布 wiki，否则公开文档会提前描述未发布的 v8；CONTRIBUTING 的分支与发版规则同步修改。
   - `gradle.properties` 的 `version` 按 CONTRIBUTING 仍是已发布的最新版本，由 8.0 的发版 PR 修改。
1. **M1 协程核心**：在现有的用户 API 与 SPI 之下，把协议改写为一份 `suspend` 实现：同步 API 经 `runBlocking`，异步视图经 `future(UNDISPATCHED)`，现有的挂起视图直接调用。
   - 等价性裁判：现有的同步 TCK 与 `Async*` TCK 中的**语义用例**保持全绿。断言 6.x 线程模型的用例（结果在哪个线程上完成、等待只占常数个线程等）按新模型重写，不算等价性证据（v4 审查）。
   - JMH：L2 命中不退化；同步变体（含 `runBlocking`）的 L1 命中、未命中退化 ≤ 3%；异步变体的 8 线程闭环不低于 6.10 的 90%。
   - 然后删除引擎中的异步分支、`Futures` 的续接、定时器与许可等待队列；`kotlinx-coroutines-core` 进入 core，AGENTS.md 的依赖规则同步修改。
2. **M2 flight 层与协议**：合并规则逐 key、`load.timeout`、取消与中断的规则（§4.4）、L2 填充改为 `compute` + 按身份复核（I-C）、令牌在 `NonCancellable` 中守恒；接入随机注入取消的性质测试与确定性多节点仿真。
3. **M3 SPI**：`suspend` 的 `L1Store`、`FutureL1Store` 适配、能力访问器、`ForwardingL1Store`、TCK fail closed；删除异步 SPI 与 `Async*` 规范。先删不依赖替代品的遗留，再建新形状；切片见 [todo.md](../todo.md) §2。M3d 同时实施第三轮转给它的 SPI 变更：`fill` 返回 `L1Fill`、`reclaim` 收 SPI 自己的内容指纹（K12），除确定未执行外的失败都异步释放令牌（K14），`read` / `readAll` 的可选截止时间（D6）（§4.3）。
4. **M4 线格式 2 与布局**：
   - 布局（没有版本标记，V13）、带前缀的频道、namespace 字符集与内部 key（D5）；按存储探测实际频道（K8）。
   - 头部的段（SHA-256 摘要段，读取方校验，`Digest` 带算法标识，D1）、扩展区的 TLV 与写入标识（D2）、状态 `E`、失效帧的演进规则与预留的帧版本 2（D3）、集合的 `!f` / `!m`（K10）。
   - 逐脚本规则，含带摘要段的内容（D4）、`RECLAIM` 与格式号（K15）、`FILL` / `RELEASE` 只读核心头部（K19）。
   - 不提供拷贝工具（维护者决定，2026-11-03，§8）。
   - 第三轮对抗验证已在这一步之前完成（v6）；合并即冻结 8.x 的格式（§4.7 的定稿项）。
5. **M5 用户 API**：类型（V1–V3，按 v4 全部实现）、`Lookup.Unknown` 与 `unknownRead` 的取值（S16）、`CoCacheFactory`（校验 `type` 是接口，声明接口与手写实现的区分，S21）；`cocache-coroutines` 并入 core，挂起变体的上下文规则（K2、K6）与重入守卫（K7）；带版本数据源的删除（K16）；`Store` 的类型默认值（D7）；按缓存的读超时接入引擎（D6）；`InvalidationListener` 收到编码 key、`keyPrefix` 为空时启动告警（S4）；评估被取消的写入是否整体放进 `NonCancellable`（K18）。
6. **M6 配置**（V10）：含“字段 × 层”的语义表、显式哨兵、overrides 只收紧并钳制按调用的寿命（S8、S9、S10）；`constrain` 的值域与 customizer 写锁定字段（S12）；`cocache.key-prefix`（S17；不检测 6.x 的键，§4.8）；按缓存的读超时的配置键（D6）；每种 spec 的默认策略（S23）；`read-failure` 与 `unknown-read` 的适用性（S11、S16）；“未分层”的标注（S20）；驱逐检查的取值（S22）。
   **M7 Spring**（V11）：含事务上下文在调用入口捕获、记录进入任何共享层的 key、事务内 leader 的结果不共享、按 (namespace, 编码 key) 匹配（S7、S15、S24）；`Unknown` 的不对称与可选的 `CacheErrorHandler`、`afterCommit` 的异常、通知嵌套的告警（S18、S19）。S5、S6 已在 main 与 6.x 修复，M7 的装饰器沿用。
7. **M8 检验与文档**：GA 前的 JMH 对比（§7 的全部预算，基线 v6.10.0；开发期间各切片不跑，维护者 2026-11-03）、Lincheck（已批准，CI 已运行）、TLA+、architecture.md 重排（V12）、wiki 与升级说明（§8）、skills。
8. **8.0.0** 发布；8.x 按 §4.2 的演进规则新增能力，新的必须理解的段两阶段发布。

每个里程碑由若干 PR 组成，每个 PR 的 `./gradlew check` 全绿；JMH 对比在 GA 之前一次完成（§7）。

## 11. 风险与未决问题

- **Q1 范围放在 v8 还是 7.0**：维护者指定 v8，并跳过 v7（v4 决定）。
- ~~Q2 命名~~：已关闭。维护者 2026-11-02 确认 SPI 改名为 `L1Store`，配套命名见 §4.3。
- **Q3 基线升到 JDK 21**：v4 决定升到 JDK 21；v5 改为协程核心后保持 JDK 21，虚拟线程作为阻塞 loader 的默认调度器（§4.4）。生产建议 JDK 24 及以上（JEP 491）。
- ~~Q4 `fill-in-transaction` 的默认值~~：已关闭（第三轮）。维持 `registered-keys` + 默认开启的回滚失效，并带上 S5、S6、S15、S7 的修正（§4.9）。判定依据：默认值必须覆盖“应用什么也没做”时的残留（回滚），登记只用来进一步缩小窗口。两个下游都不使用 Spring 事务，默认值只影响一般的 Spring 用户。
- **Q5 响应式事务**：需要 `cocache-spring` 可选依赖 reactor，须先经维护者批准。
- **Q6 摘要的抗碰撞**：v5 决定权威数据的摘要改用客户端计算的 SHA-256；v6 保留它，`Digest` 带算法标识、读取方校验，并改写威胁模型（D1，§4.7）；只有派生缓存的条件回收在脚本中用 SHA-1 指纹，与安全无关。
- **Q7 代理与受检异常**：JDK 动态代理的限制；构建期生成实现留到 8.x 评估。
- **Q8 派生 `Cache` 是否保留 `put`**：保留。
- ~~Q9 `Store` 的 L2~~：已关闭（D7）。`Store` 默认 `localMaxAge` 30 s、`absentTtl` 10 s，端点标注“类型默认值”（§4.1）：权威数据的失效一旦丢失，没有回源可以纠正，陈旧的上界只剩 L2。
- ~~Q10 修订号或写入标识~~：已关闭（D2）。M4 冻结扩展区的 TLV 帧格式，8.0 起权威写入带写入标识（TLV 类型 1），按标识比较的条件写入在 8.x 加入；不存在态的 ABA 写进文档，指向 `VersionedStore`；不做必须理解的修订号段（§4.6、§4.7）。
- **Q11 重置的严格线性化**：用读写锁把填充与“递增 + `clear`”分开，代价是未命中路径多一次 CAS。
- ~~Q12 全集群清空~~：已关闭（D3）。M4 冻结“读不懂的帧按该频道的 `onReset` 处理”，预留帧版本 2 为 namespace 重置；重置帧（删除 L1 并广播）与 `@CacheEvict(allEntries = true)` 的全集群语义在 8.x 加入，不需要两阶段发布（§4.7、§4.9）。
- **Q13**（v2）：已关闭，由状态 `E` 取代（§4.7）。
- ~~Q14 类型元数据~~：已关闭（D4）。8.0 以摘要段作为权威标量的标记：`READ` 不授予令牌，`RECLAIM` 与 `EVICT_KEEPING_VERSION` 拒绝，派生读者读到时计数告警（§4.7）；元数据 key 不进 8.0，只预留 `{keyPrefix}!meta:{namespace}` 的位置。
- ~~Q15 `unknownRead = ABSENT` 的短暂缓存~~：已关闭（D6）。不缓存降级结果：按 key 缓存对高基数的撤销表几乎无效，还给权威数据引入一种新的 L2 状态。8.0 做按缓存的读超时（上限为 `command-timeout`，SPI 的读取带截止时间，§4.3）；后端级快速失败涉及所有缓存的故障语义，8.x 另立 RFC。
- ~~Q16 7.B 的前向兼容声明~~：v4 删除（没有 7.B）。
- ~~Q17 是否保留 sans-IO 状态机~~：v5 以协程核心解决，测试调度器与虚拟时间让多节点仿真可以确定性重放（§4.10）。
- **Q18 Lincheck 作为测试依赖**：已决定，批准（维护者，2026-11-02；可行性实验 #726，CI 作业 #738）。
- ~~Q19 布局标记的拼写~~：已关闭。2026-11-03 维护者决定去掉布局标记（§12）；namespace 字符集 `[A-Za-z0-9._-]+`，内部 key 在 `{keyPrefix}!…`（§4.7）。
- **Q20 `kotlinx-coroutines-core` 进入 core**：v5 决定批准（§4.4）；AGENTS.md 中“kotlinx 只出现在 `cocache-coroutines`”的规则在 M1 中同步修改。

## 12. 对抗验证记录

本节的编号（C、M、P、U、K、S，以及第三轮的维护者决定 D1–D8）与附录 A、B 的编号相互独立；正文引用附录时一律带前缀（如 A.D1、B.1），不带前缀的 D1–D8 都指第三轮的决定。

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
| M4 | 高 | 换摘要算法会破坏 `Digest` 的使用者 | §4.7 摘要段存 SHA-1（v5 改为 SHA-256，迁移见 §8 与第三轮 D1） |
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
- ~~**CoSec 的摘要**：在 v8 中不受影响~~：**v6 撤回**。这一结论在摘要仍是 SHA-1 时成立；v5 改用 SHA-256 之后，CoSec 持久化在 key 与载荷中的 SHA-1 摘要都受影响（第三轮 S2、D1，迁移见 §8）。
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

### v4 → v5：审查与维护者决定（2026-11-01）

作者对 v4 的复查发现：

| # | 发现 | 处理 |
|---|------|------|
| 1 | 不迁移数据会丢掉唯一副本：撤销表一类权威数据没有源头可以重建；6.x 与 8.x 同一包名，不能在一个 JVM 里双写；滚动发布期间两边互不可见 | 维护者决定提供一次性拷贝工具（§8） |
| 2 | M1 的等价性裁判写成“`Async*` TCK 全绿”，但其中一部分用例断言的是 6.x 的线程模型 | §10：语义用例全绿，线程模型用例按新模型重写 |
| 3 | 异步调用的跟随者每个占一个虚拟线程 | 协程核心中跟随者挂起在 `Deferred` 上，不占线程（§4.4） |
| 4 | 整个协议期间清除中断标志，loader 收不到取消 | 只有 L1 命令与令牌步骤不可取消，loader 照常可以被取消（§4.4） |
| 5 | SHA-1 摘要可以碰撞，权威数据的使用者是安全产品 | 维护者决定改用客户端计算的 SHA-256，去掉 `ON_DEMAND`（§4.7） |
| 6 | 频道只按 namespace 命名，前缀不同的应用互相收到失效帧 | 维护者决定频道带上 key 前缀（§4.7） |
| 7 | 布局标记可能与 6.x 中名为 `v8` 的 namespace 相撞；`observeAsync` 多余；Lincheck 检验真实 Caffeine 的可行性未知 | Q19；§5；§4.10 先做小实验 |

维护者另外决定：
- **协议核心用协程实现**，同步、异步、挂起三种变体共用一份 `suspend` 实现；批准 `kotlinx-coroutines-core` 进入 core（Q20）；`cocache-coroutines` 并入 core。取舍见 §4.4 的“被否决的方案”。
- **JDK 基线保持 21**，虚拟线程作为阻塞 loader 的默认调度器。
- **8.0 的用户 API 类型按 v4 全部实现**（`Cache`、`VersionedCache`、`Store`、`VersionedStore`、`SetStore`、`JoinView` / `JoinCache`）。

### M3 之前：维护者决定（2026-11-02）

作者在 M3 动手之前对照代码复查了 §4.3，维护者确认以下决定：

| # | 决定 | 依据 | 对本 RFC 的影响 |
|---|------|------|----------------|
| 1 | SPI 改名为 `L1Store`，结果类型去掉 `Remote` 前缀（Q2 关闭） | 只改主接口会让 SPI 中两套命名混用 | §4.3 的命名表；§11 |
| 2 | `FutureL1Store` 是接口加 `adapt`，不是基类 | Kotlin 不允许同名的 `suspend` 与非 `suspend` 重载 | §0 V4；§4.3；§8 |
| 3 | 引擎从不取消 L1 调用，中断不到达存储；被中断的同步调用方等在途的命令结束 | kotlinx 1.10.2 与 1.11.0 的 `runBlocking` 被中断时取消协程并继续等它结束（1.8.0 立即抛出，对照字节码与源码）；立即返回会让命令在调用方看到失败之后写出 | §4.3；§4.4；§4.6；I-E |
| 4 | `subscribe` 保持阻塞，只有 `publish` 挂起 | 只在创建时调用一次 | §4.3 |
| 5 | M3 的能力面只放已有的操作：`deleteIfMatch` 随 M5、`evictKeepingVersion` 随 M4 | M3 只换形状 | §4.3 |
| 6 | 删除没有数据源的缓存上的 `foreign-format: reclaim`，以及 SPI 的 `foreignAsAbsent` 与整体覆盖的集合写入 | 只服务于 5.x 留在同一 key 中的内容，8.x 的 key 与之隔离（V13）；v4 已决定删除 `foreign-format`，§4.1 为 `Store` 留的开关与之矛盾 | §4.1；§4.3；§5 |
| 7 | 先删不依赖替代品的遗留（5.x 迁移工具、`Async*` 引擎规范、决定 6），再建新形状；不推倒重来 | 协议核心已在 M1 / M2 重写，重来会丢掉作为裁判的测试 | §10；todo.md §2 |

另外记下：V7 的失败路径分类（“确定未执行 / 结果未知”的异常类型、`CacheWriteException.outcomeUnknown`、派生 `put` 的补偿失效）此前没有归属的里程碑，放到 M5。

### v5 → v6：第三轮（2026-11-03）

M4 冻结格式之前，v5 由两路独立的只读会话验证：
- 协议与格式：对照 `origin/main` 的 `1e7325a` 与 M3b 的 SPI（`origin/feat/l1store-spi` 的 `405909b`，当时未合并）、kotlinx-coroutines 1.10.2 字节码与 1.11.0 源码、spring-core 7.0.9 字节码。重点是协程核心（取消与 `NonCancellable` 的边界、`runBlocking` 中的中断、作用域的关闭、执行上下文与重入守卫、与 RFC 0004 异步视图的差异）与格式定稿。四个临时用例（E1–E4）复现了 K6、K2、K7、K5。
- 语义与迁移：对照 `1e7325a` 的代码与文档、spring-context / spring-context-support / spring-tx 7.0.10 的源码，以及下游 CoSec（`e82abdee`）与 Wow（`4509f763b4`）的源码。重点是拷贝工具与写入冻结、三层配置、两个事务机制。两个临时用例复现了 S5、S6。

作者对照代码复核了其中的关键论断：
- K1：`InvalidationFrame.decode` 要求版本相等；`RedisInvalidationChannel.dispatch` 解码失败只告警并丢弃；
- K5：`SingleFlight.sharedFailure` 按异常类型判断“被中断”；
- S5：`CoCacheSpringCache` 没有覆盖 `evictIfPresent`。

44 条发现（K：高 2、中 10、低 8；S：高 3、中 11、低 10）全部处理。处理决定由架构会话给出；需要维护者决定的八项（格式冻结、偏离已定的 RFC）列在下面，维护者 2026-11-03 确认“全部按推荐”。现行代码中的缺陷不等本 RFC：S5、S6 在 main 与 6.x 修复，K5、K17 在 main 修复；K12、K14 与 D6 的 SPI 部分随 M3d 实施。

**协议与格式**：

| ID | 严重度 | 发现 | 处理 |
|----|--------|------|------|
| K1 | 高 | 失效帧不能演进：解码要求版本相等，读不懂的帧被告警丢弃，8.x 新增的帧在 8.0 节点上就是丢失效 | D3：读不懂的帧按 `onReset` 处理，预留帧版本 2（§4.7，M4 冻结） |
| K2 | 高 | 挂起变体直接调用核心时，`Unconfined` 的调用方在 L1 客户端的 I/O 线程上继续核心与用户代码 | 调度器取自运行时，从不在完成线程上恢复（§4.4，M5） |
| K3 | 中 | 摘要由客户端计算、存在头部，“防碰撞”的论证对能写 Redis 的攻击者不成立；读取方是否校验没有规定 | D1：改写威胁模型，读取方重算并比较（§4.7，M4） |
| K4 | 中 | 误声明的派生缓存可以凭刷新令牌覆盖、或经 `RECLAIM` 删除带摘要段的权威内容 | D4：不授予令牌，`RECLAIM` / `EVICT_KEEPING_VERSION` 拒绝，派生读者告警（§4.7，M4） |
| K5 | 中 | loader 自己的 `CancellationException` 被当作 leader 被中断，回源次数随并发放大 | 现在修（core）：按 leader 自己的状态判断，包装为 `SourceLoadCancelledException`（§4.4） |
| K6 | 中 | 没有 `ExecutionMode` 的 leader 丢掉调用方的上下文元素（MDC、追踪） | 元素取自调用方、去掉 `Job`（§4.4，M5） |
| K7 | 中 | 重入守卫“改为”协程上下文元素行不通：元素穿不过 `runBlocking` 与 `future {}` | 改为“增加”；换线程的重入以 `load.timeout` 为界（§4.4、§5，M5） |
| K8 | 中 | 原子广播的探测不代表带前缀的实际频道，ACL 漏配时每次写入都在 `PUBLISH` 处失败 | `store(namespace)` 创建时探测实际频道，ACL 拒绝时启动失败（§4.7，M4） |
| K9 | 中 | 内部 key 与存储 key 相撞，namespace 字符集不受限 | D5：namespace `[A-Za-z0-9._-]+`，内部 key 在 `{keyPrefix}v8!…`（§4.7，M4 冻结） |
| K10 | 中 | 集合格式没有演进机制，`!f` 的取值前后矛盾 | `!f = '2'`，`!` 元数据字段与必须理解的 `!m`（§4.7，M4 冻结） |
| K11 | 中 | 拷贝工具的转换与幂等规则不完整，部分写入可能丢数据 | 以 8.x 的 spec 为输入、转换表、令牌清零、逐 key 原子写入、冲突默认不覆盖（§8，M4） |
| K12 | 中 | M3 的 `fill: Boolean` 与 `reclaim(…, Digest, …)` 装不下 §4.7 的语义 | `L1Fill`，SPI 自己的内容指纹（§4.3，M3d） |
| K13 | 低 | RFC 说同步 leader 被中断时共享加载不受影响，代码中 leader 内联执行 | 改写 RFC：中断取消这一次加载，跟随者至多多回源一次（§4.4） |
| K14 | 低 | 存储只在超时与中断时释放令牌，断线排空时 lease 留到 `lease.ttl` | 除确定未执行外的失败都异步释放（§4.3，M3d） |
| K15 | 低 | 逐脚本规则的缺口：`RECLAIM` 转 `E`、保留到期时间的例外、授予令牌前不查格式号、版本段中的 `NO_VERSION` | `RECLAIM` 拒绝；例外写进 §6；先查格式号；禁止 `NO_VERSION`（§4.7、§6，M4） |
| K16 | 低 | 带版本的数据源返回不带版本的 `null` 时一律被拒绝，没有负缓存 | 以 `Versioned(null, w)` 表达删除；计数、首次告警（§4.1，M5） |
| K17 | 低 | 运行时关闭时不等在途的不可取消步骤，就关闭执行器 | 现在修（core）：以 `commandTimeout` 为上限等待子协程（§4.4） |
| K18 | 低 | 被取消的异步写入以取消报告，已生效的结果丢失 | 写明结果未知（I-E）；M5 评估整体放进 `NonCancellable`（§4.6） |
| K19 | 低 | `FILL` / `RELEASE` 为比较令牌 `GET` 整个值 | `GETRANGE key 0 35`（§4.7，M4） |
| K20 | 低 | 拷贝工具原样拷贝载荷，codec 不兼容要到写入方启动之后才发现 | `--verify-decode`，解码失败为 0 或显式放行（§8，M4） |

**语义与迁移**：

| ID | 严重度 | 发现 | 处理 |
|----|--------|------|------|
| S1 | 高 | 撤销表由任何应用的登出写入，§8 的写入冻结对 CoSec 不可行；写入方切到 8.x 之后不能回退 | D8：follow 模式，冲突策略按 namespace；写明不能回退（§8，M4） |
| S2 | 高 | SHA-256 破坏 CoSec 持久化在 key 与载荷中的 SHA-1 摘要；第二轮“CoSec 的摘要不受影响”已过时 | D1：转换钩子迁移，`Digest` 带算法标识；撤回第二轮的结论（§8、§12） |
| S3 | 高 | 共存期间派生缓存跨版本丢失失效，陈旧以对方读者的 `ttl` 为界 | 双向失效中继；写明残余与上界；窗口期用 overrides 收紧（§8，M4） |
| S4 | 中 | 布局标记夹在 key 中间，下游按 `"$namespace:"` 反解存储 key 的代码静默失效；空前缀部署的 ACL | `InvalidationListener` 收到编码 key；空前缀告警；ACL 补形状；下游清单（§4.7、§4.9、§8，M5） |
| S5 | 中 | 事务内的 `@CacheEvict(beforeInvocation = true)` 立即失效，提交前的读穿透填回旧值（已复现） | 现在修（main 与 6.x）：立即失效并登记（§4.9） |
| S6 | 中 | 回滚到 savepoint 时外层同步看不到 `afterCompletion`，机制 1 漏掉 NESTED（已复现） | 现在修（main 与 6.x）：`savepointRollback` 时失效（§4.9、I-J） |
| S7 | 中 | 没有规定事务状态在哪里判断；异步变体的调用方线程上可能有事务 | 调用入口捕获一次；异步与挂起变体不门控、不记录（§4.9，M7） |
| S8 | 中 | overrides 的语义不完整：未列出的字段、哨兵值与取 min 冲突、不约束按调用给出的寿命 | “字段 × 层”表、显式哨兵、钳制按调用的寿命并告警（§4.8，M6） |
| S9 | 中 | 等待界限与带版本类型的 `ttl` 取 min 并不是收紧 | 二者不进 overrides，只收紧陈旧界限（§4.8，M6） |
| S10 | 中 | 模式字段“直接设定”会放宽声明 | 按严格序取更严，没有严格序的不进 overrides（§4.8，M6） |
| S11 | 中 | §4.1 与 §4.8 对外部来源缓存上的 `read-failure` 说法矛盾 | `read-failure` 作用于带 loader 的读取，`unknown-read` 作用于不带 loader 的读取（§4.1、§4.8，M5 / M6） |
| S12 | 中 | `constrain` 与三态不匹配，`forever` 穿过约束；customizer 能否写锁定字段没有规定 | 值域含 `Forever`；customizer 写锁定字段时抛出（§4.8，M6） |
| S13 | 中 | 拷贝工具的输入不够：布局、codec、目标类型、内容不同时、不广播、墓碑 | 见 K11；写目标时广播；墓碑带版本拷贝（§8，M4） |
| S14 | 中 | 运维必须做、RFC 没写的步骤（内存、清理、写入方、ACL、冷启动） | 运维检查表；`--purge-6x`（§8，M4） |
| S15 | 低 | 机制 1 漏掉只进 L2 的降级填充与 flight 的跟随者 | 记录进入任何共享层的 key；事务内 leader 的结果不共享（§4.9，M7） |
| S16 | 低 | `unknownRead` 只有 `ABSENT`，“默认放行、运维可改为拒绝”无处表达 | 取值 `THROW` / `ABSENT`，`caches.[name]` 可以收紧（§4.1，M5 / M6） |
| S17 | 低 | 6.x 键逐个列出，漏一个就静默忽略；`key-prefix` 的位置没有定义 | 按规则检测；`cocache.key-prefix`（§4.8，M6） |
| S18 | 低 | L1 故障时不带 `sync` 的 `@Cacheable` 失败；`afterCommit` 中的失败绕过 `CacheErrorHandler` | 写明不对称；可选的 `CacheErrorHandler`；`afterCommit` 的异常交给它（§4.9，M7） |
| S19 | 低 | 两个事务机制依赖缓存通知与事务通知的嵌套顺序 | 写明两种嵌套；order 相同时告警（§4.9，M7） |
| S20 | 低 | 绕过 `CoCacheFactory` 的缓存不受分层配置影响 | 标注“未分层”；建议库经 `CoCacheFactory` 并用 `lock`（§4.8，M6） |
| S21 | 低 | `@SubclassOptInRequired` 与接口式声明冲突 | 区分声明接口与手写实现；校验 `type` 是接口（§4.2，M5） |
| S22 | 低 | 驱逐检查“一律失败”与 `store-eviction-check` 的模式矛盾 | 写明一律失败的情形与剩下的取值（§4.1、§4.8，M6） |
| S23 | 低 | 整体 `CachePolicy` 视为全部写出，会把派生缓存的 1 h `ttl` 带进 `Store` | 每种 spec 有自己的默认策略常量（§4.8，M6） |
| S24 | 低 | `registered-keys` 按什么匹配“已登记”没有写 | 按 (namespace, 编码后的 key)（§4.9，M7） |

**维护者决定**（2026-11-03，“全部按推荐”）：

| # | 问题 | 决定 |
|---|------|------|
| D1 | S2、K3：`Digest` 是否仍改为 SHA-256（v5 的 Q6） | 保留 SHA-256，`Digest` 带算法标识；改写威胁模型；读取方重算并比较，不一致按损坏 `FAIL`；CoSec 的摘要经拷贝工具的按 namespace 转换钩子迁移，与拷贝在同一个窗口内完成；撤回第二轮“CoSec 的摘要不受影响”。理由：M4 冻结之后，SHA-1 会在 8.x 的线格式中固定多年 |
| D2 | Q10 | 冻结扩展区的 TLV 帧格式；8.0 起权威写入带写入标识（TLV 类型 1）；不存在态的 ABA 写进文档，指向 `VersionedStore`；不做修订号段 |
| D3 | Q12、K1 | 读不懂的帧（未知版本、类型、解码失败）一律按该频道的 `onReset` 处理，预留帧版本 2 为 namespace 重置；重置帧本身在 8.x 加入 |
| D4 | Q14、K4 | `READ` 不对带摘要段的内容授予令牌，`RECLAIM` / `EVICT_KEEPING_VERSION` 拒绝它，派生读者读到时计数告警；元数据 key 不进 8.0，只预留位置 |
| D5 | Q19、K9 | 标记沿用 `v8:`，不得含 `{}` 与 glob 元字符；namespace 字符集 `[A-Za-z0-9._-]+`；内部 key 在 `{keyPrefix}v8!…`；格式 1 的内容按更高格式处理；CoSec 把前缀移到 `keyPrefix` |
| D6 | Q15 | 8.0 做按缓存的读超时（上限为 `command-timeout`），SPI 的 `read` / `readAll` 增加可选的截止时间（随 M3d）；后端级快速失败在 8.x 另立 RFC；否决缓存降级结果 |
| D7 | Q9 | `Store` 默认 `localMaxAge` 30 s、`absentTtl` 10 s，端点标注“类型默认值” |
| D8 | S1 的回退 | 拷贝工具提供 follow 模式；不提供反向模式，写入方切到 8.x 之后不能回退，列为 CoSec 的安全前提 |

**尝试攻破但成立的论断**（第三轮摘要）：
- **`runBlocking` 的中断**：kotlinx 1.10.2 与 1.11.0 的顺序相同，中断只在协程停下时处理，在途的 L1 等待照常完成；`SyncOutcome` 正确区分“取消生效”与“取消之后自己结束”。调用之前已被中断的线程照常发出命令，与 6.10 的立即失败不同，写进 changelog。
- **同步写入不可取消**：走 `withContext` 的快路径，第二次递增在 `NonCancellable` 的 `finally` 中，取消跳不过它（G2）。
- **取消路径上的令牌守恒**、**中断不到达存储**：成立。
- **现行同步与异步变体不在 Lettuce 线程上续接**：成立，例外是将来的挂起变体（K2）。
- **格式 2**：载荷偏移；6.x 节点读到格式 2 时授予的刷新令牌无害；`E` 的逐脚本表（K15 之外）；8 字节有符号版本的比较与帧解码的边界。
- **Spring 7**：错误处理路径与 `TransactionAwareCacheDecorator` 的契约描述准确；`TransactionalInvalidator` 在 NESTED 与 `REQUIRES_NEW` 下的行为；M7 的论证。
- **M3 的适用性规则**已按 RFC 实现；`lock` 能覆盖 CoSec 锁定的全部布局项（补上 S12 的规则之后）；CoSec 与 Wow 的分型；V13 的物理隔离。

### M4 期间：维护者决定（2026-11-03）

维护者：“不考虑兼容性”“不提供迁移工具，保持架构、代码清洁”“不要背负历史债务”；布局标记经确认去掉。

| 项 | 决定 | 取代 |
|----|------|------|
| 迁移 | 不提供拷贝工具；6.x 与 8.x 不共存；有数据源的缓存升级后回源重建，没有数据源的数据不随升级保留（§8） | v5 决定 3；K11、K20、S1、S3、S13、S14；D8；D1 与 S2 的转换钩子 |
| 布局 | 去掉版本标记 `v8:`：key `{keyPrefix}{namespace}:{encodedKey}`、频道 `{keyPrefix}inv:{namespace}`、内部 key `{keyPrefix}!…`（§4.7） | v4 的“以布局标记与 6.x 物理隔离”；D5 的标记部分；#752 的标记 |
| 格式 1 | 与格式 0 一样按读不懂处理：派生缓存回收后回源，没有数据源的缓存 `FAIL`（§4.7） | D5 的“格式 1 按更高格式处理” |
| 配置 | 不检测 6.x 的键（§4.8） | v4 与 S17 的检测 |
| 代码 | 删除只为旧版本保留的代码：二进制兼容的成员、`RemovedProperties`、描述 6.x 历史的注释（§5） | — |
| 文档 | 不提供升级 skill 与静态审计脚本；破坏性变更表与升级说明照常 | §8 的升级 skill |
| 下游 | CoSec 尚未发布正式版，没有兼容负担：§8 的下游只写适配，不写升级与数据迁移 | S2 的摘要迁移；§8 中 CoSec 的切换与撤销窗口 |

D5 的其余部分（namespace 字符集、内部 key 的位置、`keyPrefix` 为空时告警）、K8 与 D3 不变。

### M5 之前：维护者决定（2026-11-03）

| 问题 | 决定 |
|------|------|
| 手写实现的 opt-in（S21） | 不加机制；U13 保证委托类不会遇到 `AbstractMethodError`，文档注明“不供用户实现，委托除外” |
| `Lookup.fold` | 删除：基线 JDK 21 的 Java 能对 sealed 类型穷尽 `switch`，保留理由（JDK 17）不再成立；只留 `valueOrThrow()` |
| 挂起视图 | 接口在 `cocache-api`，经抽象的 `suspending()` 取得，与 `async()` 对称；实现在 core；删除 `cocache-coroutines` 模块 |

## 附录 A：6.8.0 架构审查发现（2026-10-28）

范围：`cocache-api`、`cocache-core`、`cocache-redis`、`cocache-spring`、`cocache-spring-boot-starter`。
- “复现”：用临时用例实际运行观察到。
- “读代码”：经代码与依赖库确认（spring-tx 7.0.9 字节码、JDK 语义）。

| 编号 | 严重度 | 发现 | 依据 | 位置 | 去向 |
|------|--------|------|------|------|------|
| D1 | 高 | 没有数据源的 `Cache` 在 L1 读失败（默认 `DEGRADE`）时返回 `null`、不报错，并作为已决的负缓存写入 L2；之后 `get(key, loader)` 命中它，不调用 loader | 复现 | `CoherentEngine.loadThrough`、`completeBypass` | 6.9.0 已修复（#700）；v8 §4.1 |
| D2 | 高（权威副本） | 默认 `RETRY` 的延迟重试会删除其它节点之后写入的值 | 复现 | `CoherentEngine.scheduleRetry` | 6.9.0 已修复（#700）；v8 §4.1 |
| D3 | 中 | 没取得刷新令牌的节点按往返频率轮询 L1；寿命 ≤ 刷新窗口时持有者背靠背回源 | 复现（前者）、读代码（后者） | `CoherentEngine.refill`、`AbstractEngine.localEntry` | 6.9.0 已修复（#698）；v8 §4.6 |
| D4 | 中低 | L2 填充“校验—写入—复核—撤销”不可线性化 | 读代码 | `AbstractEngine.fillLocal` | v8 §4.5，8.0 已修复（M2a，`fillGuarded`） |
| R1 | 中高 | 超时只取消派生的 future，Lettuce 命令不被取消 | 读代码 | `RedisConnections.bounded`、`await` | 6.9.0 已修复（#701）；中断路径见 B.1 |
| R2 | 中低 | `sharded` 频道未确认期间只重置一次 | 读代码 | `ShardedInvalidationChannel.ping` | 6.9.0 已修复（#701） |
| R3 | 低 | 订阅与退订并发时订阅者可能永久收不到失效 | 读代码 | `RedisInvalidationChannel.subscribe` | 6.9.0 已修复（#701） |
| R4 | 中 | 非原子广播时条件写入的重放永不广播 | 读代码 | `CoherentEngine.writeIfNewer` | 6.9.0 已修复（#700）；v8 §4.6 |
| T1 | 中高 | 事务回滚时不失效；事务内的回源可能把未提交的数据写进 L1 | 读代码 | `TransactionalInvalidator` | 6.9.0 已修复（#697）；`@CacheEvict` 的同类缺口见 v8 §4.9 |
| T2 | 中低 | 在另一个同步的 `afterCommit` 中登记的失效会丢失；`flush` 遇到第一个异常即停止 | 读代码（含 spring-tx 7.0.9 字节码） | `TransactionalInvalidator` | 6.9.0 已修复（#697） |
| P1 | 中低 | 接口式代理遇到受检异常时由 JDK 包装为 `UndeclaredThrowableException` | 读代码（JDK 语义） | `CacheProxies.Handler` | 6.10.0 文档已写明（#711）；v8 Q7 |
| C1 | 低 | 时钟用墙钟，回拨时 L2 副本多活 | 读代码 | `TickingClock` | 6.10.0 已修复（#711）；v8 §4.5 |
| S1 | 低 | 同步跟随者用 `join()` 等待，不可中断、无上限 | 读代码 | `SingleFlight.await` | 可中断：6.10.0 已修复（#711）；上限：v8 §4.4 |
| L1 | 低 | 读 L1 超时之后不释放读脚本可能已授予的 lease | 读代码 | `RedisRemoteStore.read` | 6.9.0 已修复（#701）；中断路径见 B.1 |
| L2 | 低 | 同步的 `SetCache.getAll` 逐个 key 串行访问 L1 | 读代码 | `SetEngine.getAll` | 6.x 待排期 |
| L3 | 低 | 策略 `ttl` 永不过期时，墓碑也永不过期 | 读代码 | `CoherentEngine.versionedTtl` | 文档；v8 `tombstoneTtl` |
| L4 | 低 | namespace 只校验非空：`a` 与 `a:b` 可能映射到相同的存储 key | 读代码 | `CacheSpec` 的 `requireValidName` | 6.x 待排期 |
| L5 | 低 | `addMembersIfReadable` / `removeMembersIfReadable` 的默认实现先读后写、不原子 | 读代码 | `RemoteStore` | v8 §4.3 |
| L6 | 低 | `@CacheEvict(allEntries = true)` 只清本节点 L2；端点的按 key 删除对 `SetCache` 会删除唯一副本 | 读代码 | `CoCacheSpringCache.clear`、`CoCacheEndpoint` | v8 §4.9 |
| L7 | 低 | 格式字节按有符号解析 | 读代码 | `RedisRemoteStore` | 6.9.0 已修复（#701） |
| L8 | 低 | 策略时长不带单位按秒、`cocache.redis.*` 按毫秒；`defaults` 覆盖显式声明 | 读代码 | `CoCacheProperties` | v8 §4.8 |
| L9 | 低 | 创建缓存时 `backend.store` 或工厂抛出会让 namespace 保持占用 | 读代码 | `CacheRuntime` 的注册流程 | 6.10.0 已修复（#711） |
| L10 | 低 | architecture.md §6.3 说“启动时不检查这两点”，与 `SetCacheEvictionCheck` 不符 | 读代码 | `docs/architecture.md` §6.3 | 6.10.0 已修复（#707，随 M11 一起） |

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
