# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CoCache is a Level 2 Distributed Coherence Cache Framework for Java/Kotlin. It implements a two-level caching architecture:
- **L2 Client-side cache**: Local in-memory cache (bounded Caffeine by default)
- **L1 Distributed cache**: Shared cache layer (e.g., Redis)

Cache coherence is maintained through an event bus that publishes `CacheEvictedEvent` when entries are modified, allowing all client instances to invalidate their local caches. Staleness is bounded: finite default TTLs, a separate short negative-cache TTL, and L2 is cleared whenever the event channel (re)subscribes.

Design goals, module boundaries, and invariants live in `docs/architecture.md` — read it before changing coherence, storage, codec, or proxy code.

## Key Features

- **Two-Level Caching**: L2 (local) → L1 (distributed) → DataSource, with per-key `SingleFlight` coalescing to prevent cache stampede
- **JoinCache**: Compose multiple cached values into a single result (see `document/JoinCache.svg`)
- **Event-Driven Coherence**: `CacheEvictedEventBus` enables distributed cache invalidation across instances
- **Annotation-Based Configuration**: `@CoCache`, `@JoinCacheable`, `@CaffeineCache` for declarative cache setup
- **Proxy-Based Caching**: Cache interfaces are implemented via JDK dynamic proxies (`CacheInvocationHandler`, shared by CoCache and JoinCache)

## Build Commands

```bash
# Full build (no tests)
./gradlew build -x test

# Full check (tests + detekt + dokka); use clean check in CI for reproducibility
./gradlew check
./gradlew clean check

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :cocache-core:test
./gradlew :cocache-spring:test

# Run detekt (code quality)
./gradlew detekt

# Run detekt with auto-fix
./gradlew detektAutoFix

# Run a single test class
./gradlew :cocache-core:test --tests "me.ahoo.cache.proxy.ProxyCacheTest"

# Run integration tests (requires Redis)
./gradlew :cocache-spring-redis:check
./gradlew :cocache-spring-boot-starter:check

# Publish to local Maven
./gradlew publishToMavenLocal

# Hit-path JMH benchmark (requires Redis; compiled by check, never run by it)
./gradlew :cocache-spring-redis:jmh -PjmhThreads=8 -PjmhIncludes=l2Hit
```

## Module Architecture

```
cocache-api          - Core interfaces (Cache, CacheValue, ClientSideCache, CacheSource)
cocache-core         - Default implementations (DefaultCoherentCache, proxy-based caching)
cocache-spring       - Spring integration (@EnableCoCache, factory beans)
cocache-spring-redis - Redis distributed cache implementation
cocache-spring-cache - Spring Cache abstraction bridge
cocache-spring-boot-starter - Auto-configuration for Spring Boot
cocache-test         - Shared test specs (CacheSpec, DistributedCacheSpec, etc.)
cocache-example      - Example application demonstrating usage
cocache-bom          - Bill of Materials for dependency management
cocache-dependencies - Centralized version catalog
code-coverage-report - Aggregated JaCoCo coverage report
```

## Key Interfaces

All SPI lives in `cocache-api`; `cocache-core` holds orchestration and defaults.

- **`Cache<K, V>`** - User-facing cache: `getCache`/`get`/`getTtlAt`, `setCache`/`set`/`evict`
- **`CacheValue<V>`** - Sealed: `PresentValue(value, ttlAt)` | `MissingValue(ttlAt)` (explicit negative cache; `CacheValue.of(null, …)` is missing)
- **`CacheStore<V>`** - Pure storage by string key; no TTL policy. Specialized as **`ClientSideCache<V>`** (L2: `CaffeineClientSideCache`, `MapClientSideCache`) and **`DistributedCache<V>`** (L1: `RedisDistributedCache`, `InMemoryDistributedCache`)
- **`TtlPolicy`** (core) - `ttl` ± `ttlAmplitude` for values, `missingTtl` for negative cache
- **`CoherentCache<K, V>`** (core) - Two-level cache; exposes cache semantics plus read-only `configuration`. Default impl `DefaultCoherentCache`
- **`CacheSource<K, V>`** - Data source loader; returning `null` writes a negative cache (prevents penetration)
- **`KeyConverter<K>`** / **`KeyFilter`** - Business key → storage key; Bloom-style existence filter
- **`CacheEvictedEventBus`** / **`CacheEvictedSubscriber`** - Invalidation channel; subscribers get `onEvicted` and `onReset` (on every (re)subscription). Implementations: `RedisCacheEvictedEventBus`, `LocalCacheEvictedEventBus`, `NoOpCacheEvictedEventBus`
- **`JoinCache<K1, V1, K2, V2>`** / **`JoinValue`** - Composes two independent caches via `JoinKeyExtractor`; `evict(key)` evicts only the first cache

## Key Annotations

- **`@CoCache`** - name, keyPrefix, keyExpression (SpEL template), ttl (default 3600s), ttlAmplitude (60s), missingTtl (60s)
- **`@JoinCacheable`** - firstCacheName, joinCacheName, joinKeyExpression
- **`@CaffeineCache`** - L2 settings: maximumSize (default 10000), initialCapacity, expireAfterAccess

## Configuration

Enable CoCache via `@EnableCoCache(caches = [YourCacheInterface::class])` on your Spring configuration. Customize components with beans named `{cacheName}.ClientSideCache`, `.DistributedCache`, `.KeyConverter`, `.CacheSource` (stateful components resolve by name only; `CacheSource` may also resolve by unique generic type).

## Caching Strategy

1. **Cache Get**: L2 → KeyFilter → per-key `SingleFlight` { L1 → CacheSource }. An L1 miss (absent/deleted/corrupted) always reloads; it is never treated as a negative cache
2. **Write-back guard**: every L1→L2 fill and source write-back is protected by `InvalidationStamps` (stamp before, re-check before and after writing)
3. **Cache Set**: invalidate stamp → L1 → L2 → publish
4. **Cache Evict**: invalidate stamp → L2 → L1 → publish (update the data source *before* evicting)
5. **Coherence**: `onEvicted` from other instances evicts L2; `onReset` (channel (re)subscribed) clears L2
6. **JoinCache**: reads the first value, extracts the join key, reads the second cache, composes `JoinValue` with the earlier ttlAt

## Testing

- Unit tests use JUnit 5 (Jupiter) with **mockk** and **fluent-assert**
- Fluent-assert pattern: `import me.ahoo.test.asserts.assert` then use `.assert()` extension on any value — never use AssertJ's `assertThat()`
- Shared test specifications live in `cocache-test`: `CacheStoreSpec` (→ `ClientSideCacheSpec`, `DistributedCacheSpec`) for stores, `CacheSpec` for `Cache` implementations, `DefaultCoherentCacheSpec` / `MultipleInstanceSyncSpec` / `CacheEvictedEventBusSpec` for coherence — new implementations extend these
- Every fixed defect gets a reproducing test; orchestrate interleavings with latches (never sleeps); assert eventual cross-instance effects by polling with a timeout
- Integration tests require Redis at localhost:6379 (`cocache-spring-redis`, `cocache-spring-boot-starter`); in CI a Redis service container is used (see `integration-test.yml`). Redis tests use `RedisTestSupport` (listener container on `SyncTaskExecutor` so subscription resets complete inside `register()`)
- Logback configured via `config/logback.xml` for tests (fixes JaCoCo logging gaps)

## Build Configuration

- **JDK 17+** (via `jvmToolchain` in root `build.gradle.kts`)
- **Gradle 9.8.1** (wrapper)
- **Kotlin compiler flags**: `-Xjsr305=strict` (strict null-safety for JSR-305 annotations), `-Xjvm-default=all-compatibility` (generates default methods in interfaces for Java interop)
- **Detekt** config at `config/detekt/detekt.yml` — key overrides: `LongParameterList`, `TooManyFunctions`, `ReturnCount`, `MagicNumber`, `UnusedPrivateMember` all disabled; `MaxLineLength` raised to 300; `WildcardImport` allowed for `java.util.*`
