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

`ManualClock` drives every lifetime CoCache computes: `ttl`, `absent-ttl`, `local-max-age`, and `refresh-window`. It doesn't drive Caffeine's own `expireAfterAccess`, nor the background retry of failed invalidations, which run on real time.

## Plain unit tests {#unit}

Without Spring, build a runtime on an `InMemoryBackend`. Two runtimes sharing one backend behave like two instances sharing one Redis, which lets you test cross-instance behavior in a single JVM:

```kotlin
class UserCacheTest {
    private val clock = ManualClock()
    private val backend = InMemoryBackend(clock)
    private val db = ConcurrentHashMap(mapOf("42" to "Ada"))
    private val loads = AtomicInteger()
    private val runtimes = mutableListOf<CacheRuntime>()

    private fun instance(): Cache<String, String> {
        val runtime = CacheRuntime.builder().backend(backend).clock(clock).build().also { runtimes += it }
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
| `failAll` | Every Redis operation |
| `failWrites` | `put`, `invalidate`, and set element writes |
| `failFill` | Writing loaded values to Redis |
| `failRelease` | Releasing a lease |

```kotlin
val hooked = HookedBackend(InMemoryBackend(clock))
val runtime = CacheRuntime.builder().backend(hooked).clock(clock).build()
val users = runtime.create(cacheSpec<String, String>("UserCache") {
    codec = StringCodec
    loader = CacheLoader { id -> db[id] }
})

hooked.failAll = true
assertEquals("Ada", users["42"])                         // degrades: loaded from the source
assertThrows<CacheWriteException> { users.put("42", "x") }
users.invalidate("42")                                   // doesn't throw: queued for retry
assertEquals(1, runtime.admin("UserCache")!!.pendingInvalidationCount)

hooked.failAll = false
// the first retry runs after about 100 ms of real time: poll until the count drops to 0
```

In a Spring Boot test, declare the wrapper as your `CoherenceBackend` bean; a declared backend takes precedence over `cocache.backend`:

```kotlin
@Bean
fun backend(clock: ManualClock): CoherenceBackend = HookedBackend(InMemoryBackend(clock))
```

[How It Works](./concepts.md#failures) lists the behavior these tests should expect.

## Testing a backend of your own {#tck}

If you implement `CoherenceBackend` for a store other than Redis, `cocache-test` is also the compatibility kit: extend `BackendProtocolSpec`, `EngineSpec`, `SetEngineSpec`, `MultiNodeSpec`, and `FaultSpec`. See [Extending CoCache](/architecture/extending).
