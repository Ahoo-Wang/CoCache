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

## 6.4 之后

| 项 | 目标 | 内容 | 现状 |
|----|------|------|------|
| 协程示例 | 易用 | `cocache-example` 中的 WebFlux + 协程示例（RFC 0004 §7）：`CoroutineController`（`suspend fun` 处理器、`cache.suspending()`、调用级 loader、写后失效）与 `suspendingLoader` 声明的 `profile` 缓存 | ✅ 已完成 |
| 异步命令超时改用 Lettuce `TimeoutOptions` | G3 | 异步视图的命令超时目前由 `CompletableFuture.orTimeout` 计时（JDK 的单线程延迟调度器，每条命令一次调度与撤销），8 线程闭环基准低于 RFC 0004 §11 的 90% 的一部分来自这里；改由 Lettuce 自己的命令超时触发、在 `bounded` 中只做异常映射（RFC 0004 §14） | ✅ 已实现（未发布）：单机只由 Lettuce 计时；集群中等待建立节点连接的命令 Lettuce 不计时，另以它的时间轮兜底。收益是不再依赖 JDK 的单线程延迟调度器；“8 线程差距部分来自 `orTimeout`”的假设未被证实（8 线程 99.1%–101.8%，见 RFC 0004 §14） |
| 8 线程异步闭环低于 RFC 0004 §11 的 90% | G3 | 6.3 实测为同步的 80%–93%（RFC 0004 §14“6.3 / PR 2”）；去掉 `orTimeout` 后不变，原因尚未查明（候选之一是 A9 要求的线程切换） | 待调查，暂不追踪 |
| 外部失效 CLI | G1 | 非 JVM 环境构造帧并原子执行 `DEL` + `PUBLISH` 的命令行工具 | 未提供；JVM 上已有 `ExternalInvalidator`，协议见运维文档 |
| RESP3 client tracking 通道 | G2 | `InvalidationChannel` 的一种实现（[RFC 0002](rfc/0002-resp3-client-tracking.md)） | 预研 |

6.0 已交付按 key 查看与失效（actuator `GET` / `DELETE /actuator/cocache/{name}/{key}`、`CacheAdmin.inspect`）与 `ExternalInvalidator`。

## 暂缓

按字节限制 L2 与压缩 codec、Sharded 模式的原子广播（分桶布局，RFC 0003 §8 Q2）。等指标证明有需要时再排期。
