---
title: 更新日志
description: CoCache 版本发布历史和重要变更。
---

# 更新日志

## 未发布 {#unreleased}

**模块组：** `me.ahoo.cocache` · 计划作为 8.0.0 发布，是包含破坏性变更的大版本（[RFC 0005](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)）。8.x 不与 6.x 同时运行在一个 Redis 上：先停 6.x，再启动 8.x（见[从 6.x 升级](./upgrade.md)）。6.x 的修复继续在 `6.x` 分支上进行。

### 从 6.x 升级 {#upgrading-from-6x}

8.x 不与 6.x 同时运行在一个 Redis 上，也没有迁移工具：停止所有 6.x 实例，删除没有数据源的缓存的 key，再启动 8.x，有数据源的缓存从数据源重新加载。[从 6.x 升级](./upgrade.md)给出操作步骤，并把 6.x 的类型、配置项、Redis ACL 与后端 SPI 对应到 8.0。下面各节列出全部变更。

### 用户 API 按角色拆分 {#api-types}

8.0 用每个角色一个类型取代了 6.x 中有没有 loader 都叫 `Cache` 的接口与 `SetCache`（[RFC 0005 §4.1](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)）。派生类型持有数据源可以重建的副本；权威类型持有唯一副本（在 Redis 中），没有 loader。每个类型只有对它的角色安全的操作，所有方法都是抽象的：不会因为缓存的声明方式而抛出 `UnsupportedOperationException`。见[选择类型](./caches.md#types)。

| 6.x | 8.x | 变化 |
|-----|-----|------|
| 有 loader 的 `Cache<K, V>` | `Cache<K, V>`，`cacheSpec { loader(…) }` | 去掉了 `getIfChanged`、`putIfMatch`、`putIfNewer`、`invalidateIfNewer` 与 `getVersioned`。 |
| 没有 loader、由 Spring Cache 或其它系统填充的 `Cache<K, V>` | 带 `externallySourced()` 的 `Cache<K, V>`，或 `@CoCache(source = EXTERNAL)` | `CacheSpec` 必须恰好声明 `loader`、`asyncLoader` 与 `externallySourced()` 之一，都没有时创建失败。它写入的值不带写入标识与摘要，失败的 `invalidate` 与有 loader 的缓存一样在后台重试。 |
| 没有 loader、Redis 是唯一副本的 `Cache<K, V>` | `Store<K, V>`，`storeSpec` | `delete` 与 `deleteIfMatch` 取代 `invalidate`：同步完成，失败时抛出，从不重试。`getIfChanged` 与 `putIfMatch` 在这里。读取不申请 lease。存储必须提供 `conditional()`。 |
| 带 `versioned` 与带版本的 loader 的 `Cache<K, V>` | `VersionedCache<K, V>`，`versionedCacheSpec` | `getVersioned`、`putIfNewer`、`invalidateIfNewer`、`evict`（保留版本，状态 `E`）与 `purge`（丢掉版本）。没有 `put` 与普通的 `invalidate`。 |
| 带 `versioned`、没有 loader 的 `Cache<K, V>` | `VersionedStore<K, V>`，`versionedStoreSpec` | `deleteIfNewer` 取代 `invalidateIfNewer`；`purge` 丢掉版本。墓碑在 `tombstoneTtl`（默认 1 天）之后过期。 |
| `SetCache<K, E>`、`SetCacheSpec` | `SetStore<K, E>`、`SetStoreSpec`、`setStoreSpec` | 没有 `invalidate`，改用 `clear(key)`。 |
| `AsyncCache`、`AsyncSetCache` | `AsyncCache`、`AsyncVersionedCache`、`AsyncStore`、`AsyncVersionedStore`、`AsyncSetStore`（都是 `AsyncReadableCache`） | 异步视图按同样的方式拆分，挂起视图也是。 |
| `cocache-coroutines`：`SuspendingCache`、`suspending()` 扩展函数、`suspendingLoader` | `suspending()` 是每个类型的成员，`SuspendingCache` 等在 `cocache-api` 中；`suspendingLoader` 与 `suspendingVersionedLoader` 在 `me.ahoo.cocache.core` 中 | 删除 `cocache-coroutines` 模块与它在 BOM 中的条目。去掉这个依赖与 `me.ahoo.cocache.coroutines` 的导入。 |
| 任意两个缓存上的 `JoinCache` | 任意可读缓存上的 `JoinView`（只读）；`JoinCache` 只用于两个派生 `Cache` | `JoinCache` 是带两个失效操作的 `JoinView`。 |
| `CacheRuntime.create(CacheSpec)` 返回 `Cache` | 每种 spec 一个 `create`，返回对应的类型；`create(spec: Spec)` 按类型分派；`cache(name)` 返回 `ReadableCache` | 创建时检查类型需要的能力：`Store` 需要 `conditional()`，`VersionedCache` 与 `VersionedStore` 需要 `versioned()`，`SetStore` 需要 `sets()`。 |

读取与失败：

- `Lookup` 有第三种结果 `Unknown(error)`：L1 失败，又没有数据源可以回退。它只来自 L1 故障，从不来自内容。`Lookup.valueOrThrow()` 抛出它的 `CacheReadException`。见[读取：存在、不存在与未知](./caches.md#unknown)。
- `CacheSpec`、`StoreSpec` 与 `VersionedStoreSpec` 上的 `unknownRead`（默认 `THROW`，或 `ABSENT`）决定不带 loader 的读取遇到 `Unknown` 时返回什么；`ABSENT` 读到不存在，两层都不缓存。`SetStore` 总是抛出。`CacheSpec` 只有同时声明 `externallySourced()` 才能设置 `ABSENT`。`readFailure` 现在只作用于带 loader 的读取，默认仍是 `DEGRADE`；不带 loader 的读取上的 `readFailure = DEGRADE` 不再把失败变成不存在。
- 部分 key 为 `Unknown` 的 `getAll` 先完成其余 key，再抛出 `CacheReadException`，带 `unknownKeys` 与其余 key 的 `partialResult`。新的 `lookupAll` 改为逐个 key 返回 `Lookup`。
- `CacheWriteException.outcomeUnknown` 表示写入是否可能已到达 Redis。只有写入没有发出、或存储拒绝了它且没有修改 Redis 时为 `false`。SPI：存储以新的 `L1RejectedException` 报告发出之前被拒绝的命令，Redis 后端在断线期间即是如此。
- 带版本的 loader 返回不带版本的 `null`、而 Redis 中是带版本的内容时，这次填充被拒绝：不写入任何东西，计入 `cocache.fill.unversioned.absent`，缓存第一次告警。
- 新指标：`cocache.reads.unknown`（`CacheMetrics.readsUnknown`）与 `cocache.fill.unversioned.absent`（`CacheMetrics.unversionedAbsentRefusals`）。端点的本地状态 `UNRESOLVED` 改名为 `UNFILLED`。

默认值与线格式：

- 权威类型（`Store`、`VersionedStore`、`SetStore`）从自己的策略开始：`ttl` 永不过期、`localMaxAge` 30 秒、`absentTtl` 10 秒（[默认策略](./caches.md#store-defaults)）。它们的 `ttl` 只由声明与 `cocache.caches.[name].ttl` 设置（见[三层配置](#three-layer-config)）。
- `Store` 与 `VersionedStore` 写入写入标识，`Store` 另写入摘要，也只有这两个类型校验它。`Cache`（无论是否外部来源）两者都不写。派生缓存读到带摘要的值计入类型不符，`evict` 拒绝它。
- SPI：`ConditionalL1Store.deleteIfMatch(key, expected, frame)`（抽象方法；`FutureConditionalL1Store` 与 `ForwardingConditionalL1Store` 同样）在保存的摘要等于 `expected` 时删除值并发布帧。API 中它的结果是 `ConditionalDelete`。
- Spring：`@CoCache` 接口按它继承的类型创建，五个类型都不继承时启动失败。没有匹配的 loader bean 的 `Cache` 接口启动失败，除非声明 `source = EXTERNAL`；6.x 中它静默地没有 loader。权威类型上设置 `loader` 或 `source = EXTERNAL` 时启动失败。Spring Cache 与 `TransactionalInvalidator` 只接受派生的 `Cache`。`cocache.redis.set-cache-eviction-check` 改名为 `cocache.redis.store-eviction-check`，同时覆盖 `Store` 与 `VersionedStore`。删除 `CoCacheProperties.setPolicyFor`（见[三层配置](#three-layer-config)）。
- TCK：`CacheAccess` 创建每个类型（`cache`、`versionedCache`、`store`、`versionedStore`、`setStore`）。`EngineSpec` 覆盖 `Cache` 与 `Store`，`VersionedSpec` 覆盖 `VersionedCache` 与 `VersionedStore`，`SetEngineSpec` 覆盖 `SetStore`。`HookedBackend.rejectWrites` 让写入在发出之前以 `L1RejectedException` 被拒绝。

### 库经 `CoCacheFactory` 创建缓存 {#cocache-factory}

- `CoCacheFactory`（`cocache-core`）是创建缓存的入口（[RFC 0005 §4.8](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)）：每种 spec 一个 `create(spec)`；接口式声明用 `create(type, spec)`，`type` 必须是继承 spec 声明的那个类型的接口。starter 发布一个 `CoCacheFactory` bean，自己的接口式声明与 spec bean 也经它创建；注入它的库得到同样的定制器与 `cocache.*` 配置、端点与指标。见 [CoCacheFactory](./caches.md#factory)。
- 直接用 `CacheRuntime.create` 创建的缓存是*未分层*的：定制器与 `cocache.*` 配置对它不生效。它的启动日志以 `unlayered: …` 结尾，端点报告 `layered: false`；指向它的 `cocache.caches.[name]` 告警，并列在端点的 `ignoredConfiguration` 中。
- `CacheAdmin` 新增 `apiType`（缓存的 API 类型）、`layered` 与 `policyReport`；端点的缓存报告新增 `type`、`layered`、`ignoredConfiguration`、`policy` 与 `skippedConfiguration`（见[三层配置](#three-layer-config)）。
- `CacheRuntime.addCreationListener` 在之后创建的每个缓存之后回调 `CacheCreationListener`。starter 用它为每个缓存绑定指标，包括启动之后直接创建的缓存。

### 三层配置 {#three-layer-config}

缓存的策略现在由声明前后的三个配置层逐字段合并而成（[RFC 0005 §4.8](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)）。见[配置如何叠加](./configuration.md#precedence)。

- **层**，由低到高：类型默认值（`DefaultPolicies`）、`cocache.defaults.cache.*` / `cocache.defaults.store.*`、声明、`CacheSpecCustomizer` bean、`cocache.overrides.cache.*` / `cocache.overrides.store.*`、`cocache.caches.[name].*`。`cache` 一节作用于 `Cache` 与 `VersionedCache`，`store` 一节作用于 `Store`、`VersionedStore` 与 `SetStore`。
- **默认值低于声明**：只填充声明没有写出的字段。设置了注解属性、`policy { … }` 中赋值（包括 `ttl = forever()`；嵌套的 `lease`、`load` 或 `local` 只写出值有变化的字段），或 spec 整体赋值 `policy`（写出全部字段），都算写出。`Spec.declaredFields` 列出这些字段。
- **覆盖只收紧**：`local-max-age`、`degraded-local-max-age`、`absent-ttl` 与 `Cache` 的 `ttl` 取最小值，`read-failure` 取更严的模式。`overrides.cache.ttl` 还限制按调用给出的寿命（`ttlOf`、`put(key, value, ttl)`、`putAll(entries, ttl)`），计入新指标 `cocache.ttl.clamped`（`CacheMetrics.ttlClamped`，端点字段 `ttlClamped`）；更长的 `cocache.caches.[name].ttl` 把该缓存的上限抬到它，`forever` 取消上限。`overrides` 中的其它配置项一律跳过。
- **适用性**。全局层按缓存跳过不适用于其类型、该层不允许或被库锁定的配置项：创建时记录一行 `WARN`，并列在端点新增的 `skippedConfiguration` 中。`cocache.caches.[name]` 中不适用的配置项与未知的缓存名一样让启动失败；`cocache.caches-validation: warn`（默认 `fail`）对两者都只告警。指向未分层缓存的条目仍然只告警。见[哪些配置项适用](./configuration.md#applicability)与[校验](./configuration.md#validation)。
- **取值**。每个时长都必须带单位，包括 `cocache.redis.*`：`500ms`、`5s`、`2m`、`PT2M`。哨兵值显式：`forever`（`ttl`、`tombstone-ttl`）、`off` 或 `0s`（`refresh-window`）、`unlimited`（`load-max-concurrent`）。值在绑定时解析，写错的值让启动失败并指明配置键，例如 `cocache.defaults.cache.ttl: "60" needs a unit, e.g. 60s, 5m, PT2M.`。注解仍按秒。
- **新增的配置项**：`read-timeout`（`CachePolicy.readTimeout`）、`tombstone-ttl`，以及只能写在 `cocache.caches.[name]` 中的 `unknown-read` 与 `unlock`。`cocache.key-prefix` 在分层之外为所有缓存设置前缀；`cocache.caches.[name].key-prefix` 仍优先于它。
- **库的锁定**。`CoCacheFactory.create(spec) { lock(…); lockLayout(…); constrain(field) { … }; verify { … } }`（Java：`create(spec, Locks.builder("my-lib")….build())`）固定策略字段与存储布局。被锁定的字段报告为 `LOCKED`（`locked by …`），全局层跳过它们，`cocache.caches.[name]` 写出它们时启动失败，除非 `unlock` 列出了它们（该字段随之报告为 `UNLOCKED` 并告警），修改它们的定制器抛出异常。锁定的 `KEY_PREFIX` 不受 `cocache.key-prefix` 影响。见[锁定字段](./configuration.md#locks)。
- **校验连锁**。全局层让 `refresh-window` 不再小于 `local-max-age` 或 `ttl` 减去抖动、且 `cocache.caches.[name]` 没有设置这些字段时，这个缓存的窗口被关闭（`CLAMPED`，并告警）。只由声明引起的冲突，或牵涉 `cocache.caches.[name]` 所设字段的冲突，让启动失败。
- **报告**。每个已分层的缓存记录一行 `INFO`，列出不是来自类型默认值的字段及其来源。`GET /actuator/cocache/{name}` 新增 `policy`（每个字段的生效值与来源：`TYPE_DEFAULT`、`DEFAULTS`、`DECLARATION`、`CUSTOMIZER`、`OVERRIDES`、`CACHES`、`CLAMPED`、`LOCKED`、`UNLOCKED`）与 `skippedConfiguration`。`CacheAdmin.policyReport` 返回同一个 `PolicyReport`，未分层的缓存为 `null`。见[生效值与来源](./configuration.md#policy-report)。
- **驱逐检查**。存在权威缓存时，`allkeys-*` 总是让启动失败；读不到 `maxmemory-policy` 又没有配置 `cocache.redis.assume-maxmemory-policy` 时同样如此。`cocache.redis.store-eviction-check`（`warn`、`fail`、`off`）现在只决定 `volatile-*` 且有 `ttl` 有限的权威缓存这一种情形，`fail` 在这里让启动失败。见[驱逐检查](./configuration.md#eviction-check)。
- API（`cocache-core`）：`DefaultPolicies`；每个可配置字段一个对象的 `PolicyField`；哨兵类型 `Ttl`（`Forever`、`Finite`、`finiteOr`、`min`）、`RefreshWindow`（`Off`、`Of`）与 `Concurrency`（`Unlimited`、`Max`）；`Locks`、`LayoutField`、`PolicyReport` 与 `Layered`；`CachePolicy.ttlCeiling`；构建器辅助方法 `forever()`、`off()` 与 `unlimited()`。

升级时需要修改的地方：

| 之前 | 8.x | 须知 |
|------|-----|------|
| `cocache.defaults.<field>`，如 `cocache.defaults.ttl` | `cocache.defaults.cache.<field>`（`Cache`、`VersionedCache`）或 `cocache.defaults.store.<field>`（`Store`、`VersionedStore`、`SetStore`） | **语义变了。** `cocache.defaults.<field>` 覆盖声明（包括注解）；新的配置项低于声明，只填充声明没有写出的字段。要继续让某个值压过所有声明，用 `cocache.overrides.cache.<field>`（只限陈旧界限与 `read-failure`）或 `cocache.caches.[name].<field>`。Spring Boot 静默忽略旧的配置项。 |
| `ttl-forever: true` | `ttl: forever` | Spring Boot 静默忽略 `ttl-forever`，依赖它的缓存于是取类型默认值：派生缓存为 1 小时。 |
| `cocache.defaults.key-prefix` | `cocache.key-prefix` | 配置仍优先于声明，库锁定了 `KEY_PREFIX` 时除外。Spring Boot 静默忽略旧的配置项，缓存于是退回声明的前缀（默认 `cocache:`）：派生缓存相当于冷启动，权威缓存则读到空的数据。 |
| 不带单位的数字：`cocache.defaults` 与 `cocache.caches` 下按秒（`ttl: 600`），`cocache.redis` 下按毫秒（`command-timeout: 2000`） | `ttl: 10m`、`command-timeout: 2s` | 不带单位的数字现在让启动失败，并指明配置键。 |
| `refresh-window: 0`、`ttl-jitter: 0` | `refresh-window: off`（或 `0s`）、`ttl-jitter: 0s` | |
| `load-max-concurrent: 0`（不限制） | `load-max-concurrent: unlimited` | `0` 让启动失败。 |
| `LoadPolicy(maxConcurrent = 0)` | `LoadPolicy(maxConcurrent = null)`，或在 `policy { … }` 中用 `unlimited()` | `maxConcurrent` 是 `Int?`，`0` 抛出 `IllegalArgumentException`。 |
| `CachePolicy.DEFAULT`、`StoreSpec.DEFAULT_POLICY`、`VersionedStoreSpec.DEFAULT_POLICY`、`SetStoreSpec.DEFAULT_POLICY` | `DefaultPolicies.CACHE`、`VERSIONED_CACHE`、`STORE`、`VERSIONED_STORE`、`SET_STORE` | `CachePolicy.builder()` 以 `DefaultPolicies.CACHE` 为起点。 |
| 在 spec 上用 `policy = …` 修改几个字段 | `policy { … }` / `updatePolicy(…)` | 整体策略视为写出全部字段，`cocache.defaults` 于是不再作用于这个缓存。 |
| `CacheLayers.apply(spec)`，返回 spec | `CacheLayers.apply(spec, locks)`，返回 `Layered`（`spec`、`report`） | `CacheLayers.NONE` 只保留声明，并执行约束。 |
| `CoCacheProperties.policyFor`、`authoritativePolicyFor`、`keyPrefixFor`、`PolicyProperties.applyTo` | 删除 | 由 starter 的 `CacheAssembler` 合并各层。`CoCacheProperties.defaults` 与 `overrides` 是 `Layer(cache, store)`，策略与 `cocache.redis` 的时长按字符串绑定。 |
| `cocache.caches` 中未声明的名称总是让启动失败 | 默认启动失败；`cocache.caches-validation: warn` 时只告警 | 不适用于缓存类型的按缓存配置项同样处理。 |
| 驱逐检查：`allkeys-*` 与读不到策略只在 `store-eviction-check: fail` 时启动失败；`volatile-*` 且 `ttl` 有限时即使 `fail` 也只告警 | `allkeys-*`，以及读不到策略又没有 `assume-maxmemory-policy`，总是启动失败；`store-eviction-check` 只决定 `volatile-*` 且 `ttl` 有限的情形，`fail` 在这里启动失败 | `off` 不再跳过 `allkeys-*` 的检查。 |

### 破坏性变更

- 配置由声明前后的三层合并而成：`cocache.defaults.<field>` 改为 `cocache.defaults.cache.<field>` 或 `cocache.defaults.store.<field>`，并且**低于**声明；`cocache.overrides.*` 只收紧；每个时长都必须带单位；若干配置项与常量改名。见[三层配置](#three-layer-config)。
- 接口代理只经 `CoCacheFactory.create(type, spec)` 取得：`CacheProxies.create` 改为内部方法，`CacheAssembler` 不再创建缓存（它是 starter 的 `CacheLayers`）。见[库经 `CoCacheFactory` 创建缓存](#cocache-factory)。
- 用户 API 拆分为 `Cache`、`VersionedCache`、`Store`、`VersionedStore` 与 `SetStore`，删除 `cocache-coroutines`：见[用户 API 按角色拆分](#api-types)。
- 挂起视图上的调用级 loader（`suspending().get(key) { … }`）不再在调用方的 dispatcher 或 `Dispatchers.Default` 上执行：它在这次加载之中执行，第一次挂起之后在 CoCache 的执行器上继续，`load-timeout` 会取消它。见[挂起视图直接调用缓存的核心](#suspending-native)。
- 基线改为 JDK 21（原为 JDK 17）。
- 存储 key 保持 6.x 的形状 `{keyPrefix}{namespace}:{encodedKey}`。失效频道改为 `{keyPrefix}inv:{namespace}`（6.x 为 `cocache:inv:{namespace}`，不含前缀），命名空间相同、前缀不同的应用不再收到彼此的失效。`pubsub` 模式的启动探测使用 `cocache:!probe:{a}` 与 `cocache:!probe:{b}`（6.x 为 `cocache:probe:{a}`，它也是命名空间 `probe` 的 key）。内部 key 放在 `{keyPrefix}!` 之下，任何命名空间都产生不了这个位置，`{keyPrefix}!meta:{namespace}` 预留。见[存储 key](./caches.md#storage-keys)。
- 命名空间必须符合 `[A-Za-z0-9._-]+`。含 `:`（或字符集之外任何字符）的命名空间在创建时以 `IllegalArgumentException` 失败，信息给出把前缀移到 `keyPrefix` 的写法：`namespace = "app:user"` 改为 `keyPrefix = "cocache:app:"` 与 `namespace = "user"`。6.x 中命名空间 `a` 与 `a:b` 的重叠因此不再可能。
- 缓存按前缀与命名空间登记。命名空间相同、前缀不同的两个缓存现在可以在同一个运行时中创建。重复时的错误为 `Namespace[x] with keyPrefix[p] is already used by another cache.`
- Redis ACL：频道模式为 `&{keyPrefix}inv:*`（默认 `&cocache:inv:*`），`pubsub` 模式另加 `&cocache:!probe:*`（6.x 为 `&cocache:probe:*`）。`keyPrefix` 为空时，key 与频道没有公共前缀：逐个列出命名空间（`~{namespace}:*`、`&inv:{namespace}`），另加 `~!*`。见 [Redis ACL](../operations/index.md#acl)。
- 在 Redis 上创建缓存时，向该缓存的频道发布一个不带 key 的帧，原子广播时在写脚本中发布。ACL 不放行这个频道时，创建以 `IllegalStateException` 失败并给出要放行的模式，而不是每次写入都在修改 Redis 之后才在广播处失败。暂时性错误照旧失败，ACL 拒绝也从不把后端降级为非原子广播。`ExternalInvalidator` 第一次用到一个命名空间时同样探测。
- 频道读不懂的失效帧（未知版本、截断、尾随字节）现在会重置该频道上的每个缓存，清空它的 L2。6.x 告警后丢弃，丢的就是一次失效。帧版本 `2` 预留给之后的 namespace 重置（`InvalidationFrame.NAMESPACE_RESET_VERSION`）。不带 key 的帧什么也不做，也不计入 `cocache.invalidations.received`。
- SPI：新增值类型 `Keyspace(keyPrefix, namespace)`，标识一个缓存的 key 与频道。`CoherenceBackend.store`、`InvalidationChannel.subscribe` / `publish` 与 `FutureInvalidationChannel.subscribe` / `publish` 改收 `Keyspace`，不再收命名空间字符串。后端按两者构造频道名，解码失败的帧重置订阅者，不带 key 的帧照常投递。每种 spec 都有 `Spec.keyspace`，给出缓存的 keyspace。
- `LoadPolicy.asyncTimeout` 改为 `LoadPolicy.timeout`，配置项 `load-async-timeout` 改为 `load-timeout`（默认 `30s`）。它不再只约束异步加载：等待别的调用方加载的读取，在这次加载进行了这么久之后以 `SourceLoadTimeoutException` 失败，之后的读取开始新的加载，不再排在卡住的同步 loader 后面。6.x 中同步读取会一直等下去。`cocache.load.timeouts` 两种超时都计数。
- `cocache-core` 在运行时依赖 `kotlinx-coroutines-core`。协议正在改写为一份协程实现，同时服务阻塞、异步与挂起三种 API；两种缓存的读取、提前刷新与写入都已用上它。开启断言（`-ea`，测试时的默认值）时，读取运行期间 kotlinx 会给线程名加上协程后缀（如 ` @coroutine#3`），阻塞的 loader 里也看得到。
- 运行时不再启动 `cocache-timer` 线程：等待 lease 与等待回源许可都是协程挂起。
- `cocache-redis` 不再包含 CoCache 5.x 迁移工具：删除了 `CoCache5Migrator`、`CoCache5Migration`、`CoCache5Format`、`CoCache5KeyMapper`、`CoCache5MigrationOptions`、`CoCache5MigrationReport`、`CoCache5CacheReport` 与 `CoCache5MigrationException`（包 `me.ahoo.cocache.redis.migration`）。8.x 不需要读 5.x 的数据，因此不再附带这个转换工具。以 Redis 为唯一副本的 5.x 数据，先用 CoCache 6.x（6.5 起）迁移，见[用 `CoCache5Migrator` 迁移](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/zh/guide/migration-6.md)。
- 回源许可（`load.max-concurrent`）对同步与异步调用方一律按到达顺序交接。6.x 的阻塞调用方直接争用释放出来的许可，可能抢在已在等待的异步调用方之前。
- `CacheRuntime` 关闭时仍在进行的异步读取以 `IllegalStateException("CacheRuntime closed.")` 失败。仍在进行的异步写入先做完 Redis 修改、广播与本地淘汰：成功时再以这个异常完成，失败时以自己的异常完成。
- 阻塞调用（`get`、`getAll`、`put`、`invalidate` 等）不再为每条 Redis 命令阻塞线程：命令与异步 API 一样发出，调用方停在 `runBlocking` 中等它们完成。因此中断调用方线程不再放弃 Redis 命令：被中断的调用方至多多等一个 `command-timeout`（默认 `2s`）。6.x 中等待立即以 `RedisException("Interrupted while waiting for Redis.")` 失败：写入把它包装为 `CacheWriteException` 抛出，读取把它当作 Redis 故障（默认从数据源加载）。在途的命令先完成或超时；之后读取释放它取得的 lease、不再调用 loader，调用抛出 `InterruptedException`。在中断生效之前已经完成的调用（如 L1 命中、命令已在途的 `put`）照常返回。两种情形都保留中断标志。超时而放弃的命令仍然不会在之后发出。
- **线格式 2**：8.x 写入的全部内容都是格式 2，lease、负缓存与集合也是（见[线格式](../architecture/index.md#wire-format)、[RFC 0005 §4.7](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)）。36 字节头部的第 3 字节（6.x 的保留字节）改为标志位，头部之后是段：版本（8 字节，标志位 bit 0）与 TLV 组成的扩展区（标志位 bit 7）。带版本的值把版本放在版本段而不是令牌位置，6.8 的 `VERSION_MARK` 已删除。集合的 `!f` 字段为 `"2"`；`!m` 字段存在且不是 `"0"` 时集合读不懂。`Cache` 与 `VersionedCache` 的值、lease 与负缓存的头部仍是 36 字节；`Store` 与 `VersionedStore` 写入的每个值另带 28 字节的写入标识。
- 8.x 不读 6.x 的数据。格式 1（6.x 的格式）与不是 CoCache 写入的内容一样，是缓存读不懂的内容：有 loader 的缓存回收它并从数据源重新加载，没有 loader 的缓存读取失败。8.x 与 6.x 的实例不能共用一个缓存。格式 2 中带本版本不认识的必须理解的标志位（bit 2–6）或状态的内容是更高的格式，存储把它报告为格式 3：有 loader 的缓存绕过它回源，任何读取都不回收、不覆盖它。读取只对没有这类标志位的格式 2 内容授予 lease 或刷新令牌，填充与释放只读 36 字节的头部（`GETRANGE`），版本段中从不是 `Versioned.NO_VERSION`。
- Redis ACL：脚本现在调用 `GETRANGE`、`STRLEN` 与 `HMGET`，不再调用 `HGET` 与 `HEXISTS`。升级前给已有用户加上 `+getrange +strlen +hmget`，见 [Redis ACL](../operations/index.md#acl)。
- 删除 `foreign-format`：`CachePolicy.foreignFormat`、`CachePolicy.Builder.foreignFormat`、`ForeignFormatMode`，以及配置项 `cocache.defaults.foreign-format` 与 `cocache.caches.[name].foreign-format`。有 loader 的读取回收读不懂的内容并重新加载，没有 loader 的读取以 `CacheReadException` 失败。6.x 中没有 loader 的缓存上的 `reclaim` 会删除唯一副本（[RFC 0005](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md) §4.7、§12 决定 6）。Spring Boot 与对待任何不认识的配置项一样忽略它。
- 条件写入（`putIfMatch`、`deleteIfMatch`）、按版本写入（`putIfNewer`、`invalidateIfNewer`、`deleteIfNewer`）与 `SetStore.add` / `remove` 遇到缓存读不懂的内容时一律拒绝：抛出 `CacheWriteException`（`cause` 为 `ForeignFormatException`），不修改 key；`getIfChanged` 同样抛出 `CacheReadException`。6.x 中 `foreign-format: reclaim` 让它们把不是 CoCache 写入的内容（格式 0）当作没有值：`putIfMatch(key, null, …)` 与 `putIfNewer` 覆盖它，`getIfChanged` 返回 `Absent`，`add` 用新成员替换整个 key。
- SPI：`RemoteStore.putIfMatch` / `putIfNewer` 及其 `AsyncRemoteStore` 对应方法删除 `foreignAsAbsent` 参数，存储读不懂的内容总是返回 `Unreadable`。删除会覆盖这类内容的 `RemoteStore.addMembers` / `removeMembers` 与 `AsyncRemoteStore.addMembersAsync` / `removeMembersAsync`；由 `addMembersIfReadable` / `removeMembersIfReadable` 及其异步方法接替，它们改为抽象方法，因为“先读后写”的默认实现不原子。Redis 脚本 `ADD_MEMBERS` 与 `REMOVE_MEMBERS` 已删除。
- SPI：L1 SPI 改为挂起形状（[RFC 0005 §4.3](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)）。`CoherenceBackend.store` 返回 `L1Store`，它的操作都是 `suspend` 函数。条件读写、按版本写入与集合成员的增删移到能力面上，由 `conditional()`、`versioned()` 与 `sets()` 返回，存储不支持时返回 `null`。删除 `CoherenceBackend.versionedWrites`，由 `versioned()` 取代。`InvalidationChannel.publish` 改为 `suspend`，`subscribe` 仍是阻塞的。Java 后端实现 `FutureL1Store` 与 `FutureInvalidationChannel`（方法返回 `CompletableFuture`），返回 `FutureL1Store.adapt(…)` 与 `FutureInvalidationChannel.adapt(…)`；装饰器继承 `ForwardingL1Store`。实现自己取消的 future 按 L1 故障处理，不当作调用方被取消。`L1Store` 没有 `close`：连接属于后端，运行时不再关闭存储。引擎从不取消 L1 调用，实现可以用可取消的 `await()` 等待。只实现阻塞的 `RemoteStore` 的后端不再可用：引擎不再把这样的存储放到阻塞执行器上执行。删除 `RemoteStore`、`AsyncRemoteStore` 与 `AsyncInvalidationChannel`。
- `CacheRuntime.create` 检查缓存需要的 L1 能力：`Store` 需要 `conditional()`，`VersionedCache` 与 `VersionedStore` 需要 `versioned()`，`SetStore` 需要 `sets()`；缺少时创建以 `UnsupportedOperationException` 失败，不注册任何东西。每个缓存创建时的日志给出它的类型，并列出它的命名空间的存储提供的能力，例如 `created as Store … L1 capabilities[conditional, versioned, sets]`。6.x 在这样的存储上 `getIfChanged` 退回普通读取。
- SPI：L1 的结果类型改名：`RemoteRead` 改为 `L1Read`，`RemoteConditionalRead` / `RemoteConditionalWrite` 改为 `L1ConditionalRead` / `L1ConditionalWrite`，`RemoteVersionedWrite` 改为 `L1VersionedWrite`。
- SPI：`Envelope.Scalar` 新增 `writer: Token?`（默认 `null`），即写入标识，引擎在 `Store` 与 `VersionedStore` 的每次标量写入中给出它。存储在每个写入标量的操作（`put`、`putAll`、`fill`、`fillAll`、`putIfMatch`、`putIfNewer`）中保存它，`read` 与 `readAll` 原样返回；它参与相等比较，不影响摘要。`VersionedL1Store.putIfNewer` 以 `IllegalArgumentException` 拒绝 `Versioned.NO_VERSION`。引擎只读与 `CoherenceBackend.format` 完全相同的内容，更低的格式与格式 `0` 一样是读不懂的内容（6.x 只对 `0` 如此）；更高的格式照旧绕开。`InMemoryBackend.FORMAT` 为 `2`。
- `ForeignFormatException` 带上本节点的格式：构造函数为 `(cacheName, storageKey, format, nodeFormat, cause)`，`newer` 由两者算出。删除 6.x 的两个构造函数。`SetStore` 读到更新格式的集合时抛出这个异常（仍是 `IllegalStateException`），不再是普通的 `IllegalStateException`。
- SPI：`L1Store.fill` 返回 `L1Fill`，不再返回 `Boolean`：写入时为 `Filled(advanced)`，没有写入时为 `Rejected(currentVersion)`；`fillAll` 逐项返回它。令牌不匹配是 `Rejected(currentVersion = null)`；版本规则见下。`reclaim` 比较 SPI 自己的 `ContentFingerprint`（内容的 SHA-1，由脚本计算），不再收 `Digest`。
- SPI：`L1Store.read`、`readAll` 与 `ConditionalL1Store.readIfChanged` 增加这次调用的 `timeout: Duration?`。`null` 为存储自己的命令超时；给出时必须为正，以命令超时为上限，存储以两者中较短的一个限定这次调用。`FutureL1Store`、它的能力面、`adapt` 与各 `Forwarding*` 类同样增加这个参数。引擎传入缓存的 `CachePolicy.readTimeout`（见下）。
- SPI 约定：取得或交回令牌的命令（`read`、`readAll`、`fill`、`fillAll`、`release`、`reclaim`）失败之后，除非命令在发出之前就被拒绝，存储都异步释放该令牌。令牌已不匹配的 `RELEASE` 什么也不做，所以迟到或重复的释放都无害。存储从不以 `CancellationException` 报告失败：在 JVM 上它就是协程的取消，引擎会把它当作调用方被取消。
- **摘要段**：`Store` 写入的值在头部带载荷的 SHA-256（标志位 bit 1，32 字节），与写入标识并列：这样的值比 `Cache` 的值多 60 字节（[线格式](../architecture/index.md#wire-format)，[RFC 0005 §4.7](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)）。CoCache 在客户端计算它，拿到载荷时校验：`Store` 读到载荷与摘要不一致的值，或不带摘要的值，读取以 `CacheReadException`（`cause` 为 `ForeignFormatException`）失败，key 不被修改。带摘要的值从不取得刷新令牌、从不被回收。`Cache` 或 `VersionedCache` 读到它时计入新指标 `cocache.type.mismatch`（`CacheMetrics.typeMismatches`）并记录一次日志：同一个 namespace 上还有 `Store`。
- `Digest` 是载荷的 SHA-256，带算法标识：`Digest(algorithm, bytes)`（`Digest.SHA_256`）、`Digest(bytes)`、`Digest.ofPayload(bytes)`、`bytes`，`toString()` 输出 `SHA-256:<hex>`。删除 `Digest.sha1`；6.x 保存的摘要（SHA-1）不会再匹配。
- 条件读写（`getIfChanged`、`putIfMatch`、`deleteIfMatch`）只作用于 `Store` 写入的值。没有摘要的值（`Cache` 写入的）、带版本的值、墓碑与被淘汰的带版本 key 都是读不懂的内容：`getIfChanged` 抛出 `CacheReadException`，`putIfMatch` 抛出 `CacheWriteException`，`cause` 都是 `ForeignFormatException`。对没有值的 key，`putIfMatch(key, null, value)` 照常写入。脚本只读头部（`GETRANGE` 与 `STRLEN`）、比较保存的摘要，摘要未变的 `getIfChanged` 在 Redis 中的成本与值的大小无关；Lua 不再读取整个值或计算 SHA-1。`getIfChanged` 返回值时，CoCache 重算摘要，不一致就抛出。
- **带版本的缓存提前刷新**：带版本的值取得刷新令牌，`cocache.defaults.cache.refresh-window` 作用于声明没有写出 `refreshWindow` 的 `VersionedCache`；带 `refreshWindow` 的 `VersionedCacheSpec` 是合法的。刷新只写回不比 Redis 中更旧的版本：loader 返回更旧的版本时，实例保留自己的副本，退避之后再试。写入方推送每一个版本的带版本缓存可以设置 `refreshWindow = off()`（或 `cocache.caches.[name].refresh-window: off`）。
- 带版本的缓存的本地副本从不退回更旧的版本：刷新的令牌被别的读取抢走、或写回失败，而 loader 返回比副本更旧的版本时，实例保留副本，退避之后再试；被丢弃的填充计入 `cocache.stale.discards`。
- 结果未知的带版本写回（填充）按写入处理：实例递增戳、丢掉本地副本（非原子广播时发布失效），因为超时或被排空的 `FILL` 仍可能稍后让版本前进，而它的失效消息在这个实例上被过滤。
- 填充遵循 Redis 中的版本：带版本的内容上，凭刷新令牌或 lease 的填充在版本更旧（或没有版本）时被拒绝，令牌在同一个脚本中清零；版本相等时续期，不发失效消息；版本更新时写入并广播，加载所在的实例按写入处理：丢掉本地副本，不把结果填进 L2。
- 端点的按 key `DELETE`（`CacheAdmin.invalidateEncoded`）在 `VersionedCache` 上保留版本：key 变为仍然拒绝旧版本的淘汰标记（状态 `E`，44 字节，寿命同墓碑）。下一次读取在其上取得 lease；loader 落后时，它的结果照常返回并在 L2 中保留 `degraded-local-max-age`，但不写入 Redis（`cocache.fill.rejections`）。失败时抛出，不在后台重试。见[`evict` 与 `purge`](./versioned-cache.md#evict)。
- 端点的按 key `DELETE` 默认拒绝删除唯一副本：`Store` 与 `SetStore` 需要 `management.endpoint.cocache.allow-store-delete=true`，`VersionedStore` 需要 `management.endpoint.cocache.allow-purge=true`。没有开启时请求返回 `403`，不做任何修改。见[删除唯一副本需要开关](#endpoint-switches)。
- 结果未知的 `Cache.put` 或 `putAll` 在默认的 `invalidate-failure: retry` 下不再抛出，而是登记补偿失效。见[补偿结果未知的写入](#compensated-puts)。
- **修复**：Redis 中已是给出的版本时，`putIfNewer` 与 `invalidateIfNewer` 在所有后端上都发布失效（不写入）；此前只有非原子广播的路径这样做。在 lease 上填充这个版本的加载不广播，写入方自己的 `putIfNewer` 返回 `Stale` 之后，别的实例仍可能持有更旧的副本直到 `local-max-age`。更旧的版本照常不发布。
- SPI：`Envelope.Scalar` 新增 `digest: Digest?`（默认 `null`），由引擎与写入标识一起给出。存储在每个写入标量的操作中把它保存为摘要段，`read`、`readAll` 与 `readIfChanged` 原样返回，不校验、不重算。`readIfChanged` 与 `putIfMatch` 比较保存的摘要，拒绝没有摘要的值与带版本的内容。`L1Fill` 现在承载版本规则：填充让版本前进或替换了淘汰标记、并且存储广播了它（只含这个 key，来源为填充令牌的节点）时为 `Filled(advanced = true)`，版本规则拒绝时为 `Rejected(currentVersion)`。`VersionedL1Store` 与 `FutureVersionedL1Store` 新增 `evictKeepingVersion(key, ttl, frame)`，返回新的 `L1Eviction`（`Evicted`、`Authoritative`、`Unreadable(format)`）。`L1ConditionalRead.Changed` 按值相等。

- Spring：classpath 中有 `spring-tx` 时，starter 的 `cocacheCacheManager` 是 CoCache 的 `TransactionAwareSpringCacheManager`，它的缓存是 `TransactionAwareSpringCache`。它们取代 Spring 的 `TransactionAwareCacheManagerProxy` 与 `TransactionAwareCacheDecorator`，检查或解包这两个类型的代码不再找到它们。事务内的 `@CacheEvict` 现在回滚时同样失效，之后对同一 key 的驱逐取代之前的 `@CachePut`，提交之后的失败交给 `CacheErrorHandler`，不再抛出。不用 Spring Boot 时，用 `TransactionAwareSpringCacheManager` 代替 `TransactionAwareCacheManagerProxy` 包装 `CoCacheSpringCacheManager`。见 [Spring：CoCache 自己的事务装饰器](#spring-transaction-decorator)。
- Spring：`TransactionalInvalidator` 每个事务一份登记，不再每个实例一份。经多个实例与 `evictIfPresent` 对同一缓存登记的失效在提交之后合并为一次 `invalidateAll`，失败一起抛出（第一个异常，其余作为 suppressed）。
- Spring：删除 `CoCacheSpringCacheManager(runtime, executor)` 与 `CoCacheSpringCacheManager.DEFAULT_EXECUTOR`，改用 `CoCacheSpringCacheManager(runtime)`。`CoCacheSpringCache(cache, admin, executor)` 保留：其执行器执行 `retrieve(key, loader)` 的方法体。配置项 `cocache.spring-cache.retrieve-executor` 同样删除：Spring Boot 不再绑定它，设置它也不再告警。

- `SourceLoadCancelledException` 从 `me.ahoo.cocache.coroutines` 移到 `me.ahoo.cocache.core`，并覆盖全部数据源：`CacheLoader` 自己抛出 `CancellationException`，或 `AsyncCacheLoader` 的 future 以它完成（其中的 `withTimeout`、HTTP 客户端取消了请求）时，共享这次加载的每个调用方都以 `SourceLoadCancelledException` 失败，原始异常是它的 `cause`。6.x 中这些调用方收到的就是那个 `CancellationException`，异步调用方的 future 因此看起来像是被取消了。

- starter 不再检查 6.0 删除的配置项（`cocache.redis.strict-failure`、`cocache.redis.eviction-channel`、`cocache.redis.missing-guard-sentinel`、`cocache.metrics.client-side-hits`、`cocache.load.*`、`cocache.next.*`）。它们不再以 `CoCache 6.0 removed these properties` 让启动失败，与其它未知的配置项一样被 Spring Boot 忽略。配置元数据中的相应条目也已删除。
- 运行时的默认阻塞执行器总是每个任务一个虚拟线程（`Executors.newVirtualThreadPerTaskExecutor()`）。为没有虚拟线程的 JDK 准备的后备（最多 512 个 `cocache-blocking-*` 守护线程的线程池）已删除，starter 也不再为 `Executors` 登记原生镜像的反射提示。
- wiki 不再收录 5.x 升级指南（`guide/migration-6`），[6.x 分支](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/zh/guide/migration-6.md)保留它。删除 `cocache-migrate` agent skill，8.x 不提供升级 skill。

- SPI：`InvalidationListener.onInvalidated` 收到的是**编码后的 key**，即存储 key 中 `{keyPrefix}{namespace}:` 之后的部分（`KeyEncoder` 的输出、`ExternalInvalidator` 的参数），不是存储 key。通道用新增的 `Keyspace.encodedKeys(…)` 转换帧中的 key（同时去掉不属于这个 keyspace 的 key），并按订阅的先后依次回调同一 keyspace 的订阅者，因此缓存创建之后加入的订阅者被回调时，缓存已经丢弃了本地副本。自己订阅通道、从 key 中剥掉 `"$namespace:"` 的代码必须改为直接使用收到的 key，否则每一条通知都被静默忽略。`BackendProtocolSpec` 的用例 `listenersReceiveEncodedKeysInSubscriptionOrder` 检查这两条规则。

- Spring：`CoCacheSpringCache.retrieve(key)`（Spring 在返回 `CompletableFuture`、`Mono` 或 `Flux` 的 `@Cacheable` 方法上调用它）在 Redis 没有答案（`Lookup.Unknown`）时与 `get(key)` 一样以 `CacheReadException` 失败，不再当作未命中。三种方法上 Spring 都把失败交给 `CacheErrorHandler`；`unknownRead = ABSENT` 仍然读作未命中。见 [Redis 故障时](./spring-cache.md#failures)。
- Spring：缓存操作（`@Cacheable`、`@CachePut` 或 `@CacheEvict`，不论 `sync`）指向不是 `Cache` 的 CoCache 缓存（`Store`、`VersionedStore`、`SetStore`、`VersionedCache`）时启动失败。之前要到第一次调用才以“找不到缓存”失败。见[启动检查](./spring-cache.md#sync-check)。
- Spring：默认关注 Spring 读写事务内的加载（见下一节）。回滚的事务现在失效它加载的内容，事务内驱逐过的 key 在该事务内私有加载：设置 `cocache.invalidate-fills-on-rollback: false` 与 `cocache.fill-in-transaction: allow` 可保持 6.x 的行为。

### 新增：Spring 事务内的加载 {#transactional-loads}

见 [Spring Cache：事务内的回源](./spring-cache.md#transactional-loads)。

- 读写事务内带数据源的同步读取（`@Cacheable(sync = true)`、`get`、`lookup`、`getAll`、`get(key, loader)`）可能加载到事务写入、却永远不会提交的行。CoCache 现在记录这类加载写入 Redis 或 L2 的每个 key（包括因 Redis 故障只写入本实例 L2 的填充），在事务回滚、提交结果未知或 `NESTED` 事务回滚到 savepoint 时失效它们（`cocache.invalidate-fills-on-rollback`，默认开启）。6.x 中除非事务内驱逐过这个 key，这一行会留在 Redis 中直到 `ttl`。
- `cocache.fill-in-transaction`（默认 `registered-keys`）：一个 key 在事务中登记失效之后，事务内再次加载它是私有的：不申请 lease、不写 Redis 与 L2、不与其它调用方共享。登记时还会立即丢弃本实例的副本。`private` 让写事务内的全部加载都私有，`allow` 关闭这一机制。私有加载计入新增的指标 `cocache.fills.gated`（`CacheMetrics.fillsGated`）。
- 这类事务内的调用方领头加载时，同一实例上并发的调用方不再拿它的结果，而是各自加载。
- 只读事务、异步与挂起调用、提前刷新与响应式事务都不受关注。读取未命中 L2 时，在调用方线程上读一次事务状态。
- 登记到事务的 key 按 namespace 与编码后的 key 匹配，所以经接口缓存的代理调用 `TransactionalInvalidator`，同样取代经 Spring Cache 对同一 key 的 `@CachePut`。
- `cocache-core` 新增钩子 `FillObserver`、`FillScope` 与 `FillKey`，经 `CacheRuntime.Builder.fillObserver(…)` 设置（默认 `FillObserver.NONE`）。`cocache-spring` 的实现是 `TransactionalFillObserver`，classpath 中有 `spring-tx` 时 starter 把它注册为 `cocacheFillObserver`。

### 新增：按缓存的读取时限与只广播的失效

- `CachePolicy.readTimeout`（`CachePolicy.Builder.readTimeout(…)`，默认不设置）限定缓存的每次 Redis 读取（`get`、`getAll`、`getIfChanged`），存储以它的命令超时为上限。超时按 Redis 读取失败处理：带 loader 的读取由 `read-failure` 决定，不带 loader 的读取为 `Unknown`（由 `unknownRead` 决定），`getIfChanged` 抛出，读取取得的 lease 被释放。用于不能回退到 loader、又不能在 Redis 卡顿时等满 `command-timeout` 的缓存，例如撤销表。它的配置项是 `read-timeout`（见[三层配置](#three-layer-config)）。`EngineSpec` 增加可选的 `pause()` 钩子，用例 `readTimeoutBoundsEveryL1Read` 检查它。
- `ExternalInvalidator.broadcast(namespace, encodedKeys)` 只发布失效消息、不修改 Redis：各实例丢弃本地副本并从 Redis 重新读取。Redis 中已是正确的值时使用它。`invalidate` 会删除 key，用在没有 loader 的缓存的命名空间上就是删除唯一的一份数据。
- 以空的 `keyPrefix` 创建缓存时记录告警：它的 key 与频道没有公共前缀，ACL 与按前缀的运维只能逐个写出命名空间。

### 行为变化：挂起视图直接调用缓存的核心 {#suspending-native}

挂起视图不再包装异步视图，而是直接调用缓存的核心（[RFC 0005 §4.4](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)）。

- L2 命中返回时不挂起、不切换 dispatcher、不分配 future。
- 未命中、写入与条件读取从第一次挂起起在 CoCache 的执行器上执行，调用方从 CoCache 的线程上恢复，从不从 Redis 客户端的线程上恢复。用 `Dispatchers.Unconfined` 的调用方（Spring WebFlux 处理器）因此不会再在 Lettuce 的事件循环上继续执行。
- 调用方的上下文元素（追踪上下文、`MDCContext` 等 `ThreadContextElement`，协程名）随共享加载传递，不带调用方的 `Job` 与 dispatcher；声明的 `AsyncCacheLoader` 现在也看得到它们。
- 调用级 loader 在这次加载之中执行：在调用方线程上开始，之后在 CoCache 的执行器上继续（之前：在调用方的 dispatcher 上执行，没有 dispatcher 或在 `runBlocking` 的事件循环上时在 `Dispatchers.Default` 上）。`load-timeout` 到期与运行时关闭都会取消它。阻塞调用请包在 `withContext(Dispatchers.IO)` 中。
- 挂起方法抛出原始的异常实例，协程的调试模式下也是。

### 行为变化：关闭运行时会等待在途的 Redis 步骤

- `CacheRuntime.close()` 在关闭执行器之前至多等待 5 秒，让在途的异步调用做完不能跳过的 Redis 步骤：释放 lease、后端不支持原子广播时写入之后的广播，以及已经发出的命令。后端要晚于运行时关闭；Spring Boot starter 已经如此，因为运行时 bean 依赖后端。之前 `close()` 立即返回，紧接着关闭的后端可能让这些步骤失败：其它节点的本地旧副本留到 `local-max-age`，lease 留到 `lease-ttl`。同步调用在调用方自己的线程上运行，不在等待之列。

### 行为变化：补偿结果未知的写入 {#compensated-puts}

- 结果未知（超时、应答途中断线）的 `Cache.put` 或 `putAll`，在 `invalidate-failure` 为 `retry`（派生缓存的默认值）时不再抛出。它的值现在或之后可能在 Redis 中，所以 CoCache 用与失败的 `invalidate` 相同的重试队列登记这些 key 的失效，这些 key 收敛到“从数据源重新加载”（[RFC 0005 §4.6](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)）。登记期间，该实例上的未命中跳过 Redis。迟到的写入晚于补偿的删除执行时，它的值保留到自己的 TTL。
- 确定未执行的写入（`CacheWriteException.outcomeUnknown = false`）照常抛出，不登记任何东西。`invalidate-failure: throw` 时，失败的写入一律抛出。`Store` 与 `SetStore` 的写入从不补偿。
- 告警日志为 `Cache[…] - put […] failed, retrying the invalidation.`（失败的 `invalidate` 为 `invalidate […] failed, retrying the invalidation.`）。

### 新增：`@CoCache(unknownRead)`

- `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)` 在接口式声明上设置 spec 的 `unknownRead`：不带 loader 的读取遇到 Redis 故障时返回 `null`，而不是抛出（[`Unknown`](./caches.md#unknown)）。它用于 `Store`、`VersionedStore` 与 `source = EXTERNAL` 的 `Cache`；其它接口上的 `ABSENT` 会启动失败。

### 行为变化：删除唯一副本需要开关 {#endpoint-switches}

- `Store` 或 `SetStore` 上的 `DELETE /actuator/cocache/{name}/{key}` 需要 `management.endpoint.cocache.allow-store-delete=true`，`VersionedStore` 上的（即 purge）需要 `management.endpoint.cocache.allow-purge=true`。两者默认都是 `false`；没有开启时请求返回 `403`，说明要开启的开关，不做任何修改。失效 `Cache` 的 key 与淘汰 `VersionedCache` 的 key 不需要开关（[可观测性](../operations/observability.md#invalidate-key)）。
- `DELETE /actuator/cocache/{name}/{key}?purge=true` 清除 `VersionedCache` 或 `VersionedStore` 的 key 并丢掉版本，需要 `allow-purge`；其它类型返回 `400`。
- 按 key `DELETE` 的报告新增字段 `deletion`（`INVALIDATE`、`EVICT`、`DELETE`、`PURGE` 或 `CLEAR`），`CacheAdmin` 新增 `keyDeletion` 与 `purgeEncoded(encodedKey)`。

### 行为变化：被中断的加载的等待者改为自己加载

- 多个读取等待同一次加载、而执行它的线程被中断（或它的协程被取消）时，这个线程仍收到原始异常；等待的读取不再随之失败，而是各自重试一次，再合并到被中断的加载时就自己加载。批量读取对受影响的 key 同样处理。6.x 中所有等待者都以被中断线程的异常失败。
- 只有执行加载的线程被中断、或它的协程被取消时，才算加载被中断。其余失败与 6.x 一样交给等待的读取，包括 loader 自己的 `CancellationException`（见上面的 `SourceLoadCancelledException`），以及调用方线程之外的线程上的 `InterruptedException`。

### 性能：L2 命中不再拼接 Redis key

- 本地（L2）副本改以编码后的 key 为 key，不再用完整的 Redis key（`{keyPrefix}{namespace}:{key}`），由 L2 回答的读取因此不再拼接、哈希一个新字符串。命中路径基准中，单个缓存的 L2 命中吞吐约为之前的 2.6 倍（同步、异步与接口代理都一样），在三个配置不同的缓存之间交替读取时 1 线程快 68%、8 线程快 8%。L1 命中不变。Redis 中的 key 与失效消息不变。

### 修复：运行时关闭时不再放弃异步读取的 Redis 命令

- `CacheRuntime` 关闭时，正在等待 Redis 的异步读取会立即停止等待，并发出一条不等待结果的释放。后端不按发出顺序执行命令时（或遇到 Redis Cluster 重定向），释放可能先生效，读取随后取得的 lease 要到 `lease-ttl` 才回收，其间其它实例对这个 key 的未命中都要等 `lease-wait`。现在读取等到命令完成、释放取得的令牌，再像以前一样以 `IllegalStateException("CacheRuntime closed.")` 失败。配置了 `LoadObserver` 时，等待 lease 的重读取得的 lease 同样被释放。

### 修复：写入返回之后不再出现旧的本地副本

- 在并发写入之前刚读过 Redis 的加载，仍可能在写入返回之后把旧值写进本地（L2）副本，直到它自己的复核撤销为止；这段时间里本实例紧接着写入的读取可能读到旧值。现在本地副本在 key 的锁内一步完成校验与写入，写入返回之后，本实例的本地副本不会再出现更旧的值。

### 修复：批量读取中一个 key 重新加载失败，不再拖累整批

- `getAll` 先整批回源并写回，再逐个回收损坏的 key。损坏的 key 重新加载失败时，其余 key 照常写回 Redis（或释放 lease），整批随后以第一个失败结束。6.x 的阻塞路径先回收损坏的 key、失败即中断，其余 key 的 lease 留到过期，其它实例读这些 key 时要先等 `lease.wait`。

### 修复：Spring 事务不再丢失失效

`6.x` 分支同样修复。见 [Spring Cache 的事务](./spring-cache.md#transactions)。

- **事务内的 `@CacheEvict(beforeInvocation = true)`**：Spring 对它调用 `evictIfPresent`，Spring 的 `TransactionAwareCacheDecorator` 直接委托这个调用，所以 key 只在提交之前失效。驱逐与提交之间，另一个实例上的读取可能把已提交的旧行重新加载进 Redis，之后再没有失效，所有实例在 `ttl` 内都读到旧值。`evictIfPresent` 仍按 Spring 的要求立即失效，事务内现在还把 key 登记到 `TransactionalInvalidator`，提交、回滚或回滚到 savepoint 时再失效一次。没有事务或没有 `spring-tx` 时行为不变。
- **回滚到 savepoint（`NESTED`）**：嵌套事务回滚到 savepoint 时，Spring 不对外层事务的同步调用 `afterCompletion`，外层之后提交时报告的是提交。嵌套段内的读取放进 Redis 的未提交行，在外层事务结束之前对所有实例（包括外层事务自己）可见。`TransactionalInvalidator` 现在处理 `savepointRollback`（Spring 6.2 起）：失效本事务到目前为止登记的全部 key，登记保留，事务结束时再失效一次。这里的失败只记录日志、不抛出：Spring 在把数据库回滚到 savepoint 之前调用 `savepointRollback`，抛出会跳过这次回滚。
- `6.x` 分支上，嵌套事务内的 `@CachePut` 在嵌套事务回滚到 savepoint 之后，仍会在外层事务提交时写入它的值。8.0 丢弃它，见下一节。

### Spring：CoCache 自己的事务装饰器 {#spring-transaction-decorator}

见 [Spring Cache 的事务](./spring-cache.md#transactions)。

- **`@CacheEvict` 回滚时同样失效**。Spring 的装饰器只在提交之后执行它。同一事务内的 `@Cacheable(sync = true)` 读取可能把未提交的行加载进 Redis，回滚之后这一行留到 `ttl`；6.9.0 changelog 把它列为不覆盖的情形。现在 key 登记到事务，提交之后与回滚之后都失效。
- **后面的驱逐优先**。提交时先执行登记的失效，再执行推迟的、之后没有同一 key 失效的 `@CachePut` 写入。对同一个 key 先 `@CachePut` 再 `@CacheEvict`，结果是 key 被驱逐。
- **savepoint**：`NESTED` 事务或编程式 savepoint 回滚时，丢弃 savepoint 之后登记的写入。此前回滚了的嵌套事务里的 `@CachePut` 仍在外层提交时写入。
- **提交之后的失败交给 `CacheErrorHandler`**（缓存拦截器的，与事务之外一样），不再抛给数据已经提交、会去重试的调用方。处理器重新抛出时（Spring 默认的处理器就是如此），以 `ERROR` 记录日志，不再抛出。Spring 也因此照常调用其余同步的 `afterCommit`，抛出时它们会被跳过。
- `@CacheEvict(allEntries = true)` 仍只清空本实例的 L2，现在每个缓存第一次调用时记录一条 `WARN`。全集群清空随之后 8.x 的 namespace 重置加入。
- 缓存通知与事务通知的 order 相同时，starter 在启动时记录一条 `WARN`。两者默认都是 `LOWEST_PRECEDENCE`，谁在外层取决于注册顺序。声明 `@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)` 让事务在外层。见[事务通知在外层](./spring-cache.md#advice-order)。

### Spring：可选的降级 `CacheErrorHandler`

- `cocache.spring-cache.degrade-on-error: true` 为 Spring 的缓存拦截器换上 `DegradingCacheErrorHandler`。它只对 CoCache 缓存生效：读写失败只记录日志、不重新抛出，Redis 故障期间不带 sync 的 `@Cacheable` 因此执行方法，与 `sync = true` 一样。失败的失效与清空、其它缓存的失败仍交给原来配置的处理器，它成为委托。见 [Redis 故障时](./spring-cache.md#failures)。

### Redis：存储原生挂起

- `RedisBackend.store` 返回原生挂起的存储：以 Lettuce 的异步命令发出，等待期间不占线程；`readAll` 与 `fillAll` 先发出全部命令再一起等待。失效通道的 `publish` 同样挂起。命令、脚本与线格式不变。
- **Lettuce 取消的命令按 Redis 故障处理。** Lettuce 在重置或关闭连接时取消的命令，现在以 `RedisException` 失败，cause 是那次取消。6.x 中它以 `CancellationException` 到达核心，被当作调用方自己的取消：读取不降级回源，等待它的读者各自加载。
- **断线之后 lease 更早收回。** 读取、回收、填充或释放失败时，除非命令在发出之前就被拒绝，现在都释放它的令牌；断线期间被拒绝的释放按退避（100 ms 起倍增，至多 2 s）重发，直到执行、令牌过期或后端关闭。6.x 只在超时或中断时释放，所以断线时在途的读取取得的 lease 要留到 `lease-ttl`，期间全集群对该 key 的未命中都要先等 `lease-wait`。
- **修复：断线导致失败的批量读取会释放其余的 lease。** Lettuce 以同一个异常结束全部在途的命令。6.x 中 `getAll` 因此抛出 `IllegalArgumentException: Self-suppression not permitted`，而不是 Redis 的错误，批量中其余读取取得的 lease 留到 `lease-ttl`；批量写入（`putAll`、`invalidate`）同样抛出这个 `IllegalArgumentException`。现在抛出的是 Redis 的错误，lease 也被释放。
- 应答的解析（解析头部、拷贝值或集合成员）在阻塞 API 的调用方线程上、异步 API 的运行时执行器上进行，不再在 Lettuce 的 I/O 线程上。
- 跨多个组的批量写入（`putAll`、`invalidate`）同时发出全部组，失败时约等一个 `command-timeout`，不再每组各等一个。

### TCK

- **破坏性变更：** 每个套件（`BackendProtocolSpec`、`EngineSpec`、`SetEngineSpec`、`MultiNodeSpec`、`FaultSpec`、`VersionedSpec`）都有抽象的 `expectedCapabilities: Set<L1Capability>`（`CONDITIONAL`、`VERSIONED`、`SETS`），没有默认值：没有声明它的子类不再能编译。每个套件新增的 `storeProvidesExactlyTheDeclaredCapabilities` 在以下情形失败：存储提供了没有声明的能力、缺少声明了的能力，或 `conditional()`、`versioned()`、`sets()` 再次调用时返回另一个对象。需要某个能力的用例只在它没有声明时跳过：没有 `SETS` 或 `VERSIONED` 时，`SetEngineSpec` 与 `VersionedSpec` 跳过全部用例。删除 `EngineSpec.supportsConditionalWrites`，改为不声明 `CONDITIONAL`。`L1Capability` 只存在于 TCK 中：存储的访问器仍是表达能力的唯一方式。见[声明能力](../architecture/extending.md#expected-capabilities)。
- `FaultSpec.cancelledCommandsAreL1FaultsNotCancellations` 检查存储底下的客户端取消的命令（`FutureL1Store` 取消的 future、Lettuce 的连接重置）：读取回退到 loader，并把结果交给等在它上面的读取方；`put` 以 `CacheWriteException` 失败；没有调用方看到 `CancellationException`；L1 恢复之后，被取消的读取的 lease 已不存在。覆盖新钩子 `commandCancellation()`（返回 `CommandCancellation`：`hold`、`cancel`、`close`）时运行，默认的 `null` 跳过它。CoCache 在经 `FutureL1Store.adapt` 的内存后端与单机 Redis 上运行它。`HookedBackend.puts` 为单 key 的 `put` 计数。
- CoCache 在经 `FutureL1Store.adapt` 的内存后端（Java 后端走的路径）与没有任何可选能力的存储上运行整套 TCK。两种视图现在调用同一个存储，所以 Redis 上的异步视图只在经典 Pub/Sub 上把每个引擎套件重跑一遍。
- **破坏性变更：** TCK 删除了 `AsyncEngineSpec`、`AsyncSetEngineSpec`、`AsyncMultiNodeSpec`、`AsyncFaultSpec` 与 `AsyncVersionedSpec`。改为继承 `EngineSpec`、`SetEngineSpec`、`MultiNodeSpec`、`FaultSpec` 或 `VersionedSpec`；还想经异步视图运行某个套件时，覆盖 `access = CacheAccess.ASYNC`。异步专有的用例（取消、完成线程、等待不占线程、超时、重入）测的是核心而不是后端，已移到 CoCache 自己的测试中。
- **破坏性变更：** `BackendProtocolSpec` 改为挂起形状：用例经 `Suspending.await`（TCK 新增，只用 Kotlin 标准库）直接调用 `L1Store`，集合成员的用例要求 `sets()`。删除 `AsyncBackendProtocolSpec`；Java 后端对 `FutureL1Store.adapt` 返回的存储运行 `BackendProtocolSpec`。`HookedBackend` 以 `ForwardingL1Store` 与 `Forwarding*` 能力面包装存储，只保留被包装的存储有的能力面；钩子在调用所在的协程里、被包装的存储返回之后执行，`ioExecutor` 送达每个调用的结果。`HookedBackend.versionedWrites` 随 SPI 属性一起删除。
- **破坏性变更：** TCK 随上面的 SPI 变化：`L1Fill`、`ContentFingerprint`、`timeout` 参数与 `L1*` 结果类型。`HookedBackend.afterFill` 收到 `L1Fill` 结果，不再是 `Boolean`。
- **破坏性变更：** TCK 随摘要段与状态 `E` 变化。`BackendProtocolSpec.writerIdAndVersionRoundTripThroughEveryScalarWrite` 改名为 `segmentsRoundTripThroughEveryScalarWrite`，同时检查摘要；新用例检查带摘要的值不取得刷新令牌、从不被回收，条件读写比较保存的摘要并拒绝没有摘要的值，`E` 上的 lease 在超过 lease 寿命之后被抢占。`VersionedSpec.versionedContentIsNeverRefreshedOrReclaimed` 改名为 `versionedContentIsRefreshedButNeverReclaimed`，新用例覆盖填充的版本规则、`E` 上的每个脚本、`evictKeepingVersion`、带版本的缓存的提前刷新与按 key 淘汰。`EngineSpec` 检查只有 `Store` 写入摘要、它的读取遇到被篡改或缺失的摘要时失败、`Cache` 计入类型不符、条件读取校验它返回的值；`writeRemoteSilently` 新增可选的 `digest` 参数，`writeAuthoritativeSilently` 写入带摘要的值。
- `BackendProtocolSpec` 增加 `pause()` 钩子，新用例 `readTimeoutBoundsTheCallAndReleasesItsLease` 检查：L1 卡顿时，带短 `timeout` 的读取在大约这么长的时间内以 L1 故障失败，它请求的 lease 在之后被释放。`pause()` 返回 `null`（默认）时跳过。`InMemoryBackend` 为此增加 `stall()` 与构造参数 `commandTimeout`：卡顿期间的操作最多等它的时限，超时的操作永远不执行。
- `Concurrency.currentThreadName()` 返回去掉 kotlinx 协程后缀的线程名。`HookedBackend.issuer()` 与各规范都用它，按线程名编排竞态的钩子不论是否开启断言都能工作。
- `EngineSpec.corruptKeyFailingItsReloadStillCompletesTheRestOfTheBatch` 覆盖上面的批量修复，`EngineSpec.getAllAfterOwnInvalidateNeverSharesALoadStartedBefore` 覆盖批量读取的读己之写。
- `Concurrency.Forked.parked` 在线程等待时（`WAITING` 或 `TIMED_WAITING`）为真。阻塞调用的跟随者现在在 `runBlocking` 中等待，以限时停顿挂起，所以各规范不再只认 `WAITING`。
- `BackendProtocolSpec` 用“检查并写入”的操作（`SetL1Store.addMembers` / `removeMembers`）写集合成员。新增的 `memberWritesRefuseUnreadableContentAndKeepIt` 检查标量值上的元素写入返回其格式版本、不修改任何东西，取代 `removeMembersOnScalarIsNoOp`；`membersAreAddedAtomicallyOverAnyNonSetState` 改名为 `membersAreAddedAtomicallyOverLeases`。
- `EngineSpec` 与 `SetEngineSpec` 删除没有数据源时的 `reclaim` 用例。`EngineSpec.conditionalOperationsFailOnUnreadableContent` 覆盖条件读写，并检查它们从不回收。随着 `foreign-format` 删除，`EngineSpec` 删除 `foreignContentWithLoaderIsReclaimedWhenConfigured` 与 `foreignContentFailsWithLoaderWhenConfigured`。`SetEngineSpec.unrecognizedContentFailsByDefault` 与 `SetEngineSpec.memberWritesOverUnreadableContentFailByDefault` 去掉 `ByDefault` 后缀。
- `BackendProtocolSpec.writerIdAndVersionRoundTripThroughEveryScalarWrite` 检查存储经 `put`、`putAll`、`fill`、`fillAll`、`putIfMatch` 与 `putIfNewer` 保存 `Envelope.Scalar.writer`（声明了 `VERSIONED` 时还有 `version`），`read` 与 `readAll` 原样返回它们。`BackendProtocolSpec.putIfNewerRejectsNoVersion` 检查 `putIfNewer` 拒绝 `Versioned.NO_VERSION` 且不修改任何东西。`EngineSpec.onlyStoresWriteAWriterIdAndADigest` 与 `VersionedSpec.onlyVersionedStoresWriteAWriterId` 检查只有 `Store` 与 `VersionedStore` 写入它。

## v6.10.0（当前版本）

**模块组：** `me.ahoo.cocache` · 次版本（新增一项配置与 `CacheRuntime.policy`），也是 6.x 的最后一个功能版本：之后的 6.x 版本只修缺陷。修复 6.8.0 架构审查中余下的 6.x 项，并隔离失败的失效监听器。没有删除任何 API。Redis 线格式与失效频道不变，可以与任何 6.x 实例共用 Redis，逐台升级。

### 行为变化：`SetCache` 驱逐检查不再静默跳过

- 启动检查从 `INFO memory` 读取 `maxmemory-policy`，不再用 `CONFIG GET`。禁用 `CONFIG` 的托管服务通常允许 `INFO`。使用 `SetCache` 时 Redis 用户需要 `+info`，[ACL 起点](../operations/index.md#acl)已加上它。
- 仍然读不到策略时不再静默跳过：设置了新增的 `cocache.redis.assume-maxmemory-policy` 时按它判断，否则记录告警，`cocache.redis.set-cache-eviction-check: fail` 时启动失败。要在禁止 `INFO` 的 Redis 上保留 `fail` 模式，请授予 `+info`，或把 `assume-maxmemory-policy` 设为部署实际使用的策略。
- 检查所有已声明的 `SetCache`，包括启动时尚未创建的延迟初始化 bean（如 `spring.main.lazy-initialization=true`）。它们的 `ttl` 与创建时一样叠加注解、定制器与配置文件得到。检查不会创建它们。
- `volatile-*` 下为每个 `ttl` 有限的 `SetCache` 告警：Redis 可能在到期前淘汰它。这只是告警，`fail` 模式下也一样。
- 见 [maxmemory-policy](../operations/index.md#maxmemory-policy)。

### 行为变化：端点对未知缓存的 `DELETE` 返回 `404`

- 缓存名不存在时，`DELETE /actuator/cocache/{name}` 与 `DELETE /actuator/cocache/{name}/{key}` 返回 `404`，不再返回 `204`，拼错的缓存名不再看起来成功了。已知缓存仍返回 `204` 与 `200`。
- `CoCacheEndpoint.clearLocal` 返回 `Boolean`（缓存是否存在），不再返回 `Unit`。经 JMX 调用时返回这个值。

### 修复：抛出异常的失效订阅者不再让其它订阅者错过消息

- 失效订阅者抛出 `Error`（或 Kotlin 代码抛出的受检异常）时，排在它之后的订阅者照常收到这条消息。6.9.1 及之前 Redis 通道对每个订阅者只兜住 `RuntimeException`；现在兜住任何 `Throwable`，记录后继续，只重新抛出 `VirtualMachineError`。重置本来就互不影响。
- 内存后端（`InMemoryBackend`，供测试使用）同样隔离订阅者。订阅者的异常不再传给发布这条消息的 `put` 或 `invalidate` 的调用方。

### 修复：被中断的等待者停止等待

- 合并到别人对同一 key 的加载的同步调用（`get`、`getAll`）现在响应中断：抛出 `InterruptedException` 并保留中断标志，只有这个调用方停止等待，加载照常进行，其它等待者照常拿到结果。6.9.1 及之前等待者在加载结束前忽略中断，`Future.cancel(true)`、`TimeLimiter` 或 `ExecutorService.shutdownNow` 都停不下它。
- 经 `@CoCache` 接口调用时，这个 `InterruptedException` 被 JDK 包装为 `UndeclaredThrowableException`：JDK 对接口方法没有声明的受检异常都这样处理（见下）。

### 修复：L2 副本不受墙钟跳变影响

- 默认时钟启动时读一次墙钟，之后按 `System.nanoTime()` 推进。6.9.1 及之前每 100 ms 重读墙钟，墙钟回拨一小时（NTP 校正、人工调整）会让 L2 副本多活一小时，向前跳则让它提前到期。命中路径仍然只是一次 volatile 读。

### 修复：创建缓存失败后可以重试

- 创建缓存时 `backend.store` 或构造引擎失败，namespace 会一直被占用，重试同一个缓存以 `Namespace[…] is already used by another cache.` 失败。现在会释放 namespace，并关闭已创建的存储。

### 文档

- `Subscription.close()` 不等待已在投递中的回调，这样的回调可能在 `close()` 返回之后才到达。它只保证之后到达的消息与重置不再投递。见[扩展 CoCache](../architecture/extending.md)。
- [部署](../operations/index.md#channel-mode)写明了没有原子广播时（`sharded` 模式，或探测降级之后）写入成功、随后的发布失败意味着什么。
- `@CoCache` 接口的代理原样传递异常，只有 JDK 另有规定的情形例外：接口方法没有声明的受检异常（如 Kotlin loader 抛出的 `IOException`）被包装为 `UndeclaredThrowableException`，原始异常是它的 `cause`。spec 声明注入的是引擎本身，不受影响。见[架构](../architecture/index.md)。

### API

- `CacheRuntime.policy(name)` 返回已创建缓存生效的 `CachePolicy`。
- 新增配置 `cocache.redis.assume-maxmemory-policy`（见[配置](./configuration.md)）。

## v6.9.1

**模块组：** `me.ahoo.cocache` · 修订版本：补全 6.9.0 两项 Redis 修复漏掉的路径，由 RFC 0005 第二轮审查发现（附录 B）。API、线格式与频道都不变，6.9.1 实例可以与任何 6.x 实例共用 Redis，逐台升级。

### 修复：被中断的 Redis 调用

- **被中断的脚本**：等待 Redis 时调用方线程被中断，按超时处理：脚本之后永远不会再写入 Redis，调用以 `RedisException("Interrupted while waiting for Redis.")` 失败，中断标志保留。6.9.0 中被中断的 `put` 仍可能迟到执行（Redis Cluster 中等待节点连接时），覆盖更新的值。中断来自 `Future.cancel(true)`、Resilience4j `TimeLimiter` 或 `ExecutorService.shutdownNow`。中断标志在时，同一线程上之后的等待都立即以同样的方式失败，例如 `getAll` 其余的 key。
- **读取被中断后的 lease**：等待被中断的读取在后台尽力释放它可能已取得的 lease 或刷新令牌，与超时的读取相同。`getAll` 中所有尚未返回的 key 都会释放。6.9.0 中这些 key 的 lease 留到 `lease-ttl`，其它实例对它的每次未命中都先等 `lease-wait`。

### 修复：填充失败后的 lease

- 超时或等待被中断的填充（回源结果写回 L1）在后台尽力释放它的 lease。6.9.0 中发出之前就被丢弃的填充，lease 留到 `lease-ttl`。填充已经执行时，释放找不到匹配的令牌，什么也不改变。断线时被拒绝的填充，lease 仍由 `lease-ttl` 回收。
- 批量填充（`getAll`）在第一个失败处停止，放弃其余的填充：释放它们的 lease，不再逐个等待，失败的批量最多等待一个 `command-timeout`。
- 超时或被中断的 lease 释放会再发一次：它可能在发出之前就被丢弃。

## v6.9.0

**模块组：** `me.ahoo.cocache` · 次版本：没有 loader 的缓存（Redis 是唯一副本）有两项默认值变化；另有对 6.8.0 架构审查发现的修复：事务内的失效、提前刷新、Redis 命令超时与订阅，以及条件写入与按版本写入的一项广播修复。没有删除任何 API。`CachePolicy.invalidateFailure` 与 `CachePolicy.readFailure` 改为可空，JVM 签名不变，已编译的调用方照常运行。Redis 线格式与失效频道不变，6.9.0 实例可以与任何 6.x 实例共用 Redis，逐台升级。

### 行为变化：没有 loader 的读取在 Redis 故障时失败

- `read-failure`（`CachePolicy.readFailure`）默认改为未设置，按这次读取决定：有 loader 时与以前一样是 `degrade`，没有 loader 时是 `throw`。显式设置的 `degrade` 或 `throw` 照常生效。
- 因此默认情况下，没有 loader 的读取（没有 loader 的 `Cache` 经 `get`、`lookup` 或 `getAll` 读取）在 Redis 读取失败时抛出 `CacheReadException`，也不在 L2 中缓存任何东西。这样的读取没有可降级的数据源：跳过 Redis 只能得到“不存在”。这与 `getIfChanged` 和 `SetCache` 一致。
- 把“读不到”当作安全答案的无 loader 缓存（如撤销表：Redis 故障即视为未撤销）显式设置 `read-failure: degrade`。读取随后返回 `null`（`getAll` 中缺席，`lookup` 返回 `Absent`），计入 `cocache.remote.failures`，但不写入 L1 或 L2，故障期间的每次读取都会再次访问 Redis。
- 6.8.0 及以前，读取返回 `null`，并把这个 `null` 作为负缓存在 L2 中保留 `degraded-local-max-age`（10 秒）。故障期间调用方把存在的 key 读成不存在；恢复后的这 10 秒内 `get(key)` 仍返回 `null`，`get(key, loader)` 也返回 `null`、不调用它的 loader。显式设置 `degrade` 不会恢复这份缓存。
- `get(key, loader)` 有数据源，照常降级。它合并到一次失败的、没有 loader 的读取时，改用自己的 loader 加载，而不是跟着失败。
- 没有 loader 时其余跳过 Redis 的读取（等待 lease 超时、失效仍在重试、更高格式版本且 `foreign-format: reclaim`、回收失败）仍返回 `null`，但不再把它缓存为负缓存，`get(key, loader)` 因此不会被它挡住。
- Spring Cache：不带 sync 的 `@Cacheable` 是没有 loader 的读取，Redis 读取失败会交给 Spring 的 `CacheErrorHandler`（见 [Spring Cache](./spring-cache.md#failures)）。

### 行为变化：没有 loader 时 `invalidate-failure` 默认为 `throw`

- `invalidate-failure`（`CachePolicy.invalidateFailure`）默认改为未设置，按缓存决定：有 loader 时与以前一样是 `retry`，没有 loader 时是 `throw`。显式设置的 `retry` 或 `throw` 照常生效。
- 没有 loader 时延迟的重试会丢失更新：实例 A 的 `invalidate` 失败、进入重试队列；实例 B 随后 `put` 写入这个 key；约 100 ms 后 A 的重试删掉了 B 的值。有 loader 时删除总是安全的，下一次读取会重新加载。
- Spring Cache 的缓存通常没有 loader，`@CacheEvict` 失败因此改为抛出 `CacheWriteException`（经 Spring 的 `CacheErrorHandler`），不再重试。那里被注解的方法就是数据源、删除是安全的：在这些缓存上设置 `invalidate-failure: retry` 即可保持 6.8 的行为。
- `CachePolicy.invalidateFailure` 与 `CachePolicy.Builder.invalidateFailure` 的类型改为 `InvalidateFailureMode?`，`readFailure` 改为 `ReadFailureMode?`。读取这个属性的 Kotlin 代码看到可空类型。针对 6.8 及以前编译的二进制（包括 6.4 的构造函数）得到新的默认值。

### 修复：没有原子广播时，重放的条件写入与按版本写入会广播

- 没有原子广播时（`sharded` 频道模式，或启动探测回退时），`putIfNewer` 或 `putIfMatch` 可能已写入 Redis，而单独的发布失败了。调用方得到 `CacheWriteException` 并按文档重放，但重放返回 `Stale(version)` 或 `Conflict(自己的摘要)`，不发布任何消息，其它实例的 L2 副本一直陈旧到 `local-max-age`。
- 现在 `putIfNewer` / `invalidateIfNewer` 返回的 `Stale` 版本等于给出的版本时，以及 `putIfMatch` 冲突且当前摘要等于自己的值的摘要时，引擎也发布失效。多余的失效只会丢弃副本。更旧的版本与其它冲突仍不发布。原子广播不变。

### 修复：事务内的失效

以下修复针对 `TransactionalInvalidator`，见[事务](./freshness.md#transactions)。

- **回滚**：事务回滚、或提交结果未知时，现在同样失效本事务中登记的 key。事务内的加载在调用方线程上执行，可能读到本事务未提交的行，并凭 lease 写入 Redis；6.8.0 中回滚会让这个值在 Redis 中留到 `ttl`。
- **迟到的登记**：在另一个同步的 `afterCommit` 中登记、或在本类的同步执行之后才登记的失效，不再丢失，而是在 `afterCompletion` 中执行，或立即执行。排在前面的同步的 `afterCommit` 抛出时同样如此。
- **失败**：一个缓存失败不再影响其余缓存。全部登记的缓存都失效之后，抛出第一个异常，其余作为它的 suppressed。`afterCompletion` 中的异常（回滚、兜底）由 Spring 记录日志，不抛给调用方。
- **不覆盖的情形**：`@CacheEvict` 与 `@CachePut` 经 Spring 的 `TransactionAwareCacheDecorator`，它只在提交之后执行，所以回滚时仍不执行。响应式事务（R2DBC、`@Transactional` 挂起函数）本类看不到，会在提交之前立即失效；这类事务请在事务结束之后失效。

### 修复：提前刷新

- **没取得令牌时退避**：没取得刷新令牌的实例，再次读取 Redis 之前先退避，退避时长取 `max(lease-wait, 100 ms)` 与值剩余寿命的一半中较小的一个。以前另一个实例回源期间，它每次命中都重读 Redis；那个实例崩溃时，这种重读会持续到 `lease-ttl`。现在刷新期间每个实例、每个 key 的 Redis 读取次数不再随命中频率增长，并且仍会在旧值到期之前取到新值。
- **寿命较短的值**：剩余寿命不长于 `refresh-window` 的值不再提前刷新，它照常到期，下一次未命中时重新加载。`ttlOf` 或 `put(key, value, ttl)` 设置的寿命同样适用。6.8.0 中这样的值每次命中都会回源。

### 修复：Redis 超时与订阅

- **超时的脚本**：超时的脚本之后永远不会再写入 Redis。以前 Lettuce 尚未发出的命令，可能在调用方看到超时之后照样执行，覆盖更新的值。这类命令有两种：Redis Cluster 中等待节点连接的命令；`RedisBackend.of` 配合断线时缓冲命令的客户端时，被缓冲的命令。已经发出的命令仍会在 Redis 上执行，应答被丢弃，与以前相同。使用 `RedisBackend.of` 时请保留 `DisconnectedBehavior.REJECT_COMMANDS`，否则命令要等满 `command-timeout` 才失败，而不是立即失败。
- **读取超时后的 lease**：超时的读取在后台尽力释放它可能已取得的 lease 或刷新令牌。其它实例不再在 `lease-ttl` 内，对这个 key 的每次未命中都先等 `lease-wait`。
- **`sharded` 频道模式**：订阅丢失、尚未重新确认的频道，在每次心跳时都重置订阅者，而不只重置一次。陈旧度保持在心跳周期加重新订阅时长之内。
- **并发的订阅与退订**：同时订阅与退订同一频道（多个运行时共用一个后端）时，订阅者不再永久收不到失效。
- **格式字节**：Redis 中值的格式字节按无符号读取。只有格式号 ≥ 128 时才有区别。

### TCK

- `EngineSpec`、`FaultSpec` 与 `VersionedSpec`（及其异步版本）新增上述行为的用例，包括没有 loader 时的 `read-failure: degrade`。`VersionedSpec.refusedVersionsChangeNothingAndDoNotBroadcast` 现在要求没有原子广播的后端上相等的版本会广播。
- `EngineSpec` 新增两个提前刷新的用例：没取得令牌的实例会退避；寿命不长于 `refresh-window` 的值不提前刷新。`MultiNodeSpec.refreshAheadLoadsOncePerKeyAcrossNodes` 先把时钟推过退避，再检查其它实例。

## v6.8.0

**模块组：** `me.ahoo.cocache` · 次版本，只有新增：`Cache`、`AsyncCache` 与 `SuspendingCache` 以 default 方法新增按版本有序的写入（没有覆盖它们的实现抛出 `UnsupportedOperationException`），`CacheSpec` 可以把缓存声明为带版本的，`cocache-api` 的 SPI 新增 `CoherenceBackend.versionedWrites` 与 `RemoteStore.putIfNewer` / `AsyncRemoteStore.putIfNewerAsync`（都带默认实现）。已有的调用方、自定义 `Cache` / `SuspendingCache` 实现与自定义后端照常编译与运行。Redis 的 key 布局、头部布局、格式版本与失效频道不变；6.0 到 6.7 的实例能读取带版本的值（见下文），6.8.0 实例可以与任何 6.x 实例共用 Redis 并逐台升级。

### 按版本写入

供知道每个值的版本的写入方使用，例如只有一个写入方、版本递增、事件可能被重投的事件溯源聚合。见[按版本写入](./freshness.md#versioned-writes)。

- 用 `versioned = true`，或返回 `Versioned(value, version)` 的 `versionedLoader` / `versionedAsyncLoader`（`cocache-coroutines` 中为 `suspendingVersionedLoader`）把缓存声明为带版本的。后端没有 `versionedWrites` 时创建立即失败；Redis 与内存后端都支持。
- `putIfNewer(key, value, version[, ttl])` 在一个 Lua 脚本中，只在 `version` 大于 Redis 中的版本时写入。版本相等是幂等的空操作，更小则被拒绝；两者都返回 `Stale(current)`，不写入、不广播、不改寿命。写入成功就是一次 `put`：在途的加载都写不回，包括持有 lease、读到更高版本的那一次。
- `invalidateIfNewer(key, version[, ttl])` 写入墓碑：带版本的负缓存，在值的寿命内挡住迟到的旧版本。
- 带版本的 loader 回源时保存版本，之后的写入与它比较。loader 返回 `Versioned(null, n)`（在版本 `n` 时已删除）时写入与 `invalidateIfNewer(key, n)` 相同的墓碑；返回 `null`（从来不存在）时仍是普通负缓存。`getVersioned(key)` 读穿透并返回值及其版本，不存在与墓碑都返回 `null`。
- 版本顺序只在条目存活期间成立：值或墓碑到期之后，任何版本都能再次写入。按重投的时间窗口设置 `ttl`。
- 带版本的缓存不支持 `refresh-window`，`cocache.defaults.refresh-window` 不作用于它。
- 新指标 `cocache.versioned.stale`（[可观测性](../operations/observability.md#metrics-writes)）。

### 线格式

带版本的值或墓碑是格式 1 的 String，36 字节头与载荷照旧，只有令牌位置的内容是新的：16 字节的标记、8 字节的版本，签发时间为 `+inf`。旧实例照常读取载荷，它们的读脚本（6.8 中没有改动）不会在这样的值上授予刷新令牌，它们的 fill 与 release 也不会匹配它。它们的写入（`put`、`invalidate`、`putIfMatch`）会用没有版本的内容替换它，所以只有带版本的缓存的全部写入方都运行 6.8 之后才有顺序保证。见[线格式](../architecture/index.md#wire-format)。

### SPI

- `CoherenceBackend.versionedWrites`（默认 `false`）、`RemoteStore.putIfNewer` / `AsyncRemoteStore.putIfNewerAsync`（默认以 `UnsupportedOperationException` 拒绝：“先读后写”的默认实现会让迟到的版本覆盖更新的版本）、`RemoteVersionedWrite`、`Fill.version`（新的构造函数；6.7 的构造函数、`copy` 与 `componentN` 不变）与 `Envelope.Scalar.version`。支持版本的后端原子地覆盖两个方法，在 `fill`、`put` 与 `putAll` 时保存 `Envelope.Scalar.version`、读取时原样返回。见[扩展](../architecture/extending.md#l1-store)。
- TCK 新增 `VersionedSpec` 与 `AsyncVersionedSpec`，用于支持 `versionedWrites` 的后端。

## v6.7.0

**模块组：** `me.ahoo.cocache` · 次版本，只有新增：`Cache`、`AsyncCache` 与 `SuspendingCache` 以 default 方法新增条件读写（没有覆盖它们的实现抛出 `UnsupportedOperationException`），`cocache-api` 的 SPI 新增 `RemoteStore.readIfChanged` / `putIfMatch` 及 `AsyncRemoteStore` 中对应的异步方法（都带默认实现）。已有的调用方、自定义 `Cache` / `SuspendingCache` 实现与自定义后端照常编译与运行。Redis 线格式与失效频道不变（新脚本只读取现有格式），6.7.0 实例可以与任何 6.x 实例共用 Redis 并逐台升级。

### 条件读写

用于 Redis 是唯一副本、值以“读取—修改—写回”更新的缓存，见[条件写入](./store.md#conditional-writes)。

- `putIfMatch(key, expected, value[, ttl])` 只在 Redis 中仍是摘要为 `expected` 的值时写入（`null`：只在 Redis 中没有值时写入）。比较与写入是一个 Lua 脚本，只在写入时广播失效。返回 `Written(digest)` 或 `Conflict(current)`。写入成功时的寿命与栅栏规则同 `put`。
- `getIfChanged(key, known)` 读 Redis、从不读 L2，值的摘要仍等于 `known` 时返回 `Unchanged`，不传输值；否则返回 `Present(value, digest)` 或 `Absent`。它省的是网络传输，不是 Redis 的 CPU：脚本每次调用都读取并哈希这个值，在 Redis 7.4 上 8 MB 的值约 20 ms（其中约 13 ms 是普通的 L1 读取同样要付的读取）。摘要不在写入时保存，因为那需要改变线格式。
- 摘要是值编码后的字节的 SHA-1，即 `reclaim` 对标量值一直使用的摘要。
- 读与写对读不懂的内容分类相同。更高版本的 CoCache 写入的值与集合总是抛出，`cause` 为 `ForeignFormatException`，内容不被修改；`reclaim` 也从不把它们当作没有值。不是 CoCache 6 写入的内容按 `foreign-format` 处理：`fail`（默认）抛出，`reclaim` 视为没有值。编解码器无法解码的值使 `getIfChanged` 抛出，`putIfMatch` 遇到它只会冲突。Redis 故障时 `getIfChanged` 抛出 `CacheReadException`，与 `read-failure` 无关。
- 新指标 `cocache.conditional.conflicts` 与 `cocache.conditional.unchanged`（[可观测性](../operations/observability.md#metrics-writes)）。

### SPI

- `RemoteStore.readIfChanged` / `putIfMatch`、`AsyncRemoteStore.readIfChangedAsync` / `putIfMatchAsync`，以及结果类型 `RemoteConditionalRead` / `RemoteConditionalWrite`。`readIfChanged` 的默认实现先读、在客户端比较：正确，只是总是传输值。`putIfMatch` 的默认实现抛出 `UnsupportedOperationException`（异步版本以它失败）：先读后 `put` 的默认实现在并发下会丢失更新。后端只有像 Redis 与内存后端那样用一次原子操作覆盖两者，才支持条件写入。见[扩展](../architecture/extending.md#l1-store)。
- TCK（`EngineSpec`）覆盖这两个操作。没有实现 `putIfMatch` 的后端设置 `supportsConditionalWrites = false` 跳过这些用例；否则必须全部通过，其中的并发用例让所有写入方在同一个摘要上竞争。

## v6.6.0

**模块组：** `me.ahoo.cocache` · 次版本：`cocache-api` 的 SPI 新增 `RemoteStore.addMembersIfReadable` / `removeMembersIfReadable` 及 `AsyncRemoteStore` 中对应的异步方法，都带默认实现，已有的后端照常编译与运行。两项行为变化收紧没有数据源的缓存的 fail closed 处理：`SetCache` 的写入遇到读不懂的内容时失败，没有数据源的读取遇到更高版本写入的值时失败（见下文）。Redis 线格式与失效频道不变，6.6.0 实例可以与任何 6.x 实例共用 Redis 并逐台升级。

### 行为变化：`SetCache` 的写入遇到读不懂的内容时失败

- `SetCache.add` 与 `remove` 现在与读取一样遵循 `foreign-format`。为 `fail` 时（`SetCache` 的默认值），在本缓存读不懂的内容上增删成员抛出 `CacheWriteException`（`cause` 为 `ForeignFormatException`）。这类内容包括未迁移的 5.x 原生 Set、String 或 Hash，没有 6.x 格式字段的 Hash，6.x 的标量值，以及更高版本的 CoCache 写入的集合。检查与写入是一个原子步骤：key 的内容与 TTL 不变，不广播，这次写入计入 `cocache.unknown.formats` 与 `cocache.write.failures`。
- 6.5.0 及以前，对未迁移的原生 Set 调用一次 `add` 就会替换它的全部成员，之后 key 看起来已经迁移，`CoCache5Migrator` 跳过它，旧成员永久丢失；`remove` 静默地什么也不做并报告成功。给缓存设置 `foreign-format: reclaim` 即可保留这一行为。
- 整体写入（`put`、`putAll`，`Cache` 与 `SetCache` 都是）照旧覆盖任何内容，因为调用方给出的是完整的值。被覆盖的 5.x 内容不会被迁移工具备份，迁移工具之后会跳过这个 key。见 [Redis 是唯一副本的缓存](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/zh/guide/migration-6.md)。

### 行为变化：没有数据源时更高格式版本的值读取失败

- 没有数据源的读取命中更高版本的 CoCache 写入的值（混合版本滚动升级期间）时，现在与 `foreign-format: fail` 下读不懂的内容一样失败：抛出 `CacheReadException`（`cause` 为 `ForeignFormatException`，`newer = true`），保留 key、不写 L2，计入 `cocache.unknown.formats`。此前读作不存在，在 Redis 是唯一副本时就是悄悄丢数据。`foreign-format: reclaim` 保留旧行为。有数据源的读取不变：回源，且不改动更高版本的值。

### SPI

- `RemoteStore.addMembersIfReadable` / `removeMembersIfReadable` 及 `AsyncRemoteStore` 中对应的异步方法：同 `addMembers` / `removeMembers`，但不改动不是本协议集合的内容，并返回它的格式版本。默认实现先读后写，不原子，也认不出更高的格式版本；后端应覆盖为一次原子的检查加写入，Redis 与内存后端即如此。见[扩展](../architecture/extending.md#l1-store)。
- `ForeignFormatException` 新增 `newer` 属性与构造参数，保留 6.5.0 的构造函数。

## v6.5.0

**模块组：** `me.ahoo.cocache` · 次版本。`cocache-core` 与 `cocache-redis` 新增 API；没有数据源的读取有一项默认行为变化（见下文）。Redis 线格式与失效频道不变，6.x 实例可以共用 Redis 并逐台升级。

### 迁移 Redis 是唯一副本的缓存

`CoCache5Migrator`（`cocache-redis`）把 CoCache 5.x 的数据转换为 6.x 格式，用于没有数据源、以 Redis 为权威存储的缓存，以及 5.x 的 `SetElementsCache`。它覆盖 5.x 的全部值格式：JSON 或普通字符串、原生 Set（迁移为 `SetCache` 或 `Cache<K, Set<String>>`）、Hash 与负缓存，并保留每个 key 的剩余寿命。它可以重复执行，按缓存报告读取、写入、跳过与失败的 key 数，遇到第一个无法解码的 key 即中止，并提供只读的 `dryRun`。原地迁移先备份每个 5.x 值，`rollback` 从备份恢复。见 [Redis 是唯一副本的缓存](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/zh/guide/migration-6.md)。

```kotlin
CoCache5Migrator.standalone(uri).use { migrator ->
    migrator.migrate(CoCache5Migration.scalar(policySpec, CoCache5Format.string()).sourcePrefix("app:policy:").build())
}
```

### 行为变化：没有数据源的读取遇到读不懂的内容时失败

- 新策略 `foreignFormat`（`fail` 或 `reclaim`；配置项 `foreign-format`）决定 key 中是本缓存读不懂的内容时读取怎么做：不是 6.x 写入的值（格式 0，例如 5.x 遗留的值、原生 Set 或 Hash），或编解码器无法解码的值。
- **没有数据源的读取（`SetCache`，以及没有 loader 的 `Cache`）默认改为 `fail`**：抛出 `CacheReadException`（`cause` 为 `ForeignFormatException`），Redis 与 L2 都不改动，计入 `cocache.unknown.formats`；`read-failure: degrade` 时同样如此。6.4 及以前，这样的读取会回收这个 key（即删除唯一副本）并返回 `null`，`SetCache` 返回空集合，调用方都会当作真实的“不存在”。
- 有数据源的读取（包括 `get(key, loader)`）照旧回收并重新加载（`reclaim`）。给缓存设置 `foreign-format: reclaim` 即可恢复旧行为。`cocache.defaults.foreign-format` 不作用于 `SetCache`。
- 受影响的只有在没有数据源的缓存下存在读不懂内容的应用；6.5 之前这些内容在第一次读取时被静默丢弃。

## v6.4.1

**模块组：** `me.ahoo.cocache` · 修订版本。API 与配置都没有变化。Redis 线格式与失效频道都没有变化，6.4.1 的实例可以与任何 6.x 实例共用 Redis，逐台升级。

### 异步 Redis 命令的超时

- 异步 Redis 命令的超时不再经 JDK 的单线程延迟调度器（`CompletableFuture.orTimeout`）计时（[RFC 0004 §14](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0004-async-api.md)）。CoCache 自建的客户端改由 Lettuce 自己的命令超时（`TimeoutOptions`，固定为 `cocache.redis.command-timeout`）为每条命令计时，它替换 Lettuce 默认的 60 秒超时，不增加开销。单机 Redis 上只有这一个计时器。Cluster 中 Lettuce 在命令写入节点连接时才开始计时，所以等待建立节点连接的命令仍由 CoCache 另外计时，改用 Lettuce 的 Netty 时间轮。传给 `RedisBackend.of` 的客户端保留自己的选项，它的异步命令由 CoCache 在同一个时间轮上计时。
- 超时仍以同样的 `RedisException("Command timed out after …")` 失败，同步与异步路径相同。异步路径上，`NOSCRIPT` 之后重试的 `EVAL` 是一条新命令，单独计时。

行为变化：

- 异步超时的精度为时间轮的一个刻度（默认 100 毫秒），最多比 `command-timeout` 晚一个刻度触发；此前精确到毫秒。
- CoCache 自建的客户端上，Lettuce 的命令超时从 60 秒改为 `command-timeout`（默认 2 秒），并关闭 Lettuce 7 在服务端维护期间的超时放宽。这也约束 Lettuce 自己发出的命令：重连后的自动重订阅，以及 Cluster 拓扑刷新时的 `CLUSTER NODES` / `INFO`。超时的重订阅在服务端照常执行，连接真的断开时由心跳强制重连；超时的拓扑刷新由下一次自适应触发重试。很大或很忙的 Cluster 中 `CLUSTER NODES` 慢于 `command-timeout` 时，应调大它。建立连接时的握手不受影响。
- CoCache 自建的客户端上，超时异常的 cause 可能是 Lettuce 的 `RedisCommandTimeoutException`，而不再是 `TimeoutException`；外层异常的类型与消息不变。

### 异步视图减少线程切换

- 上一步在同一个异步执行器正在执行的任务中完成时，异步续接就地执行，不再重新提交给执行器（[调查报告](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/investigations/async-8t-gap.md)）。经异步视图的一次 L1 命中只切换 1 次线程（原为 4 次），一次未命中至多 3 次（原为 7 次）。在 Lettuce 的 I/O 线程、定时器线程、阻塞执行器或数据源自己的线程上完成的结果仍交给异步执行器，续接从不在 I/O 线程上执行。就地执行最多嵌套 16 层，超过后重新派发。
- 在默认的 `ForkJoinPool` 上，每次多余的切换都可能唤醒一个空闲线程。与 6.4.0 交替对比（同轮配对中位数），异步 L1 命中 1 线程快 5.5%、8 线程快 8%，异步未命中 8 线程至少快约 8%（只有 2 对有效数据）。写入与 L2 命中不变。

### 修复

- **Lettuce 6.8（Spring Boot 4.0）上心跳永久停止。** 自 6.0.0 起，在 Spring Boot 4.0 管理的 Lettuce 版本上，心跳失败后的强制重连会抛出 `NoClassDefFoundError`（它用到了 Lettuce 7 的 `Delegating` 接口）。这个错误逃出了心跳任务，订阅连接从此不再被检查，半开连接之后节点无法自愈。强制重连已不再使用这个接口。心跳任务现在也会记录任何错误（包括 `Error`），然后继续下一次心跳。`cocache-redis` 的全部测试在 Lettuce 6.8.2 与 7.5.2 上都通过。
- **就地续接不再误判重入。** 线程在加载某个 key 期间（作为同步调用的 leader，或在数据源中），在这个线程上完成的其它 key 的异步续接不再就地执行。这包括数据源完成另一个 key 的加载，以及同步 leader 把回源许可交给另一个 key 的异步等待者。修复之前，那个 key 的回调再读正在加载的 key 会以 `IllegalStateException`（"Recursive call on the same key"）失败。问题来自 6.4.0 之后的改动，没有任何已发布版本受影响。

## v6.4.0

**模块组：** `me.ahoo.cocache` · 次版本，仅新增 API。已有模块没有 API 变化，Redis 线格式与失效频道也不变，6.x 实例可以共用 Redis，逐台升级。

### Kotlin 协程

新的可选模块 `cocache-coroutines` 在异步 API 之上提供挂起视图（[RFC 0004 §7](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0004-async-api.md)），见 [Kotlin 协程](./coroutines.md)。

```kotlin
val users = userCache.suspending()
val user = users.get("42")                    // 挂起而不是阻塞；L2 命中不挂起

cacheSpec<String, User>("user") {
    suspendingLoader { id -> userRepository.findById(id) }
}
```

- `Cache.suspending()` / `SetCache.suspending()` 返回 `SuspendingCache` / `SuspendingSetCache`，方法与同步 API 相同，共享同一个 L2、栅栏、在途加载与 lease。
- `SuspendingCacheLoader` 是挂起的数据源：用 `suspendingLoader(context)`（默认 `Dispatchers.Default`）设置，或用 `asAsyncCacheLoader()` 转换。调用级 loader `get(key) { … }` 在调用方的协程上下文去掉 `Job` 之后的上下文中执行。
- 取消等待的协程不会取消共享的加载；异常是原始异常。loader 读取同一缓存中自己正在加载的 key 立即以 `IllegalStateException` 失败，挂起之后同样如此。
- 以取消结束的加载，对共享它的每个调用方（同步、异步与挂起）都以 `SourceLoadCancelledException`（`RuntimeException`）失败：包括 loader 自己抛出的 `CancellationException`（如其中的 `withTimeout`），以及 spec 的 `context` 中已被取消的 `Job`。spec 级加载都在同一个 `SupervisorJob` 下，一次失败不取消之后的加载。
- Java 经门面类 `SuspendingCaches` 与 `SuspendingCacheLoaders` 看到这些扩展函数。
- 本模块依赖 `kotlinx-coroutines-core` 1.10.2。其它模块的运行时都不依赖协程；`cocache-dependencies` 平台声明了 1.10.2 的版本约束，与 Spring Boot 4.1 管理的版本相同。模块在类路径上时，starter 为挂起视图登记原生镜像提示。
- 同步与异步的命中路径不变。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-coroutines:6.4.0")
```

## v6.3.1

**模块组：** `me.ahoo.cocache` · 修订版本。API 与配置都没有变化。Redis 线格式与失效频道都没有变化，6.3.1 的实例可以与任何 6.x 实例共用 Redis，逐台升级。

### 修复

- **异步执行器拒绝任务不再吞掉写入的广播。** 只影响以单独命令广播失效的后端（`channel-mode: sharded`，或启动时原子广播探测失败）。异步的 `put`、`putAll`、`invalidate` 或集合写入之后，如果你配置的 `cocache.async-executor` 拒绝了发送广播的任务，广播会被跳过，写入的结果也被换成 `RejectedExecutionException`：其它实例可能在 `localMaxAge` 内继续返回旧的本地副本，成功的写入被计为写入失败，成功的 `invalidate` 还会登记多余的重试。现在执行器拒绝时广播在完成线程上直接发出，写入保留自己的结果或异常。如果执行器同样拒绝完成返回给你的 future，该 future 仍以 `RejectedExecutionException` 失败，与此前相同。默认执行器（`ForkJoinPool`）不拒绝任务，默认配置不受影响。
- **命令超时后的广播：只补充文档，不改行为。** 同步 `putAll` 或 `invalidate` 超时后补发的广播，可能先于超时的命令在 Redis 端执行而到达其它实例。我们评估了等待命令真正结束的做法，决定维持现状：超时后无从得知命令是否、何时执行，任何有上界的等待都只能缩小窗口、不能关闭它。这个窗口内的旧副本仍受 `localMaxAge` 限制。见 [architecture.md §4.2](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/architecture.md)。

## v6.3.0

**模块组：** `me.ahoo.cocache` · 次版本。6.2 没有单独发布：原计划在 6.2 交付的内容随 6.3.0 一并发布。Redis 线格式与失效频道都没有变化，6.0、6.1 与 6.3 的实例可以共用 Redis，逐台升级。

### 异步 API

每个缓存都有一个基于同一个引擎的异步视图（[RFC 0004](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0004-async-api.md)），见[异步 API](./async.md) 指南。

```kotlin
val async = userCache.async()                 // 同一个 L2、栅栏、在途加载与 lease
async.get("42").thenAccept { user -> ... }
async.put("42", user).thenCompose { async.get("42") }
```

- `Cache.async()` / `SetCache.async()` 返回 `AsyncCache` / `AsyncSetCache`，方法与同步 API 同名、语义相同，返回 `CompletableFuture`。L2 命中返回已完成的 future。
- `AsyncCacheLoader`（`loadAsync`、`loadAllAsync`）从非阻塞的数据源加载。在 spec 中设为 `asyncLoader`；`@CoCache` 接口按类型或 `loader = "…"` 找到它。两种 loader 都能配合两种 API。
- 不挂起线程：等待其它实例的 lease 与回源许可都经调度完成，future 在异步执行器上完成，从不在 Lettuce 的 I/O 线程上。future 以原始异常失败，取消一个 future 不会取消共享的加载，异步方法从不同步抛出。
- 新配置：
  - `cocache.async-executor` 与 `cocache.blocking-executor`，各自指定一个 `Executor` bean 的名称；
  - 每个缓存的 `load-async-timeout`（默认 30 秒），以及新的 `SourceLoadTimeoutException` 与 `cocache.load.timeouts` 指标；
  - `cocache.observation.enabled`（默认 `true`），用于[回源路径追踪](#回源路径追踪)。类路径上有 Spring Boot Actuator 与 Micrometer tracing bridge 时追踪默认开启，设为 `false` 可关闭。
- Redis 后端原生实现新的可选 SPI `AsyncRemoteStore` / `AsyncInvalidationChannel`。第三方后端无需改动：引擎在阻塞执行器上调用它们的同步方法。
- **Spring Cache：** `retrieve` 经异步视图，异步与响应式的 `@Cacheable(sync = true)` 方法回源时不占用线程。
- **性能：** 同步命中路径不变（JMH 在 1 与 8 线程下与异步改动之前的代码相比，差异在噪声内）。一次异步 L2 命中约为同步命中的 90%。32 个在途请求下，一个线程完成的 Redis 读写约为一个阻塞线程的 12 倍。

### 用 putAll 批量写入

`putAll(entries)` 与 `putAll(entries, ttl)` 把多个 key 作为一次写入：每个 Redis slot 组一个脚本、广播一次，防止旧值写回的保护与 `put` 相同。见[保持数据新鲜](./freshness.md)。

### 回源路径追踪

容器中有 `ObservationRegistry` bean 时，starter 注册 `MicrometerLoadObserver`：每次回源与每次等待其它实例的 lease 都包在一个 `cocache.load` 观测中，标签为 `cache`、`operation` 与 `outcome`。命中不产生观测。新的 `LoadObserver` SPI 可以接入你自己的实现。见[可观测性](../operations/observability.md#tracing)。

### SetCache 读取更快

`SetCache` 的读脚本用 `HKEYS` 取成员、用 `HGET` 取格式版本，不再用 `HGETALL` 连同每个成员的空值一起返回。存储布局不变。服务端吞吐在 10、100、1000 个成员时分别是原来的 1.33、1.55、1.76 倍。读脚本内的 ACL 错误现在会抛给调用方，不再被当作无法识别的内容。

### L2 命中计数

`cocache.metrics.local-hits=true`（不用 Spring 时：`CacheRuntime.builder().recordLocalHits(true)`）把由 L2 回答的读取计入 `cocache.hits{level=l2}` 与端点字段 `localHits`，读取因此能精确地分成 L2 命中、未命中与被过滤三类。默认关闭，因为它给每次 L2 命中加一次原子自增：开启后 L2 命中吞吐下降约 3%–9%（JMH，1 与 8 线程，视调用点而定）；关闭时与 6.1 持平。5.x 的 `cocache.metrics.client-side-hits` 现在提示改用它。见[可观测性](../operations/observability.md#metrics-reads)。

### 修复

- 非原子广播（`channel-mode: sharded`，或原子广播探测失败）时，失败但已部分生效的写入现在也会广播。之前一组 slot 失败的 `putAll`，或 Redis 已执行但应答丢失的 `put`，会让其它实例一直读旧的本地副本，直到 `local-max-age`。Redis 上同步的 `putAll` 与 `invalidateAll` 现在等全部 slot 组结束后才抛出。
- 读到刷新窗口内的本地副本时，若上一次刷新恰好刚完成，同一实例可能对同一 key 再刷新一次。这次多余的刷新只多一次 Redis 读取，现在不再发生。
- 按 bean 名指定的执行器（`cocache.refresh-executor`，以及新的 `cocache.async-executor` / `cocache.blocking-executor`）在应用关闭时可能先于 CoCache 运行时被销毁，运行时关闭期间提交的任务因此被拒绝。这个问题从 6.1 起就存在。现在运行时先关闭。

### 兼容性测试套件与 CI

- 新的 TCK 套件：`MultiNodeSpec`（三个运行时、所有失效交错、全集群每个 key 只回源一次与刷新一次）、`FaultSpec`（L1 故障、重试、已生效但报错的写入、重连后不重放），以及每个套件的异步版本（`AsyncBackendProtocolSpec`、`AsyncEngineSpec`、`AsyncSetEngineSpec`、`AsyncMultiNodeSpec`、`AsyncFaultSpec`）。
- Redis 后端与 starter 在 CI 中于 Valkey 8 上测试。
- `SetCache` 读取基准（`setRead`）确认保留 Hash 编码。

### 升级注意

- `cocache.spring-cache.retrieve-executor`、`CoCacheSpringCacheManager(runtime, executor)` 与 `CoCacheSpringCacheManager.DEFAULT_EXECUTOR` 已弃用：执行器被忽略，配置项在启动时告警。7.0 删除。`CoCacheSpringCache(cache, admin, executor)` 没有弃用：其执行器执行方法体。
- **异步与响应式的 `@Cacheable(sync = true)` 方法**有两处行为变化：
  - 方法体在 `cocache.blocking-executor` 上执行：JDK 21+ 为虚拟线程；JDK 17 上是最多 512 个线程的线程池，满了就拒绝新的加载（`RejectedExecutionException`）。原来的 retrieve 执行器满时会改在调用方线程上执行。
  - 加载受 `load-async-timeout`（默认 30 秒）约束：超时后调用以 `SourceLoadTimeoutException` 失败，方法体仍会继续执行。
- `Cache.async()` 是默认抛出 `UnsupportedOperationException` 的 default 方法。你自己实现的 `Cache`（例如测试替身）只有在调用方使用异步视图时才需要覆盖它。
- 自定义的 `LoadObserver` 只有覆盖 `observeAsync` 才能看到异步加载。
- **Kotlin 调用方需要针对 6.3 重新编译**，否则报 `NoSuchMethodError`。`LoadPolicy` 新增 `asyncTimeout`，改变了 `copy` 与 Kotlin 默认参数背后的合成构造函数，调用 `copy` 或省略 `LoadPolicy` 参数的 Kotlin 代码都受影响。`CoCacheProperties` 与 `PolicyProperties` 的构造函数和 `copy` 也变了，自己构造 starter 配置属性的代码同样受影响。Java 调用方不受影响。
- **Redis ACL**：使用最小 ACL 的用户须在升级前加上 `+hkeys +hget`，否则 `SetCache` 读取会以 ACL 错误失败。`+hgetall` 仍需保留：条件回收与 6.0 / 6.1 实例还在使用它。见 [ACL](../operations/index.md#acl)。
- TCK：`HookedBackend.closesDelegate`（默认 `false`，与之前相同）与各套件的 `closesBackend` 让套件关闭每个用例新建的后端，后端会打开连接或线程时请开启。`BackendProtocolSpec` 与 `AsyncBackendProtocolSpec` 总是关闭自己创建的后端，没有这个开关。按调用方筛选的钩子请改用 `HookedBackend.issuer()`，因为异步调用在其它线程上完成。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.3.0")
```

## v6.1.0

**模块组：** `me.ahoo.cocache` · 次版本。Redis 线格式与失效频道不变，6.0 与 6.1 实例可以共享 Redis，并逐台升级。

### Spring AOT 与 GraalVM 原生镜像

使用 starter 的应用现在可以用 Spring AOT 构建，并以 GraalVM 原生镜像运行。CoCache 在构建期登记它需要的提示：

- `@CoCache` 与 `@JoinCacheable` 接口的 JDK 代理，以及这些接口的反射（包括 default 方法）；
- 默认 key 编码器用到的 key 类型的 `toString()`；
- `keyExpression` 与 `joinKeyExpression` 的 SpEL 根对象类型；
- 使用默认 codec 时值类型的 Jackson 绑定。

缓存 bean 由 AOT 能够生成代码的工厂方法创建。自定义 `Codec`，以及只有你自己的代码通过反射访问的类型，仍需要你自己提供提示。见[原生镜像](./native.md)。

### 执行器可配置

```yaml
cocache:
  refresh-executor: applicationTaskExecutor          # 提前刷新的回源
  spring-cache:
    retrieve-executor: applicationTaskExecutor       # Spring Cache 的异步 retrieve
```

两者都按 bean 名指定一个 `Executor`。名字不存在或 bean 不是 `Executor` 时启动失败。CoCache 不会关闭你提供的执行器。`retrieve` 建议使用虚拟线程执行器：会把任务排在等待它的任务之后的线程池，可能让嵌套的异步 `@Cacheable(sync = true)` 死锁。见[配置参考](./configuration.md#executors)。

### SetCache 驱逐检查

有 `SetCache` 时，CoCache 启动时读取各主节点的 `maxmemory-policy`。`allkeys-*` 可能淘汰集合的唯一副本，默认记录告警。`cocache.redis.set-cache-eviction-check` 可选 `warn`、`fail` 或 `off`。`CONFIG` 不可用（托管 Redis、ACL）时跳过检查。不使用 Spring 的应用可以调用 `RedisBackend.maxmemoryPolicies()` 自行检查。

### Agent Skills 与文档

- Agent Skills 按交付结果拆分为 `cocache-develop`、`cocache-migrate` 与 `cocache-extend`，各自带 `claude plugin eval` 用例，并在 CI 中校验。
- 文档站点首页新增展示读写路径的动效，并更换了 logo。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.1.0")
```

## v6.0.0

**模块组：** `me.ahoo.cocache` · **主版本。** CoCache 按 [RFC 0003](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0003-target-architecture.md) 的一致性协议重建。**不能滚动升级：** Redis 中的值格式与失效频道都已改变，5.x 与 6.0 实例不能共享 Redis。请按[迁移指南](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/zh/guide/migration-6.md)整体切换。

### 主要变化

- **消除了陈旧回写。** 回源只有持有 lease 令牌时才写 Redis，每次填充 L2 都校验失效戳。5.x 的“已知固有窗口”不再存在：协议内的竞态不会在 Redis 中留下陈旧值。
- **命中路径更快。** L2 命中比 5.4 快 54–59%，接口代理命中快 28–64%，`put` / `invalidate` 约快一倍（JMH，RFC 0003 §7.1）。
- **全集群每个 key 只回源一次**；提前刷新全集群每个 key 只刷新一次；批量 `getAll` 先读 L2，再一次往返读 Redis，最后一次 `loadAll` 回源。
- **可选择的失败模式：** `read-failure: degrade | throw`、`invalidate-failure: retry | throw`、有界的失效重试队列、回源并发上限（`load-max-concurrent`）。
- **基于原生 Lettuce 的 Redis 后端**（`cocache-redis`，不依赖 Spring）：单机、Sentinel、Cluster；TLS 通过 Spring Boot SSL bundle 或 `rediss://`；Pub/Sub 或 Sharded Pub/Sub（`cocache.redis.channel-mode`）。
- **Spring：** `@EnableCoCache` 配合 `@CoCache` 接口或 `CacheSpec` bean；`CacheSpecCustomizer`；分层的 `cocache.defaults.*` / `cocache.caches.[name].*` 配置并带完整的 IDE 元数据；`CoCacheSpringCacheManager`（一致的回源请用 `@Cacheable(sync = true)`）；`TransactionalInvalidator`（事务提交后失效）。
- **可观测性：** actuator 端点 `cocache`，以及按 `cache` 打标签的 `cocache.*` Micrometer 指标。

### 开发体验

- **`JoinCache`**：两个缓存的只读组合视图，内部是两次批量读；用 `@JoinCacheable` 声明，`@EnableCoCache(joinCaches = […])` 注册。见 [JoinCache](./join-cache.md)。
- **本地开发与测试不需要 Redis**：`cocache.backend: memory` 让所有缓存运行在进程内后端上（仅限单进程）。声明 `CacheClock` bean（如 `ManualClock`）即可在测试中推进时间。
- **按 key 排查**：`GET /actuator/cocache/{name}/{key}` 查看某个 key 的 L2 副本与 L1 状态，不回源；同一路径的 `DELETE` 在全集群失效它。见[运维](../operations/observability.md#inspect-key)。
- **健康检查** `cocache`：Redis 心跳失败时为 DOWN；仍在重试的失效列在详情中。
- **Spring Cache 检查**：用在 CoCache 缓存上、但没有 `sync = true` 的 `@Cacheable` 会逐个告警（非 sync 路径不受 lease 保护）；`cocache.spring-cache.require-sync=true` 时改为启动失败。
- **全局 key 前缀**：`cocache.defaults.key-prefix`（以及按缓存的 `cocache.caches.[name].key-prefix`），例如多个应用共用一个 Redis 时设为 `${spring.application.name}:`。
- **key 类型检查**：默认的 `toString()` 编码器拒绝没有重写 `toString()` 的 key 类型，否则缓存永远不会命中。
- **`ExternalInvalidator`**：CDC 消费者、批处理任务按同一协议失效，不需要创建缓存。
- 面向 Java：`LeasePolicy`、`LoadPolicy`、`LocalPolicy`、`RetryPolicy`、`RedisBackendOptions` 加了 `@JvmOverloads`，spec 构建器新增 `updatePolicy(Consumer)`。

### 迁移速查

| 5.x | 6.0 |
|-----|-----|
| `me.ahoo.cache.*` | `me.ahoo.cocache.*`；`@EnableCoCache` 位于 `me.ahoo.cocache.spring.boot` |
| `cocache-spring-redis`、`cocache-spring-cache` | `cocache-redis`、`cocache-spring`（另有 `cocache-codec-jackson`） |
| `ttlAmplitude`、`missingTtl`、`clientSideTtl`、`refreshAhead` | `ttlJitter`、`absentTtl`、`localMaxAge`、`refreshWindow` |
| `{name}.CacheSource` bean | 按泛型匹配的 `CacheLoader<K, V>` bean，或 `@CoCache(loader = …)` |
| `{name}.ClientSideCache` 等 bean 名后缀 | `CacheSpecCustomizer`、`cocache.caches.[name].*` |
| `getCache` / `set(key, ttlAt, value)` / `evict` | `get` / `lookup` / `put(key, value, ttl)` / `invalidate` |
| `SetElementsCache` | `SetCache` |
| `cocache.redis.strict-failure=true` | `cocache.defaults.read-failure=throw` |
| `cocache.redis.eviction-channel=sharded` | `cocache.redis.channel-mode=sharded` |
| `cocache.load.*` | `cocache.defaults.load-max-concurrent` / `load-acquire-timeout` |
| actuator `cocacheClient` | 已删除，改用 `cocache` |
| `JoinCache`（`me.ahoo.cache.api.join`） | `me.ahoo.cocache.api.join.JoinCache`：两个缓存的只读组合视图，用 `@EnableCoCache(joinCaches = […])` 注册；`evict` → `invalidate`，不再有 `set`（见 [JoinCache](./join-cache.md)） |
| Jedis、可替换的 L2、显式写入负缓存 | 已删除 |

已删除的 5.x 配置项会让启动失败，错误信息会给出替代项（如果有的话）。部分改名同时改变了语义（例如 L2 现在总受 `localMaxAge` 限龄，默认 5 分钟），详见[行为变化](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/zh/guide/migration-6.md)。

### 依赖

| 依赖 | 版本 |
|------|------|
| JDK（最低） | 17（在 17 与 25 上测试） |
| Spring Boot | 4.1.1 |
| Kotlin | 2.4.21（产物的 Kotlin API 与语言级别为 2.2） |
| Lettuce | 7.5.2.RELEASE |
| Caffeine | 3.2.4 |
| Jackson | 3.1.5（`tools.jackson`） |

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.0.0")
```

## v5.4.0

**模块组：** `me.ahoo.cocache` · 次版本，仅新增 API。默认失效通道与 Redis 线格式不变，5.3.x 与 5.4 实例可共享 Redis。新通道默认关闭。

### Sharded Pub/Sub 失效通道

```yaml
cocache:
  redis:
    eviction-channel: sharded   # 默认 pubsub
```

Redis Cluster 中普通 `PUBLISH` 会到达每个节点；`SPUBLISH`/`SSUBSCRIBE`（Redis 7+）只在频道所属 slot 的分片内传播，开销不随集群规模增长。

- 需要 Lettuce（`ShardedRedisCacheEvictedEventBus` 直接使用 Lettuce，Spring Data Redis 尚不支持 Sharded Pub/Sub）。
- 频道名与消息格式不变；断线重连后重新订阅同样会清空 L2。
- **两种通道互不相通，所有实例必须同时切换。**
- 两种通道的发布失败都计入 `cocache.eviction.publish.failures`（新接口 `EvictionPublishStats`）。

### 从 CoCache 之外淘汰

[运维指南](../operations/troubleshooting.md#external-invalidation)公开了失效协议（存储 key、频道、`key@@publisherId` 消息），并给出 `redis-cli` 与 CDC 消费者示例：绕过 CoCache 的写入方先 `DEL` 存储 key，再 `PUBLISH`。

### Redis Cluster 测试

CI 新增 “Test (Redis Cluster)” 任务，Redis、Spring Cache 与 starter 的测试同时在 3 主 3 从的集群上运行。本地可用 `.github/scripts/start-redis-cluster.sh` 启动同样的集群。

### TCK

`SetElementsCacheSpec` 的两个并发用例改为：立即断言 L1（权威副本）内容，各实例的读取以带超时的轮询断言。此前它们在失效事件较慢的环境（如 Redis Cluster）下会偶发失败。

### 设计文档

- [RFC 0001](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0001-l1-write-back-lease.md)：6.0 的 L1 写回 lease，关闭跨实例的旧值写回窗口。
- [RFC 0002](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0002-resp3-client-tracking.md)：RESP3 client tracking 预研结论（6.0 不采用）。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.4.0")
```

## v5.3.0

**模块组：** `me.ahoo.cocache` · 次版本，仅新增 API（新接口方法都带默认实现）。Redis 线格式与失效消息格式不变，5.2.x 与 5.3 实例可共享 Redis。新行为默认关闭。

### 批量读取：`getAll` / `getCaches` / `loadCacheValues`

读取 N 个 key 不再需要 N 次 Redis 往返和 N 次回源：

```kotlin
val users: Map<String, User> = userCache.getAll(ids)
```

- 先读 L2，剩余 key 用一次 pipeline 读 Redis，仍未命中的 key 一次调用 `CacheSource.loadCacheValues(keys)` 加载（结果中缺少的 key 写入负缓存）。100 个 key 的 L1 读取从约 13.6 ms 降到约 1 ms（JMH）。
- 每个 key 的回填与写回都受失效戳保护，与单 key 读取相同。
- `CacheSource.loadCacheValues` 默认逐个调用 `loadCacheValue`，覆盖为一次查询（如 `findAllById`）即可。
- `SimpleJoinCache` 以两次批量读取完成组合。
- 新增 API：`CacheGetter.getCaches` / `getAll`、`CacheStore.getCaches`、`CacheSource.loadCacheValues`、`CodecExecutor.executeAndDecodeAll`，均带默认实现。

### 提前刷新：`@CoCache.refreshAhead`

```kotlin
@CoCache(ttl = 600, refreshAhead = 60)
interface UserCache : Cache<String, User>
```

值的剩余存活时间不超过 `refreshAhead` 秒时，读取照常返回当前值，并在后台刷新一次（每个实例每个 key 同一时刻至多一次）。热点 key 过期时不再出现回源延迟尖刺。不会返回已过期的值，负缓存不刷新。默认 `0` 关闭。指标：`cocache.refreshes`。

### 回源并发上限

```yaml
cocache:
  load:
    max-concurrent: 32
    acquire-timeout: 1s
```

Redis 故障期间每次 L2 未命中都会回源。设置后，每个缓存同时进行的回源数最多为 `max-concurrent`，超出的回源等待 `acquire-timeout` 后抛出 `SourceLoadRejectedException`。指标：`cocache.load.rejections`。默认不限制。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.3.0")
```

## v5.2.0

**模块组：** `me.ahoo.cocache` · 次版本，仅新增 API。Redis 线格式与失效消息格式不变，5.1.x 与 5.2 实例可共享 Redis。

### L2 陈旧上限：`@CoCache.clientSideTtl`

失效事件经 Redis Pub/Sub 至多投递一次。事件丢失时，其它实例会一直使用 L2 副本，直到值的 `ttl`（默认 3600 秒）到期。

```kotlin
@CoCache(clientSideTtl = 30)
interface UserCache : Cache<String, User>
```

- L2 副本最多存活 `clientSideTtl` 秒，过期后从 Redis 重新填充；L1 仍保留完整 TTL。
- 默认 `0` 不限制，与 5.1 行为一致。
- 只在写入 L2 时计算，命中路径没有额外开销；每次 L2 过期需要一次 Redis 往返。
- 启用后，L2 命中时 `getTtlAt` 返回 L2 副本的到期时间。

### Micrometer 指标

classpath 上有 Micrometer 时（`spring-boot-starter-actuator` 会引入），starter 绑定 `cocache.*` 指标，按 `cache` 标签区分：`hits{level=l2|l1}`、`misses`、`loads`（timer）、`load.failures`、`filtered`、`stale.discards`、`evictions.received`、`resets`、`l2.size`，以及不带标签的 `eviction.publish.failures`。完整说明见[运维](../operations/observability.md#metrics)。

- 统计由编排层以 `LongAdder` 累计，可通过 `CoherentCache.stats` 直接读取。
- L2 命中计数（`hits{level=l2}`）默认关闭，用 `cocache.metrics.client-side-hits=true` 开启：在最热的路径上计数会使 L2 命中吞吐下降 8%–15%（JMH，1–8 线程）。其余指标都不在命中路径上，命中路径吞吐不变。
- `RedisCacheEvictedEventBus.publishFailureCount` 记录发布失败次数。

### 移除 CosId 依赖

`cocache-core` 只用 CosId 生成 clientId，现改用 JDK 实现。

- `cosid-core` 不再随 CoCache 传递引入。直接使用 CosId 的项目需要自行声明依赖。
- 移除 `CosIdHostAddressSupplierAutoConfiguration`：容器中的 CosId `HostAddressSupplier` 不再用于 clientId 的 host 部分。clientId 的唯一性由随机后缀保证，host 只影响可读性。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.2.0")
```

## v5.1.1

**模块组：** `me.ahoo.cocache` · 补丁版本，与 5.1.0 向后兼容，Redis 线格式与失效消息格式不变。

- **clientId 在实例间保证唯一。** `HostClientIdGenerator` 此前以 `{计数器}:{pid}@{host}` 作为 clientId，但 host + pid 并不唯一：同一宿主机上以 host 网络模式运行的两个容器 IP 相同、pid 都可能为 1。订阅方会忽略 publisherId 等于自身 clientId 的失效事件，因此这两个实例会互相丢弃对方的失效，L2 中的旧值要等到 TTL 才过期。clientId 现为 `{计数器}:{pid}:{随机后缀}@{host}`；`ClientIdGenerator` 的契约补充了“必须在所有实例间唯一”。
- 依赖：CosId 3.4.1，fluent-assert 1.2.1。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.1.1")
```

## v5.1.0

**模块组：** `me.ahoo.cocache` · 次版本，仅新增 API。Redis 线格式与失效消息格式不变，5.0.x 与 5.1 实例可共享 Redis。

### 集合值缓存的原子元素增删

并发的“读整集 → 修改 → 写整集”会在实例间丢失更新。缓存接口现在可以再声明 `SetElementsCache<K, E>`，每次增删都是一次原子的 L1 操作：

```kotlin
@CoCache(ttl = TtlAt.FOREVER)
interface PolicyIndexCache : Cache<String, Set<String>>, SetElementsCache<String, String>

policyIndexCache.addElements(key, listOf(policyId))
policyIndexCache.removeElements(key, listOf(policyId))
```

- **装配**：分布式缓存须实现新的 SPI `SetElementsStore`。Redis 使用 `RedisSetDistributedCache(redisTemplate)`（原生 Redis Set，与 `SetToSetCodecExecutor` 布局相同），`InMemoryDistributedCache` 同样支持。代理声明了 `SetElementsCache` 而存储不支持时，创建代理即失败，错误信息给出修复方法。
- **语义**：L1 是权威副本，增删不访问 `CacheSource`。加入时替换负缓存（哨兵不会残留在集合中）。每次增删后，key 的到期时间按缓存的 `TtlPolicy` 重置（`TtlAt.FOREVER` → 无到期时间，即使此前是 60 秒的负缓存）。对不存在的 key 或负缓存执行移除不做任何事；移除最后一个元素会删除 key。
- **一致性**：失效戳 → L1 原子增删（一次 Lua 往返）→ 淘汰 L2 → 广播失效事件。读己之写与在途加载保护同 `setCache`。
- **失败总会暴露**：元素增删是显式写入，L1 失败时总是抛出异常，即使 `strictFailure = false`。更新仍可能已生效（例如响应超时），因此仍会淘汰本地副本并广播失效。两个操作都是幂等的，调用方可以重试。
- **写回安全**：对实现 `SetElementsStore` 的存储，回源写回改为条件写入（仅当 key 不存在或为负缓存时写入），撤销改为比较后删除。此前，开始于 `addElements` 之前的回源（例如写入负缓存）可能覆盖或删除新加入的元素。`InMemoryDistributedCache` 实现了该 SPI，因此对任意值类型都使用条件写回。
- 新增 `TtlPolicy.valueTtlAt()`（含抖动的命中值到期时间）；`RedisDistributedCache` 改为 `open`。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.1.0")
```

## v5.0.1

**模块组：** `me.ahoo.cocache` · 补丁版本，与 5.0.0 向后兼容，Redis 线格式不变。

- **读取时不再删除损坏载荷。** 无法解码的载荷仍按未命中处理，由一致性缓存回源后的写回（受失效戳保护）覆盖。此前读取会无条件 `DEL`，可能误删其他实例刚写入的有效值。
- **`cocache-spring-boot-starter` 移除 `actuator-support` / `cloud-support` Gradle capability。** Actuator 改为 `compileOnly`，端点仍只在应用自行引入 `spring-boot-starter-actuator` 时启用。POM 不再列出 actuator 可选依赖；未使用这两个 capability 的 Maven/Gradle 用户不受影响。
- 构建：为 Gradle 10 做好准备（构建脚本不再使用已弃用 API），改用 Kotlin `-jvm-default=enable`（字节码与 `-Xjvm-default=all-compatibility` 相同）。测试同时在 JDK 25 上运行。
- 文档站：修复 3 张无法渲染的时序图，修复文档工具链的全部 Dependabot 安全告警。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.0.1")
```

## v5.0.0

**模块组：** `me.ahoo.cocache`

5.0 以三条第一性目标重建一致性内核：陈旧度有上界、失效不被吞没、命中路径够便宜。这是不提供兼容桥接的破坏性版本。**Redis 存储结构与失效消息格式保持不变**，滚动升级期间 4.x 与 5.0 实例可以共享同一 Redis。

### 正确性修复

- **L1 读不到绝不视为负缓存**：Redis 读取改为一次原子往返（Lua 脚本同时返回 TTL 与值）。key 不存在、读取期间被删除、载荷损坏都按未命中处理并回源。此前若在 `TTL` 与 `GET` 之间发生并发淘汰，会被解码为负缓存并写入 L2，直到旧 TTL 到期（默认永久）。
- **本地写入使在途回源失效**：同实例上的 `evict`/`setCache` 现在会让并发回源放弃陈旧写回（此前只有远端事件生效）；L1 → L2 填充受同样保护。
- **代理抛出原始异常**：代理解包 `InvocationTargetException`，调用方不再收到 `UndeclaredThrowableException`。
- **默认陈旧度有界**：默认 `ttl` 为 3600 秒（原为永久）；负缓存使用独立的 `missingTtl`（默认 60 秒，原与命中值共用 TTL）；默认 L2 为有界 Caffeine（10000 条）；失效通道每次（重新）订阅都会清空 L2，丢失的 Pub/Sub 消息不会让 L2 永久陈旧。
- **读己之写**：同一线程 `evict`/`setCache` 之后的 `get`，不会返回开始于该次失效之前的加载结果，而是重新加载一次。
- **Spring Cache**：`get(key, valueLoader)` 按 key 只加载一次（满足 `@Cacheable(sync = true)`）；`retrieve` 改用专用线程池，不再占用 ForkJoin 公共池。
- **有状态组件不再共享**：`ClientSideCache`、`DistributedCache`、`KeyConverter` bean 只按名称解析；此前按泛型类型匹配的 bean 会被同值类型的所有缓存共享。
- **JoinCache.evict(key) 不再回源**：只淘汰主缓存。

### 架构

- `cocache-api` 承载全部 SPI：`CacheStore` → `ClientSideCache` / `DistributedCache`、`CacheEvictedEventBus` / `CacheEvictedSubscriber` / `CacheEvictedEvent`、`KeyConverter`、`KeyFilter`、`CacheSource`。
- `CacheValue` 为密封类型：`PresentValue(value, ttlAt)` | `MissingValue(ttlAt)`。
- 存储层只存不判；TTL 策略（`TtlPolicy`）由编排层持有。
- `DefaultCoherentCache` 以 `SingleFlight` 按 key 合并回源（无分段锁碰撞），以 `InvalidationStamps` 保护写回。
- CoCache 与 JoinCache 代理共用一个 `CacheInvocationHandler`。

### 性能

JMH，4.3.0 对比 5.0.0，同一机器、同一 Redis（ops/s）：

| 基准 | 4.3.0 · 1 线程 | 5.0.0 · 1 线程 | 4.3.0 · 8 线程 | 5.0.0 · 8 线程 |
|---|---:|---:|---:|---:|
| L2 命中 | 31.1M | 32.6M | 217.5M | 235.9M |
| L1 读取 | 3,188 | 6,144 | 5,438 | 10,758 |
| 未命中回源 | 1,487 | 3,082 | 2,467 | 5,173 |
| 写入 | 2,779 | 2,804 | 4,714 | 4,694 |

一次 Lua 往返取代 `TTL` + `GET`，L1 读取与未命中回源约快 2 倍。L2 命中保持随线程扩展：有界 Caffeine 在读取时对照缓存时钟 `CacheClock` 判断过期。基准随仓库提供：`RedisCacheBenchmark`（`./gradlew :cocache-spring-redis:jmh`）。

### 从 4.x 迁移

| 4.x | 5.0 |
|-----|-----|
| `me.ahoo.cache.DefaultCacheValue(value, ttlAt)` / `.forever(v)` / `.missingGuard(...)` | `CacheValue.of(value, ttlAt)` / `CacheValue.forever(v)` / `CacheValue.missing(ttlAt)` |
| `cacheValue.isMissingGuard` | `cacheValue.isMissing`（或 `is MissingValue`） |
| `ComputedTtlAt.at(ttl)`、`ComputedTtlAt.FOREVER`、`CacheSecondClock.INSTANCE.currentTime()` | `TtlAt.at(ttl)`、`TtlAt.FOREVER`、`TtlAt.currentTime()` |
| `ClientSideCache` / `DistributedCache` 继承 `Cache`（`cache[key] = v`） | 二者是 `CacheStore`：`getCache` / `setCache` / `evict` |
| `me.ahoo.cache.distributed.DistributedCache`、`me.ahoo.cache.consistency.CacheEvicted*`、`me.ahoo.cache.converter.KeyConverter`、`me.ahoo.cache.KeyFilter` | `me.ahoo.cache.api.distributed.DistributedCache`、`me.ahoo.cache.api.consistency.CacheEvicted*`、`me.ahoo.cache.api.converter.KeyConverter`、`me.ahoo.cache.api.filter.KeyFilter` |
| `@GuavaCache`、`GuavaClientSideCache`、`GuavaCacheEvictedEventBus` | `@CaffeineCache`、`CaffeineClientSideCache.build(...)`、`LocalCacheEvictedEventBus` |
| `MockDistributedCache` | `InMemoryDistributedCache` |
| `CoherentCache.clientSideCache` / `.distributedCache` / ... | `CoherentCache.configuration.clientSideCache` / ... |
| 自定义 `CacheEvictedSubscriber` | 实现 `onReset()`（放弃本地副本） |
| 自定义 `CodecExecutor` | `executeAndDecode(key)` 自行读取值与 TTL；继承 `StringCodecExecutor` / `HashCodecExecutor` 或 `AbstractCodecExecutor` |
| 按类型匹配的 `ClientSideCache<User>` bean | 命名为 `UserCache.ClientSideCache` |
| `TtlConfiguration`、`TtlConfigurationAware`、`ComputedCache` | 已移除，TTL 来自 `@CoCache`（`TtlPolicy`） |
| `@CoCache` 不指定 `ttl` 即永久 | 现为 3600 秒；如需旧行为请显式设置 `ttl = TtlAt.FOREVER` |
| `JoinCache.evict(key)` 同时淘汰两个缓存 | 只淘汰主缓存；同时淘汰请用 `evict(firstKey, joinKey)` |
| `CacheSource` 内部读同一缓存的同一 key 会重入锁 | 改为抛出 `IllegalStateException` 快速失败（原行为会导致错误淘汰） |

### 依赖版本

| 依赖 | 版本 |
|------|------|
| Spring Boot | 4.1.1 |
| CosId | 3.2.1 |
| Guava | 33.7.2-jre |
| Kotlin | 2.4.21 |
| JUnit | 6.1.3 |

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.0.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>5.0.0</version>
</dependency>
```

## v4.3.0

**模块组：** `me.ahoo.cocache`

### 亮点

- **击穿防护加固** —— `DefaultCoherentCache` 的 per-key 锁映射（存在锁对象回收竞态，可能导致并发重复回源）替换为 Guava `Striped` 锁（#519）。
- **时钟线程启动竞态修复** —— `CacheSecondClock` 计时线程启动不再与字段初始化竞态；杜绝"时钟冻结 → 缓存永不过期"的故障模式（#519）。
- **旧值回填竞态修复** —— 引入 per-key 失效代际计数器，在途回源可检测加载期间到达的失效事件：过期结果被丢弃（或补偿淘汰），不再钉死在两级缓存直至 TTL（#519）。
- **生命周期管理** —— `CoherentCache` 继承 `AutoCloseable`，新增幂等 `close()`（注销事件订阅 + 关闭分布式缓存）；Spring 容器关闭时自动销毁缓存（#519）。
- **脏载荷自愈** —— 无法解码的 Redis 值被删除并按缓存未命中处理（回源重建），不再每次读取都抛异常（#520）。
- **null 值归一化** —— null 值在所有 codec 下一致写入为负缓存哨兵（此前：空串损坏、NPE 或 codec 各异的语义）（#520）。
- **Redis 故障降级** —— 默认情况下，Redis 读失败降级为缓存未命中语义（回源，业务无感），写/evict 失败仅记录告警。设置 `cocache.redis.strict-failure=true` 可恢复严格抛出行为（#520）。
- **Hash/Set 原子写入** —— 结构型 codec 写入使用单 key Lua 脚本（`DEL` + `HSET`/`SADD` + `EXPIRE`），消除并发写"字段混合"脏值。空 Map/Set 写入改为静默淘汰而非抛异常（#520）。
- **哨兵值可配置** —— 新属性 `cocache.redis.missing-guard-sentinel` 缓解业务数据与 `"_nil_"` 哨兵的碰撞（#520）。

### 破坏性变更与行为说明

- **API 移除（唯一破坏性变更）**：`AbstractCodecExecutor.setPipelined`/`serialize` 成员被移除——仅影响直接继承该抽象类的第三方 codec。
- `CodecExecutor.executeAndDecode` 返回类型可空化（`CacheValue<V>?`）——对实现者协变兼容；直接调用方重编译时需处理 `null`。
- Redis 故障默认降级（此前为抛异常）；JSON codec 的 null 值从 `"null"` 字面量往返变为负缓存哨兵。
- Spring 容器关闭时自动 close 缓存；`FactoryBean.getObject()` 返回记忆化实例（消除重复事件订阅）。
- `strict-failure` / `missing-guard-sentinel` 仅作用于自动装配（fallback）创建的缓存；自定义 `DistributedCache` Bean 不受影响。
- 线上格式（存储结构、失效消息）零变更——滚动升级完全兼容。

### 依赖版本

| 依赖 | 版本 |
|------------|---------|
| Spring Boot | 4.1.0 |
| CosId | 3.2.0 |
| Guava | 33.6.0-jre |
| Kotlin | 2.4.10 |
| JUnit | 6.1.3 |

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:4.3.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>4.3.0</version>
</dependency>
```

## v4.2.0

**模块组：** `me.ahoo.cocache`

### 依赖版本

| 依赖 | 版本 |
|------|------|
| Spring Boot | 4.1.0 |
| CosId | 3.2.0 |
| Guava | 33.6.0-jre |
| Kotlin | 2.4.0 |
| JUnit | 6.1.1 |

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:4.2.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>4.2.0</version>
</dependency>
```

## 历史版本

完整的发布历史请查看 [GitHub Releases](https://github.com/Ahoo-Wang/CoCache/releases)。
