---
name: cocache
description: Use when building or modifying Java/Kotlin applications with CoCache two-level distributed coherent caching. Invoke for @CoCache cache interfaces, @JoinCacheable composition, Redis-backed coherence, Spring Boot integration, cache proxy behavior, custom cache backends, cache breakdown protection, or CoCache TCK tests.
---

# CoCache Development Guide

CoCache is a Java/Kotlin two-level distributed coherent cache framework:
- L2 client-side cache: local Map, Guava, or Caffeine cache.
- L1 distributed cache: Redis-backed shared cache.
- Coherence: `CacheEvictedEventBus` publishes evictions so peer instances invalidate local entries.

## Start Here

Choose the smallest reference that fits the request:

| Task | Read |
|------|------|
| Add CoCache to a Spring or Spring Boot app | `references/setup.md` |
| Compose cached values with `@JoinCacheable` | `references/join-cache.md` |
| Write or update tests | `references/testing.md` |
| Implement a custom L1/L2 cache, event bus, key converter, or source | `references/custom-implementation.md` |

## Repository Rules

When editing this repository, follow `AGENTS.md`:
- Use `me.ahoo.test.asserts.assert` and `.assert()` in Kotlin tests; do not use AssertJ `assertThat()`.
- Extend the TCK specs in `cocache-test` for cache implementations.
- Ask before changing `cocache-api` public interfaces or adding dependencies.
- Run the relevant Gradle checks before finishing; prefer `./gradlew check` for broad changes.

## Core Model

Define one cache interface per cache domain. The interface extends `Cache<K, V>`, is annotated with `@CoCache`, and is registered through `@EnableCoCache`. CoCache creates a proxy at runtime.

```kotlin
@CoCache(keyPrefix = "user:", ttl = 120)
@GuavaCache(maximumSize = 1_000_000, expireAfterAccess = 120, expireUnit = TimeUnit.SECONDS)
interface UserCache : Cache<String, User>

@SpringBootApplication
@EnableCoCache(caches = [UserCache::class])
class App
```

Use caches through Kotlin operators: `cache[key]`, `cache[key] = value`, `cache.evict(key)`, and `cache.getCache(key)` when `CacheValue` metadata is required.

## Extension Points

CoCache auto-configures defaults, but each component can be overridden:
- Per cache, define named beans such as `UserCache.CacheSource`, `UserCache.ClientSideCache`, `UserCache.KeyConverter`, or `UserCache.JoinKeyExtractor`.
- Globally, define beans by type; auto-configured beans use `@ConditionalOnMissingBean`.
- For data loading, implement `CacheSource<K, V>.loadCacheValue(key)` and return `DefaultCacheValue.forever(value)`, `DefaultCacheValue.ttlAt(value, ttl)`, or bounded `DefaultCacheValue.missingGuard(ttl, amplitude)` values.

## Testing Pattern

CoCache provides abstract specs in `cocache-test` for compatibility coverage:
- `CacheSpec<K,V>` for base cache behavior.
- `ClientSideCacheSpec<V>` for L2 caches.
- `DistributedCacheSpec<V>` for L1 caches.
- `DefaultCoherentCacheSpec<K,V>` for two-level coherent cache behavior and cache breakdown protection.
- `MultipleInstanceSyncSpec<K,V>` and `CacheEvictedEventBusSpec` for cross-instance coherence and event buses.

Use the repo's `createCacheEntry(): Pair<K, V>` contract:

```kotlin
class MyDistributedCacheTest : DistributedCacheSpec<String>() {
    override fun createCache(): DistributedCache<String> {
        return MyDistributedCache()
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to "test_value"
    }
}
```

## Key Classes

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

## Build Commands

```bash
./gradlew build -x test
./gradlew test
./gradlew :cocache-core:test
./gradlew :cocache-core:test --tests "me.ahoo.cache.proxy.ProxyCacheTest"
./gradlew :cocache-spring-redis:check
./gradlew :cocache-spring-boot-starter:check
./gradlew check
```
