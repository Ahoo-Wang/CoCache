# Configuration and Wiring

Use this reference for dependencies, `cocache.*` properties, backend selection, Redis connection and channel mode, actuator and health, and plain `CacheRuntime` use without Spring Boot. Property keys and defaults drift between versions: confirm them in `CoCacheProperties` and the auto-configuration of the resolved version.

## Contents

- [Dependencies](#dependencies)
- [Precedence](#precedence)
- [Top-level properties](#top-level-properties)
- [Policy properties](#policy-properties)
- [Redis](#redis)
- [Actuator and health](#actuator-and-health)
- [Beyond properties](#beyond-properties)
- [Without Spring Boot](#without-spring-boot)

## Dependencies

Spring Boot 4 on JDK 21+: `me.ahoo.cocache:cocache-spring-boot-starter` plus `org.springframework.boot:spring-boot-starter-data-redis` (connection details), optionally the actuator. `me.ahoo.cocache:cocache-bom` manages module versions. Without Spring: `cocache-core`, `cocache-redis`, `cocache-codec-jackson`. `cocache-test` is a test dependency only when using `HookedBackend` or the TCK. Coroutines need no extra module: `cocache-core` exposes `kotlinx-coroutines-core`. CoCache builds its own Lettuce client from `spring.data.redis.*` and never touches the application's connection factory; Jedis is not used by CoCache.

## Precedence

Lowest to highest, field by field: built-in defaults (`CachePolicy.DEFAULT` for `Cache` / `VersionedCache`; `StoreSpec.DEFAULT_POLICY`, `VersionedStoreSpec.DEFAULT_POLICY`, `SetStoreSpec.DEFAULT_POLICY` for the authoritative types), the declaration (annotation or spec), `CacheSpecCustomizer` beans, `cocache.defaults.*`, `cocache.caches.[name].*`. Properties come last so operators can retune without a rebuild, so `cocache.defaults.*` overrides annotations too. `cocache.defaults.ttl`, `ttl-forever` and `refresh-window` do not apply to `Store`, `VersionedStore` or `SetStore`; every other default does, including `absent-ttl` and `local-max-age`, which then replace those types' shorter built-in values.

## Top-level properties

| Property | Default | Meaning |
|---|---|---|
| `cocache.enabled` | `true` | `false` turns off everything; injection points of CoCache caches then fail |
| `cocache.backend` | `redis` | `memory`: in-process `InMemoryBackend`, single instance, for tests and local runs. A `CoherenceBackend` bean replaces either. |
| `cocache.defaults.*` / `cocache.caches.[name].*` | | Policy properties |
| `cocache.redis.*` | | Redis backend settings |
| `cocache.spring-cache.require-sync` | `false` | `true` fails startup on a non-sync `@Cacheable` on a CoCache cache |
| `cocache.spring-cache.degrade-on-error` | `false` | `true`: `DegradingCacheErrorHandler` logs read and write failures on CoCache caches instead of rethrowing them; invalidation failures and other caches go to the previous handler |
| `cocache.invalidate-fills-on-rollback` | `true` | Needs `spring-tx`: keys a synchronous read loaded inside a read-write transaction are invalidated on rollback, unknown commit outcome and rollback to a savepoint |
| `cocache.fill-in-transaction` | `registered-keys` | Needs `spring-tx`: which loads in a read-write transaction are private (no lease, no Redis or L2 write, not shared): `registered-keys` (keys registered for invalidation in it), `private` (all), `allow` (none); every cache |
| `cocache.refresh-executor` | built-in | Bean name of the `Executor` for refresh-ahead loads; CoCache never shuts it down |
| `cocache.async-executor` | runtime-owned `ForkJoinPool` (`cocache-async-*`) | Bean name of the `Executor` for async-view continuations; short non-blocking work only, never block on it |
| `cocache.blocking-executor` | one virtual thread per task | Bean name of the `Executor` for synchronous loaders called through the async view; must not queue |
| `management.health.cocache.enabled` | `true` | Registers the `cocache` health indicator |

## Policy properties

Valid under `cocache.defaults.` and `cocache.caches.[name].`. Bare numbers are seconds; Spring duration strings (`500ms`, `10m`, `PT10M`) also work.

| Key | Default | Meaning |
|---|---|---|
| `ttl` / `ttl-forever` | `1h` (authoritative types: forever) / `false` | Value lifetime in Redis |
| `ttl-jitter` | `1m` | Random spread, capped at `ttl / 2`; `0` off |
| `absent-ttl` | `1m` (authoritative types: `10s`) | Negative-entry lifetime |
| `local-max-age` | `5m` (authoritative types: `30s`) | Maximum age of an L2 copy |
| `degraded-local-max-age` | `10s` | L2 age of a copy not written to Redis |
| `refresh-window` | off | Refresh-ahead; `0` removes a declared window |
| `lease-ttl` / `lease-wait` | `10s` / `200ms` | Lease lifetime; wait for another instance's load |
| `load-max-concurrent` / `load-acquire-timeout` | `0` (unlimited) / `1s` | Per-cache, per-instance loader bulkhead; rejection throws `SourceLoadRejectedException` |
| `load-timeout` | `30s` | Upper bound for a load: an `AsyncCacheLoader` load past it fails with `SourceLoadTimeoutException` (lease released); readers waiting for another caller's load fail the same way at that deadline, and later readers start a new load. Give synchronous loaders their own timeout |
| `local-maximum-size` | `10000` | L2 entries per instance |
| `invalidate-failure` | `retry` | `Cache.invalidate` only (externally sourced included). `throw`: surface `CacheWriteException`. Authoritative deletes always throw and never retry |
| `read-failure` | `degrade` | Reads with a source only (declared or per-call loader). `throw`: answer `Lookup.Unknown`, so `get` throws `CacheReadException`. Reads without a source are always `Unknown`; the spec's `unknownRead` (no property) decides whether they throw or return `null` |
| `key-prefix` | `cocache:` | First part of every storage key; placeholders allowed |

- Quote and bracket cache names in YAML (`"[UserCache]"`). Names match case-insensitively, so `COCACHE_CACHES_USERCACHE_TTL=10m` works. An undeclared name fails startup.
- Every duration must be positive and at most 100 years; only `ttl-jitter`, `lease-wait`, `load-acquire-timeout` and `refresh-window` may be zero.

## Redis

The topology comes from `spring.data.redis.*`: `cluster.nodes` (Cluster), `sentinel.*` (Sentinel), `host`/`port`/`url` (standalone). Static master-replica fails startup. TLS follows `spring.data.redis.ssl.*` or a `rediss://` URL. CoCache always uses masters.

| Property | Default | Meaning |
|---|---|---|
| `cocache.redis.channel-mode` | `pubsub` | `pubsub` broadcasts atomically inside the write script; `sharded` uses `SPUBLISH`/`SSUBSCRIBE` right after the write. Every instance sharing a cache must use the same mode. |
| `cocache.redis.command-timeout` | `2s` | Per command or script |
| `cocache.redis.heartbeat` | `5s` | Subscription `PING`; a failure clears L2 and reconnects |
| `cocache.redis.invalidate-batch-size` | `512` | Keys per invalidation script |
| `cocache.redis.client-name` | unset | `CLIENT SETNAME` for CoCache connections |
| `cocache.redis.store-eviction-check` | `warn` | With any `Store`, `VersionedStore` or `SetStore` (lazy ones included), read `maxmemory-policy` from `INFO memory` at startup: `allkeys-*` warns, or fails startup with `fail`; `off` skips it |
| `cocache.redis.assume-maxmemory-policy` | unset | Policy the check assumes when `INFO` is not allowed |

Bare numbers under `cocache.redis` are milliseconds; write a unit. In `pubsub` mode the backend probes at startup and falls back to publish-after-write where atomic cross-slot publish is unsupported (health shows `atomicBroadcast: false`).

To share one Redis between applications, set `cocache.defaults.key-prefix: ${spring.application.name}:`. The invalidation channel `{keyPrefix}inv:{namespace}` carries the prefix, so applications with different prefixes never receive each other's invalidations.

## Actuator and health

Expose with `management.endpoints.web.exposure.include: health, cocache`; restrict deletes with `management.endpoint.cocache.access: read-only`.

| Request | Effect | Scope |
|---|---|---|
| `GET /actuator/cocache` and `GET /actuator/cocache/{name}` | Reports and counters | this instance |
| `GET /actuator/cocache/{name}/{key}` | Inspects L2 and L1 for one encoded key without loading | this instance plus one Redis read |
| `DELETE /actuator/cocache/{name}` | Clears this cache's L2 | this instance |
| `DELETE /actuator/cocache/{name}/{key}` | Per type: `Cache.invalidate`, `VersionedCache.evict`, `Store.delete`, `VersionedStore.purge`, `SetStore.clear`. Only the `Cache` case retries a Redis failure; the authoritative cases delete the only copy and return `403` unless `management.endpoint.cocache.allow-store-delete` (`Store`, `SetStore`) or `allow-purge` (`VersionedStore`, and `?purge=true` on a `VersionedCache`) is `true` | every instance |

The `cocache` health indicator is `DOWN` only while the subscription heartbeat fails; details show `backend`, `format`, `atomicBroadcast`, `channelMode`, `heartbeat` and `pendingInvalidations`. Keep it out of liveness. Micrometer meters are `cocache.*` tagged `cache`; the L2 hit counter `cocache.hits{level=l2}` is off by default (`cocache.metrics.local-hits=true` turns it on, at a measurable cost to L2 hit throughput).

## Beyond properties

| Setting | Where |
|---|---|
| `ttlOf`, `keyEncoder`, `keyFilter`, `codec`, `loader`, `unknownRead`, `tombstoneTtl` | Spec or customizer |
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
