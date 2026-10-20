---
title: Declaring Caches
description: Declare caches as annotated interfaces or CacheSpec beans, and control their names, storage keys, key encoding, loaders, codecs, set caches, customizers, and startup validation.
---

# Declaring Caches

A cache is a name, a key type, a value type, a way to load missing values, a way to encode values for Redis, and a policy. You can write that down in two ways, and both produce the same engine:

| | Interface | Spec bean |
|-|-----------|-----------|
| Looks like | `@CoCache interface UserCache : Cache<String, User>` | `@Bean fun userSpec(): CacheSpec<String, User>` |
| Inject as | `UserCache` | `Cache<String, User>` with `@Qualifier` |
| Loader and codec | Found by type, or named in the annotation | Set in code |
| Can configure | Policy basics and L2 size | Everything, including `ttlOf`, `KeyEncoder`, and `KeyFilter` |

Start with an interface. Switch to a spec bean (or add a [customizer](#customizer)) when you need something the annotation doesn't offer.

## Interface caches {#interface}

```kotlin
@CoCache(ttl = 600, localMaxAge = 60)
@CaffeineCache(maximumSize = 100_000)
interface UserCache : Cache<String, User>

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App
```

The interface must extend `Cache<K, V>` (or [`SetCache<K, E>`](#set-cache)) with concrete type arguments. `@CoCache` itself is optional: an interface listed in `@EnableCoCache` without it gets every default. Annotations are read from the interface itself, not from meta-annotations or super-interfaces.

### `@CoCache` {#cocache-annotation}

Durations are in **seconds**. Every policy attribute defaults to `CoCache.UNSET` (`-1`): "not set here, use the next layer".

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `name` | interface simple name | Cache name and bean name |
| `namespace` | the cache name | Middle part of every storage key, and the invalidation channel ([storage keys](#storage-keys)) |
| `keyPrefix` | `cocache:` | First part of every storage key |
| `keyExpression` | `toString()` | SpEL template that turns a key into a string ([keys](#keys)) |
| `ttl` | `3600` (forever for a `SetCache`) | Lifetime of a value in Redis. `CoCache.FOREVER`: never expires. |
| `ttlJitter` | `60` | Random ± spread added to each value's lifetime. Capped at `ttl / 2`. `0` turns it off. |
| `absentTtl` | `60` | Lifetime of a negative entry. Never jittered. |
| `localMaxAge` | `300` | Maximum age of an L2 copy: the staleness bound when an invalidation message is lost |
| `refreshWindow` | off | Refresh a value in the background when it has this long left. `0`: off. |
| `loader` | `""` | Bean name of the `CacheLoader`. Empty: the single bean of type `CacheLoader<K, V>`, or none. |
| `codec` | `""` | Bean name of the `Codec`. Empty: the single bean of type `Codec<V>`, or Jackson. |

`CoCache.FOREVER` is valid only for `ttl` (and for `@CaffeineCache.expireAfterAccess`, where it means off). On any other duration attribute it fails startup. `@CaffeineCache.maximumSize` is a count, not a duration, so `FOREVER` there is simply `Long.MAX_VALUE` entries. Zero is valid only for `ttlJitter` and `refreshWindow`; any other zero duration, and any negative value other than `UNSET`, fails startup.

### `@CaffeineCache` {#caffeine-annotation}

Sizes the L2. L2 is always Caffeine and always bounded.

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `maximumSize` | `10000` | Maximum entries per instance |
| `initialCapacity` | Caffeine's default | Initial table size |
| `expireAfterAccess` | off | Evict entries idle for this many seconds. `CoCache.FOREVER`: off. |

You don't need `expireAfterAccess` for correctness: entries already expire at `localMaxAge` and at their Redis expiry. It frees memory sooner, but it records the access time on every read, which costs throughput on very hot keys.

### Adding methods {#interface-methods}

Default methods run as written, so you can add helpers:

```kotlin
interface UserCache : Cache<String, User> {
    fun nameOf(id: String): String? = get(id)?.name
}
```

An abstract method must match a `Cache` (or `SetCache`) method by name and parameters, otherwise startup fails. Exceptions from the cache reach you unwrapped.

## Spec beans {#spec-bean}

A `CacheSpec` bean declares a cache in code. The starter registers a `Cache<K, V>` bean for each one.

::: code-group

```kotlin [Kotlin]
@Bean
fun productSpec(mapper: ObjectMapper, repo: ProductRepository): CacheSpec<String, Product> =
    cacheSpec("product") {
        codec = jacksonCodec<Product>(mapper)
        loader = CacheLoader { id -> repo.findById(id) }
        policy {
            ttl = Duration.ofMinutes(10)
            local = LocalPolicy(maximumSize = 100_000)
        }
    }
```

```java [Java]
@Bean
CacheSpec<String, Product> productSpec(ObjectMapper mapper, ProductRepository repo) {
    return CacheSpec.<String, Product>builder("product")
        .codec(JacksonCodec.of(mapper, Product.class))
        .loader(id -> repo.findById(id).orElse(null))
        .updatePolicy(policy -> policy
            .ttl(Duration.ofMinutes(10))
            .local(new LocalPolicy(100_000)))
        .build();
}
```

:::

```kotlin
class ProductService(@Qualifier("product") private val products: Cache<String, Product>)
```

- **The return type must declare the generics** (`CacheSpec<String, Product>`). The starter reads `K` and `V` from it without creating the spec. A raw return type fails startup.
- **Bean name**: the spec bean name minus its `Spec` suffix (`productSpec` → `product`). Without the suffix, `Cache` is appended (`products` → `productsCache`). The *cache* name is the one you pass to `cacheSpec(…)`.
- **A spec must set its codec.** There is no Jackson fallback in code; `ObjectMapper` here is Jackson 3 (`tools.jackson.databind`).
- In Kotlin, `policy { … }` edits the current policy; in Java, use `updatePolicy(…)`. `policy(CachePolicy)` replaces it outright.

`setCacheSpec<K, E>("name") { elementCodec = … }` declares a [`SetCache`](#set-cache) the same way, and registers a `SetCache<K, E>` bean.

## Names and storage keys {#storage-keys}

Every key in Redis is:

```
{keyPrefix}{namespace}:{encodedKey}
 cocache:   UserCache :42
```

| Part | Default | Set with |
|------|---------|----------|
| `keyPrefix` | `cocache:` | `@CoCache(keyPrefix)`, `CacheSpec.keyPrefix`, or the [`key-prefix` property](./configuration.md#key-prefix) |
| `namespace` | the cache name | `@CoCache(namespace)`, `CacheSpec.namespace` |
| `encodedKey` | `key.toString()` | `@CoCache(keyExpression)`, `CacheSpec.keyEncoder` |

- Cache names and namespaces must each be unique within an application. A duplicate fails startup.
- Invalidations travel on the channel `cocache:inv:{namespace}`. The channel doesn't include the prefix.
- The **encoded key** is what the actuator endpoint and `ExternalInvalidator` take, so other tools can address a key without knowing its Kotlin type.
- To share one Redis between applications, give each a different prefix with the [`cocache.defaults.key-prefix` property](./configuration.md#key-prefix) (for example `${spring.application.name}:`). `@CoCache(keyPrefix)` is literal text: placeholders in it are not resolved (and in Kotlin, `$` starts a string template). Changing the prefix or namespace of a running cache is a cold start: old keys are ignored and expire on their own.

## Keys {#keys}

By default a key becomes its `toString()`. That works for `String`, numbers, `UUID`, data classes, and records.

The first time each key type is encoded, CoCache checks that the type overrides `toString()`. If it doesn't (the inherited `Object.toString()` gives `ClassName@hash`, different for every instance), the call fails with `IllegalArgumentException`, because such a cache would never hit. Use a data class or record, override `toString()`, or supply an encoder.

**With an interface**, `keyExpression` is a SpEL template with the key as root object:

```kotlin
data class TenantKey(val tenantId: String, val id: String)

@CoCache(keyExpression = "#{tenantId}:#{id}")
interface TenantUserCache : Cache<TenantKey, User>
```

The expression is evaluated on **every** read, including L2 hits, because the encoded key is how L2 is looked up. Prefer a key type with a suitable `toString()` on hot paths. A template without any `#{…}` would be a constant that maps every key to the same entry, so startup rejects it.

**With a spec**, set a `KeyEncoder`, which is a plain function:

```kotlin
cacheSpec<TenantKey, User>("TenantUserCache") {
    keyEncoder = KeyEncoder { "${it.tenantId}:${it.id}" }
    // …
}
```

Two keys that encode to the same string are the same cache entry. Make the encoding injective.

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

- **`null` means "doesn't exist"** and is cached as a negative entry for `absentTtl`. Throw if the lookup *failed*: the exception reaches the caller (and every caller sharing the load), and nothing is cached.
- **The default `loadAll`** calls `load` once per key. Override it with a batch query if you use `getAll`.
- **The loader runs on the calling thread**, so transactions, `SecurityContext`, and MDC are available. Through the [async view](./async.md) it runs on the blocking executor instead.
- **Don't read the same key from inside its own loader** on the same thread; that fails fast with `IllegalStateException`.
- **Interfaces** find the loader by type `CacheLoader<K, V>`, then `AsyncCacheLoader<K, V>`. If several beans match, or one of each, startup fails; name one with `@CoCache(loader = "…")`. If none matches, the cache has no loader: misses return `null` and the cache is filled only by `put`.
- **Per-call loaders**: `cache.get(key) { k -> … }` loads a miss with the given loader instead, with the same protection. Spring's `@Cacheable(sync = true)` uses this.
- **Non-blocking sources**: implement `AsyncCacheLoader<K, V>` (`loadAsync` returns a `CompletionStage`) and set it as `asyncLoader` in a spec. A cache has one source, sync or async; both APIs work with either. See [Async API](./async.md#loader).

### Per-entry TTL {#ttl-of}

When lifetime depends on the value, set `ttlOf` (spec or customizer):

```kotlin
cacheSpec<String, Session>("SessionCache") {
    codec = jacksonCodec<Session>(mapper)
    loader = CacheLoader { id -> sessions.find(id) }
    ttlOf = TtlFunction { _, session -> Duration.between(Instant.now(), session.expiresAt) }
}
```

A non-null result overrides the policy `ttl` and is not jittered. `null` falls back to the policy. Negative entries always use `absentTtl`. The `ttl` property doesn't affect `ttlOf`.

### Rejecting impossible keys {#key-filter}

A `KeyFilter` (spec or customizer) is checked after an L2 miss. A key it rejects returns "doesn't exist" without touching Redis or the loader, which is useful in front of a Bloom filter:

```kotlin
keyFilter = KeyFilter { storageKey -> !bloom.mightContain(storageKey) }
```

It receives the full storage key and must reject only keys that certainly don't exist.

## Codecs {#codecs}

A `Codec<V>` turns a value into the bytes stored in Redis and back.

| Declaration | Codec used |
|-------------|------------|
| Interface, `@CoCache(codec = "name")` | That bean |
| Interface, one bean of type `Codec<V>` | That bean |
| Interface, several beans of type `Codec<V>` | Startup fails; name one with `@CoCache(codec)` |
| Interface, none | `JacksonCodec` for `V`, built from your application's Jackson 3 `ObjectMapper` |
| Spec | Whatever you set: `jacksonCodec<V>(mapper)`, `JacksonCodec.of(mapper, V::class.java)`, `StringCodec`, or your own |

A custom codec is two functions:

```kotlin
@Bean
fun moneyCodec(): Codec<Money> = object : Codec<Money> {
    override fun encode(value: Money) = value.toString().toByteArray()
    override fun decode(bytes: ByteArray) = Money.parse(String(bytes))
}
```

If a payload can't be decoded (for example, after an incompatible change to the value class), CoCache treats it as a miss and reloads it. A [`SetCache`](#set-cache) has no source to reload from and throws instead. Changes that still decode but mean something different won't be caught: change the namespace or key prefix for those.

Redis values carry a short binary header before the codec's bytes. `redis-cli GET` shows binary, and other programs must not read or write CoCache keys directly.

## Set caches {#set-cache}

`SetCache<K, E>` is for a set whose **only copy is Redis**: an index, a membership list, a set of online sessions. Elements are added and removed atomically in Redis, so concurrent updates from different instances are never lost.

```kotlin
@CoCache
interface OnlineUsersCache : SetCache<String, String>

online.add(roomId, listOf(userId))       // creates the set if needed
online.remove(roomId, listOf(userId))    // deletes the key when it becomes empty
online.clear(roomId)
val members: Set<String> = online[roomId] // empty set when absent
```

- **No loader.** A set cache has no source to reload from; `@CoCache(loader = …)` on one fails startup, and `get(key, loader)` throws.
- **`invalidate` and `invalidateAll` throw `UnsupportedOperationException`**, because they would destroy the only copy. Use `clear`.
- **`put(key, emptySet())` equals `clear(key)`**: an empty set is stored as no key. `putAll` follows the same rule for each entry ([batch writes](./freshness.md#batch-writes)).
- **TTL defaults to forever.** Each `add` or `remove` resets the expiry when you set one.
- **Failures are loud.** Writes throw `CacheWriteException` (they are idempotent, so retry). Reads throw the Redis error, since there is no source to fall back to.
- **Unreadable content throws.** An element the codec can't decode makes the read throw, and a set written in a newer format throws `IllegalStateException`. Content CoCache didn't write (for example a native Redis Set left by 5.x at the same key) makes the read throw `CacheReadException`, and an `add` or `remove` on it, or on a set in a newer format, throws `CacheWriteException` and leaves the key unchanged. Only `put` replaces it. With `foreign-format: reclaim` such content reads as an empty set instead, and the next `add` replaces it ([upgrading from 5.x](./migration-6.md#foreign-format)).
- **Durability is Redis durability.** Use a `maxmemory-policy` that never evicts keys without a TTL (not `allkeys-*`). An asynchronous-replication failover can lose recent writes.

If the set's truth lives in your database, don't use `SetCache`. Declare `Cache<K, Set<E>>`, update the database, and `invalidate`.

## Customizing declarations {#customizer}

A `CacheSpecCustomizer` bean edits a declaration before the cache is created. Use it for what the annotation can't express, or to change many caches at once:

```kotlin
@Bean
fun userCacheCustomizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
    override fun supports(cacheName: String) = cacheName == "UserCache"   // default: every cache

    override fun customize(spec: CacheSpec.Builder<*, *>) {
        spec.policy {
            absentTtl = Duration.ofSeconds(30)
            lease = LeasePolicy(ttl = Duration.ofSeconds(30))   // a loader that can take 20 s
        }
    }
}
```

In Java, implement the same interface and call `spec.updatePolicy(p -> p.absentTtl(Duration.ofSeconds(30)))`.

Customizers run in Spring order, after the declaration and before the `cocache.*` properties ([precedence](./configuration.md#precedence)). Override `customize(SetCacheSpec.Builder)` to reach set caches.

## Startup validation {#validation}

CoCache refuses to start rather than run a cache that can't behave as declared. Each message names the cache.

| Fails startup | Why |
|---------------|-----|
| Interface without concrete `Cache<K, V>` type arguments, or a spec bean whose return type doesn't declare them | Keys, loader, and codec are resolved from those types |
| Abstract interface method that matches no `Cache` method | The call would fail at runtime instead |
| Several `CacheLoader<K, V>` or `Codec<V>` beans match | A silent pick could use the wrong source or format |
| `loader` on a `SetCache` | A set cache has no source; misses would silently return empty |
| `FOREVER` on a duration attribute other than `ttl` / `expireAfterAccess`, a zero duration (except `ttlJitter`, `refreshWindow`), or any negative duration | Meaningless or unsafe lifetimes |
| `refreshWindow` not less than `ttl − ttlJitter` (the effective jitter), or not less than `localMaxAge` | A fresh copy would start inside the window and refresh on every hit |
| Duplicate cache name, namespace, or bean name | Two caches would share keys or invalidations |
| `cocache.caches.[name]` for a cache that doesn't exist, or an unsupported property name from an earlier major version (see [Upgrading from 5.x](./migration-6.md#properties)) | Almost always a typo; see [Configuration Reference](./configuration.md#validation) |

The `toString()` check on key types runs on the first read of each key type, not at startup.
