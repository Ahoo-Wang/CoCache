---
title: cocache-spring-redis Module
description: The cocache-spring-redis module provides the Redis-backed distributed cache (L1) and cross-instance cache coherence via Redis Pub/Sub. It includes a codec hierarchy for encoding cache values to Redis data structures.
---

# cocache-spring-redis Module

The `cocache-spring-redis` module implements the distributed cache layer (L1) using Redis and the cross-instance cache coherence mechanism using Redis Pub/Sub. It provides the production-ready implementations that make CoCache a true distributed cache framework.

## Module Dependencies

```mermaid
graph LR
    subgraph "cocache-spring-redis Dependencies"


        core["cocache-core"]
        style core fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        spring["cocache-spring"]
        style spring fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        redis["cocache-spring-redis"]
        style redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        jackson["jackson-databind<br>jackson-module-kotlin"]
        style jackson fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        sdr["spring-data-redis"]
        style sdr fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        core --> redis
        spring --> redis
        jackson --> redis
        sdr --> redis
    end

```

## Source Files

| File | Package | Description |
|------|---------|-------------|
| [RedisDistributedCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCache.kt#L28) | `me.ahoo.cache.spring.redis` | L1 distributed cache implementation using `StringRedisTemplate` |
| [RedisCacheEvictedEventBus.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt#L32) | `me.ahoo.cache.spring.redis` | Cross-instance event bus using Redis Pub/Sub |
| [RedisDistributedCacheFactory.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFactory.kt#L27) | `me.ahoo.cache.spring.redis` | Factory for creating `RedisDistributedCache` instances via `AbstractCacheFactory` |
| [CodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutor.kt#L22) | `me.ahoo.cache.spring.redis.codec` | Codec interface for encoding/decoding cache values |
| [AbstractCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt#L21) | `me.ahoo.cache.spring.redis.codec` | Abstract base with pipelined write and MissingGuard handling |
| [ObjectToJsonCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/ObjectToJsonCodecExecutor.kt#L27) | `me.ahoo.cache.spring.redis.codec` | JSON serialization via Jackson (default codec) |
| [StringToStringCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/StringToStringCodecExecutor.kt#L25) | `me.ahoo.cache.spring.redis.codec` | Direct string storage for `String` values |
| [MapToHashCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/MapToHashCodecExecutor.kt#L26) | `me.ahoo.cache.spring.redis.codec` | Redis Hash storage for `Map<String, String>` values |
| [ObjectToHashCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocoa-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/ObjectToHashCodecExecutor.kt#L26) | `me.ahoo.cache.spring.redis.codec` | Hash storage for arbitrary objects via `MapConverter` |
| [SetToSetCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/SetToSetCodecExecutor.kt#L25) | `me.ahoo.cache.spring.redis.codec` | Redis Set storage for `Set<String>` values |
| [EvictedEvents.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/EvictedEvents.kt#L19) | `me.ahoo.cache.spring.redis.codec` | Message format for evicted events (key@@clientId encoding) |

## RedisDistributedCache

[RedisDistributedCache](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCache.kt#L28) implements `DistributedCache<V>` using Spring's `StringRedisTemplate` and a pluggable `CodecExecutor`.

### Cache Read Flow

```mermaid
sequenceDiagram
autonumber
    autonumber
    participant Caller as CoherentCache
    participant RDC as RedisDistributedCache
    participant Redis as Redis
    participant Codec as CodecExecutor

    Caller->>RDC: getCache(key)
    RDC->>Redis: getExpire(key)
    Redis-->>RDC: TTL in seconds

    alt Key does not exist (TTL = -2)
        RDC-->>Caller: null
    else Key has no expiry (TTL = -1)
        RDC->>Codec: executeAndDecode(key, FOREVER)
        Codec->>Redis: GET / HGETALL / SMEMBERS (based on codec)
        Redis-->>Codec: raw value
        Codec->>Codec: Check MissingGuard
        Codec-->>RDC: CacheValue<V>
        RDC-->>Caller: CacheValue<V>
    else Key has TTL
        RDC->>RDC: ttlAt = currentTime + TTL
        RDC->>Codec: executeAndDecode(key, ttlAt)
        Codec->>Redis: GET key
        Redis-->>Codec: raw value
        Codec-->>RDC: CacheValue<V>
        RDC-->>Caller: CacheValue<V>
    end
```

### Cache Write Flow

```mermaid
sequenceDiagram
autonumber
    autonumber
    participant Caller as CoherentCache
    participant RDC as RedisDistributedCache
    participant Codec as CodecExecutor
    participant Redis as Redis

    Caller->>RDC: setCache(key, cacheValue)
    RDC->>RDC: Check if expired -> skip

    alt Is Forever
        RDC->>Codec: executeAndEncode(key, cacheValue)
        Codec->>Codec: toRawValue()
        Codec->>Redis: SET key value (no expiry)
    else Has TTL
        RDC->>Codec: executeAndEncode(key, cacheValue)
        Codec->>Codec: toRawValue()
        Codec->>Codec: Compute expiredDuration
        Codec->>Redis: SET key value EX ttl
    end
```

## RedisCacheEvictedEventBus

[RedisCacheEvictedEventBus](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt#L32) uses Redis Pub/Sub to distribute cache eviction events across all application instances.

```mermaid
graph TB
    subgraph "Redis Pub/Sub Cache Coherence"


        inst1["Instance 1<br>(clientId: 0:1234@10.0.0.1)"]
        style inst1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        inst2["Instance 2<br>(clientId: 0:5678@10.0.0.2)"]
        style inst2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        inst3["Instance 3<br>(clientId: 0:9012@10.0.0.3)"]
        style inst3 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        redis["Redis Pub/Sub<br>(Channel = cacheName)"]
        style redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        inst1 -->|"PUBLISH userCache<br>'user:123@@0:1234@10.0.0.1'"| redis
        redis -->|"Deliver to subscribers"| inst2
        redis -->|"Deliver to subscribers"| inst3
        redis -->|"Deliver to subscribers"| inst1
        inst1 -->|"Filter: publisherId == self<br>-> Ignore"| inst1
        inst2 -->|"Evict L2 entry<br>user:123"| inst2
        inst3 -->|"Evict L2 entry<br>user:123"| inst3
    end

```

### Event Registration

When a `CoherentCache` is created by `DefaultCoherentCacheFactory`, it registers with the event bus:

```mermaid
sequenceDiagram
autonumber
    autonumber
    participant CCF as DefaultCoherentCacheFactory
    participant RCEEB as RedisCacheEvictedEventBus
    participant RMLC as RedisMessageListenerContainer
    participant Redis as Redis

    CCF->>RCEEB: register(coherentCache)
    RCEEB->>RCEEB: Create MessageListenerAdapter
    RCEEB->>RMLC: addMessageListener(adapter, ChannelTopic(cacheName))
    RMLC->>Redis: SUBSCRIBE cacheName
```

### MessageListenerAdapter

The [MessageListenerAdapter](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt#L67) wraps `CacheEvictedSubscriber` to implement Spring's `MessageListener` interface. On receiving a Redis message, it delegates to `EvictedEvents.fromMessage()` to parse the message and then calls `subscriber.onEvicted()`.

## EvictedEvents Message Format

[EvictedEvents](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/EvictedEvents.kt#L19) defines the wire format for cache eviction messages:

| Field | Encoding | Example |
|-------|----------|---------|
| Channel | Cache name (from `NamedCache.cacheName`) | `userCache` |
| Body | `key + "@@" + clientId` | `user:123@@0:1234@10.0.0.1` |

Parsing at [EvictedEvents.fromMessage()](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/EvictedEvents.kt#L22):
- `cacheName` = `message.channel.decodeToString()`
- Split `message.body.decodeToString()` by `"@@"` into `[key, clientId]`
- Construct `CacheEvictedEvent(cacheName, key, clientId)`

## Codec Hierarchy

The codec system handles serialization of cache values to/from Redis data structures. Each codec maps a specific value type to a Redis data type.

```mermaid
classDiagram
    class CodecExecutor~V~ {
        <<interface>>
        +executeAndDecode(key: String, ttlAt: Long) CacheValue~V~
        +executeAndEncode(key: String, cacheValue: CacheValue~V~)
    }

    class AbstractCodecExecutor~V, RAW~ {
        <<abstract>>
        #redisTemplate: StringRedisTemplate
        +executeAndDecode(key, ttlAt) CacheValue~V~
        +executeAndEncode(key, cacheValue)
        #toRawValue() RAW
        #getRawValue(key) RAW?
        #isMissingGuard(raw) Boolean
        #decode(raw) V
        #setForeverValue(key, cacheValue)
        #setValueWithTtlAt(key, cacheValue)
    }

    class ObjectToJsonCodecExecutor~V~ {
        -valueType: Type
        -objectMapper: ObjectMapper
        Redis type: STRING (GET/SET)
    }

    class StringToStringCodecExecutor {
        Redis type: STRING (GET/SET)
    }

    class MapToHashCodecExecutor {
        Redis type: HASH (HGETALL/HMSET)
    }

    class ObjectToHashCodecExecutor~V~ {
        -mapConverter: MapConverter
        Redis type: HASH (HGETALL/HMSET)
    }

    class SetToSetCodecExecutor {
        Redis type: SET (SMEMBERS/SADD)
    }

    CodecExecutor <|.. AbstractCodecExecutor
    AbstractCodecExecutor <|-- ObjectToJsonCodecExecutor
    AbstractCodecExecutor <|-- StringToStringCodecExecutor
    AbstractCodecExecutor <|-- MapToHashCodecExecutor
    AbstractCodecExecutor <|-- ObjectToHashCodecExecutor
    AbstractCodecExecutor <|-- SetToSetCodecExecutor
```

### Codec Details

| Codec | Value Type | Redis Type | Serialization | MissingGuard Encoding |
|-------|-----------|------------|---------------|----------------------|
| `ObjectToJsonCodecExecutor` | Any (POJO) | STRING | Jackson ObjectMapper JSON | `"_nil_"` string |
| `StringToStringCodecExecutor` | `String` | STRING | Direct (no conversion) | `"_nil_"` string |
| `MapToHashCodecExecutor` | `Map<String, String>` | HASH | Direct key-value mapping | `{"_nil_": "<timestamp>"}` |
| `ObjectToHashCodecExecutor` | Any via `MapConverter` | HASH | Object <-> Map conversion | `{"_nil_": "<timestamp>"}` |
| `SetToSetCodecExecutor` | `Set<String>` | SET | Direct set members | `{"_nil_"}` single-element set |

### AbstractCodecExecutor Write Pipeline

[AbstractCodecExecutor](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt#L21) provides a `setPipelined()` helper at [line 45](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt#L45) that atomically deletes the old key and writes the new value in a single Redis pipeline, preventing stale reads during the write window.

### MissingGuard Detection Per Codec

Each codec has a codec-specific way to detect the missing guard sentinel, matching the polymorphic `MissingGuard.Companion.isMissingGuard` extensions:

```mermaid
flowchart LR
    subgraph "MissingGuard Detection by Value Type"


        check{"Value Type?"}
        style check fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        str["String<br>== '_nil_'"]
        style str fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        set["Set<br>first() == '_nil_'"]
        style set fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        map["Map<br>firstKey() == '_nil_'"]
        style map fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        obj["Object<br>is MissingGuard"]
        style obj fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        check -->|"String"| str
        check -->|"Set<String>"| set
        check -->|"Map<String,String>"| map
        check -->|"Other"| obj
    end

```

## RedisDistributedCacheFactory

[RedisDistributedCacheFactory](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFactory.kt#L27) extends `AbstractCacheFactory` and creates `RedisDistributedCache` instances. Its `fallback()` method at [line 47](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFactory.kt#L47) creates a `RedisDistributedCache` with `ObjectToJsonCodecExecutor` (JSON serialization) as the default codec.

Users can customize the distributed cache by declaring a Spring bean named `"{cacheName}.DistributedCache"`:

```kotlin
@Bean("UserCache.DistributedCache")
fun userDistributedCache(
    redisTemplate: StringRedisTemplate
): DistributedCache<User> {
    val codec = ObjectToHashCodecExecutor(
        mapConverter = object : ObjectToHashCodecExecutor.MapConverter<User> {
            override fun asValue(map: Map<String, String>): User = /* convert map to User */
            override fun asMap(value: User): Map<String, String> = /* convert User to map */
        },
        redisTemplate = redisTemplate
    )
    return RedisDistributedCache(redisTemplate, codec, ttl = 7200, ttlAmplitude = 60)
}
```

## Cross-Instance Coherence Flow

The complete flow of a cache write with cross-instance invalidation:

```mermaid
sequenceDiagram
autonumber
    autonumber
    participant Client as Client (Instance A)
    participant DCCA as DefaultCoherentCache (A)
    participant L2A as ClientSideCache (A)
    participant Redis as Redis
    participant EventBus as RedisCacheEvictedEventBus
    participant L2B as ClientSideCache (B)
    participant DCCB as DefaultCoherentCache (B)

    Client->>DCCA: set(key, value)
    DCCA->>DCCA: keyConverter.toStringKey(key)
    DCCA->>L2A: setCache(cacheKey, value)
    DCCA->>Redis: SET cacheKey value EX ttl
    DCCA->>EventBus: publish(CacheEvictedEvent)
    EventBus->>Redis: PUBLISH cacheName "cacheKey@@clientIdA"
    Redis->>DCCB: onMessage (Pub/Sub delivery)
    DCCB->>DCCB: Parse EvictedEvent
    DCCB->>DCCB: Filter: cacheName matches?
    DCCB->>DCCB: Filter: publisherId != self?
    DCCB->>L2B: evict(cacheKey)
    Note over L2B: Instance B's L2 is now invalidated
```

## Related Pages

- [Module Overview](./index.md) -- Dependency graph and module descriptions
- [cocache-core](./cocache-core.md) -- DefaultCoherentCache, DistributedCache interface, CacheEvictedEventBus
- [cocache-spring](./cocache-spring.md) -- AbstractCacheFactory base class, Spring integration
- [cocache-spring-boot-starter](./cocache-spring-boot-starter.md) -- Auto-configuration that wires RedisDistributedCacheFactory
