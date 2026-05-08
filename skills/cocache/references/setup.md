# CoCache Project Setup Guide

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
- `MapClientSideCache` as L2
- `RedisCacheEvictedEventBus` for cross-instance coherence
- `ToStringKeyConverter` with the `keyPrefix`
- `CacheSource.noOp()` (returns null, you provide your own)

## Step-by-Step Setup

### Step 1: Add Dependencies

```kotlin
// build.gradle.kts
plugins {
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"
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
@CoCache(keyPrefix = "user:", ttl = 300)
@GuavaCache(maximumSize = 500_000, expireAfterAccess = 300, expireUnit = TimeUnit.SECONDS)
interface UserCache : Cache<String, User>

// ProductCache.kt
@CoCache(keyPrefix = "product:", ttl = 600)
@CaffeineCache(maximumSize = 1_000_000, expireAfterWrite = 600, expireUnit = TimeUnit.SECONDS)
interface ProductCache : Cache<String, Product>
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

    @Bean("UserCache.CacheSource")
    fun userCacheSource(userRepository: UserRepository): CacheSource<String, User> {
        return CacheSource { key ->
            userRepository.findById(key).orElse(null)?.let {
                DefaultCacheValue(it)
            }
        }
    }

    @Bean("ProductCache.CacheSource")
    fun productCacheSource(productRepository: ProductRepository): CacheSource<String, Product> {
        return CacheSource { key ->
            productRepository.findById(key).orElse(null)?.let {
                DefaultCacheValue(it)
            }
        }
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
        userCache[user.id] = user
    }

    fun deleteUser(id: String) {
        userCache.evict(id)
    }
}
```

## Without Spring Boot

For plain Spring projects, use `cocache-spring` directly:

```kotlin
@Configuration
@EnableCoCache(caches = [UserCache::class])
class CacheConfig {

    @Bean
    fun distributedCache(): DistributedCache<String> {
        return RedisDistributedCache("user", redisTemplate)
    }

    @Bean
    fun clientSideCache(): ClientSideCache<User> {
        return GuavaClientSideCache(maximumSize = 100_000)
    }

    @Bean
    fun cacheEvictedEventBus(): CacheEvictedEventBus {
        return RedisCacheEvictedEventBus(redisTemplate)
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
        return GuavaCacheEvictedEventBus()  // in-process only
    }

    @Bean
    fun distributedCache(): DistributedCache<User> {
        return MapClientSideCache<User>()  // use local map as "distributed" cache
    }
}
```

Note: This loses cross-instance coherence. Only use for development/testing.

## Disabling CoCache

```yaml
cocache:
  enabled: false
```

This prevents all CoCache auto-configuration. Useful for test profiles.
