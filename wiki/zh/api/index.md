---
title: API 概览
description: CoCache API 架构概览，介绍接口层次结构、模块组织、关键包结构以及二级分布式一致性缓存框架的核心设计。
---

# CoCache API 概览

CoCache 为 Java/Kotlin 应用提供了**二级分布式一致性缓存框架**。API 按多个模块进行组织，每个模块负责缓存架构中的不同层次。

## 模块组织

API 表面分布在以下模块中，按从底层到高层的顺序排列：

| 模块 | 构件 | 用途 | 源码 |
|--------|----------|---------|--------|
| **cocache-api** | 核心接口和注解 | 定义 `Cache`、`CacheValue`、`ClientSideCache`、`CacheSource`、`JoinCache` 及所有缓存注解 | [cocache-api/src/main/kotlin/me/ahoo/cache/api](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api) |
| **cocache-core** | 默认实现 | 提供 `DefaultCoherentCache`、基于代理的缓存、键转换器、事件总线、键过滤器 | [cocache-core/src/main/kotlin/me/ahoo/cache](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache) |
| **cocache-spring** | Spring Framework 集成 | Factory Bean、`@EnableCoCache`、感知 Spring 的客户端缓存/分布式缓存工厂 | [cocache-spring/src/main/kotlin/me/ahoo/cache/spring](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring) |
| **cocache-spring-redis** | Redis 分布式缓存 | `RedisDistributedCache`、`RedisCacheEvictedEventBus`、`RedisDistributedCacheFactory` | [cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis) |
| **cocache-spring-cache** | Spring Cache 桥接 | `CoCacheManager`、`CoSpringCache` 适配器，用于 Spring 的 `CacheManager` 抽象 | [cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache) |
| **cocache-spring-boot-starter** | Spring Boot 自动配置 | `CoCacheAutoConfiguration`、Actuator 端点、属性绑定 | [cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter) |

## 接口层次结构

CoCache API 建立在分层的接口层次结构之上。下图展示了核心类型之间的关系：

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

## 缓存层级架构

CoCache 实现了三层缓存架构，包括 L2（客户端缓存）、L1（分布式缓存）和 L0（数据源）：

```mermaid
graph TB
    subgraph Client["客户端实例"]
        style Client fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        App["应用程序代码"]
        Proxy["缓存代理<br>(动态代理)"]
        L2["L2: ClientSideCache<br>(Guava / Caffeine / Map)"]
    end

    subgraph Distributed["共享层"]
        style Distributed fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        L1["L1: DistributedCache<br>(Redis)"]
        EventBus["CacheEvictedEventBus<br>(Redis Pub/Sub)"]
    end

    subgraph DataSource["数据层"]
        style DataSource fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        L0["L0: CacheSource<br>(数据库 / API)"]
    end

    App --> Proxy
    Proxy --> L2
    L2 -- "未命中" --> L1
    L1 -- "未命中" --> L0
    L0 -- "已加载" --> L1
    L1 -- "已缓存" --> L2
    Proxy -- "驱逐" --> L2
    Proxy -- "驱逐" --> L1
    L1 -- "驱逐事件" --> EventBus
    EventBus -- "失效" --> L2

```

## 关键包结构

### cocache-api 包

| 包 | 说明 | 关键类型 |
|---------|-------------|-----------|
| `me.ahoo.cache.api` | 核心缓存抽象 | `Cache`、`CacheGetter`、`CacheSetter`、`CacheValue`、`TtlAt`、`NamedCache` |
| `me.ahoo.cache.api.client` | 客户端缓存接口 | `ClientSideCache` |
| `me.ahoo.cache.api.source` | 数据源接口 | `CacheSource`、`NoOpCacheSource` |
| `me.ahoo.cache.api.join` | Join 缓存抽象 | `JoinCache`、`JoinValue`、`JoinKeyExtractor` |
| `me.ahoo.cache.api.annotation` | 声明式缓存注解 | `@CoCache`、`@GuavaCache`、`@CaffeineCache`、`@JoinCacheable` |

### cocache-core 包

| 包 | 说明 | 关键类型 |
|---------|-------------|-----------|
| `me.ahoo.cache` | 核心实现和接口 | `ComputedCache`、`DefaultCacheValue`、`MissingGuard`、`KeyFilter`、`TtlConfiguration` |
| `me.ahoo.cache.consistency` | 缓存一致性引擎 | `CoherentCache`、`DefaultCoherentCache`、`CacheEvictedEventBus`、`CacheEvictedEvent`、`CoherentCacheFactory` |
| `me.ahoo.cache.proxy` | 基于动态代理的缓存 | `CacheProxyFactory`、`DefaultCacheProxyFactory`、`CoCacheInvocationHandler`、`CoCacheProxy` |
| `me.ahoo.cache.client` | 客户端缓存实现 | `MapClientSideCache`、`GuavaClientSideCache`、`CaffeineClientSideCache`、`ClientSideCacheFactory` |
| `me.ahoo.cache.distributed` | 分布式缓存抽象 | `DistributedCache`、`DistributedClientId`、`DistributedCacheFactory` |
| `me.ahoo.cache.converter` | 键转换工具 | `KeyConverter`、`ToStringKeyConverter`、`ExpKeyConverter`、`KeyConverterFactory` |
| `me.ahoo.cache.filter` | 缓存键过滤器 | `BloomKeyFilter`、`NoOpKeyFilter` |
| `me.ahoo.cache.source` | 缓存数据源工厂 | `CacheSourceFactory` |
| `me.ahoo.cache.join` | Join 缓存实现 | `SimpleJoinCache`、`DefaultJoinValue`、`ExpJoinKeyExtractor`、`JoinKeyExtractorFactory` |
| `me.ahoo.cache.annotation` | 元数据解析器 | `CoCacheMetadata`、`CoCacheMetadataParser`、`JoinCacheMetadata`、`JoinCacheMetadataParser` |

## 动态代理架构

CoCache 使用 JDK 动态代理来实现缓存接口。代理拦截所有方法调用，并委托给底层的 `CoherentCache`：

```mermaid
sequenceDiagram
autonumber
    participant App as 应用程序
    participant Proxy as 缓存代理<br>(JDK 动态代理)
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
    alt L2 命中
        L2-->>Coherent: CacheValue
    else L2 未命中
        Coherent->>L1: getCache(cacheKey)
        alt L1 命中
            L1-->>Coherent: CacheValue
            Coherent->>L2: setCache(cacheKey, value)
        else L1 未命中
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

## 缓存驱逐流程

当缓存条目被驱逐时，事件通过事件总线传播到所有客户端实例：

```mermaid
graph LR
    subgraph InstanceA["实例 A"]
        style InstanceA fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        A_Proxy["缓存代理"]
        A_L2["L2 缓存"]
        A_L1["L1 缓存"]
    end

    subgraph EventBus["事件总线 (Redis Pub/Sub)"]
        style EventBus fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        Bus["CacheEvictedEventBus"]
    end

    subgraph InstanceB["实例 B"]
        style InstanceB fill:#161b22,stroke:#6d5dfc,color:#e6edf3
        B_L2["L2 缓存"]
        B_Coherent["CoherentCache"]
    end

    A_Proxy -->|"evict(key)"| A_L2
    A_Proxy -->|"evict(key)"| A_L1
    A_L1 -->|"publish(event)"| Bus
    Bus -->|"onEvicted(event)"| B_Coherent
    B_Coherent -->|"evict(key)"| B_L2

```

## 工厂模式

CoCache 广泛使用**工厂模式**来创建缓存组件。所有工厂接受 `CoCacheMetadata`（从注解解析）并生成相应的缓存组件：

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

## 相关页面

- [核心接口](./core-interfaces.md) -- 所有核心接口的详细参考
- [注解](./annotations.md) -- 完整的注解参考
- [Spring 集成](./spring-integration.md) -- Spring 和 Spring Boot 集成 API
- [Actuator 端点](./actuator.md) -- 监控和管理端点
