---
title: Changelog
description: Release history and notable changes for CoCache.
---

# Changelog

## v6.3.0 (Current)

**Module Group:** `me.ahoo.cocache` · Minor release. 6.2 was not released on its own: the work planned for 6.2 ships in 6.3.0. The Redis wire format and the invalidation channel are unchanged, so 6.0, 6.1 and 6.3 instances can share Redis and be upgraded one at a time.

### Async API

Every cache now has an asynchronous view on the same engine ([RFC 0004](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0004-async-api.md)). See the [Async API](./async.md) guide.

```kotlin
val async = userCache.async()                 // same L2, fences, in-flight loads and leases
async.get("42").thenAccept { user -> ... }
async.put("42", user).thenCompose { async.get("42") }
```

- `Cache.async()` / `SetCache.async()` return `AsyncCache` / `AsyncSetCache`. The methods have the same names and semantics as the synchronous API and return `CompletableFuture`. An L2 hit returns an already-completed future.
- `AsyncCacheLoader` (`loadAsync`, `loadAllAsync`) loads from non-blocking sources. Set it as `asyncLoader` in a spec; `@CoCache` interfaces find it by type or by `loader = "…"`. Either kind of loader works with both APIs.
- No thread is parked: waits for another instance's lease and for load permits are scheduled, and futures complete on the async executor, never on the Lettuce I/O thread. Futures fail with the original exception, cancelling one doesn't cancel the shared load, and async methods never throw synchronously.
- New settings:
  - `cocache.async-executor` and `cocache.blocking-executor`, each naming an `Executor` bean;
  - `load-async-timeout` (default 30 seconds) per cache, with the new `SourceLoadTimeoutException` and the `cocache.load.timeouts` metric;
  - `cocache.observation.enabled` (default `true`) for [load-path tracing](#load-path-tracing). With Spring Boot Actuator and a Micrometer tracing bridge on the classpath, tracing is on by default; set it to `false` to turn it off.
- The Redis backend implements the new optional SPI `AsyncRemoteStore` / `AsyncInvalidationChannel` natively. Third-party backends keep working unchanged, because the engine runs their synchronous methods on the blocking executor.
- **Spring Cache:** `retrieve` uses the async view, so async and reactive `@Cacheable(sync = true)` methods load without holding a thread.
- **Performance:** the synchronous hit path is unchanged (JMH within noise of the code before the async work, at 1 and 8 threads). An async L2 hit runs at about 90% of a synchronous one. With 32 requests in flight, one thread completes about 12 times as many Redis reads or writes as a blocking thread.

### Batch writes with putAll

`putAll(entries)` and `putAll(entries, ttl)` write several keys as one write: one script per Redis slot group, one broadcast per group, and the same stale-write-back protection as `put`. See [Keeping Data Fresh](./freshness.md).

### Load-path tracing

With an `ObservationRegistry` bean, the starter registers a `MicrometerLoadObserver`. It wraps every source load and every wait for another instance's lease in a `cocache.load` observation, with tags `cache`, `operation` and `outcome`. Hits create no observation. The new `LoadObserver` SPI lets you plug in your own. See [Observability](../operations/observability.md#tracing).

### Faster SetCache reads

The `SetCache` read script fetches members with `HKEYS` and the format version with `HGET`, instead of `HGETALL` with every member's empty value. The stored layout is unchanged. In server-side throughput, reads are 1.33, 1.55 and 1.76 times as fast at 10, 100 and 1000 members. ACL errors raised inside the read script now reach the caller instead of being read as unrecognized content.

### L2 hit counter

`cocache.metrics.local-hits=true` (without Spring: `CacheRuntime.builder().recordLocalHits(true)`) counts reads answered by L2 as `cocache.hits{level=l2}` and the endpoint key `localHits`, so reads split exactly into L2 hits, misses and filtered reads. It is off by default because it adds an atomic increment to every L2 hit: with it on, L2 hit throughput drops by about 3–9% in JMH (1 and 8 threads, depending on the call site). With it off, throughput matches 6.1. The 5.x property `cocache.metrics.client-side-hits` now points to it. See [Observability](../operations/observability.md#metrics-reads).

### Fixes

- With non-atomic broadcast (`channel-mode: sharded`, or when the atomic-broadcast probe fails), a write that failed but partly took effect is now broadcast too. Before, a `putAll` that failed in one slot group, or a `put` applied in Redis whose reply was lost, left other instances reading the old local copy until `local-max-age`. Synchronous `putAll` and `invalidateAll` on Redis now wait for every slot group before they throw.
- A read that saw a local copy inside the refresh window just as the previous refresh finished could start a second refresh of the same key on the same instance. That second refresh only cost an extra Redis read, and it no longer happens.
- Executors named by bean (`cocache.refresh-executor`, and the new `cocache.async-executor` / `cocache.blocking-executor`) could be destroyed before the CoCache runtime when the application shut down, so work submitted while the runtime closed was rejected. This was present since 6.1. The runtime now closes first.

### Compatibility kit and CI

- New TCK suites: `MultiNodeSpec` (three runtimes, every invalidation interleaving, one load and one refresh per key cluster-wide), `FaultSpec` (L1 faults, retries, writes that took effect but reported an error, no replay after a reconnect), and async variants of every suite (`AsyncBackendProtocolSpec`, `AsyncEngineSpec`, `AsyncSetEngineSpec`, `AsyncMultiNodeSpec`, `AsyncFaultSpec`).
- The Redis backend and the starter are tested on Valkey 8 in CI.
- A `SetCache` read benchmark (`setRead`) confirmed the Hash encoding.

### Upgrade notes

- `cocache.spring-cache.retrieve-executor`, `CoCacheSpringCacheManager(runtime, executor)` and `CoCacheSpringCacheManager.DEFAULT_EXECUTOR` are deprecated: the executor is ignored, and the property logs a warning at startup. They are removed in 7.0. `CoCacheSpringCache(cache, admin, executor)` is not deprecated: its executor runs the method body.
- **Async and reactive `@Cacheable(sync = true)` methods** behave differently in two ways:
  - The method body runs on `cocache.blocking-executor`: virtual threads on JDK 21+. On JDK 17 it's a pool of up to 512 threads that rejects new loads when full (`RejectedExecutionException`). When full, the old retrieve executor ran them on the calling thread instead.
  - The load is bounded by `load-async-timeout` (default 30 seconds): past it the call fails with `SourceLoadTimeoutException`, while the method body keeps running.
- `Cache.async()` is a default method that throws `UnsupportedOperationException`. A `Cache` you implement yourself, for example a test double, needs to override it only if callers use the async view.
- A custom `LoadObserver` sees async loads only if it overrides `observeAsync`.
- **Kotlin callers must recompile** against 6.3, or they fail with `NoSuchMethodError`. `LoadPolicy` gained `asyncTimeout`, which changes `copy` and the synthetic constructor behind Kotlin default arguments, so this applies to Kotlin code that calls `copy` or omits `LoadPolicy` arguments. The constructors and `copy` of `CoCacheProperties` and `PolicyProperties` changed too, for code that builds the starter's properties itself. Java callers are unaffected.
- **Redis ACL:** a user with a minimal ACL needs `+hkeys +hget` before the upgrade, or `SetCache` reads fail with an ACL error. Keep `+hgetall`: conditional reclaim and 6.0/6.1 instances still use it. See [ACL](../operations/index.md#acl).
- TCK: `HookedBackend.closesDelegate` (default `false`, as before) and each suite's `closesBackend` let the suites close a backend created per case. Turn it on if your backend opens connections or threads. `BackendProtocolSpec` and `AsyncBackendProtocolSpec` always close the backend they create and have no such switch. Hooks that pick out a caller should use `HookedBackend.issuer()`, because async calls complete on other threads.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.3.0")
```

## v6.1.0

**Module Group:** `me.ahoo.cocache` · Minor release. The Redis wire format and the invalidation channel are unchanged, so 6.0 and 6.1 instances can share Redis and be upgraded one at a time.

### Spring AOT and GraalVM native images

Applications that use the starter now build with Spring AOT and run as GraalVM native images. CoCache registers the hints it needs at build time:

- JDK proxies for `@CoCache` and `@JoinCacheable` interfaces, and reflection on those interfaces (default methods included);
- the key type's `toString()` for the default key encoder;
- the SpEL root types of `keyExpression` and `joinKeyExpression`;
- Jackson binding for the value type when the default codec applies.

Cache beans are created by factory methods that AOT can generate code for. A custom `Codec` or types that only your own code reflects on still need your hints. See [Native images](./native.md).

### Configurable executors

```yaml
cocache:
  refresh-executor: applicationTaskExecutor          # refresh-ahead loads
  spring-cache:
    retrieve-executor: applicationTaskExecutor       # Spring Cache async retrieve
```

Both name an `Executor` bean, resolved by name. An unknown name or a bean that isn't an `Executor` fails startup. CoCache never shuts down an executor you supply. Prefer a virtual-thread executor for `retrieve`: a pool that queues tasks behind ones waiting on them can deadlock nested async `@Cacheable(sync = true)` calls. See [Configuration](./configuration.md#executors).

### SetCache eviction check

With any `SetCache`, CoCache reads every primary's `maxmemory-policy` at startup. `allkeys-*` can evict the set's only copy, so it logs a warning by default. `cocache.redis.set-cache-eviction-check` selects `warn`, `fail` or `off`. The check is skipped when `CONFIG` isn't available (managed Redis, ACL). `RedisBackend.maxmemoryPolicies()` exposes the lookup for applications without Spring.

### Agent skills and docs

- The agent skills are split by outcome: `cocache-develop`, `cocache-migrate` and `cocache-extend`, each with `claude plugin eval` cases and a validator in CI.
- The documentation site has an animated home page that shows the read and write paths, and a new logo.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.1.0")
```

## v6.0.0

**Module Group:** `me.ahoo.cocache` · **Major release.** CoCache is rebuilt on the coherence protocol in [RFC 0003](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0003-target-architecture.md). **No rolling upgrade:** the Redis value layout and the invalidation channel changed, so 5.x and 6.0 instances cannot share Redis. Follow the [migration guide](./migration-6.md) and switch every instance at once.

### What changes for you

- **Stale write-backs are closed.** Loads write Redis only while they hold a lease token, and every L2 fill is checked against an invalidation stamp. The 5.x "known inherent window" no longer exists: races inside the protocol leave no stale value in Redis.
- **Faster hit path.** An L2 hit is 54–59% faster than 5.4, an interface proxy hit 28–64% faster, and `put` / `invalidate` about twice as fast (JMH, RFC 0003 §7.1).
- **One loader per key across the cluster**, refresh-ahead with one refresh per key cluster-wide, and batch `getAll` that reads L2, then Redis in one round trip, then the source in one `loadAll`.
- **Failure modes you can choose:** `read-failure: degrade | throw`, `invalidate-failure: retry | throw`, a bounded invalidation retry queue, and a load bulkhead (`load-max-concurrent`).
- **Redis backend on native Lettuce** (`cocache-redis`, no Spring dependency): standalone, Sentinel and Cluster, TLS through Spring Boot SSL bundles or `rediss://`, Pub/Sub or Sharded Pub/Sub (`cocache.redis.channel-mode`).
- **Spring:** `@EnableCoCache` with `@CoCache` interfaces or `CacheSpec` beans, `CacheSpecCustomizer`, layered `cocache.defaults.*` / `cocache.caches.[name].*` properties with full IDE metadata, the `CoCacheSpringCacheManager` (use `@Cacheable(sync = true)` for coherent loads), and `TransactionalInvalidator` (invalidate after commit).
- **Observability:** the `cocache` actuator endpoint and `cocache.*` Micrometer meters tagged by `cache`.

### Developer experience

- **`JoinCache`**: a read-only view that joins two caches with two batch reads, declared with `@JoinCacheable` and registered with `@EnableCoCache(joinCaches = […])`. See [JoinCache](./join-cache.md).
- **No Redis for local development and tests:** `cocache.backend: memory` runs every cache on the in-process backend (single process only). A `CacheClock` bean such as `ManualClock` lets tests advance time.
- **Per-key troubleshooting:** `GET /actuator/cocache/{name}/{key}` shows the L2 copy and the L1 state of one key without loading it; `DELETE` on the same path invalidates it cluster-wide. See [Operations](../operations/observability.md#inspect-key).
- **Health indicator** `cocache`: DOWN when the Redis heartbeat fails; pending invalidations are listed in the details.
- **Spring Cache check:** a WARN for each `@Cacheable` on a CoCache cache without `sync = true` (the non-sync path isn't lease-protected); `cocache.spring-cache.require-sync=true` fails startup instead.
- **Global key prefix:** `cocache.defaults.key-prefix` (and per cache `cocache.caches.[name].key-prefix`), for example `${spring.application.name}:` when several apps share one Redis.
- **Key type check:** the default `toString()` key encoder rejects key types that don't override `toString()`, which would otherwise never hit.
- **`ExternalInvalidator`:** invalidate from CDC consumers or batch jobs with the same protocol, without creating a cache.
- `@JvmOverloads` on `LeasePolicy`, `LoadPolicy`, `LocalPolicy`, `RetryPolicy` and `RedisBackendOptions`, and `updatePolicy(Consumer)` on spec builders, for Java.

### Migration at a glance

| 5.x | 6.0 |
|-----|-----|
| `me.ahoo.cache.*` | `me.ahoo.cocache.*`; `@EnableCoCache` is in `me.ahoo.cocache.spring.boot` |
| `cocache-spring-redis`, `cocache-spring-cache` | `cocache-redis`, `cocache-spring` (plus `cocache-codec-jackson`) |
| `ttlAmplitude`, `missingTtl`, `clientSideTtl`, `refreshAhead` | `ttlJitter`, `absentTtl`, `localMaxAge`, `refreshWindow` |
| `{name}.CacheSource` bean | a `CacheLoader<K, V>` bean matched by type, or `@CoCache(loader = …)` |
| `{name}.ClientSideCache` and other bean suffixes | `CacheSpecCustomizer`, `cocache.caches.[name].*` |
| `getCache` / `set(key, ttlAt, value)` / `evict` | `get` / `lookup` / `put(key, value, ttl)` / `invalidate` |
| `SetElementsCache` | `SetCache` |
| `cocache.redis.strict-failure=true` | `cocache.defaults.read-failure=throw` |
| `cocache.redis.eviction-channel=sharded` | `cocache.redis.channel-mode=sharded` |
| `cocache.load.*` | `cocache.defaults.load-max-concurrent` / `load-acquire-timeout` |
| actuator `cocacheClient` | removed; use `cocache` |
| `JoinCache` (`me.ahoo.cache.api.join`) | `me.ahoo.cocache.api.join.JoinCache`: a read-only view of two caches, registered with `@EnableCoCache(joinCaches = […])`; `evict` → `invalidate`, no `set` (see [JoinCache](./join-cache.md)) |
| Jedis, pluggable L2, explicit negative-entry writes | removed |

Removed 5.x property keys fail startup with a message that names the replacement, if there is one. Some renames change meaning (for example, L2 is now always bounded by `localMaxAge`, 5 minutes by default): see [Behavior changes](./migration-6.md#semantics).

### Dependencies

| Dependency | Version |
|------------|---------|
| JDK (minimum) | 17 (tested on 17 and 25) |
| Spring Boot | 4.1.1 |
| Kotlin | 2.4.21 (artifacts target Kotlin API and language level 2.2) |
| Lettuce | 7.5.2.RELEASE |
| Caffeine | 3.2.4 |
| Jackson | 3.1.5 (`tools.jackson`) |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.0.0")
```

## v5.4.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive. The default eviction channel and the Redis wire format are unchanged, so 5.3.x and 5.4 instances can share Redis. The new channel is off by default.

### Sharded Pub/Sub eviction channel

```yaml
cocache:
  redis:
    eviction-channel: sharded   # default: pubsub
```

In Redis Cluster, a plain `PUBLISH` reaches every node. `SPUBLISH`/`SSUBSCRIBE` (Redis 7+) stays within the shard that owns the channel's slot, so the cost doesn't grow with the cluster.

- It requires Lettuce. `ShardedRedisCacheEvictedEventBus` uses Lettuce directly, because Spring Data Redis doesn't support Sharded Pub/Sub yet.
- Channel names and messages are unchanged. Resubscribing after a reconnect still clears L2.
- **The two channels don't interoperate, so switch every instance at once.**
- Failed publishes on either channel count in `cocache.eviction.publish.failures` (new interface `EvictionPublishStats`).

### Evicting from outside CoCache

The [operations guide](../operations/troubleshooting.md#external-invalidation) now documents the eviction protocol: the storage key, the channel, and the `key@@publisherId` message. It includes `redis-cli` and CDC-consumer examples. A writer that bypasses CoCache runs `DEL` on the storage key, then `PUBLISH`.

### Redis Cluster tests

A new CI job, "Test (Redis Cluster)", also runs the Redis, Spring Cache, and starter tests on a 3-master, 3-replica cluster. Start the same cluster locally with `.github/scripts/start-redis-cluster.sh`.

### TCK

The two concurrent cases in `SetElementsCacheSpec` now assert the L1 contents (the authoritative copy) immediately, and poll the per-instance reads with a timeout. Before this change they could fail intermittently where eviction events are slower, such as on Redis Cluster.

### Design documents

- [RFC 0001](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0001-l1-write-back-lease.md): L1 write-back leases for 6.0, which close the cross-instance stale write-back window.
- [RFC 0002](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0002-resp3-client-tracking.md): the RESP3 client tracking spike result (not adopted for 6.0).

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.4.0")
```

## v5.3.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive (every new interface method has a default). Redis wire format and eviction messages are unchanged, so 5.2.x and 5.3 instances can share Redis. New behaviors are off by default.

### Batch reads: `getAll` / `getCaches` / `loadCacheValues`

Reading N keys no longer costs N Redis round trips and N source loads:

```kotlin
val users: Map<String, User> = userCache.getAll(ids)
```

- CoCache reads L2 first, then the remaining keys from Redis in one pipeline, then loads the rest with one `CacheSource.loadCacheValues(keys)` call. Keys missing from that result become negative entries. Reading 100 keys from L1 drops from about 13.6 ms to about 1 ms (JMH).
- Every fill and write-back is stamp-guarded per key, as in single-key reads.
- `CacheSource.loadCacheValues` calls `loadCacheValue` per key by default. Override it with one query, such as `findAllById`.
- `SimpleJoinCache` joins with two batch reads.
- New API, all with default implementations: `CacheGetter.getCaches` / `getAll`, `CacheStore.getCaches`, `CacheSource.loadCacheValues`, and `CodecExecutor.executeAndDecodeAll`.

### Refresh ahead: `@CoCache.refreshAhead`

```kotlin
@CoCache(ttl = 600, refreshAhead = 60)
interface UserCache : Cache<String, User>
```

When a value has at most `refreshAhead` seconds left, reads still return it, and one background refresh per key per instance reloads it. Hot keys no longer stall on expiry. Expired values are never served, and negative entries are not refreshed. The default `0` turns it off. Meter: `cocache.refreshes`.

### Source load limit

```yaml
cocache:
  load:
    max-concurrent: 32
    acquire-timeout: 1s
```

While Redis is down, every L2 miss goes to the source. With a limit set, each cache runs at most `max-concurrent` source loads at once. A load beyond the limit waits `acquire-timeout`, then fails with `SourceLoadRejectedException`. Meter: `cocache.load.rejections`. Unlimited by default.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.3.0")
```

## v5.2.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive. Redis wire format and eviction messages are unchanged, so 5.1.x and 5.2 instances can share Redis.

### Bounded L2 staleness: `@CoCache.clientSideTtl`

Eviction events travel over Redis Pub/Sub, which delivers at most once. When an event is lost, other instances keep serving their L2 copy until the value's `ttl` (3600 s by default).

```kotlin
@CoCache(clientSideTtl = 30)
interface UserCache : Cache<String, User>
```

- An L2 copy lives at most `clientSideTtl` seconds, then refills from Redis. L1 keeps the full TTL.
- The default `0` means unlimited, the same as 5.1.
- The cap is computed only when writing L2, so the hit path costs nothing extra. Each L2 expiry costs one Redis round trip.
- When set, an L2 hit reports the L2 copy's expiry from `getTtlAt`.

### Micrometer metrics

With Micrometer on the classpath (`spring-boot-starter-actuator` brings it), the starter binds `cocache.*` meters tagged by `cache`: `hits{level=l2|l1}`, `misses`, `loads` (timer), `load.failures`, `filtered`, `stale.discards`, `evictions.received`, `resets`, `l2.size`, plus the untagged `eviction.publish.failures`. See [Operations](../operations/observability.md#metrics).

- The orchestration layer accumulates the counts in `LongAdder`s. Read them directly from `CoherentCache.stats`.
- The L2 hit counter (`hits{level=l2}`) is off by default. Turn it on with `cocache.metrics.client-side-hits=true`. Counting on the hottest path costs 8–15% of L2 hit throughput (JMH, 1–8 threads). No other meter touches the hit path.
- `RedisCacheEvictedEventBus.publishFailureCount` counts failed publishes.

### CosId dependency removed

`cocache-core` used CosId only to build the clientId. It now uses the JDK instead.

- `cosid-core` is no longer a transitive dependency. Projects that use CosId directly must declare it.
- `CosIdHostAddressSupplierAutoConfiguration` is removed, so a CosId `HostAddressSupplier` bean no longer feeds the clientId's host. The random suffix makes the id unique; the host is only for readability.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.2.0")
```

## v5.1.1

**Module Group:** `me.ahoo.cocache` · Patch release, backward compatible with 5.1.0. Redis wire format and eviction messages are unchanged.

- **Client ids are unique across instances.** `HostClientIdGenerator` used `{counter}:{pid}@{host}` as the clientId, but host + pid is not unique: two containers on the same machine with host networking share an IP and can both run as pid 1. Subscribers ignore eviction events whose publisherId equals their own clientId, so such instances dropped each other's evictions and served stale L2 copies until TTL. The clientId is now `{counter}:{pid}:{random suffix}@{host}`, and the `ClientIdGenerator` contract now requires ids to be unique across instances.
- Dependencies: CosId 3.4.1, fluent-assert 1.2.1.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.1.1")
```

## v5.1.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive. Redis wire format and eviction messages are unchanged, so 5.0.x and 5.1 instances can share Redis.

### Atomic element updates for Set-valued caches

Concurrent "read the whole set → modify → write the whole set" updates lose writes across instances. A cache interface can now also declare `SetElementsCache<K, E>`, and each update becomes one atomic L1 operation:

```kotlin
@CoCache(ttl = TtlAt.FOREVER)
interface PolicyIndexCache : Cache<String, Set<String>>, SetElementsCache<String, String>

policyIndexCache.addElements(key, listOf(policyId))
policyIndexCache.removeElements(key, listOf(policyId))
```

- **Wiring**: the distributed cache must implement the new `SetElementsStore` SPI. For Redis, use `RedisSetDistributedCache(redisTemplate)` (native Redis Set, same layout as `SetToSetCodecExecutor`). `InMemoryDistributedCache` supports it as well. If a proxy declares `SetElementsCache` over a store that can't support it, creating the proxy fails with a message that names the fix.
- **Semantics**: L1 is the authoritative copy, and these operations never call the `CacheSource`. Adding to a negative cache replaces it, so the sentinel never ends up in the set. After every update, the key's expiry follows the cache's `TtlPolicy` (`TtlAt.FOREVER` → no expiry, even when the key previously held the 60 s negative cache). Removing from an absent key or a negative cache does nothing. Removing the last element deletes the key.
- **Coherence**: invalidation stamp → atomic L1 update (one Lua round trip) → L2 evict → eviction event. Read-your-writes and in-flight-load protection match `setCache`.
- **Failures always surface**: element updates are explicit writes, so an L1 failure always throws, even with `strictFailure = false`. The update may still have been applied, for example when the response timed out, so the local copy is evicted and the eviction is published anyway. Both operations are idempotent, so callers can retry.
- **Write-back safety**: on stores that implement `SetElementsStore`, a source load's write-back is now conditional: it writes only when the key is absent or holds a negative cache. Its undo is now compare-and-delete. Before this, a load that started before an `addElements` (for example, writing the negative cache) could overwrite or delete the added elements. Because `InMemoryDistributedCache` implements the SPI, it uses the conditional write-back for every value type.
- `TtlPolicy.valueTtlAt()` exposes the jittered value expiry. `RedisDistributedCache` is now `open`.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.1.0")
```

## v5.0.1

**Module Group:** `me.ahoo.cocache` · Patch release, backward compatible with 5.0.0. Redis wire format unchanged.

- **Corrupted payloads are no longer deleted on read.** A payload that fails to decode is still a miss. The coherent cache's reload now overwrites it through the stamp-guarded write-back. Previously the read issued an unconditional `DEL`, which could remove a valid value that another instance had just written.
- **`cocache-spring-boot-starter` drops the `actuator-support` / `cloud-support` Gradle capabilities.** Actuator is now `compileOnly`, and the endpoints still activate only when the application adds `spring-boot-starter-actuator`. The POM no longer lists actuator as an optional dependency, and nothing changes for Maven or Gradle consumers who don't request those capabilities.
- Build: ready for Gradle 10 (no deprecated APIs in the build scripts) and Kotlin's `-jvm-default=enable`, with the same binary output as `-Xjvm-default=all-compatibility`. The test suite also runs on JDK 25.
- Docs site: fixed three sequence diagrams that failed to render, and patched all Dependabot alerts in the docs toolchain.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.0.1")
```

## v5.0.0

**Module Group:** `me.ahoo.cocache`

5.0 rebuilds the coherence core around three first-principles goals — bounded staleness, no lost invalidations, and a cheap hit path. It is a breaking release with no compatibility bridges. **Redis storage layout and the eviction message format are unchanged**, so 4.x and 5.0 instances can share Redis during a rolling upgrade.

### Correctness Fixes

- **L1 miss is never a negative cache** — Redis reads are one atomic round trip (a Lua script returns TTL + value). An absent key, a key deleted mid-read, or a corrupted payload is a miss that reloads from the source. Previously a concurrent eviction between the `TTL` and `GET` calls was decoded as a negative cache and pinned into L2 until the old TTL (forever by default).
- **Local writes invalidate in-flight loads** — `evict`/`setCache` on the same instance now discard a concurrent source load's stale write-back (previously only remote events did). L1 → L2 fills are guarded the same way.
- **Original exceptions from cache proxies** — proxies unwrap `InvocationTargetException`; callers no longer receive `UndeclaredThrowableException`.
- **Bounded staleness by default** — default `ttl` is 3600 s (was forever); the negative cache has its own `missingTtl` (default 60 s, was the value TTL); default L2 is a bounded Caffeine cache (10 000 entries); every (re)subscription of the eviction channel clears L2 so lost pub/sub messages cannot leave L2 stale.
- **Read-your-writes** — a `get` after the same thread's `evict`/`setCache` never returns the result of a load that started before that invalidation; it reloads once.
- **Spring Cache** — `get(key, valueLoader)` loads once per key (`@Cacheable(sync = true)` semantics); `retrieve` runs on a dedicated executor instead of the common ForkJoin pool.
- **Stateful components are never shared** — `ClientSideCache`, `DistributedCache` and `KeyConverter` beans resolve by name only; previously a bean matched by generic type could be shared by every cache with the same value type.
- **JoinCache.evict(key) no longer loads** — it evicts only the first cache.

### Architecture

- `cocache-api` owns all SPI: `CacheStore` → `ClientSideCache` / `DistributedCache`, `CacheEvictedEventBus` / `CacheEvictedSubscriber` / `CacheEvictedEvent`, `KeyConverter`, `KeyFilter`, `CacheSource`.
- `CacheValue` is sealed: `PresentValue(value, ttlAt)` | `MissingValue(ttlAt)`.
- Storage tiers are pure stores; TTL policy (`TtlPolicy`) lives in the orchestration layer.
- `DefaultCoherentCache` coalesces loads per key with `SingleFlight` (no striped-lock collisions) and guards write-backs with `InvalidationStamps`.
- One `CacheInvocationHandler` serves CoCache and JoinCache proxies.

### Performance

JMH, 4.3.0 vs 5.0.0, same machine and Redis (ops/s):

| Benchmark | 4.3.0 · 1 thread | 5.0.0 · 1 thread | 4.3.0 · 8 threads | 5.0.0 · 8 threads |
|---|---:|---:|---:|---:|
| L2 hit | 31.1M | 32.6M | 217.5M | 235.9M |
| L1 read | 3,188 | 6,144 | 5,438 | 10,758 |
| Miss + load | 1,487 | 3,082 | 2,467 | 5,173 |
| Set | 2,779 | 2,804 | 4,714 | 4,694 |

L1 reads and miss-loads are about 2× faster, because one Lua round trip replaces `TTL` + `GET`. L2 hits still scale with threads: plain bounded Caffeine checks expiry on read against the cached `CacheClock`. The benchmark ships as `RedisCacheBenchmark` (`./gradlew :cocache-spring-redis:jmh`).

### Migration from 4.x

| 4.x | 5.0 |
|-----|-----|
| `me.ahoo.cache.DefaultCacheValue(value, ttlAt)` / `.forever(v)` / `.missingGuard(...)` | `CacheValue.of(value, ttlAt)` / `CacheValue.forever(v)` / `CacheValue.missing(ttlAt)` |
| `cacheValue.isMissingGuard` | `cacheValue.isMissing` (or `is MissingValue`) |
| `ComputedTtlAt.at(ttl)`, `ComputedTtlAt.FOREVER`, `CacheSecondClock.INSTANCE.currentTime()` | `TtlAt.at(ttl)`, `TtlAt.FOREVER`, `TtlAt.currentTime()` |
| `ClientSideCache` / `DistributedCache` extended `Cache` (`cache[key] = v`) | They are `CacheStore`s: `getCache` / `setCache` / `evict` |
| `me.ahoo.cache.distributed.DistributedCache`, `me.ahoo.cache.consistency.CacheEvicted*`, `me.ahoo.cache.converter.KeyConverter`, `me.ahoo.cache.KeyFilter` | `me.ahoo.cache.api.distributed.DistributedCache`, `me.ahoo.cache.api.consistency.CacheEvicted*`, `me.ahoo.cache.api.converter.KeyConverter`, `me.ahoo.cache.api.filter.KeyFilter` |
| `@GuavaCache`, `GuavaClientSideCache`, `GuavaCacheEvictedEventBus` | `@CaffeineCache`, `CaffeineClientSideCache.build(...)`, `LocalCacheEvictedEventBus` |
| `MockDistributedCache` | `InMemoryDistributedCache` |
| `CoherentCache.clientSideCache` / `.distributedCache` / ... | `CoherentCache.configuration.clientSideCache` / ... |
| Custom `CacheEvictedSubscriber` | Implement `onReset()` (clear local copies) |
| Custom `CodecExecutor` | `executeAndDecode(key)` reads value and TTL itself; extend `StringCodecExecutor` / `HashCodecExecutor` or `AbstractCodecExecutor` |
| `ClientSideCache<User>` bean picked by type | Name it `UserCache.ClientSideCache` |
| `TtlConfiguration`, `TtlConfigurationAware`, `ComputedCache` | Removed — TTL comes from `@CoCache` (`TtlPolicy`) |
| `@CoCache` without `ttl` meant "forever" | Now 3600 s — set `ttl = TtlAt.FOREVER` explicitly to keep the old behavior |
| `JoinCache.evict(key)` evicted both caches | Evicts the first cache only; use `evict(firstKey, joinKey)` for both |
| A `CacheSource` reading the same key from the same cache re-entered the lock | Fails fast with `IllegalStateException` (it previously over-evicted) |

### Dependencies

| Dependency | Version |
|------------|---------|
| Spring Boot | 4.1.1 |
| CosId | 3.2.1 |
| Guava | 33.7.2-jre |
| Kotlin | 2.4.21 |
| JUnit | 6.1.3 |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.0.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>5.0.0</version>
</dependency>
```

## v4.3.0

**Module Group:** `me.ahoo.cocache`

### Highlights

- **Cache Breakdown Protection Hardened** — Replaced the per-key lock map (which had a lock-object recycling race allowing duplicate source loads) with Guava `Striped` locks in `DefaultCoherentCache` (#519).
- **Clock Thread Startup Race Fixed** — `CacheSecondClock` timer thread startup no longer races with field initialization; eliminates the "frozen clock → caches never expire" failure mode (#519).
- **Stale Write-Back Race Fixed** — A per-key invalidation generation counter makes in-flight loads detect eviction events that arrive during loading: stale results are discarded (or compensating-evicted) instead of being pinned into both cache tiers until TTL (#519).
- **Lifecycle Management** — `CoherentCache` now extends `AutoCloseable` with an idempotent `close()` (unregister from event bus + close distributed cache); Spring destroys caches automatically at shutdown (#519).
- **Corrupted Payload Self-Healing** — Undecodable Redis values are deleted and treated as a cache miss (source reload) instead of throwing on every read (#520).
- **Null Value Normalization** — `null` values are consistently written as negative-cache sentinels across all codecs (previously: empty-string corruption, NPEs, or codec-dependent semantics) (#520).
- **Redis Failure Degradation** — By default, Redis read failures now degrade to cache-miss semantics (source reload, business calls unaffected) and write/evict failures only log a warning. Set `cocache.redis.strict-failure=true` to restore the strict throwing behavior (#520).
- **Atomic Hash/Set Writes** — Structural codec writes use single-key Lua scripts (`DEL` + `HSET`/`SADD` + `EXPIRE`), eliminating concurrent-write field-union corruption. Empty Map/Set writes now silently evict the key instead of throwing (#520).
- **Configurable Missing-Guard Sentinel** — New property `cocache.redis.missing-guard-sentinel` mitigates collisions between business data and the `"_nil_"` sentinel (#520).

### Breaking Changes & Behavior Notes

- **API removal (the only breaking change)**: `AbstractCodecExecutor.setPipelined`/`serialize` members were removed — affects only third-party codecs directly extending this abstract class.
- `CodecExecutor.executeAndDecode` return type is now nullable (`CacheValue<V>?`) — covariant-compatible for implementers; direct callers must handle `null` on recompile.
- Redis failures degrade by default (previously threw); JSON codec `null` values become negative-cache sentinels instead of `"null"`-literal round-trips.
- Spring containers now auto-close caches at shutdown; `FactoryBean.getObject()` returns a memoized instance (eliminates duplicate event-bus subscriptions).
- `strict-failure` / `missing-guard-sentinel` apply only to auto-configured (fallback-created) caches; custom `DistributedCache` beans are unaffected.
- Wire formats (storage structure, eviction messages) are unchanged — rolling upgrades are fully compatible.

### Dependencies

| Dependency | Version |
|------------|---------|
| Spring Boot | 4.1.0 |
| CosId | 3.2.0 |
| Guava | 33.6.0-jre |
| Kotlin | 2.4.10 |
| JUnit | 6.1.3 |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:4.3.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>4.3.0</version>
</dependency>
```

## v4.2.0

**Module Group:** `me.ahoo.cocache`

### Dependencies

| Dependency | Version |
|------------|---------|
| Spring Boot | 4.1.0 |
| CosId | 3.2.0 |
| Guava | 33.6.0-jre |
| Kotlin | 2.4.0 |
| JUnit | 6.1.1 |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:4.2.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>4.2.0</version>
</dependency>
```

## Historical Releases

For the full release history, see [GitHub Releases](https://github.com/Ahoo-Wang/CoCache/releases).
