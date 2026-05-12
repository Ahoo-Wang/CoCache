---
title: Proxy and Annotation System
description: How CoCache uses @CoCache annotations and JDK dynamic proxies to create declarative cache interfaces. Covers EnableCoCacheRegistrar, CoCacheMetadata parsing, CoCacheProxy, CoCacheInvocationHandler, CacheProxyFactoryBean, and JoinCacheProxy flow.
---

# Proxy and Annotation System

CoCache uses a declarative, annotation-driven approach to cache configuration. Developers define cache interfaces annotated with `@CoCache`, and the framework automatically creates JDK dynamic proxy implementations backed by `DefaultCoherentCache`. This system eliminates boilerplate cache wiring and allows cache behavior to be configured entirely through annotations.

## Overview

```mermaid
flowchart TD
    subgraph "Developer Defines"

        Interface["Cache Interface<br>+ @CoCache annotation"]
    end

    subgraph "Startup Registration"

        Enable["@EnableCoCache<br>caches = [...]"]
        Registrar["EnableCoCacheRegistrar"]
        Parse["CoCacheMetadataParser<br>parse interface"]
        RegisterBean["Register CacheProxyFactoryBean<br>as Spring Bean"]
    end

    subgraph "Bean Creation"

        FactoryBean["CacheProxyFactoryBean<br>.getObject()"]
        ProxyFactory["DefaultCacheProxyFactory<br>.create(metadata)"]
        InvocationHandler["CoCacheInvocationHandler"]
        Proxy["JDK Proxy<br>implements Cache interface"]
    end

    Interface --> Enable
    Enable --> Registrar
    Registrar --> Parse
    Parse --> RegisterBean
    RegisterBean --> FactoryBean
    FactoryBean --> ProxyFactory
    ProxyFactory --> InvocationHandler
    InvocationHandler --> Proxy

    style Interface fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Enable fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Registrar fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Parse fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style RegisterBean fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style FactoryBean fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ProxyFactory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style InvocationHandler fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Proxy fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## The @EnableCoCache Annotation

The entry point is [`@EnableCoCache`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCache.kt#L20), a Spring `@Import` annotation that triggers the registration process:

```kotlin
@Import(EnableCoCacheRegistrar::class)
@Target(AnnotationTarget.CLASS)
annotation class EnableCoCache(
    val caches: Array<KClass<out Cache<*, *>>> = []
)
```

Usage in a Spring configuration class:

```kotlin
@EnableCoCache(caches = [UserProfileCache::class, ProductCache::class])
class CacheConfiguration
```

## EnableCoCacheRegistrar -- Bean Definition Registration

[`EnableCoCacheRegistrar`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCacheRegistrar.kt#L31) implements Spring's `ImportBeanDefinitionRegistrar` interface. During application startup, it:

1. Reads the `caches` array from the `@EnableCoCache` annotation
2. Separates `JoinCache` types from regular `Cache` types
3. Parses each interface into `CoCacheMetadata` or `JoinCacheMetadata`
4. Registers Spring `FactoryBean` definitions for each cache

```mermaid
sequenceDiagram
autonumber
    autonumber
    participant SC as Spring Container
    participant R as EnableCoCacheRegistrar
    participant P as CoCacheMetadataParser
    participant F as CacheProxyFactoryBean
    participant JF as JoinCacheProxyFactoryBean

    SC->>R: registerBeanDefinitions(metadata, registry)
    R->>R: getCacheTypes from @EnableCoCache annotation

    loop For each non-JoinCache type
        R->>P: parse(KClass) via toCoCacheMetadata()
        P-->>R: CoCacheMetadata
        R->>R: Register CoCacheMetadata bean
        R->>R: Register CacheProxyFactoryBean bean
    end

    loop For each JoinCache type
        R->>R: parse via toJoinCacheMetadata()
        R->>R: Register JoinCacheProxyFactoryBean bean
    end

    SC->>F: getObject() [lazy bean creation]
    F-->>SC: Cache proxy instance
```

The key logic in `registerBeanDefinitions()` at [line 45](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCacheRegistrar.kt#L45):

```kotlin
override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
    val cacheMetadataList = resolveCacheMetadataList(importingClassMetadata)
    cacheMetadataList.forEach { cacheMetadata ->
        registry.registerCacheMetadata(cacheMetadata)
        val beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(CacheProxyFactoryBean::class.java)
        beanDefinitionBuilder.addConstructorArgValue(cacheMetadata)
        beanDefinitionBuilder.setPrimary(true)
        registry.registerBeanDefinition(cacheMetadata.cacheName, beanDefinitionBuilder.beanDefinition)
    }
    val joinCacheMetadataList = resolveJoinCacheMetadataList(importingClassMetadata)
    joinCacheMetadataList.forEach { cacheMetadata ->
        val beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(JoinCacheProxyFactoryBean::class.java)
        beanDefinitionBuilder.addConstructorArgValue(cacheMetadata)
        beanDefinitionBuilder.setPrimary(true)
        registry.registerBeanDefinition(cacheMetadata.cacheName, beanDefinitionBuilder.beanDefinition)
    }
}
```

## CoCacheMetadata and CoCacheMetadataParser

### CoCacheMetadata

[`CoCacheMetadata`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/annotation/CoCacheMetadata.kt#L20) is a data class that holds all parsed configuration from a cache interface:

```kotlin
data class CoCacheMetadata(
    override val proxyInterface: KClass<*>,
    override val name: String,
    val keyPrefix: String,
    val keyExpression: String,
    override val ttl: Long,
    override val ttlAmplitude: Long,
    val keyType: KType,
    val valueType: KType
) : ComputedNamedCache, TtlConfiguration {
    override val cacheName: String = name.ifBlank {
        proxyInterface.simpleName!!
    }
}
```

If `name` is blank, the `cacheName` defaults to the interface's simple class name.

### CoCacheMetadataParser

[`CoCacheMetadataParser`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/annotation/CoCacheMetadataParser.kt#L24) parses a `KClass` into `CoCacheMetadata` at [line 30](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/annotation/CoCacheMetadataParser.kt#L30):

```mermaid
flowchart TD
    Input["KClass&lt;out Cache&lt;*, *&gt;&gt;"] --> IsInterface{"Is it an interface?"}
    IsInterface -->|No| Error["Throw error:<br>must be interface"]
    IsInterface -->|Yes| FindAnnotation["Find @CoCache annotation<br>(or use defaults)"]
    FindAnnotation --> FindSuper["Find Cache&lt;K, V&gt; supertype"]
    FindSuper --> ExtractTypes["Extract keyType and valueType<br>from generic arguments"]
    ExtractTypes --> BuildMetadata["Build CoCacheMetadata"]

    style Input fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style IsInterface fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Error fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style FindAnnotation fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style FindSuper fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ExtractTypes fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style BuildMetadata fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

The parser enforces that the target must be an interface. It reads the `@CoCache` annotation (or uses defaults if absent), extracts the generic type parameters from the `Cache<K, V>` supertype, and produces a `CoCacheMetadata` instance.

## JDK Dynamic Proxy Creation

### CoCacheProxy -- Abstract InvocationHandler

[`CoCacheProxy`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CoCacheProxy.kt#L20) is an abstract `InvocationHandler` that provides the core delegation logic:

```kotlin
abstract class CoCacheProxy<DELEGATE> : InvocationHandler, CacheDelegated<DELEGATE>
    where DELEGATE : Cache<*, *> {

    abstract val proxyInterface: Class<*>

    private val declaredDefaultMethods by lazy {
        proxyInterface.declaredMethods.filter { it.isDefault }
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        val methodArgs = args ?: EMPTY_ARGS
        if (method.isDefault && declaredDefaultMethods.contains(method)) {
            return InvocationHandler.invokeDefault(proxy, method, *methodArgs)
        }
        return method.invoke(delegate, *methodArgs)
    }
}
```

The proxy distinguishes between:
- **Default methods** (declared on the interface itself with a body) -- invoked via `InvocationHandler.invokeDefault()`
- **Abstract methods** (from `Cache<K, V>` and parent interfaces) -- delegated to the `DefaultCoherentCache` instance

### CoCacheInvocationHandler -- Concrete Handler

[`CoCacheInvocationHandler`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CoCacheInvocationHandler.kt#L22) extends `CoCacheProxy` and adds special handling for the `delegate` and `cacheMetadata` accessor methods:

```kotlin
class CoCacheInvocationHandler<DELEGATE>(
    override val cacheMetadata: CoCacheMetadata,
    override val delegate: DELEGATE
) : CacheDelegated<DELEGATE>, CacheMetadataCapable, CoCacheProxy<DELEGATE>()
    where DELEGATE : Cache<*, *>, DELEGATE : NamedCache {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        if (DELEGATE_METHOD_SIGN == method.name) return delegate
        if (CACHE_METADATA_METHOD_SIGN == method.name) return cacheMetadata
        return super.invoke(proxy, method, args)
    }
}
```

This allows callers to access the underlying `delegate` (the `DefaultCoherentCache`) and the `cacheMetadata` from the proxy, enabling introspection without casting.

### DefaultCacheProxyFactory -- Factory

[`DefaultCacheProxyFactory`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/DefaultCacheProxyFactory.kt#L30) orchestrates the creation of a cache proxy at [line 40](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/DefaultCacheProxyFactory.kt#L40):

```mermaid
sequenceDiagram
autonumber
    autonumber
    participant F as DefaultCacheProxyFactory
    participant CID as ClientIdGenerator
    participant CSF as ClientSideCacheFactory
    participant DF as DistributedCacheFactory
    participant SF as CacheSourceFactory
    participant KCF as KeyConverterFactory
    participant CHF as CoherentCacheFactory
    participant IH as CoCacheInvocationHandler
    participant P as JDK Proxy

    F->>CID: generate()
    CID-->>F: clientId
    F->>CSF: create(metadata)
    CSF-->>F: ClientSideCache (L2)
    F->>DF: create(metadata)
    DF-->>F: DistributedCache (L1)
    F->>SF: create(metadata)
    SF-->>F: CacheSource (L0)
    F->>KCF: create(metadata)
    KCF-->>F: KeyConverter

    F->>CHF: create(CoherentCacheConfiguration)
    CHF-->>F: DefaultCoherentCache (delegate)

    F->>IH: new CoCacheInvocationHandler(metadata, delegate)
    F->>P: Proxy.newProxyInstance(classLoader, interfaces, handler)
    P-->>F: CACHE proxy instance
```

The proxy implements four interfaces simultaneously:
1. The user's cache interface (e.g., `UserProfileCache`)
2. `CoherentCache<K, V>` -- full coherent cache API
3. `CacheDelegated` -- access to the underlying delegate
4. `CacheMetadataCapable` -- access to the parsed metadata

### CacheProxyFactoryBean -- Spring Integration

[`CacheProxyFactoryBean`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBean.kt#L23) bridges the Spring `FactoryBean` contract with the proxy factory:

```kotlin
class CacheProxyFactoryBean(private val cacheMetadata: CoCacheMetadata) :
    FactoryBean<Cache<Any, Any>>, ApplicationContextAware {

    override fun getObject(): Cache<Any, Any> {
        val cacheProxyFactory = appContext.getBean(CacheProxyFactory::class.java)
        return cacheProxyFactory.create(cacheMetadata)
    }

    override fun getObjectType(): Class<*> {
        return cacheMetadata.proxyInterface.java
    }
}
```

It lazily resolves the `CacheProxyFactory` from the Spring `ApplicationContext` when `getObject()` is first called.

## JoinCache Proxy Flow

For `JoinCache` interfaces (which compose two cached values), a parallel registration path exists through [`JoinCacheProxyFactoryBean`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBean.kt#L23) and [`DefaultJoinCacheProxyFactory`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/join/proxy/DefaultJoinCacheProxyFactory.kt#L25).

```mermaid
sequenceDiagram
autonumber
    autonumber
    participant F as DefaultJoinCacheProxyFactory
    participant CF as CacheFactory
    participant JK as JoinKeyExtractorFactory
    participant SC as SimpleJoinCache
    participant IH as JoinCacheInvocationHandler
    participant P as JDK Proxy

    F->>CF: getCache(firstCacheName)
    CF-->>F: firstCache (Cache<K1, V1>)
    F->>CF: getCache(joinCacheName)
    CF-->>F: joinCache (Cache<K2, V2>)
    F->>JK: create(metadata)
    JK-->>F: JoinKeyExtractor

    F->>SC: new SimpleJoinCache(firstCache, joinCache, joinKeyExtractor)
    SC-->>F: delegate

    F->>IH: new JoinCacheInvocationHandler(metadata, delegate)
    F->>P: Proxy.newProxyInstance(classLoader, interfaces, handler)
    P-->>F: JoinCache proxy instance
```

The `DefaultJoinCacheProxyFactory.create()` at [line 30](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/join/proxy/DefaultJoinCacheProxyFactory.kt#L30):
1. Looks up the **first cache** by `firstCacheName` (or by type if name is blank)
2. Looks up the **join cache** by `joinCacheName` (or by type)
3. Creates a `JoinKeyExtractor` that extracts the join key from the first cache's value
4. Wraps them in a `SimpleJoinCache` delegate
5. Creates a JDK proxy implementing the user's `JoinCache` interface

## Complete Registration Flow Diagram

```mermaid
graph TB
    subgraph "1. Annotation Parsing"

        A1["@EnableCoCache<br>caches = [MyCache::class]"]
        A2["EnableCoCacheRegistrar"]
        A3["CoCacheMetadataParser.parse()"]
        A4["CoCacheMetadata"]
    end

    subgraph "2. Bean Definition"

        B1["Register CoCacheMetadata bean<br>(name: cacheName.CacheMetadata)"]
        B2["Register CacheProxyFactoryBean<br>(name: cacheName, primary: true)"]
    end

    subgraph "3. Proxy Construction"

        C1["CacheProxyFactoryBean.getObject()"]
        C2["DefaultCacheProxyFactory.create()"]
        C3["Create L2 + L1 + L0 + KeyConverter"]
        C4["CoherentCacheFactory.create()"]
        C5["DefaultCoherentCache<br>(registers with Event Bus)"]
        C6["CoCacheInvocationHandler"]
        C7["JDK Proxy.newProxyInstance()"]
    end

    A1 --> A2 --> A3 --> A4
    A4 --> B1
    A4 --> B2
    B2 --> C1 --> C2 --> C3 --> C4 --> C5 --> C6 --> C7

    style A1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style A2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style A3 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style A4 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C3 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C4 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C5 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C6 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C7 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Key Class Relationships

| Class/Interface | Role | Module | Source |
|----------------|------|--------|--------|
| [`@EnableCoCache`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCache.kt#L20) | Triggers registration via `@Import` | cocache-spring | [EnableCoCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCache.kt#L20) |
| [`EnableCoCacheRegistrar`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCacheRegistrar.kt#L31) | Parses annotations, registers bean definitions | cocache-spring | [EnableCoCacheRegistrar.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCacheRegistrar.kt#L31) |
| [`CoCacheMetadata`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/annotation/CoCacheMetadata.kt#L20) | Parsed cache configuration | cocache-core | [CoCacheMetadata.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/annotation/CoCacheMetadata.kt#L20) |
| [`CoCacheMetadataParser`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/annotation/CoCacheMetadataParser.kt#L24) | Reflective interface parser | cocache-core | [CoCacheMetadataParser.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/annotation/CoCacheMetadataParser.kt#L24) |
| [`CoCacheProxy`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CoCacheProxy.kt#L20) | Abstract InvocationHandler with default method support | cocache-core | [CoCacheProxy.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CoCacheProxy.kt#L20) |
| [`CoCacheInvocationHandler`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CoCacheInvocationHandler.kt#L22) | Concrete handler with delegate/metadata access | cocache-core | [CoCacheInvocationHandler.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CoCacheInvocationHandler.kt#L22) |
| [`DefaultCacheProxyFactory`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/DefaultCacheProxyFactory.kt#L30) | Assembles all components and creates proxy | cocache-core | [DefaultCacheProxyFactory.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/DefaultCacheProxyFactory.kt#L30) |
| [`CacheProxyFactoryBean`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBean.kt#L23) | Spring FactoryBean bridge | cocache-spring | [CacheProxyFactoryBean.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBean.kt#L23) |
| [`JoinCacheProxyFactoryBean`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBean.kt#L23) | Spring FactoryBean for JoinCache | cocache-spring | [JoinCacheProxyFactoryBean.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBean.kt#L23) |
| [`DefaultJoinCacheProxyFactory`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/join/proxy/DefaultJoinCacheProxyFactory.kt#L25) | Creates JoinCache proxies with two cache composition | cocache-core | [DefaultJoinCacheProxyFactory.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/join/proxy/DefaultJoinCacheProxyFactory.kt#L25) |

## Source References

| File | Line(s) | Description |
|------|---------|-------------|
| [`EnableCoCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCache.kt#L20) | 20-24 | @EnableCoCache annotation definition |
| [`EnableCoCacheRegistrar.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/EnableCoCacheRegistrar.kt#L31) | 31-98 | Bean definition registrar |
| [`CoCacheMetadata.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/annotation/CoCacheMetadata.kt#L20) | 20-33 | Parsed metadata data class |
| [`CoCacheMetadataParser.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/annotation/CoCacheMetadataParser.kt#L30) | 30-57 | Reflective parser |
| [`CoCacheProxy.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CoCacheProxy.kt#L34) | 34-41 | Abstract InvocationHandler |
| [`CoCacheInvocationHandler.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CoCacheInvocationHandler.kt#L37) | 37-46 | Concrete invocation handler |
| [`DefaultCacheProxyFactory.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/proxy/DefaultCacheProxyFactory.kt#L40) | 40-68 | Proxy factory assembling all components |
| [`CacheProxyFactoryBean.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBean.kt#L23) | 23-39 | Spring FactoryBean |
| [`JoinCacheProxyFactoryBean.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBean.kt#L23) | 23-39 | JoinCache FactoryBean |
| [`DefaultJoinCacheProxyFactory.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/join/proxy/DefaultJoinCacheProxyFactory.kt#L30) | 30-65 | JoinCache proxy factory |

## Related Pages

- [Architecture Overview](./index.md) -- high-level system architecture and module graph
- [Cache Layers Deep Dive](./cache-layers.md) -- L0/L1/L2 layer details
- [Cache Coherence and Event Bus](./coherence.md) -- distributed invalidation mechanism
