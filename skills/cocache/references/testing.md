# CoCache Testing Guide

## Contents

- [Test Specs Overview](#test-specs-overview)
- [Testing a ClientSideCache Implementation](#testing-a-clientsidecache-implementation)
- [Testing a DistributedCache Implementation](#testing-a-distributedcache-implementation)
- [Testing a CoherentCache (Two-Level Cache)](#testing-a-coherentcache-two-level-cache)
- [Testing CacheEvictedEventBus](#testing-cacheevictedeventbus)
- [Testing Cross-Instance Coherence](#testing-cross-instance-coherence)
- [Testing with Redis (Integration Tests)](#testing-with-redis-integration-tests)
- [Redis-Style TTL Drift](#redis-style-ttl-drift)
- [Race-Condition Tests](#race-condition-tests)
- [Assertion Style](#assertion-style)
- [Test Dependencies](#test-dependencies)

`cocache-test` provides abstract specs that verify CoCache contracts. Extend the matching spec, implement its factory methods, and the contract tests come with it.

## Test Specs Overview

| Spec | Factory method | Verifies | Use For |
|------|----------------|----------|---------|
| `CacheStoreSpec<V>` | `createCacheStore()` | absent → `null`, forever / `ttlAt` round trip, expired write evicts, negative-cache round trip, evict | Base store contract |
| `ClientSideCacheSpec<V>` | `createCacheStore()` | + `clear()`, `size` | L2 stores |
| `DistributedCacheSpec<V>` | `createCacheStore()` | store contract | L1 stores |
| `CacheSpec<K,V>` | `createCache()` | `get`/`set`/`getTtlAt`/`evict`, negative cache | `Cache` implementations |
| `DefaultCoherentCacheSpec<K,V>` | component factories | read-through, `missingTtl`, fill, events, `onReset`, single load, exception propagation, in-flight invalidation races | Two-level coherence with your L1/L2/bus |
| `MultipleInstanceSyncSpec<K,V>` | component factories | two instances converge after set/evict | L1 + bus end-to-end |
| `CacheEvictedEventBusSpec` | `createCacheEvictedEventBus()` | `register` → `onReset`, routing by cache name, unregister | Event buses |

All specs are in `me.ahoo.cache.test`, except `CacheEvictedEventBusSpec`, which is in `me.ahoo.cache.test.consistency`.

## Testing a ClientSideCache Implementation

```kotlin
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.test.ClientSideCacheSpec

class MyClientSideCacheTest : ClientSideCacheSpec<String>() {
    override fun createCacheStore(): ClientSideCache<String> = MyClientSideCache()
    override fun createCacheEntry(): Pair<String, String> =
        UUID.randomUUID().toString() to UUID.randomUUID().toString()
}
```

## Testing a DistributedCache Implementation

```kotlin
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.test.DistributedCacheSpec

class MyDistributedCacheTest : DistributedCacheSpec<String>() {
    override fun createCacheStore(): DistributedCache<String> = MyDistributedCache()
    override fun createCacheEntry(): Pair<String, String> =
        UUID.randomUUID().toString() to UUID.randomUUID().toString()
}
```

## Testing a CoherentCache (Two-Level Cache)

```kotlin
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.converter.KeyConverter
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.LocalCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.cache.test.DefaultCoherentCacheSpec

class MyCoherentCacheTest : DefaultCoherentCacheSpec<String, String>() {
    override fun createKeyConverter(): KeyConverter<String> = ToStringKeyConverter("test:")
    override fun createClientSideCache(): ClientSideCache<String> = MapClientSideCache()
    override fun createDistributedCache(): DistributedCache<String> = InMemoryDistributedCache()
    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = LocalCacheEvictedEventBus()
    override fun createCacheName(): String = "testCache"
    override fun createCacheEntry(): Pair<String, String> =
        UUID.randomUUID().toString() to UUID.randomUUID().toString()
}
```

The spec drives its `CacheSource` through the protected `loader` and counts calls in `sourceCalls`, so you can add your own scenarios.

## Testing CacheEvictedEventBus

```kotlin
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.test.consistency.CacheEvictedEventBusSpec

class MyEventBusTest : CacheEvictedEventBusSpec() {
    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = MyEventBus()
}
```

The spec requires `register` to trigger `onReset`. Your bus must call it whenever a subscription is (re)established.

## Testing Cross-Instance Coherence

```kotlin
class MyMultiInstanceTest : MultipleInstanceSyncSpec<String, String>() {
    override fun createKeyConverter(): KeyConverter<String> = ToStringKeyConverter("test:")
    override fun createClientSideCache(): ClientSideCache<String> = MapClientSideCache()
    override fun createDistributedCache(): DistributedCache<String> = InMemoryDistributedCache()
    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = LocalCacheEvictedEventBus()
    override fun createCacheName(): String = "testCache"
    override fun createCacheEntry(): Pair<String, String> =
        UUID.randomUUID().toString() to UUID.randomUUID().toString()
}
```

Two `CoherentCache` instances share one L1 and one bus. The spec waits, with a timeout, until each set/evict on one instance has invalidated the other instance's L2.

## Testing with Redis (Integration Tests)

Redis tests need Redis at `localhost:6379`. If the shell exports `SPRING_DATA_REDIS_*` cluster variables, unset them for local runs.

Asynchronous channels deliver `onReset` after subscription, and a late reset can wipe L2 in the middle of a test. Give the `RedisMessageListenerContainer` a `SyncTaskExecutor` in tests; `register()` then returns only after the reset ran:

```kotlin
val listenerContainer = RedisMessageListenerContainer().apply {
    setConnectionFactory(connectionFactory)
    setTaskExecutor(SyncTaskExecutor())
    afterPropertiesSet()
    start()
}
```

Lifecycle overrides must repeat the JUnit annotation, or JUnit will not run them:

```kotlin
class RedisCoherentCacheTest : DefaultCoherentCacheSpec<String, String>() {
    @BeforeEach
    override fun setup() { redis = RedisTestSupport(); super.setup() }

    @AfterEach
    override fun tearDown() { super.tearDown(); redis.close() }
    // ...
}
```

## Redis-Style TTL Drift

Stores that rebuild `ttlAt` from the server's remaining expiry can drift by ±1 s across a second boundary. `CacheStoreSpec.setWithTtlAt` and `setMissingWithTtlAt` are `open`; override them with a tolerance:

```kotlin
@Test
override fun setWithTtlAt() {
    val (key, value) = createCacheEntry()
    val cacheValue = CacheValue.of(value, TtlAt.at(10))
    cacheStore.setCache(key, cacheValue)
    val actual = requireNotNull(cacheStore.getCache(key))
    actual.value.assert().isEqualTo(value)
    actual.ttlAt.assert().isCloseTo(cacheValue.ttlAt, Offset.offset(1))
}
```

`Offset` (`org.assertj.core.data.Offset`) is the one allowed AssertJ import, as an argument only.

## Race-Condition Tests

- Orchestrate interleavings with latches, for example by blocking the `CacheSource` until the test releases it. Never use sleeps to create an interleaving.
- Wait on a `finished` latch so a dead background thread fails the test instead of passing vacuously.
- Assert cross-instance (eventual) effects by polling with a timeout.
- Every fixed defect gets a reproducing test.

```kotlin
loader = {
    loadStarted.countDown()
    releaseLoad.await(5, TimeUnit.SECONDS)
    CacheValue.forever(staleValue)
}
// start loader thread → await loadStarted → coherentCache.evict(key) → releaseLoad.countDown()
// → await finished → assert L1 and L2 do not hold the stale value
```

## Assertion Style

```kotlin
import me.ahoo.test.asserts.assert

value.assert().isEqualTo(expected)
requireNotNull(cache.getCache(key)).isMissing.assert().isTrue()
runCatching { cache[key] }.exceptionOrNull().assert().isSameAs(failure)

assertThat(value).isEqualTo(expected)  // DON'T
```

## Test Dependencies

```kotlin
dependencies {
    testImplementation("me.ahoo.cocache:cocache-test")
    testImplementation("io.mockk:mockk")
}
```
