# 待办清单

截至 2026-10-31（v8 设计确定为 RFC 0005 草案 v4：跳过 v7，不考虑兼容与数据迁移）。本文只列**下一步要做的事**与验收标准；目标与分期的依据在 [roadmap](roadmap.md)，设计与论证在 [RFC 0005](rfc/0005-v8-target-architecture.md)，现行规范在 [architecture.md](architecture.md)。完成一项后从本文删除，并在 roadmap 的对应版本中记为已交付。

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
- [ ] **发布 6.10.0**（6.x 的最后一个功能版本）：
  - 合并 #707、#708、#709、#711。
  - 按 CONTRIBUTING 的发版步骤开 `chore(release): bump version to 6.10.0`，把 changelog 中各 PR 的 `Unreleased` 段落合成一段。
  - 发布后从发布点切出 `6.x` 维护分支；`main` 改为 8.0.0-SNAPSHOT，基线 JDK 21（RFC 0005 §10 第 0 步）。


## 2. v8 里程碑（RFC 0005 §10）

每个里程碑由若干 PR 组成，每个 PR 的 `./gradlew check` 全绿；触及命中路径或异步路径的 PR 附 `scripts/jmh-compare.sh` 的对比。

| 里程碑 | 要做什么 | 验收 |
|--------|----------|------|
| M1 异步视图改在虚拟线程上 | 异步视图的 L2 命中直接返回，未命中把同步调用提交给虚拟线程执行器；然后删除引擎中的异步分支、`Futures` 的续接、定时器与许可等待队列。用户 API 与 SPI 不变 | 现有 `Async*` TCK 全绿；L2 命中（含 `l2HitAsync`）不退化；异步 L1 命中、未命中的 8 线程闭环不低于 6.10 的 90% |
| M2 flight 层与协议 | 合并规则逐 key、`load.timeout`、中断语义（`SourceLoadInterruptedException`）、L2 填充改为 `compute` + 按身份复核（A.D4）、令牌在 `finally` 中守恒；L2 key 不含前缀（只在 JMH 不退化时采用） | 复现 A.D4 的交错；性质测试与带种子的故障仿真接入；`missLoad` 退化 ≤ 2% |
| M3 SPI | `L1Store`（阻塞）、能力访问器、`ForwardingL1Store`、TCK fail closed；删除异步 SPI 与 `Async*` 规范 | TCK 断言“提供的能力 = 声明的能力” |
| M4 线格式 2 与布局 | 布局标记、头部的段、状态 `E`、逐脚本规则；`runtime.create` 拒绝会让存储 key 相撞的 namespace（A.L4） | 第三轮对抗验证在合并之前完成；TCK 覆盖各种头部组合与 `E` 的全部脚本；合并即冻结 8.x 的格式 |
| M5 用户 API | 类型（V1–V3）、`Lookup.Unknown`、`CoCacheFactory`、挂起视图；`SetStore` 的同步批量读不再逐个 key 访问 L1（A.L2） | 不支持的操作在编译期不可调用；`SetEngineSpec` 的批量用例 |
| M6 配置 | 三层配置、字段三态、适用性表、取上限与夹紧、`lock` / `constrain` / `unlock`、6.x 键的启动失败（V10） | 每条规则的用例；启动日志与端点输出生效值与来源 |
| M7 Spring | 回滚时失效事务内的填充、已登记 key 的回源私有、starter 的事务装饰器、`Unknown` 的映射（V11） | 同步、异步、响应式三条路径的用例 |
| M8 检验与文档 | Lincheck（经批准后）、TLA+、architecture.md 重排（V12）、wiki 与升级指南、skills | Lincheck 与 TLC 在 CI 中运行；wiki 构建通过 |

## 3. 需要维护者决定

均见 RFC 0005 §11。

| 问题 | 何时需要 |
|------|----------|
| Q17 是否保留 sans-IO 状态机（换取确定性仿真） | M2 之前 |
| Q18 Lincheck 作为测试依赖 | M2 之前 |
| Q2 SPI 改名为 `L1Store`（默认采用） | M3 之前 |
| Q10 修订号或写入标识占用的头部位置 | M4 之前（格式定稿） |
| Q14 把每个 namespace 的类型写入 `cocache:v8:meta:{namespace}`（线格式新增） | M4 之前 |
| Q19 布局标记的拼写 | M4 之前 |
| Q6 摘要算法（默认保留 SHA-1） | M4 之前 |
| Q12 全集群清空的帧（是否进入 8.0 的帧格式） | M4 之前 |
| Q15 `unknownRead = ABSENT` 的短暂缓存 | M5 之前 |
| Q11 重置的严格线性化（读写锁） | M2 之后评估 |
| Q5 `cocache-spring` 可选依赖 reactor（响应式事务） | 需要时 |
