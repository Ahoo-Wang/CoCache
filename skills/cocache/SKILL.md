---
name: cocache
description: Use when building or modifying Java/Kotlin applications with CoCache 6.x two-level distributed coherent caching. Invoke for @CoCache cache interfaces, CacheSpec beans, CacheLoader data sources, invalidate-after-commit (TransactionalInvalidator), Redis-backed coherence (leases, Pub/Sub or sharded channel), Spring Boot and Spring Cache (@Cacheable) integration, CachePolicy and cocache.* properties (ttl, absentTtl, localMaxAge, refreshWindow, read-failure), SetCache, custom CoherenceBackend implementations, the cocache-test TCK, or migrating from CoCache 5.x.
---

# CoCache Development Guide

CoCache (6.x) is a Java/Kotlin two-level distributed coherent cache:
- **L2**: bounded local Caffeine copy, one per instance (fixed; not pluggable).
- **L1**: shared Redis, which serializes every write per key.
- **Data source**: a `CacheLoader<K, V>`; `null` means "not found" and is cached for `absentTtl`.
- **Coherence**: a miss takes a lease in Redis and only the lease holder may fill, so a slow load never overwrites a newer invalidation. `put` and `invalidate` write and broadcast in one Lua script; peers drop their L2 copy when the message arrives. Lost messages are bounded by `localMaxAge` (default 5 min) and channel resets.

Packages are `me.ahoo.cocache.*` (5.x used `me.ahoo.cache.*`). The normative design is `docs/architecture.md` in the CoCache repository.

## Start Here

| Task | Read |
|------|------|
| Add CoCache to a Spring Boot / Spring / plain JVM app; configure TTLs, properties, failure behavior, metrics | `references/setup.md` |
| Upgrade an app from CoCache 5.x | `references/migration-5x.md` |
| Write or update tests | `references/testing.md` |
| Implement a custom `CoherenceBackend`, codec, key encoder, key filter, or per-entry TTL | `references/custom-implementation.md` |

## Core Model

Declare each cache once, either as an annotated interface or as a spec bean. Both become the same engine.

```kotlin
// Interface style: durations in seconds; defaults ttl 3600, ttlJitter 60, absentTtl 60, localMaxAge 300, refreshWindow off
@CoCache(ttl = 120)
@CaffeineCache(maximumSize = 100_000)              // optional; L2 is always bounded (default 10 000)
interface UserCache : Cache<String, User>          // bean name "UserCache"

@SpringBootApplication
@EnableCoCache(caches = [UserCache::class])
class App

@Bean // matched to UserCache by generic type (or @CoCache(loader = "beanName"))
fun userLoader(repo: UserRepository) = CacheLoader<String, User> { id -> repo.findById(id) }
```

```kotlin
// Spec style: inject Cache<String, User>; bean name "user" (spec bean name minus "Spec")
@Bean
fun userSpec(repo: UserRepository): CacheSpec<String, User> = cacheSpec("user") {
    codec = jacksonCodec<User>(mapper)
    loader = CacheLoader { id -> repo.findById(id) }
    policy { ttl = Duration.ofMinutes(2) }
}
```

Operations on `Cache<K, V>`: `get(key)` / `cache[key]`, `get(key, loader)`, `getAll(keys)`, `lookup(key)` (`Lookup.Present` / `Lookup.Absent`), `put(key, value)`, `put(key, value, ttl)`, `invalidate(key)`, `invalidateAll(keys)`.

**Write pattern**: commit the data source first, then `invalidate(key)`. Inside a transaction use `TransactionalInvalidator.invalidate(cache, key)`, which runs after commit. Prefer `invalidate` over `put`; `put` is for writers that compute the final value, and it does not fill the local L2.

## Rules That Prevent Bugs

- Never invalidate before the transaction commits: a concurrent load can read the old row and cache it.
- With Spring Cache, use `@Cacheable(sync = true)`. Without `sync`, Spring's "miss → call → put" is an unconditional write and loses the lease protection.
- A cache without a loader is valid (fill it with `put`); misses return `null` and nothing negative is written to Redis.
- Use `SetCache<K, E>` only when Redis is the only copy of the set (no loader allowed; `invalidate` throws, use `clear`). A set that lives in your database is a plain `Cache<K, Set<E>>` maintained with `invalidate`.
- All instances of one cluster must use the same `cocache.redis.channel-mode` and the same major version (5.x and 6.x cannot share Redis).
- Size `lease-ttl` above the loader's p99 and `lease-wait` above its typical latency, or cross-instance deduplication falls back to independent loads.

## Key Classes

| Class | Module | Purpose |
|-------|--------|---------|
| `Cache<K,V>` / `SetCache<K,E>` / `Lookup<V>` | cocache-api | User API |
| `CacheLoader<K,V>` / `Codec<V>` | cocache-api | Data source / value bytes |
| `@CoCache` / `@CaffeineCache` | cocache-api | Interface-style declaration |
| `CacheReadException` / `CacheWriteException` | cocache-api | L1 failures surfaced to callers |
| `CoherenceBackend`, `RemoteStore`, `InvalidationChannel` (`api.spi`) | cocache-api | Backend SPI |
| `CacheRuntime`, `CacheSpec` / `cacheSpec {}`, `SetCacheSpec` / `setCacheSpec {}` | cocache-core | Runtime and declarations |
| `CachePolicy`, `LeasePolicy`, `LoadPolicy`, `LocalPolicy`, `ReadFailureMode`, `InvalidateFailureMode` | cocache-core | Lifetimes and failure behavior |
| `KeyEncoder`, `TtlFunction`, `KeyFilter` | cocache-core | Key mapping, per-entry TTL, existence filter |
| `InMemoryBackend`, `ManualClock`, `StringCodec` | cocache-core | Tests / single instance |
| `RedisBackend`, `RedisBackendOptions`, `RedisChannelMode` | cocache-redis | Lettuce-native Redis backend |
| `JacksonCodec`, `jacksonCodec<V>()` | cocache-codec-jackson | Jackson 3 codec |
| `CoCacheSpringCacheManager`, `TransactionalInvalidator` | cocache-spring | Spring Cache bridge, after-commit invalidation |
| `@EnableCoCache`, `CacheSpecCustomizer`, `CoCacheProperties` | cocache-spring-boot-starter | Boot wiring |
| `BackendProtocolSpec`, `EngineSpec`, `SetEngineSpec`, `HookedBackend` | cocache-test | TCK |

## Repository Rules

When editing the CoCache repository itself, follow its `AGENTS.md`:
- Use `me.ahoo.test.asserts.assert` and `.assert()` in Kotlin tests; do not use AssertJ `assertThat()`.
- New backends pass the `cocache-test` TCK; every fixed defect gets a reproducing test.
- Ask before changing `cocache-api`, the Redis wire format, or adding dependencies.
- Update `docs/architecture.md` when changing coherence, storage, codec, proxy, or Spring wiring behavior.
- Run `./gradlew check` (Redis on `localhost:6379`) before finishing broad changes.

```bash
./gradlew build -x test
./gradlew :cocache-core:test --tests "me.ahoo.cocache.core.engine.CoherentEngineTest"
./gradlew :cocache-redis:test                  # Redis at localhost:6379
./gradlew :cocache-spring-boot-starter:test
./gradlew check
```
