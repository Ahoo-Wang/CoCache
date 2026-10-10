---
title: Spring Cache
description: Use CoCache caches behind Spring's @Cacheable / @CacheEvict through CoCacheManager.
---

# Spring Cache

`cocache-spring-cache` exposes every CoCache cache as a Spring `Cache`, so `@Cacheable`, `@CachePut`, and `@CacheEvict` get two-level coherent caching. The starter registers `CoCacheManager` unless your application already defines a `CacheManager`.

## Usage

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
    fun getUser(id: String): User = userRepository.findById(id)

    @CacheEvict(cacheNames = ["UserCache"], key = "#user.id")
    fun updateUser(user: User): User = userRepository.save(user)
}
```

The annotated method acts as the loader, so the cache usually has no `CacheSource` of its own. Spring's key must match the cache's key type, which is `String` here.

After a write, prefer `@CacheEvict` over `@CachePut`. Evicting makes the next read load the committed state, and every instance drops its L2 copy.

## Semantics

| Spring operation | CoCache behavior |
|------------------|------------------|
| `get(key)` | A hit returns the value. Absent, expired, and **negative** entries are a miss. |
| `get(key, valueLoader)` (`sync = true`) | Concurrent callers for one key share a single loader call per instance. Loader exceptions are wrapped in `ValueRetrievalException`. |
| `put(key, value)` | `cache[key] = value` with the cache's TTL policy. `null` stores a negative entry. |
| `evict(key)` | `cache.evict(key)` on L2 and L1, then notifies peers |
| `clear()` | Clears **this instance's L2 only**. L1 is shared, and CoCache never clears it wholesale. |
| `retrieve(…)` (reactive / async) | Runs the blocking lookup on a bounded daemon pool. When its queue is full, the caller's thread runs the lookup. |

Negative entries read as a miss for a reason. A CoCache cache stores a negative entry whenever its source finds nothing, and with the default `noOp` source that is every miss. If Spring treated these entries as a cached `null`, `@Cacheable` would never call your method.

## Without Spring Boot

```kotlin
@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun cacheManager(cacheFactory: CacheFactory): CoCacheManager = CoCacheManager(cacheFactory)
}
```

`CoCacheManager(cacheFactory, executor)` takes the executor that `retrieve` should use.
