---
title: Upgrading from 5.x
description: Everything that changes between CoCache 5.x and 6.0 — modules, packages, annotations, API, properties, Redis wire format, metrics, actuator, removed features, and how to switch a cluster over.
---

# Upgrading from 5.x

6.0 is a rewrite. The interface-style declaration (`@CoCache interface UserCache : Cache<String, User>`) still works with new imports and a few renamed attributes, but almost everything around it changed: the API, the SPI, the modules, the properties, the Redis wire format, and the invalidation channel. This page lists every change and what replaces it.

## Switching a cluster over {#switch-over}

**There is no rolling upgrade.** The Redis wire format and the invalidation channel both changed, so 5.x and 6.0 instances can't share a cache: neither sees the other's invalidations, and they overwrite each other's values. Choose one of:

- **Stop every 5.x instance, then start 6.0.** Simplest. Expect a cold cache.
- **Run 6.0 side by side on separate keys**, for a blue-green deployment: give every 6.0 cache a different `keyPrefix` (or namespace), or point 6.0 at a different Redis database, and shift traffic. Remember that writes on one side don't invalidate the other side.

Then deal with the keys 5.x left behind:

- **Caches with a loader:** with the default layout, a 6.x key and a 5.x key are both `cocache:{cacheName}:{key}`, so 6.x reads old values. Any 5.x value (string, hash, or native set, including the `_nil_` sentinel) is recognized as foreign content (format 0), reclaimed only if it is still unchanged, and reloaded from the source. No manual cleanup is needed.
- **Caches without a loader, and every 5.x `SetElementsCache`:** Redis holds the only copy, so there is nothing to reload. **Migrate these keys before 6.x starts**, as described in [Caches whose only copy is Redis](#redis-as-store). Since 6.5, a 6.x read of an unmigrated key fails instead of returning "not found". Since 6.6.0, so does a `SetCache.add` or `remove` on it.
- To free memory at once, delete the old keys by prefix (`SCAN` with `MATCH`, then `UNLINK`).

`redis-cli GET` no longer shows JSON: every value starts with a 36-byte binary header. Programs that read CoCache keys directly from Redis, for example with `HGET` on a hash-encoded cache, are no longer supported.

## Caches whose only copy is Redis {#redis-as-store}

This section applies to caches with no loader (no `CacheSource` in 5.x), where Redis is the store of record, and to every 5.x `SetElementsCache`. Authorization data kept only in Redis is a typical case. These caches have nothing to reload from, so 6.x must not discard what 5.x wrote: convert the data with `CoCache5Migrator` before any 6.x instance takes traffic.

### Without migration: reads and set writes fail {#foreign-format}

A read that has no loader and finds content 6.x can't read throws `CacheReadException`, whose `cause` is `ForeignFormatException`. Such content includes a 5.x value, a native set or hash, the `_nil_` sentinel, or a value the codec can't decode. The key stays in Redis untouched, and the read counts in `cocache.unknown.formats`. A forgotten migration therefore makes reads fail (fail closed) instead of answering "not found". This also holds with `read-failure: degrade`.

Up to 6.4, the same read reclaimed the key and returned `null`. Without a loader, reclaiming deletes the only copy. A `SetCache` read an empty set, and the first `add` replaced the old members.

The `foreign-format` policy (`cocache.caches.[name].foreign-format`, or `CachePolicy.foreignFormat`) selects the behavior:

| Value | Behavior | Default for |
|-------|----------|-------------|
| `fail` | Throw `CacheReadException`. Redis and L2 are left unchanged. | Reads without a loader: `SetCache`, and `Cache` without a loader |
| `reclaim` | Reclaim the key if it is unchanged, then reload. A `SetCache` reads an empty set, and the content stays until a write replaces it. | Reads with a loader, including `get(key, loader)` on a cache without one |

`cocache.defaults.foreign-format` doesn't apply to `SetCache`.

The same policy guards `SetCache.add` and `remove` (since 6.6.0). With `fail`, the default for a `SetCache`, adding or removing members on a key that holds content 6.x can't read throws `CacheWriteException`, whose `cause` is `ForeignFormatException`. Such content is an unmigrated native set, string or hash, a hash without the 6.x format field, or a set written by a newer CoCache version. The check and the write are one atomic script: the key keeps its members and TTL, nothing is broadcast, and the write counts in `cocache.unknown.formats` and `cocache.write.failures`. The key can still be migrated afterwards. In 6.5.0 and earlier, and with `reclaim`, a single `add` replaced the whole native set with the new members, and the key then looked migrated, so `CoCache5Migrator` skipped it and the old members were lost for good.

Whole-value writes aren't guarded: `put` and `putAll` (on a `Cache` or a `SetCache`) replace whatever the key holds, because the caller supplies the complete value. The 5.x content of that key is then gone, and `CoCache5Migrator` skips the key, so no backup of it is made. Migrate before 6.x takes writes.

### Migrating with `CoCache5Migrator` {#cocache5-migrator}

`CoCache5Migrator` (module `cocache-redis`, since 6.5) converts 5.x keys to the 6.x format. It covers every 5.x value format (5.0 to 5.4 share one wire format):

| 5.x `CodecExecutor` | Redis type | 6.x cache | Migration |
|---------------------|------------|-----------|-----------|
| `ObjectToJsonCodecExecutor` (the default), `StringToStringCodecExecutor` | string | `Cache<K, V>` | `scalar(spec, CoCache5Format.string())`: decodes the 5.x bytes with the 6.x codec. The bytes are the same for a `JacksonCodec` with the same `ObjectMapper` settings, and for `StringCodec`. Use `string { text -> … }` otherwise. |
| `SetToSetCodecExecutor` | set | `SetCache<K, E>` | `set(spec)`: members are decoded and encoded with the element codec |
| `SetToSetCodecExecutor` | set | `Cache<K, Set<String>>` | `scalar(spec, CoCache5Format.set { it })` |
| `MapToHashCodecExecutor`, `ObjectToHashCodecExecutor` | hash | `Cache<K, V>` | `scalar(spec, CoCache5Format.hash { it })`, or `hash(mapConverter::asValue)` |
| Negative entry (`_nil_`) | any of the above | 6.x negative entry | Automatic. Set `CoCache5MigrationOptions.missingGuardSentinel` if 5.x used another sentinel. |

The remaining TTL of every key is kept, and keys without a TTL stay without one.

```kotlin
// The same specs the application declares (or mirrors of its @CoCache interfaces)
val policySpec = cacheSpec<String, Policy>("PolicyCache") {
    codec = jacksonCodec<Policy>(objectMapper)
    policy { ttl = null }
}
val indexSpec = setCacheSpec<String, String>("GlobalIndexCache") { elementCodec = StringCodec }

val migrations = listOf(
    // 5.x keys "app:policy:{id}" → 6.x keys "cocache:PolicyCache:{id}"
    CoCache5Migration.scalar(policySpec, CoCache5Format.string()).sourcePrefix("app:policy:").build(),
    // One fixed 5.x key → the 6.x SetCache key ""
    CoCache5Migration.set(indexSpec).sourceKey("app:global-index", "").build(),
)

CoCache5Migrator.standalone(RedisURI.create("redis://localhost:6379")).use { migrator ->
    println(migrator.dryRun(migrations))   // read and decode only
    println(migrator.migrate(migrations))  // every writer stopped
}
```

- **Target.** `scalar(spec, format)` and `set(spec)` take the key prefix, namespace and codec from the 6.x spec, so the result is exactly what the application reads. Pass the spec the application uses, with any `cocache.caches.[name].key-prefix` applied. Without a spec, use `scalar(name, codec, format)` or `set(name, elementCodec)` with `keyPrefix(…)` and `namespace(…)`.
- **Source.** By default the migrator scans the 5.x default prefix `cocache:{name}:`. Change it with `sourcePrefix(…)`, or list keys with `sourceKey(key, encodedKey)`, for example when a 5.x `KeyConverter` mapped the cache to fixed keys. The 6.x key is `{keyPrefix}{namespace}:` plus the 5.x key without its prefix. Use `keyMapper(…)` when 5.x and 6.x encode keys differently.
- **Clients.** `CoCache5Migrator.standalone(uri)`, `cluster(uris)` (scans every primary), or `of(client)` with your own `RedisClient` / `RedisClusterClient`.

Steps:

1. **Check.** Run `dryRun(migrations)` against the production Redis. It only reads, and it reports what `migrate` would do or fails at the first key it can't decode.
2. **Stop every writer**: all 5.x and 6.x instances that write these keys. Writes must stay stopped until step 4. An in-place write compares the key's `DUMP` first and aborts if the key changed, but it can't catch every concurrent write.
3. **Migrate.** `migrate(migrations)` returns a report per cache: `read`, `written` (`negatives` included), `skipped` (already in the 6.x format, or deleted meanwhile), `failed`, and `backedUp`. The first key it can't decode, or whose Redis type doesn't match the configured format, aborts with `CoCache5MigrationException`, which carries the key and the report so far. That key is left as it was. Fix the cause and run again: keys already migrated are skipped.
4. **Start 6.x** and verify.
5. **Drop the backups** with `dropBackups(migrations)`. After this you can't roll back.

### Rollback {#migration-rollback}

What `migrate` keeps depends on where each key goes:

- **In place**: the 6.x key equals the 5.x key, as with the default layout. Before overwriting, the migrator copies the 5.x value with `RESTORE` to `cocache5-backup:{5.x key}` (`CoCache5MigrationOptions.backupPrefix`), with the same type, content and remaining TTL.
- **To a new key**: for example a custom 5.x prefix, or a new `keyPrefix` on the 6.x spec. The 5.x key isn't touched, so it is its own backup. The 6.x key must not already exist.

To roll back, stop every writer again, call `rollback(migrations)`, then redeploy 5.x. It restores each backup (then deletes it) and deletes the 6.x keys written to new keys. Data written by 6.x after the migration is lost. `rollback` can be run again.

The default is in place with a backup, because it keeps the key layout you already run and needs no configuration change. The backups cost one extra copy of the data until `dropBackups`. To leave the 5.x data completely untouched, give the 6.x specs a new `keyPrefix`: rollback then only deletes the new keys.

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
| `me.ahoo.cache.api.join.JoinCache` / `JoinValue` / `JoinKeyExtractor` | `me.ahoo.cocache.api.join.JoinCache` / `JoinValue` / `JoinKeyExtractor` |
| `me.ahoo.cache.api.annotation.JoinCacheable` | `me.ahoo.cocache.api.annotation.JoinCacheable` |

## Annotations {#annotations}

Durations are still seconds.

| 5.x | 6.0 |
|-----|-----|
| `@CoCache(ttl)` | `ttl`. `TtlAt.FOREVER` becomes `CoCache.FOREVER`. |
| `@CoCache(ttlAmplitude)` | `ttlJitter` |
| `@CoCache(missingTtl)` | `absentTtl` |
| `@CoCache(clientSideTtl)` | `localMaxAge`. **`0` (unlimited) has no equivalent**, see [below](#semantics). |
| `@CoCache(refreshAhead)` | `refreshWindow`. `0` still means off. |
| `@CoCache(keyPrefix)` default `cocache:{name}:` | `keyPrefix` default `cocache:`, followed by the namespace. The annotation no longer resolves Spring `${…}` placeholders. The `key-prefix` property does, see [Properties](#properties). |
| `@CoCache(keyExpression)` | `keyExpression` (same SpEL template, key as root object) |
| — | new: `namespace`, `loader`, `codec` |
| `@CaffeineCache(maximumSize, initialCapacity, expireAfterAccess)` | same names |
| `@CaffeineCache(expireUnit)` | removed: `expireAfterAccess` is always seconds |
| Meta-annotations and `@Inherited` | removed: annotations must be placed directly on the cache interface |
| `@JoinCacheable` | `me.ahoo.cocache.api.annotation.JoinCacheable`, plus a new `joinKeyExtractor` attribute, see [JoinCache](#joincache) |

In 6.0, `FOREVER` is accepted only by `ttl` and `expireAfterAccess`, and `0` only by `ttlJitter` and `refreshWindow`. Anything else fails startup.

## Data source {#data-source}

| 5.x | 6.0 |
|-----|-----|
| `CacheSource<K, V>.loadCacheValue(key): CacheValue<V>?` | `CacheLoader<K, V>.load(key): V?` |
| `loadCacheValues(keys): Map<K, CacheValue<V>>` | `loadAll(keys): Map<K, V>` |
| The source chose each value's `ttlAt` | The policy decides (`ttl` with jitter). Per entry: `CacheSpec.ttlOf(key, value)`, see [Per-entry TTL](./caches.md#ttl-of). |
| Bean named `{cacheName}.CacheSource`, else the unique `CacheSource<K, V>` | `@CoCache(loader = "beanName")`, else the unique `CacheLoader<K, V>`. Several candidates fail startup. |
| `CacheSource.noOp()`: every miss stored a negative entry in Redis | No loader: a miss returns `null` and writes nothing to Redis |

## API {#api}

| 5.x | 6.0 |
|-----|-----|
| `cache[key]` / `get(key)` | `cache[key]` / `get(key)` |
| `getCache(key): CacheValue<V>?` | `lookup(key): Lookup<V>` (`Present(value, expiresAt)` / `Absent(expiresAt)`) |
| `getTtlAt(key)` | `lookup(key).expiresAt`: the expiry of the returned copy (on an L2 hit, the L2 copy, capped at `localMaxAge` / `degradedLocalMaxAge`), with the cache clock's tick precision. For the Redis TTL, use the [actuator inspect](../operations/observability.md#inspect-key) or `CacheAdmin.inspect`. |
| `getCaches(keys)` / `getAll(keys)` | `getAll(keys)`, now coalesced with concurrent single-key loads |
| `cache[key] = value` / `set(key, value)` | `put(key, value)` |
| `set(key, ttlAt, value)` | `put(key, value, ttl: Duration)` |
| `set(key, null)` / `setCache(key, MissingValue)` | removed. Use `invalidate(key)`, or let the loader return `null`. |
| `evict(key)` | `invalidate(key)`, `invalidateAll(keys)` |
| — | `get(key, loader)`: protected load with a caller-supplied loader |
| `SetElementsCache.addElements` / `removeElements` | `SetCache.add` / `remove` / `clear` |
| `CacheValue`, `PresentValue`, `MissingValue`, `TtlAt`, `CacheClock` object | internal, or replaced by `java.time` types and an injectable `CacheClock` |

`SetCache` is only for sets whose only copy is Redis: it has no loader, and `invalidate` throws. If the set's data lives in your database, declare `Cache<K, Set<E>>` and invalidate. See [Set caches](./caches.md#set-cache).

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

JoinCache is still there, under `me.ahoo.cocache.api.join`, but it is now a **read-only view** instead of a `Cache` subtype. See [JoinCache](./join-cache.md).

| 5.x | 6.0 |
|-----|-----|
| `JoinCache<K1, V1, K2, V2> : Cache<K1, JoinValue<…>>` | `JoinCache<K1, V1, K2, V2>`, not a `Cache`: `name`, `get`, `getAll`, `invalidate(key)`, `invalidate(firstKey, joinKey)` |
| `set(key, joinValue)`, `getCache`, `getTtlAt` | removed: write and inspect each component cache directly |
| `evict(key)` / `evict(firstKey, joinKey)` | `invalidate(key)` (first cache only) / `invalidate(firstKey, joinKey)` (both) |
| `JoinValue.joinKey: K2` | `joinKey: K2?`: a `JoinKeyExtractor` may return `null` for "no join" |
| Listed in `@EnableCoCache(caches = …)` | listed in `@EnableCoCache(joinCaches = …)` |
| `firstCacheName` / `joinCacheName` optional (fell back to the unique cache of the matching type) | required: caches are resolved by name only |
| Extractor bean named `{name}.JoinKeyExtractor` | `@JoinCacheable(joinKeyExtractor = "beanName")`, else the unique `JoinKeyExtractor<V1, K2>` |
| `joinKeyExpression` | unchanged: SpEL template with the first value as root, `String` join keys only |
| Meta-annotation use of `@JoinCacheable` | removed: place it directly on the interface |

## Spring Cache {#spring-cache}

- `CoCacheManager` is now `CoCacheSpringCacheManager`, built from a `CacheRuntime`.
- `@Cacheable(sync = true)` now loads through a lease- and stamp-protected path. Use it wherever you need CoCache's guarantees: without `sync`, Spring's unconditional `put` can overwrite an invalidation. At startup, the starter logs a `WARN` for each `@Cacheable` on a CoCache cache without `sync = true`. `cocache.spring-cache.require-sync: true` fails startup instead.
- `put(key, null)` now invalidates instead of storing a negative entry.
- With `spring-tx`, the manager is transaction-aware: `@CacheEvict` and `@CachePut` inside a transaction run after commit. For direct calls, use `TransactionalInvalidator`.

See [Spring Cache](./spring-cache.md).

## Properties {#properties}

Removed 5.x keys fail startup with a message that names the replacement, if there is one.

| 5.x | 6.0 |
|-----|-----|
| `cocache.enabled` | `cocache.enabled` |
| `cocache.redis.strict-failure: true` | `cocache.defaults.read-failure: throw` (or per cache under `cocache.caches.[name]`). Explicit writes always throw in 6.0. |
| `cocache.redis.eviction-channel: sharded` | `cocache.redis.channel-mode: sharded` |
| `cocache.redis.missing-guard-sentinel` | removed: negative entries are a state byte in the value header, so there is no sentinel |
| `cocache.load.max-concurrent` | `cocache.defaults.load-max-concurrent` |
| `cocache.load.acquire-timeout` | `cocache.defaults.load-acquire-timeout` |
| `cocache.metrics.client-side-hits` | `cocache.metrics.local-hits` (6.3; absent in 6.0 and 6.1) |
| `cocache.next.*` (6.0 previews) | `cocache.*` |

New: every policy field under `cocache.defaults.*` and `cocache.caches.[name].*`, including `key-prefix` (placeholders allowed, for example `${spring.application.name}:`); `cocache.redis.command-timeout`, `heartbeat`, `invalidate-batch-size`, `client-name`, `channel-mode`; `cocache.backend` (`redis` or `memory`); and `cocache.spring-cache.require-sync`. See [Configuration](./configuration.md#properties).

## Redis {#redis}

| | 5.x | 6.0 |
|-|-----|-----|
| Client | Spring Data Redis, Lettuce or Jedis | Native Lettuce. CoCache builds its own client from `spring.data.redis.*`. **Jedis is not supported**: add Lettuce (it doesn't affect your own Redis client). |
| Topologies | standalone, Sentinel, Cluster | standalone, Sentinel, Cluster. Static master-replica fails startup. |
| Minimum version | — | Redis 7.0+ or Valkey 7.2+. CI tests standalone Valkey 8. |
| Key | `keyPrefix` + key, default prefix `cocache:{name}:` | `{keyPrefix}{namespace}:{key}`, default `cocache:{name}:{key}`. A custom `keyPrefix: "user:"` gave `user:42` and now gives `user:UserCache:42`. |
| Value | JSON string, hash, or set, with an in-band `_nil_` sentinel | 36-byte binary header + codec bytes. Sets are hashes with `!f` and `+{member}` fields. |
| TTL precision | seconds | milliseconds |
| Channel | the cache name, for example `UserCache` | `cocache:inv:{namespace}`, for example `cocache:inv:UserCache` |
| Message | `{key}@@{publisherId}` text | binary frame (`InvalidationFrame`), see [Operations](../operations/troubleshooting.md#external-invalidation) |
| Invalidation | `DEL`, then `PUBLISH` (2 round trips) | one atomic script (`pubsub` mode) |

External programs that evicted with `DEL` + `PUBLISH {cacheName} key@@id` must switch to the [6.0 protocol](../operations/troubleshooting.md#external-invalidation).

## Actuator {#actuator}

| 5.x | 6.0 |
|-----|-----|
| `GET /actuator/cocache` | `GET /actuator/cocache`: name, L2 size, load counts, and every metric counter |
| `GET /actuator/cocache/{name}` | `GET /actuator/cocache/{name}` |
| `GET /actuator/cocache/{name}/{key}` (loaded the key on a miss) | `GET /actuator/cocache/{name}/{key}`: inspects the key's L2 copy and L1 state **without loading**. `{key}` is the encoded key. |
| `DELETE /actuator/cocache/{name}/{key}` | `DELETE /actuator/cocache/{name}/{key}`: invalidates the key on every instance, as `invalidate(key)` does |
| `cocacheClient` endpoint (`GET /{name}`, `GET /{name}/{key}`, `DELETE /{name}`) | removed. `DELETE /actuator/cocache/{name}` clears this instance's L2. |
| — | new health indicator `cocache`: `DOWN` when the Redis subscription heartbeat fails |

Remove `cocacheClient` from `management.endpoints.web.exposure.include`. See [Observability](../operations/observability.md#actuator-endpoint).

## Metrics {#metrics}

All meters keep the `cache` tag.

| 5.x | 6.0 |
|-----|-----|
| `cocache.hits{level=l2}` | `cocache.hits{level=l2}` with `cocache.metrics.local-hits=true` (6.3; absent in 6.0 and 6.1) |
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

New: `cocache.lease.waits`, `cocache.lease.wait.timeouts`, `cocache.fill.rejections`, `cocache.degraded.fills`, `cocache.reclaims`, `cocache.unknown.formats`, `cocache.refresh.rejections`, `cocache.remote.failures`, `cocache.write.failures`, `cocache.invalidation.retries`, `cocache.invalidations.dropped`. See [Operations](../operations/observability.md#metrics).

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
