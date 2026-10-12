---
title: 运维
description: 生产环境运行 CoCache：Actuator 端点、指标、Redis 故障行为、负缓存哨兵、容量规划与陈旧读取排查。
---

# 运维

## Actuator 端点

classpath 上有 `spring-boot-starter-actuator` 时，starter 会注册两个端点。像其它 Actuator 端点一样暴露它们：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: cocache, cocacheClient
```

| 请求 | 效果 |
|------|------|
| `GET /actuator/cocache` | 所有缓存的报告：名称、`clientId`、L2 大小、TTL 策略、组件类 |
| `GET /actuator/cocache/{name}` | 单个缓存的报告 |
| `GET /actuator/cocache/{name}/{key}` | 经由缓存读取条目。**未命中时会回源加载** |
| `DELETE /actuator/cocache/{name}/{key}` | 从 L2、L1 淘汰该 key，并通知所有实例 |
| `GET /actuator/cocacheClient/{name}` | 本实例 L2 的条目数 |
| `GET /actuator/cocacheClient/{name}/{key}` | 本实例 L2 中的条目，不触发加载 |
| `DELETE /actuator/cocacheClient/{name}` | 清空本实例的 L2 |

`DELETE` 操作会修改缓存，请像保护其它可写 Actuator 端点一样保护它们。

## 指标 {#metrics}

classpath 上有 Micrometer 时（`spring-boot-starter-actuator` 会引入它），starter 会把以下指标绑定到所有 `MeterRegistry`。按缓存统计的指标带 `cache` 标签，值为缓存名。

| 指标 | 类型 | 含义 |
|------|------|------|
| `cocache.hits{level=l2}` | counter | 由本实例 L2 返回的读取。仅在 `cocache.metrics.client-side-hits=true` 时统计 |
| `cocache.hits{level=l1}` | counter | L2 未命中、由 Redis 返回的读取 |
| `cocache.misses` | counter | L2 未命中次数，统计于同一 key 的并发未命中合并之前 |
| `cocache.loads` | timer | 调用 `CacheSource` 的次数与耗时，含失败 |
| `cocache.load.failures` | counter | `CacheSource` 抛出异常的次数 |
| `cocache.filtered` | counter | key 过滤器判定“不存在”的读取 |
| `cocache.stale.discards` | counter | 加载期间 key 被失效，加载结果被放弃或撤销的次数 |
| `cocache.evictions.received` | counter | 收到其它实例失效事件的次数 |
| `cocache.resets` | counter | 失效通道（重新）订阅并清空 L2 的次数 |
| `cocache.refreshes` | counter | 提前刷新的执行次数，含失败；其中的回源同时计入 `cocache.loads` |
| `cocache.load.rejections` | counter | 被 `cocache.load.max-concurrent` 拒绝的回源 |
| `cocache.l2.size` | gauge | 本实例 L2 的条目数 |
| `cocache.eviction.publish.failures` | counter | 发布失败的失效事件（无 `cache` 标签） |

常用比值：

- **L2 命中率** = `hits{level=l2}` / (`hits{level=l2}` + `misses`)。需要开启 `cocache.metrics.client-side-hits=true`：在最热的路径上计数会使 L2 命中吞吐下降 8%–15%（JMH，1–8 线程），因此默认关闭。其余指标都不在命中路径上记录，始终开启。
- **被合并的读取** = `misses` − `hits{level=l1}` − `loads`（不使用批量读取时），即等待其它线程加载结果的未命中。一次批量回源在 `loads` 中计为一次。
- **`eviction.publish.failures` > 0** 表示其它实例的 L2 可能保留旧副本，直到 `clientSideTtl` 或 `ttl` 到期。

## Redis 故障 {#redis-failures}

在默认的 `cocache.redis.strict-failure=false` 下，Redis 错误不会到达业务代码：

| 操作 | 默认（降级） | `strict-failure=true` |
|------|--------------|------------------------|
| L1 读取 | 记录 `WARN` 并视为未命中，值从数据源加载 | 重抛 `DataAccessException` |
| L1 写入 / 淘汰 | 记录 `WARN` | 重抛 |
| 发布淘汰事件 | 记录 `WARN`（两种模式相同） | 记录 `WARN` |

请为降级的代价做好准备：Redis 不可用期间，每次 L2 未命中都会访问数据源。由于并发未命中会合并，同一 key 在每个实例上同时最多只有一次加载。确认数据源能承受这部分负载，否则选择 `strict-failure=true` 并自行处理异常。

不同 key 的加载不会合并，故障期间仍可能同时产生大量回源。设置 `cocache.load.max-concurrent`（每个实例、每个缓存）即可限制：超过上限的回源最多等待 `cocache.load.acquire-timeout` 获取许可，超时抛出 `SourceLoadRejectedException`，并计入 `cocache.load.rejections`。合并到在途加载的读取不占用许可，一次批量回源占用一个许可。

失效通道断开期间不会进一步缩短 L2 副本的存活时间：通道重新订阅时每个实例都会清空 L2，因此断开造成的陈旧不会超过断开时长。连接未断但事件丢失的情况由 `clientSideTtl` 限定上界。

淘汰事件通道重连时，每个实例清空 L2，并以 `INFO` 级别记录 `Channel[…] subscribed - reset subscriber`。故障期间发布的事件已丢失，清空 L2 保证旧条目不会在故障后继续存在。

## 负缓存哨兵 {#the-negative-cache-sentinel}

在 Redis 中，负缓存以哨兵值存储，默认为 `_nil_`：

| Redis 类型 | 负缓存的形式 |
|------------|--------------|
| String | `_nil_` |
| Hash | 单个字段 `{_nil_: <写入时间>}` |
| Set | 单个成员 `{_nil_}` |

在内存中，负缓存是独立的类型（`MissingValue`），因此冲突只可能发生在 Redis 中。若一个 String 缓存的真实值可能恰好是 `_nil_`，读回时会被当作“不存在”；由其它程序写入的 Hash 或 Set 也可能以同样方式冲突。这种情况下请设置别的哨兵：

```yaml
cocache:
  redis:
    missing-guard-sentinel: "\u0000myapp:nil"
```

默认哨兵与自定义哨兵互不识别，因此**必须在所有实例上同时切换**。该值不能为空白。

## 从 CoCache 之外淘汰 {#external-eviction}

绕过 CoCache 的写入（直接执行 SQL、批处理任务、其它语言的服务）会让缓存保持陈旧直到 `ttl` 到期。任何能访问同一 Redis 的进程都可以用 CoCache 自己的协议淘汰 key。该格式在同一主版本内冻结。

| 部分 | 取值 |
|------|------|
| 存储 key | `keyPrefix` + 转换后的 key。默认前缀为 `cocache:{cacheName}:`，因此 `UserCache` 的 key `42` 是 `cocache:UserCache:42`。使用 `keyExpression` 时取表达式的结果 |
| 频道 | 缓存名，例如 `UserCache` |
| 消息 | `{storageKey}@@{publisherId}`，订阅方按最后一个 `@@` 切分。`publisherId` 不得包含 `@@`，且不得与任何实例的 `clientId` 相同，例如 `cdc` |

数据源提交后，按以下顺序淘汰：

1. `DEL {storageKey}`：删除 L1 副本。
2. `PUBLISH {cacheName} {storageKey}@@{publisherId}`：每个实例推进失效戳并丢弃 L2 副本。

```bash
redis-cli DEL cocache:UserCache:42
redis-cli PUBLISH UserCache 'cocache:UserCache:42@@cdc'
```

在变更数据捕获（Debezium、Canal 或任何行变更流）的消费者中：

```kotlin
class UserChangeEvictor(private val redis: StringRedisTemplate) {
    fun onUserChanged(userId: String) {   // 在变更提交后调用
        val storageKey = "cocache:UserCache:$userId"
        redis.delete(storageKey)
        redis.convertAndSend("UserCache", "$storageKey@@cdc")
    }
}
```

- 与 CoCache 自己的事件一样至多投递一次。消息丢失时，其它实例的 L2 保持陈旧，直到 `clientSideTtl`（若已设置）或 `ttl` 到期。
- 省略 `DEL` 会把旧值留在 L1，各实例会把它重新加载到 L2；省略 `PUBLISH` 会让其它实例的 L2 副本保留到过期。
- 恰好在 `DEL` 之前读到旧数据的实例仍可能把它写回 L1。这与[一致性](../architecture/consistency.md#what-is-not-guaranteed)中描述的 cache-aside 竞态相同，由 `ttl` 限定上界。

## 容量规划

- **L2 内存**约为 `maximumSize` × 平均值大小，按缓存、按实例计算。默认 `maximumSize` 为 10,000。
- **Redis 内存**为每个存活 key 存一份。负缓存体积很小，并在 `missingTtl` 后过期。
- **Redis Pub/Sub** 每个缓存一个频道，以缓存名命名。每次写入或淘汰发布一条消息，每个实例都会收到。

## 问题排查

**某个实例一直返回旧值。**
1. 确认写入方在数据库提交*之后*才淘汰。提交前淘汰会让并发读取重新加载旧数据。
2. 确认每条写入路径都会淘汰，包括批处理任务以及写同一份数据的其它服务。CoCache 之外的写入路径可以使用[淘汰协议](#external-eviction)。
3. 查看 `GET /actuator/cocache/{name}`：所有实例必须使用相同的缓存名，缓存名就是频道名。
4. 查看 `cocache.eviction.publish.failures`。发布失败时，其它实例的 L2 副本会保持陈旧，直到 `clientSideTtl`（若已设置）或 `ttl` 到期。
5. 剩下的陈旧来源是[一致性](../architecture/consistency.md#what-is-not-guaranteed)中描述的 cache-aside 竞态，由 `ttl` 限定上界。

**新建的数据被报告为不存在。** 数据创建前的一次查询缓存了负缓存，最多持续 `missingTtl` 秒。创建数据后淘汰该 key，或调低 `missingTtl`。

**启动失败，提示有多个 `CacheSource` 候选。** 有两个 bean 都匹配该缓存的 `CacheSource<K, V>` 类型。把正确的那个命名为 `{cacheName}.CacheSource`，或标记为 `@Primary`。
