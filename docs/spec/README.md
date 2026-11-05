# CoCache 协议的 TLA+ 模型

[`CoCache.tla`](CoCache.tla) 是 8.x 一致性协议的 TLA+ 模型（[RFC 0005](../rfc/0005-v8-target-architecture.md) §4.10，V9），由 TLC 在小规模下穷举全部交错。它与另外三件检验工具分工如下：

| 工具 | 检验对象 | 覆盖方式 |
|------|----------|----------|
| Lincheck（`cocache-core` 的 `*LincheckTest`） | 单节点上真实的 SingleFlight、Caffeine L2 与失效戳 | 模型检验真实代码的交错 |
| 确定性多节点仿真（`MultiNodeSimulationTest`） | 多个真实运行时共享一个注入故障的后端 | 按种子随机搜索交错 |
| TLA+（本目录） | 多节点协议本身：L1 脚本、令牌、版本、L2 栅栏、失效通道 | 穷举小规模下的全部交错，含超时之后仍执行的命令、连接替换、持有令牌崩溃 |

规范的事实来源仍是 [architecture.md](../architecture.md) 与 RFC 0005（下文的 §n 指 architecture.md 的章节，RFC §n 指 RFC 0005 的章节）。模型与它们不一致时，按它们修正模型；模型找到的反例如果说明设计本身有问题，作为发现报告（见“发现”），不改模型绕过它。

## 运行

```bash
scripts/tlc.sh            # CI 的全部配置（Findings*.cfg 除外）
scripts/tlc.sh Cache      # 只运行一个配置
scripts/tlc.sh Findings   # 复现已报告的反例，预期失败
```

`scripts/tlc.sh` 把固定版本的 `tla2tools.jar`（v1.7.4，校验 SHA-256）下载到 `build/tla/`，每个配置的完整输出写到 `build/tla/<配置>.out`。需要 JDK 11 及以上；`TLC_WORKERS`（默认 `auto`）与 `TLC_HEAP`（默认 `4g`）可调。CI 的非阻塞作业 `TLC` 运行全部配置，失败时上传输出。

CI 运行的配置（除 `LateFill` 与 `LivenessReconnect` 外都是 2 个节点、每个节点一个读取者、n1 上有写入方；`VersionedCache` 的配置中 L1 的值、墓碑与 `E` 不到期，见文末的“观察”；带故障的配置不含到期与令牌超时，`MaxNoise = 0`）：

| 配置 | 内容 | 检验的性质 | 不同状态数 | 本机耗时 |
|------|------|------------|-----------|---------|
| [`Cache`](Cache.cfg) | `Cache`，提前刷新，两次提交，到期 | `G2L1` `G2L2` `G2Read` `NoLostInvalidation` | 1,590,876 | 5 s |
| [`CacheTimeouts`](CacheTimeouts.cfg) | `Cache`，一次超时或连接替换（迟到的命令、K14、`RETRY`、P12） | `G2L1` `G2L2` `G2Read` `NoLostInvalidation` | 1,252,063 | 5 s |
| [`CacheFaults`](CacheFaults.cfg) | `Cache`，提前刷新，一次崩溃、丢帧、重复帧、重置或回源失败 | `G2L1` `G2L2` `G2Read` `NoLostInvalidation` | 868,395 | 3 s |
| [`VersionedCache`](VersionedCache.cfg) | `VersionedCache`，`putIfNewer`、`evict`（`E`）、带版本的填充与刷新 | `G2L1` `G2L2` `G2Read` `NoLostInvalidation` `VersionMonotonic` `L2VersionMonotonic` | 1,134,763 | 6 s |
| [`VersionedTimeouts`](VersionedTimeouts.cfg) | `VersionedCache`，一次超时或连接替换 | `G2L1` `G2L2` `G2Read` `NoLostInvalidation` `G2L2LateFill` `VersionMonotonic` | 1,488,478 | 5 s |
| [`VersionedFaults`](VersionedFaults.cfg) | `VersionedCache`，墓碑，一次崩溃、丢帧、重复帧、重置或回源失败 | `G2L1` `G2L2` `G2Read` `NoLostInvalidation` `VersionMonotonic` | 1,781,641 | 7 s |
| [`LateFill`](LateFill.cfg) | `VersionedCache`，一个节点两个读取者，提前刷新，一次连接替换（F2） | `G2L2LateFill` | 3,427,073 | 11 s |
| [`Lagging`](Lagging.cfg) | `VersionedCache`，loader 读从库 | `VersionMonotonic` `L2VersionMonotonic` | 479,367 | 3 s |
| [`LaggingTimeouts`](LaggingTimeouts.cfg) | `VersionedCache`，loader 读从库，一次超时（F3） | `VersionMonotonic` `L2VersionMonotonic` | 313,561 | 2 s |
| [`Liveness`](Liveness.cfg) | `Cache`，弱公平，一次超时 | `TokensReleased` `Converges` | 111,390 | 7 s |
| [`LivenessReconnect`](LivenessReconnect.cfg) | `Cache` 的读取者，弱公平，一次超时或连接替换 | `TokensBounded` | 20,222 | 2 s |
| [`LivenessVersioned`](LivenessVersioned.cfg) | `VersionedCache`，弱公平，一次回源失败 | `TokensReleased` | 72,386 | 3 s |
| [`ConvergesVersioned`](ConvergesVersioned.cfg) | `VersionedCache`，弱公平，L1 可被驱逐，不带故障 | `Converges` | 207,575 | 7 s |

合计约 1270 万个不同状态。

耗时是本机（18 核）的参考值：全部配置约 1.2 分钟、800 CPU 秒；CI 的 4 核 runner 慢 4–6 倍，弱公平的配置检查活性时基本单线程，在 CI 中最慢。故障与规模每加一档，状态数约增加一个数量级（例如两次超时或连接替换的 `Cache`、一次超时或连接替换且两次提交的 `VersionedCache` 都超过 1000 万），所以每个配置只放一类故障；更大的组合可以在本地改参数运行。

## 建模的内容

一个 key，N 个节点共享一个 L1。

- **数据源**：已提交的版本号 `db`（0 表示 key 从未存在，`Deleted` 中的版本表示该版本时已删除）。值以“从哪个版本加载”标识，提交历史就是陈旧度的裁判。`LaggingLoader` 打开时 loader 可以读到任何已提交的旧版本（从库），这在协议之外，只用来检验与版本相关的性质。
- **L1**：一个 key 的状态机 `A`（不存在）、`L`（lease 占位）、`V`、`M`、`E`（RFC §4.7），带令牌（lease 或刷新令牌）、令牌是否已超过 `lease.ttl`、版本段。每个脚本原子执行：`READ`（申请 lease 或刷新令牌）、`FILL`（令牌与 RFC §4.7 的版本规则：更小或没有版本时拒绝并在脚本内清零令牌；相等时续期；更大的版本，以及 `E` 上接受的填充，广播并回复 `Filled(advanced = true)`）、`RELEASE`（`L` 删除，`V` 与 `E` 只清零令牌）、`INVALIDATE`、`PUT_IF_NEWER`（版本相等的 `Stale` 也广播，见 F1）、`EVICT_KEEPING_VERSION`。到期（PX、驱逐）与令牌超过 `lease.ttl` 是任意时刻的事件。
- **节点**：L2 副本与失效戳。L2 填充按 `fillGuarded`（RFC §4.5）分三步：在 compute 内校验戳、写入、按身份复核；单个 key 的淘汰等待进行中的 compute，重置不等待（I-C 的竞态）。
- **读取者**：每个读取者独立执行 `loadThrough`，不经 SingleFlight（P11 的“绕过 SingleFlight 的读取者”，也覆盖超过 `load.timeout` 被注销的 flight）。未命中：取戳 → `READ`（lease）→ 命中 / 回源并 `FILL` / 等待 lease 后重读一次再绕过 L1；失效重试中绕过 L1。提前刷新：每个节点同一时刻一次，取得令牌才回源，没取得只从 L1 重新填充，L1 没有值或读失败时退回 `loadThrough`。`Filled(advanced = true)` 在本节点按写入处理（递增戳、淘汰 L2，不填充 L2）；刷新路径上的版本拒绝以取得令牌时读到的值重新填充 L2（P2）；带版本的 `FILL` 结果未知时同样按写入处理（F2）；其它未写入 L1 的情形以降级副本进入 L2。带版本的缓存的 L2 填充在 compute 中保留版本更新的副本（F3）。
- **写入方**（§6.1）：先提交数据源，再写缓存：戳 → 淘汰 L2 → 脚本 → 戳 → 淘汰 L2。提交与写缓存之间、第一次淘汰与发出脚本之间，本节点与其它节点的读取者都可以取戳、读 L1、回源（第二次递增要防的正是后者，§8.1）。`Cache` 用 `invalidate`，失败模式 `RETRY`（结果未知时登记重试、正常返回，重试期间本节点的读取绕过 L1，重试成功再递增一次戳）；`VersionedCache` 用 `putIfNewer` / `invalidateIfNewer`（数据源中已删除的版本写墓碑）与 `evict`（`E`），结果未知时抛出。
- **失效通道**：原子广播，脚本内发布。帧可以丢失、乱序（集合）、重复投递。节点过滤自己的帧；写入或带版本的 `FILL` 结果未知时不再过滤（P12，#780）：原子广播的后端上，引擎在失败之后的 `localMaxAge` 之内处理自己的帧，不提前结束（存储的 SPI 无法确认被放弃的命令已经不在了）。模型不计时：被放弃的写入与 `FILL` 都不会再执行、它们的帧已经送达之后，任何时刻都可以恢复过滤（`ResumeFiltering`）。这是引擎的窗口最早可能结束而不漏掉那一帧的时刻，引擎的窗口更长，因此对安全性是可靠的抽象；前提是被放弃的命令在失败之后的 `localMaxAge` 之内执行（已发出的命令在命令超时之内执行，或永不执行）。丢帧之后由重新订阅重置该节点（心跳）；任意时刻的重置是故障。
- **命令与连接**：每个节点的命令按发出顺序排队，同一条连接上先进先出，Redis 执行队首。超时（结果未知）之后命令仍可能执行或被丢弃；存储随即在当前连接上异步释放它的令牌（K14），直到执行。连接替换之后，旧连接上的命令可能晚于新连接上的命令执行（P8、P9、P12），也可能永不执行；它们的发出方随后以“结果未知”失败。
- **节点崩溃**：在途的命令可能执行，但没有人收到应答；持有的令牌等 `lease.ttl`（`L` 到期或令牌被接管）。崩溃的节点不重启（重启的节点与一个新节点没有区别）。
- **规模的边界**：`MaxVersion`、`MaxTokens`、`MaxEvicts` 限制工作量；`MaxFaults` 与 `Faults` 限制故障的次数与种类；`MaxNoise` 限制到期与令牌超时的次数。

## 抽象掉的内容

| 不在模型中 | 原因与替代 |
|------------|------------|
| SingleFlight 的合并、读己之写的复核、`load.timeout` | 读取者都自己运行协议，是 SingleFlight 之下全部交错的超集；合并规则与读己之写由 Lincheck 检验 |
| 时间：`ttl`、`localMaxAge`、`lease.wait`、刷新窗口、心跳的时长 | 不计时的模型：到期是任意时刻的事件，G1 只陈述为“写入停止之后最终收敛”（`Converges`），时长上界由仿真检验 |
| 批量（`getAll`、`readAll`、`fillAll`、`putAll`） | 逐 key 的协议相同；跨 key 的原子性不是协议的保证 |
| 派生 `Cache` 的 `put` | 后写者胜（§6.2），并发的 `put` 本身就可以留下旧值；它的结果未知（P9 的补偿失效）以 `ttl` 为界，不属于 G2 |
| `Store`、`VersionedStore`、`SetStore`、条件读写、`purge` | 权威副本没有回源与 lease，不经填充路径；条件写入的 ABA 见 RFC §4.6 |
| 线格式：格式号、未知位、摘要段、损坏与 `RECLAIM` | 格式的分类由 `WireFormat2Test` 与 TCK 覆盖；模型中的内容都是本格式且可读 |
| 非原子广播（`sharded`、探测失败） | 广播与写入分开，写入超时之后补发的广播可能先于写入生效，文档写明以 `localMaxAge` 为界（§6.1） |
| 同步、异步、挂起三种变体，事务门控（I-J） | 它们共用一份协议核心（I-I）；变体由引擎用例覆盖 |
| 失效戳的分段碰撞、多个 key | 碰撞只会多放弃填充 |
| 失效重试的放弃（耗尽、溢出、运行时关闭） | 放弃之后其它节点的陈旧以 L1 剩余寿命为界（§7），计入 `invalidationsDropped`，不属于 G2；模型中的重试总会继续 |
| 写入被确定未执行地拒绝 | 等同于超时之后被丢弃的命令，只是少了令牌的释放与 P12，对安全性没有新的行为 |

## 性质与不变量

| 性质 | 类型 | 对应 |
|------|------|------|
| `G2L1`：填充只带着 L1 上一次销毁令牌的变化（`DEL`、整体覆盖、`E`）之后取得的令牌落地，因而数据不早于那次变化时的提交 | 不变式 | §1 G2 的 L1 条款；I-1 栅栏规则；§8.1 |
| `G2L2`：节点处理完一次失效（本地调用返回、处理了帧、执行了重置）之后，它的 L2 不再出现更早的数据；重置之后，进行中的 compute 写入的副本至多留到填充方自己的复核 | 不变式 | §1 G2 的 L2 条款；I-1、I-C；RFC P11 |
| `G2Read`：读取开始于本节点处理完一次失效之后，返回的数据不早于它 | 不变式 | §1 G2；§6.1 写入方顺序 |
| `NoLostInvalidation`：L1 上执行过的写入，每个存活节点要么已经处理，要么还有帧在途，要么丢帧之后等待重置；不会有节点留着更早的副本却再也收不到任何东西 | 不变式 | §1 G2（失效不被吞没）；I-4、I-L |
| `VersionMonotonic`：经 `VersionedCache` 的写入与回源，L1 的版本不后退、不被抹掉，只有到期能抹掉 | 动作性质 | I-K；RFC §4.7 的 `E` 与 `FILL` 规则 |
| `L2VersionMonotonic`：带版本的缓存从不以更旧的版本替换 L2 中的副本（淘汰与到期可以清空 L2，之后的填充从头开始） | 动作性质 | I-K 的 L2 条款；RFC §4.7 版本拒绝（P2）；F3 的改正 |
| `G2L2LateFill`：比 `G2L2` 更强：超时之后才执行、使版本前进的 `FILL` 也算本节点的一次写入 | 不变式 | RFC §4.7（版本前进的 fill 是一次写入）；F2 的改正（结果未知的带版本 `FILL` 按写入处理） |
| `TokensReleased`：每个取得的令牌最终被填充或释放，除非它超过了 `lease.ttl` | 活性 | I-F；RFC K14 |
| `TokensBounded`：连接替换之下的同一性质，只放过 F4 的接受上界：读取者放弃之后才执行的 `READ` 授予的 lease 留到 `lease.ttl` | 活性 | §9；RFC K14 |
| `Converges`：写入停止之后，各节点的 L2 不依赖 `localMaxAge` 就收敛到最后一次提交 | 活性 | §1 G1 可以不计时陈述的部分 |

裁判是幽灵变量，协议从不读取它们：`l1db`（L1 上一次销毁令牌时的 `db`）、`l1ver`（L1 广播过的最高版本）、`floorS` / `floorR`（节点经单个 key 的失效 / 重置处理到的数据下限）、`floorF`（`G2L2LateFill` 的下限）、`owed`（节点尚未处理的、已在 L1 执行的写入）、`lost`（节点丢过帧、尚未重置）。

## 变异检验

性质不是空洞的：把下列协议缺陷注入模型（每次一处），对应的配置都给出反例。

| 注入的缺陷 | 配置 | 发现它的性质 |
|------------|------|--------------|
| 写入方不做第二次递增与淘汰（§8.1） | `Cache` | `G2L2` |
| 收到远端帧不淘汰 L2 | `Cache` | `G2L2` |
| `fillGuarded` 不复核（重置与进行中的 compute，I-C） | `CacheFaults` | `G2L2` |
| 失效重试期间的读取照常读 L1（§7） | `CacheTimeouts` | `G2L2` |
| `RELEASE` 遇到 `E` 也删除（P1） | `VersionedFaults` | `VersionMonotonic` |
| `FILL` 不比较版本 | `Lagging` | `VersionMonotonic` |
| 刷新路径在版本拒绝后照常写 L2（P2），同时去掉 F3 的规则（P2 单独去掉时被 F3 的规则覆盖） | `Lagging` | `L2VersionMonotonic` |
| L2 的填充不保留更新的副本（去掉 F3 的改正） | `LaggingTimeouts` | `L2VersionMonotonic` |
| 结果未知的带版本 `FILL` 照常降级填充（去掉 F2 的改正） | `LateFill` | `G2L2LateFill` |
| 版本前进的 `FILL` 不淘汰本节点的 L2（P11） | `VersionedCache` | `G2L2` |
| 存储不释放结果未知的命令的令牌（K14） | `Liveness`、`LivenessReconnect` | `TokensReleased`、`TokensBounded` |
| 版本相等的 `Stale` 不广播（F1 改正之前的规则） | `VersionedCache`、`ConvergesVersioned` | `NoLostInvalidation`、`Converges` |

`E` 上的 `READ` 不看持有者就授予 lease，不违反任何安全性：原持有者的 `FILL` 只是因令牌不匹配被拒绝。

## 发现

维护者的决定（2026-11-03）：F1、F2、F3 已改正（F1 随 #762，F2、F3 随 #764），模型以改正之后的规则为准，它们的性质都在 CI 中检验；F4 只改文档，作为接受的上界。

- F2：`LateFill` 检验 `G2L2LateFill`。#764 写明的残余（失败之后、迟到的 `FILL` 执行之前开始的同节点读取仍可能填入旧版本）在原子广播的后端上已经关闭（#780）：结果未知的带版本 `FILL` 同样让本节点在 `localMaxAge` 之内不过滤自己的帧（P12），迟到的 `FILL` 广播的那一帧在本节点照常递增戳、淘汰本地副本，那次读取填入的旧版本随之被淘汰。模型的 `ReaderTimeout` 在这个分支设置 `unfilter`。非原子广播的后端上迟到的命令不带广播，残余仍由 `localMaxAge` 兜底。
  模型生成不了这条残余路径，所以这一改动没有反例可以对照：模型在连接替换时把存储的释放（K14）原位移到新连接上，排在之后的每次读取之前，迟到的 `FILL` 要么先于那次读取执行，要么因令牌已被释放而被拒绝。真实的存储以退避重发失败的释放，之后发出的读取可能先于它执行；要让模型生成这条路径（并检验它已关闭），需要把重发建模为之后任意时刻追加到当前连接的队尾，这会改变所有带连接替换的配置，留作后续。两次连接替换（`LateFill` 加 `MaxFaults = 2`，3,900 万个状态，本地 254 s）在改动之前同样没有违反。
- F3：`Lagging` 与 `LaggingTimeouts` 检验 `L2VersionMonotonic`（I-K 的 L2 条款），它取代了只看刷新路径的 `RefreshKeepsNewer`。
- F4 的反例在 `FindingsReconnectLease.cfg` 中（`scripts/tlc.sh Findings`，严格的 `TokensReleased`，预期失败），不在 CI 中运行。接受的上界以 `TokensBounded` 在 CI 的 `LivenessReconnect` 中检验（参数相同）：放过的只是读取者放弃之后才执行的 `READ` 授予的 lease，其余令牌照常要求被填充或释放。

F1 已改正：维护者选择了改法 (a)（2026-11-03，随 M4c+M4d 实施，RFC §4.7 的表格随之修改），模型以它为规则，`NoLostInvalidation` 与 `Converges` 在带版本的配置中检验。去掉这条规则，`VersionedCache` 与 `ConvergesVersioned` 都给出下面的反例（见“变异检验”）。带版本的 `Converges` 不带故障检验：模型中带版本的写入方在结果未知时抛出、不重放（重放由调用方负责，§6.6），被丢弃的 `putIfNewer` 就再也不会写入。

| 发现 | 性质 | 需要的条件 | 后果的上界 | 配置 |
|------|------|------------|------------|------|
| F1（已改正）：`putIfNewer` 被不广播的填充吸收，其它节点收不到失效 | `NoLostInvalidation`、`Converges` | 无故障 | `degradedLocalMaxAge` 或 `localMaxAge` | 已在 CI 的配置中 |
| F2（已改正）：超时之后才执行的版本前进的 `FILL` 在本节点被过滤 | `G2L2LateFill` | 连接替换 | 直到写入方的 `putIfNewer` 落地 | [`LateFill`](LateFill.cfg) |
| F3（已改正）：刷新路径在结果未知或令牌被抢占时以更旧的值替换副本（P2 不完整） | `L2VersionMonotonic` | loader 读从库，加一次超时 | `degradedLocalMaxAge` | [`LaggingTimeouts`](LaggingTimeouts.cfg)、[`Lagging`](Lagging.cfg) |
| F4（接受的上界）：连接替换之后异步释放先于被排空的 `READ`，lease 无人释放 | `TokensReleased`；CI 检验 `TokensBounded` | 连接替换 | `lease.ttl` | [`FindingsReconnectLease`](FindingsReconnectLease.cfg)；上界：[`LivenessReconnect`](LivenessReconnect.cfg) |

### F1（已改正）：`VersionedCache` 的 `putIfNewer` 被填充吸收时，其它节点收不到失效

`NoLostInvalidation`，改正之前的规则下不需要任何故障（2 个节点，n1 有写入方）：

1. n1 的读取者在不存在的 key 上取得 lease（`L`）。
2. n2 的读取者读到 `Leased`，重读仍是 `Leased`，等待 lease 超时之后绕过 L1 回源，读到版本 0（key 不存在），以降级限龄写入 n2 的 L2。
3. n1 的写入方向数据源提交版本 1。
4. n1 的 loader 读到版本 1，`FILL` 落在 `L` 上：内容没有版本段，照常写入、不广播。
5. 写入方的 `PUT_IF_NEWER(1)` 返回 `Stale(1)`：原子广播的后端上 `Stale` 不广播（§6.6）。
6. n2 的 L2 仍是“不存在”，而 L1 与数据源都是版本 1，之后不会再有帧。陈旧持续到 `degradedLocalMaxAge`；L1 被驱逐之后其它节点保留的旧副本同理，持续到 `localMaxAge`。

L1 中没有版本的状态（初始、到期或驱逐之后）都可能这样：相等或更新的版本经不广播的填充进入 L1，写入方随后的 `putIfNewer` 返回 `Stale`，也不广播。派生的 `Cache` 不受影响：`invalidate` 总是删除并广播。`E` 上的填充已经为“淘汰其它节点降级填充的旧副本”而广播（RFC §4.7），`L` 上的填充没有。两种改法：(a) 原子广播的后端上，`PUT_IF_NEWER` 在当前版本等于给出的版本时也广播（非原子广播的重放规则已经如此，代价是重复投递时多一帧）；(b) 带版本的 `FILL` 落在没有版本的内容上时像 `E` 一样广播（代价是每次带版本的未命中填充多一次 `PUBLISH`）。维护者选择了 (a)。

### F2（已改正）：超时之后才执行、使版本前进的 `FILL`，在本节点被过滤

`G2L2LateFill`（比 `G2L2` 更强的性质），一个节点、两个读取者、一次连接替换：

1. 读取者 1 的副本到达刷新点，取得 `V(1)` 上的刷新令牌；读取者 2（不经 SingleFlight）读 L1 命中 `V(1)`，准备写入 L2。
2. 写入方提交版本 2（它的 `putIfNewer(2)` 还没有发出）。读取者 1 的 loader 读到版本 2，在连接 0 上发出 `FILL(2)`。
3. 连接被替换；读取者 1 以结果未知失败，不写 L2；存储按 K14 在新连接上释放令牌。
4. 旧连接上的 `FILL(2)` 先于这次释放执行：令牌仍匹配，版本前进，写入 `V(2)` 并广播。
5. 本节点过滤自己的帧：P11 的“本节点递增戳并淘汰本地副本”只在 `FILL` 回复“已前进”时执行，P12 的“不再过滤自身帧”当时只覆盖写入（#780 起同样覆盖结果未知的带版本 `FILL`）。读取者 2 写入的版本 1 留在 L2，而 L1 已是版本 2。

写入方随后的 `putIfNewer(2)` 返回 `Stale(2)` 并广播（F1 的改正），这个副本留到那次写入落地或 `localMaxAge`，以先到者为准。按 G2 的字面（本节点没有“处理完”这次填充）这不算违反，但与 RFC §4.7“版本前进的 fill 是一次写入”不一致；改正（#764）：结果未知的带版本 `FILL` 按可能已前进处理，递增戳、淘汰本地副本、不填 L2。模型的 `ReaderTimeout` 照此处理。

### F3（已改正）：刷新路径只在版本拒绝时保留副本，结果未知或令牌不匹配时仍以更旧的值替换它

当时的性质 `RefreshKeepsNewer`（刷新写入 L2 的版本不低于 L1 的版本），`LaggingLoader`（loader 读到从库的旧版本，协议之外）加一次超时：

1. n1 的 L2 有版本 1 的副本，到达刷新点；刷新取得 `V(1)` 上的刷新令牌。
2. loader 从从库读到版本 0（key 尚不存在）。
3. `FILL` 超时，结果未知；存储异步释放令牌（K14）。
4. 刷新路径按 `completeLoad(filled = false)` 以降级限龄把版本 0 写入 L2，替换了版本 1 的副本。

RFC §4.7 的规则（P2）只覆盖 `FILL` 回复版本拒绝（`Rejected(v)`）的情形；结果未知、令牌不匹配（被抢占）时回复不带版本，刷新路径照常降级填充，L2 退回到比 L1 更旧的版本，直到 `degradedLocalMaxAge`。改正（#764）：带版本的缓存的 L2 不按版本后退，`compute` 保留未到期且版本更新的副本（不只在刷新路径上），F2 的改正同时让结果未知的填充不再写 L2。模型的 `Write` 照此处理，性质改为 `L2VersionMonotonic`。

### F4（接受的上界）：连接替换之后，异步释放可能先于被排空的 `READ` 执行，lease 留到 `lease.ttl`

`TokensReleased`（活性），一次连接替换：

1. n2 的读取者在连接 0 上发出 `READ`（申请 lease）。
2. n2 的连接被替换（连接 1）；`READ` 仍在旧连接上，读取者以结果未知失败、绕过 L1 回源。
3. 存储按 K14 在当前连接（连接 1）上异步释放这个令牌：`RELEASE` 先执行，令牌不匹配，什么也不做，释放就此结束。
4. 旧连接上的 `READ` 随后执行，写入 lease 占位 `L(t1)`。没有人持有它，也不会再有释放：全集群对这个 key 的未命中先等 `lease.wait`，直到 `lease.ttl` 到期。

architecture.md §9 曾写道“断线时在途的读取取得的 lease 因此在重连之后就被收回”，前提是释放排在原命令之后（同一连接）；连接替换之后，被排空的命令可能晚于新连接上的命令执行（P8 的前提），这个说法不成立，上界退回 `lease.ttl`。没有连接替换时（`Liveness.cfg`）令牌总会被填充或释放。决定：只改文档。lease 通常在重连之后就被收回，释放与被排空的 `READ` 竞争时至多留到 `lease.ttl`；`LivenessReconnect` 以 `TokensBounded` 检验这个上界。

### 观察：迟到的 `putIfNewer` 越过已到期的版本栅栏

不是协议缺陷，记录在此以免误读 `VersionedCache` 的配置。连接替换之后，旧连接上结果未知的 `putIfNewer(1)` 可以任意晚地执行（P8 的前提）。若此前 `putIfNewer(2)` 已写入、而条目随后到期或被驱逐，迟到的 `putIfNewer(1)` 写入版本 1，写入方节点读到的数据早于它已确认的版本 2。§6.6 以“寿命应覆盖重复投递的时间窗口”约束这一点，I-K 也把到期列为抹掉版本的入口；但被排空的命令的迟到时长不受应用控制，派生缓存所在的 Redis 也可以是 `allkeys-*` 驱逐。因此带版本的配置在检验 G2 时关闭 L1 的到期（`L1Expiry = FALSE`，lease 占位照常到期），`VersionMonotonic` 照常把到期排除在外。
