---
title: 故障排查
description: CoCache 生产问题的处理手册：陈旧值、命中率骤降、回源风暴、等待 lease 超时、失效被放弃、Redis 不可用、损坏或未知格式的载荷、启动失败，以及从 CoCache 之外失效的协议。
---

# 故障排查

每个条目按“现象 → 原因 → 检查 → 处理”组织。用到的工具是 [Actuator 端点](./observability.md#actuator-endpoint)、[指标](./observability.md#metrics)与 [`redis-cli`](#inspecting-redis)。

**任何错误值的急救**：`DELETE /actuator/cocache/{name}/{key}` 在所有实例上失效该 key。然后再查它为什么错。

## 单个实例上的陈旧值 {#stale-one-node}

**现象**。一个实例返回旧值，其它实例返回新值。

**原因**。该实例的 L2 漏掉了一条失效消息。消息至多投递一次：订阅连接断开期间发布的消息、或因输出缓冲区限制被丢弃的消息都会丢失。该副本最迟在 `local-max-age`（默认 5 分钟）后自愈，连接被重置时会更早。

**检查**。

1. 在出问题的实例上[查看该 key](./observability.md#inspect-key)。`local` 比 `remote` 旧即可确认漏掉了消息，`local.expiresAt` 是最晚的自愈时间。
2. 查看该实例在写入时间前后的 `cocache.resets` 与日志，找 `Invalidation channel heartbeat failed` 或重连。
3. 确认该实例与写入方使用相同的 `cocache.redis.channel-mode`、相同的命名空间、相同的 Redis。[健康检查](./observability.md#health)会显示 `channelMode`。模式不同的实例永远收不到对方的消息。
4. 检查 Redis 是否断开了订阅者：`redis-cli INFO stats`（较新版本有 `client_output_buffer_limit_disconnections`）与 `CONFIG GET client-output-buffer-limit`（`pubsub` 类）。

**处理**。

- 让所有实例的通道模式、命名空间与 Redis 保持一致。
- 订阅者因输出缓冲区限制被断开时，调高 `pubsub` 的限制，或减少消息量（用 `invalidateAll` 批量失效，或在 Redis Cluster 中使用 `sharded` 模式）。
- 如果这个窗口对你的数据来说太长，调低 `local-max-age`。它就是这类故障的上界。

## 所有实例都陈旧 {#stale-everywhere}

**现象**。所有实例都返回旧值，查看结果显示 `local` 与 `remote` 是同一个旧值。

**原因**。数据源变化之后没有任何东西失效该 key，或者失效发生在变化可见之前。于是 L1 保留旧值直到 `ttl`。

**按顺序检查**。

1. **没有失效的写入路径**。直接 SQL、批处理、其它服务、管理工具。每一条都必须失效，见[外部失效](#external-invalidation)。
2. **在提交之前失效**。并发的未命中可能在失效之后加载到未提交的（旧）行并填充。请在提交之后失效。事务内使用 `TransactionalInvalidator`，它会推迟到 `afterCommit`（[Spring Cache](../guide/spring-cache.md#transactions)）。
3. **没有 `sync = true` 的 `@Cacheable`**。此时由 Spring 加载并调用无条件的 `put`，可能用旧值覆盖更晚的失效。starter 会在启动时为每个这样的方法记录 `WARN`。请使用 `sync = true`，或设置 `cocache.spring-cache.require-sync: true` 让启动失败。
4. **loader 读取了滞后的从库**。加载可能在失效之后返回旧行。请从主库加载，或者接受最多 `ttl` 的陈旧。
5. **失效被放弃**。检查 `cocache.invalidations.dropped`，见[下文](#invalidations-dropped)。
6. 写入之后紧接着发生的 **Redis 故障切换**可能丢失 `DEL`。见 [Redis 不可用](#redis-down)。
7. **key 编码不一致**。写入方失效的编码后 key 与读取方使用的不同，例如 `keyExpression`、`key-prefix` 或命名空间不同。对比查看结果中的 `storageKey` 与写入方删除的 key。

**处理**。先失效该 key，再补上找到的漏洞。保持 `ttl` 有限，作为你控制不了的写入的安全网。

### 新建的记录被报告为不存在 {#negative-entry}

记录创建之前的一次读取缓存了负缓存，它最多保留 `absent-ttl`（默认 1 分钟）。查看结果显示 `ABSENT`。创建记录之后失效该 key，或者调低 `absent-ttl`。

## 命中率骤降：反复重置 {#resets}

**现象**。`cocache.misses` 与 `cocache.hits{level=l1}` 突增，`cocache.local.size` 反复跌到接近 0，`cocache.resets` 持续上升。健康检查可能是 `DOWN`。

**原因**。每次心跳失败、每次（重新）订阅都会清空实例上的所有 L2，因为消息可能已经丢失。心跳持续失败时，L2 每个 `heartbeat` 周期（5 s）被清空一次。

**检查**。

- `GET /actuator/health/cocache`：`heartbeat.error` 显示最近一次失败。
- 日志：`Invalidation channel heartbeat failed - reset all subscribers and force reconnect.`
- **ACL 禁止了 `PING`**。`heartbeat.error` 中含 `NOPERM`。见 [ACL](./index.md#acl)。
- **网络**。超时意味着代理、负载均衡或 NAT 丢弃了空闲或长连接，或者 Redis 过载。
- **`command-timeout` 太小**。心跳等待 `PONG` 的上限是 `command-timeout`（默认 2 s）。
- **Redis Cluster 的 `pubsub` 模式**。CoCache 无法强制集群订阅连接重连（`cannot force reconnect … rely on TCP keepalive`），半开连接会一直持续到 TCP keepalive 发现它（约 30 s）。

**处理**。允许 `PING`，修复网络路径，或调高 `cocache.redis.command-timeout`。调高 `cocache.redis.heartbeat` 会减少重置，但会推迟发现断开的连接。发布与 Redis 故障切换期间的重置属于正常。

## 回源风暴与回源被拒绝 {#load-rejections}

**现象**。数据源过载，或者调用方收到 `SourceLoadRejectedException`，`cocache.load.rejections` 上升。

**原因**。同一个 key 的并发未命中会合并：每个实例一次加载，loader 在 `lease-wait` 内完成时全集群一次。不同的 key 不会合并。以下情况会让加载激增：

- Redis 故障，每次 L2 未命中都回源（[Redis 不可用](#redis-down)）。
- 大量 key 同时到期。`ttl-jitter` 会打散到期时间，但它为 `0` 或相对 `ttl` 很小时不起作用。
- L2 被清空（发布、[重置](#resets)），而 L1 也是冷的。
- 流量命中大量未缓存的 key。

`load-max-concurrent` 限制每个缓存在每个实例上的并发加载数。在 `load-acquire-timeout`（默认 1 s）内拿不到许可的加载会以 `SourceLoadRejectedException` 失败。合并到在途加载的等待者不占许可，一次批量加载占一个许可。

**检查**。`cocache.loads` 的速率与 `cocache_loads_seconds_sum / cocache_loads_seconds_count`（平均加载耗时）、`cocache.remote.failures`、`cocache.degraded.fills`、`cocache.resets`，以及数据源自己的指标。

**处理**。

- 拒绝是在保护数据源。在调用方处理 `SourceLoadRejectedException`（降级、返回 `503`），或者在数据源有余量时调高 `load-max-concurrent`。
- 让 `ttl-jitter` 保持有意义（默认 1 分钟对 1 小时的 `ttl`）。
- 对热点 key 使用 `refresh-window`，让它们在到期之前由全集群一次地在后台重新加载。
- 对不存在的 key，`KeyFilter` 可以不回源直接回答，负缓存会在 `absent-ttl` 内吸收重复请求。

## 等待 lease 超时 {#lease-wait-timeouts}

**现象**。`cocache.lease.wait.timeouts` 占 `cocache.lease.waits` 的比例很高，数据源看到同一个 key 被多个实例加载。

**原因**。某个实例持有 key 的 lease 时，其它实例带退避地重读，最多等待 `lease-wait`（默认 200 ms）。loader 更慢时，它们自行加载、不写 L1，结果以 `degraded-local-max-age` 留在 L2 中。结果正确，但失去了全集群合并回源。

**处理**。如果调用方等得起，把 `lease-wait` 调到 loader 的 p99 以上，或者让 loader 更快。`lease-ttl`（默认 10 s）也要大于 loader 的最坏耗时：比 lease 活得更久的加载无法填充 L1（`cocache.fill.rejections`）。

## 失效在重试或被放弃 {#invalidations-dropped}

**现象**。`cocache.write.failures` 与 `cocache.invalidation.retries` 上升，健康详情中的 `pendingInvalidations` 大于 0。最坏情况下 `cocache.invalidations.dropped` 上升，并有 `ERROR` 日志 `Cache[…] - invalidate […] dropped: L1 keeps the stale value until its remote TTL[…] expires.`

**原因**。某次 `invalidate` 没能写到 Redis。默认（`invalidate-failure: retry`）下，本地效果立即生效，调用正常返回，失效在后台重试：最多 8 次，退避从 100 ms 起翻倍到 10 s，最多排队 10,000 个。重试期间，本实例上该 key 的未命中会跳过 L1 直接回源。其它实例的 L1 中仍是旧值。

重试耗尽、队列已满或应用在仍有重试时关闭，失效就会被放弃。此时 L1 保留旧值直到其剩余 `ttl` 结束（`ttl-forever` 时永远保留），每个实例都可能把它重新加载进 L2。

**检查**。Redis 的可用性与延迟、`cocache.remote.failures`，以及 `ERROR` 日志中被放弃的 key。

**处理**。

- Redis 恢复后再次失效被放弃的 key：`DELETE /actuator/cocache/{name}/{key}`，或者 `cache.invalidateAll(keys)`。
- 想让调用方失败而不是重试，设置 `invalidate-failure: throw`，调用会抛出 `CacheWriteException`。
- 要调整重试策略，自行声明 `CacheRuntime` bean，用 `RetryPolicy(maxAttempts, initialBackoff, maxBackoff, capacity)` 构建。存在这样的 bean 时，starter 的运行时会退让。
- 优雅停机，让退出时的重试队列尽量短。

## Redis 不可用 {#redis-down}

**现象**。`cocache.remote.failures`、`cocache.degraded.fills` 与 `cocache.write.failures` 上升，数据源负载上升，健康检查变为 `DOWN`。

**CoCache 的行为**。

| 操作 | Redis 故障期间 |
|------|----------------|
| `get`、`getAll`、`lookup` | 默认（`read-failure: degrade`）：从数据源加载，结果以 `degraded-local-max-age`（10 s）留在 L2 中，不写 L1。`read-failure: throw` 时抛出 `CacheReadException` |
| 填充、释放 lease、刷新 | 尽力而为：记录日志并计数，从不抛出 |
| `invalidate`、`invalidateAll` | 本地效果立即生效，然后在后台重试（[见上文](#invalidations-dropped)）。`invalidate-failure: throw` 时抛出 `CacheWriteException` |
| `put`、`SetCache` 写入 | 本地效果立即生效，然后抛出 `CacheWriteException`。超时之后写入可能已经到达 Redis。这些调用幂等，可以安全重试 |
| `SetCache` 读取 | 抛出 Redis 异常：没有可以降级的数据源 |
| 通道 | 心跳失败，所有 L2 被清空，订阅恢复时再清空一次 |

断线期间命令被拒绝而不是缓冲，每条命令最多等待 `command-timeout`（2 s）。所以 Redis 不可用时，每次未命中的代价是一次快速失败加一次加载。

**处理**。恢复 Redis。在此期间 `load-max-concurrent` 保护数据源。恢复后不需要额外操作：重试会自行完成，每个实例的订阅恢复时都会重置 L2。检查 `cocache.invalidations.dropped`，对被放弃的 key 重新失效。

**故障切换之后**。异步复制可能丢失最近的写入，包括失效，所以切换前刚被失效的值可能重新出现在 L1 中，持续到其 `ttl`。如果在意这一点，切换后失效相关的 key 或缓存。

## 损坏或无法读取的载荷 {#corrupt-payloads}

**现象**。`cocache.reclaims` 上升，伴随 `WARN Cache[…] - key[…] - corrupted payload, reclaim and reload.`。查看结果显示 `remote.state: UNREADABLE`。

**原因**。某个 L1 值无法解码：

- **外来内容**（格式 `0`）：不是 CoCache 写入的，例如旧客户端、脚本，或者前缀与命名空间相同的另一个应用。
- **编解码失败**（格式 `1`）：值的类发生了不兼容的变化（字段改名、类型变化），或者两个应用用不同的编解码器或值类型共享同一个缓存。

**CoCache 的行为**。读路径从不盲目删除。它请求 Redis：仅当 key 仍是它读到的那份字节时（在脚本内比较 SHA-1），才把 key 转为 lease。成功则回源并正常填充；key 在此期间变了，就不写 L1 直接回源。每次读取最多回收一次。

**处理**。

- 编解码变更之后，所有旧值被替换时回收就会停止。如果新旧实例并存，它们会不断回收对方的值。不兼容的值变更优先使用新的命名空间，或者使用能读两种形式的编解码器。
- 用 `redis-cli MONITOR`（短时间使用，开销很大）或审计谁在写你的 key 前缀来找外来写入方。给其它应用设置各自的 [key 前缀](./index.md#key-layout)。

## 发布期间出现未知格式 {#unknown-format}

**现象**。`cocache.unknown.formats` 上升。查看结果显示 `UNREADABLE`，原因是 `Written by a newer format[…] than this node[…]`。

**原因**。某个实例写入了本实例不认识的更新线格式。本实例回源、不写 L1，结果以 `degraded-local-max-age` 留在 L2 中，这样新旧实例不会互相覆盖。`SetCache` 没有可回源的数据源：在本实例上读取更新格式的集合会抛出 `IllegalStateException`；不是 CoCache 写入的内容（例如遗留的原生 Redis Set）读作空集合，直到被 `put` 或 `add` 覆盖。

**处理**。格式升级滚动发布期间属于正常，所有实例升级到新版本后就会停止。非发布期间出现时，找出运行新版本的实例。见[滚动发布](./index.md#rolling-deploys)。

## 启动失败 {#startup-failures}

CoCache 对无法安全运行的配置快速失败，错误信息会指出问题：

| 错误信息（节选） | 原因 | 处理 |
|------------------|------|------|
| `CoCache 6.0 removed these properties: [cocache.…] - …` | 使用了已不存在的配置项 | 按信息改名。见[从 5.x 升级](../guide/migration-6.md#properties) |
| `cocache.caches configures undeclared caches [x]; declared caches: […]` | `cocache.caches.[name]` 中出现了不存在的缓存，通常是拼写错误。缓存名不区分大小写匹配 | 修正名称或删除该项 |
| `Cache[x] - … must be positive …`、`refreshWindow[…] must be less than ttl[…] - ttlJitter[…]`、`refreshWindow[…] must be less than localMaxAge[…]` | 所有配置层叠加之后策略不合法 | 修正取值。不写单位的时长按秒解析 |
| `Cache[x] - @CoCache.<attribute> = FOREVER is not supported; FOREVER is only allowed for @CoCache.ttl and @CaffeineCache.expireAfterAccess.` | 在 `ttl` 与 `@CaffeineCache.expireAfterAccess` 以外的属性上使用了 `FOREVER` | 改用具体时长 |
| `Cache[x] already exists.` / `Namespace[x] is already used by another cache.` | 两个声明使用了相同的缓存名或命名空间，例如一个接口与一个 spec bean | 改名其中一个，或设置不同的 `namespace` |
| `Cannot register cache interface[…]: the bean name[x] is already in use.` / `Cannot register cache bean[x] for spec bean[…]` | 缓存的 bean 名与其它 bean 冲突 | 修改缓存名（`@CoCache(name)`）或 spec bean 名 |
| `Cache interface[…] must extend Cache with concrete type arguments.` / `Cache spec bean[…] must declare its generic types` | 无法解析泛型 | 使用具体类型，并把 spec 工厂方法的返回类型声明为 `CacheSpec<K, V>` |
| `Cache interface[…] declares abstract methods that do not match any Cache method: […]` | 接口上有多余的抽象方法 | 改为 `default` 方法 |
| `Multiple CacheLoader beans match …` / `Multiple Codec beans match …` | 一个缓存有多个候选 | `@CoCache(loader = "beanName")` / `@CoCache(codec = "beanName")` |
| `@CoCache(loader = …) is not supported on SetCache interface[…]` | `SetCache` 没有数据源 | 去掉 loader，或改用 `Cache<K, Set<E>>` |
| `Cannot create cache[x] - no CoherenceBackend is available: …` | 既没有 Spring Data Redis 连接信息，也没有后端 bean | 配置 `spring.data.redis.*`，本地运行时设置 `cocache.backend: memory`，或声明 `CoherenceBackend` bean |
| `Cannot create cache[x] - CoCache is disabled (cocache.enabled=false)` | CoCache 关闭时仍声明了缓存 | 启用它，或删除这些声明 |
| `CoCache does not support Redis master-replica (spring.data.redis.masterreplica.*)` | 静态主从 | 使用单机、Sentinel 或 Cluster |
| 创建 `cocacheBackend` 时出现 `RedisConnectionException` 或 `Command timed out after …` | 启动时连不上 Redis。后端在创建时就连接并探测 | 修复连通性。探测超时会故意让启动失败，而不是悄悄关闭原子广播 |
| 创建缓存时出现 `NOPERM` | ACL 禁止了该缓存的频道或 key | 见 [ACL](./index.md#acl) |
| `@Cacheable without sync = true on CoCache caches: …` | `cocache.spring-cache.require-sync: true` 且存在不带 sync 的 `@Cacheable` | 加上 `sync = true`，或关闭该检查 |
| `Join cache[x] - …` | `@JoinCacheable` 指向不存在的缓存、类型不符，或同时指定了两种提取方式 | 按信息处理。见 [JoinCache](../guide/join-cache.md) |

有一项检查发生在第一次调用而不是启动时：`Key type [...] does not override toString()` 表示缓存的 key 类型没有有意义的 `toString()`，每个 key 对象都会映射到不同的 Redis key。请使用 data class 或 record、重写 `toString()`，或者设置 `keyExpression` 或 `KeyEncoder`。

## 来自 CoCache 之外的写入 {#external-invalidation}

绕过 CoCache 的写入（直接 SQL、批处理、其它语言的服务、CDC 管道）会让旧值一直缓存到 `ttl`。任何能访问同一个 Redis 的进程都可以用 CoCache 自己使用的协议失效。该协议在一个大版本内冻结。

**始终先修改数据源，再失效**，与 `cache.invalidate` 一样。

### JVM 上：`ExternalInvalidator` {#external-invalidator}

`ExternalInvalidator`（在 `cocache-core` 中）做的事与 `invalidate` 完全相同，但不需要创建缓存。后端支持原子广播时，删除与广播在同一个脚本内完成。

```kotlin
@Bean
fun externalInvalidator(backend: CoherenceBackend) = ExternalInvalidator(backend)

@Bean
fun orderInvalidator(backend: CoherenceBackend) = ExternalInvalidator(backend, "orders:")  // 非默认的 key 前缀

// 数据提交之后
externalInvalidator.invalidate("UserCache", listOf("42", "43"))  // 命名空间、编码后的 key
orderInvalidator.invalidate("OrderCache", "7/9")
```

第一个参数是**命名空间**（缓存设置了 `namespace` 时用它，否则就是缓存名），key 是**编码后的 key**（[含义](./observability.md#encoded-key)）。不在 Spring 中时，传入缓存使用的同一个 `CoherenceBackend`，或者连接同一个 Redis、使用同一种通道模式的任意 `RedisBackend`。

编码后的 key 为空串时抛出 `IllegalArgumentException`，传入空集合什么也不做。`close()` 之后的任何调用都抛出 `IllegalStateException`；`close()` 只释放失效器自己创建的存储句柄，不关闭后端。

### 任意语言：原始协议 {#raw-protocol}

| 部分 | 取值 |
|------|------|
| 存储 key | `{keyPrefix}{namespace}:{encodedKey}`，例如 `cocache:UserCache:42` |
| 频道 | `cocache:inv:{namespace}`，例如 `cocache:inv:UserCache`。不含 key 前缀 |
| 消息 | 二进制帧，见下文 |
| 发布命令 | `PUBLISH`；缓存使用 `channel-mode: sharded` 时为 `SPUBLISH` |

帧格式：

```
[版本：1 字节 = 0x01]
[发布者 id：16 字节]
[key 数量：varint]
重复 key 数量次：
  [key 的字节长度：varint]
  [存储 key：UTF-8]
```

- varint 是无符号 LEB128：每字节 7 位，低位在前，除最后一个字节外最高位都置 1。小于 128 的值占 1 个字节。
- key 是完整的**存储 key**，包含前缀与命名空间。一帧可以携带同一命名空间的多个 key。
- 发布者 id 必须与每个 CoCache 实例的 id 都不同：使用 16 个随机字节。实例会忽略带有自己 id 的帧。
- 畸形帧会被丢弃并记录 `WARN`，不产生任何效果。

每次修改，在数据源提交之后：

1. **`DEL` 存储 key**。这会删除 L1 副本，并销毁任何 lease 或刷新令牌，正在进行的加载就无法把旧值写回。
2. **发布帧**到该命名空间的频道。每个实例丢弃自己的 L2 副本。

先删除再发布。如果先发布，某个实例可能在删除之前把 L1 中的旧值重新加载进 L2，并保留到 `local-max-age`。如果不发布，各实例的 L2 副本保留到 `local-max-age`。如果不删除，各实例会从 L1 重新加载旧值。

Python（`redis-py`）：

```python
import os
import redis

def varint(n: int) -> bytes:
    out = bytearray()
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)

def frame(storage_keys: list[str]) -> bytes:
    body = bytearray(b"\x01") + os.urandom(16) + varint(len(storage_keys))
    for key in storage_keys:
        raw = key.encode("utf-8")
        body += varint(len(raw)) + raw
    return bytes(body)

r = redis.Redis()
keys = ["cocache:UserCache:42"]
r.delete(*keys)                                  # Redis Cluster 中每次调用一个 key
r.publish("cocache:inv:UserCache", frame(keys))  # sharded 模式下用 r.spublish(...)
```

在 shell 中，对一个短于 128 字节的 ASCII key（`bash`）：

```bash
key='cocache:UserCache:42'
redis-cli DEL "$key"
{ printf '\x01'; head -c 16 /dev/urandom; printf '\x01'; printf "\\x$(printf %02x ${#key})"; printf '%s' "$key"; } \
  | redis-cli -x PUBLISH cocache:inv:UserCache
```

在没有 `cocache-core` 的 JVM 上，`cocache-api`（没有运行时依赖）中的 `InvalidationFrame.encode(NodeId.random(), storageKeys)` 可以构造帧。

与 CoCache 自己的消息一样，投递至多一次：丢失的消息会让 L2 副本陈旧到 `local-max-age`。

::: warning SetCache
对 `SetCache` 来说，`DEL` 会删除集合的唯一副本。不要从外部失效集合缓存，请通过 `SetCache` 写入。
:::

## 手动查看 Redis {#inspecting-redis}

CoCache 的值不是可读的 JSON：每个值都以 36 字节的二进制头开始。常用命令：

| 问题 | 命令 |
|------|------|
| 一个缓存有哪些 key？ | `redis-cli --scan --pattern 'cocache:UserCache:*' \| head`（集群中逐个主节点执行） |
| key 处于什么状态？ | `redis-cli TYPE cocache:UserCache:42`：`string`（值、负缓存或 lease）或 `hash`（集合）。对 string，`redis-cli GETRANGE cocache:UserCache:42 2 2` 返回状态：`V` 值、`M` 负缓存、`L` lease |
| 还能存活多久？ | `redis-cli PTTL cocache:UserCache:42`（毫秒，`-1` 表示永不过期） |
| 里面是什么？ | `GET /actuator/cocache/{name}/{key}` 会解码两层。原始字节：`redis-cli GETRANGE key 36 -1` 返回编解码器的输出 |
| 占多少内存？ | `redis-cli MEMORY USAGE cocache:UserCache:42` |
| 谁在监听一个命名空间？ | `redis-cli PUBSUB NUMSUB cocache:inv:UserCache`，`sharded` 模式下用 `PUBSUB SHARDNUMSUB`。集群中按节点计数 |
| 哪些连接属于 CoCache？ | 设置 `cocache.redis.client-name`，然后在 `redis-cli CLIENT LIST` 中找该 `name`。订阅连接的 `sub=` 或 `ssub=` 大于 0 |
| 失效消息在流动吗？ | `redis-cli SUBSCRIBE cocache:inv:UserCache`（或 `SSUBSCRIBE`）会打印每一帧，二进制输出中的存储 key 是可读的 |

除了通过[失效协议](#external-invalidation)，不要手动修改 CoCache 的 key。
