# Backend Contract Map

Use this map while implementing or reviewing a `CoherenceBackend`. It lists what to verify in the pinned version's SPI sources and KDoc (`me.ahoo.cocache.api.spi`); if they disagree with this map, the sources win. It describes the 8.0 shape; [6.x backends](#6x-backends) lists what differs before 8.0.

## Contents

- [Shapes](#shapes)
- [Capabilities](#capabilities)
- [Store obligations by method](#store-obligations-by-method)
- [Rules across all methods](#rules-across-all-methods)
- [Failures, time limits and cancellation](#failures-time-limits-and-cancellation)
- [Java backends and decorators](#java-backends-and-decorators)
- [Invalidation channel](#invalidation-channel)
- [Invariants to review against](#invariants-to-review-against)
- [6.x backends](#6x-backends)
- [Wiring](#wiring)

## Shapes

```kotlin
interface CoherenceBackend : AutoCloseable {
    val atomicBroadcast: Boolean            // can a write publish its frame in the same atomic step?
    val format: Int                         // wire format version this implementation writes
    val channel: InvalidationChannel        // Java: FutureInvalidationChannel.adapt(…)
    fun store(namespace: String): L1Store   // called once per cache; Java: FutureL1Store.adapt(…)
}

interface L1Store {                         // every operation suspends; no close: connections belong to the backend
    suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): L1Read
    suspend fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>, timeout: Duration?): Map<String, L1Read>
    suspend fun fill(fill: Fill): L1Fill
    suspend fun fillAll(fills: List<Fill>): List<L1Fill>
    suspend fun release(key: String, token: Token)
    suspend fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean
    suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray)
    suspend fun putAll(puts: List<Put>, frame: ByteArray)
    suspend fun invalidate(keys: Collection<String>, frame: ByteArray)
    fun conditional(): ConditionalL1Store?  // readIfChanged, putIfMatch
    fun versioned(): VersionedL1Store?      // putIfNewer
    fun sets(): SetL1Store?                 // addMembers, removeMembers
}
```

- `atomicBroadcast = true`: every write method publishes its `frame` atomically with the change. `false`: the engine calls `channel.publish(namespace, frame)` after each successful write; a crash in between loses that message (bounded by `local-max-age`).
- `format`: start at `1`. Return `format = 0` in `L1Read.Hit` for content your protocol did not write. A higher stored format is loaded around and never overwritten; bump only through a two-phase rollout.

| Type | Shape |
|---|---|
| `Envelope` | `Scalar(bytes, version)`, `Members(list of bytes)`, `Absent` (negative entry); `version` defaults to `Versioned.NO_VERSION` |
| `L1Read` | `Hit(envelope, format, ttl, refreshGranted)`, `Miss(leaseGranted)`, `Leased(remaining)`; `ttl == null` means no expiry |
| `Token` | 24 bytes (node id + sequence); compare by bytes |
| `LeaseRequest(token, ttl)` | Lease to place on a miss |
| `RefreshRequest(window, token, tokenTtl)` | Refresh token to place on a value inside `window`; older than `tokenTtl` may be taken over |
| `Fill(key, token, envelope, ttl[, version])` | Write only if the key holds `token`; `version` is stored with the content, and `Absent` with a version is a tombstone |
| `L1Fill` | `Filled(advanced)` or `Rejected(currentVersion)`; with today's format always `Filled(advanced = false)` or `Rejected(currentVersion = null)` |
| `ContentFingerprint` | SHA-1 of the content compared by `reclaim` (`ContentFingerprint.of`) |
| `Digest` | Content digest compared by conditional reads and writes (`Digest.of`) |
| `L1ConditionalRead` / `L1ConditionalWrite` / `L1VersionedWrite` | Results of `readIfChanged`, `putIfMatch`, `putIfNewer` |

Keys arrive complete, with prefix and namespace. The SPI has no default implementations: every operation is abstract.

## Capabilities

- Each accessor returns its surface, or `null` when unsupported. It never throws and returns the same result for the store's whole life (the same object every call, or always `null`).
- Offer a surface only if its compare-and-write is atomic; a read-then-write lets two writers both win. Without `conditional()`, `getIfChanged` / `putIfMatch` throw `UnsupportedOperationException`; without `versioned()` or `sets()`, creating a versioned cache or a `SetCache` fails.
- The surfaces are separate interfaces, not sub-interfaces of `L1Store`. A store that supports versions stores `Fill.version` and `Envelope.Scalar.version` in `fill`, `put` and `putAll`, and `read` returns it.
- The TCK's `expectedCapabilities` must equal what the accessors return.

## Store obligations by method

| Method | Must |
|---|---|
| `read` | Absent with a lease request: place the lease and return `Miss(true)`. Absent without one: `Miss(false)`, write nothing. Leased: `Leased(remaining)`. Value: `Hit` with remaining TTL; grant a refresh token (keeping the TTL) only for a scalar with `0 < ttl <= window` and no live token. Never on a value without expiry. With both requests `null`, write nothing. |
| `readAll` | `read` per key, leasing only keys in `leases`; return every key. A failure releases the leases this call may have taken. |
| `fill` / `fillAll` | Write only if the key holds exactly `fill.token` (as a lease or refresh token); reset expiry to `fill.ttl`; return `Filled(advanced = false)` when written, `Rejected(currentVersion = null)` on a token mismatch, one result per fill. |
| `release` | Matching lease: delete the key. Matching refresh token on a value: clear the token, keep value and TTL. Otherwise nothing. |
| `put` | Overwrite any state, destroy any token, set expiry, broadcast. `Members` replaces the whole set. |
| `putAll` | `put` per entry, atomic per key; broadcast each key exactly once with its write (the frame may be split). No per-entry default. |
| `invalidate` | Delete every key, destroy any token, broadcast. Atomic per key; batches may split. |
| `reclaim` | Only if current content has fingerprint `observed` and is not a lease: replace it with the lease; return whether done. Never delete changed content. |
| `SetL1Store.addMembers` | One atomic check-and-write. A set of your protocol: add the members. Absent, negative or leased key: replace with a set of these members. Then destroy tokens, set expiry (`null` removes it), broadcast, return `null`. Anything that is not a set of your protocol (content you didn't write, a scalar, a set in a newer format): change nothing, broadcast nothing, return that content's format (`0` = not yours). There is no unchecked member write: a `SetCache` has no source, so overwriting is deleting its only copy. |
| `SetL1Store.removeMembers` | Same check. A set of your protocol: remove, delete the key when empty, otherwise reset expiry. Absent, negative or leased key: change nothing. Broadcast and return `null` in both cases. Unreadable content: change nothing, broadcast nothing, return its format. |
| `ConditionalL1Store.readIfChanged` | Pure; compare in the store. A scalar of your protocol whose payload digest equals `known`: `Unchanged(format)`, payload not sent back. Other scalar: `Changed(envelope, format)`. Absent, lease, negative entry: `Absent`. Anything else (not yours, a set, a newer format): `Unreadable(format)`. |
| `ConditionalL1Store.putIfMatch` | One atomic compare-and-write: matches when `expected == null` and the key holds no value (absent, lease, negative entry), or a scalar whose payload digest equals `expected`. Unreadable content returns `Unreadable(format)` and never matches as no value. Match: write like `put`, return `Written`. Otherwise change nothing, broadcast nothing, return `Conflict(current digest or null)`. |
| `VersionedL1Store.putIfNewer` | One atomic compare-by-version: write `envelope` (scalar, or `Absent` for a tombstone) with `version` like `put` only if `version` is greater than the stored version; content without a version, leases and absent keys count as `NO_VERSION`. Otherwise change nothing, broadcast nothing, return `Stale(current)`. Unreadable content as in `putIfMatch`. Versioned content holds no token: fill/release never match it, `read` never grants a refresh token on it, `reclaim` refuses it. `putIfMatch` writes without a version. |

## Rules across all methods

- Atomic per key; batch methods are atomic per key, not across keys.
- Reset the expiry on every state change, at millisecond precision; a lease's or negative entry's lifetime must never leak onto a new value.
- Never delete on read.
- Throw on failure; never swallow or retry internally. The engine decides whether to degrade, retry or rethrow.
- Reject commands while disconnected instead of buffering and replaying them.
- Talk only to a primary that serializes writes per key; replicas cannot hold leases.

## Failures, time limits and cancellation

- **Two kinds of failure.** Definitely not executed: rejected before it was written (connection down) or failed synchronously before it was sent. Everything else has an unknown outcome: a timeout, a command drained on disconnect, a script error reply, a command the client cancelled, a reply that fails to decode.
- **Release tokens (K14).** When `read`, `readAll`, `fill`, `fillAll`, `release` or `reclaim` fails with an unknown outcome, release that token asynchronously without waiting, and resend a failed release with backoff until it runs or the token expires. A stale-token release is a no-op.
- **Never report a failure as `CancellationException`.** On the JVM it is coroutine cancellation: the engine would skip the fallback to the source and let waiting readers run their own loads. Wrap a client's cancellation (connection reset or close) in another exception.
- **Bound every call.** Reads take a `timeout`: `null` means the backend's command timeout; a value is positive and capped by it; bound the call by the shorter. A timeout cancels the underlying command, so an unwritten command is never written.
- The engine never cancels a call (it calls inside `NonCancellable`) and interrupts never reach the store, so a cancellable `await()` is safe.
- Write methods return or throw only after every command they sent has ended.

## Java backends and decorators

- **Java:** implement `FutureL1Store` (same operations returning `CompletableFuture`), its `FutureConditionalL1Store` / `FutureVersionedL1Store` / `FutureSetL1Store` surfaces and `FutureInvalidationChannel`; return `FutureL1Store.adapt(store)` and `FutureInvalidationChannel.adapt(channel)`. Futures may complete on an I/O thread; only a blocking client completes them on its own executor. Throwing synchronously and failing the future both work. A future you cancel yourself becomes an `IllegalStateException` (an L1 failure); releasing its tokens is still your job. Never return `null` or complete a non-null result with `null`. `adapt` reads each accessor once.
- **Decorators:** extend `ForwardingL1Store` and override only what you intercept. Its accessors return the wrapped surfaces unchanged, so to intercept a capability override the accessor and return a `ForwardingConditionalL1Store` / `ForwardingVersionedL1Store` / `ForwardingSetL1Store` built once in the constructor, or `null` when the wrapped store has none. Never add a capability the wrapped store lacks.

## Invalidation channel

```kotlin
interface InvalidationChannel {
    fun subscribe(namespace: String, listener: InvalidationListener): Subscription  // blocking, once per cache
    suspend fun publish(namespace: String, frame: ByteArray)  // only when atomicBroadcast == false
}
interface InvalidationListener {
    fun onInvalidated(keys: List<String>, origin: NodeId)
    fun onReset()
}
```

- `publish` follows the store's failure, time-limit and cancellation rules.
- Frames are built by the engine with `InvalidationFrame.encode`; deliver them to every listener of the namespace, decoded with `InvalidationFrame.decode`. Drop malformed frames. Do not filter by origin.
- Call `onReset()` on every listener each time a subscription is established or re-established and confirmed, and whenever messages may have been lost (failed heartbeat, rebalance, sequence gap).
- The first listener's reset must have run before `subscribe` returns; later listeners on an existing subscription get one `onReset()` immediately.
- Subscribing a new namespace must not reset other namespaces; a throwing listener must not block others.
- If `subscribe` fails, throw and leave no partial registration. Closing a `Subscription` unsubscribes that listener.

## Invariants to review against

1. Every fill holds a token taken before the read; invalidation destroys tokens.
2. An L1 miss is a miss: an absent or leased key is never a negative entry; negative entries exist only as `Absent`.
3. The read path never deletes unconditionally; only `reclaim` with a matching fingerprint converts content to a lease.
4. Broadcast atomically when you can, and declare it honestly.
5. Tokens are released or expire; a failure with an unknown outcome releases them; every state transition sets its expiry explicitly.
6. The store executes protocol only; no policy, no shared state between caches.
7. Reject while disconnected; never replay buffered writes.
8. A capability is offered only with an atomic implementation, and the accessors are stable.

## 6.x backends

Before 8.0 the store is the blocking `RemoteStore` (with `close`), optionally `AsyncRemoteStore` (an `xxxAsync` twin of each method) and `AsyncInvalidationChannel.publishAsync`. Differences to verify in the pinned 6.x sources:

- One interface instead of capability surfaces: `readIfChanged` (default reads and compares on the client), `putIfMatch` and `putIfNewer` (defaults throw `UnsupportedOperationException`), `addMembersIfReadable` / `removeMembersIfReadable`; `CoherenceBackend.versionedWrites` (default `false`) declares `putIfNewer`.
- `fill` returns `Boolean`, `reclaim` takes a `Digest`, reads take no `timeout`, and the result types are `RemoteRead`, `RemoteConditionalRead`, `RemoteConditionalWrite`, `RemoteVersionedWrite`.
- `readAll` releases the leases it granted before throwing; the general rule to release tokens after any failure with an unknown outcome arrives in 8.0.
- TCK: `EngineSpec.supportsConditionalWrites = false` skips the conditional cases; `AsyncBackendProtocolSpec` covers `AsyncRemoteStore`; 6.x also ships `Async*` engine suites. There is no `expectedCapabilities`.

## Wiring

In Spring Boot, a `CoherenceBackend` bean replaces the Redis backend and Spring closes it on shutdown. Without Spring, pass it to `CacheRuntime.builder().backend(...)` and close the runtime before the backend. A backend needs only `cocache-api` at runtime and `cocache-test` for its tests.
