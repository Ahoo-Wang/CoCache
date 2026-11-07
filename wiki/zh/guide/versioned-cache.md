---
title: VersionedCache
description: 派生的带版本缓存类型：何时用 VersionedCache，声明它的带版本 loader，写入方的 putIfNewer 与 invalidateIfNewer，evict 与 purge，提前刷新，默认值，以及故障。
---

# VersionedCache

`VersionedCache<K, V>` 保存**带版本的派生副本**：写入方知道它写入的每个值的版本、带版本的数据源又能重建的状态。事件溯源的聚合只有一个写入方、版本严格递增；CDC 流带有提交序号。写入方在提交之后立即把每个新状态推进 Redis；Redis 丢了某个 key 时，loader 连同版本一起重建它。Redis 在值旁边保存版本，重复投递或重放的旧版本因此永远替换不了新版本。

| | `VersionedCache<K, V>` |
|-|------------------------|
| 数据来自 | 带版本的 loader（`versionedLoader` / `versionedAsyncLoader`），加上写入方的 `putIfNewer` |
| 读取 | `get`、`getAll`、`lookup`、`lookupAll`、`getVersioned` |
| 写入 | `putIfNewer`、`invalidateIfNewer`（墓碑） |
| 删除 | `evict` 保留版本栅栏；`purge` 丢掉它 |
| Redis 读失败时 | 从数据源加载（`read-failure: degrade`） |
| Redis 写失败时 | 抛出 `CacheWriteException`；用同一个版本重试是安全的 |
| 默认寿命 | `ttl` 1 小时 ± 1 分钟，墓碑与值同寿，`local-max-age` 5 分钟 |
| Spring Cache、`TransactionalInvalidator` | 不可以 |

没有 `put`、`putIfMatch` 与普通的 `invalidate`：没有丢掉版本的写入。有带版本的写入方、却没有可以重新加载的数据源的数据，属于 [`VersionedStore`](./versioned-store.md)。

## 声明 {#declare}

::: code-group

```kotlin [Kotlin]
@Bean
fun orderViewSpec(mapper: ObjectMapper, repository: OrderRepository): VersionedCacheSpec<String, OrderView> =
    versionedCacheSpec("order-view") {
        codec = jacksonCodec<OrderView>(mapper)
        versionedLoader = CacheLoader { id ->
            val state = repository.find(id) ?: return@CacheLoader null          // 从来没有
            Versioned(if (state.deleted) null else state.view, state.version)   // 已删除：Versioned(null, version)
        }
    }
```

```java [Java]
@Bean
VersionedCacheSpec<String, OrderView> orderViewSpec(ObjectMapper mapper, OrderRepository repository) {
    return VersionedCacheSpec.<String, OrderView>builder("order-view")
        .codec(JacksonCodec.of(mapper, OrderView.class))
        .versionedLoader(id -> repository.find(id)
            .map(state -> new Versioned<>(state.deleted() ? null : state.view(), state.version()))
            .orElse(null))
        .build();
}
```

:::

接口式声明 `interface OrderViews : VersionedCache<String, OrderView>` 按类型查找数据源：`CacheLoader<K, Versioned<V>>` 或 `AsyncCacheLoader<K, Versioned<V>>` 类型的 bean，或 `@CoCache(loader)` 指定的 bean。没有带版本数据源的 `VersionedCache` 启动失败。在 Kotlin 中，`suspendingVersionedLoader { … }` 把一个挂起函数设为数据源（见 [Kotlin 协程](./coroutines.md#loader)）。

## 带版本的 loader {#versioned-loaders}

数据源返回 `Versioned(value, version)`，填充把版本与值一起保存。

- **删除要写成 `Versioned(null, version)`。** 它写入一个墓碑，挡住这个版本与更旧的版本，与 `invalidateIfNewer(key, version)` 相同，重放的旧状态因此无法让 key 复活。
- **单纯的 `null` 表示“从来没有”**，写入不带版本的负缓存，任何按版本的写入都能替换它。
- 如果 Redis 中这个 key 已经是带版本的内容（比如 [`evict`](#evict) 之后），不带版本的 `null` 会被**拒绝**：读取返回“不存在”，但 Redis 中什么也不写，于是在版本栅栏到期之前每次未命中都重新加载。每次拒绝计入 `cocache.fill.unversioned.absent`，每个缓存第一次时告警。
- **进行中的加载永远赢不了写入。** 读取方持有加载 lease 时 `putIfNewer` 先落地，它的写回会被拒绝，即使它从数据源读到的版本更高。让 Redis 中的版本前进的填充就是一次写入：它广播失效，执行加载的实例丢弃自己的副本，而不是把它填进 L2。

## 由写入方写入 {#writing}

```kotlin
// 每次提交之后（重放与重复投递时也一样）
when (views.putIfNewer(id, view, version)) {
    VersionedPut.Written -> Unit                  // 更新：已写入并广播
    is VersionedPut.Stale -> Unit                 // Redis 中已是这个版本或更新的版本
}
views.invalidateIfNewer(id, deletedAtVersion)     // 删除：挡住旧版本的墓碑

views.getVersioned(id)                            // Versioned(view, version)，或 null
```

- `putIfNewer` 在一个脚本中只写入大于 Redis 中版本的版本，返回 `Written`。相等或更旧的版本返回 `Stale(current)`，什么也不改。
- `invalidateIfNewer` 写入墓碑。读取把这个 key 看作不存在，不调用 loader；版本相同或更旧的 `putIfNewer` 返回 `Stale`。更新的版本可以让 key 重新出现。
- `getVersioned` 与 `get` 一样读穿透，同时返回版本：遇到墓碑或不存在的 key 时为 `null`。
- 两种写入都可以带寿命：`putIfNewer(key, value, version, ttl)` 与 `invalidateIfNewer(key, version, ttl)`。`ttl` 为 `null` 表示策略的寿命，与不带 `ttl` 时相同（`ttlOf` 优先；墓碑为 `tombstone-ttl`），寿命可选（`ttl: Duration?`）的写入方因此直接传入，不必分支。
- 叫 `invalidate` 还是 `delete` 取决于类型的角色：派生副本可以从数据源重建，所以移除它是失效；权威副本不能，所以移除它是删除（`VersionedStore.deleteIfNewer`）。

完整规则见[按版本写入](./freshness.md#versioned-writes)：版本如何比较、顺序成立多久，以及为什么相等的版本仍发布失效消息。

## `evict` 与 `purge` {#evict}

- **`evict(key)` 保留版本。** 它在每个实例上丢弃副本，并把 Redis 中带版本的值或墓碑换成一个保留版本的淘汰标记：下一次读取从数据源加载，迟到的旧版本仍被拒绝。数据源落后于 Redis 中的版本时，这次加载的结果照常返回（在 L2 中保留 `degraded-local-max-age`），但不写入 Redis，计入 `cocache.fill.rejections`，直到数据源追上。标记存活 `tombstone-ttl`。失败时抛出，从不在后台重试。
- **`purge(key)` 丢掉版本。** 它删除 key，之后任何版本（即使是迟到的旧版本）都能写入。它是运维工具：从不重试，失败时抛出。

端点的按 key `DELETE` 在 `VersionedCache` 上执行 `evict`，`?purge=true` 执行 `purge`，后者需要 `management.endpoint.cocache.allow-purge=true`（见[失效一个 key](../operations/observability.md#invalidate-key)）。

## 提前刷新 {#refresh-ahead}

设置了 `refresh-window` 时，`VersionedCache` 像 `Cache` 一样提前刷新；声明没有写出它时，来自 `cocache.defaults.cache.refresh-window` 的窗口同样生效。刷新只写回不低于 Redis 中版本的值，实例的本地副本也从不退回更旧的版本：数据源返回更旧的版本时（写入方推得比数据源快），实例保留自己的副本，退避后再试。即使加载很慢，保留的副本也随 Redis 中的值到期。写入方反正会推送每个版本时，在 `policy { … }` 中设置 `refreshWindow = off()`，或用 `cocache.caches.[name].refresh-window: off` 关闭提前刷新。

## 默认值与配置 {#defaults}

`VersionedCache` 以 `DefaultPolicies.VERSIONED_CACHE` 为起点：寿命与 `Cache` 相同，墓碑与淘汰标记与值同寿（`tombstone-ttl` 未设置）。`ttl` 与 `tombstone-ttl` 还限定重复投递的旧版本被拒绝多久：值或墓碑一旦过期，任何版本都能再次写入。按重复投递的时间窗口设置它们。出于同样的原因，`cocache.overrides` 不能缩短它们（见[各层能设置什么](./configuration.md#field-layers)）。

## Redis 故障时 {#failures}

- 带数据源的读取降级到数据源，同 `Cache`。
- `putIfNewer`、`invalidateIfNewer`、`evict` 与 `purge` 抛出 `CacheWriteException`。用同一个版本重试 `putIfNewer` 或 `invalidateIfNewer` 是安全的：第一次已经生效时，重试返回 `Stale(version)` 并再次发布失效。
- 失败的按版本写入从不进入后台重试队列：由写入方重试，通常是重新投递那个事件。

## 视图 {#views}

`async()` 返回 `AsyncVersionedCache`，`suspending()` 返回 `SuspendingVersionedCache`，方法相同。带版本的缓存不能放在 Spring Cache 或 `TransactionalInvalidator` 后面，它们需要 `invalidate`；指名它的缓存操作会让启动失败。
