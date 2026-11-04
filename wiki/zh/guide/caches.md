---
title: 声明缓存
description: 在五种缓存类型（Cache、VersionedCache、Store、VersionedStore、SetStore）之间选择，用带注解的接口或 spec bean 声明它们，并控制缓存名、存储 key、key 编码、loader、编解码、定制器与启动校验。
---

# 声明缓存

一个缓存由这些要素组成：名字、key 类型、值类型、说明 Redis 为它保存了什么的类型、为 Redis 编码值的方式，以及一套策略。本页先讲类型，再讲两种声明方式。

## 选择类型 {#types}

对数据只问一个问题：**Redis 丢了这个 key，本进程能否按需重建它？**

- **能**：Redis 保存的是*派生副本*。声明 `Cache`；写入方按版本有序写入时声明 `VersionedCache`。
- **不能**：Redis 保存的是*唯一副本*。声明 `Store`、`VersionedStore`，元素集合则声明 `SetStore`。

| 类型 | Redis 中是 | 数据来自 | 写入 | 删除 |
|------|-----------|----------|------|------|
| `Cache<K, V>` | 派生副本 | `loader` / `asyncLoader`，或调用方（`externallySourced()`） | `put`、`putAll`（后写者胜） | `invalidate`，Redis 失败时在后台重试 |
| `VersionedCache<K, V>` | 派生的带版本副本 | 带版本的 loader | `putIfNewer`、`invalidateIfNewer` | `evict` 保留版本栅栏；`purge` 丢掉它 |
| `Store<K, V>` | 唯一副本 | 你的写入 | `put`、`putAll`、`putIfMatch` | `delete`、`deleteIfMatch`：同步完成，从不重试 |
| `VersionedStore<K, V>` | 唯一的带版本副本 | 你的按版本写入 | `putIfNewer` | `deleteIfNewer`（墓碑）；`purge` |
| `SetStore<K, E>` | 集合的唯一副本 | 你的元素写入 | `add`、`remove`、`put`、`putAll` | `clear` |

所有类型的读取方式相同（`get`、`getAll`、`lookup`、`lookupAll`），都有方法相同的[异步视图](./async.md)与[挂起视图](./coroutines.md)。每个类型只声明它支持的操作：`Store` 没有 `invalidate`，`VersionedCache` 没有 `put`，调用错了是编译错误，而不是运行时的意外。

| 你的数据 | 类型 |
|----------|------|
| 数据库中的行、远程 API、loader 能重新取得的任何数据 | 带 `loader` 的 `Cache` |
| Spring Cache（`@Cacheable` 方法），或只经调用级 loader 读取的缓存 | `externallySourced()` 的 `Cache` |
| loader 能连同版本一起重建的事件溯源读模型、CDC 投影 | `VersionedCache` |
| 策略快照、撤销表、会话、一次性验证码、幂等键 | `Store` |
| 只能离线重建的投影、由另一个服务维护的数据 | `Store` |
| 没有数据源、写入方又必须扛住迟到、重放或重复写入的状态 | `VersionedStore` |
| 权威数据就在 Redis 中的索引或成员列表 | `SetStore` |

write-behind（先写缓存、再异步落库）不适合任何类型：落库之前 Redis 是唯一副本，落库之后它又是派生副本。

同一个 namespace 的所有声明必须是同一类型。类型不保存在 Redis 中，跨应用的不一致 CoCache 只能部分发现：派生缓存读到 `Store` 写入的值时计入 `cocache.type.mismatch` 并告警。启动日志写出每个缓存的类型（`Cache[name] - created as Store on namespace[…]`），便于跨应用核对声明。

**分错类型的后果**：

| 错误 | 后果 | 怎么发现 |
|------|------|----------|
| 唯一副本声明成 `externallySourced()` 的 `Cache` | 默认 `ttl`（1 小时）之后过期；失败的 `invalidate` 稍后重试，可能删掉其间写入的值；Redis 驱逐检查不覆盖它 | 启动日志写出每个缓存的类型 |
| 可重建的副本声明成 `Store` | Redis 丢了 key 之后读成“不存在”，直到有人重新写入；`delete` 失败抛出、不重试 | 存在匹配的 `CacheLoader` bean 时启动告警 |
| `Cache` 的 loader 没有匹配到 | 启动失败：`Cache` 必须有 loader，或声明 `externallySourced()` | 异常信息写出接口名 |

## 接口式缓存 {#interface}

```kotlin
@CoCache(ttl = 600, localMaxAge = 60)
@CaffeineCache(maximumSize = 100_000)
interface UserCache : Cache<String, User>

@CoCache
interface PolicySnapshots : Store<String, PolicySnapshot>

@EnableCoCache(caches = [UserCache::class, PolicySnapshots::class])
@SpringBootApplication
class App
```

接口必须恰好继承 `Cache<K, V>`、`VersionedCache<K, V>`、`Store<K, V>`、`VersionedStore<K, V>`、`SetStore<K, E>` 之一，并带具体的类型参数；继承哪一个决定创建哪种缓存。`@CoCache` 本身可以省略：列在 `@EnableCoCache` 中却没有标注它的接口使用全部默认值。注解只从接口本身读取，不读元注解，也不读父接口上的注解。

用户 API 的类型不供你的类实现，委托给 CoCache 创建的缓存除外：8.x 以新的能力类型或默认方法新增操作，手写的实现可能没有预料到它们。像上面这样继承它们的接口没有问题，由代理实现。

### `@CoCache` {#cocache-annotation}

时长单位为**秒**。所有策略属性默认都是 `CoCache.UNSET`（`-1`），意思是“这里不设置，交给下一层”。默认值取决于类型：派生类型（`Cache`、`VersionedCache`）以 `CachePolicy.DEFAULT` 为起点，权威类型（`Store`、`VersionedStore`、`SetStore`）以各自的[默认策略](#store-defaults)为起点。

| 属性 | 默认值 | 含义 |
|------|--------|------|
| `name` | 接口简单类名 | 缓存名，也是 bean 名 |
| `namespace` | 缓存名 | 每个存储 key 的中间部分，也决定失效频道。只能含字母、数字、`.`、`_` 与 `-`（见[存储 key](#storage-keys)） |
| `keyPrefix` | `cocache:` | 每个存储 key 的开头 |
| `keyExpression` | `toString()` | 把 key 转为字符串的 SpEL 模板（见 [key](#keys)） |
| `ttl` | `3600`；权威类型永不过期 | 值在 Redis 中的寿命。`CoCache.FOREVER`：永不过期。 |
| `ttlJitter` | `60` | 每个值的寿命上加的随机 ± 抖动，不超过 `ttl / 2`。`0`：关闭。 |
| `absentTtl` | `60`；权威类型为 `10` | 负缓存的寿命（派生类型），或 L2 中“不存在”的寿命（权威类型），不加抖动 |
| `localMaxAge` | `300`；权威类型为 `30` | L2 副本的最长寿命：失效消息丢失时的陈旧度上界 |
| `refreshWindow` | 关闭 | 值剩余寿命不足该时长时在后台刷新。`0`：关闭。只作用于有数据源的派生缓存。 |
| `loader` | `""` | 数据源的 bean 名（只用于 `Cache` 与 `VersionedCache`）。为空时取唯一匹配的 bean（见 [loader](#loaders)）。 |
| `source` | `LOADER` | 只用于 `Cache`。`CoCache.Source.EXTERNAL` 即 `externallySourced()`：值由调用方提供。不能与 `loader` 同时设置。 |
| `codec` | `""` | `Codec` 的 bean 名。为空时取唯一的 `Codec<V>` 类型 bean，没有则用 Jackson。 |
| `unknownRead` | `THROW` | 不带 loader 的读取遇到 Redis 故障（[`Unknown`](#unknown)）时返回什么：`CoCache.UnknownRead.ABSENT` 返回 `null`。只用于 `Store`、`VersionedStore` 与 `source = EXTERNAL` 的 `Cache`。 |

`CoCache.FOREVER` 只能用于 `ttl`（以及 `@CaffeineCache.expireAfterAccess`，表示关闭），用在其它时长属性上启动失败。`@CaffeineCache.maximumSize` 是条目数而不是时长，在它上面写 `FOREVER` 就是 `Long.MAX_VALUE` 个条目。只有 `ttlJitter` 与 `refreshWindow` 可以为 0；其它时长为 0，或任何时长为 `UNSET` 以外的负数，都会启动失败。

### `@CaffeineCache` {#caffeine-annotation}

设置 L2 的容量。L2 固定是 Caffeine，并且总是有界的。

| 属性 | 默认值 | 含义 |
|------|--------|------|
| `maximumSize` | `10000` | 每个实例的最大条目数 |
| `initialCapacity` | Caffeine 的默认值 | 初始表大小 |
| `expireAfterAccess` | 关闭 | 空闲超过这么多秒的条目被淘汰。`CoCache.FOREVER`：关闭。 |

正确性不需要 `expireAfterAccess`：条目本来就会在 `localMaxAge` 与 Redis 到期时过期。它能更早释放内存，但每次读取都要记录访问时间，在极热的 key 上会损失吞吐。

### 添加方法 {#interface-methods}

default 方法按原样执行，可以添加辅助方法：

```kotlin
interface UserCache : Cache<String, User> {
    fun nameOf(id: String): String? = get(id)?.name
}
```

抽象方法必须在名称与参数上对应它所继承的类型的某个方法，否则启动失败。缓存抛出的异常原样到达调用方，不会被包装。

## spec bean {#spec-bean}

spec bean 用代码声明缓存。每种类型有一种 spec，starter 为每个 spec 注册一个对应类型的 bean：

| spec | Kotlin DSL | Java 构建器 | bean 类型 |
|------|------------|-------------|-----------|
| `CacheSpec<K, V>` | `cacheSpec` | `CacheSpec.builder` | `Cache<K, V>` |
| `VersionedCacheSpec<K, V>` | `versionedCacheSpec` | `VersionedCacheSpec.builder` | `VersionedCache<K, V>` |
| `StoreSpec<K, V>` | `storeSpec` | `StoreSpec.builder` | `Store<K, V>` |
| `VersionedStoreSpec<K, V>` | `versionedStoreSpec` | `VersionedStoreSpec.builder` | `VersionedStore<K, V>` |
| `SetStoreSpec<K, E>` | `setStoreSpec` | `SetStoreSpec.builder` | `SetStore<K, E>` |

::: code-group

```kotlin [Kotlin]
@Bean
fun productSpec(mapper: ObjectMapper, repo: ProductRepository): CacheSpec<String, Product> =
    cacheSpec("product") {
        codec = jacksonCodec<Product>(mapper)
        loader = CacheLoader { id -> repo.findById(id) }
        policy {
            ttl = Duration.ofMinutes(10)
            local = LocalPolicy(maximumSize = 100_000)
        }
    }

@Bean
fun snapshotSpec(mapper: ObjectMapper): StoreSpec<String, PolicySnapshot> =
    storeSpec("policy-snapshot") {
        codec = jacksonCodec<PolicySnapshot>(mapper)
    }
```

```java [Java]
@Bean
CacheSpec<String, Product> productSpec(ObjectMapper mapper, ProductRepository repo) {
    return CacheSpec.<String, Product>builder("product")
        .codec(JacksonCodec.of(mapper, Product.class))
        .loader(id -> repo.findById(id).orElse(null))
        .updatePolicy(policy -> policy
            .ttl(Duration.ofMinutes(10))
            .local(new LocalPolicy(100_000)))
        .build();
}

@Bean
StoreSpec<String, PolicySnapshot> snapshotSpec(ObjectMapper mapper) {
    return StoreSpec.<String, PolicySnapshot>builder("policy-snapshot")
        .codec(JacksonCodec.of(mapper, PolicySnapshot.class))
        .build();
}
```

:::

```kotlin
class ProductService(@Qualifier("product") private val products: Cache<String, Product>)
```

- **返回类型必须写出泛型**（`CacheSpec<String, Product>`）。starter 从中读取 `K` 与 `V`，而不创建 spec。返回原始类型时启动失败。
- **bean 名**：spec bean 名去掉 `Spec` 后缀（`productSpec` → `product`）；没有该后缀时追加 `Cache`（`products` → `productsCache`）。*缓存名*则是传给 `cacheSpec(…)` 的那个名字。
- **spec 必须设置编解码。** 代码中没有 Jackson 兜底；这里的 `ObjectMapper` 是 Jackson 3（`tools.jackson.databind`）。`SetStoreSpec` 设置的是 `elementCodec`。
- **`CacheSpec` 必须恰好声明一个数据来源**：`loader`、`asyncLoader` 或 `externallySourced()`。`VersionedCacheSpec` 必须设置 `versionedLoader` 或 `versionedAsyncLoader`。权威类型的 spec 根本没有 loader。
- Kotlin 中 `policy { … }` 在当前策略上修改；Java 中用 `updatePolicy(…)`。`policy(CachePolicy)` 则整体替换。权威类型的 spec 以自己的默认策略为起点，请在它上面修改，不要换成 `CachePolicy.DEFAULT`（那会给 `Store` 一个 1 小时的 `ttl`）。

不用 Spring 时，`CacheRuntime.create(spec)` 返回对应的类型：`create(storeSpec)` 返回 `Store`，依此类推；`create(spec: Spec<*, *>)` 按 spec 的类型分派。创建缓存时检查后端的存储是否支持这种类型需要的能力，不支持时在注册任何东西之前失败：

| 类型 | 需要 |
|------|------|
| `Cache` | 只需基本的存储 |
| `Store` | 条件读写（`getIfChanged`、`putIfMatch`、`deleteIfMatch`） |
| `VersionedCache`、`VersionedStore` | 按版本写入 |
| `SetStore` | 集合成员 |

Redis 与内存后端全部支持。

## 缓存名与存储 key {#storage-keys}

Redis 中的每个 key 都是：

```
{keyPrefix}{namespace}:{encodedKey}
 cocache:   UserCache :42
```

| 部分 | 默认值 | 设置方式 |
|------|--------|----------|
| `keyPrefix` | `cocache:` | `@CoCache(keyPrefix)`、spec 的 `keyPrefix`，或 [`key-prefix` 配置项](./configuration.md#key-prefix) |
| `namespace` | 缓存名 | `@CoCache(namespace)`、spec 的 `namespace` |
| `encodedKey` | `key.toString()` | `@CoCache(keyExpression)`、spec 的 `keyEncoder` |

- CoCache 的内部 key 放在 `{keyPrefix}!` 之下，任何 namespace 都产生不了这个位置：`!` 不在 namespace 的字符集中。
- namespace 只能含字母、数字、`.`、`_` 与 `-`（`[A-Za-z0-9._-]+`）。含 `:` 的 namespace 在创建时失败，信息给出把 `:` 之前的部分移到 `keyPrefix` 的写法：`namespace = "app:user"` 改为 `keyPrefix = "cocache:app:"` 与 `namespace = "user"`。没有 `:`，一个 namespace 的 key 就不会落进另一个 namespace 的范围。
- 在一个应用内，缓存名必须唯一，前缀与 namespace 的组合也必须唯一。namespace 相同、前缀不同的两个缓存可以共存。重复时启动失败。
- 失效消息走频道 `{keyPrefix}inv:{namespace}`，前缀不同的应用收不到彼此的消息。
- actuator 端点与 `ExternalInvalidator` 接受的都是**编码后的 key**，所以其它工具不必知道 key 的 Kotlin 类型也能定位它。
- 多个应用共用一个 Redis 时，用 [`cocache.defaults.key-prefix` 配置项](./configuration.md#key-prefix)给每个应用一个不同的前缀（比如 `${spring.application.name}:`）。`@CoCache(keyPrefix)` 是字面文本：其中的占位符不会被解析（在 Kotlin 中 `$` 还会开启字符串模板）。修改运行中派生缓存的前缀或 namespace 相当于冷启动：旧 key 不再被读取，随各自的寿命过期。权威类型则会丢失数据，因为旧 key 中是唯一副本。

## key {#keys}

默认情况下 key 转为它的 `toString()`。这对 `String`、数字、`UUID`、data class 与 record 都适用。

每种 key 类型第一次编码时，CoCache 检查它是否重写了 `toString()`。没有重写时（继承的 `Object.toString()` 返回 `类名@哈希`，每个实例都不同），调用以 `IllegalArgumentException` 失败，因为这样的缓存永远不会命中。请使用 data class 或 record、重写 `toString()`，或提供编码器。

**接口式声明**用 `keyExpression`，它是以 key 为根对象的 SpEL 模板：

```kotlin
data class TenantKey(val tenantId: String, val id: String)

@CoCache(keyExpression = "#{tenantId}:#{id}")
interface TenantUserCache : Cache<TenantKey, User>
```

表达式在**每次**读取时求值，包括 L2 命中，因为 L2 正是按编码后的 key 查找的。热点路径上优先使用 `toString()` 合适的 key 类型。不含任何 `#{…}` 的模板是常量，会把所有 key 映射到同一个条目，因此启动时被拒绝。

**spec 声明**设置 `KeyEncoder`，它只是一个普通函数：

```kotlin
cacheSpec<TenantKey, User>("TenantUserCache") {
    keyEncoder = KeyEncoder { "${it.tenantId}:${it.id}" }
    // …
}
```

编码结果相同的两个 key 就是同一个缓存条目，编码必须是单射的。

## loader {#loaders}

`Cache` 要声明数据从哪里来。`CacheLoader<K, V>` 负责加载缓存中没有的数据。它只管加载，值活多久由缓存决定。

```kotlin
class UserLoader(private val repo: UserRepository) : CacheLoader<String, User> {
    override fun load(key: String): User? = repo.findById(key)      // null 表示不存在

    // 可选：getAll 对两级都未命中的 key 只调用一次。
    // 结果中缺少的 key 视为“不存在”。
    override fun loadAll(keys: Collection<String>): Map<String, User> =
        repo.findAllById(keys).associateBy { it.id }
}
```

- **`null` 表示“不存在”**，写入负缓存，存活 `absentTtl`。查询*失败*时请抛出异常：异常到达调用方（以及共享这次加载的所有调用方），不缓存任何东西。
- **默认的 `loadAll`** 逐个调用 `load`。如果用到 `getAll`，请用一次批量查询覆盖它。
- **loader 在调用方线程上执行**，事务、`SecurityContext` 与 MDC 都可用。经[异步视图](./async.md)调用时改在阻塞执行器上执行。
- **不要在 loader 内同线程读取同一个 key**，那会以 `IllegalStateException` 快速失败。
- **接口式声明**先按 `CacheLoader<K, V>`、再按 `AsyncCacheLoader<K, V>` 类型查找 loader。匹配到多个 bean，或两种各一个时启动失败，用 `@CoCache(loader = "…")` 指定一个。一个都没有时同样启动失败：值由调用方提供时声明 `@CoCache(source = CoCache.Source.EXTERNAL)`，什么都无法重建它们时改为继承 `Store`。
- **按调用提供 loader**：`cache.get(key) { k -> … }` 用给定的 loader 加载未命中的值，保护方式不变。Spring 的 `@Cacheable(sync = true)` 用的就是它。
- **非阻塞的数据源**：实现 `AsyncCacheLoader<K, V>`（`loadAsync` 返回 `CompletionStage`），在 spec 中设为 `asyncLoader`。一个缓存只有一个数据源，同步或异步；两种 API 都能配合任意一种。见[异步 API](./async.md#loader)。

### 外部来源的缓存 {#externally-sourced}

`externallySourced()`（接口上写 `@CoCache(source = EXTERNAL)`）声明一个值来自调用方的 `Cache`：`put`、Spring Cache 或调用级 loader。它仍是派生副本，因此得到派生副本的默认值：1 小时的 `ttl`，`invalidate` 失败时在后台重试。

- **未命中是“未填充”。** Redis 中没有这个 key 时，不带 loader 的读取返回 `null`（`Lookup.Absent`）。这个答案进入 L2，与任何填充一样受栅栏保护，限龄 `absentTtl` 与 `localMaxAge`；但自带 loader 的调用不接受它：`get(key) { … }` 照常加载。Redis 中什么也不写。
- **Redis 读失败没有答案。** 不带 loader 的读取得到 [`Lookup.Unknown`](#unknown)：`get` 抛出 `CacheReadException`。“不存在”是安全答案时，在 spec 上声明 `unknownRead = UnknownReadMode.ABSENT`。带调用级 loader 的读取由 `readFailure` 决定，默认从那个 loader 加载。
- **读不懂的内容**（本版本无法解码的内容）使不带 loader 的读取抛出 `CacheReadException`；带 loader 的读取回收它并重新加载。更高版本的 CoCache 写入的内容读作未填充。

### 带版本的 loader {#versioned-loaders}

`VersionedCache` 连同版本一起加载值。它的数据源返回 `Versioned(value, version)`：

```kotlin
versionedCacheSpec<String, OrderView>("order-view") {
    codec = jacksonCodec<OrderView>(mapper)
    versionedLoader = CacheLoader { id ->
        val state = repository.find(id) ?: return@CacheLoader null        // 从来没有
        Versioned(if (state.deleted) null else state.view, state.version) // 已删除：Versioned(null, version)
    }
}
```

**删除要写成 `Versioned(null, version)`。** 它写入一个挡住更旧版本的墓碑。单纯的 `null` 表示“从来没有”，写入不带版本的负缓存。如果 Redis 中这个 key 已经是带版本的内容（比如 [`evict`](./freshness.md#versioned-writes) 之后），这个不带版本的 `null` 会被拒绝：读取返回“不存在”，但 Redis 中什么也不写，于是在版本栅栏到期之前每次未命中都重新加载。每次拒绝计入 `cocache.fill.unversioned.absent`，每个缓存第一次时告警。接口式声明按 `CacheLoader<K, Versioned<V>>` 或 `AsyncCacheLoader<K, Versioned<V>>` 类型查找数据源。没有数据源的 `VersionedCache` 启动失败；没有带版本数据源的数据属于 `VersionedStore`。

### 按条目决定寿命 {#ttl-of}

寿命取决于值时，设置 `ttlOf`（在 spec 或定制器中）：

```kotlin
cacheSpec<String, Session>("SessionCache") {
    codec = jacksonCodec<Session>(mapper)
    loader = CacheLoader { id -> sessions.find(id) }
    ttlOf = TtlFunction { _, session -> Duration.between(Instant.now(), session.expiresAt) }
}
```

返回非 `null` 时覆盖策略 `ttl`，且不加抖动；返回 `null` 时沿用策略。负缓存总是使用 `absentTtl`，墓碑使用 `tombstoneTtl`。`ttl` 配置项不影响 `ttlOf`。`StoreSpec`、`VersionedCacheSpec` 与 `VersionedStoreSpec` 同样接受 `ttlOf`。

### 拒绝不可能存在的 key {#key-filter}

`KeyFilter`（在 spec 或定制器中）在 L2 未命中之后检查。被它拒绝的 key 直接返回“不存在”，不访问 Redis 也不调用 loader，适合放在布隆过滤器前面：

```kotlin
keyFilter = KeyFilter { storageKey -> !bloom.mightContain(storageKey) }
```

它接收完整的存储 key，只能拒绝确定不存在的 key。

## 读取：存在、不存在与未知 {#unknown}

每种类型都经 L2 与 Redis 读取。`lookup(key)` 区分三种答案：

| `Lookup` | 含义 | `get` 返回 |
|----------|------|------------|
| `Present(value, expiresAt)` | 有值 | 值 |
| `Absent(expiresAt)` | 确认不存在：负缓存、Redis 中没有的 `Store` key、未填充的 key | `null` |
| `Unknown(error)` | 这一次读取没有答案：Redis 失败或超时 | 抛出 `error`（`CacheReadException`） |

`Unknown` 是关于这一次读取的事实，不是关于数据的：它从不被缓存、从不广播，也不交给自己有数据源的调用方。`lookup(key).valueOrThrow()` 返回值或 `null`，遇到 `Unknown` 时抛出。在 Java 21 中对这个密封类型做穷尽的 `switch`；`as? Lookup.Present` 会静默地把 `Unknown` 当作不存在。

- **谁会得到 `Unknown`**：没有数据源的读取（`Store`、`VersionedStore`、`SetStore`，以及外部来源的 `Cache` 上不带 loader 的读取），以及设置了 `readFailure = THROW` 时带数据源的读取。默认情况下带数据源的读取改为从数据源加载。
- **`unknownRead`** 设在 `CacheSpec`（只限外部来源）、`StoreSpec` 或 `VersionedStoreSpec` 上，决定不带 loader 的读取遇到 `Unknown` 时返回什么：`THROW`（默认），或 `ABSENT`：返回 `null`（`Lookup.Absent`）且什么也不缓存，下一次读取重新访问 Redis。它不作用于 `getIfChanged`、`getVersioned` 与带 loader 的读取，`SetStore` 没有它。在 spec 中、接口上的 `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)` 或[定制器](#customizer)中设置；它暂时没有配置项。
- **`getAll`** 先完成其余每个 key（释放 lease、写回加载结果），再抛出 `CacheReadException`：`unknownKeys` 是没有答案的 key，`partialResult` 是已经得到的值。`lookupAll(keys)` 改为对每个 key 返回一个 `Lookup`，不抛出。
- 加载超时（`load-timeout`）与被拒绝的加载（`load-max-concurrent`）不是 Redis 故障：它们抛出各自的异常，`lookup` 也一样。
- 每个 `Unknown` 计入 `cocache.reads.unknown`（含按 `ABSENT` 换算的），那次 Redis 故障本身计入 `cocache.remote.failures`。

## 编解码 {#codecs}

`Codec<V>` 负责把值转成存入 Redis 的字节，以及反向转换。

| 声明方式 | 使用的编解码 |
|----------|--------------|
| 接口，`@CoCache(codec = "name")` | 该 bean |
| 接口，有一个 `Codec<V>` 类型的 bean | 该 bean |
| 接口，有多个 `Codec<V>` 类型的 bean | 启动失败；用 `@CoCache(codec)` 指定一个 |
| 接口，一个都没有 | 用应用的 Jackson 3 `ObjectMapper` 为 `V` 构造 `JacksonCodec` |
| spec | 你设置的那个：`jacksonCodec<V>(mapper)`、`JacksonCodec.of(mapper, V::class.java)`、`StringCodec` 或自定义 |

自定义编解码就是两个函数：

```kotlin
@Bean
fun moneyCodec(): Codec<Money> = object : Codec<Money> {
    override fun encode(value: Money) = value.toString().toByteArray()
    override fun decode(bytes: ByteArray) = Money.parse(String(bytes))
}
```

载荷无法解码时（比如值类型做了不兼容的修改），有 loader 的缓存按未命中处理并重新加载。没有数据源的类型无从重新加载，改为抛出 `CacheReadException`，key 原样保留。仍能解码但含义变了的修改无法被发现：这类修改请同时更换 namespace 或 key 前缀。

Redis 中的值在编解码输出之前带有一个简短的二进制头。`redis-cli GET` 看到的是二进制内容；其它程序不得直接读写 CoCache 的 key。

## Store {#store}

`Store<K, V>` 用于**唯一副本就在 Redis 中**的数据：控制面发布的策略快照、撤销表、会话、一次性验证码。没有 loader，读取从不申请加载 lease。

```kotlin
snapshots.put(id, snapshot)                  // 后写者胜
snapshots[id]                                // Redis 中没有时为 null
snapshots.delete(id)                         // 同步完成；Redis 失败时抛出
```

- **不存在就是不存在。** Redis 中没有的 key，包括其中是别的缓存的加载 lease 的 key，读作 `Lookup.Absent`，这个答案像值一样进入 L2。
- **Redis 故障是 [`Unknown`](#unknown)**：除非 spec 写了 `unknownRead = ABSENT`，`get` 抛出 `CacheReadException`。
- **删除从不重试。** `delete` 与 `deleteIfMatch` 在返回之前完成，否则抛出 `CacheWriteException`。延迟的重试可能删掉失败之后写入的值。删除必须扛住重试与乱序时，使用 `VersionedStore` 与 `deleteIfNewer`。
- **每次写入都在值的头部带写入标识与载荷的 SHA-256。** 读取时重算摘要；载荷与摘要不符、或没有摘要（由其它类型写入）的值抛出 `CacheReadException`，并原样保留。
- **条件写入**（`getIfChanged`、`putIfMatch`、`deleteIfMatch`）让多个写入方对同一个值“读—改—写”而不丢失更新。见[条件写入](./freshness.md#conditional-writes)。
- **持久性等于 Redis 的持久性。** `maxmemory-policy` 不能驱逐这些 key（不要用 `allkeys-*`）；starter [在启动时检查它](./configuration.md#redis)。异步复制下的故障切换可能丢失最近的写入。
- **读不懂的内容**（本版本读不懂的格式的内容、其它程序写入的值、更高版本的 CoCache 写入的值）抛出 `CacheReadException`，从不删除，因为删除它就是删除唯一副本。

### 默认策略 {#store-defaults}

权威类型以自己的默认策略为起点：`StoreSpec.DEFAULT_POLICY`（`VersionedStoreSpec.DEFAULT_POLICY` 与 `SetStoreSpec.DEFAULT_POLICY` 取同样的值）：

| 字段 | `Store`、`VersionedStore`、`SetStore` | 派生类型（`CachePolicy.DEFAULT`） |
|------|---------------------------------------|-----------------------------------|
| `ttl` | 永不过期 | 1 小时 |
| `localMaxAge` | 30 秒 | 5 分钟 |
| `absentTtl` | 10 秒 | 1 分钟 |
| `tombstoneTtl` | 1 天（只用于 `VersionedStore`） | 值的 `ttl` |

L2 的寿命更短，是因为丢失的失效无法靠重新加载纠正：剩下的上界只有 L2 的寿命。需要更长时在声明中写出。寿命即语义的数据（会话、一次性验证码、幂等键）需要显式的 `ttl`。

## 带版本的类型 {#versioned}

`VersionedCache<K, V>` 与 `VersionedStore<K, V>` 在每个值旁边保存版本，并按版本对写入排序，重复投递或重放的旧版本因此替换不了新版本。两者都没有丢掉版本的写入：

| | `VersionedCache` | `VersionedStore` |
|-|------------------|------------------|
| 数据源 | [带版本的 loader](#versioned-loaders) | 没有 |
| 写入 | `putIfNewer(key, value, version)` | `putIfNewer(key, value, version)` |
| 有序的删除 | `invalidateIfNewer(key, version)`（墓碑） | `deleteIfNewer(key, version)`（墓碑） |
| 保留栅栏的删除 | `evict(key)`：下一次读取重新加载，旧版本仍被拒绝 | — |
| 丢掉栅栏的删除 | `purge(key)` | `purge(key)` |
| 连同版本读取 | `getVersioned(key)` | `getVersioned(key)` |
| 头部带写入标识 | 否 | 是 |
| 提前刷新 | 是 | 否 |

规则见[按版本写入](./freshness.md#versioned-writes)。`VersionedStore` 的读取同 `Store`：不申请加载 lease，不存在就是不存在，Redis 故障是 `Unknown`。它的墓碑存活 `tombstoneTtl`（默认 1 天），必须覆盖重复投递的时间窗口；墓碑永不过期的 `VersionedStore` 构建失败。

## SetStore {#set-store}

`SetStore<K, E>` 用于**唯一副本就在 Redis 中**的集合：索引、成员列表、在线会话集合。元素的增删在 Redis 中原子完成，不同实例的并发更新不会丢失。

```kotlin
@CoCache
interface OnlineUsers : SetStore<String, String>

online.add(roomId, listOf(userId))       // 需要时创建集合
online.remove(roomId, listOf(userId))    // 移空时删除 key
online.clear(roomId)
val members: Set<String> = online[roomId] // 不存在时为空集合
```

- **没有 loader**，也没有 `invalidate`：`clear` 删除集合。在它上面写 `@CoCache(loader = …)` 启动失败。
- **`put(key, emptySet())` 等同于 `clear(key)`**：空集合不保留 key。`putAll` 对每一项遵循同样的规则（[批量写入](./freshness.md#batch-writes)）。
- **TTL 默认永不过期。** 设置了 TTL 时，每次 `add` 或 `remove` 都会重设到期时间。不存在的集合在 L2 中至多保留 `absentTtl`（10 秒）。
- **故障会显式暴露。** 写入抛出 `CacheWriteException`。Redis 读失败是 [`Unknown`](#unknown)：`get` 抛出 `CacheReadException`，`lookup` 返回 `Unknown`。`SetStore` 没有 `unknownRead`。
- **读不懂的内容会抛出。** 元素无法解码时读取抛出异常，更高格式写入的集合抛出 `IllegalStateException`。本版本读不懂的格式的内容（比如同一个 key 上的 Redis 原生 Set）使读取抛出 `CacheReadException`；在它或更高格式写入的集合上调用 `add` / `remove` 抛出 `CacheWriteException`，key 不变，只有 `put` 会整体覆盖它。`SetStore` 从不回收这样的内容（见[无法读取的载荷](../operations/troubleshooting.md#corrupt-payloads)）。
- **持久性等于 Redis 的持久性**，同 [Store](#store)。

如果集合的权威数据在数据库中，不要用 `SetStore`。声明为 `Cache<K, Set<E>>`，更新数据库后 `invalidate`。

## 定制声明 {#customizer}

`CacheSpecCustomizer` bean 在缓存创建之前修改声明。用它表达注解做不到的配置，或一次修改多个缓存：

```kotlin
@Bean
fun userCacheCustomizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
    override fun supports(cacheName: String) = cacheName == "UserCache"   // 默认：所有缓存

    override fun customize(spec: CacheSpec.Builder<*, *>) {
        spec.policy {
            absentTtl = Duration.ofSeconds(30)
            lease = LeasePolicy(ttl = Duration.ofSeconds(30))   // loader 可能要跑 20 秒
        }
    }

    override fun customize(spec: StoreSpec.Builder<*, *>) {
        spec.unknownRead = UnknownReadMode.ABSENT              // Redis 故障时视为“未撤销”
    }
}
```

Java 中实现同一个接口，调用 `spec.updatePolicy(p -> p.absentTtl(Duration.ofSeconds(30)))`。

每种 spec 有一个重载（`CacheSpec.Builder`、`VersionedCacheSpec.Builder`、`StoreSpec.Builder`、`VersionedStoreSpec.Builder`、`SetStoreSpec.Builder`），默认什么也不做。定制器按 Spring 的顺序执行，位于声明之后、`cocache.*` 配置项之前（见[优先级](./configuration.md#precedence)）。

## 启动校验 {#validation}

CoCache 宁可启动失败，也不运行一个无法按声明工作的缓存。每条错误信息都带有缓存名。

| 启动失败的情形 | 原因 |
|----------------|------|
| 接口没有继承五种类型之一、继承了多个，或没有具体的类型参数；spec bean 的返回类型没有写出泛型 | key、loader 与编解码都由这些类型解析 |
| 接口的抽象方法对应不上它所继承的类型的任何方法 | 否则调用会在运行时失败 |
| `Cache` 没有匹配到 loader 也没有声明 `source = EXTERNAL`；`VersionedCache` 没有带版本的数据源；`CacheSpec` 没有数据来源或有多个 | 缓存必须说明数据从哪里来 |
| `Store`、`VersionedStore`、`SetStore` 上设置了 `loader` 或 `source = EXTERNAL`；有数据源的 `CacheSpec` 上设置了 `unknownRead`；有 loader 的 `Cache`、`VersionedCache` 或 `SetStore` 上写了 `@CoCache(unknownRead = ABSENT)` | 这些类型没有数据源，或它们的读取不接受 `unknownRead` |
| 匹配到多个 `CacheLoader<K, V>` 或 `Codec<V>` bean | 静默挑一个可能用错数据源或格式 |
| 后端的存储缺少这种类型需要的能力（见[能力](#spec-bean)） | 这种类型的操作无法工作 |
| 墓碑永不过期的 `VersionedStore` | 每个删除过的 key 都会永远留在 Redis 中 |
| 在 `ttl` / `expireAfterAccess` 之外的时长属性上用 `FOREVER`，时长为 0（`ttlJitter`、`refreshWindow` 除外），或任何时长为负数 | 无意义或不安全的寿命 |
| `refreshWindow` 不小于 `ttl − ttlJitter`（按实际抖动计算），或不小于 `localMaxAge` | 新副本一出生就在窗口内，每次命中都会触发刷新 |
| 缓存名、前缀与 namespace 的组合或 bean 名重复 | 两个缓存会共享 key 或失效消息 |
| namespace 不符合 `[A-Za-z0-9._-]+`，例如含 `:` | 它的 key 可能落进另一个 namespace 的范围；把前缀移到 `keyPrefix` |
| `cocache.caches.[name]` 指向不存在的缓存 | 几乎总是拼写错误；见[配置参考](./configuration.md#validation) |

存在匹配的 `CacheLoader` bean 的权威类型接口可以启动，但会告警：这份数据也许应该是 `Cache`。key 类型的 `toString()` 检查发生在每种 key 类型第一次读取时，而不是启动时。
