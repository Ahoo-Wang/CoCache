---
title: 配置
description: CoCache 的全部配置：@CoCache 的 TTL、@CaffeineCache 的 L2 容量、cocache.* 属性、按缓存定制的组件 bean、Redis 编码以及用代码构建缓存。
---

# 配置

CoCache 的配置分三层：

1. **缓存接口上的注解**：TTL、key 格式、L2 容量。
2. **以缓存命名的 bean**：替换单个组件，如 L2、L1、key 转换器或数据源。
3. **`cocache.*` 属性**：全局开关。

## `@CoCache`

| 属性 | 默认值 | 含义 |
|------|--------|------|
| `name` | 接口简单类名 | 缓存名。决定淘汰事件频道与按缓存定制的 bean 名 |
| `keyPrefix` | `cocache:{name}:` | 所有存储 key 的前缀，支持 Spring `${…}` 占位符 |
| `keyExpression` | `""`（使用 `key.toString()`） | 把 key 转为字符串的 SpEL 模板，例如 `#{#root.tenantId}:#{#root.id}` |
| `ttl` | `3600` | 值的存活秒数；`TtlAt.FOREVER` 表示永不过期 |
| `ttlAmplitude` | `60` | 随机抖动秒数：每个值存活 `ttl ± ttlAmplitude` 秒，且不少于 1 秒 |
| `missingTtl` | `60` | 负缓存（“不存在”）的存活秒数，不抖动 |

### 如何选择 TTL

- **`ttl` 就是陈旧度上限。** 有一种竞态会让旧值留在 Redis 中直到过期：慢加载可能在并发更新的淘汰之后才写入结果（见[一致性](../architecture/consistency.md#what-is-not-guaranteed)）。按业务在这种少见情况下能容忍的最长陈旧时间来选。只有当所有写入方都可靠地调用 `evict`、并且接受上述竞态永不修复时，`TtlAt.FOREVER` 才是安全的。
- **`missingTtl` 是新建数据可能“不可见”的时长**：之前的查询缓存了它不存在。应保持较短。
- **`ttlAmplitude` 打散过期时间**，一起加载的 key（如发布后）不会在同一秒过期。取 `ttl` 的百分之几即可。

## `@CaffeineCache`

配置默认的 L2；不标注时使用下列默认值。L2 始终有界。

| 属性 | 默认值 | 含义 |
|------|--------|------|
| `maximumSize` | `10000` | 每个实例的最大条目数 |
| `initialCapacity` | 不设置 | 初始哈希表大小 |
| `expireAfterAccess` | `0`（关闭） | 空闲多久后淘汰 |
| `expireUnit` | `SECONDS` | `expireAfterAccess` 的单位 |

正确性不依赖 `expireAfterAccess`：每个条目自带 `ttlAt`，过期后在读取时淘汰。空闲淘汰只是更早释放内存；它在每次读取时记录访问时间，对极热的 key 会损失吞吐。

## 属性

| 属性 | 默认值 | 含义 |
|------|--------|------|
| `cocache.enabled` | `true` | `false` 关闭全部 CoCache 自动配置（包括 Actuator 端点） |
| `cocache.redis.strict-failure` | `false` | `false` 在 Redis 故障时降级：读取回源，写入与淘汰仅告警。`true` 重抛异常。见[运维](./operations.md#redis-failures) |
| `cocache.redis.missing-guard-sentinel` | `_nil_` | Redis 中标记负缓存的值。见[运维](./operations.md#the-negative-cache-sentinel) |

两个 `cocache.redis.*` 属性只作用于默认创建的 Redis 缓存；自定义的 `{name}.DistributedCache` bean 自行决定策略。

## 按缓存定制组件 {#per-cache-components}

每个缓存由若干组件组装而成。要为单个缓存替换某个组件，声明名为 `{cacheName}{suffix}` 的 bean：

| Bean 名 | 类型 | 默认 |
|---------|------|------|
| `{name}.ClientSideCache` | `ClientSideCache<V>` | Caffeine，容量取自 `@CaffeineCache` |
| `{name}.DistributedCache` | `DistributedCache<V>` | 使用 JSON 编码的 `RedisDistributedCache` |
| `{name}.KeyConverter` | `KeyConverter<K>` | 由 `keyPrefix` 与 `keyExpression` 构建 |
| `{name}.CacheSource` | `CacheSource<K, V>` | 唯一的 `CacheSource<K, V>` 类型 bean，否则为 `CacheSource.noOp()` |
| `{name}.JoinKeyExtractor` | `JoinKeyExtractor<V1, K2>` | 设置了 `joinKeyExpression` 时由其构建，否则为唯一的该类型 bean |

```kotlin
@Configuration
class UserCacheConfiguration {
    @Bean("UserCache.ClientSideCache")
    fun userClientSideCache(): ClientSideCache<User> =
        CaffeineClientSideCache.build(maximumSize = 100_000)

    @Bean("UserCache.DistributedCache")
    fun userDistributedCache(redisTemplate: StringRedisTemplate, objectMapper: ObjectMapper): DistributedCache<User> =
        RedisDistributedCache(redisTemplate, ObjectToJsonCodecExecutor(User::class.java, redisTemplate, objectMapper))
}
```

**有状态组件只按名称匹配。** 有状态组件是 `ClientSideCache`、`DistributedCache` 与 `KeyConverter`。若按类型匹配，所有同值类型的缓存会共享同一实例：清空一个会清空全部，key 也会互相冲突。`CacheSource` 与 `JoinKeyExtractor` 无状态，因此也可按唯一的泛型类型匹配。若有多个同类型候选且都不是 `@Primary`，启动失败，CoCache 不会悄悄回退为 `noOp`。

### 全局组件

自动配置中的其它 bean，如 `CacheEvictedEventBus`、`ClientIdGenerator`、`CoherentCacheFactory`、各组件工厂与 `CacheManager`，都带有 `@ConditionalOnMissingBean`。声明同类型的 bean 即可替换。例如，自定义的 `CacheEvictedEventBus` 可以通过 Kafka 传递淘汰事件，但必须遵守[扩展 CoCache](../architecture/extending.md#invalidation-channel) 中的约定。

## Redis 存储格式

默认的 L1 把值以 JSON 存入 Redis String。要以 Hash 或 Set 存储，请在 `{name}.DistributedCache` bean 中换用其它编码器：

| 编码器 | 值类型 | Redis 类型 |
|--------|--------|------------|
| `ObjectToJsonCodecExecutor`（默认） | Jackson 能序列化的任意类型 | String |
| `StringToStringCodecExecutor` | `String` | String |
| `MapToHashCodecExecutor` | `Map<String, String>` | Hash |
| `ObjectToHashCodecExecutor` | 任意类型，经 `MapConverter` 转换 | Hash |
| `SetToSetCodecExecutor` | `Set<String>` | Set |

读取通过一个原子 Lua 脚本，一次往返同时取回剩余 TTL 与值。

## 用代码构建缓存

也可以不用注解，自己组装 `CoherentCache`。`CoherentCacheFactory` bean 会把它接入淘汰事件通道：

```kotlin
@Bean
fun userCache(
    redisTemplate: StringRedisTemplate,
    objectMapper: ObjectMapper,
    coherentCacheFactory: CoherentCacheFactory,
    clientIdGenerator: ClientIdGenerator,
): CoherentCache<String, User> = coherentCacheFactory.create(
    CoherentCacheConfiguration(
        cacheName = "userCache",
        clientId = clientIdGenerator.generate(),
        keyConverter = ToStringKeyConverter("user:"),
        distributedCache = RedisDistributedCache(
            redisTemplate,
            ObjectToJsonCodecExecutor(User::class.java, redisTemplate, objectMapper),
        ),
        clientSideCache = CaffeineClientSideCache.build(maximumSize = 100_000),
        cacheSource = CacheSource { id -> /* 加载 */ null },
        keyFilter = KeyFilter.NO_OP,          // 或 BloomKeyFilter
        ttlPolicy = TtlPolicy(ttl = 600, ttlAmplitude = 30, missingTtl = 30),
    )
)
```

这也是设置 `KeyFilter`（如 `BloomKeyFilter`）的唯一方式。key 过滤器在 L2 未命中后才参与：被它判定为不存在的 key 直接得到负结果，不访问 Redis 与数据源。使用 `BloomKeyFilter` 需要 classpath 上有 Guava。
