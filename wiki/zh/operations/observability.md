---
title: 可观测性
description: 每个 cocache.* Micrometer 指标的含义与告警建议、以 Micrometer Observation 追踪回源、cocache 健康检查、Actuator 端点（列出缓存、清空 L2、按 key 查看与失效），以及值得告警的日志。
---

# 可观测性

classpath 上有 `spring-boot-starter-actuator` 时，starter 通过四个渠道报告 CoCache 的状态：

| 渠道 | 用途 |
|------|------|
| [指标](#metrics)（`cocache.*`） | 趋势与告警 |
| [链路追踪](#tracing)（`cocache.load`） | 一次慢的未命中把时间花在了哪里 |
| [健康检查](#health)（`cocache`） | 本实例能否收到失效消息 |
| [Actuator 端点](#actuator-endpoint)（`/actuator/cocache`） | 本实例的计数器、查看一个 key、强制失效 |

另外还有[日志](#logs)。它们都不触碰 L2 命中路径。

## 指标 {#metrics}

starter 把以下指标绑定到每个 `MeterRegistry`，每个指标都带 `cache` 标签（缓存名）。它们读取引擎本来就在维护的计数器，是启动以来的累计值，不能单独关闭。延迟创建的缓存在其 bean 初始化时补绑。

Prometheus 名称遵循 Micrometer 的约定：`cocache.misses` 变为 `cocache_misses_total`，timer `cocache.loads` 变为 `cocache_loads_seconds_count` 与 `cocache_loads_seconds_sum`，gauge 变为 `cocache_local_size`。

### 读取 {#metrics-reads}

| 指标 | 类型 | 计数对象 |
|------|------|----------|
| `cocache.misses` | counter | L2 未命中的读取，按调用计数，在合并同一 key 的并发未命中之前。不含 `KeyFilter` 直接回答的读取。`SetCache` 上是 L1 中没有该集合的读取 |
| `cocache.hits{level=l1}` | counter | 由 L1 回答的 L2 未命中，包括负缓存 |
| `cocache.filtered` | counter | `KeyFilter` 判定“不存在”的读取，不访问 Redis |
| `cocache.lease.waits` | counter | 发现其它实例正在加载该 key 并等待它的未命中 |
| `cocache.lease.wait.timeouts` | counter | 停止等待（超过 `lease-wait`，或者对方 lease 的剩余寿命更短时超过该剩余寿命）、自行加载且不写 L1 的未命中 |

没有 L2 命中计数器：给最热的路径计数会造成可测量的吞吐损失。L2 命中数等于请求速率减去 `cocache.misses`。

### 加载 {#metrics-loads}

| 指标 | 类型 | 计数对象 |
|------|------|----------|
| `cocache.loads` | function timer | loader 调用次数与累计耗时。一次 `loadAll` 批量计一次。包含抛出异常的调用 |
| `cocache.load.failures` | counter | 抛出异常的 loader 调用。异常抛给调用方与所有合并的等待者 |
| `cocache.load.rejections` | counter | 被 `load-max-concurrent` 拒绝的加载：在 `load-acquire-timeout` 内没有拿到许可，调用方收到 `SourceLoadRejectedException` |
| `cocache.load.timeouts` | counter | 超过 `load-async-timeout` 的 `AsyncCacheLoader` 加载，调用方收到 `SourceLoadTimeoutException`，lease 已释放。见[异步 API](../guide/async.md#timeouts) |
| `cocache.refreshes` | counter | 执行了的提前刷新任务 |
| `cocache.refresh.rejections` | counter | 被刷新执行器拒绝的刷新。值照常提供，到期后正常过期 |

### 填充与栅栏 {#metrics-fills}

| 指标 | 类型 | 计数对象 |
|------|------|----------|
| `cocache.fill.rejections` | counter | 持有 lease、但 Redis 拒绝填充的加载：失效销毁了 lease、loader 慢于 `lease-ttl`，或者填充失败 |
| `cocache.degraded.fills` | counter | 只写入 L2、以 `degraded-local-max-age` 限龄的加载：Redis 读取失败、等待 lease 超时、填充被拒绝、值的格式未知，或者该 key 的失效仍在重试 |
| `cocache.stale.discards` | counter | 读取期间该 key 发生了失效，因而被丢弃的 L2 填充。这是 L2 栅栏在起作用 |
| `cocache.reclaims` | counter | 本实例无法解码的 L1 值（外来内容或编解码失败）。随后由单独的 reclaim 脚本在值未变的前提下把它转为 lease，本实例再回源 |
| `cocache.unknown.formats` | counter | 以比本实例更新的线格式写入的 L1 值。直接回源，不覆盖任何内容 |

### 写入、失效与通道 {#metrics-writes}

| 指标 | 类型 | 计数对象 |
|------|------|----------|
| `cocache.remote.failures` | counter | 读取侧失败的 Redis 操作：读取、填充、释放、回收、刷新 |
| `cocache.write.failures` | counter | 在 Redis 上失败的 `put`、`invalidate`、`invalidateAll` 与 `SetCache` 写入（首次尝试） |
| `cocache.invalidation.retries` | counter | 失败失效的重试次数 |
| `cocache.invalidations.dropped` | counter | 重试耗尽、重试队列已满或关闭时被放弃的失效。L1 中仍是旧值 |
| `cocache.invalidations.received` | counter | 来自其它实例或外部写入方的失效消息（按消息计，一条消息可能包含多个 key） |
| `cocache.resets` | counter | 因为消息可能已丢失而清空整个 L2 的次数：每次得到确认的（重新）订阅（包括启动时的第一次）与每次心跳失败 |
| `cocache.local.size` | gauge | 本实例 L2 中的条目数（估计值） |

### 告警 {#alerts}

| 条件 | 级别 | 含义 | 处理 |
|------|------|------|------|
| `increase(cocache_invalidations_dropped_total[5m]) > 0` | 紧急 | 有失效没有写到 Redis，所有实例都可能提供旧值，直到其 `ttl` 到期 | [失效被放弃](./troubleshooting.md#invalidations-dropped) |
| 健康检查 `cocache` 持续 `DOWN` 超过一分钟 | 紧急 | 本实例收不到失效消息，每次心跳都会清空它的 L2 | [命中率骤降](./troubleshooting.md#resets) |
| `rate(cocache_remote_failures_total[5m]) > 0` 持续 | 警告 | Redis 故障，读取在回源 | [Redis 不可用](./troubleshooting.md#redis-down) |
| `rate(cocache_load_rejections_total[5m]) > 0` | 警告 | 调用方收到 `SourceLoadRejectedException` | [回源被拒绝](./troubleshooting.md#load-rejections) |
| 非发布期间 `rate(cocache_resets_total[5m])` 大于 0 | 警告 | 订阅连接不稳定 | [命中率骤降](./troubleshooting.md#resets) |
| `cocache_lease_wait_timeouts_total` / `cocache_lease_waits_total` 超过 10% | 警告 | loader 慢于 `lease-wait`，跨实例的并发未命中各自回源 | [等待 lease 超时](./troubleshooting.md#lease-wait-timeouts) |
| 非发布期间 `increase(cocache_unknown_formats_total[10m]) > 0` | 警告 | 有实例在写更新的格式 | [未知格式](./troubleshooting.md#unknown-format) |
| `rate(cocache_reclaims_total[5m]) > 0` 持续 | 警告 | 有东西在写本实例无法解码的值 | [损坏的载荷](./troubleshooting.md#corrupt-payloads) |
| `rate(cocache_degraded_fills_total[5m])` 上升 | 提示 | 绕过 L1 的加载增多，数据源负载上升 | [Redis 不可用](./troubleshooting.md#redis-down) |

## 链路追踪 {#tracing}

classpath 上有 `micrometer-observation`、容器中有 `ObservationRegistry` bean（引入 actuator 后由 Spring Boot 的 `ObservationAutoConfiguration` 提供）时，starter 注册 bean `cocacheLoadObserver`（`MicrometerLoadObserver`），把每次回源包在一个 Micrometer `Observation` 中。追踪桥接（如 `micrometer-tracing-bridge-otel`）把它转为 span，Spring Boot 的指标处理器把它转为计时器。

| 字段 | 值 |
|------|----|
| 名称 | `cocache.load` |
| 上下文名称（span 名） | `cocache {operation} {cache}`，如 `cocache load UserCache` |
| `cache`（低基数） | 缓存名 |
| `operation`（低基数） | `load`（未命中、`get(key, loader)`，或批量读取退回单 key）、`loadAll`（一次批量回源）、`refresh`（提前刷新回源）、`lease.wait`（等待其它实例的 lease） |
| `outcome`（低基数） | `success` 或 `error`；异步加载超过 `load-async-timeout` 时为 `timeout`。`error` 时异常记录到 Observation 上，并原样抛出 |
| `key.count`（高基数，只出现在 span 上） | 本次调用的 key 数：1，`loadAll` 时为批量大小 |

观测范围：

- 只包住 loader 调用本身，以及确实发生的 lease 等待。L2 命中、L1 读取与填充都不产生 Observation；没有配置观测器时引擎连对象都不分配。被 `load-max-concurrent` 拒绝的加载不观测。
- 从不记录 key：高基数，且可能是个人信息。
- loader 在调用方线程上、在 Observation 的作用域内执行，因此 loader 产生的 span（JDBC、HTTP）是 `cocache.load` 的子 span，而 `cocache.load` 又是请求 span 的子 span。提前刷新在刷新执行器上执行，只有该执行器传播上下文时它的 span 才有父 span。
- 异步加载的观测从调用 `loadAsync` 开始，到其 future 完成为止。`loadAsync` 的同步部分在 Observation 的作用域内执行，在那里捕获上下文的响应式客户端（经 Micrometer context propagation）会产生子 span。
- 启用 Spring Boot 指标时，每个 Observation 还会产生 `cocache.load` 计时器与 `cocache.load.active` 长任务计时器，标签为 `cache`、`operation`、`outcome`（计时器上另有 Micrometer 自带的 `error` 标签）。它们与 [`cocache.loads`](#metrics-loads) 函数计时器重叠，后者仍是开销最低、始终开启的那一个。

用 `cocache.observation.enabled: false` 或 Spring Boot 的 `management.observations.enable.cocache: false` 关闭。自己声明的 `LoadObserver` bean 会取代 Micrometer 观测器；不用 Spring 时传给 `CacheRuntime.builder().loadObserver(…)`。实现必须在调用方线程上执行 block 恰好一次，并原样抛出其异常。要观测异步加载，还需覆盖 `observeAsync`：默认实现直接执行 block，不观测。

## 健康检查 {#health}

starter 注册健康检查 `cocache`（bean `cocacheHealthIndicator`）。它只读取内存中的状态，从不访问 Redis，频繁轮询也很便宜。用 `management.health.cocache.enabled: false` 关闭。

| 状态 | 条件 |
|------|------|
| `DOWN` | Redis 订阅连接最近一次心跳失败。其它实例的失效可能丢失，本实例的 L2 已被清空。L1 可能仍然可用 |
| `UP` | 其它情况，包括失效正在重试时 |

`GET /actuator/health/cocache`（`management.endpoint.health.show-details` 允许展示详情时）：

```json
{
  "status": "UP",
  "details": {
    "backend": "RedisBackend",
    "format": 1,
    "atomicBroadcast": true,
    "channelMode": "pubsub",
    "heartbeat": { "healthy": true },
    "pendingInvalidations": 0,
    "caches": {
      "UserCache": { "pendingInvalidations": 0, "localSize": 8123 }
    }
  }
}
```

| 详情 | 含义 |
|------|------|
| `backend` | `RedisBackend`、`InMemoryBackend` 或你自己的类 |
| `format` | 本实例写入的线格式版本 |
| `atomicBroadcast` | `false` 表示写入在脚本之后单独发布，见[通道模式](./index.md#channel-mode) |
| `channelMode` | `pubsub` 或 `sharded`（仅 Redis） |
| `heartbeat` | `healthy`，失败时另有 `error`（最近一次失败的原因，仅 Redis）。下一次 PING 成功即恢复 |
| `pendingInvalidations` | 写入 Redis 失败、正在重试的失效 key 数，分总数与每个缓存。重试成功之前，这些 key 的未命中会跳过 L1 |
| `caches.*.localSize` | L2 条目数（估计值） |

失效重试不改变状态：重试队列会自行收敛，最终失败会体现在 `cocache.invalidations.dropped` 中。

**就绪探针**。不要把 `cocache` 放进存活（liveness）组：重启解决不了 Redis 的问题。是否放进就绪（readiness）组（`management.endpoint.health.group.readiness.include: readinessState,cocache`）是一个取舍。`DOWN` 期间，实例仍从 L1 与数据源提供数据，陈旧度以 `local-max-age` 为上界，通常仍然可以提供服务。只有当漏掉失效比损失容量更糟时才加入。注意，这样一来 Redis 故障会让所有实例同时退出流量。

## Actuator 端点 {#actuator-endpoint}

端点 `cocache` 只在可用时才创建，所以需要暴露它：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, cocache
```

| 请求 | 效果 | 范围 |
|------|------|------|
| `GET /actuator/cocache` | 所有缓存的报告 | 本实例 |
| `GET /actuator/cocache/{name}` | 一个缓存的报告 | 本实例 |
| `DELETE /actuator/cocache/{name}` | 清空该缓存的 L2。L1 不受影响，正在进行的加载不会把结果写入 L2 | 本实例 |
| `GET /actuator/cocache/{name}/{key}` | [查看一个 key](#inspect-key) 在 L2 与 L1 中的状态 | 本实例，外加一次 Redis 读取 |
| `DELETE /actuator/cocache/{name}/{key}` | [失效一个 key](#invalidate-key) | **所有实例** |

`{name}` 是缓存名。缓存不存在时，`GET` 返回 `404`，`DELETE` 返回无内容的 `204`（按 key 的 `DELETE` 成功时返回 `200` 及结果）。空的 `{key}` 以 `400` 拒绝。Spring 会去掉空的路径段与首尾空白，因此含 `//`、首尾 `/` 或首尾空格的 key 无法经端点精确寻址。

缓存报告：

```json
{
  "name": "UserCache",
  "localSize": 8123,
  "loads": 5120,
  "loadNanos": 912000000,
  "metrics": { "remoteHits": 40211, "misses": 45342, "filtered": 0, "loadFailures": 2, "loadRejected": 0, "leaseWaits": 31, "leaseWaitTimeouts": 0, "fillRejected": 1, "degradedFills": 1, "staleFillsDiscarded": 3, "reclaims": 0, "unknownFormats": 0, "refreshes": 0, "refreshRejected": 0, "remoteFailures": 0, "invalidationsReceived": 977, "writeFailures": 0, "invalidationRetries": 0, "invalidationsDropped": 0, "resets": 1 }
}
```

`metrics` 的字段与指标一一对应：`remoteHits` 是 `cocache.hits{level=l1}`，`loadRejected` 是 `cocache.load.rejections`，`fillRejected` 是 `cocache.fill.rejections`，`staleFillsDiscarded` 是 `cocache.stale.discards`，`refreshRejected` 是 `cocache.refresh.rejections`，`loadTimeouts` 是 `cocache.load.timeouts`，其余与指标名对应。`loads` 与 `loadNanos` 就是 timer `cocache.loads`。

### 编码后的 key {#encoded-key}

`{key}` 是**编码后的 key**：Redis key 中 `{keyPrefix}{namespace}:` 之后的部分，即 key 的 `toString()` 或缓存 `keyExpression` 的结果，所以端点适用于任意 key 类型。

| 缓存 key | 编码后的 key | 请求 |
|----------|--------------|------|
| `UserCache` 中的 `"42"` | `42` | `/actuator/cocache/UserCache/42` |
| data class `OrderId(tenant=7, id=9)` | `OrderId(tenant=7, id=9)` | `/actuator/cocache/OrderCache/OrderId(tenant=7,%20id=9)` |
| `keyExpression = "#{tenant}/#{id}"` | `7/9` | `/actuator/cocache/OrderCache/7/9` |

- key 中的 `/` 原样写出：缓存名之后的所有路径段会用 `/` 拼接起来。
- 空格、`%`、`?`、`#` 需要百分号编码。
- 含空段（`a//b`、以 `/` 开头或结尾）、`.` 或 `..` 段、`;` 或编码后的 `%2F` 的 key，可能被 Web 服务器的路径处理改写或拒绝。这类 key 请在代码中调用 `CacheAdmin`：`runtime.admin("OrderCache")?.inspect(encodedKey)`。

### 查看一个 key {#inspect-key}

```bash
curl -s localhost:8080/actuator/cocache/UserCache/42
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

| 字段 | 取值 |
|------|------|
| `storageKey` | 完整的 Redis key，供 `redis-cli` 使用 |
| `local` | 本实例的 L2 副本，没有时为 `null`。`state`：`VALUE`、`ABSENT`（负缓存）或 `UNRESOLVED`（没有 loader 的缓存未命中）。`expired: true` 表示已过 `expiresAt`、但 Caffeine 尚未移除的副本，它永远不会被返回 |
| `remote.state` | `VALUE`、`ABSENT`（负缓存）、`SET`（`SetCache`）、`LEASED`（某个实例正在加载，`ttl` 是 lease 的剩余寿命）、`MISS`、`UNREADABLE`（外来内容、更新的格式、形状不符或编解码失败，原因见 `error`）或 `ERROR`（读取 Redis 失败） |
| `remote.ttl` | L1 中的剩余寿命，永不过期时为 `null` |
| `value` | 用缓存的编解码器解码后的 `toString()`，截断到 256 个字符 |
| `invalidationPending` | 该 key 的失效写入 Redis 失败，仍在重试。成功之前，未命中会跳过 L1 |

查看是只读的：不调用 loader，不申请 lease 或刷新令牌，不填充任何一层，不与加载合并，不计入指标，不回收损坏的内容，也不影响 Caffeine 的淘汰统计。代价是一次 Redis 读取。

如何解读结果：

| `local` 与 `remote` | 含义 |
|---------------------|------|
| 值相同 | 一致 |
| `local` 比 `remote` 旧 | 本实例漏掉了一次失效，最迟在 `local.expiresAt` 自愈。见[单个实例陈旧](./troubleshooting.md#stale-one-node) |
| 有 `local`，`remote` 为 `MISS` | 该 key 在 L1 中已被失效或已过期。如果 `local` 早于那次失效，说明本实例漏掉了消息 |
| 两者都旧 | 数据源变化之后没有任何东西失效这个 key。见[所有实例都陈旧](./troubleshooting.md#stale-everywhere) |
| `remote` 为 `UNREADABLE` | 见[损坏的载荷](./troubleshooting.md#corrupt-payloads)或[未知格式](./troubleshooting.md#unknown-format) |

### 失效一个 key {#invalidate-key}

```bash
curl -s -X DELETE localhost:8080/actuator/cocache/UserCache/42
# {"cache":"UserCache","key":"42"}
```

它走正常的失效路径，与 `cache.invalidate(key)` 相同：删除 L1，每个实例丢弃自己的 L2 副本，下一次读取从数据源加载。操作幂等。Redis 失败时按缓存的 `invalidate-failure` 处理（默认进入重试队列并返回 `200`；设为 `throw` 时返回错误）。在 `SetCache` 上它等价于 `clear(key)`，会**删除集合的唯一副本**。

## 日志 {#logs}

logger 都在 `me.ahoo.cocache` 下。值得告警或放进看板的日志：

| 级别 | 消息（开头） | 含义 |
|------|--------------|------|
| `ERROR` | `Cache[…] - invalidate […] dropped: L1 keeps the stale value until its remote TTL[…] expires.` | 一次失效被放弃，对应 `cocache.invalidations.dropped` |
| `WARN` | `Cache[…] - invalidate […] failed, retrying.` | 失效写入 Redis 失败，已进入重试队列 |
| `WARN` | `Invalidation channel heartbeat failed - reset all subscribers and force reconnect.` | 订阅连接的 PING 失败，所有 L2 已清空 |
| `WARN` | `Invalidation channel - cannot force reconnect of the subscription connection, rely on TCP keepalive.` | Redis Cluster 的 `pubsub` 模式：CoCache 无法自行断开该连接，依赖 TCP keepalive |
| `WARN` | `Invalidation channel[…] unsubscribed by server (slot migration) - reset subscribers and resubscribe.` | `sharded` 模式下 slot 发生迁移。resharding 期间属正常 |
| `WARN` | `Invalidation channel[…] - subscribed on […] but slot is owned by […] - reset subscribers and resubscribe.` | `sharded` 模式下心跳发现了故障切换 |
| `WARN` | `Invalidation channel[…] - resubscribe failed, retry on next heartbeat.` | 重新订阅失败，下次心跳重试 |
| `WARN` | `Invalidation channel[…] - subscribe acknowledgement not received in …` | Redis 没有在 `command-timeout` 内确认订阅 |
| `WARN` | `Ignore malformed invalidation frame on channel[…]` | 有人在 CoCache 的频道上发布了非 CoCache 消息。见[外部失效](./troubleshooting.md#external-invalidation) |
| `WARN` | `Cache[…] - key[…] - remote … failed, degrading.` | Redis 读取、填充、释放或回收失败，本次读取改为回源 |
| `WARN` | `Cache[…] - key[…] - corrupted payload, reclaim and reload.` | 一个 L1 值无法解码 |
| `WARN` | `Cache[…] - key[…] - refresh failed.` / `refresh rejected.` | 提前刷新没有执行或失败，值正常到期 |
| `WARN` | `Atomic broadcast is not supported by this Redis deployment - publish after writes instead.` | 启动探测降级，见[通道模式](./index.md#channel-mode) |
| `WARN` | `@Cacheable on … uses CoCache cache … without sync = true …` | 某个 Spring Cache 方法不受 lease 保护，见 [Spring Cache](../guide/spring-cache.md#use-sync-true) |
| `WARN` | `CoCache uses the in-memory backend …` | 部署环境中出现 `cocache.backend=memory` 属于配置错误 |
| `INFO` | `Invalidation channel[…] subscribed - reset subscribers.` 与 `Cache[…] - reset: clear local copies.` | （重新）订阅清空了 L2。启动与重连后属于正常 |
