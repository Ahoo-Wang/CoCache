---
name: cocache
description: Use when building Spring Boot applications with CoCache distributed caching. Invoke whenever the user mentions CoCache, coherent cache, two-level cache, L2 cache, distributed cache coherence, @CoCache, @JoinCacheable, cache proxy, or wants to set up caching with Redis + local cache in a Java/Kotlin project. Also triggers on cache stampede prevention, cache breakdown protection, or event-driven cache invalidation.
---

# CoCache Development Guide

CoCache is a Level 2 Distributed Coherence Cache Framework for Java/Kotlin. It provides a two-level caching architecture:
- **L2 (Client-side)**: Local in-memory cache (Map/Guava/Caffeine)
- **L1 (Distributed)**: Shared cache layer (Redis)

Cache coherence is maintained via an event bus that publishes `CacheEvictedEvent` when entries change, invalidating local caches across all instances.

## Quick Start

To add CoCache to a Spring Boot project, read `references/setup.md`.

## Core Concept: Cache Interfaces

CoCache uses a proxy-based approach. You define a **cache interface** extending `Cache<K, V>`, annotate it, and CoCache generates the implementation at runtime. This is the primary way to use the library.

### Creating a Cache Interface

```kotlin
@CoCache(keyPrefix = "user:", ttl = 120)
@GuavaCache(maximumSize = 1_000_000, expireAfterAccess = 120, expireUnit = TimeUnit.SECONDS)
interface UserCache : Cache<String, User>
```

Then register it:

```kotlin
@SpringBootApplication
@EnableCoCache(caches = [UserCache::class])
class App
```

The proxy object injected into your code is **both** the `UserCache` interface and a `CoherentCache`, so you can cast to `CoherentCache` if you need access to internals like `clientSideCache.size`.

### Using a Cache

```kotlin
@Service
class UserService(@Qualifier("userCache") private val userCache: UserCache) {

    fun getUser(id: String): User? {
        return userCache[id]              // CacheGetter operator
    }

    fun saveUser(user: User) {
        userCache[user.id] = user         // CacheSetter operator
    }

    fun deleteUser(id: String) {
        userCache.evict(id)
    }
}
```

### @CoCache Annotation Reference

| Property | Default | Description |
|----------|---------|-------------|
| `name` | interface simpleName | Cache bean name |
| `keyPrefix` | `""` | Prefix prepended to all cache keys |
| `keyExpression` | `""` | SpEL expression for key derivation |
| `ttl` | `Long.MAX_VALUE` | TTL in seconds (forever by default) |
| `ttlAmplitude` | `10` | Random TTL variance to prevent thundering herd |

### @GuavaCache / @CaffeineCache Annotation Reference

| Property | Default | Description |
|----------|---------|-------------|
| `initialCapacity` | `-1` (unset) | Initial capacity |
| `maximumSize` | `-1` (unset) | Maximum entries |
| `expireUnit` | `SECONDS` | Time unit for expiry values |
| `expireAfterWrite` | `-1` (unset) | Expire after write duration |
| `expireAfterAccess` | `-1` (unset) | Expire after access duration |
| `concurrencyLevel` | `-1` (Guava only) | Concurrency level |

## Customizing Cache Behavior

CoCache auto-configures defaults, but every component can be overridden via Spring beans.

### Per-Cache Bean Naming Convention

For a cache named `UserCache`, define beans with these names:

```kotlin
@Bean("UserCache.CacheSource")
fun userCacheSource(): CacheSource<String, User> {
    return CacheSource { key ->
        val user = userRepository.findById(key).orElse(null)
        user?.let { DefaultCacheValue(it) }
    }
}

@Bean("UserCache.ClientSideCache")
fun userClientSideCache(): ClientSideCache<User> {
    return CaffeineClientSideCache(
        maximumSize = 500_000,
        expireAfterAccess = Duration.ofSeconds(60)
    )
}
```

Available suffixes: `.ClientSideCache`, `.CacheSource`, `.KeyConverter`, `.JoinKeyExtractor`

### Global Bean Override

Define beans by type to override defaults for all caches:

```kotlin
@Bean
fun cacheEvictedEventBus(): CacheEvictedEventBus {
    return GuavaCacheEvictedEventBus()  // single-instance, no Redis needed
}
```

All auto-configured beans use `@ConditionalOnMissingBean`, so any bean you define takes precedence.

## JoinCache: Composing Cached Values

JoinCache composes two independent caches into one logical view. Read `references/join-cache.md` for the full guide.

**Quick example:**

```kotlin
@CoCache(keyPrefix = "user:", ttl = 120)
interface UserCache : Cache<String, User>

@CoCache(keyPrefix = "user_ext:", ttl = 120)
interface UserExtendInfoCache : Cache<String, UserExtendInfo>

@JoinCacheable(
    firstCacheName = "UserExtendInfoCache",
    joinCacheName = "UserCache",
    joinKeyExpression = "#{#root.userId}"
)
interface UserExtendInfoJoinCache : JoinCache<String, UserExtendInfo, String, User>
```

When you call `userExtendInfoJoinCache.get("extInfo123")`:
1. Gets `UserExtendInfo` from `UserExtendInfoCache`
2. Evaluates `#{#root.userId}` to extract the userId
3. Gets `User` from `UserCache` by that userId
4. Returns `JoinValue<UserExtendInfo, String, User>`

## Writing Tests

CoCache provides abstract test specs in `cocache-test` that verify cache contracts. Read `references/testing.md` for the full guide.

**Key pattern:** Extend the appropriate spec and implement factory methods:

```kotlin
class MyDistributedCacheTest : DistributedCacheSpec<String>() {
    override fun createCache(): DistributedCache<String> {
        return MyDistributedCache()
    }

    override fun createCacheEntry(): CacheValue<String> {
        return DefaultCacheValue("test_value")
    }
}
```

## Creating Custom Implementations

To implement a custom `ClientSideCache`, `DistributedCache`, or `CacheEvictedEventBus`, read `references/custom-implementation.md`.

## Spring Cache Bridge

CoCache integrates with Spring's `@Cacheable` abstraction via `cocache-spring-cache`. When `@EnableCaching` is present, a `CoCacheManager` is auto-configured, allowing:

```kotlin
@Cacheable(cacheNames = ["userCache"])
fun getUser(id: String): User? { ... }
```

This routes through CoCache's two-level cache automatically.

## Actuator Endpoints

When Spring Actuator is on the classpath:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/actuator/cocache` | GET | List all coherent caches |
| `/actuator/cocache/{name}` | GET | Cache stats |
| `/actuator/cocache/{name}/{key}` | GET | Get a cache entry |
| `/actuator/cocache/{name}/{key}` | DELETE | Evict a cache entry |
| `/actuator/cocacheClient` | GET | Client-side cache info |

## Build Commands

```bash
# Build without tests
./gradlew build -x test

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :cocache-core:test

# Run a single test class
./gradlew :cocache-core:test --tests "me.ahoo.cache.proxy.ProxyCacheTest"

# Run integration tests (requires Redis)
./gradlew :cocache-spring-redis:check
./gradlew :cocache-spring-boot-starter:check
```

## Key Classes Reference

| Class | Module | Purpose |
|-------|--------|---------|
| `Cache<K,V>` | cocache-api | Base cache interface |
| `CoherentCache<K,V>` | cocache-core | Two-level cache engine |
| `DefaultCoherentCache` | cocache-core | Default implementation |
| `ClientSideCache<V>` | cocache-api | L2 local cache interface |
| `MapClientSideCache` | cocache-core | ConcurrentHashMap impl |
| `GuavaClientSideCache` | cocache-core | Guava Cache impl |
| `CaffeineClientSideCache` | cocache-core | Caffeine Cache impl |
| `DistributedCache<V>` | cocache-core | L1 distributed cache interface |
| `RedisDistributedCache` | cocache-spring-redis | Redis impl |
| `CacheSource<K,V>` | cocache-api | Data source loader |
| `CacheEvictedEventBus` | cocache-core | Event bus for coherence |
| `RedisCacheEvictedEventBus` | cocache-spring-redis | Redis Pub/Sub impl |
| `JoinCache<K1,V1,K2,V2>` | cocache-api | Composed cache interface |
| `SimpleJoinCache` | cocache-core | Default JoinCache impl |
| `KeyFilter` | cocache-core | Bloom filter for cache breakdown protection |
| `BloomKeyFilter` | cocache-core | Guava BloomFilter impl |
