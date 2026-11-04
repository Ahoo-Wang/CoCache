---
title: Store
description: The authoritative type for values whose only copy is Redis — when to use Store, declaring it, reads without a lease, put and synchronous delete, conditional writes with getIfChanged, putIfMatch and deleteIfMatch, digests, defaults, durability, and Redis failures.
---

# Store

A `Store<K, V>` holds the **only copy** of its values, in Redis: a policy snapshot published by a control plane, a revocation list, a session, a one-time code, an idempotency key, a projection that only an offline job can rebuild. There is no loader. If Redis loses a key, it is gone, so a `Store` never deletes on its own, never retries a delete, and fails a read loudly rather than guess.

| | `Store<K, V>` |
|-|---------------|
| Data comes from | Your writes |
| Reads | `get`, `getAll`, `lookup`, `lookupAll`; `getIfChanged` reads Redis with a digest |
| Writes | `put`, `putAll` (last writer wins); `putIfMatch` (compare and write) |
| Deletes | `delete`, `deleteIfMatch`: synchronous, never retried |
| On a Redis read failure | `Lookup.Unknown`: `get` throws `CacheReadException`, unless `unknownRead = ABSENT` |
| On a Redis write failure | Throws `CacheWriteException`; never compensated or retried |
| Default lifetimes | `ttl` forever, `local-max-age` 30 s, `absent-ttl` 10 s |
| Needs | A Redis that doesn't evict its keys ([eviction check](./configuration.md#eviction-check)) |

## Declare it {#declare}

::: code-group

```kotlin [Kotlin]
@CoCache(keyPrefix = "flag:")
interface FeatureFlagStore : Store<String, String>

@EnableCoCache(caches = [FeatureFlagStore::class])
@SpringBootApplication
class App

// or as a spec bean
@Bean
fun snapshotSpec(mapper: ObjectMapper): StoreSpec<String, PolicySnapshot> =
    storeSpec("policy-snapshot") {
        codec = jacksonCodec<PolicySnapshot>(mapper)
    }
```

```java [Java]
@CoCache(keyPrefix = "flag:")
public interface FeatureFlagStore extends Store<String, String> {}

@Bean
StoreSpec<String, PolicySnapshot> snapshotSpec(ObjectMapper mapper) {
    return StoreSpec.<String, PolicySnapshot>builder("policy-snapshot")
        .codec(JacksonCodec.of(mapper, PolicySnapshot.class))
        .build();
}
```

:::

A `Store` has no source: `@CoCache(loader)` or `source = EXTERNAL` on a `Store` interface fails startup. For a `Store` interface, startup logs a warning when a `CacheLoader` or `AsyncCacheLoader` bean with its key and value types exists: data that a loader can rebuild belongs in a [`Cache`](./cache.md). Creating a `Store` also needs a backend whose store supports conditional reads and writes; Redis and the in-memory backend do.

## Reading {#reading}

```kotlin
flags["dark-mode"]                  // null when Redis doesn't have it
flags.lookup("dark-mode")           // Present, Absent or Unknown
```

- **Reads never take a lease.** A key Redis doesn't have, including one that holds another cache's load lease, reads as `Lookup.Absent`, and that answer goes into L2 like a value, for at most `absent-ttl` (10 s).
- **A Redis failure has no answer**: [`Lookup.Unknown`](./caches.md#unknown). `get` throws `CacheReadException`, and `getAll` finishes the other keys first, then throws with `unknownKeys` and `partialResult`. Use `lookupAll` to handle each key yourself.
- **`unknownRead = UnknownReadMode.ABSENT`** returns `null` instead and caches nothing, so the next read asks Redis again. Declare it only where "absent" is a safe answer: for a revocation list it means "not revoked" while Redis is down. Set it in the spec, with `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)`, in a customizer, or with `cocache.caches.[name].unknown-read: absent`; no global layer sets it. A library that relies on it [locks](./configuration.md#locks) `UNKNOWN_READ`.
- **`read-timeout`** bounds each Redis read of the store. A store that can't fall back to a source can still refuse to wait a full `command-timeout` for a stalled Redis: the read then counts as failed and `unknownRead` decides.
- **Every value carries a writer id and the SHA-256 of its payload** in its header. Reads recompute the digest: a value whose payload doesn't match, or that has no digest because another type wrote it, throws `CacheReadException` and is kept as it is.

## Writing and deleting {#writing}

```kotlin
flags.put("dark-mode", "on")                       // last writer wins
flags.put("banner", "on", Duration.ofHours(1))     // an explicit lifetime
flags.delete("dark-mode")                          // synchronous; throws if Redis fails
```

- `put` and `putAll` overwrite Redis, destroy any load lease, and broadcast, so every instance drops its L2 copy. They don't fill this instance's L2; the next local read costs one round trip.
- `delete` completes before it returns or throws `CacheWriteException`. It is never queued for a background retry: a delayed delete of the only copy could remove a value written after the failure. When deletes must survive retries and reordering, use a [`VersionedStore`](./versioned-store.md) and `deleteIfNewer`.
- A failed `put`, `putAll` or `delete` always throws, whatever the outcome. `CacheWriteException.outcomeUnknown` says whether it may have reached Redis. Retrying a `put` is a new last-writer-wins write.

## Conditional writes {#conditional-writes}

When several writers update one value by reading it, changing it and writing it back (for example a document that holds many records), a plain `put` loses updates: two writers read the same value and the second `put` overwrites the first. `putIfMatch` writes only if Redis still holds the value the writer read, and `getIfChanged` reads that value from Redis together with its digest:

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
- **All three bypass L2.** They read and write Redis only and never fill L2. The store's L2 may be older than what `getIfChanged` returns, within `local-max-age`.
- **Failures:** `getIfChanged` throws `CacheReadException` when Redis fails, whatever `unknownRead` says, because there is nothing to degrade to. `putIfMatch` and `deleteIfMatch` throw `CacheWriteException`. They aren't idempotent: when the exception's `outcomeUnknown` is `true` (a timeout, say), call `getIfChanged` first. If the current digest is the one `Written` would have returned, the write went through. `outcomeUnknown = false` means the write didn't happen.
- **ABA:** a digest match proves the value is the one you read, not that nothing happened in between. A write whose outcome is unknown may run late and match a value that was written again with the same content, and a late `deleteIfMatch` can delete it. Writers that need strict ordering use a [`VersionedStore`](./versioned-store.md).
- **Without atomic broadcast** (the `sharded` [channel mode](../operations/index.md#channel-mode), or when the startup probe falls back), the invalidation is published after the script. If the write lands in Redis but that publish fails, the call throws `CacheWriteException`. Replaying the same write then returns `Conflict(current)` whose digest is that of your own value (for `deleteIfMatch`: a conflict with no current value), and CoCache publishes the invalidation again so other instances drop their stale copies. Other conflicts publish nothing.
- **Unreadable content** is never treated as an empty value by one method and as a value by another. A value written by a newer CoCache, a set, a value without a digest (written by a derived cache), a versioned value, a tombstone or an evicted versioned key makes all three throw with a `ForeignFormatException` cause and leaves the key alone. A value the codec can't decode, or whose payload doesn't match its digest, makes `getIfChanged` throw. `putIfMatch` and `deleteIfMatch` can't see the codec, so on a value it can't decode they only ever return `Conflict` and never change it. Content in a format this version can't read (such as a value another program wrote) makes them throw too: it never counts as no value, so `putIfMatch(key, null, value)` never replaces it.
- **Every writer must use `putIfMatch`.** A plain `put` doesn't check and still wins over a concurrent read-modify-write.

The [async](./async.md) and [suspending](./coroutines.md) views have the same methods.

## Defaults and lifetimes {#defaults}

A `Store` starts from `DefaultPolicies.STORE`: `ttl` forever, `local-max-age` 30 s, `absent-ttl` 10 s ([default policy per type](./caches.md#store-defaults)). The L2 lifetimes are shorter than a `Cache`'s because a lost invalidation can't be corrected by a reload: L2's own bound is the only one left. Write longer ones on the declaration, or for every authoritative cache with `cocache.defaults.store.*`.

Data whose lifetime is its meaning (a session, a one-time code, an idempotency key) needs an explicit `ttl` on the declaration, or a per-call one with `put(key, value, ttl)` or `ttlOf`. Only the declaration and `cocache.caches.[name].ttl` set a `Store`'s `ttl`; `cocache.defaults` and `cocache.overrides` never do, so one line of global configuration can't make every store expire ([what each layer may set](./configuration.md#field-layers)).

## Durability {#durability}

- **Durability is Redis durability.** Enable persistence (AOF or RDB) on the Redis that holds stores. An asynchronous-replication failover can lose recent writes ([persistence and failover](../operations/index.md#failover)).
- **Redis must not evict the keys.** With a `Store` declared, startup reads every primary's `maxmemory-policy`: `allkeys-*` always fails startup, and so does a policy it can't read unless `cocache.redis.assume-maxmemory-policy` names it. Under `volatile-*`, a store with a finite `ttl` can be evicted before it expires, which `cocache.redis.store-eviction-check` decides about ([eviction check](./configuration.md#eviction-check)).
- **Unreadable content** (a format this version can't read, a value another program wrote, a newer CoCache's value) throws `CacheReadException` and is never deleted, because deleting it would delete the only copy.

## Operating it {#operations}

- The actuator endpoint's per-key `DELETE` deletes the only copy, so it needs `management.endpoint.cocache.allow-store-delete=true`; without it the request returns `403` and changes nothing ([invalidating a key](../operations/observability.md#invalidate-key)).
- `ExternalInvalidator.invalidate` on a store's namespace deletes the only copy too. To make other instances drop a stale L2 copy while Redis already holds the right value, use `ExternalInvalidator.broadcast`, which leaves Redis alone ([writers outside your application](./freshness.md#external-writers)).
- All declarations of one namespace must be the same type. A `Cache` or `VersionedCache` that reads a value a `Store` wrote counts it in `cocache.type.mismatch` and logs once.

## Views {#views}

`async()` returns an `AsyncStore` and `suspending()` a `SuspendingStore`. A `Store` can't sit behind Spring Cache or `TransactionalInvalidator`: it has no `invalidate`, and a cache operation that names one fails startup.
