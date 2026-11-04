---
title: 从 6.x 升级
description: 一次切换把应用从 CoCache 6.x 升级到 8.0：停止 6.x，删除没有数据源的缓存的 key，再启动 8.x；并把 6.x 的类型、配置项、Redis ACL 与后端 SPI 对应到 8.0。
---

# 从 6.x 升级

CoCache 8.0 与 6.x 之间是一次彻底的切换，不是兼容的升级：

- **不能共存。** 8.x 不读也不写 6.x 的值，6.x 与 8.x 的实例不能共用一个缓存。使用同一个命名空间的所有应用要一起升级。
- **没有迁移工具。** 没有任何东西把 6.x 的数据拷进 8.x。有数据源的缓存从数据源重建；唯一副本在 Redis 中的数据过不了这次升级，除非你的应用重新写入它。
- **没有回滚工具。** 回到 6.x 就是反向做一遍同样的切换，靠手工完成（见[下文](#rollback)）。

8.0 需要 JDK 21 或更新版本、Spring Boot 4，以及 Redis 7.0 或更新版本。[更新日志](./changelog.md#unreleased)详细列出每一项破坏性变更，本页是对照表与操作步骤。

## 切换之前 {#prepare}

1. **为每个缓存选择类型。** 6.x 只有一个 `Cache`，有没有 loader 都是它；8.x 有五个类型，由一个问题选出：Redis 丢了一个 key，本进程能否按需重建它？按下面的[类型对照](#types)选择，拿不准时看[选择类型](./caches.md#types)。选错类型是升级中最严重的错误：声明为 `Cache` 的唯一副本默认一小时后过期。
2. **修改代码。** 大部分 API 变化是编译错误，错误本身指向改法（见 [API](#api)）。少数变化能编译通过、行为却不同：仔细阅读[这些变化](#behavior)。
3. **重写配置。** 若干配置项改了含义或名字，Spring Boot 不加告警地忽略旧的配置项（见[配置项](#configuration)）。
4. **在第一个 8.x 实例启动之前更新 Redis ACL**（见 [ACL](#acl)）。
5. **规划冷启动。** 切换之后每个派生缓存都是空的，读取方会同时从数据源加载。分批启动实例，每批的量以数据源承受得住为准。
6. **导出必须保留的数据。** 只有 Redis 保存的数据（6.x 中没有 loader 的 `Cache`、`SetCache`）在切换的第 2 步被删除。之后还需要它的话，先导出，切换之后再写回去，或者给这个缓存一个数据源。

## 切换 {#steps}

1. **停止这些命名空间在 6.x 上的所有写入方与读取方。** 包括不是服务的进程：命令行工具、定时维护任务、控制面发布者、上报代理。
2. **删除没有数据源的缓存的旧 key**：6.x 中所有没有 loader 的 `Cache`（8.x 中的 `Store`、`VersionedStore` 或外部来源的 `Cache`）与所有 `SetCache`（8.x 中的 `SetStore`）。8.x 把 6.x 的值当作读不懂的内容，没有数据源的缓存遇到这样的读取直接失败，而不是去猜。以默认前缀 `cocache:` 为例（其它前缀换成你自己的），删除一个命名空间，在集群中逐个主节点执行：

   ```bash
   redis-cli --scan --pattern 'cocache:{namespace}:*' | xargs redis-cli unlink
   ```

   有数据源的缓存可以保留它们的 key：读取会回收旧值，并从数据源重新加载。仍然删掉它们，冷启动更可预期。
3. **启动 8.x。** 有数据源的缓存按需重新加载。没有数据源的缓存从空开始：按当初填充它们的方式重新填充（控制面重新发布，上报代理重新上报）。

### 回滚 {#rollback}

没有回滚工具。要回到 6.x：停止所有 8.x 实例，删除 8.x 写入的 key（6.x 同样读不懂格式 2，每次未命中都会绕过这些值回源，却不重新填充 Redis），再启动 6.x。有数据源的缓存会重建；只写进 8.x 中没有数据源的缓存的数据同样会丢失，除非你事先导出。

## 类型 {#types}

| 6.x | 8.x |
|-----|-----|
| 有 loader 的 `Cache` | [`Cache`](./cache.md)，不变 |
| 没有 loader、可由调用方重建的 `Cache`：Spring Cache、调用级 loader、调用方的 `put` | 带 `externallySourced()` 的 `Cache`；接口上用 `@CoCache(source = CoCache.Source.EXTERNAL)`（见[外部来源](./cache.md#externally-sourced)） |
| 没有 loader、唯一副本在 Redis 中的 `Cache` | [`Store`](./store.md)：`invalidate` 改为 `delete`；Redis 故障时必须读到“不存在”的缓存声明 `unknownRead = ABSENT` |
| `CacheSpec.versioned`，有带版本的 loader | [`VersionedCache`](./versioned-cache.md)，`versionedCacheSpec`：运维删除改为 `evict`（保留版本）或 `purge`（丢掉版本） |
| `CacheSpec.versioned`，没有 loader | [`VersionedStore`](./versioned-store.md)，`versionedStoreSpec`：`invalidateIfNewer` 改为 `deleteIfNewer` |
| `SetCache`、`SetCacheSpec` | [`SetStore`](./set-store.md)、`SetStoreSpec`、`setStoreSpec`：`invalidate` 改为 `clear` |
| `JoinCache` | 只读取时用 [`JoinView`](./join-cache.md)；要调用它的 `invalidate` 时用 `JoinCache`（两边都必须是 `Cache`） |
| `AsyncCache`、`AsyncSetCache` | 每个类型一个异步视图：`AsyncCache`、`AsyncVersionedCache`、`AsyncStore`、`AsyncVersionedStore`、`AsyncSetStore` |
| `cocache-coroutines`：`SuspendingCache`、`suspending()` 扩展函数 | 每个类型都有 `suspending()`，视图在 `cocache-api` 中；去掉这个模块与 `me.ahoo.cocache.coroutines` 的导入。`suspendingLoader` 与 `suspendingVersionedLoader` 在 `me.ahoo.cocache.core` 中 |
| 在 starter 之外组装缓存的库，或 `CacheProxies.create` | [`CoCacheFactory`](./caches.md#factory)：`create(spec)`、`create(type, spec)`，用 `lock` 与 `constrain` 固定库所依赖的字段 |
| 第三方后端 | `L1Store` 与能力访问器，并通过新的 TCK（见 [SPI](#spi)） |

6.x 中没有 loader 的 `Cache`，按它在 8.x 中成为的类型，变化如下：

| | 6.x 中没有 loader 的 `Cache` | 8.x 中带 `externallySourced()` 的 `Cache` | 8.x 的 `Store` |
|-|------------------------------|-------------------------------------------|----------------|
| Redis 读取失败 | 抛出（6.9 起） | 抛出 `CacheReadException`；`unknownRead = ABSENT` 时读到不存在，什么也不缓存 | 同左 |
| `invalidate` / `delete` 失败 | 抛出（6.9 起） | 在后台重试 | 抛出，从不重试 |
| Redis 中没有这个 key | 不存在，保留在 L2 中 | 未填充：不带 loader 的读取得到不存在，调用级 loader 会加载它 | 不存在，保留在 L2 中 |
| 默认 `ttl` | 1 小时 | 1 小时 | 永不过期 |
| 默认 `local-max-age` / `absent-ttl` | 5 分钟 / 1 分钟 | 5 分钟 / 1 分钟 | 30 秒 / 10 秒 |

## API {#api}

这些变化大多编译不过，编译错误正好指出要改的地方。

| 6.x | 8.x |
|-----|-----|
| `CacheRuntime.create(CacheSpec)` 对每种缓存都返回 `Cache` | 每种 spec 一个 `create`，返回对应的类型；`cache(name)` 返回 `ReadableCache` |
| `Lookup` 只有 `Present` 与 `Absent` | 第三种结果 `Unknown(error)`：Redis 失败，而这次读取没有数据源。穷举处理所有情形；`valueOrThrow()` 抛出它的 `CacheReadException`（见[读取](./caches.md#unknown)） |
| 读取失败时 `getAll` 抛出 | `getAll` 先完成其余 key，再抛出带 `unknownKeys` 与 `partialResult` 的 `CacheReadException`；`lookupAll` 逐个 key 返回 `Lookup` |
| `CachePolicy.DEFAULT`、`StoreSpec.DEFAULT_POLICY` 与其它 spec 上的常量 | `DefaultPolicies.CACHE`、`VERSIONED_CACHE`、`STORE`、`VERSIONED_STORE`、`SET_STORE` |
| `LoadPolicy.asyncTimeout` | `LoadPolicy.timeout` |
| 以 `LoadPolicy(maxConcurrent = 0)` 表示不限制 | `maxConcurrent = null`，或在 `policy { … }` 中用 `unlimited()` |
| `CachePolicy.foreignFormat`、`ForeignFormatMode` | 删除：有数据源的读取回收读不懂的内容，没有数据源的读取失败 |
| `Digest.sha1` | `Digest` 是载荷的 SHA-256，带算法标识；6.x 的摘要永远不会匹配 |
| `me.ahoo.cocache.coroutines` 中的 `SourceLoadCancelledException` | 移到 `me.ahoo.cocache.core`，适用于各种 loader |
| 针对 `CacheSpec.Builder` 与 `SetCacheSpec.Builder` 的 `CacheSpecCustomizer.customize` | 每种 spec 一个重载（`CacheSpec`、`VersionedCacheSpec`、`StoreSpec`、`VersionedStoreSpec`、`SetStoreSpec`）；定制器不能修改数据源或被锁定的字段 |
| `CoCacheSpringCacheManager(runtime, executor)` | `CoCacheSpringCacheManager(runtime)` |
| `CacheRuntime.Builder.asyncExecutor(…)`、`blockingExecutor(…)`；`CacheRuntime.blockingExecutor` | `CacheRuntime.Builder.executor(…)`；`CacheRuntime.executor`（一个执行器，见[异步](./async.md)） |
| `LoadObserver.observeAsync` | 删除：`observe` 同样看到异步与挂起的加载（见[可观测性](../operations/observability.md)） |
| 包住 CoCache 的 manager 的 Spring `TransactionAwareCacheManagerProxy` | `TransactionAwareSpringCacheManager`（见[事务](./spring-cache.md#transactions)） |

## 编译不报错的行为变化 {#behavior}

- **`cocache.defaults.*` 现在低于声明**，分为 `cocache.defaults.cache.*` 与 `cocache.defaults.store.*`。旧的 `cocache.defaults.<field>` 覆盖所有声明；现在 Spring Boot 不加告警地忽略它。要让某个值压过声明，用 `cocache.overrides.*`（只收紧）或 `cocache.caches.[name].*`（见[配置项](#configuration)）。
- **没有匹配的 loader 的 `Cache` 接口启动失败**，除非声明 `source = EXTERNAL`。6.x 中它没有数据源也能启动。
- **`lookup` 返回 `Unknown`，不再抛出。** 设置 `read-failure: throw` 时（以及 6.9 起没有 loader 的缓存上），读取失败让 `lookup` 抛出 `CacheReadException`；现在它返回 `Lookup.Unknown`。依赖这个异常在故障时拒绝（fail closed）、又把任何不是 `Present` 的结果当作“不存在”的代码（`as? Lookup.Present`、一个 `else` 分支），现在会在故障时放行（fail open）。
- **结果未知的 `Cache.put` 不抛出**（默认的 `invalidate-failure: retry` 下）：它改为登记这些 key 的失效（见[补偿结果未知的写入](./cache.md#failures)）。
- **同步的跟随者在 `load-timeout`（30 秒）之后不再等待**：等待别的调用方加载的读取以 `SourceLoadTimeoutException` 失败。6.x 中它会一直等下去。
- **带版本的缓存提前刷新。** 全局的 `refresh-window` 现在作用于 `VersionedCache`；写入方推送每一个版本时，在声明中写 `refreshWindow = off()`。
- **权威类型在 L2 中保留副本的时间更短**：默认 `local-max-age` 30 秒、`absent-ttl` 10 秒。
- **挂起的调用级 loader 在这次加载之中执行**：它在调用方线程上开始，在 CoCache 的执行器上继续，`load-timeout` 会取消它（见[协程](./coroutines.md#loader)）。
- **Spring 事务关注加载。** 回滚让事务加载的内容失效，事务中驱逐过的 key 在该事务内私有加载。`cocache.defaults.cache`（或 `cocache.caches.[name]`，只作用于这个缓存）下的 `invalidate-fills-on-rollback: false` 与 `fill-in-transaction: allow` 分别关闭这两项（见[事务内的回源](./spring-cache.md#transactional-loads)）。
- **Spring Cache 的读取在 `Unknown` 时失败。** Redis 故障时，非 sync 的 `@Cacheable` 与外部来源缓存上的 `retrieve(key)` 把 `CacheReadException` 交给 `CacheErrorHandler`；`cocache.spring-cache.degrade-on-error: true` 让它们改为执行方法（见 [Redis 故障时](./spring-cache.md#failures)）。指向 `Cache` 之外的缓存的缓存操作让启动失败。
- **端点默认不删除唯一副本。** `Store`、`SetStore` 或 `VersionedStore` 上的按 key `DELETE` 返回 `403`，直到设置了 `management.endpoint.cocache.allow-store-delete` 或 `allow-purge`。在 `VersionedCache` 上它是一次 `evict`。
- **被中断的阻塞调用等待它的 Redis 命令**（至多一个 `command-timeout`），不再立即失败，并保留中断标志。
- **读不懂的失效帧会重置**该频道上的缓存，不再被丢弃。
- **异步与挂起视图总是在 `cocache.executor` 上恢复**，从不在完成上一步的线程上就地继续；默认执行器下每次恢复都是一个新的虚拟线程。

## 配置项 {#configuration}

| 6.x | 8.x | 说明 |
|-----|-----|------|
| `cocache.defaults.<field>`（`cocache.defaults.ttl` 等） | `cocache.defaults.cache.<field>`（`Cache`、`VersionedCache`）或 `cocache.defaults.store.<field>`（`Store`、`VersionedStore`、`SetStore`） | **语义变了**：低于声明，只填充声明没有写出的字段。不允许 `defaults.store.ttl` |
| — | `cocache.overrides.cache.*`、`cocache.overrides.store.*` | 新增：高于声明，只限陈旧界限与 `read-failure`，且只收紧 |
| `cocache.caches.[name].*` | 不变，另有 `unknown-read`、`read-timeout`、`tombstone-ttl`、`unlock` | 条目指向不存在的缓存，或设置了该缓存的类型没有的配置项时，启动失败；`cocache.caches-validation: warn` 只告警 |
| `cocache.defaults.key-prefix` | `cocache.key-prefix` | 不参与分层；配置仍优先于声明 |
| `ttl-forever: true` | `ttl: forever` | |
| 不带单位的数字：`cocache.defaults` 与 `cocache.caches` 下按秒（`ttl: 600`），`cocache.redis` 下按毫秒（`command-timeout: 2000`） | `ttl: 10m`、`command-timeout: 2s` | 每个时长都必须带单位，不带单位的数字让启动失败（见[时长](./configuration.md#durations)）。注解仍按秒 |
| `refresh-window: 0` | `refresh-window: off`（或 `0s`） | |
| `load-max-concurrent: 0`（不限制） | `load-max-concurrent: unlimited` | `0` 让启动失败 |
| `load-async-timeout` | `load-timeout` | 同时约束同步的跟随者 |
| `foreign-format` | 删除 | |
| `cocache.redis.set-cache-eviction-check` | `cocache.redis.store-eviction-check` | 覆盖所有权威类型。存在权威缓存时，`allkeys-*` 与读不到的策略现在总是让启动失败；这个设置只决定 `volatile-*` 且 `ttl` 有限的情形 |
| — | `cocache.redis.assume-maxmemory-policy` | 新增：读不到 `INFO memory` 时假定的策略 |
| `cocache.spring-cache.retrieve-executor` | 删除 | `retrieve` 使用异步视图 |
| `cocache.async-executor`、`cocache.blocking-executor` | `cocache.executor` | 异步与挂起视图的每一步及其阻塞 loader 共用一个执行器，它必须容得下阻塞的任务。不设置时每个任务一个虚拟线程 |
| — | `invalidate-fills-on-rollback`、`fill-in-transaction`（在 `cocache.defaults.cache`、`cocache.overrides.cache` 与 `cocache.caches.[name]` 下）、`cocache.spring-cache.degrade-on-error` | 新增的 Spring 设置 |
| — | `management.endpoint.cocache.allow-store-delete`、`management.endpoint.cocache.allow-purge` | 新增的端点开关，默认关闭 |

[配置参考](./configuration.md)描述 8.x 的每个配置项，[生效值与来源](./configuration.md#policy-report)按缓存给出每个值来自哪里：升级之后检查一遍。

## Redis ACL {#acl}

| | 6.x | 8.x |
|-|-----|-----|
| 频道 | `&cocache:inv:*`，与 key 前缀无关 | 每个前缀一个 `&{keyPrefix}inv:*`（默认前缀下为 `&cocache:inv:*`） |
| 原子广播探测（`pubsub` 模式） | key 与频道 `cocache:probe:*` | key `cocache:!probe:*`（已被 `~cocache:*` 覆盖）与频道 `&cocache:!probe:*` |
| 脚本中的命令 | … `HGET`、`HEXISTS` … | 加上 `+getrange +strlen +hmget`；不再使用 `HGET` 与 `HEXISTS` |
| 空的 `keyPrefix` | — | key 与频道没有公共前缀：逐个列出命名空间（`~{namespace}:*`、`&inv:{namespace}`），另加 `~!*` |

现在创建缓存时会向它的频道发布一个空帧，所以缺少频道权限会在启动时失败，并给出要放行的模式。完整的列表见 [Redis ACL](../operations/index.md#acl)。

## Redis 中的 key 与值 {#wire-format}

- 存储 key 保持 `{keyPrefix}{namespace}:{encodedKey}` 的形状。内部 key 移到 `{keyPrefix}!` 之下。
- 失效频道为 `{keyPrefix}inv:{namespace}`（6.x 为 `cocache:inv:{namespace}`），前缀不同的应用不再收到彼此的失效。
- 命名空间必须符合 `[A-Za-z0-9._-]+`。含 `:` 的命名空间在创建时失败，错误信息给出如何把 `:` 之前的部分移到 `keyPrefix`；存储 key 仍是同样的字符串。
- 每个值都是线格式 2。`Store` 的值带写入标识与 SHA-256 摘要（多 60 字节），`VersionedStore` 的值带写入标识与版本（见 [Redis 内存](../operations/index.md#sizing-redis)）。

## 后端 SPI 与 TCK {#spi}

供实现 Redis 与内存后端之外的 `CoherenceBackend` 的作者参考（见[扩展 CoCache](../architecture/extending.md)）：

| 6.x | 8.x |
|-----|-----|
| `RemoteStore`（阻塞）、`AsyncRemoteStore` | `L1Store`，它的操作都是 `suspend`；Java 后端实现 `FutureL1Store`，返回 `FutureL1Store.adapt(…)` |
| `AsyncInvalidationChannel` | `InvalidationChannel`，它的 `publish` 是 `suspend`；Java：`FutureInvalidationChannel.adapt(…)` |
| `CoherenceBackend.versionedWrites`，以及默认实现会抛出的可选方法 | 能力访问器 `conditional()`、`versioned()` 与 `sets()`，不支持时返回 `null` |
| `store(namespace)`、`subscribe(namespace, …)`、`publish(namespace, …)` | 改收 `Keyspace(keyPrefix, namespace)` |
| `RemoteRead`、`RemoteConditionalRead`、`RemoteConditionalWrite`、`RemoteVersionedWrite` | `L1Read`、`L1ConditionalRead`、`L1ConditionalWrite`、`L1VersionedWrite` |
| `fill` 返回 `Boolean`；`reclaim` 接收 `Digest` | `fill` 返回 `L1Fill`；`reclaim` 接收 `ContentFingerprint` |
| 会覆盖读不懂的内容的 `addMembers` / `removeMembers`；`foreignAsAbsent` 参数 | 拒绝这类内容的 `SetL1Store.addMembers` / `removeMembers`；参数已删除 |
| — | 新增：`ConditionalL1Store.deleteIfMatch`、`VersionedL1Store.evictKeepingVersion`、读取的 `timeout`、`Envelope.Scalar.writer` 与 `digest`、表示命令从未发出的 `L1RejectedException` |
| `InvalidationListener.onInvalidated` 收到存储 key | 收到编码后的 key，即 `{keyPrefix}{namespace}:` 之后的部分 |
| TCK：`Async*Spec` 套件、`EngineSpec.supportsConditionalWrites` | 删除：每个套件都声明 `expectedCapabilities`，能力不符时失败（fail closed）；`access = CacheAccess.ASYNC` 经异步视图重跑套件（见 [TCK](../architecture/extending.md#tck)） |
