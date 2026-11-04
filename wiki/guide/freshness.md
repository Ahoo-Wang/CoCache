---
title: Keeping Data Fresh
description: Write the source then invalidate, invalidate after commit, handle writers outside your application, choose TTLs and refresh-ahead, and know what happens when Redis is down during a write.
---

# Keeping Data Fresh

A cache is only as fresh as the writes it hears about. This page covers how to tell CoCache about changes, and how to choose the lifetimes that bound everything it can't hear about. Which writes a cache has depends on its [type](./caches.md#types): a `Cache` is invalidated after the source changes, a `Store` is written directly, and the versioned types order every write by version.

## The rule: write, then invalidate {#rule}

**Change the source of truth first. After the change is committed, invalidate the key.**

```kotlin
repo.rename(id, name)        // committed
userCache.invalidate(id)     // every instance drops the key
```

Invalidating first is a bug, even though it looks safer. Between the invalidation and the commit, another request can miss, read the *old* row, and cache it. That stale value then lives until `ttl`. Invalidating after the commit closes that window: any load that started before the invalidation is fenced out, and any load that starts after it reads the new row.

For several keys, `invalidateAll(keys)` sends them in as few Redis scripts as possible.

## `invalidate` or `put`? {#invalidate-vs-put}

| | `invalidate(key)` | `put(key, value)` |
|-|-------------------|-------------------|
| Redis | Deletes the key | Overwrites the value |
| Other instances | Drop their L2 copy | Drop their L2 copy |
| This instance | Drops its L2 copy | Drops its L2 copy (doesn't fill it) |
| Next read | Loads from the source | Reads the new value from Redis |
| On Redis failure | Retried in the background (default) | Throws `CacheWriteException` |

**Prefer `invalidate`.** It can't cache a value that differs from the source, and the next read loads exactly what was committed. Use `put` only when the writer holds the final value and loading it again would be expensive. `put(key, value, ttl)` sets an explicit lifetime; plain `put` uses the cache's `ttlOf` or policy.

After either call returns, the next read on the same instance sees the change. Other instances see it once the invalidation message arrives, typically within milliseconds.

## Batch writes {#batch-writes}

`putAll` writes several keys at once. Each key follows the same rules as `put`:

```kotlin
userCache.putAll(users.associateBy { it.id })                          // ttlOf or policy, jittered per key
userCache.putAll(users.associateBy { it.id }, Duration.ofMinutes(5))   // one explicit lifetime
```

- **No stale write-back.** The batch is one write: this instance drops its L2 copies of every key before and after Redis is written, and the overwrite destroys any load lease. A load that started before `putAll` can't cache its old value, neither in L2 nor in Redis. Like `put`, `putAll` doesn't fill this instance's L2.
- **One round trip, one message.** Redis receives one script that writes every key and publishes one invalidation message for all of them. In Redis Cluster, keys are grouped by slot and split at `cocache.redis.invalidate-batch-size`, and each group runs its own script concurrently. Each key is written atomically, but the batch as a whole is not.
- **Encoded-key collisions:** if two keys encode to the same storage key, the later entry wins. An empty map does nothing.
- **Failures:** like `put`, `putAll` throws `CacheWriteException` if Redis fails, and some keys may already be written. Retrying is safe.
- **`Store.putAll`** writes each value with a writer id and digest, like `Store.put`. **`SetStore.putAll`** replaces each set, and an empty set clears its key, exactly like `put`.

## Conditional writes {#conditional-writes}

When Redis is the only copy and several writers update one value by reading it, changing it, and writing it back (for example a document that holds many records), a plain `put` loses updates: two writers read the same version and the second `put` overwrites the first. A [`Store`](./caches.md#store) has conditional operations for this: `putIfMatch` writes only if Redis still holds the value the writer read, and `getIfChanged` reads that value from Redis together with its digest:

```kotlin
fun update(key: String, change: (Document?) -> Document) {
    while (true) {
        val current = store.getIfChanged(key, null)              // reads Redis, never L2
        val (document, digest) = when (current) {
            is ConditionalGet.Present -> current.value to current.digest
            else -> null to null                                  // Absent: no value yet
        }
        when (store.putIfMatch(key, digest, change(document))) {
            is ConditionalPut.Written -> return
            is ConditionalPut.Conflict -> continue               // someone else wrote first: read again
        }
    }
}
```

- **The digest** is the SHA-256 of the value's bytes as the codec encoded them (`Digest.algorithm` is `SHA-256`, `toString()` prints `SHA-256:<hex>`). A `Store` computes it on every write and stores it in the value's header. `ConditionalGet.Present` and `ConditionalPut.Written` both return it. A codec that encodes equal values to different bytes (an unordered map, say) produces different digests.
- **`putIfMatch(key, expected, value)`** compares and writes in one Redis script. With `expected = null` it writes only if Redis holds no value: the key is absent, a load lease is in place, or the key holds a negative entry. Otherwise it writes only if the value's digest equals `expected`. A conflict returns `Conflict(current)` (`null` when Redis holds no value) and changes nothing: no write, no TTL change, no invalidation message. A successful write is a `put`: same TTL rules (`ttlOf` or policy, or the `ttl` argument), same invalidation of every instance's L2, and this instance's next read sees it.
- **`deleteIfMatch(key, expected)`** deletes only if the value's digest equals `expected`, and returns `Deleted` or `Conflict(current)` (`null` when Redis holds no value). Like `delete`, it completes before it returns and is never retried.
- **`getIfChanged(key, known)`** asks Redis whether the value still has digest `known`. If it does, the answer is `Unchanged` and the value isn't transferred. The script reads only the value's header and compares the stored digest, so confirming a value costs the same in Redis whatever its size, and Redis sends back two integers. Otherwise it returns `Present(value, digest)` or `Absent`. When it returns the value, CoCache recomputes the SHA-256 and throws if it doesn't match the stored digest. Use it to revalidate a value you hold in memory: keep the digest, and call `getIfChanged` on a schedule or when an invalidation arrives.
- **All three bypass L2.** They read and write Redis only and never fill L2. The store's L2 may be older than what `getIfChanged` returns, within `localMaxAge`.
- **Failures:** `getIfChanged` throws `CacheReadException` when Redis fails, whatever `unknownRead` says, because there is nothing to degrade to. `putIfMatch` and `deleteIfMatch` throw `CacheWriteException`. They aren't idempotent: when the exception's `outcomeUnknown` is `true` (a timeout, say), call `getIfChanged` first. If the current digest is the one `Written` would have returned, the write went through. `outcomeUnknown = false` means the write didn't happen.
- **ABA:** a digest match proves the value is the one you read, not that nothing happened in between. A write whose outcome is unknown may run late and match a value that was written again with the same content, and a late `deleteIfMatch` can delete it. Writers that need strict ordering use a [`VersionedStore`](#versioned-writes).
- **Without atomic broadcast** (the `sharded` [channel mode](../operations/index.md#channel-mode), or when the startup probe falls back), the invalidation is published after the script. If the write lands in Redis but that publish fails, the call throws `CacheWriteException`. Replaying the same write then returns `Conflict(current)` whose digest is that of your own value (for `deleteIfMatch`: a conflict with no current value), and CoCache publishes the invalidation again so other instances drop their stale copies. Other conflicts publish nothing.
- **Unreadable content** is never treated as an empty value by one method and as a value by another. A value written by a newer CoCache, a set, a value without a digest (written by a derived cache), a versioned value, a tombstone or an evicted versioned key makes all three throw with a `ForeignFormatException` cause and leaves the key alone. A value the codec can't decode, or whose payload doesn't match its digest, makes `getIfChanged` throw. `putIfMatch` and `deleteIfMatch` can't see the codec, so on a value it can't decode they only ever return `Conflict` and never change it. Content in a format this version can't read (such as a value another program wrote) makes them throw too: it never counts as no value, so `putIfMatch(key, null, value)` never replaces it.
- **Every writer must use `putIfMatch`.** A plain `put` doesn't check and still wins over a concurrent read-modify-write.
- A `SetStore` doesn't need them, since its `add` and `remove` are already atomic. The async view and the [suspending view](./coroutines.md) have the same methods. Creating a `Store` fails on a backend whose store has no conditional writes.

## Versioned writes {#versioned-writes}

Some writers know the version of every value they write: an event-sourced aggregate has one writer and strictly increasing versions, a CDC stream carries commit sequence numbers. The writer can push each new state into Redis right after the commit, but redelivery and replay send older versions again, and a plain `put` of an older version would replace the newer one. The [versioned types](./caches.md#versioned) store the version next to each value and order writes by it. A `VersionedCache` has a versioned source to reload from; a `VersionedStore` has none:

```kotlin
val views = runtime.create(versionedCacheSpec<String, OrderView>("order-view") {
    codec = jacksonCodec<OrderView>(mapper)
    versionedLoader = CacheLoader { id ->
        val state = repository.find(id) ?: return@CacheLoader null          // never existed: plain negative entry
        Versioned(if (state.deleted) null else state.view, state.version)   // deleted at state.version: tombstone
    }
})

// the writer, after each commit (also on replay and redelivery)
when (views.putIfNewer(id, view, version)) {
    VersionedPut.Written -> Unit                  // newer: written and broadcast
    is VersionedPut.Stale -> Unit                 // Redis already holds this version or a newer one
}
views.invalidateIfNewer(id, deletedAtVersion)     // deletion: a tombstone that blocks older versions

views.getVersioned(id)                            // Versioned(view, version), or null
```

- **Sources.** A `VersionedCache` needs a `versionedLoader` / `versionedAsyncLoader` that returns `Versioned(value, version)` (the [suspending](./coroutines.md) builder has `suspendingVersionedLoader`). A loader that returns `Versioned(null, n)` reports a key deleted at version `n`: the load writes the same tombstone as `invalidateIfNewer(key, n)`, so a replayed older state can't bring it back. Returning `null` means the key never existed and caches a plain negative entry for `absent-ttl`, which any versioned write replaces; over versioned content (after `evict`) it is [refused](./caches.md#versioned-loaders). Creating a versioned type fails at once on a backend that doesn't support versions. The Redis and in-memory backends do.
- **`putIfNewer(key, value, version[, ttl])`** compares and writes in one Redis script, so it's one round trip. It writes only when `version` is greater than the version in Redis, and returns `Written`. An equal version is a duplicate delivery and an older one is late; both return `Stale(current)` and change nothing in Redis: no write, no TTL change. An equal version still publishes the invalidation: a load may have written that version without one, while another instance holds an older copy. An older version publishes nothing. Versions compare as signed 64-bit numbers. Content without a version counts as older than any version: a negative entry, a load lease, or no key at all. A successful write fences out every load in flight and evicts every instance's L2.
- **A load in flight never wins.** If a reader holds the load lease while `putIfNewer` lands, its write-back is refused, even when it read a higher version from the source. The writer's next version arrives through `putIfNewer` anyway. A load that completes first stores the loader's version, and later writes compare against it. A write-back that does go through and raises the version is a write: it broadcasts an invalidation, and the loading instance drops its own copy instead of filling L2 with it, so its next read comes from Redis.
- **`invalidateIfNewer(key, version[, ttl])`** (`deleteIfNewer` on a `VersionedStore`) writes a tombstone: readers see the key as absent (without calling the loader), and `putIfNewer` with that version or an older one returns `Stale`. A newer version can bring the key back. The tombstone lives for the policy's `tombstoneTtl` (a `VersionedCache` defaults to the value `ttl` with jitter; a `VersionedStore` to 1 day), or the `ttl` argument, not `absent-ttl`. Once it expires, a late older version can be written again, so give tombstones a lifetime that covers your redelivery window.
- **`getVersioned(key)`** reads through like `get` and returns the value with its version. It returns `null` for a tombstone, the same as for an absent key; while the tombstone lives, `putIfNewer` with its version or a lower one is still refused. A value without a version reports `Versioned.NO_VERSION`.
- **Ordering holds while the entry lives.** Once a value or tombstone expires, the key holds no version, and any version can be written again, including an older one. Size `ttl` (and `tombstoneTtl`) to your redelivery horizon. A writer that replays its stream to the head ends on the newest version anyway, so after an expiry the staleness is bounded by that replay.
- **No write drops the version.** The versioned types have no `put`, `putIfMatch` or plain `invalidate`. The only ways to remove a version are `purge(key)` (deletes the key; any version can be written afterwards; never retried, throws on failure), expiry, and tools outside CoCache.
- **`evict(key)` keeps the version.** A `VersionedCache` drops the copy on every instance and replaces a versioned value or tombstone in Redis with an eviction marker that keeps the version: the next read loads from the source, and a late older version is still refused. If the source is behind the version Redis held, that load's result is returned (and kept in L2 for `degraded-local-max-age`) but not written to Redis, counted in `cocache.fill.rejections`, until the source catches up. A value without a version is deleted. The marker lives for `tombstoneTtl`. A failure throws instead of being retried in the background. The endpoint's per-key `DELETE` (`CacheAdmin.invalidateEncoded`) is an `evict` on a `VersionedCache` and a `purge` on a `VersionedStore`.
- **Refresh-ahead works** on a `VersionedCache`. A versioned value gets a refresh token like any other, and `cocache.defaults.refresh-window` applies. The refresh writes back only a version at least as new as the one in Redis. When the source returns an older version than Redis holds (the writer pushed ahead of the source), Redis refuses it and the instance keeps its newer copy, trying again after a back-off. The local copy never moves back to an older version in general: when a refresh loses its token to another reader, or its write-back to Redis fails, and the source still returns an older version, the instance keeps the copy it has (counted in `cocache.stale.discards`). A write-back that fails with an unknown outcome counts as a write: the instance drops its copy and reads Redis next time. Set `refreshWindow = null` on the declaration (or `cocache.caches.[name].refresh-window: 0`) when the writer's pushes are enough.
- **Failures:** `putIfNewer` throws `CacheWriteException` when Redis fails. Retrying the same version is safe: if the first attempt went through, the retry returns `Stale(version)` and publishes the invalidation again, in case the first attempt was written but its separate publish failed. Unreadable content is classified as for [conditional writes](#conditional-writes).
- The async view and the suspending view have the same methods. A meter, `cocache.versioned.stale`, counts refused versions.

## Inside a transaction {#transactions}

Inside a Spring transaction, "after the commit" is not where your code runs. `TransactionalInvalidator` (registered by the starter when `spring-tx` is present) moves the invalidation there. It takes a `Cache`: the other types have no `invalidate`.

::: code-group

```kotlin [Kotlin]
@Transactional
fun rename(id: String, name: String) {
    repo.rename(id, name)
    invalidator.invalidate(userCache, id)    // runs in afterCommit
}
```

```java [Java]
@Transactional
public void rename(String id, String name) {
    repo.rename(id, name);
    invalidator.invalidate(userCache, id);   // runs in afterCommit
}
```

:::

- Invalidations of one cache within one transaction are merged into a single `invalidateAll`, deduplicated, in order.
- On rollback, the registered keys are invalidated too. The loader runs on your thread and joins the transaction, so a read after your write may already have put the uncommitted row in Redis, where every instance would read it until `ttl`. An extra invalidation is harmless. Loads inside the transaction are also invalidated on rollback even when nothing registered them, and a registered key loads privately for the rest of the transaction. See [Spring Cache](./spring-cache.md#transactional-loads).
- On a rollback to a savepoint (a `NESTED` transaction), every key registered so far is invalidated at once and again when the transaction ends. Spring doesn't report that rollback to `afterCompletion`, so an uncommitted row loaded inside the nested part would otherwise stay visible to every instance, including the outer transaction, until the outer transaction ends.
- A `REQUIRES_NEW` inner transaction invalidates when it ends.
- Late registrations are not lost. One made in another synchronization's `afterCommit` runs right away, or in `afterCompletion` if it is the transaction's first. In `afterCompletion` itself, for example in an `@TransactionalEventListener(AFTER_COMMIT)`, it invalidates immediately.
- One failing cache doesn't stop the others: all are invalidated, then the first failure is thrown with the rest suppressed (only with [`invalidate-failure: throw`](#redis-down)). After a rollback, Spring logs the failure instead of throwing it.
- Without an active transaction, it invalidates immediately.
- Only thread-bound transactions (`PlatformTransactionManager`, such as JDBC or JPA) are visible to it. In a reactive transaction (`ReactiveTransactionManager`, such as R2DBC, including `@Transactional` suspend functions), it invalidates immediately, which is before the commit. There, invalidate after the transaction ends, outside its boundary.

`@CacheEvict` and `@CachePut` on CoCache caches are deferred to after the commit as well, but skipped on rollback. `@CacheEvict(beforeInvocation = true)` invalidates at once and again when the transaction ends. See [Spring Cache](./spring-cache.md#transactions).

## Writers outside your application {#external-writers}

Batch jobs, CDC consumers, admin tools, and other services change the database without going through your caches. If they don't invalidate, caches stay stale until `ttl`.

On the JVM, such a writer uses `ExternalInvalidator` from `cocache-core`. It speaks the same protocol as the caches, with the same backend, and needs no cache declaration:

::: code-group

```kotlin [Kotlin]
@Bean(destroyMethod = "close")
fun externalInvalidator(backend: CoherenceBackend) = ExternalInvalidator(backend)

// after committing the change:
externalInvalidator.invalidate("UserCache", "42")                         // namespace, encoded key
externalInvalidator.invalidate("UserCache", listOf("42", "43"))           // several keys

// a cache with a non-default key prefix gets its own invalidator
val shopInvalidator = ExternalInvalidator(backend, "shop:")
shopInvalidator.invalidate("ProductCache", "7")
```

```java [Java]
ExternalInvalidator invalidator = new ExternalInvalidator(backend);

invalidator.invalidate("UserCache", "42");
invalidator.invalidate("UserCache", List.of("42", "43"));
new ExternalInvalidator(backend, "shop:").invalidate("ProductCache", "7");
```

:::

- Pass the cache's **namespace** (the cache name unless set) and the **encoded key**: the `toString()` or `keyExpression` result, as it appears in the storage key.
- Pass `keyPrefix` to the constructor when the cache doesn't use `cocache:`. The prefix also selects the channel, so a wrong one reaches no instance of the cache.
- A namespace outside `[A-Za-z0-9._-]+` throws `IllegalArgumentException`, as it does for a cache.
- Calls are idempotent: if one throws (for example, the delete succeeded but the broadcast failed), retry it.
- It deletes the keys from Redis and broadcasts the invalidation (in one script with the default channel mode). Every instance, including caches in the same process, drops its L2 copy.
- It doesn't retry. If Redis fails, it throws; retry the call yourself.
- An empty encoded key throws `IllegalArgumentException`; an empty collection does nothing. After `close()`, every call throws `IllegalStateException`. `close()` releases the invalidator's own store handles and doesn't close the backend.

Outside the JVM, any Redis client can do the same in two commands: `DEL {keyPrefix}{namespace}:{encodedKey}`, then `PUBLISH {keyPrefix}inv:{namespace}` with a small binary frame (`SPUBLISH` in sharded mode). [Troubleshooting](/operations/troubleshooting#external-invalidation) documents the frame format. Skipping the `PUBLISH` leaves other instances' L2 stale for up to `localMaxAge`.

## Choosing lifetimes {#lifetimes}

Each lifetime bounds one kind of staleness. Pick each for the failure it covers:

| Setting | Default | Bounds | Shorter means | Guidance |
|---------|---------|--------|---------------|----------|
| `ttl` | 1 h | Changes CoCache never hears about | More reloads | Long if every writer invalidates. `ttl-forever` is safe then. Shorter if some writers can't invalidate. |
| `ttl-jitter` | 1 min | — | — | Spreads expiries so keys loaded together don't expire together. Effective spread is capped at `ttl / 2`. |
| `absent-ttl` | 1 min | How long a newly created row stays invisible after a lookup cached its absence | More loads for missing keys | Keep it short, or invalidate the key when you create the row. |
| `local-max-age` | 5 min | L2 staleness when an invalidation message is lost | One more Redis read per key per period | Costs nothing on the hit path. Lower it for data where minutes of drift matter. |
| `degraded-local-max-age` | 10 s | Loads that couldn't be written to Redis | More loads while Redis is down | Rarely changed. |
| `lease-wait` | 200 ms | — | — | Other instances wait this long for a load in progress. Raise it if your loader is slower, to keep one load per cluster. |
| `lease-ttl` | 10 s | — | — | How long a crashed loader's lease blocks a key. Keep it above the loader's p99. |

```yaml
cocache:
  defaults:
    local-max-age: 2m
  caches:
    "[UserCache]":
      ttl-forever: true      # every writer invalidates
    "[PriceCache]":
      ttl: 30s               # fed by a system that can't invalidate
      absent-ttl: 5s
```

Bare numbers are seconds. Write a unit for anything shorter: `lease-wait: 500ms`, not `lease-wait: 0.5`.

The defaults in the table are those of the derived types. `Store`, `VersionedStore` and `SetStore` start from their own [default policy](./caches.md#store-defaults): `ttl` forever, `local-max-age` 30 s, `absent-ttl` 10 s.

## Refresh-ahead {#refresh-ahead}

When a hot key expires, the next reader pays the full load latency. Refresh-ahead hides that: a read that finds a value within `refreshWindow` of its Redis expiry returns it immediately and schedules one background reload.

```yaml
cocache:
  caches:
    "[UserCache]":
      ttl: 10m
      refresh-window: 1m
```

- The window must be less than `ttl − ttl-jitter` and less than `local-max-age`, or every fresh copy would start inside it. With the defaults (5 min `local-max-age`), the window must be under 5 minutes.
- Only one instance per key wins the refresh token in Redis and calls the loader. The cluster reloads each key once.
- The other instances keep serving their copy and read Redis again after a back-off: `lease-wait` (at least 100 ms), and never more than half the old value's remaining lifetime. So they pick up the reloaded value before the old one expires, and while the reload runs they read Redis a bounded number of times, however hot the key is.
- A value that lives no longer than the window, from a short `ttlOf` or `put(key, value, ttl)`, is not refreshed: it would start inside the window, and every hit would reload it. It expires and reloads on the next miss. Likewise, an instance that first reads a value inside its window doesn't start a refresh for that copy; copies on other instances still do.
- Only caches with a loader refresh. Negative entries are not refreshed.
- A failed or skipped refresh only logs. The value expires normally, so staleness bounds never depend on refresh.
- Refreshes run on a bounded pool (at most 4 threads, queue of 1024). When it is full, refreshes are skipped and counted in `cocache.refresh.rejections`. [`cocache.refresh-executor`](./configuration.md#executors) names another `Executor` bean to use instead.

To turn off a window that a declaration enables, use any of: `refresh-window: 0` in the [properties](./configuration.md#policy-properties), `@CoCache(refreshWindow = 0)`, or `refreshWindow = null` in a [customizer](./caches.md#customizer).

## When Redis is down during a write {#redis-down}

Every failed write throws `CacheWriteException`, except a `Cache.invalidate` that is retried. Its `outcomeUnknown` says what you know:

- `false`: definitely not executed. The client rejected the command before sending it (for example while disconnected), or Redis refused the write because the key holds content this version can't read. Nothing changed.
- `true`: outcome unknown. The command may have run (a timeout, a connection dropped mid-reply), and it may still run later. Only ordered writes stay harmless when they land late: a replayed `putIfNewer`, a conditional write (with the ABA caveat above).

**`Cache.invalidate`** applies its local effects first (this instance's L2 is dropped). If the Redis delete fails:

- The default (`invalidate-failure: retry`) **doesn't throw**: deleting a derived copy is always safe, and invalidations usually run after a commit, where an exception can't roll anything back. The delete goes to a bounded retry queue: up to 8 attempts, backing off from 100 ms to 10 s, at most 10 000 pending. This holds for externally sourced caches too.
- While the retry is pending, misses for that key on this instance skip Redis (which still holds the old value) and load from the source. An externally sourced cache reads `null` meanwhile, as an unfilled answer rather than a negative entry, so `get(key, loader)` still calls its loader.
- If the retries are exhausted or the queue overflows, CoCache logs an error and counts `cocache.invalidations.dropped`. Other instances can then reload the old value from Redis until it expires, so the bound is the value's remaining TTL. With `ttl-forever` there is no bound: invalidate again once Redis is back.
- With `invalidate-failure: throw`, the call throws `CacheWriteException` instead, and you decide.

**`Store.delete`** and **`deleteIfMatch`**, and `purge` on the versioned types, throw and are never retried: a delayed delete of the only copy could remove a value written after the failure.

**`put`** and **`putAll`**, and the `SetStore` writes `add`, `remove`, and `clear`, throw `CacheWriteException`. When the outcome is unknown, retrying a `put` is safe for a `Cache` (the next read reloads anyway); on a `Store` the retry is a new last-writer-wins write.

The retry queue's limits come from `RetryPolicy` on the `CacheRuntime`. To change them in Spring Boot, declare your own `CacheRuntime` bean ([Configuration Reference](./configuration.md#beyond-properties)). Watch the queue in the health details (`pendingInvalidations`) and in [Observability](/operations/observability).
