# Write Paths and Freshness

Use this reference when code changes cached data, when a writer lives outside the application, or when choosing lifetimes. Confirm exact signatures and defaults in the resolved CoCache version.

## Contents

- [The write rule](#the-write-rule)
- [invalidate or put](#invalidate-or-put)
- [Read-modify-write on a Store](#read-modify-write-on-a-store)
- [Versioned writers](#versioned-writers)
- [Inside a transaction](#inside-a-transaction)
- [Writers outside the application](#writers-outside-the-application)
- [Choosing lifetimes](#choosing-lifetimes)
- [Refresh-ahead](#refresh-ahead)
- [Redis down during a write](#redis-down-during-a-write)
- [Staleness bounds](#staleness-bounds)

## The write rule

This applies to derived copies (`Cache`, `VersionedCache`). For a `Store`, `VersionedStore` or `SetStore` the write itself is the source of truth: write it through its API, never invalidate it.

Change the source of truth first; after the change is committed, invalidate the key.

```kotlin
repo.rename(id, name)        // committed
userCache.invalidate(id)     // every instance drops the key
```

Invalidating first is a bug: between the invalidation and the commit another request can miss, read the old row and cache it until `ttl`. After the commit, the lease fences out any load that started earlier and any later load reads the new row. Use `invalidateAll(keys)` for several keys.

Inventory every write path of the cached data (services, repositories, SQL, batch jobs, message consumers, admin tools, other services). A path that does not invalidate leaves the value stale until `ttl`.

## invalidate or put

| | `invalidate(key)` | `put(key, value)` |
|---|---|---|
| Redis | Deletes the key and any lease | Overwrites the value |
| Other instances | Drop their L2 copy | Drop their L2 copy |
| This instance | Drops its L2 copy | Drops its L2 copy; does not fill it |
| Next read | Loads from the source | One Redis read |
| Redis failure | Retried in the background (default, externally sourced caches included) | Throws `CacheWriteException` |

Prefer `invalidate`: it cannot cache a value that differs from the source. Use `put` only when the writer holds the final value and reloading is expensive; `put(key, value, ttl)` sets an explicit lifetime. After either call, the next read on the same instance sees the change; others see it when the message arrives.

For many keys, `putAll(entries)` (or `putAll(entries, ttl)`) applies `put` to each key in one fenced write: one Redis script per slot group and one invalidation message, no stale write-back from in-flight loads, not atomic across keys, throws `CacheWriteException` like `put`.

## Read-modify-write on a Store

When Redis is the only copy (`Store`) and several writers update one value by reading, changing and writing it back, `put` (last writer wins) loses updates. Use the digest-conditional operations instead, and make every writer use them:

```kotlin
while (true) {
    val current = store.getIfChanged(key, null)                          // Redis only, never L2
    val (doc, digest) = (current as? ConditionalGet.Present)?.let { it.value to it.digest } ?: (null to null)
    if (store.putIfMatch(key, digest, change(doc)) is ConditionalPut.Written) break   // Conflict: read again
}
```

- `putIfMatch(key, expected, value)`: one atomic compare-and-write; `expected = null` means "only if Redis holds no value" (absent, lease or negative entry). A conflict changes nothing and broadcasts nothing. A write behaves like `put` (TTL, fencing, no L2 fill).
- `deleteIfMatch(key, expected)`: one atomic compare-and-delete; returns `ConditionalDelete.Deleted` or `Conflict(current)`. Synchronous and never retried, like `delete`.
- `getIfChanged(key, known)`: `Unchanged` without transferring the value when its digest still equals `known`, otherwise `Present(value, digest)` or `Absent`. Use it to revalidate a value held in memory.
- The digest is the SHA-256 of the payload (`Digest.algorithm`, `toString()` = `SHA-256:<hex>`; no `.sha1`), written by every `Store` write into the value's header. A value without a digest (written by another type) or a versioned value makes these methods throw (`ForeignFormatException` cause). `Store` reads, and `getIfChanged` returning a value, recompute the digest and fail on a mismatch.
- ABA: a digest compares content, not history. A value changed and changed back matches again; use a `VersionedStore` when writers must be ordered.
- Neither touches L2. `getIfChanged` throws `CacheReadException` on a Redis failure regardless of `unknownRead`; `putIfMatch` and `deleteIfMatch` throw `CacheWriteException` and are not idempotent: when `outcomeUnknown` is `true`, re-read before retrying. Without atomic broadcast, a conflict whose digest is the caller's own value republishes the invalidation, so a replay repairs a lost publish.
- Unreadable content: newer-format values and sets always throw, key untouched. A value the codec can't decode makes the read throw, and the write only conflicts on it. Content in another wire format (format 0 or 1, e.g. written by another program) throws too and is never treated as no value. Only `Store` has these methods; creating a `Store` fails on a backend without conditional writes.
- Cost: an unchanged `getIfChanged` reads only the header in Redis (`GETRANGE` + `STRLEN`), so it costs the same whatever the value's size.

## Versioned writers

When the writer knows each value's version (an event-sourced aggregate, a CDC stream) and redelivery or replay can send older versions again, a plain `put` can regress Redis. Declare a versioned type and write with the version: `VersionedCache` when a versioned loader can rebuild the value, `VersionedStore` when nothing can.

```kotlin
versionedCacheSpec<String, View>("view") { codec = …; versionedLoader = CacheLoader { id -> Versioned(view, version) } }
cache.putIfNewer(id, view, version)          // Written, or Stale(current) when Redis holds this version or newer
cache.invalidateIfNewer(id, version)         // tombstone: reads as absent, blocks older versions
cache.getVersioned(id)                       // Versioned(value, version)?
cache.evict(id)                              // next read reloads; older versions still refused
store.deleteIfNewer(id, version)             // VersionedStore: ordered delete (tombstone)
```

- One Lua round trip, atomic. Equal version: idempotent no-op; older: refused; neither writes nor changes the TTL. An equal version still publishes the invalidation (a load may have filled that version without one); an older one publishes nothing. A write behaves like `put` and wins over a load holding the lease, even one that read a higher version. Content without a version (plain `put`, plain loader, negative entry, absent) is older than any version.
- A versioned loader returns `Versioned(null, n)` for a key deleted at version `n` (the fill writes a tombstone, so older replays can't resurrect it) and `null` for a key that never existed (plain negative entry). `getVersioned` returns `null` for both; a living tombstone still refuses older versions.
- The versioned types have no write that drops the version except `purge` (a plain `DEL`) and expiry. Ordering holds only while the entry lives: tombstones live `tombstoneTtl` (the value `ttl` on a `VersionedCache` by default, 1 day on a `VersionedStore`), which must cover the redelivery window.
- `evict` (`VersionedCache`) leaves an eviction marker (state `E`) for `tombstoneTtl`; it throws and is never retried, because a retried `DEL` would drop the version. `purge` drops the fence and throws on failure too.
- A versioned loader that returns a plain `null` over versioned content is refused (`cocache.fill.unversioned.absent`, one warning per cache): return `Versioned(null, version)` for deletions.
- Refresh-ahead: a `VersionedCache` refreshes ahead and `cocache.defaults.refresh-window` applies to it (a `VersionedStore` has no source and never refreshes); a refresh writes back only a version at least as new as Redis holds, and keeps its copy otherwise; the local copy never moves back to an older version (a dropped older fill counts in `cocache.stale.discards`). Turn it off with `refreshWindow = null` / `cocache.caches.[name].refresh-window: 0` when the writer pushes every version.
- Operator delete: the endpoint's per-key `DELETE` on a `VersionedCache` is `evict` (keeps the version), on a `VersionedStore` `purge` (drops it; needs `management.endpoint.cocache.allow-purge=true`, as does `?purge=true` on a `VersionedCache`). After `evict` the next read loads from the source, and a loader behind Redis is returned but not written (`cocache.fill.rejections`).
- Creating a versioned type fails when the backend's store has no `versioned()` capability; Redis and in-memory support it. Coroutines: `suspendingVersionedLoader` on `versionedCacheSpec`.
- `putIfNewer` throws `CacheWriteException` on Redis failure; replaying the same version is safe (returns `Stale`, and an equal version republishes the invalidation).

## Inside a transaction

`TransactionalInvalidator` (registered by the starter when `spring-tx` is present) defers the invalidation to `afterCommit`:

```kotlin
@Transactional
fun rename(id: String, name: String) {
    repo.rename(id, name)
    invalidator.invalidate(userCache, id)    // after commit; also after a rollback
}
```

- Invalidations of one cache in one transaction merge into one deduplicated `invalidateAll`.
- On rollback it invalidates too: the loader joins the transaction, so a read after the write may have cached the uncommitted row.
- `REQUIRES_NEW` invalidates when it ends; without an active transaction it invalidates immediately. Registrations from another synchronization's `afterCommit` or `afterCompletion` are not lost.
- Only thread-bound transactions are visible. In a reactive transaction (R2DBC, `@Transactional` suspend functions) it invalidates immediately, before the commit; invalidate after the transaction ends instead.
- It takes a `Cache` (derived copies only); for a join view, pass the component cache. Authoritative writes are synchronous: make them part of the operation, not an after-commit step.
- `@CacheEvict` and `@CachePut` through the transaction-aware cache manager are deferred to after the commit too, but skipped on rollback (see `spring-cache.md`).

## Writers outside the application

On the JVM, use `ExternalInvalidator` from `cocache-core`; it speaks the cache protocol with the same backend and needs no cache declaration:

```kotlin
@Bean(destroyMethod = "close")
fun externalInvalidator(backend: CoherenceBackend) = ExternalInvalidator(backend)

externalInvalidator.invalidate("UserCache", listOf("42", "43"))   // namespace, encoded keys
ExternalInvalidator(backend, "shop:").invalidate("ProductCache", "7")   // non-default key prefix
```

- Arguments are the cache's namespace (the cache name unless set) and encoded keys exactly as they appear in the storage key.
- It deletes and broadcasts (one script in the default channel mode), does not retry, and throws on failure; calls are idempotent, so the caller retries. `close()` does not close the backend.

Outside the JVM, a process does `DEL {keyPrefix}{namespace}:{encodedKey}` and then publishes a binary invalidation frame on `{keyPrefix}inv:{namespace}` (`SPUBLISH` in sharded mode). The frame layout is documented in the CoCache operations guide (troubleshooting, external invalidation); copy it from the docs of the resolved version rather than from memory. Delete before publishing; skipping the publish leaves peers' L2 stale up to `local-max-age`. Never invalidate a `Store`, `VersionedStore` or `SetStore` from outside: `DEL` destroys the only copy. On a `VersionedCache`, a `DEL` also drops the version fence.

## Choosing lifetimes

Each lifetime bounds one kind of staleness. Bare numbers under `cocache.defaults` and `cocache.caches` are seconds; write a unit for anything shorter (`lease-wait: 500ms`).

| Setting | Default | Bounds | Guidance |
|---|---|---|---|
| `ttl` | 1 h | Changes CoCache never hears about | Long, even `ttl-forever`, when every writer invalidates; shorter when some cannot |
| `ttl-jitter` | 1 min | Synchronized expiry | Keep meaningful; effective spread is capped at `ttl / 2` |
| `absent-ttl` | 1 min | A new row hidden by a cached absence | Keep short, or invalidate the key when creating the row |
| `local-max-age` | 5 min | L2 staleness after a lost message | Costs nothing on the hit path; lower it where minutes of drift matter |
| `degraded-local-max-age` | 10 s | Loads not written to Redis | Rarely changed |
| `lease-wait` | 200 ms | Cluster-wide coalescing | Raise above the loader's typical latency to keep one load per cluster |
| `lease-ttl` | 10 s | A crashed loader blocking a key | Keep above the loader's p99 |

The defaults above are the derived types'. `Store`, `VersionedStore` and `SetStore` default to `ttl` forever, `local-max-age` 30 s and `absent-ttl` 10 s, because a lost invalidation can't be corrected by a reload; `cocache.defaults.ttl`, `ttl-forever` and `refresh-window` don't apply to them.

## Refresh-ahead

`refresh-window` makes a read within the window of Redis expiry return the value and schedule one background reload; only the instance that wins the refresh token in Redis calls the loader.

- The window must be below `ttl - ttl-jitter` and below `local-max-age` (with defaults, under 5 minutes), or startup fails.
- Only caches with a source refresh (`Cache` with a loader, `VersionedCache`); authoritative types and negative entries do not. A failed or skipped refresh only logs; values still expire normally.
- Refreshes run on a bounded pool (at most 4 threads, queue 1024); overflow counts in `cocache.refresh.rejections`.
- Turn a declared window off with `refresh-window: 0`, `@CoCache(refreshWindow = 0)`, or `refreshWindow = null` in a customizer.

## Redis down during a write

- `Cache.invalidate`: the local L2 copy is dropped first. By default (`invalidate-failure: retry`, every `Cache`, externally sourced included) the call does not throw; the delete is queued (8 attempts, 100 ms to 10 s backoff, 10 000 pending) and misses for that key on this instance skip Redis meanwhile. Exhausted or overflowing retries log an error and count `cocache.invalidations.dropped`; other instances may then serve the old value until its remaining `ttl` (forever with `ttl-forever`), so invalidate again once Redis is back. `invalidate-failure: throw` raises `CacheWriteException` instead.
- Every other write throws `CacheWriteException` and is never retried: `put`, `putAll`, conditional and versioned writes, `evict`, `purge`, `Store.delete` / `deleteIfMatch`, `SetStore` writes. A delayed delete of an only copy could remove a value written after the failure, and a retried `DEL` would drop a version.
- `CacheWriteException.outcomeUnknown`: `false` means the write did not happen (rejected before it was sent, or refused on unreadable content); `true` means it may have run (timeout, dropped connection) and may still run. Retrying `put` on a `Cache` is safe; on a `Store` it is a new last-writer-wins write; replaying `putIfNewer` with the same version is safe; re-read before retrying `putIfMatch` / `deleteIfMatch`.
- The retry limits come from `RetryPolicy` on the `CacheRuntime`; in Spring Boot declare your own `CacheRuntime` bean to change them.

## Staleness bounds

| Situation | Bound |
|---|---|
| A load racing an invalidation | None: the stale result is discarded |
| Another instance, message delivered | Pub/Sub delay |
| Another instance, message lost | `local-max-age`, or sooner after a heartbeat reset |
| A write that bypasses CoCache | `ttl` (never with `ttl-forever`) |
| A new row after a cached absence | `absent-ttl` |
| A load not written to Redis | `degraded-local-max-age` |
| An invalidation that exhausted its retries | The Redis value's remaining TTL |

CoCache is not linearizable: peers see a change when the message arrives.
