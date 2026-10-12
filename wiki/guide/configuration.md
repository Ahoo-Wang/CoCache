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
| `refreshAhead` | `0` (off) | Refresh window in seconds. When a value has at most this long left to live, reads still return it, and one background refresh reloads it. Must be less than `ttl` and `clientSideTtl`. |
| `clientSideTtl` | `0` (unlimited) | Maximum seconds an L2 (local) copy lives before it is refilled from L1. `0` keeps the L1 expiry. |

### Choosing TTLs

- **`ttl` is your staleness bound.** One race can leave an old value in Redis until it expires: a slow load can write its result after a concurrent update's evict (see [Consistency](../architecture/consistency.md#what-is-not-guaranteed)). Pick the longest staleness the business can tolerate in that rare case. `TtlAt.FOREVER` is safe only if every writer reliably calls `evict` and you accept that the race above is never repaired.
- **`missingTtl` is how long a newly created row can stay invisible** after a lookup cached its absence. Keep it short.
- **`clientSideTtl` bounds staleness from lost eviction events.** Eviction events are delivered at most once. If one is lost, a peer keeps serving its L2 copy until `ttl`. With `clientSideTtl = 30`, that copy lives at most 30 seconds and is then refilled from Redis. The hit path costs nothing extra, but each L2 expiry costs one Redis round trip. When set, an L2 hit reports the L2 copy's expiry from `getTtlAt`.
- **`refreshAhead` removes the latency spike when a hot key expires.** Without it, the first read after expiry waits for the source. With `refreshAhead = 60`, a read within the last 60 seconds triggers one background reload per key per instance, and readers keep getting the current value. Expired values are never served, so `ttl` still bounds staleness. Negative entries are not refreshed. For Set caches with atomic element updates, L1 is authoritative, so a refresh only refills L2.
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
| `cocache.metrics.client-side-hits` | `false` | Count L2 hits as `cocache.hits{level=l2}`. Off by default because the counter costs the L2 hit path about 8% of its throughput on one thread and 15% on eight. See [Operations](./operations.md#metrics). |
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

## Set-valued caches: atomic element updates {#set-elements}

Reading a whole set, modifying it, and writing it back loses updates when two instances do it at the same time. For a `Set` cache whose contents live only in the cache (an index, a membership list), also declare `SetElementsCache` and back it with `RedisSetDistributedCache`:

```kotlin
@CoCache(ttl = TtlAt.FOREVER)
interface PolicyIndexCache : Cache<String, Set<String>>, SetElementsCache<String, String>

@Bean("PolicyIndexCache.DistributedCache")
fun policyIndexDistributedCache(redisTemplate: StringRedisTemplate): DistributedCache<Set<String>> =
    RedisSetDistributedCache(redisTemplate)

policyIndexCache.addElements(key, listOf("p1"))
policyIndexCache.removeElements(key, listOf("p1"))
```

- Each call is one atomic Lua script on a native Redis Set (the same layout as `SetToSetCodecExecutor`). Afterwards the local copy is evicted and an eviction event is published, so other instances reload.
- The cache is the authoritative copy: these calls never consult the `CacheSource`. If the set's data lives in your database, update the database and `evict` instead.
- Adding to a key that holds the negative cache replaces it. The key's expiry follows the cache's TTL policy, so `TtlAt.FOREVER` leaves the key without an expiry.
- Removing from an absent key or a negative cache does nothing. Removing the last element deletes the key, so the next read returns `null`.
- Elements equal to the negative-cache sentinel (`_nil_` by default) are rejected.
- A Redis failure always throws, even with `strictFailure = false`, so an update is never dropped silently. Both calls are idempotent, so you can retry.
- If the interface declares `SetElementsCache` but its `DistributedCache` can't support it, for example a plain `RedisDistributedCache`, creating the cache fails at startup.

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
