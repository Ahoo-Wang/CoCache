# Testing Code That Uses CoCache

Use this reference to write or fix tests of application code that uses CoCache. Tests need neither Redis nor sleeps. Confirm class names and packages in the resolved version; use the assertion and mocking style the target module already uses. Docs: https://cocache.ahoo.me/guide/testing.

## Choose the harness

| Question the test answers | Harness |
|---|---|
| Does the service cache, invalidate after commit, wire Spring Cache? | `@SpringBootTest` with `cocache.backend=memory` |
| Does a value expire, refresh or reload at the right time? | Add a `ManualClock` bean (Spring) or pass it to the runtime and backend |
| Does an invalidation on one instance reach another? | Two `CacheRuntime`s sharing one `InMemoryBackend` |
| What happens when Redis fails? | `HookedBackend` from `cocache-test` around `InMemoryBackend` |
| Does it work against real Redis (topology, ACL, TLS)? | Integration test against a disposable Redis 7+; label it separately |

## Spring Boot without Redis

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

- Caches, loaders, `TransactionalInvalidator`, Spring Cache and the actuator endpoint behave as in production. Spring Boot's own Redis health indicator still connects when Spring Data Redis and the actuator are present; disable it with `management.health.redis.enabled=false`.
- The in-memory store lives as long as the Spring context, and contexts are reused across classes: invalidate the keys a test touches, or use `@DirtiesContext`.
- A declared `CoherenceBackend` bean takes precedence over `cocache.backend`.

## Moving time

Declare a `ManualClock` bean (or pass one to `InMemoryBackend(clock)` and `CacheRuntime.builder().clock(clock)`). Advancing it expires L2 and the in-memory L1 together, at millisecond precision. It drives `ttl`, `absent-ttl`, `local-max-age` and `refresh-window`; it does not drive Caffeine's `expireAfterAccess` or the background invalidation retry, which use real time. Set `ttlJitter` to zero in the test policy when asserting exact expiry. Pass `.refreshExecutor(Executor { it.run() })` to run refresh-ahead inline.

## Several instances in one JVM

```kotlin
private val clock = ManualClock()
private val backend = InMemoryBackend(clock)
private fun instance(): Cache<String, String> =
    CacheRuntime.builder().backend(backend).clock(clock).build().also { runtimes += it }
        .create(cacheSpec<String, String>("UserCache") {
            codec = StringCodec
            loader = CacheLoader { id -> loads.incrementAndGet(); db[id] }
        })

@Test
fun invalidationReachesOtherInstances() {
    val a = instance(); val b = instance()
    a["42"]; b["42"]                      // one load; b reads the shared L1
    db["42"] = "Grace"
    a.invalidate("42")                    // in-memory delivery is synchronous
    check(b["42"] == "Grace")
}
```

Close every runtime after the test. `runtime.metrics(name)` exposes counters (loads, L1 hits, degraded fills) and `runtime.admin(name)` exposes `inspect` and pending invalidations.

## Redis failures

`HookedBackend(delegate)` (test dependency `me.ahoo.cocache:cocache-test`) switches: `failAll`, `failWrites` (writes fail with an unknown outcome), `rejectWrites` (writes throw `L1RejectedException` before reaching the store: `CacheWriteException.outcomeUnknown` is `false`), `failFill`, `failRelease`. Expected behavior:

- A `Cache` with a loader: reads degrade to the source; `invalidate` does not throw and queues a retry (`runtime.admin(name)?.pendingInvalidationCount`). Under `failAll` or `failWrites`, `put` / `putAll` do not throw either: the outcome is unknown, so they queue a compensating invalidation. Only `rejectWrites` (or `invalidate-failure: throw`) makes a `Cache.put` throw `CacheWriteException`, with `outcomeUnknown = false`. The retry runs on real time, so poll with a timeout instead of sleeping a fixed amount.
- Reads without a source (`Store`, `VersionedStore`, `SetStore`, an externally sourced `Cache` read without a loader): `lookup` returns `Lookup.Unknown`, `get` throws `CacheReadException`, nothing is cached; with `unknownRead = ABSENT` they return `null`. Assert `cocache.reads.unknown` through `runtime.metrics(name)?.readsUnknown`.
- Authoritative writes and deletes (`Store` and `SetStore` writes, `Store.delete`, `deleteIfMatch`, `purge`, `SetStore.clear`) and `VersionedCache` writes throw `CacheWriteException` under `failAll`, `failWrites` or `rejectWrites` and queue nothing (`failFill` / `failRelease` only touch fills and releases).

Test each type you declare: a `Store` read after a write on another runtime, a conditional write that conflicts, a `VersionedCache` replay of an older version returning `Stale`.

## Race tests

- Orchestrate interleavings with latches inside the loader or `HookedBackend` hooks, never with sleeps.
- Wait on a `finished` latch so a dead background thread fails the test instead of passing silently.
- Assert cross-instance effects by polling with a timeout.

## Evidence to report

State which harness each test used. A memory-backend test proves application wiring and coherence logic; it does not prove Redis topology, ACL, TLS, channel mode or network behavior.
