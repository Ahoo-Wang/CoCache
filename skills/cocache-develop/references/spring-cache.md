# Spring Cache over CoCache

Use this reference when application code uses `@Cacheable`, `@CachePut`, `@CacheEvict` or a Spring `CacheManager` in front of CoCache caches. Confirm bean names and properties in the resolved CoCache version.

## Wiring

The starter registers `CoCacheSpringCacheManager` (bean `cocacheCacheManager`; when `spring-tx` is present it is wrapped in CoCache's `TransactionAwareSpringCacheManager`) unless the application defines its own `CacheManager`. It exposes caches already declared with CoCache under the same names and never creates caches from unknown names.

```kotlin
@CoCache(ttl = 600)
interface UserCache : Cache<String, User>          // usually no CacheLoader: the method loads

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
| `retrieve` (async, reactive) | The cache's async view, so no thread waits and an L2 hit returns a completed future; with `sync = true` the same protected load, and the method body runs on `cocache.blocking-executor` (it may block before it returns the future) |

- A `sync = true` method returning `null` produces a negative entry for `absent-ttl`; invalidate the key when the row is created, or shorten `absent-ttl`.
- Prefer `@CacheEvict` over `@CachePut` after writes.
- Redis failures, for a cache without its own loader (the usual Spring Cache case): `sync = true` still degrades to the method; a non-sync `@Cacheable` read throws `CacheReadException` to Spring's `CacheErrorHandler` (or, with `read-failure: degrade` on the cache, misses without caching so the method runs); a failed `@CacheEvict` throws `CacheWriteException` because `invalidate-failure` defaults to `throw` without a loader. The method is the source, so a delete is safe: set `invalidate-failure: retry` on such caches to keep queued retries.
- `cocache.spring-cache.degrade-on-error: true` gives the cache interceptor a `DegradingCacheErrorHandler`: read and non-null write failures on CoCache caches are logged, not rethrown, so a non-sync `@Cacheable` runs its method while Redis is down. Invalidation and clear failures, and other caches, still go to the previous handler (its delegate). Without Boot, return `DegradingCacheErrorHandler(handler)` from `CachingConfigurer.errorHandler()`.
- Spring's `put` / `evict` are blocking: on reactive methods, `@CachePut`, `@CacheEvict` and non-sync `@Cacheable` hold the callback thread for one Redis round trip. Call `cache.async().invalidate(key)` where a write must not block.

## Transactions

With `TransactionAwareSpringCacheManager`, inside a transaction:

- `@CacheEvict` is registered with the transaction and invalidated after the commit and after a rollback (also on a rollback to a savepoint). `@CacheEvict(beforeInvocation = true)` invalidates at once and is registered as well.
- `@CachePut` is written after the commit and dropped on rollback; a rollback to a savepoint drops the writes registered after it. An invalidation of the same key registered later (any entry point, including `TransactionalInvalidator` with the same cache object) replaces it: invalidations run first at the commit, then the remaining writes.
- `allEntries = true` clears this instance's L2 after the commit; with `beforeInvocation = true`, at once.
- A failure after the commit goes to the cache interceptor's `CacheErrorHandler`; if the handler rethrows (Spring's default), it is logged at `ERROR`, never thrown to the caller.
- This assumes the transaction advice is outermost. Both default to `LOWEST_PRECEDENCE`, and the starter warns at startup when the orders are equal: recommend `@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)`. With the cache advice outermost, `@CacheEvict` runs after the commit unregistered and `beforeInvocation = true` invalidates only before the transaction begins.

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

The sync check matches by cache name, so give CoCache caches names other managers do not use. Without Spring Boot, register `CoCacheSpringCacheManager(runtime)` (optionally behind the transaction decorator, as above) and `TransactionalInvalidator()` yourself.
