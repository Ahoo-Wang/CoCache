# JoinCache Views

Use this reference when a read model is "one cached thing plus the cached thing it points to" (an order and its customer, a profile extension and its user). Confirm attribute names in the resolved CoCache version.

## Decide

A `JoinCache<K1, V1, K2, V2>` (`me.ahoo.cocache.api.join`) is a read-only view: it reads the first cache, derives a join key from the first value, and reads the join cache. It stores nothing, has no loader and no `put`, and is not a `Cache` subtype. It saves nothing over two hand-written reads except reuse and one typed bean; `getAll` does exactly two batch reads. Both component caches must already exist as ordinary caches with their own loaders and invalidation.

## Declare

```kotlin
@JoinCacheable(
    firstCacheName = "UserExtendInfoCache",   // required, resolved by name only
    joinCacheName = "UserCache",              // required
    joinKeyExpression = "#{#root.userId}",    // SpEL on the first value; String join keys only
)
interface UserExtendInfoJoinCache : JoinCache<String, UserExtendInfo, String, User> {
    fun userOf(id: String): User? = get(id)?.secondValue   // default methods run on the proxy
}

@EnableCoCache(
    caches = [UserExtendInfoCache::class, UserCache::class],
    joinCaches = [UserExtendInfoJoinCache::class],          // not in `caches`
)
@SpringBootApplication
class App
```

- Bean name: `@JoinCacheable(name)`, default the interface's simple name.
- `firstCacheName` / `joinCacheName` accept an interface cache's name, or a spec cache's name or bean name.

## Join key: exactly one way

1. `joinKeyExpression`: only when `K2` is `String`.
2. `joinKeyExtractor = "beanName"`: a `JoinKeyExtractor<V1, K2>` bean, any key type.
3. Neither: the single `JoinKeyExtractor<V1, K2>` bean by generic type.

```kotlin
@Bean
fun orderCustomerId() = JoinKeyExtractor<Order, Long> { it.customerId }   // null = nothing to join
```

Both set, an expression on a non-`String` key, an expression without `#{...}`, or zero or several extractor beans fail startup.

## Semantics

| Call | Behavior |
|---|---|
| `get(key)` | `null` when the first value is absent or negative; else `JoinValue(firstValue, joinKey, secondValue)`, with `secondValue = null` when the join key is `null` or the second value is absent |
| `getAll(keys)` | One `getAll` on the first cache, then one `getAll` over the distinct non-null join keys |
| `invalidate(key)` | First cache only |
| `invalidate(firstKey, joinKey)` | Both caches; errors are combined |

The two reads are not a snapshot: a pair may combine values from different moments. Each component cache's guarantees apply unchanged.

## Invalidation

The view holds nothing. Keep each component fresh at its own write path: when a `User` changes, invalidate `UserCache` (after commit); every view reading it is fresh on the next call. `TransactionalInvalidator` takes a `Cache`, so pass the component cache.

## Without Spring and in tests

```kotlin
val view = SimpleJoinCache("UserExtendInfoJoinCache", userExtendInfoCache, userCache) { it.userId }
val typed = JoinCacheProxies.create(UserExtendInfoJoinCache::class.java, view)
```

Test the view over two caches on an `InMemoryBackend` runtime: absent first value, `null` join key, absent second value, and distinct join keys in `getAll`.
