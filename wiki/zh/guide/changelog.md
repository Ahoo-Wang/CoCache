---
title: 更新日志
description: CoCache 版本发布历史和重要变更。
---

# 更新日志

## 未发布

**模块组：** `me.ahoo.cocache` · 计划为 6.9.0。次版本：没有 loader 的缓存（Redis 是唯一副本）有两项默认值变化，条件写入与按版本写入有一项广播修复。没有删除任何 API。`CachePolicy.invalidateFailure` 改为可空，JVM 签名不变，已编译的调用方照常运行。Redis 线格式与失效频道不变，6.9.0 实例可以与任何 6.x 实例共用 Redis，逐台升级。

### 行为变化：没有 loader 的读取在 Redis 故障时失败

- 没有 loader 的读取（没有 loader 的 `Cache` 经 `get`、`lookup` 或 `getAll` 读取）在 Redis 读取失败时抛出 `CacheReadException`，与 `read-failure` 无关，也不在 L2 中缓存任何东西。这样的读取没有可降级的数据源：跳过 Redis 只能得到“不存在”。这与 `getIfChanged` 和 `SetCache` 一致。
- 6.8.0 及以前，读取返回 `null`，并把这个 `null` 作为负缓存在 L2 中保留 `degraded-local-max-age`（10 秒）。故障期间调用方把存在的 key 读成不存在；恢复后的这 10 秒内 `get(key)` 仍返回 `null`，`get(key, loader)` 也返回 `null`、不调用它的 loader。
- `get(key, loader)` 有数据源，照常降级。它合并到一次失败的、没有 loader 的读取时，改用自己的 loader 加载，而不是跟着失败。
- 没有 loader 时其余跳过 Redis 的读取（等待 lease 超时、失效仍在重试、更高格式版本且 `foreign-format: reclaim`、回收失败）仍返回 `null`，但不再把它缓存为负缓存，`get(key, loader)` 因此不会被它挡住。
- Spring Cache：不带 sync 的 `@Cacheable` 是没有 loader 的读取，Redis 读取失败会交给 Spring 的 `CacheErrorHandler`（见 [Spring Cache](./spring-cache.md#failures)）。

### 行为变化：没有 loader 时 `invalidate-failure` 默认为 `throw`

- `invalidate-failure`（`CachePolicy.invalidateFailure`）默认改为未设置，按缓存决定：有 loader 时与以前一样是 `retry`，没有 loader 时是 `throw`。显式设置的 `retry` 或 `throw` 照常生效。
- 没有 loader 时延迟的重试会丢失更新：实例 A 的 `invalidate` 失败、进入重试队列；实例 B 随后 `put` 写入这个 key；约 100 ms 后 A 的重试删掉了 B 的值。有 loader 时删除总是安全的，下一次读取会重新加载。
- Spring Cache 的缓存通常没有 loader，`@CacheEvict` 失败因此改为抛出 `CacheWriteException`（经 Spring 的 `CacheErrorHandler`），不再重试。那里被注解的方法就是数据源、删除是安全的：在这些缓存上设置 `invalidate-failure: retry` 即可保持 6.8 的行为。
- `CachePolicy.invalidateFailure` 与 `CachePolicy.Builder.invalidateFailure` 的类型改为 `InvalidateFailureMode?`。读取这个属性的 Kotlin 代码看到可空类型。针对 6.8 及以前编译的二进制（包括 6.4 的构造函数）得到新的默认值。

### 修复：没有原子广播时，重放的条件写入与按版本写入会广播

- 没有原子广播时（`sharded` 频道模式，或启动探测回退时），`putIfNewer` 或 `putIfMatch` 可能已写入 Redis，而单独的发布失败了。调用方得到 `CacheWriteException` 并按文档重放，但重放返回 `Stale(version)` 或 `Conflict(自己的摘要)`，不发布任何消息，其它实例的 L2 副本一直陈旧到 `local-max-age`。
- 现在 `putIfNewer` / `invalidateIfNewer` 返回的 `Stale` 版本等于给出的版本时，以及 `putIfMatch` 冲突且当前摘要等于自己的值的摘要时，引擎也发布失效。多余的失效只会丢弃副本。更旧的版本与其它冲突仍不发布。原子广播不变。

### TCK

- `EngineSpec`、`FaultSpec` 与 `VersionedSpec`（及其异步版本）新增上述行为的用例。`VersionedSpec.refusedVersionsChangeNothingAndDoNotBroadcast` 现在要求没有原子广播的后端上相等的版本会广播。

## v6.8.0（当前版本）

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

- `CoherenceBackend.versionedWrites`（默认 `false`）、`RemoteStore.putIfNewer` / `AsyncRemoteStore.putIfNewerAsync`（默认以 `UnsupportedOperationException` 拒绝：“先读后写”的默认实现会让迟到的版本覆盖更新的版本）、`RemoteVersionedWrite`、`Fill.version`（新的构造函数；6.7 的构造函数、`copy` 与 `componentN` 不变）与 `Envelope.Scalar.version`。支持版本的后端原子地覆盖两个方法，在 `fill`、`put` 与 `putAll` 时保存 `Envelope.Scalar.version`、读取时原样返回。见[扩展](../architecture/extending.md#remote-store)。
- TCK 新增 `VersionedSpec` 与 `AsyncVersionedSpec`，用于支持 `versionedWrites` 的后端。

## v6.7.0

**模块组：** `me.ahoo.cocache` · 次版本，只有新增：`Cache`、`AsyncCache` 与 `SuspendingCache` 以 default 方法新增条件读写（没有覆盖它们的实现抛出 `UnsupportedOperationException`），`cocache-api` 的 SPI 新增 `RemoteStore.readIfChanged` / `putIfMatch` 及 `AsyncRemoteStore` 中对应的异步方法（都带默认实现）。已有的调用方、自定义 `Cache` / `SuspendingCache` 实现与自定义后端照常编译与运行。Redis 线格式与失效频道不变（新脚本只读取现有格式），6.7.0 实例可以与任何 6.x 实例共用 Redis 并逐台升级。

### 条件读写

用于 Redis 是唯一副本、值以“读取—修改—写回”更新的缓存，见[条件写入](./freshness.md#conditional-writes)。

- `putIfMatch(key, expected, value[, ttl])` 只在 Redis 中仍是摘要为 `expected` 的值时写入（`null`：只在 Redis 中没有值时写入）。比较与写入是一个 Lua 脚本，只在写入时广播失效。返回 `Written(digest)` 或 `Conflict(current)`。写入成功时的寿命与栅栏规则同 `put`。
- `getIfChanged(key, known)` 读 Redis、从不读 L2，值的摘要仍等于 `known` 时返回 `Unchanged`，不传输值；否则返回 `Present(value, digest)` 或 `Absent`。它省的是网络传输，不是 Redis 的 CPU：脚本每次调用都读取并哈希这个值，在 Redis 7.4 上 8 MB 的值约 20 ms（其中约 13 ms 是普通的 L1 读取同样要付的读取）。摘要不在写入时保存，因为那需要改变线格式。
- 摘要是值编码后的字节的 SHA-1，即 `reclaim` 对标量值一直使用的摘要。
- 读与写对读不懂的内容分类相同。更高版本的 CoCache 写入的值与集合总是抛出，`cause` 为 `ForeignFormatException`，内容不被修改；`reclaim` 也从不把它们当作没有值。不是 CoCache 6 写入的内容按 `foreign-format` 处理：`fail`（默认）抛出，`reclaim` 视为没有值。编解码器无法解码的值使 `getIfChanged` 抛出，`putIfMatch` 遇到它只会冲突。Redis 故障时 `getIfChanged` 抛出 `CacheReadException`，与 `read-failure` 无关。
- 新指标 `cocache.conditional.conflicts` 与 `cocache.conditional.unchanged`（[可观测性](../operations/observability.md#metrics-writes)）。

### SPI

- `RemoteStore.readIfChanged` / `putIfMatch`、`AsyncRemoteStore.readIfChangedAsync` / `putIfMatchAsync`，以及结果类型 `RemoteConditionalRead` / `RemoteConditionalWrite`。`readIfChanged` 的默认实现先读、在客户端比较：正确，只是总是传输值。`putIfMatch` 的默认实现抛出 `UnsupportedOperationException`（异步版本以它失败）：先读后 `put` 的默认实现在并发下会丢失更新。后端只有像 Redis 与内存后端那样用一次原子操作覆盖两者，才支持条件写入。见[扩展](../architecture/extending.md#remote-store)。
- TCK（`EngineSpec`）覆盖这两个操作。没有实现 `putIfMatch` 的后端设置 `supportsConditionalWrites = false` 跳过这些用例；否则必须全部通过，其中的并发用例让所有写入方在同一个摘要上竞争。

## v6.6.0

**模块组：** `me.ahoo.cocache` · 次版本：`cocache-api` 的 SPI 新增 `RemoteStore.addMembersIfReadable` / `removeMembersIfReadable` 及 `AsyncRemoteStore` 中对应的异步方法，都带默认实现，已有的后端照常编译与运行。两项行为变化收紧没有数据源的缓存的 fail closed 处理：`SetCache` 的写入遇到读不懂的内容时失败，没有数据源的读取遇到更高版本写入的值时失败（见下文）。Redis 线格式与失效频道不变，6.6.0 实例可以与任何 6.x 实例共用 Redis 并逐台升级。

### 行为变化：`SetCache` 的写入遇到读不懂的内容时失败

- `SetCache.add` 与 `remove` 现在与读取一样遵循 `foreign-format`。为 `fail` 时（`SetCache` 的默认值），在本缓存读不懂的内容上增删成员抛出 `CacheWriteException`（`cause` 为 `ForeignFormatException`）。这类内容包括未迁移的 5.x 原生 Set、String 或 Hash，没有 6.x 格式字段的 Hash，6.x 的标量值，以及更高版本的 CoCache 写入的集合。检查与写入是一个原子步骤：key 的内容与 TTL 不变，不广播，这次写入计入 `cocache.unknown.formats` 与 `cocache.write.failures`。
- 6.5.0 及以前，对未迁移的原生 Set 调用一次 `add` 就会替换它的全部成员，之后 key 看起来已经迁移，`CoCache5Migrator` 跳过它，旧成员永久丢失；`remove` 静默地什么也不做并报告成功。给缓存设置 `foreign-format: reclaim` 即可保留这一行为。
- 整体写入（`put`、`putAll`，`Cache` 与 `SetCache` 都是）照旧覆盖任何内容，因为调用方给出的是完整的值。被覆盖的 5.x 内容不会被迁移工具备份，迁移工具之后会跳过这个 key。见 [Redis 是唯一副本的缓存](./migration-6.md#foreign-format)。

### 行为变化：没有数据源时更高格式版本的值读取失败

- 没有数据源的读取命中更高版本的 CoCache 写入的值（混合版本滚动升级期间）时，现在与 `foreign-format: fail` 下读不懂的内容一样失败：抛出 `CacheReadException`（`cause` 为 `ForeignFormatException`，`newer = true`），保留 key、不写 L2，计入 `cocache.unknown.formats`。此前读作不存在，在 Redis 是唯一副本时就是悄悄丢数据。`foreign-format: reclaim` 保留旧行为。有数据源的读取不变：回源，且不改动更高版本的值。

### SPI

- `RemoteStore.addMembersIfReadable` / `removeMembersIfReadable` 及 `AsyncRemoteStore` 中对应的异步方法：同 `addMembers` / `removeMembers`，但不改动不是本协议集合的内容，并返回它的格式版本。默认实现先读后写，不原子，也认不出更高的格式版本；后端应覆盖为一次原子的检查加写入，Redis 与内存后端即如此。见[扩展](../architecture/extending.md#remote-store)。
- `ForeignFormatException` 新增 `newer` 属性与构造参数，保留 6.5.0 的构造函数。

## v6.5.0

**模块组：** `me.ahoo.cocache` · 次版本。`cocache-core` 与 `cocache-redis` 新增 API；没有数据源的读取有一项默认行为变化（见下文）。Redis 线格式与失效频道不变，6.x 实例可以共用 Redis 并逐台升级。

### 迁移 Redis 是唯一副本的缓存

`CoCache5Migrator`（`cocache-redis`）把 CoCache 5.x 的数据转换为 6.x 格式，用于没有数据源、以 Redis 为权威存储的缓存，以及 5.x 的 `SetElementsCache`。它覆盖 5.x 的全部值格式：JSON 或普通字符串、原生 Set（迁移为 `SetCache` 或 `Cache<K, Set<String>>`）、Hash 与负缓存，并保留每个 key 的剩余寿命。它可以重复执行，按缓存报告读取、写入、跳过与失败的 key 数，遇到第一个无法解码的 key 即中止，并提供只读的 `dryRun`。原地迁移先备份每个 5.x 值，`rollback` 从备份恢复。见 [Redis 是唯一副本的缓存](./migration-6.md#redis-as-store)。

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

**模块组：** `me.ahoo.cocache` · **主版本。** CoCache 按 [RFC 0003](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0003-target-architecture.md) 的一致性协议重建。**不能滚动升级：** Redis 中的值格式与失效频道都已改变，5.x 与 6.0 实例不能共享 Redis。请按[迁移指南](./migration-6.md)整体切换。

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

已删除的 5.x 配置项会让启动失败，错误信息会给出替代项（如果有的话）。部分改名同时改变了语义（例如 L2 现在总受 `localMaxAge` 限龄，默认 5 分钟），详见[行为变化](./migration-6.md#semantics)。

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
