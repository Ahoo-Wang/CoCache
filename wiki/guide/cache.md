---
title: Cache
description: The derived cache type — when to use Cache, declaring it with a loader or as externally sourced, sync, async, suspending and per-call loaders, reading, invalidate and put, defaults, and what happens when Redis fails.
---

# Cache

A `Cache<K, V>` holds a **derived copy**: something the process can fetch again from a source of truth, such as a database row or a remote API response. Losing a key in Redis costs one reload, never data. It is the type to use unless the data has no source ([choosing a type](./caches.md#types)).

| | `Cache<K, V>` |
|-|---------------|
| Data comes from | A `loader` or `asyncLoader`, or the callers (`externallySourced()`) |
| Reads | `get`, `getAll`, `lookup`, `lookupAll`, `get(key, loader)` |
| Writes | `invalidate`, `invalidateAll` (preferred); `put`, `putAll` (last writer wins) |
| On a Redis read failure | Loads from the source (`read-failure: degrade`) |
| On a Redis write failure | `invalidate` is retried in the background; an outcome-unknown `put` is compensated |
| Default lifetimes | `ttl` 1 h ± 1 min, `local-max-age` 5 min, `absent-ttl` 1 min |
| Spring Cache, `TransactionalInvalidator` | Yes, the only type they accept |

## Declare it {#declare}

As an interface, with a loader bean matched by its generic type:

::: code-group

```kotlin [Kotlin]
@CoCache(ttl = 600)
interface UserCache : Cache<String, User>

@Bean
fun userLoader(repo: UserRepository): CacheLoader<String, User> =
    CacheLoader { id -> repo.findById(id) }      // null = not found

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App
```

```java [Java]
@CoCache(ttl = 600)
public interface UserCache extends Cache<String, User> {}

@Bean
CacheLoader<String, User> userLoader(UserRepository repo) {
    return id -> repo.findById(id).orElse(null);  // null = not found
}
```

:::

Or as a spec bean, which sets its source and codec in code:

::: code-group

```kotlin [Kotlin]
@Bean
fun productSpec(mapper: ObjectMapper, repo: ProductRepository): CacheSpec<String, Product> =
    cacheSpec("product") {
        codec = jacksonCodec<Product>(mapper)
        loader = CacheLoader { id -> repo.findById(id) }
        policy { ttl = Duration.ofMinutes(10) }
    }
```

```java [Java]
@Bean
CacheSpec<String, Product> productSpec(ObjectMapper mapper, ProductRepository repo) {
    return CacheSpec.<String, Product>builder("product")
        .codec(JacksonCodec.of(mapper, Product.class))
        .loader(id -> repo.findById(id).orElse(null))
        .updatePolicy(policy -> policy.ttl(Duration.ofMinutes(10)))
        .build();
}
```

:::

A `Cache` must say where its data comes from: a `CacheSpec` sets exactly one of `loader`, `asyncLoader` and `externallySourced()`, and an interface finds its loader bean or declares `@CoCache(source = CoCache.Source.EXTERNAL)`. Anything else fails startup. [Declaring Caches](./caches.md) covers the annotations, spec beans, keys and codecs that every type shares.

## Loaders {#loaders}

`CacheLoader<K, V>` loads what the cache doesn't have. It only loads; the cache decides how long values live.

```kotlin
class UserLoader(private val repo: UserRepository) : CacheLoader<String, User> {
    override fun load(key: String): User? = repo.findById(key)      // null = doesn't exist

    // Optional: getAll calls this once for every key that missed both levels.
    // Keys missing from the result count as "doesn't exist".
    override fun loadAll(keys: Collection<String>): Map<String, User> =
        repo.findAllById(keys).associateBy { it.id }
}
```

- **`null` means "doesn't exist"** and is cached as a negative entry for `absent-ttl`. Throw if the lookup *failed*: the exception reaches the caller (and every caller sharing the load), and nothing is cached.
- **The default `loadAll`** calls `load` once per key. Override it with a batch query if you use `getAll`.
- **The loader runs on the calling thread**, so transactions, `SecurityContext` and MDC are available. Through the [async view](./async.md) it runs on the blocking executor instead.
- **Don't read the same key from inside its own loader** on the same thread. That fails fast with `IllegalStateException`.
- **Interfaces** find the loader by type `CacheLoader<K, V>`, then `AsyncCacheLoader<K, V>`. If several beans match, or one of each, startup fails; name one with `@CoCache(loader = "…")`.
- **Non-blocking sources** implement `AsyncCacheLoader<K, V>` (`loadAsync` returns a `CompletionStage`) and set it as `asyncLoader`. In Kotlin, `suspendingLoader { … }` sets a suspend function as the async loader. A cache has one source, sync or async, and every view works with either: see [Async API](./async.md#loader) and [Kotlin Coroutines](./coroutines.md#loader).

### Per-call loaders {#per-call-loader}

`get(key) { k -> … }` loads a miss with the loader you pass instead of the declared one, with the same protection: one load per key per instance, the Redis lease across instances, and the invalidation fence. Spring's `@Cacheable(sync = true)` uses it, with the annotated method as the loader.

```kotlin
val user = users.get(id) { key -> userClient.fetch(key) }
```

## Externally sourced caches {#externally-sourced}

`externallySourced()` (`@CoCache(source = EXTERNAL)` on an interface) declares a `Cache` whose values come from its callers: `put`, a [Spring Cache](./spring-cache.md) method, or a per-call loader. It is still a derived copy, so it gets a derived copy's defaults: a 1 h `ttl`, and a failed `invalidate` retried in the background.

```kotlin
@CoCache(source = CoCache.Source.EXTERNAL)
interface UserCache : Cache<String, User>          // filled by @Cacheable(sync = true) methods
```

- **A miss is "unfilled".** When Redis doesn't have the key, a read without a loader returns `null` (`Lookup.Absent`). That answer goes into L2, fenced like any fill and capped at `absent-ttl` and `local-max-age`, but a call that brings its own loader doesn't accept it: `get(key) { … }` loads anyway. Nothing is written to Redis.
- **A Redis read failure has no answer** for a read without a loader: [`Lookup.Unknown`](./caches.md#unknown), so `get` throws `CacheReadException`. Declare `unknownRead = UnknownReadMode.ABSENT` (`@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)`) when "absent" is a safe answer. A read with a per-call loader follows `read-failure` and, by default, loads from that loader.
- **Unreadable content** makes a read without a loader throw `CacheReadException`; a read with a loader reclaims it and reloads. Content written by a newer CoCache reads as unfilled.

Use an externally sourced `Cache` only for data that *can* be rebuilt, by its callers. Data whose only copy is Redis belongs in a [`Store`](./store.md): declared as a `Cache`, it would expire after an hour, and a retried `invalidate` could delete a value written in the meantime.

## Reading {#reading}

```kotlin
users["42"]                         // L2 → Redis → loader; null when the loader found nothing
users.getAll(listOf("1", "2"))      // only the keys that exist
users.lookup("42")                  // Present, Absent or Unknown
```

- A loader that returns `null` writes a **negative entry**: reads of that key return `null` for `absent-ttl` without calling the loader. Invalidate the key when you create the row ([a new record is reported as missing](../operations/troubleshooting.md#negative-entry)).
- When Redis fails, a read with a source loads from it and keeps the result in L2 for `degraded-local-max-age` (10 s), without writing Redis. `read-failure: throw` makes such a read [`Unknown`](./caches.md#unknown) instead.
- [Refresh-ahead](./freshness.md#refresh-ahead) reloads hot keys in the background before they expire, once per cluster. It needs a loader.

## Writing {#writing}

**Change the source of truth, then invalidate** ([the rule](./freshness.md#rule)):

```kotlin
repo.rename(id, name)          // committed
users.invalidate(id)           // every instance drops the key; the next read loads the new row
```

- `invalidate` and `invalidateAll` delete the key in Redis, destroy any load lease, and broadcast, so a load that started before the change can't write its old result back. Inside a Spring transaction, use [`TransactionalInvalidator`](./freshness.md#transactions) to run it after the commit.
- `put(key, value)` and `putAll(entries)` overwrite Redis and broadcast the same invalidation, but don't fill this instance's L2. `put(key, value, ttl)` sets an explicit lifetime. Prefer `invalidate`: it can never cache a value that differs from the source ([`invalidate` or `put`?](./freshness.md#invalidate-vs-put)).
- After either call returns, the next read on the same instance sees the change. Other instances see it once the invalidation message arrives.

## When Redis fails {#failures}

| Operation | Default | Opt-in |
|-----------|---------|--------|
| Read with a source | Loads from the source, keeps the result in L2 for `degraded-local-max-age` | `read-failure: throw`: `Lookup.Unknown`, `get` throws `CacheReadException` |
| Read without a loader (externally sourced) | `Lookup.Unknown`: `get` throws `CacheReadException` | `unknownRead = ABSENT`: `null`, nothing cached |
| `invalidate`, `invalidateAll` | Doesn't throw: the delete is retried in the background (up to 8 attempts), and meanwhile misses on this instance skip Redis | `invalidate-failure: throw`: `CacheWriteException` |
| `put`, `putAll`, outcome unknown (a timeout) | Doesn't throw: CoCache queues an invalidation of those keys, so they converge to "reload from the source" | `invalidate-failure: throw`: `CacheWriteException` |
| `put`, `putAll`, definitely not executed (rejected while disconnected) | Throws `CacheWriteException` with `outcomeUnknown = false` | — |

Deleting a derived copy is always safe, which is why a `Cache` retries instead of throwing: an invalidation usually runs after a commit, where an exception can't roll anything back. [When Redis is down during a write](./freshness.md#redis-down) has the details.

## Views {#views}

`users.async()` is an `AsyncCache<K, V>` and `users.suspending()` a `SuspendingCache<K, V>`, with the same methods returning a `CompletableFuture` or suspending. They are the same cache: one L2, one set of fences, shared in-flight loads. See [Async API](./async.md) and [Kotlin Coroutines](./coroutines.md).

## Related {#related}

- [Keeping Data Fresh](./freshness.md): the write rule, transactions, writers outside your application, lifetimes.
- [Spring Cache](./spring-cache.md): `@Cacheable(sync = true)` over an externally sourced `Cache`.
- [JoinCache](./join-cache.md): reading two caches as one.
- [`VersionedCache`](./versioned-cache.md): a derived copy whose writer orders its writes by version.
