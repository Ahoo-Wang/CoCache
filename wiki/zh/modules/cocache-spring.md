---
title: cocache-spring 模块
description: cocache-spring 模块将 CoCache 与 Spring 框架集成。它提供 @EnableCoCache 用于声明式缓存注册、AbstractCacheFactory 用于 Spring Bean 解析，以及 FactoryBean 用于创建与 Spring 管理组件连接的缓存代理。
---

# cocache-spring 模块

`cocache-spring` 模块将 CoCache 的核心抽象与 Spring 框架的依赖注入容器桥接起来。它通过 `@EnableCoCache` 实现声明式缓存注册，为所有缓存组件提供自动的 Spring Bean 解析，并基于 FactoryBean 创建缓存代理。

## 模块依赖

```mermaid
graph LR
    subgraph sg_50 ["cocache-spring 依赖"]

        core["cocache-core"]
        style core fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        spring_mod["cocache-spring"]
        style spring_mod fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        spring_ctx["spring-context"]
        style spring_ctx fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        core --> spring_mod
        spring_ctx --> spring_mod
    end

```

## 源文件（10 个文件）

| 文件 | 包 | 用途 |
|------|-----|------|
| [EnableCoCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCache.kt#L22) | `me.ahoo.cache.spring` | `@EnableCoCache` 注解，触发缓存注册 |
| [EnableCoCacheRegistrar.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCacheRegistrar.kt#L31) | `me.ahoo.cache.spring` | `ImportBeanDefinitionRegistrar` 实现，解析缓存类并注册 Bean |
| [AbstractCacheFactory.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/AbstractCacheFactory.kt#L21) | `me.ahoo.cache.spring` | Spring 感知工厂模式的基类，支持 Bean 名称查找 |
| [SpringCacheFactory.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/SpringCacheFactory.kt#L24) | `me.ahoo.cache.spring` | 使用 Spring `ListableBeanFactory` 的 `CacheFactory` 实现 |
| [CacheProxyFactoryBean.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBean.kt#L23) | `me.ahoo.cache.spring.proxy` | 标准 `Cache` 代理的 `FactoryBean` |
| [JoinCacheProxyFactoryBean.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBean.kt#L23) | `me.ahoo.cache.spring.join` | `JoinCache` 代理的 `FactoryBean` |
| [SpringClientSideCacheFactory.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/client/SpringClientSideCacheFactory.kt#L25) | `me.ahoo.cache.spring.client` | 解析 `ClientSideCache` Bean，否则回退到默认实现 |
| [SpringKeyConverterFactory.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/converter/SpringKeyConverterFactory.kt#L27) | `me.ahoo.cache.spring.converter` | 解析 `KeyConverter` Bean 或创建 `ToStringKeyConverter`/`ExpKeyConverter` |
| [SpringCacheSourceFactory.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/source/SpringCacheSourceFactory.kt#L24) | `me.ahoo.cache.spring.source` | 解析 `CacheSource` Bean 或默认使用 `NoOpCacheSource` |
| [SpringJoinKeyExtractorFactory.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/SpringJoinKeyExtractorFactory.kt#L24) | `me.ahoo.cache.spring.join` | 解析 `JoinKeyExtractor` Bean 或根据表达式创建 `ExpJoinKeyExtractor` |

## @EnableCoCache -- 注册入口

[@EnableCoCache](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCache.kt#L22) 是主要的入口点：

```kotlin
@Import(EnableCoCacheRegistrar::class)
@Target(AnnotationTarget.CLASS)
annotation class EnableCoCache(
    val caches: Array<KClass<out Cache<*, *>>> = []
)
```

使用方式：

```kotlin
@EnableCoCache(caches = [UserCache::class, ProductCache::class, UserProductJoinCache::class])
@Configuration
class CacheConfiguration
```

## 注册流程

```mermaid
flowchart TB
    subgraph sg_51 ["EnableCoCacheRegistrar 注册流程"]

        scan["@EnableCoCache(caches=[...])<br>ImportBeanDefinitionRegistrar"]
        style scan fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        parse["解析 AnnotationMetadata<br>提取缓存类"]
        style parse fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        filter1{"是 JoinCache?"}
        style filter1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        co_cache["解析为 CoCacheMetadata<br>(通过 toCoCacheMetadata())"]
        style co_cache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        join_cache["解析为 JoinCacheMetadata<br>(通过 toJoinCacheMetadata())"]
        style join_cache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        register_metadata["注册 CoCacheMetadata Bean<br>(name + '.CacheMetadata')"]
        style register_metadata fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        register_cache["注册 CacheProxyFactoryBean<br>(name = cacheName, primary=true)"]
        style register_cache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        register_join["注册 JoinCacheProxyFactoryBean<br>(name = cacheName, primary=true)"]
        style register_join fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        scan --> parse --> filter1
        filter1 -->|否| co_cache --> register_metadata --> register_cache
        filter1 -->|是| join_cache --> register_join
    end

```

[EnableCoCacheRegistrar.kt:45](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCacheRegistrar.kt#L45) 中的注册器执行以下步骤：

1. 从 `@EnableCoCache` 注解属性中提取 `caches` 数组。
2. 将缓存类分为两组：实现 `JoinCache` 的和未实现的。
3. 对于非 JoinCache 类：通过 `KClass.toCoCacheMetadata()` 解析 `CoCacheMetadata`，注册元数据 Bean 和 `CacheProxyFactoryBean`。
4. 对于 JoinCache 类：通过 `KClass.toJoinCacheMetadata()` 解析 `JoinCacheMetadata`，注册 `JoinCacheProxyFactoryBean`。

## AbstractCacheFactory 模式

[AbstractCacheFactory](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/AbstractCacheFactory.kt#L21) 是所有 Spring 感知组件工厂的共享基类。它实现了三级解析策略：

```mermaid
flowchart TB
    subgraph sg_52 ["AbstractCacheFactory.createBean(cacheMetadata)"]

        bean_name["计算 beanName<br>= cacheName + suffix"]
        style bean_name fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        exists{"beanFactory<br>.containsBean<br>(beanName)?"}
        style exists fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        by_name["返回 beanFactory<br>.getBean(beanName)"]
        style by_name fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        by_type["getBeanProvider(type)<br>.getIfAvailable()"]
        style by_type fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        type_found{"按类型找到<br>Bean?"}
        style type_found fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        fallback["调用 fallback()<br>(默认实现)"]
        style fallback fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        ttl_aware{"实现了 TtlConfigurationAware?"}
        style ttl_aware fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        set_ttl["setTtlConfiguration(metadata)"]
        style set_ttl fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        done["返回 Bean"]
        style done fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        bean_name --> exists
        exists -->|是| by_name --> done
        exists -->|否| by_type --> type_found
        type_found -->|是| ttl_aware
        type_found -->|否| fallback --> ttl_aware
        ttl_aware -->|是| set_ttl --> done
        ttl_aware -->|否| done
    end

```

每个子类定义：
- **`suffix`**：基于约定的 Bean 名称后缀（例如 `".ClientSideCache"`、`".DistributedCache"`、`".KeyConverter"`、`".CacheSource"`、`".JoinKeyExtractor"`）
- **`getBeanType()`**：用于基于类型查找 Bean 的 `ResolvableType`
- **`fallback()`**：未找到 Spring Bean 时的默认工厂方法

### 工厂后缀与 Bean 命名

| 工厂 | 后缀 | Bean 名称示例 |
|------|------|---------------|
| `SpringClientSideCacheFactory` | `.ClientSideCache` | `UserCache.ClientSideCache` |
| `SpringKeyConverterFactory` | `.KeyConverter` | `UserCache.KeyConverter` |
| `SpringCacheSourceFactory` | `.CacheSource` | `UserCache.CacheSource` |
| `RedisDistributedCacheFactory`（在 cocache-spring-redis 中） | `.DistributedCache` | `UserCache.DistributedCache` |
| `SpringJoinKeyExtractorFactory` | `.JoinKeyExtractor` | `UserProductJoinCache.JoinKeyExtractor` |

这种命名约定允许用户通过声明一个具有预期名称的 Spring Bean 来覆盖任何组件。

## CacheProxyFactoryBean

[CacheProxyFactoryBean](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBean.kt#L23) 是一个 Spring `FactoryBean`，用于创建缓存代理实例。它从 `ApplicationContext` 中延迟获取 `CacheProxyFactory` 并委托创建：

```mermaid
sequenceDiagram
autonumber
    participant SB as Spring BeanFactory
    participant CPFB as CacheProxyFactoryBean
    participant CPF as CacheProxyFactory<br>(DefaultCacheProxyFactory)
    participant CCF as CoherentCacheFactory
    participant EB as CacheEvictedEventBus

    SB->>CPFB: getObject()
    CPFB->>SB: getBean(CacheProxyFactory)
    SB-->>CPFB: DefaultCacheProxyFactory
    CPFB->>CPF: create(cacheMetadata)
    CPF->>CPF: 生成 clientId
    CPF->>CPF: 创建 L2 (ClientSideCache)
    CPF->>CPF: 创建 L1 (DistributedCache)
    CPF->>CPF: 创建 L0 (CacheSource)
    CPF->>CPF: 创建 KeyConverter
    CPF->>CCF: create(CoherentCacheConfiguration)
    CCF->>CCF: new DefaultCoherentCache(...)
    CCF->>EB: register(coherentCache)
    CCF-->>CPF: CoherentCache
    CPF->>CPF: new CoCacheInvocationHandler(metadata, delegate)
    CPF->>CPF: Proxy.newProxyInstance(...)
    CPF-->>CPFB: 缓存代理
    CPFB-->>SB: 缓存代理 (作为 FactoryBean 结果)
```

## JoinCacheProxyFactoryBean

[JoinCacheProxyFactoryBean](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBean.kt#L23) 遵循相同的模式，但获取的是 `JoinCacheProxyFactory`，并创建与第一个缓存、join 缓存和 join 键提取器连接的 JoinCache 代理。

## SpringCacheFactory

[SpringCacheFactory](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/SpringCacheFactory.kt#L24) 使用 Spring 的 `ListableBeanFactory` 实现 `CacheFactory` 接口：

| 方法 | 策略 |
|------|------|
| `caches` | `beanFactory.getBeansOfType(Cache::class.java)` |
| `getCache(name, type)` | `beanFactory.getBean(name, type)` 并处理 `NoSuchBeanDefinitionException` |
| `getCache(keyType, valueType)` | `beanFactory.getBeanProvider(ResolvableType)` 用于泛型类型匹配 |

## SpringKeyConverterFactory

[SpringKeyConverterFactory](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/converter/SpringKeyConverterFactory.kt#L27) 对 `String` 键类型有特殊处理——当键类型为 `String` 时，它跳过 Bean 提供者查找，直接进入 `fallback()`，因为 `String` 键不需要类型化转换器。

[SpringKeyConverterFactory.kt:50](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/converter/SpringKeyConverterFactory.kt#L50) 中的回退逻辑：

1. 从 `@CoCache` 中解析 `keyPrefix`（支持 Spring 属性占位符）。
2. 如果没有前缀，默认使用 `"cocache:{cacheName}:"`。
3. 如果设置了 `keyExpression`，创建 `ExpKeyConverter`。
4. 否则，创建 `ToStringKeyConverter`。

## SpringJoinKeyExtractorFactory

[SpringJoinKeyExtractorFactory](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/SpringJoinKeyExtractorFactory.kt#L24) 按以下顺序解析 join 键提取器：

1. 如果 `@JoinCacheable` 中设置了 `joinKeyExpression`，创建 `ExpJoinKeyExtractor`。
2. 按名称查找 Bean（`cacheName + ".JoinKeyExtractor"`）。
3. 按类型查找唯一 Bean（`JoinKeyExtractor<V1, K2>`）。
4. 如果都未找到，抛出错误。

## 工厂层次结构

```mermaid
classDiagram
    class AbstractCacheFactory {
        <<abstract>>
        #beanFactory: BeanFactory
        +suffix: String
        +createBean(cacheMetadata) Any
        #getBeanName(cacheMetadata) String
        #getBeanType(cacheMetadata) ResolvableType
        #getBeanProvider(metadata, fallback) Any
        #fallback(cacheMetadata) Any
    }

    class ClientSideCacheFactory {
        <<interface>>
        +create(cacheMetadata) ClientSideCache
    }

    class KeyConverterFactory {
        <<interface>>
        +create(cacheMetadata) KeyConverter
    }

    class CacheSourceFactory {
        <<interface>>
        +create(cacheMetadata) CacheSource
    }

    class SpringClientSideCacheFactory {
        +suffix = ".ClientSideCache"
        +fallback() DefaultClientSideCacheFactory
    }

    class SpringKeyConverterFactory {
        +suffix = ".KeyConverter"
        +fallback() ToStringKeyConverter or ExpKeyConverter
    }

    class SpringCacheSourceFactory {
        +suffix = ".CacheSource"
        +fallback() NoOpCacheSource
    }

    AbstractCacheFactory <|-- SpringClientSideCacheFactory
    AbstractCacheFactory <|-- SpringKeyConverterFactory
    AbstractCacheFactory <|-- SpringCacheSourceFactory
    ClientSideCacheFactory <|.. SpringClientSideCacheFactory
    KeyConverterFactory <|.. SpringKeyConverterFactory
    CacheSourceFactory <|.. SpringCacheSourceFactory
```

## JoinCache 注册流程

```mermaid
sequenceDiagram
autonumber
    participant User as @EnableCoCache
    participant Reg as EnableCoCacheRegistrar
    participant BDR as BeanDefinitionRegistry
    participant App as ApplicationContext
    participant JPF as JoinCacheProxyFactory

    User->>Reg: registerBeanDefinitions(metadata, registry)
    Reg->>Reg: 解析 @EnableCoCache caches 数组
    Reg->>Reg: 过滤 JoinCache 子类
    Reg->>Reg: toJoinCacheMetadata()
    Reg->>BDR: 注册 JoinCacheProxyFactoryBean
    BDR-->>Reg: Bean 已注册

    Note over App: 稍后，在上下文刷新期间...
    App->>App: getBean(JoinCacheProxyFactoryBean)
    App->>App: getObject()
    App->>JPF: create(joinCacheMetadata)
    JPF->>JPF: 解析 firstCache、joinCache、joinKeyExtractor
    JPF->>JPF: 创建 SimpleJoinCache(first, join, extractor)
    JPF->>JPF: 包装为 JoinCacheProxy
    JPF-->>App: JoinCache 代理
```

## 自定义示例

用户可以通过声明 Spring Bean 来覆盖任何组件：

```kotlin
@Configuration
class CustomCacheConfig {

    // 覆盖 UserCache 的客户端缓存
    @Bean("UserCache.ClientSideCache")
    fun userCacheClientSide(): ClientSideCache<User> {
        return CaffeineClientSideCache(
            Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofMinutes(30))
                .build()
        )
    }

    // 覆盖 UserCache 的缓存数据源
    @Bean("UserCache.CacheSource")
    fun userCacheSource(userRepository: UserRepository): CacheSource<String, User> {
        return CacheSource { key ->
            val user = userRepository.findById(key)
            user.map { DefaultCacheValue.ttlAt(it, 3600) }.orElse(null)
        }
    }
}
```

## 相关页面

- [模块概览](./index.md) -- 依赖关系图和模块说明
- [cocache-api](./cocache-api.md) -- 接口和注解
- [cocache-core](./cocache-core.md) -- 默认实现
- [cocache-spring-redis](./cocache-spring-redis.md) -- Redis 分布式缓存实现
- [cocache-spring-boot-starter](./cocache-spring-boot-starter.md) -- 自动配置
- [cocache-spring-cache](./cocache-spring-cache.md) -- Spring Cache 抽象桥接
