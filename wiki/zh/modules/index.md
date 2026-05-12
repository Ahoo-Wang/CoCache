---
title: CoCache 模块概览
description: CoCache 各模块的完整概览，包括它们的依赖关系以及如何共同组成一个二级分布式一致性缓存框架。
---

# 模块概览

CoCache 由一组专注的 Gradle 模块组成，从纯 API 定义到核心逻辑、Spring 集成、Redis 持久化和 Spring Boot 自动配置逐层叠加。每个模块具有单一职责，仅依赖依赖图中位于其下方的模块。

## 模块依赖图

```mermaid
graph TB
    subgraph sg_53 ["CoCache 模块架构"]

        cocache-bom["cocache-bom<br>(物料清单)"]
        style cocache-bom fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-dependencies["cocache-dependencies<br>(版本目录)"]
        style cocache-dependencies fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-api["cocache-api<br>(核心接口)"]
        style cocache-api fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-core["cocache-core<br>(默认实现)"]
        style cocache-core fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-spring["cocache-spring<br>(Spring 集成)"]
        style cocache-spring fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-spring-redis["cocache-spring-redis<br>(Redis 实现)"]
        style cocache-spring-redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-spring-cache["cocache-spring-cache<br>(Spring Cache 桥接)"]
        style cocache-spring-cache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-spring-boot-starter["cocache-spring-boot-starter<br>(自动配置)"]
        style cocache-spring-boot-starter fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-test["cocache-test<br>(共享测试规范)"]
        style cocache-test fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cocache-example["cocache-example<br>(示例应用)"]
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

## 模块说明

| 模块 | 用途 | 关键内容 | 源码 |
|------|------|----------|------|
| **cocache-api** | 纯接口和注解，零实现依赖 | `Cache`、`CacheValue`、`ClientSideCache`、`CacheSource`、`JoinCache`、`@CoCache`、`@JoinCacheable` | [cocache-api/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-api) |
| **cocache-core** | 所有核心抽象的默认实现 | `DefaultCoherentCache`、`CoCacheProxy`、`SimpleJoinCache`、`MapClientSideCache`、`GuavaClientSideCache`、`CaffeineClientSideCache`、`BloomKeyFilter`、`CacheEvictedEventBus` | [cocache-core/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-core) |
| **cocache-spring** | Spring 框架集成，基于 DI 的缓存创建 | `@EnableCoCache`、`EnableCoCacheRegistrar`、`AbstractCacheFactory`、`CacheProxyFactoryBean`、`JoinCacheProxyFactoryBean` | [cocache-spring/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring) |
| **cocache-spring-redis** | 基于 Redis 的分布式缓存和事件总线 | `RedisDistributedCache`、`RedisCacheEvictedEventBus`、`CodecExecutor` 层次结构 | [cocache-spring-redis/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring-redis) |
| **cocache-spring-cache** | Spring `CacheManager` 抽象的桥接 | `CoCacheManager`、`CoSpringCache` | [cocache-spring-cache/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring-cache) |
| **cocache-spring-boot-starter** | Spring Boot 自动配置和 Actuator 端点 | `CoCacheAutoConfiguration`、`CoCacheProperties`、`CoCacheEndpoint`、`CoCacheClientEndpoint` | [cocache-spring-boot-starter/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-spring-boot-starter) |
| **cocache-test** | 共享的抽象测试规范，用于验证缓存实现 | `CacheSpec`、`DistributedCacheSpec`、`ClientSideCacheSpec`、`MultipleInstanceSyncSpec` | [cocache-test/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-test) |
| **cocache-example** | 示例应用 | `UserCache`、`UserExtendInfoJoinCache`、示例配置 | [cocache-example/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example) |
| **cocache-bom** | 依赖管理的物料清单 | BOM POM 发布 | [cocache-bom/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-bom) |
| **cocache-dependencies** | 集中版本目录 | 所有第三方依赖版本 | [cocache-dependencies/](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-dependencies) |
| **code-coverage-report** | 聚合的 JaCoCo 覆盖率 | 多模块覆盖率聚合 | [code-coverage-report/](https://github.com/Ahoo-Wang/CoCache/tree/main/code-coverage-report) |

## Gradle 构建配置

所有模块都在 [settings.gradle.kts](https://github.com/Ahoo-Wang/CoCache/blob/main/settings.gradle.kts) 中声明：

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

### 构建依赖链

`build.gradle.kts` 中的依赖声明建立了以下编译时依赖链：

```mermaid
flowchart LR
    subgraph sg_54 ["构建依赖"]

        api["cocache-api<br>(无依赖)"]
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

## 分层架构

```mermaid
graph TB
    subgraph sg_55 ["应用层"]

        app["应用程序代码<br>(@CoCache 接口)"]
        style app fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
        spring_boot["Spring Boot Starter<br>(自动配置)"]
        style spring_boot fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    end

    subgraph sg_56 ["集成层"]

        spring_mod["cocache-spring<br>(@EnableCoCache)"]
        style spring_mod fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
        redis_mod["cocache-spring-redis<br>(Redis 实现)"]
        style redis_mod fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
        spring_cache_mod["cocache-spring-cache<br>(CacheManager)"]
        style spring_cache_mod fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    end

    subgraph sg_57 ["核心层"]

        core_mod["cocache-core<br>(CoherentCache、Proxy、TTL)"]
        style core_mod fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    end

    subgraph sg_58 ["API 层"]

        api_mod["cocache-api<br>(接口、注解)"]
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

## 两级缓存数据流

```mermaid
flowchart LR
    subgraph sg_59 ["缓存读取路径 (L2 -> L1 -> 数据源)"]

        app_req["应用程序<br>get(key)"]
        style app_req fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        l2["L2: ClientSideCache<br>(本地内存)"]
        style l2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        l1["L1: DistributedCache<br>(Redis)"]
        style l1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        ds["L0: CacheSource<br>(数据库)"]
        style ds fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        result["返回值"]
        style result fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        app_req --> l2
        l2 -->|"未命中"| l1
        l1 -->|"未命中"| ds
        ds -->|"找到"| result
        l1 -->|"命中<br>(填充 L2)"| result
        l2 -->|"命中"| result
    end

```

## 核心接口层次结构

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

## 关键设计原则

1. **接口隔离**：`cocache-api` 仅包含接口和注解，允许下游模块仅依赖契约而无实现耦合。

2. **工厂模式**：每个主要组件（`ClientSideCache`、`DistributedCache`、`CacheSource`、`KeyConverter`、`JoinKeyExtractor`）在 `cocache-core` 中都有对应的工厂接口，在 `cocache-spring` 中有 Spring 感知的实现。

3. **AbstractCacheFactory**：[AbstractCacheFactory](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/AbstractCacheFactory.kt) 基类提供了统一的 Spring Bean 解析模式——先按约定名称查找 Bean，再回退到类型查找，最后使用默认工厂方法。

4. **插件架构**：用户可以通过简单地声明具有预期名称或类型的 Spring Bean 来替换任何组件（客户端缓存、分布式缓存、编解码器、事件总线）。

## 相关页面

- [cocache-api](./cocache-api.md) -- 所有接口和注解
- [cocache-core](./cocache-core.md) -- 默认实现和核心逻辑
- [cocache-spring](./cocache-spring.md) -- Spring 框架集成
- [cocache-spring-redis](./cocache-spring-redis.md) -- Redis 分布式缓存实现
- [cocache-spring-boot-starter](./cocache-spring-boot-starter.md) -- 自动配置和端点
- [cocache-spring-cache](./cocache-spring-cache.md) -- Spring Cache 抽象桥接
