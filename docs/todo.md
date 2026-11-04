# 待办清单

截至 2026-11-03（6.10.0 已发布；v8 设计为 RFC 0005 草案 v6：跳过 v7、协程核心、与 6.x 物理隔离，三轮对抗验证已完成）。本文只列**下一步要做的事**与验收标准；目标与分期的依据在 [roadmap](roadmap.md)，设计与论证在 [RFC 0005](rfc/0005-v8-target-architecture.md)，现行规范在 [architecture.md](architecture.md)。完成一项后从本文删除，并在 roadmap 的对应版本中记为已交付。

编号沿用 RFC 0005：附录 A 的审查发现写作 A.xx，第一轮、第二轮对抗验证的发现写作 C / M / P / U，第三轮写作 K / S、维护者对它的决定写作 D1–D8，未决问题写作 Q，v8 的里程碑写作 M1–M8（RFC 0005 §10）。

## 1. 发布跟进

- [ ] **确认 6.9.1 已上 Maven Central**。
  - 发布 v6.9.1 触发的 [Packages Deploy](https://github.com/Ahoo-Wang/CoCache/actions/runs/40867726421) 要成功。
  - `cocache-core` 与 `cocache-spring-boot-starter` 的 6.9.1 要能下载。
  - 失败时按 run 页面的原因重跑，或修复后重发。
- [ ] **通知下游升级时要改的配置**（6.9.0 起没有 loader 的缓存默认 fail closed，见 changelog）：
  - CoSec（依赖 6.7.0）：
    - 撤销表 `revokedToken` / `revokedSubject` 显式配置 `read-failure: degrade`。否则 Redis 故障时撤销检查从放行变成报错（CoSec 的用例 `anUnreadableStoreMeansNotRevoked` 会发现）。
    - `MembershipCache` 视需要配置 `invalidate-failure: retry`。
  - Wow（依赖 6.8.0）：不需要改代码。
  - Spring Cache 用户：没有 loader 的缓存上，`@CacheEvict` 失败改为抛出；要保持 6.8 的重试，配置 `invalidate-failure: retry`。
- [ ] **6.x 缺陷 [#747](https://github.com/Ahoo-Wang/CoCache/issues/747)**（2026-11-03 登记，维护者：先登记、继续推进 v8）：断线时 Lettuce 以同一个异常实例使在途命令全部失败，`RedisRemoteStore` 把它 suppress 到自己身上，抛出 `IllegalArgumentException`；批量读因此漏释放其余 key 的 lease，各节点等到 `lease.ttl`。`main` 已在 #746 修复；6.x 的修复与发版由维护者安排。
- [ ] **切换 `main` 到 v8**（RFC 0005 §10 第 0 步）：
  - 从 `v6.10.0` 切出 `6.x`；`6.x` 上推送触发的 CI 与 wiki 发布改为 `6.x`。
  - 仓库设置（维护者操作）：GitHub Pages 的部署环境 `github-pages` 允许 `6.x`；`6.x` 的分支保护规则与 `main` 一致。
  - `main`：基线 JDK 21；停止发布 wiki；CONTRIBUTING 的分支与发版规则。

## 0. 交接（2026-11-02，供接手者继续推进）

**当前状态**（2026-11-03）：`main` 是 8.0 开发线。M1（协程核心，#716–#720）、M2（flight 层与协议，#721–#727）与 M3（SPI，#729–#751）已完成。**M4 进行中**（§2）：M4a 已合并（#752），M4a′ 与 M4b 并行。维护者决定 8.x **不考虑兼容、不背历史债务**（2026-11-03，RFC 0005 §12）：不提供迁移工具，布局去掉版本标记。

**第三轮对抗验证已完成**（2026-11-03）：RFC 0005 修订为草案 v6，44 条发现（K1–K20、S1–S24）全部处理，维护者确认 D1–D8“全部按推荐”（RFC 0005 §12）。现行代码的缺陷不等 RFC：S5、S6（Spring，main 与 6.x）与 K5、K17（core，main）各自修复；K12、K14、D6 的 SPI 变更转给 M3d；其余按 §2 进入 M4–M8。

**M3 的约定**
- 先删不依赖替代品的遗留（M3·0a、M3·0b、M3·0c），再建新形状；不推倒重来（RFC 0005 §12 决定 7）。
- M3 的能力面只放已有的操作，不顺带改语义；行为变化只有 M3a 的中断规则与 M3·0c 的 foreign RECLAIM，两者都写进 architecture.md 与 changelog。
- 改 `cocache-api` 与 TCK 的形状以 RFC 0005 §4.3 为准，偏离时先回到架构会话讨论。
- JMH：M3a、M3c、M3d 附 `scripts/jmh-compare.sh` 的对比。本机性能足以在 JMH 运行时并行工作（维护者，2026-11-03）：其它会话照常编辑、编译与跑单个测试类，不因 JMH 停下；同一时间只跑一组 JMH（脚本的机器锁 `/tmp/cocache-jmh.lock` 保证），受干扰的运行由脚本按负载升高与 CV 作废重试。
- 但整套构建会把负载推到 9–12（M3c 的筛查因此 60 次作废 40 次、拖过一小时）：锁目录存在时，其它会话不开 `./gradlew check`、lincheck 与整套测试。逐格补跑之间锁会释放几秒，所以门槛是锁**连续 2 分钟**不存在：`while :; do while [ -d /tmp/cocache-jmh.lock ]; do sleep 30; done; sleep 120; [ -d /tmp/cocache-jmh.lock ] || break; done; ./gradlew check`。有效配对少于 3 的格子单独补跑。
- 基准要精简（维护者，2026-11-03）：按 diff 改动的开销，每种开销只选它占比最大的基准；开销相同、外围工作更多的基准，以及共用同一路径的基准不重复跑。`l2HitMixed` 照常包含。PR 里写明没跑的基准及理由。
- 一次对比控制在 15 分钟内（维护者，2026-11-03）：先跑筛查，`-r 3 --warmup-iterations 2 --warmup-time 2s --iterations 3 --time 3s`（每次运行约 16 s）；同步基准的中位数退化落在 2%–4% 的格子，只把这一格按默认设置（5 轮、3 s 预热、5 s 测量）补跑，以补跑结果判定。M3a 的筛查：`l1HitViaEngine`、`set`、`l2HitMixed`，1、8 线程，36 次，约 13 分钟（原先 6 个基准、默认设置是 120 次，约 2 小时）。不要在 `./gradlew check` 刚结束、负载还在 7 以上时开跑，否则开头的运行会因 CV 超限反复作废。
- 维护者通知本机暂停时（本机借给别的项目测量），不跑 Gradle、lincheck、JMH，不起 Docker 容器，只提交、推送、改 PR 正文，由 GitHub CI 验证。被打断的 JMH 作废，恢复后整套重跑；不改到 CI 上跑（共享 runner 达不到 architecture.md §13 的方法）。

**CI**
- `Lincheck` 作业（2026-11-03 起）运行 `./gradlew :cocache-core:lincheck`，约 2 分钟，不列入必需检查、失败不阻塞合并，但要查明原因（见 [Lincheck 调查](investigations/lincheck-feasibility.md)）。

**工作方式（M1、M2 一直沿用）**
- 每个切片一个分支（`<type>/<描述>`）与一个 squash 合并的 PR，标题用 Conventional Commits，PR 正文英文。维护者授权架构会话（2026-11-02）：审查通过、CI 全绿后由它合并或开启自动合并；需要 JMH 的切片等表格进入 PR 且达标后才合并。偏离 RFC、未定的公开接口、线格式、新依赖、JMH 不达标仍先问维护者。
- 先开 PR，JMH 跑完再把 `scripts/jmh-compare.sh` 的表格补进 PR 正文。JMH 运行期间本机其它构建的限制见上面“M3 的约定”。
- 本地 Redis 与环境变量的注意事项见 AGENTS.md“Commands”；测试前取消所有 `SPRING_DATA_REDIS_*` 变量。
- 每个修复都要有复现用例。协议改动除了常规用例，再用三件工具检查：`TokenConservationPropertyTest`（令牌守恒）、`MultiNodeSimulationTest`（确定性多节点仿真，`-PsimSeed=<n>` 重放）、`./gradlew :cocache-core:lincheck`（单节点交错的模型检验）。变更行为时同步 architecture.md 与 wiki（en + zh）。

**M2 期间留下的已知事项**
- 异步数据源的超时仍由 JDK 延迟线程按真实时间计时（`completeOnTimeout`），仿真中的加载总在它触发前完成；若改为协程计时，需附 JMH。
- Lincheck 操作集尚未加入 `get(key, loader)`、`getAll`、`put`、`clearLocal` 与调用方取消（RFC §4.10）。
- 失效戳与 SingleFlight 仍按存储 key（L2 已按编码后的 key，#727）；只有在命中路径之外有收益时才值得改。

## 2. v8 里程碑（RFC 0005 §10）

每个里程碑由若干 PR 组成，每个 PR 的 `./gradlew check` 全绿；触及命中路径或异步路径的 PR 附 `scripts/jmh-compare.sh` 的对比。

M1 已完成：基础设施与 `SetCache` 读路径（#716）→ 单 key 读与提前刷新（#717）→ 批量读（#718）→ 写入（含 `VersionedCache`，#719）→ 数据源适配改为挂起实现，删除 `Futures` 的续接、定时器与许可等待队列（#720）。挂起视图直接调用核心推迟到 M5：`cocache-coroutines` 并入 core 之前，它只能经公开 API 调用核心，为此新增的公开接口会在 M5 删除。

M2 拆成：L2 填充改为 `compute` + 按身份复核（M2a，#721）→ 合并规则逐 key、leader 被中断或取消时跟随者自行运行（M2b，#722）→ `load.timeout`，`LoadPolicy.asyncTimeout` 并入它（M2c，#723）→ L1 命令不可取消地等待、随机注入故障与取消的令牌守恒性质测试（M2d，#724）→ 确定性多节点仿真：测试调度器与虚拟时间、以数据源的提交历史检查 G1 与 G2（M2e，#725）→ Lincheck 可行性实验（M2f，#726：可行，有条件，见 [调查](investigations/lincheck-feasibility.md)；单独的 `lincheck` 任务，不在 `check` 中）。`kotlinx-coroutines-test` 作为测试依赖已批准；Lincheck（Q18）先做可行性实验，可行再正式加入（维护者决定，2026-11-02）。L2 key 不含前缀：JMH 中单个缓存的 L2 命中约 2.6 倍、`l2HitMixed` 1 线程 +68%、8 线程 +8%，L1 命中不变，采用（#727）。**M2 已完成。**

M3 拆成（维护者确认，2026-11-02；设计见 RFC 0005 §4.3）。已合并：删除 5.x 迁移工具（M3·0a，#729）→ 删除 `Async*` 引擎规范，异步特有用例移到 core 测试（M3·0b，#731）→ `cocache-api` 新增挂起的 `L1Store`、三个能力面、`FutureL1Store` 与 `adapt`、`ForwardingL1Store`，被取消的 future 按 L1 故障处理（M3b，#732；`FutureInvalidationChannel` 随 M3c 加入）→ 同步变体经异步 SPI 等待 L1，被中断的同步调用方等在途命令结束（M3a，#733；JMH 筛查：同步路径最差 −1.4%）→ 没有数据源的缓存拒绝 `foreign-format: reclaim`，条件、按版本与集合写入遇到读不懂的内容一律拒绝，旧 SPI 删除 `foreignAsAbsent` 与整体覆盖的集合写入（M3·0c，#736）→ 引擎与后端改为 `L1Store`，`publish` 挂起，`runtime.create` 检查能力并输出到启动日志，Redis 暂经 `FutureL1Store.adapt` 桥接（M3c，#741）→ Redis 原生挂起的 `RedisL1Store`，删除异步 SPI 与桥接，`Remote*` 改名为 `L1*`，Lettuce 取消的命令按结果未知的 L1 故障报告；第三轮转来的 SPI 变更：`fill` 返回 `L1Fill`、`reclaim` 收 `ContentFingerprint`（K12），除确定未执行外的失败都异步释放令牌、失败的释放按退避重发（K14），读取可带这次调用的 `timeout`（D6）（M3d，#746；JMH 筛查：L1 最差 −1.4%，`l2HitMixed` 在噪声内）。

| 切片 | PR 标题 | 内容 | 依赖 |
|------|---------|------|------|
| M3e | `test(tck)!: declare L1 capabilities and fail closed` | `expectedCapabilities` 无默认值，断言“提供 = 声明”且稳定；InMemory 经 `FutureL1Store` 跑整套 TCK；`FaultSpec` 覆盖底层命令被取消（按 L1 故障，不是调用方取消）；缩减 Redis 的异步变体矩阵；wiki extending（en + zh）、`skills/cocache-extend` 按最终形状改 | M3d |

M4 拆成（架构会话，2026-11-03；设计见 RFC 0005 §4.3、§4.7、§8）。约定：
- **不兼容 6.x**（维护者，2026-11-03）：没有迁移工具，6.x 与 8.x 不共存；格式 1 与格式 0 一样按读不懂处理（派生缓存回收后回源，没有数据源的缓存 `FAIL`）。代码、KDoc 与文档只描述 8.x 的行为，不写 6.x 的历史；每个切片清理它碰到的地方，其余由 M4·清理 收尾。
- **冻结**：M4a、M4a′、M4b–M4d 全部合并即冻结 8.x 的格式（RFC 0005 §4.7 的定稿项）。M4b → M4c → M4d 都改 `RedisScripts`，依次进行；M4a′ 与它们的文件基本不重叠，可以并行。
- **M5 之前的临时规则**：M5 引入 `Store` / `VersionedStore` 之前，由**没有数据源的标量缓存**充当权威标量。它的每次标量写入（`put`、`putAll`、`fill`、`putIfMatch`）带写入标识（M4b）与摘要段（M4c），条件读写只认带摘要段的值。M5 分型之后，改由 `Store` / `VersionedStore` 写入，`externallySourced()` 的 `Cache` 不带段。这条规则只在开发中存在，不随 8.0 发布。
- **SPI**（RFC 0005 §4.3）：频道带上前缀之后，后端要分别知道前缀与 namespace，`store`、`subscribe`、`publish` 改收 `Keyspace(keyPrefix, namespace)`（M4a）；`Envelope.Scalar` 带写入标识与摘要，由引擎给出，存储按 §4.7 写成段、读取时原样返回（M4b、M4c）；`VersionedL1Store` 加 `evictKeepingVersion`（M4d）。形状由切片在 PR 中定稿，架构会话审查。

已合并：v8 的 key 布局、带前缀的频道、namespace 字符集、SPI 的 `Keyspace`、按存储探测实际频道（K8）、读不懂的失效帧按 `onReset` 处理（D3）（M4a，#752）。

| 切片 | PR 标题 | 内容 | 依赖 |
|------|---------|------|------|
| M4a′ | `refactor!: drop the v8 layout marker` | 去掉布局标记（维护者确认，2026-11-03）：存储 key `{keyPrefix}{namespace}:{encodedKey}`、频道 `{keyPrefix}inv:{namespace}`、内部 key `{keyPrefix}!…`（预留 `{keyPrefix}!meta:{namespace}`）、探测 key / 频道 `cocache:!probe:{a}` / `cocache:!probe:{b}`；ACL 与运维文档（en + zh）、升级说明（停掉 6.x → 删除没有数据源的缓存的旧 key → 启动 8.x）。namespace 字符集、按 keyspace 登记、K8、D3 与 `Keyspace` 不变 | M4a |
| M4b | `feat!: wire format 2 header with version segment and extension area` | 全部内容（含 `L`、`M` 与集合）改为格式 2：核心头部的标志位（6.x 的保留字节）、版本段（bit 0，取代 `VERSION_MARK`）、扩展区（bit 7）的 TLV 帧格式与写入标识（TLV 类型 1，D2；TLV 长度的编码在此定稿）；载荷偏移由标志位计算，脚本与客户端共用一个函数；未知的必须理解的位等同于更高格式；格式 1 按读不懂处理，删除 `VERSION_MARK` 的识别与 `foreign-format`；`READ` 授予令牌之前先检查格式号（K15），带版本段的内容暂不授予令牌（同 6.x，M4d 放开）；`FILL` / `RELEASE` 以 `GETRANGE 0 35` 只读核心头部（K19）；版本段不得写 `NO_VERSION`（K15）；集合的 `!f = '2'` 与 `!m`（K10）。TCK 与 Redis 用例覆盖各种头部组合（无段、只有版本段、只有扩展区、两者都有）、未知的必须理解的位、未知 TLV 的跳过、集合的 `!m`；architecture.md §3.3 改写。JMH | M3e |
| M4c | `feat!: SHA-256 digest segment and reader verification` | `Digest` 改为 SHA-256 并带算法标识（D1）；摘要段（bit 1）由写入方在客户端计算；`READ_IF_CHANGED` / `PUT_IF_MATCH` 以 `GETRANGE 0 67` 只比较头部，有载荷却没有摘要段的值拒绝；读取方拿到载荷时重算并比较，不一致或缺摘要段按损坏 `FAIL`（K3）；带摘要段的内容：`READ` 不授予 lease 或刷新令牌、`RECLAIM` 拒绝，派生读者读到时计数 `cocache.type.mismatch` 并告警（D4）。新基准 `conditionalUnchanged` 与权威 L1 读取（1 KB / 1 MB / 8 MB，§7）；`MEMORY USAGE` 对照（§7）。JMH | M4b |
| M4d | `feat!: state E, evictKeepingVersion and versioned fills` | 状态 `E` 与 §4.7 的逐脚本表（P1）；`VersionedL1Store.evictKeepingVersion`，端点对带版本的缓存按 key 删除时改用它；`FILL` 的版本规则：更小或 `NO_VERSION` 拒绝并在脚本内清零令牌、相等续期不广播、更大写入并广播，返回 `Filled(advanced)` / `Rejected(currentVersion)`；引擎：版本前进的填充按写入处理（递增戳、淘汰本地副本、不填 L2），版本拒绝时刷新路径保留副本并退避，未命中路径（lease 在 `E` 上）返回回源值、以降级限龄写入 L2、计入 `cocache.fill.rejections`；带版本的内容授予刷新令牌（`VersionedCache` 支持提前刷新，写进 changelog）；读到 `E` 或未知标志位的副本不设刷新点（P14）；`RECLAIM` 遇到带版本的内容拒绝（K15）。TCK 覆盖 `E` 的全部脚本与版本规则；`TokenConservationPropertyTest`、`MultiNodeSimulationTest` 覆盖 `E` 上的 lease。JMH | M4c |
| M4·清理 | `chore!: remove code kept only for older versions` | 删除只为旧版本保留的代码（RFC 0005 §5）：`CachePolicy` 的二进制兼容构造函数；`CoCacheSpringCacheManager` 的废弃构造函数与 `DEFAULT_EXECUTOR`，基线 JDK 21 之后虚拟线程不再经反射取得；starter 的 `RemovedProperties`；main 源码、KDoc 与 docs / wiki / skills 中描述 6.x、5.x 历史的文字（`git grep -nE '6\.[0-9]|6\.x|5\.x'`），只写现行行为；只验证这些兼容行为的测试 | M4a′、M4b |

| 里程碑 | 要做什么 | 验收 |
|--------|----------|------|
| M1 协程核心 | 在现有用户 API 与 SPI 之下把协议改写为一份 `suspend` 实现（同步经 `runBlocking`、异步经 `future(UNDISPATCHED)`）；然后删除引擎中的异步分支、`Futures` 的续接、定时器与许可等待队列；`kotlinx-coroutines-core` 进入 core，AGENTS.md 的依赖规则同步修改 | 同步 TCK 与 `Async*` TCK 的语义用例全绿，线程模型用例按新模型重写；L2 命中不退化；同步变体的 L1 命中、未命中退化 ≤ 3%；异步 8 线程闭环不低于 6.10 的 90% |
| M2 flight 层与协议 | 合并规则逐 key、`load.timeout`、取消与中断的规则（共享加载在运行时作用域，令牌步骤 `NonCancellable`）、L2 填充改为 `compute` + 按身份复核（A.D4）；L2 key 不含前缀（只在 JMH 不退化时采用） | 复现 A.D4 的交错；随机注入取消的性质测试与确定性多节点仿真接入；`missLoad` 退化 ≤ 2% |
| M3 SPI | `suspend` 的 `L1Store`、`FutureL1Store` 适配、能力访问器、`ForwardingL1Store`、TCK fail closed；删除异步 SPI 与 `Async*` 规范 | TCK 断言“提供的能力 = 声明的能力”，并覆盖 `FutureL1Store` |
| M4 线格式 2 与布局 | 布局（没有版本标记）、带前缀的频道、namespace 字符集 `[A-Za-z0-9._-]+` 与内部 key `{keyPrefix}!…`（D5，A.L4 不再可能）、按存储探测实际频道（K8）；头部的段（SHA-256 摘要段，读取方重算校验，`Digest` 带算法标识，D1）、扩展区的 TLV 与写入标识（D2）、状态 `E`、读不懂的失效帧按 `onReset` 处理并预留帧版本 2（D3）、集合的 `!f = '2'` 与 `!m`（K10）；逐脚本规则，含带摘要段的内容（D4）、`RECLAIM` 与格式号、禁止 `NO_VERSION`（K15）、`FILL` / `RELEASE` 只读核心头部（K19）；格式 1 按读不懂处理；不提供迁移工具；删除只为旧版本保留的代码 | TCK 覆盖各种头部组合（含扩展区）、`E` 的全部脚本、未知帧版本触发 `onReset`、带摘要段内容的拒绝、读取方的摘要校验、集合的 `!m`、含 `:` 的 namespace 启动失败、ACL 拒绝实际频道时启动失败、派生缓存回收格式 1 的内容；合并即冻结 8.x 的格式 |
| M5 用户 API | 类型（V1–V3，按 v4 全部实现）、`Lookup.Unknown` 与 `unknownRead` 的取值 `THROW` / `ABSENT`（S16）、`CoCacheFactory`（校验 `type` 是接口，区分声明接口与手写实现，S21）；`cocache-coroutines` 并入 core：挂起变体的元素取自调用方、调度器取自运行时（K2、K6），重入守卫增加上下文元素（K7）；带版本数据源以 `Versioned(null, w)` 表达删除（K16）；`Store` 的类型默认值 `localMaxAge` 30 s、`absentTtl` 10 s（D7）；按缓存的读超时接入引擎（D6）；`InvalidationListener` 收到编码 key、`keyPrefix` 为空时启动告警（S4）；评估被取消的写入整体放进 `NonCancellable`（K18）；`SetStore` 的同步批量读不再逐个 key 访问 L1（A.L2）；V7 的失败路径分类：“确定未执行 / 结果未知”的异常类型、`CacheWriteException.outcomeUnknown`、派生 `put` 的补偿失效 | 不支持的操作在编译期不可调用；`SetEngineSpec` 的批量用例；`FaultSpec` 覆盖两类写入失败；引擎用例：`Unconfined` 调用方不在后端的完成线程上恢复、loader 看得到调用方的 `ThreadContextElement`、同步变体的同线程重入仍快速失败 |
| M6 配置 | 三层配置、字段三态、适用性表、取上限与夹紧、`lock` / `constrain` / `unlock`（V10）；“字段 × 层”的语义表、显式哨兵（`forever` / `off` / `unlimited`）、overrides 只收紧陈旧界限并钳制按调用的寿命、有严格序的模式字段取更严、等待界限与带版本类型的 `ttl` 不进 overrides（S8、S9、S10）；`constrain` 的值域含 `Forever`、customizer 写锁定字段时抛出（S12）；`cocache.key-prefix`（S17；不检测 6.x 的键）；按缓存的读超时的配置键（D6）；每种 spec 的默认策略常量（S23）；`read-failure` 与 `unknown-read` 的适用性（S11、S16）；“未分层”的标注（S20）；驱逐检查一律失败的情形与 `store-eviction-check` 的取值（S22） | 每条规则的用例；启动日志与端点输出生效值与来源 |
| M7 Spring | 回滚时失效事务内的填充、已登记 key 的回源私有、starter 的事务装饰器、`Unknown` 的映射（V11）；事务上下文在调用入口捕获、异步与挂起变体不门控不记录（S7）；记录进入任何共享层的 key、事务内 leader 的结果不与事务外的跟随者共享（S15）；按 (namespace, 编码 key) 匹配（S24）；可选的 `CacheErrorHandler`、`afterCommit` 的异常交给它（S18）；缓存通知与事务通知 order 相同时告警（S19）。S5、S6 已在 main 与 6.x 修复，装饰器沿用 | 同步、异步、响应式三条路径的用例；NESTED 与 savepoint 回滚、降级填充回滚、两种通知嵌套的用例 |
| M8 检验与文档 | Lincheck（已批准，CI 的非阻塞作业，#738）、TLA+、architecture.md 重排（V12）、wiki 与升级说明（含下游清单）、skills | Lincheck 与 TLC 在 CI 中运行；wiki 构建通过 |

## 3. 需要维护者决定

均见 RFC 0005 §11。

| 问题 | 何时需要 |
|------|----------|
| Q11 重置的严格线性化（读写锁） | M2 之后评估 |
| Q5 `cocache-spring` 可选依赖 reactor（响应式事务） | 需要时 |
