# CoCache Testing Guide (6.x)

## Contents

- [Testing Application Code](#testing-application-code)
- [Spring Boot Tests](#spring-boot-tests)
- [Simulating Several Instances](#simulating-several-instances)
- [TCK for Custom Backends](#tck-for-custom-backends)
- [Fault Injection with HookedBackend](#fault-injection-with-hookedbackend)
- [Testing with Redis](#testing-with-redis)
- [Race-Condition Tests](#race-condition-tests)
- [Assertion Style](#assertion-style)
- [Test Dependencies](#test-dependencies)

## Testing Application Code

Use `InMemoryBackend` (full L1 protocol in one process) and `ManualClock` (deterministic time) from `cocache-core`. No Redis, no sleeps.

```kotlin
class UserCacheTest {
    private val clock = ManualClock()
    private val backend = InMemoryBackend(clock)
    private val runtime = CacheRuntime.builder().backend(backend).clock(clock).build()
    private val database = ConcurrentHashMap<String, String>()

    private val cache = runtime.create(cacheSpec<String, String>("user") {
        codec = StringCodec
        loader = CacheLoader { database[it] }
        policy { ttl = Duration.ofMinutes(10); ttlJitter = Duration.ZERO }
    })

    @AfterEach
    fun close() {
        runtime.close()
        backend.close()
    }

    @Test
    fun invalidateDropsTheOldValue() {
        database["1"] = "old"
        cache["1"].assert().isEqualTo("old")
        database["1"] = "new"
        cache.invalidate("1")
        cache["1"].assert().isEqualTo("new")
    }

    @Test
    fun absentValueExpiresAfterAbsentTtl() {
        cache["missing"].assert().isNull()
        database["missing"] = "created"
        clock.advance(CachePolicy.DEFAULT_ABSENT_TTL.plusMillis(1))
        cache["missing"].assert().isEqualTo("created")
    }
}
```

`ManualClock.advance` moves both L1 expiry (the backend reads the same clock) and L2 expiry. Expiry is millisecond-precise, so assertions need no tolerance.

## Spring Boot Tests

Declare a `CoherenceBackend` bean in the test configuration; the Redis backend backs off:

```kotlin
@TestConfiguration
class InMemoryCoCache {
    @Bean
    fun cocacheBackend(): CoherenceBackend = InMemoryBackend()
}
```

Use `ApplicationContextRunner` with `AutoConfigurations.of(CoCacheAutoConfiguration::class.java)` to test wiring. `assertThat(context)` from Spring's test support is the only AssertJ call allowed there. Startup failures worth asserting: two `CacheLoader<K, V>` candidates, raw `CacheSpec` return types, bean-name conflicts, undeclared names under `cocache.caches`.

## Simulating Several Instances

Several runtimes sharing one `InMemoryBackend` behave like separate instances: each has its own `NodeId`, L2, and subscription.

```kotlin
val backend = InMemoryBackend(clock)
val nodeA = CacheRuntime.builder().backend(backend).clock(clock).build()
val nodeB = CacheRuntime.builder().backend(backend).clock(clock).build()
val a = nodeA.create(spec())
val b = nodeB.create(spec())

b["1"]                  // fills L1 and B's L2
a.invalidate("1")       // B receives the invalidation synchronously
backend.reconnect()     // simulates a channel reconnect: every L2 is cleared
```

Pass `.refreshExecutor(Executor { it.run() })` to make refresh-ahead run inline in tests.

## TCK for Custom Backends

A custom `CoherenceBackend` must pass all three specs from `cocache-test` (`me.ahoo.cocache.test`):

| Spec | Abstract members | Verifies |
|------|------------------|----------|
| `BackendProtocolSpec` | `createBackend()`, `elapse(duration)` | L1 state machine: lease grant and `Leased`, token-checked `fill`, `release`, `invalidate` destroys tokens, expiry reset on every transition, negative entries, refresh tokens (window, once, preemption), `reclaim`, `readAll`, set members, broadcast frames and subscribe-time reset |
| `EngineSpec` | `createBackend()`, `clock`, `elapse(duration)`, `simulateReconnect()` | Real engines on two runtimes sharing your backend: single load across nodes, lease-wait timeout, invalidation during a load, same-node invalidate vs read, concurrent `put`, read-your-writes, loader failure releases the lease, degraded reads, invalidation retry, `localMaxAge`, batch reads, refresh once across nodes, corrupt payload reclaim, newer format bypass, bulkhead |
| `SetEngineSpec` | `createBackend()`, `clock`, `elapse(duration)` | `SetCache` add / remove / clear across nodes |

```kotlin
class MyBackendProtocolTest : BackendProtocolSpec() {
    override fun createBackend(): CoherenceBackend = MyBackend.connect()
    override fun elapse(duration: Duration) = MyBackend.advanceTime(duration)
}

class MyEngineTest : EngineSpec() {
    override val clock: CacheClock = TickingClock()
    override fun createBackend(): CoherenceBackend = MyBackend.connect()
    override fun elapse(duration: Duration) = MyBackend.advanceTime(duration)
    override fun simulateReconnect() = MyBackend.dropSubscriptions()
}
```

Override `policy()` in `EngineSpec` or `shortTtl` in `BackendProtocolSpec` if your backend needs different timings.

## Fault Injection with HookedBackend

`HookedBackend(delegate)` wraps any backend:

- `failAll`, `failWrites`, `failFill`, `failRelease`: throw `RemoteUnavailableException` from those operations.
- `afterRead`, `afterReadAll`: rewrite read results (for example, return a newer `format` or corrupt bytes).
- `afterPut`, `beforeInvalidate`: run code at exact points to orchestrate races.
- `atomicBroadcastOverride = false`: exercise the publish-after-write path.
- `reads`, `readAlls`, `fills`: counters for round-trip assertions.

## Testing with Redis

Integration tests need Redis 7+ on `localhost:6379`:

```bash
docker run -d --name cocache-redis -p 6379:6379 redis:7-alpine
```

- Redis expiry follows the server clock and cannot be advanced. Shorten remaining lifetimes instead (CoCache's own tests use `PTTL` + `PEXPIRE` over the cache's key pattern), or use short TTLs and poll.
- Use a unique cache name or namespace per test so parallel tests don't share keys or channels.
- To force a channel reset, `CLIENT KILL` the subscription connection (give the backend a `clientName` in `RedisBackendOptions` so you can find it). Lettuce reconnects, re-subscribes, and every subscriber's L2 is cleared.
- `subscribe` returns only after the server acknowledged the subscription and the reset ran, so a late reset will not clear L2 mid-test.

## Race-Condition Tests

- Orchestrate interleavings with `CountDownLatch`es placed in the loader or in `HookedBackend` hooks, never with sleeps.
- Wait on a `finished` latch so a dead background thread fails the test instead of passing silently.
- Assert cross-instance effects by polling with a timeout.
- `cocache-test` ships helpers in `Concurrency.kt` (`awaitOrFail`, `awaitCondition`, `fork`).

## Assertion Style

Use fluent-assert in Kotlin tests:

```kotlin
import me.ahoo.test.asserts.assert

cache["1"].assert().isEqualTo("v")
(cache.lookup("missing") is Lookup.Absent).assert().withFailMessage { "expected a negative entry" }.isTrue()
```

`assert()` accepts nullable receivers. Prefer `requireNotNull(...)` over `!!` chains.

## Test Dependencies

```kotlin
dependencies {
    testImplementation("me.ahoo.cocache:cocache-test")   // TCK, only when you implement a backend
    testImplementation("me.ahoo.test:fluent-assert-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
}
```

`InMemoryBackend`, `ManualClock`, and `StringCodec` come with `cocache-core`, which the starter already includes.
