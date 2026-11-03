# 待办清单

截至 2026-11-01（6.10.0 已发布；v8 设计为 RFC 0005 草案 v5：跳过 v7、协程核心、与 6.x 物理隔离）。本文只列**下一步要做的事**与验收标准；目标与分期的依据在 [roadmap](roadmap.md)，设计与论证在 [RFC 0005](rfc/0005-v8-target-architecture.md)，现行规范在 [architecture.md](architecture.md)。完成一项后从本文删除，并在 roadmap 的对应版本中记为已交付。

编号沿用 RFC 0005：附录 A 的审查发现写作 A.xx，第一轮、第二轮对抗验证的发现写作 C / M / P / U，未决问题写作 Q，v8 的里程碑写作 M1–M8（RFC 0005 §10）。

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
- [ ] **切换 `main` 到 v8**（RFC 0005 §10 第 0 步）：
  - 从 `v6.10.0` 切出 `6.x`；`6.x` 上推送触发的 CI 与 wiki 发布改为 `6.x`。
  - 仓库设置（维护者操作）：GitHub Pages 的部署环境 `github-pages` 允许 `6.x`；`6.x` 的分支保护规则与 `main` 一致。
  - `main`：基线 JDK 21；停止发布 wiki；CONTRIBUTING 的分支与发版规则。

## 0. 交接（2026-11-02，供接手者继续推进）

**当前状态**：`main` 是 8.0 开发线。M1（协程核心，#716–#720）与 M2（flight 层与协议，#721–#727）已完成，**M3 SPI 进行中**。拆分与命名已由维护者确认（2026-11-02，RFC 0005 §4.3、§12“M3 之前”），切片与依赖见 §2。M3·0a、M3·0b、M3b、M3a、M3·0c 已合并（#729–#736），M3c 进行中；M4 之前的第三轮对抗验证（RFC 0005 §12）与 M3c 并行，由两路只读会话进行。

**M3 的约定**
- 先删不依赖替代品的遗留（M3·0a、M3·0b、M3·0c），再建新形状；不推倒重来（RFC 0005 §12 决定 7）。
- M3 的能力面只放已有的操作，不顺带改语义；行为变化只有 M3a 的中断规则与 M3·0c 的 foreign RECLAIM，两者都写进 architecture.md 与 changelog。
- 改 `cocache-api` 与 TCK 的形状以 RFC 0005 §4.3 为准，偏离时先回到架构会话讨论。
- JMH：M3a、M3c、M3d 附 `scripts/jmh-compare.sh` 的对比。本机性能足以在 JMH 运行时并行工作（维护者，2026-11-03）：其它会话照常编辑、编译与测试，不因 JMH 停下；同一时间只跑一组 JMH（脚本的机器锁 `/tmp/cocache-jmh.lock` 保证），受干扰的运行由脚本按负载升高与 CV 作废重试。
- 基准要精简（维护者，2026-11-03）：按 diff 改动的开销，每种开销只选它占比最大的基准；开销相同、外围工作更多的基准，以及共用同一路径的基准不重复跑。`l2HitMixed` 照常包含。PR 里写明没跑的基准及理由。
- 一次对比控制在 15 分钟内（维护者，2026-11-03）：先跑筛查，`-r 3 --warmup-iterations 2 --warmup-time 2s --iterations 3 --time 3s`（每次运行约 16 s）；同步基准的中位数退化落在 2%–4% 的格子，只把这一格按默认设置（5 轮、3 s 预热、5 s 测量）补跑，以补跑结果判定。M3a 的筛查：`l1HitViaEngine`、`set`、`l2HitMixed`，1、8 线程，36 次，约 13 分钟（原先 6 个基准、默认设置是 120 次，约 2 小时）。不要在 `./gradlew check` 刚结束、负载还在 7 以上时开跑，否则开头的运行会因 CV 超限反复作废。
- 维护者通知本机暂停时（本机借给别的项目测量），不跑 Gradle、lincheck、JMH，不起 Docker 容器，只提交、推送、改 PR 正文，由 GitHub CI 验证。被打断的 JMH 作废，恢复后整套重跑；不改到 CI 上跑（共享 runner 达不到 architecture.md §13 的方法）。

**CI**
- `Lincheck` 作业（2026-11-03 起）运行 `./gradlew :cocache-core:lincheck`，约 2 分钟，不列入必需检查、失败不阻塞合并，但要查明原因（见 [Lincheck 调查](investigations/lincheck-feasibility.md)）。

**工作方式（M1、M2 一直沿用）**
- 每个切片一个分支（`<type>/<描述>`）与一个 squash 合并的 PR，标题用 Conventional Commits，PR 正文英文。维护者授权架构会话（2026-11-02）：审查通过、CI 全绿后由它合并或开启自动合并；需要 JMH 的切片等表格进入 PR 且达标后才合并。偏离 RFC、未定的公开接口、线格式、新依赖、JMH 不达标仍先问维护者。
- 先开 PR，JMH 跑完再把 `scripts/jmh-compare.sh` 的表格补进 PR 正文。JMH 运行期间不要在本机跑其它 Gradle 任务（会被负载门槛作废，或污染结果）。
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

M3 拆成（维护者确认，2026-11-02；设计见 RFC 0005 §4.3）。已合并：删除 5.x 迁移工具（M3·0a，#729）→ 删除 `Async*` 引擎规范，异步特有用例移到 core 测试（M3·0b，#731）→ `cocache-api` 新增挂起的 `L1Store`、三个能力面、`FutureL1Store` 与 `adapt`、`ForwardingL1Store`，被取消的 future 按 L1 故障处理（M3b，#732；`FutureInvalidationChannel` 随 M3c 加入）→ 同步变体经异步 SPI 等待 L1，被中断的同步调用方等在途命令结束（M3a，#733；JMH 筛查：同步路径最差 −1.4%）→ 没有数据源的缓存拒绝 `foreign-format: reclaim`，条件、按版本与集合写入遇到读不懂的内容一律拒绝，旧 SPI 删除 `foreignAsAbsent` 与整体覆盖的集合写入（M3·0c，#736）。

| 切片 | PR 标题 | 内容 | 依赖 |
|------|---------|------|------|
| M3c | `feat(core)!: run engines and backends on L1Store` | `store()` 返回 `L1Store`、`publish` 挂起（`cocache-api` 加 `FutureInvalidationChannel` 与 `adapt`，同 `FutureL1Store` 的规则）、删 `versionedWrites`；引擎在 `NonCancellable` 中调用 L1；`runtime.create` 检查能力并输出到启动日志；InMemory 原生实现、`HookedBackend` 继承 `ForwardingL1Store`、`ScriptedBackend` 改为 `FutureL1Store`；`BackendProtocolSpec` 改为挂起形状，删 `AsyncBackendProtocolSpec`；Redis 暂以自身的异步方法桥接。JMH | M3a、M3b、M3·0c |
| M3d | `refactor(redis)!: implement L1Store natively and delete the async SPI` | `RedisL1Store`；删 `RedisConnections` 的同步等待与中断分支；删 `RemoteStore`、`AsyncRemoteStore`、`AsyncInvalidationChannel`、`BlockingAsync*`；`Remote*` 改名为 `L1*`；Lettuce 取消的命令（连接重置、关闭）按结果未知的 L1 故障报告，不以 `CancellationException` 失败（§4.3），`L1Store` 的 KDoc 写明这条契约；Redis 全矩阵。JMH | M3c |
| M3e | `test(tck)!: declare L1 capabilities and fail closed` | `expectedCapabilities` 无默认值，断言“提供 = 声明”且稳定；InMemory 经 `FutureL1Store` 跑整套 TCK；`FaultSpec` 覆盖底层命令被取消（按 L1 故障，不是调用方取消）；缩减 Redis 的异步变体矩阵；wiki extending（en + zh）、`skills/cocache-extend` 按最终形状改 | M3d |

| 里程碑 | 要做什么 | 验收 |
|--------|----------|------|
| M1 协程核心 | 在现有用户 API 与 SPI 之下把协议改写为一份 `suspend` 实现（同步经 `runBlocking`、异步经 `future(UNDISPATCHED)`）；然后删除引擎中的异步分支、`Futures` 的续接、定时器与许可等待队列；`kotlinx-coroutines-core` 进入 core，AGENTS.md 的依赖规则同步修改 | 同步 TCK 与 `Async*` TCK 的语义用例全绿，线程模型用例按新模型重写；L2 命中不退化；同步变体的 L1 命中、未命中退化 ≤ 3%；异步 8 线程闭环不低于 6.10 的 90% |
| M2 flight 层与协议 | 合并规则逐 key、`load.timeout`、取消与中断的规则（共享加载在运行时作用域，令牌步骤 `NonCancellable`）、L2 填充改为 `compute` + 按身份复核（A.D4）；L2 key 不含前缀（只在 JMH 不退化时采用） | 复现 A.D4 的交错；随机注入取消的性质测试与确定性多节点仿真接入；`missLoad` 退化 ≤ 2% |
| M3 SPI | `suspend` 的 `L1Store`、`FutureL1Store` 适配、能力访问器、`ForwardingL1Store`、TCK fail closed；删除异步 SPI 与 `Async*` 规范 | TCK 断言“提供的能力 = 声明的能力”，并覆盖 `FutureL1Store` |
| M4 线格式 2 与布局 | 布局标记、带前缀的频道、头部的段（SHA-256 摘要段）、状态 `E`、逐脚本规则；一次性拷贝工具（`Store` / `SetStore`）；`runtime.create` 拒绝会让存储 key 相撞的 namespace（A.L4） | 第三轮对抗验证在合并之前完成；TCK 覆盖各种头部组合与 `E` 的全部脚本；拷贝工具的转换用例（`VERSION_MARK`、集合、TTL）；合并即冻结 8.x 的格式 |
| M5 用户 API | 类型（V1–V3，按 v4 全部实现）、`Lookup.Unknown`、`CoCacheFactory`；`cocache-coroutines` 并入 core；`SetStore` 的同步批量读不再逐个 key 访问 L1（A.L2）；V7 的失败路径分类：“确定未执行 / 结果未知”的异常类型、`CacheWriteException.outcomeUnknown`、派生 `put` 的补偿失效 | 不支持的操作在编译期不可调用；`SetEngineSpec` 的批量用例；`FaultSpec` 覆盖两类写入失败 |
| M6 配置 | 三层配置、字段三态、适用性表、取上限与夹紧、`lock` / `constrain` / `unlock`、6.x 键的启动失败（V10） | 每条规则的用例；启动日志与端点输出生效值与来源 |
| M7 Spring | 回滚时失效事务内的填充、已登记 key 的回源私有、starter 的事务装饰器、`Unknown` 的映射（V11） | 同步、异步、响应式三条路径的用例 |
| M8 检验与文档 | Lincheck（经批准后）、TLA+、architecture.md 重排（V12）、wiki 与升级指南、skills | Lincheck 与 TLC 在 CI 中运行；wiki 构建通过 |

## 3. 需要维护者决定

均见 RFC 0005 §11。

| 问题 | 何时需要 |
|------|----------|
| Q10 修订号或写入标识占用的头部位置 | M4 之前（格式定稿） |
| Q14 把每个 namespace 的类型写入 `cocache:v8:meta:{namespace}`（线格式新增） | M4 之前 |
| Q19 布局标记的拼写 | M4 之前 |
| Q12 全集群清空的帧（是否进入 8.0 的帧格式） | M4 之前 |
| Q15 `unknownRead = ABSENT` 的短暂缓存 | M5 之前 |
| Q11 重置的严格线性化（读写锁） | M2 之后评估 |
| Q5 `cocache-spring` 可选依赖 reactor（响应式事务） | 需要时 |
