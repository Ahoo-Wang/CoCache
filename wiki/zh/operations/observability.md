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
| [Actuator 端点](#actuator-endpoint)（`/actuator/cocache`） | 本实例的计数器、查看一个 key、强制失效或只广播失效 |

另外还有[日志](#logs)。除默认关闭的 [L2 命中计数](#metrics-reads)外，它们都不触碰 L2 命中路径。

## 指标 {#metrics}

starter 把以下指标绑定到每个 `MeterRegistry`，每个指标都带 `cache` 标签（缓存名）。它们读取引擎本来就在维护的计数器，是启动以来的累计值，不能单独关闭。之后创建的缓存（如延迟初始化的缓存 bean、库在运行时创建的缓存）在创建时补绑。

Prometheus 名称遵循 Micrometer 的约定：`cocache.misses` 变为 `cocache_misses_total`，timer `cocache.loads` 变为 `cocache_loads_seconds_count` 与 `cocache_loads_seconds_sum`，gauge 变为 `cocache_local_size`。

### 读取 {#metrics-reads}

| 指标 | 类型 | 计数对象 |
|------|------|----------|
| `cocache.misses` | counter | L2 未命中的读取，按调用计数，在合并同一 key 的并发未命中之前。不含 `KeyFilter` 直接回答的读取。`SetStore` 上是 L1 中没有该集合的读取 |
| `cocache.hits{level=l2}` | counter | 由 L2 回答的读取，包括负缓存。只在 `cocache.metrics.local-hits=true` 时注册 |
| `cocache.hits{level=l1}` | counter | 由 L1 回答的 L2 未命中，包括负缓存 |
| `cocache.filtered` | counter | `KeyFilter` 判定“不存在”的读取，不访问 Redis |
| `cocache.lease.waits` | counter | 发现其它实例正在加载该 key 并等待它的未命中 |
| `cocache.lease.wait.timeouts` | counter | 停止等待（超过 `lease-wait`，或者对方 lease 的剩余寿命更短时超过该剩余寿命）、自行加载且不写 L1 的未命中 |
| `cocache.reads.unknown` | counter | 因 Redis 失败而没有答案的读取（[`Lookup.Unknown`](../guide/caches.md#unknown)），按调用方计：抛出 `CacheReadException` 的、`lookup` 返回 `Unknown` 的，以及由 `unknownRead = ABSENT` 换算为 `null` 的。Redis 故障本身另计入 `cocache.remote.failures` |

每次读取（`getAll` 按 key）恰好计入 `hits{level=l2}`、`misses` 或 `filtered` 之一。L2 未命中之后，Redis 中有该 key 时计入 `hits{level=l1}`，调用了 loader 时计入 `loads`。

L2 命中计数**默认关闭**：它给每次 L2 命中（最热的路径）加一次原子自增，多线程下有可测量的吞吐损失。用 `cocache.metrics.local-hits=true` 开启（不用 Spring 时：`CacheRuntime.builder().recordLocalHits(true)`），作用于该运行时的全部缓存。关闭时，L2 命中数等于请求速率减去 `cocache.misses` 与 `cocache.filtered`。

### 加载 {#metrics-loads}

| 指标 | 类型 | 计数对象 |
|------|------|----------|
| `cocache.loads` | function timer | loader 调用次数与累计耗时。一次 `loadAll` 批量计一次。包含抛出异常的调用 |
| `cocache.load.failures` | counter | 抛出异常的 loader 调用。异常抛给调用方与所有合并的等待者 |
| `cocache.load.rejections` | counter | 被 `load-max-concurrent` 拒绝的加载：在 `load-acquire-timeout` 内没有拿到许可，调用方收到 `SourceLoadRejectedException` |
| `cocache.load.timeouts` | counter | 超过 `load-timeout` 的加载：`AsyncCacheLoader` 的加载（lease 已释放），以及等待别的调用方加载这么久的读取。它们都收到 `SourceLoadTimeoutException`。见[异步 API](../guide/async.md#timeouts) |
| `cocache.refreshes` | counter | 执行了的提前刷新任务 |
| `cocache.refresh.rejections` | counter | 被刷新执行器拒绝的刷新。值照常提供，到期后正常过期 |
| `cocache.refresh.skipped` | counter | 到期却没有运行的提前刷新：刷新令牌在别的实例手里，本实例只重新读了 Redis 并退避；或缓存提前刷新、副本却没有刷新点（剩余寿命不长于 `refresh-window`，或刷新只会读到同样的内容：更高的格式、被 evict 的版本、`Store` 写入的值）。这样的副本照常提供，到期后正常过期 |

### 填充与栅栏 {#metrics-fills}

| 指标 | 类型 | 计数对象 |
|------|------|----------|
| `cocache.fill.rejections` | counter | 持有 lease 或刷新令牌、但 Redis 拒绝填充的加载：失效销毁了令牌、loader 慢于 `lease-ttl`、填充失败，或者（带版本的缓存）loader 返回的版本比 Redis 中的旧 |
| `cocache.degraded.fills` | counter | 只写入 L2、以 `degraded-local-max-age` 限龄的加载：Redis 读取失败、等待 lease 超时、填充被拒绝、值的格式未知，或者该 key 的失效仍在重试。没有数据源的读取在 Redis 失败时不计入：它没有答案，也不缓存任何东西（计入 `cocache.reads.unknown` 与 `cocache.remote.failures`） |
| `cocache.fills.gated` | counter | Spring 读写事务内保持私有的加载（`fill-in-transaction`），按 key 计：不申请 lease、不写 Redis 与 L2、不与其它调用方共享。每次同时计入未命中与加载。见[事务内的回源](../guide/spring-cache.md#transactional-loads)。 |
| `cocache.fill.unversioned.absent` | counter | `VersionedCache` 的带版本数据源返回普通 `null`、而 Redis 中该 key 是带版本的内容的加载。Redis 拒绝这个负缓存，读取返回“不存在”，下一次未命中再次加载。删除应表达为 `Versioned(null, version)`（[带版本的数据源](../guide/versioned-cache.md#versioned-loaders)）。每个缓存的第一次记录告警 |
| `cocache.stale.discards` | counter | 读取期间该 key 发生了失效，或（带版本的缓存）L2 中已是比这次填充更新的版本（如 loader 读到落后的从库），因而被丢弃的 L2 填充。这是 L2 栅栏在起作用 |
| `cocache.reclaims` | counter | 本实例无法解码的 L1 值（外来内容或编解码失败）。随后由单独的 reclaim 脚本在值未变的前提下把它转为 lease，本实例再回源 |
| `cocache.unknown.formats` | counter | 以比本实例更新的线格式写入、或带本实例不认识的必须理解的标志位的 L1 值：有数据源的读取绕过它回源，不覆盖任何内容；外部来源的 `Cache` 上不带 loader 的读取把该 key 当作未填充；权威类型的读取失败（`CacheReadException`）。另外还有：没有数据源的读取遇到的读不懂的内容（读取抛出 `CacheReadException`）；落在读不懂的内容上而被拒绝的 `SetStore.add` / `remove`、`putIfMatch` / `deleteIfMatch`、`putIfNewer` / `invalidateIfNewer` / `deleteIfNewer` 与 `evict`（抛出 `CacheWriteException`，同时计入 `cocache.write.failures`）；以及读到读不懂的内容的 `getIfChanged` |

### 写入、失效与通道 {#metrics-writes}

| 指标 | 类型 | 计数对象 |
|------|------|----------|
| `cocache.remote.failures` | counter | 读取侧失败的 Redis 操作：读取、填充、释放、回收、刷新 |
| `cocache.write.failures` | counter | 各种类型在 Redis 上失败的写入与删除（首次尝试）：`put`、`putAll`、`invalidate`、`delete`、条件写入与按版本的写入、`evict`、`purge`，以及 `SetStore` 的写入。冲突与过时的版本不算失败 |
| `cocache.conditional.conflicts` | counter | 因 Redis 中是另一个值而没有写入的 `putIfMatch` 与 `deleteIfMatch`（[条件写入](../guide/store.md#conditional-writes)）。持续出现说明写入方经常在同一个 key 上竞争 |
| `cocache.conditional.unchanged` | counter | 确认值未变、没有传输值的 `getIfChanged` |
| `cocache.versioned.stale` | counter | 因 Redis 中已是这个版本或更新的版本而没有写入的 `putIfNewer` / `invalidateIfNewer` / `deleteIfNewer`（[按版本写入](../guide/freshness.md#versioned-writes)）：重复投递与迟到的旧版本 |
| `cocache.type.mismatch` | counter | `Cache` 或 `VersionedCache` 读到 `Store` 写入的值（带摘要段）的次数，以及在这样的值上被拒绝的 `VersionedCache.evict`。说明同一个 namespace 上还有 `Store`：值照常读取，但从不被回收或刷新，淘汰抛出。每个缓存只记录一次日志。见[五种类型](../guide/caches.md#types) |
| `cocache.invalidation.retries` | counter | 失败失效的重试次数 |
| `cocache.invalidations.dropped` | counter | 重试耗尽、重试队列已满或关闭时被放弃的失效。L1 中仍是旧值 |
| `cocache.invalidations.received` | counter | 来自其它实例或外部写入方的失效消息（按消息计，一条消息可能包含多个 key），消息生效之后才计数：计数增加时，它的 key 的本地副本已经淘汰 |
| `cocache.resets` | counter | 因为消息可能已丢失而清空整个 L2 的次数：每次得到确认的（重新）订阅（包括启动时的第一次，以及缓存自己订阅本实例已订阅的频道）、每次心跳失败、其频道上每个读不懂的帧，以及 `sharded` 模式下每次因 slot 迁移而失去的订阅（并且在重新得到确认之前的每次心跳） |
| `cocache.ttl.clamped` | counter | 长于 `cocache.overrides.cache.ttl`、改按这个覆盖值写入的按调用寿命（`ttlOf` 的结果，`put(key, value, ttl)` 与 `putAll(entries, ttl)` 的 `ttl`）。每个缓存第一次钳制时记录告警。见[各层能设置什么](../guide/configuration.md#field-layers) |
| `cocache.local.size` | gauge | 本实例 L2 中的条目数（估计值） |

### Redis 后端 {#metrics-backend}

这个指标属于后端而不属于某个缓存，所以没有 `cache` 标签。每个 registry 注册一次，只有 Redis 后端才有，被包装（如包在 `HookedBackend` 中）时同样有。

| 指标 | 类型 | 计数对象 |
|------|------|----------|
| `cocache.redis.releases.abandoned` | counter | 放弃的 lease 与刷新令牌的释放。取得或交回令牌的 Redis 命令以结果未知失败时，CoCache 释放这个令牌，失败时按退避重发；令牌的寿命已过、连接已关闭或释放根本发不出去时放弃。令牌随后在寿命结束时回收（lease 为 `lease-ttl`），在此之前所有实例上这个 key 的未命中都要等待至多 `lease-wait`。命令超时风暴期间会上升（[命令超时](./troubleshooting.md#command-timeouts)） |

### 告警 {#alerts}

| 条件 | 级别 | 含义 | 处理 |
|------|------|------|------|
| `increase(cocache_invalidations_dropped_total[5m]) > 0` | 紧急 | 有失效没有写到 Redis，所有实例都可能提供旧值，直到其 `ttl` 到期 | [失效被放弃](./troubleshooting.md#invalidations-dropped) |
| 健康检查 `cocache` 持续 `DOWN` 超过一分钟 | 紧急 | 本实例收不到失效消息，每次心跳都会清空它的 L2 | [命中率骤降](./troubleshooting.md#resets) |
| `rate(cocache_remote_failures_total[5m]) > 0` 持续 | 警告 | Redis 故障，有数据源的读取在回源 | [Redis 不可用](./troubleshooting.md#redis-down) |
| `rate(cocache_reads_unknown_total[5m]) > 0` | 警告 | 没有数据源的读取没有答案：调用方得到 `CacheReadException` | [Redis 不可用](./troubleshooting.md#redis-down) |
| `increase(cocache_fill_unversioned_absent_total[10m]) > 0` | 警告 | 带版本的数据源对已删除的行返回普通 `null`，这些 key 的每次未命中都重新加载 | [带版本的数据源](../guide/versioned-cache.md#versioned-loaders) |
| `rate(cocache_load_rejections_total[5m]) > 0` | 警告 | 调用方收到 `SourceLoadRejectedException` | [回源被拒绝](./troubleshooting.md#load-rejections) |
| 非发布期间 `rate(cocache_resets_total[5m])` 大于 0 | 警告 | 订阅连接不稳定 | [命中率骤降](./troubleshooting.md#resets) |
| `rate(cocache_lease_wait_timeouts_total[5m]) / rate(cocache_lease_waits_total[5m])` 超过 10% | 警告 | loader 慢于 `lease-wait`，跨实例的并发未命中各自回源 | [等待 lease 超时](./troubleshooting.md#lease-wait-timeouts) |
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
| `outcome`（低基数） | `success` 或 `error`；异步加载超过 `load-timeout` 时为 `timeout`。`error` 时异常记录到 Observation 上，并原样抛出 |
| `key.count`（高基数，只出现在 span 上） | 本次调用的 key 数：1，`loadAll` 时为批量大小 |

观测范围：

- 只包住 loader 调用本身，以及确实发生的 lease 等待。L2 命中、L1 读取与填充都不产生 Observation；没有配置观测器时引擎连对象都不分配。被 `load-max-concurrent` 拒绝的加载不观测。
- 从不记录 key：高基数，且可能是个人信息。
- 阻塞 API 上，loader 在调用方线程上、在 Observation 的作用域内执行，因此 loader 产生的 span（JDBC、HTTP）是 `cocache.load` 的子 span，而 `cocache.load` 又是请求 span 的子 span。提前刷新在刷新执行器上执行，只有该执行器传播上下文时它的 span 才有父 span。
- 异步与挂起的加载在自己的虚拟线程上观测，每次被观测的加载一个（`cocache-observe-N`），从不在调用方线程上，也不在运行时的执行器上。这个线程在整个加载期间等待，观测因此持续到 loader 的 future 完成。调用 `observe` 时已装上挂起调用方协程上下文中的元素（`ThreadContextElement`，如 MDC、追踪上下文），加载的观测因此接在调用方的观测之下；异步视图（`CompletableFuture`）的调用方没有这类元素。加载从这个线程上、在 Observation 的作用域内开始，开始时不再重新装上调用方的元素，所以 `loadAsync`（或调用级挂起 loader）的同步部分看到的当前观测是 `cocache.load`，调用方以 `ThreadContextElement` 传播自己的观测时也一样，在那里捕获上下文的响应式客户端（经 Micrometer context propagation）会产生子 span。loader 第一次挂起之后，它的代码恢复时重新装上调用方的元素：MDC 仍在，当前观测是调用方的。
- 启用 Spring Boot 指标时，每个 Observation 还会产生 `cocache.load` 计时器，标签为 `cache`、`operation`、`outcome` 与 Micrometer 自带的 `error`；以及 `cocache.load.active` 长任务计时器，标签只有 `cache` 与 `operation`（加载进行期间结果尚未可知）。它们与 [`cocache.loads`](#metrics-loads) 函数计时器重叠，后者仍是开销最低、始终开启的那一个。

用 `cocache.observation.enabled: false` 或 Spring Boot 的 `management.observations.enable.cocache: false` 关闭。自己声明的 `LoadObserver` bean 会取代 Micrometer 观测器；不用 Spring 时传给 `CacheRuntime.builder().loadObserver(…)`。`LoadObserver` 只有一个方法 `observe`：实现必须在调用 `observe` 的线程上同步执行 block 恰好一次，原样返回结果、原样抛出异常。异步与挂起的加载经同一个方法观测，CoCache 在上面说的观测线程上调用它。

## 健康检查 {#health}

starter 注册健康检查 `cocache`（bean `cocacheHealthIndicator`）。它只读取内存中的状态，从不访问 Redis，频繁轮询也很便宜。用 `management.health.cocache.enabled: false` 关闭。

| 状态 | 条件 |
|------|------|
| `DOWN` | Redis 订阅连接最近一次心跳失败。其它实例的失效可能丢失，本实例的 L2 已被清空。L1 可能仍然可用 |
| `UP` | 其它情况，包括失效正在重试、命令连接的心跳失败时 |

`GET /actuator/health/cocache`（`management.endpoint.health.show-details` 允许展示详情时）：

```json
{
  "status": "UP",
  "details": {
    "backend": "RedisBackend",
    "format": 2,
    "atomicBroadcast": true,
    "channelMode": "pubsub",
    "heartbeat": { "healthy": true },
    "commandConnection": { "healthy": true },
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
| `commandConnection` | 命令连接的心跳（仅 Redis）：`healthy`，失败时另有 `error`，给出最近一次 PING 失败的每条连接（单机为 `redis`，Cluster 为主节点的 `host:port`）及原因。下一次每条命令连接都应答 PING 时恢复 |
| `pendingInvalidations` | 写入 Redis 失败、正在重试的失效 key 数，分总数与每个缓存。重试成功之前，这些 key 的未命中会跳过 L1 |
| `caches.*.localSize` | L2 条目数（估计值） |
| `storeEviction` | [驱逐检查](../guide/configuration.md#eviction-check)对权威缓存最近一次的结论：`safe`、`policies`（各主节点的 `maxmemory-policy`，键为 `host:port`，单机为 `redis`，来自 `assume-maxmemory-policy` 时为 `assumed`）与 `checkedAt`；不安全时另有 `risk`（`unreadable-policy`、`evicts-any-key` 或 `evicts-expiring-keys`）与涉及的 `caches`。后端不是 Redis、没有权威缓存或尚未检查时不列出。 |

Redis 的详情取自运行时使用的后端，看穿 `HookedBackend` 这样的包装（这时 `backend` 是包装的类名）。`storeEviction` 是快照，在启动时、创建权威缓存时与每个 `cocache.redis.store-eviction-check-interval` 更新；读取它不访问 Redis，它也从不改变状态。

失效重试不改变状态：重试队列会自行收敛，最终失败会体现在 `cocache.invalidations.dropped` 中。命令连接的心跳失败同样不改变状态：一次 PING 可能排在大载荷后面超时；命令连接不可用时读取改为回源、写入失败，把实例判为不健康也不会让 Redis 恢复。连续 `cocache.redis.command-heartbeat-failures` 次（默认 3 次）PING 失败后，CoCache 断开这条连接，由 Lettuce 重连（[命令超时](./troubleshooting.md#command-timeouts)）。

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
| `DELETE /actuator/cocache/{name}/{key}` | 按类型[删除一个 key](#invalidate-key)；`?purge=true` 清除带版本的 key | **所有实例** |
| `POST /actuator/cocache/{name}/{key}` | 为一个 key [只广播失效](#broadcast-key)：各实例丢掉 L2 副本，Redis 不变 | **所有实例** |

`{name}` 是缓存名。缓存不存在时，`GET`、`DELETE` 与 `POST` 都返回 `404`，不做任何修改。`DELETE /actuator/cocache/{name}` 成功时返回 `204`，按 key 的 `DELETE` 与 `POST` 成功时返回 `200` 及结果。`DELETE` 与 `POST` 是写操作：`management.endpoint.cocache.access: read-only` 时只剩 `GET` 请求。空的 `{key}` 以 `400` 拒绝。Spring 会去掉空的路径段与首尾空白，因此含 `//`、首尾 `/` 或首尾空格的 key 无法经端点精确寻址。

缓存报告：

```json
{
  "name": "UserCache",
  "type": "Cache",
  "keyPrefix": "cocache:",
  "namespace": "UserCache",
  "layered": true,
  "ignoredConfiguration": [],
  "policy": [
    { "field": "ttl", "value": "PT10M", "source": "CACHES" },
    { "field": "ttl-jitter", "value": "PT1M", "source": "TYPE_DEFAULT" },
    { "field": "absent-ttl", "value": "PT1M", "source": "TYPE_DEFAULT" },
    { "field": "local-max-age", "value": "PT2M", "source": "OVERRIDES", "detail": "tightened from PT5M" },
    { "field": "degraded-local-max-age", "value": "PT10S", "source": "TYPE_DEFAULT" },
    { "field": "refresh-window", "value": "off", "source": "TYPE_DEFAULT" },
    { "field": "lease-ttl", "value": "PT10S", "source": "TYPE_DEFAULT" },
    { "field": "lease-wait", "value": "PT0.2S", "source": "TYPE_DEFAULT" },
    { "field": "load-max-concurrent", "value": "32", "source": "CACHES" },
    { "field": "load-acquire-timeout", "value": "PT1S", "source": "TYPE_DEFAULT" },
    { "field": "load-timeout", "value": "PT30S", "source": "TYPE_DEFAULT" },
    { "field": "read-timeout", "value": "command-timeout", "source": "TYPE_DEFAULT" },
    { "field": "local-maximum-size", "value": "100000", "source": "DECLARATION" },
    { "field": "invalidate-failure", "value": "retry", "source": "TYPE_DEFAULT" },
    { "field": "read-failure", "value": "degrade", "source": "TYPE_DEFAULT" }
  ],
  "skippedConfiguration": [
    { "key": "cocache.overrides.cache.lease-wait", "layer": "cocache.overrides.cache", "reason": "not allowed in cocache.overrides: only staleness bounds, read-failure, fill-in-transaction and invalidate-fills-on-rollback tighten" }
  ],
  "localSize": 8123,
  "loads": 5120,
  "loadNanos": 912000000,
  "metrics": { "remoteHits": 40211, "misses": 45342, "filtered": 0, "loadFailures": 2, "loadRejected": 0, "loadTimeouts": 0, "leaseWaits": 31, "leaseWaitTimeouts": 0, "fillRejected": 1, "degradedFills": 1, "staleFillsDiscarded": 3, "reclaims": 0, "unknownFormats": 0, "refreshes": 0, "refreshRejected": 0, "refreshSkipped": 0, "remoteFailures": 0, "invalidationsReceived": 977, "writeFailures": 0, "invalidationRetries": 0, "invalidationsDropped": 0, "resets": 1, "conditionalUnchanged": 0, "conditionalConflicts": 0, "staleVersions": 0, "typeMismatches": 0, "readsUnknown": 0, "unversionedAbsentRefusals": 0, "fillsGated": 0, "ttlClamped": 0 }
}
```

`type` 是缓存的 API 类型（`Cache`、`VersionedCache`、`Store`、`VersionedStore` 或 `SetStore`）。`keyPrefix` 与 `namespace` 是缓存的 keyspace（`CacheAdmin.keyspace`），已应用 [`cocache.key-prefix`](../guide/configuration.md#key-prefix) 与 `cocache.caches.[name].key-prefix`：它的 Redis key 是 `{keyPrefix}{namespace}:{key}`，`{key}` 是[编码后的 key](#encoded-key)。拼存储 key 与 [ACL](./index.md#acl) 的 key 模式时用它们，不必为每个缓存另外记录前缀。`layered: false` 表示缓存直接经 `CacheRuntime.create` 创建，配置对它不生效，`ignoredConfiguration` 列出指向它的 `cocache.caches.[name]` 条目（[CoCacheFactory](../guide/caches.md#factory)）。

`policy` 按[策略配置项](../guide/configuration.md#policy-properties)的顺序列出适用于该缓存的每个字段的生效值与来源层（`source`，见[来源](../guide/configuration.md#policy-report)）。`detail` 出现时给出补充：被 `cocache.overrides` 收紧之前的值、定制器的类、约束改变了它的库，或校验连锁关闭提前刷新窗口的原因。classpath 上有 `spring-tx`（且应用没有声明 `FillObserver` bean）时，`policy` 末尾是 `invalidate-fills-on-rollback` 与 `fill-in-transaction`。`skippedConfiguration` 列出对这个缓存跳过的全局层条目，含 `layer` 与 `reason`：不适用于它的类型、该层不允许，或被库锁定。未分层的缓存两个列表都为空。`metrics` 的字段与指标一一对应：`localHits` 是 `cocache.hits{level=l2}`（只在开启 L2 命中计数时出现），`remoteHits` 是 `cocache.hits{level=l1}`，`loadRejected` 是 `cocache.load.rejections`，`fillRejected` 是 `cocache.fill.rejections`，`staleFillsDiscarded` 是 `cocache.stale.discards`，`refreshRejected` 是 `cocache.refresh.rejections`，`loadTimeouts` 是 `cocache.load.timeouts`，`staleVersions` 是 `cocache.versioned.stale`，`typeMismatches` 是 `cocache.type.mismatch`，`readsUnknown` 是 `cocache.reads.unknown`，`unversionedAbsentRefusals` 是 `cocache.fill.unversioned.absent`，`fillsGated` 是 `cocache.fills.gated`，`ttlClamped` 是 `cocache.ttl.clamped`，其余与指标名对应。`loads` 与 `loadNanos` 就是 timer `cocache.loads`。

### 编码后的 key {#encoded-key}

`{key}` 是**编码后的 key**：Redis key 中 `{keyPrefix}{namespace}:` 之后的部分，即 key 的 `toString()` 或缓存 `keyExpression` 的结果，所以端点适用于任意 key 类型。

| 缓存 key | 编码后的 key | 请求 |
|----------|--------------|------|
| `UserCache` 中的 `"42"` | `42` | `/actuator/cocache/UserCache/42` |
| data class `OrderId(tenant=7, id=9)` | `OrderId(tenant=7, id=9)` | `/actuator/cocache/OrderCache/OrderId(tenant=7,%20id=9)` |
| `keyExpression = "#{tenant}/#{id}"` | `7/9` | `/actuator/cocache/OrderCache/7/9` |

- 传编码后的 key，不传整个 Redis key：以缓存的 `{keyPrefix}{namespace}:` 开头的 key 在这里与 `CacheAdmin` 中都被拒绝，信息给出应传的编码 key。
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
  "remote": { "state": "VALUE", "value": "User(id=42, name=Ada)", "ttl": "PT58M", "format": 2, "error": null },
  "invalidationPending": false
}
```

| 字段 | 取值 |
|------|------|
| `storageKey` | 完整的 Redis key，供 `redis-cli` 使用 |
| `local` | 本实例的 L2 副本，没有时为 `null`。`state`：`VALUE`、`ABSENT`（负缓存，或权威缓存确认不存在的 key）或 `UNFILLED`（外部来源的 `Cache` 发现不存在的 key：不带 loader 的读取看到 `null`，带 loader 的读取照常加载）。`expired: true` 表示已过 `expiresAt`、但 Caffeine 尚未移除的副本，它永远不会被返回 |
| `remote.state` | `VALUE`、`ABSENT`（负缓存）、`SET`（`SetStore`）、`LEASED`（某个实例正在加载，`ttl` 是 lease 的剩余寿命）、`MISS`、`UNREADABLE`（外来内容、更新的格式、形状不符或编解码失败，原因见 `error`）或 `ERROR`（读取 Redis 失败） |
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
curl -fsS -X DELETE localhost:8080/actuator/cocache/UserCache/42
# {"cache":"UserCache","key":"42","deletion":"INVALIDATE"}
```

它在全集群删除这个 key：修改 L1，每个实例丢弃自己的 L2 副本。操作幂等。具体执行什么取决于缓存的[类型](../guide/caches.md#types)，报告的 `deletion` 写明了它：

| 类型 | 执行（`deletion`） | 需要 | Redis 失败时 |
|------|-------------------|------|--------------|
| `Cache` | `invalidate(key)`（`INVALIDATE`）：下一次读取从数据源加载 | — | 按 `invalidate-failure` 处理：默认进入重试队列并返回 `200`；为 `throw` 时返回错误 |
| `VersionedCache` | `evict(key)`（`EVICT`）：key 变为仍然拒绝旧版本的淘汰标记（[按版本写入](../guide/freshness.md#versioned-writes)） | — | 返回错误，不重试 |
| `Store` | `delete(key)`（`DELETE`）：**删除唯一副本** | `allow-store-delete` | 返回错误，不重试 |
| `VersionedStore` | `purge(key)`（`PURGE`）：**删除唯一副本及其版本** | `allow-purge` | 返回错误，不重试 |
| `SetStore` | `clear(key)`（`CLEAR`）：**删除集合的唯一副本** | `allow-store-delete` | 返回错误，不重试 |

删除唯一副本无法从数据源恢复，所以这几种删除需要开关，默认都关闭：`Store` 与 `SetStore` 需要 `management.endpoint.cocache.allow-store-delete=true`，`VersionedStore` 需要 `management.endpoint.cocache.allow-purge=true`。没有开启时请求返回 `403`，说明要开启的开关，不做任何修改。这些开关在 `management.endpoint.cocache.access` 之上：后者决定端点的写操作是否开放。

`VersionedCache` 或 `VersionedStore` 上的 `DELETE /actuator/cocache/{name}/{key}?purge=true` 执行 `purge(key)`：版本也一并丢掉，之后任何版本（包括迟到的旧版本）都能写入。它需要 `allow-purge`；其它类型返回 `400`。

### 只广播失效 {#broadcast-key}

```bash
curl -fsS -X POST localhost:8080/actuator/cocache/UserCache/42
# {"cache":"UserCache","key":"42"}
```

它只为这个 key 发布失效消息：所有实例（包括本实例）丢掉 L2 副本，下次访问时重新读 Redis。Redis 不变：不删除任何东西，也不移动版本，因此对任何类型都安全，除 `management.endpoint.cocache.access` 之外不需要开关。适用于 Redis 中已经是正确的值、而各实例可能仍在返回旧副本的场合，例如工具直接改写了 Redis 中的 key。它是 `ExternalInvalidator.broadcast` 的端点对应物（见[应用之外的写入方](../guide/freshness.md#external-writers)）。

与所有失效消息一样，投递至多一次。发布失败时请求失败，可以重试；消息在途中丢失时，其它实例的副本保留到 `local-max-age`。

## 日志 {#logs}

logger 都在 `me.ahoo.cocache` 下。值得告警或放进看板的日志：

| 级别 | 消息（开头） | 含义 |
|------|--------------|------|
| `ERROR` | `Cache[…] - invalidate […] dropped: L1 keeps the stale value until its remote TTL[…] expires.` | 一次失效被放弃，对应 `cocache.invalidations.dropped` |
| `WARN` | `Cache[…] - invalidate […] failed, retrying the invalidation.` | 失效写入 Redis 失败，已进入重试队列 |
| `WARN` | `Cache[…] - put […] failed, retrying the invalidation.`（或 `putAll`） | 结果未知的 `Cache.put` 以排队的失效[补偿](../guide/freshness.md#redis-down) |
| `WARN` | `Invalidation channel heartbeat failed - reset all subscribers and force reconnect.` | 订阅连接的 PING 失败，所有 L2 已清空 |
| `WARN` | `Invalidation channel - cannot force reconnect of the subscription connection, rely on TCP keepalive.` | CoCache 无法断开失败的订阅连接（Redis Cluster 上为缺少 CoCache 所调用内部方法的 Lettuce 版本），依赖 TCP keepalive |
| `WARN` | `Command connection[…] heartbeat failed (N in a row): …` | 一条命令连接没有在 `command-timeout` 内应答 PING。只有一次时多半是排在大载荷后面的队头阻塞 |
| `WARN` | `Command connection[…] failed N heartbeats in a row - force reconnect; commands in flight on it fail with an unknown outcome.` | CoCache 断开了半开的命令连接，由 Lettuce 重连。其上在途的写入以结果未知失败（[命令超时](./troubleshooting.md#command-timeouts)） |
| `WARN` | `Command connection[…] - cannot force reconnect, rely on TCP keepalive.` | CoCache 无法断开失败的命令连接，依赖 TCP keepalive |
| `WARN` | `Invalidation channel[…] unsubscribed by server (slot migration) - reset subscribers and resubscribe.` | `sharded` 模式下 slot 发生迁移。resharding 期间属正常 |
| `WARN` | `Invalidation channel[…] - subscribed on […] but slot is owned by […] - reset subscribers and resubscribe.` | `sharded` 模式下心跳发现了故障切换 |
| `WARN` | `Invalidation channel[…] - resubscribe failed, retry on next heartbeat.` | 重新订阅失败，下次心跳重试 |
| `WARN` | `Invalidation channel[…] - subscribe acknowledgement not received in …` | Redis 没有在 `command-timeout` 内确认订阅 |
| `WARN` | `Unreadable invalidation frame on channel[…] - reset subscribers.` | CoCache 的频道上出现了本版本读不懂的帧：有人在那里发布了非 CoCache 消息，或更新的 CoCache 发出了本版本不认识的帧类型。该频道上的每个缓存清空 L2，代价是重新加载。见[外部失效](./troubleshooting.md#external-invalidation) |
| `WARN` | `Cache[…] - key[…] - remote … failed, degrading.` | Redis 读取、填充、释放或回收失败，本次读取改为回源 |
| `WARN` | `Release token of key[…] failed - reclaimed after its lifetime.` | 一个 lease 或刷新令牌在寿命结束之前没能释放，由它自己到期回收。每 10 秒至多一条；计入 `cocache.redis.releases.abandoned` |
| `WARN` | `N more token releases failed in the last PT10S - reclaimed after their lifetime (M abandoned in total).` | 上一条之后同一个 10 秒内放弃的释放，汇总报告一次 |
| `WARN` | `Cache[…] - key[…] - remote … failed: the read has no answer (Unknown).` | Redis 读取失败，而这次读取没有可以退回的数据源，或缓存配置了 `read-failure: throw`。`get` 抛出 `CacheReadException`（`unknownRead = ABSENT` 时返回 `null`）。计入 `cocache.reads.unknown` |
| `WARN` | `SetStore[…] - key[…] - remote read failed: the read has no answer (Unknown).` | `SetStore` 上的同一情况 |
| `WARN` | `Cache[…] - key[…] - corrupted payload, reclaim and reload.` | 一个 L1 值无法解码 |
| `WARN` | `Cache[…] - key[…] - refresh failed.` / `refresh rejected.` | 提前刷新没有执行或失败，值正常到期 |
| `WARN` | `Atomic broadcast is not supported by this Redis deployment - publish after writes instead.` | 启动探测降级，见[通道模式](./index.md#channel-mode) |
| `WARN` | `@Cacheable on … uses CoCache cache … without sync = true …` | 某个 Spring Cache 方法不受 lease 保护，见 [Spring Cache](../guide/spring-cache.md#use-sync-true) |
| `WARN` | `CoCache uses the in-memory backend …` | 部署环境中出现 `cocache.backend=memory` 属于配置错误 |
| `WARN` | `… The cache was not created through the starter's CoCacheFactory (but directly through CacheRuntime.create or a library's own CoCacheFactory), so this can't stop it.` | 启动之后不经 starter 的 `CoCacheFactory` 创建的权威缓存有驱逐风险；这一行以[驱逐检查](../guide/configuration.md#eviction-check)的消息开头（见[启动失败](./troubleshooting.md#startup-failures)）。经 starter 的工厂创建时，致命的风险会让这次创建失败。 |
| `WARN` | `Authoritative caches […] …` 或 `Cannot read maxmemory-policy …`，出现在启动之后 | 经 starter 的 `CoCacheFactory` 创建的缓存有不致命的风险（`store-eviction-check: warn` 下的 `volatile-*` 与有限的 `ttl`），或周期重读（`cocache.redis.store-eviction-check-interval`）发现了新的风险，例如 `maxmemory-policy` 被改过 |
| `WARN` | `Store eviction re-check failed - retry in ….` | 一次周期重读失败（例如 Redis 不可达），下一次照常进行 |
| `WARN` | `Cache[…] - keyPrefix is empty: the keys and the channel of namespace[…] share no prefix, …` | 缓存的 key 前缀为空，ACL 与基于前缀的操作无法按前缀划定范围，必须逐个指明 namespace（见 [key 前缀](../guide/configuration.md#key-prefix)） |
| `INFO` | `Cache[…] - policy: ttl=PT10M (caches), local-max-age=PT2M (overrides, tightened from PT5M), ….` | 每个已分层的缓存创建时一行：值不是来自类型默认值的字段及其来源（见[生效值与来源](../guide/configuration.md#policy-report)） |
| `WARN` | `Cache[…] - cocache.defaults.store.ttl skipped: not allowed in cocache.defaults.` | 对这个缓存跳过的全局层条目：不适用于它的类型、该层不允许，或被库锁定。同样列在端点的 `skippedConfiguration` 中 |
| `WARN` | `Cache[…] - refresh-window=off (clamped, turned off: …).` / `Cache[…] - … (unlocked, was locked by …).` | 全局层引起冲突之后，校验连锁关闭了提前刷新窗口；或 `cocache.caches.[name].unlock` 覆盖了库的锁定 |
| `WARN` | `Cache[…] - per-call ttl[…] exceeds the ceiling[…] from cocache.overrides and was clamped …` | 第一次被 `cocache.overrides.cache.ttl` 钳制的按调用寿命；之后的计入 `cocache.ttl.clamped` |
| `WARN` | `Cache[…] - [cocache.caches.…] don't apply: the cache was created directly through CacheRuntime.create, not CoCacheFactory, …` | 按缓存的条目指向未分层的缓存，其中的设置都不生效（见 [CoCacheFactory](../guide/caches.md#factory)） |
| `WARN` | `cocache.caches configures undeclared caches […]; declared caches: […].` | 按缓存的条目对应不上任何缓存，且 `cocache.caches-validation: warn`（默认的 `fail` 让启动失败） |
| `INFO` | `Invalidation channel[…] subscribed - reset subscribers.` 与 `Cache[…] - reset: clear local copies.` | （重新）订阅清空了 L2。启动与重连后属于正常 |
