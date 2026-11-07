---
title: Spring Cache
description: Use CoCache behind @Cacheable, @CachePut and @CacheEvict — why sync = true matters, the startup checks, null handling, Redis failures, transactions, and using CoCache alongside other cache managers.
---

# Spring Cache

If your code already uses Spring's caching annotations, CoCache can sit behind them. The starter registers `CoCacheSpringCacheManager`, which exposes every CoCache `Cache` (a derived copy) as a Spring `Cache` under the same name; the other four types [aren't exposed](#sync-check).

## Usage {#usage}

```kotlin
@CoCache(ttl = 600, source = CoCache.Source.EXTERNAL)
interface UserCache : Cache<String, User>          // externally sourced: the annotated method loads

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

- The cache must be declared; the manager never creates caches from names it hasn't seen. Under `spring.main.lazy-initialization`, a declared `Cache` that only `@Cacheable` uses has no injection point and isn't created at startup; the starter's manager creates it on the first call that names it. Only a declared `Cache` is created this way: any other name still finds no cache.
- Spring's key must have the cache's key type (`String` here), and the method must return the cache's value type.
- Only a `Cache` can sit behind Spring Cache: it is a derived copy, and the annotated method is its source. Don't name a `Store`, `VersionedStore` or `SetStore` in a cache annotation.
- The annotated method acts as the loader, so the cache usually has no `CacheLoader` of its own. Declare it [externally sourced](./cache.md#externally-sourced): `source = CoCache.Source.EXTERNAL` on the interface, or `externallySourced()` in a `cacheSpec`. A `Cache` interface with no matching loader bean and no `source = EXTERNAL` fails startup.

## Always use `sync = true` {#use-sync-true}

With `sync = true`, Spring calls `get(key, valueLoader)`, and CoCache runs your method through the same protected load as its own reads:

- concurrent callers on one instance share one method call,
- callers on other instances wait for that call through the Redis lease (up to `lease-wait`),
- an invalidation that happens while the method runs is never overwritten by its result, and
- if the calling thread is interrupted while the method runs, only that caller fails. Callers that joined its call run the method themselves instead of getting the interrupt.

Without `sync`, Spring does the work itself: read, miss, call the method, `put`. That `put` is unconditional. If the row was updated and invalidated while the method ran, the old result is written back and served until `ttl`. Concurrent misses also all call the method.

### The startup check {#sync-check}

With `@EnableCaching`, the starter inspects every bean's cache operations at startup (including names from `@CacheConfig`). For each `@Cacheable` without `sync = true` that names a CoCache cache, it logs a `WARN` with `Class#method` and the cache names. To fail startup instead:

```yaml
cocache:
  spring-cache:
    require-sync: true
```

The check matches by cache name. It skips operations that name another `cacheManager` or set a custom `cacheResolver`, since those don't necessarily reach CoCache.

The same check also logs a `WARN` when the cache advice and the transaction advice have the same order. See [Transaction advice outermost](#advice-order).

It also fails startup, whatever `require-sync` says, when any cache operation (`@Cacheable`, `@CachePut` or `@CacheEvict`, with or without `sync`) names a CoCache cache that isn't a `Cache`: a `Store`, `VersionedStore`, `SetStore` or `VersionedCache`. Spring Cache only serves derived caches, so the CoCache manager doesn't expose the others. Without the check, the call would fail later with "Cannot find cache".

The check runs only when a bean named `cocacheCacheManager` exists: the starter's own manager, or one you declare under that name (see [Alongside other cache managers](#other-managers)). The starter's manager steps aside when you declare any other `CacheManager`, so if you wire `CoCacheSpringCacheManager` yourself under a different bean name, the check doesn't apply and `sync = true` is up to you.

## How Spring operations map {#semantics}

| Spring | CoCache |
|--------|---------|
| `get(key)` | A value is a hit. Absent and **negative** entries are a miss. |
| `get(key, valueLoader)` (`sync = true`) | `cache.get(key, loader)`. A loader exception is wrapped in `ValueRetrievalException` and shared with the callers that joined the call. An `InterruptedException` from the method, meaning the calling thread was interrupted, is the exception: it fails only that caller, still wrapped in `ValueRetrievalException`, and the callers that joined run the method themselves. |
| `put(key, value)` (`@CachePut`) | `cache.put(key, value)`. A `null` value becomes `invalidate(key)`. |
| `evict(key)` (`@CacheEvict`) | `cache.invalidate(key)` on every instance. Inside a transaction, see [Transactions](#transactions). |
| `clear()` (`allEntries = true`) | Clears **this instance's L2 only**. Redis and the other instances keep their entries, because CoCache can't clear a cache cluster-wide. The first call on each cache logs a `WARN` saying so. Evict keys instead. |
| `retrieve(key)` (async and reactive) | An async `lookup` on the cache's [async view](./async.md). An L2 hit returns an already-completed future without switching threads, and a negative entry completes with `null` (a miss). A Redis read failure fails the future, as it fails `get(key)` (see [When Redis fails](#failures)). |
| `retrieve(key, loader)` (async and reactive with `sync = true`) | An async `get(key, loader)` on the [async view](./async.md): it loads through the same lease-protected path as `get(key, loader)`, and **no thread waits** for the async loader. The method body runs on the runtime's executor (`cocache.executor`), not on the calling thread, so a body that blocks before it returns a future, such as `completedFuture(repository.find(id))`, holds one of that executor's threads, never the caller's. It can't see the caller's thread-locals. A failure completes the future with the original exception. |

### Null results {#null}

With `sync = true`, a method that returns `null` produces a negative entry. For `absent-ttl` (1 minute by default), later calls return `null` without running the method. Invalidate the key when the row is created, or shorten `absent-ttl`.

Negative entries read as a miss through `get(key)` on purpose: CoCache stores them whenever a loader finds nothing, and treating them as a cached `null` would stop non-sync `@Cacheable` methods from ever running.

### When Redis fails {#failures}

A cache used through Spring Cache is usually [externally sourced](./cache.md#externally-sourced): the annotated method is the source. A read that carries the method can fall back to it; a read that doesn't has no answer:

- `@Cacheable(sync = true)` passes the method as the loader, so a Redis read failure still runs the method and keeps the result in L2 for `degraded-local-max-age`.
- A non-sync `@Cacheable` reads through `get(key)`, which has no loader. On a method that returns `CompletableFuture`, `Mono` or `Flux`, Spring reads through `retrieve(key)` instead. A Redis read failure is [`Lookup.Unknown`](./caches.md#unknown): `get(key)` throws its `CacheReadException`, `retrieve(key)` fails its future with it, and on all three kinds of method Spring hands it to the `CacheErrorHandler`. The default handler rethrows it. A `LoggingCacheErrorHandler` logs it and runs the method instead. Alternatively, declare `unknownRead = UnknownReadMode.ABSENT` on the cache's spec (in the `cacheSpec`; for an interface cache with `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)`, in a [customizer](./caches.md#customizer), or as `cocache.caches.[name].unknown-read: absent`): the read then returns a miss without caching it, so Spring runs the method.
- `@CacheEvict` calls `invalidate`. When the Redis delete fails, the key is queued for background retry, because `invalidate-failure` defaults to `retry` for every `Cache`: the method is the source, so deleting the copy is always safe. Set `invalidate-failure: throw` to get `CacheWriteException` instead. See [Configuration](./configuration.md#policy-properties).

This makes the two kinds of `@Cacheable` behave differently on the same cache. While Redis is down, every non-sync `@Cacheable` call fails, because Spring's default `CacheErrorHandler` rethrows the read failure. A `@Cacheable(sync = true)` call runs the method instead. `unknownRead = ABSENT` doesn't make a non-sync method available either: the `put` that follows fails the same way.

To let these failures through, turn on the starter's degrading handler:

```yaml
cocache:
  spring-cache:
    degrade-on-error: true
```

The starter then gives Spring's cache interceptor a `DegradingCacheErrorHandler`. For CoCache caches only, it logs a read failure or a write failure as a `WARN` (no stack trace, no key; with `DEBUG` enabled for that logger, it logs at `DEBUG` with the stack trace instead) and doesn't rethrow it. Spring then treats the read as a miss and runs the method, and the result isn't cached. The method is the source, so the cached copy can be skipped. Everything else goes to the handler you had before, the one from your `CachingConfigurer` or Spring's default:

- failures on other caches, and
- failed invalidations and clears on CoCache caches (`@CacheEvict`, and `@CachePut` with a `null` result). With the default `invalidate-failure: retry` these never fail. With `throw` you asked to see the failure.

Without Spring Boot, return `DegradingCacheErrorHandler(yourHandler)` from `CachingConfigurer.errorHandler()`.

## Executors {#executor}

`retrieve` uses the cache's [async view](./async.md) and needs no executor of its own: no call holds a thread while it waits for a load.

- `CoCacheSpringCacheManager(runtime)` takes no executor. It creates each `CoCacheSpringCache(cache, admin, executor)` with the runtime's executor (`CacheRuntime.executor`), which runs the method body of `retrieve(key, loader)`.
- The async view runs on `cocache.executor`. A synchronous loader called through `retrieve`, and the body of an async `@Cacheable(sync = true)` method, run there too, so that executor must tolerate blocking tasks (the default starts one virtual thread per task). See [Configuration](./configuration.md#executors).

Spring Cache's `put` and `evict` have only blocking signatures. On a reactive method, `@CachePut`, `@CacheEvict` and a non-sync `@Cacheable` call them from the completion callback, which holds that thread for one Redis round trip. Keep using `sync = true`, and call `cache.async().invalidate(key)` where a write path must not block.

## Prefer evict over put {#evict}

After a write, prefer `@CacheEvict` to `@CachePut`. Evicting lets the next read load exactly what was committed, and every instance drops its copy. See [`invalidate` or `put`?](./freshness.md#invalidate-vs-put).

## Transactions {#transactions}

With `spring-tx` on the classpath, the starter wraps the manager in CoCache's transaction decorator, `TransactionAwareSpringCacheManager`. It replaces Spring's `TransactionAwareCacheManagerProxy`. Inside a transaction:

| Annotation | Spring call | What happens |
|------------|-------------|--------------|
| `@CacheEvict` | `evict(key)` | Registered with the transaction. The key is invalidated after the commit **and** after a rollback. |
| `@CacheEvict(beforeInvocation = true)` | `evictIfPresent(key)` | Invalidated at once, as Spring requires, and registered as well, so it's invalidated again after the commit or rollback. |
| `@CachePut` | `put(key, value)` | Written after the commit. Dropped on rollback. A `null` result is an invalidation and is registered like `@CacheEvict`. |
| `@CacheEvict(allEntries = true)` | `clear()` | This instance's L2 is cleared after the commit. Dropped on rollback. |
| `@CacheEvict(allEntries = true, beforeInvocation = true)` | `invalidate()` | This instance's L2 is cleared at once. |

Outside a transaction, every operation runs at once.

Why the decorator does this:

- **Invalidate after the commit.** Invalidating before the commit would let a concurrent read cache the old row. For `beforeInvocation = true`, the invalidation after the commit removes an old row that a read on another instance loaded between the eviction and the commit.
- **Invalidate after a rollback too.** A `@Cacheable(sync = true)` read in the same transaction runs inside it and can load a row you wrote but never committed. Spring's decorator skips `@CacheEvict` on rollback, which would leave that row in Redis until `ttl`.
- **Later evictions win.** At the commit, CoCache runs the invalidations first, then the writes. A `@CachePut` followed by a `@CacheEvict` of the same key ends with the key evicted, and a `@CacheEvict` followed by a `@CachePut` ends with the new value.
- **Savepoints.** When a `NESTED` transaction (or a programmatic savepoint) rolls back, writes registered after the savepoint are dropped, and registered keys are invalidated at once.

### Loads inside a transaction {#transactional-loads}

Synchronous reads also watch the transaction they run in. This covers `@Cacheable(sync = true)` and direct `get`, `lookup`, `getAll`, `lookupAll` and `get(key, loader)` calls on any CoCache cache that has a source. It applies only to a read-write transaction of a `PlatformTransactionManager`. Read-only transactions, async and suspending calls, and reactive transactions aren't watched.

- **A rollback invalidates what the transaction loaded** (`invalidate-fills-on-rollback`, on by default). A load inside the transaction can read a row you wrote but haven't committed, and cache it in Redis, or only in this instance's L2 when Redis failed. CoCache records every key the transaction loaded into a cache, and invalidates them when the transaction rolls back, when its commit outcome is unknown, or when a `NESTED` transaction rolls back to its savepoint. A commit leaves them alone: they hold committed data. The exception is a transaction that rolled back to a savepoint. Spring calls every synchronization's `savepointRollback` before it rolls the database back, so another synchronization that reads the cache there loads rows that are about to be rolled back. Such a transaction therefore also invalidates everything it loaded when it commits, before its deferred `@CachePut` writes run, so those writes stay. Keys it had loaded by the rollback also load privately for the rest of the transaction, as registered keys do. A nested transaction that ends normally changes nothing. You don't have to register anything, so this also covers a transaction that never calls `@CacheEvict`. Until the rollback, other callers can still read the uncommitted value from Redis: the rollback shortens that window from `ttl` to the length of the transaction.
- **Registered keys load privately** (`fill-in-transaction`, default `registered-keys`). Once a key is registered for invalidation in the transaction (`@CacheEvict`, `TransactionalInvalidator`), loading it again in the same transaction doesn't touch Redis, L2 or other callers. The value goes to that caller only, and the load is counted in `cocache.fills.gated`. Registering also drops this instance's copy at once, so the next read in the transaction misses and loads your own write. That isn't guaranteed: another thread can put the committed value back into L2 first. `private` makes every load inside a read-write transaction private, so a read that misses L2 always calls the source. `allow` turns this off.
- **Other callers don't share a transaction's load.** When a caller inside a transaction leads a load, concurrent callers on the same instance load for themselves instead of taking its result, which might never be committed.

Keys match by keyspace (key prefix and namespace) and encoded key, so an eviction through an interface cache's proxy also covers reads through the `cacheSpec` bean or Spring Cache. Both settings are per cache, under `cocache.defaults.cache`, `cocache.overrides.cache` (which takes the stricter value) and `cocache.caches.[name]` ([what each layer may set](./configuration.md#field-layers)). An application `FillObserver` bean of its own replaces the starter's, and then neither setting has any effect. Turning both off for a cache (`invalidate-fills-on-rollback: false` and `fill-in-transaction: allow`) makes its loads ignore transactions:

```yaml
cocache:
  defaults:
    cache:
      fill-in-transaction: private      # every cache: loads in a write transaction stay private
  caches:
    ReportCache:
      fill-in-transaction: allow        # this one: ignore transactions
      invalidate-fills-on-rollback: false
```

A cache's settings take effect once it's created. A read that starts earlier, from a `CacheCreationListener` you registered on your own runtime, uses the defaults (both on) for the whole call, so a rollback still invalidates what it loaded.

### Failures after the commit {#after-commit-failures}

A deferred write or invalidation that fails after the commit goes to the cache interceptor's `CacheErrorHandler`, just like a failure outside a transaction. This includes an eviction made in another synchronization's `afterCommit` after CoCache's own has run: it runs at once and its failure goes to the handler too, so the synchronizations after it still run. The caller doesn't get an exception for an operation whose data is already committed, and so won't retry it. If the handler rethrows, as Spring's default `SimpleCacheErrorHandler` does, CoCache logs the exception at `ERROR` and doesn't throw it. The handler gets the decorated cache as its `cache` argument. With [`degrade-on-error`](#failures), a failed write is logged as a `WARN`.

The handler can use that cache to compensate, for example by evicting or rewriting an entry related to the one that failed. Such an operation runs at once, and it replaces any write of the same key that the transaction deferred and that hasn't run yet, so the older value isn't written back afterwards. Writes that already ran are left alone, and invalidations the transaction registered still run, so a rewritten key that still has one pending is simply a miss.

### Transaction advice outermost {#advice-order}

`@EnableCaching` and `@EnableTransactionManagement` both default to the order `LOWEST_PRECEDENCE`. When the orders are equal, whichever advisor was registered first ends up outermost. Everything above assumes the transaction advice is outermost, so the cache operations run inside the transaction.

When the cache advice is outermost, the cache operations run before the transaction begins or after it ends, and the decorator never sees the transaction:

- `@CacheEvict` runs after the commit. It isn't registered, and it doesn't run on rollback.
- `@CacheEvict(beforeInvocation = true)` invalidates once, before the transaction begins. Nothing invalidates after the commit, so an old row loaded in between stays until `ttl`.
- A `@Cacheable(sync = true)` method opens its own transaction inside the load, and the result is cached after that transaction ends. The load starts outside any transaction, so it isn't [watched](#transactional-loads): if the transaction rolls back and the method still returns a value, that value stays cached until `ttl`.

On a reactive method, `@CacheEvict` runs in `doOnSuccess`. With the transaction outermost, that is before the commit. Reactive transactions aren't visible to the decorator (see [Keeping Data Fresh](./freshness.md#transactions)).

When the two orders are equal, the starter logs a `WARN` at startup. Give the transaction advice a lower order to put it outermost. Declaring your own `@EnableTransactionManagement` makes Spring Boot's auto-configured one step aside:

```kotlin
@EnableCaching
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)
@SpringBootApplication
class App
```

For direct calls, use `TransactionalInvalidator` (see [Keeping Data Fresh](./freshness.md#transactions)). It shares the transaction's registration with the decorator, so its invalidation also replaces an earlier `@CachePut` of the same key. Keys match by keyspace (key prefix and namespace) and encoded key, so any object for the cache works: the `cacheSpec` bean, an interface cache's proxy, or the cache behind Spring Cache.

## Alongside other cache managers {#other-managers}

The starter's manager steps aside when your application defines its own `CacheManager`. To use CoCache next to another manager, declare it yourself and point annotations at it:

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

Naming the bean `cocacheCacheManager` keeps the [sync check](#sync-check) running. The check matches by cache name and treats operations without a `cacheManager` attribute as CoCache's, so give CoCache caches names that other managers don't use.

To switch off only CoCache's Spring Cache wiring, exclude its auto-configuration, `me.ahoo.cocache.spring.boot.CoCacheSpringCacheAutoConfiguration`, with `spring.autoconfigure.exclude` or `@SpringBootApplication(exclude = [CoCacheSpringCacheAutoConfiguration::class])`. That removes its three beans, which keep their names: the manager `cocacheCacheManager`, the startup check `cocacheSpringCacheSyncCheck` and `cocacheDegradingCacheErrorHandler` (`cocache.spring-cache.degrade-on-error`). CoCache's caches, `TransactionalInvalidator` and the rest of the starter keep working.

## Without Spring Boot {#without-spring-boot}

```kotlin
@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun cacheManager(runtime: CacheRuntime, interceptor: ObjectProvider<CacheInterceptor>): CacheManager =
        TransactionAwareSpringCacheManager(CoCacheSpringCacheManager(runtime)) {
            interceptor.getIfUnique()?.errorHandler ?: SimpleCacheErrorHandler()
        }

    @Bean
    fun transactionalInvalidator() = TransactionalInvalidator()
}
```

The manager serves the caches already created in that `CacheRuntime`. If a cache may not exist yet when Spring first asks for it (a lazily created bean), use `CoCacheSpringCacheManager(runtime, createDeclared)`, where `createDeclared` is a `java.util.function.Consumer<String>`: when a name isn't in the runtime, the manager calls `createDeclared.accept(name)` and then looks again, so the function can create the cache declared under that name, as the starter's manager does. The lambda hands failures after a commit to the interceptor's error handler. It's resolved on each failure, after the context has started. Without it, the decorator uses `SimpleCacheErrorHandler`, so those failures are only logged. Drop the decorator if you don't use transactions.

To [watch loads inside transactions](#transactional-loads), give the runtime a `TransactionalFillObserver` when you build it: `CacheRuntime.builder().backend(backend).fillObserver(TransactionalFillObserver()).build()`. Its constructor takes the two settings.
