---
title: 运维
description: 生产环境运行 CoCache：Actuator 端点、Redis 故障行为、负缓存哨兵、容量规划与陈旧读取排查。
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

## Redis 故障 {#redis-failures}

在默认的 `cocache.redis.strict-failure=false` 下，Redis 错误不会到达业务代码：

| 操作 | 默认（降级） | `strict-failure=true` |
|------|--------------|------------------------|
| L1 读取 | 记录 `WARN` 并视为未命中，值从数据源加载 | 重抛 `DataAccessException` |
| L1 写入 / 淘汰 | 记录 `WARN` | 重抛 |
| 发布淘汰事件 | 记录 `WARN`（两种模式相同） | 记录 `WARN` |

请为降级的代价做好准备：Redis 不可用期间，每次 L2 未命中都会访问数据源。由于并发未命中会合并，同一 key 在每个实例上同时最多只有一次加载。确认数据源能承受这部分负载，否则选择 `strict-failure=true` 并自行处理异常。

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

## 容量规划

- **L2 内存**约为 `maximumSize` × 平均值大小，按缓存、按实例计算。默认 `maximumSize` 为 10,000。
- **Redis 内存**为每个存活 key 存一份。负缓存体积很小，并在 `missingTtl` 后过期。
- **Redis Pub/Sub** 每个缓存一个频道，以缓存名命名。每次写入或淘汰发布一条消息，每个实例都会收到。

## 问题排查

**某个实例一直返回旧值。**
1. 确认写入方在数据库提交*之后*才淘汰。提交前淘汰会让并发读取重新加载旧数据。
2. 确认每条写入路径都会淘汰，包括批处理任务以及写同一份数据的其它服务。
3. 查看 `GET /actuator/cocache/{name}`：所有实例必须使用相同的缓存名，缓存名就是频道名。
4. 剩下的陈旧来源是[一致性](../architecture/consistency.md#what-is-not-guaranteed)中描述的 cache-aside 竞态，由 `ttl` 限定上界。

**新建的数据被报告为不存在。** 数据创建前的一次查询缓存了负缓存，最多持续 `missingTtl` 秒。创建数据后淘汰该 key，或调低 `missingTtl`。

**启动失败，提示有多个 `CacheSource` 候选。** 有两个 bean 都匹配该缓存的 `CacheSource<K, V>` 类型。把正确的那个命名为 `{cacheName}.CacheSource`，或标记为 `@Primary`。
