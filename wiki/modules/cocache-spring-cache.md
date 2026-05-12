---
title: cocache-spring-cache Module
description: The cocache-spring-cache module bridges CoCache with Spring's Cache abstraction (CacheManager). It allows CoCache-backed caches to be used with @Cacheable, @CacheEvict, and other Spring Cache annotations.
---

# cocache-spring-cache Module

The `cocache-spring-cache` module implements the bridge between CoCache and Spring's `org.springframework.cache.CacheManager` abstraction. Once configured, all CoCache-managed caches become accessible through Spring's standard `@Cacheable`, `@CachePut`, and `@CacheEvict` annotations, enabling seamless integration with existing Spring Cache infrastructure.

## Module Dependencies

```mermaid
graph LR
    subgraph sg_44 ["cocache-spring-cache Dependencies"]

        core["cocache-core"]
        style core fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        spring_cache["cocache-spring-cache"]
        style spring_cache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        spring_ctx["spring-context"]
        style spring_ctx fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        core --> spring_cache
        spring_ctx --> spring_cache
    end

```

## Source Files (3 files)

| File | Package | Purpose |
|------|---------|---------|
| [CoCacheManager.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache/CoCacheManager.kt#L21) | `me.ahoo.cache.spring.cache` | Spring `CacheManager` backed by CoCache's `CacheFactory` |
| [CoSpringCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache/CoSpringCache.kt#L27) | `me.ahoo.cache.spring.cache` | Spring `Cache` adapter wrapping a CoCache `Cache` instance |
| [SpringCacheValueWrapper.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache/SpringCacheValueWrapper.kt#L19) | `me.ahoo.cache.spring.cache` | `Cache.ValueWrapper` adapter for `CacheValue` |

## Architecture Overview

```mermaid
classDiagram
    class CacheManager {
        <<Spring Interface>>
        +getCache(name) Cache?
        +getCacheNames() Collection~String~
    }

    class CoCacheManager {
        -cacheFactory: CacheFactory
        +loadCaches() Collection~Cache~
        +getMissingCache(name) Cache?
    }

    class SpringCache {
        <<Spring Interface>>
        +getName() String
        +getNativeCache() Object
        +get(key) ValueWrapper?
        +get(key, type) T?
        +get(key, valueLoader) T?
        +put(key, value)
        +evict(key)
        +clear()
        +retrieve(key) CompletableFuture?
    }

    class CoSpringCache {
        -cacheName: String
        -delegate: Cache~Any, Any?~
        +getName() String
        +getNativeCache() Object
        +get(key) ValueWrapper?
        +get(key, type) T?
        +get(key, valueLoader) T?
        +put(key, value)
        +evict(key)
        +clear()
        +retrieve(key) CompletableFuture?
        +retrieve(key, valueLoader) CompletableFuture~T~
    }

    class ValueWrapper {
        <<Spring Interface>>
        +get() Object?
    }

    class SpringCacheValueWrapper {
        -cacheValue: CacheValue~Any?~
        +get() Object?
    }

    CacheManager <|.. CoCacheManager
    SpringCache <|.. CoSpringCache
    ValueWrapper <|.. SpringCacheValueWrapper
    CoCacheManager --> CoSpringCache : creates
    CoSpringCache --> SpringCacheValueWrapper : wraps CacheValue
```

## CoCacheManager

[CoCacheManager](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache/CoCacheManager.kt#L21) extends Spring's `AbstractCacheManager` and delegates to CoCache's `CacheFactory` for cache resolution.

### Cache Resolution Strategy

```mermaid
flowchart TB
    subgraph sg_45 ["CoCacheManager Cache Resolution"]

        request["getCache(name)"]
        style request fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        abstract_cm["AbstractCacheManager<br>.lookupCache(name)"]
        style abstract_cm fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        found{"Cache found<br>in manager?"}
        style found fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        return_cached["Return existing cache"]
        style return_cached fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        missing["getMissingCache(name)"]
        style missing fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        factory["cacheFactory.getCache(name)"]
        style factory fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        cache_found{"Cache found<br>in factory?"}
        style cache_found fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        wrap["Wrap in CoSpringCache(name, cache)"]
        style wrap fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        register["Register in cache manager"]
        style register fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        null["Return null"]
        style null fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        request --> abstract_cm --> found
        found -->|yes| return_cached
        found -->|no| missing --> factory --> cache_found
        cache_found -->|yes| wrap --> register --> return_cached
        cache_found -->|no| null
    end

```

### loadCaches()

The `loadCaches()` method at [CoCacheManager.kt:23](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache/CoCacheManager.kt#L23) eagerly loads all registered caches from the `CacheFactory` and wraps each in a `CoSpringCache`. This populates the cache manager with all known caches at startup.

## CoSpringCache

[CoSpringCache](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache/CoSpringCache.kt#L27) adapts a CoCache `Cache<Any, Any?>` to Spring's `Cache` interface. It implements `CacheDelegated` and `NamedCache` for access to the underlying CoCache infrastructure.

### Method Mapping

| Spring Cache Method | CoSpringCache Implementation | CoCache Operation |
|--------------------|------------------------------|-------------------|
| `getName()` | Returns `cacheName` | -- |
| `getNativeCache()` | Returns the delegate `Cache` | -- |
| `get(key)` | `delegate.getCache(key)` -> `SpringCacheValueWrapper` | `CacheGetter.getCache()` |
| `get(key, type)` | `delegate.get(key)` cast to `T` | `CacheGetter.get()` |
| `get(key, valueLoader)` | Get or load pattern | Get -> if null, call `valueLoader.call()`, set, return |
| `put(key, value)` | `delegate.set(key, value)` | `CacheSetter.set()` |
| `evict(key)` | `delegate.evict(key)` | `CacheSetter.evict()` |
| `clear()` | `ClientSideCache.clear()` or `CoherentCache.clientSideCache.clear()` | L2 only |
| `retrieve(key)` | `CompletableFuture.supplyAsync { delegate.get(key) }` | Async get |
| `retrieve(key, valueLoader)` | Async get-or-load with `thenCompose` | Async get -> load if null |

### Async Retrieve Support

CoSpringCache supports Spring 6.1's `Cache.retrieve()` methods for asynchronous cache access:

```mermaid
sequenceDiagram
autonumber
    participant Caller as Caller
    participant CSC as CoSpringCache
    participant Cache as CoCache

    Caller->>CSC: retrieve(key, valueLoader)
    CSC->>CSC: CompletableFuture.supplyAsync { delegate.get(key) }

    alt Cache Hit
        CSC-->>Caller: CompletableFuture(value)
    else Cache Miss
        CSC->>CSC: thenCompose { valueLoader.get() }
        CSC->>Cache: set(key, loadedValue)
        CSC-->>Caller: CompletableFuture(loadedValue)
    end
```

The `retrieve(key, valueLoader)` implementation at [CoSpringCache.kt:82](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache/CoSpringCache.kt#L82) uses `CompletableFuture.thenCompose()` to chain the cache check with the value loader, avoiding blocking the caller thread during cache misses.

### Clear Behavior

The `clear()` method at [CoSpringCache.kt:66](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache/CoSpringCache.kt#L66) only clears the L2 (client-side) cache, not the L1 (distributed) cache. This is intentional -- clearing the distributed cache would affect all instances, which is rarely the desired behavior.

```mermaid
flowchart TB
    subgraph sg_46 ["CoSpringCache.clear()"]

        clear_start["clear()"]
        style clear_start fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        is_csc{"delegate is<br>ClientSideCache?"}
        style is_csc fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        is_cc{"delegate is<br>CoherentCache?"}
        style is_cc fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        clear_l2a["delegate.clear()<br>(ClientSideCache.clear())"]
        style clear_l2a fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        clear_l2b["delegate.clientSideCache.clear()"]
        style clear_l2b fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        no_op["No-op"]
        style no_op fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        clear_start --> is_csc
        is_csc -->|yes| clear_l2a
        is_csc -->|no| is_cc
        is_cc -->|yes| clear_l2b
        is_cc -->|no| no_op
    end

```

## SpringCacheValueWrapper

[SpringCacheValueWrapper](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-cache/src/main/kotlin/me/ahoo/cache/spring/cache/SpringCacheValueWrapper.kt#L19) is a minimal adapter from CoCache's `CacheValue<Any?>` to Spring's `Cache.ValueWrapper`. The `get()` method returns `cacheValue.value`, which may be `null` for missing guard values.

## Usage with Spring Cache Annotations

Once `CoCacheManager` is registered as a bean (done automatically by `cocache-spring-boot-starter`), standard Spring Cache annotations work out of the box:

```kotlin
@Service
class UserService(
    private val userRepository: UserRepository
) {
    @Cacheable(cacheNames = ["UserCache"], key = "#userId")
    fun getUser(userId: String): User {
        return userRepository.findById(userId).orElseThrow()
    }

    @CachePut(cacheNames = ["UserCache"], key = "#user.id")
    fun updateUser(user: User): User {
        return userRepository.save(user)
    }

    @CacheEvict(cacheNames = ["UserCache"], key = "#userId")
    fun deleteUser(userId: String) {
        userRepository.deleteById(userId)
    }

    @CacheEvict(cacheNames = ["UserCache"], allEntries = true)
    fun clearAllUsers() {
        // Clears L2 only via CoSpringCache.clear()
    }
}
```

This approach works alongside CoCache's native proxy-based caching -- both mechanisms share the same underlying `CoherentCache` instance, so cache operations through either path are consistent.

## Data Flow: @Cacheable Through CoSpringCache

```mermaid
sequenceDiagram
autonumber
    participant Caller as Caller
    participant AOP as Spring AOP<br>(@Cacheable)
    participant CCM as CoCacheManager
    participant CSC as CoSpringCache
    participant CC as CoherentCache
    participant L2 as ClientSideCache
    participant L1 as DistributedCache
    participant CS as CacheSource

    Caller->>AOP: getUser(userId)
    AOP->>CCM: getCache("UserCache")
    CCM-->>AOP: CoSpringCache
    AOP->>CSC: get(userId)
    CSC->>CC: getCache(userId)
    CC->>L2: getCache(cacheKey)

    alt L2 Hit
        L2-->>CC: CacheValue
        CC-->>CSC: value
        CSC-->>AOP: SpringCacheValueWrapper
        AOP-->>Caller: Cached User
    else L2 Miss
        CC->>L1: getCache(cacheKey)

        alt L1 Hit
            L1-->>CC: CacheValue
            CC->>L2: setCache
            CC-->>CSC: value
            CSC-->>AOP: SpringCacheValueWrapper
            AOP-->>Caller: Cached User
        else L1 Miss
            CC->>CS: loadCacheValue(userId)
            CS-->>CC: CacheValue
            CC->>L2: setCache
            CC->>L1: setCache
            CC-->>CSC: value
            CSC-->>AOP: SpringCacheValueWrapper
            AOP-->>Caller: Fresh User
        end
    end
```

## Registration in Auto-Configuration

The `CoCacheManager` bean is registered in [CoCacheAutoConfiguration.kt:81](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheAutoConfiguration.kt#L81):

```kotlin
@Bean
fun coCacheManager(cacheFactory: CacheFactory): CoCacheManager {
    return CoCacheManager(cacheFactory)
}
```

In Spring Boot auto-configuration, this bean is created automatically. For non-Boot Spring applications, users must manually register the `CoCacheManager`:

```kotlin
@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun cacheManager(cacheFactory: CacheFactory): CoCacheManager {
        return CoCacheManager(cacheFactory)
    }
}
```

## Related Pages

- [Module Overview](./index.md) -- Dependency graph and module descriptions
- [cocache-core](./cocache-core.md) -- CoherentCache, Cache interface, CacheFactory
- [cocache-spring](./cocache-spring.md) -- SpringCacheFactory (the CacheFactory implementation)
- [cocache-spring-boot-starter](./cocache-spring-boot-starter.md) -- Auto-configuration that registers CoCacheManager
