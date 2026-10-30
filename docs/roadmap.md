# CoCache 路线图

本文记录 5.1 之后的演进计划；5.x 各版本已全部完成，5.x 不再维护。每一项都对应 [architecture.md](architecture.md) §1 的目标：G1 陈旧度有上界、G2 失效不被吞没、G3 命中路径够便宜。完成的项迁入 changelog，行为变化同步更新 architecture.md。

## 排序原则

1. **先正确，后功能。** 违反 G1/G2 的缺陷最先修。
2. **先度量，后优化。** 指标先于异步刷新与 lease 上线，否则无基线可比。
3. **先兼容，后破坏。** wire format 变化集中到 6.0 一次完成。
4. **每项写明目标与验收标准。**

## v5.1.1：修复

| 项 | 目标 | 内容 | 验收 |
|----|------|------|------|
| clientId 唯一性 | G2 | `HostClientIdGenerator` 增加每实例随机后缀。host + pid 并不唯一（host 网络模式下同机容器 pid 均为 1），clientId 相同的实例会把对方的失效事件当作自发事件忽略 | 复现用例：host、pid 相同的两个生成器得到不同的 clientId |

## v5.2：陈旧有界、可观测

| 项 | 目标 | 内容 | 验收 |
|----|------|------|------|
| 移除 CosId 依赖 | 精简依赖 | 用 JDK（`ProcessHandle`、`NetworkInterface`）替代 `ProcessId`、`LocalHostAddressSupplier`；`HostAddressSupplier` 集成改为可选或移除；删除 example 中的 `cosid:` 配置 | `cocache-core` 不再传递 `cosid-core`；changelog 说明 |
| L2 陈旧上限 | G1 | `@CoCache.clientSideTtl`（默认 0 = 不限制，保持现有行为）；写入 L2 时取 `min(ttlAt, now + clientSideTtl)`，命中路径零开销。由 `TtlPolicy` 持有而非 `@CaffeineCache`：策略属于编排层，且 L2 不一定是 Caffeine | TCK：丢失一条失效事件后，L2 在上限内自愈；L2 命中 JMH（1/8 线程）不退化 |
| 指标 | 验证 G1–G3 | Micrometer（starter 中 `compileOnly` + `@ConditionalOnClass`）：分层命中率、回源耗时与异常、SingleFlight 合并数、戳作废写回数、发布失败数、`onReset` 次数 | 计数基于 `LongAdder`，JMH 不退化 |

## v5.3：批量与尾延迟

| 项 | 目标 | 内容 | 验收 |
|----|------|------|------|
| 批量读 | G3 | `CacheGetter.getAll` / `CacheSource.loadAll`（带默认实现）；L1 一次 Lua 批量读；逐 key 戳保护；JoinCache 走批量 | 100 个 key：1 次 L1 往返 + 1 次批量回源；`DefaultCoherentCacheSpec` 增加批量在途失效用例 |
| 过期前刷新 | G3 尾延迟 | 软 TTL 后返回旧值并异步刷新；刷新受戳保护；陈旧上界 = TTL + 刷新超时 | 热点 key 过期时 p99 无尖刺；刷新与失效交错用例 |
| Redis 故障保护 | G1 + 保护数据源 | 回源并发上限（bulkhead）；失效通道断开期间 L2 降至 `maxStaleness` | 故障注入测试 |

依赖：故障保护依赖 5.2 的 `maxStaleness`；过期前刷新的收益用 5.2 的指标评估。

## v5.4：失效通道

| 项 | 目标 | 内容 |
|----|------|------|
| Sharded Pub/Sub | 集群扩展 | 基于 SPUBLISH/SSUBSCRIBE 的 EventBus，默认关闭；与 PUBLISH 不互通，需全集群同时切换 |
| 外部失效接入 | G1 | 公开 `key@@publisherId` 协议，提供 CDC（Debezium/Canal）接入示例 |
| RESP3 client tracking | G2 | 仅预研，产出结论文档，决定是否作为 6.0 通道。结论：6.0 不采用，6.x 再评估（[RFC 0002](rfc/0002-resp3-client-tracking.md)） |

同期产出 6.0 设计 RFC：L1 版本号 / lease 的数据结构、墓碑 TTL、迁移方案（[RFC 0001](rfc/0001-l1-write-back-lease.md)，已被 [RFC 0003](rfc/0003-target-architecture.md) 取代）。

## v6.0：目标架构重构（[RFC 0003](rfc/0003-target-architecture.md)）— 已完成

不兼容 5.x：API、SPI、模块、Redis 线格式、配置项一次性重做，线格式只破坏这一次。原计划的 L1 写回 lease（RFC 0001）与清理并入本版本。现行设计见 [architecture.md](architecture.md)。

| 项 | 目标 | 内容 | 状态 |
|----|------|------|------|
| L1 lease 栅栏 | G2 | 消除 5.x 的“已知的固有窗口”：协议内竞态不再在 L1 留下旧值；取代 `fillCache`/`evictIfUnchanged` 与写后撤销 L1 | ✅ |
| 同节点失效吞没（RFC 0003 §10 C1、v3-1） | G2 | 失效方在修改 L1 前后各递增一次戳；`put` 不写 L2 | ✅（`EngineSpec` 复现用例） |
| 跨实例回源互斥与单次刷新 | 保护数据源、G3 | 复用 lease 与刷新令牌，全集群同一 key 只回源 / 刷新一次 | ✅ |
| 原子失效与广播 | G1 | 失效、写入在一次 Lua 中完成 `DEL`/写入 + `PUBLISH`，各 1 次往返；环境不支持时退回非原子并告警 | ✅ |
| 失效重试 | G1 | L1 写入失败的失效进入有界重试队列，排队期间未命中跳过 L1 | ✅ |
| 新线格式 | G2、可演进 | 标量用 String + 36 字节定长头，集合用 Hash（控制字段与成员分离）；去掉 `_nil_` 哨兵；格式版本字段 | ✅ |
| Lettuce 原生后端 | G3 | 去掉 Spring Data Redis；共享连接多路复用批量读；每个后端一条订阅连接；订阅心跳；Sharded 通道（slot 迁移后自动重新订阅） | ✅ |
| 声明与装配 | 简化 | `CacheSpec` + `CacheRuntime` 取代 9 种工厂与 bean 名后缀约定；配置分层与启动校验；`@CoCache` 接口式声明保留（启动时翻译为 spec） | ✅ |
| Spring 集成 | 正确性 | `CoCacheSpringCacheManager`（`sync = true` 走 lease 保护的回源）；`TransactionalInvalidator` | ✅ |
| 可观测 | 验证 G1–G3 | `CacheMetrics` + Micrometer；actuator 端点 `cocache` | ✅ |
| 删除 | 简化 | JoinCache 子系统、L2 可替换、Jedis、5.x 全部包与模块 | ✅ |

JMH 对比见 RFC 0003 §7.1。

## v6.1：可部署性 — 已完成

| 项 | 目标 | 内容 | 验收 |
|----|------|------|------|
| Spring AOT / GraalVM 原生镜像 | 可部署 | 构建期为 `@CoCache` / `@JoinCacheable` 接口的 JDK 代理、`keyExpression` / `joinKeyExpression` 的根对象与默认 Jackson codec 的值类型登记 RuntimeHints；注册的 bean 定义可由 AOT 生成代码 | starter 中的 AOT 处理测试（生成、编译并运行生成的初始化器）；example 的原生镜像冒烟构建（独立 CI 工作流，不作为 PR 必需检查） |
| 执行器可配置 | 运维 | `cocache.spring-cache.retrieve-executor`、`cocache.refresh-executor` 按 bean 名指定执行器（可用 `applicationTaskExecutor`），用户提供的执行器不随运行时关闭 | 绑定与元数据测试；指定的执行器确实被使用；未知 bean 名启动失败 |
| `SetCache` 驱逐检查 | 安全 | 有 `SetCache` 时启动读取各主节点的 `maxmemory-policy`：`allkeys-*` 会驱逐集合的唯一副本；`cocache.redis.set-cache-eviction-check: warn \| fail \| off`，默认 `warn`；`CONFIG` 不可用时跳过并记录 | 单机与集群上读取策略；ACL 禁用 `CONFIG` 时跳过；告警与失败模式测试 |

## v6.3：异步 API 与 6.1 之后的改进 — 已完成

6.2 没有单独发布：原计划在 6.2 交付的内容随 6.3.0 一并发布。

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 异步 API（[RFC 0004](rfc/0004-async-api.md)） | G3 尾延迟、非阻塞栈 | `cache.async()` 视图（与同步 API 共享 L2、栅栏、SingleFlight 与 lease）、`AsyncCacheLoader`、可选的原生异步 SPI `AsyncRemoteStore` / `AsyncInvalidationChannel`；Redis 后端原生异步；Spring Cache `retrieve` 经异步视图；异步 TCK（`Async*Spec`） | ✅ 三个 PR（core + 内存后端 + TCK、Redis、Spring 与文档）；JMH 见 RFC 0004 §14 |
| `putAll` 批量写入 | G3 | 按 slot 分组、一个脚本写入并广播，逐 key 遵守写入方顺序 | ✅ |
| 链路追踪 | 可观测 | 只在未命中与回源路径接入 Micrometer `Observation`，命中路径零开销 | ✅ `LoadObserver`、`MicrometerLoadObserver` |
| TCK `MultiNodeSpec` | G2 验收 | 多节点覆盖 §4.4 的全部交错、全集群单次回源与单次刷新的计数断言 | ✅ 三个运行时共享一个后端（同一进程内的多个节点，不是多进程）；内存后端与 Redis 三种通道模式（单机与 Cluster）都运行 |
| TCK `FaultSpec` | G1 验收 | 断线时命令不被重放、故障切换后的 `NOSCRIPT` 回退等故障场景 | ✅ 降级限龄、fill 失败、重试放弃与溢出、“已生效但报错”的写入；断线用例由后端可选实现 `disconnect()`（Redis 单机经 TCP 代理），`NOSCRIPT` 回退是 `cocache-redis` 自己的用例 |
| Valkey CI | 兼容性 | Valkey 上运行 `cocache-redis` 与 starter 测试 | ✅ CI 任务 Test (Valkey)：单机 Valkey 8 |
| 集合基准 `setRead` | G3 | 成员数 10 / 100 / 1000 的吞吐与内存，决定 Hash 编码是否保留（RFC 0003 §8 Q1） | ✅ `SetCacheBenchmark`；保留 Hash（RFC 0003 §8.1） |
| 集合读脚本改用 `HKEYS` | G3 | 读脚本以 `HKEYS` + `HGET !f` 取代 `HGETALL`，不再返回空值；存储布局不变，不是线格式变更。服务端实测 1000 个成员时读吞吐约 1.8 倍（RFC 0003 §8.1） | ✅ 服务端读吞吐 10 / 100 / 1000 个成员为 1.33 / 1.55 / 1.76 倍；最小 ACL 需加 `+hkeys +hget`（运维文档） |
| L2 命中计数 | 可观测 | 分条带计数器（`LongAdder`）；8 线程 JMH 回退在噪声内才默认开启 | ✅ #655：默认关闭，`cocache.metrics.local-hits` / `recordLocalHits(true)` 开启；开启时 JMH 回退超出噪声（`l2HitViaInterface` 1 线程 −7.7%、8 线程 −8.9%，`l2Hit` −4%~−5%），关闭时与 main 持平（数据见 #655） |

## v6.3.1：修复 — 已完成

6.3.0 发版审查中记下的已知问题。

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 非原子模式下执行器拒绝写入收尾 | G2 | 非原子广播时，用户配置的 `cocache.async-executor` 拒绝任务会让写入结果被 `RejectedExecutionException` 替换，广播也被跳过（`AbstractEngine.writeAsync` 中广播的派发被拒）。修法：派发被拒时在完成线程上直接 `publishAsync`，并保留写入的原始结果或异常 | ✅ 默认的 `ForkJoinPool` 不拒绝任务，不受影响；`CoherentEngineTest`（同步与异步视图）用拒绝任务的执行器与延迟送达的 L1 结果复现 |
| 命令超时后的补发广播 | G2 | 同步 `putAll` / `invalidate` 失败后补发的广播可能先于仍在 Redis 端执行的超时命令生效，旧副本由 `localMaxAge` 兜底；失败路径最坏等待为“组数 × `commandTimeout`”（见 architecture.md §4.2） | ✅ 评估后维持现状：超时后无从得知命令是否执行，有上界的追加等待只能缩小窗口、不能关闭，却让失败路径更慢；理由记入 architecture.md §4.2 |

## v6.4：Kotlin 协程 — 已完成

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| `cocache-coroutines` | 易用 | Kotlin 挂起视图（`cache.suspending()` / `SetCache.suspending()`、`SuspendingCacheLoader`、`suspendingLoader`），异步视图的薄封装；`kotlinx-coroutines-core` 只出现在该模块（RFC 0004 §7） | ✅ 6.4.0 |

## v6.4.1：修复与异步性能 — 已完成

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 8 线程异步闭环低于 RFC 0004 §11 的 90% | G3 | 6.3 实测为同步的 80%–93%（RFC 0004 §14“6.3 / PR 2”）；去掉 `orTimeout` 后不变，主因已查明：异步链每一级都派发到执行器（L1 命中 4 次、未命中 7 次，A9 只要求 1 次、3 次），每次多余的派发在 `ForkJoinPool` 上引起一次唤醒；A9 要求的那一次切换约占 5–7 个百分点（[调查报告](investigations/async-8t-gap.md)） | ✅ 6.4.1：上一步在同一执行器派发的续接中完成时就地执行（`Futures.continueOn`），L1 命中每次只派发 1 次、未命中至多 3 次；与 6.4.0 交替对比，L1 命中 1 线程 +5.5%、8 线程 +8%，未命中 8 线程至少约 +8%（只有 2 对有效数据），写入与 L2 命中持平 |
| 异步命令超时改用 Lettuce `TimeoutOptions` | G3 | 异步视图的命令超时目前由 `CompletableFuture.orTimeout` 计时（JDK 的单线程延迟调度器，每条命令一次调度与撤销），8 线程闭环基准低于 RFC 0004 §11 的 90% 的一部分来自这里；改由 Lettuce 自己的命令超时触发、在 `bounded` 中只做异常映射（RFC 0004 §14） | ✅ 6.4.1：单机只由 Lettuce 计时；集群中等待建立节点连接的命令 Lettuce 不计时，另以它的时间轮兜底。收益是不再依赖 JDK 的单线程延迟调度器；“8 线程差距部分来自 `orTimeout`”的假设未被证实（8 线程 99.1%–101.8%，见 RFC 0004 §14） |
| Lettuce 6.8（Spring Boot 4.0）兼容 | G1 | Spring Boot 4.0 管理的 Lettuce 6.8 没有 Lettuce 7 的 `Delegating` 接口与维护期间的超时放宽：6.0–6.4.0 在 6.8 上心跳失败后强制重连抛出 `NoClassDefFoundError`，心跳任务永久停止 | ✅ 6.4.1：强制重连不再依赖 `Delegating`，维护期间的放宽在 6.x 上跳过；心跳任务兜住任何 `Throwable`，订阅者的重置互不影响；CI 在 Spring Boot 4.0 上测试 Redis 模块与示例（#675） |
| 协程示例 | 易用 | `cocache-example` 中的 WebFlux + 协程示例（RFC 0004 §7）：`CoroutineController`（`suspend fun` 处理器、`cache.suspending()`、调用级 loader、写后失效）与 `suspendingLoader` 声明的 `profile` 缓存 | ✅ 6.4.1 |

## v6.5：5.x 数据迁移 — 已完成

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 读不懂的内容不再静默丢弃 | G1 | 没有数据源的读取遇到 5.x 遗留值或无法解码的值时，6.0–6.4 会回收并删除唯一副本（`SetCache` 读成空集合）；新增 `foreignFormat` 策略（`fail` / `reclaim`），没有数据源时默认 `fail` | 6.5.0 已交付 |
| `CoCache5Migrator` | 易用 | 把 5.x 的字符串（JSON）、原生 SET、hash 与负缓存转换为 6.x 格式，保留 TTL，可重复执行，原地迁移前备份，支持 `dryRun` / `rollback` / `dropBackups` | 6.5.0 已交付 |

## v6.6：外来格式写入 fail closed — 已完成

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 增删成员不再清掉读不懂的内容 | G1 | 6.0–6.5 中，没有数据源的 `SetCache` 对未迁移的 5.x 原生 SET 调用一次 `add` 就会替换全部成员，之后迁移工具跳过该 key；`foreign-format: fail` 时 `add` / `remove` 现在抛出 `CacheWriteException`，原子地保留内容与 TTL、不广播。`cocache-api` 的 SPI 新增带默认实现的 `RemoteStore.addMembersIfReadable` / `removeMembersIfReadable` 及异步版本 | 6.6.0 已交付（#679） |
| 更高格式版本的值不再读作不存在 | G1 | 没有数据源的读取命中更高版本写入的值时，`fail` 下抛出 `CacheReadException`（`ForeignFormatException.newer = true`），保留 key、不写 L2 | 6.6.0 已交付（#679） |

## v6.7：按摘要的条件读写 — 已完成

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 条件写入 | G1 | `putIfMatch`：按载荷摘要比较并写入，一个原子步骤，冲突时不写、不广播；SPI 默认实现直接抛出 `UnsupportedOperationException`，不退化为先读后写 | 6.7.0 已交付（#683） |
| 条件读取 | G1、G3 | `getIfChanged`：摘要未变时只返回 `Unchanged`，不传输正文；读写两侧对读不懂的内容判定一致 | 6.7.0 已交付（#683） |

## v6.8：按版本有序的写入 — 已完成

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 按版本写入 | G1、G2 | `putIfNewer` / `invalidateIfNewer`：版本更高时写入，比较与写入一个原子步骤（Redis 一次往返）；相等是幂等的空操作，旧版本被拒绝，都不写、不广播、不改寿命。写入销毁 lease 与刷新令牌。版本存放在格式 1 头部的令牌位置，6.x 的读者照常读取 | 6.8.0 已交付（#693） |
| 带版本的回源与读取 | G1 | `versionedLoader` 回源时保存版本；`Versioned(null, n)` 写成墓碑，挡住重放的旧状态；`getVersioned` 返回值与版本。SPI 新增 `CoherenceBackend.versionedWrites`、`RemoteStore.putIfNewer`（默认拒绝）与 `Fill.version`，TCK 新增 `VersionedSpec` | 6.8.0 已交付（#693、#694） |

## v6.9：架构审查修复 — 已完成

审查与编号见 [RFC 0005](rfc/0005-v8-target-architecture.md) 附录 A。

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 没有数据源的缓存 fail closed | G1、G2 | L1 读失败时抛出，不再返回并缓存“不存在”（A.D1）；`invalidate` 失败默认抛出，延迟的重试不再越过之后的写入（A.D2）；非原子广播下条件写入的重放也广播（A.R4）；显式配置的 `read-failure: degrade` 对没有数据源的读取生效（返回 `null`、不缓存、计数），让撤销表这类故障放行仍可表达（RFC 0005 §12 M3） | 6.9.0 已交付（#700） |
| Redis 失败路径 | G1、G2 | 超时的脚本不再在之后写出（A.R1）；`sharded` 未确认的频道每次心跳重置（A.R2）；读超时释放 lease（A.L1）；订阅与退订串行化（A.R3）；格式字节按无符号解析（A.L7） | 6.9.0 已交付（#701） |
| 事务内的失效 | G2 | 回滚与提交结果未知时同样失效（A.T1）；迟到的登记不丢失、逐缓存的失败隔离（A.T2） | 6.9.0 已交付（#697） |
| 提前刷新退避 | G3、保护数据源 | 没取得令牌的重新填充退避；寿命 ≤ 刷新窗口的副本不设刷新点（A.D3） | 6.9.0 已交付（#698） |

## v6.9.1：修复 — 已完成

6.9.0 发布之后，RFC 0005 第二轮对抗验证发现的两个缺陷（附录 B）。

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 中断与 fill 失败时释放令牌 | G1、G2 | `await` 被中断时与超时一样取消命令、释放令牌；`fill` / `fillAll`、`reclaim` 与 `release` 在结果未知时同样释放令牌（RFC 0005 附录 B） | 6.9.1 已交付（#704） |

## 6.9 之后

下一步要做的事、验收标准与需要维护者决定的问题见 [待办清单](todo.md)。

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 外部失效 CLI | G1 | 非 JVM 环境构造帧并原子执行 `DEL` + `PUBLISH` 的命令行工具 | 未提供；JVM 上已有 `ExternalInvalidator`，协议见运维文档 |
| RESP3 client tracking 通道 | G2 | `InvalidationChannel` 的一种实现（[RFC 0002](rfc/0002-resp3-client-tracking.md)） | 预研 |
| L2 填充可线性化、单调时钟、可中断的跟随者 | G1、G2 | RFC 0005 §4.5、§4.6（A.D4、A.C1、A.S1） | 待排期 |
| 其余审查项 | G1、运维 | 驱逐策略检查改读 `INFO memory`、按声明检查；端点对未知缓存的 `DELETE` 返回 404；修正 architecture.md §6.3 关于启动检查的说法（RFC 0005 §9） | ✅ 已修复，随下一版本发布：驱逐检查读不到策略时按 `assume-maxmemory-policy` 判断，否则按 `set-cache-eviction-check` 告警或启动失败，不再静默跳过；`volatile-*` 下 `ttl` 有限的集合缓存告警 |
| starter 的事务装饰器 | G2 | 取代 `TransactionAwareCacheManagerProxy`，`@CacheEvict` 在回滚时同样失效；回滚时失效写事务内凭 lease 写进 L1 的 key（RFC 0005 §4.9） | 7.x |

6.0 已交付按 key 查看与失效（actuator `GET` / `DELETE /actuator/cocache/{name}/{key}`、`CacheAdmin.inspect`）与 `ExternalInvalidator`。

## v8：目标架构（[RFC 0005](rfc/0005-v8-target-architecture.md)）— 设计中（草案 v3，第二轮对抗验证已完成）

7.0 维持既定范围（删除 6.x 的弃用项）。v8 的前置工作放在 7.x，其中桥接版本 7.B 冻结读格式 2、配置别名与前向兼容的声明；一个 namespace 的全部读者先升级到 7.B，写入方最后升级到 v8。不破坏兼容的部分在 6.x 先行（RFC 0005 §9、§10）。

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 权威与派生分型 | G1、G2 | 按“L1 丢了能否按需重建”分为 `Cache` / `VersionedCache` 与 `Store` / `VersionedStore` / `SetStore`；派生缓存必须声明数据来源（`externallySourced()`）；`Unknown` 只表示故障，默认抛出（V1、V2） | 草案 v3 |
| 能力即类型 | 简化 | 能力子接口取代默认抛出的方法，带版本的类型没有丢失版本的写入；SPI 异步为主、阻塞为显式的第二形状，能力用访问器表达，TCK fail closed；带版本副本的 `evict` 编码为状态 `E`（V3、V4） | 草案 v3 |
| 一份协议、两个驱动 | 简化、可检验 | sans-IO 协议核心与共用的 flight 层，7.x 内部重构先行，TCK 证明等价、JMH 把关（V5） | 草案 v3 |
| 失败路径确定 | G1、G2 | 按可观察的事实区分“确定未执行”与“结果未知”（含中断）、补偿失效只用于 `RETRY`、令牌守恒（含 `fill`）、等待有界、刷新有界（V7） | 草案 v3 |
| 线格式 2 | G3、可演进 | 按需扩展的头部（版本段、SHA-1 摘要段、可跳过的扩展区）与状态 `E`；7.B 之前定稿；`fill` 不让版本后退；逐脚本的兼容矩阵；`write-format` 让 API 升级与格式切换分开（V8） | 草案 v3 |
| 可检验的栅栏 | G2 | Lincheck、TLA+、确定性多节点仿真、跨版本脚本 TCK（V9） | 草案 v3 |
| 配置与 Spring | 正确性、易用 | `defaults` / `overrides`（取上限）三层配置、全局层跳过不适用与锁定的字段、`CoCacheFactory` 与 `lock` / `constrain`；回滚时失效事务内的填充、已登记 key 的回源私有；端点默认只读（V10、V11） | 草案 v3 |

## 暂缓

按字节限制 L2 与压缩 codec、Sharded 模式的原子广播（分桶布局，RFC 0003 §8 Q2）。等指标证明有需要时再排期。
