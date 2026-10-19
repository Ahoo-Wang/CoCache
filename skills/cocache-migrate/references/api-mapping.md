# 5.x to 6.x Code Mapping

Use this map to rewrite application code. Verify every target symbol against the pinned 6.x release; when a 5.x usage is not listed here, read the target's migration guide and sources rather than inventing a replacement.

## Contents

- [Modules](#modules)
- [Imports](#imports)
- [Annotations](#annotations)
- [Data source](#data-source)
- [API calls](#api-calls)
- [Components and bean conventions](#components-and-bean-conventions)
- [JoinCache](#joincache)
- [Spring Cache](#spring-cache)
- [Tests](#tests)

## Modules

| 5.x | 6.x |
|---|---|
| `cocache-spring-boot-starter` | `cocache-spring-boot-starter` |
| `cocache-spring` | `cocache-spring` (only the Spring Cache adapter and `TransactionalInvalidator`; `@EnableCoCache` moved to the starter) |
| `cocache-spring-redis` | removed; `cocache-redis` (native Lettuce) |
| `cocache-spring-cache` | removed; merged into `cocache-spring` |
| none | `cocache-codec-jackson` (Jackson 3) |
| `cocache-test` | `cocache-test` (new TCK; application tests rarely need it) |

## Imports

The base package changes from `me.ahoo.cache` to `me.ahoo.cocache`.

| 5.x | 6.x |
|---|---|
| `me.ahoo.cache.api.Cache` | `me.ahoo.cocache.api.Cache` |
| `me.ahoo.cache.api.annotation.CoCache` / `CaffeineCache` / `JoinCacheable` | `me.ahoo.cocache.api.annotation.*` |
| `me.ahoo.cache.spring.EnableCoCache` | `me.ahoo.cocache.spring.boot.EnableCoCache` |
| `me.ahoo.cache.api.source.CacheSource` | `me.ahoo.cocache.api.CacheLoader` |
| `me.ahoo.cache.spring.cache.CoCacheManager` | `me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager` |
| `me.ahoo.cache.api.join.*` | `me.ahoo.cocache.api.join.*` |

## Annotations

| 5.x | 6.x |
|---|---|
| `ttl`, `TtlAt.FOREVER` | `ttl`, `CoCache.FOREVER` |
| `ttlAmplitude` | `ttlJitter` |
| `missingTtl` | `absentTtl` |
| `clientSideTtl` (`0` = until L1 expiry) | `localMaxAge` (default 300 s, always finite) |
| `refreshAhead` | `refreshWindow` (once per cluster) |
| `keyPrefix` default `cocache:{name}:` | `keyPrefix` default `cocache:` followed by the namespace; placeholders no longer resolved (use the `key-prefix` property) |
| `keyExpression` | unchanged |
| `@CaffeineCache(expireUnit)` | removed; seconds only |
| Meta-annotations, `@Inherited` | removed; annotate the cache interface directly |
| none | new `namespace`, `loader`, `codec` |

`FOREVER` is valid only on `ttl` and `expireAfterAccess`; `0` only on `ttlJitter` and `refreshWindow`. A custom 5.x `keyPrefix = "user:"` produced `user:42`; 6.x produces `user:UserCache:42`, so storage keys change.

## Data source

```kotlin
// 5.x
CacheSource<String, User> { id -> repo.findById(id)?.let { CacheValue.of(it, TtlAt.at(120)) } }
// 6.x: return the value; lifetime comes from the policy or ttlOf
CacheLoader<String, User> { id -> repo.findById(id) }
```

| 5.x | 6.x |
|---|---|
| `loadCacheValue(key): CacheValue<V>?` | `load(key): V?` |
| `loadCacheValues(keys)` | `loadAll(keys): Map<K, V>` |
| Source-chosen `ttlAt` | Policy `ttl`, or per entry `ttlOf` on a spec or `CacheSpecCustomizer` |
| `{cacheName}.CacheSource` bean, else unique by type | `@CoCache(loader = "beanName")`, else unique `CacheLoader<K, V>`; several fail startup |
| `CacheSource.noOp()` stored negatives on every miss | No loader: a miss returns `null` and writes nothing |

## API calls

| 5.x | 6.x |
|---|---|
| `cache[key]`, `get(key)` | unchanged |
| `getCache(key)` | `lookup(key)` (`Lookup.Present` / `Lookup.Absent`) |
| `getTtlAt(key)` | `lookup(key).expiresAt` (expiry of the returned copy, capped by L2 limits); Redis TTL via actuator inspect or `CacheAdmin.inspect` |
| `getCaches(keys)` | `getAll(keys)` |
| `cache[key] = value`, `set(key, value)` | `put(key, value)` |
| `set(key, ttlAt, value)` | `put(key, value, Duration)` |
| `set(key, null)`, `setCache(key, MissingValue)` | removed; `invalidate(key)` or a loader returning `null` |
| `evict(key)` | `invalidate(key)`, `invalidateAll(keys)` |
| `SetElementsCache.addElements` / `removeElements` | `SetCache.add` / `remove` / `clear`, only when Redis is the only copy; otherwise `Cache<K, Set<E>>` with `invalidate` |

Move every `invalidate` after the commit while porting; 5.x code that evicted before or inside a transaction keeps its race unless fixed.

## Components and bean conventions

| 5.x | 6.x |
|---|---|
| `{name}.ClientSideCache`, `{name}.DistributedCache`, `{name}.KeyConverter`, `{name}.CacheSource` beans | `CacheSpecCustomizer` beans and `cocache.caches.[name].*` properties |
| Pluggable L2 (`ClientSideCache`, `MapClientSideCache`) | removed; L2 is always Caffeine (`@CaffeineCache`, `local-maximum-size`) |
| Pluggable L1 and Redis codec executors (JSON, hash, set) | `CoherenceBackend` SPI and `Codec<V>`; the hash shape is gone |
| `KeyConverter`, `ToStringKeyConverter` | `keyExpression` or a spec `keyEncoder`; prefix and namespace are added by CoCache |
| `KeyFilter`, `BloomKeyFilter` | `keyFilter` function on a spec; wrap your own Bloom filter |
| `CacheEvictedEventBus` | `InvalidationChannel`, part of a backend |
| `ClientIdGenerator` | one `NodeId` per `CacheRuntime` |
| `CoherentCacheFactory` and other factories | `CacheSpec` with `CacheRuntime.create(spec)`, or a `CacheSpec` bean |

A custom 5.x L1 store or event bus becomes a `CoherenceBackend` implementation; that part is a `cocache-extend` task once the rest of the migration is mapped.

## JoinCache

| 5.x | 6.x |
|---|---|
| `JoinCache` is a `Cache<K1, JoinValue>` | Read-only view, not a `Cache`: `get`, `getAll`, `invalidate(key)`, `invalidate(firstKey, joinKey)` |
| `set`, `getCache`, `getTtlAt` | removed; write and inspect the component caches |
| `evict(key)` / `evict(firstKey, joinKey)` | `invalidate(key)` (first cache) / `invalidate(firstKey, joinKey)` (both) |
| `JoinValue.joinKey: K2` | `joinKey: K2?` |
| Listed in `@EnableCoCache(caches)` | Listed in `@EnableCoCache(joinCaches)` |
| `firstCacheName` / `joinCacheName` optional | required; resolved by name only |
| `{name}.JoinKeyExtractor` bean | `joinKeyExtractor = "beanName"`, else the unique `JoinKeyExtractor<V1, K2>` |

## Spring Cache

- `CoCacheManager` becomes `CoCacheSpringCacheManager(runtime)`, registered by the starter and transaction-aware with `spring-tx`.
- Add `sync = true` to every `@Cacheable` on a CoCache cache; the starter warns at startup, and `cocache.spring-cache.require-sync: true` fails it.
- `put(key, null)` now invalidates instead of storing a negative entry.

## Tests

Replace `MapClientSideCache`/`InMemoryDistributedCache` setups with `InMemoryBackend` and `ManualClock`, or `@SpringBootTest(properties = ["cocache.backend=memory"])`. Keep tests that asserted 5.x behavior only after checking the behavior still exists in 6.x (see `configuration-mapping.md`, behavior changes).
