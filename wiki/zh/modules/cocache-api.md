---
title: cocache-api 模块
description: cocache-api 模块定义了 CoCache 的所有核心接口和注解。它是一个纯 API 模块，零实现依赖，构成整个框架的契约层。
---

# cocache-api 模块

`cocache-api` 模块是 CoCache 的基础。它定义了下游模块实现的所有接口、数据契约和注解。由于没有实现依赖，任何项目都可以依赖 `cocache-api` 来基于 CoCache 契约编程，而无需引入 Guava、Caffeine、Redis 或 Spring。

## 接口层次结构

```mermaid
classDiagram
    class Cache~K, V~ {
        <<interface>>
    }

    class CacheGetter~K, V~ {
        <<interface>>
        +getCache(key: K) CacheValue~V~?
        +get(key: K) V?
        +getTtlAt(key: K) Long?
    }

    class CacheSetter~K, V~ {
        <<interface>>
        +set(key: K, ttlAt: Long, value: V)
        +set(key: K, value: V)
        +setCache(key: K, value: CacheValue~V~)
        +evict(key: K)
    }

    class TtlAt {
        <<interface>>
        +ttlAt: Long
        +isForever: Boolean
        +isExpired: Boolean
        +expiredDuration: Duration
    }

    class CacheValue~V~ {
        <<interface>>
        +value: V
        +ttlAt: Long
        +isMissingGuard: Boolean
    }

    class NamedCache {
        <<interface>>
        +cacheName: String
    }

    class ClientSideCache~V~ {
        <<interface>>
        +size: Long
        +clear()
    }

    class CacheSource~K, V~ {
        <<interface>>
        +loadCacheValue(key: K) CacheValue~V~?
    }

    class JoinCache~K1, V1, K2, V2~ {
        <<interface>>
        +joinKeyExtractor: JoinKeyExtractor~V1, K2~
        +evict(firstKey: K1, joinKey: K2)
    }

    class JoinKeyExtractor~V1, K2~ {
        <<interface>>
        +extract(firstValue: V1) K2
    }

    class JoinValue~V1, K2, V2~ {
        <<interface>>
        +firstValue: V1
        +joinKey: K2
        +secondValue: V2?
    }

    Cache <|.. CacheGetter
    Cache <|.. CacheSetter
    CacheValue ..|> TtlAt
    ClientSideCache --|> Cache : String, V
    JoinCache --|> Cache : K1, JoinValue
```

## 源文件

本模块包含恰好 **16 个源文件**，组织为 4 个包。

### 核心接口（6 个文件）

| 接口 | 文件 | 说明 |
|------|------|------|
| `Cache<K, V>` | [Cache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/Cache.kt#L21) | 顶层缓存接口，组合了 `CacheGetter` 和 `CacheSetter`。所有缓存操作由此开始。 |
| `CacheGetter<K, V>` | [CacheGetter.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/CacheGetter.kt#L20) | 只读缓存操作：`getCache()` 返回带有 TTL 元数据的 `CacheValue`，`get()` 返回原始值，`getTtlAt()` 返回过期时间戳。 |
| `CacheSetter<K, V>` | [CacheSetter.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/CacheSetter.kt#L16) | 写入缓存操作：带/不带显式 TTL 的 `set()`、带预构建 `CacheValue` 的 `setCache()`，以及 `evict()`。 |
| `CacheValue<V>` | [CacheValue.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/CacheValue.kt#L20) | 包装缓存值及其 TTL 时间戳（`ttlAt`）和 `isMissingGuard` 标志，用于缓存穿透防护。继承 `TtlAt`。 |
| `TtlAt` | [TtlAt.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/TtlAt.kt#L22) | 存活时间契约：`ttlAt`（绝对纪元秒时间戳）、`isForever`、`isExpired`、`expiredDuration`。 |
| `NamedCache` | [NamedCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/NamedCache.kt#L20) | 提供 `cacheName: String`，用于在事件总线和监控中标识缓存。 |

### 客户端缓存（1 个文件）

| 接口 | 文件 | 说明 |
|------|------|------|
| `ClientSideCache<V>` | [ClientSideCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/client/ClientSideCache.kt#L22) | L2 本地内存缓存契约。继承 `Cache<String, V>`，增加 `size` 和 `clear()`。实现包括 Map、Guava 和 Caffeine。 |

### 缓存数据源（2 个文件）

| 接口 | 文件 | 说明 |
|------|------|------|
| `CacheSource<K, V>` | [CacheSource.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/source/CacheSource.kt#L24) | L0 数据源加载器。当 L2（客户端缓存）和 L1（分布式缓存）都未命中时调用。返回 `CacheValue` 以填充缓存。`loadCacheValue()` 在失败时抛出 `TimeoutException`。 |
| `NoOpCacheSource` | [NoOpCacheSource.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/source/NoOpCacheSource.kt#L22) | 单例 `object`，`loadCacheValue()` 始终返回 `null`。用作未配置缓存数据源时的默认值。可通过 `CacheSource.noOp()` 访问。 |

### JoinCache（3 个文件）

| 接口 | 文件 | 说明 |
|------|------|------|
| `JoinCache<K1, V1, K2, V2>` | [JoinCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/join/JoinCache.kt#L23) | 组合两个缓存值。继承 `Cache<K1, JoinValue<V1, K2, V2>>`。包含 `joinKeyExtractor` 用于从主值派生次级键，以及双键 `evict(firstKey, joinKey)`。 |
| `JoinKeyExtractor<V1, K2>` | [JoinKeyExtractor.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/join/JoinKeyExtractor.kt#L8) | 函数式接口（`fun interface`），从第一个值中提取 join/次级键。 |
| `JoinValue<V1, K2, V2>` | [JoinValue.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/join/JoinValue.kt#L16) | 结果类型，组合 `firstValue: V1`、`joinKey: K2` 和可选的 `secondValue: V2?`。 |

### 注解（4 个文件）

| 注解 | 文件 | 说明 |
|------|------|------|
| `@CoCache` | [CoCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/annotation/CoCache.kt#L29) | 标记缓存接口。参数：`name`（缓存名称，默认为接口简单名称）、`keyPrefix`、`keyExpression`（SpEL）、`ttl`（默认 `Long.MAX_VALUE` = 永不过期）、`ttlAmplitude`（默认 10 秒，用于抖动）。 |
| `@GuavaCache` | [GuavaCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/annotation/GuavaCache.kt#L28) | 配置 Guava 作为 L2 客户端缓存。参数：`initialCapacity`、`concurrencyLevel`、`maximumSize`、`expireUnit`、`expireAfterWrite`、`expireAfterAccess`。 |
| `@CaffeineCache` | [CaffeineCache.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/annotation/CaffeineCache.kt#L30) | 配置 Caffeine 作为 L2 客户端缓存。参数：`initialCapacity`、`maximumSize`、`expireUnit`、`expireAfterWrite`、`expireAfterAccess`。 |
| `@JoinCacheable` | [JoinCacheable.kt](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-api/src/main/kotlin/me/ahoo/cache/api/annotation/JoinCacheable.kt#L24) | 标记缓存接口为 JoinCache。参数：`name`、`firstCacheName`、`joinCacheName`、`joinKeyExpression`。 |

## CacheValue 数据流

以下图展示了 `CacheValue` 如何在系统中从数据源流向客户端：

```mermaid
sequenceDiagram
autonumber
    participant App as 应用程序
    participant CC as CoherentCache
    participant CSC as ClientSideCache<br>(L2)
    participant DC as DistributedCache<br>(L1)
    participant CS as CacheSource<br>(L0)

    App->>CC: get(key)
    CC->>CC: keyConverter.toStringKey(key)
    CC->>CSC: getCache(cacheKey)

    alt L2 命中且未过期
        CSC-->>CC: CacheValue<V>
        CC-->>App: value
    else L2 未命中或已过期
        CC->>DC: getCache(cacheKey)

        alt L1 命中且未过期
            DC-->>CC: CacheValue<V>
            CC->>CSC: setCache(cacheKey, value)
            CC-->>App: value
        else L1 未命中
            CC->>CS: loadCacheValue(key)

            alt 数据源有值
                CS-->>CC: CacheValue<V>
                CC->>CSC: setCache(cacheKey, value)
                CC->>DC: setCache(cacheKey, value)
                CC-->>App: value
            else 数据源无值
                CC->>CC: missingGuard(ttl, amplitude)
                CC->>CSC: setCache(cacheKey, MISSING)
                CC->>DC: setCache(cacheKey, MISSING)
                CC-->>App: null
            end
        end
    end
```

## JoinCache 组合

```mermaid
graph LR
    subgraph sg_35 ["JoinCache 数据流"]

        key1["K1（主键）"]
        style key1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        fc["FirstCache"]
        style fc fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        v1["V1（第一个值）"]
        style v1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        jke["JoinKeyExtractor<br>.extract(V1) -> K2"]
        style jke fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        k2["K2（join 键）"]
        style k2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        jc["JoinCache<br>(第二个缓存)"]
        style jc fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        v2["V2（第二个值）"]
        style v2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        jv["JoinValue(V1, K2, V2)"]
        style jv fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        key1 --> fc --> v1 --> jke --> k2 --> jc --> v2
        v1 --> jv
        v2 --> jv
    end

```

## MissingGuard 机制

`MissingGuard` 模式防止缓存穿透（也称为缓存空值/nil 攻击）。当 `CacheSource` 对数据库中不存在的键返回 `null` 时，CoCache 会存储一个哨兵值（`"_nil_"`）。后续对同一键的查找会找到哨兵值并返回 `null`，而无需查询数据库。

```mermaid
graph TB
    subgraph sg_36 ["MissingGuard 检测"]

        check{"值是<br>MissingGuard?"}
        style check fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        string_check{"值是 String<br>== '_nil_'?"}
        style string_check fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        set_check{"值是 Set<br>first == '_nil_'?"}
        style set_check fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        map_check{"值是 Map<br>firstKey == '_nil_'?"}
        style map_check fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        obj_check{"值是<br>MissingGuard?"}
        style obj_check fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        yes["返回 null<br>(穿透已阻止)"]
        style yes fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        no["返回实际值"]
        style no fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

        check --> string_check
        check --> set_check
        check --> map_check
        check --> obj_check

        string_check -->|是| yes
        set_check -->|是| yes
        map_check -->|是| yes
        obj_check -->|是| yes

        string_check -->|否| no
        set_check -->|否| no
        map_check -->|否| no
        obj_check -->|否| no
    end

```

哨兵检测逻辑位于 [MissingGuard](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-core/src/main/kotlin/me/ahoo/cache/MissingGuard.kt#L17) 伴生对象中，在 `String`、`Set`、`Map` 和实现 `MissingGuard` 标记接口的对象之间进行多态处理。

## 使用示例

```kotlin
// 1. 定义缓存接口
@CoCache(name = "userCache", keyPrefix = "user:", ttl = 3600, ttlAmplitude = 30)
@GuavaCache(maximumSize = 10000, expireAfterWrite = 600)
interface UserCache : Cache<String, User>

// 2. 在应用代码中使用
class UserService(private val userCache: UserCache) {
    fun getUser(userId: String): User? = userCache[userId]
    fun updateUser(userId: String, user: User) {
        userCache[userId] = user   // 设置 L2 + L1 + 发布事件
    }
    fun deleteUser(userId: String) {
        userCache.evict(userId)    // 驱逐 L2 + L1 + 发布事件
    }
}
```

## 相关页面

- [模块概览](./index.md) -- 依赖关系图和模块说明
- [cocache-core](./cocache-core.md) -- 所有 API 接口的默认实现
- [cocache-spring](./cocache-spring.md) -- Spring 集成和 `@EnableCoCache`
- [cocache-spring-boot-starter](./cocache-spring-boot-starter.md) -- 自动配置
