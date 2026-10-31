# 待办清单

截至 2026-10-29（6.9.1 发布）。本文只列**下一步要做的事**与验收标准；目标与分期的依据在 [roadmap](roadmap.md)，设计与论证在 [RFC 0005](rfc/0005-v8-target-architecture.md)，现行规范在 [architecture.md](architecture.md)。完成一项后从本文删除，并在 roadmap 的对应版本中记为已交付。

编号沿用 RFC 0005：附录 A 的审查发现写作 A.xx，第一轮、第二轮对抗验证的发现写作 C / M / P / U，未决问题写作 Q。

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

## 2. 6.x 待排期（不破坏兼容）

每项都要有复现用例：修复前失败，修复后通过（AGENTS.md）。

| 项 | 来源 | 要做什么 | 验收 |
|----|------|----------|------|
| L2 填充可线性化 | A.D4、C4 | `fillLocal` 改为在 Caffeine `compute` 内校验失效戳并写入，写后以 `remove(k, entry)` 按身份复核（RFC 0005 §4.5） | 复现“写入方返回之后旧副本仍可见”的交错（latch 或 Lincheck）；`missLoad` 的 JMH 退化 ≤ 2% |
| 驱逐策略检查 | M11、U19 | 改读 `INFO memory` 的 `maxmemory_policy`；按声明检查，而不只检查已创建的缓存；`volatile-*` 且有 `ttl` 有限的唯一副本时告警 | 禁用 `CONFIG` 时仍能检查的用例 |
| 端点对未知缓存的 `DELETE` | M13 | 返回 404，不再返回 204 | 端点测试 |
| architecture.md §6.3 | A.L10 | 删掉“启动时不检查这两点”，改为描述 `SetCacheEvictionCheck` | — |
| `SetCache.getAll` 批量化 | A.L2 | 同步路径不再逐个 key 访问 L1，与异步路径一样并发 | `SetEngineSpec` 中的批量用例 |
| namespace 前缀重叠 | A.L4 | 拒绝互为前缀、会让存储 key 相撞的 namespace（`a` 与 `a:b`） | `CacheRuntime` 的用例 |
| L2 key 不含前缀 | RFC 0005 §4.5 | 用编码后的 key 作为 L2、失效戳与 SingleFlight 的 key，收到帧时剥掉前缀 | 只在 `scripts/jmh-compare.sh -b l2Hit,l2HitViaInterface,l2HitMixed -t 1,8` 不退化时采用 |
| 机器检验 | RFC 0005 §4.10 | 引入 Lincheck，检验 L2 栅栏与 SingleFlight，操作集含 `onReset` / `clearLocal`；新增 TLA+ 模型 `docs/spec/CoCache.tla` | CI 中运行；Lincheck 是新的测试依赖，须先经维护者批准 |

## 3. 7.x

| 项 | 来源 | 要做什么 |
|----|------|----------|
| starter 的事务装饰器 | U3、RFC 0005 §4.9 | 取代 `TransactionAwareCacheManagerProxy`：`evict` 经 `TransactionalInvalidator`，提交与回滚都失效；默认在回滚时失效写事务内凭 lease 写进 L1 的 key |
| “没有数据源”的告警 | U12 | 移到 `CacheRuntime.create`（core），让库创建的缓存也收到；迁移报告与 `cocache.migration.strict` |
| 协议核心 sans-IO 重构 | V5 | 每条路径一个 PR（单 key 读 → 批量 → 刷新 → 写入），同步与异步 TCK 证明等价，JMH 把关 |
| v8 形状的 SPI | V4 | 异步为主形状、`BlockingRemoteStore`、抽象的能力访问器、`ForwardingRemoteStore`、参数化 TCK；与旧接口并存，旧接口标记弃用 |

## 4. 7.B（桥接版本）

- [ ] **前置：格式定稿**。在 7.B 发布之前定下 RFC 0005 §4.7 的全部段与状态：版本段、摘要段、扩展区、状态 `E`，以及 Q10 的修订号或写入标识占用的位置。
- [ ] **前置：第三轮对抗验证**。重点见 RFC 0005 §12 末尾：格式定稿与跨版本脚本矩阵、三层配置的取上限与夹紧、两个事务机制的组合、前向兼容声明能否在升级前清零告警。
- [ ] **实现**：
  - 读格式 2（含 `E` 与扩展区）；
  - 没有数据源的 `Cache` 停止申请 lease；
  - 配置别名：`overrides.*`、`unknown-read`、`load-timeout`；
  - 前向兼容的声明；
  - 告警：驱逐检查的告警模式、端点写操作的告警、`allEntries = true` 的告警。

## 5. 需要维护者决定

均见 RFC 0005 §11。

| 问题 | 何时需要 |
|------|----------|
| Q10 修订号或写入标识占用的头部位置 | 7.B 之前（格式定稿） |
| Q16 7.B 中 `cocache-api` 的新增：`externallySourced()`、`@CoCache(source = …)`、`unknownRead` | 7.B 之前 |
| Q14 把每个 namespace 的类型写入 `cocache:meta:{namespace}`（线格式新增） | 7.B 之前 |
| Q6 摘要算法是否保留 SHA-1 | 7.B 之前 |
| Q2 SPI 是否改名为 `L1Store` | 8.0 之前 |
| Q3 基线是否升到 JDK 21 | 8.0 之前 |
| Q5 `cocache-spring` 可选依赖 reactor（响应式事务） | 需要时 |
| Q11 重置的严格线性化（读写锁） | 6.x 的 L2 填充修复之后评估 |
| Q12 全集群清空的帧 | 需要时 |
| Q15 `unknownRead = ABSENT` 的短暂缓存 | 8.0 之前 |
