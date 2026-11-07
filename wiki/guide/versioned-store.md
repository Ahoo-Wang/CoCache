---
title: VersionedStore
description: The authoritative, versioned type — when to use VersionedStore, declaring it, putIfNewer and deleteIfNewer, tombstones and tombstone-ttl, purge, reads, defaults, and Redis failures.
---

# VersionedStore

A `VersionedStore<K, V>` holds the **only copy** of its values, in Redis, **with a version**. Use it for state whose writers must survive late, replayed or duplicated writes, and that has no source to reload from: the latest status reported by devices, the state a consumer projects from a stream it can't replay on demand. Every write carries a version, and Redis refuses any version that isn't newer than the one it holds, so a redelivered older event can't undo a newer one.

| | `VersionedStore<K, V>` |
|-|------------------------|
| Data comes from | Your versioned writes |
| Reads | `get`, `getAll`, `lookup`, `lookupAll`, `getVersioned` |
| Writes | `putIfNewer` |
| Deletes | `deleteIfNewer` (tombstone); `purge` drops the version |
| On a Redis read failure | `Lookup.Unknown`: `get` throws `CacheReadException`, unless `unknownRead = ABSENT` |
| On a Redis write failure | Throws `CacheWriteException`; retrying the same version is safe |
| Default lifetimes | `ttl` forever, `tombstone-ttl` 1 day, `local-max-age` 30 s, `absent-ttl` 10 s |
| Needs | A Redis that doesn't evict its keys ([eviction check](./configuration.md#eviction-check)) |

There is no `put`, `delete` or `putIfMatch`: no write can drop the version. A versioned copy that a source can rebuild is a [`VersionedCache`](./versioned-cache.md).

## Declare it {#declare}

::: code-group

```kotlin [Kotlin]
@CoCache
interface DeviceStatus : VersionedStore<String, Status>

// or as a spec bean
@Bean
fun deviceStatusSpec(mapper: ObjectMapper): VersionedStoreSpec<String, Status> =
    versionedStoreSpec("device-status") {
        codec = jacksonCodec<Status>(mapper)
        policy { tombstoneTtl = Duration.ofDays(7) }     // covers a week of redelivery
    }
```

```java [Java]
@CoCache
public interface DeviceStatus extends VersionedStore<String, Status> {}

@Bean
VersionedStoreSpec<String, Status> deviceStatusSpec(ObjectMapper mapper) {
    return VersionedStoreSpec.<String, Status>builder("device-status")
        .codec(JacksonCodec.of(mapper, Status.class))
        .updatePolicy(policy -> policy.tombstoneTtl(Duration.ofDays(7)))
        .build();
}
```

:::

A `VersionedStore` has no source: `@CoCache(loader)` or `source = EXTERNAL` on its interface fails startup. Creating one needs a backend that supports versioned writes; Redis and the in-memory backend do.

## Writing {#writing}

```kotlin
// the writer, for every event (also on replay and redelivery)
when (statuses.putIfNewer(deviceId, status, event.sequence)) {
    VersionedPut.Written -> Unit                  // newer: written and broadcast
    is VersionedPut.Stale -> Unit                 // Redis already holds this version or a newer one
}
statuses.deleteIfNewer(deviceId, event.sequence) // a tombstone that refuses older versions
statuses.getVersioned(deviceId)                  // Versioned(status, version), or null
```

- `putIfNewer` writes only a version greater than the one in Redis, in one script, and returns `Written`. An equal version (a duplicate) or an older one (a late delivery) returns `Stale(current)` and changes nothing.
- `deleteIfNewer` writes a **tombstone**: readers see the key as absent, and `putIfNewer` with that version or an older one returns `Stale`. A newer version can bring the key back.
- Each write carries a writer id in the value's header, as a `Store`'s does.
- Both writes take an optional lifetime: `putIfNewer(key, value, version, ttl)` and `deleteIfNewer(key, version, ttl)`. A `null` `ttl` means the policy's lifetime, as without it (`ttlOf` first; `tombstone-ttl` for a tombstone), so a writer whose lifetime is optional (`ttl: Duration?`) passes it straight through, with no branch.
- `invalidate` versus `delete` follows the type's role: a derived copy can be rebuilt from its source, so removing it is invalidating (`VersionedCache.invalidateIfNewer`); an authoritative copy can't, so removing it is deleting.

[Versioned writes](./freshness.md#versioned-writes) has the full rules: how versions compare, why an equal version still publishes the invalidation, and how long ordering holds.

## Tombstones and `purge` {#tombstones}

- **Tombstones always expire**, after `tombstone-ttl` (1 day by default) or the `ttl` argument of `deleteIfNewer`. Once one expires, a late older version can be written again, so give tombstones a lifetime that covers your redelivery window. A `VersionedStore` whose tombstones would never expire (`tombstone-ttl: forever`) fails to build: every deleted key would stay in Redis for good.
- **`purge(key)`** deletes the key and its version, so any version, even an older one arriving late, can be written afterwards. It throws on failure and is never retried. The actuator endpoint's per-key `DELETE` on a `VersionedStore` is a purge and needs `management.endpoint.cocache.allow-purge=true` ([invalidating a key](../operations/observability.md#invalidate-key)).
- `ttl` is forever by default, which `volatile-*` never evicts. A finite `ttl` on values is allowed; once a value expires, the key holds no version and any version can be written again.

## Reading {#reading}

A `VersionedStore` reads like a [`Store`](./store.md#reading): no load lease, a key Redis doesn't have (or a tombstone) is absent, and a Redis failure is [`Unknown`](./caches.md#unknown), which `unknownRead = ABSENT` turns into `null`. `getVersioned` returns the value with its version, or `null`.

## When Redis fails {#failures}

`putIfNewer`, `deleteIfNewer` and `purge` throw `CacheWriteException`. Retrying `putIfNewer` or `deleteIfNewer` with the same version is safe whatever the outcome: if the first attempt went through, the retry returns `Stale(version)` and publishes the invalidation again. Nothing is retried in the background.

## Defaults and durability {#defaults}

A `VersionedStore` starts from `DefaultPolicies.VERSIONED_STORE`: `ttl` forever, `tombstone-ttl` 1 day, `local-max-age` 30 s, `absent-ttl` 10 s. `cocache.overrides` can't shorten `ttl` or `tombstone-ttl`, because they also bound how long an older version stays refused ([what each layer may set](./configuration.md#field-layers)). Durability is Redis durability, and the eviction check applies, as for a [`Store`](./store.md#durability).

## Views {#views}

`async()` returns an `AsyncVersionedStore` and `suspending()` a `SuspendingVersionedStore`, with the same methods.
