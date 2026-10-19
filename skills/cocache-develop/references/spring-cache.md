# Spring Cache over CoCache

Use this reference when application code uses `@Cacheable`, `@CachePut`, `@CacheEvict` or a Spring `CacheManager` in front of CoCache caches. Confirm bean names and properties in the resolved CoCache version.

## Wiring

The starter registers `CoCacheSpringCacheManager` (bean `cocacheCacheManager`, wrapped in `TransactionAwareCacheManagerProxy` when `spring-tx` is present) unless the application defines its own `CacheManager`. It exposes caches already declared with CoCache under the same names and never creates caches from unknown names.

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
| `clear()` (`allEntries = true`) | Clears this instance's L2 only; Redis is never cleared wholesale |
| `retrieve` (async, reactive) | 6.3+: the cache's async view, so no thread waits and an L2 hit returns a completed future; with `sync = true` the same protected load. Before 6.3: a blocking lookup on the `retrieve` executor |

- A `sync = true` method returning `null` produces a negative entry for `absent-ttl`; invalidate the key when the row is created, or shorten `absent-ttl`.
- Prefer `@CacheEvict` over `@CachePut` after writes.
- 6.3 deprecates `cocache.spring-cache.retrieve-executor` and `CoCacheSpringCacheManager(runtime, executor)`; the executor is ignored. Before 6.3 it must not queue tasks behind tasks that wait on them.
- Spring's `put` / `evict` are blocking: on reactive methods, `@CachePut`, `@CacheEvict` and non-sync `@Cacheable` hold the callback thread for one Redis round trip. Call `cache.async().invalidate(key)` where a write must not block.

## Several cache managers

The starter's manager steps aside when any other `CacheManager` exists. To use CoCache next to another manager, declare it under the name `cocacheCacheManager` (keeps the sync check) and point annotations at it:

```kotlin
@Bean
fun cocacheCacheManager(runtime: CacheRuntime): CacheManager =
    TransactionAwareCacheManagerProxy(CoCacheSpringCacheManager(runtime))

@Primary
@Bean
fun localCacheManager(): CacheManager = CaffeineCacheManager()

@Cacheable(cacheNames = ["UserCache"], key = "#id", sync = true, cacheManager = "cocacheCacheManager")
fun getUser(id: String): User? = repo.findById(id)
```

The sync check matches by cache name, so give CoCache caches names other managers do not use. Without Spring Boot, register `CoCacheSpringCacheManager(runtime)` (optionally behind the transaction-aware proxy) and `TransactionalInvalidator()` yourself.
