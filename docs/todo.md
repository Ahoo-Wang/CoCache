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

**当前状态**：`main` 是 8.0 开发线。M1（协程核心，#716–#720）与 M2（flight 层与协议，#721–#727）已完成，下一步是 **M3 SPI**（见 §2 的表格与 RFC 0005 §4.3）。没有进行中（WIP）的分支或 PR。

**下一步：M3 SPI**
- 目标：`suspend` 的 `L1Store`（Q2 默认采用此名）、`FutureL1Store` 适配、能力访问器、`ForwardingL1Store`、TCK fail closed；删除异步 SPI（`AsyncRemoteStore`、`AsyncInvalidationChannel`、`BlockingAsyncStore`）与 `Async*` 规范。
- 改 `cocache-api` 的接口与 TCK，属于公开 API 变更：动手前先与维护者确认拆分与命名（AGENTS.md“Boundaries”）。
- 建议仿照 M1、M2 按 PR 拆小切片，每片都跑全量 `./gradlew check`，触及命中路径或异步路径的附 JMH。

**待维护者决定**
- 是否在 CI 中加一个单独、不阻塞的作业运行 `./gradlew :cocache-core:lincheck`（约 2 分钟，见 [Lincheck 调查](investigations/lincheck-feasibility.md)）。改工作流，未做。
- §3 中 M3 之前要定的 Q2（`L1Store` 命名）。

**工作方式（M1、M2 一直沿用）**
- 每个切片一个分支（`<type>/<描述>`）与一个 squash 合并的 PR，标题用 Conventional Commits，PR 正文英文；只在维护者说“合并”时合并。
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

| 里程碑 | 要做什么 | 验收 |
|--------|----------|------|
| M1 协程核心 | 在现有用户 API 与 SPI 之下把协议改写为一份 `suspend` 实现（同步经 `runBlocking`、异步经 `future(UNDISPATCHED)`）；然后删除引擎中的异步分支、`Futures` 的续接、定时器与许可等待队列；`kotlinx-coroutines-core` 进入 core，AGENTS.md 的依赖规则同步修改 | 同步 TCK 与 `Async*` TCK 的语义用例全绿，线程模型用例按新模型重写；L2 命中不退化；同步变体的 L1 命中、未命中退化 ≤ 3%；异步 8 线程闭环不低于 6.10 的 90% |
| M2 flight 层与协议 | 合并规则逐 key、`load.timeout`、取消与中断的规则（共享加载在运行时作用域，令牌步骤 `NonCancellable`）、L2 填充改为 `compute` + 按身份复核（A.D4）；L2 key 不含前缀（只在 JMH 不退化时采用） | 复现 A.D4 的交错；随机注入取消的性质测试与确定性多节点仿真接入；`missLoad` 退化 ≤ 2% |
| M3 SPI | `suspend` 的 `L1Store`、`FutureL1Store` 适配、能力访问器、`ForwardingL1Store`、TCK fail closed；删除异步 SPI 与 `Async*` 规范 | TCK 断言“提供的能力 = 声明的能力”，并覆盖 `FutureL1Store` |
| M4 线格式 2 与布局 | 布局标记、带前缀的频道、头部的段（SHA-256 摘要段）、状态 `E`、逐脚本规则；一次性拷贝工具（`Store` / `SetStore`）；`runtime.create` 拒绝会让存储 key 相撞的 namespace（A.L4） | 第三轮对抗验证在合并之前完成；TCK 覆盖各种头部组合与 `E` 的全部脚本；拷贝工具的转换用例（`VERSION_MARK`、集合、TTL）；合并即冻结 8.x 的格式 |
| M5 用户 API | 类型（V1–V3，按 v4 全部实现）、`Lookup.Unknown`、`CoCacheFactory`；`cocache-coroutines` 并入 core；`SetStore` 的同步批量读不再逐个 key 访问 L1（A.L2） | 不支持的操作在编译期不可调用；`SetEngineSpec` 的批量用例 |
| M6 配置 | 三层配置、字段三态、适用性表、取上限与夹紧、`lock` / `constrain` / `unlock`、6.x 键的启动失败（V10） | 每条规则的用例；启动日志与端点输出生效值与来源 |
| M7 Spring | 回滚时失效事务内的填充、已登记 key 的回源私有、starter 的事务装饰器、`Unknown` 的映射（V11） | 同步、异步、响应式三条路径的用例 |
| M8 检验与文档 | Lincheck（经批准后）、TLA+、architecture.md 重排（V12）、wiki 与升级指南、skills | Lincheck 与 TLC 在 CI 中运行；wiki 构建通过 |

## 3. 需要维护者决定

均见 RFC 0005 §11。

| 问题 | 何时需要 |
|------|----------|
| Q2 SPI 改名为 `L1Store`（默认采用） | M3 之前 |
| Q10 修订号或写入标识占用的头部位置 | M4 之前（格式定稿） |
| Q14 把每个 namespace 的类型写入 `cocache:v8:meta:{namespace}`（线格式新增） | M4 之前 |
| Q19 布局标记的拼写 | M4 之前 |
| Q12 全集群清空的帧（是否进入 8.0 的帧格式） | M4 之前 |
| Q15 `unknownRead = ABSENT` 的短暂缓存 | M5 之前 |
| Q11 重置的严格线性化（读写锁） | M2 之后评估 |
| Q5 `cocache-spring` 可选依赖 reactor（响应式事务） | 需要时 |
