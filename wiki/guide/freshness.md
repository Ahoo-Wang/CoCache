---
title: Keeping Data Fresh
description: Write the source then invalidate, invalidate after commit, handle writers outside your application, choose TTLs and refresh-ahead, and know what happens when Redis is down during a write.
---

# Keeping Data Fresh

A cache is only as fresh as the writes it hears about. This page covers how to tell CoCache about changes, and how to choose the lifetimes that bound everything it can't hear about.

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
- **`SetCache.putAll`** replaces each set, and an empty set clears its key, exactly like `put`.

A custom `Cache` implementation that doesn't override `putAll` falls back to calling `put` once per entry.

## Conditional writes {#conditional-writes}

When Redis is the only copy and several writers update one value by reading it, changing it, and writing it back (for example a document that holds many records), a plain `put` loses updates: two writers read the same version and the second `put` overwrites the first. Since 6.7, `putIfMatch` writes only if Redis still holds the value the writer read, and `getIfChanged` reads that value from Redis together with its digest:

```kotlin
fun update(key: String, change: (Document?) -> Document) {
    while (true) {
        val current = cache.getIfChanged(key, null)              // reads Redis, never L2
        val (document, digest) = when (current) {
            is ConditionalGet.Present -> current.value to current.digest
            else -> null to null                                  // Absent: no value yet
        }
        when (cache.putIfMatch(key, digest, change(document))) {
            is ConditionalPut.Written -> return
            is ConditionalPut.Conflict -> continue               // someone else wrote first: read again
        }
    }
}
```

- **The digest** is the SHA-1 of the value's bytes as the codec encoded them. `ConditionalGet.Present` and `ConditionalPut.Written` both return it. A codec that encodes equal values to different bytes (an unordered map, say) produces different digests.
- **`putIfMatch(key, expected, value)`** compares and writes in one Redis script. With `expected = null` it writes only if Redis holds no value: the key is absent, a load lease is in place, or the key holds a negative entry. Otherwise it writes only if the value's digest equals `expected`. A conflict returns `Conflict(current)` (`null` when Redis holds no value) and changes nothing: no write, no TTL change, no invalidation message. A successful write is a `put`: same TTL rules (`ttlOf` or policy, or the `ttl` argument), same invalidation of every instance's L2, and this instance's next read sees it.
- **`getIfChanged(key, known)`** asks Redis whether the value still has digest `known`. If it does, the answer is `Unchanged` and the value isn't transferred: Redis sends back two integers, so confirming a large value costs a round trip, not its size on the wire. It doesn't save Redis CPU: the script still reads the value and computes its SHA-1 every time, and Redis serves nothing else meanwhile (about 20 ms for an 8 MB value on Redis 7.4, of which about 13 ms is the read that a plain L1 read pays too). Size the revalidation interval with instances × values × size in mind. Otherwise it returns `Present(value, digest)` or `Absent`. Use it to revalidate a value you hold in memory: keep the digest, and call `getIfChanged` on a schedule or when an invalidation arrives.
- **Both bypass L2.** They read and write Redis only, never load from the loader, and never fill L2. The cache's L2 may be older than what `getIfChanged` returns, within `localMaxAge`.
- **Failures:** `getIfChanged` throws `CacheReadException` when Redis fails, whatever `read-failure` says, because there is nothing to degrade to. `putIfMatch` throws `CacheWriteException`. It isn't idempotent: after a timeout, call `getIfChanged` first. If the current digest is the one `Written` would have returned, the write went through.
- **Unreadable content** is never treated as an empty value by one method and as a value by the other. A value written by a newer CoCache, or a set, makes both methods throw with a `ForeignFormatException` cause and leaves the key alone. A value the codec can't decode makes `getIfChanged` throw. `putIfMatch` can't see the codec, so on such a value it only ever returns `Conflict` and never overwrites it. Content CoCache 6 didn't write (5.x data) follows `foreign-format`, as for a cache without a loader: with `fail` (the default) both methods throw; with `reclaim` it counts as no value, so `getIfChanged` returns `Absent` and `putIfMatch(key, null, value)` replaces it.
- **Custom backends** must implement the conditional write atomically or not at all: the SPI's default `putIfMatch` throws `UnsupportedOperationException`, which reaches the caller.
- **Every writer must use `putIfMatch`.** A plain `put` doesn't check and still wins over a concurrent read-modify-write.
- `SetCache` doesn't support them, since its `add` and `remove` are already atomic. The async view and the [suspending view](./coroutines.md) have the same methods.

## Versioned writes {#versioned-writes}

Some writers know the version of every value they write: an event-sourced aggregate has one writer and strictly increasing versions, a CDC stream carries commit sequence numbers. The writer can push each new state into Redis right after the commit, but redelivery and replay send older versions again, and a plain `put` of an older version would replace the newer one. Since 6.8, a cache declared *versioned* stores the version next to each value and orders writes by it:

```kotlin
val views = runtime.create(cacheSpec<String, OrderView>("order-view") {
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

- **Declare it.** Set `versioned = true`, or a `versionedLoader` / `versionedAsyncLoader` that returns `Versioned(value, version)` (the [suspending](./coroutines.md) builder has `suspendingVersionedLoader`). A loader that returns `Versioned(null, n)` reports a key deleted at version `n`: the load writes the same tombstone as `invalidateIfNewer(key, n)`, so a replayed older state can't bring it back. Returning `null` means the key never existed and caches a plain negative entry for `absent-ttl`, which any versioned write replaces. The versioned methods throw `UnsupportedOperationException` on any other cache, and creating a versioned cache fails at once on a backend that doesn't support versions. The Redis and in-memory backends do.
- **`putIfNewer(key, value, version[, ttl])`** compares and writes in one Redis script, so it's one round trip. It writes only when `version` is greater than the version in Redis, and returns `Written`. An equal version is a duplicate delivery and an older one is late; both return `Stale(current)` and change nothing: no write, no TTL change, no invalidation message. Versions compare as signed 64-bit numbers. A value without a version counts as older than any version: a plain `put`, a load from a loader that doesn't know versions, a negative entry, a load lease, or no key at all. A successful write is a `put`: it fences out every load in flight and evicts every instance's L2.
- **A load in flight never wins.** If a reader holds the load lease while `putIfNewer` lands, its write-back is refused, even when it read a higher version from the source. The writer's next version arrives through `putIfNewer` anyway. A load that completes first stores the loader's version, and later writes compare against it.
- **`invalidateIfNewer(key, version[, ttl])`** writes a tombstone: readers see the key as absent (without calling the loader), and `putIfNewer` with that version or an older one returns `Stale`. A newer version can bring the key back. The tombstone lives as long as a value would (`ttl` with jitter, or the `ttl` argument), not `absent-ttl`. Once it expires, a late older version can be written again, so give tombstones a lifetime that covers your redelivery window.
- **`getVersioned(key)`** reads through like `get` and returns the value with its version. It returns `null` for a tombstone, the same as for an absent key; while the tombstone lives, `putIfNewer` with its version or a lower one is still refused. A value without a version reports `Versioned.NO_VERSION`.
- **Ordering holds while the entry lives.** Once a value or tombstone expires, the key holds no version, and any version can be written again, including an older one. Size `ttl` (and the tombstone `ttl`) to your redelivery horizon. A writer that replays its stream to the head ends on the newest version anyway, so after an expiry the staleness is bounded by that replay.
- **Plain writes drop the version.** `put`, `putAll`, `putIfMatch` and `invalidate` write content without a version, and so does expiry, after which any version can be written. Every writer that needs ordering must use `putIfNewer` / `invalidateIfNewer`. Keep `invalidate` for operator purges.
- **No refresh-ahead.** A versioned cache rejects `refresh-window`; `cocache.defaults.refresh-window` skips versioned caches. Writers push new versions, so values don't need to be refreshed.
- **Failures:** `putIfNewer` throws `CacheWriteException` when Redis fails. Retrying the same version is safe: if the first attempt went through, the retry returns `Stale(version)`. Unreadable content is classified as for [conditional writes](#conditional-writes).
- **Mixed versions:** instances on 6.0 to 6.7 can share Redis with 6.8 and read versioned values, but their writes don't keep versions. Upgrade every writer of a versioned cache to 6.8.
- The async view and the suspending view have the same methods. A meter, `cocache.versioned.stale`, counts refused versions.

## Inside a transaction {#transactions}

Inside a Spring transaction, "after the commit" is not where your code runs. `TransactionalInvalidator` (registered by the starter when `spring-tx` is present) moves the invalidation there:

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
- On rollback, nothing is invalidated, because nothing changed.
- A `REQUIRES_NEW` inner transaction invalidates after its own commit.
- Without an active transaction, it invalidates immediately.

`@CacheEvict` and `@CachePut` on CoCache caches are deferred to after the commit in the same way. See [Spring Cache](./spring-cache.md#transactions).

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
- Pass `keyPrefix` to the constructor when the cache doesn't use `cocache:`.
- Calls are idempotent: if one throws (for example, the delete succeeded but the broadcast failed), retry it.
- It deletes the keys from Redis and broadcasts the invalidation (in one script with the default channel mode). Every instance, including caches in the same process, drops its L2 copy.
- It doesn't retry. If Redis fails, it throws; retry the call yourself.
- An empty encoded key throws `IllegalArgumentException`; an empty collection does nothing. After `close()`, every call throws `IllegalStateException`. `close()` releases the invalidator's own store handles and doesn't close the backend.

Outside the JVM, any Redis client can do the same in two commands: `DEL {keyPrefix}{namespace}:{encodedKey}`, then `PUBLISH cocache:inv:{namespace}` with a small binary frame (`SPUBLISH` in sharded mode). [Troubleshooting](/operations/troubleshooting#external-invalidation) documents the frame format. Skipping the `PUBLISH` leaves other instances' L2 stale for up to `localMaxAge`.

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
- Only one instance per key wins the refresh token in Redis and calls the loader. The others re-read the refreshed value. The cluster reloads each key once.
- Only caches with a loader refresh. Negative entries are not refreshed.
- A failed or skipped refresh only logs. The value expires normally, so staleness bounds never depend on refresh.
- Refreshes run on a bounded pool (at most 4 threads, queue of 1024). When it is full, refreshes are skipped and counted in `cocache.refresh.rejections`. [`cocache.refresh-executor`](./configuration.md#executors) names another `Executor` bean to use instead.

To turn off a window that a declaration enables, use any of: `refresh-window: 0` in the [properties](./configuration.md#policy-properties), `@CoCache(refreshWindow = 0)`, or `refreshWindow = null` in a [customizer](./caches.md#customizer).

## When Redis is down during a write {#redis-down}

**`invalidate`** applies its local effects first (this instance's L2 is dropped). If the Redis delete fails:

- By default (`invalidate-failure: retry`) the call **doesn't throw**. Invalidations usually run after a commit, where an exception can't roll anything back. The delete goes to a bounded retry queue: up to 8 attempts, backing off from 100 ms to 10 s, at most 10 000 pending.
- While the retry is pending, misses for that key on this instance skip Redis (which still holds the old value) and load from the source.
- If the retries are exhausted or the queue overflows, CoCache logs an error and counts `cocache.invalidations.dropped`. Other instances can then reload the old value from Redis until it expires, so the bound is the value's remaining TTL. With `ttl-forever` there is no bound: invalidate again once Redis is back.
- With `invalidate-failure: throw`, the call throws `CacheWriteException` instead, and you decide.

**`put`** and **`putAll`**, and the `SetCache` writes `add`, `remove`, and `clear`, throw `CacheWriteException`. The outcome may be unknown after a timeout, but these operations are idempotent, so retrying is safe.

The retry queue's limits come from `RetryPolicy` on the `CacheRuntime`. To change them in Spring Boot, declare your own `CacheRuntime` bean ([Configuration Reference](./configuration.md#beyond-properties)). Watch the queue in the health details (`pendingInvalidations`) and in [Observability](/operations/observability).
