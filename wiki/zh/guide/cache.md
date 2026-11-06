---
title: Cache
description: 派生缓存类型：何时用 Cache，用 loader 或以外部来源声明它，同步、异步、挂起与调用级 loader，读取，invalidate 与 put，默认值，以及 Redis 故障时会发生什么。
---

# Cache

`Cache<K, V>` 保存**派生副本**：本进程能从数据源重新取得的数据，比如数据库中的一行，或远程 API 的一次响应。Redis 丢了一个 key，代价是一次重新加载，从不丢失数据。除非数据没有数据源，否则就用这个类型（见[选择类型](./caches.md#types)）。

| | `Cache<K, V>` |
|-|---------------|
| 数据来自 | `loader` 或 `asyncLoader`，或调用方（`externallySourced()`） |
| 读取 | `get`、`getAll`、`lookup`、`lookupAll`、`get(key, loader)` |
| 写入 | `invalidate`、`invalidateAll`（首选）；`put`、`putAll`（后写者胜） |
| Redis 读失败时 | 从数据源加载（`read-failure: degrade`） |
| Redis 写失败时 | `invalidate` 在后台重试；结果未知的 `put` 会被补偿 |
| 默认寿命 | `ttl` 1 小时 ± 1 分钟，`local-max-age` 5 分钟，`absent-ttl` 1 分钟 |
| Spring Cache、`TransactionalInvalidator` | 可以，它们只接受这个类型 |

## 声明 {#declare}

作为接口声明，loader bean 按泛型类型匹配：

::: code-group

```kotlin [Kotlin]
@CoCache(ttl = 600)
interface UserCache : Cache<String, User>

@Bean
fun userLoader(repo: UserRepository): CacheLoader<String, User> =
    CacheLoader { id -> repo.findById(id) }      // null 表示不存在

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App
```

```java [Java]
@CoCache(ttl = 600)
public interface UserCache extends Cache<String, User> {}

@Bean
CacheLoader<String, User> userLoader(UserRepository repo) {
    return id -> repo.findById(id).orElse(null);  // null 表示不存在
}
```

:::

或者声明为 spec bean，在代码中设置数据源与编解码：

::: code-group

```kotlin [Kotlin]
@Bean
fun productSpec(mapper: ObjectMapper, repo: ProductRepository): CacheSpec<String, Product> =
    cacheSpec("product") {
        codec = jacksonCodec<Product>(mapper)
        loader = CacheLoader { id -> repo.findById(id) }
        policy { ttl = Duration.ofMinutes(10) }
    }
```

```java [Java]
@Bean
CacheSpec<String, Product> productSpec(ObjectMapper mapper, ProductRepository repo) {
    return CacheSpec.<String, Product>builder("product")
        .codec(JacksonCodec.of(mapper, Product.class))
        .loader(id -> repo.findById(id).orElse(null))
        .updatePolicy(policy -> policy.ttl(Duration.ofMinutes(10)))
        .build();
}
```

:::

`Cache` 必须说明数据从哪里来：`CacheSpec` 恰好设置 `loader`、`asyncLoader` 与 `externallySourced()` 之一，接口要么找到自己的 loader bean，要么声明 `@CoCache(source = CoCache.Source.EXTERNAL)`。其它情况都会启动失败。各类型共用的注解、spec bean、key 与编解码见[声明缓存](./caches.md)。

## loader {#loaders}

`CacheLoader<K, V>` 负责加载缓存中没有的数据。它只管加载，值活多久由缓存决定。

```kotlin
class UserLoader(private val repo: UserRepository) : CacheLoader<String, User> {
    override fun load(key: String): User? = repo.findById(key)      // null 表示不存在

    // 可选：getAll 对两级都未命中的 key 只调用一次。
    // 结果中缺少的 key 视为“不存在”。
    override fun loadAll(keys: Collection<String>): Map<String, User> =
        repo.findAllById(keys).associateBy { it.id }
}
```

- **`null` 表示“不存在”**，写入负缓存，存活 `absent-ttl`。查询*失败*时请抛出异常：异常到达调用方（以及共享这次加载的所有调用方），不缓存任何东西。
- **默认的 `loadAll`** 逐个调用 `load`。如果用到 `getAll`，请用一次批量查询覆盖它。
- **loader 在调用方线程上执行**，事务、`SecurityContext` 与 MDC 都可用。经[异步视图](./async.md)调用时改在运行时的执行器（`cocache.executor`）上执行。
- **不要在 loader 内同线程读取同一个 key**，那会以 `IllegalStateException` 快速失败。
- **接口式声明**先按 `CacheLoader<K, V>`、再按 `AsyncCacheLoader<K, V>` 类型查找 loader。匹配到多个 bean，或两种各一个时启动失败，用 `@CoCache(loader = "…")` 指定一个。父容器中的 bean 与作用域代理的匹配方式见[按类型匹配 bean](./caches.md#bean-matching)。
- **非阻塞的数据源**实现 `AsyncCacheLoader<K, V>`（`loadAsync` 返回 `CompletionStage`），设为 `asyncLoader`。在 Kotlin 中，`suspendingLoader { … }` 把一个挂起函数设为异步 loader。一个缓存只有一个数据源，同步或异步，每种视图都能配合任意一种：见[异步 API](./async.md#loader) 与 [Kotlin 协程](./coroutines.md#loader)。

### 调用级 loader {#per-call-loader}

`get(key) { k -> … }` 用你传入的 loader 代替声明的 loader 加载未命中的值，保护方式不变：每个实例上每个 key 只加载一次，跨实例由 Redis lease 保护，还有失效栅栏。Spring 的 `@Cacheable(sync = true)` 用的就是它，以带注解的方法作为 loader。

```kotlin
val user = users.get(id) { key -> userClient.fetch(key) }
```

## 外部来源的缓存 {#externally-sourced}

`externallySourced()`（接口上写 `@CoCache(source = EXTERNAL)`）声明一个值来自调用方的 `Cache`：`put`、[Spring Cache](./spring-cache.md) 方法或调用级 loader。它仍是派生副本，因此得到派生副本的默认值：1 小时的 `ttl`，`invalidate` 失败时在后台重试。

```kotlin
@CoCache(source = CoCache.Source.EXTERNAL)
interface UserCache : Cache<String, User>          // 由 @Cacheable(sync = true) 方法填充
```

- **未命中是“未填充”。** Redis 中没有这个 key 时，不带 loader 的读取返回 `null`（`Lookup.Absent`）。这个答案进入 L2，与任何填充一样受栅栏保护，限龄 `absent-ttl` 与 `local-max-age`；但自带 loader 的调用不接受它：`get(key) { … }` 照常加载。Redis 中什么也不写。
- **Redis 读失败时，不带 loader 的读取没有答案**：得到 [`Lookup.Unknown`](./caches.md#unknown)，`get` 抛出 `CacheReadException`。“不存在”是安全答案时，声明 `unknownRead = UnknownReadMode.ABSENT`（`@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)`）。带调用级 loader 的读取由 `read-failure` 决定，默认从那个 loader 加载。
- **读不懂的内容**使不带 loader 的读取抛出 `CacheReadException`；带 loader 的读取回收它并重新加载。更高版本的 CoCache 写入的内容读作未填充。

外部来源的 `Cache` 只用于*能*由调用方重建的数据。唯一副本在 Redis 中的数据属于 [`Store`](./store.md)：声明成 `Cache` 的话，它会在一小时后过期，被重试的 `invalidate` 还可能删掉其间写入的值。

## 读取 {#reading}

```kotlin
users["42"]                         // L2 → Redis → loader；loader 什么也没找到时为 null
users.getAll(listOf("1", "2"))      // 只含存在的 key
users.lookup("42")                  // Present、Absent 或 Unknown
```

- 返回 `null` 的 loader 写入**负缓存**：在 `absent-ttl` 内读取这个 key 都返回 `null`，不调用 loader。新建这一行时请失效这个 key（见[新建的记录被报告为不存在](../operations/troubleshooting.md#negative-entry)）。
- Redis 故障时，带数据源的读取从数据源加载，结果在 L2 中保留 `degraded-local-max-age`（10 秒），不写 Redis。`read-failure: throw` 让这样的读取改为得到 [`Unknown`](./caches.md#unknown)。
- [提前刷新](./freshness.md#refresh-ahead)在热点 key 过期之前于后台重新加载它，全集群只加载一次。它需要 loader。

## 写入 {#writing}

**先修改数据源，再失效**（见[规则](./freshness.md#rule)）：

```kotlin
repo.rename(id, name)          // 已提交
users.invalidate(id)           // 每个实例丢弃该 key；下一次读取加载新的行
```

- `invalidate` 与 `invalidateAll` 删除 Redis 中的 key、销毁加载 lease 并广播，开始于变更之前的加载因此无法把旧结果写回。在 Spring 事务中，用 [`TransactionalInvalidator`](./freshness.md#transactions) 让它在提交之后执行。
- `put(key, value)` 与 `putAll(entries)` 覆盖 Redis 并广播同样的失效，但不填充本实例的 L2。`put(key, value, ttl)` 指定寿命。优先用 `invalidate`：它不可能缓存一个与数据源不一致的值（见 [`invalidate` 还是 `put`？](./freshness.md#invalidate-vs-put)）。
- 无论哪种调用，返回之后同一实例上的下一次读取都能看到变化。其它实例在失效消息到达后看到。

## Redis 故障时 {#failures}

| 操作 | 默认 | 可选 |
|------|------|------|
| 带数据源的读取 | 从数据源加载，结果在 L2 中保留 `degraded-local-max-age` | `read-failure: throw`：`Lookup.Unknown`，`get` 抛出 `CacheReadException` |
| 不带 loader 的读取（外部来源） | `Lookup.Unknown`：`get` 抛出 `CacheReadException` | `unknownRead = ABSENT`：返回 `null`，不缓存任何东西 |
| `invalidate`、`invalidateAll` | 不抛出：删除在后台重试（最多 8 次），期间本实例上的未命中跳过 Redis | `invalidate-failure: throw`：`CacheWriteException` |
| `put`、`putAll`，结果未知（超时） | 不抛出：CoCache 为这些 key 登记失效，它们收敛到“从数据源重新加载” | `invalidate-failure: throw`：`CacheWriteException` |
| `put`、`putAll`，确定未执行（断线期间被拒绝） | 抛出 `CacheWriteException`，`outcomeUnknown = false` | — |

删除派生副本在任何时刻都安全，所以 `Cache` 重试而不是抛出：失效通常在提交之后执行，那时抛出异常什么也回滚不了。细节见[写入时 Redis 故障](./freshness.md#redis-down)。

## 视图 {#views}

`users.async()` 是 `AsyncCache<K, V>`，`users.suspending()` 是 `SuspendingCache<K, V>`，方法相同，分别返回 `CompletableFuture` 或挂起。它们就是同一个缓存：同一个 L2、同一组栅栏、共享的在途加载。见[异步 API](./async.md) 与 [Kotlin 协程](./coroutines.md)。

## 延伸阅读 {#related}

- [保持数据新鲜](./freshness.md)：写入规则、事务、应用之外的写入方、寿命。
- [Spring Cache](./spring-cache.md)：以外部来源的 `Cache` 支撑 `@Cacheable(sync = true)`。
- [JoinCache](./join-cache.md)：把两个缓存当作一个读取。
- [`VersionedCache`](./versioned-cache.md)：写入方按版本对写入排序的派生副本。
