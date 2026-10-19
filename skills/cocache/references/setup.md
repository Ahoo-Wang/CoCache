# CoCache Project Setup Guide (6.x)

## Contents

- [Dependencies](#dependencies)
- [Module Selection](#module-selection)
- [Spring Boot Setup](#spring-boot-setup)
- [Declaring Caches](#declaring-caches)
- [Policy and Configuration](#policy-and-configuration)
- [Transactions and Writes](#transactions-and-writes)
- [Redis Backend](#redis-backend)
- [Failure Behavior](#failure-behavior)
- [Spring Cache Bridge](#spring-cache-bridge)
- [Metrics and Actuator](#metrics-and-actuator)
- [Without Spring Boot](#without-spring-boot)
- [Single Instance or Tests (No Redis)](#single-instance-or-tests-no-redis)
- [Disabling CoCache](#disabling-cocache)

## Dependencies

```kotlin
dependencies {
    implementation(platform("me.ahoo.cocache:cocache-bom:${cocacheVersion}"))
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-redis") // supplies the Redis connection details
}
```

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

Requirements: JDK 17+, Spring Boot 4, Redis 7+. CoCache builds its own Lettuce client from Spring Boot's Redis settings; the application may keep using Jedis or Lettuce for its own Redis access.

## Module Selection

| Module | When to use |
|--------|-------------|
| `cocache-spring-boot-starter` | Spring Boot (recommended): auto-configuration, `@EnableCoCache`, properties, actuator, Micrometer |
| `cocache-spring` | Spring without Boot: `CoCacheSpringCacheManager`, `TransactionalInvalidator` |
| `cocache-redis` | Redis backend (Lettuce, no Spring) |
| `cocache-codec-jackson` | Jackson 3 (`tools.jackson`) codec |
| `cocache-core` | Runtime and engines, `InMemoryBackend`, no Spring |
| `cocache-api` | Interfaces and SPI only (library authors) |
| `cocache-test` | TCK for custom backends (test scope) |

## Spring Boot Setup

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

Standalone, Sentinel, and Cluster (`spring.data.redis.cluster.nodes`) are supported, including username, password, and SSL bundles. Static master-replica topologies are not supported. CoCache always talks to primaries.

```kotlin
@SpringBootApplication
@EnableCoCache(caches = [UserCache::class])   // only needed for interface-style caches
class App
```

## Declaring Caches

### Interface style

```kotlin
@CoCache(
    ttl = 120,                 // seconds; CoCache.FOREVER = never expires
    absentTtl = 30,            // negative entries
    localMaxAge = 60,          // max age of an L2 copy
    refreshWindow = 20,        // refresh ahead in the last 20 s; 0 = off
    keyExpression = "#{id}",   // SpEL template over the key; default toString()
)
@CaffeineCache(maximumSize = 100_000, expireAfterAccess = 600)
interface UserCache : Cache<String, User> {
    fun nameOf(id: String): String? = get(id)?.name   // default methods are allowed
}
```

- Bean name = cache name = `@CoCache(name)`, default the interface's simple name. Type = the interface.
- Other attributes: `namespace` (default the cache name), `keyPrefix` (default `cocache:`), `ttlJitter`, `loader`, `codec`.
- Loader: `@CoCache(loader = "beanName")`, else the unique `CacheLoader<K, V>` bean (several candidates fail startup), else no loader.
- Codec: `@CoCache(codec = "beanName")`, else the unique `Codec<V>` bean, else Jackson for the value type using the application's `ObjectMapper`.
- Redis key: `{keyPrefix}{namespace}:{key}`, for example `cocache:UserCache:42`.

### Spec bean

```kotlin
@Bean
fun userSpec(repo: UserRepository, mapper: ObjectMapper): CacheSpec<String, User> = cacheSpec("user") {
    codec = jacksonCodec<User>(mapper)
    loader = CacheLoader { id -> repo.findById(id) }      // override loadAll for a batch query
    keyEncoder = KeyEncoder { it.lowercase() }
    ttlOf = TtlFunction { _, user -> if (user.vip) Duration.ofHours(6) else null } // null = policy ttl
    policy {
        ttl = Duration.ofMinutes(10)
        local = LocalPolicy(maximumSize = 50_000)
    }
}
```

- The return type must declare its generics (`CacheSpec<String, User>`); raw types fail startup.
- The cache bean is named after the spec bean minus `Spec` (`userSpec` → `user`), otherwise plus `Cache`. Inject it as `Cache<String, User>`.
- `SetCacheSpec<K, E>` (`setCacheSpec("index") { elementCodec = StringCodec }`) registers a `SetCache<K, E>`.

### Customizers

`CacheSpecCustomizer` beans replace 5.x's `{cacheName}.ClientSideCache` bean-name conventions:

```kotlin
@Bean
fun userCacheCustomizer() = object : CacheSpecCustomizer {
    override fun supports(cacheName: String) = cacheName == "UserCache"
    override fun customize(spec: CacheSpec.Builder<*, *>) {
        spec.policy = spec.policy.toBuilder().absentTtl(Duration.ofSeconds(30)).build()
    }
}
```

## Policy and Configuration

Precedence, lowest to highest: built-in defaults → declaration (annotation or spec) → `CacheSpecCustomizer` → `cocache.defaults.*` → `cocache.caches.[name].*`.

```yaml
cocache:
  defaults:
    local-max-age: 120        # bare numbers are seconds; 500ms, 2m, PT2M also work
  caches:
    UserCache:
      ttl: 600
      ttl-jitter: 30
      absent-ttl: 30
      refresh-window: 60      # 0 = off
      lease-ttl: 10
      lease-wait: 500ms
      load-max-concurrent: 32
      local-maximum-size: 200000
      read-failure: throw     # default degrade
    ProductCache:
      ttl-forever: true
  redis:
    channel-mode: pubsub      # or sharded; must match on every instance
    command-timeout: 2000     # bare numbers are milliseconds here
    heartbeat: 5000
    client-name: my-service
```

| Field | Default | Meaning |
|-------|---------|---------|
| `ttl` / `ttl-forever` | 1 h | Value lifetime in L1 |
| `ttl-jitter` | 1 min (at most `ttl / 2`) | Random spread of `ttl` |
| `absent-ttl` | 1 min | Negative-entry lifetime (no jitter) |
| `local-max-age` | 5 min | Max age of an L2 copy; bounds staleness when an invalidation message is lost |
| `degraded-local-max-age` | 10 s | Max age of an L2 copy that was not written to L1 (Redis down, lease wait timed out) |
| `refresh-window` | off | Refresh ahead; must be `< ttl − jitter` and `< local-max-age` |
| `lease-ttl` / `lease-wait` | 10 s / 200 ms | Lease auto-release; how long a miss waits for another node's load |
| `load-max-concurrent` / `load-acquire-timeout` | 0 (unlimited) / 1 s | Loader bulkhead; rejection throws `SourceLoadRejectedException` |
| `local-maximum-size` | 10 000 | L2 capacity |
| `invalidate-failure` | `retry` | `throw` to surface L1 invalidation failures |
| `read-failure` | `degrade` | `throw` to raise `CacheReadException` when L1 reads fail |

Startup fails when `cocache.caches` names an undeclared cache, when a policy is invalid (durations must be positive and at most 100 years), or when a removed 5.x key is used (the error names the replacement).

## Transactions and Writes

```kotlin
@Service
class UserService(
    private val repo: UserRepository,
    private val userCache: UserCache,
    private val invalidator: TransactionalInvalidator,
) {
    @Transactional
    fun rename(id: String, name: String) {
        repo.rename(id, name)
        invalidator.invalidate(userCache, id)   // runs after commit; skipped on rollback
    }
}
```

Outside a transaction, `invalidate` runs immediately. Never call `cache.invalidate` inside an open transaction.

## Redis Backend

- Every `put` / `invalidate` is one Lua round trip that writes and publishes the invalidation.
- `channel-mode: pubsub` (default): classic Pub/Sub, atomic with the write. On deployments that treat `PUBLISH` as `SPUBLISH` (for example ElastiCache Serverless), CoCache detects it at startup and publishes after the write instead.
- `channel-mode: sharded`: `SSUBSCRIBE` / `SPUBLISH` (Redis 7+), published after the write. Re-subscribes automatically after slot migration.
- The subscription connection is pinged every `heartbeat`; any reconnect clears L2.
- L1 values carry a binary header. Do not read them with `redis-cli GET` or from other services.
- An external writer must `DEL` the key and publish an invalidation frame on `cocache:inv:{namespace}` (see the wiki Operations page).

## Failure Behavior

| Operation | When Redis fails |
|-----------|------------------|
| Reads, fills, refresh | Default: load from the source, keep the result in L2 for `degraded-local-max-age`. `read-failure: throw` raises `CacheReadException` |
| `invalidate`, `invalidateAll` | Local copy dropped immediately; L1 retried in the background (8 attempts, exponential backoff). Until it succeeds, misses for that key skip Redis. `invalidate-failure: throw` raises `CacheWriteException` |
| `put`, `SetCache.add` / `remove` / `clear` | Throw `CacheWriteException`; safe to retry (idempotent) |

## Spring Cache Bridge

The starter registers `CoCacheSpringCacheManager` (wrapped in `TransactionAwareCacheManagerProxy` when spring-tx is present) unless you define a `CacheManager`. It exposes caches already declared with CoCache; it never creates caches by name.

```kotlin
@Cacheable(cacheNames = ["UserCache"], sync = true)  // sync = true keeps lease protection
fun find(id: String): User? = repo.findById(id)
```

Negative entries read as misses; `@CachePut` with `null` invalidates; `clear()` only clears the local L2.

## Metrics and Actuator

With Micrometer on the classpath, each cache publishes `cocache.*` meters tagged `cache=<name>`: `cocache.hits{level=l1}`, `cocache.misses`, `cocache.loads` (timer), `cocache.load.failures`, `cocache.lease.waits`, `cocache.degraded.fills`, `cocache.invalidation.retries`, `cocache.invalidations.dropped`, `cocache.resets`, `cocache.local.size`, and more. L2 hits are not counted.

With actuator, expose the `cocache` endpoint:

```yaml
management.endpoints.web.exposure.include: cocache
```

`GET /actuator/cocache` lists caches and counters, `GET /actuator/cocache/{name}` shows one, `DELETE /actuator/cocache/{name}` clears that cache's L2 on this instance.

## Without Spring Boot

```kotlin
val backend = RedisBackend.standalone(RedisURI.create("localhost", 6379))  // or RedisBackend.cluster(uris)
val runtime = CacheRuntime.builder().backend(backend).build()               // one per process

val users: Cache<String, User> = runtime.create(cacheSpec("user") {
    codec = jacksonCodec<User>(JsonMapper.builder().findAndAddModules().build())
    loader = CacheLoader { id -> repository.findById(id) }
})

// shutdown
runtime.close()
backend.close()
```

Java uses the builders: `CacheSpec.<String, User>builder("user").codec(codec).loader(loader).policy(CachePolicy.builder().ttl(Duration.ofMinutes(2)).build()).build()`.

For Spring without Boot, add `cocache-spring` and register `CoCacheSpringCacheManager(runtime)` and `TransactionalInvalidator()` as beans.

## Single Instance or Tests (No Redis)

Declare a `CoherenceBackend` bean and the Redis backend backs off:

```kotlin
@Bean
fun cocacheBackend(): CoherenceBackend = InMemoryBackend()
```

`InMemoryBackend` implements the full protocol in one process. Runtimes that share one instance behave like separate nodes.

## Disabling CoCache

```yaml
cocache:
  enabled: false
```

This turns off the backend, runtime, cache registration, cache manager, endpoint, and metrics. Injection points for CoCache caches then fail, so disable only where nothing injects them.
