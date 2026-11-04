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
| 版本 | **Redis 7.0 及以上**。脚本用到 `TIME` 与 `SETRANGE`，`sharded` 模式用到 `SPUBLISH`。Valkey 7.2+ 实现了同样的命令。CI 在单机 Valkey 8 上运行 Redis 后端与 starter 测试 |
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

不使用 starter 时，用 `RedisBackend.standalone(uri, options)`（单机或 Sentinel URI）、`RedisBackend.cluster(uris, options)` 构建后端，或者用 `RedisBackend.of(client, options)` 传入自己管理的客户端。使用 `of` 时，请把客户端的 `disconnectedBehavior` 设为 `REJECT_COMMANDS`，否则 CoCache 会记录告警：Lettuce 默认在断线期间缓冲命令，Redis 不可用时每条命令都要等满 `command-timeout` 才失败，而不是立即失败。缓冲中超时的写入会被丢弃，不会在重连后重放。

### 持久化与故障切换 {#failover}

对 `Cache` 与 `VersionedCache` 来说，Redis 只是缓存：丢数据意味着未命中。异步复制在故障切换时可能丢失刚执行的失效，旧值可能回到 L1，最多持续到该值的 `ttl` 到期。如果某个缓存在意这一点，就把 `ttl` 设短，或者在故障切换之后失效相关缓存。

[`Store`、`VersionedStore` 与 `SetStore`](../guide/store.md) 不同：Redis 保存的是它们的唯一副本，所以它们的持久性就是 Redis 的持久性。存放它们的数据的 Redis 必须开启持久化（AOF 或 RDB）。

## `maxmemory-policy` {#maxmemory-policy}

| 你的缓存 | 可用的策略 | 原因 |
|----------|------------|------|
| 只有 `Cache` 与 `VersionedCache` | 任意。`allkeys-lru` 或 `allkeys-lfu` 让 Redis 成为有界缓存 | 值或负缓存被淘汰就是未命中。lease 被淘汰时，加载方的填充被拒绝，值只以 `degraded-local-max-age` 留在该实例的 L2 中。不会产生陈旧数据 |
| 有任何 `Store`、`VersionedStore` 或 `SetStore` | `noeviction` 或 `volatile-*` | Redis 保存的是它们的唯一副本，`allkeys-*` 会悄悄丢失数据。它们的值默认没有到期时间，`volatile-*` 不会淘汰它们。设置了有限 `ttl` 的在 `volatile-*` 下仍可能被淘汰；`VersionedStore` 的墓碑总在 `tombstoneTtl` 之后到期，同样可能被淘汰：墓碑消失之后，旧版本又会被接受 |

只要声明了 `Store`、`VersionedStore` 或 `SetStore`（不论已创建还是仍在延迟初始化），CoCache 启动时就从 `INFO memory` 读取各主节点的策略，不需要托管服务常禁用的 `CONFIG`。任一主节点为 `allkeys-*` 时启动失败，无论 `cocache.redis.store-eviction-check` 是什么。Redis 用户不能执行 `INFO` 时按 `cocache.redis.assume-maxmemory-policy` 判断；没有这项配置时同样启动失败，而不是静默跳过检查。`volatile-*` 下有 `ttl` 有限的权威缓存，是 `store-eviction-check` 唯一决定的情形：`warn`（默认）记录告警，`fail` 启动失败，`off` 接受。见[驱逐检查](../guide/configuration.md#eviction-check)。

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

::: warning 没有原子广播时
`sharded` 模式下，或探测降级之后，发布是写脚本之后单独执行的一条命令。因此：

- 进程在写入与发布之间崩溃，会丢失这条消息。
- 写入成功、随后的发布失败时，调用方看到的是写入失败：`put` 抛出 `CacheWriteException`，尽管 Redis 中已经是新值；`Cache.invalidate` 按 `invalidate-failure` 处理，重试的失效会再次发布；权威类型的删除抛出。重试 `put` 是安全的，并会再次发布。

两种情况下 L1 都已经正确，错过这条消息的实例的 L2 副本最多保留到 `local-max-age`。写入的顺序见[一致性](../architecture/consistency.md#writer-order)。
:::

## 容量规划 {#sizing}

### L2（堆内存） {#sizing-l2}

每个缓存在每个实例上都有自己的 L2，上限为 `local-maximum-size`（默认 10,000 条；接口式声明用 `@CaffeineCache(maximumSize)`）。堆内存大约是：条目数 ×（解码后的值 + key 字符串 + 约 100 字节的条目开销），再对所有缓存求和。关注 `cocache.local.size`。按热点数据集规划 L2，而不是整个 key 空间：L2 未命中只多一次 Redis 往返，不会回源。

L2 命中计数 `cocache.hits{level=l2}` 默认关闭，因为它会拖慢最热的路径。评估 L2 时，用应用的请求速率对比 `cocache.misses`，或者用 [`cocache.metrics.local-hits`](../guide/configuration.md#properties) 开启该计数。

### Redis 内存 {#sizing-redis}

| 条目 | 存储形式 | 大小 |
|------|----------|------|
| 值 | String | key + **36 字节头** + 编解码器输出。`Store` 的值另加 28 字节的写入标识与 32 字节的摘要（60 字节），`VersionedStore` 的值另加写入标识与 8 字节的版本（36 字节），`VersionedCache` 的值另加 8 字节的版本（见[线格式](../architecture/index.md#wire-format)） |
| 负缓存 | String | key + 36 字节头，存活 `absent-ttl`。带版本的墓碑另加 8 字节的版本 |
| 被淘汰的带版本 key | String | key + 44 字节（头部与版本），存活墓碑的寿命 |
| lease | String | key + 36 字节头，存活到加载结束（最多 `lease-ttl`） |
| 集合（`SetStore`） | Hash | key + 一个 `!f` 字段 + 每个成员一个 `+{member}` 字段 |

另加 Redis 自身的每 key 开销。用 `redis-cli MEMORY USAGE cocache:UserCache:42` 实测一个典型 key。除非缓存使用 `ttl: forever`（`Store`、`VersionedStore` 与 `SetStore` 默认如此），每个值都有到期时间（`ttl`，加减 `ttl-jitter`）。

### Redis 流量 {#sizing-traffic}

- **读取**。L1 命中是一次 `EVALSHA`（`PTTL` + `GET`）。未命中还会写入 lease，提前刷新会写入令牌，因此读取也会产生一些复制与 AOF 流量。
- **写入**。`put` 与 `invalidate` 各一个脚本，各发布一条消息。`invalidateAll` 每 `invalidate-batch-size` 个 key（默认 512）一个脚本，Redis Cluster 中还按 slot 拆分。
- **Pub/Sub**。订阅了某个命名空间的每个实例都会收到它的每条消息。一条消息约 18 字节加上每个存储 key。Redis Cluster 的 `pubsub` 模式下，每个节点都会转发每条消息。
- **队头阻塞**。一个实例的所有命令共用一条连接，很大的值会拖慢排在它后面的所有命令。保持值小，或者把大对象放到别处。

## 滚动发布 {#rolling-deploys}

线格式（key 布局、值头与其中的段、集合字段、消息帧、频道命名）在一个大版本内冻结，所以同一大版本的实例可以混跑。跨大版本的升级不能滚动：先停掉旧大版本的全部实例，再启动新版本（见[从 6.x 升级](../guide/upgrade.md)）。遇到本版本读不懂的格式的值，有数据源的读取重新加载，没有数据源的读取失败。之后必须理解的变更分两阶段发布：先发布能读懂、但不写入它的版本，全部实例升级之后，再发布写入它的版本。实例版本不一致期间，遇到读不全的值时直接回源且不覆盖（`cocache.unknown.formats`）。

每个新实例启动时 L2 为空，从 L1 填充，所以发布期间会看到 `cocache.misses` 与 `cocache.hits{level=l1}` 的突增，而不是回源的突增。

有些配置变更不能逐个实例滚动：

| 变更 | 能否滚动 | 新旧实例并存期间会发生什么 |
|------|----------|----------------------------|
| 策略字段（`ttl`、`local-max-age`、`lease-*`、`load-*`、失败模式等） | 能 | 各实例按自己的策略运行 |
| `channel-mode` | L1 能，L2 不能 | 模式不同的实例互相收不到消息。L1 仍然正确，但其它实例的 L2 副本最多陈旧 `local-max-age`。切换期间调低 `local-max-age`，或者所有实例同时切换 |
| `key-prefix`、`namespace`，或命名空间默认等于缓存名时的缓存名 | **不能** | 新旧实例使用不同的 key，互相忽略对方的失效，一侧的写入会让另一侧陈旧到 `ttl`。所有实例同时切换。前缀或命名空间变化也会改变频道 |
| 编解码器的字节格式 | 可以，但代价高 | 双方都把对方的值当作损坏，回收后回源（`cocache.reclaims`）。优先使用新的命名空间，或者让 `decode` 同时接受两种格式 |

旧前缀或旧命名空间下的 key 会随 `ttl` 到期。想提前删除时，用 `redis-cli --scan --pattern 'old-prefix:*' | xargs redis-cli unlink`（集群中逐个主节点执行）。

## key 布局与共用 Redis {#key-layout}

| 内容 | 格式 | 示例 |
|------|------|------|
| 存储 key | `{keyPrefix}{namespace}:{encodedKey}` | `cocache:UserCache:42` |
| 频道 | `{keyPrefix}inv:{namespace}` | `cocache:inv:UserCache` |

`keyPrefix` 默认 `cocache:`，可以为空；`namespace` 默认等于缓存名，`encodedKey` 是 key 的 `toString()` 或 `keyExpression` 的结果。CoCache 的内部 key 放在 `{keyPrefix}!` 之下，任何命名空间都产生不了这个位置。见[存储 key](../guide/caches.md#storage-keys)。不同大版本的实例不共用 key：见[滚动发布](#rolling-deploys)。

多个应用可以共用一个 Redis：

- **给每个应用设置自己的 key 前缀**，例如 `cocache.key-prefix: ${spring.application.name}:`。否则两个应用中同名的缓存会读取并覆盖彼此的值。有意在应用之间共享的缓存，各处必须使用相同的前缀、命名空间、编解码器与通道模式。
- **频道带有前缀**。前缀不同的应用即使命名空间相同，也收不到彼此的消息。但 Redis Pub/Sub 不区分数据库编号：使用不同数据库、前缀与命名空间都相同的应用共用一个频道，对方的消息会无谓地淘汰本方的 L2 副本。请使用不同的前缀。

## 安全 {#security}

### Redis ACL {#acl}

CoCache 的 Redis 用户需要以下权限：

| 方面 | 需要 |
|------|------|
| key | 对你的 key 前缀可读写（默认 `~cocache:*`）。`pubsub` 模式下还需要探测 key `cocache:!probe:{a}`，`~cocache:*` 已经包含它 |
| 频道 | 每个前缀的 `&{keyPrefix}inv:*`（默认 `&cocache:inv:*`）。`pubsub` 模式下还需要 `&cocache:!probe:*`，否则后端退回非原子广播 |
| 直接调用的命令 | `EVALSHA`、`EVAL`（`NOSCRIPT` 后的回退）、`PING`（心跳）、`PUBLISH`、`SUBSCRIBE`、`UNSUBSCRIBE`，`sharded` 模式下为 `SPUBLISH`、`SSUBSCRIBE`、`SUNSUBSCRIBE`，以及连接命令（`HELLO`、`AUTH`、`CLIENT`、`SELECT`）。使用 `Store`、`VersionedStore` 或 `SetStore` 时还需要 `INFO`，供[启动时的驱逐检查](#maxmemory-policy)使用 |
| 脚本内的命令 | Redis 7 同样按调用方的 ACL 检查：`GET`、`GETRANGE`、`STRLEN`、`SET`、`DEL`、`PTTL`、`TYPE`、`HGETALL`、`HKEYS`、`HMGET`、`HSET`、`HDEL`、`HLEN`、`PEXPIRE`、`PERSIST`、`SETRANGE`、`TIME` |

一个起点（已在 Redis 7.4 上以该用户执行全部 CoCache 脚本验证）：

```
ACL SETUSER cocache on >change-me ~cocache:* &cocache:inv:* &cocache:!probe:* +@connection +evalsha +eval +publish +subscribe +unsubscribe +spublish +ssubscribe +sunsubscribe +info +get +getrange +strlen +set +del +pttl +type +hgetall +hkeys +hmget +hset +hdel +hlen +pexpire +persist +setrange +time
```

脚本以 `HMGET` 读集合的格式字段，写回与释放令牌时以 `GETRANGE` 只读头部的开头，条件读写以 `GETRANGE` 与 `STRLEN` 只读头部，条件回收使用 `HGETALL`。用户缺少脚本调用的某条命令时，读取、写回与条件读写会以 ACL 错误失败（措辞随 Redis 版本不同）。

创建缓存时，CoCache 像之后的写入一样向该缓存的频道发布一个空帧。ACL 不放行这个频道时，创建以 `IllegalStateException` 失败，信息给出要放行的模式，例如 `[&cocache:inv:*]`。否则之后的每次写入都会先修改 Redis、再在广播处失败，其它实例陈旧到 `local-max-age`。

使用自定义 `key-prefix` 时，把 `~cocache:*` 换成你的前缀，把 `&cocache:inv:*` 换成 `&{keyPrefix}inv:*`。`pubsub` 模式下保留 `~cocache:!probe:*` 与 `&cocache:!probe:*`，原子广播的探测不使用前缀。前缀为空时，key 与频道没有公共前缀：改为逐个列出命名空间（`~{namespace}:*` 与 `&inv:{namespace}`），另加内部 key 的 `~!*`。被 ACL 拒绝的创建这时给出它自己的频道，例如 `[&inv:user]`。Redis Cluster 还要允许 Lettuce 发现拓扑所用的命令（`+cluster|nodes`、`+info`）。每个节点都要设置该用户：ACL 不会随复制同步。

常见错误是禁止了 `PING`。此时每个心跳周期都会失败，每次失败都清空所有 L2，健康检查一直是 `DOWN`。

### Actuator 端点 {#endpoint-security}

`GET /actuator/cocache/{name}/{key}` 会展示缓存的值，`DELETE` 会在全集群失效它们。只在管理端口上或认证之后暴露该端点，与其它能读取业务数据的 Actuator 端点一样。只允许查看、不允许删除时，设置 `management.endpoint.cocache.access: read-only`。删除唯一副本的请求还需要第二个开关，默认关闭：`Store` 或 `SetStore` 的 key 需要 `management.endpoint.cocache.allow-store-delete`，丢掉带版本 key 的版本的 purge 需要 `management.endpoint.cocache.allow-purge`。见[端点](./observability.md#invalidate-key)。
