---
title: Store
description: 唯一副本在 Redis 中的值的权威类型：何时用 Store，声明它，不申请 lease 的读取，put 与同步完成的 delete，用 getIfChanged、putIfMatch 与 deleteIfMatch 做条件写入，摘要，默认值，持久性，以及 Redis 故障。
---

# Store

`Store<K, V>` 在 Redis 中保存它的值的**唯一副本**：控制面发布的策略快照、撤销表、会话、一次性验证码、幂等键、只能由离线作业重建的投影。没有 loader。Redis 丢了一个 key，它就没了，所以 `Store` 从不自行删除、从不重试删除，读取失败时明确抛出，而不去猜测。

| | `Store<K, V>` |
|-|---------------|
| 数据来自 | 你的写入 |
| 读取 | `get`、`getAll`、`lookup`、`lookupAll`；`getIfChanged` 连同摘要读取 Redis |
| 写入 | `put`、`putAll`（后写者胜）；`putIfMatch`（比较后写入） |
| 删除 | `delete`、`deleteIfMatch`：同步完成，从不重试 |
| Redis 读失败时 | `Lookup.Unknown`：`get` 抛出 `CacheReadException`，除非设置了 `unknownRead = ABSENT` |
| Redis 写失败时 | 抛出 `CacheWriteException`；从不补偿，也从不重试 |
| 默认寿命 | `ttl` 永不过期，`local-max-age` 30 秒，`absent-ttl` 10 秒 |
| 需要 | 不驱逐其 key 的 Redis（见[驱逐检查](./configuration.md#eviction-check)） |

## 声明 {#declare}

::: code-group

```kotlin [Kotlin]
@CoCache(keyPrefix = "flag:")
interface FeatureFlagStore : Store<String, String>

@EnableCoCache(caches = [FeatureFlagStore::class])
@SpringBootApplication
class App

// 或者声明为 spec bean
@Bean
fun snapshotSpec(mapper: ObjectMapper): StoreSpec<String, PolicySnapshot> =
    storeSpec("policy-snapshot") {
        codec = jacksonCodec<PolicySnapshot>(mapper)
    }
```

```java [Java]
@CoCache(keyPrefix = "flag:")
public interface FeatureFlagStore extends Store<String, String> {}

@Bean
StoreSpec<String, PolicySnapshot> snapshotSpec(ObjectMapper mapper) {
    return StoreSpec.<String, PolicySnapshot>builder("policy-snapshot")
        .codec(JacksonCodec.of(mapper, PolicySnapshot.class))
        .build();
}
```

:::

`Store` 没有数据源：`Store` 接口上的 `@CoCache(loader)` 或 `source = EXTERNAL` 让启动失败。对 `Store` 接口，存在 key 与值类型都与它相同的 `CacheLoader` 或 `AsyncCacheLoader` bean 时，启动时记录告警：loader 能重建的数据属于 [`Cache`](./cache.md)。创建 `Store` 还需要后端的存储支持条件读写；Redis 与内存后端都支持。

## 读取 {#reading}

```kotlin
flags["dark-mode"]                  // Redis 中没有时为 null
flags.lookup("dark-mode")           // Present、Absent 或 Unknown
```

- **读取从不申请 lease。** Redis 中没有的 key，包括其中是别的缓存的加载 lease 的 key，读作 `Lookup.Absent`，这个答案像值一样进入 L2，至多保留 `absent-ttl`（10 秒）。
- **Redis 故障没有答案**：[`Lookup.Unknown`](./caches.md#unknown)。`get` 抛出 `CacheReadException`；`getAll` 先完成其余的 key，再抛出带 `unknownKeys` 与 `partialResult` 的异常。要逐个 key 自行处理，用 `lookupAll`。
- **`unknownRead = UnknownReadMode.ABSENT`** 改为返回 `null`，什么也不缓存，下一次读取重新访问 Redis。只在“不存在”是安全答案的地方声明它：对撤销表而言，它意味着 Redis 宕机期间一律“未撤销”。在 spec 中、用 `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)`、在定制器中，或用 `cocache.caches.[name].unknown-read: absent` 设置；全局层都不设置它。依赖它的库[锁定](./configuration.md#locks) `UNKNOWN_READ`。
- **`read-timeout`** 限定这个 store 的每次 Redis 读取。无法退回数据源的 store 仍可以不为卡住的 Redis 等满 `command-timeout`：超时的读取算作失败，由 `unknownRead` 决定结果。
- **每个值的头部都带写入标识与载荷的 SHA-256。** 读取时重算摘要；载荷与摘要不符、或因为由其它类型写入而没有摘要的值抛出 `CacheReadException`，并原样保留。

## 写入与删除 {#writing}

```kotlin
flags.put("dark-mode", "on")                       // 后写者胜
flags.put("banner", "on", Duration.ofHours(1))     // 显式指定寿命
flags.delete("dark-mode")                          // 同步完成；Redis 失败时抛出
```

- `put` 与 `putAll` 覆盖 Redis、销毁加载 lease 并广播，每个实例因此丢弃自己的 L2 副本。它们不填充本实例的 L2；本地的下一次读取需要一次往返。
- `delete` 在返回之前完成，否则抛出 `CacheWriteException`。它从不进入后台重试队列：对唯一副本延迟执行的删除可能删掉失败之后写入的值。删除必须扛住重试与乱序时，使用 [`VersionedStore`](./versioned-store.md) 与 `deleteIfNewer`。
- 失败的 `put`、`putAll` 或 `delete` 无论结果如何都抛出。`CacheWriteException.outcomeUnknown` 说明它是否可能已经到达 Redis。重试 `put` 是一次新的后写者胜的写入。

## 条件写入 {#conditional-writes}

多个写入方以“读出、修改、写回”的方式更新同一个值时（比如一个包含许多记录的文档），普通的 `put` 会丢失更新：两个写入方读到同一个值，后一个 `put` 覆盖了前一个。`putIfMatch` 只在 Redis 中仍是写入方读到的那个值时写入，`getIfChanged` 从 Redis 读出这个值及其摘要：

```kotlin
fun update(key: String, change: (Document?) -> Document) {
    while (true) {
        val current = store.getIfChanged(key, null)              // 读 Redis，从不读 L2
        val (document, digest) = when (current) {
            is ConditionalGet.Present -> current.value to current.digest
            else -> null to null                                  // Absent：还没有值
        }
        when (store.putIfMatch(key, digest, change(document))) {
            is ConditionalPut.Written -> return
            is ConditionalPut.Conflict -> continue               // 别人先写了：重新读取
        }
    }
}
```

- **摘要**是编解码输出的字节的 SHA-256（`Digest.algorithm` 为 `SHA-256`，`toString()` 输出 `SHA-256:<hex>`）。`Store` 在每次写入时计算它，保存在值的头部。`ConditionalGet.Present` 与 `ConditionalPut.Written` 都会返回它。把相等的值编码成不同字节的编解码（比如无序的 map）会得到不同的摘要。
- **`putIfMatch(key, expected, value)`** 在一个 Redis 脚本中比较并写入。`expected = null` 时只在 Redis 中没有值时写入：key 不存在、处于加载 lease 占位，或是负缓存。否则只在值的摘要等于 `expected` 时写入。冲突返回 `Conflict(current)`（Redis 中没有值时为 `null`），什么也不改：不写入、不改 TTL、不发失效消息。写入成功就是一次 `put`：同样的 TTL 规则（`ttlOf` 或策略，或 `ttl` 参数），同样使每个实例的 L2 失效，本实例的下一次读取能看到它。
- **`deleteIfMatch(key, expected)`** 只在值的摘要等于 `expected` 时删除，返回 `Deleted` 或 `Conflict(current)`（Redis 中没有值时为 `null`）。与 `delete` 一样，它在返回之前完成，从不重试。
- **`getIfChanged(key, known)`** 询问 Redis 这个值的摘要是否仍是 `known`。是则返回 `Unchanged`，不传输值。脚本只读值的头部、比较保存的摘要，所以确认一个值在 Redis 端的成本与它的大小无关，Redis 只回复两个整数。否则返回 `Present(value, digest)` 或 `Absent`。返回值时，CoCache 重算 SHA-256，与保存的摘要不一致时抛出。用它重新确认你在内存中持有的值：保存摘要，定期或收到失效时调用 `getIfChanged`。
- **三者都绕过 L2。** 它们只读写 Redis，从不填充 L2。这个 store 的 L2 可能比 `getIfChanged` 返回的更旧，旧不过 `local-max-age`。
- **失败：** Redis 故障时 `getIfChanged` 抛出 `CacheReadException`，与 `unknownRead` 无关，因为没有可以降级的答案。`putIfMatch` 与 `deleteIfMatch` 抛出 `CacheWriteException`。它们不是幂等的：异常的 `outcomeUnknown` 为 `true`（比如超时）时，先调用 `getIfChanged`；当前摘要等于 `Written` 本该返回的摘要，就说明写入已经生效。`outcomeUnknown = false` 表示写入没有发生。
- **ABA：** 摘要相等只证明值是你读到的那一份，不证明其间什么都没发生。结果未知的写入可能迟到执行，匹配上之后又以相同内容写入的值；迟到的 `deleteIfMatch` 会删掉它。需要严格顺序的写入方使用 [`VersionedStore`](./versioned-store.md)。
- **没有原子广播时**（`sharded` [频道模式](../operations/index.md#channel-mode)，或启动探测退回时），失效消息在脚本之后发布。写入在 Redis 中生效而发布失败时，调用抛出 `CacheWriteException`。重放同一个写入会返回 `Conflict(current)`，其中的摘要就是你自己的值的摘要（`deleteIfMatch` 则是当前没有值的冲突），CoCache 会再发一次失效消息，让其它实例丢弃旧副本。其它冲突不发布任何东西。
- **读不懂的内容**不会被一个方法当作空值、被另一个方法当作值。更高版本的 CoCache 写入的值、集合、没有摘要的值（派生缓存写入的）、带版本的值、墓碑或被淘汰的带版本 key，都会让三者抛出异常（`cause` 为 `ForeignFormatException`），key 原样保留。编解码无法解码的值，或载荷与摘要不符的值，让 `getIfChanged` 抛出；`putIfMatch` 与 `deleteIfMatch` 看不到编解码，所以在无法解码的值上只会返回 `Conflict`，从不改动它。本版本读不懂的格式的内容（比如其它程序写入的值）同样让它们抛出：它永远不算“没有值”，`putIfMatch(key, null, value)` 不会覆盖它。
- **所有写入方都必须用 `putIfMatch`。** 普通的 `put` 不做检查，仍会压过并发的“读—改—写”。

[异步视图](./async.md)与[挂起视图](./coroutines.md)有同样的方法。

## 默认值与寿命 {#defaults}

`Store` 以 `DefaultPolicies.STORE` 为起点：`ttl` 永不过期，`local-max-age` 30 秒，`absent-ttl` 10 秒（见[各类型的默认策略](./caches.md#store-defaults)）。L2 的寿命比 `Cache` 的短，是因为丢失的失效无法靠重新加载纠正：剩下的上界只有 L2 自己的寿命。需要更长时在声明中写出，或用 `cocache.defaults.store.*` 为所有权威缓存设置。

寿命即语义的数据（会话、一次性验证码、幂等键）需要在声明中显式写出 `ttl`，或按调用用 `put(key, value, ttl)` 或 `ttlOf` 指定。只有声明与 `cocache.caches.[name].ttl` 能设置 `Store` 的 `ttl`；`cocache.defaults` 与 `cocache.overrides` 从不设置它，一行全局配置因此不会让所有 store 过期（见[各层能设置什么](./configuration.md#field-layers)）。

## 持久性 {#durability}

- **持久性等于 Redis 的持久性。** 在保存 store 的 Redis 上开启持久化（AOF 或 RDB）。异步复制下的故障切换可能丢失最近的写入（见[持久化与故障切换](../operations/index.md#failover)）。
- **Redis 不能驱逐这些 key。** 声明了 `Store` 时，启动时读取每个主节点的 `maxmemory-policy`：`allkeys-*` 一律让启动失败；读不到策略时同样启动失败，除非 `cocache.redis.assume-maxmemory-policy` 指明了它。在 `volatile-*` 下，`ttl` 有限的 store 可能在到期之前被驱逐，如何处理由 `cocache.redis.store-eviction-check` 决定（见[驱逐检查](./configuration.md#eviction-check)）。
- **读不懂的内容**（本版本读不懂的格式的内容、其它程序写入的值、更高版本的 CoCache 写入的值）抛出 `CacheReadException`，从不删除，因为删除它就是删除唯一副本。

## 运维 {#operations}

- 端点的按 key `DELETE` 会删除唯一副本，因此需要 `management.endpoint.cocache.allow-store-delete=true`；没有开启时请求返回 `403`，不做任何修改（见[失效一个 key](../operations/observability.md#invalidate-key)）。
- 在 store 的 namespace 上调用 `ExternalInvalidator.invalidate` 同样会删除唯一副本。Redis 中已是正确的值、只需要让其它实例丢弃陈旧的 L2 副本时，用 `ExternalInvalidator.broadcast`，它不修改 Redis（见[应用之外的写入方](./freshness.md#external-writers)）。
- 同一个 namespace 的所有声明必须是同一类型。`Cache` 或 `VersionedCache` 读到 `Store` 写入的值时计入 `cocache.type.mismatch`，并记录一次日志。

## 视图 {#views}

`async()` 返回 `AsyncStore`，`suspending()` 返回 `SuspendingStore`。`Store` 不能放在 Spring Cache 或 `TransactionalInvalidator` 后面：它没有 `invalidate`，指名它的缓存操作会让启动失败。
