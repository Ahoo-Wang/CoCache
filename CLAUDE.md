# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CoCache is a Level 2 Distributed Coherence Cache Framework for Java/Kotlin. It implements a two-level caching architecture:
- **L2 Client-side cache**: Local in-memory cache (Guava/Caffeine)
- **L1 Distributed cache**: Shared cache layer (e.g., Redis)

Cache coherence is maintained through an event bus that publishes `CacheEvictedEvent` when entries are modified, allowing all client instances to invalidate their local caches.

## Key Features

- **Two-Level Caching**: L2 (local) → L1 (distributed) → DataSource with fine-grained locking to prevent cache stampede
- **JoinCache**: Compose multiple cached values into a single result (see `document/JoinCache.svg`)
- **Event-Driven Coherence**: `CacheEvictedEventBus` enables distributed cache invalidation across instances
- **Annotation-Based Configuration**: `@CoCache`, `@JoinCacheable`, `@GuavaCache` for declarative cache setup
- **Proxy-Based Caching**: Cache interfaces are implemented via dynamic proxies (`CoCacheProxy`, `JoinCacheProxy`)

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

- **`Cache<K, V>`** - Basic cache interface extending `CacheGetter` and `CacheSetter`
- **`CoherentCache<K, V>`** - Two-level cache combining `ComputedCache`, `DistributedClientId`, `NamedCache`, and `CacheEvictedSubscriber`
- **`JoinCache<K1, V1, K2, V2>`** - Composes two cached values via `JoinKeyExtractor` (see `document/JoinCache.svg`)
- **`JoinValue<V1, K2, V2>`** - Result type combining primary value with joined secondary value
- **`ClientSideCache<V>`** - L2 local cache (implementations: `MapClientSideCache`, `GuavaClientSideCache`, `CaffeineClientSideCache`)
- **`DistributedCache<V>`** - L1 shared cache (implementation: `RedisDistributedCache`)
- **`CacheSource<K, V>`** - Data source loader (prevents cache penetration via `MissingGuard`)
- **`CacheEvictedEventBus`** - Publishes `CacheEvictedEvent` for distributed cache invalidation (implementations: `GuavaCacheEvictedEventBus` in cocache-core, `NoOpCacheEvictedEventBus` in cocache-core, `RedisCacheEvictedEventBus` in cocache-spring-redis)
- **`KeyFilter`** - Bloom filter to prevent non-existent key queries (prevents cache breakdown)

## Key Annotations

- **`@CoCache`** - Marks a cache interface with name, keyPrefix, keyExpression, ttl, and ttlAmplitude
- **`@JoinCacheable`** - Marks a cache interface as a JoinCache with firstCacheName, joinCacheName, and joinKeyExpression
- **`@GuavaCache`** - Configures Guava cache settings (maximumSize, expireAfterAccess, etc.)
- **`@CaffeineCache`** - Configures Caffeine cache settings

## Configuration

Enable CoCache via `@EnableCoCache(caches = [YourCacheInterface::class])` on your Spring configuration. Optionally customize `ClientSideCache` and `CacheSource` beans by name matching the cache interface.

## Caching Strategy

1. **Cache Get**: L2 → L1 → CacheSource (with fine-grained locking to prevent cache stampede)
2. **Cache Set**: Both L2 and L1 simultaneously
3. **Cache Evict**: Local + distributed + event bus publication
4. **Event-driven Coherence**: Subscribe to `CacheEvictedEvent` to invalidate local cache on other instances
5. **JoinCache**: Retrieves primary value and join key, then fetches secondary value from another cache, composing them into `JoinValue`

## Testing

- Unit tests use JUnit 5 (Jupiter) with **mockk** and **fluent-assert**
- Fluent-assert pattern: `import me.ahoo.test.asserts.assert` then use `.assert()` extension on any value — never use AssertJ's `assertThat()`
- Shared test specifications live in `cocache-test` (abstract base classes: `CacheSpec`, `DistributedCacheSpec`, `ClientSideCacheSpec`, etc.) — new cache implementations extend these
- Integration tests require Redis (`cocache-spring-redis`, `cocache-spring-boot-starter`); in CI a Redis service container is used (see `integration-test.yml`)
- Logback configured via `config/logback.xml` for tests (fixes JaCoCo logging gaps)

## Build Configuration

- **JDK 17+** (via `jvmToolchain` in root `build.gradle.kts`)
- **Gradle 9.4.1** (wrapper)
- **Kotlin compiler flags**: `-Xjsr305=strict` (strict null-safety for JSR-305 annotations), `-Xjvm-default=all-compatibility` (generates default methods in interfaces for Java interop)
- **Detekt** config at `config/detekt/detekt.yml` — key overrides: `LongParameterList`, `TooManyFunctions`, `ReturnCount`, `MagicNumber`, `UnusedPrivateMember` all disabled; `MaxLineLength` raised to 300; `WildcardImport` allowed for `java.util.*`
