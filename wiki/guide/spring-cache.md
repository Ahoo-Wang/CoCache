---
title: Spring Cache
description: Use CoCache caches behind Spring's @Cacheable / @CachePut / @CacheEvict through CoCacheSpringCacheManager, and invalidate after commit with TransactionalInvalidator.
---

# Spring Cache

`cocache-spring` exposes every CoCache cache as a Spring `Cache`, so `@Cacheable`, `@CachePut`, and `@CacheEvict` get two-level coherent caching. The starter registers `CoCacheSpringCacheManager` unless your application already defines a `CacheManager`.

## Usage {#usage}

Declare the cache as usual. Its name is the Spring cache name. Then enable Spring caching:

```kotlin
@CoCache(ttl = 600)
interface UserCache : Cache<String, User>

@EnableCaching
@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class AppServer

@Service
class UserService(private val userRepository: UserRepository) {
    @Cacheable(cacheNames = ["UserCache"], key = "#id", sync = true)
    fun getUser(id: String): User? = userRepository.findById(id)

    @Transactional
    @CacheEvict(cacheNames = ["UserCache"], key = "#user.id")
    fun updateUser(user: User): User = userRepository.save(user)
}
```

The annotated method acts as the loader, so the cache usually has no `CacheLoader` of its own. Spring's key must match the cache's key type, which is `String` here. The manager only serves caches you declared: it never creates a cache on demand from a name.

## Use `sync = true` {#use-sync-true}

`@Cacheable(sync = true)` is the coherent path. Spring calls `get(key, valueLoader)`, which CoCache runs through the same protected load as its own reads:

- concurrent callers on one instance share one method call (`SingleFlight`),
- concurrent callers on different instances share one call through an L1 lease, if it finishes within `lease-wait`, and
- an invalidation that happens while the method runs is never overwritten by its result.

Without `sync`, Spring does "read, miss, call the method, `put`" itself. That `put` is unconditional, so an invalidation that happened while the method ran can be overwritten with the old result until `ttl`. Use `sync = true` wherever you need CoCache's guarantees.

The starter checks this at startup. With `@EnableCaching`, it asks Spring's `CacheOperationSource` for the cache operations of every bean, including names from `@CacheConfig`. For each `@Cacheable` without `sync = true` that names a CoCache cache, it logs a `WARN` with `Class#method` and the cache names. To fail startup instead:

```yaml
cocache:
  spring-cache:
    require-sync: true
```

The check matches by cache name. It skips operations that name no cache (only a `cacheResolver`), and it does nothing without `@EnableCaching`.

After a write, prefer `@CacheEvict` over `@CachePut`. Evicting makes the next read load the committed state, and every instance drops its L2 copy.

## Semantics {#semantics}

| Spring operation | CoCache behavior |
|------------------|------------------|
| `get(key)` | A hit returns the value. Absent and **negative** entries are a miss. |
| `get(key, valueLoader)` (`sync = true`) | `cache.get(key, loader)`: lease- and stamp-protected load. Loader exceptions are wrapped in `ValueRetrievalException`. |
| `put(key, value)` | `cache.put(key, value)`. `null` (for example `@CachePut` returning null) becomes `invalidate(key)`. |
| `evict(key)` | `cache.invalidate(key)` on L2 and L1, then notifies peers |
| `clear()` | Clears **this instance's L2 only**. L1 is shared, and CoCache never clears it wholesale. |
| `retrieve(…)` (reactive / async) | Runs the blocking lookup on a daemon thread pool, not on the caller's thread |

Negative entries read as a miss for a reason. A cache stores a negative entry whenever its loader finds nothing. If Spring treated these entries as a cached `null`, `@Cacheable` would never call your method.

## Transactions {#transactions}

Invalidating before the transaction commits is itself a bug: a concurrent read can reload the old row and cache it. With `spring-tx` on the classpath, the starter:

- wraps the cache manager in Spring's `TransactionAwareCacheManagerProxy`, so `@CacheEvict` and `@CachePut` inside a transaction run after the commit, and
- registers a `TransactionalInvalidator` for direct calls:

```kotlin
@Transactional
fun rename(id: String, name: String) {
    userRepository.rename(id, name)
    invalidator.invalidate(userCache, id)   // runs in afterCommit
}
```

`TransactionalInvalidator` defers the invalidation to `afterCommit`, merges every invalidation of one cache in one transaction into a single `invalidateAll`, and does nothing on rollback. A `REQUIRES_NEW` inner transaction invalidates after its own commit. Without an active transaction it invalidates immediately.

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

`CoCacheSpringCacheManager(runtime, executor)` takes the executor that `retrieve` should use. Drop the proxy if you don't use transactions.
