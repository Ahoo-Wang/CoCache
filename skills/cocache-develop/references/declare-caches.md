# Declaring Caches

Use this reference to add or change a cache declaration: its type, shape, name, storage key, key encoding, source, codec, per-entry lifetime, key filter and customizers. Confirm attribute names and defaults in the resolved CoCache version.

## Contents

- [Choose the type](#choose-the-type)
- [Choose the shape](#choose-the-shape)
- [Interface caches](#interface-caches)
- [Spec beans](#spec-beans)
- [Names and storage keys](#names-and-storage-keys)
- [Key encoding](#key-encoding)
- [Sources](#sources)
- [Authoritative types](#authoritative-types)
- [Reads: present, absent, unknown](#reads-present-absent-unknown)
- [Codecs](#codecs)
- [Per-entry lifetime and key filters](#per-entry-lifetime-and-key-filters)
- [Customizers](#customizers)
- [Startup validation](#startup-validation)

## Choose the type

Ask one question: **if Redis loses this key, can this process rebuild it on demand?** Yes: Redis holds a derived copy (`Cache`, `VersionedCache`). No: Redis holds the only copy (`Store`, `VersionedStore`, `SetStore`). Docs: https://cocache.ahoo.me/guide/caches#types, then one guide per type (`guide/cache`, `guide/versioned-cache`, `guide/store`, `guide/versioned-store`, `guide/set-store`).

| Data | Type |
|---|---|
| Rows a loader can fetch again (database, remote API) | `Cache` with a `loader` / `asyncLoader` |
| Values supplied by callers: Spring Cache methods, `put`, per-call loaders | `Cache` with `externallySourced()` (`@CoCache(source = CoCache.Source.EXTERNAL)`) |
| A read model the loader rebuilds together with its version (event-sourced, CDC) | `VersionedCache` with a versioned loader |
| A policy snapshot, revocation list, session, one-time code, idempotency key, data only another service or an offline job can rebuild | `Store` |
| Only-copy state whose writers must survive late, replayed or duplicated writes | `VersionedStore` |
| A set whose only copy is Redis | `SetStore` (see `set-store.md`) |
| Two caches read together | `JoinView` / `JoinCache` (see `join-cache.md`) |

Each type declares only its operations (`Cache`: `put`, `invalidate`; `VersionedCache`: `putIfNewer`, `invalidateIfNewer`, `evict`, `purge`; `Store`: `put`, `delete`, `getIfChanged`, `putIfMatch`, `deleteIfMatch`; `VersionedStore`: `putIfNewer`, `deleteIfNewer`, `purge`; `SetStore`: `add`, `remove`, `put`, `clear`). All read through `get`, `getAll`, `lookup`, `lookupAll`, and have `async()` and `suspending()` views. Write-behind fits no type.

Misclassification is a defect: the only copy declared as an externally sourced `Cache` expires after the default 1 h `ttl`, its failed `invalidate` is retried later and can delete a newer value, and the eviction check skips it; a rebuildable copy declared as a `Store` reads "absent" forever after Redis loses it, and startup only warns when a matching `CacheLoader` bean exists. Every declaration of one namespace must use the same type; the start log names it (`Cache[name] - created as Store on namespace[...]`), and a derived cache that reads a `Store` value counts `cocache.type.mismatch`.

## Choose the shape

| Need | Shape |
|---|---|
| Policy basics and L2 size, loader and codec found by type | `@CoCache` interface listed in `@EnableCoCache(caches = [...])` |
| `ttlOf`, `KeyEncoder`, `KeyFilter`, `tombstoneTtl`, an explicit codec, or no annotations | Spec bean (`cacheSpec`, `versionedCacheSpec`, `storeSpec`, `versionedStoreSpec`, `setStoreSpec` in Kotlin; `XSpec.builder(name)` in Java) |
| Something an annotation cannot express on an existing interface cache | `CacheSpecCustomizer` bean |
| Caches a library or plugin creates at runtime | The `CoCacheFactory` bean (below) |

Both shapes build the same engine. Start with an interface; switch only for a concrete need.

## Interface caches

```kotlin
@CoCache(ttl = 600, localMaxAge = 60)          // durations in seconds
@CaffeineCache(maximumSize = 100_000)          // optional; L2 is always bounded
interface UserCache : Cache<String, User> {
    fun nameOf(id: String): String? = get(id)?.name   // default methods run as written
}

@CoCache
interface PolicySnapshots : Store<String, PolicySnapshot>

@EnableCoCache(caches = [UserCache::class, PolicySnapshots::class])
@SpringBootApplication
class App
```

- The interface extends exactly one of `Cache<K, V>`, `VersionedCache<K, V>`, `Store<K, V>`, `VersionedStore<K, V>`, `SetStore<K, E>`, or one of their `Async*` / `Suspending*` views, with concrete type arguments; that type decides what is created (a view declares the same kind as its synchronous type, so a `SuspendingStore` interface takes a `storeSpec`). For coroutine code, prefer `interface UserCache : SuspendingCache<String, User>` over calling `suspending()`: the bean is the suspending view, and calls go straight to it. Annotations are read from the interface itself, not from meta-annotations or super-interfaces.
- Every policy attribute defaults to `CoCache.UNSET`, meaning "not declared": `cocache.defaults` or the type default decides. A set attribute is declared, and `cocache.defaults` does not change it. `CoCache.FOREVER` is valid only for `ttl` and `@CaffeineCache.expireAfterAccess`; zero only for `ttlJitter` and `refreshWindow`.
- `source = CoCache.Source.EXTERNAL` is for `Cache` only and cannot be combined with `loader`.
- `unknownRead = CoCache.UnknownRead.ABSENT` is for reads without a source: `Store`, `VersionedStore`, and a `Cache` with `source = EXTERNAL`; on any other interface it fails startup.
- An abstract method must match a method of the extended type by name and parameters, otherwise startup fails. Add helpers as default methods. Exceptions reach callers unwrapped.
- Bean name and cache name: `@CoCache(name)`, default the interface's simple name. Inject the interface type.
- Do not implement the API types in your own classes except to delegate to a cache CoCache created.

## Spec beans

```kotlin
@Bean
fun productSpec(mapper: ObjectMapper, repo: ProductRepository): CacheSpec<String, Product> =
    cacheSpec("product") {
        codec = jacksonCodec<Product>(mapper)          // required: no fallback in code
        loader = CacheLoader { id -> repo.findById(id) }
        policy { ttl = Duration.ofMinutes(10) }
    }

@Bean
fun snapshotSpec(mapper: ObjectMapper): StoreSpec<String, PolicySnapshot> =
    storeSpec("policy-snapshot") { codec = jacksonCodec<PolicySnapshot>(mapper) }

class ProductService(@Qualifier("product") private val products: Cache<String, Product>)
```

- The factory method's return type declares the generics (`CacheSpec<String, Product>`, `StoreSpec<...>`, ...; a `FactoryBean<StoreSpec<...>>` works too); a raw type fails startup. The bean has the matching type (`StoreSpec` gives a `Store`).
- Cache bean name: the spec bean name minus `Spec` (`productSpec` gives `product`), otherwise the name plus `Cache`. The cache name is the spec's name argument.
- A `CacheSpec` sets exactly one of `loader`, `asyncLoader`, `externallySourced()`. A `VersionedCacheSpec` sets `versionedLoader` or `versionedAsyncLoader`. Authoritative specs have no source. A `SetStoreSpec` sets `elementCodec`.
- `ObjectMapper` is Jackson 3 (`tools.jackson.databind`). Kotlin `policy { }` edits the type's default policy and records the assigned fields as declared (a nested `lease`/`load`/`local` records only the fields it changes), leaving the rest to `cocache.defaults`; Java uses `updatePolicy(...)`. `policy(CachePolicy)` / `policy = …` replaces it and counts as declaring every field. Each spec starts from its own `DefaultPolicies` constant (`CACHE`, `VERSIONED_CACHE`, `STORE`, `VERSIONED_STORE`, `SET_STORE`); building a `Store` policy from a derived default gives it a 1 h `ttl`. Write sentinels with `ttl = forever()`, `refreshWindow = off()`, `maxConcurrent = unlimited()` (a nested `load = LoadPolicy(maxConcurrent = unlimited())` equals the default, so it isn't recorded); an L2 size equal to the current one with `localMaximumSize = 10_000`.
- A library, or any code that creates caches outside the starter's own declarations, uses the `CoCacheFactory` bean: `create(spec)` returns the type matching the spec, `create(type, spec)` a proxy for an interface extending that type, and `create(spec) { lock(…) }` adds locks (see `configuration.md`). Its caches get the customizers and `cocache.*` properties. A cache created directly with `runtime.create` is *unlayered*: configuration doesn't apply to it, the start log and the endpoint say so, and a `cocache.caches.[name]` entry for it only logs a warning.
- Without Spring, `runtime.create(spec)` (or `CoCacheFactory(runtime)` for interfaces) returns the matching type. Creating a type whose L1 capability the backend lacks fails with `UnsupportedOperationException`: `Store` needs conditional writes, the versioned types versioned writes, `SetStore` sets. Redis and the in-memory backend provide all three.

## Names and storage keys

Every Redis key is `{keyPrefix}{namespace}:{encodedKey}`, for example `cocache:UserCache:42`. Invalidations travel on `{keyPrefix}inv:{namespace}`, for example `cocache:inv:UserCache`.

- `keyPrefix` defaults to `cocache:`; `namespace` to the cache name and matches `[A-Za-z0-9._-]+` (no `:`). Cache names and prefix-and-namespace pairs must be unique per application; duplicates fail startup.
- `@CoCache(keyPrefix)` is literal text: placeholders are not resolved, and in Kotlin `$` starts a template. Use the `cocache.key-prefix` property (placeholders allowed) to separate applications on one Redis; it wins over the declared prefix unless a library locked `KEY_PREFIX`.
- Changing a prefix, namespace or default-namespace cache name on a running derived cache is a cold start and is not rolling-safe (see `production.md`); on an authoritative type it loses the data.

## Key encoding

- Default: `key.toString()`. The first encode of each key type checks that `toString()` is overridden and throws `IllegalArgumentException` otherwise. Use a data class, record, or an override.
- Interface: `keyExpression = "#{tenantId}:#{id}"`, a SpEL template with the key as root. It runs on every read, including L2 hits; prefer a key type with a suitable `toString()` on hot paths. A template without `#{...}` is rejected.
- Spec: `keyEncoder = KeyEncoder { "${it.tenantId}:${it.id}" }`.
- The encoding must be injective, deterministic and identical on every instance and version. The encoded key is what the actuator endpoint and `ExternalInvalidator` take.
- The encoded key must be well-formed UTF-16: one with an unpaired surrogate fails with `IllegalArgumentException` before it reaches Redis (keys and frames are UTF-8). Emoji, CJK and the empty string are fine.

## Sources

```kotlin
class UserLoader(private val repo: UserRepository) : CacheLoader<String, User> {
    override fun load(key: String): User? = repo.findById(key)            // null = does not exist
    override fun loadAll(keys: Collection<String>): Map<String, User> =    // override when getAll is used
        repo.findAllById(keys).associateBy { it.id }                        // missing keys = does not exist
}
```

- `null` is cached as a negative entry for `absentTtl`. Throw when the lookup failed; nothing is cached and every coalesced caller sees the exception.
- The loader runs on the calling thread, so the transaction, security context and MDC are visible. Read the primary, not a lagging replica.
- Reading the same key from inside its own loader on the same thread fails fast with `IllegalStateException`.
- Interface resolution: `@CoCache(loader = "beanName")`, else the single `CacheLoader<K, V>` bean, else the single `AsyncCacheLoader<K, V>` bean. Several candidates, or one of each kind, fail startup; a bean implementing both is used as a `CacheLoader`. No match fails startup too: declare `source = EXTERNAL` when callers supply the values, or extend `Store` when nothing can rebuild them.
- `cache.get(key) { k -> ... }` loads a miss with a per-call loader under the same protection; `@Cacheable(sync = true)` uses it.
- `asyncLoader = AsyncCacheLoader { id -> repo.findById(id).toFuture() }` for non-blocking sources. `cache.async()` returns the async view on the same cache (same L2, fences and leases; `AsyncCache`, `AsyncStore`, ... per type). Futures fail with the original exception and never complete on the Redis I/O thread; `loadAsync` must not block and sees no caller thread-locals; a synchronous loader under the async view runs on the runtime's executor (`cocache.executor`), which must therefore tolerate blocking. Bounded by `load-timeout`.
- Coroutines need no extra module: `cache.suspending()` returns the suspending view, or the interface extends it directly (L2 hits don't suspend either way), and `suspendingLoader { id -> repo.findById(id) }` / `suspendingVersionedLoader { ... }` (`me.ahoo.cocache.core`) set a suspending source, which runs on `Dispatchers.Default` (or the context you pass) and is not cancelled by `load-timeout`. Misses and writes run on CoCache's executor from their first suspension, with the caller's context elements but not its `Job` or dispatcher, and the caller never resumes on a Redis client thread (safe for WebFlux `Unconfined` handlers). A per-call loader (`get(key) { ... }`) runs inside the load: it starts on the caller's thread, continues on CoCache's executor (not the caller's dispatcher), and `load-timeout` cancels it; wrap blocking calls in `withContext(Dispatchers.IO)`. Cancelling a caller never cancels a shared load; re-reading the key being loaded fails fast with `IllegalStateException`.
- **Externally sourced `Cache`**: a miss is *unfilled* (a read without a loader returns `null`, a call with its own loader still loads, nothing negative is written to Redis); a failed `invalidate` is retried like any `Cache`.
- **Versioned loader** (`VersionedCache`): returns `Versioned(value, version)`; express a deletion as `Versioned(null, version)` (a tombstone). A plain `null` over versioned content is refused, counted in `cocache.fill.unversioned.absent`, and every miss reloads.

## Authoritative types

- No loader, no load lease; a key Redis does not have is absent. Defaults (`DefaultPolicies.STORE`, same values in `VERSIONED_STORE` / `SET_STORE`): `ttl` forever, `localMaxAge` 30 s, `absentTtl` 10 s; `VersionedStore` `tombstoneTtl` 1 day, and a build whose tombstones would never expire fails. Data whose lifetime is its meaning (session, one-time code) needs an explicit `ttl`.
- Every write carries a writer id, and a `Store` value its SHA-256 digest, verified on read: a tampered or missing digest throws `CacheReadException` and the key is kept.
- `delete`, `deleteIfMatch` and `purge` are synchronous and never retried; they throw `CacheWriteException`. Use `VersionedStore.deleteIfNewer` when deletes must survive reordering.
- Durability is Redis durability: no `allkeys-*` eviction, persistence on (see `production.md`).

## Reads: present, absent, unknown

`lookup(key)` returns `Lookup.Present(value, expiresAt)`, `Lookup.Absent(expiresAt)` or `Lookup.Unknown(error)`; `lookup(key).valueOrThrow()` returns the value or `null` and throws on `Unknown`. Switch exhaustively; `as? Lookup.Present` silently treats `Unknown` as absent.

- `Unknown` is the answer of a read **without a source** when Redis fails: authoritative types, and an externally sourced `Cache` read without a loader. `get` throws `CacheReadException`; `getAll` finishes the other keys, then throws with `unknownKeys` and `partialResult`; `lookupAll` returns a `Lookup` per key. Nothing is cached. Counted in `cocache.reads.unknown` (and the failure in `cocache.remote.failures`).
- Reads **with a source** follow `readFailure` (default `DEGRADE`: load from the source, keep the result in L2 for `degradedLocalMaxAge`); `readFailure = THROW` makes them `Unknown` too.
- `unknownRead = UnknownReadMode.ABSENT` (on a `StoreSpec`, `VersionedStoreSpec`, or an externally sourced `CacheSpec`; not `SetStoreSpec`) returns `null` instead and caches nothing. Use it only where "absent" is a safe answer, such as a revocation list. Set it in the spec, `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)`, a customizer, or `cocache.caches.[name].unknown-read: absent` (no global layer sets it); a library that depends on it should `lock(PolicyField.UNKNOWN_READ)`.

## Codecs

| Declaration | Codec |
|---|---|
| Interface with `@CoCache(codec = "name")` | That bean |
| Interface, one `Codec<V>` bean | That bean |
| Interface, several `Codec<V>` beans | Startup fails |
| Interface, none | `JacksonCodec` for `V` from the application's Jackson 3 `ObjectMapper` |
| Spec | What you set: `jacksonCodec<V>(mapper)`, `JacksonCodec.of(mapper, V::class.java)`, `StringCodec`, or your own |

Matching by type (loaders, codecs, `JoinKeyExtractor`) sees what `getBean(name)` sees: parent-context beans count (a child bean of the same name hides the parent's; one match in each context is ambiguous), and a scoped proxy is injected as the proxy. Type arguments inherited through a generic intermediate interface (`UserStore : BaseStore<User>`) are matched and decoded as the actual type.

A custom `Codec<V>` is `encode(value): ByteArray` and `decode(bytes): V`. It must be thread-safe and stateless, throw on bytes it cannot read (never return a default object), and never sees `null`. An undecodable payload read with a source is reclaimed and reloaded; read without a source it throws `CacheReadException` and the key is kept. The byte format is part of the cache's wire format: for an incompatible value change use a new namespace or prefix, or a `decode` that accepts both forms during rollout. Keep payloads small; one connection carries every command.

## Per-entry lifetime and key filters

- `ttlOf = TtlFunction { key, value -> duration or null }` (spec or customizer; `CacheSpec`, `VersionedCacheSpec`, `StoreSpec`, `VersionedStoreSpec`): a non-null result replaces `ttl`, is not jittered and is clamped to 1 ms .. 100 years; `null` falls back to the policy. `cocache.overrides.cache.ttl` caps a longer result (counted in `cocache.ttl.clamped`). Negative entries always use `absentTtl`, tombstones `tombstoneTtl`.
- `keyFilter = KeyFilter { storageKey -> notExist }` runs after an L2 miss and receives the full storage key. Return `true` only for keys that certainly do not exist (a Bloom filter must contain every existing key); it skips Redis and the loader and caches nothing.

## Customizers

```kotlin
@Bean
fun cacheCustomizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
    override fun supports(cacheName: String) = cacheName == "UserCache" || cacheName == "Revocations"
    override fun customize(spec: CacheSpec.Builder<*, *>) {
        spec.policy { absentTtl = Duration.ofSeconds(30) }
    }
    override fun customize(spec: StoreSpec.Builder<*, *>) {
        spec.unknownRead = UnknownReadMode.ABSENT
    }
}
```

There is one overload per spec builder (`CacheSpec`, `VersionedCacheSpec`, `StoreSpec`, `VersionedStoreSpec`, `SetStoreSpec`), each a no-op by default. Customizers run in Spring order after `cocache.defaults` and the declaration, before `cocache.overrides` and `cocache.caches.[name]`; changing the data source, or a field or layout a library locked, throws. No bean-name convention configures a cache: loaders and codecs are found by type or by the bean name given in `@CoCache`.

## Startup validation

CoCache fails startup rather than run a cache that cannot behave as declared, and each message names the cache: an interface extending none or several of the fifteen declarable types (the five types and their async and suspending views), missing concrete generics, an unmatched abstract method, a `Cache` without a matching loader and without `source = EXTERNAL`, a `VersionedCache` without a versioned source, `loader` or `source = EXTERNAL` on an authoritative type, `unknownRead` on a `CacheSpec` with a source (or `@CoCache(unknownRead = ABSENT)` on an interface whose reads have a source, or on a `SetStore`), several matching loaders or codecs, a backend lacking the type's capability, a `VersionedStore` whose tombstones never expire, `FOREVER` or zero or negative values where not allowed, `refreshWindow` not below `ttl - ttlJitter` and `localMaxAge`, an invalid namespace, duplicate cache name, namespace or bean name, `cocache.caches.[name]` for an undeclared cache or with a key that does not apply to the type (both only warn with `cocache.caches-validation: warn`), a `cocache.caches.[name]` key for a locked field not listed in `unlock`, a customizer that changes a locked field or the data source, and a duration property without a unit. An authoritative interface with a matching `CacheLoader` bean only logs a warning. The `toString()` check on key types runs at the first read of each key type, not at startup.
