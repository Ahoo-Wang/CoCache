---
title: Changelog
description: Release history and notable changes for CoCache.
---

# Changelog

## v5.0.0 (Current)

**Module Group:** `me.ahoo.cocache`

5.0 rebuilds the coherence core around three first-principles goals — bounded staleness, no lost invalidations, and a cheap hit path. It is a breaking release with no compatibility bridges. **Redis storage layout and the eviction message format are unchanged**, so 4.x and 5.0 instances can share Redis during a rolling upgrade.

### Correctness Fixes

- **L1 miss is never a negative cache** — Redis reads are one atomic round trip (a Lua script returns TTL + value). An absent key, a key deleted mid-read, or a corrupted payload is a miss that reloads from the source. Previously a concurrent eviction between the `TTL` and `GET` calls was decoded as a negative cache and pinned into L2 until the old TTL (forever by default).
- **Local writes invalidate in-flight loads** — `evict`/`setCache` on the same instance now discard a concurrent source load's stale write-back (previously only remote events did). L1 → L2 fills are guarded the same way.
- **Original exceptions from cache proxies** — proxies unwrap `InvocationTargetException`; callers no longer receive `UndeclaredThrowableException`.
- **Bounded staleness by default** — default `ttl` is 3600 s (was forever); the negative cache has its own `missingTtl` (default 60 s, was the value TTL); default L2 is a bounded Caffeine cache (10 000 entries); every (re)subscription of the eviction channel clears L2 so lost pub/sub messages cannot leave L2 stale.
- **Spring Cache** — `get(key, valueLoader)` loads once per key (`@Cacheable(sync = true)` semantics); `retrieve` runs on a dedicated executor instead of the common ForkJoin pool.
- **Stateful components are never shared** — `ClientSideCache`, `DistributedCache` and `KeyConverter` beans resolve by name only; previously a bean matched by generic type could be shared by every cache with the same value type.
- **JoinCache.evict(key) no longer loads** — it evicts only the first cache.

### Architecture

- `cocache-api` owns all SPI: `CacheStore` → `ClientSideCache` / `DistributedCache`, `CacheEvictedEventBus` / `CacheEvictedSubscriber` / `CacheEvictedEvent`, `KeyConverter`, `KeyFilter`, `CacheSource`.
- `CacheValue` is sealed: `PresentValue(value, ttlAt)` | `MissingValue(ttlAt)`.
- Storage tiers are pure stores; TTL policy (`TtlPolicy`) lives in the orchestration layer.
- `DefaultCoherentCache` coalesces loads per key with `SingleFlight` (no striped-lock collisions) and guards write-backs with `InvalidationStamps`.
- One `CacheInvocationHandler` serves CoCache and JoinCache proxies.

### Migration from 4.x

| 4.x | 5.0 |
|-----|-----|
| `me.ahoo.cache.DefaultCacheValue(value, ttlAt)` / `.forever(v)` / `.missingGuard(...)` | `CacheValue.of(value, ttlAt)` / `CacheValue.forever(v)` / `CacheValue.missing(ttlAt)` |
| `cacheValue.isMissingGuard` | `cacheValue.isMissing` (or `is MissingValue`) |
| `ComputedTtlAt.at(ttl)`, `ComputedTtlAt.FOREVER`, `CacheSecondClock.INSTANCE.currentTime()` | `TtlAt.at(ttl)`, `TtlAt.FOREVER`, `TtlAt.currentTime()` |
| `ClientSideCache` / `DistributedCache` extended `Cache` (`cache[key] = v`) | They are `CacheStore`s: `getCache` / `setCache` / `evict` |
| `me.ahoo.cache.distributed.DistributedCache`, `me.ahoo.cache.consistency.CacheEvicted*`, `me.ahoo.cache.converter.KeyConverter`, `me.ahoo.cache.KeyFilter` | `me.ahoo.cache.api.distributed.DistributedCache`, `me.ahoo.cache.api.consistency.CacheEvicted*`, `me.ahoo.cache.api.converter.KeyConverter`, `me.ahoo.cache.api.filter.KeyFilter` |
| `@GuavaCache`, `GuavaClientSideCache`, `GuavaCacheEvictedEventBus` | `@CaffeineCache`, `CaffeineClientSideCache.build(...)`, `LocalCacheEvictedEventBus` |
| `MockDistributedCache` | `InMemoryDistributedCache` |
| `CoherentCache.clientSideCache` / `.distributedCache` / ... | `CoherentCache.configuration.clientSideCache` / ... |
| Custom `CacheEvictedSubscriber` | Implement `onReset()` (clear local copies) |
| Custom `CodecExecutor` | `executeAndDecode(key)` reads value and TTL itself; extend `StringCodecExecutor` / `HashCodecExecutor` or `AbstractCodecExecutor` |
| `ClientSideCache<User>` bean picked by type | Name it `UserCache.ClientSideCache` |
| `TtlConfiguration`, `TtlConfigurationAware`, `ComputedCache` | Removed — TTL comes from `@CoCache` (`TtlPolicy`) |
| `@CoCache` without `ttl` meant "forever" | Now 3600 s — set `ttl = TtlAt.FOREVER` explicitly to keep the old behavior |
| `JoinCache.evict(key)` evicted both caches | Evicts the first cache only; use `evict(firstKey, joinKey)` for both |
| A `CacheSource` reading the same key from the same cache re-entered the lock | Fails fast with `IllegalStateException` (it previously over-evicted) |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.0.0")
```

## v4.3.0

**Module Group:** `me.ahoo.cocache`

### Highlights

- **Cache Breakdown Protection Hardened** — Replaced the per-key lock map (which had a lock-object recycling race allowing duplicate source loads) with Guava `Striped` locks in `DefaultCoherentCache` (#519).
- **Clock Thread Startup Race Fixed** — `CacheSecondClock` timer thread startup no longer races with field initialization; eliminates the "frozen clock → caches never expire" failure mode (#519).
- **Stale Write-Back Race Fixed** — A per-key invalidation generation counter makes in-flight loads detect eviction events that arrive during loading: stale results are discarded (or compensating-evicted) instead of being pinned into both cache tiers until TTL (#519).
- **Lifecycle Management** — `CoherentCache` now extends `AutoCloseable` with an idempotent `close()` (unregister from event bus + close distributed cache); Spring destroys caches automatically at shutdown (#519).
- **Corrupted Payload Self-Healing** — Undecodable Redis values are deleted and treated as a cache miss (source reload) instead of throwing on every read (#520).
- **Null Value Normalization** — `null` values are consistently written as negative-cache sentinels across all codecs (previously: empty-string corruption, NPEs, or codec-dependent semantics) (#520).
- **Redis Failure Degradation** — By default, Redis read failures now degrade to cache-miss semantics (source reload, business calls unaffected) and write/evict failures only log a warning. Set `cocache.redis.strict-failure=true` to restore the strict throwing behavior (#520).
- **Atomic Hash/Set Writes** — Structural codec writes use single-key Lua scripts (`DEL` + `HSET`/`SADD` + `EXPIRE`), eliminating concurrent-write field-union corruption. Empty Map/Set writes now silently evict the key instead of throwing (#520).
- **Configurable Missing-Guard Sentinel** — New property `cocache.redis.missing-guard-sentinel` mitigates collisions between business data and the `"_nil_"` sentinel (#520).

### Breaking Changes & Behavior Notes

- **API removal (the only breaking change)**: `AbstractCodecExecutor.setPipelined`/`serialize` members were removed — affects only third-party codecs directly extending this abstract class.
- `CodecExecutor.executeAndDecode` return type is now nullable (`CacheValue<V>?`) — covariant-compatible for implementers; direct callers must handle `null` on recompile.
- Redis failures degrade by default (previously threw); JSON codec `null` values become negative-cache sentinels instead of `"null"`-literal round-trips.
- Spring containers now auto-close caches at shutdown; `FactoryBean.getObject()` returns a memoized instance (eliminates duplicate event-bus subscriptions).
- `strict-failure` / `missing-guard-sentinel` apply only to auto-configured (fallback-created) caches; custom `DistributedCache` beans are unaffected.
- Wire formats (storage structure, eviction messages) are unchanged — rolling upgrades are fully compatible.

### Dependencies

| Dependency | Version |
|------------|---------|
| Spring Boot | 4.1.0 |
| CosId | 3.2.0 |
| Guava | 33.6.0-jre |
| Kotlin | 2.4.10 |
| JUnit | 6.1.3 |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:4.3.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>4.3.0</version>
</dependency>
```

## v4.2.0

**Module Group:** `me.ahoo.cocache`

### Dependencies

| Dependency | Version |
|------------|---------|
| Spring Boot | 4.1.0 |
| CosId | 3.2.0 |
| Guava | 33.6.0-jre |
| Kotlin | 2.4.0 |
| JUnit | 6.1.1 |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:4.2.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>4.2.0</version>
</dependency>
```

## Historical Releases

For the full release history, see [GitHub Releases](https://github.com/Ahoo-Wang/CoCache/releases).
