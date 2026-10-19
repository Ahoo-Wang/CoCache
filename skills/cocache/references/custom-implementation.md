# Custom Implementations (6.x)

## Contents

- [What Can Be Customized](#what-can-be-customized)
- [CacheLoader](#cacheloader)
- [Codec](#codec)
- [KeyEncoder, TtlFunction, KeyFilter](#keyencoder-ttlfunction-keyfilter)
- [CacheSpecCustomizer](#cachespeccustomizer)
- [Custom CoherenceBackend](#custom-coherencebackend)
- [Registering a Backend](#registering-a-backend)

## What Can Be Customized

| You provide | Interface | Where it lives |
|-------------|-----------|----------------|
| Data source | `CacheLoader<K, V>` | `cocache-api` |
| Value bytes | `Codec<V>` | `cocache-api` |
| Storage key | `KeyEncoder<K>` | `cocache-core` (`CacheSpec`) |
| Per-entry lifetime | `TtlFunction<K, V>` | `cocache-core` (`CacheSpec`) |
| Existence filter (e.g. Bloom) | `KeyFilter` | `cocache-core` (`CacheSpec`) |
| Declaration tweaks | `CacheSpecCustomizer` | starter |
| L1 + invalidation channel | `CoherenceBackend` SPI | `cocache-api` (`me.ahoo.cocache.api.spi`) |

Not customizable: the L2 is always Caffeine (size it with `LocalPolicy` / `@CaffeineCache`), and stateful components (L2, stores, subscriptions) are always created by `CacheRuntime`. You pass functions and specs, never instances that hold cache state.

## CacheLoader

```kotlin
class UserLoader(private val repo: UserRepository) : CacheLoader<String, User> {
    override fun load(key: String): User? = repo.findById(key)          // null = not found (negative entry)

    override fun loadAll(keys: Collection<String>): Map<String, User> =  // keys missing from the map are not found
        repo.findAllById(keys).associateBy { it.id }
}
```

- The loader runs synchronously on the caller's thread, so transactions, security context, and MDC are available.
- Throwing propagates the original exception to the caller and to every coalesced waiter; the lease is released.
- Do not read the same key from the same cache inside `load` (same-thread reentry fails fast).
- Do not return lifetimes from the loader; use `ttlOf` or the policy.

## Codec

```kotlin
object UserProtoCodec : Codec<User> {
    override fun encode(value: User): ByteArray = value.toProto().toByteArray()
    override fun decode(bytes: ByteArray): User = UserProto.parseFrom(bytes).toUser()
}
```

A decode failure is treated as a corrupt payload: the entry is reclaimed only if Redis still holds the same bytes, then reloaded. Changing a codec's byte layout is safe only if `decode` throws on the old layout (or still reads it).

Built-ins: `StringCodec` (`cocache-core`), `JacksonCodec` / `jacksonCodec<V>(mapper)` (`cocache-codec-jackson`, Jackson 3). For interface-style caches, a unique `Codec<V>` bean is picked up automatically, or name one with `@CoCache(codec = "beanName")`.

## KeyEncoder, TtlFunction, KeyFilter

```kotlin
cacheSpec<UserId, User>("user") {
    codec = jacksonCodec<User>(mapper)
    loader = userLoader
    keyEncoder = KeyEncoder { "${it.tenant}:${it.id}" }               // stored as cocache:user:{tenant}:{id}
    ttlOf = TtlFunction { _, user -> if (user.vip) Duration.ofHours(6) else null }
    keyFilter = KeyFilter { storageKey -> !bloom.mightContain(storageKey) }
}
```

- `KeyEncoder` maps the business key to the part after `{keyPrefix}{namespace}:`. Two keys that encode equally share one entry. Interface-style caches use `@CoCache(keyExpression = "#{tenant}:#{id}")` instead.
- `TtlFunction.ttlOf(key, value)`: non-null overrides the policy `ttl` with no jitter; `null` falls back to the policy. Negative entries always use `absentTtl`. Results are clamped to `[1 ms, 100 years]`. Configuration-file `ttl` does not affect it.
- `KeyFilter.notExist(storageKey)`: `true` returns "not found" without touching L1 or the loader and without caching anything. `SetCacheSpec` has no key filter.

## CacheSpecCustomizer

```kotlin
@Bean
fun tenantPrefix() = object : CacheSpecCustomizer {
    override fun supports(cacheName: String) = cacheName.startsWith("tenant")
    override fun customize(spec: CacheSpec.Builder<*, *>) {
        spec.keyPrefix = "app1:"
    }
    override fun customize(spec: SetCacheSpec.Builder<*, *>) {
        spec.keyPrefix = "app1:"
    }
}
```

Customizers run in Spring order after the declaration and before `cocache.defaults.*` / `cocache.caches.[name].*`.

## Custom CoherenceBackend

Implement this only to put L1 on something other than Redis. The backend is one SPI with two faces, because whoever serializes writes should also emit the invalidation:

```kotlin
interface CoherenceBackend : AutoCloseable {
    val atomicBroadcast: Boolean          // can a write and its broadcast happen in one atomic step?
    val format: Int                       // wire-format version this backend writes
    val channel: InvalidationChannel
    fun store(namespace: String): RemoteStore
}
```

`RemoteStore` handles bytes and tokens only; the engine passes every TTL, refresh window, and token. Each method is atomic per key:

| Method | Contract |
|--------|----------|
| `read(key, lease, refresh)` | Absent key + lease requested → write a lease placeholder with `lease.ttl`, return `Miss(true)`. Lease placeholder → `Leased(remaining)`. Value whose remaining life ≤ `refresh.window` and no live token (or token older than `tokenTtl`) → store the refresh token, return `Hit(refreshGranted = true)`. Content you did not write → `Hit(format = 0)` |
| `readAll(keys, leases)` | `read` per key; lease only for keys in `leases`; no refresh tokens; result has every key |
| `fill(fill)` / `fillAll` | Write only if the key still holds `fill.token` (lease or refresh token); reset expiry to `fill.ttl` (`null` = never) |
| `release(key, token)` | Token matches: delete a lease placeholder, or just clear a refresh token |
| `put(key, envelope, ttl, frame)` | Overwrite, destroy any token, reset expiry, broadcast `frame` |
| `invalidate(keys, frame)` | Delete and broadcast |
| `reclaim(key, observed, lease)` | Only if the current content's `Digest` equals `observed`, turn the key into a lease placeholder |
| `addMembers` / `removeMembers` | Set semantics for `SetCache`: add replaces any non-set state; remove only touches sets and deletes the key when empty; reset expiry; broadcast |

Hard rules:

- **Reset the expiry on every state change.** A lease's or negative entry's TTL must never leak onto a new value.
- **An absent key or a lease placeholder is a miss, never a negative entry.** Negative entries are only `Envelope.Absent` written by `fill` / `put`.
- **Never delete from `read`.** Corruption goes through `reclaim`.
- **Tokens are compared byte-for-byte** (`Token.toBytes()`, 24 bytes). They are globally unique; do not generate them in the backend.
- **When `atomicBroadcast` is `true`, broadcast `frame` in the same atomic step as the write.** When `false`, ignore `frame`; the engine calls `channel.publish` after the write succeeds.
- Throw on failure; the engine decides whether to degrade, retry, or rethrow.

`InvalidationChannel`:

```kotlin
interface InvalidationChannel {
    fun subscribe(namespace: String, listener: InvalidationListener): Subscription
    fun publish(namespace: String, frame: ByteArray)   // called only when atomicBroadcast == false
}
```

- Decode frames with `InvalidationFrame.decode(bytes)` and call `listener.onInvalidated(keys, origin)`. The engine filters its own `origin`.
- **Call `onReset()` after every (re)subscription is confirmed**, including the first one, and whenever events may have been lost (for example a failed heartbeat). Delivery may be at-most-once; resets are what keep L2 bounded.
- Prefer returning from `subscribe` only after that first reset ran, so a late reset can't clear fresh L2 entries.

`InMemoryBackend` (`cocache-core`) is a complete reference implementation; `RedisScripts` in `cocache-redis` shows the same protocol in Lua.

Verify the backend with `BackendProtocolSpec`, `EngineSpec`, and `SetEngineSpec` (see `testing.md`).

## Registering a Backend

Spring Boot: declare it as a bean; the auto-configured Redis backend backs off.

```kotlin
@Bean(destroyMethod = "close")
fun cocacheBackend(): CoherenceBackend = MyBackend.connect()
```

Plain JVM: `CacheRuntime.builder().backend(MyBackend.connect()).build()`. The runtime does not close the backend; close it after the runtime.
