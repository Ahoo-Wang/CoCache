---
title: CoCache 介绍
description: 面向 Java/Kotlin 的二级分布式一致性缓存框架 -- 概述、核心特性与架构总览。
---

# CoCache 介绍

**CoCache** 是一个面向 Java/Kotlin 的**二级分布式一致性缓存框架**，提供基于事件驱动一致性的两级缓存架构。它以 `me.ahoo.cocache` 为组织标识发布，当前版本为 **5.0.0**。

CoCache 位于应用与数据源之间，增加两层缓存 -- 有界的本地内存 L2 缓存（Caffeine）和共享的分布式 L1 缓存（Redis） -- 同时通过事件总线保持所有实例间的缓存一致性。

## 三级缓存概念

CoCache 实现了三级数据访问模型：

| 层级 | 名称 | 位置 | 用途 | 源码 |
|------|------|------|------|------|
| L2 | 客户端缓存 | 进程内（有界 Caffeine） | 最快访问，每实例独立 | [cocache-api/.../client/ClientSideCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/client/ClientSideCache.kt) |
| L1 | 分布式缓存 | 共享（Redis） | 跨实例一致性 | [cocache-api/.../distributed/DistributedCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/distributed/DistributedCache.kt) |
| L0 | 数据源 | 原始数据（数据库、API） | 权威数据来源 | [cocache-api/.../source/CacheSource.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/source/CacheSource.kt) |

```mermaid
graph TB
    subgraph Application
        direction TB
        Caller["Caller<br>cache.get(key)"]
    end

    subgraph sg_20 ["L2 - Client-side Cache"]
        direction TB
        L2["Caffeine<br>In-process Memory"]
    end

    subgraph sg_21 ["L1 - Distributed Cache"]
        direction TB
        L1["Redis<br>Shared across instances"]
    end

    subgraph sg_22 ["L0 - DataSource"]
        direction TB
        L0["Database / API<br>Authoritative Source"]
    end

    Caller --> L2
    L2 -->|"cache miss"| L1
    L1 -->|"cache miss"| L0
    L0 -->|"return data"| L1
    L1 -->|"populate"| L2

    style Caller fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L0 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

```mermaid
graph LR
    subgraph sg_23 ["Instance A"]
        L2A["L2 Cache<br>ClientSideCache"]
    end
    subgraph sg_24 ["Instance B"]
        L2B["L2 Cache<br>ClientSideCache"]
    end
    subgraph sg_25 ["Shared"]
        L1["L1 Cache<br>Redis"]
        EB["Event Bus<br>Redis Pub/Sub"]
    end

    L2A --> L1
    L2B --> L1
    L1 -->|"CacheEvictedEvent"| EB
    EB -->|"invalidate"| L2A
    EB -->|"invalidate"| L2B

    style L2A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L2B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style EB fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## 核心特性

| 特性 | 说明 | 源码 |
|------|------|------|
| 二级缓存 | L2（本地）+ L1（分布式），写回受失效戳保护 | [DefaultCoherentCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt) |
| 事件驱动一致性 | `CacheEvictedEventBus` 实现分布式缓存失效 | [CacheEvictedEventBus.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/consistency/CacheEvictedEventBus.kt) |
| 注解驱动配置 | `@CoCache`、`@CaffeineCache`、`@JoinCacheable` | [cocache-api/.../annotation/](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/annotation/) |
| JoinCache | 将多个缓存值组合为单一结果 | [JoinCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/join/JoinCache.kt) |
| 缓存击穿防护 | 按 key 的 `SingleFlight` 合并并发回源 | [SingleFlight.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/concurrent/SingleFlight.kt) |
| 缓存穿透防护 | 显式负缓存（`MissingValue`），使用独立的短 `missingTtl` | [CacheValue.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/CacheValue.kt) |
| 布隆过滤器 | 可选的布隆过滤器阻止对不存在键的查询 | [BloomKeyFilter.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/filter/BloomKeyFilter.kt) |
| TTL 抖动 | 随机 TTL 幅度防止缓存雪崩 | [TtlAt.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/TtlAt.kt) |
| 陈旧度有界 | 有限的默认 TTL；事件通道每次（重新）订阅都清空 L2 | [docs/architecture.md](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/architecture.md) |
| 代理式缓存 | 运行时通过动态代理实现缓存接口 | [CacheInvocationHandler.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CacheInvocationHandler.kt) |
| Spring Boot Starter | 自动配置与条件化 Bean 注册 | [CoCacheAutoConfiguration.kt:61-186](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheAutoConfiguration.kt#L61-L186) |

## 架构概览

```mermaid
graph TB
    subgraph sg_26 ["Spring Boot Application"]
        direction TB
        EnableCoCache["@EnableCoCache<br>caches = [UserCache::class]"]
        UserCache["UserCache Interface<br>@CoCache + @CaffeineCache"]
        Proxy["JDK Proxy<br>CacheInvocationHandler"]
    end

    subgraph sg_27 ["CoCache Core"]
        direction TB
        CoherentCache["DefaultCoherentCache<br>L2 + L1 + Event"]
        KeyConverter["KeyConverter<br>key -> cacheKey"]
        TtlPolicy["TtlPolicy<br>ttl / missingTtl"]
    end

    subgraph sg_28 ["L2 - Client Side"]
        direction TB
        Caffeine["CaffeineClientSideCache"]
        Map["MapClientSideCache"]
    end

    subgraph sg_29 ["L1 - Distributed"]
        direction TB
        Redis["RedisDistributedCache"]
    end

    subgraph sg_30 ["Coherence"]
        direction TB
        EventBus["CacheEvictedEventBus<br>Redis Pub/Sub"]
    end

    EnableCoCache --> UserCache
    UserCache --> Proxy
    Proxy --> CoherentCache
    CoherentCache --> KeyConverter
    CoherentCache --> TtlPolicy
    CoherentCache --> Caffeine
    CoherentCache --> Map
    CoherentCache --> Redis
    CoherentCache --> EventBus

    style EnableCoCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style UserCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Proxy fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CoherentCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style KeyConverter fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style TtlPolicy fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Caffeine fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Map fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style EventBus fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## 缓存流转

```mermaid
sequenceDiagram
autonumber
    participant Caller as Caller
    participant L2 as L2 Cache
    participant KeyFilter as KeyFilter
    participant L1 as L1 Redis
    participant Lock as SingleFlight
    participant L0 as DataSource
    participant EventBus as EventBus

    Caller->>L2: get(key)
    alt L2 hit
        L2-->>Caller: CacheValue
    else L2 miss
        L2-->>Caller: null
        Caller->>KeyFilter: notExist(key)?
        alt key definitely not exist
            KeyFilter-->>Caller: MissingValue (null)
        else key may exist
            Caller->>L1: getCache(key)
            alt L1 hit
                L1-->>Caller: CacheValue
                Caller->>L2: setCache(key, value)
            else L1 miss
                Caller->>Lock: execute(key) [one leader per key]
                Lock->>L0: loadCacheValue(key)
                L0-->>Lock: CacheValue or null
                alt value found
                    Lock->>L1: stamp-guarded setCache(key, value)
                    Lock->>L2: stamp-guarded setCache(key, value)
                else value not found
                    Lock->>L1: setCache(key, MissingValue(missingTtl))
                    Lock->>L2: setCache(key, MissingValue(missingTtl))
                end
                Lock-->>Caller: CacheValue or null
            end
        end
    end

```

## 模块架构

| 模块 | 说明 | 源码 |
|------|------|------|
| `cocache-api` | 用户 API 与全部 SPI（`Cache`、`CacheValue`、`CacheStore`、`CacheSource`、`CacheEvictedEventBus`） | [cocache-api/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-api) |
| `cocache-core` | 编排与默认实现（`DefaultCoherentCache`、`TtlPolicy`、Caffeine L2、代理） | [cocache-core/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-core) |
| `cocache-spring` | Spring 集成（`@EnableCoCache`、FactoryBean） | [cocache-spring/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring) |
| `cocache-spring-redis` | Redis 分布式缓存实现 | [cocache-spring-redis/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring-redis) |
| `cocache-spring-cache` | Spring Cache 抽象桥接 | [cocache-spring-cache/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring-cache) |
| `cocache-spring-boot-starter` | Spring Boot 自动配置 | [cocache-spring-boot-starter/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring-boot-starter) |
| `cocache-test` | 共享测试规范（TCK） | [cocache-test/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-test) |
| `cocache-example` | 示例应用 | [cocache-example/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example) |
| `cocache-bom` | Bill of Materials | [cocache-bom/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-bom) |
| `cocache-dependencies` | 集中式版本目录 | [cocache-dependencies/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-dependencies) |

## 项目信息

| 属性 | 值 | 源码 |
|------|-----|------|
| Group | `me.ahoo.cocache` | [gradle.properties:14](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle.properties#L14) |
| Version | `5.0.0` | [gradle.properties:15](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle.properties#L15) |
| License | Apache License 2.0 | [gradle.properties:23](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle.properties#L23) |
| JDK | 17+（通过 `jvmToolchain`） | [build.gradle.kts](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts) |
| Gradle | 9.6.1（wrapper） | [gradle/wrapper/gradle-wrapper.properties](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/wrapper/gradle-wrapper.properties) |

## 快速示例

```kotlin
// 1. 定义缓存接口
@CoCache(keyPrefix = "user:", ttl = 120)
@CaffeineCache(maximumSize = 1_000_000, expireAfterAccess = 120)
interface UserCache : Cache<String, User>

// 2. 启用缓存
@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class AppServer

// 3. 使用缓存
@RestController
class UserController(private val userCache: UserCache) {
    @GetMapping("{id}")
    fun get(@PathVariable id: String): User? = userCache[id]
}
```

## 相关页面

- [快速上手](./quick-start.md) -- 几分钟内完成配置并创建第一个缓存
- [配置参考](./configuration.md) -- 所有注解参数和配置属性
- [测试概览](../testing/index.md) -- TCK 测试规范与测试模式
- [性能模式](../testing/performance-patterns.md) -- 缓存击穿、穿透和雪崩防护
