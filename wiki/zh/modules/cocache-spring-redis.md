---
title: cocache-spring-redis 模块
description: cocache-spring-redis 模块提供基于 Redis 的分布式缓存（L1）和通过 Redis Pub/Sub 实现的跨实例缓存一致性机制。它包含用于将缓存值编码到 Redis 数据结构的编解码器层次结构。
---

# cocache-spring-redis 模块

`cocache-spring-redis` 模块使用 Redis 实现分布式缓存层（L1），使用 Redis Pub/Sub 实现跨实例缓存一致性机制。它提供了使 CoCache 成为真正的分布式缓存框架的生产级实现。

## 模块依赖

```mermaid
graph LR
    subgraph sg_47 ["cocache-spring-redis 依赖"]

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

## 源文件

| 文件 | 包 | 说明 |
|------|-----|------|
| [RedisDistributedCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCache.kt#L28) | `me.ahoo.cache.spring.redis` | 使用 `StringRedisTemplate` 的 L1 分布式缓存实现 |
| [RedisCacheEvictedEventBus.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt#L32) | `me.ahoo.cache.spring.redis` | 使用 Redis Pub/Sub 的跨实例事件总线 |
| [RedisDistributedCacheFactory.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFactory.kt#L27) | `me.ahoo.cache.spring.redis` | 通过 `AbstractCacheFactory` 创建 `RedisDistributedCache` 实例的工厂 |
| [CodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/CodecExecutor.kt#L22) | `me.ahoo.cache.spring.redis.codec` | 缓存值编解码接口 |
| [AbstractCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt#L21) | `me.ahoo.cache.spring.redis.codec` | 抽象基类，提供管道写入和 MissingGuard 处理 |
| [ObjectToJsonCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/ObjectToJsonCodecExecutor.kt#L27) | `me.ahoo.cache.spring.redis.codec` | 通过 Jackson 进行 JSON 序列化（默认编解码器） |
| [StringToStringCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/StringToStringCodecExecutor.kt#L25) | `me.ahoo.cache.spring.redis.codec` | `String` 值的直接字符串存储 |
| [MapToHashCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/MapToHashCodecExecutor.kt#L26) | `me.ahoo.cache.spring.redis.codec` | `Map<String, String>` 值的 Redis Hash 存储 |
| [ObjectToHashCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocoa-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/ObjectToHashCodecExecutor.kt#L26) | `me.ahoo.cache.spring.redis.codec` | 通过 `MapConverter` 的任意对象的 Hash 存储 |
| [SetToSetCodecExecutor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/SetToSetCodecExecutor.kt#L25) | `me.ahoo.cache.spring.redis.codec` | `Set<String>` 值的 Redis Set 存储 |
| [EvictedEvents.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/EvictedEvents.kt#L19) | `me.ahoo.cache.spring.redis.codec` | 驱逐事件的消息格式（key@@clientId 编码） |

## RedisDistributedCache

[RedisDistributedCache](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCache.kt#L28) 使用 Spring 的 `StringRedisTemplate` 和可插拔的 `CodecExecutor` 实现 `DistributedCache<V>`。

### 缓存读取流程

```mermaid
sequenceDiagram
autonumber
    participant Caller as CoherentCache
    participant RDC as RedisDistributedCache
    participant Redis as Redis
    participant Codec as CodecExecutor

    Caller->>RDC: getCache(key)
    RDC->>Redis: getExpire(key)
    Redis-->>RDC: TTL（秒）

    alt 键不存在（TTL = -2）
        RDC-->>Caller: null
    else 键无过期时间（TTL = -1）
        RDC->>Codec: executeAndDecode(key, FOREVER)
        Codec->>Redis: GET / HGETALL / SMEMBERS（取决于编解码器）
        Redis-->>Codec: 原始值
        Codec->>Codec: 检查 MissingGuard
        Codec-->>RDC: CacheValue<V>
        RDC-->>Caller: CacheValue<V>
    else 键有 TTL
        RDC->>RDC: ttlAt = currentTime + TTL
        RDC->>Codec: executeAndDecode(key, ttlAt)
        Codec->>Redis: GET key
        Redis-->>Codec: 原始值
        Codec-->>RDC: CacheValue<V>
        RDC-->>Caller: CacheValue<V>
    end
```

### 缓存写入流程

```mermaid
sequenceDiagram
autonumber
    participant Caller as CoherentCache
    participant RDC as RedisDistributedCache
    participant Codec as CodecExecutor
    participant Redis as Redis

    Caller->>RDC: setCache(key, cacheValue)
    RDC->>RDC: 检查是否过期 -> 跳过

    alt 永不过期
        RDC->>Codec: executeAndEncode(key, cacheValue)
        Codec->>Codec: toRawValue()
        Codec->>Redis: SET key value（无过期时间）
    else 有 TTL
        RDC->>Codec: executeAndEncode(key, cacheValue)
        Codec->>Codec: toRawValue()
        Codec->>Codec: 计算 expiredDuration
        Codec->>Redis: SET key value EX ttl
    end
```

## RedisCacheEvictedEventBus

[RedisCacheEvictedEventBus](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt#L32) 使用 Redis Pub/Sub 在所有应用实例间分发缓存驱逐事件。

```mermaid
graph TB
    subgraph sg_48 ["Redis Pub/Sub 缓存一致性"]

        inst1["实例 1<br>(clientId: 0:1234@10.0.0.1)"]
        style inst1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        inst2["实例 2<br>(clientId: 0:5678@10.0.0.2)"]
        style inst2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        inst3["实例 3<br>(clientId: 0:9012@10.0.0.3)"]
        style inst3 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        redis["Redis Pub/Sub<br>(Channel = cacheName)"]
        style redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        inst1 -->|"PUBLISH userCache<br>'user:123@@0:1234@10.0.0.1'"| redis
        redis -->|"传递给订阅者"| inst2
        redis -->|"传递给订阅者"| inst3
        redis -->|"传递给订阅者"| inst1
        inst1 -->|"过滤：publisherId == self<br>-> 忽略"| inst1
        inst2 -->|"驱逐 L2 条目<br>user:123"| inst2
        inst3 -->|"驱逐 L2 条目<br>user:123"| inst3
    end

```

### 事件注册

当 `CoherentCache` 由 `DefaultCoherentCacheFactory` 创建时，它会注册到事件总线：

```mermaid
sequenceDiagram
autonumber
    participant CCF as DefaultCoherentCacheFactory
    participant RCEEB as RedisCacheEvictedEventBus
    participant RMLC as RedisMessageListenerContainer
    participant Redis as Redis

    CCF->>RCEEB: register(coherentCache)
    RCEEB->>RCEEB: 创建 MessageListenerAdapter
    RCEEB->>RMLC: addMessageListener(adapter, ChannelTopic(cacheName))
    RMLC->>Redis: SUBSCRIBE cacheName
```

### MessageListenerAdapter

[MessageListenerAdapter](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt#L67) 包装 `CacheEvictedSubscriber` 以实现 Spring 的 `MessageListener` 接口。在收到 Redis 消息时，它委托给 `EvictedEvents.fromMessage()` 解析消息，然后调用 `subscriber.onEvicted()`。

## EvictedEvents 消息格式

[EvictedEvents](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/EvictedEvents.kt#L19) 定义了缓存驱逐消息的传输格式：

| 字段 | 编码 | 示例 |
|------|------|------|
| Channel | 缓存名称（来自 `NamedCache.cacheName`） | `userCache` |
| Body | `key + "@@" + clientId` | `user:123@@0:1234@10.0.0.1` |

[EvictedEvents.fromMessage()](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/EvictedEvents.kt#L22) 中的解析逻辑：
- `cacheName` = `message.channel.decodeToString()`
- 以 `"@@"` 分割 `message.body.decodeToString()` 得到 `[key, clientId]`
- 构造 `CacheEvictedEvent(cacheName, key, clientId)`

## 编解码器层次结构

编解码器系统处理缓存值与 Redis 数据结构之间的序列化。每个编解码器将特定的值类型映射到 Redis 数据类型。

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
        Redis 类型：STRING (GET/SET)
    }

    class StringToStringCodecExecutor {
        Redis 类型：STRING (GET/SET)
    }

    class MapToHashCodecExecutor {
        Redis 类型：HASH (HGETALL/HMSET)
    }

    class ObjectToHashCodecExecutor~V~ {
        -mapConverter: MapConverter
        Redis 类型：HASH (HGETALL/HMSET)
    }

    class SetToSetCodecExecutor {
        Redis 类型：SET (SMEMBERS/SADD)
    }

    CodecExecutor <|.. AbstractCodecExecutor
    AbstractCodecExecutor <|-- ObjectToJsonCodecExecutor
    AbstractCodecExecutor <|-- StringToStringCodecExecutor
    AbstractCodecExecutor <|-- MapToHashCodecExecutor
    AbstractCodecExecutor <|-- ObjectToHashCodecExecutor
    AbstractCodecExecutor <|-- SetToSetCodecExecutor
```

### 编解码器详情

| 编解码器 | 值类型 | Redis 类型 | 序列化方式 | MissingGuard 编码 |
|---------|--------|-----------|-----------|------------------|
| `ObjectToJsonCodecExecutor` | 任意 POJO | STRING | Jackson ObjectMapper JSON | `"_nil_"` 字符串 |
| `StringToStringCodecExecutor` | `String` | STRING | 直接存储（无转换） | `"_nil_"` 字符串 |
| `MapToHashCodecExecutor` | `Map<String, String>` | HASH | 直接键值映射 | `{"_nil_": "<timestamp>"}` |
| `ObjectToHashCodecExecutor` | 通过 `MapConverter` 的任意类型 | HASH | 对象 <-> Map 转换 | `{"_nil_": "<timestamp>"}` |
| `SetToSetCodecExecutor` | `Set<String>` | SET | 直接集合成员 | `{"_nil_"}` 单元素集合 |

### AbstractCodecExecutor 写入管道

[AbstractCodecExecutor](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt#L21) 在[第 45 行](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt#L45)提供了 `setPipelined()` 辅助方法，它在一个 Redis 管道中原子性地删除旧键并写入新值，防止写入窗口期间的脏读。

### 各编解码器的 MissingGuard 检测

每个编解码器有特定于编解码器的缺失守卫哨兵值检测方式，与多态的 `MissingGuard.Companion.isMissingGuard` 扩展相匹配：

```mermaid
flowchart LR
    subgraph sg_49 ["按值类型的 MissingGuard 检测"]

        check{"值类型?"}
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
        check -->|"其他"| obj
    end

```

## RedisDistributedCacheFactory

[RedisDistributedCacheFactory](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFactory.kt#L27) 继承 `AbstractCacheFactory`，创建 `RedisDistributedCache` 实例。它在[第 47 行](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisDistributedCacheFactory.kt#L47)的 `fallback()` 方法创建一个使用 `ObjectToJsonCodecExecutor`（JSON 序列化）作为默认编解码器的 `RedisDistributedCache`。

用户可以通过声明名为 `"{cacheName}.DistributedCache"` 的 Spring Bean 来自定义分布式缓存：

```kotlin
@Bean("UserCache.DistributedCache")
fun userDistributedCache(
    redisTemplate: StringRedisTemplate
): DistributedCache<User> {
    val codec = ObjectToHashCodecExecutor(
        mapConverter = object : ObjectToHashCodecExecutor.MapConverter<User> {
            override fun asValue(map: Map<String, String>): User = /* 将 map 转换为 User */
            override fun asMap(value: User): Map<String, String> = /* 将 User 转换为 map */
        },
        redisTemplate = redisTemplate
    )
    return RedisDistributedCache(redisTemplate, codec, ttl = 7200, ttlAmplitude = 60)
}
```

## 跨实例一致性流程

带跨实例失效的缓存写入完整流程：

```mermaid
sequenceDiagram
autonumber
    participant Client as 客户端 (实例 A)
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
    Redis->>DCCB: onMessage (Pub/Sub 投递)
    DCCB->>DCCB: 解析 EvictedEvent
    DCCB->>DCCB: 过滤：cacheName 匹配？
    DCCB->>DCCB: 过滤：publisherId != self？
    DCCB->>L2B: evict(cacheKey)
    Note over L2B: 实例 B 的 L2 已被失效
```

## 相关页面

- [模块概览](./index.md) -- 依赖关系图和模块说明
- [cocache-core](./cocache-core.md) -- DefaultCoherentCache、DistributedCache 接口、CacheEvictedEventBus
- [cocache-spring](./cocache-spring.md) -- AbstractCacheFactory 基类、Spring 集成
- [cocache-spring-boot-starter](./cocache-spring-boot-starter.md) -- 连接 RedisDistributedCacheFactory 的自动配置
