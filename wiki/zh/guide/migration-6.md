---
title: 从 5.x 升级
description: CoCache 5.x 与 6.0 之间的全部变化：模块、包名、注解、API、配置项、Redis 线格式、指标、Actuator、移除的能力，以及如何切换集群。
---

# 从 5.x 升级

6.0 是一次重写。接口式声明（`@CoCache interface UserCache : Cache<String, User>`）仍然可用，只需更换 import 并修改几个改名的属性；但它周围几乎全部改变了：API、SPI、模块、配置项、Redis 线格式和失效通道。本页列出每一项变化及其替代方案。

## 切换集群 {#switch-over}

**不能滚动升级。** Redis 线格式和失效通道都变了，5.x 与 6.0 实例不能共用缓存：它们互相收不到失效，还会互相覆盖对方的值。从以下方式中选择一种：

- **停掉所有 5.x 实例，再启动 6.0。** 最简单，缓存从冷启动开始。
- **6.0 使用独立的 key 并行运行**，用于蓝绿部署：为每个 6.0 缓存设置不同的 `keyPrefix`（或命名空间），或者让 6.0 使用另一个 Redis 数据库，然后切换流量。注意一侧的写入不会失效另一侧。

然后处理 5.x 留下的 key：

- 默认布局下，6.0 的 key 与 5.x 的 key 都是 `cocache:{cacheName}:{key}`，所以 6.0 会读到旧值。任何 5.x 的值（String、Hash 或原生 Set，包括 `_nil_` 哨兵）都会被识别为外来内容（格式 0），仅在未被修改时回收，然后从数据源重新加载，无需手工清理。
- **5.x 的 `SetElementsCache` 是例外**：它的原生 Redis Set 是唯一副本，而 6.0 的 `SetCache` 读不了它。读取这样的 key 会返回空集合（计入 `cocache.unknown.formats`，Redis 中的内容保留不动），直到该 key 被重写；`add` 会替换旧集合，而不是在其上追加。切换之前用 `put(key, fullSet)` 逐个迁移集合，或者给 `SetCache` 设置新的 `keyPrefix`，再从旧 key 复制。
- 要立即释放内存，按前缀删除旧 key（`SCAN` 配合 `MATCH`，再 `UNLINK`）。

`redis-cli GET` 不再显示 JSON：每个值都以 36 字节的二进制头开始。直接从 Redis 读取 CoCache key 的程序（例如对 Hash 编码的缓存执行 `HGET`）不再受支持。

## 模块 {#modules}

| 5.x | 6.0 |
|-----|-----|
| `cocache-api` | `cocache-api`（新的 API 与 SPI，仍然零依赖） |
| `cocache-core` | `cocache-core`（新的引擎、`CacheRuntime`、`CacheSpec`、内存后端） |
| `cocache-spring` | `cocache-spring`：现在只包含 Spring Cache 适配与 `TransactionalInvalidator`。`@EnableCoCache` 移到了 starter |
| `cocache-spring-redis` | 删除。由 `cocache-redis`（原生 Lettuce，不依赖 Spring）取代 |
| `cocache-spring-cache` | 删除。并入 `cocache-spring` |
| — | `cocache-codec-jackson`（Jackson 3 编解码） |
| `cocache-spring-boot-starter` | `cocache-spring-boot-starter` |
| `cocache-test` | `cocache-test`（新的 TCK，见[扩展](../architecture/extending.md)） |

## Import {#imports}

基础包名从 `me.ahoo.cache` 改为 `me.ahoo.cocache`。

| 5.x | 6.0 |
|-----|-----|
| `me.ahoo.cache.api.Cache` | `me.ahoo.cocache.api.Cache` |
| `me.ahoo.cache.api.annotation.CoCache` | `me.ahoo.cocache.api.annotation.CoCache` |
| `me.ahoo.cache.api.annotation.CaffeineCache` | `me.ahoo.cocache.api.annotation.CaffeineCache` |
| `me.ahoo.cache.spring.EnableCoCache` | `me.ahoo.cocache.spring.boot.EnableCoCache`（在 starter 中） |
| `me.ahoo.cache.api.source.CacheSource` | `me.ahoo.cocache.api.CacheLoader` |
| `me.ahoo.cache.spring.cache.CoCacheManager` | `me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager` |
| `me.ahoo.cache.api.join.JoinCache` / `JoinValue` / `JoinKeyExtractor` | `me.ahoo.cocache.api.join.JoinCache` / `JoinValue` / `JoinKeyExtractor` |
| `me.ahoo.cache.api.annotation.JoinCacheable` | `me.ahoo.cocache.api.annotation.JoinCacheable` |

## 注解 {#annotations}

时长单位仍然是秒。

| 5.x | 6.0 |
|-----|-----|
| `@CoCache(ttl)` | `ttl`。`TtlAt.FOREVER` 改为 `CoCache.FOREVER` |
| `@CoCache(ttlAmplitude)` | `ttlJitter` |
| `@CoCache(missingTtl)` | `absentTtl` |
| `@CoCache(clientSideTtl)` | `localMaxAge`。**`0`（不限制）没有对应值**，见[下文](#semantics) |
| `@CoCache(refreshAhead)` | `refreshWindow`。`0` 仍表示关闭 |
| `@CoCache(keyPrefix)`，默认 `cocache:{name}:` | `keyPrefix`，默认 `cocache:`，后接命名空间。注解不再解析 Spring `${…}` 占位符；`key-prefix` 配置项支持占位符，见[配置项](#properties) |
| `@CoCache(keyExpression)` | `keyExpression`（同样的 SpEL 模板，以 key 为根对象） |
| — | 新增：`namespace`、`loader`、`codec` |
| `@CaffeineCache(maximumSize, initialCapacity, expireAfterAccess)` | 名称不变 |
| `@CaffeineCache(expireUnit)` | 删除：`expireAfterAccess` 固定以秒为单位 |
| 元注解与 `@Inherited` | 删除：注解必须直接标在缓存接口上 |
| `@JoinCacheable` | `me.ahoo.cocache.api.annotation.JoinCacheable`，新增 `joinKeyExtractor` 属性，见 [JoinCache](#joincache) |

6.0 中 `FOREVER` 只能用于 `ttl` 与 `expireAfterAccess`，`0` 只能用于 `ttlJitter` 与 `refreshWindow`，其它情况启动失败。

## 数据源 {#data-source}

| 5.x | 6.0 |
|-----|-----|
| `CacheSource<K, V>.loadCacheValue(key): CacheValue<V>?` | `CacheLoader<K, V>.load(key): V?` |
| `loadCacheValues(keys): Map<K, CacheValue<V>>` | `loadAll(keys): Map<K, V>` |
| 由数据源决定每个值的 `ttlAt` | 由策略决定（`ttl` 加抖动）。按条目决定：`CacheSpec.ttlOf(key, value)`，见[按条目决定寿命](./caches.md#ttl-of) |
| 名为 `{cacheName}.CacheSource` 的 bean，否则唯一的 `CacheSource<K, V>` | `@CoCache(loader = "beanName")`，否则唯一的 `CacheLoader<K, V>`。有多个候选时启动失败 |
| `CacheSource.noOp()`：每次未命中都在 Redis 中写入负缓存 | 没有 loader：未命中返回 `null`，不写 Redis |

## API {#api}

| 5.x | 6.0 |
|-----|-----|
| `cache[key]` / `get(key)` | `cache[key]` / `get(key)` |
| `getCache(key): CacheValue<V>?` | `lookup(key): Lookup<V>`（`Present(value, expiresAt)` / `Absent(expiresAt)`） |
| `getTtlAt(key)` | `lookup(key).expiresAt`：返回的这份副本的到期时间（命中 L2 时是 L2 副本的到期时间，受 `localMaxAge` / `degradedLocalMaxAge` 限制），精度为缓存时钟的刻度。要看 Redis 中的 TTL，用 [actuator 查看 key](../operations/observability.md#inspect-key) 或 `CacheAdmin.inspect`。 |
| `getCaches(keys)` / `getAll(keys)` | `getAll(keys)`，现在会与并发的单 key 加载合并 |
| `cache[key] = value` / `set(key, value)` | `put(key, value)` |
| `set(key, ttlAt, value)` | `put(key, value, ttl: Duration)` |
| `set(key, null)` / `setCache(key, MissingValue)` | 删除。改用 `invalidate(key)`，或让 loader 返回 `null` |
| `evict(key)` | `invalidate(key)`、`invalidateAll(keys)` |
| — | `get(key, loader)`：用调用方给出的 loader 进行受保护的加载 |
| `SetElementsCache.addElements` / `removeElements` | `SetCache.add` / `remove` / `clear` |
| `CacheValue`、`PresentValue`、`MissingValue`、`TtlAt`、`CacheClock` 单例 | 转为内部实现，或由 `java.time` 类型与可注入的 `CacheClock` 取代 |

`SetCache` 只用于唯一副本在 Redis 中的集合：它没有 loader，`invalidate` 会抛出异常。如果集合的数据在数据库中，声明 `Cache<K, Set<E>>` 并用失效维护。见[集合缓存](./caches.md#set-cache)。

## 组件 {#components}

| 5.x | 6.0 |
|-----|-----|
| bean 名约定 `{name}.ClientSideCache`、`{name}.DistributedCache`、`{name}.KeyConverter`、`{name}.CacheSource` | `CacheSpecCustomizer` bean 与 `cocache.caches.[name].*` 配置项 |
| 可替换的 L2（`ClientSideCache`、`MapClientSideCache`） | 删除：L2 固定为 Caffeine。容量用 `@CaffeineCache` 或 `local-maximum-size` 设置。测试中使用 `InMemoryBackend` 与 `ManualClock` |
| 可替换的 L1（`DistributedCache`）与 Redis 编码（`ObjectToJsonCodecExecutor`、`MapToHashCodecExecutor`、`SetToSetCodecExecutor` 等） | `CoherenceBackend` SPI。Redis 后端只有标量与集合两种形状，字节由 `Codec<V>` 决定。Hash 形状已删除 |
| `KeyConverter`、`ToStringKeyConverter` | `keyExpression`，或 spec 上的 `keyEncoder`。前缀与命名空间由 CoCache 添加 |
| `KeyFilter`、`BloomKeyFilter` | spec 上的 `keyFilter` 函数。自行包装布隆过滤器 |
| `CacheEvictedEventBus` | `InvalidationChannel`（`CoherenceBackend` 的一部分） |
| `ClientIdGenerator`（每个缓存一个 id） | 每个 `CacheRuntime` 一个 `NodeId` |
| `CoherentCacheFactory`、`CacheFactory` 等工厂 | `CacheSpec` + `CacheRuntime.create(spec)`，或 `CacheSpec` bean |

## JoinCache {#joincache}

JoinCache 仍然保留，位于 `me.ahoo.cocache.api.join`，但现在是**只读视图**，不再是 `Cache` 的子类型。见 [JoinCache](./join-cache.md)。

| 5.x | 6.0 |
|-----|-----|
| `JoinCache<K1, V1, K2, V2> : Cache<K1, JoinValue<…>>` | `JoinCache<K1, V1, K2, V2>`，不是 `Cache`：`name`、`get`、`getAll`、`invalidate(key)`、`invalidate(firstKey, joinKey)` |
| `set(key, joinValue)`、`getCache`、`getTtlAt` | 删除：直接写入、查看各组件缓存 |
| `evict(key)` / `evict(firstKey, joinKey)` | `invalidate(key)`（只失效主缓存）/ `invalidate(firstKey, joinKey)`（两者） |
| `JoinValue.joinKey: K2` | `joinKey: K2?`：`JoinKeyExtractor` 可以返回 `null` 表示没有关联 |
| 列在 `@EnableCoCache(caches = …)` 中 | 列在 `@EnableCoCache(joinCaches = …)` 中 |
| `firstCacheName` / `joinCacheName` 可选（为空时按类型唯一匹配缓存） | 必填：只按名称解析缓存 |
| 提取器 bean 名约定 `{name}.JoinKeyExtractor` | `@JoinCacheable(joinKeyExtractor = "beanName")`，否则按泛型 `JoinKeyExtractor<V1, K2>` 唯一匹配 |
| `joinKeyExpression` | 不变：以主值为根对象的 SpEL 模板，只支持 `String` 关联 key |
| 把 `@JoinCacheable` 用作元注解 | 删除：直接标注在接口上 |

## Spring Cache {#spring-cache}

- `CoCacheManager` 改为 `CoCacheSpringCacheManager`，由 `CacheRuntime` 构造。
- `@Cacheable(sync = true)` 现在经受 lease 与失效戳保护的路径加载。需要 CoCache 的一致性保证时请使用它：不带 `sync` 时，Spring 的无条件 `put` 可能覆盖失效。启动时，starter 会为每个指向 CoCache 缓存且没有 `sync = true` 的 `@Cacheable` 记录一条 `WARN`；设置 `cocache.spring-cache.require-sync: true` 则改为启动失败。
- `put(key, null)` 现在执行失效，而不是写入负缓存。
- 有 `spring-tx` 时 CacheManager 是事务感知的：事务内的 `@CacheEvict` 与 `@CachePut` 在提交之后执行。直接调用时使用 `TransactionalInvalidator`。

见 [Spring Cache](./spring-cache.md)。

## 配置项 {#properties}

已删除的 5.x 配置项会导致启动失败，错误信息给出替代项（如果有的话）。

| 5.x | 6.0 |
|-----|-----|
| `cocache.enabled` | `cocache.enabled` |
| `cocache.redis.strict-failure: true` | `cocache.defaults.read-failure: throw`（或在 `cocache.caches.[name]` 下按缓存设置）。6.0 中显式写入总是抛出 |
| `cocache.redis.eviction-channel: sharded` | `cocache.redis.channel-mode: sharded` |
| `cocache.redis.missing-guard-sentinel` | 删除：负缓存是值头中的状态字节，不再有哨兵 |
| `cocache.load.max-concurrent` | `cocache.defaults.load-max-concurrent` |
| `cocache.load.acquire-timeout` | `cocache.defaults.load-acquire-timeout` |
| `cocache.metrics.client-side-hits` | 删除：没有 L2 命中计数 |
| `cocache.next.*`（6.0 预览） | `cocache.*` |

新增：`cocache.defaults.*` 与 `cocache.caches.[name].*` 下的全部策略字段，包括 `key-prefix`（支持占位符，例如 `${spring.application.name}:`）；`cocache.redis.command-timeout`、`heartbeat`、`invalidate-batch-size`、`client-name`、`channel-mode`；`cocache.backend`（`redis` 或 `memory`）；以及 `cocache.spring-cache.require-sync`。见[配置](./configuration.md#properties)。

## Redis {#redis}

| | 5.x | 6.0 |
|-|-----|-----|
| 客户端 | Spring Data Redis，Lettuce 或 Jedis | 原生 Lettuce。CoCache 按 `spring.data.redis.*` 自建客户端。**不支持 Jedis**：需要引入 Lettuce（不影响应用自己的 Redis 客户端） |
| 拓扑 | 单机、Sentinel、Cluster | 单机、Sentinel、Cluster。静态主从启动失败 |
| 最低版本 | — | Redis 7.0+ 或 Valkey 7.2+。CI 测试单机 Valkey 8 |
| key | `keyPrefix` + key，默认前缀 `cocache:{name}:` | `{keyPrefix}{namespace}:{key}`，默认为 `cocache:{name}:{key}`。自定义 `keyPrefix: "user:"` 在 5.x 中得到 `user:42`，现在得到 `user:UserCache:42` |
| 值 | JSON 字符串、Hash 或 Set，带内哨兵 `_nil_` | 36 字节二进制头 + codec 字节。集合是带 `!f` 与 `+{member}` 字段的 Hash |
| TTL 精度 | 秒 | 毫秒 |
| 频道 | 缓存名，例如 `UserCache` | `cocache:inv:{namespace}`，例如 `cocache:inv:UserCache` |
| 消息 | 文本 `{key}@@{publisherId}` | 二进制帧（`InvalidationFrame`），见[运维](../operations/troubleshooting.md#external-invalidation) |
| 失效 | 先 `DEL` 再 `PUBLISH`（2 次往返） | 一个原子脚本（`pubsub` 模式） |

用 `DEL` + `PUBLISH {cacheName} key@@id` 失效的外部程序必须改用 [6.0 的协议](../operations/troubleshooting.md#external-invalidation)。

## Actuator {#actuator}

| 5.x | 6.0 |
|-----|-----|
| `GET /actuator/cocache` | `GET /actuator/cocache`：缓存名、L2 条目数、加载统计与全部计数器 |
| `GET /actuator/cocache/{name}` | `GET /actuator/cocache/{name}` |
| `GET /actuator/cocache/{name}/{key}`（未命中时回源） | `GET /actuator/cocache/{name}/{key}`：查看该 key 的 L2 副本与 L1 状态，**不回源**。`{key}` 是编码后的 key |
| `DELETE /actuator/cocache/{name}/{key}` | `DELETE /actuator/cocache/{name}/{key}`：在所有实例上失效该 key，与 `invalidate(key)` 相同 |
| `cocacheClient` 端点（`GET /{name}`、`GET /{name}/{key}`、`DELETE /{name}`） | 删除。`DELETE /actuator/cocache/{name}` 清空本实例的 L2 |
| — | 新增健康检查 `cocache`：Redis 订阅连接心跳失败时为 `DOWN` |

从 `management.endpoints.web.exposure.include` 中去掉 `cocacheClient`。见[可观测性](../operations/observability.md#actuator-endpoint)。

## 指标 {#metrics}

所有指标都保留 `cache` 标签。

| 5.x | 6.0 |
|-----|-----|
| `cocache.hits{level=l2}` | 删除 |
| `cocache.hits{level=l1}` | `cocache.hits{level=l1}` |
| `cocache.misses` | `cocache.misses` |
| `cocache.loads`（timer） | `cocache.loads`（function timer） |
| `cocache.load.failures` | `cocache.load.failures` |
| `cocache.load.rejections` | `cocache.load.rejections` |
| `cocache.filtered` | `cocache.filtered` |
| `cocache.stale.discards` | `cocache.stale.discards` |
| `cocache.evictions.received` | `cocache.invalidations.received` |
| `cocache.resets` | `cocache.resets`（也计入心跳失败） |
| `cocache.refreshes` | `cocache.refreshes` |
| `cocache.l2.size` | `cocache.local.size` |
| `cocache.eviction.publish.failures`（无 `cache` 标签） | 删除。失效与广播在同一个脚本中完成，失败计入 `cocache.write.failures`，最终失败计入 `cocache.invalidations.dropped` |

新增：`cocache.lease.waits`、`cocache.lease.wait.timeouts`、`cocache.fill.rejections`、`cocache.degraded.fills`、`cocache.reclaims`、`cocache.unknown.formats`、`cocache.refresh.rejections`、`cocache.remote.failures`、`cocache.write.failures`、`cocache.invalidation.retries`、`cocache.invalidations.dropped`。见[运维](../operations/observability.md#metrics)。

## 行为变化 {#semantics}

- **L2 副本总是受 `localMaxAge` 限龄，默认 5 分钟。** 5.x 中 `clientSideTtl = 0`（默认值）让 L2 副本一直存活到 L1 到期，事件丢失时可能陈旧长达 `ttl`。6.0 没有“不限制”的设置：`localMaxAge` 就是事件丢失时的陈旧度上界。可以按需设得很长（最多 100 年），但它总是有限值。
- **提前刷新全集群只执行一次**，而不是每个实例一次：只有在 Redis 中抢到刷新令牌的节点重新加载。
- **并发未命中跨实例合并**，经 L1 lease 实现，前提是 loader 在 `lease-wait`（默认 200 ms）内完成。
- **旁路缓存竞态已消除。** 5.x 中，慢加载可能在并发失效之后把旧值写入 Redis，只能靠 `ttl` 收敛。6.0 中每次填充都要持有加载前取得的 lease，而失效会销毁它。
- **`put` 不再写 L2。** 该实例的下一次读取从 Redis 填充 L2。
- **显式写入会抛出异常。** Redis 失败时，`put`、`add`、`remove`、`clear` 抛出 `CacheWriteException`。5.x 除非设置了 `strict-failure`，否则只记录告警。
- **失败的失效会在后台重试**，而不只是记录日志；重试成功之前，这些 key 的未命中跳过 L1。
- **`ttlJitter` 不超过 `ttl / 2`**，每个策略时长不超过 100 年。
- **校验更严格。** `refreshWindow` 必须小于 `ttl − ttlJitter`，并且小于 `localMaxAge`。`cocache.caches` 下出现未知的缓存名时启动失败。
