---
title: VersionedCache
description: The derived, versioned cache type — when to use VersionedCache, declaring its versioned loader, putIfNewer and invalidateIfNewer from the writer, evict and purge, refresh-ahead, defaults, and failures.
---

# VersionedCache

A `VersionedCache<K, V>` holds a **derived copy with a version**: state whose writer knows the version of every value it writes, and that a versioned source can rebuild. An event-sourced aggregate has one writer and strictly increasing versions; a CDC stream carries commit sequence numbers. The writer pushes each new state into Redis right after the commit, and the loader rebuilds a key, with its version, when Redis loses it. Redis keeps the version next to the value, so a redelivered or replayed older version can never replace a newer one.

| | `VersionedCache<K, V>` |
|-|------------------------|
| Data comes from | A versioned loader (`versionedLoader` / `versionedAsyncLoader`), plus the writer's `putIfNewer` |
| Reads | `get`, `getAll`, `lookup`, `lookupAll`, `getVersioned` |
| Writes | `putIfNewer`, `invalidateIfNewer` (tombstone) |
| Deletes | `evict` keeps the version fence; `purge` drops it |
| On a Redis read failure | Loads from the source (`read-failure: degrade`) |
| On a Redis write failure | Throws `CacheWriteException`; retrying the same version is safe |
| Default lifetimes | `ttl` 1 h ± 1 min, tombstones the value's lifetime, `local-max-age` 5 min |
| Spring Cache, `TransactionalInvalidator` | No |

There is no `put`, `putIfMatch` or plain `invalidate`: no write can drop the version. Data that has a versioned writer but no source to reload from is a [`VersionedStore`](./versioned-store.md).

## Declare it {#declare}

::: code-group

```kotlin [Kotlin]
@Bean
fun orderViewSpec(mapper: ObjectMapper, repository: OrderRepository): VersionedCacheSpec<String, OrderView> =
    versionedCacheSpec("order-view") {
        codec = jacksonCodec<OrderView>(mapper)
        versionedLoader = CacheLoader { id ->
            val state = repository.find(id) ?: return@CacheLoader null          // never existed
            Versioned(if (state.deleted) null else state.view, state.version)   // deleted: Versioned(null, version)
        }
    }
```

```java [Java]
@Bean
VersionedCacheSpec<String, OrderView> orderViewSpec(ObjectMapper mapper, OrderRepository repository) {
    return VersionedCacheSpec.<String, OrderView>builder("order-view")
        .codec(JacksonCodec.of(mapper, OrderView.class))
        .versionedLoader(id -> repository.find(id)
            .map(state -> new Versioned<>(state.deleted() ? null : state.view(), state.version()))
            .orElse(null))
        .build();
}
```

:::

As an interface, `interface OrderViews : VersionedCache<String, OrderView>` finds its source by type: a `CacheLoader<K, Versioned<V>>` or `AsyncCacheLoader<K, Versioned<V>>` bean, or the bean named by `@CoCache(loader)`. A `VersionedCache` without a versioned source fails startup. In Kotlin, `suspendingVersionedLoader { … }` sets a suspend function as the source ([Kotlin Coroutines](./coroutines.md#loader)).

## Versioned loaders {#versioned-loaders}

The source returns `Versioned(value, version)`, and the fill stores the version with the value.

- **Express a deletion as `Versioned(null, version)`.** It writes a tombstone that refuses that version and older ones, the same as `invalidateIfNewer(key, version)`, so a replayed older state can't bring the key back.
- **A plain `null` means "never existed"** and caches a negative entry without a version, which any versioned write replaces.
- When Redis already holds versioned content for the key (for example after [`evict`](#evict)), an unversioned `null` is **refused**: the read returns "absent", but nothing is written to Redis, so every miss reloads until the version fence expires. Each refusal counts in `cocache.fill.unversioned.absent`, and the first one per cache logs a warning.
- **A load in flight never wins over a write.** If `putIfNewer` lands while a reader holds the load lease, the reader's write-back is refused, even when it read a higher version from the source. A fill that raises the version in Redis is a write: it broadcasts, and the loading instance drops its own copy instead of filling L2 with it.

## Writing from the writer {#writing}

```kotlin
// after each commit (also on replay and redelivery)
when (views.putIfNewer(id, view, version)) {
    VersionedPut.Written -> Unit                  // newer: written and broadcast
    is VersionedPut.Stale -> Unit                 // Redis already holds this version or a newer one
}
views.invalidateIfNewer(id, deletedAtVersion)     // deletion: a tombstone that refuses older versions

views.getVersioned(id)                            // Versioned(view, version), or null
```

- `putIfNewer` writes only a version greater than the one in Redis, in one script, and returns `Written`. An equal or older version returns `Stale(current)` and changes nothing.
- `invalidateIfNewer` writes a tombstone. Readers see the key as absent without calling the loader, and `putIfNewer` with that version or an older one returns `Stale`. A newer version can bring the key back.
- `getVersioned` reads through like `get` and returns the version too: `null` for a tombstone or an absent key.

[Versioned writes](./freshness.md#versioned-writes) has the full rules: how versions compare, how long ordering holds, and why an equal version still publishes the invalidation.

## `evict` and `purge` {#evict}

- **`evict(key)` keeps the version.** It drops the copy on every instance and replaces a versioned value or tombstone in Redis with an eviction marker that keeps the version: the next read loads from the source, and a late older version is still refused. If the source is behind the version Redis held, that load's result is returned (and kept in L2 for `degraded-local-max-age`) but not written to Redis, counted in `cocache.fill.rejections`, until the source catches up. The marker lives for `tombstone-ttl`. A failure throws, and is never retried in the background.
- **`purge(key)` drops the version.** It deletes the key, so any version, even an older one arriving late, can be written afterwards. It is an operator's tool: never retried, throws on failure.

The actuator endpoint's per-key `DELETE` runs `evict` on a `VersionedCache`, and `?purge=true` runs `purge`, which needs `management.endpoint.cocache.allow-purge=true` ([invalidating a key](../operations/observability.md#invalidate-key)).

## Refresh-ahead {#refresh-ahead}

A `VersionedCache` refreshes ahead like a `Cache` when `refresh-window` is set, including from `cocache.defaults.cache.refresh-window` when the declaration doesn't write it. The refresh writes back only a version at least as new as the one in Redis, and the instance's local copy never moves back to an older version: when the source returns an older one (the writer pushed ahead of it), the instance keeps its copy and tries again after a back-off. When the writer pushes every version anyway, turn refresh-ahead off with `refreshWindow = off()` in `policy { … }` or `cocache.caches.[name].refresh-window: off`.

## Defaults and configuration {#defaults}

A `VersionedCache` starts from `DefaultPolicies.VERSIONED_CACHE`: the same lifetimes as a `Cache`, with tombstones and eviction markers living as long as values (`tombstone-ttl` unset). `ttl` and `tombstone-ttl` also bound how long a redelivered older version stays refused: once a value or tombstone expires, any version can be written again. Size them to cover your redelivery window. For the same reason, `cocache.overrides` can't shorten them ([what each layer may set](./configuration.md#field-layers)).

## When Redis fails {#failures}

- Reads with the source degrade to it, as for a `Cache`.
- `putIfNewer`, `invalidateIfNewer`, `evict` and `purge` throw `CacheWriteException`. Retrying `putIfNewer` or `invalidateIfNewer` with the same version is safe: if the first attempt went through, the retry returns `Stale(version)` and publishes the invalidation again.
- A failed versioned write is never queued for a background retry: the writer retries it, usually by redelivering the event.

## Views {#views}

`async()` returns an `AsyncVersionedCache` and `suspending()` a `SuspendingVersionedCache`, with the same methods. A versioned cache can't sit behind Spring Cache or `TransactionalInvalidator`, which need `invalidate`; a cache operation that names one fails startup.
