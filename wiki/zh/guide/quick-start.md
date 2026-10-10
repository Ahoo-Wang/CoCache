---
title: 快速上手
description: 把 CoCache 接入 Spring Boot 应用：依赖、缓存接口、数据源与第一次读取。
---

# 快速上手

本页把一个 Spring Boot 应用从没有缓存带到可用的二级缓存。代码取自 [`cocache-example`](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example)。

**前置条件：** JDK 17+、Spring Boot 4.x、一个 Redis 服务。

## 1. 添加依赖

::: code-group

```kotlin [Gradle (Kotlin)]
dependencies {
    implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.0.1")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
}
```

```groovy [Gradle (Groovy)]
dependencies {
    implementation 'me.ahoo.cocache:cocache-spring-boot-starter:5.0.1'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
}
```

```xml [Maven]
<dependency>
    <groupId>me.ahoo.cocache</groupId>
    <artifactId>cocache-spring-boot-starter</artifactId>
    <version>5.0.1</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

:::

如需统一管理所有 CoCache 模块的版本，可导入 BOM `me.ahoo.cocache:cocache-bom`。

## 2. 配置 Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

## 3. 声明缓存

缓存是一个继承 `Cache<K, V>` 的接口，CoCache 在启动时生成其实现。

```kotlin
@CoCache(keyPrefix = "user:", ttl = 120)          // 单位秒；默认 ttl 3600、ttlAmplitude 60、missingTtl 60
@CaffeineCache(maximumSize = 1_000_000)          // 可选的 L2 配置；默认 maximumSize 10000
interface UserCache : Cache<String, User>
```

用 `@EnableCoCache` 注册：

```kotlin
@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class AppServer
```

缓存名默认为接口简单类名（`UserCache`），也可用 `@CoCache(name = …)` 指定。缓存名决定淘汰事件的频道，以及按缓存定制组件时的 bean 名。

## 4. 从数据源加载

L2、L1 都未命中时，CoCache 调用该缓存的 `CacheSource`。把它声明为 bean：

```kotlin
@Configuration
class UserCacheConfiguration {
    @Bean
    fun userCacheSource(userRepository: UserRepository): CacheSource<String, User> =
        CacheSource { id ->
            // null 表示“不存在”：CoCache 写入负缓存，保留 missingTtl 秒
            userRepository.findById(id)?.let { CacheValue.of(it, TtlAt.at(120)) }
        }
}
```

CoCache 按泛型类型 `CacheSource<String, User>` 找到这个 bean。若多个缓存的 key、value 类型相同，请改用 bean 名 `UserCache.CacheSource`。没有任何数据源时，未命中返回空（`CacheSource.noOp()`）。

返回的 `ttlAt` 会原样使用：相对 TTL 用 `TtlAt.at(seconds)`，永不过期用 `CacheValue.forever(value)`。

## 5. 使用

像注入其它 bean 一样注入接口：

```kotlin
@Service
class UserService(
    private val userCache: UserCache,
    private val userRepository: UserRepository,
) {
    fun find(id: String): User? = userCache[id]   // L2 → L1 → CacheSource

    fun rename(id: String, name: String) {
        userRepository.rename(id, name)           // 1. 先更新数据源
        userCache.evict(id)                       // 2. 再淘汰；所有实例都会丢弃副本
    }
}
```

| 调用 | 效果 |
|------|------|
| `cache[key]` | 返回值；不存在、负缓存或已过期时为 `null` |
| `cache.getCache(key)` | 返回条目本身：`PresentValue(value, ttlAt)` 或 `MissingValue(ttlAt)` |
| `cache.getTtlAt(key)` | 值的绝对到期时间（纪元秒），或 `null` |
| `cache[key] = value` | 按缓存的 TTL 策略写入 L1 与 L2 并通知其它实例；`null` 写入负缓存 |
| `cache[key, ttlAt] = value` | 同上，使用显式的绝对到期时间 |
| `cache.evict(key)` | 从 L2、L1 删除该 key 并通知其它实例 |

**先更新数据库，再淘汰缓存。** 先淘汰的话，并发读取可能在更新提交前重新加载旧数据。

## 下一步

- [配置](./configuration.md)：选择 TTL，按缓存覆盖组件。
- [运维](./operations.md)：Actuator 端点与 Redis 故障行为。
- [JoinCache](./join-cache.md) 与 [Spring Cache](./spring-cache.md) 集成。
