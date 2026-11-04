---
title: Extending CoCache
description: Implementing a CoherenceBackend (L1Store and InvalidationChannel, or FutureL1Store from Java) or a Codec — the exact SPI contracts, capabilities, failure and cancellation rules, the cocache-test TCK suites and how to run them, codec rules, and what not to customize.
---

# Extending CoCache

| You want to | Implement | Verify with |
|-------------|-----------|-------------|
| Store L1 somewhere other than Redis, or talk to Redis differently | [`CoherenceBackend`](#backend) | [`BackendProtocolSpec`, `EngineSpec`, `SetEngineSpec`, `MultiNodeSpec`, `FaultSpec`, `VersionedSpec`](#tck) |
| Change how values become bytes | [`Codec<V>`](#codecs) | Your own round-trip tests |
| Load, encode keys, reject impossible keys, choose lifetimes per entry | [Functions](#functions): `CacheLoader`, `KeyEncoder`, `KeyFilter`, `TtlFunction` | Your own tests |

The SPI lives in `me.ahoo.cocache.api.spi` (`cocache-api`, no dependencies). A backend needs only `cocache-api` at runtime and `cocache-test` for its tests.

In a Spring Boot application, a `CoherenceBackend` bean replaces the Redis backend (the starter backs off), and Spring closes it on shutdown. The single `Codec<V>` bean is used by every interface cache whose value type is `V`, unless the cache names another with `@CoCache(codec = "beanName")`.

## Backend {#backend}

```kotlin
interface CoherenceBackend : AutoCloseable {
    val atomicBroadcast: Boolean            // can a write publish its frame in the same atomic step?
    val format: Int                         // wire format version this implementation writes
    val channel: InvalidationChannel        // Java: FutureInvalidationChannel.adapt(…)
    fun store(keyspace: Keyspace): L1Store  // once per cache, may block; Java: FutureL1Store.adapt(…)
    override fun close() = Unit
}
```

L1 storage and the invalidation channel are two faces of one object, because the side that serializes a write should also announce it. Connections and threads belong to the backend and close with it: a store has no `close`.

- **`Keyspace`.** `store`, `subscribe` and `publish` take a `Keyspace(keyPrefix, namespace)`. Its `storagePrefix` is `{keyPrefix}{namespace}:`, and its constructor rejects a namespace outside `[A-Za-z0-9._-]+`. Name one channel per keyspace from both parts (the Redis backend uses `{keyPrefix}inv:{namespace}`): two keyspaces with the same namespace and different prefixes must not share a channel. A backend with access control checks in `store` that it may publish on the keyspace's channel, and throws if not, instead of letting every write fail at its broadcast after changing L1. The Redis backend publishes one frame with no keys there.
- **`atomicBroadcast`.** If `true`, every write method of the store must publish its `frame` in the same atomic step as the change. If `false`, the engine calls `channel.publish(keyspace, frame)` after each successful write. Atomic is better: a crash between write and publish loses the message. Declare only what you can guarantee. The TCK checks both kinds.
- **`format`.** The engine compares it with `L1Read.Hit.format`. `0` means content your protocol didn't write, which the engine treats as corrupt. The engine reads only content in exactly your `format`. Any lower format is content it can't read, like `0`. A higher format is loaded around and never reclaimed or written back. Report content with a feature you don't understand (an unknown flag or state) as a higher format, never as a lower one, or caches with a loader reclaim it. The shipped backends write `2`. Bump yours only through a two-phase upgrade.

### `L1Store` {#l1-store}

The store's operations are `suspend` functions. A Kotlin backend implements `L1Store` directly; a Java backend implements [`FutureL1Store`](#java) and adapts it.

```kotlin
interface L1Store {
    suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): L1Read
    suspend fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>, timeout: Duration?): Map<String, L1Read>
    suspend fun fill(fill: Fill): L1Fill
    suspend fun fillAll(fills: List<Fill>): List<L1Fill>
    suspend fun release(key: String, token: Token)
    suspend fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean
    suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray)
    suspend fun putAll(puts: List<Put>, frame: ByteArray)
    suspend fun invalidate(keys: Collection<String>, frame: ByteArray)
    fun conditional(): ConditionalL1Store?  // Store, or null
    fun versioned(): VersionedL1Store?      // VersionedCache and VersionedStore, or null
    fun sets(): SetL1Store?                 // SetStore, or null
}
interface ConditionalL1Store {
    suspend fun readIfChanged(key: String, known: Digest?, timeout: Duration?): L1ConditionalRead
    suspend fun putIfMatch(key: String, expected: Digest?, envelope: Envelope.Scalar, ttl: Duration?, frame: ByteArray): L1ConditionalWrite
    suspend fun deleteIfMatch(key: String, expected: Digest, frame: ByteArray): L1ConditionalWrite
}
interface VersionedL1Store {
    suspend fun putIfNewer(key: String, envelope: Envelope, version: Long, ttl: Duration?, frame: ByteArray): L1VersionedWrite
    suspend fun evictKeepingVersion(key: String, ttl: Duration?, frame: ByteArray): L1Eviction
}
interface SetL1Store {
    suspend fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int?
    suspend fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int?
}
```

Keys arrive complete, with prefix and namespace. A store handles bytes and tokens only, and holds no policy: every TTL, window, time limit and token is an argument. Every operation is abstract; the SPI has no non-atomic defaults. The types:

| Type | Shape |
|------|-------|
| `Envelope` | `Scalar(bytes, version, writer, digest)`, `Members(list of bytes)`, or `Absent` (a negative entry). `version` defaults to `Versioned.NO_VERSION`, `writer` and `digest` to `null`; all three take part in equality. `writer` is a write id shaped like a `Token` that the engine sets on every scalar write of an authoritative type (`Store`, `VersionedStore`). `digest` is the payload's SHA-256, which the engine sets on every `Store` write; versioned writes never carry one. |
| `L1Read` | `Hit(envelope, format, ttl, refreshGranted)`, `Miss(leaseGranted)`, or `Leased(remaining)`. `ttl == null` means no expiry. |
| `Token` | 24 bytes: `NodeId` (16) + sequence (8). Unique, never reused. Compare by bytes. |
| `LeaseRequest(token, ttl)` | Lease to place on a miss, expiring after `ttl` |
| `RefreshRequest(window, token, tokenTtl)` | Refresh token to place on a value inside `window`. A token older than `tokenTtl` may be taken over. |
| `Fill(key, token, envelope, ttl[, version])` | Write `envelope` with `ttl` only if `key` holds `token`. `version` (not part of `copy`) defaults to the scalar's version; `Absent` with a version is a tombstone. |
| `L1Fill` | `Filled(advanced)` when the fill wrote, `Rejected(currentVersion)` when it didn't. `advanced` is `true` when the fill raised a version or replaced an eviction marker, and you broadcast it; `currentVersion` is the key's version when the version rule refused the fill, and `null` when the token didn't match. |
| `Put(key, envelope, ttl)` | One entry of `putAll`: overwrite `key` like `put` |
| `ContentFingerprint` | SHA-1 of the content, compared by `reclaim`: scalar → payload bytes, set → each member as `int32 length + bytes` in returned order, negative entry → empty. `ContentFingerprint.of(envelope)` computes it. |
| `Digest` | The payload's SHA-256 (`Digest(algorithm, bytes)`, `Digest.of(envelope)`, `Digest.ofPayload(bytes)`), compared by conditional reads and writes. The engine computes it; a store keeps and returns it, and never recomputes it. |
| `L1ConditionalRead` | `Unchanged(format)`, `Changed(envelope, format)` (a scalar value), `Absent` (absent, lease, or negative entry), or `Unreadable(format)` |
| `L1ConditionalWrite` | The result of `putIfMatch` and `deleteIfMatch`: `Written` (written or deleted), `Conflict(current)` (`current == null`: no value), or `Unreadable(format)` |
| `L1VersionedWrite` | `Written`, `Stale(current)`, or `Unreadable(format)` |
| `L1Eviction` | `Evicted`, `Authoritative` (the key holds a value with a digest), or `Unreadable(format)` |

The contract, method by method. The state machine is in [Consistency](./consistency.md#l1-fence).

| Method | Must |
|--------|------|
| `read` | Absent key and `lease != null`: place the lease (expiring after `lease.ttl`) and return `Miss(true)`. Absent and no lease: `Miss(false)`, write nothing. Leased key: `Leased(remaining)`. Value: `Hit` with the remaining TTL. A scalar value whose remaining TTL is `> 0` and `≤ refresh.window`, with no token or a token older than `refresh.tokenTtl`: store the refresh token (keeping the TTL) and return `refreshGranted = true`. Never on a value without expiry, and never on a value with a digest; a versioned value gets one. An eviction marker (`evictKeepingVersion`) reads as `Miss(false)` without a lease request; with one, and no token or a token older than `lease.ttl`, store the lease on it in place (keeping its version and TTL) and return `Miss(true)`, otherwise `Leased(lease.ttl minus the token's age)`. Content you didn't write: `Hit(…, format = 0)`. With both requests `null`, `read` is pure and writes nothing. |
| `readAll` | `read` for each key, requesting a lease only for keys in `leases`. Return every key. A failure releases the leases this call may have taken ([below](#failures)). |
| `fill` / `fillAll` | Write only if the key currently holds exactly `fill.token`, as a lease or a refresh token, and reset the expiry to `fill.ttl`. Return `Filled(advanced = false)` when you wrote and `Rejected(currentVersion = null)` when the token didn't match; `fillAll` returns one result per fill, in order. A store with `versioned()` stores `fill.version` with the content: a versioned loader's `Versioned(null, n)` arrives as `Absent` with version `n` and must become the same tombstone `putIfNewer` writes. When the key holds a version (a versioned value with a refresh token, an eviction marker with a lease), apply the version rule in the same atomic step: `fill.version` older or `NO_VERSION` → clear the token, keep the content and expiry, return `Rejected(current version)`; equal on a value → write, don't broadcast, `Filled(false)`; newer, or any accepted fill on an eviction marker → write and broadcast a frame for `fill.key` whose origin is `fill.token.node`, `Filled(true)`. |
| `release` | Token matches a lease: delete the key. Token matches a refresh token on a value, or a lease on an eviction marker: clear the token, keep the content and its TTL. Otherwise do nothing. |
| `reclaim` | Only if the key's current content has fingerprint `observed` and isn't a lease, versioned content, an eviction marker or a value with a digest: replace it with the lease. Return whether you did. Never delete content that changed. |
| `put` | Overwrite any state, destroying any token, set the expiry, broadcast `frame`. A `Members` envelope replaces the whole set. |
| `putAll` | `put` for each entry, atomic per key, not across keys. `frame` carries every key: broadcast each key exactly once with its write (you may split the frame, for example per slot group). There is no default that calls `put` per entry, because each call would broadcast every key. |
| `invalidate` | Delete every key, destroying any token, and broadcast `frame`. Atomic per key. Batches may split. |
| `addMembers` | One atomic check-and-write. Key holds a set of your protocol: add the members. Absent key, negative entry, or lease: replace it with a set of these members. Either way destroy any token, set the expiry (`null` removes it), broadcast, and return `null`. Content that isn't a set of your protocol (content you didn't write, a scalar value, or a set in a newer format than yours): change nothing, broadcast nothing, and return that content's format (`0` for content you didn't write). A `SetStore` has no source, so overwriting such content would delete its only copy; there is no unchecked member write. |
| `removeMembers` | Same check as `addMembers`. Key holds a set of your protocol: remove the members, delete the key when the set becomes empty, otherwise reset the expiry. Absent key, negative entry, or lease: change nothing. Broadcast and return `null` in both cases. Unreadable content: change nothing, broadcast nothing, return its format. |
| `readIfChanged` | Pure, like `read` with no requests. A scalar value of your protocol with a stored digest equal to `known`: return `Unchanged(format)` without sending the payload back. Another value with a digest: `Changed(envelope, format)`, the envelope carrying the stored digest and writer. Absent key, lease, or negative entry without a version: `Absent`. Anything else (content you didn't write, a set, a newer format, a value without a digest, versioned content, an eviction marker): `Unreadable(format)`. Compare the stored digest, don't recompute it, and read no more than you need to compare, so an unchanged value costs the same whatever its size. |
| `putIfMatch` | One atomic compare-and-write. Current content matches when `expected == null` and the key holds no value (absent, lease, negative entry without a version), or when it's a value whose stored digest equals `expected`. Content `readIfChanged` would call `Unreadable`: return `Unreadable(format)`. Store `envelope.digest` as given. It never counts as no value: `expected == null` would then overwrite it, and an older instance would downgrade a newer value during a rolling upgrade. On a match, write like `put` (destroy tokens, set the expiry, broadcast `frame`) and return `Written`. Otherwise change nothing, broadcast nothing, and return `Conflict(digest of the current value, or null)`. A read-then-write would let two concurrent writers both succeed: if your store can't compare and write atomically, return `null` from `conditional()`. |
| `deleteIfMatch` | One atomic compare-and-delete. It matches only a value whose stored digest equals `expected` (never `null`). On a match, delete the key, destroying any token, broadcast `frame`, and return `Written`. Otherwise change nothing, broadcast nothing, and return `Conflict(digest of the current value)`, or `Conflict(null)` when the key holds no value (absent, lease, negative entry without a version). Content `readIfChanged` would call `Unreadable`: return `Unreadable(format)`. |
| `putIfNewer` | One atomic compare-and-write by version. The key's version is the one stored with a scalar value or negative entry; a value or negative entry without a version, a lease, and an absent key count as `NO_VERSION` and are always written. If `version` is greater: write `envelope` (a scalar, or `Absent` for a tombstone from `invalidateIfNewer` or `deleteIfNewer`) like `put`, store `version` with it, and return `Written`. Otherwise change nothing, broadcast nothing, and return `Stale(current)`. An eviction marker counts with its version. Classify unreadable content as you do for `evictKeepingVersion`. `reclaim` must refuse versioned content, because a lease can't carry the version. `fill`, `put` and `putAll` store `Envelope.Scalar.version` and `read` returns it; `putIfMatch` writes a value without a version. `version` is never `Versioned.NO_VERSION`: throw `IllegalArgumentException` before changing anything. |
| `evictKeepingVersion` | One atomic step. Versioned content (a value, a tombstone, an eviction marker): replace it with an eviction marker holding that version and no token, set the expiry to `ttl`, broadcast `frame`, return `Evicted`. Content without a version or digest (a plain value or negative entry, a lease, no key): delete it, broadcast, return `Evicted`. A value with a digest: change nothing, return `Authoritative`. Content you can't read (not yours, a set, a newer format): change nothing, return `Unreadable(format)`. |

Across all methods:

- **Keep the writer id and the digest.** Every operation that writes a scalar (`fill`, `fillAll`, `put`, `putAll`, `putIfMatch`, `putIfNewer`) stores `Envelope.Scalar.writer` and `Envelope.Scalar.digest` with the value, and `read`, `readAll` and `readIfChanged` return them unchanged. A whole write without them stores none. Don't verify the digest: the engine does, the same way for every backend.
- **Atomic per key.** Batch methods are atomic per key, not across keys.
- **Reset the expiry on every state change**, at millisecond precision. A lease's or a negative entry's lifetime must never leak onto a new value.
- **Never delete on read.**
- **Throw on failure.** Don't swallow errors or retry internally: the engine decides whether to degrade, retry, or rethrow, and counts it. [Failures, time limits and cancellation](#failures) has the rest.
- **Reject commands while disconnected** rather than buffering and replaying them.

`InMemoryBackend` implements the whole protocol in one process and is the reference implementation. Several runtimes sharing one instance behave like several instances, which also makes it a good backend for application tests.

### Capabilities {#capabilities}

Conditional reads and writes, versioned writes and set-member writes are optional. A store exposes each through an accessor that returns its surface, or `null` when it doesn't support it:

- **Never throw from an accessor,** and return the same result for the store's whole life: the same object every call, or always `null`. The engine may read it once.
- **The accessors are abstract,** with no default `null`: otherwise a decorator that wraps only the basic operations would silently strip capabilities.
- **The surfaces are separate interfaces,** not sub-interfaces of `L1Store`, so a [decorator](#decorators) that wants to intercept them overrides the accessor instead of being bypassed.
- `CacheRuntime.create` checks what a [cache type](../guide/caches.md#types) needs: a `Store` needs `conditional()`, a `VersionedCache` or `VersionedStore` needs `versioned()`, and a `SetStore` needs `sets()`. When one is missing, creation fails with `UnsupportedOperationException` and registers nothing. A `Cache` needs none of them. The log line for each created cache lists what its store provides, for example `L1 capabilities[conditional, versioned, sets]`.

The TCK checks that the store provides exactly the capabilities you [declare](#expected-capabilities).

### Failures, time limits and cancellation {#failures}

- **Two kinds of failure.** A command is *definitely not executed* when it was rejected before it was written (for example because the connection is down) or failed synchronously before it was sent. Report exactly these as `L1RejectedException` (`me.ahoo.cocache.api.spi`), with the client's exception as its cause. Every other failure has an *unknown outcome*: a timeout, a command drained when the connection dropped, a script's error reply, a command your client cancelled, a reply that fails to decode. Report those with any other exception. A batch is definitely not executed only if every command in it was; when some were sent, the whole call has an unknown outcome. The engine maps the two kinds to `CacheWriteException.outcomeUnknown` (`false` for `L1RejectedException`), releases no token for a rejected command, and doesn't publish after it on a backend without atomic broadcast. `RedisBackend` maps Lettuce's not-written commands and failed node connections to `L1RejectedException`.
- **Release tokens after a failure.** When a command that takes or gives back a token (`read`, `readAll`, `fill`, `fillAll`, `release`, `reclaim`) fails with an unknown outcome, release that token asynchronously, without waiting, and resend a failed release with backoff until it runs or the token expires. A release whose token no longer matches does nothing, so a late, repeated or post-reconnect release is harmless. A release usually runs after the command it cleans up, on the same connection, so the token comes back right after a reconnect. After the connection is replaced, the release on the new connection can run before the old connection's drained command; that command's token then lives until its lifetime (`lease.ttl` for a lease). Without it, a lease taken by a command whose reply was lost blocks the key on every instance until `lease.ttl`.
- **Never report a failure as `CancellationException`.** On the JVM that is coroutine cancellation: the engine would take it for the caller's own cancellation, skip the fallback to the source, and let the readers waiting on that load run their own. A command your client cancels (on a connection reset or close) is a failure with an unknown outcome: throw another exception, with the cancellation as its cause if you like.
- **Bound every call.** A call must end on its own, through a command timeout for example, never hang. Reads take a `timeout` for that call: `null` means your command timeout; a value is positive, capped at your command timeout, and you bound the call by the shorter of the two. A timeout cancels the underlying command, so a command not written yet is never written.
- **The engine never cancels a call.** It calls the store inside `NonCancellable`, and thread interrupts don't reach the store, so waiting with a cancellable `await()` is safe.
- **Write methods return or throw only after every command they sent has ended.** Otherwise a broadcast the engine sends afterwards could overtake a command still running.

### Java backends: `FutureL1Store` {#java}

Kotlin can't overload a `suspend` method with a blocking one of the same name, so a Java backend implements `FutureL1Store`: the same operations with the same semantics, returning `CompletableFuture`. The capability surfaces have `FutureConditionalL1Store`, `FutureVersionedL1Store` and `FutureSetL1Store`, and the channel has `FutureInvalidationChannel`. Return the adapted forms:

```java
public final class MyBackend implements CoherenceBackend {
    private final InvalidationChannel channel = FutureInvalidationChannel.adapt(new MyChannel());

    @Override
    public L1Store store(Keyspace keyspace) {
        return FutureL1Store.adapt(new MyStore(keyspace));
    }

    @Override
    public InvalidationChannel getChannel() {
        return channel;
    }
    // atomicBroadcast, format, close …
}
```

- A future may complete on an I/O thread. Only a backend with a blocking client completes its futures on an executor of its own; never block the caller's thread.
- Failing the future and throwing synchronously both work: the engine sees the original exception, unwrapped from `CompletionException` and `ExecutionException`.
- Bound every future, as above. The engine never cancels a returned future.
- A future you cancel yourself (to implement a timeout, say), and a `CancellationException` thrown synchronously, count as a failure with an unknown outcome: the adapter turns it into an `IllegalStateException` with the cancellation as its cause. Releasing the tokens of such a command is still your job.
- Never return `null`, and never complete a future whose result type is non-null with `null` (`Void` results and the `Integer` of member writes may be `null`).
- `adapt` reads each capability accessor once and adapts that surface once, so the adapted store's accessors are stable.

### Decorators: `ForwardingL1Store` {#decorators}

A decorator around another backend's store (metrics, tracing, fault injection) extends `ForwardingL1Store` and overrides only what it intercepts. The capability accessors return the wrapped store's surfaces unchanged, so their operations bypass the decorator. To intercept them, override the accessor and return a `ForwardingConditionalL1Store`, `ForwardingVersionedL1Store` or `ForwardingSetL1Store` created once, in the constructor, and `null` when the wrapped store has none:

```kotlin
class CountingStore(delegate: L1Store) : ForwardingL1Store(delegate) {
    val conditionalWrites = AtomicInteger()

    private val conditional = delegate.conditional()?.let { surface ->
        object : ForwardingConditionalL1Store(surface) {
            override suspend fun putIfMatch(
                key: String,
                expected: Digest?,
                envelope: Envelope.Scalar,
                ttl: Duration?,
                frame: ByteArray
            ): L1ConditionalWrite {
                conditionalWrites.incrementAndGet()
                return super.putIfMatch(key, expected, envelope, ttl, frame)
            }
        }
    }

    override fun conditional(): ConditionalL1Store? = conditional
}
```

The TCK's `HookedBackend` is built this way.

### `InvalidationChannel` {#invalidation-channel}

```kotlin
interface InvalidationChannel {
    fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription  // blocking, once per cache
    suspend fun publish(keyspace: Keyspace, frame: ByteArray)  // called only when atomicBroadcast == false
}
interface InvalidationListener {
    fun onInvalidated(keys: List<String>, origin: NodeId)
    fun onReset()
}
fun interface Subscription : AutoCloseable
```

`publish` follows the store's rules on time limits, cancellation and failures. A Java backend implements `FutureInvalidationChannel` and returns `FutureInvalidationChannel.adapt(…)`.

- **Frames.** `frame` is built by the engine with `InvalidationFrame.encode(origin, storageKeys)`. Deliver it to every listener of the keyspace, decoded with `InvalidationFrame.decode`, with the keys passed through `keyspace.encodedKeys(…)`: listeners receive encoded keys (the storage key without `{keyPrefix}{namespace}:`), and keys outside the keyspace are dropped. Call the listeners of a keyspace one after another, in the order they subscribed, for frames and resets alike: a cache subscribes when it is created, so a listener added later runs after the cache has dropped its copies. When `decode` rejects a frame (an unknown version, including the reserved `NAMESPACE_RESET_VERSION`, a truncated frame, trailing bytes), call `onReset()` on every listener of that channel instead of dropping it: a dropped frame is a lost invalidation. A frame with no keys is valid; deliver it, and listeners ignore it. Don't filter by origin: listeners ignore their own `NodeId`.
- **At most once is enough, silence is not.** Call `onReset()` on every listener of a subscription each time it is established or re-established **and confirmed**, and whenever messages may have been lost (a failed heartbeat, a consumer-group rebalance, a gap in a sequence). The listener then clears its L2. Without resets, a lost message leaves L2 stale until `local-max-age`.
- **Reset before `subscribe` returns.** The first listener's reset must have run when `subscribe` returns, or a late reset clears copies filled after subscribing. Later listeners on an existing subscription get one `onReset()` right away.
- **Isolation.** Subscribing a new keyspace must not reset other keyspaces. A listener that throws, even an `Error`, must not keep other listeners from receiving the message or the reset. The Redis and in-memory backends log the failure and go on. While delivering a message they rethrow only a `VirtualMachineError`.
- **Failure.** If `subscribe` fails, throw and leave no partial registration: the runtime rolls back the cache.
- Closing the `Subscription` unsubscribes that listener. `close()` only guarantees that messages and resets arriving after it returns are not delivered. A callback already being delivered may reach the listener after `close()` returns, so a listener must tolerate a late callback. The engine's listener does: a late callback only evicts L2 copies.

## TCK {#tck}

`cocache-test` is the published TCK. Every backend must pass it.

```kotlin
testImplementation("me.ahoo.cocache:cocache-test")
```

| Suite | Covers | You provide |
|-------|--------|-------------|
| `BackendProtocolSpec` | The `L1Store` contract, called directly through `Suspending.await`: lease grant, token checks, `L1Fill` results, expiry resets, release, refresh tokens, reclaim, batch reads and writes, set updates, broadcast frames, reset on subscribe, and (optional) a read `timeout` bounding the call under a stalled L1 and releasing its lease | `createBackend()`, `expectedCapabilities`, `elapse(duration)`. Optionally `shortTtl` (default 300 ms) and `pause()`. |
| `EngineSpec` | `Cache` and `Store` on two runtimes on your backend: single load across instances, lease-wait timeout, invalidation during a load, same-instance invalidate-vs-read race, concurrent `put`s, read-your-writes, loader failure, Redis failure, invalidation retry, reconnect reset, `local-max-age`, batch reads, refresh-ahead, reclaim, newer format, load limit, (optional) a cache's `readTimeout` bounding its single, batch and conditional reads under a stalled L1, `Lookup.Unknown` for reads without a source (including a partial `getAll`), an externally sourced cache's unfilled miss and retried invalidation, and with `CONDITIONAL` the `Store`: reads without a lease, writer ids and digests (a tampered digest fails the read, a derived cache counts a type mismatch), synchronous deletes, and conditional reads, writes and deletes (including a concurrent read-modify-write that forces every writer to race on one digest and asserts exactly one wins) | `createBackend()`, `expectedCapabilities`, `clock`, `elapse(duration)`, `simulateReconnect()`. Optionally `pause()`. |
| `VersionedSpec` | With `VERSIONED`, `VersionedCache` and `VersionedStore`: the protocol (versions stored by whole writes, compared as signed 64-bit numbers, a versioned write destroying a held lease, no refresh tokens or reclaim on versioned content, tombstones) and two runtimes on your backend: ordering, equal-version idempotence, older-version refusal without a broadcast or TTL change (an equal version broadcasts again without atomic broadcast, so a replay repairs a lost publish), tombstones against older redelivery, a versioned write winning over a fill that holds the lease, fills storing the loader's version, concurrent out-of-order writers converging on the highest version, safe replay after an unknown outcome, `evict` keeping the version and `purge` dropping it, an eviction refused on a `Store` value, an unversioned `null` refused over versioned content, and a `VersionedStore` reading without a lease, writing a writer id, and letting tombstones expire | `createBackend()`, `expectedCapabilities`, `clock`, `elapse(duration)` |
| `SetEngineSpec` | With `SETS`: `SetStore` across two runtimes: atomic concurrent adds, empty-set deletion, `put` and `putAll` replacing sets, member writes refusing unreadable content, write failures, and a read failure answering `Unknown` | `createBackend()`, `expectedCapabilities`, `clock`, `elapse(duration)` |
| `MultiNodeSpec` | Three runtimes on your backend: invalidation before a lease, after a lease while another instance waits on it, and after a fill but before the L2 write; a remote write landing between a read and its L2 fill; concurrent `put`s from different instances (events arriving in reverse order on non-atomic backends); misses while an invalidation retry is pending; exactly one source load and one refresh per key across all instances | `createBackend()`, `expectedCapabilities`, `clock`, `elapse(duration)` |
| `FaultSpec` | Your backend under L1 faults: copies bounded by `degraded-local-max-age` while the store fails, a failed fill and its lease, a failed `Store.delete` throwing instead of retrying, invalidation retries that give up or overflow the queue, `put` and `invalidate` that take effect but report an error and are safe to retry; on a backend that broadcasts atomically, an outcome-unknown `put` that runs late evicts the writer's own stale copy through its own broadcast; (optional) writes rejected while disconnected are not replayed after the reconnect; (optional) commands your client cancels are L1 failures, not cancellations | `createBackend()`, `expectedCapabilities`, `clock`, `elapse(duration)`; optionally `disconnect()` and `commandCancellation()` |

There is one set of suites for every API. Each suite has a `protected open val access: CacheAccess`, and a backend only needs the default, `CacheAccess.SYNC`. How the blocking, [async](../guide/async.md), and suspending APIs behave (cancellation, the thread a future completes on, waits that hold no threads, and so on) belongs to the core, and CoCache's own tests cover it. CoCache overrides `access` to rerun the suites through the async view (`CacheAccess.ASYNC`). You can do the same if you also want that coverage on your backend.

A Java backend runs the same suites on the store that `FutureL1Store.adapt` returns; CoCache runs the whole TCK on its in-memory backend that way.

### Declaring capabilities {#expected-capabilities}

Every suite has an abstract `expectedCapabilities: Set<L1Capability>` with no default, so a subclass that doesn't declare it doesn't compile. Declare the capabilities your store provides:

```kotlin
override val expectedCapabilities = setOf(L1Capability.CONDITIONAL, L1Capability.VERSIONED, L1Capability.SETS)
```

```java
@Override
protected Set<L1Capability> getExpectedCapabilities() {
    return EnumSet.of(L1Capability.CONDITIONAL, L1Capability.SETS);
}
```

- **The TCK fails closed.** Each suite's `storeProvidesExactlyTheDeclaredCapabilities` fails when the store provides a capability you didn't declare or lacks one you did, and when an accessor returns a different object on another call or switches between `null` and a surface. It checks two fresh namespaces.
- **Cases skip only for undeclared capabilities.** The `Store` cases of `EngineSpec` need `CONDITIONAL`; the set cases of `BackendProtocolSpec`, and every case of `SetEngineSpec`, need `SETS`; every case of `VersionedSpec` needs `VERSIONED`. A capability you declare must pass its cases, including the concurrent ones that only an atomic implementation passes.
- `L1Capability` exists only in the TCK. In the SPI, the accessors are the only way a store states a capability.

### Wiring the suites {#tck-hooks}

The suites wrap your backend in `HookedBackend`, which injects faults (`failAll`, `failWrites`, `failFill`, `failRelease`, and `rejectWrites`, which throws `L1RejectedException` before a write reaches your store; `deferPuts` fails single-key puts with an unknown outcome and holds them until `applyDeferredPuts()` runs them), rewrites read results, runs hooks after reads and fills and around writes so races are orchestrated with latches instead of sleeps (a throwing `afterPut` or `afterInvalidate` simulates a write that took effect but reported an error), counts calls (`reads`, `readAlls`, `fills`, `puts`, `putAlls`), and can override `atomicBroadcast` to exercise the non-atomic path. Use it in your own tests too.

`elapse(duration)` must move time forward for both your store and the engine:

- **In-process backend:** advance a `ManualClock` shared by the backend and `clock`.
- **Real server:** advance `ManualClock` for the engine, and shorten the remaining TTL of the test keys on the server by `duration`. For short durations in `BackendProtocolSpec`, sleeping also works. Token issue times come from the server clock.

`simulateReconnect()` must break the subscription so your channel resubscribes, for example by killing its connection on the server.

The optional hooks return `null` by default, and their cases are skipped:

- **`BackendProtocolSpec.pause()`** and **`EngineSpec.pause()`** stall your backend's commands until the returned handle is closed (or for a fixed time of at least ten times the case's read timeout): commands started meanwhile don't complete. CoCache's Redis tests route the backend through a local TCP proxy that holds client data; `InMemoryBackend.stall()` does the same in process.
- **`FaultSpec.disconnect()`** cuts the command connection and keeps it down until the returned handle is closed. Commands issued meanwhile must fail instead of being buffered. A proxy that closes its listener works for a real server.
- **`FaultSpec.commandCancellation()`** returns a `CommandCancellation`: `hold()` makes later commands stay in flight in your client, `cancel()` has the client cancel them, and `close()` restores normal operation (a reconnect is fine). The case then checks that a read whose command was cancelled falls back to the loader and serves the readers waiting on it, that a `put` fails with `CacheWriteException`, that no caller sees a `CancellationException`, and that the cancelled read's lease is gone once L1 is back. CoCache's in-memory backend holds and then cancels the futures of its `FutureL1Store`; its Redis tests drop replies at the proxy, have Lettuce reset its connections, which cancels the commands in flight, and reconnect.

The in-memory backend's TCK classes are a minimal template:

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

// MultiNodeSpec and VersionedSpec have the same members as SetEngineSpec
class MyFaultTest : FaultSpec() {
    override val expectedCapabilities = CAPABILITIES
    private val manualClock = ManualClock()
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock)
    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
```

Each case creates a backend through `createBackend()`. If yours opens connections or threads, override `closesBackend` to `true` so the suites close it after each case; it defaults to `false` because a backend may be shared. `BackendProtocolSpec` always closes the backend it creates, whatever `closesBackend` says.

Run them with your build's normal test task (JUnit 6). `cocache-redis` runs `BackendProtocolSpec`, `EngineSpec`, `MultiNodeSpec`, `FaultSpec` and `VersionedSpec` in three modes: classic Pub/Sub, non-atomic (an ACL denies the probe channel), and sharded. `SetEngineSpec` runs on classic Pub/Sub only, and the async view reruns each engine suite once, on classic Pub/Sub. The disconnect and cancellation cases of `FaultSpec` run on standalone classic Pub/Sub, and `cocache-redis` adds its own case for the `NOSCRIPT` fallback after the script cache is flushed. Run the suites for every mode your backend supports.

## Codecs {#codecs}

```kotlin
interface Codec<V> {
    fun encode(value: V): ByteArray
    fun decode(bytes: ByteArray): V
}
```

- **`decode` throws on bytes it can't read.** The engine then treats the entry as corrupt: a miss, a conditional reclaim, and a reload. Don't return a default object.
- **Thread-safe and stateless.** One instance serves every thread.
- **Never sees `null`.** Negative entries are handled by the engine.
- **A set element codec must be canonical.** `SetStore` members are compared by their encoded bytes, so equal elements must always encode to identical bytes. JSON with unstable field order breaks that.
- **The byte format is part of the cache's wire format.** Changing it in a running cluster makes old and new instances reclaim each other's values. Use a new namespace or key prefix for an incompatible change, or make `decode` accept both forms during the rollout.
- **Keep payloads small.** All of an instance's commands share one connection, and a large value delays commands behind it.

`StringCodec` (UTF-8) is in `cocache-core`, and `JacksonCodec` (`jacksonCodec<V>(mapper)`) in `cocache-codec-jackson`. Interface caches without a codec bean use `JacksonCodec` with the application's `ObjectMapper`.

## Functions {#functions}

| Function | Contract |
|----------|----------|
| `CacheLoader<K, V>` | `load(key)` returns the value, or `null` for "doesn't exist" (stored as a negative entry for `absent-ttl`). `loadAll(keys)` loads a batch, and keys missing from its result are negative. The default calls `load` per key, so override it with one query. Runs on the caller's thread. Must not read its own cache for the same key (that fails fast). Must read the primary, not a lagging replica. |
| `KeyEncoder<K>` | Turns a key into the string after `{keyPrefix}{namespace}:`. Must be **injective** (distinct keys, distinct strings), deterministic, and identical on every instance and version. The default is `toString()`, which rejects types that don't override it. |
| `AsyncCacheLoader<K, V>` | The asynchronous form of `CacheLoader`: `loadAsync(key)` returns a `CompletionStage`, and completing it with `null` means "doesn't exist". Must return quickly without blocking: it runs on the calling thread or a CoCache thread, without the caller's thread-locals. Bounded by `load-timeout`. See [Async API](../guide/async.md#loader). |
| `KeyFilter` | `notExist(storageKey)` receives the full storage key. Return `true` only for keys that certainly don't exist: a Bloom filter must contain every existing key. A `true` answer skips Redis and the loader, and caches nothing. Must be a local, non-blocking check: async callers invoke it on their own thread, which may be an event loop. |
| `TtlFunction<K, V>` (`ttlOf`) | A per-entry lifetime. A non-null result replaces `ttl` and gets no jitter. Clamped to between 1 ms and 100 years. Not applied to negative entries. |

## What not to customize {#not-pluggable}

- **L2.** It is always a bounded Caffeine cache, with expiry checked on read against the ticking clock. A pluggable L2 would make the hit path polymorphic and let two caches share one by mistake. Tune it with `local-maximum-size`.
- **The fences and the write order.** Leases, refresh tokens, stamps, the double bump, and filling L2 only from L1 are the protocol. A backend implements the L1 side, and the engine owns the rest.
- **Policy inside a store.** Jitter, negative TTLs, and refresh windows belong to the engine. A store only sees the durations it is given.
- **The Redis wire format within a major version.** Other code may read CoCache keys for diagnosis, but must write them only through the [invalidation protocol](../operations/troubleshooting.md#external-invalidation).
- **Shared state between caches.** Every stateful component is created by `CacheRuntime.create` per cache. Pass specs and stateless functions, never L2 instances, stores, or stamps.
