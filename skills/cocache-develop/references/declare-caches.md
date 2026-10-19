# Declaring Caches

Use this reference to add or change a cache declaration: its shape, name, storage key, key encoding, loader, codec, per-entry lifetime, key filter and customizers. Confirm attribute names and defaults in the resolved CoCache version.

## Contents

- [Choose the shape](#choose-the-shape)
- [Interface caches](#interface-caches)
- [Spec beans](#spec-beans)
- [Names and storage keys](#names-and-storage-keys)
- [Key encoding](#key-encoding)
- [Loaders](#loaders)
- [Codecs](#codecs)
- [Per-entry lifetime and key filters](#per-entry-lifetime-and-key-filters)
- [Customizers](#customizers)
- [Startup validation](#startup-validation)

## Choose the shape

| Need | Shape |
|---|---|
| Policy basics and L2 size, loader and codec found by type | `@CoCache` interface listed in `@EnableCoCache(caches = [...])` |
| `ttlOf`, `KeyEncoder`, `KeyFilter`, an explicit codec, or no annotations | `CacheSpec` bean (`cacheSpec("name") { ... }` in Kotlin, `CacheSpec.builder(...)` in Java) |
| Something an annotation cannot express on an existing interface cache | `CacheSpecCustomizer` bean |
| A set whose only copy is Redis | `SetCache` (see `set-cache.md`) |
| Two caches read together | `JoinCache` view (see `join-cache.md`) |

Both shapes build the same engine. Start with an interface; switch only for a concrete need.

## Interface caches

```kotlin
@CoCache(ttl = 600, localMaxAge = 60)          // durations in seconds
@CaffeineCache(maximumSize = 100_000)          // optional; L2 is always bounded
interface UserCache : Cache<String, User> {
    fun nameOf(id: String): String? = get(id)?.name   // default methods run as written
}

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App
```

- The interface extends `Cache<K, V>` (or `SetCache<K, E>`) with concrete type arguments. Annotations are read from the interface itself, not from meta-annotations or super-interfaces.
- Every policy attribute defaults to `CoCache.UNSET`, meaning "use the next layer". `CoCache.FOREVER` is valid only for `ttl` and `@CaffeineCache.expireAfterAccess`; zero only for `ttlJitter` and `refreshWindow`.
- An abstract method must match a `Cache` method by name and parameters, otherwise startup fails. Add helpers as default methods. Exceptions reach callers unwrapped.
- Bean name and cache name: `@CoCache(name)`, default the interface's simple name. Inject the interface type.

## Spec beans

```kotlin
@Bean
fun productSpec(mapper: ObjectMapper, repo: ProductRepository): CacheSpec<String, Product> =
    cacheSpec("product") {
        codec = jacksonCodec<Product>(mapper)          // required: no fallback in code
        loader = CacheLoader { id -> repo.findById(id) }
        policy { ttl = Duration.ofMinutes(10) }
    }

class ProductService(@Qualifier("product") private val products: Cache<String, Product>)
```

- The factory method's return type declares the generics (`CacheSpec<String, Product>`); a raw type fails startup.
- Cache bean name: the spec bean name minus `Spec` (`productSpec` gives `product`), otherwise the name plus `Cache`. The cache name is the argument of `cacheSpec(...)`.
- `ObjectMapper` is Jackson 3 (`tools.jackson.databind`). Kotlin `policy { }` edits the policy; Java uses `updatePolicy(...)`; `policy(CachePolicy)` replaces it.

## Names and storage keys

Every Redis key is `{keyPrefix}{namespace}:{encodedKey}`, for example `cocache:UserCache:42`. Invalidations travel on `cocache:inv:{namespace}` (no prefix).

- `keyPrefix` defaults to `cocache:`; `namespace` to the cache name. Both must be unique per application; duplicates fail startup.
- `@CoCache(keyPrefix)` is literal text: placeholders are not resolved, and in Kotlin `$` starts a template. Use the `cocache.defaults.key-prefix` property (placeholders allowed) to separate applications on one Redis.
- Changing a prefix, namespace or default-namespace cache name on a running cache is a cold start and is not rolling-safe (see `production.md`).

## Key encoding

- Default: `key.toString()`. The first encode of each key type checks that `toString()` is overridden and throws `IllegalArgumentException` otherwise. Use a data class, record, or an override.
- Interface: `keyExpression = "#{tenantId}:#{id}"`, a SpEL template with the key as root. It runs on every read, including L2 hits; prefer a key type with a suitable `toString()` on hot paths. A template without `#{...}` is rejected.
- Spec: `keyEncoder = KeyEncoder { "${it.tenantId}:${it.id}" }`.
- The encoding must be injective, deterministic and identical on every instance and version. The encoded key is what the actuator endpoint and `ExternalInvalidator` take.

## Loaders

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
- Interface resolution: `@CoCache(loader = "beanName")`, else the single `CacheLoader<K, V>` bean, else none. Several candidates fail startup. With no loader, misses return `null` and nothing negative is written to Redis.
- `cache.get(key) { k -> ... }` loads a miss with a per-call loader under the same protection; `@Cacheable(sync = true)` uses it.

## Codecs

| Declaration | Codec |
|---|---|
| Interface with `@CoCache(codec = "name")` | That bean |
| Interface, one `Codec<V>` bean | That bean |
| Interface, several `Codec<V>` beans | Startup fails |
| Interface, none | `JacksonCodec` for `V` from the application's Jackson 3 `ObjectMapper` |
| Spec | What you set: `jacksonCodec<V>(mapper)`, `JacksonCodec.of(mapper, V::class.java)`, `StringCodec`, or your own |

A custom `Codec<V>` is `encode(value): ByteArray` and `decode(bytes): V`. It must be thread-safe and stateless, throw on bytes it cannot read (never return a default object), and never sees `null`. An undecodable payload is treated as a miss, reclaimed and reloaded. The byte format is part of the cache's wire format: for an incompatible value change use a new namespace or prefix, or a `decode` that accepts both forms during rollout. Keep payloads small; one connection carries every command.

## Per-entry lifetime and key filters

- `ttlOf = TtlFunction { key, value -> duration or null }` (spec or customizer): a non-null result replaces `ttl`, is not jittered and is clamped to 1 ms .. 100 years; `null` falls back to the policy. Negative entries always use `absentTtl`.
- `keyFilter = KeyFilter { storageKey -> notExist }` runs after an L2 miss and receives the full storage key. Return `true` only for keys that certainly do not exist (a Bloom filter must contain every existing key); it skips Redis and the loader and caches nothing.

## Customizers

```kotlin
@Bean
fun userCacheCustomizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
    override fun supports(cacheName: String) = cacheName == "UserCache"   // default: every cache
    override fun customize(spec: CacheSpec.Builder<*, *>) {
        spec.policy { absentTtl = Duration.ofSeconds(30) }
    }
}
```

Customizers run in Spring order after the declaration and before `cocache.*` properties. Override `customize(SetCacheSpec.Builder)` to reach set caches. There are no bean-name conventions in 6.x.

## Startup validation

CoCache fails startup rather than run a cache that cannot behave as declared, and each message names the cache: missing concrete generics, an unmatched abstract method, several matching loaders or codecs, a loader on a `SetCache`, `FOREVER` or zero or negative values where not allowed, `refreshWindow` not below `ttl - ttlJitter` and `localMaxAge`, duplicate cache name, namespace or bean name, and `cocache.caches.[name]` for an undeclared cache. The `toString()` check on key types runs at the first read of each key type, not at startup.
