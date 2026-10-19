---
title: Quick Start
description: Add CoCache to a Spring Boot application — dependency, cache declaration, data source, first read, and composing two caches.
---

# Quick Start

This page takes a Spring Boot application from no cache to a working two-level cache. The code comes from [`cocache-example`](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example).

**You need:** JDK 17+, Spring Boot 4.x, and Redis 7+ (or Valkey 7.2+).

## 1. Add the dependencies

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

The starter brings `cocache-redis` (a native Lettuce backend), `cocache-codec-jackson`, and `cocache-spring`. To manage the versions of all CoCache modules in one place, import the BOM `me.ahoo.cocache:cocache-bom`.

## 2. Point Spring at Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

CoCache reads Spring Boot's Redis connection details (standalone, Sentinel, or Cluster, with username, password, and SSL bundle) and builds **its own** Lettuce client from them. It never reuses or reconfigures your application's Redis connection factory. See [Redis connection](./configuration.md#redis-connection).

## 3. Declare a cache

A cache is an interface that extends `Cache<K, V>`:

```kotlin
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.annotation.CaffeineCache
import me.ahoo.cocache.api.annotation.CoCache

@CoCache(ttl = 120)                         // seconds; defaults: ttl 3600, ttlJitter 60, absentTtl 60, localMaxAge 300
@CaffeineCache(maximumSize = 1_000_000)     // optional L2 sizing; default maximumSize 10000
interface UserCache : Cache<String, User>
```

Register it with `@EnableCoCache`:

```kotlin
import me.ahoo.cocache.spring.boot.EnableCoCache

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class AppServer
```

The cache's name is the interface's simple name (`UserCache`) unless `@CoCache(name = …)` sets one. The bean has the same name and the interface as its type. Keys and values go to Redis as `cocache:UserCache:{key}`, and values are JSON-encoded with your application's Jackson `ObjectMapper` unless a `Codec<User>` bean exists.

## 4. Load from your data source

On a miss in both L2 and L1, CoCache calls the cache's `CacheLoader`. Declare one as a bean:

```kotlin
@Configuration
class UserCacheConfiguration {
    @Bean
    fun userLoader(userRepository: UserRepository): CacheLoader<String, User> =
        CacheLoader { id -> userRepository.findById(id) }   // null = "not found": a negative entry for absentTtl
}
```

CoCache finds this bean by its generic type `CacheLoader<String, User>`. If several beans match, startup fails. Name the right one with `@CoCache(loader = "userLoader")`. Without any loader, a miss returns `null` and nothing is written to Redis, so the cache is filled only by `put`.

The loader only loads. How long a value lives is decided by the cache's policy (`ttl` with jitter), or per entry by a [`ttlOf` function](./configuration.md#per-entry-ttl). The loader always runs on the calling thread, so transactions, security context, and MDC are available.

Batch reads (`getAll`) call `loadAll(keys)` once with every key that missed L2 and L1. By default it calls `load` per key. Override it to load them in one query:

```kotlin
object : CacheLoader<String, User> {
    override fun load(key: String): User? = userRepository.findById(key)

    // Keys missing from the result are "not found" and get negative entries
    override fun loadAll(keys: Collection<String>): Map<String, User> =
        userRepository.findAllById(keys).associateBy { it.id }
}
```

## 5. Use it

Inject the interface like any other bean:

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
        userRepository.rename(id, name)                    // 1. update the source of truth
        invalidator.invalidate(userCache, id)              // 2. invalidate after the commit
    }
}
```

| Call | Effect |
|------|--------|
| `cache[key]` | The value, or `null` if absent or negative |
| `cache.get(key, loader)` | Like `cache[key]`, but a miss loads with the given loader instead of the cache's own. The load is protected the same way. |
| `cache.getAll(keys)` | Present values by key. Reads L2, then the remaining keys from Redis in one batch, then loads the rest with one `loadAll` call. Absent and negative keys are left out. |
| `cache.lookup(key)` | `Lookup.Present(value, expiresAt)` or `Lookup.Absent(expiresAt)`, when you need to tell "confirmed absent" apart |
| `cache.put(key, value)` | Writes L1 with the cache's TTL policy and notifies peers. Doesn't fill L2: the next read fills it from L1. Throws `CacheWriteException` if Redis fails. |
| `cache.put(key, value, ttl)` | Same, with an explicit `java.time.Duration` |
| `cache.invalidate(key)` | Removes the key from L2 and L1 and notifies peers. If Redis fails, the invalidation is retried in the background. |
| `cache.invalidateAll(keys)` | Same, for several keys |

**Update the database first, then invalidate.** If you invalidate first, a concurrent read can reload the old row before your update commits. Inside a transaction, use `TransactionalInvalidator`: it defers the invalidation to `afterCommit`, merges repeated invalidations of one cache into one `invalidateAll`, and skips it on rollback. Outside a transaction it invalidates immediately.

Prefer `invalidate` over `put`. Use `put` only when the writer computes the final value itself.

## Without annotations: a spec bean {#spec-bean}

You can declare a cache as a `CacheSpec` bean instead of an interface. The starter registers a `Cache<K, V>` bean for it, named after the spec bean minus its `Spec` suffix:

```kotlin
@Bean
fun productSpec(): CacheSpec<String, String> = cacheSpec("product") {
    codec = StringCodec
    loader = CacheLoader { id -> "product-$id" }
    policy { ttl = Duration.ofMinutes(10) }
}

// inject it as Cache<String, String>, bean name "product"
class ProductService(@Qualifier("product") private val productCache: Cache<String, String>)
```

The factory method must declare its generic return type (`CacheSpec<String, String>`), because CoCache reads `K` and `V` from it without instantiating the spec. A spec bean is injected as the engine itself, with no proxy in between. See [Building a cache in code](./configuration.md#building-a-cache-in-code).

## Composing two caches {#composing-caches}

A read model built from two caches is two batch reads. For example, extended user info that refers to a user:

```kotlin
@CoCache
interface UserExtendInfoCache : Cache<String, UserExtendInfo>   // UserExtendInfo(id, userId)

fun extendInfos(ids: List<String>): List<Pair<UserExtendInfo, User?>> {
    val infos = userExtendInfoCache.getAll(ids)
    val users = userCache.getAll(infos.values.map { it.userId }.distinct())
    return infos.values.map { it to users[it.userId] }
}
```

Each `getAll` reads L2, then Redis in one batch, then the source in one `loadAll`, and each cache keeps its own TTL and invalidation. This replaces the JoinCache of 5.x.

## Next steps

- [Configuration](./configuration.md): choose TTLs, sizes, and failure behavior.
- [Spring Cache](./spring-cache.md): use CoCache behind `@Cacheable`.
- [Operations](./operations.md): actuator endpoint, metrics, and Redis failure behavior.
