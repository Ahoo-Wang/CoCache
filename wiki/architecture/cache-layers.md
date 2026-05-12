---
title: Cache Layers Deep Dive
description: Detailed exploration of CoCache's three cache layers -- L0 (CacheSource), L1 (DistributedCache/Redis), L2 (ClientSideCache/Guava/Caffeine/Map). Covers the read path, write path, and eviction flow.
---

# Cache Layers Deep Dive

CoCache organizes data retrieval into three distinct layers, each with a specific role in the caching hierarchy. The `DefaultCoherentCache` class orchestrates all three layers, handling the read path, write path, and eviction flow with fine-grained locking and cache coherence event publication.

## Layer Overview

```mermaid
graph LR
    subgraph "CoCache Layers"


        subgraph "L2 - ClientSideCache"

            Guava["GuavaClientSideCache"]
            Caffeine["CaffeineClientSideCache"]
            Map["MapClientSideCache"]
        end

        subgraph "L1 - DistributedCache"

            Redis["RedisDistributedCache"]
        end

        subgraph "L0 - CacheSource"

            DB["Database / DataSource"]
        end
    end

    App["Application"] --> L2Label
    L2Label --> L1Label
    L1Label --> L0Label

    L2Label["L2: In-Memory<br>(fastest, per-instance)"]
    L1Label["L1: Distributed<br>(shared, Redis)"]
    L0Label["L0: Data Source<br>(authoritative)"]

    style App fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L2Label fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L1Label fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L0Label fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Guava fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Caffeine fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Map fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style DB fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## L2 -- ClientSideCache (In-Memory, Per-Instance)

The L2 layer is the fastest cache tier. It stores `CacheValue<V>` entries directly in the JVM heap, keyed by `String`. Every application instance maintains its own independent L2 cache.

### Interface

The [`ClientSideCache<V>`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/client/ClientSideCache.kt#L22) interface extends `Cache<String, V>` and adds a `size` property and a `clear()` method:

```kotlin
interface ClientSideCache<V> : Cache<String, V> {
    val size: Long
    fun clear()
}
```

### Implementations

| Implementation | Backing Store | Configuration Annotation | Source |
|----------------|---------------|--------------------------|--------|
| [`MapClientSideCache`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/client/MapClientSideCache.kt#L24) | `ConcurrentHashMap` | Default (no annotation needed) | [MapClientSideCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/client/MapClientSideCache.kt#L24) |
| [`GuavaClientSideCache`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/client/GuavaClientSideCache.kt#L26) | Guava `Cache` | `@GuavaCache` | [GuavaClientSideCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/client/GuavaClientSideCache.kt#L26) |
| [`CaffeineClientSideCache`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/client/CaffeineClientSideCache.kt#L27) | Caffeine `Cache` | `@CaffeineCache` | [CaffeineClientSideCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/client/CaffeineClientSideCache.kt#L27) |

Both `GuavaClientSideCache` and `CaffeineClientSideCache` provide companion factory methods that read their respective annotations to configure `initialCapacity`, `maximumSize`, `expireAfterWrite`, `expireAfterAccess`, and other cache parameters. The `ClientSideCacheFactory` abstraction resolves which implementation to use at runtime based on the annotation present on the cache interface.

```mermaid
classDiagram
    class ClientSideCache~V~ {
        <<interface>>
        +getCache(key: String) CacheValue~V~?
        +setCache(key: String, value: CacheValue~V~)
        +evict(key: String)
        +size: Long
        +clear()
    }

    class MapClientSideCache~V~ {
        -cacheMap: ConcurrentHashMap
        +getCache(key) CacheValue?
        +setCache(key, value)
        +evict(key)
    }

    class GuavaClientSideCache~V~ {
        -guavaCache: Guava Cache
        +getCache(key) CacheValue?
        +setCache(key, value)
        +evict(key)
    }

    class CaffeineClientSideCache~V~ {
        -caffeineCache: Caffeine Cache
        +getCache(key) CacheValue?
        +setCache(key, value)
        +evict(key)
    }

    class ComputedClientSideCache~V~ {
        <<interface>>
        +ttl: Long
        +ttlAmplitude: Long
    }

    ClientSideCache <|-- ComputedClientSideCache
    ComputedClientSideCache <|-- MapClientSideCache
    ComputedClientSideCache <|-- GuavaClientSideCache
    ComputedClientSideCache <|-- CaffeineClientSideCache

    style ClientSideCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ComputedClientSideCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style MapClientSideCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style GuavaClientSideCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CaffeineClientSideCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## L1 -- DistributedCache (Shared, Redis)

The L1 layer is a shared cache accessible by all application instances. Currently the primary implementation is `RedisDistributedCache`, which uses Spring Data Redis.

### Interface

The [`DistributedCache<V>`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/distributed/DistributedCache.kt#L22) interface extends `ComputedCache<String, V>` and `AutoCloseable`:

```kotlin
interface DistributedCache<V> : ComputedCache<String, V>, AutoCloseable
```

### RedisDistributedCache

[`RedisDistributedCache`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCache.kt#L28) uses a `StringRedisTemplate` and a `CodecExecutor` for serialization. On read, it first queries the Redis TTL via `getExpire(key)` to compute the local `ttlAt` timestamp, then fetches and decodes the value. This ensures the local representation carries the correct remaining TTL from Redis.

```mermaid
sequenceDiagram
autonumber
    autonumber
    participant CC as DefaultCoherentCache
    participant DC as RedisDistributedCache
    participant RT as StringRedisTemplate
    participant CE as CodecExecutor

    CC->>DC: getCache(key)
    DC->>RT: getExpire(key)
    RT-->>DC: ttl (seconds or -1/-2)
    DC->>DC: compute ttlAt from ttl

    alt key exists (ttl != -2)
        DC->>CE: executeAndDecode(key, ttlAt)
        CE-->>DC: CacheValue<V>
        DC-->>CC: CacheValue<V>
    else key does not exist (ttl == -2)
        DC-->>CC: null
    end

    CC->>DC: setCache(key, value)
    DC->>CE: executeAndEncode(key, value)
    CE-->>DC: done
```

| Constant | Value | Meaning |
|----------|-------|---------|
| `FOREVER` | `-1` | Key exists but has no expiration |
| `NOT_EXIST` | `-2` | Key does not exist in Redis |

## L0 -- CacheSource (Data Source)

The L0 layer represents the authoritative data source (typically a database). It is the last resort when both L2 and L1 miss. The [`CacheSource<K, V>`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/source/CacheSource.kt#L24) interface defines a single method:

```kotlin
interface CacheSource<K, V> {
    fun loadCacheValue(key: K): CacheValue<V>?
}
```

When `loadCacheValue` returns `null`, `DefaultCoherentCache` stores a `missingGuard` value to prevent cache penetration. When it returns a value, the value is written to both L1 and L2, and a `CacheEvictedEvent` is published.

## Read Path -- getCache()

The complete read path is implemented in [`DefaultCoherentCache.getCache()`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L89) and the helper [`getL2Cache()`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L50):

```mermaid
flowchart TD
    Start["getCache(key)"] --> ConvertKey["Convert key to cacheKey string"]
    ConvertKey --> L2Check1["L2: clientSideCache.getCache(cacheKey)"]

    L2Check1 -->|hit, not expired| ReturnL2["Return L2 value"]
    L2Check1 -->|hit, expired| EvictExpiredL2["Evict expired L2 entry"]
    L2Check1 -->|miss| KeyFilterCheck["KeyFilter.notExist(cacheKey)?"]

    EvictExpiredL2 --> KeyFilterCheck

    KeyFilterCheck -->|true| ReturnMissingGuard["Return missingGuard<br>(prevents cache penetration)"]
    KeyFilterCheck -->|false| L1Check["L1: distributedCache.getCache(cacheKey)"]

    L1Check -->|hit, not expired| SetL2["Copy to L2: clientSideCache.setCache()"]
    SetL2 --> ReturnL1Value["Return L1 value"]
    L1Check -->|miss or expired| AcquireLock["Acquire fine-grained lock<br>synchronized(keyLock)"]

    AcquireLock --> L2Check2["L2: getL2Cache(cacheKey)<br>(double-check after lock)"]
    L2Check2 -->|hit| ReleaseLockReturn["Release lock, return value"]
    L2Check2 -->|miss| L0Load["L0: cacheSource.loadCacheValue(key)"]

    L0Load -->|value found| WriteBoth["Write to L2 + L1<br>publish CacheEvictedEvent"]
    WriteBoth --> ReturnL0Value["Return L0 value"]

    L0Load -->|null| WriteMissingGuard["Store missingGuard in L2+L1<br>publish CacheEvictedEvent"]
    WriteMissingGuard --> ReturnNull["Return null"]

    ReleaseLockReturn --> ReleaseLock["releaseLock(cacheKey)"]

    style Start fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ConvertKey fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L2Check1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ReturnL2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style EvictExpiredL2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style KeyFilterCheck fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ReturnMissingGuard fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L1Check fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SetL2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ReturnL1Value fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style AcquireLock fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L2Check2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ReleaseLockReturn fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L0Load fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style WriteBoth fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ReturnL0Value fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style WriteMissingGuard fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ReturnNull fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ReleaseLock fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

### Key Implementation Details

**Fine-Grained Locking** -- The lock map at [line 47](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L47) uses `ConcurrentHashMap<String, Any>()` to store one lock object per cache key. The `getLock()` method at [line 78](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L78) uses `computeIfAbsent` for atomic lock creation. After the L0 load completes, `releaseLock()` at [line 84](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L84) removes the lock entry to prevent memory leaks.

**Double-Check After Lock** -- After acquiring the lock, `getL2Cache()` is called again at [line 104](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L104) to check if another thread already loaded the value while this thread was waiting for the lock. This prevents redundant L0 calls.

## Write Path -- setCache()

The write path at [`setCache()`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L142) writes to both cache layers simultaneously and then publishes an eviction event:

```kotlin
override fun setCache(key: K, value: CacheValue<V>) {
    if (value.isExpired) {
        return
    }
    val cacheKey = keyConverter.toStringKey(key)
    setCache(cacheKey, value)                    // writes to L2 + L1
    cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
}
```

The private `setCache(cacheKey, cacheValue)` at [line 137](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L137) writes to both layers:

```kotlin
private fun setCache(cacheKey: String, cacheValue: CacheValue<V>) {
    clientSideCache.setCache(cacheKey, cacheValue)   // L2
    distributedCache.setCache(cacheKey, cacheValue)   // L1
}
```

## Eviction Path -- evict()

The eviction path at [`evict()`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L151) removes the entry from both layers and publishes an event:

```kotlin
override fun evict(key: K) {
    val cacheKey = keyConverter.toStringKey(key)
    clientSideCache.evict(cacheKey)                    // L2
    distributedCache.evict(cacheKey)                    // L1
    cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
}
```

The event publication triggers remote instances to evict their own L2 caches for the same key. This is the core of CoCache's coherence mechanism. See [Cache Coherence](./coherence.md) for the full event flow.

## Layer Interaction Summary

```mermaid
graph TB
    subgraph "Write Path: setCache"

        W1["Write L2"] --> W2["Write L1"]
        W2 --> W3["Publish CacheEvictedEvent"]
    end

    subgraph "Eviction Path: evict"

        E1["Evict L2"] --> E2["Evict L1"]
        E2 --> E3["Publish CacheEvictedEvent"]
    end

    subgraph "Read Path: getCache"

        R1["Read L2"] --> R2["KeyFilter"]
        R2 --> R3["Read L1"]
        R3 --> R4["Lock + Load L0"]
        R4 --> R5["Write L2 + L1"]
        R5 --> R6["Publish CacheEvictedEvent"]
    end

    style W1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style W2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style W3 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E3 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style R1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style R2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style R3 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style R4 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style R5 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style R6 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## Source References

| File | Line(s) | Description |
|------|---------|-------------|
| [`DefaultCoherentCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L50) | 50-76 | `getL2Cache()` -- L2 + KeyFilter + L1 lookup |
| [`DefaultCoherentCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L89) | 89-135 | `getCache()` -- full read path with locking |
| [`DefaultCoherentCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L142) | 142-149 | `setCache()` -- write path (L2 + L1 + event) |
| [`DefaultCoherentCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt#L151) | 151-156 | `evict()` -- eviction path (L2 + L1 + event) |
| [`MapClientSideCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/client/MapClientSideCache.kt#L24) | 24-50 | ConcurrentHashMap-backed L2 |
| [`GuavaClientSideCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/client/GuavaClientSideCache.kt#L26) | 26-78 | Guava-backed L2 with annotation factory |
| [`CaffeineClientSideCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/client/CaffeineClientSideCache.kt#L27) | 27-76 | Caffeine-backed L2 with annotation factory |
| [`CacheSource.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/source/CacheSource.kt#L24) | 24-35 | L0 interface |
| [`DistributedCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/distributed/DistributedCache.kt#L22) | 22 | L1 interface |
| [`RedisDistributedCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCache.kt#L28) | 28-68 | Redis L1 implementation |
| [`ClientSideCache.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/client/ClientSideCache.kt#L22) | 22-30 | L2 interface |
| [`KeyFilter.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/KeyFilter.kt#L21) | 21-23 | Bloom filter adapter interface |
| [`CoherentCacheConfiguration.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/consistency/CoherentCacheConfiguration.kt#L26) | 26-34 | Configuration with defaults |

## Related Pages

- [Architecture Overview](./index.md) -- high-level system architecture and module graph
- [Cache Coherence and Event Bus](./coherence.md) -- distributed invalidation mechanism
- [Proxy and Annotations](./proxy.md) -- declarative cache interface creation
