---
title: Quick Start
description: Add CoCache to a Spring Boot application — dependency, cache interface, data source, and first read.
---

# Quick Start

This page takes a Spring Boot application from no cache to a working two-level cache. The code comes from [`cocache-example`](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example).

**You need:** JDK 17+, Spring Boot 4.x, and a Redis server.

## 1. Add the dependencies

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

To manage the versions of all CoCache modules in one place, import the BOM `me.ahoo.cocache:cocache-bom`.

## 2. Point Spring at Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

## 3. Declare a cache

A cache is an interface that extends `Cache<K, V>`. CoCache generates the implementation at startup.

```kotlin
@CoCache(keyPrefix = "user:", ttl = 120)          // seconds; defaults: ttl 3600, ttlAmplitude 60, missingTtl 60
@CaffeineCache(maximumSize = 1_000_000)          // optional L2 settings; default maximumSize 10000
interface UserCache : Cache<String, User>
```

Register it with `@EnableCoCache`:

```kotlin
@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class AppServer
```

The cache's name is the interface's simple name (`UserCache`) unless `@CoCache(name = …)` sets one. The name scopes the eviction channel and the bean names of per-cache components.

## 4. Load from your data source

On a miss in both L2 and L1, CoCache calls the cache's `CacheSource`. Declare one as a bean:

```kotlin
@Configuration
class UserCacheConfiguration {
    @Bean
    fun userCacheSource(userRepository: UserRepository): CacheSource<String, User> =
        CacheSource { id ->
            // null = "not found": CoCache stores a negative entry for missingTtl seconds
            userRepository.findById(id)?.let { CacheValue.of(it, TtlAt.at(120)) }
        }
}
```

CoCache finds this bean by its generic type `CacheSource<String, User>`. If several caches share key and value types, name the bean `UserCache.CacheSource` instead. Without any source, misses return nothing (`CacheSource.noOp()`).

The `ttlAt` you return is used as-is. Use `TtlAt.at(seconds)` for a relative TTL, or `CacheValue.forever(value)` for no expiry.

## 5. Use it

Inject the interface like any other bean:

```kotlin
@Service
class UserService(
    private val userCache: UserCache,
    private val userRepository: UserRepository,
) {
    fun find(id: String): User? = userCache[id]   // L2 → L1 → CacheSource

    fun rename(id: String, name: String) {
        userRepository.rename(id, name)           // 1. update the source of truth
        userCache.evict(id)                       // 2. then evict; every instance drops its copy
    }
}
```

| Call | Effect |
|------|--------|
| `cache[key]` | The value, or `null` if absent, negative, or expired |
| `cache.getCache(key)` | The entry itself: `PresentValue(value, ttlAt)` or `MissingValue(ttlAt)` |
| `cache.getTtlAt(key)` | The value's absolute expiry in epoch seconds, or `null` |
| `cache[key] = value` | Writes L1 and L2 with the cache's TTL policy and notifies peers. `null` writes a negative entry. |
| `cache[key, ttlAt] = value` | Same, with an explicit absolute expiry |
| `cache.evict(key)` | Removes the key from L2 and L1 and notifies peers |

**Update the database first, then evict.** If you evict first, a concurrent read can reload the old row before your update commits.

## Next steps

- [Configuration](./configuration.md): choose TTLs and override per-cache components.
- [Operations](./operations.md): actuator endpoints and Redis failure behavior.
- [JoinCache](./join-cache.md) and [Spring Cache](./spring-cache.md) integration.
