---
title: 配置参考
description: 所有 cocache.* 配置项的类型、默认值与单位；全局默认值、声明、定制器、全局覆盖与按缓存配置如何逐字段合并；哪些配置项适用于哪种缓存类型；供库作者使用的锁定；Redis 配置与驱逐检查；生效策略报告；启动校验；以及只能用代码配置的部分。
---

# 配置参考

## 配置如何叠加 {#precedence}

每个缓存的策略由六层逐字段合并而成。高层覆盖低层；某层没有设置的字段原样传下去：

| 层（由低到高） | 配置 | 作用 |
|----------------|------|------|
| 1. 类型默认值 | `DefaultPolicies.CACHE`、`VERSIONED_CACHE`、`STORE`、`VERSIONED_STORE`、`SET_STORE` | 每种类型各有一份：`Store` 不会继承派生缓存 1 小时的 `ttl`（见[类型默认值](./caches.md#store-defaults)） |
| 2. 全局默认值 | `cocache.defaults.cache.*`、`cocache.defaults.store.*` | **低于声明**：只填充声明中[没有写出](#declared-fields)的字段 |
| 3. 声明 | `@CoCache` / `@CaffeineCache`，或你构建的 spec | 库或应用的意图 |
| 4. 定制器 | `CacheSpecCustomizer` bean，按 Spring 的顺序 | 可以修改策略字段与不决定类型的字段，不能修改数据来源（见[定制器](./caches.md#customizer)） |
| 5. 全局覆盖 | `cocache.overrides.cache.*`、`cocache.overrides.store.*` | **高于声明，只收紧**：陈旧界限取最小值，有严格序的模式（`read-failure`、`fill-in-transaction`、`invalidate-fills-on-rollback`）取更严的值；其余都不允许（见[各层能设置什么](#field-layers)） |
| 6. 单个缓存 | `cocache.caches.[name].*` | 设置任何[适用于](#applicability)该缓存的字段，另有 `unknown-read`、`key-prefix` 与 `unlock` |

合并之后执行库的[约束](#locks)，再执行[校验连锁](#validation)。`cache` 一节作用于派生类型（`Cache`、`VersionedCache`），`store` 一节作用于权威类型（`Store`、`VersionedStore`、`SetStore`）：

```yaml
cocache:
  defaults:
    cache:
      local-max-age: 2m        # 没有自己声明的派生缓存都用它
    store:
      local-max-age: 10s       # 没有自己声明的权威缓存都用它
  overrides:
    cache:
      ttl: 30m                 # 事故中：派生缓存的值都不超过 30 分钟
  caches:
    "[UserCache]":
      load-max-concurrent: 32
```

默认值低于声明，因为声明最了解数据：声明了 `readFailure = THROW` 或较短 `ttl` 的库，无论全局默认值写什么都保持不变。覆盖高于声明，运维无需重新构建就能处处限定陈旧，但它只能收紧，因此永远不会削弱声明的选择。

各层作用于经 [`CoCacheFactory`](./caches.md#factory) 创建的每个缓存：`@CoCache` 接口、spec bean，以及使用 starter 的工厂 bean 的库。直接用 `CacheRuntime.create` 创建的缓存是*未分层*的：任何一层都到不了它，启动日志与端点会标注出来。

全局层从不因为某一个缓存而让启动失败。不适用于缓存类型、该层不允许或被[锁定](#locks)的配置项，对这个缓存跳过：启动日志为它记录一行 `WARN`，端点把它列在 [`skippedConfiguration`](#policy-report) 中。

### 声明写出了哪些字段 {#declared-fields}

`cocache.defaults` 只填充声明没有写出的字段，所以“写出”的含义很重要：

- **注解**：每个不是 `CoCache.UNSET` 的 `@CoCache` 属性。设置了 `maximumSize` 的 `@CaffeineCache` 写出 `local-maximum-size`。
- **spec**：`policy { … }`（Java：`updatePolicy(…)`）中赋值的每个字段，以及赋值了的 `unknownRead`。写出哨兵值也算：`ttl = forever()` 是写出的，`defaults.cache.ttl` 因此不作用于它。给嵌套的策略赋值（`lease = LeasePolicy(…)`、`load = LoadPolicy(…)`、`local = LocalPolicy(…)`）只写出值有变化的字段：`lease = LeasePolicy(ttl = Duration.ofSeconds(30))` 写出 `lease-ttl`，`lease-wait` 仍交给 `cocache.defaults`。
- **整体策略**：`policy = somePolicy`（Java：`policy(CachePolicy)`）写出全部字段。`cocache.defaults` 于是对这个缓存不生效，每个被跳过的配置项都带着原因 `the declaration sets the whole policy` 列出。

除非有意固定每个字段，请用 `policy { … }` 修改类型默认值。

### 各层能设置什么 {#field-layers}

| 字段 | 类别 | `defaults` | `overrides` | `caches.[name]` |
|------|------|------------|-------------|-----------------|
| `Cache` 的 `ttl` | 陈旧界限 | 设置 | 取最小值，并限制按调用给出的寿命（见下） | 设置 |
| `local-max-age`、`degraded-local-max-age`、`absent-ttl` | 陈旧界限 | 设置 | 取最小值 | 设置 |
| `read-failure` | 模式，`throw` 严于 `degrade` | 设置 | 取两者中更严的 | 设置 |
| `fill-in-transaction` | 模式，`private` 严于 `registered-keys` 严于 `allow` | 设置 | 取两者中更严的 | 设置 |
| `invalidate-fills-on-rollback` | 模式，`true` 严于 `false` | 设置 | 取两者中更严的 | 设置 |
| `invalidate-failure` | 没有严格序的模式 | 设置 | 不允许 | 设置 |
| `lease-wait`、`load-timeout`、`load-acquire-timeout`、`read-timeout` | 等待界限 | 设置 | 不允许 | 设置 |
| `VersionedCache` 的 `ttl`、`tombstone-ttl` | 版本栅栏的寿命 | 设置 | 不允许 | 设置 |
| `Store`、`VersionedStore`、`SetStore` 的 `ttl` | 寿命即数据的语义 | 不允许 | 不允许 | 设置 |
| `ttl-jitter`、`refresh-window`、`lease-ttl`、`load-max-concurrent`、`local-maximum-size` | 其余 | 设置 | 不允许 | 设置 |
| `unknown-read` | 模式 | 不允许 | 不允许 | 设置 |
| `key-prefix`、`unlock` | 不是策略 | 不允许（用 [`cocache.key-prefix`](#key-prefix)） | 不允许 | 设置 |

覆盖之所以这么窄：取更小的值只对陈旧界限是收紧。调短等待界限会让更多实例绕过 lease 自行加载，让本已变慢的数据源承受成倍的负载。带版本类型的 `ttl` 与 `tombstone-ttl` 还限定重复投递的旧事件被拒绝多久，调短它们会让旧事件重新写入。直接设置模式可能放宽下层更严的值，所以只有有严格序的模式参与：`read-failure` 与两个[事务模式](./spring-cache.md#transactional-loads)。

**`overrides.cache.ttl` 还限制按调用给出的寿命**：`ttlOf` 的结果，以及传给 `put(key, value, ttl)` 与 `putAll(entries, ttl)` 的 `ttl`。更长的寿命被钳制到覆盖值，每次钳制计入 `cocache.ttl.clamped`，每个缓存第一次钳制时记录一行 `WARN`。即使缓存自己的 `ttl` 已经短于覆盖值，这个上限照样生效。`cocache.caches.[name].ttl` 更长时把这个缓存的上限抬到它，写 `ttl: forever` 时取消上限，因此按缓存的 `ttl` 不会被它自己的按调用上限截短。

## 顶层配置项 {#properties}

| 配置项 | 类型 | 默认值 | 含义 |
|--------|------|--------|------|
| `cocache.enabled` | boolean | `true` | `false` 关闭 CoCache 的全部自动配置，包括端点、健康检查与指标 |
| `cocache.backend` | `redis` \| `memory` | `redis` | `redis`：由 `spring.data.redis.*` 构建。`memory`：进程内、单实例，用于开发与测试。自己声明的 `CoherenceBackend` bean 优先于两者。 |
| `cocache.key-prefix` | string | 未设置（沿用声明，默认 `cocache:`） | 每个存储 key 的开头，不参与分层（见[下文](#key-prefix)） |
| `cocache.defaults.cache.*`、`cocache.defaults.store.*` | 策略 | — | 派生缓存与权威缓存的全局默认值，低于声明（见[策略配置项](#policy-properties)） |
| `cocache.overrides.cache.*`、`cocache.overrides.store.*` | 策略 | — | 只收紧的全局覆盖（见[各层能设置什么](#field-layers)） |
| `cocache.caches.[name].*` | 策略 | — | 单个缓存的[策略配置项](#policy-properties)，另有 `unknown-read`、`key-prefix` 与 `unlock`（见[单个缓存](#per-cache)） |
| `cocache.caches-validation` | `fail` \| `warn` | `fail` | `cocache.caches` 中名称对应不上任何缓存的条目，或设置了不适用于该缓存的配置项的条目，如何处理（见[校验](#validation)） |
| `cocache.redis.*` | — | — | [Redis 后端](#redis)配置 |
| `cocache.spring-cache.require-sync` | boolean | `false` | `true`：CoCache 缓存上的 `@Cacheable` 缺少 `sync = true` 时启动失败。`false`：只记录告警。见 [Spring Cache](./spring-cache.md#sync-check)。 |
| `cocache.spring-cache.degrade-on-error` | boolean | `false` | `true`：Spring 的缓存拦截器改用 `DegradingCacheErrorHandler`，CoCache 缓存的读写失败只记录日志、不重新抛出，Redis 故障期间不带 sync 的 `@Cacheable` 因此执行方法。失败的失效、其它缓存的失败仍交给原来的处理器。见 [Spring Cache](./spring-cache.md#failures)。 |
| `cocache.refresh-executor` | bean 名 | 内置 | 执行[提前刷新](./freshness.md#refresh-ahead)回源的 `Executor` bean。见[执行器](#executors)。 |
| `cocache.executor` | bean 名 | 内置 | 执行[异步](./async.md)与[挂起](./coroutines.md)视图的 `Executor` bean：它们的续接、共享加载，以及经它们调用的同步 loader。见[执行器](#executors)。 |
| `management.health.cocache.enabled` | boolean | `true` | 注册 `cocache` 健康检查（见[下文](#actuator)） |
| `cocache.observation.enabled` | boolean | `true` | 有 `ObservationRegistry` bean 时把回源包在 Micrometer `Observation` 中。见[链路追踪](../operations/observability.md#tracing)。 |
| `cocache.metrics.local-hits` | boolean | `false` | 为全部缓存记录 L2 命中数（`cocache.hits{level=l2}`，端点字段 `localHits`）。默认关闭：它给每次 L2 命中加一次原子自增。见[指标](../operations/observability.md#metrics-reads)。 |

## 策略配置项 {#policy-properties}

以下配置项可以写在 `cocache.defaults.cache.`、`cocache.defaults.store.`、`cocache.overrides.cache.`、`cocache.overrides.store.` 与 `cocache.caches.[name].` 之下，以[各层允许](#field-layers)且[该类型有这个字段](#applicability)为限。

| 配置项 | 取值 | 类型默认值 | 对应注解 | 含义 |
|--------|------|------------|----------|------|
| `ttl` | 时长或 `forever` | `1h`；权威类型：`forever` | `@CoCache.ttl` | 值在 Redis 中的寿命 |
| `ttl-jitter` | 时长 | `1m` | `@CoCache.ttlJitter` | 每个值寿命上的随机 ± 抖动，不超过 `ttl / 2`。`0s`：关闭。 |
| `absent-ttl` | 时长 | `1m`；权威类型：`10s` | `@CoCache.absentTtl` | 负缓存的寿命 |
| `local-max-age` | 时长 | `5m`；权威类型：`30s` | `@CoCache.localMaxAge` | L2 副本的最长寿命：失效消息丢失时陈旧的上界 |
| `degraded-local-max-age` | 时长 | `10s` | — | 无法写入 Redis 的 L2 副本的最长寿命 |
| `tombstone-ttl` | 时长或 `forever` | 与值的寿命相同；`VersionedStore`：`1d` | — | 带版本的墓碑与淘汰标记的寿命，必须覆盖重复投递的时间窗口。`VersionedStore` 必须是有限值。 |
| `refresh-window` | 时长或 `off` | `off` | `@CoCache.refreshWindow` | 提前刷新窗口。`off`（或 `0s`）也会关掉声明中开启的窗口，见[校验](#validation)。 |
| `lease-ttl` | 时长 | `10s` | — | 持有者一直不写回时 lease 的寿命，应大于 loader 的 p99 |
| `lease-wait` | 时长 | `200ms` | — | 未命中时等待其它实例加载的最长时间，超时后自行加载 |
| `load-max-concurrent` | 整数或 `unlimited` | `unlimited` | — | 每个实例上每个缓存同时进行的数据源加载数上限 |
| `load-acquire-timeout` | 时长 | `1s` | — | 加载等待许可的最长时间，超时抛出 `SourceLoadRejectedException` |
| `load-timeout` | 时长 | `30s` | — | 一次加载的上限。`AsyncCacheLoader` 的一次加载超过它时以 `SourceLoadTimeoutException` 失败并释放 lease；等待别的调用方加载的读取，在这次加载进行了这么久之后同样失败，之后的读取开始新的加载，不再排在它后面。同步 loader 无法被外部结束，请给它自己的超时。见[异步 API](./async.md#timeouts)。 |
| `read-timeout` | 时长 | `cocache.redis.command-timeout` | — | 缓存每次 Redis 读取（`get`、`getAll`、`getIfChanged`）的时限，以 `command-timeout` 为上限。超时按 Redis 读取失败处理：有数据源的读取由 `read-failure` 决定，没有数据源的读取为 [`Lookup.Unknown`](./caches.md#unknown)。用于不能回退到数据源、又不能在 Redis 卡顿时等满 `command-timeout` 的缓存。 |
| `local-maximum-size` | 整数 | `10000` | `@CaffeineCache.maximumSize` | 每个实例 L2 的最大条目数 |
| `invalidate-failure` | `retry` \| `throw` | `retry` | — | `Cache.invalidate` 失败时：进入后台重试队列，或抛出 `CacheWriteException`。删除派生副本总是安全的，所以默认重试，外部来源的缓存也一样。权威类型的删除总是抛出、从不重试。见[写入时 Redis 故障](./freshness.md#redis-down)。 |
| `read-failure` | `degrade` \| `throw` | `degrade` | — | 有数据源（声明的 loader 或调用级 loader）的读取在 Redis 失败时：从数据源加载，或回答 [`Lookup.Unknown`](./caches.md#unknown)，于是 `get` 抛出 `CacheReadException` |
| `fill-in-transaction` | `registered-keys` \| `private` \| `allow` | `registered-keys` | — | 需要 `spring-tx`。读写事务内哪些回源是私有的（不申请 lease、不写 Redis 与 L2、不共享）：本事务已登记失效的 key、全部回源，或都不是。见 [Spring Cache](./spring-cache.md#transactional-loads)。 |
| `invalidate-fills-on-rollback` | boolean | `true` | — | 需要 `spring-tx`。读写事务内同步读取回源、写入 Redis 或 L2 的 key，在事务回滚、提交结果未知或回滚到 savepoint 时失效。它为 `false` 且 `fill-in-transaction: allow` 时，这个缓存的回源不感知事务。 |
| `unknown-read` | `throw` \| `absent` | `throw` | — | 只能写在 `cocache.caches.[name]` 中。没有数据源的读取在 Redis 失败时：`throw` 让 `get` 抛出 `CacheReadException`；`absent` 读作不存在，且什么也不缓存。见[未知的读取](./caches.md#unknown)。 |
| `key-prefix` | string | `cocache.key-prefix` | `@CoCache.keyPrefix` | 只能写在 `cocache.caches.[name]` 中。这个缓存的存储 key 前缀（见[下文](#key-prefix)）。 |
| `unlock` | 配置键的列表 | — | — | 只能写在 `cocache.caches.[name]` 中。这个条目有意覆盖的、被库[锁定](#locks)的字段。 |

缓存如何对待读不懂的内容不可配置：有数据源的读取回收它并重新加载，没有数据源的读取失败（见[无法读取的载荷](../operations/troubleshooting.md#corrupt-payloads)）。

如何选择这些值，见[保持数据新鲜](./freshness.md#lifetimes)。

### 哪些配置项适用于哪种类型 {#applicability}

| 配置项 | 有 loader 的 `Cache` | 外部来源的 `Cache` | `VersionedCache` | `Store` | `VersionedStore` | `SetStore` |
|--------|:-:|:-:|:-:|:-:|:-:|:-:|
| `ttl`、`ttl-jitter`、`absent-ttl`、`local-max-age`、`read-timeout`、`local-maximum-size` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `degraded-local-max-age`、`lease-ttl`、`lease-wait`、`load-max-concurrent`、`load-acquire-timeout`、`load-timeout`、`invalidate-failure`、`read-failure`、`fill-in-transaction`、`invalidate-fills-on-rollback` | ✓ | ✓ | ✓ | — | — | — |
| `refresh-window` | ✓ | — | ✓ | — | — | — |
| `tombstone-ttl` | — | — | ✓ | — | ✓ | — |
| `unknown-read` | — | ✓ | — | ✓ | ✓ | — |

外部来源的 `Cache` 没有可以提前刷新的数据源，但它带调用级 loader 的读取（`get(key, loader)`、Spring Cache 的 `sync = true`）会加载，所以 lease、加载、`read-failure` 与事务的配置项适用于它。权威类型完全没有数据源。`SetStore` 的读取失败总是抛出，所以它没有 `unknown-read`。

### 时长与哨兵值 {#durations}

CoCache 配置中的每个时长都必须带单位，包括 `cocache.redis.*`：`500ms`、`5s`、`2m`、`1h`、`1d`，或 ISO-8601 写法如 `PT2M`。不带单位的数字让启动失败，错误信息指明配置键：

```
cocache.defaults.cache.ttl: "60" needs a unit, e.g. 60s, 5m, PT2M.
cocache.redis.command-timeout: "1500" needs a unit, e.g. 1500ms, 5m, PT2M.
```

“永不”与“关闭”各有自己的词，没有哪个数字带着隐含的意思：

| 哨兵值 | 配置项 | 含义 |
|--------|--------|------|
| `forever` | `ttl`、`tombstone-ttl` | 永不过期。取最小值时任何有限寿命都优先于它。 |
| `off`（或 `0s`） | `refresh-window` | 不提前刷新 |
| `unlimited` | `load-max-concurrent` | 不限制。`0` 会失败。 |

- 除 `refresh-window: 0s` 之外，只有 `ttl-jitter`、`lease-wait` 与 `load-acquire-timeout` 可以为 `0s`；其它时长都必须为正。任何时长都不能为负，也不能超过 100 年。
- `load-max-concurrent` 与 `local-maximum-size` 必须是正整数。
- 值在配置绑定时解析，所以写错的值即使没有缓存用到它也会让启动失败。
- **注解仍按秒**：`@CoCache(ttl = 600)` 是 10 分钟。注解属性是由编译器检查的数字，`UNSET` 与 `FOREVER` 是它们的哨兵值。

### 单个缓存 {#per-cache}

```yaml
cocache:
  caches:
    "[UserCache]":
      ttl: 10m
      load-max-concurrent: 32
      read-failure: throw
    "[RevokedTokens]":
      unknown-read: absent      # Redis 故障：视为“未撤销”
      read-timeout: 200ms
```

- 缓存名不区分大小写匹配，所以环境变量形式也可以：`COCACHE_CACHES_USERCACHE_TTL=10m`。
- 在 YAML 中给缓存名加上引号与方括号（`"[UserCache]"`），保持原样。
- 条目是最后一层：无论声明、定制器与覆盖写了什么，它都设置字段，被[锁定](#locks)的字段除外。
- 名称对应不上任何缓存，或配置项不[适用](#applicability)于该缓存时，默认启动失败（见[校验](#validation)）。

### key 前缀 {#key-prefix}

存储 key 是 `{keyPrefix}{namespace}:{encodedKey}`（见[声明缓存](./caches.md#storage-keys)）。统一设置前缀，可以隔离共用一个 Redis 的多个应用：

```yaml
cocache:
  key-prefix: ${spring.application.name}:
  caches:
    "[SharedIndexCache]":
      key-prefix: "shared:"
```

`cocache.key-prefix` 不是策略字段，不参与分层：配置优先于声明与定制器，包括 `@CoCache` 中显式写出的 `keyPrefix`。`cocache.caches.[name].key-prefix` 优先于 `cocache.key-prefix`。[锁定](#locks)了 `KEY_PREFIX` 的库保持自己的前缀：`cocache.key-prefix` 对这个缓存跳过，按缓存的 `key-prefix` 让启动失败，除非条目在 `unlock` 中列出 `key-prefix`。

同一应用的所有实例必须使用相同的前缀，修改前缀相当于冷启动。失效频道 `{keyPrefix}inv:{namespace}` 带有前缀，前缀不同的应用收不到彼此的消息。前缀可以为空：这时 key 与频道没有公共前缀，Redis ACL 要逐个列出命名空间（见 [Redis ACL](../operations/index.md#acl)）。`cocache.defaults.cache.key-prefix` 以及其它全局层中的前缀不生效：它们被跳过，并指向 `cocache.key-prefix`。

## 锁定字段（供库作者） {#locks}

正确性依赖某个字段的库，例如必须 fail closed 并保持存储 key 不变的撤销表，经 `CoCacheFactory` 创建缓存并锁定这些字段。配置于是不会意外地削弱它们：

```kotlin
@Bean
fun revokedTokens(factory: CoCacheFactory, mapper: ObjectMapper): Store<String, Instant> =
    factory.create(storeSpec<String, Instant>("revoked-tokens") {
        codec = jacksonCodec<Instant>(mapper)
        unknownRead = UnknownReadMode.ABSENT                 // Redis 故障：视为“未撤销”
        policy {
            ttl = Duration.ofHours(1)
            readTimeout = Duration.ofMillis(200)
        }
    }) {
        lock(PolicyField.UNKNOWN_READ, PolicyField.READ_TIMEOUT)
        lockLayout(LayoutField.KEY_PREFIX, LayoutField.NAMESPACE)
        constrain(PolicyField.TTL) { it.finiteOr(Duration.ofHours(1)) }   // 条目一定过期
        verify { spec -> require(spec is StoreSpec<*, *>) }
    }
```

| 块中的调用 | 作用 |
|------------|------|
| `lock(vararg PolicyField)` | 该字段报告为 `LOCKED`（`locked by …`）。全局层对这个缓存跳过它（启动日志与 `skippedConfiguration` 中标注 `locked by …`）。设置它的 `cocache.caches.[name]` 条目让启动失败，除非在 `unlock` 中列出它。修改它的定制器抛出异常。 |
| `lockLayout(vararg LayoutField)` | 对存储布局同样如此：`NAMESPACE`、`KEY_PREFIX`、`KEY_ENCODER`、`CODEC`、`KEY_FILTER`、`TTL_OF`、`SOURCE`。锁定的 `KEY_PREFIX` 不受 `cocache.key-prefix` 影响。 |
| `constrain(field) { value -> … }` | 在合并之后、校验之前作用于生效值。值可能是哨兵值（`Ttl.Forever`、`RefreshWindow.Off`、`Concurrency.Unlimited`），所以意图是“一定过期”时写 `it.finiteOr(…)`，而不是判空。被改变的值报告为 `LOCKED`（`constrained by …`）。 |
| `verify { spec -> … }` | 检查生效的 spec；失败时创建失败 |

日志与错误信息中的锁定者默认是调用方的类；在块中设置 `by` 换成库的名称。Java 中构建锁定再传入：`factory.create(spec, Locks.builder("my-lib").lock(PolicyField.READ_TIMEOUT.INSTANCE).build())`。

**破窗开关**。事故中运维可以有意覆盖被锁定的字段：

```yaml
cocache:
  caches:
    "[revoked-tokens]":
      read-timeout: 1s
      unlock: [read-timeout]
```

启动日志告警，端点把该字段报告为 `UNLOCKED`。`unlock` 接受配置键，包括 `key-prefix`。

直接用 `CacheRuntime.create` 创建的缓存无法锁定，因为任何一层都到不了它。库应经 `CoCacheFactory` 创建缓存并锁定所需的字段，而不是绕开工厂。

## 执行器 {#executors}

CoCache 在两个执行器上运行后台工作、异步与挂起的工作：

| 工作 | 配置项 | 内置执行器 |
|------|--------|------------|
| 提前刷新回源 | `cocache.refresh-executor` | 有界守护线程池（1–4 个线程，队列 1024）。队列满时跳过这次刷新，计入 `cocache.refresh.rejections`。 |
| [异步](./async.md)与[挂起](./coroutines.md)视图在 Redis 应答或 loader 的 future 完成之后的每一步（解码、填充 L2、发出下一条 Redis 命令、调用 `loadAsync`、完成你拿到的 future）；共享加载；经这两种视图调用的同步 loader；Spring Cache `retrieve(key, loader)` 的方法体 | `cocache.executor` | 运行时自建，每个任务一个虚拟线程，线程名 `cocache-worker-N` |

每个配置项的值都是 `Executor` bean 的**名称**。CoCache 只按名称查找，从不按类型查找。名称没有对应的 bean，或该 bean 不是 `Executor` 时，启动失败，错误信息指明配置项。该执行器归容器管理：关闭 `CacheRuntime` 不会关闭它，容器在运行时之后才销毁它。

Spring Boot 自带的 `applicationTaskExecutor` 使用虚拟线程时，两者都可以用它：

```yaml
spring:
  threads:
    virtual:
      enabled: true          # applicationTaskExecutor 使用虚拟线程
cocache:
  refresh-executor: applicationTaskExecutor
  executor: applicationTaskExecutor
```

::: warning `cocache.executor` 必须容得下阻塞的任务
它在每个异步与挂起调用的续接旁边运行阻塞的工作（同步 loader、Spring 的方法体）。不要给它队列，也不要只给少量线程：阻塞的 loader 会占住全部线程，而它们等待的续接排在后面；一个 loader 也可能等待另一次加载。请使用虚拟线程，或不排队的平台线程池。JDK 21–23 上，在 `synchronized` 块内阻塞的 loader 会钉住虚拟线程的载体线程（JEP 491 在 JDK 24 修复），因此建议 JDK 24 及以上，或使用平台线程池。阻塞 API 从不使用这个执行器：它的 loader 在调用方线程上执行。链路追踪也不用它：被观测的异步加载在自己的虚拟线程上观测。
:::

Spring Cache `retrieve` 经异步视图，不在任何线程上等待，也不需要单独的执行器。见 [Spring Cache](./spring-cache.md#executor)。

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
| `cocache.redis.command-timeout` | 时长 | `2s` | 单条命令或脚本的最长等待时间。`read-timeout` 只能缩短它。 |
| `cocache.redis.heartbeat` | 时长 | `5s` | 订阅连接上的 `PING` 间隔。PING 失败时清空 L2 并重连。 |
| `cocache.redis.invalidate-batch-size` | integer | `512` | 单个失效脚本处理的 key 数上限 |
| `cocache.redis.client-name` | string | 未设置 | CoCache 连接的 `CLIENT SETNAME` |
| `cocache.redis.store-eviction-check` | `warn` \| `fail` \| `off` | `warn` | `volatile-*` 下，对 `ttl` 有限的权威缓存如何处理（见[驱逐检查](#eviction-check)） |
| `cocache.redis.assume-maxmemory-policy` | 字符串 | 未设置 | 驱逐检查读不到 `INFO memory`（Redis 用户没有 `+info`，或服务禁用了它）时按这个策略判断，如 `noeviction`、`volatile-lru`。必须是 `noeviction`、`allkeys-*` 或 `volatile-*`。读得到策略时忽略。 |

这里的时长与其它地方一样必须带单位（见[时长](#durations)）。

### 驱逐检查 {#eviction-check}

`Store`、`VersionedStore` 与 `SetStore` 的唯一副本在 Redis 中，被驱逐的 key 无法重建。应用中有其中任何一种（已创建，或已声明但仍是延迟初始化的）时，starter 在启动时从 `INFO memory` 读取各主节点的 `maxmemory-policy`。权威缓存的 `ttl` 按创建它们时同样的分层得出。

| `maxmemory-policy` | 结果 |
|--------------------|------|
| `noeviction` | 正常启动 |
| 任一主节点为 `allkeys-*` | **启动失败**，无论 `store-eviction-check` 是什么：任何 key 都可能被驱逐 |
| 读不到，且没有设置 `assume-maxmemory-policy` | **启动失败**，错误信息给出两种修复：授予 `+info`，或设置 `assume-maxmemory-policy` |
| `volatile-*`，且有 `ttl` 有限的权威缓存 | 按 `store-eviction-check`：`warn` 记录告警，`fail` 启动失败，`off` 接受。这些 key 可能在到期前被驱逐。 |
| `volatile-*`，且所有权威缓存都是 `ttl: forever` | 正常启动：`volatile-*` 从不驱逐没有到期时间的 key |

`VersionedStore` 的墓碑总会过期，但驱逐它只会让迟到的旧版本提前可写，所以墓碑不计入。只检查 Redis 后端。见 [maxmemory-policy](../operations/index.md#maxmemory-policy)。

部署与容量方面的选择见[运维](../operations/)。

## Actuator 与健康检查 {#actuator}

classpath 中有 `spring-boot-starter-actuator` 时：

| 功能 | 开关 | 说明 |
|------|------|------|
| `cocache` 端点 | `management.endpoints.web.exposure.include=cocache`；访问控制用 `management.endpoint.cocache.access` | 列出缓存及其生效策略与计数，查看与失效单个 key，以及只为一个 key 广播失效、不动 Redis 中的数据 |
| 按 key 删除唯一副本 | `management.endpoint.cocache.allow-store-delete`（默认 `false`）：`Store`、`SetStore`；`management.endpoint.cocache.allow-purge`（默认 `false`）：`VersionedStore`，以及 `VersionedCache` 上的 `?purge=true` | 没有开启时返回 `403`，不做任何修改（[可观测性](../operations/observability.md#invalidate-key)） |
| `cocache` 健康检查 | `management.health.cocache.enabled`（默认 `true`） | 只有 Redis 失效通道心跳失败期间为 `DOWN` |
| Micrometer 指标 `cocache.*` | classpath 中有 Micrometer | 以 `cache` 标签区分 |

端点路径与指标名见[可观测性](../operations/observability.md)。

### 生效值与来源 {#policy-report}

每个已分层的缓存都报告它的每个字段来自哪里：

- **启动日志**。每个缓存一行 `INFO`，列出值不是来自类型默认值的字段及其来源，例如 `Cache[UserCache] - policy: ttl=PT10M (caches), local-max-age=PT2M (defaults).`。每个被跳过的条目、被夹紧的字段与被解锁的字段各有一行 `WARN`。
- **端点**。`GET /actuator/cocache/{name}` 在 `policy` 下列出适用于该缓存的每个字段的生效值与来源，在 `skippedConfiguration` 下列出每个被跳过的条目，含配置键、层与原因。见[缓存报告](../operations/observability.md#actuator-endpoint)。

| 来源 | 含义 |
|------|------|
| `TYPE_DEFAULT` | 类型默认值 |
| `DEFAULTS` | `cocache.defaults.cache.*` 或 `cocache.defaults.store.*` |
| `DECLARATION` | 注解或 spec |
| `CUSTOMIZER` | `CacheSpecCustomizer`（detail 中给出是哪一个） |
| `OVERRIDES` | 被 `cocache.overrides.*` 收紧（detail 中给出收紧之前的值） |
| `CACHES` | `cocache.caches.[name].*` |
| `CLAMPED` | 全局层引起冲突之后，被[校验连锁](#validation)改变 |
| `LOCKED` | 被库锁定（`locked by …`），或被它的约束改变（`constrained by …`） |
| `UNLOCKED` | 由 `cocache.caches.[name]` 越过锁定设置，已列在 `unlock` 中 |

哨兵值写作 `forever`、`off` 与 `unlimited`，模式为小写，时长用 ISO-8601（`PT10M`）。类路径上有 `spring-tx` 时，`Cache` 与 `VersionedCache` 在策略字段之后另列 `invalidate-fills-on-rollback` 与 `fill-in-transaction`：它们不是策略字段，没有声明层，但全局层与按缓存的层照同样的规则合并。未分层的缓存的 `policy` 为空，两个事务模式取类型默认值。

## 启动校验 {#validation}

CoCache 不会带着无法兑现的配置运行，而是让启动失败。

**值**。不带单位的时长、写错的哨兵值、只有正值才有意义处写了零、或负值，在配置绑定时就失败，错误信息给出完整的配置键（见[时长](#durations)）。

**`cocache.caches` 中的条目**。两条规则并列生效。第一条由 `cocache.caches-validation` 决定，第二条不受任何配置影响：

| 条目 | `caches-validation: fail`（默认） | `caches-validation: warn` |
|------|-----------------------------------|---------------------------|
| 名称对应不上任何缓存，在所有单例创建完成后检查（几乎总是拼写错误；错误信息列出已声明的缓存） | 启动失败 | `WARN` |
| 配置项不[适用](#applicability)于该缓存的类型，或 `unlock` 中有不是字段的项 | 创建缓存失败 | `WARN`，忽略该配置项 |
| 名称对应直接用 `CacheRuntime.create` 创建的缓存（未分层） | `WARN`，并列在端点的 `ignoredConfiguration` 中 | 相同 |

第三条只告警，是因为这样的缓存可能在启动之后才创建，而事故中写下的覆盖不应让应用起不来。名称检查只在所有单例创建完成时执行一次，库在那之后才创建的缓存此时还不为它所知：配置这样的缓存时请用 `warn`。

**锁定**。设置了[被锁定](#locks)字段、却没有在 `unlock` 中列出它的 `cocache.caches.[name]` 条目，无论 `caches-validation` 是什么都让启动失败。修改被锁定的字段或布局、或修改数据来源的定制器同样如此。

**策略**。叠加所有层之后，策略必须自洽：`refresh-window` 必须小于 `ttl − ttl-jitter`（按实际抖动计算），也必须小于 `local-max-age`，否则新副本一出生就在窗口内。校验连锁判定责任在谁：

- 全局层（`cocache.defaults` 或 `cocache.overrides`）设置了相关字段（`refresh-window`、`local-max-age`、`ttl`、`ttl-jitter`）之一、而 `cocache.caches.[name]` 一个也没有设置时，这个缓存的窗口被**关闭**。启动日志告警，端点把 `refresh-window` 报告为 `CLAMPED`。运维处处调短 `local-max-age` 不会让应用起不来。
- 其余情形启动失败：冲突只由声明引起，或 `cocache.caches.[name]` 设置了相关字段之一。例如 `cocache.defaults.cache.ttl: 10m` 加上 `cocache.caches.[UserCache].refresh-window: 20m` 会失败：这个窗口是按缓存的条目要求的。

`refresh-window: off` 会关掉声明中开启的窗口，所以配置项也可以为单个缓存关闭提前刷新。

声明层面的校验见[声明缓存](./caches.md#validation)。

## 配置项之外 {#beyond-properties}

有些配置只存在于代码中，因为它们是函数或对象，而不是值：

| 配置 | 位置 |
|------|------|
| `ttlOf`、`keyEncoder`、`keyFilter`、`codec`、`loader` | spec 或[定制器](./caches.md#customizer)（定制器不能修改数据来源） |
| `local.initialCapacity`、`local.expireAfterAccess` | `@CaffeineCache`、spec 或定制器 |
| 锁定与约束 | `CoCacheFactory.create(spec) { … }`（见[锁定](#locks)） |
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
