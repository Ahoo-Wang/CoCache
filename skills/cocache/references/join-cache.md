# JoinCache Guide

JoinCache composes two independent caches into a single logical view. It retrieves a primary value, extracts a join key from it, then fetches a secondary value from another cache.

## When to Use JoinCache

Use JoinCache when you need to return a composite result from two related cached entities. For example:
- A user's profile (primary) + their department info (secondary)
- An order (primary) + the product details (secondary)
- A blog post (primary) + the author info (secondary)

Without JoinCache, you'd need two separate cache lookups and manual composition. JoinCache handles this atomically and caches the composed result.

## How It Works

```
get(key)
  ├── firstCache.getCache(key) → CacheValue<V1>
  ├── joinKeyExtractor.extract(firstValue) → K2
  ├── joinCache.getCache(joinKey) → CacheValue<V2>
  └── JoinValue(firstValue, joinKey, secondValue)
```

**Set flow:**
1. Sets `firstCache` with the primary value
2. If secondary value is not null, sets `joinCache` with it at the extracted join key

**Evict flow:**
1. `evict(key)`: Gets primary value, extracts join key, evicts both caches
2. `evict(firstKey, joinKey)`: Directly evicts both by their respective keys

**TTL:** The composed `JoinValue` uses `min(firstTtl, secondTtl)`.

## Creating a JoinCache

### Step 1: Define the Two Base Caches

```kotlin
@CoCache(keyPrefix = "user:", ttl = 300)
interface UserCache : Cache<String, User>

@CoCache(keyPrefix = "user_ext:", ttl = 300)
interface UserExtendInfoCache : Cache<String, UserExtendInfo>
```

### Step 2: Define the JoinCache Interface

```kotlin
@JoinCacheable(
    firstCacheName = "UserExtendInfoCache",
    joinCacheName = "UserCache",
    joinKeyExpression = "#{#root.userId}"
)
interface UserExtendInfoJoinCache : JoinCache<String, UserExtendInfo, String, User>
```

The type parameters are: `JoinCache<PrimaryKey, PrimaryValue, JoinKey, JoinValue>`

### Step 3: Register All Three

```kotlin
@SpringBootApplication
@EnableCoCache(caches = [UserCache::class, UserExtendInfoCache::class, UserExtendInfoJoinCache::class])
class MyApp
```

### Step 4: Use It

```kotlin
@Service
class UserExtendInfoService(
    @Qualifier("userExtendInfoJoinCache")
    private val joinCache: UserExtendInfoJoinCache
) {
    fun getExtendInfoWithUser(extId: String): JoinValue<UserExtendInfo, String, User>? {
        val result = joinCache[extId]  // Returns JoinValue<UserExtendInfo, String, User>
        return result
    }

    fun getUser(result: JoinValue<UserExtendInfo, String, User>): User? {
        return result.secondValue  // The joined User
    }

    fun evict(extId: String) {
        joinCache.evict(extId)  // Evicts from both caches
    }
}
```

## JoinKeyExtractor Resolution

The join key is extracted from the primary value using one of these methods (in order of priority):

### 1. SpEL Expression (via @JoinCacheable)

```kotlin
@JoinCacheable(
    firstCacheName = "OrderCache",
    joinCacheName = "ProductCache",
    joinKeyExpression = "#{#root.productId}"
)
interface OrderProductJoinCache : JoinCache<String, Order, String, Product>
```

`#root` refers to the primary value. Common patterns:
- `#{#root.userId}` - property access
- `#{#root.items[0].productId}` - nested access
- `#{#root.categoryId.toString()}` - method call

### 2. Named Bean

Define a bean named `{cacheName}.JoinKeyExtractor`:

```kotlin
@Bean("OrderProductJoinCache.JoinKeyExtractor")
fun orderJoinKeyExtractor(): JoinKeyExtractor<Order, String> {
    return JoinKeyExtractor { order -> order.productId }
}
```

### 3. Type-Based Bean

Define a bean by generic type:

```kotlin
@Bean
fun orderJoinKeyExtractor(): JoinKeyExtractor<Order, String> {
    return JoinKeyExtractor { order -> order.productId }
}
```

## JoinValue Structure

`JoinValue<V1, K2, V2>` contains:
- `firstValue: V1` - the primary value
- `joinKey: K2` - the extracted join key
- `secondValue: V2?` - the joined secondary value (null if not found)

## Nested JoinCache

You can compose JoinCaches to create multi-level joins:

```kotlin
// Level 1: Order + Product
@JoinCacheable(
    firstCacheName = "OrderCache",
    joinCacheName = "ProductCache",
    joinKeyExpression = "#{#root.productId}"
)
interface OrderProductJoinCache : JoinCache<String, Order, String, Product>

// Level 2: (Order+Product) + Category
@JoinCacheable(
    firstCacheName = "OrderProductJoinCache",
    joinCacheName = "CategoryCache",
    joinKeyExpression = "#{#root.secondValue.categoryId}"
)
interface OrderProductCategoryJoinCache : JoinCache<String, JoinValue<Order, String, Product>, String, Category>
```

## Eviction in JoinCache

When the secondary cache value changes, you need to evict the JoinCache too:

```kotlin
// When User changes, evict the JoinCache
fun updateUser(user: User) {
    userCache[user.id] = user
    // Evict all JoinCache entries that reference this user
    // Since we don't know which extInfo keys map to this user,
    // we need a strategy:
}

// Option 1: If you know the primary key
fun updateUserWithExtId(user: User, extId: String) {
    userCache[user.id] = user
    userExtendInfoJoinCache.evict(extId, user.id)  // evict(firstKey, joinKey)
}

// Option 2: Evict when primary changes
fun updateExtendInfo(extInfo: UserExtendInfo) {
    userExtendInfoCache[extInfo.id] = extInfo
    userExtendInfoJoinCache.evict(extInfo.id)  // auto-extracts joinKey and evicts both
}
```

## Common Patterns

### Pattern: Enriching an Entity

```kotlin
// Cache the core entity
@CoCache(keyPrefix = "order:", ttl = 600)
interface OrderCache : Cache<String, Order>

// Cache the enrichment data
@CoCache(keyPrefix = "customer:", ttl = 300)
interface CustomerCache : Cache<String, Customer>

// Compose them
@JoinCacheable(
    firstCacheName = "OrderCache",
    joinCacheName = "CustomerCache",
    joinKeyExpression = "#{#root.customerId}"
)
interface OrderWithCustomerCache : JoinCache<String, Order, String, Customer>
```

### Pattern: Multi-Tenant Data

```kotlin
@CoCache(keyPrefix = "tenant:", ttl = 3600)
interface TenantCache : Cache<String, Tenant>

@CoCache(keyPrefix = "tenant_config:", ttl = 3600)
interface TenantConfigCache : Cache<String, TenantConfig>

@JoinCacheable(
    firstCacheName = "TenantCache",
    joinCacheName = "TenantConfigCache",
    joinKeyExpression = "#{#root.id}"
)
interface TenantWithConfigCache : JoinCache<String, Tenant, String, TenantConfig>
```
