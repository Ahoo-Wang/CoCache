---
title: VersionedStore
description: 带版本的权威类型：何时用 VersionedStore，声明它，putIfNewer 与 deleteIfNewer，墓碑与 tombstone-ttl，purge，读取，默认值，以及 Redis 故障。
---

# VersionedStore

`VersionedStore<K, V>` 在 Redis 中保存它的值的**唯一副本，并带版本**。它用于写入方必须扛住迟到、重放或重复写入，又没有可以重新加载的数据源的状态：设备上报的最新状态、消费者从一个无法按需重放的流投影出的状态。每次写入都带版本，Redis 拒绝任何不比它所持版本更新的版本，重复投递的旧事件因此无法撤销更新的事件。

| | `VersionedStore<K, V>` |
|-|------------------------|
| 数据来自 | 你的按版本写入 |
| 读取 | `get`、`getAll`、`lookup`、`lookupAll`、`getVersioned` |
| 写入 | `putIfNewer` |
| 删除 | `deleteIfNewer`（墓碑）；`purge` 丢掉版本 |
| Redis 读失败时 | `Lookup.Unknown`：`get` 抛出 `CacheReadException`，除非设置了 `unknownRead = ABSENT` |
| Redis 写失败时 | 抛出 `CacheWriteException`；用同一个版本重试是安全的 |
| 默认寿命 | `ttl` 永不过期，`tombstone-ttl` 1 天，`local-max-age` 30 秒，`absent-ttl` 10 秒 |
| 需要 | 不驱逐其 key 的 Redis（见[驱逐检查](./configuration.md#eviction-check)） |

没有 `put`、`delete` 与 `putIfMatch`：没有丢掉版本的写入。数据源能重建的带版本副本属于 [`VersionedCache`](./versioned-cache.md)。

## 声明 {#declare}

::: code-group

```kotlin [Kotlin]
@CoCache
interface DeviceStatus : VersionedStore<String, Status>

// 或者声明为 spec bean
@Bean
fun deviceStatusSpec(mapper: ObjectMapper): VersionedStoreSpec<String, Status> =
    versionedStoreSpec("device-status") {
        codec = jacksonCodec<Status>(mapper)
        policy { tombstoneTtl = Duration.ofDays(7) }     // 覆盖一周的重复投递
    }
```

```java [Java]
@CoCache
public interface DeviceStatus extends VersionedStore<String, Status> {}

@Bean
VersionedStoreSpec<String, Status> deviceStatusSpec(ObjectMapper mapper) {
    return VersionedStoreSpec.<String, Status>builder("device-status")
        .codec(JacksonCodec.of(mapper, Status.class))
        .updatePolicy(policy -> policy.tombstoneTtl(Duration.ofDays(7)))
        .build();
}
```

:::

`VersionedStore` 没有数据源：它的接口上写 `@CoCache(loader)` 或 `source = EXTERNAL` 让启动失败。创建它需要支持按版本写入的后端；Redis 与内存后端都支持。

## 写入 {#writing}

```kotlin
// 写入方，处理每个事件时（重放与重复投递时也一样）
when (statuses.putIfNewer(deviceId, status, event.sequence)) {
    VersionedPut.Written -> Unit                  // 更新：已写入并广播
    is VersionedPut.Stale -> Unit                 // Redis 中已是这个版本或更新的版本
}
statuses.deleteIfNewer(deviceId, event.sequence) // 挡住旧版本的墓碑
statuses.getVersioned(deviceId)                  // Versioned(status, version)，或 null
```

- `putIfNewer` 在一个脚本中只写入大于 Redis 中版本的版本，返回 `Written`。相等的版本（重复）或更旧的版本（迟到）返回 `Stale(current)`，什么也不改。
- `deleteIfNewer` 写入**墓碑**：读取把这个 key 看作不存在，版本相同或更旧的 `putIfNewer` 返回 `Stale`。更新的版本可以让 key 重新出现。
- 每次写入都在值的头部带写入标识，与 `Store` 相同。
- 两种写入都可以带寿命：`putIfNewer(key, value, version, ttl)` 与 `deleteIfNewer(key, version, ttl)`。`ttl` 为 `null` 表示策略的寿命，与不带 `ttl` 时相同（`ttlOf` 优先；墓碑为 `tombstone-ttl`），寿命可选（`ttl: Duration?`）的写入方因此直接传入，不必分支。
- 叫 `invalidate` 还是 `delete` 取决于类型的角色：派生副本可以从数据源重建，所以移除它是失效（`VersionedCache.invalidateIfNewer`）；权威副本不能，所以移除它是删除。

完整规则见[按版本写入](./freshness.md#versioned-writes)：版本如何比较、为什么相等的版本仍发布失效消息，以及顺序成立多久。

## 墓碑与 `purge` {#tombstones}

- **墓碑总会过期**，在 `tombstone-ttl`（默认 1 天）或 `deleteIfNewer` 的 `ttl` 参数之后。墓碑过期之后，迟到的旧版本又能写入，所以墓碑的寿命要覆盖重复投递的时间窗口。墓碑永不过期（`tombstone-ttl: forever`）的 `VersionedStore` 构建失败：每个删除过的 key 都会永远留在 Redis 中。
- **`purge(key)`** 删除 key 及其版本，之后任何版本（即使是迟到的旧版本）都能写入。失败时抛出，从不重试。端点的按 key `DELETE` 在 `VersionedStore` 上就是 purge，需要 `management.endpoint.cocache.allow-purge=true`（见[失效一个 key](../operations/observability.md#invalidate-key)）。
- `ttl` 默认永不过期，`volatile-*` 从不驱逐这样的 key。值也可以有有限的 `ttl`；值过期之后，key 中没有版本，任何版本都能再次写入。

## 读取 {#reading}

`VersionedStore` 的读取同 [`Store`](./store.md#reading)：不申请加载 lease，Redis 中没有的 key（或墓碑）是不存在，Redis 故障是 [`Unknown`](./caches.md#unknown)，`unknownRead = ABSENT` 把它变成 `null`。`getVersioned` 返回值及其版本，或 `null`。

## Redis 故障时 {#failures}

`putIfNewer`、`deleteIfNewer` 与 `purge` 抛出 `CacheWriteException`。无论结果如何，用同一个版本重试 `putIfNewer` 或 `deleteIfNewer` 都是安全的：第一次已经生效时，重试返回 `Stale(version)` 并再次发布失效。不在后台重试任何东西。

## 默认值与持久性 {#defaults}

`VersionedStore` 以 `DefaultPolicies.VERSIONED_STORE` 为起点：`ttl` 永不过期，`tombstone-ttl` 1 天，`local-max-age` 30 秒，`absent-ttl` 10 秒。`cocache.overrides` 不能缩短 `ttl` 与 `tombstone-ttl`，因为它们还限定旧版本被拒绝多久（见[各层能设置什么](./configuration.md#field-layers)）。持久性等于 Redis 的持久性，驱逐检查同样适用，同 [`Store`](./store.md#durability)。

## 视图 {#views}

`async()` 返回 `AsyncVersionedStore`，`suspending()` 返回 `SuspendingVersionedStore`，方法相同。
