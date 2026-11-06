# Sets: SetStore or Cache of Set

Use this reference whenever cached data is a set. The first decision is where the set's truth lives.

## Decide by the source of truth

| Truth lives in | Use | Maintained by |
|---|---|---|
| The database (tags, roles, members stored in tables) | `Cache<K, Set<E>>` with a normal loader | Write the database, then `invalidate` |
| Only Redis (online sessions, an index built at runtime) | `SetStore<K, E>` | `add`, `remove`, `put`, `clear` |

Using `SetStore` for database-backed data is a defect: it cannot reload, so a lost or partial write is permanent. Ask the type question from `declare-caches.md`: if Redis loses the key, can this process rebuild it? Yes means `Cache<K, Set<E>>`.

## SetStore rules

```kotlin
@CoCache
interface OnlineUsers : SetStore<String, String>

online.add(roomId, listOf(userId))       // atomic in Redis; creates the set
online.remove(roomId, listOf(userId))    // deletes the key when it becomes empty
online.clear(roomId)
val members: Set<String> = online[roomId] // empty set when absent
```

- No loader and no `invalidate`: `SetStore` has neither method, and `@CoCache(loader = ...)` or `source = EXTERNAL` on a `SetStore` interface fails startup. `put(key, emptySet())` equals `clear(key)`; `putAll` follows the same rule per entry.
- Defaults come from `DefaultPolicies.SET_STORE`: `ttl` forever, `localMaxAge` 30 s, `absentTtl` 10 s. Only the declaration and `cocache.caches.[name].ttl` set its `ttl`; `cocache.defaults.store.*` fills `absent-ttl`, `local-max-age`, `ttl-jitter`, `read-timeout` and `local-maximum-size` when the declaration leaves them unwritten. A `SetStore` has no `refresh-window`, lease, load or `unknown-read` keys. Each write resets a configured expiry.
- Writes throw `CacheWriteException` and are never retried; `outcomeUnknown` says whether the write may have reached Redis. A Redis read failure is `Lookup.Unknown`: `get` throws `CacheReadException`, `lookup` returns `Unknown`, `getAll` throws with `unknownKeys` and `partialResult`. There is no `unknownRead` for sets.
- Unreadable content makes reads throw `CacheReadException`: an undecodable element, a set in a newer format (cause `ForeignFormatException`), and content CoCache can't read (a native Redis set another program wrote, or a set in wire format 0 or 1). `add` / `remove` on such content or on a newer-format set throw `CacheWriteException` (cause `ForeignFormatException`) and leave the key unchanged; `put` replaces it.
- The element codec must be canonical: members compare by encoded bytes.
- Spec form: `setStoreSpec<K, E>("name") { elementCodec = ... }` (Java `SetStoreSpec.builder(name)`) registers a `SetStore<K, E>` bean. The backend must provide set members (`sets()`); Redis and the in-memory backend do.

## Operational requirements

- Redis durability is the set's durability: enable AOF or RDB, and expect an asynchronous-replication failover to lose recent writes.
- `maxmemory-policy` must be `noeviction`, or `volatile-*` when the set has no `ttl`; `allkeys-*` silently drops members. The starter checks this at startup for every authoritative type (`cocache.redis.store-eviction-check`, default `warn`) by reading `INFO memory`, so the Redis user needs `+info`, or set `cocache.redis.assume-maxmemory-policy`.
- Never `DEL` a `SetStore` key from outside, and never send it an external invalidation; the actuator per-key `DELETE` on a set store is `clear`, which deletes the only copy.
