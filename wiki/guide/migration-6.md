---
title: Migrating from 5.x to 6.0
description: Everything that changes between CoCache 5.x and 6.0 — modules, packages, annotations, API, properties, Redis wire format, metrics, actuator, removed features, and how to switch a cluster over.
---

# Migrating from 5.x to 6.0

6.0 is a rewrite. The interface-style declaration (`@CoCache interface UserCache : Cache<String, User>`) still works with new imports and a few renamed attributes, but almost everything around it changed: the API, the SPI, the modules, the properties, the Redis wire format, and the invalidation channel. This page lists every change and what replaces it.

## Switching a cluster over {#switch-over}

**There is no rolling upgrade.** The Redis wire format and the invalidation channel both changed, so 5.x and 6.0 instances can't share a cache: neither sees the other's invalidations, and they overwrite each other's values. Choose one of:

- **Stop every 5.x instance, then start 6.0.** Simplest. Expect a cold cache.
- **Run 6.0 side by side on separate keys**, for a blue-green deployment: give every 6.0 cache a different `keyPrefix` (or namespace), or point 6.0 at a different Redis database, and shift traffic. Remember that writes on one side don't invalidate the other side.

Then deal with the keys 5.x left behind:

- With the default layout, a 6.0 key and a 5.x key are both `cocache:{cacheName}:{key}`, so 6.0 reads old values. Any 5.x value (string, hash, or native set, including the `_nil_` sentinel) is recognized as foreign content (format 0), reclaimed only if it is still unchanged, and reloaded from the source. No manual cleanup is needed.
- **A 5.x `SetElementsCache` is the exception**: its set was the only copy, and 6.0 reads it as foreign content, so a `SetCache` on the same key starts empty. Copy the data over once with `put(key, fullSet)` (or use a new `keyPrefix` and copy from the old keys).
- To free memory at once, delete the old keys by prefix (`SCAN` with `MATCH`, then `UNLINK`).

`redis-cli GET` no longer shows JSON: every value starts with a 36-byte binary header. Programs that read CoCache keys directly from Redis, for example with `HGET` on a hash-encoded cache, are no longer supported.

## Modules {#modules}

| 5.x | 6.0 |
|-----|-----|
| `cocache-api` | `cocache-api` (new API and SPI, still zero dependencies) |
| `cocache-core` | `cocache-core` (new engine, `CacheRuntime`, `CacheSpec`, in-memory backend) |
| `cocache-spring` | `cocache-spring`: now only the Spring Cache adapter and `TransactionalInvalidator`. `@EnableCoCache` moved to the starter. |
| `cocache-spring-redis` | removed. Replaced by `cocache-redis` (native Lettuce, no Spring dependency). |
| `cocache-spring-cache` | removed. Merged into `cocache-spring`. |
| — | `cocache-codec-jackson` (Jackson 3 codec) |
| `cocache-spring-boot-starter` | `cocache-spring-boot-starter` |
| `cocache-test` | `cocache-test` (new TCK, see [Extending](../architecture/extending.md)) |

## Imports {#imports}

The base package changed from `me.ahoo.cache` to `me.ahoo.cocache`.

| 5.x | 6.0 |
|-----|-----|
| `me.ahoo.cache.api.Cache` | `me.ahoo.cocache.api.Cache` |
| `me.ahoo.cache.api.annotation.CoCache` | `me.ahoo.cocache.api.annotation.CoCache` |
| `me.ahoo.cache.api.annotation.CaffeineCache` | `me.ahoo.cocache.api.annotation.CaffeineCache` |
| `me.ahoo.cache.spring.EnableCoCache` | `me.ahoo.cocache.spring.boot.EnableCoCache` (in the starter) |
| `me.ahoo.cache.api.source.CacheSource` | `me.ahoo.cocache.api.CacheLoader` |
| `me.ahoo.cache.spring.cache.CoCacheManager` | `me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager` |

## Annotations {#annotations}

Durations are still seconds.

| 5.x | 6.0 |
|-----|-----|
| `@CoCache(ttl)` | `ttl`. `TtlAt.FOREVER` becomes `CoCache.FOREVER`. |
| `@CoCache(ttlAmplitude)` | `ttlJitter` |
| `@CoCache(missingTtl)` | `absentTtl` |
| `@CoCache(clientSideTtl)` | `localMaxAge`. **`0` (unlimited) has no equivalent**, see [below](#semantics). |
| `@CoCache(refreshAhead)` | `refreshWindow`. `0` still means off. |
| `@CoCache(keyPrefix)` default `cocache:{name}:` | `keyPrefix` default `cocache:`, followed by the namespace. Spring `${…}` placeholders are no longer resolved. |
| `@CoCache(keyExpression)` | `keyExpression` (same SpEL template, key as root object) |
| — | new: `namespace`, `loader`, `codec` |
| `@CaffeineCache(maximumSize, initialCapacity, expireAfterAccess)` | same names |
| `@CaffeineCache(expireUnit)` | removed: `expireAfterAccess` is always seconds |
| Meta-annotations and `@Inherited` | removed: annotations must be placed directly on the cache interface |
| `@JoinCacheable` | removed, see [JoinCache](#joincache) |

In 6.0, `FOREVER` is accepted only by `ttl` and `expireAfterAccess`, and `0` only by `ttlJitter` and `refreshWindow`. Anything else fails startup.

## Data source {#data-source}

| 5.x | 6.0 |
|-----|-----|
| `CacheSource<K, V>.loadCacheValue(key): CacheValue<V>?` | `CacheLoader<K, V>.load(key): V?` |
| `loadCacheValues(keys): Map<K, CacheValue<V>>` | `loadAll(keys): Map<K, V>` |
| The source chose each value's `ttlAt` | The policy decides (`ttl` with jitter). Per entry: `CacheSpec.ttlOf(key, value)`, see [Per-entry TTL](./configuration.md#per-entry-ttl). |
| Bean named `{cacheName}.CacheSource`, else the unique `CacheSource<K, V>` | `@CoCache(loader = "beanName")`, else the unique `CacheLoader<K, V>`. Several candidates fail startup. |
| `CacheSource.noOp()`: every miss stored a negative entry in Redis | No loader: a miss returns `null` and writes nothing to Redis |

## API {#api}

| 5.x | 6.0 |
|-----|-----|
| `cache[key]` / `get(key)` | `cache[key]` / `get(key)` |
| `getCache(key): CacheValue<V>?` | `lookup(key): Lookup<V>` (`Present(value, expiresAt)` / `Absent(expiresAt)`) |
| `getTtlAt(key)` | `lookup(key).expiresAt` (an `Instant`, millisecond precision) |
| `getCaches(keys)` / `getAll(keys)` | `getAll(keys)`, now coalesced with concurrent single-key loads |
| `cache[key] = value` / `set(key, value)` | `put(key, value)` |
| `set(key, ttlAt, value)` | `put(key, value, ttl: Duration)` |
| `set(key, null)` / `setCache(key, MissingValue)` | removed. Use `invalidate(key)`, or let the loader return `null`. |
| `evict(key)` | `invalidate(key)`, `invalidateAll(keys)` |
| — | `get(key, loader)`: protected load with a caller-supplied loader |
| `SetElementsCache.addElements` / `removeElements` | `SetCache.add` / `remove` / `clear` |
| `CacheValue`, `PresentValue`, `MissingValue`, `TtlAt`, `CacheClock` object | internal, or replaced by `java.time` types and an injectable `CacheClock` |

`SetCache` is only for sets whose only copy is Redis: it has no loader, and `invalidate` throws. If the set's data lives in your database, declare `Cache<K, Set<E>>` and invalidate. See [Set caches](./configuration.md#set-elements).

## Components {#components}

| 5.x | 6.0 |
|-----|-----|
| Bean-name conventions `{name}.ClientSideCache`, `{name}.DistributedCache`, `{name}.KeyConverter`, `{name}.CacheSource` | `CacheSpecCustomizer` beans and `cocache.caches.[name].*` properties |
| Pluggable L2 (`ClientSideCache`, `MapClientSideCache`) | removed: L2 is always Caffeine. Size it with `@CaffeineCache` or `local-maximum-size`. In tests, use `InMemoryBackend` and `ManualClock`. |
| Pluggable L1 (`DistributedCache`) and Redis codecs (`ObjectToJsonCodecExecutor`, `MapToHashCodecExecutor`, `SetToSetCodecExecutor`, …) | `CoherenceBackend` SPI. The Redis backend has two shapes, scalar and set, and a `Codec<V>` decides the bytes. The hash shape is gone. |
| `KeyConverter`, `ToStringKeyConverter` | `keyExpression`, or `keyEncoder` on a spec. The prefix and namespace are added by CoCache. |
| `KeyFilter`, `BloomKeyFilter` | `keyFilter` function on a spec. Wrap your own Bloom filter. |
| `CacheEvictedEventBus` | `InvalidationChannel` (part of a `CoherenceBackend`) |
| `ClientIdGenerator` (one id per cache) | one `NodeId` per `CacheRuntime` |
| `CoherentCacheFactory`, `CacheFactory`, and the other factories | `CacheSpec` + `CacheRuntime.create(spec)`, or a `CacheSpec` bean |

## JoinCache {#joincache}

The JoinCache subsystem (`@JoinCacheable`, `JoinCache`, `JoinValue`, `JoinKeyExtractor`, `joinKeyExpression`) is removed. Compose the two caches with two `getAll` calls, as shown in [Composing two caches](./quick-start.md#composing-caches).

## Spring Cache {#spring-cache}

- `CoCacheManager` is now `CoCacheSpringCacheManager`, built from a `CacheRuntime`.
- `@Cacheable(sync = true)` now loads through a lease- and stamp-protected path. Use it wherever you need CoCache's guarantees: without `sync`, Spring's unconditional `put` can overwrite an invalidation.
- `put(key, null)` now invalidates instead of storing a negative entry.
- With `spring-tx`, the manager is transaction-aware: `@CacheEvict` and `@CachePut` inside a transaction run after commit. For direct calls, use `TransactionalInvalidator`.

See [Spring Cache](./spring-cache.md).

## Properties {#properties}

Removed 5.x keys fail startup with a message that names the replacement.

| 5.x | 6.0 |
|-----|-----|
| `cocache.enabled` | `cocache.enabled` |
| `cocache.redis.strict-failure: true` | `cocache.defaults.read-failure: throw` (or per cache under `cocache.caches.[name]`). Explicit writes always throw in 6.0. |
| `cocache.redis.eviction-channel: sharded` | `cocache.redis.channel-mode: sharded` |
| `cocache.redis.missing-guard-sentinel` | removed: negative entries are a state byte in the value header, so there is no sentinel |
| `cocache.load.max-concurrent` | `cocache.defaults.load-max-concurrent` |
| `cocache.load.acquire-timeout` | `cocache.defaults.load-acquire-timeout` |
| `cocache.metrics.client-side-hits` | removed: there is no L2 hit counter |
| `cocache.next.*` (6.0 previews) | `cocache.*` |

New: every policy field under `cocache.defaults.*` and `cocache.caches.[name].*`, and `cocache.redis.command-timeout`, `heartbeat`, `invalidate-batch-size`, `client-name`. See [Configuration](./configuration.md#properties).

## Redis {#redis}

| | 5.x | 6.0 |
|-|-----|-----|
| Client | Spring Data Redis, Lettuce or Jedis | Native Lettuce. CoCache builds its own client from `spring.data.redis.*`. **Jedis is not supported**: add Lettuce (it doesn't affect your own Redis client). |
| Topologies | standalone, Sentinel, Cluster | standalone, Sentinel, Cluster. Static master-replica fails startup. |
| Minimum version | — | Redis 7.0+ or Valkey 7.2+ |
| Key | `keyPrefix` + key, default prefix `cocache:{name}:` | `{keyPrefix}{namespace}:{key}`, default `cocache:{name}:{key}`. A custom `keyPrefix: "user:"` gave `user:42` and now gives `user:UserCache:42`. |
| Value | JSON string, hash, or set, with an in-band `_nil_` sentinel | 36-byte binary header + codec bytes. Sets are hashes with `!f` and `+{member}` fields. |
| TTL precision | seconds | milliseconds |
| Channel | the cache name, for example `UserCache` | `cocache:inv:{namespace}`, for example `cocache:inv:UserCache` |
| Message | `{key}@@{publisherId}` text | binary frame (`InvalidationFrame`), see [Operations](./operations.md#external-eviction) |
| Invalidation | `DEL`, then `PUBLISH` (2 round trips) | one atomic script (`pubsub` mode) |

External programs that evicted with `DEL` + `PUBLISH {cacheName} key@@id` must switch to the [6.0 protocol](./operations.md#external-eviction).

## Actuator {#actuator}

| 5.x | 6.0 |
|-----|-----|
| `GET /actuator/cocache` | `GET /actuator/cocache`: name, L2 size, load counts, and every metric counter |
| `GET /actuator/cocache/{name}` | `GET /actuator/cocache/{name}` |
| `GET /actuator/cocache/{name}/{key}` (loaded on a miss) | removed |
| `DELETE /actuator/cocache/{name}/{key}` | removed: call `invalidate` from your application |
| `cocacheClient` endpoint (`GET /{name}`, `GET /{name}/{key}`, `DELETE /{name}`) | removed. `DELETE /actuator/cocache/{name}` clears this instance's L2. |

Remove `cocacheClient` from `management.endpoints.web.exposure.include`.

## Metrics {#metrics}

All meters keep the `cache` tag.

| 5.x | 6.0 |
|-----|-----|
| `cocache.hits{level=l2}` | removed |
| `cocache.hits{level=l1}` | `cocache.hits{level=l1}` |
| `cocache.misses` | `cocache.misses` |
| `cocache.loads` (timer) | `cocache.loads` (function timer) |
| `cocache.load.failures` | `cocache.load.failures` |
| `cocache.load.rejections` | `cocache.load.rejections` |
| `cocache.filtered` | `cocache.filtered` |
| `cocache.stale.discards` | `cocache.stale.discards` |
| `cocache.evictions.received` | `cocache.invalidations.received` |
| `cocache.resets` | `cocache.resets` (also counts heartbeat failures) |
| `cocache.refreshes` | `cocache.refreshes` |
| `cocache.l2.size` | `cocache.local.size` |
| `cocache.eviction.publish.failures` (no `cache` tag) | removed. Invalidation and broadcast are one script, so a failure counts in `cocache.write.failures`, and a final failure in `cocache.invalidations.dropped`. |

New: `lease.waits`, `lease.wait.timeouts`, `fill.rejections`, `degraded.fills`, `reclaims`, `unknown.formats`, `refresh.rejections`, `remote.failures`, `write.failures`, `invalidation.retries`, `invalidations.dropped`. See [Operations](./operations.md#metrics).

## Behavior changes {#semantics}

- **L2 copies are always bounded by `localMaxAge`, 5 minutes by default.** In 5.x, `clientSideTtl = 0` (the default) let an L2 copy live until its L1 expiry, so a lost event could leave it stale for up to `ttl`. 6.0 has no unlimited setting: `localMaxAge` is the staleness bound when events are lost. Set it as long as you need (up to 100 years), but it is always finite.
- **Refresh-ahead runs once per cluster**, not once per instance: only the node that wins a refresh token in Redis reloads.
- **Concurrent misses are coalesced across instances** through an L1 lease, if the loader finishes within `lease-wait` (200 ms by default).
- **The cache-aside race is gone.** In 5.x, a slow load could write an old value into Redis after a concurrent evict, bounded only by `ttl`. In 6.0, every fill needs the lease it took before loading, and an invalidation destroys it.
- **`put` no longer writes L2.** The next read on that instance fills L2 from Redis.
- **Explicit writes throw.** `put`, `add`, `remove`, and `clear` throw `CacheWriteException` when Redis fails. 5.x logged a warning unless `strict-failure` was set.
- **Failed invalidations are retried** in the background instead of only logged, and misses for those keys skip L1 until the retry succeeds.
- **`ttlJitter` is capped at `ttl / 2`**, and every policy duration is at most 100 years.
- **Validation is stricter.** `refreshWindow` must be less than `ttl − ttlJitter` and less than `localMaxAge`. An unknown cache name under `cocache.caches` fails startup.
