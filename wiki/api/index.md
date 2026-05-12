---
title: CoCache API Overview
description: Comprehensive overview of the CoCache API architecture, interface hierarchy, module organization, and key packages for the Level 2 Distributed Coherence Cache Framework.
---

# CoCache API Overview

CoCache provides a **Level 2 Distributed Coherence Cache Framework** for Java/Kotlin applications. The API is organized across multiple modules, each responsible for a distinct layer of the caching architecture.

## Module Organization

The API surface is spread across the following modules, ordered from low-level to high-level:

| Module | Artifact | Purpose | Source |
|--------|----------|---------|--------|
| **cocache-api** | Core interfaces and annotations | Defines `Cache`, `CacheValue`, `ClientSideCache`, `CacheSource`, `JoinCache`, and all cache annotations | [cocache-api/src/main/kotlin/me/ahoo/cache/api](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api) |
| **cocache-core** | Default implementations | Provides `DefaultCoherentCache`, proxy-based caching, key converters, event bus, key filters | [cocache-core/src/main/kotlin/me/ahoo/cache](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache) |
| **cocache-spring** | Spring Framework integration | Factory beans, `@EnableCoCache`, Spring-aware factories for client-side/distributed caches | [cocache-spring/src/main/kotlin/me/ahoo/cache/spring](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring) |
| **cocache-spring-redis** | Redis distributed cache | `RedisDistributedCache`, `RedisCacheEvictedEventBus`, `RedisDistributedCacheFactory` | [cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis) |
| **cocache-spring-cache** | Spring Cache bridge | `CoCacheManager`, `CoSpringCache` adapters for Spring's `CacheManager` abstraction | [cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache) |
| **cocache-spring-boot-starter** | Spring Boot auto-configuration | `CoCacheAutoConfiguration`, actuator endpoints, properties binding | [cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter) |

## Interface Hierarchy

The CoCache API is built on a layered interface hierarchy. The following diagram shows the core type relationships:

```mermaid
classDiagram
    direction TB

    class Cache~K,V~ {
        <<interface>>
    }
    class CacheGetter~K,V~ {
        <<interface>>
        +getCache(key: K) CacheValue~V~?
        +get(key: K) V?
        +getTtlAt(key: K) Long?
    }
    class CacheSetter~K,V~ {
        <<interface>>
        +set(key: K, ttlAt: Long, value: V)
        +set(key: K, value: V)
        +setCache(key: K, value: CacheValue~V~)
        +evict(key: K)
    }
    class ComputedCache~K,V~ {
        <<interface>>
        +ttl: Long
        +ttlAmplitude: Long
    }
    class NamedCache {
        <<interface>>
        +cacheName: String
    }
    class DistributedClientId {
        <<interface>>
        +clientId: String
    }
    class TtlConfiguration {
        <<interface>>
        +ttl: Long
        +ttlAmplitude: Long
    }
    class CacheEvictedSubscriber {
        <<interface>>
        +onEvicted(event: CacheEvictedEvent)
    }
    class CoherentCache~K,V~ {
        <<interface>>
        +cacheEvictedEventBus: CacheEvictedEventBus
        +clientSideCache: ClientSideCache~V~
        +distributedCache: DistributedCache~V~
        +keyFilter: KeyFilter
        +keyConverter: KeyConverter~K~
        +cacheSource: CacheSource~K,V~
    }

    Cache <|-- CacheGetter
    Cache <|-- CacheSetter
    Cache <|-- ComputedCache
    ComputedCache ..|> TtlConfiguration
    CoherentCache ..|> ComputedCache
    CoherentCache ..|> DistributedClientId
    CoherentCache ..|> NamedCache
    CoherentCache ..|> CacheEvictedSubscriber

    style Cache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CacheGetter fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CacheSetter fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ComputedCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style NamedCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style DistributedClientId fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style TtlConfiguration fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CacheEvictedSubscriber fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CoherentCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## Cache Layer Architecture

CoCache implements a three-tier cache architecture with L2 (client-side), L1 (distributed), and L0 (data source):

```mermaid
graph TB
    subgraph Client["Client Instance"]
        style Client fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        App["Application Code"]
        Proxy["Cache Proxy<br>(Dynamic Proxy)"]
        L2["L2: ClientSideCache<br>(Guava / Caffeine / Map)"]
    end

    subgraph Distributed["Shared Layer"]
        style Distributed fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        L1["L1: DistributedCache<br>(Redis)"]
        EventBus["CacheEvictedEventBus<br>(Redis Pub/Sub)"]
    end

    subgraph DataSource["Data Layer"]
        style DataSource fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        L0["L0: CacheSource<br>(Database / API)"]
    end

    App --> Proxy
    Proxy --> L2
    L2 -- "miss" --> L1
    L1 -- "miss" --> L0
    L0 -- "loaded" --> L1
    L1 -- "cached" --> L2
    Proxy -- "evict" --> L2
    Proxy -- "evict" --> L1
    L1 -- "evict event" --> EventBus
    EventBus -- "invalidate" --> L2

```

## Key Packages

### cocache-api Packages

| Package | Description | Key Types |
|---------|-------------|-----------|
| `me.ahoo.cache.api` | Core cache abstractions | `Cache`, `CacheGetter`, `CacheSetter`, `CacheValue`, `TtlAt`, `NamedCache` |
| `me.ahoo.cache.api.client` | Client-side cache interface | `ClientSideCache` |
| `me.ahoo.cache.api.source` | Data source interface | `CacheSource`, `NoOpCacheSource` |
| `me.ahoo.cache.api.join` | Join cache abstractions | `JoinCache`, `JoinValue`, `JoinKeyExtractor` |
| `me.ahoo.cache.api.annotation` | Declarative cache annotations | `@CoCache`, `@GuavaCache`, `@CaffeineCache`, `@JoinCacheable` |

### cocache-core Packages

| Package | Description | Key Types |
|---------|-------------|-----------|
| `me.ahoo.cache` | Core implementations and interfaces | `ComputedCache`, `DefaultCacheValue`, `MissingGuard`, `KeyFilter`, `TtlConfiguration` |
| `me.ahoo.cache.consistency` | Cache coherence engine | `CoherentCache`, `DefaultCoherentCache`, `CacheEvictedEventBus`, `CacheEvictedEvent`, `CoherentCacheFactory` |
| `me.ahoo.cache.proxy` | Dynamic proxy-based caching | `CacheProxyFactory`, `DefaultCacheProxyFactory`, `CoCacheInvocationHandler`, `CoCacheProxy` |
| `me.ahoo.cache.client` | Client-side cache implementations | `MapClientSideCache`, `GuavaClientSideCache`, `CaffeineClientSideCache`, `ClientSideCacheFactory` |
| `me.ahoo.cache.distributed` | Distributed cache abstractions | `DistributedCache`, `DistributedClientId`, `DistributedCacheFactory` |
| `me.ahoo.cache.converter` | Key conversion utilities | `KeyConverter`, `ToStringKeyConverter`, `ExpKeyConverter`, `KeyConverterFactory` |
| `me.ahoo.cache.filter` | Cache key filters | `BloomKeyFilter`, `NoOpKeyFilter` |
| `me.ahoo.cache.source` | Cache source factories | `CacheSourceFactory` |
| `me.ahoo.cache.join` | Join cache implementation | `SimpleJoinCache`, `DefaultJoinValue`, `ExpJoinKeyExtractor`, `JoinKeyExtractorFactory` |
| `me.ahoo.cache.annotation` | Metadata parsers | `CoCacheMetadata`, `CoCacheMetadataParser`, `JoinCacheMetadata`, `JoinCacheMetadataParser` |

## Dynamic Proxy Architecture

CoCache uses JDK dynamic proxies to implement cache interfaces. The proxy intercepts all method calls and delegates to the underlying `CoherentCache`:

```mermaid
sequenceDiagram
autonumber
    participant App as Application
    participant Proxy as Cache Proxy<br>(JDK Dynamic Proxy)
    participant Handler as CoCacheInvocationHandler
    participant Coherent as DefaultCoherentCache
    participant L2 as ClientSideCache
    participant L1 as DistributedCache
    participant L0 as CacheSource
    participant Bus as CacheEvictedEventBus

    App->>Proxy: cache.get(key)
    Proxy->>Handler: invoke(proxy, method, args)
    Handler->>Coherent: getCache(key)
    Coherent->>L2: getCache(cacheKey)
    alt L2 Hit
        L2-->>Coherent: CacheValue
    else L2 Miss
        Coherent->>L1: getCache(cacheKey)
        alt L1 Hit
            L1-->>Coherent: CacheValue
            Coherent->>L2: setCache(cacheKey, value)
        else L1 Miss
            Coherent->>L0: loadCacheValue(key)
            L0-->>Coherent: CacheValue
            Coherent->>L2: setCache(cacheKey, value)
            Coherent->>L1: setCache(cacheKey, value)
            Coherent->>Bus: publish(CacheEvictedEvent)
        end
    end
    Coherent-->>Handler: CacheValue
    Handler-->>Proxy: value
    Proxy-->>App: value
```

## Cache Eviction Flow

When a cache entry is evicted, the event is propagated across all client instances via the event bus:

```mermaid
graph LR
    subgraph InstanceA["Instance A"]
        style InstanceA fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        A_Proxy["Cache Proxy"]
        A_L2["L2 Cache"]
        A_L1["L1 Cache"]
    end

    subgraph EventBus["Event Bus (Redis Pub/Sub)"]
        style EventBus fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        Bus["CacheEvictedEventBus"]
    end

    subgraph InstanceB["Instance B"]
        style InstanceB fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        B_L2["L2 Cache"]
        B_Coherent["CoherentCache"]
    end

    A_Proxy -->|"evict(key)"| A_L2
    A_Proxy -->|"evict(key)"| A_L1
    A_L1 -->|"publish(event)"| Bus
    Bus -->|"onEvicted(event)"| B_Coherent
    B_Coherent -->|"evict(key)"| B_L2

```

## Factory Pattern

CoCache uses a **factory pattern** extensively to create cache components. All factories accept a `CoCacheMetadata` (parsed from annotations) and produce the corresponding cache component:

```mermaid
classDiagram
    direction TB

    class CacheProxyFactory {
        <<interface>>
        +create(cacheMetadata: CoCacheMetadata) CACHE
    }
    class CoherentCacheFactory {
        <<interface>>
        +create(cacheConfig: CoherentCacheConfiguration) CoherentCache
    }
    class ClientSideCacheFactory {
        <<interface>>
        +create(cacheMetadata: CoCacheMetadata) ClientSideCache
    }
    class DistributedCacheFactory {
        <<interface>>
        +create(cacheMetadata: CoCacheMetadata) DistributedCache
    }
    class CacheSourceFactory {
        <<interface>>
        +create(cacheMetadata: CoCacheMetadata) CacheSource
    }
    class KeyConverterFactory {
        <<interface>>
        +create(cacheMetadata: CoCacheMetadata) KeyConverter
    }
    class JoinKeyExtractorFactory {
        <<interface>>
        +create(cacheMetadata: JoinCacheMetadata) JoinKeyExtractor
    }
    class JoinCacheProxyFactory {
        <<interface>>
        +create(cacheMetadata: JoinCacheMetadata) CACHE
    }

    class DefaultCacheProxyFactory {
        +coherentCacheFactory
        +clientIdGenerator
        +clientSideCacheFactory
        +distributedCacheFactory
        +cacheSourceFactory
        +keyConverterFactory
    }
    class DefaultCoherentCacheFactory {
        +cacheEvictedEventBus
    }

    CacheProxyFactory <|.. DefaultCacheProxyFactory
    CoherentCacheFactory <|.. DefaultCoherentCacheFactory
    DefaultCacheProxyFactory --> CoherentCacheFactory
    DefaultCacheProxyFactory --> ClientSideCacheFactory
    DefaultCacheProxyFactory --> DistributedCacheFactory
    DefaultCacheProxyFactory --> CacheSourceFactory
    DefaultCacheProxyFactory --> KeyConverterFactory

    style CacheProxyFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CoherentCacheFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ClientSideCacheFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style DistributedCacheFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CacheSourceFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style KeyConverterFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style JoinKeyExtractorFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style JoinCacheProxyFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style DefaultCacheProxyFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style DefaultCoherentCacheFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## Related Pages

- [Core Interfaces](./core-interfaces.md) -- Detailed reference for all core interfaces
- [Annotations](./annotations.md) -- Complete annotation reference
- [Spring Integration](./spring-integration.md) -- Spring and Spring Boot integration API
- [Actuator Endpoints](./actuator.md) -- Monitoring and management endpoints
