---
title: Testing
description: Test applications that use CoCache without Redis — the in-memory backend in @SpringBootTest, ManualClock to move time, plain unit tests with CacheRuntime, and simulated Redis failures with HookedBackend.
---

# Testing

CoCache ships everything you need to test cached code without Redis and without `sleep`: an in-memory backend that implements the full protocol, a clock you move by hand, and a backend wrapper that injects Redis failures.

## `@SpringBootTest` without Redis {#spring-boot}

Switch the backend with one property:

```kotlin
@SpringBootTest(properties = ["cocache.backend=memory"])
class UserServiceTest {
    @Autowired lateinit var users: UserService
    @MockitoBean lateinit var repo: UserRepository

    @Test
    fun secondReadIsCached() {
        given(repo.findById("42")).willReturn(User("42", "Ada"))

        users.find("42")
        users.find("42")

        then(repo).should(times(1)).findById("42")
    }
}
```

- CoCache doesn't connect to Redis, even if Spring Data Redis is configured. The starter logs a `WARN` saying the backend is single-process. Spring Boot's own Redis health indicator still connects when Spring Data Redis and the actuator are on the classpath; turn it off with `management.health.redis.enabled=false`.
- Caches, loaders, `TransactionalInvalidator`, Spring Cache, and the actuator endpoint all work as in production.
- The in-memory "Redis" lives as long as the Spring context, and Spring reuses contexts across test classes. Invalidate the keys a test touches (or use `@DirtiesContext`) so one test's entries don't leak into the next.

The in-memory backend is for tests and local development only. Each process has its own copy, so instances never see each other's writes.

## Moving time with `ManualClock` {#manual-clock}

Declare a `ManualClock` bean. The runtime and the in-memory backend both use it, so advancing it expires L2 and "Redis" together. `UserCache` here is the [Quick Start](./quick-start.md#declare) declaration, `@CoCache(ttl = 600)`:

```kotlin
@SpringBootTest(properties = ["cocache.backend=memory"])
class ExpiryTest {
    @TestConfiguration
    class Clock {
        @Bean
        fun manualClock() = ManualClock()
    }

    @Autowired lateinit var clock: ManualClock
    @Autowired lateinit var userCache: UserCache
    @MockitoBean lateinit var repo: UserRepository

    @Test
    fun reloadsAfterTtl() {
        given(repo.findById("42")).willReturn(User("42", "Ada"))
        userCache["42"]

        clock.advance(Duration.ofMinutes(6))     // past local-max-age (5m): read back from L1
        userCache["42"]
        then(repo).should(times(1)).findById("42")

        clock.advance(Duration.ofMinutes(10))    // past ttl (600 s ± 60 s): reload from the source
        userCache["42"]
        then(repo).should(times(2)).findById("42")
    }
}
```

`ManualClock` drives every lifetime CoCache computes: `ttl`, `absent-ttl`, `local-max-age`, and `refresh-window`. It doesn't drive Caffeine's own `expireAfterAccess`, nor the background retry of failed invalidations, which run on real time. `ManualClock.advance` takes whole milliseconds, the clock's precision: a sub-millisecond duration such as `Duration.ofNanos(500)` throws `IllegalArgumentException` instead of being dropped.

## Plain unit tests {#unit}

Without Spring, build a runtime on an `InMemoryBackend`. Two runtimes sharing one backend behave like two instances sharing one Redis, which lets you test cross-instance behavior in a single JVM. Pass the `ManualClock` once, to the backend: a runtime built without its own clock adopts the one its `InMemoryBackend` was built with (`InMemoryBackend.clock`), so L1 and L2 expire on the same time.

```kotlin
class UserCacheTest {
    private val clock = ManualClock()
    private val backend = InMemoryBackend(clock)
    private val db = ConcurrentHashMap(mapOf("42" to "Ada"))
    private val loads = AtomicInteger()
    private val runtimes = mutableListOf<CacheRuntime>()

    private fun instance(): Cache<String, String> {
        val runtime = CacheRuntime.builder().backend(backend).build().also { runtimes += it }   // runtime.clock === clock
        return runtime.create(
            cacheSpec<String, String>("UserCache") {
                codec = StringCodec
                loader = CacheLoader { id -> loads.incrementAndGet(); db[id] }
            }
        )
    }

    @AfterEach
    fun close() = runtimes.forEach { it.close() }

    @Test
    fun invalidationReachesOtherInstances() {
        val a = instance()
        val b = instance()
        assertEquals("Ada", a["42"])
        assertEquals("Ada", b["42"])            // served from the shared L1
        assertEquals(1, loads.get())

        db["42"] = "Grace"
        a.invalidate("42")                      // in-memory delivery is synchronous

        assertEquals("Grace", b["42"])
    }
}
```

`runtime.metrics("UserCache")` gives the counters (loads, L1 hits, degraded fills, and so on) if you want to assert on them.

## Simulating Redis failures {#faults}

`HookedBackend`, from the `me.ahoo.cocache:cocache-test` artifact (add it as a test dependency), wraps any backend and makes its operations fail on demand:

| Switch | Fails |
|--------|-------|
| `failAll` | Every operation on the L1 store (the invalidation channel keeps working) |
| `failReads` | Reads only: single-key, batch and conditional reads. Writes keep working, so a test can check how reads degrade (`read-failure`, `unknownRead`) while writers carry on |
| `failWrites` | Redis writes: `put`, `putAll`, `invalidate`, set member writes, conditional and versioned writes. The outcome is unknown, as after a timeout, so a `Cache.put` is compensated instead of throwing |
| `rejectWrites` | The same writes, rejected with `L1RejectedException` before they reach the wrapped backend: the write is known not to have happened, so `CacheWriteException.outcomeUnknown` is `false` |
| `failFill` | Writing loaded values to Redis |
| `failRelease` | Releasing a lease |

A runtime built on `HookedBackend(InMemoryBackend(clock))` adopts the wrapped backend's clock too, so the clock is passed once here as well:

```kotlin
val hooked = HookedBackend(InMemoryBackend(clock))
val runtime = CacheRuntime.builder().backend(hooked).build()   // runtime.clock === clock
val users = runtime.create(cacheSpec<String, String>("UserCache") {
    codec = StringCodec
    loader = CacheLoader { id -> db[id] }
})

hooked.failAll = true
assertEquals("Ada", users["42"])                         // degrades: loaded from the source
users.invalidate("42")                                   // doesn't throw: queued for retry
users.put("43", "x")                                     // outcome unknown: compensated, doesn't throw
assertEquals(2, runtime.admin("UserCache")!!.pendingInvalidationCount)

hooked.failAll = false
hooked.rejectWrites = true
assertThrows<CacheWriteException> { users.put("42", "x") } // definitely not executed: throws
hooked.rejectWrites = false
// the first retry runs after about 100 ms of real time: poll until the count drops to 0
```

Its hooks (`afterRead`, `afterPut`, `beforeInvalidate`, …) receive full storage keys, `{keyPrefix}{namespace}:{encodedKey}` (`Keyspace.storageKey`), not encoded keys.

`HookedBackend` forwards `CoherenceBackend.unwrap`, so whatever looks for a concrete backend sees through it: the runtime finds the in-memory backend's clock, and around a `RedisBackend` the [eviction check](./configuration.md#eviction-check), the close timeout, the Redis health details and the `cocache.redis.releases.abandoned` meter work as they do without the wrapper.

A test double of a backend must keep the same contract: `unwrap(type)` returns the backend itself when it is an instance of `type`, otherwise `null` (a wrapper asks the backend it wraps). A MockK mock replaces that default method too, so stub it. Otherwise building a runtime on the mock fails, a relaxed mock with `ClassCastException`:

```kotlin
val redis = mockk<RedisBackend>(relaxed = true)
every { redis.unwrap(any<Class<Any>>()) } answers { firstArg<Class<*>>().takeIf { it.isInstance(self) }?.let { self } }
```

In a Spring Boot test, declare the wrapper as your `CoherenceBackend` bean; a declared backend takes precedence over `cocache.backend`:

```kotlin
@Bean
fun backend(clock: ManualClock): CoherenceBackend = HookedBackend(InMemoryBackend(clock))
```

[How It Works](./concepts.md#failures) lists the behavior these tests should expect.

## Testing a backend of your own {#tck}

If you implement `CoherenceBackend` for a store other than Redis, `cocache-test` is also the compatibility kit: extend `BackendProtocolSpec`, `EngineSpec` (`Cache` and `Store`), `VersionedSpec` (`VersionedCache` and `VersionedStore`), `SetEngineSpec` (`SetStore`), `MultiNodeSpec`, and `FaultSpec`. Each declares `expectedCapabilities`, the optional surfaces your store provides (`CONDITIONAL`, `VERSIONED`, `SETS`), and fails when the store provides a different set. In every spec except `BackendProtocolSpec`, override `access` with `CacheAccess.ASYNC` to rerun the same cases through the [async view](./async.md). See [Extending CoCache](/architecture/extending#tck).
