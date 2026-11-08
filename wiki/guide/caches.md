---
title: Choosing and Declaring Caches
description: Choose between the five cache types (Cache, VersionedCache, Store, VersionedStore, SetStore) with one question, declare them as annotated interfaces or spec beans, and control their names, storage keys, key encoding, codecs, default policies, CoCacheFactory, customizers, and startup validation.
---

# Choosing and Declaring Caches

A cache is a name, a key type, a value type, a type that says what Redis holds for it, a way to encode values for Redis, and a policy. This page starts with the type, then shows what every type shares: the two ways to declare one, keys, codecs, reads, and startup checks. Each type has its own guide for its operations.

## Choosing a type {#types}

Ask one question about your data: **if Redis loses this key, can this process rebuild it on demand?**

- **Yes**: Redis holds a *derived copy*. Declare a `Cache`, or a `VersionedCache` when the writer orders its writes by version.
- **No**: Redis holds the *only copy*. Declare a `Store`, a `VersionedStore`, or, for a set of elements, a `SetStore`.

| Type | Redis holds | Data comes from | Writes | Delete | Guide |
|------|-------------|-----------------|--------|--------|-------|
| `Cache<K, V>` | A derived copy | A `loader` / `asyncLoader`, or the callers (`externallySourced()`) | `put`, `putAll` (last writer wins) | `invalidate`, retried in the background if Redis fails | [Cache](./cache.md) |
| `VersionedCache<K, V>` | A derived, versioned copy | A versioned loader | `putIfNewer`, `invalidateIfNewer` | `evict` keeps the version fence; `purge` drops it | [VersionedCache](./versioned-cache.md) |
| `Store<K, V>` | The only copy | Your writes | `put`, `putAll`, `putIfMatch` | `delete`, `deleteIfMatch`: synchronous, never retried | [Store](./store.md) |
| `VersionedStore<K, V>` | The only, versioned copy | Your versioned writes | `putIfNewer` | `deleteIfNewer` (tombstone); `purge` | [VersionedStore](./versioned-store.md) |
| `SetStore<K, E>` | The only copy of a set | Your element writes | `add`, `remove`, `put`, `putAll` | `clear` | [SetStore](./set-store.md) |

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

All declarations of one namespace must use the same type. The type isn't stored in Redis, so CoCache can only partly detect a mismatch across applications: a derived cache that reads a `Store` value counts it in `cocache.type.mismatch` and logs a warning. The start log names each cache's type (`Cache[name] - created as Store on namespace[…]`), so you can compare declarations across applications. A shared cache must match in more than its type: see [Sharing a cache across applications](#sharing).

**Getting it wrong**:

| Mistake | Consequence | How you find out |
|---------|-------------|------------------|
| The only copy declared as a `Cache` with `externallySourced()` | It expires after the default `ttl` (1 h). A failed `invalidate` is retried later and may delete a value written meanwhile. The Redis eviction check skips it. | The start log names the type of every cache |
| A rebuildable copy declared as a `Store` | After Redis loses the key, reads return "absent" until someone writes it again. A failed `delete` throws instead of retrying. | Startup warns when a matching `CacheLoader` or `AsyncCacheLoader` bean exists |
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

The interface must extend exactly one of `Cache<K, V>`, `VersionedCache<K, V>`, `Store<K, V>`, `VersionedStore<K, V>` or `SetStore<K, E>`, or one of their async or suspending views (`AsyncCache`, `SuspendingCache`, `AsyncStore`, `SuspendingStore` and so on), with concrete type arguments. That type decides what kind of cache is created. A view declares the same kind of cache as its synchronous type (a `SuspendingStore` is a `Store`), and the bean is the view:

```kotlin
@CoCache
interface UserCache : SuspendingCache<String, User>

suspend fun profile(id: String): User? = userCache.get(id)
```

Calls through a suspending interface go straight to the cache's suspending view, and an `Async*` interface returns futures ([coroutines](./coroutines.md#declare), [async](./async.md#declare)). `@CoCache` itself is optional: an interface listed in `@EnableCoCache` without it gets every default. Annotations are read from the interface itself, not from meta-annotations or super-interfaces.

The user API types are not meant to be implemented by your own classes, except to delegate to a cache CoCache created: new 8.x operations are added as new capability types or as default methods, which hand-written implementations may not anticipate. Interfaces that extend them, like the ones above, are fine: the proxy implements them.

### `@CoCache` {#cocache-annotation}

Durations are in **seconds**. Every policy attribute defaults to `CoCache.UNSET` (`-1`): "not written here", so `cocache.defaults` or the type default decides ([precedence](./configuration.md#precedence)). An attribute you set is written by the declaration, and global defaults don't change it. Each type starts from its own default: `DefaultPolicies.CACHE` and `DefaultPolicies.VERSIONED_CACHE` for the derived types, and the [authoritative defaults](#store-defaults) for `Store`, `VersionedStore` and `SetStore`.

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
| `loader` | `""` | Bean name of the data source (`Cache` and `VersionedCache` only). Empty: the single matching bean ([loaders](./cache.md#loaders), [versioned loaders](./versioned-cache.md#versioned-loaders)). |
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

A `maximumSize` you set is written by the declaration even when it equals the default `10000`, so `cocache.defaults.*.local-maximum-size` doesn't replace it ([which fields a declaration writes](./configuration.md#declared-fields)).

### Matching beans by type {#bean-matching}

When `loader` or `codec` is empty, the starter looks the bean up by its full generic type (`CacheLoader<K, V>`, `AsyncCacheLoader<K, V>`, `Codec<V>`; a [join cache](./join-cache.md) does the same for `JoinKeyExtractor<V1, K2>`). It sees the same beans as a lookup by name:

- **Parent contexts count.** A bean in the child context hides a parent bean with the same name, whatever its type. So does an alias the child registers, even when it points to a bean in the parent: `getBean` by that name returns the alias's target, so the parent's own bean of that name is not a candidate. That includes a parent's scoped proxy: its target (`scopedTarget.loader` behind `loader`) is hidden with it. A matching bean in the child and another in the parent, under different names, are two candidates, so startup fails as for any ambiguity, the same as Spring's injection by type.
- **A scoped proxy is injected as the proxy** (`@Scope(proxyMode = …)`), so its scope still applies: a prototype loader gets a new target for each call. A scoped `JoinKeyExtractor` named with `joinKeyExtractor = "…"` has its generics checked on its target's definition, because the proxy instance has lost them.
- **The type arguments are the ones the interface inherits.** With `interface BaseStore<V : Any> : Store<String, V>` and `interface UserStore : BaseStore<User>`, `UserStore` matches `Codec<User>`, and the default Jackson codec decodes `User`, also inside `List<User>` and as set elements.

### Adding methods {#interface-methods}

Default methods run as written, so you can add helpers:

```kotlin
interface UserCache : Cache<String, User> {
    fun nameOf(id: String): String? = get(id)?.name
}
```

Default methods may live in a package-private interface: the interface you declare can be package-private, or public and inherit them from a package-private base. CoCache then calls them through that interface's private lookup. In a named module, `opens` the package to CoCache, or creating the cache fails.

An abstract method must match a method of the type the interface extends, by name, parameters, and return type, otherwise startup fails. The value type may narrow (`get` returns `User` instead of `V`), but any other return type, such as the `Map` of `getAll`, a `Set`, a `Lookup`, or a view, may only stay the same or widen: the cache returns its own implementation (a `LinkedHashMap`, not a `SortedMap`). The same holds for the result of a `suspend` method. In a `Suspending*` interface a default method can be `suspend` and call the interface's suspending methods. Exceptions from the cache and from your default methods reach you unwrapped.

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

- **The return type must declare the generics** (`CacheSpec<String, Product>`). The starter reads `K` and `V` from it without creating the spec. A `FactoryBean<CacheSpec<String, Product>>` works too: `K` and `V` come from its product type, and the factory isn't created to read them. A raw return type fails startup.
- **Bean name**: the spec bean name minus its `Spec` suffix (`productSpec` → `product`). Without the suffix, `Cache` is appended (`products` → `productsCache`). The *cache* name is the one you pass to `cacheSpec(…)`.
- **A spec must set its codec.** There is no Jackson fallback in code; `ObjectMapper` here is Jackson 3 (`tools.jackson.databind`). A `SetStoreSpec` sets `elementCodec`.
- **A `CacheSpec` must declare exactly one source**: `loader`, `asyncLoader`, or `externallySourced()`. A `VersionedCacheSpec` must set `versionedLoader` or `versionedAsyncLoader`. Authoritative specs have no loader at all.
- In Kotlin, `policy { … }` edits the type's default policy; in Java, use `updatePolicy(…)`. Only the fields you assign count as written by the declaration; the rest stay open to `cocache.defaults` ([which fields a declaration writes](./configuration.md#declared-fields)). `policy(CachePolicy)` (Kotlin: `policy = …`) replaces the whole policy and counts as writing every field, so global defaults no longer apply. `CachePolicy` has no public constructor and no `builder()` without a base, so such a policy always starts from one: the type's own default, `DefaultPolicies.STORE.toBuilder()` or `CachePolicy.builder(DefaultPolicies.STORE)`. Starting a `Store`'s policy from a derived cache's default would give it a 1 h `ttl`. `ttl = forever()`, `refreshWindow = off()` and `maxConcurrent = unlimited()` write the sentinels explicitly. Java (and Kotlin code that wants the type checked) uses the typed setters: `ttl(Ttl.FOREVER)`, `refreshWindow(RefreshWindow.OFF)`, `maxConcurrent(Concurrency.UNLIMITED)`, and `tombstoneTtl(Ttl.FOREVER)`. In Java, `ttl(null)`, `tombstoneTtl(null)`, `refreshWindow(null)` and `maxConcurrent(null)` no longer compile, because `null` matches both overloads.

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
| `keyPrefix` | `cocache:` | `@CoCache(keyPrefix)`, the spec's `keyPrefix`, or the [`key-prefix` property](./configuration.md#key-prefix). The properties win over the declaration unless a library [locked](./configuration.md#locks) the prefix. |
| `namespace` | the cache name | `@CoCache(namespace)`, the spec's `namespace` |
| `encodedKey` | `key.toString()` | `@CoCache(keyExpression)`, the spec's `keyEncoder` |

- CoCache's internal keys live under `{keyPrefix}!`, which no namespace can produce: `!` is outside the namespace charset. The one exception is the atomic-broadcast probe, which uses the fixed key `cocache:!probe:{a}` (and channel `cocache:!probe:{b}`) whatever the prefix.
- A namespace may contain only letters, digits, `.`, `_` and `-` (`[A-Za-z0-9._-]+`). A namespace with `:` fails at creation with a message that shows how to move the part before the `:` into `keyPrefix`: `namespace = "app:user"` becomes `keyPrefix = "cocache:app:"` and `namespace = "user"`. Without `:`, one namespace's keys can never fall inside another namespace's range.
- Cache names must be unique within an application, and so must prefix-and-namespace pairs. Two caches with the same namespace and different prefixes are fine. A duplicate fails startup. So do two caches whose storage prefixes `{keyPrefix}{namespace}:` are equal or one starts the other, such as `app:` + `users` and `app:users:` + `v2`: the key `v2:k` of the first and the key `k` of the second would both be `app:users:v2:k`. CoCache checks this within one runtime; across applications sharing a Redis, choose prefixes that don't start one another.
- Invalidations travel on the channel `{keyPrefix}inv:{namespace}`, so applications with different prefixes don't receive each other's messages.
- The **encoded key** is what the actuator endpoint and `ExternalInvalidator` take, so other tools can address a key without knowing its Kotlin type.
- To share one Redis between applications, give each a different prefix with the [`cocache.key-prefix` property](./configuration.md#key-prefix) (for example `${spring.application.name}:`). `@CoCache(keyPrefix)` is literal text: placeholders in it are not resolved (and in Kotlin, `$` starts a string template). Changing the prefix or namespace of a running derived cache is a cold start: old keys are ignored and expire on their own. For an authoritative type it loses the data, because the old keys hold the only copy.

## Sharing a cache across applications {#sharing}

Two applications share a cache only when everything that decides where and how its values are stored matches: the key prefix, the namespace, the key encoder, the type and the codec. CoCache can't compare configuration across applications, and the two kinds of mismatch behave differently:

| Mismatch | What happens | Detected |
|----------|--------------|----------|
| Key prefix, namespace or key encoder | Each application uses its own keys and its own invalidation channel (`{keyPrefix}inv:{namespace}`), and nothing fails. A derived cache stays stale until its `ttl`; an authoritative cache becomes two unrelated data sets. | No: the applications never touch the same key |
| Type or codec | Each reads values the other wrote. A derived cache that reads a `Store` value counts it in `cocache.type.mismatch`; a value it can't decode is reclaimed and reloaded (`cocache.reclaims`), so the two keep overwriting each other. An authoritative cache fails the read with `CacheReadException`. | Yes, once it happens |

So prevent a mismatch rather than wait for it:

1. **Declare the shared cache once.** Put the declaration in a module every application depends on, and create the cache through the `CoCacheFactory` bean with its layout [locked](./configuration.md#locks):

   ```kotlin
   // In a module that every application sharing the cache depends on
   @Bean
   fun sharedIndex(factory: CoCacheFactory, loader: IndexLoader): Cache<String, Index> =
       factory.create(cacheSpec<String, Index>("SharedIndexCache") {
           keyPrefix = "shared:"
           codec = IndexCodec
           this.loader = loader
       }) {
           lockLayout(LayoutField.KEY_PREFIX, LayoutField.NAMESPACE, LayoutField.KEY_ENCODER, LayoutField.CODEC)
       }
   ```

   With the layout locked, an application's `cocache.key-prefix` skips this cache (the start log says why, and the endpoint lists it under `skippedConfiguration`). A `cocache.caches.[SharedIndexCache].key-prefix` fails startup unless the entry lists `key-prefix` in `unlock`, which reports the field `UNLOCKED` and logs a warning. A customizer that changes a locked part throws. `@CoCache` interfaces and spec beans can't carry locks, so don't declare a shared cache that way.

2. **Test the layout in every application.** A test that starts the application with its real configuration and checks the effective layout catches drift in CI. Keep it in the shared module, next to the declaration, so every application runs the same check:

   ```kotlin
   val admin = requireNotNull(runtime.admin("SharedIndexCache"))
   assertEquals("shared:", admin.keyspace.keyPrefix)
   assertEquals("SharedIndexCache", admin.keyspace.namespace)
   assertEquals(Cache::class.java, admin.apiType)
   ```

3. **Compare on deploy.** The start log names each cache's type and namespace (`Cache[name] - created as … on namespace[…]`), and the [actuator endpoint](../operations/observability.md#actuator-endpoint) reports `keyPrefix` and `namespace` for every cache.

Policy fields such as `ttl` or `local-max-age` don't decide where values are stored, so they don't have to match: each instance applies its own. The source must still be the same data for a derived cache, or each application caches its own answer under the same key.

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

An encoded key must also be well-formed UTF-16. Redis keys and invalidation messages are UTF-8, which can't represent an unpaired surrogate: a lone `char` in `\uD800`–`\uDFFF`, such as half an emoji left by cutting a string. It would turn into `?`, share its Redis key with another key, and other instances would evict that other entry instead. Every call with such a key fails with `IllegalArgumentException` before it changes anything: reads, writes, `CacheAdmin` and [`ExternalInvalidator`](../operations/troubleshooting.md#external-invalidator) alike, and a batch call is rejected as a whole, wherever the key sits in it: a rejected `getAll` refreshes nothing, loads nothing and counts nothing, even for the valid keys before it. The rejection is a caller error, not an L1 fault: `read-failure` and `unknownRead` ([unknown reads](#unknown)) don't apply to it, so such a call never becomes a miss, an `ABSENT` or a load from the source. The message names the cache and the position and shows the character as `\uXXXX`. Emoji and other characters outside the BMP, CJK text and the empty string work as usual. The check runs only on the way to Redis, so an L2 hit costs the same as before.

## Per-entry TTL {#ttl-of}

When lifetime depends on the value, set `ttlOf` (spec or customizer):

```kotlin
cacheSpec<String, Session>("SessionCache") {
    codec = jacksonCodec<Session>(mapper)
    loader = CacheLoader { id -> sessions.find(id) }
    ttlOf = TtlFunction { _, session -> Duration.between(Instant.now(), session.expiresAt) }
}
```

A non-null result overrides the policy `ttl` and is not jittered. `null` falls back to the policy. Negative entries always use `absentTtl`, and tombstones use `tombstoneTtl`. The `ttl` property doesn't affect `ttlOf`, except that on a `Cache`, `cocache.overrides.cache.ttl` caps its results ([overrides](./configuration.md#field-layers)), counted in `cocache.ttl.clamped`. `StoreSpec`, `VersionedCacheSpec` and `VersionedStoreSpec` take a `ttlOf` too.

## Rejecting impossible keys {#key-filter}

A `KeyFilter` (spec or customizer) is checked after an L2 miss. A key it rejects returns "doesn't exist" without touching Redis or the loader, which is useful in front of a Bloom filter:

```kotlin
keyFilter = KeyFilter { encodedKey -> !bloom.mightContain(encodedKey) }
```

It receives the [encoded key](#storage-keys), without `{keyPrefix}{namespace}:`, and must reject only keys that certainly don't exist. Build the filter from encoded keys: it then keeps answering correctly when the key prefix or namespace changes.

## Reading: present, absent, or unknown {#unknown}

Every type reads through L2 and Redis. `lookup(key)` tells three answers apart:

| `Lookup` | Means | `get` returns |
|----------|-------|---------------|
| `Present(value, expiresAt)` | The value | the value |
| `Absent(expiresAt)` | Confirmed absent: a negative entry, a `Store` key Redis doesn't have, an unfilled key | `null` |
| `Unknown(error)` | This read has no answer: Redis failed or timed out | throws `error` (a `CacheReadException`) |

`Unknown` is a fact about one read, not about the data: it is never cached, never broadcast, and never handed to a caller that has its own source. `lookup(key).valueOrThrow()` returns the value or `null` and throws on `Unknown`. In Java 21, switch exhaustively over the sealed type; `as? Lookup.Present` silently treats `Unknown` as absent.

`Lookup.Known` is the sealed supertype of `Present` and `Absent`. A read that only answers returns it and throws where `lookup` would return `Unknown`, so matching its result takes two cases. [`lookupRemote`](./store.md#reading), which reads Redis now on a `Store` or `VersionedStore`, is one. A match over `Lookup` itself still has the three cases above.

- **Who gets `Unknown`:** reads without a source (a `Store`, a `VersionedStore`, a `SetStore`, an externally sourced `Cache` read without a loader), and reads with a source when you set `readFailure = THROW`. By default a read with a source loads from it instead.
- **`unknownRead`** on a `CacheSpec` (externally sourced only), `StoreSpec` or `VersionedStoreSpec` maps `Unknown` for reads without a loader: `THROW` (default) or `ABSENT`, which returns `null` (`Lookup.Absent`) and caches nothing, so the next read asks Redis again. It applies to `getVersioned` as to `get`, not to `getIfChanged`, `lookupRemote` or reads with a loader, and a `SetStore` doesn't have it. Set it in the spec, with `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)` on an interface, in a [customizer](#customizer), or with `cocache.caches.[name].unknown-read` ([configuration](./configuration.md#policy-properties)); no global layer sets it.
- **`getAll`** finishes every other key first (releasing leases, writing back loads), then throws a `CacheReadException` whose `unknownKeys` are the keys without an answer and whose `partialResult` holds the values it did get. `lookupAll(keys)` returns a `Lookup` for every key instead of throwing.
- Load timeouts (`load-timeout`) and rejected loads (`load-max-concurrent`) are not Redis failures: they throw their own exceptions, from `lookup` too.
- Each `Unknown` counts in `cocache.reads.unknown`, including those mapped to absent, and the Redis failure itself in `cocache.remote.failures`.

**Retry or degrade.** `CacheFailures.isTransient(error)` (`cocache-core`) tells whether a failure is temporary, so a caller can serve a fallback or retry later without catching each exception type:

::: code-group

```kotlin [Kotlin]
val user = try {
    users[id]
} catch (e: RuntimeException) {
    if (CacheFailures.isTransient(e)) fallback(id) else throw e
}
```

```java [Java]
users.async().get(id).exceptionally(e -> {
    if (CacheFailures.isTransient(e)) return fallback(id);
    throw new CompletionException(e);
});
```

:::

- `true`: a load that timed out, was rejected or was cancelled (`SourceLoadTimeoutException`, `SourceLoadRejectedException`, `SourceLoadCancelledException`), and a failed Redis read or write (`CacheReadException`, `CacheWriteException`), unless Redis holds content this version can't read (the cause is a `ForeignFormatException`): a retry would read the same content, so upgrade the instance or deal with that key.
- `false`: everything else, including your loader's own exceptions and the `UnsupportedOperationException` of a backend that lacks a capability.
- It unwraps `CompletionException` and `ExecutionException` first, so it takes what `join()`, `get()` or `exceptionally` hands you.
- A write whose [outcome is unknown](./freshness.md#redis-down) is transient too, but check whether it took effect before you retry it.

## Codecs {#codecs}

A `Codec<V>` turns a value into the bytes stored in Redis and back.

| Declaration | Codec used |
|-------------|------------|
| Interface, `@CoCache(codec = "name")` | That bean |
| Interface, one bean of type `Codec<V>` | That bean |
| Interface, several beans of type `Codec<V>` | Startup fails; name one with `@CoCache(codec)` |
| Interface, none | `JacksonCodec` for `V`, built from your application's Jackson 3 `ObjectMapper` |
| Spec | Whatever you set: `jacksonCodec<V>(mapper)`, `JacksonCodec.of(mapper, V::class.java)`, `StringCodec`, or your own |

The `ObjectMapper` is looked up only when an interface falls back to Jackson, and it is shared by every cache that does. Caches with a named or matching codec, and specs, never use it, so several `ObjectMapper` beans don't matter to them. When a cache does fall back and there are several `ObjectMapper` beans, the `@Primary` one is used; without one, startup fails with an error that names the cache. With no `ObjectMapper` bean, CoCache builds its own `JsonMapper`.

A custom codec is two functions:

```kotlin
@Bean
fun moneyCodec(): Codec<Money> = object : Codec<Money> {
    override fun encode(value: Money) = value.toString().toByteArray()
    override fun decode(bytes: ByteArray) = Money.parse(String(bytes))
}
```

If a payload can't be decoded (for example, after an incompatible change to the value class), a cache with a loader treats it as a miss and reloads it. A read without a source (a `Store`, a `VersionedStore`, a `SetStore`, an externally sourced `Cache` read without a loader) has nothing to reload from and throws `CacheReadException` instead, leaving the key untouched. Changes that still decode but mean something different won't be caught: change the namespace or key prefix for those.

Redis values carry a short binary header before the codec's bytes. `redis-cli GET` shows binary, and other programs must not read or write CoCache keys directly.

## Default policy per type {#store-defaults}

Each type starts from its own default policy in `DefaultPolicies`: `STORE`, `VERSIONED_STORE` and `SET_STORE` for the authoritative types, `CACHE` and `VERSIONED_CACHE` for the derived ones. The endpoint reports values that come from here as `TYPE_DEFAULT`.

| Field | `Store`, `VersionedStore`, `SetStore` | `Cache`, `VersionedCache` |
|-------|---------------------------------------|---------------------------------------|
| `ttl` | forever | 1 h |
| `localMaxAge` | 30 s | 5 min |
| `absentTtl` | 10 s | 1 min |
| `tombstoneTtl` | 1 day (`VersionedStore` only) | the value `ttl` (`VersionedCache` only) |

The L2 lifetimes are shorter because a lost invalidation can't be corrected by a reload: the only bound left is L2's. Write them out on the declaration, or set them for every authoritative cache with `cocache.defaults.store.*`, when you need longer ones. Data whose lifetime is its meaning (a session, a one-time code, an idempotency key) needs an explicit `ttl`: on these types only the declaration and `cocache.caches.[name].ttl` set it, never a global layer ([what each layer may set](./configuration.md#field-layers)).

## Libraries and plain runtimes: `CoCacheFactory` {#factory}

`CoCacheFactory` is the entry point for creating caches outside the starter's own declarations. With the starter, inject the `CoCacheFactory` bean: a cache it creates gets the same customizers and `cocache.*` properties as an `@CoCache` interface or a spec bean, shows up on the endpoint and gets meters.

```kotlin
interface RevokedTokens : Store<String, Instant>

@Bean
fun revokedTokens(factory: CoCacheFactory, mapper: ObjectMapper): RevokedTokens =
    factory.create(RevokedTokens::class.java, storeSpec<String, Instant>("revoked-tokens") { codec = jacksonCodec<Instant>(mapper) })
```

- `create(spec)` returns the type matching the spec (`storeSpec` gives a `Store`, and so on). `create(type, spec)` returns a proxy for your interface. The interface must extend the type the spec declares, or that type's async or suspending view (`SuspendingStore` for a `storeSpec`), and every abstract method it redeclares must match one of that type's methods. A class is rejected: the user API types are not for hand-written implementations. Create the cache with `create(spec)` and delegate to it instead. A sealed interface is rejected too, because a JDK proxy can't implement it; a non-sealed interface that extends it works. These checks, and the JDK's own proxy checks, run before the cache is created: a rejected interface registers nothing, so a retry with the same spec and a valid interface succeeds.
- **Locks.** A library that depends on a field or on its storage layout locks it on creation: `factory.create(spec) { lock(PolicyField.READ_TIMEOUT); lockLayout(LayoutField.KEY_PREFIX) }`. Global layers then skip it, and a `cocache.caches.[name]` entry must `unlock` it explicitly. See [Locking fields](./configuration.md#locks).
- **Unlayered caches.** A cache created directly with `CacheRuntime.create` skips every configuration layer: customizers, `cocache.defaults`, `cocache.overrides` and `cocache.caches.[name]` don't apply to it, and it can't be locked. The start log ends its line with `unlayered: …`, the endpoint reports `layered: false`. A `cocache.caches.[name]` entry for it logs a warning when the cache is created and is listed under the endpoint's `ignoredConfiguration`. It doesn't fail startup, because overrides are often written during an incident. Libraries should create their caches through `CoCacheFactory`.
- **Caches created later.** A library that creates caches after startup, or in a lazily initialized bean, publishes the names it may create with a `CacheNameContributor` bean (`cocache-spring`, so the library doesn't need the starter). An application's `cocache.caches.[name]` entries for them then pass the [name check](./configuration.md#validation) instead of failing startup as undeclared.
- **Authoritative caches and Redis eviction.** With the starter, a `Store`, `VersionedStore` or `SetStore` created through the `CoCacheFactory` bean is checked against Redis's `maxmemory-policy` when it is created: a risk that would fail startup fails that creation ([eviction check](./configuration.md#eviction-check)). Without the starter, a library checks its authoritative caches itself once it has created them, with the same rule from `cocache-redis`: `StoreEvictionRisk.check(factory.runtime).requireSafe()`. It reads `INFO memory` at most once per primary, throws `IllegalStateException` for a fatal risk, and otherwise returns the verdict: `NotApplicable` when the backend isn't Redis (a wrapper such as `HookedBackend` is seen through) or there is no authoritative cache, `Safe`, or a `Risk` to log. Optional arguments set the mode for `volatile-*` (`StoreEvictionRisk.Mode.WARN` by default) and an assumed policy for a Redis that blocks `INFO`.
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

There is one overload per spec type (`CacheSpec.Builder`, `VersionedCacheSpec.Builder`, `StoreSpec.Builder`, `VersionedStoreSpec.Builder`, `SetStoreSpec.Builder`); each does nothing by default. Customizers run in Spring order, after `cocache.defaults` and the declaration, and before `cocache.overrides` and `cocache.caches.[name]` ([precedence](./configuration.md#precedence)). A customizer may change policy fields and fields that don't decide the type. Changing the data source, or a field or layout that a library [locked](./configuration.md#locks), throws.

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
| `cocache.caches.[name]` for a cache that doesn't exist, or with a key that doesn't apply to the cache's type (unless `cocache.caches-validation: warn`) | Almost always a typo; see [Configuration Reference](./configuration.md#validation) |
| `cocache.caches.[name]` that sets a field a library locked, without listing it in `unlock`; a customizer that changes a locked field or the data source | The library depends on that value ([locks](./configuration.md#locks)) |
| A duration property without a unit, such as `ttl: 60` | Write `60s`, `5m` or `PT2M` ([durations](./configuration.md#durations)) |

A `Store` or `VersionedStore` interface for which a matching `CacheLoader` or `AsyncCacheLoader` bean exists starts, but logs a warning: the data may belong in a `Cache`. The `toString()` check on key types runs the first time a key of each type is encoded (by any read or write), not at startup.
