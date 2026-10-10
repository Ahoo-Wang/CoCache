---
title: Extending CoCache
description: The SPI contracts for custom L2/L1 stores, eviction channels, key converters, and sources — and the TCK suites that verify them.
---

# Extending CoCache

Every extension point is an interface in `cocache-api`. Implement the interface, verify it against the matching test suite from `cocache-test`, and register it as a [per-cache bean](../guide/configuration.md#per-cache-components) or a global bean.

```kotlin
testImplementation("me.ahoo.cocache:cocache-test")
```

| You implement | Contract | Verify with |
|---------------|----------|-------------|
| `ClientSideCache<V>` (L2) | [Stores](#stores) | `ClientSideCacheSpec` |
| `DistributedCache<V>` (L1) | [Stores](#stores) | `DistributedCacheSpec` |
| `CacheEvictedEventBus` | [Invalidation channel](#invalidation-channel) | `CacheEvictedEventBusSpec`, `MultipleInstanceSyncSpec` |
| `Cache<K, V>` (a whole cache) | the `Cache` API | `CacheSpec` |
| `KeyConverter<K>`, `CacheSource<K, V>`, `KeyFilter`, `JoinKeyExtractor` | single-method functional interfaces | your own tests |

`DefaultCoherentCacheSpec` runs the full coherence suite against a combination of L2, L1, and channel. Use it when you replace more than one component.

## Stores

```kotlin
interface CacheStore<V> {
    fun getCache(key: String): CacheValue<V>?
    fun setCache(key: String, value: CacheValue<V>)
    fun evict(key: String)
}
interface ClientSideCache<V> : CacheStore<V> { val size: Long; fun clear() }
interface DistributedCache<V> : CacheStore<V>, AutoCloseable
```

Every store:

- **Only stores.** Keep `CacheValue`s exactly as given. Don't apply TTL defaults, jitter, or negative-cache policy, because the orchestration layer already did.
- Treats `setCache` with an already expired value as `evict`.
- May return expired entries from `getCache`. The caller checks `isExpired`.

An **L2** store must also be **bounded**. Avoid per-entry expiry callbacks that write metadata on every read, such as a Caffeine `Expiry`. With one, a hot key stops scaling across threads.

An **L1** store must also follow these rules:

- **Return `null` for every kind of miss:** absent, deleted mid-read, or undecodable. Return `MissingValue` only when the store actually holds a negative record that you wrote. Treating a miss as "not found" would hide data that exists.
- Read the value and its remaining TTL atomically, ideally in one round trip, and rebuild `ttlAt` from the store's own expiry.
- When writing, round any remaining TTL up to at least one second, and write `TtlAt.FOREVER` entries without expiry.
- Never delete on read. A delete could remove a valid value that another instance just wrote.

For a new Redis data structure, extend `AbstractCodecExecutor` and wrap it in `RedisDistributedCache`. The base class provides the atomic Lua read, the miss semantics, and the TTL handling.

Stores that rebuild `ttlAt` from server-side expiry can drift by up to a second. `CacheStoreSpec`'s TTL tests are `open`, so override them with `isCloseTo(expected, Offset.offset(1))`.

## Invalidation channel

```kotlin
interface CacheEvictedEventBus {
    fun publish(event: CacheEvictedEvent)          // CacheEvictedEvent(cacheName, key, publisherId)
    fun register(subscriber: CacheEvictedSubscriber)
    fun unregister(subscriber: CacheEvictedSubscriber)
}
interface CacheEvictedSubscriber : NamedCache {
    fun onEvicted(cacheEvictedEvent: CacheEvictedEvent)
    fun onReset()
}
```

- **`publish` is best effort.** It must not throw on a transport failure. Log the failure and return.
- **Deliver by `cacheName`.** Subscribers ignore their own events by comparing `publisherId` with their `clientId`. The channel doesn't need to filter them.
- **`onReset` is mandatory.** Call it whenever a subscription is established or re-established: on `register`, after every reconnect, and after any consumer-group rebalance that may have skipped messages. The subscriber clears its L2. Without resets, a single lost event can leave L2 stale until its TTL expires.

## Sources and keys

- **`CacheSource<K, V>`** returns a `CacheValue` with an absolute `ttlAt`, or `null` for "does not exist". CoCache stores `null` as a negative entry for `missingTtl` seconds. A source is called at most once at a time per key per instance. It must not read its own cache for the same key, or the call fails fast.
- **`KeyConverter<K>`** must produce a different string for every key and include a cache-specific prefix, because L1 is shared by every cache.
- **`KeyFilter.notExist(key)`** may return `true` only for keys that certainly don't exist. For a Bloom filter, that means it was built from every existing key.
