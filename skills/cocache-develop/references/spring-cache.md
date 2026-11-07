# Spring Cache over CoCache

Use this reference when application code uses `@Cacheable`, `@CachePut`, `@CacheEvict` or a Spring `CacheManager` in front of CoCache caches. Confirm bean names and properties in the resolved CoCache version. Docs: https://cocache.ahoo.me/guide/spring-cache.

## Wiring

The starter registers `CoCacheSpringCacheManager` (bean `cocacheCacheManager`; when `spring-tx` is present it is wrapped in CoCache's `TransactionAwareSpringCacheManager`) unless the application defines its own `CacheManager`. It exposes caches already declared with CoCache under the same names and never creates caches from unknown names.

Only a `Cache` (a derived copy) belongs behind Spring Cache: the annotated method is its source. Never name a `Store`, `VersionedStore` or `SetStore` in a cache annotation. A `Cache` whose only source is the annotated methods is externally sourced; without a matching loader bean and without `source = EXTERNAL`, a `Cache` interface fails startup.

```kotlin
@CoCache(ttl = 600, source = CoCache.Source.EXTERNAL)
interface UserCache : Cache<String, User>          // externally sourced: the method loads

@EnableCaching
@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App

@Cacheable(cacheNames = ["UserCache"], key = "#id", sync = true)
fun getUser(id: String): User? = repo.findById(id)

@Transactional
@CacheEvict(cacheNames = ["UserCache"], key = "#user.id")
fun save(user: User): User = repo.save(user)
```

Spring's key must have the cache's key type and the method must return the cache's value type.

## Always `sync = true`

With `sync = true`, Spring calls `get(key, valueLoader)`, which runs the method through CoCache's protected load: one call per instance, other instances wait on the Redis lease up to `lease-wait`, and an invalidation during the call is never overwritten. Without it Spring does read, miss, call, unconditional `put`: a row updated and invalidated during the call is written back stale until `ttl`, and concurrent misses all call the method.

The starter checks at startup (with `@EnableCaching`, when a bean named `cocacheCacheManager` exists) and logs a `WARN` per non-sync `@Cacheable` on a CoCache cache. `cocache.spring-cache.require-sync: true` fails startup instead; recommend it for new code. The check skips operations naming another `cacheManager` or a custom `cacheResolver`.

## Operation mapping

| Spring | CoCache |
|---|---|
| `get(key)` | A value is a hit; absent and negative entries are a miss |
| `get(key, valueLoader)` (`sync = true`) | `cache.get(key, loader)`; loader exceptions wrapped in `ValueRetrievalException` |
| `put` (`@CachePut`) | `cache.put`; a `null` value becomes `invalidate` |
| `evict` (`@CacheEvict`) | `cache.invalidate` on every instance |
| `clear()` (`allEntries = true`) | Clears this instance's L2 only; Redis is never cleared wholesale. Logs a `WARN` the first time on each cache. Evict keys instead |
| `retrieve` (async, reactive) | The cache's async view, so no thread waits and an L2 hit returns a completed future; with `sync = true` the same protected load, and the method body runs on the runtime's executor, `cocache.executor` (it may block before it returns the future) |

- A `sync = true` method returning `null` produces a negative entry for `absent-ttl`; invalidate the key when the row is created, or shorten `absent-ttl`.
- Prefer `@CacheEvict` over `@CachePut` after writes.
- Redis failures, for an externally sourced cache (the usual Spring Cache case): `sync = true` still degrades to the method (`read-failure: degrade`, the default); a non-sync `@Cacheable` read is `Lookup.Unknown`, so `get(key)` throws `CacheReadException` to Spring's `CacheErrorHandler`, and on `CompletableFuture`, `Mono` and `Flux` methods `retrieve(key)` fails its future with it, which Spring also hands to the handler (or, with `unknownRead = UnknownReadMode.ABSENT` on the spec, in a customizer, as `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)` or as `cocache.caches.[name].unknown-read: absent`, misses without caching so the method runs); a failed `@CacheEvict` is queued for background retry, and a `@CachePut` whose outcome is unknown queues a compensating invalidation without throwing, because `invalidate-failure` defaults to `retry` for every `Cache`. `invalidate-failure: throw` makes both throw `CacheWriteException` instead.
- `cocache.spring-cache.degrade-on-error: true` gives the cache interceptor a `DegradingCacheErrorHandler`: read and non-null write failures on CoCache caches are logged, not rethrown, so a non-sync `@Cacheable` runs its method while Redis is down. Invalidation and clear failures, and other caches, still go to the previous handler (its delegate). Without Boot, return `DegradingCacheErrorHandler(handler)` from `CachingConfigurer.errorHandler()`.
- Spring's `put` / `evict` are blocking: on reactive methods, `@CachePut`, `@CacheEvict` and non-sync `@Cacheable` hold the callback thread for one Redis round trip. Call `cache.async().invalidate(key)` where a write must not block.

## Transactions

With `TransactionAwareSpringCacheManager`, inside a transaction:

- `@CacheEvict` is registered with the transaction and invalidated after the commit and after a rollback (also on a rollback to a savepoint). `@CacheEvict(beforeInvocation = true)` invalidates at once and is registered as well.
- `@CachePut` is written after the commit and dropped on rollback; a rollback to a savepoint drops the writes registered after it. An invalidation of the same key registered later (any entry point, including `TransactionalInvalidator` through any object for that cache: keys match by namespace and encoded key) replaces it: invalidations run first at the commit, then the remaining writes.
- `allEntries = true` clears this instance's L2 after the commit; with `beforeInvocation = true`, at once.
- A failure after the commit goes to the cache interceptor's `CacheErrorHandler`; if the handler rethrows (Spring's default), it is logged at `ERROR`, never thrown to the caller.
- This assumes the transaction advice is outermost. Both default to `LOWEST_PRECEDENCE`, and the starter warns at startup when the orders are equal: recommend `@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)`. With the cache advice outermost, `@CacheEvict` runs after the commit unregistered, `beforeInvocation = true` invalidates only before the transaction begins, and a `sync = true` load runs outside any watched transaction, so a value returned from a rolled-back transaction stays cached.
- Loads inside a read-write transaction are watched (`TransactionalFillObserver`, bean `cocacheFillObserver`): a synchronous read with a source records every key it writes to Redis or L2 and invalidates it on rollback, unknown commit outcome or rollback to a savepoint (`invalidate-fills-on-rollback`, default `true`); a key registered for invalidation in the transaction loads privately there, with no lease, no Redis or L2 write and no sharing (`fill-in-transaction`: `registered-keys` default, `private`, `allow`; counted in `cocache.fills.gated`). Both are per cache, under `cocache.defaults.cache`, `cocache.overrides.cache` (stricter value) and `cocache.caches.[name]`. Read-only transactions, async and suspending calls and reactive transactions are not watched. Without Boot, pass `TransactionalFillObserver()` to `CacheRuntime.Builder.fillObserver`.
- Any cache operation naming a CoCache cache that is not a `Cache` (`Store`, `VersionedStore`, `SetStore`, `VersionedCache`) fails startup.

## Several cache managers

The starter's manager steps aside when any other `CacheManager` exists. To use CoCache next to another manager, declare it under the name `cocacheCacheManager` (keeps the sync check) and point annotations at it:

```kotlin
@Bean
fun cocacheCacheManager(runtime: CacheRuntime, interceptor: ObjectProvider<CacheInterceptor>): CacheManager =
    TransactionAwareSpringCacheManager(CoCacheSpringCacheManager(runtime)) {
        interceptor.getIfUnique()?.errorHandler ?: SimpleCacheErrorHandler()
    }

@Primary
@Bean
fun localCacheManager(): CacheManager = CaffeineCacheManager()

@Cacheable(cacheNames = ["UserCache"], key = "#id", sync = true, cacheManager = "cocacheCacheManager")
fun getUser(id: String): User? = repo.findById(id)
```

The sync check matches by cache name, so give CoCache caches names other managers do not use. To switch off only CoCache's Spring Cache wiring (`cocacheCacheManager`, `cocacheSpringCacheSyncCheck`, `cocacheDegradingCacheErrorHandler`), exclude `me.ahoo.cocache.spring.boot.CoCacheSpringCacheAutoConfiguration`; the rest of CoCache keeps working. Without Spring Boot, register `CoCacheSpringCacheManager(runtime)` (optionally behind the transaction decorator, as above) and `TransactionalInvalidator()` yourself.
