# Running the TCK

Use this reference to wire and run the `cocache-test` suites against a backend, and to read a failure. Confirm abstract members and default timings in the pinned `cocache-test` sources.

## Suites

| Suite | Covers | You provide |
|---|---|---|
| `BackendProtocolSpec` | The `L1Store` contract, called directly through `Suspending.await`: lease grant, token checks, `L1Fill` results, expiry resets, release, refresh tokens, reclaim, batch reads and writes, writer ids and versions round-tripping through every scalar write, `NO_VERSION` refused by `putIfNewer` (`VERSIONED`), set updates (`SETS`), broadcast frames, reset on subscribe, unreadable frames resetting the subscribers, listeners receiving encoded keys in subscription order, channels separated by keyspace; optionally a read `timeout` bounding the call under a stalled L1 and releasing its lease | `createBackend()`, `expectedCapabilities`, `elapse(duration)`; optionally `shortTtl`, `pause()` |
| `EngineSpec` | `Cache` and `Store` on two runtimes on your backend: single load across instances, lease-wait timeout, invalidation during a load, invalidate-vs-read race, concurrent `put`s, read-your-writes, loader failure, store failure, invalidation retry, reconnect reset, `local-max-age`, batch reads, refresh-ahead, reclaim, newer format, load limit, `Lookup.Unknown` for reads without a source (including a partial `getAll`), an externally sourced cache's unfilled miss and retried invalidation, write failures reporting `outcomeUnknown`, outcome-unknown `put`s compensated under `invalidate-failure: retry`; optionally a per-cache read timeout bounding every L1 read; with `CONDITIONAL`, the `Store`: reads without a lease, writer ids and digests only on its writes (a tampered digest fails the read, a derived cache counts a type mismatch), synchronous deletes, conditional reads, writes and `deleteIfMatch`, including a forced concurrent read-modify-write | `createBackend()`, `expectedCapabilities`, `clock`, `elapse(duration)`, `simulateReconnect()`; optionally `pause()`, `policy()`, `configureNode(builder)` |
| `SetEngineSpec` | With `SETS`: `SetStore` across two runtimes: concurrent adds, empty-set deletion, `put` / `putAll` replacing sets, batch reads sent as one `readAll`, member writes refusing unreadable content, write failures, a read failure answering `Unknown` | `createBackend()`, `expectedCapabilities`, `clock`, `elapse(duration)` |
| `VersionedSpec` | With `VERSIONED`, `VersionedCache` and `VersionedStore`: versions stored by whole writes and compared as signed 64-bit numbers, tombstones, versioned writes destroying a held lease, ordering and convergence across two runtimes, safe replay after an unknown outcome, `evict` keeping and `purge` dropping the version, an eviction refused on a `Store` value, an unversioned `null` refused over versioned content, a `VersionedStore` reading without a lease and writing a writer id | `createBackend()`, `expectedCapabilities`, `clock`, `elapse(duration)`; optionally `policy()`, `configureNode(builder)` |
| `MultiNodeSpec` | Three runtimes on your backend: invalidation before a lease, after a lease while another node waits, after a fill but before the L2 write; a remote write between a read and its L2 fill; cross-node concurrent `put`s; misses while an invalidation retry is pending; one source load and one refresh per key cluster-wide | `createBackend()`, `expectedCapabilities`, `clock`, `elapse(duration)` |
| `FaultSpec` | L1 faults: copies bounded by `degradedLocalMaxAge`, failed fill and its lease, a failed `Store.delete` throwing instead of retrying, retries that give up or overflow, `put` / `invalidate` that take effect but report an error (a `put` is safe to retry under `invalidate-failure: throw` and compensated under the default `retry`), an outcome-unknown `put` that runs late evicting the writer's own stale copy through its broadcast (atomic broadcast only); optionally, writes rejected while disconnected are not replayed, and commands the client cancels are L1 failures, not cancellations | `createBackend()`, `expectedCapabilities`, `clock`, `elapse(duration)`; optionally `disconnect()`, `commandCancellation()` |

```kotlin
testImplementation("me.ahoo.cocache:cocache-test")
```

Every suite except `BackendProtocolSpec` (which calls the store directly) has `protected open val access: CacheAccess` (default `CacheAccess.SYNC`), and a backend needs only the default: how the blocking, async and suspending APIs behave belongs to the core. To also run a suite through the async view, override `access = CacheAccess.ASYNC`. A Java backend runs the same suites on the store `FutureL1Store.adapt` returns.

Each case creates a backend through `createBackend()`. `BackendProtocolSpec` closes it after each case. The other suites wrap it in `HookedBackend` and close it only when you override `closesBackend = true`: do that when your backend opens connections or threads; the default is `false` because a backend may be shared.

## Capabilities

`expectedCapabilities: Set<L1Capability>` is abstract with no default: a subclass that doesn't declare it doesn't compile. Declare exactly the surfaces your store returns (`CONDITIONAL` for `conditional()`, `VERSIONED` for `versioned()`, `SETS` for `sets()`).

- Every suite's `storeProvidesExactlyTheDeclaredCapabilities` fails on any difference (an extra or a missing capability) and when an accessor returns a different object on another call or switches between `null` and a surface.
- Cases that need a capability skip only when it isn't declared. A declared capability must pass its cases, including the concurrent ones only an atomic implementation passes.
- Don't drop a capability from the declaration to silence its failures: the capability check then fails. Fix the implementation, or return `null` from the accessor and document that the capability is unsupported.

## Template

```kotlin
private val CAPABILITIES = setOf(L1Capability.CONDITIONAL, L1Capability.VERSIONED, L1Capability.SETS)

class MyBackendProtocolTest : BackendProtocolSpec() {
    override val expectedCapabilities = CAPABILITIES
    private val clock = ManualClock()
    override fun createBackend(): CoherenceBackend = MyBackend(clock)
    override fun elapse(duration: Duration) = clock.advance(duration)
}

class MyEngineTest : EngineSpec() {
    override val expectedCapabilities = CAPABILITIES
    private val manualClock = ManualClock()
    private lateinit var backend: MyBackend
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock).also { backend = it }
    override fun elapse(duration: Duration) = manualClock.advance(duration)
    override fun simulateReconnect() = backend.dropSubscription()
}

class MySetEngineTest : SetEngineSpec() {
    override val expectedCapabilities = CAPABILITIES
    private val manualClock = ManualClock()
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock)
    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

// MultiNodeSpec and VersionedSpec take the same members as SetEngineSpec
class MyFaultTest : FaultSpec() {
    override val expectedCapabilities = CAPABILITIES
    private val manualClock = ManualClock()
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock)
    override fun elapse(duration: Duration) = manualClock.advance(duration)
    // only if the backend holds connections or its client can cancel commands; see "Hooks"
    // override fun disconnect(): AutoCloseable? = proxy.cut()
    // override fun commandCancellation(): CommandCancellation? = MyCancellation(proxy, client)
}
```

Java: `@Override protected Set<L1Capability> getExpectedCapabilities() { return EnumSet.allOf(L1Capability.class); }`.

## Time, reconnects and hooks

- `elapse(duration)` must move time forward for both the store and the engine.
  - In-process store: advance one `ManualClock` shared by the store and `clock`.
  - Real server: advance the engine's `ManualClock` and shorten the remaining TTL of the test keys on the server by `duration` (CoCache's Redis tests read `PTTL` and set `PEXPIRE` over the test key pattern). For short durations in `BackendProtocolSpec`, sleeping also works; token issue times come from the server clock.
- `simulateReconnect()` must break the subscription so the channel resubscribes and calls `onReset()`, for example by killing its connection on the server (give the connection a recognizable client name).
- `pause()` on `BackendProtocolSpec` and `EngineSpec` (optional, default `null` = the read-timeout case is skipped) stalls the backend's commands until the returned handle is closed, or for a fixed time of at least ten times the case's read timeout; commands started meanwhile don't complete. A local TCP proxy that holds client data works for a real server.
- `EngineSpec.writeForeign(storageKey, raw)` (optional, default `false` = the lower-format reclaim cases are skipped) writes `raw` without broadcasting as content in a format below the backend's, and returns `true`; a read must return `Envelope.Scalar(raw)` with that lower format. A real server can write the raw bytes directly (CoCache's Redis tests `SET` them: format `0`).
- `FaultSpec.disconnect()` (optional) must cut the backend's command connection and keep it down until the returned handle is closed, so commands issued meanwhile fail instead of being buffered. A local TCP proxy that closes its listener works for a real server; refusing new connections matters, because a proxy that accepts and then closes looks like a handshake error, and some clients fail fast on that regardless of their buffering setting.
- `FaultSpec.commandCancellation()` (optional) returns a `CommandCancellation`: `hold()` keeps later commands in flight in your client (a proxy that drops replies, or futures you don't complete), `cancel()` has the client cancel them (a `FutureL1Store` cancels its futures; CoCache's Redis tests have Lettuce reset its connections), and `close()` restores normal operation (reconnecting if the protocol state is lost). The case asserts that the read degrades to the loader and serves waiting readers, a `put` on a cache with `invalidate-failure: throw` fails with `CacheWriteException`, no caller sees a `CancellationException`, and the cancelled read's lease is gone once L1 is back.
- Use unique namespaces per test run on a shared server so parallel runs do not share keys or channels.

## Modes

Run every suite for every mode the backend supports. CoCache's own Redis backend runs `BackendProtocolSpec`, `EngineSpec`, `MultiNodeSpec`, `FaultSpec` and `VersionedSpec` with atomic broadcast, non-atomic broadcast and sharded channels, and `SetEngineSpec` in one mode. A backend that can fall back from atomic to non-atomic broadcast needs both runs.

## HookedBackend

The engine suites wrap your backend in `HookedBackend` (a `ForwardingL1Store` that keeps only the surfaces your store has), which injects failures (`failAll`; `failWrites`, an unknown outcome on every write; `failFill`; `failRelease`; `rejectWrites`, which throws `L1RejectedException` before any write, conditional, versioned and set-member writes included, reaches your store; and `deferPuts`, which fails single-key puts with an unknown outcome and holds them until `applyDeferredPuts()` runs them), rewrites read results (`afterRead`, `afterReadAll`), runs hooks around writes and after fills so races are orchestrated with latches instead of sleeps (`afterFill`, `afterPut`, `beforeInvalidate`, `afterInvalidate`, `afterVersionedWrite`; a throwing `afterPut`, `afterInvalidate` or `afterVersionedWrite` simulates a write that took effect but reported an error), resumes callers on another thread (`ioExecutor`), overrides `atomicBroadcast` (`atomicBroadcastOverride`) to exercise the non-atomic path, and counts calls (`reads`, `readAlls`, `fills`, `puts`, `putAlls`). Use it in additional backend-specific tests too.

## Reading a failure

| Failing area | Usual cause |
|---|---|
| `storeProvidesExactlyTheDeclaredCapabilities` | `expectedCapabilities` differs from the accessors, or an accessor builds a new surface on every call (create it once) or decides per call whether to return `null` |
| Invalidation during a load is overwritten | `fill` writes without comparing the token, or `invalidate` does not destroy the lease |
| Negative entry or lease outlives its TTL onto a value | Expiry not reset on a state transition |
| Refresh runs on several nodes | Refresh token not stored, or granted while another live token exists |
| Leases linger after a failed read or batch read | Tokens of a failure with an unknown outcome not released, or the release not resent after a reconnect |
| A read times out late, or not at all, while L1 stalls | The read's `timeout` ignored, or the underlying command not cancelled on timeout |
| A cancelled command reaches the caller as `CancellationException`, or waiting readers load again | The store rethrows the client's cancellation instead of wrapping it in another exception |
| L2 stays stale after reconnect | `onReset()` not called, or called before the subscription is confirmed |
| Reset clears copies filled after subscribing | Reset ran after `subscribe` returned |
| Corrupt payload never recovers, or valid data deleted | `reclaim` ignores the fingerprint, or deletes instead of leasing |
| A paused read of lower-format content overwrites a later `put` of the same bytes | `reclaim` compares only the content bytes, not the kind and format the read reported |
| Writes reappear after a failure | Commands buffered while disconnected and replayed |
| More than one load or refresh per key across nodes | Lease or refresh token granted twice, or a waiter's re-read does not take a fresh lease after the holder's was destroyed |
| A node keeps a value written before another node's invalidation | Channel delivers the event before the write is visible, or drops it without calling `onReset()` |

Fix the backend; never edit, skip or loosen the TCK case. Rerun the single case, then the suite in every mode, and report exact commands and results.
