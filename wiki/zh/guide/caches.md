---
title: 选择与声明缓存
description: 用一个问题在五种缓存类型（Cache、VersionedCache、Store、VersionedStore、SetStore）之间选择，用带注解的接口或 spec bean 声明它们，并控制缓存名、存储 key、key 编码、编解码、默认策略、CoCacheFactory、定制器与启动校验。
---

# 选择与声明缓存

一个缓存由这些要素组成：名字、key 类型、值类型、说明 Redis 为它保存了什么的类型、为 Redis 编码值的方式，以及一套策略。本页先讲类型，再讲所有类型共有的部分：两种声明方式、key、编解码、读取与启动校验。每个类型的操作见它自己的指南。

## 选择类型 {#types}

对数据只问一个问题：**Redis 丢了这个 key，本进程能否按需重建它？**

- **能**：Redis 保存的是*派生副本*。声明 `Cache`；写入方按版本有序写入时声明 `VersionedCache`。
- **不能**：Redis 保存的是*唯一副本*。声明 `Store`、`VersionedStore`，元素集合则声明 `SetStore`。

| 类型 | Redis 中是 | 数据来自 | 写入 | 删除 | 指南 |
|------|-----------|----------|------|------|------|
| `Cache<K, V>` | 派生副本 | `loader` / `asyncLoader`，或调用方（`externallySourced()`） | `put`、`putAll`（后写者胜） | `invalidate`，Redis 失败时在后台重试 | [Cache](./cache.md) |
| `VersionedCache<K, V>` | 派生的带版本副本 | 带版本的 loader | `putIfNewer`、`invalidateIfNewer` | `evict` 保留版本栅栏；`purge` 丢掉它 | [VersionedCache](./versioned-cache.md) |
| `Store<K, V>` | 唯一副本 | 你的写入 | `put`、`putAll`、`putIfMatch` | `delete`、`deleteIfMatch`：同步完成，从不重试 | [Store](./store.md) |
| `VersionedStore<K, V>` | 唯一的带版本副本 | 你的按版本写入 | `putIfNewer` | `deleteIfNewer`（墓碑）；`purge` | [VersionedStore](./versioned-store.md) |
| `SetStore<K, E>` | 集合的唯一副本 | 你的元素写入 | `add`、`remove`、`put`、`putAll` | `clear` | [SetStore](./set-store.md) |

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
| 可重建的副本声明成 `Store` | Redis 丢了 key 之后读成“不存在”，直到有人重新写入；`delete` 失败抛出、不重试 | 存在匹配的 `CacheLoader` 或 `AsyncCacheLoader` bean 时启动告警 |
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

接口必须恰好继承 `Cache<K, V>`、`VersionedCache<K, V>`、`Store<K, V>`、`VersionedStore<K, V>`、`SetStore<K, E>` 之一，或它们的异步、挂起视图之一（`AsyncCache`、`SuspendingCache`、`AsyncStore`、`SuspendingStore` 等），并带具体的类型参数；继承哪一个决定创建哪种缓存。视图声明的缓存与它的同步类型相同（`SuspendingStore` 就是 `Store`），bean 是这个视图：

```kotlin
@CoCache
interface UserCache : SuspendingCache<String, User>

suspend fun profile(id: String): User? = userCache.get(id)
```

经挂起接口的调用直接交给缓存的挂起视图，`Async*` 接口返回 future（见[协程](./coroutines.md#declare)、[异步](./async.md#declare)）。`@CoCache` 本身可以省略：列在 `@EnableCoCache` 中却没有标注它的接口使用全部默认值。注解只从接口本身读取，不读元注解，也不读父接口上的注解。

用户 API 的类型不供你的类实现，委托给 CoCache 创建的缓存除外：8.x 以新的能力类型或默认方法新增操作，手写的实现可能没有预料到它们。像上面这样继承它们的接口没有问题，由代理实现。

### `@CoCache` {#cocache-annotation}

时长单位为**秒**。所有策略属性默认都是 `CoCache.UNSET`（`-1`），意思是“这里没有写出”，由 `cocache.defaults` 或类型默认值决定（见[优先级](./configuration.md#precedence)）。设置了的属性就是声明写出的，全局默认值不会改变它。每种类型以自己的默认值为起点：派生类型为 `DefaultPolicies.CACHE` 与 `DefaultPolicies.VERSIONED_CACHE`，`Store`、`VersionedStore` 与 `SetStore` 为[权威类型的默认值](#store-defaults)。

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
| `loader` | `""` | 数据源的 bean 名（只用于 `Cache` 与 `VersionedCache`）。为空时取唯一匹配的 bean（见 [loader](./cache.md#loaders)、[带版本的 loader](./versioned-cache.md#versioned-loaders)）。 |
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

设置了的 `maximumSize` 是声明写出的，即使它等于默认值 `10000`，`cocache.defaults.*.local-maximum-size` 也不会替换它（[声明写出了哪些字段](./configuration.md#declared-fields)）。

### 按类型匹配 bean {#bean-matching}

`loader` 或 `codec` 为空时，starter 按完整的泛型类型查找 bean（`CacheLoader<K, V>`、`AsyncCacheLoader<K, V>`、`Codec<V>`；[组合缓存](./join-cache.md)以同样的方式查找 `JoinKeyExtractor<V1, K2>`），看到的 bean 与按名称查找时相同：

- **父容器中的 bean 也算。** 子容器中同名的 bean 遮蔽父容器的，不论类型。子容器注册的别名也是如此，即使它指向父容器的 bean：按这个名称 `getBean` 得到的是别名的目标，父容器自己的同名 bean 因此不是候选。父容器的作用域代理也是如此：它的目标（`loader` 背后的 `scopedTarget.loader`）随之被遮蔽。子容器与父容器各有一个匹配、名称不同时是两个候选，与其它歧义一样启动失败，与 Spring 按类型注入相同。
- **作用域代理按代理注入**（`@Scope(proxyMode = …)`），作用域因此照常生效：prototype 的 loader 每次调用都取得新的目标。用 `joinKeyExtractor = "…"` 指定的作用域代理 `JoinKeyExtractor` 按其目标的定义校验泛型，因为代理实例已经丢失了泛型。
- **类型参数取接口继承之后的。** 有 `interface BaseStore<V : Any> : Store<String, V>` 与 `interface UserStore : BaseStore<User>` 时，`UserStore` 匹配 `Codec<User>`，默认的 Jackson 编解码按 `User` 解码，`List<User>` 中的元素与集合的成员也一样。

### 添加方法 {#interface-methods}

default 方法按原样执行，可以添加辅助方法：

```kotlin
interface UserCache : Cache<String, User> {
    fun nameOf(id: String): String? = get(id)?.name
}
```

default 方法可以声明在包可见的接口中：声明的接口本身可以是包可见的，也可以是 `public` 的、从包可见的基接口继承 default 方法。这时 CoCache 经那个接口的私有查找调用它们。在具名模块中要把这个包 `opens` 给 CoCache，否则创建缓存失败。

抽象方法必须在名称、参数与返回类型上对应它所继承的类型的某个方法，否则启动失败。值类型可以收窄（`get` 返回 `User` 而不是 `V`），其它返回类型，如 `getAll` 的 `Map`、`Set`、`Lookup` 或视图，只能相同或放宽：缓存返回自己的实现类（`LinkedHashMap`，不是 `SortedMap`）。`suspend` 方法的结果类型同样如此。`Suspending*` 接口中的 default 方法可以是 `suspend` 的，调用接口的挂起方法。缓存与 default 方法抛出的异常原样到达调用方，不会被包装。

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

- **返回类型必须写出泛型**（`CacheSpec<String, Product>`）。starter 从中读取 `K` 与 `V`，而不创建 spec。返回 `FactoryBean<CacheSpec<String, Product>>` 也可以：`K` 与 `V` 取自它的产品类型，读取时不创建工厂。返回原始类型时启动失败。
- **bean 名**：spec bean 名去掉 `Spec` 后缀（`productSpec` → `product`）；没有该后缀时追加 `Cache`（`products` → `productsCache`）。*缓存名*则是传给 `cacheSpec(…)` 的那个名字。
- **spec 必须设置编解码。** 代码中没有 Jackson 兜底；这里的 `ObjectMapper` 是 Jackson 3（`tools.jackson.databind`）。`SetStoreSpec` 设置的是 `elementCodec`。
- **`CacheSpec` 必须恰好声明一个数据来源**：`loader`、`asyncLoader` 或 `externallySourced()`。`VersionedCacheSpec` 必须设置 `versionedLoader` 或 `versionedAsyncLoader`。权威类型的 spec 根本没有 loader。
- Kotlin 中 `policy { … }` 在类型的默认策略上修改；Java 中用 `updatePolicy(…)`。只有赋值的字段算作声明写出的，其余字段仍交给 `cocache.defaults`（见[声明写出了哪些字段](./configuration.md#declared-fields)）。`policy(CachePolicy)`（Kotlin：`policy = …`）整体替换策略，视为写出全部字段，全局默认值于是不再生效。`CachePolicy` 没有公开的构造器，也没有不带起点的 `builder()`，这样的策略因此总有一个起点：类型自己的默认值，`DefaultPolicies.STORE.toBuilder()` 或 `CachePolicy.builder(DefaultPolicies.STORE)`。从派生缓存的默认值构建 `Store` 的策略会给它一个 1 小时的 `ttl`。`ttl = forever()`、`refreshWindow = off()` 与 `maxConcurrent = unlimited()` 显式写出哨兵值。

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
| `keyPrefix` | `cocache:` | `@CoCache(keyPrefix)`、spec 的 `keyPrefix`，或 [`key-prefix` 配置项](./configuration.md#key-prefix)。配置项优先于声明，除非库[锁定](./configuration.md#locks)了前缀。 |
| `namespace` | 缓存名 | `@CoCache(namespace)`、spec 的 `namespace` |
| `encodedKey` | `key.toString()` | `@CoCache(keyExpression)`、spec 的 `keyEncoder` |

- CoCache 的内部 key 放在 `{keyPrefix}!` 之下，任何 namespace 都产生不了这个位置：`!` 不在 namespace 的字符集中。唯一的例外是原子广播探测：不论前缀，它都用固定的 key `cocache:!probe:{a}`（频道 `cocache:!probe:{b}`）。
- namespace 只能含字母、数字、`.`、`_` 与 `-`（`[A-Za-z0-9._-]+`）。含 `:` 的 namespace 在创建时失败，信息给出把 `:` 之前的部分移到 `keyPrefix` 的写法：`namespace = "app:user"` 改为 `keyPrefix = "cocache:app:"` 与 `namespace = "user"`。没有 `:`，一个 namespace 的 key 就不会落进另一个 namespace 的范围。
- 在一个应用内，缓存名必须唯一，前缀与 namespace 的组合也必须唯一。namespace 相同、前缀不同的两个缓存可以共存。重复时启动失败。两个缓存的存储前缀 `{keyPrefix}{namespace}:` 相等或一个是另一个的开头时同样失败，例如 `app:` + `users` 与 `app:users:` + `v2`：前者的 key `v2:k` 与后者的 key `k` 都是 `app:users:v2:k`。CoCache 在一个运行时内检查这一点；多个应用共用一个 Redis 时，选择互不为开头的前缀。
- 失效消息走频道 `{keyPrefix}inv:{namespace}`，前缀不同的应用收不到彼此的消息。
- actuator 端点与 `ExternalInvalidator` 接受的都是**编码后的 key**，所以其它工具不必知道 key 的 Kotlin 类型也能定位它。
- 多个应用共用一个 Redis 时，用 [`cocache.key-prefix` 配置项](./configuration.md#key-prefix)给每个应用一个不同的前缀（比如 `${spring.application.name}:`）。`@CoCache(keyPrefix)` 是字面文本：其中的占位符不会被解析（在 Kotlin 中 `$` 还会开启字符串模板）。修改运行中派生缓存的前缀或 namespace 相当于冷启动：旧 key 不再被读取，随各自的寿命过期。权威类型则会丢失数据，因为旧 key 中是唯一副本。

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

编码后的 key 还必须是合法的 UTF-16。Redis 的 key 与失效消息都是 UTF-8，表示不了未配对的代理（surrogate）：`\uD800`–`\uDFFF` 中单独的一个 `char`，例如截断字符串时留下的半个 emoji。它会变成 `?`，与另一个 key 共用同一个 Redis key，其它实例淘汰的也会是那个条目。带这种 key 的调用在修改任何东西之前以 `IllegalArgumentException` 失败：读取、写入、`CacheAdmin` 与 [`ExternalInvalidator`](../operations/troubleshooting.md#external-invalidator) 都一样，批量调用整批拒绝，与这个 key 在批量中的位置无关：被拒的 `getAll` 不刷新、不回源、不计数，排在它前面的合法 key 也一样。这个拒绝是调用方的错误，不是 L1 故障：`read-failure` 与 `unknownRead`（见[未知读取](#unknown)）都不适用，这样的调用从不变成未命中、`ABSENT` 或回源。信息给出缓存名与位置，并把这个字符显示为 `\uXXXX`。emoji 等 BMP 之外的字符、中文与空串照常可用。检查只在去往 Redis 的路上进行，L2 命中的开销不变。

## 按条目决定寿命 {#ttl-of}

寿命取决于值时，设置 `ttlOf`（在 spec 或定制器中）：

```kotlin
cacheSpec<String, Session>("SessionCache") {
    codec = jacksonCodec<Session>(mapper)
    loader = CacheLoader { id -> sessions.find(id) }
    ttlOf = TtlFunction { _, session -> Duration.between(Instant.now(), session.expiresAt) }
}
```

返回非 `null` 时覆盖策略 `ttl`，且不加抖动；返回 `null` 时沿用策略。负缓存总是使用 `absentTtl`，墓碑使用 `tombstoneTtl`。`ttl` 配置项不影响 `ttlOf`，唯一的例外是 `Cache` 上的 `cocache.overrides.cache.ttl` 限制它的结果（见[覆盖](./configuration.md#field-layers)），计入 `cocache.ttl.clamped`。`StoreSpec`、`VersionedCacheSpec` 与 `VersionedStoreSpec` 同样接受 `ttlOf`。

## 拒绝不可能存在的 key {#key-filter}

`KeyFilter`（在 spec 或定制器中）在 L2 未命中之后检查。被它拒绝的 key 直接返回“不存在”，不访问 Redis 也不调用 loader，适合放在布隆过滤器前面：

```kotlin
keyFilter = KeyFilter { encodedKey -> !bloom.mightContain(encodedKey) }
```

它接收[编码后的 key](#storage-keys)，不含 `{keyPrefix}{namespace}:`，只能拒绝确定不存在的 key。过滤器按编码后的 key 建立，key 前缀或命名空间改变之后照常回答。

## 读取：存在、不存在与未知 {#unknown}

每种类型都经 L2 与 Redis 读取。`lookup(key)` 区分三种答案：

| `Lookup` | 含义 | `get` 返回 |
|----------|------|------------|
| `Present(value, expiresAt)` | 有值 | 值 |
| `Absent(expiresAt)` | 确认不存在：负缓存、Redis 中没有的 `Store` key、未填充的 key | `null` |
| `Unknown(error)` | 这一次读取没有答案：Redis 失败或超时 | 抛出 `error`（`CacheReadException`） |

`Unknown` 是关于这一次读取的事实，不是关于数据的：它从不被缓存、从不广播，也不交给自己有数据源的调用方。`lookup(key).valueOrThrow()` 返回值或 `null`，遇到 `Unknown` 时抛出。在 Java 21 中对这个密封类型做穷尽的 `switch`；`as? Lookup.Present` 会静默地把 `Unknown` 当作不存在。

- **谁会得到 `Unknown`**：没有数据源的读取（`Store`、`VersionedStore`、`SetStore`，以及外部来源的 `Cache` 上不带 loader 的读取），以及设置了 `readFailure = THROW` 时带数据源的读取。默认情况下带数据源的读取改为从数据源加载。
- **`unknownRead`** 设在 `CacheSpec`（只限外部来源）、`StoreSpec` 或 `VersionedStoreSpec` 上，决定不带 loader 的读取遇到 `Unknown` 时返回什么：`THROW`（默认），或 `ABSENT`：返回 `null`（`Lookup.Absent`）且什么也不缓存，下一次读取重新访问 Redis。它与 `get` 一样作用于 `getVersioned`，不作用于 `getIfChanged` 与带 loader 的读取，`SetStore` 没有它。在 spec 中、接口上的 `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)`、[定制器](#customizer)或 `cocache.caches.[name].unknown-read` 中设置（见[配置](./configuration.md#policy-properties)），全局层都不设置它。
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

只有接口回退到 Jackson 时才查找 `ObjectMapper`，所有回退的缓存共用它。指定了编解码或匹配到编解码的缓存，以及 spec，都不用它，所以应用有多个 `ObjectMapper` bean 与它们无关。确实回退时如果有多个 `ObjectMapper` bean，使用 `@Primary` 的那个；没有 `@Primary` 时启动失败，错误信息指出这个缓存。一个 `ObjectMapper` bean 都没有时，CoCache 自己构造一个 `JsonMapper`。

自定义编解码就是两个函数：

```kotlin
@Bean
fun moneyCodec(): Codec<Money> = object : Codec<Money> {
    override fun encode(value: Money) = value.toString().toByteArray()
    override fun decode(bytes: ByteArray) = Money.parse(String(bytes))
}
```

载荷无法解码时（比如值类型做了不兼容的修改），有 loader 的缓存按未命中处理并重新加载。没有数据源的读取（`Store`、`VersionedStore`、`SetStore`、不带 loader 读取的外部来源 `Cache`）无从重新加载，改为抛出 `CacheReadException`，key 原样保留。仍能解码但含义变了的修改无法被发现：这类修改请同时更换 namespace 或 key 前缀。

Redis 中的值在编解码输出之前带有一个简短的二进制头。`redis-cli GET` 看到的是二进制内容；其它程序不得直接读写 CoCache 的 key。

## 各类型的默认策略 {#store-defaults}

每种类型以 `DefaultPolicies` 中自己的默认策略为起点：权威类型为 `STORE`、`VERSIONED_STORE` 与 `SET_STORE`，派生类型为 `CACHE` 与 `VERSIONED_CACHE`。端点把来自这里的值报告为 `TYPE_DEFAULT`。

| 字段 | `Store`、`VersionedStore`、`SetStore` | `Cache`、`VersionedCache` |
|------|---------------------------------------|-----------------------------------|
| `ttl` | 永不过期 | 1 小时 |
| `localMaxAge` | 30 秒 | 5 分钟 |
| `absentTtl` | 10 秒 | 1 分钟 |
| `tombstoneTtl` | 1 天（只用于 `VersionedStore`） | 值的 `ttl`（只用于 `VersionedCache`） |

L2 的寿命更短，是因为丢失的失效无法靠重新加载纠正：剩下的上界只有 L2 的寿命。需要更长时在声明中写出，或用 `cocache.defaults.store.*` 为所有权威缓存设置。寿命即语义的数据（会话、一次性验证码、幂等键）需要显式的 `ttl`：这些类型的 `ttl` 只能由声明与 `cocache.caches.[name].ttl` 设置，全局层都不能（见[各层能设置什么](./configuration.md#field-layers)）。

## 库与不用 starter 的运行时：`CoCacheFactory` {#factory}

在 starter 自己的声明之外创建缓存，入口是 `CoCacheFactory`。使用 starter 时注入 `CoCacheFactory` bean：它创建的缓存与 `@CoCache` 接口、spec bean 一样叠加定制器与 `cocache.*` 配置，出现在端点上，也会注册指标。

```kotlin
interface RevokedTokens : Store<String, Instant>

@Bean
fun revokedTokens(factory: CoCacheFactory, mapper: ObjectMapper): RevokedTokens =
    factory.create(RevokedTokens::class.java, storeSpec<String, Instant>("revoked-tokens") { codec = jacksonCodec<Instant>(mapper) })
```

- `create(spec)` 返回与 spec 对应的类型（`storeSpec` 得到 `Store`，以此类推）；`create(type, spec)` 返回实现你的接口的代理。接口必须继承 spec 声明的那个类型，或这个类型的异步、挂起视图（`storeSpec` 对应 `SuspendingStore`），接口中重新声明的抽象方法都要对应这个类型的某个方法。类会被拒绝：用户 API 类型不供手写实现，应当用 `create(spec)` 创建缓存再委托给它。sealed 接口同样被拒绝，因为 JDK 代理不能实现它；继承它的非 sealed 接口可以。这些校验以及 JDK 自己对代理的检查都在创建缓存之前完成：被拒绝的接口不注册任何东西，用同一个 spec 和有效的接口重试照常成功。
- **锁定。** 依赖某个字段或存储布局的库在创建时锁定它：`factory.create(spec) { lock(PolicyField.READ_TIMEOUT); lockLayout(LayoutField.KEY_PREFIX) }`。全局层于是跳过它，`cocache.caches.[name]` 的条目必须显式 `unlock` 它。见[锁定字段](./configuration.md#locks)。
- **未分层的缓存。** 直接用 `CacheRuntime.create` 创建的缓存跳过所有配置层：定制器、`cocache.defaults`、`cocache.overrides` 与 `cocache.caches.[name]` 对它都不生效，也无法锁定。启动日志在这一行末尾标注 `unlayered: …`，端点报告 `layered: false`。指向它的 `cocache.caches.[name]` 在缓存创建时告警，并列在端点的 `ignoredConfiguration` 中；它不让启动失败，因为覆盖值常在事故中写下。库应经 `CoCacheFactory` 创建缓存。
- 不用 Spring 时，`CoCacheFactory(runtime)` 提供接口代理，不叠加任何层。没有配置层的应用直接用 `runtime.create(spec)` 也没有问题。

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

每种 spec 有一个重载（`CacheSpec.Builder`、`VersionedCacheSpec.Builder`、`StoreSpec.Builder`、`VersionedStoreSpec.Builder`、`SetStoreSpec.Builder`），默认什么也不做。定制器按 Spring 的顺序执行，位于 `cocache.defaults` 与声明之后、`cocache.overrides` 与 `cocache.caches.[name]` 之前（见[优先级](./configuration.md#precedence)）。定制器可以修改策略字段与不决定类型的字段；修改数据来源，或修改被库[锁定](./configuration.md#locks)的字段或布局时抛出异常。

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
| `cocache.caches.[name]` 指向不存在的缓存，或含有不适用于该缓存类型的配置项（除非 `cocache.caches-validation: warn`） | 几乎总是拼写错误；见[配置参考](./configuration.md#validation) |
| `cocache.caches.[name]` 设置了被库锁定的字段却没有在 `unlock` 中列出；定制器修改了被锁定的字段或数据来源 | 库依赖这个值（见[锁定](./configuration.md#locks)） |
| 不带单位的时长配置项，如 `ttl: 60` | 写成 `60s`、`5m` 或 `PT2M`（见[时长](./configuration.md#durations)） |

存在匹配的 `CacheLoader` 或 `AsyncCacheLoader` bean 的 `Store` 或 `VersionedStore` 接口可以启动，但会告警：这份数据也许应该是 `Cache`。key 类型的 `toString()` 检查发生在每种类型的 key 第一次编码时（无论是读取还是写入），而不是启动时。
