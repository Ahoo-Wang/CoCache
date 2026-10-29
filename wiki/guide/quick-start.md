---
title: Quick Start
description: Add a two-level coherent cache to a Spring Boot application in five minutes, check that it works, and create one without Spring.
---

# Quick Start

This page takes a Spring Boot application from no cache to a working two-level cache. The code is based on [`cocache-example`](https://github.com/Ahoo-Wang/CoCache/tree/main/cocache-example).

**You need** JDK 17+, Spring Boot 4, and either Redis 7+ or nothing at all (the in-memory backend, step 2).

## 1. Add the dependencies {#dependencies}

::: code-group

```kotlin [Gradle (Kotlin)]
dependencies {
    implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.8.0")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator") // optional: the cocache endpoint
}
```

```groovy [Gradle (Groovy)]
dependencies {
    implementation 'me.ahoo.cocache:cocache-spring-boot-starter:6.8.0'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-actuator' // optional: the cocache endpoint
}
```

```xml [Maven]
<dependency>
    <groupId>me.ahoo.cocache</groupId>
    <artifactId>cocache-spring-boot-starter</artifactId>
    <version>6.8.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<!-- optional: the cocache endpoint -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

:::

The BOM `me.ahoo.cocache:cocache-bom` manages the versions of all CoCache modules if you use more than the starter, for example the optional `me.ahoo.cocache:cocache-coroutines` for [Kotlin coroutines](./coroutines.md) (6.4+).

## 2. Point it at Redis {#redis}

CoCache reads Spring Boot's Redis connection settings and opens **its own** Lettuce connections. It never touches your application's connection factory.

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

No Redis on your laptop? Use the in-memory backend instead:

```yaml
cocache:
  backend: memory   # single process only: for local development and tests
```

## 3. Declare a cache {#declare}

A cache is an interface that extends `Cache<K, V>`. List it in `@EnableCoCache`:

::: code-group

```kotlin [Kotlin]
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.spring.boot.EnableCoCache
import org.springframework.boot.autoconfigure.SpringBootApplication

data class User(val id: String, val name: String)

@CoCache(ttl = 600)                  // seconds in Redis; everything else uses defaults
interface UserCache : Cache<String, User>

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App
```

```java [Java]
// User.java
public record User(String id, String name) {}

// UserCache.java
import me.ahoo.cocache.api.Cache;
import me.ahoo.cocache.api.annotation.CoCache;

@CoCache(ttl = 600)
public interface UserCache extends Cache<String, User> {}

// App.java
import me.ahoo.cocache.spring.boot.EnableCoCache;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableCoCache(caches = UserCache.class)
@SpringBootApplication
public class App {}
```

:::

The cache is named after the interface (`UserCache`), and so is the bean. Values go to Redis under `cocache:UserCache:{id}`, encoded as JSON with your application's Jackson `ObjectMapper`.

## 4. Give it a loader {#loader}

On a miss in both levels, CoCache calls the cache's `CacheLoader`. Declare one as a bean; it is matched by its generic type:

::: code-group

```kotlin [Kotlin]
import me.ahoo.cocache.api.CacheLoader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CacheConfig {
    @Bean
    fun userLoader(repo: UserRepository): CacheLoader<String, User> =
        CacheLoader { id -> repo.findById(id) }   // null = not found, cached as a negative entry
}
```

```java [Java]
import me.ahoo.cocache.api.CacheLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CacheConfig {
    @Bean
    CacheLoader<String, User> userLoader(UserRepository repo) {
        return id -> repo.findById(id).orElse(null);   // null = not found
    }
}
```

:::

## 5. Read, write, invalidate {#use}

Inject the interface like any other bean:

```kotlin
@Service
class UserService(
    private val userCache: UserCache,
    private val repo: UserRepository,
    private val invalidator: TransactionalInvalidator,
) {
    fun find(id: String): User? = userCache[id]       // L2 → Redis → loader

    @Transactional
    fun rename(id: String, name: String) {
        repo.rename(id, name)                         // 1. change the source of truth
        invalidator.invalidate(userCache, id)         // 2. invalidate after the commit
    }
}
```

`TransactionalInvalidator` runs the invalidation after the transaction commits. It also runs after a rollback, because a read inside the transaction may have cached the uncommitted row. Invalidating before the commit would let a concurrent read reload the old row. Outside a transaction it invalidates immediately. [Keeping Data Fresh](./freshness.md) explains the rule and the alternatives.

## 6. Check that it works {#check}

Expose the actuator endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: cocache
```

Call `find("42")` twice, then:

```bash
curl localhost:8080/actuator/cocache/UserCache        # localSize, loads, counters
curl localhost:8080/actuator/cocache/UserCache/42     # this key in L2 and in Redis
```

The first read missed and loaded once (`loads: 1`). The second was an L2 hit. The key view shows the local copy with its expiry and the Redis copy with its remaining TTL, without loading anything. Run a second instance against the same Redis, call `rename` on one, and the other returns the new name on its next read.

## Without Spring {#without-spring}

Add `cocache-core`, `cocache-redis`, and `cocache-codec-jackson` (same version). Create one `CacheRuntime` per process and create caches from specs:

::: code-group

```kotlin [Kotlin]
import io.lettuce.core.RedisURI
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.codec.jackson.jacksonCodec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.redis.RedisBackend
import tools.jackson.databind.json.JsonMapper   // Jackson 3
import java.time.Duration

val mapper = JsonMapper.builder().findAndAddModules().build()
val backend = RedisBackend.standalone(RedisURI.create("redis://localhost:6379"))
val runtime = CacheRuntime.builder().backend(backend).build()

val users: Cache<String, User> = runtime.create(
    cacheSpec<String, User>("UserCache") {
        codec = jacksonCodec<User>(mapper)
        loader = CacheLoader { id -> repo.findById(id) }
        policy { ttl = Duration.ofMinutes(10) }
    }
)

// on shutdown: caches first, then the backend
runtime.close()
backend.close()
```

```java [Java]
import io.lettuce.core.RedisURI;
import java.time.Duration;
import me.ahoo.cocache.api.Cache;
import me.ahoo.cocache.codec.jackson.JacksonCodec;
import me.ahoo.cocache.core.CacheRuntime;
import me.ahoo.cocache.core.CacheSpec;
import me.ahoo.cocache.redis.RedisBackend;
import tools.jackson.databind.ObjectMapper;        // Jackson 3
import tools.jackson.databind.json.JsonMapper;

ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
RedisBackend backend = RedisBackend.standalone(RedisURI.create("redis://localhost:6379"));
CacheRuntime runtime = CacheRuntime.builder().backend(backend).build();

Cache<String, User> users = runtime.create(
    CacheSpec.<String, User>builder("UserCache")
        .codec(JacksonCodec.of(mapper, User.class))
        .loader(id -> repo.findById(id).orElse(null))
        .updatePolicy(policy -> policy.ttl(Duration.ofMinutes(10)))
        .build()
);

runtime.close();
backend.close();
```

:::

## Next steps {#next}

- [How It Works](./concepts.md): the mental model behind reads, writes, and staleness.
- [Declaring Caches](./caches.md): keys, loaders, codecs, spec beans, and set caches.
- [Configuration Reference](./configuration.md): every `cocache.*` property.
