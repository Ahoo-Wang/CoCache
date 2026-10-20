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

- **有数据源的缓存**：默认布局下，6.x 的 key 与 5.x 的 key 都是 `cocache:{cacheName}:{key}`，所以 6.x 会读到旧值。任何 5.x 的值（String、Hash 或原生 Set，包括 `_nil_` 哨兵）都会被识别为外来内容（格式 0），仅在未被修改时回收，然后从数据源重新加载，无需手工清理。
- **没有数据源的缓存，以及所有 5.x 的 `SetElementsCache`**：Redis 中是唯一副本，没有可以重新加载的地方。**在 6.x 启动之前迁移这些 key**，见 [Redis 是唯一副本的缓存](#redis-as-store)。从 6.5 起，6.x 读到未迁移的 key 时读取失败，而不是返回“不存在”。
- 要立即释放内存，按前缀删除旧 key（`SCAN` 配合 `MATCH`，再 `UNLINK`）。

`redis-cli GET` 不再显示 JSON：每个值都以 36 字节的二进制头开始。直接从 Redis 读取 CoCache key 的程序（例如对 Hash 编码的缓存执行 `HGET`）不再受支持。

## Redis 是唯一副本的缓存 {#redis-as-store}

本节适用于没有数据源（5.x 中没有 `CacheSource`）、以 Redis 为权威存储的缓存，以及所有 5.x 的 `SetElementsCache`。只存放在 Redis 中的授权数据是典型的例子。这些缓存没有可以重新加载的地方，6.x 不能丢弃 5.x 写入的内容：在任何 6.x 实例接收流量之前，用 `CoCache5Migrator` 转换数据。

### 不迁移：读取失败 {#foreign-format}

没有数据源的读取遇到 6.x 读不懂的内容时，抛出 `CacheReadException`，其 `cause` 为 `ForeignFormatException`。这类内容包括 5.x 的值、原生 Set 或 Hash、`_nil_` 哨兵，以及编解码器无法解码的值。key 在 Redis 中原样保留，这次读取计入 `cocache.unknown.formats`。因此漏跑迁移的结果是读取失败（fail closed），而不是回答“不存在”。`read-failure: degrade` 时同样如此。

6.4 及以前，同样的读取会回收这个 key 并返回 `null`。没有数据源时，回收就是删除唯一副本。`SetCache` 读到空集合，第一次 `add` 会替换旧成员。

`foreign-format` 策略（`cocache.caches.[name].foreign-format`，或 `CachePolicy.foreignFormat`）决定行为：

| 取值 | 行为 | 默认用于 |
|------|------|----------|
| `fail` | 抛出 `CacheReadException`，Redis 与 L2 都不改动 | 没有数据源的读取：`SetCache`，以及没有 loader 的 `Cache` |
| `reclaim` | key 未变时回收，然后重新加载。`SetCache` 读到空集合，内容保留到下一次写入覆盖 | 有数据源的读取，包括对没有 loader 的缓存调用 `get(key, loader)` |

`cocache.defaults.foreign-format` 不作用于 `SetCache`。写入不受保护：`put` 会替换这个 key，对未迁移的原生 Set 调用 `SetCache.add` 会替换整个集合。在 6.x 接收写入之前完成迁移。

### 用 `CoCache5Migrator` 迁移 {#cocache5-migrator}

`CoCache5Migrator`（`cocache-redis` 模块，6.5 起）把 5.x 的 key 转换为 6.x 格式，覆盖 5.x 的全部值格式（5.0 到 5.4 的线格式相同）：

| 5.x `CodecExecutor` | Redis 类型 | 6.x 缓存 | 迁移 |
|---------------------|------------|----------|------|
| `ObjectToJsonCodecExecutor`（默认）、`StringToStringCodecExecutor` | string | `Cache<K, V>` | `scalar(spec, CoCache5Format.string())`：用 6.x 的编解码器解码 5.x 的字节。`ObjectMapper` 设置相同的 `JacksonCodec`、以及 `StringCodec` 与 5.x 的字节相同；否则用 `string { text -> … }` |
| `SetToSetCodecExecutor` | set | `SetCache<K, E>` | `set(spec)`：成员用元素编解码器解码再编码 |
| `SetToSetCodecExecutor` | set | `Cache<K, Set<String>>` | `scalar(spec, CoCache5Format.set { it })` |
| `MapToHashCodecExecutor`、`ObjectToHashCodecExecutor` | hash | `Cache<K, V>` | `scalar(spec, CoCache5Format.hash { it })`，或 `hash(mapConverter::asValue)` |
| 负缓存（`_nil_`） | 以上任一 | 6.x 负缓存 | 自动处理。5.x 使用了其它哨兵时设置 `CoCache5MigrationOptions.missingGuardSentinel` |

每个 key 的剩余寿命保持不变，没有寿命的 key 迁移后仍然没有。

```kotlin
// 与应用声明的 spec 相同（或与其 @CoCache 接口对应的 spec）
val policySpec = cacheSpec<String, Policy>("PolicyCache") {
    codec = jacksonCodec<Policy>(objectMapper)
    policy { ttl = null }
}
val indexSpec = setCacheSpec<String, String>("GlobalIndexCache") { elementCodec = StringCodec }

val migrations = listOf(
    // 5.x 的 key "app:policy:{id}" → 6.x 的 key "cocache:PolicyCache:{id}"
    CoCache5Migration.scalar(policySpec, CoCache5Format.string()).sourcePrefix("app:policy:").build(),
    // 一个固定的 5.x key → 6.x SetCache 的 key ""
    CoCache5Migration.set(indexSpec).sourceKey("app:global-index", "").build(),
)

CoCache5Migrator.standalone(RedisURI.create("redis://localhost:6379")).use { migrator ->
    println(migrator.dryRun(migrations))   // 只读取与解码
    println(migrator.migrate(migrations))  // 所有写入方已停止
}
```

- **目标**：`scalar(spec, format)` 与 `set(spec)` 从 6.x 的 spec 取 key 前缀、命名空间与编解码器，迁移结果正是应用读取的格式。传入应用使用的 spec，并应用 `cocache.caches.[name].key-prefix` 等配置。没有 spec 时，用 `scalar(name, codec, format)` 或 `set(name, elementCodec)`，再设置 `keyPrefix(…)` 与 `namespace(…)`。
- **来源**：默认扫描 5.x 的默认前缀 `cocache:{name}:`。用 `sourcePrefix(…)` 修改前缀，或用 `sourceKey(key, encodedKey)` 列出 key，例如 5.x 的 `KeyConverter` 把缓存映射到固定 key 时。6.x 的 key 是 `{keyPrefix}{namespace}:` 加上去掉前缀的 5.x key；5.x 与 6.x 的 key 编码不同时用 `keyMapper(…)`。
- **客户端**：`CoCache5Migrator.standalone(uri)`、`cluster(uris)`（扫描全部主节点），或用 `of(client)` 传入自己的 `RedisClient` / `RedisClusterClient`。

步骤：

1. **检查**。对生产 Redis 运行 `dryRun(migrations)`。它只读取，报告 `migrate` 将要做的事，遇到第一个无法解码的 key 即失败。
2. **停止所有写入方**：写这些 key 的全部 5.x 与 6.x 实例，直到第 4 步。原地写入前会比较 key 的 `DUMP`，key 变了就中止，但无法发现所有并发写入。
3. **迁移**。`migrate(migrations)` 按缓存返回报告：`read`、`written`（含 `negatives`）、`skipped`（已是 6.x 格式，或期间被删除）、`failed` 与 `backedUp`。遇到第一个无法解码、或 Redis 类型与配置的格式不符的 key 时，以 `CoCache5MigrationException` 中止，异常带有该 key 与到此为止的报告；该 key 保持原样。修正原因后重新执行，已迁移的 key 会被跳过。
4. **启动 6.x** 并验证。
5. **删除备份**：`dropBackups(migrations)`。之后不能再回滚。

### 回滚 {#migration-rollback}

`migrate` 保留什么，取决于每个 key 写到哪里：

- **原地**：6.x 的 key 与 5.x 的 key 相同，默认布局即如此。覆盖之前，迁移器用 `RESTORE` 把 5.x 的值复制到 `cocache5-backup:{5.x key}`（`CoCache5MigrationOptions.backupPrefix`），类型、内容与剩余寿命不变。
- **写到新 key**：例如 5.x 使用了自定义前缀，或给 6.x 的 spec 设置了新的 `keyPrefix`。5.x 的 key 不被改动，它本身就是备份。6.x 的 key 必须事先不存在。

回滚时再次停止所有写入方，调用 `rollback(migrations)`，然后重新部署 5.x。它恢复每个备份（然后删除备份），并删除写到新 key 的 6.x 数据。迁移之后 6.x 写入的数据会丢失。`rollback` 可以重复执行。

默认原地迁移并备份，因为它保持现有的 key 布局，不需要修改配置；备份在 `dropBackups` 之前多占一份数据的内存。要让 5.x 数据完全不被改动，给 6.x 的 spec 设置新的 `keyPrefix`：回滚时只需删除新 key。

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
| `cocache.metrics.client-side-hits` | `cocache.metrics.local-hits`（6.3；6.0 与 6.1 中没有） |
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
| `cocache.hits{level=l2}` | `cocache.hits{level=l2}`，需 `cocache.metrics.local-hits=true`（6.3；6.0 与 6.1 中没有） |
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
