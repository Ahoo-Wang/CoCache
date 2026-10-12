# CoCache 路线图

本文记录 5.1 之后的演进计划。每一项都对应 [architecture.md](architecture.md) §1 的目标：G1 陈旧度有上界、G2 失效不被吞没、G3 命中路径够便宜。完成的项迁入 changelog，行为变化同步更新 architecture.md。

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
| L2 陈旧上限 | G1 | `@CaffeineCache.maxStaleness`（默认 0 = 不限制，保持现有行为）；填充 L2 时取 `min(ttlAt, now + maxStaleness)`，命中路径零开销 | TCK：丢失一条失效事件后，L2 在上限内自愈；L2 命中 JMH（1/8 线程）不退化 |
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
| RESP3 client tracking | G2 | 仅预研，产出结论文档，决定是否作为 6.0 通道 |

同期产出 6.0 设计 RFC：L1 版本号 / lease 的数据结构、墓碑 TTL、迁移方案。

## v6.0：L1 写回保护

| 项 | 目标 | 内容 |
|----|------|------|
| Redis 侧版本号 / lease | G2 | 消除 architecture.md §5 的“已知的固有窗口”：L1 陈旧上界从 TTL 降至事件延迟 |
| 跨实例回源互斥 | 保护数据源 | 复用 lease，移出非目标 |
| 清理 | — | 移除 5.x 废弃接口；按预研结论确定通道实现 |

验收：`MultipleInstanceSyncSpec` 覆盖 §5 跨实例竞态；architecture.md 删除“固有窗口”；升级指南说明 wire format 变化。

## 暂缓

异步 API（JDK 21 虚拟线程已覆盖多数场景）、按字节限制 L2 与压缩 codec、值格式版本化。等 5.2 的指标证明有需要时再排期。
