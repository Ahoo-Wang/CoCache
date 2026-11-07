# Backend Contract Map

Use this map while implementing or reviewing a `CoherenceBackend`. It lists what to verify in the pinned version's SPI sources and KDoc (`me.ahoo.cocache.api.spi`); if they disagree with this map, the sources win.

## Contents

- [Shapes](#shapes)
- [Capabilities](#capabilities)
- [Store obligations by method](#store-obligations-by-method)
- [Rules across all methods](#rules-across-all-methods)
- [Failures, time limits and cancellation](#failures-time-limits-and-cancellation)
- [Java backends and decorators](#java-backends-and-decorators)
- [Invalidation channel](#invalidation-channel)
- [Invariants to review against](#invariants-to-review-against)
- [Wiring](#wiring)

## Shapes

```kotlin
interface CoherenceBackend : AutoCloseable {
    val atomicBroadcast: Boolean            // can a write publish its frame in the same atomic step?
    val format: Int                         // wire format version this implementation writes
    val channel: InvalidationChannel        // Java: FutureInvalidationChannel.adapt(…)
    fun store(keyspace: Keyspace): L1Store  // called once per cache, may block; Java: FutureL1Store.adapt(…)
    override fun close() = Unit             // releases the connections the backend owns
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
    fun conditional(): ConditionalL1Store?  // readIfChanged, putIfMatch, deleteIfMatch (Store)
    fun versioned(): VersionedL1Store?      // putIfNewer, evictKeepingVersion (VersionedCache, VersionedStore)
    fun sets(): SetL1Store?                 // addMembers, removeMembers (SetStore)
}
```

- `Keyspace(keyPrefix, namespace)`: `store`, `subscribe` and `publish` take it; `storagePrefix` is `{keyPrefix}{namespace}:`, and `encodedKeys(storageKeys)` strips it (dropping keys outside the keyspace). Name one channel per keyspace from both parts, so two keyspaces with the same namespace and different prefixes never share one.
- A backend with access control probes the keyspace's real channel in `store(keyspace)` by publishing a zero-key frame (listeners ignore it), as an atomic write script would; when publishing is denied, creation fails instead of falling back to non-atomic broadcast.

- `atomicBroadcast = true`: every write method publishes its `frame` atomically with the change. `false`: the engine calls `channel.publish(keyspace, frame)` after each successful write; a crash in between loses that message (bounded by `local-max-age`).
- `format`: the format version your implementation writes (the shipped backends write `2`). In `L1Read.Hit`, report `0` for content your protocol did not write, and the stored format otherwise. The engine reads only content in exactly your `format`. Any lower format, like `0`, is content it can't read: a cache with a source reclaims it and reloads, one without fails. A higher format is loaded around and never overwritten. Report content with features you don't understand (an unknown flag or state) as a higher format, never as a lower one, or caches with a source reclaim it. Bump only through a two-phase rollout.

| Type | Shape |
|---|---|
| `Envelope` | `Scalar(bytes, version, writer, digest)`, `Members(list of bytes)`, `Absent` (negative entry); `version` defaults to `Versioned.NO_VERSION`, `writer` and `digest` to `null`. `writer` is a write id shaped like a `Token`, set by the engine on every scalar write of an authoritative type (`Store`, `VersionedStore`), and `digest` the payload's SHA-256, set on every `Store` write (never on a versioned one): store them on every operation that writes a scalar (`put`, `putAll`, `fill`, `fillAll`, `putIfMatch`, `putIfNewer`) and return them unchanged from `read`, `readAll` and `readIfChanged`; never verify or recompute the digest |
| `L1Read` | `Hit(envelope, format, ttl, refreshGranted)`, `Miss(leaseGranted)`, `Leased(remaining)`; `ttl == null` means no expiry |
| `Token` | 24 bytes (node id + sequence); compare by bytes |
| `LeaseRequest(token, ttl)` | Lease to place on a miss |
| `RefreshRequest(window, token, tokenTtl)` | Refresh token to place on a value inside `window`; older than `tokenTtl` may be taken over |
| `Fill(key, token, envelope, ttl[, version])` | Write only if the key holds `token`; `version` is stored with the content, and `Absent` with a version is a tombstone |
| `L1Fill` | `Filled(advanced)` or `Rejected(currentVersion)`; `advanced` = the fill raised a version or replaced an eviction marker and you broadcast it; `currentVersion` = the key's version when the version rule refused it, `null` on a token mismatch |
| `ContentFingerprint` | SHA-1 compared by `reclaim` over a kind byte (`V` scalar, `M` negative entry, `S` set), the format the read reported (big-endian `int32`) and the content (`ContentFingerprint.of(envelope, format)`); same bytes in another kind or format never match |
| `Digest` | SHA-256 of the payload (`Digest(algorithm, bytes)`, `Digest.of`, `Digest.ofPayload`), compared by conditional reads and writes; computed by the engine |
| `L1ConditionalRead` / `L1ConditionalWrite` / `L1VersionedWrite` / `L1Eviction` | Results of `readIfChanged`, `putIfMatch` and `deleteIfMatch`, `putIfNewer`, `evictKeepingVersion` |
| `L1RejectedException` | Thrown by a store only for a command that definitely did not execute (see [Failures](#failures-time-limits-and-cancellation)) |

Keys arrive complete, with prefix and namespace. Every `L1Store` operation and capability accessor is abstract, with no default (a decorator that forgot an accessor would silently drop a capability); only `CoherenceBackend.close()` defaults to a no-op.

## Capabilities

- Each accessor returns its surface, or `null` when unsupported. It never throws and returns the same result for the store's whole life (the same object every call, or always `null`).
- Offer a surface only if its compare-and-write is atomic; a read-then-write lets two writers both win. Creating a cache type whose surface is missing fails with `UnsupportedOperationException`: a `Store` needs `conditional()`, a `VersionedCache` or `VersionedStore` needs `versioned()`, a `SetStore` needs `sets()`. A `Cache` needs none.
- The surfaces are separate interfaces, not sub-interfaces of `L1Store`. A store that supports versions stores `Fill.version` and `Envelope.Scalar.version` in `fill`, `put` and `putAll`, and `read` returns it.
- The TCK's `expectedCapabilities` must equal what the accessors return.

## Store obligations by method

| Method | Must |
|---|---|
| `read` | Absent with a lease request: place the lease and return `Miss(true)`. Absent without one: `Miss(false)`, write nothing. Leased: `Leased(remaining)`. Value: `Hit` with remaining TTL; grant a refresh token (keeping the TTL) only for a scalar with `0 < ttl <= window` and no live token. Never on a value without expiry or a value with a digest (a `Store` value); versioned values are refreshed. With both requests `null`, write nothing. |
| `readAll` | `read` per key, leasing only keys in `leases`; return every key. A failure releases the leases this call may have taken. |
| `fill` / `fillAll` | Write only if the key holds exactly `fill.token` (as a lease or refresh token); reset expiry to `fill.ttl`; return `Filled(advanced = false)` when written, `Rejected(currentVersion = null)` on a token mismatch, one result per fill. 8.0 version rule, in the same atomic step, when the key holds a version (a versioned value with a refresh token, an eviction marker with a lease): older or `NO_VERSION` fill → clear the token, keep content and expiry, `Rejected(current)`; equal on a value → write, no broadcast, `Filled(false)`; newer, or accepted on an eviction marker → write and broadcast a frame for `fill.key` with origin `fill.token.node`, `Filled(true)`. |
| `release` | Matching lease: delete the key. Matching refresh token on a value, or lease on an eviction marker: clear the token, keep content and TTL. Otherwise nothing. |
| `put` | Overwrite any state, destroy any token, set expiry, broadcast. `Members` replaces the whole set. |
| `putAll` | `put` per entry, atomic per key; broadcast each key exactly once with its write (the frame may be split). No per-entry default. |
| `invalidate` | Delete every key, destroy any token, broadcast. Atomic per key; batches may split. |
| `reclaim` | Only if current content has fingerprint `observed` (computed from the envelope and format `read` would return now) and is not a lease, versioned content, an eviction marker or a value with a digest: replace it with the lease; return whether done. Never delete changed content. |
| `SetL1Store.addMembers` | One atomic check-and-write. A set of your protocol: add the members. Absent, negative or leased key: replace with a set of these members. Then destroy tokens, set expiry (`null` removes it), broadcast, return `null`. Anything that is not a set of your protocol (content you didn't write, a scalar, a set in a newer format): change nothing, broadcast nothing, return that content's format (`0` = not yours). There is no unchecked member write: a `SetStore` has no source, so overwriting is deleting its only copy. |
| `SetL1Store.removeMembers` | Same check. A set of your protocol: remove, delete the key when empty, otherwise reset expiry. Absent, negative or leased key: change nothing. Broadcast and return `null` in both cases. Unreadable content: change nothing, broadcast nothing, return its format. |
| `ConditionalL1Store.readIfChanged` | Pure; compare the stored digest in the store, never recompute it. A value of your protocol with a stored digest equal to `known`: `Unchanged(format)`, payload not sent back. Other value with a digest: `Changed(envelope, format)` carrying the stored digest and writer. Absent, lease, unversioned negative entry: `Absent`. Anything else (not yours, a set, a newer format, a value without a digest, versioned content, an eviction marker): `Unreadable(format)`. |
| `ConditionalL1Store.putIfMatch` | One atomic compare-and-write: matches when `expected == null` and the key holds no value (absent, lease, unversioned negative entry), or a value whose stored digest equals `expected`; store `envelope.digest` as given. Unreadable content returns `Unreadable(format)` and never matches as no value. Match: write like `put`, return `Written`. Otherwise change nothing, broadcast nothing, return `Conflict(current digest or null)`. |
| `ConditionalL1Store.deleteIfMatch` | One atomic compare-and-delete: matches only a value whose stored digest equals `expected` (never `null`). Match: delete the key, destroy any token, broadcast, return `Written`. Otherwise change nothing, broadcast nothing, return `Conflict(current digest)`, or `Conflict(null)` when the key holds no value (absent, lease, unversioned negative entry). Unreadable content (as for `readIfChanged`): `Unreadable(format)`. |
| `VersionedL1Store.putIfNewer` | One atomic compare-by-version: write `envelope` (scalar, or `Absent` for a tombstone from `invalidateIfNewer` / `deleteIfNewer`) with `version` like `put` only if `version` is greater than the stored version; content without a version, leases and absent keys count as `NO_VERSION`. An equal stored version: change nothing but broadcast `frame` (a fill may have written that version without broadcasting), return `Stale(current)`. An older given version: change nothing, broadcast nothing, return `Stale(current)`. Unreadable content as in `evictKeepingVersion`. `version` is never `Versioned.NO_VERSION`: throw `IllegalArgumentException` before changing anything. An eviction marker counts with its version. `reclaim` refuses versioned content. `putIfMatch` writes without a version. |
| `VersionedL1Store.evictKeepingVersion` | One atomic step. Versioned content (value, tombstone, eviction marker): replace with an eviction marker (`E`) holding that version and no token, expiry `ttl`, broadcast, `Evicted`. No version and no digest (plain value or negative entry, lease, absent): delete, broadcast, `Evicted`. A value with a digest: change nothing, `Authoritative`. Unreadable (not yours, a set, a newer format): change nothing, `Unreadable(format)`. `read` on `E` is `Miss(false)`, or with a lease request places the lease in place (keeping version and expiry) when the token is zero or older than `lease.ttl` and returns `Miss(true)`, otherwise `Leased`. |

## Rules across all methods

- Atomic per key; batch methods are atomic per key, not across keys.
- Reset the expiry on every state change, at millisecond precision; a lease's or negative entry's lifetime must never leak onto a new value.
- Never delete on read.
- Throw on failure; never swallow or retry internally. The engine decides whether to degrade, retry or rethrow.
- Reject commands while disconnected instead of buffering and replaying them.
- Talk only to a primary that serializes writes per key; replicas cannot hold leases.

## Failures, time limits and cancellation

- **Two kinds of failure.** Definitely not executed: rejected before it was written (connection down) or failed synchronously before it was sent; report exactly these as `L1RejectedException` (`me.ahoo.cocache.api.spi`) with the client's exception as cause. Everything else has an unknown outcome (a timeout, a command drained on disconnect, a script error reply, a command the client cancelled, a reply that fails to decode); report it with any other exception. A batch is definitely not executed only when every command in it was; if some were sent, the whole call has an unknown outcome. The engine maps `L1RejectedException` to `CacheWriteException.outcomeUnknown = false`, releases no token for it, and publishes nothing after it. `RedisBackend` maps Lettuce's not-written commands and failed node connections this way; `HookedBackend.rejectWrites` simulates it.
- **Release tokens (K14).** When `read`, `readAll`, `fill`, `fillAll`, `release` or `reclaim` fails with an unknown outcome, release that token asynchronously without waiting, and resend a failed release with backoff until it runs or the token expires. A stale-token release is a no-op. After a connection is replaced, the release can run before the drained original, whose token then lives until its lifetime (`lease.ttl`); that bound is accepted.
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
    fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription  // blocking, once per cache
    suspend fun publish(keyspace: Keyspace, frame: ByteArray)  // after writes only when atomicBroadcast == false; broadcast-only invalidations (ExternalInvalidator.broadcast, the endpoint's POST) on every backend
}
interface InvalidationListener {
    fun onInvalidated(keys: List<String>, origin: NodeId)
    fun onReset()
}
```

- `publish` follows the store's failure, time-limit and cancellation rules.
- Frames are built by the engine with `InvalidationFrame.encode`; deliver them to every listener of the keyspace, decoded with `InvalidationFrame.decode`, and pass **encoded keys** (`keyspace.encodedKeys(…)`: the part after `{keyPrefix}{namespace}:`), not storage keys. Do not filter by origin.
- A frame you can't read (`InvalidationFrame.decode` throws `IllegalArgumentException`: unknown version, unknown type, truncated, trailing bytes) resets that channel's listeners (`onReset()`); never drop it, because a dropped frame is a lost invalidation.
- Call the listeners of one keyspace one after another, in subscription order, for frames and resets alike: the cache subscribes first, so a later listener runs after the cache has dropped its local copies. A zero-key frame (the creation probe) is a no-op for listeners.
- Call `onReset()` on every listener each time a subscription is established or re-established and confirmed, and whenever messages may have been lost (failed heartbeat, rebalance, sequence gap).
- The first listener's reset must have run before `subscribe` returns; later listeners on an existing subscription get one `onReset()` immediately.
- Subscribing a new keyspace must not reset other keyspaces; a throwing listener must not block others.
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

## Wiring

In Spring Boot, a `CoherenceBackend` bean replaces the Redis backend and Spring closes it on shutdown. Without Spring, pass it to `CacheRuntime.builder().backend(...)` and close the runtime before the backend. A backend needs only `cocache-api` at runtime and `cocache-test` for its tests.
