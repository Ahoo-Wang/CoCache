# Sets: SetCache or Cache of Set

Use this reference whenever cached data is a set. The first decision is where the set's truth lives.

## Decide by the source of truth

| Truth lives in | Use | Maintained by |
|---|---|---|
| The database (tags, roles, members stored in tables) | `Cache<K, Set<E>>` with a normal loader | Write the database, then `invalidate` |
| Only Redis (online sessions, an index built at runtime) | `SetCache<K, E>` | `add`, `remove`, `clear` |

Using `SetCache` for database-backed data is a defect: it cannot reload, so a lost or partial write is permanent.

## SetCache rules

```kotlin
@CoCache
interface OnlineUsersCache : SetCache<String, String>

online.add(roomId, listOf(userId))       // atomic in Redis; creates the set
online.remove(roomId, listOf(userId))    // deletes the key when it becomes empty
online.clear(roomId)
val members: Set<String> = online[roomId] // empty set when absent
```

- No loader: `@CoCache(loader = ...)` fails startup and `get(key, loader)` throws.
- `invalidate` and `invalidateAll` throw `UnsupportedOperationException`; use `clear`. `put(key, emptySet())` equals `clear(key)`.
- TTL defaults to forever; `cocache.defaults.ttl` does not apply to set caches, only `cocache.caches.[name].ttl`. Each write resets a configured expiry.
- Writes throw `CacheWriteException` (idempotent, retry). Reads throw the Redis error: there is no source to fall back to. An undecodable element throws; a set in a newer format throws `IllegalStateException`.
- The element codec must be canonical: members compare by encoded bytes.
- Spec form: `setCacheSpec<K, E>("name") { elementCodec = ... }` registers a `SetCache<K, E>` bean.

## Operational requirements

- Redis durability is the set's durability: enable AOF or RDB, and expect an asynchronous-replication failover to lose recent writes.
- `maxmemory-policy` must be `noeviction` or `volatile-*`; `allkeys-*` silently drops members. CoCache does not check this.
- Never `DEL` a `SetCache` key from outside, and never send it an external invalidation; the actuator per-key `DELETE` on a set cache is `clear`.
