---
title: 部署
description: 在生产环境运行 CoCache：Redis 版本与拓扑、maxmemory-policy、TLS、通道模式、L2 与 Redis 的容量规划、滚动发布、多个应用共用 Redis 时的 key 布局，以及 CoCache 需要的 ACL 权限。
---

# 部署

本页讲 CoCache 上生产之前要定下来的事。上线之后看什么见[可观测性](./observability.md)，出问题时怎么办见[故障排查](./troubleshooting.md)。

## 运行时的组成 {#topology}

每个应用实例为每个缓存持有一份有界的 L2（Caffeine）。所有实例共用一个 Redis，它存放 L1，也承载失效消息。每个实例上，CoCache 会建立：

| 连接或线程 | 用途 |
|------------|------|
| 1 条命令连接 | 所有缓存操作，每次调用一个 Lua 脚本（`EVALSHA`），多路复用。Redis Cluster 中，Lettuce 会为路由到的每个主节点再建一条连接 |
| 1 条订阅连接 | 本实例所有缓存命名空间的失效消息。每隔 `heartbeat` PING 一次 |
| `cocache-redis-heartbeat` 线程 | 心跳与重新订阅 |
| `cocache-clock` 线程 | 每 100 ms 更新一次缓存的时钟 |
| `cocache-refresh-N` 线程 | 提前刷新，最多 4 个，只在有缓存使用 `refresh-window` 时工作 |
| `cocache-retry-1` 线程 | 在后台重试写入 Redis 失败的失效 |

CoCache 根据 `spring.data.redis.*` 自建 Lettuce 客户端，不共用应用的 Redis 连接工厂，所以它的客户端选项不会改变应用的行为。即使应用从副本读取，CoCache 的连接也只访问主节点。

## Redis 要求 {#redis}

| 项 | 要求 |
|----|------|
| 版本 | **Redis 7.0 及以上**。脚本用到 `TIME` 与 `SETRANGE`，`sharded` 模式用到 `SPUBLISH`。Valkey 7.2+ 实现了同样的命令，应当可用，但未纳入 CI 测试 |
| 客户端 | Lettuce，由 starter 引入。不支持 Jedis；引入 Lettuce 不影响应用自己基于 Jedis 的客户端 |
| 脚本 | 必须允许 `EVAL` / `EVALSHA`。脚本不带 shebang 标志 |
| Pub/Sub | `SUBSCRIBE` / `PUBLISH`，`sharded` 模式下为 `SSUBSCRIBE` / `SPUBLISH` |

### 拓扑 {#topologies}

starter 按以下顺序从 Spring Boot 的 Redis 连接信息中选择拓扑：

| `spring.data.redis.*` | CoCache 的连接方式 | 说明 |
|-----------------------|--------------------|------|
| `cluster.nodes` | Redis Cluster | 自适应拓扑刷新。脚本按 key 路由，不会出现 `CROSSSLOT`。原子性在单个 slot 内成立 |
| `sentinel.master` + `sentinel.nodes` | Sentinel | 跟随当前主节点。Sentinel 节点使用 `sentinel.username` / `sentinel.password`，数据节点使用 `username` / `password` |
| `host` / `port` / `url` | 单机 | key 遵循 `database`。Pub/Sub 不区分数据库，见 [key 布局](#key-layout) |
| `masterreplica.nodes` | **启动失败** | 不支持静态主从：读脚本会写入 lease 与刷新令牌，只能在主节点上执行。请使用 Sentinel 或 Cluster，或者自行声明 `CoherenceBackend` bean |

不使用 starter 时，用 `RedisBackend.standalone(uri, options)`（单机或 Sentinel URI）、`RedisBackend.cluster(uris, options)` 构建后端，或者用 `RedisBackend.of(client, options)` 传入自己管理的客户端。使用 `of` 时，请把客户端的 `disconnectedBehavior` 设为 `REJECT_COMMANDS`，否则 CoCache 会记录告警：Lettuce 默认在断线期间缓冲命令并在重连后重放，一个已经向调用方报错的 `put` 可能稍后执行，覆盖更新的值。

### 持久化与故障切换 {#failover}

对 `Cache` 来说，Redis 只是缓存：丢数据意味着未命中。异步复制在故障切换时可能丢失刚执行的失效，旧值可能回到 L1，最多持续到该值的 `ttl` 到期。如果某个缓存在意这一点，就把 `ttl` 设短，或者在故障切换之后失效相关缓存。

`SetCache` 不同：Redis 保存的是它的唯一副本，所以它的持久性就是 Redis 的持久性。存放 `SetCache` 数据的 Redis 必须开启持久化（AOF 或 RDB）。

## `maxmemory-policy` {#maxmemory-policy}

| 你的缓存 | 可用的策略 | 原因 |
|----------|------------|------|
| 只有 `Cache` | 任意。`allkeys-lru` 或 `allkeys-lfu` 让 Redis 成为有界缓存 | 值或负缓存被淘汰就是未命中。lease 被淘汰时，加载方的填充被拒绝，值只以 `degraded-local-max-age` 留在该实例的 L2 中。不会产生陈旧数据 |
| 有任何 `SetCache` | `noeviction` 或 `volatile-*` | Redis 保存的是集合的唯一副本，`allkeys-*` 会悄悄丢失成员。`SetCache` 的 key 默认没有到期时间，`volatile-*` 不会淘汰它们。启动时 CoCache 读取各主节点的策略，遇到 `allkeys-*` 时告警（`cocache.redis.set-cache-eviction-check`：`warn`、`fail` 或 `off`）；`CONFIG` 不可用时跳过检查。设置了有限 `ttl` 的 `SetCache` 在 `volatile-*` 下仍可能被淘汰 |

`noeviction` 下 Redis 达到 `maxmemory` 时：

- 失效照常工作。删除不需要内存，脚本也不带会让 Redis 拒绝执行的标志。
- 读取不再取得 lease。每次未命中都直接回源，不写 L1，结果以 `degraded-local-max-age` 留在 L2 中。`cocache.degraded.fills` 上升，跨实例合并回源失效。
- 需要分配内存的写入（例如向已有集合加入成员）可能因 OOM 失败，调用方收到 `CacheWriteException`。
- 写入不会只生效一半。只有脚本尚未写入任何东西时，Redis 才会在它第一条需要分配内存的命令处拒绝执行，因此被拒绝的写入不改变任何状态。第一条写入是删除的脚本（`put` 就是这样替换旧值的）会执行到底，即使因此超过 `maxmemory`。

## TLS {#tls}

TLS 用 Spring Boot 的标准配置项开启。CoCache 对它连接的每个节点都启用 TLS，包括 Sentinel 与 Cluster 节点。

| 配置 | 效果 |
|------|------|
| `spring.data.redis.ssl.enabled: true` | 使用 JDK 默认信任库的 TLS |
| `spring.data.redis.ssl.bundle: my-bundle` | 使用 Spring Boot SSL bundle（`spring.ssl.bundle.*`）的 TLS：密钥库、信任库、启用的协议与加密套件 |
| `spring.data.redis.url: rediss://…` | 即使没有设置 `ssl.enabled` 也启用 TLS，使用默认信任库 |

不使用 starter 时，在 `RedisBackendOptions(sslOptions = …)` 中传入 Lettuce 的 `SslOptions`，并在 `RedisURI` 上启用 SSL。

## 通道模式 {#channel-mode}

`cocache.redis.channel-mode` 决定失效消息的传输方式。**共享同一个缓存的所有实例必须使用同一种模式**，两种模式互相收不到对方的消息。

| | `pubsub`（默认） | `sharded` |
|-|------------------|-----------|
| 命令 | `SUBSCRIBE` / `PUBLISH` | `SSUBSCRIBE` / `SPUBLISH` |
| 广播 | 在写脚本内完成，与 L1 修改原子发生 | 写脚本成功之后立即发布 |
| Redis Cluster | 每条消息经集群总线发往所有节点 | 消息只在频道 slot 所属的分片内传播 |
| 适用 | 单机、Sentinel、中小规模集群 | 集群总线上的 Pub/Sub 扇出成本过高的大集群 |

`pubsub` 模式下，后端在启动时检查脚本能否向另一个 slot 的频道发布。部分托管服务（如 ElastiCache Serverless）把 `PUBLISH` 当作 `SPUBLISH` 执行，会拒绝这种发布；禁止探测频道的 ACL 效果相同。此时后端改为在每次写入之后单独发布，并记录 `Atomic broadcast is not supported by this Redis deployment - publish after writes instead.`，[健康检查](./observability.md#health)显示 `atomicBroadcast: false`。只有这些确定性的错误才会触发降级；探测超时会让启动失败，而不是永久降级。

没有原子广播时，进程在写入与发布之间崩溃会丢失这条消息。此时 L1 已经正确，其它实例的 L2 副本最多保留到 `local-max-age`。

## 容量规划 {#sizing}

### L2（堆内存） {#sizing-l2}

每个缓存在每个实例上都有自己的 L2，上限为 `local-maximum-size`（默认 10,000 条；接口式声明用 `@CaffeineCache(maximumSize)`）。堆内存大约是：条目数 ×（解码后的值 + key 字符串 + 约 100 字节的条目开销），再对所有缓存求和。关注 `cocache.local.size`。按热点数据集规划 L2，而不是整个 key 空间：L2 未命中只多一次 Redis 往返，不会回源。

没有 L2 命中计数器（它会拖慢最热的路径）。评估 L2 时，用应用的请求速率对比 `cocache.misses`。

### Redis 内存 {#sizing-redis}

| 条目 | 存储形式 | 大小 |
|------|----------|------|
| 值 | String | key + **36 字节头** + 编解码器输出 |
| 负缓存 | String | key + 36 字节头，存活 `absent-ttl` |
| lease | String | key + 36 字节头，存活到加载结束（最多 `lease-ttl`） |
| 集合（`SetCache`） | Hash | key + 一个 `!f` 字段 + 每个成员一个 `+{member}` 字段 |

另加 Redis 自身的每 key 开销。用 `redis-cli MEMORY USAGE cocache:UserCache:42` 实测一个典型 key。除非缓存使用 `ttl-forever` 或是 `SetCache`，每个值都有到期时间（`ttl`，加减 `ttl-jitter`）。

### Redis 流量 {#sizing-traffic}

- **读取**。L1 命中是一次 `EVALSHA`（`PTTL` + `GET`）。未命中还会写入 lease，提前刷新会写入令牌，因此读取也会产生一些复制与 AOF 流量。
- **写入**。`put` 与 `invalidate` 各一个脚本，各发布一条消息。`invalidateAll` 每 `invalidate-batch-size` 个 key（默认 512）一个脚本，Redis Cluster 中还按 slot 拆分。
- **Pub/Sub**。订阅了某个命名空间的每个实例都会收到它的每条消息。一条消息约 18 字节加上每个存储 key。Redis Cluster 的 `pubsub` 模式下，每个节点都会转发每条消息。
- **队头阻塞**。一个实例的所有命令共用一条连接，很大的值会拖慢排在它后面的所有命令。保持值小，或者把大对象放到别处。

## 滚动发布 {#rolling-deploys}

线格式（key 布局、值头、集合字段、消息帧、频道命名）在一个大版本内冻结，所以任意 6.x 实例可以混跑。将来的格式变更分两阶段发布：先发布能读新格式、仍写旧格式的版本，全部实例升级之后，再发布写新格式的版本。实例版本不一致期间，遇到不认识的格式时直接回源且不覆盖（`cocache.unknown.formats`）。

每个新实例启动时 L2 为空，从 L1 填充，所以发布期间会看到 `cocache.misses` 与 `cocache.hits{level=l1}` 的突增，而不是回源的突增。

有些配置变更不能逐个实例滚动：

| 变更 | 能否滚动 | 新旧实例并存期间会发生什么 |
|------|----------|----------------------------|
| 策略字段（`ttl`、`local-max-age`、`lease-*`、`load-*`、失败模式等） | 能 | 各实例按自己的策略运行 |
| `channel-mode` | L1 能，L2 不能 | 模式不同的实例互相收不到消息。L1 仍然正确，但其它实例的 L2 副本最多陈旧 `local-max-age`。切换期间调低 `local-max-age`，或者所有实例同时切换 |
| `key-prefix`、`namespace`，或命名空间默认等于缓存名时的缓存名 | **不能** | 新旧实例使用不同的 key，互相忽略对方的失效，一侧的写入会让另一侧陈旧到 `ttl`。所有实例同时切换。命名空间变化也会改变频道 |
| 编解码器的字节格式 | 可以，但代价高 | 双方都把对方的值当作损坏，回收后回源（`cocache.reclaims`）。优先使用新的命名空间，或者让 `decode` 同时接受两种格式 |

旧前缀或旧命名空间下的 key 会随 `ttl` 到期。想提前删除时，用 `redis-cli --scan --pattern 'old-prefix:*' | xargs redis-cli unlink`（集群中逐个主节点执行）。

## key 布局与共用 Redis {#key-layout}

| 内容 | 格式 | 示例 |
|------|------|------|
| 存储 key | `{keyPrefix}{namespace}:{encodedKey}` | `cocache:UserCache:42` |
| 频道 | `cocache:inv:{namespace}` | `cocache:inv:UserCache` |

`keyPrefix` 默认 `cocache:`，`namespace` 默认等于缓存名，`encodedKey` 是 key 的 `toString()` 或 `keyExpression` 的结果。见[存储 key](../guide/caches.md#storage-keys)。

多个应用可以共用一个 Redis：

- **给每个应用设置自己的 key 前缀**，例如 `cocache.defaults.key-prefix: ${spring.application.name}:`。否则两个应用中同名的缓存会读取并覆盖彼此的值。有意在应用之间共享的缓存，各处必须使用相同的前缀、命名空间、编解码器与通道模式。
- **频道不带前缀**，而且 Redis Pub/Sub 不区分数据库编号。命名空间相同的应用会收到彼此的消息。每个实例只淘汰与自己存储 key 匹配的副本，所以代价只是一些额外的处理，体现在 `cocache.invalidations.received` 中。如果两个应用使用不同的数据库，但前缀与命名空间都相同，存储 key 会完全一致，对方的消息会无谓地淘汰本方的 L2 副本。请使用不同的前缀或命名空间。

## 安全 {#security}

### Redis ACL {#acl}

CoCache 的 Redis 用户需要以下权限：

| 方面 | 需要 |
|------|------|
| key | 对你的 key 前缀可读写（默认 `~cocache:*`）。`pubsub` 模式下还需要 `~cocache:probe:*` |
| 频道 | `&cocache:inv:*`。`pubsub` 模式下还需要 `&cocache:probe:*`，否则后端退回非原子广播 |
| 直接调用的命令 | `EVALSHA`、`EVAL`（`NOSCRIPT` 后的回退）、`PING`（心跳）、`PUBLISH`、`SUBSCRIBE`、`UNSUBSCRIBE`，`sharded` 模式下为 `SPUBLISH`、`SSUBSCRIBE`、`SUNSUBSCRIBE`，以及连接命令（`HELLO`、`AUTH`、`CLIENT`、`SELECT`） |
| 脚本内的命令 | Redis 7 同样按调用方的 ACL 检查：`GET`、`SET`、`DEL`、`PTTL`、`TYPE`、`HGETALL`、`HSET`、`HDEL`、`HEXISTS`、`HLEN`、`PEXPIRE`、`PERSIST`、`SETRANGE`、`TIME` |

一个起点（已在 Redis 7.4 上以该用户执行全部 CoCache 脚本验证）：

```
ACL SETUSER cocache on >change-me ~cocache:* &cocache:inv:* &cocache:probe:* +@connection +evalsha +eval +publish +subscribe +unsubscribe +spublish +ssubscribe +sunsubscribe +get +set +del +pttl +type +hgetall +hset +hdel +hexists +hlen +pexpire +persist +setrange +time
```

使用自定义 `key-prefix` 时，把 `~cocache:*` 换成你的前缀。Redis Cluster 还要允许 Lettuce 发现拓扑所用的命令（`+cluster|nodes`、`+info`）。每个节点都要设置该用户：ACL 不会随复制同步。

常见错误是禁止了 `PING`。此时每个心跳周期都会失败，每次失败都清空所有 L2，健康检查一直是 `DOWN`。

### Actuator 端点 {#endpoint-security}

`GET /actuator/cocache/{name}/{key}` 会展示缓存的值，`DELETE` 会在全集群失效它们。只在管理端口上或认证之后暴露该端点，与其它能读取业务数据的 Actuator 端点一样。只允许查看、不允许删除时，设置 `management.endpoint.cocache.access: read-only`。见[端点](./observability.md#actuator-endpoint)。
