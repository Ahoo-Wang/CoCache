---
title: 快速上手
description: 在 Spring Boot 应用中接入 CoCache：依赖、缓存声明、数据源、第一次读取，以及组合两个缓存。
---

# 快速上手

本页把一个 Spring Boot 应用从没有缓存带到可用的二级缓存。代码取自 [`cocache-example`](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example)。

**前提：** JDK 17+、Spring Boot 4.x、Redis 7+（或 Valkey 7.2+）。

## 1. 添加依赖

::: code-group

```kotlin [Gradle (Kotlin)]
dependencies {
    implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.4.0")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
}
```

```groovy [Gradle (Groovy)]
dependencies {
    implementation 'me.ahoo.cocache:cocache-spring-boot-starter:5.4.0'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
}
```

```xml [Maven]
<dependency>
    <groupId>me.ahoo.cocache</groupId>
    <artifactId>cocache-spring-boot-starter</artifactId>
    <version>5.4.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

:::

starter 会引入 `cocache-redis`（原生 Lettuce 后端）、`cocache-codec-jackson` 与 `cocache-spring`。要统一管理所有 CoCache 模块的版本，导入 BOM `me.ahoo.cocache:cocache-bom`。

## 2. 配置 Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

CoCache 读取 Spring Boot 的 Redis 连接信息（单机、Sentinel 或 Cluster，以及用户名、密码与 SSL bundle），并据此创建**自己的** Lettuce 客户端，从不复用或改动应用自己的 Redis 连接工厂。见 [Redis 连接](./configuration.md#redis-connection)。

## 3. 声明缓存

缓存是一个继承 `Cache<K, V>` 的接口：

```kotlin
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.annotation.CaffeineCache
import me.ahoo.cocache.api.annotation.CoCache

@CoCache(ttl = 120)                         // 单位为秒；默认值：ttl 3600、ttlJitter 60、absentTtl 60、localMaxAge 300
@CaffeineCache(maximumSize = 1_000_000)     // 可选的 L2 容量；默认 maximumSize 10000
interface UserCache : Cache<String, User>
```

用 `@EnableCoCache` 注册：

```kotlin
import me.ahoo.cocache.spring.boot.EnableCoCache

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class AppServer
```

缓存名默认是接口的简单类名（`UserCache`），可以用 `@CoCache(name = …)` 指定。bean 与缓存同名，类型就是这个接口。值在 Redis 中的 key 是 `cocache:UserCache:{key}`；除非存在 `Codec<User>` bean，值用应用的 Jackson `ObjectMapper` 编码为 JSON。

## 4. 从数据源加载

L2 与 L1 都未命中时，CoCache 调用缓存的 `CacheLoader`。把它声明为 bean：

```kotlin
@Configuration
class UserCacheConfiguration {
    @Bean
    fun userLoader(userRepository: UserRepository): CacheLoader<String, User> =
        CacheLoader { id -> userRepository.findById(id) }   // null 表示“不存在”：写入负缓存，存活 absentTtl
}
```

CoCache 按泛型类型 `CacheLoader<String, User>` 找到这个 bean。匹配到多个时启动失败，此时用 `@CoCache(loader = "userLoader")` 指定。没有任何 loader 时，未命中返回 `null`，也不写入 Redis，缓存只能通过 `put` 填充。

loader 只负责加载。值的寿命由缓存的策略（`ttl` 加抖动）决定，也可以用 [`ttlOf` 函数](./configuration.md#per-entry-ttl)按条目决定。loader 总是在调用方线程上执行，所以事务、安全上下文和 MDC 都可用。

批量读取（`getAll`）把 L2 与 L1 都未命中的 key 一次性交给 `loadAll(keys)`。默认实现逐个调用 `load`，应覆盖为一次查询：

```kotlin
object : CacheLoader<String, User> {
    override fun load(key: String): User? = userRepository.findById(key)

    // 结果中缺少的 key 视为“不存在”，写入负缓存
    override fun loadAll(keys: Collection<String>): Map<String, User> =
        userRepository.findAllById(keys).associateBy { it.id }
}
```

## 5. 使用

像注入其它 bean 一样注入接口：

```kotlin
@Service
class UserService(
    private val userCache: UserCache,
    private val userRepository: UserRepository,
    private val invalidator: TransactionalInvalidator,
) {
    fun find(id: String): User? = userCache[id]            // L2 → L1 → CacheLoader

    @Transactional
    fun rename(id: String, name: String) {
        userRepository.rename(id, name)                    // 1. 先更新权威数据
        invalidator.invalidate(userCache, id)              // 2. 提交之后失效
    }
}
```

| 调用 | 效果 |
|------|------|
| `cache[key]` | 值；不存在或为负缓存时返回 `null` |
| `cache.get(key, loader)` | 同 `cache[key]`，但未命中时用给定的 loader 代替缓存自己的 loader 回源，受同样的保护 |
| `cache.getAll(keys)` | 存在的值（按 key）。先读 L2，剩余的 key 一次批量读 Redis，再用一次 `loadAll` 加载其余的 key。不存在与负缓存的 key 不在结果中 |
| `cache.lookup(key)` | `Lookup.Present(value, expiresAt)` 或 `Lookup.Absent(expiresAt)`，用于区分“确认不存在” |
| `cache.put(key, value)` | 按缓存的 TTL 策略写入 L1 并通知其它实例。不填充 L2，下一次读取从 L1 填充。Redis 失败时抛出 `CacheWriteException` |
| `cache.put(key, value, ttl)` | 同上，显式指定 `java.time.Duration` |
| `cache.invalidate(key)` | 从 L2 与 L1 删除 key 并通知其它实例。Redis 失败时在后台重试 |
| `cache.invalidateAll(keys)` | 同上，作用于多个 key |

**先更新数据库，再失效。** 如果先失效，并发的读取可能在更新提交之前重新加载旧数据。在事务内使用 `TransactionalInvalidator`：它把失效推迟到 `afterCommit`，把同一缓存的多次失效合并为一次 `invalidateAll`，回滚时不失效；不在事务内时立即失效。

优先使用 `invalidate` 而不是 `put`。只有写入方自己算出最终值时才用 `put`。

## 不用注解：spec bean {#spec-bean}

也可以不写接口，把缓存声明为 `CacheSpec` bean。starter 为它注册一个 `Cache<K, V>` bean，bean 名为 spec bean 名去掉 `Spec` 后缀：

```kotlin
@Bean
fun productSpec(): CacheSpec<String, String> = cacheSpec("product") {
    codec = StringCodec
    loader = CacheLoader { id -> "product-$id" }
    policy { ttl = Duration.ofMinutes(10) }
}

// 以 Cache<String, String> 注入，bean 名为 "product"
class ProductService(@Qualifier("product") private val productCache: Cache<String, String>)
```

工厂方法必须声明泛型返回类型（`CacheSpec<String, String>`）：CoCache 从中读取 `K` 与 `V`，不实例化 spec。spec bean 注入的就是引擎本身，中间没有代理。见[用代码构建缓存](./configuration.md#building-a-cache-in-code)。

## 组合两个缓存 {#composing-caches}

由两个缓存组成的读模型就是两次批量读取。例如引用了用户的用户扩展信息：

```kotlin
@CoCache
interface UserExtendInfoCache : Cache<String, UserExtendInfo>   // UserExtendInfo(id, userId)

fun extendInfos(ids: List<String>): List<Pair<UserExtendInfo, User?>> {
    val infos = userExtendInfoCache.getAll(ids)
    val users = userCache.getAll(infos.values.map { it.userId }.distinct())
    return infos.values.map { it to users[it.userId] }
}
```

每次 `getAll` 都先读 L2，再一次批量读 Redis，最后一次 `loadAll` 回源；两个缓存各自保持自己的 TTL 与失效。这取代了 5.x 的 JoinCache。

## 下一步

- [配置](./configuration.md)：选择 TTL、容量与故障行为。
- [Spring Cache](./spring-cache.md)：在 `@Cacheable` 背后使用 CoCache。
- [运维](./operations.md)：Actuator 端点、指标与 Redis 故障行为。
