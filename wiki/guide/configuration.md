---
title: Configuration Reference
description: Every cocache.* property with its type, default and unit, how properties layer over declarations and customizers, Redis connection settings, actuator and health switches, startup validation, and what only code can configure.
---

# Configuration Reference

## How settings combine {#precedence}

Each cache's settings are built in layers. A later layer overrides an earlier one, field by field; a field a layer doesn't set passes through:

1. **Built-in defaults** (`CachePolicy.DEFAULT`; `SetCacheSpec.DEFAULT_POLICY` for set caches).
2. **The declaration**: `@CoCache` / `@CaffeineCache`, or the `CacheSpec` you build.
3. **`CacheSpecCustomizer` beans**, in Spring order.
4. **`cocache.defaults.*`**, for every cache.
5. **`cocache.caches.[name].*`**, for one cache.

Properties come last so operators can retune a cache without a rebuild. This means `cocache.defaults.*` overrides what an annotation says, too.

::: tip Set caches keep their lifetime
`cocache.defaults.ttl` and `ttl-forever` don't apply to a [`SetCache`](./caches.md#set-cache): its only copy is Redis, so a global lifetime would make sets expire and disappear. `cocache.defaults.absent-ttl` and `refresh-window` are skipped too, since a set cache has no source. Only `cocache.caches.[name].ttl` / `ttl-forever` change a set cache's lifetime. Every other `defaults` key applies as usual.

`cocache.defaults.refresh-window` doesn't apply to [versioned caches](./freshness.md#versioned-writes) either, since they don't support refresh-ahead. Setting `cocache.caches.[name].refresh-window` on one fails at startup.
:::

## Top-level properties {#properties}

| Property | Type | Default | Meaning |
|----------|------|---------|---------|
| `cocache.enabled` | boolean | `true` | `false` turns off all CoCache auto-configuration, including the endpoint, health indicator, and metrics |
| `cocache.backend` | `redis` \| `memory` | `redis` | `redis`: built from `spring.data.redis.*`. `memory`: in-process, single instance, for development and tests. A `CoherenceBackend` bean of your own replaces either. |
| `cocache.defaults.*` | policy | — | [Policy properties](#policy-properties) for every cache |
| `cocache.caches.[name].*` | policy | — | [Policy properties](#policy-properties) for one cache |
| `cocache.redis.*` | — | — | [Redis backend](#redis) settings |
| `cocache.spring-cache.require-sync` | boolean | `false` | `true`: startup fails if a `@Cacheable` on a CoCache cache lacks `sync = true`. `false`: a warning is logged. See [Spring Cache](./spring-cache.md#sync-check). |
| `cocache.spring-cache.retrieve-executor` | bean name | — | **Deprecated since 6.3** and ignored with a startup warning: `retrieve` uses the [async view](./async.md) and needs no executor. Removed in 7.0. |
| `cocache.refresh-executor` | bean name | built-in | The `Executor` bean that runs [refresh-ahead](./freshness.md#refresh-ahead) loads. See [Executors](#executors). |
| `cocache.async-executor` | bean name | built-in | The `Executor` bean that runs the [async view](./async.md)'s continuations. See [Executors](#executors). |
| `cocache.blocking-executor` | bean name | built-in | The `Executor` bean that runs synchronous loaders called through the async view. See [Executors](#executors). |
| `management.health.cocache.enabled` | boolean | `true` | Registers the `cocache` health indicator ([below](#actuator)) |
| `cocache.observation.enabled` | boolean | `true` | Wraps source loads in a Micrometer `Observation` when an `ObservationRegistry` bean exists. See [tracing](../operations/observability.md#tracing). |
| `cocache.metrics.local-hits` | boolean | `false` | Counts L2 hits (`cocache.hits{level=l2}`, endpoint key `localHits`) for every cache. Off by default: it adds an atomic increment to every L2 hit. See [metrics](../operations/observability.md#metrics-reads). |

## Policy properties {#policy-properties}

These keys work under both `cocache.defaults.` and `cocache.caches.[name].`.

| Key | Type | Built-in default | Annotation equivalent | Meaning |
|-----|------|------------------|-----------------------|---------|
| `ttl` | duration | `1h` (set caches: forever) | `@CoCache.ttl` | Lifetime of a value in Redis |
| `ttl-forever` | boolean | `false` | `ttl = CoCache.FOREVER` | `true`: values never expire. Overrides `ttl`. |
| `ttl-jitter` | duration | `1m` | `@CoCache.ttlJitter` | Random ± spread on each value's lifetime, capped at `ttl / 2`. `0`: off. |
| `absent-ttl` | duration | `1m` | `@CoCache.absentTtl` | Lifetime of a negative entry |
| `local-max-age` | duration | `5m` | `@CoCache.localMaxAge` | Maximum age of an L2 copy |
| `degraded-local-max-age` | duration | `10s` | — | Maximum age of an L2 copy that couldn't be written to Redis |
| `refresh-window` | duration | off | `@CoCache.refreshWindow` | Refresh-ahead window. `0`: off (also removes a window the declaration enables); see [validation](#validation). |
| `lease-ttl` | duration | `10s` | — | How long a lease lives if its holder never fills it. Keep it above the loader's p99. |
| `lease-wait` | duration | `200ms` | — | How long a miss waits for another instance's load before loading itself |
| `load-max-concurrent` | integer | `0` (unlimited) | — | Maximum concurrent source loads per cache per instance |
| `load-acquire-timeout` | duration | `1s` | — | How long a load waits for a permit before throwing `SourceLoadRejectedException` |
| `load-async-timeout` | duration | `30s` | — | How long one `AsyncCacheLoader` load may take. Past it the call fails with `SourceLoadTimeoutException` and the lease is released. Doesn't apply to synchronous loaders. See [Async API](./async.md#timeouts). |
| `local-maximum-size` | long | `10000` | `@CaffeineCache.maximumSize` | Maximum L2 entries per instance |
| `invalidate-failure` | `retry` \| `throw` | `retry` | — | A failed Redis invalidation: queue for background retry, or throw `CacheWriteException` |
| `read-failure` | `degrade` \| `throw` | `degrade` | — | A failed Redis read: load from the source, or throw `CacheReadException` |
| `foreign-format` | `fail` \| `reclaim` | `fail` without a loader, `reclaim` with one | — | Content the cache can't read (written by 5.x or another program, or undecodable): throw `CacheReadException` and keep it, or reclaim and reload. Without a loader, `fail` also applies to a value written by a newer CoCache version, and to `SetCache.add` / `remove` on unreadable content (they throw `CacheWriteException` and leave the key unchanged); `put` always replaces. `cocache.defaults.foreign-format` doesn't apply to `SetCache`. See [Upgrading from 5.x](./migration-6.md#foreign-format). |
| `key-prefix` | string | `cocache:` | `@CoCache.keyPrefix` | First part of every storage key ([below](#key-prefix)) |

[Keeping Data Fresh](./freshness.md#lifetimes) explains how to choose these values.

### Durations {#durations}

A bare number under `defaults` and `caches` is **seconds**, matching the annotations: `ttl: 600`. Spring duration strings also work: `500ms`, `10m`, `PT10M`. Write a unit for sub-second values such as `lease-wait: 500ms`.

Under `cocache.redis` a bare number is **milliseconds**, matching `spring.data.redis.timeout`. Write a unit there too.

### One cache {#per-cache}

```yaml
cocache:
  defaults:
    local-max-age: 2m
  caches:
    "[UserCache]":
      ttl: 10m
      load-max-concurrent: 32
      read-failure: throw
```

- Cache names match case-insensitively, so the environment-variable form works: `COCACHE_CACHES_USERCACHE_TTL=10m`.
- Quote and bracket names in YAML (`"[UserCache]"`) to keep them exactly as written.
- A name that matches no declared cache fails startup.

### Key prefix {#key-prefix}

Storage keys are `{keyPrefix}{namespace}:{encodedKey}` (see [Declaring Caches](./caches.md#storage-keys)). Setting the prefix once separates applications that share a Redis:

```yaml
cocache:
  defaults:
    key-prefix: ${spring.application.name}:
  caches:
    "[SharedIndexCache]":
      key-prefix: "shared:"
```

`caches.[name].key-prefix` overrides `defaults.key-prefix`, which overrides the declaration and customizers, even a `keyPrefix` written in `@CoCache`. Every instance of an application must use the same prefix, and changing it is a cold start. The invalidation channel `cocache:inv:{namespace}` doesn't include the prefix, so applications with the same namespace on one Redis receive each other's messages; that is wasted work, never wrong data.

## Executors {#executors}

CoCache runs background and async work on executors:

| Work | Property | Built-in executor |
|------|----------|-------------------|
| Refresh-ahead loads | `cocache.refresh-executor` | A bounded daemon pool (1–4 threads, 1024 queued tasks). A full queue skips the refresh and counts it in `cocache.refresh.rejections`. |
| Continuations of the [async view](./async.md): decoding, L2 fills, issuing the next Redis command, calling `loadAsync`, completing your future | `cocache.async-executor` | A `ForkJoinPool` owned by the runtime, sized to the CPU count, with daemon threads named `cocache-async-*` |
| Synchronous loaders called through the async view, and backends that implement only the synchronous SPI | `cocache.blocking-executor` | A virtual thread per task on JDK 21+. On JDK 17–20, a daemon pool that never queues (up to 512 threads, named `cocache-blocking-*`). When it's full, the load is rejected rather than run on the async executor. |

Each property takes the **name** of an `Executor` bean. CoCache looks the bean up by name only, never by type. A name that matches no bean, or a bean that isn't an `Executor`, fails startup with a message naming the property. The container owns that executor: closing the `CacheRuntime` doesn't shut it down.

Spring Boot's own `applicationTaskExecutor` works for refresh-ahead and as the blocking executor:

```yaml
spring:
  threads:
    virtual:
      enabled: true          # applicationTaskExecutor uses virtual threads
cocache:
  refresh-executor: applicationTaskExecutor
  blocking-executor: applicationTaskExecutor
```

::: warning What each executor may run
The async executor runs only short, non-blocking work. Never block on it, for example by calling the synchronous API from a continuation. The built-in `ForkJoinPool` compensates for a thread that blocks in `join()`, but a fixed-size pool you supply doesn't. The blocking executor runs synchronous loaders, so it must not queue: a loader can wait on another load.
:::

`cocache.spring-cache.retrieve-executor` is deprecated since 6.3. Spring Cache `retrieve` now uses the async view and waits on no thread. See [Spring Cache](./spring-cache.md#executor).

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
| `cocache.redis.set-cache-eviction-check` | `warn` \| `fail` \| `off` | `warn` | With any `SetCache`, read every primary's `maxmemory-policy` at startup: `allkeys-*` can evict the set's only copy. `warn` logs, `fail` stops startup, `off` skips. Skipped when `CONFIG` isn't available (managed Redis, ACL). See [maxmemory-policy](../operations/index.md#maxmemory-policy). |
| `cocache.redis.command-timeout` | duration (ms) | `2s` | Maximum wait for one command or script |
| `cocache.redis.heartbeat` | duration (ms) | `5s` | `PING` interval on the subscription connection. A failed ping clears L2 and reconnects. |
| `cocache.redis.invalidate-batch-size` | integer | `512` | Maximum keys per invalidation script |
| `cocache.redis.client-name` | string | unset | `CLIENT SETNAME` for CoCache's connections |

[Operations](/operations/) covers sizing and deployment choices.

## Actuator and health {#actuator}

With `spring-boot-starter-actuator` on the classpath:

| Feature | Switch | Notes |
|---------|--------|-------|
| `cocache` endpoint | `management.endpoints.web.exposure.include=cocache`; access via `management.endpoint.cocache.access` | Lists caches and counters, inspects and invalidates single keys |
| `cocache` health indicator | `management.health.cocache.enabled` (default `true`) | `DOWN` only while the Redis invalidation heartbeat fails |
| Micrometer meters `cocache.*` | Micrometer on the classpath | Tagged by `cache` |

See [Observability](/operations/observability) for the endpoint paths and meter names.

## Startup validation {#validation}

CoCache fails startup instead of running with a configuration it can't honor:

- **Unknown cache names** under `cocache.caches` (almost always a typo). The message lists the declared caches.
- **Unsupported property names** from earlier major versions, such as `cocache.redis.strict-failure` or anything under `cocache.load.*`, fail startup with a message that names the replacement, if there is one. The full list is in [Upgrading from 5.x](./migration-6.md#properties).
- **Invalid policies.** Every duration must be positive and at most 100 years; only `ttl-jitter`, `lease-wait`, `load-acquire-timeout`, and `refresh-window` (0 = off) may be zero; none may be negative. `refresh-window` must be less than `ttl − ttl-jitter` (the effective jitter) and less than `local-max-age`.

`refresh-window: 0` turns refresh-ahead off, so a property can also remove a window that the declaration enables.

[Declaring Caches](./caches.md#validation) lists the declaration checks.

## Beyond properties {#beyond-properties}

Some settings exist only in code, because they are functions or objects rather than values:

| Setting | Where |
|---------|-------|
| `ttlOf`, `keyEncoder`, `keyFilter`, `codec`, `loader` | Spec or [customizer](./caches.md#customizer) |
| `local.initialCapacity`, `local.expireAfterAccess` | `@CaffeineCache`, spec, or customizer |
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
