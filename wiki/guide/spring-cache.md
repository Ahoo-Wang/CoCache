---
title: Spring Cache
description: Use CoCache behind @Cacheable, @CachePut and @CacheEvict — why sync = true matters, the startup sync check, null handling, transactions, and using CoCache alongside other cache managers.
---

# Spring Cache

If your code already uses Spring's caching annotations, CoCache can sit behind them. The starter registers `CoCacheSpringCacheManager`, which exposes every CoCache cache as a Spring `Cache` under the same name.

## Usage {#usage}

```kotlin
@CoCache(ttl = 600)
interface UserCache : Cache<String, User>          // no CacheLoader: the annotated method loads

@EnableCaching
@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App

@Service
class UserService(private val repo: UserRepository) {
    @Cacheable(cacheNames = ["UserCache"], key = "#id", sync = true)
    fun getUser(id: String): User? = repo.findById(id)

    @Transactional
    @CacheEvict(cacheNames = ["UserCache"], key = "#user.id")
    fun save(user: User): User = repo.save(user)
}
```

- The cache must be declared; the manager never creates caches from names it hasn't seen.
- Spring's key must have the cache's key type (`String` here), and the method must return the cache's value type.
- The annotated method acts as the loader, so the cache usually has no `CacheLoader` of its own.

## Always use `sync = true` {#use-sync-true}

With `sync = true`, Spring calls `get(key, valueLoader)`, and CoCache runs your method through the same protected load as its own reads:

- concurrent callers on one instance share one method call,
- callers on other instances wait for that call through the Redis lease (up to `lease-wait`), and
- an invalidation that happens while the method runs is never overwritten by its result.

Without `sync`, Spring does the work itself: read, miss, call the method, `put`. That `put` is unconditional. If the row was updated and invalidated while the method ran, the old result is written back and served until `ttl`. Concurrent misses also all call the method.

### The startup check {#sync-check}

With `@EnableCaching`, the starter inspects every bean's cache operations at startup (including names from `@CacheConfig`). For each `@Cacheable` without `sync = true` that names a CoCache cache, it logs a `WARN` with `Class#method` and the cache names. To fail startup instead:

```yaml
cocache:
  spring-cache:
    require-sync: true
```

The check matches by cache name and skips operations that name no cache (those using only a `cacheResolver`).

## How Spring operations map {#semantics}

| Spring | CoCache |
|--------|---------|
| `get(key)` | A value is a hit. Absent and **negative** entries are a miss. |
| `get(key, valueLoader)` (`sync = true`) | `cache.get(key, loader)`. A loader exception is wrapped in `ValueRetrievalException`. |
| `put(key, value)` (`@CachePut`) | `cache.put(key, value)`. A `null` value becomes `invalidate(key)`. |
| `evict(key)` (`@CacheEvict`) | `cache.invalidate(key)` on every instance |
| `clear()` (`allEntries = true`) | Clears **this instance's L2 only**. Redis is shared and is never cleared wholesale. |
| `retrieve(key)` (async and reactive) | Runs the blocking lookup on a daemon thread pool. |
| `retrieve(key, loader)` (async and reactive with `sync = true`) | Loads through the same lease-protected path as `get(key, loader)`, waiting for the async loader on that pool. |

### Null results {#null}

With `sync = true`, a method that returns `null` produces a negative entry. For `absent-ttl` (1 minute by default), later calls return `null` without running the method. Invalidate the key when the row is created, or shorten `absent-ttl`.

Negative entries read as a miss through `get(key)` on purpose: CoCache stores them whenever a loader finds nothing, and treating them as a cached `null` would stop non-sync `@Cacheable` methods from ever running.

## Prefer evict over put {#evict}

After a write, prefer `@CacheEvict` to `@CachePut`. Evicting lets the next read load exactly what was committed, and every instance drops its copy. See [`invalidate` or `put`?](./freshness.md#invalidate-vs-put).

## Transactions {#transactions}

With `spring-tx` on the classpath, the starter wraps the manager in Spring's `TransactionAwareCacheManagerProxy`: `@CacheEvict` and `@CachePut` inside a transaction run after the commit, and not at all on rollback. Invalidating before the commit would let a concurrent read cache the old row.

For direct calls, use `TransactionalInvalidator` (see [Keeping Data Fresh](./freshness.md#transactions)).

## Alongside other cache managers {#other-managers}

The starter's manager steps aside when your application defines its own `CacheManager`. To use CoCache next to another manager, declare it yourself and point annotations at it:

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

`CoCacheSpringCacheManager(runtime, executor)` also accepts the executor `retrieve` should use. The sync check matches by cache name, so give CoCache caches names that other managers don't use.

## Without Spring Boot {#without-spring-boot}

```kotlin
@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun cacheManager(runtime: CacheRuntime): CacheManager =
        TransactionAwareCacheManagerProxy(CoCacheSpringCacheManager(runtime))

    @Bean
    fun transactionalInvalidator() = TransactionalInvalidator()
}
```

The manager serves the caches already created in that `CacheRuntime`. Drop the proxy if you don't use transactions.
