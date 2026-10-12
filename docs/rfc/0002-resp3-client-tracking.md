# RFC 0002：RESP3 client tracking 作为失效通道（预研结论）

- 状态：推迟到 6.x，作为可选通道再评估；6.0 不采用
- Issue：#607

## 1. 问题

当前失效通道是 Redis Pub/Sub（[architecture.md §6](../architecture.md)）。它有两个固有的丢失方式：

1. **发布失败**：写入方 `DEL` 成功、`PUBLISH` 失败时，其它实例的 L2 保持陈旧，直到 `clientSideTtl` 或 TTL（发布失败只告警，`cocache.eviction.publish.failures` 计数）。
2. **绕过 CoCache 的写入**：外部进程必须自己按协议 `DEL` + `PUBLISH`（见运维指南“从 CoCache 之外淘汰”）。

Redis 6+ 的 client tracking（`CLIENT TRACKING ON`，RESP3 推送 `invalidate` 消息）由服务端在 key 被修改时产生失效通知，与写入原子相关，理论上能消除第 1 种丢失，并让外部写入方只需 `DEL`。

## 2. 实测（预研）

环境：本机单机 Redis 8.10，Lettuce 7.5（RESP3），2000 轮；一次性实验代码未提交。

| 指标 | client tracking（default 模式） | Pub/Sub |
|------|---------------------------------|---------|
| 送达 | 2000/2000，全部由**不经 CoCache 的直接写入**触发 | 2000/2000 |
| 失效延迟 p50 | 0.29 ms | 0.37 ms |
| 失效延迟 p99 | 2.0 ms | 2.9 ms |
| 服务端代价 | 跟踪表 2000 个 key，`used_memory` 约 +150 KB（约 77 B/key） | 无 |

结论：延迟与 Pub/Sub 同量级（单机下略低），对 Redis 内 key 修改的覆盖是完整的。

## 3. 能解决什么，不能解决什么

| 问题 | tracking |
|------|----------|
| 发布失败导致的丢失 | **解决**：失效由服务端随写入产生，没有单独的发布步骤 |
| 外部写入方需要 `PUBLISH` | **解决**：只需 `DEL`（或任何修改 key 的命令） |
| 只改数据库、不碰 Redis 的写入 | 不解决：tracking 只看 Redis key |
| §5 的跨实例写回竞态（旧值写回 L1） | 不解决：那是写回保护问题，见 [RFC 0001](0001-l1-write-back-lease.md) |
| 断线期间的丢失 | 等价：服务端在连接断开时丢弃该客户端的跟踪状态，重连后必须重新开启 tracking 并清空 L2（与 `onReset` 相同） |

## 4. 落地成本

1. **Spring Data Redis 没有 tracking API**。L1 读取走 `StringRedisTemplate` 的共享连接；要么在该连接上以 `REDIRECT` 模式开启 tracking（失效推送到另一条 Pub/Sub 连接），要么把 L1 读路径改为 Lettuce 原生连接。前者依赖 Spring 不暴露的连接生命周期，后者重写 L1 的读路径（G3 敏感）。
2. **重连检测**：tracking 状态随连接消失。Spring 的共享连接重连时不通知 CoCache，无法可靠地重新开启 tracking 并 `onReset`。
3. **Redis Cluster**：tracking 是按节点、按连接的；需要在每个节点的读连接上分别开启，并把失效重定向到各节点对应的连接。
4. **服务端内存**：default 模式的跟踪表随“被读过的不同 key”增长（受 `tracking-table-max-keys` 限制，超出时服务端主动发送失效，安全但会清 L2）。BCAST 模式按前缀（如 `cocache:`）广播，不占跟踪表，但每次写入都会推送给所有开启 tracking 的客户端，扩展性与普通 Pub/Sub 相当。

## 5. 决定

- **6.0 不采用**。6.0 的重点是 RFC 0001 的写回 lease，它关闭的是 TTL 唯一必须兜底的窗口；tracking 解决的是另一类、且已有 `clientSideTtl` 兜底的问题。
- **6.x 作为可选的第三种通道再评估**（`cocache.redis.eviction-channel=tracking`），优先 BCAST 模式 + `REDIRECT`：
  - 前提一：Spring Data Redis 提供 tracking 或连接事件 API，或 CoCache 的 L1 读路径已因其它原因改为 Lettuce 原生连接；
  - 前提二：集群下按节点开启 tracking 的方案通过 CI 的 Redis Cluster 任务验证。
- 在此之前，降低“发布失败”影响的手段是 `clientSideTtl` 与 `cocache.eviction.publish.failures` 告警。
