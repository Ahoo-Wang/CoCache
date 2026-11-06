# Configuration and Wiring

Use this reference for dependencies, `cocache.*` properties, backend selection, Redis connection and channel mode, actuator and health, and plain `CacheRuntime` use without Spring Boot. Property keys and defaults drift between versions: confirm them in `CoCacheProperties` and the auto-configuration of the resolved version.

## Contents

- [Dependencies](#dependencies)
- [Precedence](#precedence)
- [Top-level properties](#top-level-properties)
- [Policy properties](#policy-properties)
- [Locks (libraries)](#locks-libraries)
- [Redis](#redis)
- [Actuator and health](#actuator-and-health)
- [Beyond properties](#beyond-properties)
- [Without Spring Boot](#without-spring-boot)

## Dependencies

Spring Boot 4 on JDK 21+: `me.ahoo.cocache:cocache-spring-boot-starter` plus `org.springframework.boot:spring-boot-starter-data-redis` (connection details), optionally the actuator. `me.ahoo.cocache:cocache-bom` manages module versions. Without Spring: `cocache-core`, `cocache-redis`, `cocache-codec-jackson`. `cocache-test` is a test dependency only when using `HookedBackend` or the TCK. Coroutines need no extra module: `cocache-core` exposes `kotlinx-coroutines-core`. CoCache builds its own Lettuce client from `spring.data.redis.*` and never touches the application's connection factory; Jedis is not used by CoCache.

## Precedence

Field by field, lowest to highest (configuration reference: https://cocache.ahoo.me/guide/configuration#precedence):

1. Type default: `DefaultPolicies.CACHE`, `VERSIONED_CACHE`, `STORE`, `VERSIONED_STORE`, `SET_STORE`.
2. `cocache.defaults.cache.*` (`Cache`, `VersionedCache`) / `cocache.defaults.store.*` (`Store`, `VersionedStore`, `SetStore`): **below** the declaration; fills only fields the declaration did not write.
3. The declaration. Written fields: annotation attributes that are not `UNSET`; fields assigned in `policy { … }` / `updatePolicy` (`ttl = forever()` counts; a nested `lease` / `load` / `local` writes only the fields whose value it changes); an assigned `unknownRead`. Assigning a whole `policy = …` writes every field, so `cocache.defaults` no longer applies. `Spec.declaredFields` lists them.
4. `CacheSpecCustomizer` beans, in order. They may not change the data source or a locked field.
5. `cocache.overrides.cache.*` / `cocache.overrides.store.*`: **tighten only**. `local-max-age`, `degraded-local-max-age`, `absent-ttl` and a `Cache`'s `ttl` take the minimum; `read-failure`, `fill-in-transaction` and `invalidate-fills-on-rollback` take the stricter (`throw`; `private` > `registered-keys` > `allow`; `true`). `overrides.cache.ttl` also caps `ttlOf` and `put(k, v, ttl)` lifetimes (`cocache.ttl.clamped`); a longer `cocache.caches.[name].ttl` raises the cap for that cache. Every other key is skipped.
6. `cocache.caches.[name].*`: sets any field that applies, plus `unknown-read`, `key-prefix` and `unlock`.

Then library constraints, then the validation chain. Global layers never fail startup: a key that does not apply to the type, is not allowed in that layer (`defaults.store.ttl`, `unknown-read`, anything but staleness bounds, `read-failure`, `fill-in-transaction` and `invalidate-fills-on-rollback` in `overrides`), or is locked is skipped for that cache, logged as `WARN` and listed in the endpoint's `skippedConfiguration`. To force a value over every declaration, use `overrides` (if the field may tighten) or `caches.[name]`; `defaults` never overrides a declaration. Only caches created through `CoCacheFactory` are layered; `runtime.create` gives an unlayered cache that no layer reaches.

Applicability: lease, load, `degraded-local-max-age`, `invalidate-failure`, `read-failure`, `fill-in-transaction` and `invalidate-fills-on-rollback` apply only to `Cache` (loader or externally sourced) and `VersionedCache`; `refresh-window` only to a `Cache` with a loader and `VersionedCache`; `tombstone-ttl` only to the versioned types; `unknown-read` only to an externally sourced `Cache`, `Store` and `VersionedStore`. `ttl`, `ttl-jitter`, `absent-ttl`, `local-max-age`, `read-timeout` and `local-maximum-size` apply to every type.

Validation chain: when a global layer makes `refresh-window` not less than `local-max-age` or `ttl − ttl-jitter`, and `caches.[name]` set none of those fields, the window is turned off (`CLAMPED`, `WARN`). A conflict from the declaration alone, or one involving a `caches.[name]` field, fails startup.

## Top-level properties

| Property | Default | Meaning |
|---|---|---|
| `cocache.enabled` | `true` | `false` turns off everything; injection points of CoCache caches then fail |
| `cocache.backend` | `redis` | `memory`: in-process `InMemoryBackend`, single instance, for tests and local runs. A `CoherenceBackend` bean replaces either. |
| `cocache.key-prefix` | unset (declared prefix, `cocache:`) | Prefix for every cache, outside the layering; wins over the declaration unless a library locked `KEY_PREFIX`. `cocache.caches.[name].key-prefix` wins over it. Placeholders allowed. |
| `cocache.defaults.cache.*` / `cocache.defaults.store.*` | | Global defaults below the declaration |
| `cocache.overrides.cache.*` / `cocache.overrides.store.*` | | Global overrides that only tighten |
| `cocache.caches.[name].*` | | Per-cache policy, `unknown-read`, `key-prefix`, `unlock` |
| `cocache.caches-validation` | `fail` | `fail` or `warn` for a `cocache.caches` name that matches no cache, a per-cache key CoCache doesn't know (such as `ttl-forever`), or a per-cache key that does not apply. An entry naming an unlayered cache always only warns. |
| `cocache.redis.*` | | Redis backend settings |
| `cocache.spring-cache.require-sync` | `false` | `true` fails startup on a non-sync `@Cacheable` on a CoCache cache |
| `cocache.spring-cache.degrade-on-error` | `false` | `true`: `DegradingCacheErrorHandler` logs read and write failures on CoCache caches instead of rethrowing them; invalidation failures and other caches go to the previous handler |
| `cocache.refresh-executor` | built-in | Bean name of the `Executor` for refresh-ahead loads; CoCache never shuts it down |
| `cocache.executor` | one virtual thread per task (`cocache-worker-N`) | Bean name of the one `Executor` of the async and suspending views: every continuation, shared loads, synchronous loaders called through those views, and Spring `retrieve(key, loader)` method bodies. It must tolerate blocking tasks: no queue, not just a few threads. On JDK 21–23 a loader blocking inside `synchronized` pins a virtual thread's carrier (fixed in JDK 24), so prefer JDK 24+ or a platform pool. Resolved by name only; CoCache never shuts it down. The blocking API never uses it |
| `management.health.cocache.enabled` | `true` | Registers the `cocache` health indicator |
| `cocache.observation.enabled` | `true` | Wraps source loads in a Micrometer `Observation` (`cocache.load`) when an `ObservationRegistry` exists |
| `cocache.metrics.local-hits` | `false` | Counts L2 hits (`cocache.hits{level=l2}`), at a measurable cost to L2 hit throughput |

## Policy properties

Valid under `cocache.defaults.cache.`, `.defaults.store.`, `.overrides.cache.`, `.overrides.store.` and `cocache.caches.[name].`, as each layer allows. **Every duration needs a unit** (`500ms`, `5s`, `2m`, `PT2M`), `cocache.redis.*` included; a bare number fails startup (`<key>: "60" needs a unit, e.g. 60s, 5m, PT2M.`). Sentinels: `forever` (`ttl`, `tombstone-ttl`), `off` or `0s` (`refresh-window`), `unlimited` (`load-max-concurrent`; `0` fails). Annotations stay in seconds.

| Key | Default | Meaning |
|---|---|---|
| `ttl` | `1h` (authoritative types: `forever`) | Value lifetime in Redis; on authoritative types only the declaration and `caches.[name]` set it |
| `ttl-jitter` | `1m` | Random spread, capped at `ttl / 2`; `0s` off |
| `absent-ttl` | `1m` (authoritative types: `10s`) | Negative-entry lifetime |
| `local-max-age` | `5m` (authoritative types: `30s`) | Maximum age of an L2 copy |
| `degraded-local-max-age` | `10s` | L2 age of a copy not written to Redis |
| `tombstone-ttl` | value lifetime (`VersionedStore`: `1d`) | Versioned tombstones and eviction markers; must cover the redelivery window; finite on a `VersionedStore` |
| `refresh-window` | `off` | Refresh-ahead; `off` removes a declared window |
| `lease-ttl` / `lease-wait` | `10s` / `200ms` | Lease lifetime; wait for another instance's load |
| `load-max-concurrent` / `load-acquire-timeout` | `unlimited` / `1s` | Per-cache, per-instance loader bulkhead; rejection throws `SourceLoadRejectedException` |
| `load-timeout` | `30s` | Upper bound for a load: an `AsyncCacheLoader` load past it fails with `SourceLoadTimeoutException` (lease released); readers waiting for another caller's load fail the same way at that deadline, and later readers start a new load. Give synchronous loaders their own timeout |
| `read-timeout` | `cocache.redis.command-timeout` | Per-read Redis limit, capped at `command-timeout`; a timeout is a failed read (`read-failure`, or `Unknown` without a source) |
| `local-maximum-size` | `10000` | L2 entries per instance |
| `invalidate-failure` | `retry` | Derived types only. A failed `Cache.invalidate` (externally sourced included), and a `Cache.put` / `putAll` whose outcome is unknown: `retry` queues the invalidation (for the put, a compensating one) and returns; `throw` surfaces `CacheWriteException`. A put that definitely did not run throws either way. Authoritative writes and deletes always throw and never retry |
| `read-failure` | `degrade` | Reads with a source only (declared or per-call loader). `throw`: answer `Lookup.Unknown`, so `get` throws `CacheReadException` |
| `fill-in-transaction` | `registered-keys` | Needs `spring-tx`: which loads in a read-write transaction are private (no lease, no Redis or L2 write, not shared): `registered-keys` (keys registered for invalidation in it), `private` (all), `allow` (none) |
| `invalidate-fills-on-rollback` | `true` | Needs `spring-tx`: keys a synchronous read loaded inside a read-write transaction are invalidated on rollback, unknown commit outcome and rollback to a savepoint. With `false` and `fill-in-transaction: allow`, the cache's loads ignore transactions |
| `unknown-read` | `throw` | `caches.[name]` only. Reads without a source on Redis failure: `throw` (`CacheReadException`) or `absent` (read as not found, cache nothing) |
| `key-prefix` | `cocache.key-prefix` | `caches.[name]` only. This cache's storage-key prefix |
| `unlock` | | `caches.[name]` only. Locked fields this entry overrides on purpose (logged, reported `UNLOCKED`) |

- Quote and bracket cache names in YAML (`"[UserCache]"`). Names match case-insensitively, so `COCACHE_CACHES_USERCACHE_TTL=10m` works.
- Only `ttl-jitter`, `lease-wait` and `load-acquire-timeout` may be `0s` (besides `refresh-window: 0s`); none may be negative or above 100 years.
- Spring Boot silently ignores keys CoCache does not define in the global layers, such as `cocache.defaults.ttl` (missing `.cache` / `.store`) or `cocache.defaults.key-prefix`. Under `cocache.caches.[name]` an unknown key fails startup (`caches-validation: warn` only warns). When a cache's policy looks unchanged, check the endpoint's `policy` (source of each field) and `skippedConfiguration`.

## Locks (libraries)

A library that depends on a field creates its caches through `CoCacheFactory` and locks it: `factory.create(spec) { lock(PolicyField.UNKNOWN_READ); lockLayout(LayoutField.KEY_PREFIX); constrain(PolicyField.TTL) { it.finiteOr(Duration.ofHours(1)) }; verify { … } }` (Java: `create(spec, Locks.builder("my-lib").lock(PolicyField.UNKNOWN_READ.INSTANCE).build())`). Locked fields report `LOCKED`; global layers skip them; `cocache.caches.[name]` fails on them unless its `unlock` lists them; a customizer that changes one throws. A locked `KEY_PREFIX` ignores `cocache.key-prefix`. Write constraints against the sentinel types (`it.finiteOr(…)`, not a null check), because `forever` is a value.

## Redis

The topology comes from `spring.data.redis.*`: `cluster.nodes` (Cluster), `sentinel.*` (Sentinel), `host`/`port`/`url` (standalone). Static master-replica fails startup. TLS follows `spring.data.redis.ssl.*` or a `rediss://` URL. CoCache always uses masters.

| Property | Default | Meaning |
|---|---|---|
| `cocache.redis.channel-mode` | `pubsub` | `pubsub` broadcasts atomically inside the write script; `sharded` uses `SPUBLISH`/`SSUBSCRIBE` right after the write. Every instance sharing a cache must use the same mode. |
| `cocache.redis.command-timeout` | `2s` | Per command or script (unit required) |
| `cocache.redis.heartbeat` | `5s` | Subscription `PING`; a failure clears L2 and reconnects (unit required) |
| `cocache.redis.invalidate-batch-size` | `512` | Keys per invalidation script |
| `cocache.redis.client-name` | unset | `CLIENT SETNAME` for CoCache connections |
| `cocache.redis.store-eviction-check` | `warn` | Only for `volatile-*` with an authoritative cache whose `ttl` is finite: `warn`, `fail` or `off` |
| `cocache.redis.assume-maxmemory-policy` | unset | Policy the check assumes when `INFO` is not allowed |

With any `Store`, `VersionedStore` or `SetStore` (lazy ones included), the starter reads `maxmemory-policy` from `INFO memory` at startup. `allkeys-*` always fails startup, and so does an unreadable policy without `assume-maxmemory-policy`, whatever `store-eviction-check` says. In `pubsub` mode the backend probes at startup and falls back to publish-after-write where atomic cross-slot publish is unsupported (health shows `atomicBroadcast: false`).

To share one Redis between applications, set `cocache.key-prefix: "${spring.application.name}:"` (quoted: an unquoted YAML value can't end with `:`). The invalidation channel `{keyPrefix}inv:{namespace}` carries the prefix, so applications with different prefixes never receive each other's invalidations.

## Actuator and health

Expose with `management.endpoints.web.exposure.include: health, cocache`; restrict the writes (`DELETE`, `POST`) with `management.endpoint.cocache.access: read-only`.

| Request | Effect | Scope |
|---|---|---|
| `GET /actuator/cocache` and `GET /actuator/cocache/{name}` | Reports and counters; `policy` gives each field's effective value and source (`TYPE_DEFAULT`, `DEFAULTS`, `DECLARATION`, `CUSTOMIZER`, `OVERRIDES`, `CACHES`, `CLAMPED`, `LOCKED`, `UNLOCKED`), `skippedConfiguration` the skipped global entries | this instance |
| `GET /actuator/cocache/{name}/{key}` | Inspects L2 and L1 for one encoded key without loading | this instance plus one Redis read |
| `DELETE /actuator/cocache/{name}` | Clears this cache's L2 | this instance |
| `DELETE /actuator/cocache/{name}/{key}` | Per type: `Cache.invalidate`, `VersionedCache.evict`, `Store.delete`, `VersionedStore.purge`, `SetStore.clear`. Only the `Cache` case retries a Redis failure; the authoritative cases delete the only copy and return `403` unless `management.endpoint.cocache.allow-store-delete` (`Store`, `SetStore`) or `allow-purge` (`VersionedStore`, and `?purge=true` on a `VersionedCache`) is `true` | every instance |
| `POST /actuator/cocache/{name}/{key}` | Broadcasts the invalidation for one key without touching Redis: every instance drops its L2 copy. Safe on every type, no `allow-*` switch | every instance |

The `cocache` health indicator is `DOWN` only while the subscription heartbeat fails; details show `backend`, `format`, `atomicBroadcast`, `channelMode`, `heartbeat` and `pendingInvalidations`. Keep it out of liveness. Micrometer meters are `cocache.*` tagged `cache`; the L2 hit counter `cocache.hits{level=l2}` is off by default (`cocache.metrics.local-hits`).

## Beyond properties

| Setting | Where |
|---|---|
| `ttlOf`, `keyEncoder`, `keyFilter`, `codec`, `loader` | Spec or customizer (a customizer cannot change the source) |
| Locks and constraints | `CoCacheFactory.create(spec) { … }` |
| `initialCapacity`, `expireAfterAccess` | `@CaffeineCache`, spec, or customizer |
| Clock | A `CacheClock` bean (for example `ManualClock` in tests) |
| Retry policy, node id | Your own `CacheRuntime` bean (the starter's backs off) |
| Redis client | Your own `CoherenceBackend` bean, for example `RedisBackend.of(client, options)`; set the client's `disconnectedBehavior` to `REJECT_COMMANDS` |

## Without Spring Boot

```kotlin
val backend = RedisBackend.standalone(RedisURI.create("redis://localhost:6379"))
val runtime = CacheRuntime.builder().backend(backend).build()        // one per process
val users: Cache<String, User> = runtime.create(cacheSpec<String, User>("UserCache") {
    codec = jacksonCodec<User>(mapper)
    loader = CacheLoader { id -> repo.findById(id) }
})
// shutdown: runtime first, then the backend
runtime.close()
backend.close()
```

`runtime.create(spec)` returns the type matching the spec (`storeSpec` gives a `Store`, and so on); the start log names it (`Cache[name] - created as Store on namespace[...]`). For Spring without Boot, add `cocache-spring` and register `CoCacheSpringCacheManager(runtime)` and `TransactionalInvalidator()`.
