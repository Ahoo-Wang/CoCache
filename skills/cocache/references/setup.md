# CoCache Project Setup Guide

## Contents

- [Dependencies](#dependencies)
- [Module Selection](#module-selection)
- [Minimal Configuration](#minimal-configuration)
- [Step-by-Step Setup](#step-by-step-setup)
- [Redis Failure Behavior](#redis-failure-behavior)
- [Without Spring Boot](#without-spring-boot)
- [Single-Instance Setup (No Redis)](#single-instance-setup-no-redis)
- [Spring Cache Bridge](#spring-cache-bridge)
- [Actuator Endpoints](#actuator-endpoints)
- [Disabling CoCache](#disabling-cocache)

## Dependencies

### Gradle (Kotlin DSL)

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    // Spring Boot starter (includes everything needed)
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")

    // Or use BOM for version management
    implementation(platform("me.ahoo.cocache:cocache-bom"))
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'me.ahoo.cocache:cocache-spring-boot-starter'
}
```

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>me.ahoo.cocache</groupId>
            <artifactId>cocache-bom</artifactId>
            <version>${cocache.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>me.ahoo.cocache</groupId>
    <artifactId>cocache-spring-boot-starter</artifactId>
</dependency>
```

## Module Selection

| Module | When to use |
|--------|-------------|
| `cocache-spring-boot-starter` | Spring Boot projects (recommended, includes auto-config) |
| `cocache-spring` | Spring projects without Boot |
| `cocache-spring-redis` | Redis distributed cache implementation |
| `cocache-spring-cache` | Spring Cache abstraction bridge (`@Cacheable`) |
| `cocache-core` | Core implementations, no Spring dependency |
| `cocache-api` | Only the interfaces (for library authors) |

## Minimal Configuration

### application.yaml

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

cocache:
  enabled: true  # default, can omit
```

### Application Class

```kotlin
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.spring.EnableCoCache
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableCoCache(caches = [UserCache::class])
class MyApp

fun main(args: Array<String>) {
    runApplication<MyApp>(*args)
}

@CoCache(keyPrefix = "user:", ttl = 300)
interface UserCache : Cache<String, User>
```

That's it. CoCache auto-configures:
- `RedisDistributedCache` as L1
- `CaffeineClientSideCache` as L2 (bounded, 10000 entries by default)
- `RedisCacheEvictedEventBus` for cross-instance coherence
- `ToStringKeyConverter` with the `keyPrefix`
- `CacheSource.noOp()` (returns null, you provide your own)

## Step-by-Step Setup

### Step 1: Add Dependencies

```kotlin
// build.gradle.kts
plugins {
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
}
```

### Step 2: Define Cache Interfaces

Create one interface per cache domain:

```kotlin
// UserCache.kt
@CoCache(keyPrefix = "user:", ttl = 300, missingTtl = 30)
@CaffeineCache(maximumSize = 500_000, expireAfterAccess = 300)
interface UserCache : Cache<String, User>

// ProductCache.kt -- defaults: ttl 3600s, ttlAmplitude 60s, missingTtl 60s, L2 maximumSize 10000
@CoCache(keyPrefix = "product:")
interface ProductCache : Cache<String, Product>
```

TTL guidance (all in seconds):

- `ttl` caps how long a missed invalidation can survive (cache-aside races are only repaired by expiry). Keep it finite; `TtlAt.FOREVER` disables expiry.
- `ttlAmplitude` spreads expirations to avoid avalanches (about 2–10 % of `ttl`).
- `missingTtl` caps how long a newly created row stays invisible after a "not found" was cached. Keep it short.
- L2 entries expire at their own `ttlAt`; `@CaffeineCache` only bounds size and idle time.

```kotlin
```

### Step 3: Register Caches

```kotlin
@SpringBootApplication
@EnableCoCache(caches = [UserCache::class, ProductCache::class])
class MyApp
```

### Step 4: Provide CacheSource (Data Loaders)

```kotlin
@Configuration
class CacheSourceConfig {

    // null → negative cache for missingTtl. CacheValue.forever(...) means "no expiry from the source";
    // return CacheValue.of(value, TtlAt.at(seconds)) to give the entry its own TTL instead.
    @Bean("UserCache.CacheSource")
    fun userCacheSource(userRepository: UserRepository): CacheSource<String, User> {
        return CacheSource { key -> userRepository.findById(key).orElse(null)?.let { CacheValue.of(it, TtlAt.at(300)) } }
    }

    // A CacheSource may also be matched by its generic type when it is the only CacheSource<String, Product> bean
    @Bean
    fun productCacheSource(productRepository: ProductRepository): CacheSource<String, Product> {
        return CacheSource { key -> productRepository.findById(key).orElse(null)?.let { CacheValue.of(it, TtlAt.at(600)) } }
    }
}
```

### Step 5: Inject and Use

```kotlin
@Service
class UserService(
    @Qualifier("userCache") private val userCache: UserCache
) {
    fun getUser(id: String): User? = userCache[id]

    fun saveUser(user: User) {
        userRepository.save(user)   // update the source first
        userCache.evict(user.id)    // then evict (or set the committed value)
    }

    fun deleteUser(id: String) {
        userCache.evict(id)
    }
}
```

## Redis Failure Behavior

`RedisDistributedCache` catches only `DataAccessException` (never broader types) and degrades by default:

- **Read failure** → logged as WARN, then returns `null` (cache miss), so the coherent cache reloads from the `CacheSource`.
- **Write/evict failure** → logged as WARN and swallowed.

Set `cocache.redis.strict-failure: true` to rethrow the exception instead of degrading:

```yaml
cocache:
  redis:
    strict-failure: true
```

This property reaches only the caches the auto-configuration creates. If you define your own `DistributedCache` bean, you own its failure policy.

Corrupted payloads self-heal: when the codec cannot decode a stored value, the read is treated as a miss, and the reload writes a fresh value over the bad bytes. Reads never delete keys, because a delete could remove a valid value that another instance wrote concurrently.

### Custom Missing-Guard Sentinel

Negative cache entries are stored in Redis under a sentinel value (default `"_nil_"`). If that string can collide with a legitimate serialized payload, override it:

```yaml
cocache:
  redis:
    missing-guard-sentinel: "__my_missing__"
```

The default and a custom sentinel do not recognize each other — a custom value must be switched across the whole cluster at once (old instances would read the new sentinel as a real value during a rolling upgrade), must not be blank, and must never equal any legitimate payload. The sentinel only governs the bytes written to and recognized from Redis. In-process, negative entries are the explicit `MissingValue` type, so a business value like `"_nil_"` is never misread as missing outside Redis.

## Without Spring Boot

For plain Spring projects, use `cocache-spring` directly:

```kotlin
@Configuration
@EnableCoCache(caches = [UserCache::class])
class CacheConfig {

    @Bean("UserCache.DistributedCache")
    fun distributedCache(
        redisTemplate: StringRedisTemplate,
        objectMapper: ObjectMapper
    ): DistributedCache<User> {
        val codecExecutor = ObjectToJsonCodecExecutor<User>(
            User::class.java,
            redisTemplate,
            objectMapper
        )
        return RedisDistributedCache(redisTemplate, codecExecutor)
    }

    // stateful components are matched by name: {cacheName}.ClientSideCache
    @Bean("UserCache.ClientSideCache")
    fun clientSideCache(): ClientSideCache<User> {
        return CaffeineClientSideCache.build(maximumSize = 100_000, expireAfterAccess = Duration.ofMinutes(30))
    }

    @Bean
    fun redisMessageListenerContainer(
        redisConnectionFactory: RedisConnectionFactory
    ): RedisMessageListenerContainer {
        return RedisMessageListenerContainer().apply {
            setConnectionFactory(redisConnectionFactory)
        }
    }

    @Bean
    fun cacheEvictedEventBus(
        redisTemplate: StringRedisTemplate,
        redisMessageListenerContainer: RedisMessageListenerContainer
    ): CacheEvictedEventBus {
        return RedisCacheEvictedEventBus(redisTemplate, redisMessageListenerContainer)
    }
}
```

## Single-Instance Setup (No Redis)

For development or single-instance deployments, you can skip Redis:

```kotlin
@SpringBootApplication
@EnableCoCache(caches = [UserCache::class])
class MyApp {

    @Bean
    fun cacheEvictedEventBus(): CacheEvictedEventBus {
        return LocalCacheEvictedEventBus()  // in-process only
    }

    @Bean("UserCache.DistributedCache")
    fun distributedCache(): DistributedCache<User> {
        return InMemoryDistributedCache()  // in-memory L1 for development/testing
    }
}
```

Note: This loses cross-instance coherence. Only use for development/testing.

## Spring Cache Bridge

`cocache-spring-cache` integrates CoCache with Spring's `@Cacheable` abstraction. When `@EnableCaching` is present, CoCache can auto-configure `CoCacheManager`:

```kotlin
@Cacheable(cacheNames = ["userCache"])
fun getUser(id: String): User? {
    return userRepository.findById(id).orElse(null)
}
```

The call routes through CoCache's two-level cache.

## Actuator Endpoints

When Spring Actuator is on the classpath:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/actuator/cocache` | GET | List all coherent caches |
| `/actuator/cocache/{name}` | GET | Cache stats |
| `/actuator/cocache/{name}/{key}` | GET | Get a cache entry |
| `/actuator/cocache/{name}/{key}` | DELETE | Evict a cache entry |
| `/actuator/cocacheClient/{name}` | GET | Client-side cache size |
| `/actuator/cocacheClient/{name}/{key}` | GET | Get a client-side cache entry |
| `/actuator/cocacheClient/{name}` | DELETE | Clear a client-side cache |

## Disabling CoCache

```yaml
cocache:
  enabled: false
```

This prevents all CoCache auto-configuration. Useful for test profiles.
