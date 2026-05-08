# CoCache Testing Guide

CoCache provides abstract test specs in `cocache-test` that verify cache contracts. Extend the appropriate spec, implement factory methods, and you get comprehensive test coverage for free.

## Test Specs Overview

| Spec | Tests | Use For |
|------|-------|---------|
| `CacheSpec<K,V>` | get, set, evict, TTL, missing guard | Base contract |
| `ClientSideCacheSpec<V>` | all of CacheSpec + clear() | L2 local cache implementations |
| `DistributedCacheSpec<V>` | all of CacheSpec | L1 distributed cache implementations |
| `DefaultCoherentCacheSpec<K,V>` | all of CacheSpec + cache source, event bus, concurrency | Two-level cache implementations |
| `MultipleInstanceSyncSpec<K,V>` | cross-instance coherence | Event bus implementations |
| `CacheEvictedEventBusSpec` | publish, register, unregister | Event bus implementations |

## Testing a ClientSideCache Implementation

```kotlin
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.test.ClientSideCacheSpec

class MyClientSideCacheTest : ClientSideCacheSpec<String>() {

    override fun createCache(): ClientSideCache<String> {
        return MyCustomClientSideCache()
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }
}
```

This automatically tests:
- `get` returns null for missing keys
- `get` returns value after `set`
- `set` with TTL
- `set` with TTL amplitude
- `evict` removes the entry
- `set` with missing guard
- `set` with missing guard TTL
- `clear` removes all entries
- `getWhenExpired` returns null after TTL

## Testing a DistributedCache Implementation

```kotlin
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.test.DistributedCacheSpec

class MyDistributedCacheTest : DistributedCacheSpec<String>() {

    override fun createCache(): DistributedCache<String> {
        return MyCustomDistributedCache()
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }
}
```

## Testing a CoherentCache (Two-Level Cache)

```kotlin
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.core.DefaultCacheValue
import me.ahoo.cache.core.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.test.DefaultCoherentCacheSpec

class MyCoherentCacheTest : DefaultCoherentCacheSpec<String, String>() {

    override fun createCache(): me.ahoo.cache.consistency.CoherentCache<String, String> {
        return DefaultCoherentCache(
            cacheName = "testCache",
            keyConverter = createKeyConverter(),
            clientSideCache = createClientSideCache(),
            distributedCache = createDistributedCache(),
            cacheSource = CacheSource.noOp(),
            cacheEvictedEventBus = createCacheEvictedEventBus()
        )
    }

    override fun createKeyConverter(): me.ahoo.cache.api.KeyConverter<String> {
        return ToStringKeyConverter("test:")
    }

    override fun createClientSideCache(): ClientSideCache<String> {
        return GuavaClientSideCache(maximumSize = 1000)
    }

    override fun createDistributedCache(): DistributedCache<String> {
        return MyDistributedCache()
    }

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus {
        return GuavaCacheEvictedEventBus()
    }

    override fun createCacheName(): String = "testCache"

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }
}
```

This tests:
- All basic cache operations
- Cache source integration (loading from data source)
- Event-driven eviction (local + distributed)
- Self-published events are ignored (no loops)
- Cache name matching for events
- **Concurrency**: Verifies cache stampede prevention (10/100/1000 threads, only 1 CacheSource call)

## Testing CacheEvictedEventBus

```kotlin
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.core.GuavaCacheEvictedEventBus
import me.ahoo.cache.test.CacheEvictedEventBusSpec

class MyEventBusTest : CacheEvictedEventBusSpec() {

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus {
        return GuavaCacheEvictedEventBus()
    }
}
```

Tests:
- `publish` delivers events to subscribers
- `unregister` stops delivery

## Testing Cross-Instance Coherence

```kotlin
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.core.DefaultCacheValue
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.test.MultipleInstanceSyncSpec

class MyMultiInstanceTest : MultipleInstanceSyncSpec<String, String>() {

    override fun createDistributedCache(): DistributedCache<String> {
        return MyDistributedCache()
    }

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus {
        return GuavaCacheEvictedEventBus()
    }

    override fun createCache(): me.ahoo.cache.consistency.CoherentCache<String, String> {
        // Create a CoherentCache instance
        return DefaultCoherentCache(
            cacheName = "testCache",
            keyConverter = ToStringKeyConverter("test:"),
            clientSideCache = GuavaClientSideCache(maximumSize = 1000),
            distributedCache = createDistributedCache(),
            cacheSource = CacheSource.noOp(),
            cacheEvictedEventBus = createCacheEvictedEventBus()
        )
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }
}
```

This creates two CoherentCache instances sharing the same DistributedCache and EventBus, then verifies:
- When instance A sets a value, instance B's local cache is invalidated
- When instance A evicts, instance B's local cache is invalidated

## Testing with Redis (Integration Tests)

For Redis-based tests, use Testcontainers:

```kotlin
@Testcontainers
class RedisDistributedCacheTest : DistributedCacheSpec<String>() {

    companion object {
        @Container
        val redis = GenericContainer(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
    }

    override fun createCache(): DistributedCache<String> {
        val factory = LettuceConnectionFactory(
            RedisStandaloneConfiguration("localhost", redis.firstMappedPort)
        )
        factory.afterPropertiesSet()
        val template = StringRedisTemplate(factory)
        return RedisDistributedCache("test", template)
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }
}
```

## Assertion Style

All tests use `fluent-assert`:

```kotlin
import me.ahoo.test.asserts.assert

// Correct
value.assert().isEqualTo(expected)
result.assert().isNull()
list.assert().hasSize(3)

// Wrong - don't use AssertJ
assertThat(value).isEqualTo(expected)  // DON'T
```

## Writing Custom Test Cases

When you need additional tests beyond the spec:

```kotlin
class MyCacheTest : ClientSideCacheSpec<String>() {

    override fun createCache(): ClientSideCache<String> = MyCache()
    override fun createCacheEntry(): Pair<String, String> = UUID.randomUUID().toString() to "v"

    @Test
    fun `should handle concurrent access`() {
        val cache = createCache()
        val latch = CountDownLatch(100)
        val errors = ConcurrentHashMap.newKeySet<Int>()

        repeat(100) { i ->
            thread {
                try {
                    cache["key_$i"] = DefaultCacheValue("value_$i")
                    cache.getCache("key_$i").assert().isNotNull()
                } catch (e: Exception) {
                    errors.add(i)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        errors.assert().isEmpty()
    }
}
```

## Test Dependencies

Add to your test `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("me.ahoo.cocache:cocache-test")
    testImplementation("me.ahoo.test:fluent-assert-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.mockk:mockk")
}
```
