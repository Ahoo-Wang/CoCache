---
title: Configuration Reference
description: Every cocache.* property with its type, default and unit; how global defaults, the declaration, customizers, global overrides and per-cache settings merge field by field; which keys apply to which cache type; locks for library authors; Redis settings and the eviction check; the effective-policy report; startup validation; and what only code can configure.
---

# Configuration Reference

## How settings combine {#precedence}

Each cache's policy is merged field by field from six layers. A higher layer wins over a lower one; a field a layer doesn't set passes through:

| Layer (low to high) | Keys | What it does |
|---------------------|------|--------------|
| 1. Type default | `DefaultPolicies.CACHE`, `VERSIONED_CACHE`, `STORE`, `VERSIONED_STORE`, `SET_STORE` | Each type has its own: a `Store` never inherits a derived cache's 1 h `ttl` ([type defaults](./caches.md#store-defaults)) |
| 2. Global defaults | `cocache.defaults.cache.*`, `cocache.defaults.store.*` | **Below the declaration**: fills only the fields the declaration [didn't write](#declared-fields) |
| 3. Declaration | `@CoCache` / `@CaffeineCache`, or the spec you build | The library's or application's intent |
| 4. Customizers | `CacheSpecCustomizer` beans, in Spring order | May change policy fields and fields that don't decide the type, never the data source ([customizers](./caches.md#customizer)) |
| 5. Global overrides | `cocache.overrides.cache.*`, `cocache.overrides.store.*` | **Above the declaration, tighten only**: staleness bounds take the minimum, the ordered modes (`read-failure`, `fill-in-transaction`, `invalidate-fills-on-rollback`) take the stricter value; nothing else is allowed ([what each layer may set](#field-layers)) |
| 6. One cache | `cocache.caches.[name].*` | Sets any field that [applies](#applicability) to the cache, plus `unknown-read`, `key-prefix` and `unlock` |

After the merge, a library's [constraints](#locks) run, then the [validation chain](#validation). The `cache` sections apply to the derived types (`Cache`, `VersionedCache`), the `store` sections to the authoritative types (`Store`, `VersionedStore`, `SetStore`):

```yaml
cocache:
  defaults:
    cache:
      local-max-age: 2m        # every derived cache that doesn't declare its own
    store:
      local-max-age: 10s       # every authoritative cache that doesn't declare its own
  overrides:
    cache:
      ttl: 30m                 # incident: no derived value lives longer than 30 minutes
  caches:
    "[UserCache]":
      load-max-concurrent: 32
```

Defaults sit below the declaration because the declaration knows the data: a library that declares `readFailure = THROW` or a short `ttl` keeps it whatever a global default says. Overrides sit above it so operators can bound staleness everywhere without a rebuild, but they can only tighten, so they never weaken what a declaration chose.

The layers reach every cache created through [`CoCacheFactory`](./caches.md#factory): `@CoCache` interfaces, spec beans, and libraries that use the starter's factory bean. A cache created directly with `CacheRuntime.create` is *unlayered*: no layer reaches it, and the start log and endpoint say so.

A global layer never fails startup over one cache. A key that doesn't apply to a cache's type, isn't allowed in that layer, or is [locked](#locks) is skipped for that cache: the start log has a `WARN` line for it and the endpoint lists it under [`skippedConfiguration`](#policy-report).

### Which fields a declaration writes {#declared-fields}

`cocache.defaults` fills only the fields the declaration leaves unwritten, so it matters what counts as written:

- **Annotation**: every `@CoCache` attribute that isn't `CoCache.UNSET`. An `@CaffeineCache` that sets `maximumSize` writes `local-maximum-size`.
- **Spec**: every field assigned in `policy { … }` (Java: `updatePolicy(…)`), and `unknownRead` when assigned. Writing a sentinel counts: `ttl = forever()` is written, so `defaults.cache.ttl` doesn't apply. Assigning a nested policy (`lease = LeasePolicy(…)`, `load = LoadPolicy(…)`, `local = LocalPolicy(…)`) writes only the fields whose value it changes: `lease = LeasePolicy(ttl = Duration.ofSeconds(30))` writes `lease-ttl`, and `lease-wait` stays open to `cocache.defaults`.
- **A whole policy**: `policy = somePolicy` (Java: `policy(CachePolicy)`) writes every field. `cocache.defaults` then has no effect on that cache; each skipped key is listed with the reason `the declaration sets the whole policy`.

Edit the type default with `policy { … }` unless you mean to fix every field.

### What each layer may set {#field-layers}

| Field | Category | `defaults` | `overrides` | `caches.[name]` |
|-------|----------|------------|-------------|-----------------|
| `ttl` on a `Cache` | staleness bound | sets | minimum; also caps per-call lifetimes (below) | sets |
| `local-max-age`, `degraded-local-max-age`, `absent-ttl` | staleness bound | sets | minimum | sets |
| `read-failure` | mode, `throw` stricter than `degrade` | sets | stricter of the two | sets |
| `fill-in-transaction` | mode, `private` stricter than `registered-keys` stricter than `allow` | sets | stricter of the two | sets |
| `invalidate-fills-on-rollback` | mode, `true` stricter than `false` | sets | stricter of the two | sets |
| `invalidate-failure` | mode without an order | sets | not allowed | sets |
| `lease-wait`, `load-timeout`, `load-acquire-timeout`, `read-timeout` | wait bound | sets | not allowed | sets |
| `ttl` on a `VersionedCache`, `tombstone-ttl` | lifetime of the version fence | sets | not allowed | sets |
| `ttl` on a `Store`, `VersionedStore` or `SetStore` | lifetime is the data's meaning | not allowed | not allowed | sets |
| `ttl-jitter`, `refresh-window`, `lease-ttl`, `load-max-concurrent`, `local-maximum-size` | other | sets | not allowed | sets |
| `unknown-read` | mode | not allowed | not allowed | sets |
| `key-prefix`, `unlock` | not policy | not allowed (use [`cocache.key-prefix`](#key-prefix)) | not allowed | sets |

Why overrides are this narrow: a smaller minimum tightens only a staleness bound. A shorter wait bound sends more instances around the lease to load for themselves, which multiplies load on a source that is already slow. A versioned type's `ttl` and `tombstone-ttl` also bound how long a redelivered older event stays refused, so shortening them lets old events back in. Setting a mode outright could weaken a stricter one below it, so only the modes with an order take part: `read-failure` and the two [transaction modes](./spring-cache.md#transactional-loads).

**`overrides.cache.ttl` also caps per-call lifetimes**: the results of `ttlOf`, and the `ttl` passed to `put(key, value, ttl)` and `putAll(entries, ttl)`. A longer one is clamped to the override. Each clamp counts in `cocache.ttl.clamped`, and the first one on each cache logs a `WARN`. The cap applies even when the cache's own `ttl` is already shorter than the override. A `cocache.caches.[name].ttl` raises the cap for that cache to its own value when that is longer, and `ttl: forever` there removes the cap, so a per-cache `ttl` is never cut by its own per-call cap.

## Top-level properties {#properties}

| Property | Type | Default | Meaning |
|----------|------|---------|---------|
| `cocache.enabled` | boolean | `true` | `false` turns off all CoCache auto-configuration, including the endpoint, health indicator, and metrics |
| `cocache.backend` | `redis` \| `memory` | `redis` | `redis`: built from `spring.data.redis.*`. `memory`: in-process, single instance, for development and tests. A `CoherenceBackend` bean of your own replaces either. |
| `cocache.key-prefix` | string | unset (the declaration's, `cocache:` by default) | First part of every storage key. Outside the layering ([below](#key-prefix)). |
| `cocache.defaults.cache.*`, `cocache.defaults.store.*` | policy | — | Global defaults for derived and authoritative caches, below the declaration ([policy properties](#policy-properties)) |
| `cocache.overrides.cache.*`, `cocache.overrides.store.*` | policy | — | Global overrides that only tighten ([what each layer may set](#field-layers)) |
| `cocache.caches.[name].*` | policy | — | [Policy properties](#policy-properties) for one cache, plus `unknown-read`, `key-prefix` and `unlock` ([one cache](#per-cache)) |
| `cocache.caches-validation` | `fail` \| `warn` | `fail` | What happens to a `cocache.caches` entry whose name matches no cache, or that sets a key that doesn't apply to its cache ([validation](#validation)) |
| `cocache.redis.*` | — | — | [Redis backend](#redis) settings |
| `cocache.spring-cache.require-sync` | boolean | `false` | `true`: startup fails if a `@Cacheable` on a CoCache cache lacks `sync = true`. `false`: a warning is logged. See [Spring Cache](./spring-cache.md#sync-check). |
| `cocache.spring-cache.degrade-on-error` | boolean | `false` | `true`: Spring's cache interceptor uses `DegradingCacheErrorHandler`, which logs read and write failures on CoCache caches instead of rethrowing them, so a non-sync `@Cacheable` runs its method while Redis is down. Failed invalidations, and failures on other caches, still go to the previous handler. See [Spring Cache](./spring-cache.md#failures). |
| `cocache.refresh-executor` | bean name | built-in | The `Executor` bean that runs [refresh-ahead](./freshness.md#refresh-ahead) loads. See [Executors](#executors). |
| `cocache.executor` | bean name | built-in | The `Executor` bean that runs the [async](./async.md) and [suspending](./coroutines.md) views: their continuations, shared loads, and synchronous loaders called through them. See [Executors](#executors). |
| `management.health.cocache.enabled` | boolean | `true` | Registers the `cocache` health indicator ([below](#actuator)) |
| `cocache.observation.enabled` | boolean | `true` | Wraps source loads in a Micrometer `Observation` when an `ObservationRegistry` bean exists. See [tracing](../operations/observability.md#tracing). |
| `cocache.metrics.local-hits` | boolean | `false` | Counts L2 hits (`cocache.hits{level=l2}`, endpoint key `localHits`) for every cache. Off by default: it adds an atomic increment to every L2 hit. See [metrics](../operations/observability.md#metrics-reads). |

## Policy properties {#policy-properties}

These keys work under `cocache.defaults.cache.`, `cocache.defaults.store.`, `cocache.overrides.cache.`, `cocache.overrides.store.` and `cocache.caches.[name].`, as far as [each layer allows](#field-layers) and [the type has the field](#applicability).

| Key | Value | Type default | Annotation | Meaning |
|-----|-------|--------------|------------|---------|
| `ttl` | duration or `forever` | `1h`; authoritative types: `forever` | `@CoCache.ttl` | Lifetime of a value in Redis |
| `ttl-jitter` | duration | `1m` | `@CoCache.ttlJitter` | Random ± spread on each value's lifetime, capped at `ttl / 2`. `0s`: off. |
| `absent-ttl` | duration | `1m`; authoritative types: `10s` | `@CoCache.absentTtl` | Lifetime of a negative entry |
| `local-max-age` | duration | `5m`; authoritative types: `30s` | `@CoCache.localMaxAge` | Maximum age of an L2 copy: the staleness bound when an invalidation message is lost |
| `degraded-local-max-age` | duration | `10s` | — | Maximum age of an L2 copy that couldn't be written to Redis |
| `tombstone-ttl` | duration or `forever` | the value's lifetime; `VersionedStore`: `1d` | — | Lifetime of a versioned tombstone and of an eviction marker. It must cover your redelivery window. A `VersionedStore` needs a finite one. |
| `refresh-window` | duration or `off` | `off` | `@CoCache.refreshWindow` | Refresh-ahead window. `off` (or `0s`) also removes a window the declaration enables. See [validation](#validation). |
| `lease-ttl` | duration | `10s` | — | How long a lease lives if its holder never fills it. Keep it above the loader's p99. |
| `lease-wait` | duration | `200ms` | — | How long a miss waits for another instance's load before loading itself |
| `load-max-concurrent` | integer or `unlimited` | `unlimited` | — | Maximum concurrent source loads per cache per instance |
| `load-acquire-timeout` | duration | `1s` | — | How long a load waits for a permit before throwing `SourceLoadRejectedException` |
| `load-timeout` | duration | `30s` | — | Upper bound for a load. An `AsyncCacheLoader` load that takes longer fails with `SourceLoadTimeoutException` and releases the lease. Readers waiting for another caller's load fail the same way once the load has been in flight that long, and later readers start a new load instead of waiting behind it. A synchronous loader can't be stopped from outside, so give it its own timeout. See [Async API](./async.md#timeouts). |
| `read-timeout` | duration | `cocache.redis.command-timeout` | — | Time limit for each Redis read of the cache (`get`, `getAll`, `getIfChanged`), capped at `command-timeout`. A read that runs out is a failed Redis read: with a source it follows `read-failure`, without one it is [`Lookup.Unknown`](./caches.md#unknown). For caches that can't fall back to a source but mustn't wait a full `command-timeout` for a stalled Redis. |
| `local-maximum-size` | integer | `10000` | `@CaffeineCache.maximumSize` | Maximum L2 entries per instance |
| `invalidate-failure` | `retry` \| `throw` | `retry` | — | A failed `Cache.invalidate`: queue for background retry, or throw `CacheWriteException`. Deleting a derived copy is always safe, so the default retries, externally sourced caches included. The authoritative types' deletes always throw and are never retried. See [When Redis is down during a write](./freshness.md#redis-down). |
| `read-failure` | `degrade` \| `throw` | `degrade` | — | A failed Redis read that has a source (the declared loader or a per-call one): load from the source, or answer [`Lookup.Unknown`](./caches.md#unknown), so `get` throws `CacheReadException` |
| `fill-in-transaction` | `registered-keys` \| `private` \| `allow` | `registered-keys` | — | Needs `spring-tx`. Which loads inside a read-write transaction stay private (no lease, no write to Redis or L2, not shared): keys already registered for invalidation in the transaction, every load, or none. See [Spring Cache](./spring-cache.md#transactional-loads). |
| `invalidate-fills-on-rollback` | boolean | `true` | — | Needs `spring-tx`. Keys that a synchronous read loaded inside a read-write transaction and wrote to Redis or L2 are invalidated when the transaction rolls back, its commit outcome is unknown, or it rolls back to a savepoint. With this `false` and `fill-in-transaction: allow`, the cache's loads ignore transactions. |
| `unknown-read` | `throw` \| `absent` | `throw` | — | `cocache.caches.[name]` only. A failed Redis read without a source: `throw` makes `get` throw `CacheReadException`; `absent` reads as not found and caches nothing. See [unknown reads](./caches.md#unknown). |
| `key-prefix` | string | `cocache.key-prefix` | `@CoCache.keyPrefix` | `cocache.caches.[name]` only. This cache's storage-key prefix ([below](#key-prefix)). |
| `unlock` | list of keys | — | — | `cocache.caches.[name]` only. Fields a library [locked](#locks) that this entry overrides on purpose. |

How a cache treats content it can't read isn't configurable: a read with a source reclaims it and reloads, and a read without one fails ([unreadable payloads](../operations/troubleshooting.md#corrupt-payloads)).

[Keeping Data Fresh](./freshness.md#lifetimes) explains how to choose these values.

### Which keys apply to which type {#applicability}

| Key | `Cache` with a loader | `Cache`, externally sourced | `VersionedCache` | `Store` | `VersionedStore` | `SetStore` |
|-----|:-:|:-:|:-:|:-:|:-:|:-:|
| `ttl`, `ttl-jitter`, `absent-ttl`, `local-max-age`, `read-timeout`, `local-maximum-size` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `degraded-local-max-age`, `lease-ttl`, `lease-wait`, `load-max-concurrent`, `load-acquire-timeout`, `load-timeout`, `invalidate-failure`, `read-failure`, `fill-in-transaction`, `invalidate-fills-on-rollback` | ✓ | ✓ | ✓ | — | — | — |
| `refresh-window` | ✓ | — | ✓ | — | — | — |
| `tombstone-ttl` | — | — | ✓ | — | ✓ | — |
| `unknown-read` | — | ✓ | — | ✓ | ✓ | — |

An externally sourced `Cache` has no source to refresh from, but its reads with a per-call loader (`get(key, loader)`, Spring Cache `sync = true`) load, so the lease, load, `read-failure` and transaction keys apply to them. The authoritative types have no source at all. A `SetStore` always throws on a failed read, so it has no `unknown-read`.

### Durations and sentinels {#durations}

Every duration in CoCache's configuration needs a unit, including `cocache.redis.*`: `500ms`, `5s`, `2m`, `1h`, `1d`, or ISO-8601 such as `PT2M`. A number without one fails startup, naming the key:

```
cocache.defaults.cache.ttl: "60" needs a unit, e.g. 60s, 5m, PT2M.
cocache.redis.command-timeout: "1500" needs a unit, e.g. 1500ms, 5m, PT2M.
```

"Never" and "off" have their own words, so no number has a hidden meaning:

| Sentinel | Keys | Meaning |
|----------|------|---------|
| `forever` | `ttl`, `tombstone-ttl` | Never expires. In a minimum, any finite lifetime wins over it. |
| `off` (or `0s`) | `refresh-window` | No refresh-ahead |
| `unlimited` | `load-max-concurrent` | No limit. `0` fails. |

- Apart from `refresh-window: 0s`, only `ttl-jitter`, `lease-wait` and `load-acquire-timeout` may be `0s`; every other duration must be positive. None may be negative, and none may exceed 100 years.
- `load-max-concurrent` and `local-maximum-size` must be positive integers.
- Values are parsed when the properties bind, so a malformed one fails startup even if no cache uses it.
- **Annotations stay in seconds**: `@CoCache(ttl = 600)` is 10 minutes. The attributes are numbers checked by the compiler, and `UNSET` and `FOREVER` are their sentinels.

### One cache {#per-cache}

```yaml
cocache:
  caches:
    "[UserCache]":
      ttl: 10m
      load-max-concurrent: 32
      read-failure: throw
    "[RevokedTokens]":
      unknown-read: absent      # Redis down: treat as "not revoked"
      read-timeout: 200ms
```

- Cache names match case-insensitively, so the environment-variable form works: `COCACHE_CACHES_USERCACHE_TTL=10m`.
- Quote and bracket names in YAML (`"[UserCache]"`) to keep them exactly as written.
- An entry is the last layer: it sets a field whatever the declaration, customizers and overrides said, except a [locked](#locks) one.
- A name that matches no cache, or a key that doesn't [apply](#applicability) to the cache, fails startup by default ([validation](#validation)).

### Key prefix {#key-prefix}

Storage keys are `{keyPrefix}{namespace}:{encodedKey}` (see [Declaring Caches](./caches.md#storage-keys)). Setting the prefix once separates applications that share a Redis:

```yaml
cocache:
  key-prefix: ${spring.application.name}:
  caches:
    "[SharedIndexCache]":
      key-prefix: "shared:"
```

`cocache.key-prefix` is not a policy field and doesn't take part in the layering: configuration wins over the declaration and customizers, even over a `keyPrefix` written in `@CoCache`. `cocache.caches.[name].key-prefix` wins over `cocache.key-prefix`. A library that [locks](#locks) `KEY_PREFIX` keeps its own prefix: `cocache.key-prefix` is skipped for that cache, and a per-cache `key-prefix` fails unless the entry lists `key-prefix` in `unlock`.

Every instance of an application must use the same prefix, and changing it is a cold start. The invalidation channel `{keyPrefix}inv:{namespace}` includes the prefix, so applications with different prefixes don't receive each other's messages. The prefix may be empty: keys and channels then share no prefix, and the Redis ACL lists each namespace ([Redis ACL](../operations/index.md#acl)). `cocache.defaults.cache.key-prefix` and the other global sections don't set a prefix: they are skipped, pointing to `cocache.key-prefix`.

## Locking fields (for library authors) {#locks}

A library whose correctness depends on a field, such as a revocation list that must fail closed and keep its storage keys, creates its caches through `CoCacheFactory` and locks those fields. Configuration can then no longer weaken them by accident:

```kotlin
@Bean
fun revokedTokens(factory: CoCacheFactory, mapper: ObjectMapper): Store<String, Instant> =
    factory.create(storeSpec<String, Instant>("revoked-tokens") {
        codec = jacksonCodec<Instant>(mapper)
        unknownRead = UnknownReadMode.ABSENT                 // Redis down: treat as "not revoked"
        policy {
            ttl = Duration.ofHours(1)
            readTimeout = Duration.ofMillis(200)
        }
    }) {
        lock(PolicyField.UNKNOWN_READ, PolicyField.READ_TIMEOUT)
        lockLayout(LayoutField.KEY_PREFIX, LayoutField.NAMESPACE)
        constrain(PolicyField.TTL) { it.finiteOr(Duration.ofHours(1)) }   // entries always expire
        verify { spec -> require(spec is StoreSpec<*, *>) }
    }
```

| In the block | Effect |
|--------------|--------|
| `lock(vararg PolicyField)` | The field reports `LOCKED` (`locked by …`). Global layers skip it for this cache (`locked by …` in the start log and `skippedConfiguration`). A `cocache.caches.[name]` entry that sets it fails startup unless it lists the field in `unlock`. A customizer that changes it throws. |
| `lockLayout(vararg LayoutField)` | The same for the storage layout: `NAMESPACE`, `KEY_PREFIX`, `KEY_ENCODER`, `CODEC`, `KEY_FILTER`, `TTL_OF`, `SOURCE`. A locked `KEY_PREFIX` ignores `cocache.key-prefix`. |
| `constrain(field) { value -> … }` | Runs after the merge and before validation, on the effective value. The value can be a sentinel (`Ttl.Forever`, `RefreshWindow.Off`, `Concurrency.Unlimited`), so write `it.finiteOr(…)`, not a null check, when the intent is "always expires". A changed value reports `LOCKED` (`constrained by …`). |
| `verify { spec -> … }` | Checks the effective spec; a failure fails the creation |

The lock owner shown in logs and errors defaults to the calling class; set `by` in the block to your library's name. From Java, build the locks and pass them: `factory.create(spec, Locks.builder("my-lib").lock(PolicyField.READ_TIMEOUT.INSTANCE).build())`.

**Break glass.** During an incident, an operator can override a locked field on purpose:

```yaml
cocache:
  caches:
    "[revoked-tokens]":
      read-timeout: 1s
      unlock: [read-timeout]
```

The start log warns, and the endpoint reports the field as `UNLOCKED`. `unlock` takes configuration keys, including `key-prefix`.

A cache created directly with `CacheRuntime.create` can't be locked, because no layer reaches it. Libraries should create caches through `CoCacheFactory` and lock what they need, rather than bypass the factory.

## Executors {#executors}

CoCache runs background, async and suspending work on two executors:

| Work | Property | Built-in executor |
|------|----------|-------------------|
| Refresh-ahead loads | `cocache.refresh-executor` | A bounded daemon pool (1–4 threads, 1024 queued tasks). A full queue skips the refresh and counts it in `cocache.refresh.rejections`. |
| Every step of the [async](./async.md) and [suspending](./coroutines.md) views after a Redis reply or a loader's future completes (decoding, L2 fills, the next Redis command, calling `loadAsync`, completing your future); shared loads; synchronous loaders called through those views; the method body of Spring Cache's `retrieve(key, loader)` | `cocache.executor` | One virtual thread per task, owned by the runtime, named `cocache-worker-N` |

Each property takes the **name** of an `Executor` bean. CoCache looks the bean up by name only, never by type. A name that matches no bean, or a bean that isn't an `Executor`, fails startup with a message naming the property. The container owns that executor: closing the `CacheRuntime` doesn't shut it down, and the container destroys it after the runtime.

Spring Boot's own `applicationTaskExecutor` works for both when it uses virtual threads:

```yaml
spring:
  threads:
    virtual:
      enabled: true          # applicationTaskExecutor uses virtual threads
cocache:
  refresh-executor: applicationTaskExecutor
  executor: applicationTaskExecutor
```

::: warning `cocache.executor` must tolerate blocking tasks
It runs blocking work (synchronous loaders, Spring method bodies) next to the continuations of every async and suspending call. Don't give it a queue or only a few threads: blocked loaders would hold every thread while the continuations they wait for queue behind them, and a loader can wait on another load. Use virtual threads or a platform pool that doesn't queue. On JDK 21–23, a loader that blocks inside `synchronized` pins the carrier thread of its virtual thread (JEP 491 fixes this in JDK 24), so prefer JDK 24 or later, or a platform thread pool. The blocking API never uses this executor: its loaders run on the calling thread. Tracing doesn't use it either: an observed async load is observed on a virtual thread of its own.
:::

Spring Cache `retrieve` uses the async view, waits on no thread, and needs no executor of its own. See [Spring Cache](./spring-cache.md#executor).

## Redis {#redis}

The Redis backend is built from Spring Boot's `spring.data.redis.*` connection details, with CoCache's own Lettuce client.

| Topology | Supported |
|----------|-----------|
| Standalone | Yes |
| Sentinel | Yes. Sentinel nodes use `spring.data.redis.sentinel.username` / `password`; data nodes use `spring.data.redis.username` / `password`. |
| Cluster | Yes. Scripts are routed by key, and the topology refreshes adaptively. |
| Static master-replica | No. Startup fails. |

TLS follows the Spring Boot SSL bundle (`spring.data.redis.ssl.bundle`). A `rediss://` URL enables TLS with the JDK defaults. CoCache always uses the master, because its read script also writes leases.

| Property | Type | Default | Meaning |
|----------|------|---------|---------|
| `cocache.redis.channel-mode` | `pubsub` \| `sharded` | `pubsub` | `pubsub`: `PUBLISH` / `SUBSCRIBE`, atomic with the Redis change. `sharded`: `SPUBLISH` / `SSUBSCRIBE` (Redis 7+), sent right after the change. **All instances must use the same mode**; the two don't hear each other. |
| `cocache.redis.command-timeout` | duration | `2s` | Maximum wait for one command or script. `read-timeout` can only shorten it. |
| `cocache.redis.heartbeat` | duration | `5s` | `PING` interval on the subscription connection. A failed ping clears L2 and reconnects. |
| `cocache.redis.invalidate-batch-size` | integer | `512` | Maximum keys per invalidation script |
| `cocache.redis.client-name` | string | unset | `CLIENT SETNAME` for CoCache's connections |
| `cocache.redis.store-eviction-check` | `warn` \| `fail` \| `off` | `warn` | Under `volatile-*`, what to do about an authoritative cache with a finite `ttl` ([eviction check](#eviction-check)) |
| `cocache.redis.assume-maxmemory-policy` | string | unset | The policy the eviction check uses when it can't read `INFO memory` (the Redis user lacks `+info`, or the service blocks it), such as `noeviction` or `volatile-lru`. Must be `noeviction`, `allkeys-*` or `volatile-*`. Ignored when the policy can be read. |

Durations here need a unit as everywhere else ([durations](#durations)).

### Eviction check {#eviction-check}

A `Store`, `VersionedStore` or `SetStore` keeps its only copy in Redis, and an evicted key can't be rebuilt. When the application has any of them (created, or declared and still lazy), the starter reads every primary's `maxmemory-policy` from `INFO memory` at startup. The authoritative caches' `ttl` comes from the same layers their creation uses.

| `maxmemory-policy` | Result |
|--------------------|--------|
| `noeviction` | Starts |
| `allkeys-*` on any primary | **Startup fails**, whatever `store-eviction-check` says: any key can be evicted |
| Can't be read, and `assume-maxmemory-policy` isn't set | **Startup fails**, naming both fixes: grant `+info`, or set `assume-maxmemory-policy` |
| `volatile-*`, and an authoritative cache has a finite `ttl` | `store-eviction-check`: `warn` logs a warning, `fail` stops startup, `off` accepts it. Such keys can be evicted before they expire. |
| `volatile-*`, and every authoritative cache has `ttl: forever` | Starts: keys without an expiry are never evicted under `volatile-*` |

A `VersionedStore` tombstone always expires, but evicting one only lets a late older version in early, so tombstones don't count. Only the Redis backend is checked. See [maxmemory-policy](../operations/index.md#maxmemory-policy).

[Operations](/operations/) covers sizing and deployment choices.

## Actuator and health {#actuator}

With `spring-boot-starter-actuator` on the classpath:

| Feature | Switch | Notes |
|---------|--------|-------|
| `cocache` endpoint | `management.endpoints.web.exposure.include=cocache`; access via `management.endpoint.cocache.access` | Lists caches with their effective policy and counters, inspects and invalidates single keys, and broadcasts an invalidation for one key without touching Redis |
| Per-key deletes of the only copy | `management.endpoint.cocache.allow-store-delete` (default `false`): `Store`, `SetStore`; `management.endpoint.cocache.allow-purge` (default `false`): `VersionedStore`, and `?purge=true` on a `VersionedCache` | Without the switch these return `403` and change nothing ([Observability](/operations/observability#invalidate-key)) |
| `cocache` health indicator | `management.health.cocache.enabled` (default `true`) | `DOWN` only while the Redis invalidation heartbeat fails |
| Micrometer meters `cocache.*` | Micrometer on the classpath | Tagged by `cache` |

See [Observability](/operations/observability) for the endpoint paths and meter names.

### Effective values and sources {#policy-report}

Every layered cache reports where each of its fields came from:

- **Start log.** One `INFO` line per cache lists the fields whose value doesn't come from the type default, each with its source, for example `Cache[UserCache] - policy: ttl=PT10M (caches), local-max-age=PT2M (defaults).` Each skipped entry, clamped field and unlocked field gets a `WARN` line.
- **Endpoint.** `GET /actuator/cocache/{name}` lists every field that applies to the cache under `policy`, with its effective value and source, and every skipped entry under `skippedConfiguration`, with its key, layer and reason. See [the cache report](../operations/observability.md#actuator-endpoint).

| Source | Meaning |
|--------|---------|
| `TYPE_DEFAULT` | The type default |
| `DEFAULTS` | `cocache.defaults.cache.*` or `cocache.defaults.store.*` |
| `DECLARATION` | The annotation or spec |
| `CUSTOMIZER` | A `CacheSpecCustomizer` (the detail names it) |
| `OVERRIDES` | Tightened by `cocache.overrides.*` (the detail gives the value before) |
| `CACHES` | `cocache.caches.[name].*` |
| `CLAMPED` | Changed by the [validation chain](#validation) after a global layer caused a conflict |
| `LOCKED` | Locked by a library (`locked by …`), or changed by its constraint (`constrained by …`) |
| `UNLOCKED` | Set by `cocache.caches.[name]` over a lock, listed in `unlock` |

Sentinels read `forever`, `off` and `unlimited`, modes are lowercase, and durations use ISO-8601 (`PT10M`). With `spring-tx` on the classpath, a `Cache` or `VersionedCache` also lists `invalidate-fills-on-rollback` and `fill-in-transaction` after its policy fields. They aren't policy fields and have no declaration layer, but the global and per-cache layers merge them the same way. An unlayered cache has an empty `policy` and uses the type defaults of both transaction modes.

## Startup validation {#validation}

CoCache fails startup instead of running with a configuration it can't honor.

**Values.** A duration without a unit, a misspelled sentinel, a zero where only a positive value makes sense, or a negative value fails as soon as the properties bind, naming the full key ([durations](#durations)).

**`cocache.caches` entries.** Two rules work side by side. `cocache.caches-validation` decides the first; nothing changes the second:

| Entry | `caches-validation: fail` (default) | `caches-validation: warn` |
|-------|-------------------------------------|---------------------------|
| A name that matches no cache, checked once all singletons exist (almost always a typo; the message lists the declared caches) | Startup fails | `WARN` |
| A key that doesn't [apply](#applicability) to the cache's type, or an `unlock` entry that isn't a field | Creating the cache fails | `WARN`; the key is ignored |
| A name that matches a cache created directly with `CacheRuntime.create` (unlayered) | `WARN`, and the entry is listed under the endpoint's `ignoredConfiguration` | Same |

The third rule only warns because such a cache may be created after startup, and an override written during an incident shouldn't stop the application. The name check runs once, when all singletons exist, so a cache that a library creates even later isn't known to it yet: use `warn` if you configure such a cache.

**Locks.** A `cocache.caches.[name]` entry that sets a [locked](#locks) field without listing it in `unlock` fails startup, whatever `caches-validation` says. So does a customizer that changes a locked field or layout, or any data source.

**Policies.** After every layer, every policy must hold together: `refresh-window` must be less than `ttl − ttl-jitter` (the effective jitter) and less than `local-max-age`, or a fresh copy would start inside the window. The validation chain decides who is at fault:

- When a global layer (`cocache.defaults` or `cocache.overrides`) set one of the fields involved (`refresh-window`, `local-max-age`, `ttl`, `ttl-jitter`) and `cocache.caches.[name]` set none of them, the window is turned **off** for that cache. The start log warns, and the endpoint reports `refresh-window` as `CLAMPED`. An operator who shortens `local-max-age` everywhere doesn't stop the application.
- Otherwise startup fails: when only the declaration caused the conflict, or when `cocache.caches.[name]` set one of the fields involved. For example, `cocache.defaults.cache.ttl: 10m` with `cocache.caches.[UserCache].refresh-window: 20m` fails: the per-cache entry asked for that window.

`refresh-window: off` removes a window the declaration enables, so a property can turn refresh-ahead off for one cache.

[Declaring Caches](./caches.md#validation) lists the declaration checks.

## Beyond properties {#beyond-properties}

Some settings exist only in code, because they are functions or objects rather than values:

| Setting | Where |
|---------|-------|
| `ttlOf`, `keyEncoder`, `keyFilter`, `codec`, `loader` | Spec or [customizer](./caches.md#customizer) (a customizer can't change the source) |
| `local.initialCapacity`, `local.expireAfterAccess` | `@CaffeineCache`, spec, or customizer |
| Locks and constraints | `CoCacheFactory.create(spec) { … }` ([locks](#locks)) |
| Clock | A `CacheClock` bean (for example `ManualClock` in tests) is used by the runtime and the in-memory backend |
| Retry policy, node id | Your own `CacheRuntime` bean |
| Redis client | Your own `CoherenceBackend` bean, such as `RedisBackend.of(client, options)` |

Both the `CacheRuntime` and the `CoherenceBackend` beans are replaced when you declare your own:

```kotlin
@Bean(destroyMethod = "close")
fun cocacheRuntime(backend: CoherenceBackend): CacheRuntime =
    CacheRuntime.builder()
        .backend(backend)
        .retryPolicy(RetryPolicy(maxAttempts = 16, capacity = 50_000))
        .build()
```

| `RetryPolicy` field | Default |
|---------------------|---------|
| `maxAttempts` | `8` |
| `initialBackoff` | `100ms` (doubles each attempt) |
| `maxBackoff` | `10s` |
| `capacity` | `10000` pending invalidations |
