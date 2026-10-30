# Write Paths and Freshness

Use this reference when code changes cached data, when a writer lives outside the application, or when choosing lifetimes. Confirm exact signatures and defaults in the resolved CoCache version.

## Contents

- [The write rule](#the-write-rule)
- [invalidate or put](#invalidate-or-put)
- [Read-modify-write on a Redis-only value](#read-modify-write-on-a-redis-only-value)
- [Versioned writers](#versioned-writers)
- [Inside a transaction](#inside-a-transaction)
- [Writers outside the application](#writers-outside-the-application)
- [Choosing lifetimes](#choosing-lifetimes)
- [Refresh-ahead](#refresh-ahead)
- [Redis down during a write](#redis-down-during-a-write)
- [Staleness bounds](#staleness-bounds)

## The write rule

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
| Redis failure | Retried in the background (default) | Throws `CacheWriteException` |

Prefer `invalidate`: it cannot cache a value that differs from the source. Use `put` only when the writer holds the final value and reloading is expensive; `put(key, value, ttl)` sets an explicit lifetime. After either call, the next read on the same instance sees the change; others see it when the message arrives.

For many keys, `putAll(entries)` (or `putAll(entries, ttl)`) applies `put` to each key in one fenced write: one Redis script per slot group and one invalidation message, no stale write-back from in-flight loads, not atomic across keys, throws `CacheWriteException` like `put`.

## Read-modify-write on a Redis-only value

When Redis is the only copy (no loader) and several writers update one value by reading, changing and writing it back, `put` loses updates. On 6.7.0+ use the digest-conditional pair instead, and make every writer use it:

```kotlin
while (true) {
    val current = cache.getIfChanged(key, null)                          // Redis only, never L2
    val (doc, digest) = (current as? ConditionalGet.Present)?.let { it.value to it.digest } ?: (null to null)
    if (cache.putIfMatch(key, digest, change(doc)) is ConditionalPut.Written) break   // Conflict: read again
}
```

- `putIfMatch(key, expected, value)`: one atomic compare-and-write; `expected = null` means "only if Redis holds no value" (absent, lease or negative entry). A conflict changes nothing and broadcasts nothing. A write behaves like `put` (TTL, fencing, no L2 fill).
- `getIfChanged(key, known)`: `Unchanged` without transferring the value when its digest still equals `known`, otherwise `Present(value, digest)` or `Absent`. Use it to revalidate a value held in memory. Redis still hashes the value in the script.
- Neither touches L2 or the loader. `getIfChanged` throws `CacheReadException` on a Redis failure regardless of `read-failure`; `putIfMatch` throws `CacheWriteException` and is not idempotent: after a timeout, re-read before retrying. Without atomic broadcast (6.9+), a conflict whose digest is the caller's own value republishes the invalidation, so a replay repairs a lost publish.
- Unreadable content: newer-format values and sets always throw, key untouched. A value the codec can't decode makes the read throw, and the write only conflicts on it. Format-0 (5.x) content follows `foreign-format` (default `fail`: throw; `reclaim`: counts as no value). Not available on `SetCache`, nor on backends that don't implement `putIfMatch` (`UnsupportedOperationException`).
- Cost: an unchanged `getIfChanged` saves wire bytes, not Redis CPU. Redis reads and SHA-1s the value on every call, about 20 ms for 8 MB on Redis 7.4.

## Versioned writers

When the writer knows each value's version (an event-sourced aggregate, a CDC stream) and redelivery or replay can send older versions again, a plain `put` can regress Redis. On 6.8.0+ declare the cache versioned and write with the version:

```kotlin
cacheSpec<String, View>("view") { codec = …; versionedLoader = CacheLoader { id -> Versioned(view, version) } }  // or versioned = true
cache.putIfNewer(id, view, version)          // Written, or Stale(current) when Redis holds this version or newer
cache.invalidateIfNewer(id, version)         // tombstone: reads as absent, blocks older versions
cache.getVersioned(id)                       // Versioned(value, version)?
```

- One Lua round trip, atomic. Equal version: idempotent no-op; older: refused; neither writes, broadcasts nor changes the TTL. A write behaves like `put` and wins over a load holding the lease, even one that read a higher version. Content without a version (plain `put`, plain loader, negative entry, absent) is older than any version.
- A versioned loader returns `Versioned(null, n)` for a key deleted at version `n` (the fill writes a tombstone, so older replays can't resurrect it) and `null` for a key that never existed (plain negative entry). `getVersioned` returns `null` for both; a living tombstone still refuses older versions.
- Every ordering writer must use `putIfNewer` / `invalidateIfNewer`: `put`, `putIfMatch`, `invalidate` and expiry drop the version. Ordering holds only while the entry lives: give values and tombstones (value lifetime by default) a TTL covering the redelivery window.
- Versioned caches reject `refresh-window` (`cocache.defaults.refresh-window` skips them). Creating one fails on a backend without `versionedWrites`; Redis and in-memory support it. Coroutines: `suspendingVersionedLoader`.
- `putIfNewer` throws `CacheWriteException` on Redis failure; replaying the same version is safe (returns `Stale`). Without atomic broadcast (6.9+), `Stale` with the same version republishes the invalidation.

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
- It takes a `Cache`; for a `JoinCache`, pass the component cache.
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

Outside the JVM, a process does `DEL {keyPrefix}{namespace}:{encodedKey}` and then publishes a binary invalidation frame on `cocache:inv:{namespace}` (`SPUBLISH` in sharded mode). The frame layout is documented in the CoCache operations guide (troubleshooting, external invalidation); copy it from the docs of the resolved version rather than from memory. Delete before publishing; skipping the publish leaves peers' L2 stale up to `local-max-age`. Never invalidate a `SetCache` from outside: `DEL` destroys its only copy.

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

## Refresh-ahead

`refresh-window` makes a read within the window of Redis expiry return the value and schedule one background reload; only the instance that wins the refresh token in Redis calls the loader.

- The window must be below `ttl - ttl-jitter` and below `local-max-age` (with defaults, under 5 minutes), or startup fails.
- Only caches with a loader refresh; negative entries do not. A failed or skipped refresh only logs; values still expire normally.
- Refreshes run on a bounded pool (at most 4 threads, queue 1024); overflow counts in `cocache.refresh.rejections`.
- Turn a declared window off with `refresh-window: 0`, `@CoCache(refreshWindow = 0)`, or `refreshWindow = null` in a customizer.

## Redis down during a write

- `invalidate`: the local L2 copy is dropped first. With a loader, by default (`invalidate-failure: retry`) the call does not throw; the delete is queued (8 attempts, 100 ms to 10 s backoff, 10 000 pending) and misses for that key on this instance skip Redis meanwhile. Exhausted or overflowing retries log an error and count `cocache.invalidations.dropped`; other instances may then serve the old value until its remaining `ttl` (forever with `ttl-forever`), so invalidate again once Redis is back. `invalidate-failure: throw` raises `CacheWriteException` instead. Without a loader (6.9+) `throw` is the default: Redis is the only copy, and a delayed delete would remove a value written after the failure.
- `put` and `SetCache` writes throw `CacheWriteException`; the outcome may be unknown after a timeout or an interrupted wait, and retrying is safe because they are idempotent.
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
