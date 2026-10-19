---
title: 运维
description: 生产环境运行 CoCache：Actuator 端点（按 key 查看与失效）、健康检查、指标、Redis 故障行为、从 CoCache 之外失效、查看 Redis、容量规划与陈旧读取排查。
---

# 运维

## Actuator 端点 {#actuator-endpoint}

classpath 上有 `spring-boot-starter-actuator` 时，starter 提供一个端点 `cocache`。只有端点可用（允许访问且已暴露）时才会创建，所以像其它 Actuator 端点一样暴露它：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: cocache
```

| 请求 | 效果 |
|------|------|
| `GET /actuator/cocache` | 本实例每个缓存的报告：`name`、`localSize`（L2 条目数，估计值）、`loads`、`loadNanos`，以及 `metrics` 下[指标表](#metrics)中的全部计数器 |
| `GET /actuator/cocache/{name}` | 单个缓存的报告 |
| `DELETE /actuator/cocache/{name}` | 清空**本实例**该缓存的 L2。L1 不受影响，正在进行的加载会被放弃 |
| `GET /actuator/cocache/{name}/{key}` | 查看本实例上的一个 key：L2 副本与 L1 状态（[见下文](#inspect-key)） |
| `DELETE /actuator/cocache/{name}/{key}` | 经正常的失效路径**在全集群**失效一个 key，与 `cache.invalidate(key)` 相同，幂等。在 `SetCache` 上等价于 `clear(key)`，会删除唯一副本 |

缓存名不存在时返回 `404`。`DELETE` 操作会改变状态，查看会展示值，请像其它可写、能读取业务数据的 Actuator 端点一样加以保护。

### 查看一个 key {#inspect-key}

`{key}` 是**编码后的 key**：Redis key 中 `{keyPrefix}{namespace}:` 之后的部分，即 `KeyEncoder` 的输出（`toString()` 或 `keyExpression` 的结果），因此适用于任意 key 类型，可以包含 `/`。

```bash
curl localhost:8080/actuator/cocache/UserCache/42
```

```json
{
  "cache": "UserCache",
  "storageKey": "cocache:UserCache:42",
  "local": { "state": "VALUE", "value": "User(id=42, name=Ada)", "expiresAt": "2026-10-19T08:05:00Z", "refreshAt": null, "expired": false },
  "remote": { "state": "VALUE", "value": "User(id=42, name=Ada)", "ttl": "PT58M", "format": 1, "error": null },
  "invalidationPending": false
}
```

| 字段 | 含义 |
|------|------|
| `local` | 本实例的 L2 副本，没有时为 `null`。`state` 为 `VALUE`、`ABSENT`（负缓存）或 `UNRESOLVED`（没有 loader 的缓存未命中）。已过期但 Caffeine 尚未淘汰的副本显示 `expired: true` |
| `remote.state` | `VALUE`、`ABSENT`（负缓存）、`SET`、`LEASED`（某个节点正在加载，`ttl` 是 lease 的剩余寿命）、`MISS`、`UNREADABLE`（未知或更高的格式、形状不符或编解码失败，原因见 `error`）或 `ERROR`（读取 Redis 失败） |
| `value` | 用缓存的编解码器解码后的 `toString()`，截断到 256 个字符 |
| `invalidationPending` | 该 key 的失效写入 Redis 失败，仍在重试。成功之前，未命中会跳过 L1 |

查看从不加载：不调用 loader，不申请 lease 或刷新令牌，不填充任何一层，不计入指标，也不影响 Caffeine 的淘汰统计。代价是一次 Redis 读取。

## 健康检查 {#health}

classpath 上有 actuator 时，starter 注册健康检查 `cocache`（bean `cocacheHealthIndicator`，用 `management.health.cocache.enabled` 关闭）。它只读取内存中的状态，从不访问 Redis。

| 状态 | 条件 |
|------|------|
| `DOWN` | Redis 失效通道最近一次心跳失败。本实例的 L2 已被清空，连接恢复之前可能收不到其它实例的失效 |
| `UP` | 其它情况，包括失效仍在重试时 |

详情：`backend`（类名）、`format`、`atomicBroadcast`；Redis 后端另有 `channelMode` 与 `heartbeat`（`healthy`，失败时还有 `error`）；`pendingInvalidations` 总数；以及每个缓存的 `pendingInvalidations` 与 `localSize`。失效重试不影响状态：重试队列会自行收敛，放弃的失效计入 `cocache.invalidations.dropped`，请对该指标告警。

如果不希望 Redis 订阅问题让实例退出流量，不要把 `cocache` 加入 readiness 分组。

## 指标 {#metrics}

classpath 上有 Micrometer 时（`spring-boot-starter-actuator` 会引入），starter 把以下指标绑定到所有 `MeterRegistry`。每个指标都带 `cache` 标签，值为缓存名。全部指标都在 L2 命中路径之外记录，始终开启。

| 指标 | 类型 | 含义 |
|------|------|------|
| `cocache.hits{level=l1}` | counter | 由 L1 满足的 L2 未命中 |
| `cocache.misses` | counter | L2 未命中，在合并同一 key 的并发未命中之前计数 |
| `cocache.filtered` | counter | 被 `KeyFilter` 判定为“不存在”的读取 |
| `cocache.loads` | function timer | loader 调用（单 key 与批量）及累计耗时 |
| `cocache.load.failures` | counter | 抛出异常的 loader 调用 |
| `cocache.load.rejections` | counter | 被 `load-max-concurrent` 拒绝的回源 |
| `cocache.lease.waits` | counter | 等待其它节点 lease 的读取 |
| `cocache.lease.wait.timeouts` | counter | 等待 lease 超时、本节点回源但不写 L1 的读取 |
| `cocache.fill.rejections` | counter | 被 lease 栅栏拒绝的 L1 填充（期间发生了失效，或 loader 慢于 `lease-ttl`） |
| `cocache.degraded.fills` | counter | 只写入 L2（以 `degraded-local-max-age` 限龄）的加载 |
| `cocache.stale.discards` | counter | 因加载期间收到失效而放弃的 L2 填充 |
| `cocache.reclaims` | counter | 回收并重新加载的无法解码的 L1 载荷 |
| `cocache.unknown.formats` | counter | 线格式版本高于本节点所知的 L1 值 |
| `cocache.refreshes` | counter | 提前刷新的执行次数 |
| `cocache.refresh.rejections` | counter | 被刷新执行器拒绝的刷新 |
| `cocache.remote.failures` | counter | 失败的 L1 操作 |
| `cocache.write.failures` | counter | 在 L1 上失败的写入与失效 |
| `cocache.invalidation.retries` | counter | 安排的失效重试 |
| `cocache.invalidations.dropped` | counter | 重试耗尽或重试队列溢出后放弃的失效 |
| `cocache.invalidations.received` | counter | 从其它实例收到的失效事件 |
| `cocache.resets` | counter | 清空 L2 的通道（重新）订阅与心跳失败 |
| `cocache.local.size` | gauge | 本实例 L2 的条目数（估计值） |

没有 L2 命中计数：给最热的路径计数会造成可测量的吞吐下降。

需要关注：

- **`invalidations.dropped` > 0**：部分实例可能在 `local-max-age` 内返回陈旧的 L2 副本，L1 可能保留旧值直到其 TTL。请检查 Redis 的可用性。
- **`degraded.fills`、`remote.failures` 上升**：Redis 出现故障，读取正在回源。
- **`lease.wait.timeouts` 相对 `lease.waits` 偏高**：loader 慢于 `lease-wait`，跨实例合并没有生效。调大 `lease-wait` 或加快 loader。
- **`fill.rejections` 上升但没有对应的失效**：loader 慢于 `lease-ttl`。
- **没有部署时 `resets` 上升**：订阅连接不稳定。

## Redis 故障 {#redis-failures}

| 操作 | L1 失败时 |
|------|-----------|
| 读取（`get`、`getAll`、`lookup`） | 默认（`read-failure: degrade`）：从数据源加载，只写入 L2，以 `degraded-local-max-age`（10 秒）限龄。`read-failure: throw`：抛出 `CacheReadException` |
| 填充、释放 lease、刷新 | 总是尽力而为：记录日志并计数，从不抛出 |
| `invalidate`、`invalidateAll` | 本地效果先生效。默认（`invalidate-failure: retry`）：以指数退避在后台重试，不抛出；重试成功之前，该 key 的未命中跳过 L1 直接回源。`invalidate-failure: throw`：抛出 `CacheWriteException` |
| `put`，以及 `SetCache` 的 `add` / `remove` / `clear` | 本地效果先生效，然后抛出 `CacheWriteException`。超时时写入可能已经生效。这些操作都是幂等的，可以重试 |

**为降级的代价做好准备。** Redis 不可用时，每个 L2 未命中都会回源。同一 key 的并发未命中在每个实例内仍会合并，但不同的 key 不会。要限制回源量，设置 `load-max-concurrent`（每个缓存、每个实例）。超过上限的回源最多等待 `load-acquire-timeout` 获取许可，然后抛出 `SourceLoadRejectedException`，并计入 `load.rejections`。合并到在途加载上的调用不占用许可，一次批量回源占一个许可。

失效重试队列最多容纳 10,000 个失效，每个最多重试 8 次，退避从 100 ms 增长到 10 s。要修改这些参数，自己声明 `CacheRuntime` bean 并传入 `RetryPolicy`。

**通道故障。** 订阅连接每隔 `heartbeat`（5 秒）PING 一次 Redis。PING 失败或连接断开时，每个缓存清空 L2，并以 `INFO` 级别记录 `Cache[…] - reset: clear local copies.`。连接恢复后会再清空一次，因为故障期间发布的事件已经丢失。

## 从 CoCache 之外失效 {#external-eviction}

绕过 CoCache 的写入，例如直接执行 SQL、批处理任务、其它语言的服务，会让缓存陈旧直到 `ttl`。任何能访问同一 Redis 的进程都可以用 CoCache 自己的协议失效一个 key。该格式在一个大版本内冻结。

| 部分 | 值 |
|------|----|
| 存储 key | `{keyPrefix}{namespace}:{key}`。默认配置下，`UserCache` 的 key `42` 是 `cocache:UserCache:42`。使用 `keyExpression` 时取表达式的结果 |
| 频道 | `cocache:inv:{namespace}`，例如 `cocache:inv:UserCache` |
| 消息 | 二进制帧：`[版本 = 1 : 1 字节][发布者 id : 16 字节][key 数 : varint]([key 长度 : varint][key : UTF-8])*`。发布者 id 必须与所有实例都不同，使用随机值即可 |

权威数据提交之后：

1. `DEL {storageKey}` 删除 L1 副本并销毁 lease，所以在途的加载不能再把旧数据写回。
2. `PUBLISH cocache:inv:{namespace} {frame}` 让每个实例丢弃 L2 副本。`channel-mode: sharded` 时使用 `SPUBLISH`。

`cocache-api`（没有运行时依赖）中的 `InvalidationFrame` 负责构造帧：

```kotlin
class UserChangeInvalidator(private val redis: RedisCommands<String, ByteArray>) {
    private val publisher = NodeId.random()

    fun onUserChanged(userId: String) {   // 在变更提交之后调用
        val storageKey = "cocache:UserCache:$userId"
        redis.del(storageKey)
        redis.publish("cocache:inv:UserCache", InvalidationFrame.encode(publisher, listOf(storageKey)))
    }
}
```

一个帧可以携带同一命名空间的多个 key。与 CoCache 自己的事件一样，投递至多一次：消息丢失时，其它实例的 L2 会陈旧到 `local-max-age`。省略 `DEL` 会让旧值留在 L1，各实例又会把它填回 L2。

## 查看 Redis {#inspecting-redis}

Redis 中每个 CoCache 值都以 36 字节的二进制头开始（magic 字节 `0xC0`、格式版本、状态、令牌、时间戳），后面是 codec 的字节。因此 `redis-cli GET` 显示的是二进制数据；负缓存与进行中的 lease 只有头、没有载荷。集合缓存是 Redis Hash，带一个 `!f` 格式字段，每个成员是一个 `+{member}` 字段。除了上面的失效协议，不要从其它程序读写 CoCache 的 key。

要查看某个实例对某个 key 持有什么，用 [`GET /actuator/cocache/{name}/{key}`](#inspect-key)，它会解码两层的内容；要让所有实例重新加载某个 key，失效它，例如 `DELETE /actuator/cocache/{name}/{key}`。

## 容量规划 {#sizing}

- **L2 内存**约为 `maximumSize` × 平均值大小，按缓存、按实例计算。默认 `maximumSize` 为 10,000。
- **Redis 内存**为每个存活 key 一份副本，外加 36 字节的头。负缓存只有头，在 `absentTtl` 后过期。
- **Redis 流量。** L1 未命中的读取会写入 lease，提前刷新会写入令牌，所以读取也会产生一些复制与 AOF 流量。CoCache 的所有命令都发往主节点。
- **Pub/Sub。** 每个缓存命名空间一个频道（`cocache:inv:{namespace}`），每个实例一条订阅连接。每次 `put` 或 `invalidate` 发布一条消息，所有实例都会收到。在 Redis Cluster 中，`channel-mode: sharded` 让这些流量留在一个分片内。
- **大批量写入。** 一个脚本最多处理 `invalidate-batch-size`（512）个 key。一次写入非常大的 `SetCache` 集合会在执行期间阻塞所在分片。

## 排查 {#troubleshooting}

**某个实例一直返回旧值。**
1. 在该实例上[查看这个 key](#inspect-key)：对比 `local` 副本与 `remote`，检查 `invalidationPending`。排查原因期间，`DELETE /actuator/cocache/{name}/{key}` 可以立即修复。
2. 确认写入方在数据库提交**之后**失效。事务内使用 `TransactionalInvalidator`。
3. 确认所有写入路径都会失效，包括批处理任务和其它服务。CoCache 之外的路径可以使用[失效协议](#external-eviction)。
4. 确认所有实例使用相同的缓存命名空间和相同的 `channel-mode`。`pubsub` 与 `sharded` 的实例互相收不到消息。
5. 检查 `cocache.invalidations.dropped` 与 `cocache.write.failures`。
6. 不带 `sync = true` 的 `@Cacheable` 中，Spring 的无条件 `put` 可能覆盖失效。请使用 `sync = true`。starter 启动时会对这些方法告警（[Spring Cache](./spring-cache.md#use-sync-true)）。

**新建的记录被报告为不存在。** 记录创建之前的一次查询缓存了负缓存，最长持续 `absentTtl`。创建记录后失效该 key，或调小 `absent-ttl`。

**启动失败，提示有多个 `CacheLoader` 候选。** 有两个 bean 匹配缓存的 `CacheLoader<K, V>` 类型。用 `@CoCache(loader = "beanName")` 指定正确的那个。

**启动失败，提示某个 `cocache.*` 配置项。** 它是已删除的 5.x 配置项，或者 `cocache.caches.[name]` 指向了不存在的缓存。错误信息会给出替代项。见[从 5.x 迁移](./migration-6.md#properties)。
