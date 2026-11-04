---
title: Upgrading from 6.x
description: Move an application from CoCache 6.x to 8.0 in one cutover — stop 6.x, delete the keys of caches without a source, start 8.x — and map 6.x types, configuration keys, Redis ACLs and the backend SPI to 8.0.
---

# Upgrading from 6.x

CoCache 8.0 is a clean cutover from 6.x, not a compatible upgrade:

- **No coexistence.** 8.x doesn't read or write 6.x values, and 6.x and 8.x instances can't share a cache. Upgrade every application that uses a namespace at once.
- **No migration tool.** Nothing copies 6.x data into 8.x. Caches with a source rebuild from it; data whose only copy is Redis doesn't survive the upgrade unless your application writes it again.
- **No rollback tooling.** Going back to 6.x is the same cutover in reverse, done by hand ([below](#rollback)).

8.0 needs JDK 21 or newer, Spring Boot 4, and Redis 7.0 or newer. The [changelog](./changelog.md#unreleased) lists every breaking change in detail. This page is the map and the procedure.

## Before the cutover {#prepare}

1. **Choose each cache's type.** 6.x had one `Cache` with or without a loader; 8.x has five types, picked by one question: if Redis loses a key, can this process rebuild it on demand? Use the [type map](#types) below, and [Choosing a type](./caches.md#types) when in doubt. A wrong choice is the most serious upgrade mistake: the only copy declared as a `Cache` expires after an hour by default.
2. **Update the code.** Most API changes are compile errors that point at the fix ([API](#api)). A few changes compile and behave differently: read [those](#behavior) carefully.
3. **Rewrite the configuration.** Several keys changed meaning or name, and Spring Boot ignores the old ones without a warning ([configuration keys](#configuration)).
4. **Update the Redis ACL** before the first 8.x instance starts ([ACL](#acl)).
5. **Plan the cold start.** After the cutover every derived cache is empty, and readers load from their sources together. Start instances in batches that your sources can absorb.
6. **Export what must survive.** Data that only Redis holds (a 6.x `Cache` without a loader, a `SetCache`) is deleted in step 2 of the cutover. If you need it afterwards, export it before, and write it again after, or give the cache a source.

## The cutover {#steps}

1. **Stop every 6.x writer and reader of the namespaces.** That includes the processes that aren't services: CLIs, scheduled maintenance jobs, control-plane publishers, reporting agents.
2. **Delete the old keys of caches without a source**: every 6.x `Cache` without a loader (an 8.x `Store`, `VersionedStore` or externally sourced `Cache`) and every `SetCache` (an 8.x `SetStore`). 8.x reads a 6.x value as content it can't read, and a cache without a source fails such a read instead of guessing. For one namespace with the default `cocache:` prefix (use your own prefix otherwise), on each master in a cluster:

   ```bash
   redis-cli --scan --pattern 'cocache:{namespace}:*' | xargs redis-cli unlink
   ```

   Caches with a source may keep their keys: a read reclaims an old value and reloads it from the source. Deleting them anyway makes the cold start predictable.
3. **Start 8.x.** Caches with a source reload on demand. Caches without one start empty: fill them again the way they were filled in the first place (the control plane republishes, reporting agents report again).

### Rolling back {#rollback}

There is no rollback tool. To return to 6.x: stop every 8.x instance, delete the keys 8.x wrote (6.x can't read format 2 either, and would load around those values on every miss without refilling Redis), and start 6.x. Caches with a source rebuild; data written only to 8.x caches without a source is lost the same way, unless you export it first.

## Types {#types}

| 6.x | 8.x |
|-----|-----|
| `Cache` with a loader | [`Cache`](./cache.md), unchanged |
| `Cache` without a loader, rebuildable by its callers: Spring Cache, a per-call loader, a caller's `put` | `Cache` with `externallySourced()`; on an interface, `@CoCache(source = CoCache.Source.EXTERNAL)` ([externally sourced](./cache.md#externally-sourced)) |
| `Cache` without a loader, whose only copy is Redis | [`Store`](./store.md): `invalidate` becomes `delete`; a cache that must read "absent" while Redis is down declares `unknownRead = ABSENT` |
| `CacheSpec.versioned` with a versioned loader | [`VersionedCache`](./versioned-cache.md), `versionedCacheSpec`: operator deletes become `evict` (keeps the version) or `purge` (drops it) |
| `CacheSpec.versioned` without a loader | [`VersionedStore`](./versioned-store.md), `versionedStoreSpec`: `invalidateIfNewer` becomes `deleteIfNewer` |
| `SetCache`, `SetCacheSpec` | [`SetStore`](./set-store.md), `SetStoreSpec`, `setStoreSpec`: `invalidate` becomes `clear` |
| `JoinCache` | [`JoinView`](./join-cache.md) when it's only read; `JoinCache` when you call its `invalidate` (both sides must then be `Cache`s) |
| `AsyncCache`, `AsyncSetCache` | One async view per type: `AsyncCache`, `AsyncVersionedCache`, `AsyncStore`, `AsyncVersionedStore`, `AsyncSetStore` |
| `cocache-coroutines`: `SuspendingCache`, the `suspending()` extension | `suspending()` on every type, views in `cocache-api`; drop the module and the `me.ahoo.cocache.coroutines` imports. `suspendingLoader` and `suspendingVersionedLoader` are in `me.ahoo.cocache.core` |
| A library that assembles caches outside the starter, or `CacheProxies.create` | [`CoCacheFactory`](./caches.md#factory): `create(spec)`, `create(type, spec)`, with `lock` and `constrain` for the fields the library depends on |
| A third-party backend | `L1Store` and the capability accessors, passing the new TCK ([SPI](#spi)) |

What changes for a 6.x `Cache` without a loader, by the 8.x type it becomes:

| | 6.x `Cache` without a loader | 8.x `Cache` with `externallySourced()` | 8.x `Store` |
|-|------------------------------|------------------------------------|-------------|
| Redis read fails | Throws (6.9+) | Throws `CacheReadException`; `unknownRead = ABSENT` reads absent, caches nothing | Same |
| `invalidate` / `delete` fails | Throws (6.9+) | Retried in the background | Throws, never retried |
| Key not in Redis | Absent, kept in L2 | Unfilled: absent without a loader, loaded by a per-call loader | Absent, kept in L2 |
| Default `ttl` | 1 h | 1 h | Never expires |
| Default `local-max-age` / `absent-ttl` | 5 min / 1 min | 5 min / 1 min | 30 s / 10 s |

## API {#api}

Most of these fail to compile, which shows you where to change the code.

| 6.x | 8.x |
|-----|-----|
| `CacheRuntime.create(CacheSpec)` returns `Cache` for every kind of cache | One `create` per spec type, returning that type; `cache(name)` returns `ReadableCache` |
| `Lookup` is `Present` or `Absent` | A third case, `Unknown(error)`: Redis failed and the read had no source. Switch exhaustively; `valueOrThrow()` throws its `CacheReadException` ([reading](./caches.md#unknown)) |
| `getAll` throws on a failed read | `getAll` completes the other keys first, then throws `CacheReadException` with `unknownKeys` and `partialResult`; `lookupAll` returns a `Lookup` per key |
| `CachePolicy.DEFAULT`, `StoreSpec.DEFAULT_POLICY` and the other spec constants | `DefaultPolicies.CACHE`, `VERSIONED_CACHE`, `STORE`, `VERSIONED_STORE`, `SET_STORE` |
| `LoadPolicy.asyncTimeout` | `LoadPolicy.timeout` |
| `LoadPolicy(maxConcurrent = 0)` for unlimited | `maxConcurrent = null`, or `unlimited()` inside `policy { … }` |
| `CachePolicy.foreignFormat`, `ForeignFormatMode` | Removed: a read with a source reclaims content it can't read, a read without one fails |
| `Digest.sha1` | `Digest` is the SHA-256 of the payload and names its algorithm; 6.x digests never match |
| `SourceLoadCancelledException` in `me.ahoo.cocache.coroutines` | In `me.ahoo.cocache.core`, for every kind of loader |
| `CacheSpecCustomizer.customize` for `CacheSpec.Builder` and `SetCacheSpec.Builder` | One overload per spec type (`CacheSpec`, `VersionedCacheSpec`, `StoreSpec`, `VersionedStoreSpec`, `SetStoreSpec`); a customizer can't change the data source or a locked field |
| `CoCacheSpringCacheManager(runtime, executor)` | `CoCacheSpringCacheManager(runtime)` |
| `CacheRuntime.Builder.asyncExecutor(…)`, `blockingExecutor(…)`; `CacheRuntime.blockingExecutor` | `CacheRuntime.Builder.executor(…)`; `CacheRuntime.executor` (one executor, [async](./async.md)) |
| `LoadObserver.observeAsync` | Removed: `observe` also sees async and suspending loads ([observability](../operations/observability.md)) |
| Spring's `TransactionAwareCacheManagerProxy` around CoCache's manager | `TransactionAwareSpringCacheManager` ([transactions](./spring-cache.md#transactions)) |

## Behavior that changes without a compile error {#behavior}

- **`cocache.defaults.*` sits below the declaration now**, under `cocache.defaults.cache.*` or `cocache.defaults.store.*`. The old `cocache.defaults.<field>` overrode every declaration; Spring Boot now ignores it without a warning. To force a value over declarations, use `cocache.overrides.*` (it only tightens) or `cocache.caches.[name].*` ([configuration keys](#configuration)).
- **A `Cache` interface without a matching loader fails startup** unless it declares `source = EXTERNAL`. In 6.x it started without a source.
- **`lookup` returns `Unknown` instead of throwing.** With `read-failure: throw` (and on a cache without a loader in 6.9+), a failed read made `lookup` throw `CacheReadException`. Now it returns `Lookup.Unknown`. Code that relied on the exception to fail closed, and treats any non-`Present` result as "absent" (`as? Lookup.Present`, an `else` branch), now fails open.
- **An outcome-unknown `Cache.put` doesn't throw** under the default `invalidate-failure: retry`: it queues an invalidation of the keys instead ([compensated puts](./cache.md#failures)).
- **Synchronous followers stop waiting after `load-timeout`** (30 s): a reader waiting for another caller's load fails with `SourceLoadTimeoutException`. In 6.x it waited indefinitely.
- **Versioned caches refresh ahead.** A global `refresh-window` now applies to a `VersionedCache`; write `refreshWindow = off()` on the declaration when the writer pushes every version.
- **Authoritative types keep their L2 copies for less time**: `local-max-age` 30 s and `absent-ttl` 10 s by default.
- **A per-call suspending loader runs inside the load**: it starts on the caller's thread, continues on CoCache's executor, and `load-timeout` cancels it ([coroutines](./coroutines.md#loader)).
- **Spring transactions watch loads.** A rollback invalidates what the transaction loaded, and a key evicted in the transaction loads privately there. `invalidate-fills-on-rollback: false` and `fill-in-transaction: allow` under `cocache.defaults.cache` (or per cache under `cocache.caches.[name]`) turn both off ([loads inside a transaction](./spring-cache.md#transactional-loads)).
- **Spring Cache reads fail on `Unknown`.** A non-sync `@Cacheable` and `retrieve(key)` on an externally sourced cache throw `CacheReadException` to the `CacheErrorHandler` when Redis fails; `cocache.spring-cache.degrade-on-error: true` lets them run the method instead ([when Redis fails](./spring-cache.md#failures)). A cache operation that names a cache other than a `Cache` fails startup.
- **The endpoint won't delete an only copy by default.** A per-key `DELETE` on a `Store`, `SetStore` or `VersionedStore` returns `403` until `management.endpoint.cocache.allow-store-delete` or `allow-purge` is set. On a `VersionedCache` it is an `evict`.
- **An interrupted blocking call waits for its Redis command** (at most one `command-timeout`) instead of failing at once, and keeps its interrupt flag.
- **An unreadable invalidation frame resets** the caches on that channel instead of being dropped.
- **Async and suspending views always resume on `cocache.executor`**, never in place on the thread that completed the previous step; with the default executor, each resume is a new virtual thread.

## Configuration keys {#configuration}

| 6.x | 8.x | Note |
|-----|-----|------|
| `cocache.defaults.<field>` (`cocache.defaults.ttl`, …) | `cocache.defaults.cache.<field>` (`Cache`, `VersionedCache`) or `cocache.defaults.store.<field>` (`Store`, `VersionedStore`, `SetStore`) | **Meaning changed**: below the declaration, filling only fields the declaration didn't write. `defaults.store.ttl` isn't allowed |
| — | `cocache.overrides.cache.*`, `cocache.overrides.store.*` | New: above the declaration, staleness bounds and `read-failure` only, and only tightening |
| `cocache.caches.[name].*` | Unchanged, plus `unknown-read`, `read-timeout`, `tombstone-ttl`, `unlock` | An entry that names no cache, or sets a key the cache's type doesn't have, fails startup; `cocache.caches-validation: warn` only warns |
| `cocache.defaults.key-prefix` | `cocache.key-prefix` | Outside the layering; configuration still wins over the declaration |
| `ttl-forever: true` | `ttl: forever` | |
| A bare number: seconds under `cocache.defaults` and `cocache.caches` (`ttl: 600`), milliseconds under `cocache.redis` (`command-timeout: 2000`) | `ttl: 10m`, `command-timeout: 2s` | Every duration needs a unit; a bare number fails startup ([durations](./configuration.md#durations)). Annotations stay in seconds |
| `refresh-window: 0` | `refresh-window: off` (or `0s`) | |
| `load-max-concurrent: 0` (unlimited) | `load-max-concurrent: unlimited` | `0` fails startup |
| `load-async-timeout` | `load-timeout` | Also bounds synchronous followers |
| `foreign-format` | Removed | |
| `cocache.redis.set-cache-eviction-check` | `cocache.redis.store-eviction-check` | Covers every authoritative type. `allkeys-*`, and a policy that can't be read, now always fail startup with an authoritative cache; the setting decides only `volatile-*` with a finite `ttl` |
| — | `cocache.redis.assume-maxmemory-policy` | New: the policy to assume when `INFO memory` can't be read |
| `cocache.spring-cache.retrieve-executor` | Removed | `retrieve` uses the async view |
| `cocache.async-executor`, `cocache.blocking-executor` | `cocache.executor` | One executor for every step of the async and suspending views and their blocking loaders; it must tolerate blocking. Unset: one virtual thread per task |
| — | `invalidate-fills-on-rollback`, `fill-in-transaction` (under `cocache.defaults.cache`, `cocache.overrides.cache` and `cocache.caches.[name]`), `cocache.spring-cache.degrade-on-error` | New Spring settings |
| — | `management.endpoint.cocache.allow-store-delete`, `management.endpoint.cocache.allow-purge` | New endpoint switches, off by default |

The [Configuration Reference](./configuration.md) describes every 8.x key, and [effective values and sources](./configuration.md#policy-report) shows, per cache, where each value came from: check it after the upgrade.

## Redis ACL {#acl}

| | 6.x | 8.x |
|-|-----|-----|
| Channels | `&cocache:inv:*`, whatever the key prefix | `&{keyPrefix}inv:*` for each prefix (`&cocache:inv:*` with the default prefix) |
| Atomic-broadcast probe (`pubsub` mode) | key and channel `cocache:probe:*` | key `cocache:!probe:*` (covered by `~cocache:*`) and channel `&cocache:!probe:*` |
| Commands inside scripts | … `HGET`, `HEXISTS` … | Add `+getrange +strlen +hmget`; `HGET` and `HEXISTS` are no longer used |
| Empty `keyPrefix` | — | Keys and channels share no prefix: list each namespace (`~{namespace}:*`, `&inv:{namespace}`), plus `~!*` |

Creating a cache now publishes one empty frame to its channel, so a missing channel permission fails at startup, naming the pattern to allow. The complete list is in [Redis ACL](../operations/index.md#acl).

## Keys and values in Redis {#wire-format}

- Storage keys keep the shape `{keyPrefix}{namespace}:{encodedKey}`. Internal keys move under `{keyPrefix}!`.
- The invalidation channel is `{keyPrefix}inv:{namespace}` (6.x: `cocache:inv:{namespace}`), so applications with different prefixes no longer hear each other.
- A namespace must match `[A-Za-z0-9._-]+`. One with `:` fails at creation, and the message shows how to move the part before the `:` into `keyPrefix`; the storage keys stay the same strings.
- Every value is wire format 2. A `Store` value carries a writer id and a SHA-256 digest (60 more bytes), a `VersionedStore` value a writer id and its version ([sizing](../operations/index.md#sizing-redis)).

## Backend SPI and TCK {#spi}

For authors of a `CoherenceBackend` other than the shipped Redis and in-memory ones ([Extending CoCache](../architecture/extending.md)):

| 6.x | 8.x |
|-----|-----|
| `RemoteStore` (blocking), `AsyncRemoteStore` | `L1Store`, whose operations are `suspend`; a Java backend implements `FutureL1Store` and returns `FutureL1Store.adapt(…)` |
| `AsyncInvalidationChannel` | `InvalidationChannel`, whose `publish` is `suspend`; Java: `FutureInvalidationChannel.adapt(…)` |
| `CoherenceBackend.versionedWrites`, optional methods with throwing defaults | Capability accessors `conditional()`, `versioned()` and `sets()`, returning `null` when unsupported |
| `store(namespace)`, `subscribe(namespace, …)`, `publish(namespace, …)` | Take a `Keyspace(keyPrefix, namespace)` |
| `RemoteRead`, `RemoteConditionalRead`, `RemoteConditionalWrite`, `RemoteVersionedWrite` | `L1Read`, `L1ConditionalRead`, `L1ConditionalWrite`, `L1VersionedWrite` |
| `fill` returns `Boolean`; `reclaim` takes a `Digest` | `fill` returns `L1Fill`; `reclaim` takes a `ContentFingerprint` |
| `addMembers` / `removeMembers` that overwrite unreadable content; the `foreignAsAbsent` parameter | `SetL1Store.addMembers` / `removeMembers`, which refuse it; the parameter is gone |
| — | New: `ConditionalL1Store.deleteIfMatch`, `VersionedL1Store.evictKeepingVersion`, a read `timeout`, `Envelope.Scalar.writer` and `digest`, `L1RejectedException` for commands never sent |
| `InvalidationListener.onInvalidated` receives storage keys | Receives encoded keys, the part after `{keyPrefix}{namespace}:` |
| TCK: `Async*Spec` suites, `EngineSpec.supportsConditionalWrites` | Removed: every suite declares `expectedCapabilities` and fails closed; `access = CacheAccess.ASYNC` reruns a suite through the async view ([TCK](../architecture/extending.md#tck)) |
