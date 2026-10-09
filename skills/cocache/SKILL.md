---
name: cocache
description: Use when building or modifying Java/Kotlin applications with CoCache two-level distributed coherent caching. Invoke for @CoCache cache interfaces, @JoinCacheable composition, Redis-backed coherence, Spring Boot integration, cache proxy behavior, custom cache backends (CacheStore, CacheEvictedEventBus), negative caching (MissingValue, missingTtl), cache breakdown protection, TTL choices, Redis failure policy (strict-failure, missing-guard sentinel), Redis TTL-drift test failures, migrating from CoCache 4.x, or CoCache TCK tests.
---

# CoCache Development Guide

CoCache (5.x) is a Java/Kotlin two-level distributed coherent cache framework:
- L2 client-side cache: bounded local Caffeine store (one per instance).
- L1 distributed cache: shared Redis store.
- Coherence: `CacheEvictedEventBus` publishes evictions so peer instances drop local copies. Write-backs are guarded against concurrent invalidation, and L2 is cleared whenever the event channel (re)subscribes.

Design goals and invariants are documented in `docs/architecture.md` of the CoCache repository.

## Start Here

Choose the smallest reference that fits the request:

| Task | Read |
|------|------|
| Add CoCache to a Spring or Spring Boot app; configure TTLs, Redis failure behavior or the missing-guard sentinel | `references/setup.md` |
| Compose cached values with `@JoinCacheable` | `references/join-cache.md` |
| Write or update tests | `references/testing.md` |
| Implement a custom L1/L2 store, event bus, key converter, or source | `references/custom-implementation.md` |

## Repository Rules

When editing the CoCache repository, follow `AGENTS.md`:
- Use `me.ahoo.test.asserts.assert` and `.assert()` in Kotlin tests; do not use AssertJ `assertThat()`.
- Extend the TCK specs in `cocache-test` for new implementations; every fixed defect gets a reproducing test.
- Ask before changing `cocache-api` public interfaces, Redis wire formats, or adding dependencies.
- Update `docs/architecture.md` when changing coherence, storage, codec, or proxy behavior.
- Run `./gradlew check` before finishing broad changes.

## Core Model

Define one cache interface per cache domain. It extends `Cache<K, V>`, carries `@CoCache`, and is registered through `@EnableCoCache`. CoCache implements it with a proxy at runtime.

```kotlin
// ttl / ttlAmplitude / missingTtl are seconds; defaults 3600 / 60 / 60
@CoCache(keyPrefix = "user:", ttl = 120)
@CaffeineCache(maximumSize = 1_000_000, expireAfterAccess = 120)   // optional; L2 is always bounded
interface UserCache : Cache<String, User>

@SpringBootApplication
@EnableCoCache(caches = [UserCache::class])
class App
```

Operations: `cache[key]`, `cache[key] = value` (uses the cache's TTL policy; `null` stores a negative entry), `cache.evict(key)`, and `cache.getCache(key)` when you need the `CacheValue` (`PresentValue` or `MissingValue`, with `ttlAt`).

Write pattern: **update the data source first, then `evict(key)`**.

## Extension Points

- Per cache, define beans named `UserCache.ClientSideCache`, `UserCache.DistributedCache`, `UserCache.KeyConverter`, `UserCache.CacheSource`, or `UserCache.JoinKeyExtractor`. Stateful components (`ClientSideCache`, `DistributedCache`, `KeyConverter`) are resolved **by name only**. A `CacheSource` / `JoinKeyExtractor` may also be matched by a unique bean of its generic type.
- Globally, define beans by type; auto-configured beans use `@ConditionalOnMissingBean`.
- Data loading: `CacheSource<K, V>` is a `fun interface`. Return `CacheValue.forever(v)`, `CacheValue.of(v, TtlAt.at(seconds))`, or `CacheValue.missing(ttlAt)`. Returning `null` stores a negative entry for the cache's `missingTtl` (cache-penetration protection).

## Testing Pattern

Specs in `cocache-test`:
- `ClientSideCacheSpec<V>` / `DistributedCacheSpec<V>` (both `CacheStoreSpec`) for stores — implement `createCacheStore()`.
- `CacheSpec<K,V>` for `Cache` implementations — implement `createCache()`.
- `DefaultCoherentCacheSpec<K,V>` for coherence invariants and concurrency.
- `MultipleInstanceSyncSpec<K,V>` and `CacheEvictedEventBusSpec` for cross-instance coherence and event buses.

```kotlin
class MyDistributedCacheTest : DistributedCacheSpec<String>() {
    override fun createCacheStore(): DistributedCache<String> = MyDistributedCache()
    override fun createCacheEntry(): Pair<String, String> = UUID.randomUUID().toString() to "test_value"
}
```

## Key Classes

| Class | Module | Purpose |
|-------|--------|---------|
| `Cache<K,V>` | cocache-api | User-facing cache |
| `CacheValue<V>` (`PresentValue` / `MissingValue`) | cocache-api | Sealed entry with absolute `ttlAt` |
| `CacheStore<V>` | cocache-api | Pure storage SPI (no TTL policy) |
| `ClientSideCache<V>` | cocache-api | L2 store SPI |
| `DistributedCache<V>` | cocache-api | L1 store SPI (`null` = miss) |
| `CacheSource<K,V>` | cocache-api | Data source loader |
| `CacheEvictedEventBus` / `CacheEvictedSubscriber` | cocache-api | Invalidation channel (`onEvicted`, `onReset`) |
| `KeyConverter<K>` / `KeyFilter` | cocache-api | Storage key mapping / existence filter |
| `JoinCache<K1,V1,K2,V2>` | cocache-api | Composed cache |
| `CoherentCache<K,V>` / `DefaultCoherentCache` | cocache-core | Two-level orchestration |
| `TtlPolicy` | cocache-core | `ttl`, `ttlAmplitude`, `missingTtl` |
| `CaffeineClientSideCache` / `MapClientSideCache` | cocache-core | L2 implementations |
| `InMemoryDistributedCache` | cocache-core | In-memory L1 (tests) |
| `LocalCacheEvictedEventBus` / `NoOpCacheEvictedEventBus` | cocache-core | In-process / disabled buses |
| `SimpleJoinCache` | cocache-core | Default JoinCache |
| `BloomKeyFilter` | cocache-core | Guava BloomFilter adapter (Guava is compile-only) |
| `RedisDistributedCache` / `RedisCacheEvictedEventBus` | cocache-spring-redis | Redis implementations |

## Migrating from 4.x

`DefaultCacheValue` → `CacheValue.of/forever/missing`; `isMissingGuard` → `isMissing`; `ComputedTtlAt`/`CacheSecondClock` → `TtlAt`; `@GuavaCache`/`GuavaClientSideCache` → `@CaffeineCache`/`CaffeineClientSideCache.build`; `GuavaCacheEvictedEventBus` → `LocalCacheEvictedEventBus`; `MockDistributedCache` → `InMemoryDistributedCache`; SPI packages moved to `me.ahoo.cache.api.*`; `@CoCache` default `ttl` is now 3600 s (set `TtlAt.FOREVER` explicitly for the old behavior). The full table is in the wiki changelog.

## Build Commands

```bash
./gradlew build -x test
./gradlew :cocache-core:test
./gradlew :cocache-core:test --tests "me.ahoo.cache.proxy.ProxyCacheTest"
./gradlew :cocache-spring-redis:check          # Redis at localhost:6379
./gradlew :cocache-spring-boot-starter:check
./gradlew check
```
