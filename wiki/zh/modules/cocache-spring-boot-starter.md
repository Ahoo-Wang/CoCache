---
title: cocache-spring-boot-starter 模块
description: cocache-spring-boot-starter 模块提供 CoCache 的 Spring Boot 自动配置。它自动注册所有必要的 Bean，配置基于 Redis 的分布式缓存和事件总线，并暴露 Actuator 端点用于缓存监控和管理。
---

# cocache-spring-boot-starter 模块

`cocache-spring-boot-starter` 模块为 Spring Boot 应用中的 CoCache 提供零配置设置。它自动注册所有必需的 Bean（工厂、事件总线、一致性缓存工厂、代理工厂、缓存管理器），并暴露 Spring Boot Actuator 端点用于运行时缓存检查和管理。

## 模块依赖

```mermaid
graph LR
    subgraph sg_40 ["cocache-spring-boot-starter 依赖"]

        spring["cocache-spring"]
        style spring fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        redis["cocache-spring-redis"]
        style redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cache["cocache-spring-cache"]
        style cache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        starter["cocache-spring-boot-starter"]
        style starter fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        boot["spring-boot-starter"]
        style boot fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        data_redis["spring-boot-data-redis"]
        style data_redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        jackson_boot["spring-boot-starter-jackson"]
        style jackson_boot fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        spring --> starter
        redis --> starter
        cache --> starter
        boot --> starter
        data_redis --> starter
        jackson_boot --> starter
    end

```

## 源文件（8 个文件）

| 文件 | 包 | 说明 |
|------|-----|------|
| [CoCacheAutoConfiguration.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheAutoConfiguration.kt#L61) | `...boot.starter` | 主自动配置类，注册所有 Bean |
| [CoCacheProperties.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheProperties.kt#L23) | `...boot.starter` | `cocache.*` 前缀下的配置属性 |
| [ConditionalOnCoCacheEnabled.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/ConditionalOnCoCacheEnabled.kt#L23) | `...boot.starter` | 启用/禁用 CoCache 的条件注解 |
| [EnabledSuffix.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/EnabledSuffix.kt#L20) | `...boot.starter` | `.enabled` 后缀常量 |
| [CoCacheEndpoint.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheEndpoint.kt#L27) | `...boot.starter` | 缓存统计和管理的 Actuator 端点 |
| [CoCacheClientEndpoint.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheClientEndpoint.kt#L25) | `...boot.starter` | 客户端（L2）缓存检查的 Actuator 端点 |
| [AbstractCoCacheEndpoint.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/AbstractCoCacheEndpoint.kt#L19) | `...boot.starter` | 缓存端点的基类 |
| [CoCacheEndpointAutoConfiguration.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheEndpointAutoConfiguration.kt#L27) | `...boot.starter` | Actuator 端点的自动配置 |

## 自动配置注册

CoCache 使用 Spring Boot 标准的 `AutoConfiguration.imports` 机制：

**文件**：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
me.ahoo.cache.spring.boot.starter.CoCacheAutoConfiguration
me.ahoo.cache.spring.boot.starter.CoCacheEndpointAutoConfiguration
```

## CoCacheAutoConfiguration -- Bean 装配

[CoCacheAutoConfiguration](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheAutoConfiguration.kt#L61) 是主自动配置类，使用 `@AutoConfiguration(after = [DataRedisAutoConfiguration::class])` 注解，确保 Redis 先被配置。

```mermaid
graph TB
    subgraph sg_41 ["CoCacheAutoConfiguration Bean 图"]

        props["@EnableConfigurationProperties<br>(CoCacheProperties)"]
        style props fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cond["@ConditionalOnCoCacheEnabled<br>(cocache.enabled=true)"]
        style cond fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cid["ClientIdGenerator<br>(默认：HostClientIdGenerator)"]
        style cid fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cf["CacheFactory<br>(SpringCacheFactory)"]
        style cf fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        ccm["CoCacheManager<br>(Spring Cache 桥接)"]
        style ccm fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        rmlc["RedisMessageListenerContainer<br>(用于 Pub/Sub)"]
        style rmlc fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        eeb["CacheEvictedEventBus<br>(RedisCacheEvictedEventBus)"]
        style eeb fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        ccf["CoherentCacheFactory<br>(DefaultCoherentCacheFactory)"]
        style ccf fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        csf["CacheSourceFactory<br>(SpringCacheSourceFactory)"]
        style csf fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cscf["ClientSideCacheFactory<br>(SpringClientSideCacheFactory)"]
        style cscf fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        dcf["DistributedCacheFactory<br>(RedisDistributedCacheFactory)"]
        style dcf fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        kcf["KeyConverterFactory<br>(SpringKeyConverterFactory)"]
        style kcf fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cpf["CacheProxyFactory<br>(DefaultCacheProxyFactory)"]
        style cpf fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        jkef["JoinKeyExtractorFactory<br>(SpringJoinKeyExtractorFactory)"]
        style jkef fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        jcpf["JoinCacheProxyFactory<br>(DefaultJoinCacheProxyFactory)"]
        style jcpf fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        props --> cond
        cond --> cid
        cond --> cf
        cf --> ccm
        cond --> rmlc
        rmlc --> eeb
        eeb --> ccf
        cond --> csf
        cond --> cscf
        cond --> dcf
        cond --> kcf
        cid --> cpf
        ccf --> cpf
        cscf --> cpf
        dcf --> cpf
        csf --> cpf
        kcf --> cpf
        cf --> jcpf
        jkef --> jcpf
    end

```

### 完整 Bean 列表

| Bean | 类型 | 条件 | 默认实现 |
|------|------|------|----------|
| `defaultHostClientIdGenerator` | `ClientIdGenerator` | `@ConditionalOnMissingBean(ClientIdGenerator, HostAddressSupplier)` | `ClientIdGenerator.HOST` |
| `cacheFactory` | `CacheFactory` | `@ConditionalOnMissingBean` | `SpringCacheFactory` |
| `coCacheManager` | `CoCacheManager` | （始终注册） | `CoCacheManager(cacheFactory)` |
| `cocacheRedisMessageListenerContainer` | `RedisMessageListenerContainer` | `@ConditionalOnMissingBean`、`@ConditionalOnSingleCandidate(RedisConnectionFactory)` | 使用 `redisConnectionFactory` 的容器 |
| `cacheEvictedEventBus` | `CacheEvictedEventBus` | `@ConditionalOnMissingBean` | `RedisCacheEvictedEventBus` |
| `coherentCacheFactory` | `CoherentCacheFactory` | `@ConditionalOnMissingBean` | `DefaultCoherentCacheFactory` |
| `cacheSourceFactory` | `CacheSourceFactory` | `@ConditionalOnMissingBean` | `SpringCacheSourceFactory` |
| `clientSideCacheFactory` | `ClientSideCacheFactory` | `@ConditionalOnMissingBean` | `SpringClientSideCacheFactory` |
| `distributedCacheFactory` | `DistributedCacheFactory` | `@ConditionalOnMissingBean` | `RedisDistributedCacheFactory` |
| `keyConverterFactory` | `KeyConverterFactory` | `@ConditionalOnMissingBean` | `SpringKeyConverterFactory` |
| `cacheProxyFactory` | `CacheProxyFactory` | `@ConditionalOnMissingBean` | `DefaultCacheProxyFactory` |
| `joinKeyExtractorFactory` | `JoinKeyExtractorFactory` | `@ConditionalOnMissingBean` | `SpringJoinKeyExtractorFactory` |
| `joinCacheProxyFactory` | `JoinCacheProxyFactory` | `@ConditionalOnMissingBean` | `DefaultJoinCacheProxyFactory` |

每个 Bean 都标注了 `@ConditionalOnMissingBean`，允许用户通过简单地声明自己的 Bean 来覆盖任何组件。

## CoCacheProperties

[CoCacheProperties](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheProperties.kt#L23) 映射到 `cocache.*` 前缀：

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `cocache.enabled` | `Boolean` | `true` | 启用/禁用 CoCache 的主开关 |

```yaml
# application.yml
cocache:
  enabled: true  # 设置为 false 可禁用所有 CoCache Bean
```

## @ConditionalOnCoCacheEnabled

[@ConditionalOnCoCacheEnabled](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/ConditionalOnCoCacheEnabled.kt#L23) 是一个组合注解，检查 `cocache.enabled=true`。它使用 `matchIfMissing = true`，因此当属性未设置时 CoCache 默认启用。

## Actuator 端点

### CoCacheEndpoint (`/actuator/cocache`)

[CoCacheEndpoint](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheEndpoint.kt#L27) 提供全面的缓存管理：

```mermaid
graph LR
    subgraph sg_42 ["CoCacheEndpoint 操作"]

        total["GET /actuator/cocache<br>-> 所有缓存报告列表"]
        style total fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        stat["GET /actuator/cocache/{name}<br>-> 命名缓存的 CacheReport"]
        style stat fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        get["GET /actuator/cocache/{name}/{key}<br>-> 特定条目的 CacheValue"]
        style get fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        evict["DELETE /actuator/cocache/{name}/{key}<br>-> 驱逐缓存条目"]
        style evict fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        total --- stat
        stat --- get
        stat --- evict
    end

```

端点返回的 `CacheReport` 数据类包含：

| 字段 | 说明 |
|------|------|
| `name` | 缓存名称 |
| `clientId` | 当前实例的客户端 ID |
| `clientSize` | L2 客户端缓存中的条目数量 |
| `keyConverter` | 键转换器的 `toString()` |
| `distributedCaching` | 分布式缓存的全限定类名 |
| `clientSideCaching` | 客户端缓存的全限定类名 |
| `cacheEvictedEventBus` | 事件总线的全限定类名 |
| `cacheSource` | 缓存数据源的全限定类名 |
| `keyFilter` | 键过滤器的全限定类名 |

### CoCacheClientEndpoint (`/actuator/cocacheClient`)

[CoCacheClientEndpoint](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheClientEndpoint.kt#L25) 提供 L2（客户端）缓存检查：

| 操作 | HTTP | 说明 |
|------|------|------|
| `getSize` | `GET /actuator/cocacheClient/{name}` | 返回 L2 客户端缓存的大小 |
| `get` | `GET /actuator/cocacheClient/{name}/{key}` | 返回特定键（经过键转换后）的 L2 缓存值 |
| `clear` | `DELETE /actuator/cocacheClient/{name}` | 清除指定缓存的整个 L2 客户端缓存 |

### 端点自动配置

[CoCacheEndpointAutoConfiguration](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheEndpointAutoConfiguration.kt#L27) 以 classpath 上存在 `Endpoint`（即包含 Spring Boot Actuator）为条件。它注册两个端点 Bean。

## 端点类层次结构

```mermaid
classDiagram
    class AbstractCoCacheEndpoint {
        <<abstract>>
        +cacheFactory: CacheFactory
        #coherentCache(name) CoherentCache?
    }

    class CoCacheEndpoint {
        +@Endpoint(id="cocache")
        +total() List~CacheReport~
        +stat(name) CacheReport?
        +evict(name, key)
        +get(name, key) CacheValue?
    }

    class CoCacheClientEndpoint {
        +@Endpoint(id="cocacheClient")
        +getSize(name) Long?
        +get(name, key) CacheValue?
        +clear(name)
    }

    class CacheReport {
        <<data class>>
        +name: String
        +clientId: String
        +clientSize: Long
        +keyConverter: String
        +distributedCaching: String
        +clientSideCaching: String
    }

    AbstractCoCacheEndpoint <|-- CoCacheEndpoint
    AbstractCoCacheEndpoint <|-- CoCacheClientEndpoint
    CoCacheEndpoint --> CacheReport : 返回
```

## 启用/禁用决策流程

```mermaid
flowchart TB
    subgraph sg_43 ["CoCache 激活逻辑"]

        boot["Spring Boot 启动"]
        style boot fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        check_prop{"cocache.enabled<br>属性?"}
        style check_prop fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        not_set["属性未设置<br>(matchIfMissing=true)"]
        style not_set fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        is_true{"value == true?"}
        style is_true fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        activate["加载 CoCacheAutoConfiguration<br>注册所有 Bean"]
        style activate fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        endpoints{"Actuator 在<br>classpath 上?"}
        style endpoints fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        register_ep["注册 CoCacheEndpoint<br>注册 CoCacheClientEndpoint"]
        style register_ep fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        skip_ep["跳过端点注册"]
        style skip_ep fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        disabled["CoCache 已禁用<br>(所有 Bean 被跳过)"]
        style disabled fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        boot --> check_prop
        check_prop -->|未设置| not_set --> activate
        check_prop -->|已设置| is_true
        is_true -->|是| activate
        is_true -->|否| disabled
        activate --> endpoints
        endpoints -->|是| register_ep
        endpoints -->|否| skip_ep
    end

```

## CosID 集成

自动配置在 [CoCacheAutoConfiguration.kt:176](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheAutoConfiguration.kt#L176) 包含一个嵌套的 `CosIdHostAddressSupplierAutoConfiguration` 类，当 [CosID](https://github.com/Ahoo-Wang/CosID) 可用时进行集成。如果存在来自 CosID 的 `HostAddressSupplier` Bean，它会创建一个使用 CosID 主机地址解析的 `HostClientIdGenerator`。

## 启动序列

```mermaid
sequenceDiagram
autonumber
    participant Boot as Spring Boot
    participant DRA as DataRedisAutoConfiguration
    participant CCA as CoCacheAutoConfiguration
    participant App as ApplicationContext
    participant Registrar as EnableCoCacheRegistrar

    Boot->>DRA: 配置 Redis Bean
    DRA-->>Boot: StringRedisTemplate, ConnectionFactory
    Boot->>CCA: 自动配置 CoCache Bean
    CCA->>CCA: 注册 ClientIdGenerator
    CCA->>CCA: 注册 CacheFactory
    CCA->>CCA: 注册 CoCacheManager
    CCA->>CCA: 注册 RedisMessageListenerContainer
    CCA->>CCA: 注册 CacheEvictedEventBus
    CCA->>CCA: 注册 CoherentCacheFactory
    CCA->>CCA: 注册所有 *Factory Bean
    CCA->>CCA: 注册 CacheProxyFactory
    CCA->>CCA: 注册 JoinCacheProxyFactory
    CCA-->>Boot: 所有 Bean 已注册
    Boot->>Registrar: 处理 @EnableCoCache
    Registrar->>App: 为每个缓存注册 CacheProxyFactoryBean / JoinCacheProxyFactoryBean
    App->>App: 实例化缓存代理（延迟 FactoryBean）
```

## 在 Spring Boot 应用中启用 CoCache

最小化设置：

```kotlin
// 1. 添加依赖 (build.gradle.kts)
dependencies {
    implementation("me.ahoo.cococache:cocache-spring-boot-starter")
}

// 2. 定义缓存接口
@CoCache(name = "userCache", keyPrefix = "user:", ttl = 3600)
interface UserCache : Cache<String, User>

// 3. 启用 CoCache
@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class MyApplication
```

自动配置会处理其余所有事情：Redis 连接、事件总线、代理创建和缓存管理器注册。

## 相关页面

- [模块概览](./index.md) -- 依赖关系图和模块说明
- [cocache-spring](./cocache-spring.md) -- @EnableCoCache 和 AbstractCacheFactory
- [cocache-spring-redis](./cocache-spring-redis.md) -- Redis 分布式缓存和事件总线
- [cocache-spring-cache](./cocache-spring-cache.md) -- Spring Cache 桥接（CoCacheManager）
