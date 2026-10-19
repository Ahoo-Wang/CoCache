---
title: 配置参考
description: 所有 cocache.* 配置项的类型、默认值与单位，配置项如何叠加在声明与定制器之上，Redis 连接配置，actuator 与健康检查开关，启动校验，以及只能用代码配置的部分。
---

# 配置参考

## 配置如何叠加 {#precedence}

每个缓存的配置分层构建。后一层按字段覆盖前一层；某层没有设置的字段原样传下去：

1. **内置默认值**（`CachePolicy.DEFAULT`；集合缓存为 `SetCacheSpec.DEFAULT_POLICY`）。
2. **声明**：`@CoCache` / `@CaffeineCache`，或你构建的 `CacheSpec`。
3. **`CacheSpecCustomizer` bean**，按 Spring 的顺序。
4. **`cocache.defaults.*`**，作用于所有缓存。
5. **`cocache.caches.[name].*`**，作用于单个缓存。

配置项排在最后，运维无需重新构建就能调整缓存。这也意味着 `cocache.defaults.*` 会覆盖注解中写的值。

::: tip 集合缓存保持自己的寿命
`cocache.defaults.ttl` 与 `ttl-forever` 不作用于 [`SetCache`](./caches.md#set-cache)：它的唯一副本在 Redis 中，全局寿命会让集合过期并消失。`cocache.defaults.absent-ttl` 与 `refresh-window` 同样跳过集合缓存，因为它没有数据源。只有 `cocache.caches.[name].ttl` / `ttl-forever` 能改变集合缓存的寿命，`defaults` 的其它配置项照常生效。
:::

## 顶层配置项 {#properties}

| 配置项 | 类型 | 默认值 | 含义 |
|--------|------|--------|------|
| `cocache.enabled` | boolean | `true` | `false` 关闭 CoCache 的全部自动配置，包括端点、健康检查与指标 |
| `cocache.backend` | `redis` \| `memory` | `redis` | `redis`：由 `spring.data.redis.*` 构建。`memory`：进程内、单实例，用于开发与测试。自己声明的 `CoherenceBackend` bean 优先于两者。 |
| `cocache.defaults.*` | 策略 | — | 作用于所有缓存的[策略配置项](#policy-properties) |
| `cocache.caches.[name].*` | 策略 | — | 作用于单个缓存的[策略配置项](#policy-properties) |
| `cocache.redis.*` | — | — | [Redis 后端](#redis)配置 |
| `cocache.spring-cache.require-sync` | boolean | `false` | `true`：CoCache 缓存上的 `@Cacheable` 缺少 `sync = true` 时启动失败。`false`：只记录告警。见 [Spring Cache](./spring-cache.md#sync-check)。 |
| `cocache.spring-cache.retrieve-executor` | bean 名 | — | **6.3 起弃用**，设置时启动告警并被忽略：`retrieve` 经[异步视图](./async.md)，不需要执行器。7.0 删除。 |
| `cocache.refresh-executor` | bean 名 | 内置 | 执行[提前刷新](./freshness.md#refresh-ahead)回源的 `Executor` bean。见[执行器](#executors)。 |
| `cocache.async-executor` | bean 名 | 内置 | 执行[异步视图](./async.md)续接的 `Executor` bean。见[执行器](#executors)。 |
| `cocache.blocking-executor` | bean 名 | 内置 | 执行经异步视图调用的同步 loader 的 `Executor` bean。见[执行器](#executors)。 |
| `management.health.cocache.enabled` | boolean | `true` | 注册 `cocache` 健康检查（见[下文](#actuator)） |
| `cocache.observation.enabled` | boolean | `true` | 有 `ObservationRegistry` bean 时把回源包在 Micrometer `Observation` 中。见[链路追踪](../operations/observability.md#tracing)。 |
| `cocache.metrics.local-hits` | boolean | `false` | 为全部缓存记录 L2 命中数（`cocache.hits{level=l2}`，端点字段 `localHits`）。默认关闭：它给每次 L2 命中加一次原子自增。见[指标](../operations/observability.md#metrics-reads)。 |

## 策略配置项 {#policy-properties}

以下配置项在 `cocache.defaults.` 与 `cocache.caches.[name].` 下都可以使用。

| 配置项 | 类型 | 内置默认值 | 对应注解 | 含义 |
|--------|------|------------|----------|------|
| `ttl` | 时长 | `1h`（集合缓存：永不过期） | `@CoCache.ttl` | 值在 Redis 中的寿命 |
| `ttl-forever` | boolean | `false` | `ttl = CoCache.FOREVER` | `true`：值永不过期，覆盖 `ttl` |
| `ttl-jitter` | 时长 | `1m` | `@CoCache.ttlJitter` | 每个值寿命上的随机 ± 抖动，不超过 `ttl / 2`。`0`：关闭。 |
| `absent-ttl` | 时长 | `1m` | `@CoCache.absentTtl` | 负缓存的寿命 |
| `local-max-age` | 时长 | `5m` | `@CoCache.localMaxAge` | L2 副本的最长寿命 |
| `degraded-local-max-age` | 时长 | `10s` | — | 无法写入 Redis 的 L2 副本的最长寿命 |
| `refresh-window` | 时长 | 关闭 | `@CoCache.refreshWindow` | 提前刷新窗口。`0`：关闭（也会关掉声明中开启的窗口），见[校验](#validation)。 |
| `lease-ttl` | 时长 | `10s` | — | 持有者一直不写回时 lease 的寿命，应大于 loader 的 p99 |
| `lease-wait` | 时长 | `200ms` | — | 未命中时等待其它实例加载的最长时间，超时后自行加载 |
| `load-max-concurrent` | integer | `0`（不限制） | — | 每个实例上每个缓存同时进行的数据源加载数上限 |
| `load-acquire-timeout` | 时长 | `1s` | — | 加载等待许可的最长时间，超时抛出 `SourceLoadRejectedException` |
| `load-async-timeout` | 时长 | `30s` | — | 一次 `AsyncCacheLoader` 加载的最长时间。超时后调用以 `SourceLoadTimeoutException` 失败并释放 lease。不作用于同步 loader。见[异步 API](./async.md#timeouts)。 |
| `local-maximum-size` | long | `10000` | `@CaffeineCache.maximumSize` | 每个实例 L2 的最大条目数 |
| `invalidate-failure` | `retry` \| `throw` | `retry` | — | Redis 失效失败时：进入后台重试队列，或抛出 `CacheWriteException` |
| `read-failure` | `degrade` \| `throw` | `degrade` | — | Redis 读取失败时：从数据源加载，或抛出 `CacheReadException` |
| `key-prefix` | string | `cocache:` | `@CoCache.keyPrefix` | 每个存储 key 的开头（见[下文](#key-prefix)） |

如何选择这些值，见[保持数据新鲜](./freshness.md#lifetimes)。

### 时长 {#durations}

`defaults` 与 `caches` 下不写单位的数字按**秒**解析，与注解一致：`ttl: 600`。也可以写 Spring 的时长字符串：`500ms`、`10m`、`PT10M`。亚秒级的值请写单位，如 `lease-wait: 500ms`。

`cocache.redis` 下不写单位的数字按**毫秒**解析，与 `spring.data.redis.timeout` 一致。那里同样建议写单位。

### 单个缓存 {#per-cache}

```yaml
cocache:
  defaults:
    local-max-age: 2m
  caches:
    "[UserCache]":
      ttl: 10m
      load-max-concurrent: 32
      read-failure: throw
```

- 缓存名不区分大小写匹配，所以环境变量形式也可以：`COCACHE_CACHES_USERCACHE_TTL=10m`。
- 在 YAML 中给缓存名加上引号与方括号（`"[UserCache]"`），保持原样。
- 对应不上任何已声明缓存的名字会让启动失败。

### key 前缀 {#key-prefix}

存储 key 是 `{keyPrefix}{namespace}:{encodedKey}`（见[声明缓存](./caches.md#storage-keys)）。统一设置前缀，可以隔离共用一个 Redis 的多个应用：

```yaml
cocache:
  defaults:
    key-prefix: ${spring.application.name}:
  caches:
    "[SharedIndexCache]":
      key-prefix: "shared:"
```

`caches.[name].key-prefix` 覆盖 `defaults.key-prefix`，后者又覆盖声明与定制器，包括 `@CoCache` 中显式写出的 `keyPrefix`。同一应用的所有实例必须使用相同的前缀，修改前缀相当于冷启动。失效频道 `cocache:inv:{namespace}` 不含前缀，所以在同一个 Redis 上 namespace 相同的应用会收到彼此的消息；这只是多余的处理，不会产生错误数据。

## 执行器 {#executors}

CoCache 在执行器上运行后台工作与异步工作：

| 工作 | 配置项 | 内置执行器 |
|------|--------|------------|
| 提前刷新回源 | `cocache.refresh-executor` | 有界守护线程池（1–4 个线程，队列 1024）。队列满时跳过这次刷新，计入 `cocache.refresh.rejections`。 |
| [异步视图](./async.md)的续接：解码、填充 L2、发出下一条 Redis 命令、调用 `loadAsync`、完成你拿到的 future | `cocache.async-executor` | 运行时自建的 `ForkJoinPool`，并行度为 CPU 数，守护线程名为 `cocache-async-*` |
| 经异步视图调用的同步 loader，以及只实现同步 SPI 的后端 | `cocache.blocking-executor` | JDK 21+ 上每个任务一个虚拟线程。JDK 17–20 上是不排队的守护线程池（最多 512 个线程，名为 `cocache-blocking-*`）；满时拒绝这次加载，而不是在异步执行器上执行。 |

每个配置项的值都是 `Executor` bean 的**名称**。CoCache 只按名称查找，从不按类型查找。名称没有对应的 bean，或该 bean 不是 `Executor` 时，启动失败，错误信息指明配置项。该执行器归容器管理：关闭 `CacheRuntime` 不会关闭它。

Spring Boot 自带的 `applicationTaskExecutor` 可以用于提前刷新，也可以作为阻塞执行器：

```yaml
spring:
  threads:
    virtual:
      enabled: true          # applicationTaskExecutor 使用虚拟线程
cocache:
  refresh-executor: applicationTaskExecutor
  blocking-executor: applicationTaskExecutor
```

::: warning 各执行器能运行什么
异步执行器只运行短小的非阻塞工作，不要在上面阻塞，例如在续接里调用同步 API。内置的 `ForkJoinPool` 会为在 `join()` 上阻塞的线程补偿线程，你提供的固定大小线程池不会。阻塞执行器运行同步 loader，因此不能排队：一个 loader 可能等待另一次加载。
:::

`cocache.spring-cache.retrieve-executor` 自 6.3 起弃用：Spring Cache `retrieve` 现在经异步视图，不在任何线程上等待。见 [Spring Cache](./spring-cache.md#executor)。

## Redis {#redis}

Redis 后端由 Spring Boot 的 `spring.data.redis.*` 连接信息构建，使用 CoCache 自己的 Lettuce 客户端。

| 拓扑 | 是否支持 |
|------|----------|
| 单机 | 支持 |
| Sentinel | 支持。Sentinel 节点使用 `spring.data.redis.sentinel.username` / `password`；数据节点使用 `spring.data.redis.username` / `password`。 |
| Cluster | 支持。脚本按 key 路由，拓扑自适应刷新。 |
| 静态主从 | 不支持，启动失败。 |

TLS 遵循 Spring Boot 的 SSL bundle（`spring.data.redis.ssl.bundle`）。`rediss://` URL 以 JDK 默认配置启用 TLS。CoCache 总是访问主节点，因为它的读脚本也会写入 lease。

| 配置项 | 类型 | 默认值 | 含义 |
|--------|------|--------|------|
| `cocache.redis.channel-mode` | `pubsub` \| `sharded` | `pubsub` | `pubsub`：`PUBLISH` / `SUBSCRIBE`，与 Redis 修改原子地完成。`sharded`：`SPUBLISH` / `SSUBSCRIBE`（Redis 7+），在修改之后立即发送。**所有实例必须使用相同的模式**，两种模式互不相通。 |
| `cocache.redis.set-cache-eviction-check` | `warn` \| `fail` \| `off` | `warn` | 有 `SetCache` 时，启动读取各主节点的 `maxmemory-policy`：`allkeys-*` 会淘汰集合的唯一副本。`warn` 记录告警，`fail` 启动失败，`off` 不检查。`CONFIG` 不可用（托管 Redis、ACL）时跳过。见 [maxmemory-policy](../operations/index.md#maxmemory-policy)。 |
| `cocache.redis.command-timeout` | 时长（毫秒） | `2s` | 单条命令或脚本的最长等待时间 |
| `cocache.redis.heartbeat` | 时长（毫秒） | `5s` | 订阅连接上的 `PING` 间隔。PING 失败时清空 L2 并重连。 |
| `cocache.redis.invalidate-batch-size` | integer | `512` | 单个失效脚本处理的 key 数上限 |
| `cocache.redis.client-name` | string | 未设置 | CoCache 连接的 `CLIENT SETNAME` |

部署与容量方面的选择见[运维](../operations/)。

## Actuator 与健康检查 {#actuator}

classpath 中有 `spring-boot-starter-actuator` 时：

| 功能 | 开关 | 说明 |
|------|------|------|
| `cocache` 端点 | `management.endpoints.web.exposure.include=cocache`；访问控制用 `management.endpoint.cocache.access` | 列出缓存与计数，查看与失效单个 key |
| `cocache` 健康检查 | `management.health.cocache.enabled`（默认 `true`） | 只有 Redis 失效通道心跳失败期间为 `DOWN` |
| Micrometer 指标 `cocache.*` | classpath 中有 Micrometer | 以 `cache` 标签区分 |

端点路径与指标名见[可观测性](../operations/observability.md)。

## 启动校验 {#validation}

CoCache 不会带着无法兑现的配置运行，而是让启动失败：

- **未知的缓存名**出现在 `cocache.caches` 下（几乎总是拼写错误）。错误信息会列出已声明的缓存。
- **不再支持的旧大版本配置名**，如 `cocache.redis.strict-failure` 或 `cocache.load.*` 下的任何项，会让启动失败，错误信息给出替代项（如果有的话）。完整列表见[从 5.x 升级](./migration-6.md#properties)。
- **不合法的策略。** 所有时长必须为正，且不超过 100 年；只有 `ttl-jitter`、`lease-wait`、`load-acquire-timeout` 与 `refresh-window`（0 表示关闭）可以为 0；任何时长都不能为负。`refresh-window` 必须小于 `ttl − ttl-jitter`（按实际抖动计算），也必须小于 `local-max-age`。

`refresh-window: 0` 表示关闭提前刷新，因此配置项也可以关掉声明中开启的窗口。

声明层面的校验见[声明缓存](./caches.md#validation)。

## 配置项之外 {#beyond-properties}

有些配置只存在于代码中，因为它们是函数或对象，而不是值：

| 配置 | 位置 |
|------|------|
| `ttlOf`、`keyEncoder`、`keyFilter`、`codec`、`loader` | spec 或[定制器](./caches.md#customizer) |
| `local.initialCapacity`、`local.expireAfterAccess` | `@CaffeineCache`、spec 或定制器 |
| 时钟 | `CacheClock` bean（如测试中的 `ManualClock`）会被运行时与内存后端使用 |
| 重试策略、节点 id | 自己声明的 `CacheRuntime` bean |
| Redis 客户端 | 自己声明的 `CoherenceBackend` bean，如 `RedisBackend.of(client, options)` |

自己声明 `CacheRuntime` 或 `CoherenceBackend` bean 时，自动配置的那个会退让：

```kotlin
@Bean(destroyMethod = "close")
fun cocacheRuntime(backend: CoherenceBackend): CacheRuntime =
    CacheRuntime.builder()
        .backend(backend)
        .retryPolicy(RetryPolicy(maxAttempts = 16, capacity = 50_000))
        .build()
```

| `RetryPolicy` 字段 | 默认值 |
|--------------------|--------|
| `maxAttempts` | `8` |
| `initialBackoff` | `100ms`（每次翻倍） |
| `maxBackoff` | `10s` |
| `capacity` | 最多 `10000` 个排队的失效 |
