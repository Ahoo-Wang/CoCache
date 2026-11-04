---
title: Declaring Caches
description: Choose between the five cache types (Cache, VersionedCache, Store, VersionedStore, SetStore), declare them as annotated interfaces or spec beans, and control their names, storage keys, key encoding, loaders, codecs, customizers, and startup validation.
---

# Declaring Caches

A cache is a name, a key type, a value type, a type that says what Redis holds for it, a way to encode values for Redis, and a policy. This page starts with the type, then shows the two ways to declare one.

## Choosing a type {#types}

Ask one question about your data: **if Redis loses this key, can this process rebuild it on demand?**

- **Yes**: Redis holds a *derived copy*. Declare a `Cache`, or a `VersionedCache` when the writer orders its writes by version.
- **No**: Redis holds the *only copy*. Declare a `Store`, a `VersionedStore`, or, for a set of elements, a `SetStore`.

| Type | Redis holds | Data comes from | Writes | Delete |
|------|-------------|-----------------|--------|--------|
| `Cache<K, V>` | A derived copy | A `loader` / `asyncLoader`, or the callers (`externallySourced()`) | `put`, `putAll` (last writer wins) | `invalidate`, retried in the background if Redis fails |
| `VersionedCache<K, V>` | A derived, versioned copy | A versioned loader | `putIfNewer`, `invalidateIfNewer` | `evict` keeps the version fence; `purge` drops it |
| `Store<K, V>` | The only copy | Your writes | `put`, `putAll`, `putIfMatch` | `delete`, `deleteIfMatch`: synchronous, never retried |
| `VersionedStore<K, V>` | The only, versioned copy | Your versioned writes | `putIfNewer` | `deleteIfNewer` (tombstone); `purge` |
| `SetStore<K, E>` | The only copy of a set | Your element writes | `add`, `remove`, `put`, `putAll` | `clear` |

Every type reads the same way (`get`, `getAll`, `lookup`, `lookupAll`) and has an [async view](./async.md) and a [suspending view](./coroutines.md) with the same methods. Each type declares only the operations it supports: a `Store` has no `invalidate`, a `VersionedCache` has no `put`, so calling the wrong one is a compile error, not a runtime surprise.

| Your data | Type |
|-----------|------|
| Rows from a database, a remote API, anything a loader can fetch again | `Cache` with a `loader` |
| A Spring Cache (`@Cacheable` methods), or a cache only read with a per-call loader | `Cache` with `externallySourced()` |
| An event-sourced read model or a CDC projection the loader can rebuild with its version | `VersionedCache` |
| A policy snapshot, a revocation list, a session, a one-time code, an idempotency key | `Store` |
| A projection only an offline job can rebuild, data maintained by another service | `Store` |
| State whose writers must survive late, replayed or duplicated writes without a source | `VersionedStore` |
| An index or membership list whose truth is Redis | `SetStore` |

Write-behind (write the cache now, the database later) fits no type: before the write lands, Redis holds the only copy; after it, Redis holds a derived one.

All declarations of one namespace must use the same type. The type isn't stored in Redis, so CoCache can only partly detect a mismatch across applications: a derived cache that reads a `Store` value counts it in `cocache.type.mismatch` and logs a warning. The start log names each cache's type (`Cache[name] - created as Store on namespace[…]`), so you can compare declarations across applications.

**Getting it wrong**:

| Mistake | Consequence | How you find out |
|---------|-------------|------------------|
| The only copy declared as a `Cache` with `externallySourced()` | It expires after the default `ttl` (1 h). A failed `invalidate` is retried later and may delete a value written meanwhile. The Redis eviction check skips it. | The start log names the type of every cache |
| A rebuildable copy declared as a `Store` | After Redis loses the key, reads return "absent" until someone writes it again. A failed `delete` throws instead of retrying. | Startup warns when a matching `CacheLoader` bean exists |
| A `Cache` whose loader isn't found | Startup fails: a `Cache` must have a loader or declare `externallySourced()` | The exception names the interface |

## Interface caches {#interface}

```kotlin
@CoCache(ttl = 600, localMaxAge = 60)
@CaffeineCache(maximumSize = 100_000)
interface UserCache : Cache<String, User>

@CoCache
interface PolicySnapshots : Store<String, PolicySnapshot>

@EnableCoCache(caches = [UserCache::class, PolicySnapshots::class])
@SpringBootApplication
class App
```

The interface must extend exactly one of `Cache<K, V>`, `VersionedCache<K, V>`, `Store<K, V>`, `VersionedStore<K, V>` or `SetStore<K, E>`, with concrete type arguments. That type decides what kind of cache is created. `@CoCache` itself is optional: an interface listed in `@EnableCoCache` without it gets every default. Annotations are read from the interface itself, not from meta-annotations or super-interfaces.

The user API types are not meant to be implemented by your own classes, except to delegate to a cache CoCache created: new 8.x operations are added as new capability types or as default methods, which hand-written implementations may not anticipate. Interfaces that extend them, like the ones above, are fine: the proxy implements them.

### `@CoCache` {#cocache-annotation}

Durations are in **seconds**. Every policy attribute defaults to `CoCache.UNSET` (`-1`): "not set here, use the next layer". Defaults depend on the type: derived caches (`Cache`, `VersionedCache`) start from `CachePolicy.DEFAULT`, authoritative ones (`Store`, `VersionedStore`, `SetStore`) from their own [default policy](#store-defaults).

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `name` | interface simple name | Cache name and bean name |
| `namespace` | the cache name | Middle part of every storage key, and the invalidation channel. Letters, digits, `.`, `_` and `-` only ([storage keys](#storage-keys)) |
| `keyPrefix` | `cocache:` | First part of every storage key |
| `keyExpression` | `toString()` | SpEL template that turns a key into a string ([keys](#keys)) |
| `ttl` | `3600`; forever for authoritative types | Lifetime of a value in Redis. `CoCache.FOREVER`: never expires. |
| `ttlJitter` | `60` | Random ± spread added to each value's lifetime. Capped at `ttl / 2`. `0` turns it off. |
| `absentTtl` | `60`; `10` for authoritative types | Lifetime of a negative entry (derived types), or of a cached "absent" in L2 (authoritative types). Never jittered. |
| `localMaxAge` | `300`; `30` for authoritative types | Maximum age of an L2 copy: the staleness bound when an invalidation message is lost |
| `refreshWindow` | off | Refresh a value in the background when it has this long left. `0`: off. Derived caches with a source only. |
| `loader` | `""` | Bean name of the data source (`Cache` and `VersionedCache` only). Empty: the single matching bean ([loaders](#loaders)). |
| `source` | `LOADER` | `Cache` only. `CoCache.Source.EXTERNAL` declares `externallySourced()`: callers supply the values. Can't be combined with `loader`. |
| `codec` | `""` | Bean name of the `Codec`. Empty: the single bean of type `Codec<V>`, or Jackson. |
| `unknownRead` | `THROW` | What a read without a loader returns when Redis fails ([`Unknown`](#unknown)): `CoCache.UnknownRead.ABSENT` returns `null`. Only on a `Store`, a `VersionedStore`, or a `Cache` with `source = EXTERNAL`. |

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

An abstract method must match a method of the type the interface extends, by name and parameters, otherwise startup fails. Exceptions from the cache reach you unwrapped.

## Spec beans {#spec-bean}

A spec bean declares a cache in code. There is one spec per type, and the starter registers a bean of the matching type for each:

| Spec | Kotlin DSL | Java builder | Bean type |
|------|------------|--------------|-----------|
| `CacheSpec<K, V>` | `cacheSpec` | `CacheSpec.builder` | `Cache<K, V>` |
| `VersionedCacheSpec<K, V>` | `versionedCacheSpec` | `VersionedCacheSpec.builder` | `VersionedCache<K, V>` |
| `StoreSpec<K, V>` | `storeSpec` | `StoreSpec.builder` | `Store<K, V>` |
| `VersionedStoreSpec<K, V>` | `versionedStoreSpec` | `VersionedStoreSpec.builder` | `VersionedStore<K, V>` |
| `SetStoreSpec<K, E>` | `setStoreSpec` | `SetStoreSpec.builder` | `SetStore<K, E>` |

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

@Bean
fun snapshotSpec(mapper: ObjectMapper): StoreSpec<String, PolicySnapshot> =
    storeSpec("policy-snapshot") {
        codec = jacksonCodec<PolicySnapshot>(mapper)
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

@Bean
StoreSpec<String, PolicySnapshot> snapshotSpec(ObjectMapper mapper) {
    return StoreSpec.<String, PolicySnapshot>builder("policy-snapshot")
        .codec(JacksonCodec.of(mapper, PolicySnapshot.class))
        .build();
}
```

:::

```kotlin
class ProductService(@Qualifier("product") private val products: Cache<String, Product>)
```

- **The return type must declare the generics** (`CacheSpec<String, Product>`). The starter reads `K` and `V` from it without creating the spec. A raw return type fails startup.
- **Bean name**: the spec bean name minus its `Spec` suffix (`productSpec` → `product`). Without the suffix, `Cache` is appended (`products` → `productsCache`). The *cache* name is the one you pass to `cacheSpec(…)`.
- **A spec must set its codec.** There is no Jackson fallback in code; `ObjectMapper` here is Jackson 3 (`tools.jackson.databind`). A `SetStoreSpec` sets `elementCodec`.
- **A `CacheSpec` must declare exactly one source**: `loader`, `asyncLoader`, or `externallySourced()`. A `VersionedCacheSpec` must set `versionedLoader` or `versionedAsyncLoader`. Authoritative specs have no loader at all.
- In Kotlin, `policy { … }` edits the current policy; in Java, use `updatePolicy(…)`. `policy(CachePolicy)` replaces it outright. Authoritative specs start from their own default policy, so prefer editing it over replacing it with `CachePolicy.DEFAULT` (which would give a `Store` a 1 h `ttl`).

Without Spring, `CacheRuntime.create(spec)` returns the matching type: `create(storeSpec)` returns a `Store`, and so on. `create(spec: Spec<*, *>)` dispatches on the spec's type. Creating a cache checks that the backend's store supports what the type needs and fails before registering anything if it doesn't:

| Type | Needs |
|------|-------|
| `Cache` | nothing beyond the basic store |
| `Store` | conditional reads and writes (`getIfChanged`, `putIfMatch`, `deleteIfMatch`) |
| `VersionedCache`, `VersionedStore` | versioned writes |
| `SetStore` | set members |

The Redis and in-memory backends support all of them.

## Names and storage keys {#storage-keys}

Every key in Redis is:

```
{keyPrefix}{namespace}:{encodedKey}
 cocache:   UserCache :42
```

| Part | Default | Set with |
|------|---------|----------|
| `keyPrefix` | `cocache:` | `@CoCache(keyPrefix)`, the spec's `keyPrefix`, or the [`key-prefix` property](./configuration.md#key-prefix) |
| `namespace` | the cache name | `@CoCache(namespace)`, the spec's `namespace` |
| `encodedKey` | `key.toString()` | `@CoCache(keyExpression)`, the spec's `keyEncoder` |

- CoCache's internal keys live under `{keyPrefix}!`, which no namespace can produce: `!` is outside the namespace charset.
- A namespace may contain only letters, digits, `.`, `_` and `-` (`[A-Za-z0-9._-]+`). A namespace with `:` fails at creation with a message that shows how to move the part before the `:` into `keyPrefix`: `namespace = "app:user"` becomes `keyPrefix = "cocache:app:"` and `namespace = "user"`. Without `:`, one namespace's keys can never fall inside another namespace's range.
- Cache names must be unique within an application, and so must prefix-and-namespace pairs. Two caches with the same namespace and different prefixes are fine. A duplicate fails startup.
- Invalidations travel on the channel `{keyPrefix}inv:{namespace}`, so applications with different prefixes don't receive each other's messages.
- The **encoded key** is what the actuator endpoint and `ExternalInvalidator` take, so other tools can address a key without knowing its Kotlin type.
- To share one Redis between applications, give each a different prefix with the [`cocache.defaults.key-prefix` property](./configuration.md#key-prefix) (for example `${spring.application.name}:`). `@CoCache(keyPrefix)` is literal text: placeholders in it are not resolved (and in Kotlin, `$` starts a string template). Changing the prefix or namespace of a running derived cache is a cold start: old keys are ignored and expire on their own. For an authoritative type it loses the data, because the old keys hold the only copy.

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

A `Cache` declares where its data comes from. `CacheLoader<K, V>` loads what the cache doesn't have. It only loads; the cache decides how long values live.

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
- **Interfaces** find the loader by type `CacheLoader<K, V>`, then `AsyncCacheLoader<K, V>`. If several beans match, or one of each, startup fails; name one with `@CoCache(loader = "…")`. If none matches, startup fails too: declare `@CoCache(source = CoCache.Source.EXTERNAL)` when callers supply the values, or extend `Store` when nothing can rebuild them.
- **Per-call loaders**: `cache.get(key) { k -> … }` loads a miss with the given loader instead, with the same protection. Spring's `@Cacheable(sync = true)` uses this.
- **Non-blocking sources**: implement `AsyncCacheLoader<K, V>` (`loadAsync` returns a `CompletionStage`) and set it as `asyncLoader` in a spec. A cache has one source, sync or async; both APIs work with either. See [Async API](./async.md#loader).

### Externally sourced caches {#externally-sourced}

`externallySourced()` (`@CoCache(source = EXTERNAL)` on an interface) declares a `Cache` whose values come from its callers: `put`, a Spring Cache, or a per-call loader. It is still a derived copy, so it gets a derived copy's defaults: a 1 h `ttl`, and a failed `invalidate` retried in the background.

- **A miss is "unfilled".** When Redis doesn't have the key, a read without a loader returns `null` (`Lookup.Absent`). That answer goes into L2, fenced like any fill and capped at `absentTtl` and `localMaxAge`, but a call that brings its own loader doesn't accept it: `get(key) { … }` loads anyway. Nothing is written to Redis.
- **A Redis read failure has no answer.** A read without a loader gets [`Lookup.Unknown`](#unknown): `get` throws `CacheReadException`. Declare `unknownRead = UnknownReadMode.ABSENT` on the spec when "absent" is a safe answer. A read with a per-call loader follows `readFailure` and, by default, loads from that loader.
- **Unreadable content** (content this version can't decode) makes a read without a loader throw `CacheReadException`; a read with a loader reclaims it and reloads. Content written by a newer CoCache reads as unfilled.

### Versioned loaders {#versioned-loaders}

A `VersionedCache` loads values together with their version. Its source returns `Versioned(value, version)`:

```kotlin
versionedCacheSpec<String, OrderView>("order-view") {
    codec = jacksonCodec<OrderView>(mapper)
    versionedLoader = CacheLoader { id ->
        val state = repository.find(id) ?: return@CacheLoader null        // never existed
        Versioned(if (state.deleted) null else state.view, state.version) // deleted: Versioned(null, version)
    }
}
```

**Express a deletion as `Versioned(null, version)`.** It writes a tombstone that blocks older versions. A plain `null` means "never existed" and caches a negative entry without a version. When Redis already holds versioned content for the key (for example after [`evict`](./freshness.md#versioned-writes)), that unversioned `null` is refused: the read returns "absent" but nothing is written to Redis, so every miss reloads until the version fence expires. Each refusal is counted in `cocache.fill.unversioned.absent`, and the first one per cache logs a warning. Interfaces find the source by type `CacheLoader<K, Versioned<V>>` or `AsyncCacheLoader<K, Versioned<V>>`. A `VersionedCache` without one fails startup; data without a versioned source belongs in a `VersionedStore`.

### Per-entry TTL {#ttl-of}

When lifetime depends on the value, set `ttlOf` (spec or customizer):

```kotlin
cacheSpec<String, Session>("SessionCache") {
    codec = jacksonCodec<Session>(mapper)
    loader = CacheLoader { id -> sessions.find(id) }
    ttlOf = TtlFunction { _, session -> Duration.between(Instant.now(), session.expiresAt) }
}
```

A non-null result overrides the policy `ttl` and is not jittered. `null` falls back to the policy. Negative entries always use `absentTtl`, and tombstones use `tombstoneTtl`. The `ttl` property doesn't affect `ttlOf`. `StoreSpec`, `VersionedCacheSpec` and `VersionedStoreSpec` take a `ttlOf` too.

### Rejecting impossible keys {#key-filter}

A `KeyFilter` (spec or customizer) is checked after an L2 miss. A key it rejects returns "doesn't exist" without touching Redis or the loader, which is useful in front of a Bloom filter:

```kotlin
keyFilter = KeyFilter { storageKey -> !bloom.mightContain(storageKey) }
```

It receives the full storage key and must reject only keys that certainly don't exist.

## Reading: present, absent, or unknown {#unknown}

Every type reads through L2 and Redis. `lookup(key)` tells three answers apart:

| `Lookup` | Means | `get` returns |
|----------|-------|---------------|
| `Present(value, expiresAt)` | The value | the value |
| `Absent(expiresAt)` | Confirmed absent: a negative entry, a `Store` key Redis doesn't have, an unfilled key | `null` |
| `Unknown(error)` | This read has no answer: Redis failed or timed out | throws `error` (a `CacheReadException`) |

`Unknown` is a fact about one read, not about the data: it is never cached, never broadcast, and never handed to a caller that has its own source. `lookup(key).valueOrThrow()` returns the value or `null` and throws on `Unknown`. In Java 21, switch exhaustively over the sealed type; `as? Lookup.Present` silently treats `Unknown` as absent.

- **Who gets `Unknown`:** reads without a source (a `Store`, a `VersionedStore`, a `SetStore`, an externally sourced `Cache` read without a loader), and reads with a source when you set `readFailure = THROW`. By default a read with a source loads from it instead.
- **`unknownRead`** on a `CacheSpec` (externally sourced only), `StoreSpec` or `VersionedStoreSpec` maps `Unknown` for reads without a loader: `THROW` (default) or `ABSENT`, which returns `null` (`Lookup.Absent`) and caches nothing, so the next read asks Redis again. It doesn't apply to `getIfChanged`, `getVersioned` or reads with a loader, and a `SetStore` doesn't have it. Set it in the spec, with `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)` on an interface, or in a [customizer](#customizer); it has no property yet.
- **`getAll`** finishes every other key first (releasing leases, writing back loads), then throws a `CacheReadException` whose `unknownKeys` are the keys without an answer and whose `partialResult` holds the values it did get. `lookupAll(keys)` returns a `Lookup` for every key instead of throwing.
- Load timeouts (`load-timeout`) and rejected loads (`load-max-concurrent`) are not Redis failures: they throw their own exceptions, from `lookup` too.
- Each `Unknown` counts in `cocache.reads.unknown`, including those mapped to absent, and the Redis failure itself in `cocache.remote.failures`.

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

If a payload can't be decoded (for example, after an incompatible change to the value class), a cache with a loader treats it as a miss and reloads it. A type without a source has nothing to reload from and throws `CacheReadException` instead, leaving the key untouched. Changes that still decode but mean something different won't be caught: change the namespace or key prefix for those.

Redis values carry a short binary header before the codec's bytes. `redis-cli GET` shows binary, and other programs must not read or write CoCache keys directly.

## Stores {#store}

`Store<K, V>` is for data whose **only copy is Redis**: a policy snapshot published by a control plane, a revocation list, a session, a one-time code. There is no loader, and reads never take a load lease.

```kotlin
snapshots.put(id, snapshot)                  // last writer wins
snapshots[id]                                // null when Redis doesn't have it
snapshots.delete(id)                         // synchronous; throws if Redis fails
```

- **Absent means absent.** A key Redis doesn't have, including one that holds another cache's load lease, reads as `Lookup.Absent`, and that answer goes into L2 like a value.
- **Redis failures are [`Unknown`](#unknown)**: `get` throws `CacheReadException` unless the spec says `unknownRead = ABSENT`.
- **Deletes never retry.** `delete` and `deleteIfMatch` complete before they return, or throw `CacheWriteException`. A delayed retry could delete a value written after the failure. When deletes must survive retries and reordering, use a `VersionedStore` and `deleteIfNewer`.
- **Every write carries a writer id and the SHA-256 of the payload** in the value's header. Reads recompute the digest; a value whose payload doesn't match, or that has no digest (another type wrote it), throws `CacheReadException` and is kept as it is.
- **Conditional writes** (`getIfChanged`, `putIfMatch`, `deleteIfMatch`) let several writers read, change and write one value without losing updates. See [Conditional writes](./freshness.md#conditional-writes).
- **Durability is Redis durability.** Use a `maxmemory-policy` that never evicts these keys (not `allkeys-*`); the starter [checks this at startup](./configuration.md#redis). An asynchronous-replication failover can lose recent writes.
- **Unreadable content** (content in a format this version can't read, a value another program wrote, a newer CoCache's value) throws `CacheReadException` and is never deleted, because deleting it would delete the only copy.

### Default policy {#store-defaults}

Authoritative types start from their own default policy, `StoreSpec.DEFAULT_POLICY` (and the same values in `VersionedStoreSpec.DEFAULT_POLICY` and `SetStoreSpec.DEFAULT_POLICY`):

| Field | `Store`, `VersionedStore`, `SetStore` | Derived types (`CachePolicy.DEFAULT`) |
|-------|---------------------------------------|---------------------------------------|
| `ttl` | forever | 1 h |
| `localMaxAge` | 30 s | 5 min |
| `absentTtl` | 10 s | 1 min |
| `tombstoneTtl` | 1 day (`VersionedStore` only) | the value `ttl` |

The L2 lifetimes are shorter because a lost invalidation can't be corrected by a reload: the only bound left is L2's. Write them out on the declaration when you need longer ones. Data whose lifetime is its meaning (a session, a one-time code, an idempotency key) needs an explicit `ttl`.

## Versioned types {#versioned}

`VersionedCache<K, V>` and `VersionedStore<K, V>` store a version next to each value and order writes by it, so a redelivered or replayed older version can't replace a newer one. Neither has a write that drops the version:

| | `VersionedCache` | `VersionedStore` |
|-|------------------|------------------|
| Source | a [versioned loader](#versioned-loaders) | none |
| Write | `putIfNewer(key, value, version)` | `putIfNewer(key, value, version)` |
| Delete with ordering | `invalidateIfNewer(key, version)` (tombstone) | `deleteIfNewer(key, version)` (tombstone) |
| Delete keeping the fence | `evict(key)`: the next read reloads, older versions are still refused | — |
| Delete dropping the fence | `purge(key)` | `purge(key)` |
| Read with version | `getVersioned(key)` | `getVersioned(key)` |
| Writer id in the header | no | yes |
| Refresh-ahead | yes | no |

[Versioned writes](./freshness.md#versioned-writes) covers the rules. A `VersionedStore` reads like a `Store`: no load lease, absent means absent, a Redis failure is `Unknown`. Its tombstones live for `tombstoneTtl` (1 day by default), which must cover your redelivery window; a `VersionedStore` whose tombstones would never expire fails to build.

## Set stores {#set-store}

`SetStore<K, E>` is for a set whose **only copy is Redis**: an index, a membership list, a set of online sessions. Elements are added and removed atomically in Redis, so concurrent updates from different instances are never lost.

```kotlin
@CoCache
interface OnlineUsers : SetStore<String, String>

online.add(roomId, listOf(userId))       // creates the set if needed
online.remove(roomId, listOf(userId))    // deletes the key when it becomes empty
online.clear(roomId)
val members: Set<String> = online[roomId] // empty set when absent
```

- **No loader**, and no `invalidate`: `clear` deletes the set. `@CoCache(loader = …)` on a set store fails startup.
- **`put(key, emptySet())` equals `clear(key)`**: an empty set is stored as no key. `putAll` follows the same rule for each entry ([batch writes](./freshness.md#batch-writes)).
- **TTL defaults to forever.** Each `add` or `remove` resets the expiry when you set one. An absent set stays in L2 for at most `absentTtl` (10 s).
- **Failures are loud.** Writes throw `CacheWriteException`. A Redis read failure is [`Unknown`](#unknown): `get` throws `CacheReadException`, `lookup` returns `Unknown`. A set store has no `unknownRead`.
- **Unreadable content throws.** An element the codec can't decode makes the read throw, and a set written in a newer format throws `IllegalStateException`. Content in a format this version can't read (for example a native Redis Set at the same key) makes the read throw `CacheReadException`, and an `add` or `remove` on it, or on a set in a newer format, throws `CacheWriteException` and leaves the key unchanged. Only `put` replaces it. A set store never reclaims such content ([unreadable payloads](../operations/troubleshooting.md#corrupt-payloads)).
- **Durability is Redis durability**, as for a [Store](#store).

If the set's truth lives in your database, don't use `SetStore`. Declare `Cache<K, Set<E>>`, update the database, and `invalidate`.

## Libraries and plain runtimes: `CoCacheFactory` {#factory}

`CoCacheFactory` is the entry point for creating caches outside the starter's own declarations. With the starter, inject the `CoCacheFactory` bean: a cache it creates gets the same customizers and `cocache.*` properties as an `@CoCache` interface or a spec bean, shows up on the endpoint and gets meters.

```kotlin
interface RevokedTokens : Store<String, Instant>

@Bean
fun revokedTokens(factory: CoCacheFactory): RevokedTokens =
    factory.create(RevokedTokens::class.java, storeSpec("revoked-tokens") { codec = jacksonCodec<Instant>() })
```

- `create(spec)` returns the type matching the spec (`storeSpec` gives a `Store`, and so on). `create(type, spec)` returns a proxy for your interface. The interface must extend the type the spec declares, and every abstract method it redeclares must match one of that type's methods. A class is rejected: the user API types are not for hand-written implementations. Create the cache with `create(spec)` and delegate to it instead. These checks run before the cache is created.
- **Unlayered caches.** A cache created directly with `CacheRuntime.create` skips every configuration layer: customizers, `cocache.defaults` and `cocache.caches.[name]` don't apply to it. The start log ends its line with `unlayered: …`, the endpoint reports `layered: false`. A `cocache.caches.[name]` entry for it logs a warning when the cache is created and is listed under the endpoint's `ignoredConfiguration`. It doesn't fail startup, because overrides are often written during an incident. Libraries should create their caches through `CoCacheFactory`.
- Without Spring, `CoCacheFactory(runtime)` gives you the interface proxies with no layers. Plain `runtime.create(spec)` remains fine for an application that has no configuration layers.

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

    override fun customize(spec: StoreSpec.Builder<*, *>) {
        spec.unknownRead = UnknownReadMode.ABSENT              // Redis down: treat as "not revoked"
    }
}
```

In Java, implement the same interface and call `spec.updatePolicy(p -> p.absentTtl(Duration.ofSeconds(30)))`.

There is one overload per spec type (`CacheSpec.Builder`, `VersionedCacheSpec.Builder`, `StoreSpec.Builder`, `VersionedStoreSpec.Builder`, `SetStoreSpec.Builder`); each does nothing by default. Customizers run in Spring order, after the declaration and before the `cocache.*` properties ([precedence](./configuration.md#precedence)).

## Startup validation {#validation}

CoCache refuses to start rather than run a cache that can't behave as declared. Each message names the cache.

| Fails startup | Why |
|---------------|-----|
| An interface that extends none, or more than one, of the five types, or without concrete type arguments; a spec bean whose return type doesn't declare them | Keys, loader, and codec are resolved from those types |
| Abstract interface method that matches no method of the type it extends | The call would fail at runtime instead |
| A `Cache` without a matching loader and without `source = EXTERNAL`; a `VersionedCache` without a versioned source; a `CacheSpec` with no source or more than one | A cache must say where its data comes from |
| `loader` or `source = EXTERNAL` on a `Store`, `VersionedStore` or `SetStore`; `unknownRead` on a `CacheSpec` that has a source; `@CoCache(unknownRead = ABSENT)` on a `Cache` with a loader, a `VersionedCache` or a `SetStore` | These types have no source, or their reads don't take `unknownRead` |
| Several `CacheLoader<K, V>` or `Codec<V>` beans match | A silent pick could use the wrong source or format |
| A backend whose store lacks what the type needs ([capabilities](#spec-bean)) | The type's operations couldn't work |
| A `VersionedStore` whose tombstones would never expire | Every deleted key would stay in Redis forever |
| `FOREVER` on a duration attribute other than `ttl` / `expireAfterAccess`, a zero duration (except `ttlJitter`, `refreshWindow`), or any negative duration | Meaningless or unsafe lifetimes |
| `refreshWindow` not less than `ttl − ttlJitter` (the effective jitter), or not less than `localMaxAge` | A fresh copy would start inside the window and refresh on every hit |
| Duplicate cache name, prefix-and-namespace pair, or bean name | Two caches would share keys or invalidations |
| A namespace outside `[A-Za-z0-9._-]+`, such as one with `:` | Its keys could fall inside another namespace's range; move the prefix into `keyPrefix` |
| `cocache.caches.[name]` for a cache that doesn't exist | Almost always a typo; see [Configuration Reference](./configuration.md#validation) |

An authoritative interface for which a matching `CacheLoader` bean exists starts, but logs a warning: the data may belong in a `Cache`. The `toString()` check on key types runs on the first read of each key type, not at startup.
