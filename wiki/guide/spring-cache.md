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

The check matches by cache name. It skips operations that name another `cacheManager` or set a custom `cacheResolver`, since those don't necessarily reach CoCache.

The check runs only when a bean named `cocacheCacheManager` exists: the starter's own manager, or one you declare under that name (see [Alongside other cache managers](#other-managers)). The starter's manager steps aside when you declare any other `CacheManager`, so if you wire `CoCacheSpringCacheManager` yourself under a different bean name, the check doesn't apply and `sync = true` is up to you.

## How Spring operations map {#semantics}

| Spring | CoCache |
|--------|---------|
| `get(key)` | A value is a hit. Absent and **negative** entries are a miss. |
| `get(key, valueLoader)` (`sync = true`) | `cache.get(key, loader)`. A loader exception is wrapped in `ValueRetrievalException`. |
| `put(key, value)` (`@CachePut`) | `cache.put(key, value)`. A `null` value becomes `invalidate(key)`. |
| `evict(key)` (`@CacheEvict`) | `cache.invalidate(key)` on every instance |
| `clear()` (`allEntries = true`) | Clears **this instance's L2 only**. Redis is shared and is never cleared wholesale. |
| `retrieve(key)` (async and reactive) | An async `lookup` on the cache's [async view](./async.md). An L2 hit returns an already-completed future without switching threads, and a negative entry completes with `null` (a miss). |
| `retrieve(key, loader)` (async and reactive with `sync = true`) | An async `get(key, loader)` on the [async view](./async.md): it loads through the same lease-protected path as `get(key, loader)`, and **no thread waits** for the async loader. The method body runs on the runtime's blocking executor (`cocache.blocking-executor`), as it ran on the retrieve executor before 6.3, so a body that blocks before it returns a future, such as `completedFuture(repository.find(id))`, doesn't stall CoCache's async executor. It can't see the caller's thread-locals. A failure completes the future with the original exception. |

### Null results {#null}

With `sync = true`, a method that returns `null` produces a negative entry. For `absent-ttl` (1 minute by default), later calls return `null` without running the method. Invalidate the key when the row is created, or shorten `absent-ttl`.

Negative entries read as a miss through `get(key)` on purpose: CoCache stores them whenever a loader finds nothing, and treating them as a cached `null` would stop non-sync `@Cacheable` methods from ever running.

### When Redis fails {#failures}

A cache used through Spring Cache usually has no loader of its own: the annotated method is the source. Since 6.9 such a cache treats reads and invalidations without a source as having no fallback:

- `@Cacheable(sync = true)` passes the method as the loader, so a Redis read failure still runs the method and keeps the result in L2 for `degraded-local-max-age`.
- A non-sync `@Cacheable` reads through `get(key)`, which has no loader. A Redis read failure throws `CacheReadException`, and Spring hands it to the `CacheErrorHandler`. The default handler rethrows it. A `LoggingCacheErrorHandler` logs it and runs the method instead. Alternatively, set `read-failure: degrade` on the cache: the read then returns a miss without caching it, so Spring runs the method.
- `@CacheEvict` throws `CacheWriteException` when the Redis delete fails, because `invalidate-failure` defaults to `throw` without a loader. Here the method is the source and a delete is always safe, so set `invalidate-failure: retry` on these caches to queue failed evictions for retry as in 6.8. See [Configuration](./configuration.md#policy-properties).

## The retrieve executor (deprecated) {#executor}

Since 6.3, `retrieve` uses the cache's [async view](./async.md) and needs no executor of its own. Before 6.3 each waiting call held a thread, and the executor had to avoid queueing to keep nested async `@Cacheable(sync = true)` calls from deadlocking. Neither applies any more.

- `cocache.spring-cache.retrieve-executor` is deprecated. When set, startup logs a warning and ignores it. It's removed in 7.0.
- `CoCacheSpringCacheManager(runtime, executor)` and `CoCacheSpringCacheManager.DEFAULT_EXECUTOR` are deprecated too, and the executor is ignored. `CoCacheSpringCache(cache, admin, executor)` is not deprecated: its executor runs the method body of `retrieve(key, loader)`, and `CoCacheSpringCacheManager` passes the runtime's blocking executor.
- The async view runs on `cocache.async-executor` and `cocache.blocking-executor`. A synchronous loader called through `retrieve`, and the body of an async `@Cacheable(sync = true)` method, run on the blocking executor. See [Configuration](./configuration.md#executors).

Spring Cache's `put` and `evict` have only blocking signatures. On a reactive method, `@CachePut`, `@CacheEvict` and a non-sync `@Cacheable` call them from the completion callback, which holds that thread for one Redis round trip. Keep using `sync = true`, and call `cache.async().invalidate(key)` where a write path must not block.

## Prefer evict over put {#evict}

After a write, prefer `@CacheEvict` to `@CachePut`. Evicting lets the next read load exactly what was committed, and every instance drops its copy. See [`invalidate` or `put`?](./freshness.md#invalidate-vs-put).

## Transactions {#transactions}

With `spring-tx` on the classpath, the starter wraps the manager in Spring's `TransactionAwareCacheManagerProxy`: `@CacheEvict` and `@CachePut` inside a transaction run after the commit, and not at all on rollback. Invalidating before the commit would let a concurrent read cache the old row.

Skipping on rollback leaves one gap. If a `@Cacheable(sync = true)` read in the same transaction loads the row you just wrote, the uncommitted row stays in Redis until `ttl` after a rollback. `TransactionalInvalidator` also invalidates on rollback, so use it in transactions that read what they write.

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

Naming the bean `cocacheCacheManager` keeps the [sync check](#sync-check) running. The check matches by cache name and treats operations without a `cacheManager` attribute as CoCache's, so give CoCache caches names that other managers don't use.

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
