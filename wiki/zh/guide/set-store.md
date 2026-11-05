---
title: SetStore
description: 集合的权威类型：何时用 SetStore，用元素编解码声明它，原子的 add 与 remove，put 与 clear，读取，默认值，故障，以及数据库拥有的集合何时应改用 Cache。
---

# SetStore

`SetStore<K, E>` 在 Redis 中保存**集合的唯一副本**：索引、成员列表、一个房间的在线会话。元素的增删在 Redis 中原子完成，不同实例的并发更新不会丢失，修改集合的实例也不必先读出整个集合。

| | `SetStore<K, E>`（一个 `ReadableCache<K, Set<E>>`） |
|-|------------------------------------------------|
| 数据来自 | 你的元素写入 |
| 读取 | `get`（不存在时为空集合）、`getAll`、`lookup`、`lookupAll` |
| 写入 | `add`、`remove`（按元素原子执行）；`put`、`putAll`（替换整个集合） |
| 删除 | `clear` |
| Redis 读失败时 | `Lookup.Unknown`：`get` 抛出 `CacheReadException`；没有 `unknownRead` |
| Redis 写失败时 | 抛出 `CacheWriteException`；从不重试 |
| 默认寿命 | `ttl` 永不过期，`local-max-age` 30 秒，`absent-ttl` 10 秒 |
| 需要 | 不驱逐其 key 的 Redis（见[驱逐检查](./configuration.md#eviction-check)） |

## 声明 {#declare}

::: code-group

```kotlin [Kotlin]
@CoCache
interface OnlineUsers : SetStore<String, String>

// 或者声明为 spec bean
@Bean
fun roomMembersSpec(): SetStoreSpec<String, String> =
    setStoreSpec("room-members") {
        elementCodec = StringCodec
    }
```

```java [Java]
@CoCache
public interface OnlineUsers extends SetStore<String, String> {}

@Bean
SetStoreSpec<String, String> roomMembersSpec() {
    return SetStoreSpec.<String, String>builder("room-members")
        .elementCodec(StringCodec.INSTANCE)
        .build();
}
```

:::

spec 设置 `elementCodec`，即单个元素的编解码。没有编解码 bean 的接口用应用的 Jackson `ObjectMapper` 把元素编码为 JSON。`SetStore` 没有数据源：它的接口上写 `@CoCache(loader)` 或 `source = EXTERNAL` 让启动失败。创建它需要支持集合的后端；Redis 与内存后端都支持。

## 使用 {#using}

```kotlin
online.add(roomId, listOf(userId))        // 需要时创建集合
online.remove(roomId, listOf(userId))     // 移空时删除 key
online.put(roomId, setOf("a", "b"))       // 替换整个集合
online.clear(roomId)                      // 删除集合
val members: Set<String> = online[roomId] // 不存在时为空集合
```

- **没有 `invalidate`**：`clear` 删除集合。空集合存为没有 key，所以 `put(key, emptySet())` 等同于 `clear(key)`，`putAll` 对每一项遵循同样的规则。
- **`add` 与 `remove` 在 Redis 中原子执行。** 两个实例同时添加不同的成员，两者都会生效。每次写入都从每个实例的 L2 中丢弃这个集合，本实例的下一次读取能看到它。
- **TTL 默认永不过期。** 设置了 TTL 时，每次 `add` 或 `remove` 都会重设到期时间。不存在的集合在 L2 中至多保留 `absent-ttl`（10 秒）。
- **每个元素**在 Redis 中存为一个 hash 字段，所以集合的大小受 Redis 限制，而不受单个 key 的值大小限制（见 [Redis 内存](../operations/index.md#sizing-redis)）。

## Redis 故障时 {#failures}

- 写入抛出 `CacheWriteException`，从不在后台重试。`outcomeUnknown` 说明写入是否可能已经到达 Redis。重试 `add` 或 `remove` 是安全的；重试 `put` 是一次新的替换。
- Redis 读失败是 [`Unknown`](./caches.md#unknown)：`get` 抛出 `CacheReadException`，`lookup` 返回 `Unknown`，`getAll` 抛出带 `unknownKeys` 与 `partialResult` 的异常。集合没有可以退回的安全的“不存在”答案，所以没有 `unknownRead`。
- **读不懂的内容会抛出。** 编解码无法解码的元素使读取抛出异常，更高格式写入的集合抛出 `ForeignFormatException`（一个 `IllegalStateException`）。本版本读不懂的格式的内容（比如同一个 key 上的 Redis 原生 Set）使读取抛出 `CacheReadException`；在它或更高格式写入的集合上调用 `add` 或 `remove` 抛出 `CacheWriteException`，key 不变。只有 `put` 会替换它。`SetStore` 从不回收这样的内容（见[无法读取的载荷](../operations/troubleshooting.md#corrupt-payloads)）。

## 持久性 {#durability}

持久性等于 Redis 的持久性，驱逐检查同样适用，同 [`Store`](./store.md#durability)。端点的按 key `DELETE` 执行 `clear`，它删除集合的唯一副本，需要 `management.endpoint.cocache.allow-store-delete=true`。

## 集合属于数据库时 {#database-sets}

如果集合的权威数据在数据库中，不要用 `SetStore`。声明一个带 loader（读取整个集合）的 `Cache<K, Set<E>>`，更新数据库后 `invalidate` 这个 key：下一次读取重新加载整个集合，Redis 丢了 key 也只是多一次重新加载。

## 视图 {#views}

`async()` 返回 `AsyncSetStore`，`suspending()` 返回 `SuspendingSetStore`，它们的 `get` 同样返回空集合，从不返回 `null`。
