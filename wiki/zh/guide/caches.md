---
title: 声明缓存
description: 用带注解的接口或 CacheSpec bean 声明缓存，并控制缓存名、存储 key、key 编码、loader、编解码、集合缓存、定制器与启动校验。
---

# 声明缓存

一个缓存由这些要素组成：名字、key 类型、值类型、加载缺失值的方式、为 Redis 编码值的方式，以及一套策略。有两种写法，产出的是同一个引擎：

| | 接口 | spec bean |
|-|------|-----------|
| 写法 | `@CoCache interface UserCache : Cache<String, User>` | `@Bean fun userSpec(): CacheSpec<String, User>` |
| 注入为 | `UserCache` | `Cache<String, User>`，配合 `@Qualifier` |
| loader 与编解码 | 按类型查找，或在注解中指定 bean 名 | 在代码中设置 |
| 可配置范围 | 基本策略与 L2 容量 | 全部，包括 `ttlOf`、`KeyEncoder` 与 `KeyFilter` |

先用接口。需要注解提供不了的能力时，换成 spec bean（或加一个[定制器](#customizer)）。

## 接口式缓存 {#interface}

```kotlin
@CoCache(ttl = 600, localMaxAge = 60)
@CaffeineCache(maximumSize = 100_000)
interface UserCache : Cache<String, User>

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App
```

接口必须继承带具体类型参数的 `Cache<K, V>`（或 [`SetCache<K, E>`](#set-cache)）。`@CoCache` 本身可以省略：列在 `@EnableCoCache` 中却没有标注它的接口使用全部默认值。注解只从接口本身读取，不读元注解，也不读父接口上的注解。

### `@CoCache` {#cocache-annotation}

时长单位为**秒**。所有策略属性默认都是 `CoCache.UNSET`（`-1`），意思是“这里不设置，交给下一层”。

| 属性 | 默认值 | 含义 |
|------|--------|------|
| `name` | 接口简单类名 | 缓存名，也是 bean 名 |
| `namespace` | 缓存名 | 每个存储 key 的中间部分，也决定失效频道（见[存储 key](#storage-keys)） |
| `keyPrefix` | `cocache:` | 每个存储 key 的开头 |
| `keyExpression` | `toString()` | 把 key 转为字符串的 SpEL 模板（见 [key](#keys)） |
| `ttl` | `3600`（`SetCache` 为永不过期） | 值在 Redis 中的寿命。`CoCache.FOREVER`：永不过期。 |
| `ttlJitter` | `60` | 每个值的寿命上加的随机 ± 抖动，不超过 `ttl / 2`。`0`：关闭。 |
| `absentTtl` | `60` | 负缓存的寿命，不加抖动 |
| `localMaxAge` | `300` | L2 副本的最长寿命：失效消息丢失时的陈旧度上界 |
| `refreshWindow` | 关闭 | 值剩余寿命不足该时长时在后台刷新。`0`：关闭。 |
| `loader` | `""` | `CacheLoader` 的 bean 名。为空时取唯一的 `CacheLoader<K, V>` 类型 bean，没有则无 loader。 |
| `codec` | `""` | `Codec` 的 bean 名。为空时取唯一的 `Codec<V>` 类型 bean，没有则用 Jackson。 |

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

抽象方法必须在名称与参数上对应某个 `Cache`（或 `SetCache`）的方法，否则启动失败。缓存抛出的异常原样到达调用方，不会被包装。

## spec bean {#spec-bean}

`CacheSpec` bean 用代码声明缓存。starter 为每个 spec 注册一个 `Cache<K, V>` bean。

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
```

:::

```kotlin
class ProductService(@Qualifier("product") private val products: Cache<String, Product>)
```

- **返回类型必须写出泛型**（`CacheSpec<String, Product>`）。starter 从中读取 `K` 与 `V`，而不创建 spec。返回原始类型时启动失败。
- **bean 名**：spec bean 名去掉 `Spec` 后缀（`productSpec` → `product`）；没有该后缀时追加 `Cache`（`products` → `productsCache`）。*缓存名*则是传给 `cacheSpec(…)` 的那个名字。
- **spec 必须设置编解码。** 代码中没有 Jackson 兜底；这里的 `ObjectMapper` 是 Jackson 3（`tools.jackson.databind`）。
- Kotlin 中 `policy { … }` 在当前策略上修改；Java 中用 `updatePolicy(…)`。`policy(CachePolicy)` 则整体替换。

`setCacheSpec<K, E>("name") { elementCodec = … }` 以同样的方式声明 [`SetCache`](#set-cache)，并注册一个 `SetCache<K, E>` bean。

## 缓存名与存储 key {#storage-keys}

Redis 中的每个 key 都是：

```
{keyPrefix}{namespace}:{encodedKey}
 cocache:   UserCache :42
```

| 部分 | 默认值 | 设置方式 |
|------|--------|----------|
| `keyPrefix` | `cocache:` | `@CoCache(keyPrefix)`、`CacheSpec.keyPrefix`，或 [`key-prefix` 配置项](./configuration.md#key-prefix) |
| `namespace` | 缓存名 | `@CoCache(namespace)`、`CacheSpec.namespace` |
| `encodedKey` | `key.toString()` | `@CoCache(keyExpression)`、`CacheSpec.keyEncoder` |

- 在一个应用内，缓存名与 namespace 都必须唯一，重复时启动失败。
- 失效消息走频道 `cocache:inv:{namespace}`，频道不含前缀。
- actuator 端点与 `ExternalInvalidator` 接受的都是**编码后的 key**，所以其它工具不必知道 key 的 Kotlin 类型也能定位它。
- 多个应用共用一个 Redis 时，用 [`cocache.defaults.key-prefix` 配置项](./configuration.md#key-prefix)给每个应用一个不同的前缀（比如 `${spring.application.name}:`）。`@CoCache(keyPrefix)` 是字面文本：其中的占位符不会被解析（在 Kotlin 中 `$` 还会开启字符串模板）。修改运行中缓存的前缀或 namespace 相当于冷启动：旧 key 不再被读取，随各自的寿命过期。

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

- **`null` 表示“不存在”**，写入负缓存，存活 `absentTtl`。查询*失败*时请抛出异常：异常到达调用方（以及共享这次加载的所有调用方），不缓存任何东西。
- **默认的 `loadAll`** 逐个调用 `load`。如果用到 `getAll`，请用一次批量查询覆盖它。
- **loader 在调用方线程上执行**，事务、`SecurityContext` 与 MDC 都可用。经[异步视图](./async.md)调用时改在阻塞执行器上执行。
- **不要在 loader 内同线程读取同一个 key**，那会以 `IllegalStateException` 快速失败。
- **接口式声明**先按 `CacheLoader<K, V>`、再按 `AsyncCacheLoader<K, V>` 类型查找 loader。匹配到多个 bean，或两种各一个时启动失败，用 `@CoCache(loader = "…")` 指定一个。一个都没有时缓存没有 loader：未命中返回 `null`，缓存只能由 `put` 填充。
- **按调用提供 loader**：`cache.get(key) { k -> … }` 用给定的 loader 加载未命中的值，保护方式不变。Spring 的 `@Cacheable(sync = true)` 用的就是它。
- **非阻塞的数据源**：实现 `AsyncCacheLoader<K, V>`（`loadAsync` 返回 `CompletionStage`），在 spec 中设为 `asyncLoader`。一个缓存只有一个数据源，同步或异步；两种 API 都能配合任意一种。见[异步 API](./async.md#loader)。

### 按条目决定寿命 {#ttl-of}

寿命取决于值时，设置 `ttlOf`（在 spec 或定制器中）：

```kotlin
cacheSpec<String, Session>("SessionCache") {
    codec = jacksonCodec<Session>(mapper)
    loader = CacheLoader { id -> sessions.find(id) }
    ttlOf = TtlFunction { _, session -> Duration.between(Instant.now(), session.expiresAt) }
}
```

返回非 `null` 时覆盖策略 `ttl`，且不加抖动；返回 `null` 时沿用策略。负缓存总是使用 `absentTtl`。`ttl` 配置项不影响 `ttlOf`。

### 拒绝不可能存在的 key {#key-filter}

`KeyFilter`（在 spec 或定制器中）在 L2 未命中之后检查。被它拒绝的 key 直接返回“不存在”，不访问 Redis 也不调用 loader，适合放在布隆过滤器前面：

```kotlin
keyFilter = KeyFilter { storageKey -> !bloom.mightContain(storageKey) }
```

它接收完整的存储 key，只能拒绝确定不存在的 key。

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

载荷无法解码时（比如值类型做了不兼容的修改），CoCache 按未命中处理并重新加载。[`SetCache`](#set-cache) 没有数据源可以重新加载，会直接抛出。仍能解码但含义变了的修改无法被发现：这类修改请同时更换 namespace 或 key 前缀。

Redis 中的值在编解码输出之前带有一个简短的二进制头。`redis-cli GET` 看到的是二进制内容；其它程序不得直接读写 CoCache 的 key。

## 集合缓存 {#set-cache}

`SetCache<K, E>` 用于**唯一副本就在 Redis 中**的集合：索引、成员列表、在线会话集合。元素的增删在 Redis 中原子完成，不同实例的并发更新不会丢失。

```kotlin
@CoCache
interface OnlineUsersCache : SetCache<String, String>

online.add(roomId, listOf(userId))       // 需要时创建集合
online.remove(roomId, listOf(userId))    // 移空时删除 key
online.clear(roomId)
val members: Set<String> = online[roomId] // 不存在时为空集合
```

- **没有 loader。** 集合缓存没有可重新加载的数据源；在它上面写 `@CoCache(loader = …)` 启动失败，`get(key, loader)` 会抛出异常。
- **`invalidate` 与 `invalidateAll` 抛出 `UnsupportedOperationException`**，因为它们会销毁唯一副本。请用 `clear`。
- **`put(key, emptySet())` 等同于 `clear(key)`**：空集合不保留 key。`putAll` 对每一项遵循同样的规则（[批量写入](./freshness.md#batch-writes)）。
- **TTL 默认永不过期。** 设置了 TTL 时，每次 `add` 或 `remove` 都会重设到期时间。
- **故障会显式暴露。** 写入抛出 `CacheWriteException`（它们是幂等的，可以重试）。读取直接抛出 Redis 错误，因为没有数据源可以降级。
- **读不懂的内容会抛出。** 元素无法解码时读取抛出异常，更高格式写入的集合抛出 `IllegalStateException`。不是 CoCache 写入的内容（比如 5.x 在同一个 key 上留下的 Redis 原生 Set）使读取抛出 `CacheReadException`；在它或更高格式写入的集合上调用 `add` / `remove` 抛出 `CacheWriteException`，key 不变，只有 `put` 会整体覆盖它。`foreign-format: reclaim` 时这类内容读作空集合，下一次 `add` 替换它（见[从 5.x 升级](./migration-6.md#foreign-format)）。
- **持久性等于 Redis 的持久性。** `maxmemory-policy` 不能淘汰没有 TTL 的 key（不要用 `allkeys-*`）。异步复制下的故障切换可能丢失最近的写入。

如果集合的权威数据在数据库中，不要用 `SetCache`。声明为 `Cache<K, Set<E>>`，更新数据库后 `invalidate`。

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
}
```

Java 中实现同一个接口，调用 `spec.updatePolicy(p -> p.absentTtl(Duration.ofSeconds(30)))`。

定制器按 Spring 的顺序执行，位于声明之后、`cocache.*` 配置项之前（见[优先级](./configuration.md#precedence)）。要修改集合缓存，覆盖 `customize(SetCacheSpec.Builder)`。

## 启动校验 {#validation}

CoCache 宁可启动失败，也不运行一个无法按声明工作的缓存。每条错误信息都带有缓存名。

| 启动失败的情形 | 原因 |
|----------------|------|
| 接口没有具体的 `Cache<K, V>` 类型参数，或 spec bean 的返回类型没有写出泛型 | key、loader 与编解码都由这些类型解析 |
| 接口的抽象方法对应不上任何 `Cache` 方法 | 否则调用会在运行时失败 |
| 匹配到多个 `CacheLoader<K, V>` 或 `Codec<V>` bean | 静默挑一个可能用错数据源或格式 |
| `SetCache` 上设置了 `loader` | 集合缓存没有数据源，未命中会悄悄返回空集合 |
| 在 `ttl` / `expireAfterAccess` 之外的时长属性上用 `FOREVER`，时长为 0（`ttlJitter`、`refreshWindow` 除外），或任何时长为负数 | 无意义或不安全的寿命 |
| `refreshWindow` 不小于 `ttl − ttlJitter`（按实际抖动计算），或不小于 `localMaxAge` | 新副本一出生就在窗口内，每次命中都会触发刷新 |
| 缓存名、namespace 或 bean 名重复 | 两个缓存会共享 key 或失效消息 |
| `cocache.caches.[name]` 指向不存在的缓存，或使用了旧大版本中不再支持的配置名（见[从 5.x 升级](./migration-6.md#properties)） | 几乎总是拼写错误；见[配置参考](./configuration.md#validation) |

key 类型的 `toString()` 检查发生在每种 key 类型第一次读取时，而不是启动时。
