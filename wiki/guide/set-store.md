---
title: SetStore
description: The authoritative set type — when to use SetStore, declaring it with an element codec, atomic add and remove, put and clear, reads, defaults, failures, and when a database-owned set should be a Cache instead.
---

# SetStore

A `SetStore<K, E>` holds the **only copy of a set**, in Redis: an index, a membership list, the online sessions of a room. Elements are added and removed atomically in Redis, so concurrent updates from different instances are never lost, and no instance has to read the whole set to change it.

| | `SetStore<K, E>` (a `ReadableCache<K, Set<E>>`) |
|-|------------------------------------------------|
| Data comes from | Your element writes |
| Reads | `get` (an empty set when absent), `getAll`, `lookup`, `lookupAll` |
| Writes | `add`, `remove` (atomic per element); `put`, `putAll` (replace the set) |
| Deletes | `clear` |
| On a Redis read failure | `Lookup.Unknown`: `get` throws `CacheReadException`; there is no `unknownRead` |
| On a Redis write failure | Throws `CacheWriteException`; never retried |
| Default lifetimes | `ttl` forever, `local-max-age` 30 s, `absent-ttl` 10 s |
| Needs | A Redis that doesn't evict its keys ([eviction check](./configuration.md#eviction-check)) |

## Declare it {#declare}

::: code-group

```kotlin [Kotlin]
@CoCache
interface OnlineUsers : SetStore<String, String>

// or as a spec bean
@Bean
fun roomMembersSpec(): SetStoreSpec<String, String> =
    setStoreSpec("room-members") {
        elementCodec = StringCodec
    }
```

```java [Java]
@CoCache
public interface OnlineUsers extends SetStore<String, String> {}

@Bean
SetStoreSpec<String, String> roomMembersSpec() {
    return SetStoreSpec.<String, String>builder("room-members")
        .elementCodec(StringCodec.INSTANCE)
        .build();
}
```

:::

A spec sets `elementCodec`, the codec of one element. An interface without a codec bean encodes elements as JSON with your application's Jackson `ObjectMapper`. A `SetStore` has no source: `@CoCache(loader)` or `source = EXTERNAL` on its interface fails startup. Creating one needs a backend that supports sets; Redis and the in-memory backend do.

## Using it {#using}

```kotlin
online.add(roomId, listOf(userId))        // creates the set if needed
online.remove(roomId, listOf(userId))     // deletes the key when it becomes empty
online.put(roomId, setOf("a", "b"))       // replaces the whole set
online.clear(roomId)                      // deletes the set
val members: Set<String> = online[roomId] // empty set when absent
```

- **No `invalidate`**: `clear` deletes the set. An empty set is stored as no key, so `put(key, emptySet())` equals `clear(key)`, and `putAll` follows the same rule for each entry.
- **`add` and `remove` are atomic in Redis.** Two instances adding different members at the same time both win. Every write drops the set from every instance's L2, and this instance's next read sees it.
- **TTL defaults to forever.** When you set one, each `add` or `remove` resets the expiry. An absent set stays in L2 for at most `absent-ttl` (10 s).
- **Each element** is stored as one hash field in Redis, so a set's size is bounded by Redis, not by the value size of one key ([Redis memory](../operations/index.md#sizing-redis)).

## When Redis fails {#failures}

- Writes throw `CacheWriteException`, and are never retried in the background. `outcomeUnknown` says whether the write may have reached Redis. Retrying `add` or `remove` is safe; retrying `put` is a new replacement.
- A Redis read failure is [`Unknown`](./caches.md#unknown): `get` throws `CacheReadException`, `lookup` returns `Unknown`, and `getAll` throws with `unknownKeys` and `partialResult`. A set has no safe "absent" answer to fall back to, so there is no `unknownRead`.
- **Unreadable content throws `CacheReadException`.** A read throws it for an element the codec can't decode, for a set written in a newer format (with a `ForeignFormatException` cause), and for content in a format this version can't read (for example a native Redis Set at the same key). An `add` or `remove` on such content throws `CacheWriteException` and leaves the key unchanged. Only `put` replaces it. A set store never reclaims such content ([unreadable payloads](../operations/troubleshooting.md#corrupt-payloads)).

## Durability {#durability}

Durability is Redis durability, and the eviction check applies, as for a [`Store`](./store.md#durability). The actuator endpoint's per-key `DELETE` runs `clear`, which deletes the set's only copy and needs `management.endpoint.cocache.allow-store-delete=true`.

## When the set belongs to your database {#database-sets}

If the set's truth lives in your database, don't use `SetStore`. Declare a `Cache<K, Set<E>>` with a loader that reads the set, update the database, and `invalidate` the key: the next read loads the whole set again, and a lost Redis key costs one reload.

## Views {#views}

`async()` returns an `AsyncSetStore` and `suspending()` a `SuspendingSetStore`, whose `get` also returns an empty set, never `null`.
