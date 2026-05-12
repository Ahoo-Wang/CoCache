---
title: CoCache Module Overview
description: Complete overview of CoCache modules, their dependencies, and how they fit together to form a Level 2 Distributed Coherence Cache Framework.
---

# Module Overview

CoCache is organized into a set of focused Gradle modules that layer from pure API definitions through core logic, Spring integration, Redis persistence, and Spring Boot auto-configuration. Each module has a single responsibility and depends only on the modules below it in the dependency graph.

## Module Dependency Graph

```mermaid
graph TB
    subgraph sg_53 ["CoCache Module Architecture"]

        cocache-bom["cocache-bom<br>(Bill of Materials)"]
        style cocache-bom fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-dependencies["cocache-dependencies<br>(Version Catalog)"]
        style cocache-dependencies fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-api["cocache-api<br>(Core Interfaces)"]
        style cocache-api fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-core["cocache-core<br>(Default Implementations)"]
        style cocache-core fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-spring["cocache-spring<br>(Spring Integration)"]
        style cocache-spring fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-spring-redis["cocache-spring-redis<br>(Redis Implementation)"]
        style cocache-spring-redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-spring-cache["cocache-spring-cache<br>(Spring Cache Bridge)"]
        style cocache-spring-cache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-spring-boot-starter["cocache-spring-boot-starter<br>(Auto-Configuration)"]
        style cocache-spring-boot-starter fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-test["cocache-test<br>(Shared Test Specs)"]
        style cocache-test fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-example["cocache-example<br>(Example App)"]
        style cocache-example fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-core --> cocache-api
        cocache-spring --> cocache-core
        cocache-spring-redis --> cocache-core
        cocache-spring-redis --> cocache-spring
        cocache-spring-cache --> cocache-core
        cocache-spring-boot-starter --> cocache-spring
        cocache-spring-boot-starter --> cocache-spring-redis
        cocache-spring-boot-starter --> cocache-spring-cache
        cocache-test -.-> cocache-core
        cocache-example -.-> cocache-spring-boot-starter
    end

```

## Module Descriptions

| Module | Purpose | Key Contents | Source |
|--------|---------|-------------|--------|
| **cocache-api** | Pure interfaces and annotations with zero implementation dependencies | `Cache`, `CacheValue`, `ClientSideCache`, `CacheSource`, `JoinCache`, `@CoCache`, `@JoinCacheable` | [cocache-api/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-api) |
| **cocache-core** | Default implementations of all core abstractions | `DefaultCoherentCache`, `CoCacheProxy`, `SimpleJoinCache`, `MapClientSideCache`, `GuavaClientSideCache`, `CaffeineClientSideCache`, `BloomKeyFilter`, `CacheEvictedEventBus` | [cocache-core/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-core) |
| **cocache-spring** | Spring Framework integration for DI-based cache creation | `@EnableCoCache`, `EnableCoCacheRegistrar`, `AbstractCacheFactory`, `CacheProxyFactoryBean`, `JoinCacheProxyFactoryBean` | [cocache-spring/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring) |
| **cocache-spring-redis** | Redis-backed distributed cache and event bus | `RedisDistributedCache`, `RedisCacheEvictedEventBus`, `CodecExecutor` hierarchy | [cocache-spring-redis/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring-redis) |
| **cocache-spring-cache** | Bridge to Spring's `CacheManager` abstraction | `CoCacheManager`, `CoSpringCache` | [cocache-spring-cache/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring-cache) |
| **cocache-spring-boot-starter** | Spring Boot auto-configuration and actuator endpoints | `CoCacheAutoConfiguration`, `CoCacheProperties`, `CoCacheEndpoint`, `CoCacheClientEndpoint` | [cocache-spring-boot-starter/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring-boot-starter) |
| **cocache-test** | Shared abstract test specifications for verifying cache implementations | `CacheSpec`, `DistributedCacheSpec`, `ClientSideCacheSpec`, `MultipleInstanceSyncSpec` | [cocache-test/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-test) |
| **cocache-example** | Demonstration application | `UserCache`, `UserExtendInfoJoinCache`, example configuration | [cocache-example/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example) |
| **cocache-bom** | Bill of Materials for dependency management | BOM POM publishing | [cocache-bom/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-bom) |
| **cocache-dependencies** | Centralized version catalog | All third-party dependency versions | [cocache-dependencies/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-dependencies) |
| **code-coverage-report** | Aggregated JaCoCo coverage | Multi-module coverage aggregation | [code-coverage-report/](https://github.com/Ahoo-Wang/CoCache/tree/main/code-coverage-report) |

## Gradle Build Configuration

All modules are declared in [settings.gradle.kts](https://github.com/Ahoo-Wang/CoCache/blob/main/settings.gradle.kts):

```kotlin
rootProject.name = "CoCache"

include(":cocache-bom")
include(":cocache-dependencies")
include(":cocache-api")
include(":cocache-core")
include(":cocache-spring")
include(":cocache-spring-cache")
include(":cocache-spring-redis")
include(":cocache-spring-boot-starter")
include(":cocache-test")
include(":cocache-example")
include(":code-coverage-report")
```

### Build Dependency Chain

The `build.gradle.kts` dependency declarations establish this compile-time chain:

```mermaid
flowchart LR
    subgraph sg_54 ["Build Dependencies"]

        api["cocache-api<br>(no deps)"]
        style api fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        core["cocache-core<br>api(cocache-api)<br>api(cosid-core)"]
        style core fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        spring["cocache-spring<br>api(cocache-core)"]
        style spring fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        redis["cocache-spring-redis<br>api(cocache-core)<br>api(cocache-spring)"]
        style redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        springcache["cocache-spring-cache<br>api(cocache-core)"]
        style springcache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        starter["cocache-spring-boot-starter<br>api(cocache-spring)<br>api(cocache-spring-redis)<br>api(cocache-spring-cache)"]
        style starter fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        api --> core --> spring --> redis
        core --> springcache
        spring --> starter
        redis --> starter
        springcache --> starter
    end

```

## Layered Architecture

```mermaid
graph TB
    subgraph sg_55 ["Application Layer"]

        app["Application Code<br>(@CoCache interfaces)"]
        style app fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
        spring_boot["Spring Boot Starter<br>(Auto-config)"]
        style spring_boot fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    end

    subgraph sg_56 ["Integration Layer"]

        spring_mod["cocache-spring<br>(@EnableCoCache)"]
        style spring_mod fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
        redis_mod["cocache-spring-redis<br>(Redis impl)"]
        style redis_mod fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
        spring_cache_mod["cocache-spring-cache<br>(CacheManager)"]
        style spring_cache_mod fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    end

    subgraph sg_57 ["Core Layer"]

        core_mod["cocache-core<br>(CoherentCache, Proxy, TTL)"]
        style core_mod fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    end

    subgraph sg_58 ["API Layer"]

        api_mod["cocache-api<br>(Interfaces, Annotations)"]
        style api_mod fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    end

    app --> spring_boot
    spring_boot --> spring_mod
    spring_boot --> redis_mod
    spring_boot --> spring_cache_mod
    spring_mod --> core_mod
    redis_mod --> core_mod
    spring_cache_mod --> core_mod
    core_mod --> api_mod

```

## Two-Level Caching Data Flow

```mermaid
flowchart LR
    subgraph sg_59 ["Cache Read Path (L2 -> L1 -> DataSource)"]

        app_req["Application<br>get(key)"]
        style app_req fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        l2["L2: ClientSideCache<br>(Local Memory)"]
        style l2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        l1["L1: DistributedCache<br>(Redis)"]
        style l1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        ds["L0: CacheSource<br>(Database)"]
        style ds fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        result["Return value"]
        style result fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        app_req --> l2
        l2 -->|"miss"| l1
        l1 -->|"miss"| ds
        ds -->|"found"| result
        l1 -->|"hit<br>(populate L2)"| result
        l2 -->|"hit"| result
    end

```

## Core Interface Hierarchy

```mermaid
classDiagram
    class Cache~K, V~ {
        <<interface>>
    }
    class CacheGetter~K, V~ {
        <<interface>>
        +get(key: K) V?
    }
    class CacheSetter~K, V~ {
        <<interface>>
        +set(key: K, value: V)
        +evict(key: K)
    }
    class CoherentCache~K, V~ {
        <<interface>>
        +clientSideCache
        +distributedCache
        +cacheSource
    }
    class JoinCache~K1, V1, K2, V2~ {
        <<interface>>
        +joinKeyExtractor
    }
    class ClientSideCache~V~ {
        <<interface>>
        +size: Long
        +clear()
    }
    class DistributedCache~V~ {
        <<interface>>
    }
    class CacheSource~K, V~ {
        <<interface>>
        +loadCacheValue(key) CacheValue?
    }

    Cache <|.. CacheGetter
    Cache <|.. CacheSetter
    CoherentCache --|> Cache
    JoinCache --|> Cache
    ClientSideCache --|> Cache : String, V
    DistributedCache --|> Cache : String, V
```

## Key Design Principles

1. **Interface Segregation**: `cocache-api` contains only interfaces and annotations, allowing downstream modules to depend only on the contract without implementation coupling.

2. **Factory Pattern**: Every major component (`ClientSideCache`, `DistributedCache`, `CacheSource`, `KeyConverter`, `JoinKeyExtractor`) has a corresponding Factory interface in `cocache-core` with Spring-aware implementations in `cocache-spring`.

3. **AbstractCacheFactory**: The [AbstractCacheFactory](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/AbstractCacheFactory.kt) base class provides a unified Spring bean resolution pattern -- look up a bean by convention name first, fall back to type-based lookup, then use a default factory method.

4. **Plugin Architecture**: Users can replace any component (client-side cache, distributed cache, codec, event bus) by simply declaring a Spring bean with the expected name or type.

## Related Pages

- [cocache-api](./cocache-api.md) -- All interfaces and annotations
- [cocache-core](./cocache-core.md) -- Default implementations and core logic
- [cocache-spring](./cocache-spring.md) -- Spring Framework integration
- [cocache-spring-redis](./cocache-spring-redis.md) -- Redis distributed cache implementation
- [cocache-spring-boot-starter](./cocache-spring-boot-starter.md) -- Auto-configuration and endpoints
- [cocache-spring-cache](./cocache-spring-cache.md) -- Spring Cache abstraction bridge
