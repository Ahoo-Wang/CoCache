---
title: Configuration
description: Every CoCache setting — @CoCache TTLs, @CaffeineCache L2 sizing, cocache.* properties, per-cache component beans, Redis codecs, and building caches in code.
---

# Configuration

CoCache has three layers of configuration:

1. **Annotations** on the cache interface. They set TTLs, the key format, and L2 sizing.
2. **Beans** named after the cache. They replace an individual component, such as L2, L1, the key converter, or the data source.
3. **`cocache.*` properties.** They are global switches.

## `@CoCache`

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `name` | interface simple name | Cache name. Scopes the eviction channel and per-cache bean names. |
| `keyPrefix` | `cocache:{name}:` | Prefix of every storage key. Supports Spring `${…}` placeholders. |
| `keyExpression` | `""` (uses `key.toString()`) | SpEL template that turns the key into a string, for example `#{#root.tenantId}:#{#root.id}` |
| `ttl` | `3600` | Seconds a value lives. `TtlAt.FOREVER` means it never expires. |
| `ttlAmplitude` | `60` | Random jitter in seconds. Each value lives `ttl ± ttlAmplitude` seconds, never less than 1. |
| `missingTtl` | `60` | Seconds a negative entry ("not found") lives. No jitter. |

### Choosing TTLs

- **`ttl` is your staleness bound.** One race can leave an old value in Redis until it expires: a slow load can write its result after a concurrent update's evict (see [Consistency](../architecture/consistency.md#what-is-not-guaranteed)). Pick the longest staleness the business can tolerate in that rare case. `TtlAt.FOREVER` is safe only if every writer reliably calls `evict` and you accept that the race above is never repaired.
- **`missingTtl` is how long a newly created row can stay invisible** after a lookup cached its absence. Keep it short.
- **`ttlAmplitude` spreads expiries.** Keys loaded together, such as after a deploy, then don't all expire in the same second. Keep it at a few percent of `ttl`.

## `@CaffeineCache`

Configures the default L2. Without it, the defaults below apply. L2 is always bounded.

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `maximumSize` | `10000` | Maximum entries per instance |
| `initialCapacity` | unset | Initial hash-table size |
| `expireAfterAccess` | `0` (off) | Evict entries idle for this long |
| `expireUnit` | `SECONDS` | Unit of `expireAfterAccess` |

You don't need `expireAfterAccess` for correctness. Every entry carries its own `ttlAt` and is dropped when read after it expires. Idle eviction only frees memory sooner. It also records the access time on every read, which costs throughput on very hot keys.

## Properties

| Property | Default | Meaning |
|----------|---------|---------|
| `cocache.enabled` | `true` | `false` turns off all CoCache auto-configuration, including the actuator endpoints. |
| `cocache.redis.strict-failure` | `false` | `false` degrades during a Redis failure: reads fall back to the source, and writes and evicts log a warning. `true` rethrows. See [Operations](./operations.md#redis-failures). |
| `cocache.redis.missing-guard-sentinel` | `_nil_` | The Redis value that marks a negative entry. See [Operations](./operations.md#the-negative-cache-sentinel). |

The two `cocache.redis.*` properties apply only to the default Redis caches. A custom `{name}.DistributedCache` bean sets its own policy.

## Per-cache components

Each cache is assembled from components. To replace one for a single cache, declare a bean named `{cacheName}{suffix}`:

| Bean name | Type | Default |
|-----------|------|---------|
| `{name}.ClientSideCache` | `ClientSideCache<V>` | Caffeine, sized by `@CaffeineCache` |
| `{name}.DistributedCache` | `DistributedCache<V>` | `RedisDistributedCache` with the JSON codec |
| `{name}.KeyConverter` | `KeyConverter<K>` | from `keyPrefix` and `keyExpression` |
| `{name}.CacheSource` | `CacheSource<K, V>` | the unique bean of type `CacheSource<K, V>`, else `CacheSource.noOp()` |
| `{name}.JoinKeyExtractor` | `JoinKeyExtractor<V1, K2>` | built from `joinKeyExpression` if set, else the unique bean of that type |

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

**Stateful components are matched by name only.** These are `ClientSideCache`, `DistributedCache`, and `KeyConverter`. If one were matched by type, every cache with the same value type would share it: clearing one cache would clear the others, and their keys would collide. `CacheSource` and `JoinKeyExtractor` are stateless, so a unique bean of the right generic type also matches. If several beans of that type match and none is `@Primary`, startup fails. CoCache does not silently fall back to `noOp`.

### Global components

Every other bean in the auto-configuration, such as `CacheEvictedEventBus`, `ClientIdGenerator`, `CoherentCacheFactory`, the component factories, and `CacheManager`, is `@ConditionalOnMissingBean`. Declare your own bean of that type to replace it. For example, a custom `CacheEvictedEventBus` can carry evictions over Kafka. It must follow the contract in [Extending CoCache](../architecture/extending.md#invalidation-channel).

## Redis value formats

The default L1 stores each value as JSON in a Redis string. To store a value as a hash or set, use another codec in a `{name}.DistributedCache` bean:

| Codec | Value type | Redis type |
|-------|-----------|------------|
| `ObjectToJsonCodecExecutor` (default) | any type Jackson can serialize | String |
| `StringToStringCodecExecutor` | `String` | String |
| `MapToHashCodecExecutor` | `Map<String, String>` | Hash |
| `ObjectToHashCodecExecutor` | any type, via a `MapConverter` | Hash |
| `SetToSetCodecExecutor` | `Set<String>` | Set |

Reads use one atomic Lua script that returns the remaining TTL and the value in a single round trip.

## Building a cache in code

You can skip the annotations and assemble a `CoherentCache` yourself. The `CoherentCacheFactory` bean connects it to the eviction channel:

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
        cacheSource = CacheSource { id -> /* load */ null },
        keyFilter = KeyFilter.NO_OP,          // or a BloomKeyFilter
        ttlPolicy = TtlPolicy(ttl = 600, ttlAmplitude = 30, missingTtl = 30),
    )
)
```

This is also the only way to set a `KeyFilter`, such as `BloomKeyFilter`. A key filter is consulted after an L2 miss. Keys it rejects get a negative result without touching Redis or the source. You need Guava on the classpath to use `BloomKeyFilter`.
