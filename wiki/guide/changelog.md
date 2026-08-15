---
title: Changelog
description: Release history and notable changes for CoCache.
---

# Changelog

## v4.3.0 (Current)

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
