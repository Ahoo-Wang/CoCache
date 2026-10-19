# JoinCache Guide

## Contents

- [When to Use](#when-to-use)
- [Declare a JoinCache](#declare-a-joincache)
- [Join Key Resolution](#join-key-resolution)
- [Semantics](#semantics)
- [Invalidation](#invalidation)
- [Without Spring and in Tests](#without-spring-and-in-tests)
- [Limits](#limits)

A `JoinCache<K1, V1, K2, V2>` (`me.ahoo.cocache.api.join`) is a **read-only view** over two ordinary caches: it reads the first cache, extracts a join key from the first value, and reads the join cache. It is **not** a `Cache` subtype: no `put`, no loader, no copy of its own.

## When to Use

Return a composite of two cached entities, for example an order plus its customer, or a profile extension plus its user. It does exactly two batch reads for `getAll`; writing them by hand is equivalent. Use the view when the composition is reused, or to keep callers on one typed bean.

## Declare a JoinCache

```kotlin
@CoCache(keyPrefix = "user:")
interface UserCache : Cache<String, User>

@CoCache(keyPrefix = "user_ext:")
interface UserExtendInfoCache : Cache<String, UserExtendInfo>   // UserExtendInfo(id, userId)

@JoinCacheable(                                 // me.ahoo.cocache.api.annotation.JoinCacheable
    firstCacheName = "UserExtendInfoCache",     // required, resolved by name
    joinCacheName = "UserCache",                // required, resolved by name
    joinKeyExpression = "#{#root.userId}",      // SpEL template, first value as root
)
interface UserExtendInfoJoinCache : JoinCache<String, UserExtendInfo, String, User> {
    fun userOf(id: String): User? = get(id)?.secondValue   // default methods run on the proxy
}

@SpringBootApplication
@EnableCoCache(
    caches = [UserCache::class, UserExtendInfoCache::class],
    joinCaches = [UserExtendInfoJoinCache::class],          // NOT in `caches`
)
class App
```

- Bean name: `@JoinCacheable(name)`, default the interface's simple name (`UserExtendInfoJoinCache`); bean type is the interface.
- `firstCacheName` / `joinCacheName` accept an interface cache's name, or a spec cache's name or bean name (`cacheSpec("orders")` from `orderSpec()` → `"orders"` or `"order"`).
- Startup fails with a message naming the view when a name is blank or unknown, when a referenced cache is not `Cache<K1, V1>` / `Cache<K2, V2>`, or when the interface's type arguments are not concrete.
- Abstract methods other than `JoinCache`'s fail at creation; use default methods.

## Join Key Resolution

Exactly one of:

1. `joinKeyExpression = "#{#root.userId}"` (or `#{userId}`): only when `K2` is `String`.
2. `joinKeyExtractor = "beanName"`: a `JoinKeyExtractor<V1, K2>` bean, any key type.
3. Neither: the unique `JoinKeyExtractor<V1, K2>` bean by generic type; none or several fail startup.

Both set → startup failure.

```kotlin
@Bean
fun orderCustomerId() = JoinKeyExtractor<Order, Long> { it.customerId }   // return null for "no join"
```

## Semantics

| Call | Behavior |
|------|----------|
| `get(key)` | `null` if the first value is absent (including a negative entry). Else `JoinValue(firstValue, joinKey, secondValue)`; `secondValue` is `null` when the join key is `null` or the second value is absent. |
| `getAll(keys)` | first cache `getAll`, then one join cache `getAll` over the distinct non-null join keys (skipped if none). Only keys with a first value appear. |
| `invalidate(key)` | first cache only |
| `invalidate(firstKey, joinKey)` | both caches |

`JoinValue<V1, K2, V2>(firstValue: V1, joinKey: K2?, secondValue: V2?)` is a data class.

The two reads are not atomic; each component cache's coherence guarantees apply unchanged.

## Invalidation

The view holds nothing, so keep the component caches fresh:

```kotlin
fun updateUser(user: User) {
    userRepository.save(user)
    transactionalInvalidator.invalidate(userCache, user.id)   // every view reading this user is fresh on the next call
}

fun updateExtendInfo(info: UserExtendInfo) {
    repository.save(info)
    userExtendInfoJoinCache.invalidate(info.id)               // == userExtendInfoCache.invalidate(info.id)
}
```

Inside a transaction, invalidate after commit (`TransactionalInvalidator` takes a `Cache`, so pass the component cache).

## Without Spring and in Tests

```kotlin
val view = SimpleJoinCache("extJoin", userExtendInfoCache, userCache) { it.userId }   // me.ahoo.cocache.core.join
val typed = JoinCacheProxies.create(UserExtendInfoJoinCache::class.java, view)       // me.ahoo.cocache.core.proxy
```

Build both component caches on a `CacheRuntime` with `InMemoryBackend` (see `testing.md`).

## Limits

- No nested joins: `firstCacheName` / `joinCacheName` must name a `Cache`, not another JoinCache. Compose in code instead.
- No `set` / `put` through the view; write each component cache.
- `@JoinCacheable` is not a meta-annotation; place it directly on the interface.
