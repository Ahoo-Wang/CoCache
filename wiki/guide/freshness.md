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
- Refreshes run on a bounded pool (at most 4 threads, queue of 1024). When it is full, refreshes are skipped and counted in `cocache.refresh.rejections`.

To turn off a window that a declaration enables, use any of: `refresh-window: 0` in the [properties](./configuration.md#policy-properties), `@CoCache(refreshWindow = 0)`, or `refreshWindow = null` in a [customizer](./caches.md#customizer).

## When Redis is down during a write {#redis-down}

**`invalidate`** applies its local effects first (this instance's L2 is dropped). If the Redis delete fails:

- By default (`invalidate-failure: retry`) the call **doesn't throw**. Invalidations usually run after a commit, where an exception can't roll anything back. The delete goes to a bounded retry queue: up to 8 attempts, backing off from 100 ms to 10 s, at most 10 000 pending.
- While the retry is pending, misses for that key on this instance skip Redis (which still holds the old value) and load from the source.
- If the retries are exhausted or the queue overflows, CoCache logs an error and counts `cocache.invalidations.dropped`. Other instances can then reload the old value from Redis until it expires, so the bound is the value's remaining TTL. With `ttl-forever` there is no bound: invalidate again once Redis is back.
- With `invalidate-failure: throw`, the call throws `CacheWriteException` instead, and you decide.

**`put`**, and the `SetCache` writes `add`, `remove`, and `clear`, throw `CacheWriteException`. The outcome may be unknown after a timeout, but these operations are idempotent, so retrying is safe.

The retry queue's limits come from `RetryPolicy` on the `CacheRuntime`. To change them in Spring Boot, declare your own `CacheRuntime` bean ([Configuration Reference](./configuration.md#beyond-properties)). Watch the queue in the health details (`pendingInvalidations`) and in [Observability](/operations/observability).
