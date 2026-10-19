# Running the TCK

Use this reference to wire and run the `cocache-test` suites against a backend, and to read a failure. Confirm abstract members and default timings in the pinned `cocache-test` sources.

## Suites

| Suite | Covers | You provide |
|---|---|---|
| `BackendProtocolSpec` | The `RemoteStore` contract: lease grant, token checks, expiry resets, release, refresh tokens, reclaim, batch reads, set updates, broadcast frames, reset on subscribe | `createBackend()`, `elapse(duration)`; optionally `shortTtl` |
| `EngineSpec` | Two runtimes on your backend: single load across instances, lease-wait timeout, invalidation during a load, invalidate-vs-read race, concurrent `put`s, read-your-writes, loader failure, store failure, invalidation retry, reconnect reset, `local-max-age`, batch reads, refresh-ahead, reclaim, newer format, load limit | `createBackend()`, `clock`, `elapse(duration)`, `simulateReconnect()`; optionally `policy()` |
| `SetEngineSpec` | `SetCache` across two runtimes: concurrent adds, empty-set deletion, rejected `invalidate`, write and read failures | `createBackend()`, `clock`, `elapse(duration)` |

```kotlin
testImplementation("me.ahoo.cocache:cocache-test")
```

## Template

```kotlin
class MyBackendProtocolTest : BackendProtocolSpec() {
    private val clock = ManualClock()
    override fun createBackend(): CoherenceBackend = MyBackend(clock)
    override fun elapse(duration: Duration) = clock.advance(duration)
}

class MyEngineTest : EngineSpec() {
    private val manualClock = ManualClock()
    private lateinit var backend: MyBackend
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock).also { backend = it }
    override fun elapse(duration: Duration) = manualClock.advance(duration)
    override fun simulateReconnect() = backend.dropSubscription()
}

class MySetEngineTest : SetEngineSpec() {
    private val manualClock = ManualClock()
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock)
    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
```

## Time and reconnects

- `elapse(duration)` must move time forward for both the store and the engine.
  - In-process store: advance one `ManualClock` shared by the store and `clock`.
  - Real server: advance the engine's `ManualClock` and shorten the remaining TTL of the test keys on the server by `duration` (CoCache's Redis tests read `PTTL` and set `PEXPIRE` over the test key pattern). For short durations in `BackendProtocolSpec`, sleeping also works; token issue times come from the server clock.
- `simulateReconnect()` must break the subscription so the channel resubscribes and calls `onReset()`, for example by killing its connection on the server (give the connection a recognizable client name).
- Use unique namespaces per test run on a shared server so parallel runs do not share keys or channels.

## Modes

Run every suite for every mode the backend supports. CoCache's own Redis backend runs `BackendProtocolSpec` and `EngineSpec` with atomic broadcast, non-atomic broadcast and sharded channels, and `SetEngineSpec` in one mode. A backend that can fall back from atomic to non-atomic broadcast needs both runs.

## HookedBackend

The suites wrap your backend in `HookedBackend`, which injects failures (`failAll`, `failWrites`, `failFill`, `failRelease`), rewrites read results, runs hooks around writes so races are orchestrated with latches instead of sleeps, overrides `atomicBroadcast` to exercise the non-atomic path, and counts reads and fills. Use it in additional backend-specific tests too.

## Reading a failure

| Failing area | Usual cause |
|---|---|
| Invalidation during a load is overwritten | `fill` writes without comparing the token, or `invalidate` does not destroy the lease |
| Negative entry or lease outlives its TTL onto a value | Expiry not reset on a state transition |
| Refresh runs on several nodes | Refresh token not stored, or granted while another live token exists |
| Leases linger after a failed `readAll` | Granted leases not released before throwing |
| L2 stays stale after reconnect | `onReset()` not called, or called before the subscription is confirmed |
| Reset clears copies filled after subscribing | Reset ran after `subscribe` returned |
| Corrupt payload never recovers, or valid data deleted | `reclaim` ignores the digest, or deletes instead of leasing |
| Writes reappear after a failure | Commands buffered while disconnected and replayed |

Fix the backend; never edit, skip or loosen the TCK case. Rerun the single case, then the suite in every mode, and report exact commands and results.
