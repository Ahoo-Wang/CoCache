# Migrating from CoCache 5.x to 6.x

6.0 replaces 5.x entirely: new packages (`me.ahoo.cache.*` → `me.ahoo.cocache.*`), a new Redis wire format, and new configuration keys. The full guide is the wiki page [Migrating from 5.x to 6.0](https://cocache.ahoo.me/guide/migration-6). This page is the checklist an agent needs.

## Rollout

- **No rolling upgrade.** The wire format and invalidation channel changed, so 5.x and 6.x instances cannot share a Redis. Switch every instance of a service at once.
- Old 5.x values in Redis are read as unrecognized content (format 0), reclaimed, and reloaded; no manual cleanup is needed. Deleting the old keys by pattern at cutover frees memory immediately.
- Redis keys change to `{keyPrefix}{namespace}:{key}` (default prefix `cocache:`, namespace = cache name). A 5.x `@CoCache(keyPrefix = "user:")` key `user:42` becomes `user:UserCache:42`.
- L1 values now carry a binary header. Other services can no longer `GET` / `HGET` CoCache keys and parse JSON.
- Jedis is no longer used by CoCache. It builds its own Lettuce client from `spring.data.redis.*`; the app may keep Jedis for its own access.

## Interface Declarations

| 5.x | 6.x |
|-----|-----|
| `me.ahoo.cache.api.Cache` | `me.ahoo.cocache.api.Cache` |
| `me.ahoo.cache.api.annotation.CoCache` | `me.ahoo.cocache.api.annotation.CoCache` |
| `me.ahoo.cache.api.annotation.CaffeineCache` | `me.ahoo.cocache.api.annotation.CaffeineCache` (seconds only; no `expireUnit`) |
| `me.ahoo.cache.spring.EnableCoCache` | `me.ahoo.cocache.spring.boot.EnableCoCache` |
| `ttlAmplitude` | `ttlJitter` |
| `missingTtl` | `absentTtl` |
| `clientSideTtl` (default 0 = unlimited) | `localMaxAge` (default 300 s; always finite) |
| `refreshAhead` | `refreshWindow` (one refresh per key cluster-wide, not per instance) |
| `TtlAt.FOREVER` | `CoCache.FOREVER` (only for `ttl` and `@CaffeineCache.expireAfterAccess`) |
| `{name}.CacheSource` bean | unique `CacheLoader<K, V>` bean, or `@CoCache(loader = "beanName")` |
| `{name}.KeyConverter` bean | `@CoCache(keyExpression = "...")` or `CacheSpec.keyEncoder` |
| `{name}.ClientSideCache` / `{name}.DistributedCache` beans | removed; L2 is always Caffeine, configure it with `@CaffeineCache`, `local-maximum-size`, or a `CacheSpecCustomizer` |
| `MapClientSideCache`, `InMemoryDistributedCache` in tests | `InMemoryBackend` + `ManualClock` |

## Data Source

```kotlin
// 5.x
CacheSource<String, User> { id -> repo.findById(id)?.let { CacheValue.of(it, TtlAt.at(120)) } }
// 6.x: return the value; lifetime comes from the policy or ttlOf
CacheLoader<String, User> { id -> repo.findById(id) }
```

Per-entry lifetimes move to `CacheSpec.ttlOf` (spec beans, or a `CacheSpecCustomizer` for interface caches). Batch loading moves from `loadCacheValues` to `CacheLoader.loadAll`.

## API Calls

| 5.x | 6.x |
|-----|-----|
| `cache[key]`, `get(key)` | unchanged |
| `getCache(key)` (`PresentValue` / `MissingValue`) | `lookup(key)` (`Lookup.Present` / `Lookup.Absent`) |
| `getTtlAt(key)` | `lookup(key).expiresAt` |
| `getCaches(keys)` | `getAll(keys)` |
| `cache[key] = value`, `set(key, value)` | `put(key, value)` |
| `set(key, ttlAt, value)` | `put(key, value, Duration)` |
| `set(key, null)`, `setCache(key, MissingValue)` | `invalidate(key)` (negative entries are written only when the loader returns `null`) |
| `evict(key)` | `invalidate(key)` |
| `SetElementsCache.addElements` / `removeElements` | `SetCache.add` / `remove`, only if Redis is the only copy; otherwise use `Cache<K, Set<E>>` + `invalidate` |
| `JoinCache`, `@JoinCacheable` | kept under `me.ahoo.cocache.api.join` / `me.ahoo.cocache.api.annotation`, but a read-only view (not a `Cache`): `evict` → `invalidate`, no `set`, register with `@EnableCoCache(joinCaches = [...])`, `firstCacheName`/`joinCacheName` required, extractor bean via `joinKeyExtractor = "beanName"` (no `{name}.JoinKeyExtractor` convention); see `join-cache.md` |
| `CoCacheManager` (Spring Cache) | `CoCacheSpringCacheManager`; use `@Cacheable(sync = true)` |

Move `invalidate` after commit: use `TransactionalInvalidator` inside transactions.

## Configuration Keys

Removed 5.x keys fail startup with a message naming the replacement.

| 5.x | 6.x |
|-----|-----|
| `cocache.redis.strict-failure: true` | `cocache.defaults.read-failure: throw` (or per cache under `cocache.caches.[name]`) |
| `cocache.redis.eviction-channel: sharded` | `cocache.redis.channel-mode: sharded` |
| `cocache.redis.missing-guard-sentinel` | removed (no in-band sentinel) |
| `cocache.load.max-concurrent` / `acquire-timeout` | `cocache.defaults.load-max-concurrent` / `load-acquire-timeout` |
| `cocache.metrics.client-side-hits` | removed (L2 hits are not counted) |
| — | `cocache.caches.[name].*` overrides any annotation or spec value; bare policy durations are seconds |

## Operations

- Actuator: the `cocache` endpoint remains (list, per cache, `DELETE` clears local L2); `cocacheClient` is gone.
- Metrics are `cocache.*` with tag `cache`; see the wiki Operations page for the list.
- Failed invalidations are now retried in the background instead of only logged.

## Custom Implementations

`CacheStore`, `ClientSideCache`, `DistributedCache`, `CacheEvictedEventBus`, `SetElementsStore`, and `CodecExecutor` are replaced by one SPI, `CoherenceBackend` (`RemoteStore` + `InvalidationChannel`). Port custom stores to it and run `BackendProtocolSpec`, `EngineSpec`, and `SetEngineSpec` (see `custom-implementation.md`, `testing.md`). Custom Redis codecs become a `Codec<V>`.
