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

Spring Boot 4 on JDK 17+: `me.ahoo.cocache:cocache-spring-boot-starter` plus `org.springframework.boot:spring-boot-starter-data-redis` (connection details), optionally the actuator. `me.ahoo.cocache:cocache-bom` manages module versions. Without Spring: `cocache-core`, `cocache-redis`, `cocache-codec-jackson`. `cocache-test` is a test dependency only when using `HookedBackend` or the TCK. CoCache builds its own Lettuce client from `spring.data.redis.*` and never touches the application's connection factory; Jedis is not used by CoCache.

## Precedence

Lowest to highest, field by field: built-in defaults, the declaration (annotation or spec), `CacheSpecCustomizer` beans, `cocache.defaults.*`, `cocache.caches.[name].*`. Properties come last so operators can retune without a rebuild, so `cocache.defaults.*` overrides annotations too. `cocache.defaults.ttl`, `ttl-forever`, `absent-ttl` and `refresh-window` do not apply to set caches.

## Top-level properties

| Property | Default | Meaning |
|---|---|---|
| `cocache.enabled` | `true` | `false` turns off everything; injection points of CoCache caches then fail |
| `cocache.backend` | `redis` | `memory`: in-process `InMemoryBackend`, single instance, for tests and local runs. A `CoherenceBackend` bean replaces either. |
| `cocache.defaults.*` / `cocache.caches.[name].*` | | Policy properties |
| `cocache.redis.*` | | Redis backend settings |
| `cocache.spring-cache.require-sync` | `false` | `true` fails startup on a non-sync `@Cacheable` on a CoCache cache |
| `cocache.spring-cache.retrieve-executor` | — | Deprecated in 6.3 and ignored with a startup warning: `retrieve` uses the async view and needs no executor. Remove it |
| `cocache.refresh-executor` | built-in | Bean name of the `Executor` for refresh-ahead loads; CoCache never shuts it down |
| `cocache.async-executor` (6.3+) | runtime-owned `ForkJoinPool` (`cocache-async-*`) | Bean name of the `Executor` for async-view continuations; short non-blocking work only, never block on it |
| `cocache.blocking-executor` (6.3+) | virtual threads on JDK 21+, else a non-queueing pool of up to 512 | Bean name of the `Executor` for synchronous loaders called through the async view; must not queue |
| `management.health.cocache.enabled` | `true` | Registers the `cocache` health indicator |

## Policy properties

Valid under `cocache.defaults.` and `cocache.caches.[name].`. Bare numbers are seconds; Spring duration strings (`500ms`, `10m`, `PT10M`) also work.

| Key | Default | Meaning |
|---|---|---|
| `ttl` / `ttl-forever` | `1h` / `false` | Value lifetime in Redis |
| `ttl-jitter` | `1m` | Random spread, capped at `ttl / 2`; `0` off |
| `absent-ttl` | `1m` | Negative-entry lifetime |
| `local-max-age` | `5m` | Maximum age of an L2 copy |
| `degraded-local-max-age` | `10s` | L2 age of a copy not written to Redis |
| `refresh-window` | off | Refresh-ahead; `0` removes a declared window |
| `lease-ttl` / `lease-wait` | `10s` / `200ms` | Lease lifetime; wait for another instance's load |
| `load-max-concurrent` / `load-acquire-timeout` | `0` (unlimited) / `1s` | Per-cache, per-instance loader bulkhead; rejection throws `SourceLoadRejectedException` |
| `load-async-timeout` (6.3+) | `30s` | Upper bound for one `AsyncCacheLoader` load; past it the call fails with `SourceLoadTimeoutException` and the lease is released. Not applied to synchronous loaders |
| `local-maximum-size` | `10000` | L2 entries per instance |
| `invalidate-failure` | `retry` with a loader, `throw` without one (6.9+; before: `retry`) | `throw`: surface `CacheWriteException`. Without a loader a delayed retry deletes later writes |
| `read-failure` | `degrade` | `throw`: surface `CacheReadException`. A read without a loader always throws (6.9+) |
| `key-prefix` | `cocache:` | First part of every storage key; placeholders allowed |

- Quote and bracket cache names in YAML (`"[UserCache]"`). Names match case-insensitively, so `COCACHE_CACHES_USERCACHE_TTL=10m` works. An undeclared name fails startup.
- Every duration must be positive and at most 100 years; only `ttl-jitter`, `lease-wait`, `load-acquire-timeout` and `refresh-window` may be zero.
- Removed properties of an earlier major version fail startup with a message naming the replacement; that is a `cocache-migrate` task.

## Redis

The topology comes from `spring.data.redis.*`: `cluster.nodes` (Cluster), `sentinel.*` (Sentinel), `host`/`port`/`url` (standalone). Static master-replica fails startup. TLS follows `spring.data.redis.ssl.*` or a `rediss://` URL. CoCache always uses masters.

| Property | Default | Meaning |
|---|---|---|
| `cocache.redis.channel-mode` | `pubsub` | `pubsub` broadcasts atomically inside the write script; `sharded` uses `SPUBLISH`/`SSUBSCRIBE` right after the write. Every instance sharing a cache must use the same mode. |
| `cocache.redis.command-timeout` | `2s` | Per command or script |
| `cocache.redis.heartbeat` | `5s` | Subscription `PING`; a failure clears L2 and reconnects |
| `cocache.redis.invalidate-batch-size` | `512` | Keys per invalidation script |
| `cocache.redis.client-name` | unset | `CLIENT SETNAME` for CoCache connections |

Bare numbers under `cocache.redis` are milliseconds; write a unit. In `pubsub` mode the backend probes at startup and falls back to publish-after-write where atomic cross-slot publish is unsupported (health shows `atomicBroadcast: false`).

To share one Redis between applications, set `cocache.defaults.key-prefix: ${spring.application.name}:`. The invalidation channel has no prefix, so coinciding namespaces cost extra work but never wrong data.

## Actuator and health

Expose with `management.endpoints.web.exposure.include: health, cocache`; restrict deletes with `management.endpoint.cocache.access: read-only`.

| Request | Effect | Scope |
|---|---|---|
| `GET /actuator/cocache` and `GET /actuator/cocache/{name}` | Reports and counters | this instance |
| `GET /actuator/cocache/{name}/{key}` | Inspects L2 and L1 for one encoded key without loading | this instance plus one Redis read |
| `DELETE /actuator/cocache/{name}` | Clears this cache's L2 | this instance |
| `DELETE /actuator/cocache/{name}/{key}` | Invalidates the key (`clear` on a set cache) | every instance |

The `cocache` health indicator is `DOWN` only while the subscription heartbeat fails; details show `backend`, `format`, `atomicBroadcast`, `channelMode`, `heartbeat` and `pendingInvalidations`. Keep it out of liveness. Micrometer meters are `cocache.*` tagged `cache`; the L2 hit counter `cocache.hits{level=l2}` is off by default (`cocache.metrics.local-hits=true` turns it on, at a measurable cost to L2 hit throughput).

## Beyond properties

| Setting | Where |
|---|---|
| `ttlOf`, `keyEncoder`, `keyFilter`, `codec`, `loader` | Spec or customizer |
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

For Spring without Boot, add `cocache-spring` and register `CoCacheSpringCacheManager(runtime)` and `TransactionalInvalidator()`.
