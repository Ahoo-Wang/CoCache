---
title: Extending CoCache
description: Implementing a CoherenceBackend (RemoteStore and InvalidationChannel) or a Codec — the exact SPI contracts, the cocache-test TCK suites and how to run them, codec rules, and what not to customize.
---

# Extending CoCache

| You want to | Implement | Verify with |
|-------------|-----------|-------------|
| Store L1 somewhere other than Redis, or talk to Redis differently | [`CoherenceBackend`](#backend) | [`BackendProtocolSpec`, `EngineSpec`, `SetEngineSpec`, `MultiNodeSpec`, `FaultSpec`](#tck) |
| Change how values become bytes | [`Codec<V>`](#codecs) | Your own round-trip tests |
| Load, encode keys, reject impossible keys, choose lifetimes per entry | [Functions](#functions): `CacheLoader`, `KeyEncoder`, `KeyFilter`, `TtlFunction` | Your own tests |

The SPI lives in `me.ahoo.cocache.api.spi` (`cocache-api`, no dependencies). A backend needs only `cocache-api` at runtime and `cocache-test` for its tests.

In a Spring Boot application, a `CoherenceBackend` bean replaces the Redis backend (the starter backs off), and Spring closes it on shutdown. The single `Codec<V>` bean is used by every interface cache whose value type is `V`, unless the cache names another with `@CoCache(codec = "beanName")`.

## Backend {#backend}

```kotlin
interface CoherenceBackend : AutoCloseable {
    val atomicBroadcast: Boolean               // can a write publish its frame in the same atomic step?
    val format: Int                            // wire format version this implementation writes
    val channel: InvalidationChannel
    fun store(namespace: String): RemoteStore  // called once per cache
}
```

L1 storage and the invalidation channel are two faces of one object, because the side that serializes a write should also announce it.

- **`atomicBroadcast`.** If `true`, every write method of the store must publish its `frame` in the same atomic step as the change. If `false`, the engine calls `channel.publish(namespace, frame)` after each successful write. Atomic is better: a crash between write and publish loses the message. Declare only what you can guarantee. The TCK checks both kinds.
- **`format`.** The engine compares it with `RemoteRead.Hit.format`: a higher stored format is loaded around and never overwritten, and `0` means content your protocol didn't write, which the engine treats as corrupt. Start at `1`, and bump it only through a two-phase upgrade.

### `RemoteStore` {#remote-store}

```kotlin
interface RemoteStore : AutoCloseable {
    fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead
    fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead>
    fun fill(fill: Fill): Boolean
    fun fillAll(fills: List<Fill>): List<Boolean>
    fun release(key: String, token: Token)
    fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray)
    fun putAll(puts: List<Put>, frame: ByteArray)   // default: put per entry
    fun invalidate(keys: Collection<String>, frame: ByteArray)
    fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean
    fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray)
    fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray)
    // since 6.6.0; default: read, then addMembers / removeMembers (not atomic)
    fun addMembersIfReadable(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int?
    fun removeMembersIfReadable(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int?
    // since 6.7.0; readIfChanged default: read, compare on the client; putIfMatch default: throws UnsupportedOperationException
    fun readIfChanged(key: String, known: Digest?): RemoteConditionalRead
    fun putIfMatch(
        key: String, expected: Digest?, envelope: Envelope.Scalar, ttl: Duration?, frame: ByteArray, foreignAsAbsent: Boolean
    ): RemoteConditionalWrite
    // since 6.8.0; default: throws UnsupportedOperationException. Also set CoherenceBackend.versionedWrites = true.
    fun putIfNewer(
        key: String, envelope: Envelope, version: Long, ttl: Duration?, frame: ByteArray, foreignAsAbsent: Boolean
    ): RemoteVersionedWrite
}
```

Keys arrive complete, with prefix and namespace. A store handles bytes and tokens only, and holds no policy: every TTL, window, and token is an argument. The types:

| Type | Shape |
|------|-------|
| `Envelope` | `Scalar(bytes, version)`, `Members(list of bytes)`, or `Absent` (a negative entry). `version` (since 6.8) defaults to `Versioned.NO_VERSION`; it takes part in equality but not in the digest. |
| `RemoteRead` | `Hit(envelope, format, ttl, refreshGranted)`, `Miss(leaseGranted)`, or `Leased(remaining)`. `ttl == null` means no expiry. |
| `Token` | 24 bytes: `NodeId` (16) + sequence (8). Unique, never reused. Compare by bytes. |
| `LeaseRequest(token, ttl)` | Lease to place on a miss, expiring after `ttl` |
| `RefreshRequest(window, token, tokenTtl)` | Refresh token to place on a value inside `window`. A token older than `tokenTtl` may be taken over. |
| `Fill(key, token, envelope, ttl[, version])` | Write `envelope` with `ttl` only if `key` holds `token`. `version` (since 6.8, not part of `copy`) defaults to the scalar's version; `Absent` with a version is a tombstone. |
| `Put(key, envelope, ttl)` | One entry of `putAll`: overwrite `key` like `put` |
| `Digest` | SHA-1 of the content: scalar → payload bytes, set → each member as `int32 length + bytes` in returned order, negative entry → empty |
| `RemoteConditionalRead` | `Unchanged(format)`, `Changed(envelope, format)` (a scalar value), `Absent` (absent, lease, or negative entry), or `Unreadable(format)` |
| `RemoteConditionalWrite` | `Written`, `Conflict(current)` (`current == null`: no value), or `Unreadable(format)` |
| `RemoteVersionedWrite` | `Written`, `Stale(current)`, or `Unreadable(format)` |

The contract, method by method. The state machine is in [Consistency](./consistency.md#l1-fence).

| Method | Must |
|--------|------|
| `read` | Absent key and `lease != null`: place the lease (expiring after `lease.ttl`) and return `Miss(true)`. Absent and no lease: `Miss(false)`, write nothing. Leased key: `Leased(remaining)`. Value: `Hit` with the remaining TTL. A scalar value whose remaining TTL is `> 0` and `≤ refresh.window`, with no token or a token older than `refresh.tokenTtl`: store the refresh token (keeping the TTL) and return `refreshGranted = true`. Never on a value without expiry. Content you didn't write: `Hit(…, format = 0)`. With both requests `null`, `read` is pure and writes nothing. |
| `readAll` | `read` for each key, requesting a lease only for keys in `leases`. Return every key. If any key fails, release the leases already granted in this call (otherwise every instance waits for `lease-ttl`), then throw. |
| `fill` / `fillAll` | Write only if the key currently holds exactly `fill.token`, as a lease or a refresh token, and reset the expiry to `fill.ttl`. Return whether you wrote. A backend with `versionedWrites` stores `fill.version` with the content: a versioned loader's `Versioned(null, n)` arrives as `Absent` with version `n` and must become the same tombstone `putIfNewer` writes. |
| `release` | Token matches a lease: delete the key. Token matches a refresh token on a value: clear the token, keep the value and its TTL. Otherwise do nothing. |
| `put` | Overwrite any state, destroying any token, set the expiry, broadcast `frame`. A `Members` envelope replaces the whole set. |
| `putAll` | `put` for each entry, atomic per key, not across keys. `frame` carries every key: broadcast each key exactly once with its write (you may split the frame, for example per slot group). Optional: the default calls `put` once per entry with a one-key frame. |
| `invalidate` | Delete every key, destroying any token, and broadcast `frame`. Atomic per key. Batches may split. |
| `reclaim` | Only if the key's current content has digest `observed` and isn't a lease: replace it with the lease. Return whether you did. Never delete content that changed. |
| `addMembers` | Key holds a set: add the members atomically. Otherwise (absent, negative entry, lease, scalar): replace it with a set of these members. Destroy any token, set the expiry (`null` removes it), broadcast. |
| `removeMembers` | Only if the key holds a set: remove the members, delete the key when the set becomes empty, otherwise reset the expiry. Broadcast in every case. |
| `addMembersIfReadable` / `removeMembersIfReadable` | Like `addMembers` / `removeMembers`, except when the key holds content that isn't a set of your protocol: content you didn't write, a scalar value, or a set in a newer format than yours. Then change nothing, broadcast nothing, and return that content's format (`0` for content you didn't write). Return `null` after a normal write. Absent keys, negative entries, and leases are written normally. A `SetCache` with `foreign-format: fail` (its default) calls these instead of the plain methods, because overwriting is deleting its only copy. Optional: the default reads first and then writes, which isn't atomic and can't recognize a newer format, so override it with one atomic check-and-write. |
| `readIfChanged` | Pure, like `read` with no requests. A scalar value of your protocol whose payload digest equals `known`: return `Unchanged(format)` without sending the payload back (the digest covers only the payload, so the engine checks the format). Another scalar value: `Changed(envelope, format)`. Absent key, lease, or negative entry: `Absent`. Anything else (content you didn't write, a set, a newer format): `Unreadable(format)`. Optional: the default calls `read` and compares on the client, so it always transfers the payload. Override it to compare in your store, which is the point of the method. |
| `putIfMatch` | One atomic compare-and-write. Current content matches when `expected == null` and the key holds no value (absent, lease, negative entry), or when it's a scalar value whose payload digest equals `expected`. Content `readIfChanged` would call `Unreadable`: return `Unreadable(format)`, except that content you didn't write (format `0`) matches as no value when `foreignAsAbsent` is set. A set, or content in a newer format, is never treated as no value, or an older instance would downgrade it during a rolling upgrade. On a match, write like `put` (destroy tokens, set the expiry, broadcast `frame`) and return `Written`. Otherwise change nothing, broadcast nothing, and return `Conflict(digest of the current value, or null)`. The default throws `UnsupportedOperationException`, which the engine passes to the caller unchanged: a read-then-put default would let two concurrent writers both succeed. Implement it only if your store can compare and write atomically. A native `AsyncRemoteStore` must override `putIfMatchAsync` too: its default fails with the same exception rather than block on the sync method. |
| `putIfNewer` | Only for backends that set `CoherenceBackend.versionedWrites = true` (default `false`: [versioned caches](../guide/freshness.md#versioned-writes) then fail at creation). One atomic compare-and-write by version. The key's version is the one stored with a scalar value or negative entry; a value or negative entry without a version, a lease, and an absent key count as `NO_VERSION` and are always written. If `version` is greater: write `envelope` (a scalar, or `Absent` for a tombstone) like `put`, store `version` with it, and return `Written`. Otherwise change nothing, broadcast nothing, and return `Stale(current)`. Classify unreadable content as `putIfMatch` does. Versioned content holds no token: `fill` and `release` must not match it, `read` must never grant a refresh token on it, and `reclaim` must refuse it, because a lease can't carry the version. `fill`, `put` and `putAll` store `Envelope.Scalar.version` and `read` returns it; `putIfMatch` writes a value without a version. The defaults throw (sync) or fail (async) with `UnsupportedOperationException`, for the same reason as `putIfMatch`. |

Across all methods:

- **Atomic per key.** Batch methods are atomic per key, not across keys.
- **Reset the expiry on every state change**, at millisecond precision. A lease's or a negative entry's lifetime must never leak onto a new value.
- **Never delete on read.**
- **Throw on failure.** Don't swallow errors or retry internally: the engine decides whether to degrade, retry, or rethrow, and counts it.
- **Reject commands while disconnected** rather than buffering and replaying them.

### `InvalidationChannel` {#invalidation-channel}

```kotlin
interface InvalidationChannel {
    fun subscribe(namespace: String, listener: InvalidationListener): Subscription
    fun publish(namespace: String, frame: ByteArray)   // called only when atomicBroadcast == false
}
interface InvalidationListener {
    fun onInvalidated(keys: List<String>, origin: NodeId)
    fun onReset()
}
fun interface Subscription : AutoCloseable
```

- **Frames.** `frame` is built by the engine with `InvalidationFrame.encode(origin, storageKeys)`. Deliver it to every listener of the namespace, decoded with `InvalidationFrame.decode`. Drop malformed frames. Don't filter by origin: listeners ignore their own `NodeId`.
- **At most once is enough, silence is not.** Call `onReset()` on every listener of a subscription each time it is established or re-established **and confirmed**, and whenever messages may have been lost (a failed heartbeat, a consumer-group rebalance, a gap in a sequence). The listener then clears its L2. Without resets, a lost message leaves L2 stale until `local-max-age`.
- **Reset before `subscribe` returns.** The first listener's reset must have run when `subscribe` returns, or a late reset clears copies filled after subscribing. Later listeners on an existing subscription get one `onReset()` right away.
- **Isolation.** Subscribing a new namespace must not reset other namespaces. A listener that throws must not keep other listeners from receiving the message.
- **Failure.** If `subscribe` fails, throw and leave no partial registration: the runtime rolls back the cache.
- Closing the `Subscription` unsubscribes that listener.

## TCK {#tck}

`cocache-test` is the published TCK. Every backend must pass it.

```kotlin
testImplementation("me.ahoo.cocache:cocache-test")
```

| Suite | Covers | You provide |
|-------|--------|-------------|
| `BackendProtocolSpec` | The `RemoteStore` contract: lease grant, token checks, expiry resets, release, refresh tokens, reclaim, batch reads and writes, set updates, broadcast frames, reset on subscribe | `createBackend()`, `elapse(duration)`. Optionally `shortTtl` (default 300 ms). |
| `EngineSpec` | Two runtimes on your backend: single load across instances, lease-wait timeout, invalidation during a load, same-instance invalidate-vs-read race, concurrent `put`s, read-your-writes, loader failure, Redis failure, invalidation retry, reconnect reset, `local-max-age`, batch reads, refresh-ahead, reclaim, newer format, load limit, conditional reads and writes (including concurrent read-modify-write losing no update) | `createBackend()`, `clock`, `elapse(duration)`, `simulateReconnect()`. If your store doesn't implement `putIfMatch`, override `supportsConditionalWrites = false` to skip the conditional-write cases. Otherwise they must pass, including a concurrent case that forces every writer to race on one digest and asserts exactly one wins. |
| `VersionedSpec` | Only for backends with `versionedWrites`. The protocol (versions stored by whole writes, compared as signed 64-bit numbers, a versioned write destroying a held lease, no refresh tokens or reclaim on versioned content, tombstones) and two runtimes on your backend: ordering, equal-version idempotence, stale refusal without a broadcast or TTL change, tombstones against older redelivery, a versioned write winning over a fill that holds the lease, fills storing the loader's version, concurrent out-of-order writers converging on the highest version, and safe replay after an unknown outcome. `AsyncVersionedSpec` runs it through the async view. | `createBackend()`, `clock`, `elapse(duration)` |
| `SetEngineSpec` | `SetCache` across two runtimes: atomic concurrent adds, empty-set deletion, `invalidate` rejected, write and read failures | `createBackend()`, `clock`, `elapse(duration)` |
| `MultiNodeSpec` | Three runtimes on your backend: invalidation before a lease, after a lease while another instance waits on it, and after a fill but before the L2 write; a remote write landing between a read and its L2 fill; concurrent `put`s from different instances (events arriving in reverse order on non-atomic backends); misses while an invalidation retry is pending; exactly one source load and one refresh per key across all instances | `createBackend()`, `clock`, `elapse(duration)` |
| `FaultSpec` | Your backend under L1 faults: copies bounded by `degraded-local-max-age` while the store fails, a failed fill and its lease, invalidation retries that give up or overflow the queue, `put` and `invalidate` that take effect but report an error and are safe to retry, and (optional) writes rejected while disconnected are not replayed after the reconnect | `createBackend()`, `clock`, `elapse(duration)`; optionally `disconnect()` |
| `AsyncEngineSpec`, `AsyncSetEngineSpec`, `AsyncMultiNodeSpec`, `AsyncFaultSpec` | Every case of the corresponding suite again through the [async view](../guide/async.md), plus async-only cases in `AsyncEngineSpec`: cancellation, the thread a future completes on, lease and permit waits that hold no threads, original exceptions, no synchronous throws, `load-async-timeout`, sync and async calls joining one load, reentrancy | The same members as the corresponding suite |
| `AsyncBackendProtocolSpec` | Every `BackendProtocolSpec` case through the `AsyncRemoteStore` methods (no synchronous throws, original exceptions), plus sync and async calls on one key agreeing | The same members as `BackendProtocolSpec`. Only for backends that implement `AsyncRemoteStore`. |

The suites wrap your backend in `HookedBackend`, which injects faults (`failAll`, `failWrites`, `failFill`, `failRelease`), rewrites read results, runs hooks after reads and fills and around writes so races are orchestrated with latches instead of sleeps (a throwing `afterPut` or `afterInvalidate` simulates a write that took effect but reported an error), and can override `atomicBroadcast` to exercise the non-atomic path. Use it in your own tests too.

`elapse(duration)` must move time forward for both your store and the engine:

- **In-process backend:** advance a `ManualClock` shared by the backend and `clock`.
- **Real server:** advance `ManualClock` for the engine, and shorten the remaining TTL of the test keys on the server by `duration`. For short durations in `BackendProtocolSpec`, sleeping also works. Token issue times come from the server clock.

`simulateReconnect()` must break the subscription so your channel resubscribes, for example by killing its connection on the server.

`disconnect()` in `FaultSpec` is optional. If your backend holds connections, override it to cut the command connection and keep it down until the returned handle is closed (CoCache's Redis tests route the backend through a local TCP proxy that closes its listener). Commands issued meanwhile must fail instead of being buffered. The default returns `null`, and the disconnect case is skipped.

The in-memory backend's TCK classes are a minimal template:

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

// MultiNodeSpec has the same members as SetEngineSpec
class MyFaultTest : FaultSpec() {
    private val manualClock = ManualClock()
    override val clock: CacheClock = manualClock
    override fun createBackend(): CoherenceBackend = MyBackend(manualClock)
    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
```

Each case creates a backend through `createBackend()`. If yours opens connections or threads, override `closesBackend` to `true` so the suites close it after each case; it defaults to `false` because a backend may be shared. `BackendProtocolSpec` always closes the backend it creates, whatever `closesBackend` says.

Run them with your build's normal test task (JUnit 6). `cocache-redis` runs `BackendProtocolSpec`, `EngineSpec`, `MultiNodeSpec`, and `FaultSpec` in three modes: classic Pub/Sub, non-atomic (an ACL denies the probe channel), and sharded. `SetEngineSpec` runs on classic Pub/Sub only. The disconnect case of `FaultSpec` runs on standalone classic Pub/Sub, and `cocache-redis` adds its own case for the `NOSCRIPT` fallback after the script cache is flushed. Run the suites for every mode your backend supports.

## Async backends {#async}

A backend that implements only `RemoteStore` works with the [async view](../guide/async.md) unchanged: the engine calls its synchronous methods on the blocking executor. To serve async callers without those threads, implement the optional sub-interfaces:

- `AsyncRemoteStore extends RemoteStore` adds `readAsync`, `readAllAsync`, `fillAsync`, `fillAllAsync`, `releaseAsync`, `putAsync`, `putAllAsync`, `invalidateAsync`, `reclaimAsync`, `addMembersAsync`, `removeMembersAsync`, and (with defaults built on `readAsync`) `addMembersIfReadableAsync`, `removeMembersIfReadableAsync`, and `readIfChangedAsync`, plus `putIfMatchAsync` and `putIfNewerAsync`, whose defaults fail with `UnsupportedOperationException`. Each has exactly the semantics of its synchronous counterpart.
- `AsyncInvalidationChannel extends InvalidationChannel` adds `publishAsync`, which the engine uses on non-atomic backends.
- **Never throw synchronously.** Fail the returned future instead, with the same exception types as the synchronous methods, not a `CompletionException`.
- **Bound every operation.** A future that never completes stalls that key on that instance until `load-async-timeout`.
- **Complete on any thread.** The engine switches to its own executor before running engine code or user code, so completing on an I/O thread is fine. Keep that completion cheap, and don't call codecs or user code there.

Then run `AsyncBackendProtocolSpec` and the four async engine suites.

`InMemoryBackend` implements the whole protocol in one process. Several runtimes sharing one instance behave like several instances, which makes it a good backend for application tests.

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
- **A set element codec must be canonical.** `SetCache` members are compared by their encoded bytes, so equal elements must always encode to identical bytes. JSON with unstable field order breaks that.
- **The byte format is part of the cache's wire format.** Changing it in a running cluster makes old and new instances reclaim each other's values. Use a new namespace or key prefix for an incompatible change, or make `decode` accept both forms during the rollout.
- **Keep payloads small.** All of an instance's commands share one connection, and a large value delays commands behind it.

`StringCodec` (UTF-8) is in `cocache-core`, and `JacksonCodec` (`jacksonCodec<V>(mapper)`) in `cocache-codec-jackson`. Interface caches without a codec bean use `JacksonCodec` with the application's `ObjectMapper`.

## Functions {#functions}

| Function | Contract |
|----------|----------|
| `CacheLoader<K, V>` | `load(key)` returns the value, or `null` for "doesn't exist" (stored as a negative entry for `absent-ttl`). `loadAll(keys)` loads a batch, and keys missing from its result are negative. The default calls `load` per key, so override it with one query. Runs on the caller's thread. Must not read its own cache for the same key (that fails fast). Must read the primary, not a lagging replica. |
| `KeyEncoder<K>` | Turns a key into the string after `{keyPrefix}{namespace}:`. Must be **injective** (distinct keys, distinct strings), deterministic, and identical on every instance and version. The default is `toString()`, which rejects types that don't override it. |
| `AsyncCacheLoader<K, V>` | The asynchronous form of `CacheLoader`: `loadAsync(key)` returns a `CompletionStage`, and completing it with `null` means "doesn't exist". Must return quickly without blocking: it runs on the calling thread or the async executor, without the caller's thread-locals. Bounded by `load-async-timeout`. See [Async API](../guide/async.md#loader). |
| `KeyFilter` | `notExist(storageKey)` receives the full storage key. Return `true` only for keys that certainly don't exist: a Bloom filter must contain every existing key. A `true` answer skips Redis and the loader, and caches nothing. Must be a local, non-blocking check: async callers invoke it on their own thread, which may be an event loop. |
| `TtlFunction<K, V>` (`ttlOf`) | A per-entry lifetime. A non-null result replaces `ttl` and gets no jitter. Clamped to between 1 ms and 100 years. Not applied to negative entries. |

## What not to customize {#not-pluggable}

- **L2.** It is always a bounded Caffeine cache, with expiry checked on read against the ticking clock. A pluggable L2 would make the hit path polymorphic and let two caches share one by mistake. Tune it with `local-maximum-size`.
- **The fences and the write order.** Leases, refresh tokens, stamps, the double bump, and filling L2 only from L1 are the protocol. A backend implements the L1 side, and the engine owns the rest.
- **Policy inside a store.** Jitter, negative TTLs, and refresh windows belong to the engine. A store only sees the durations it is given.
- **The Redis wire format within a major version.** Other code may read CoCache keys for diagnosis, but must write them only through the [invalidation protocol](../operations/troubleshooting.md#external-invalidation).
- **Shared state between caches.** Every stateful component is created by `CacheRuntime.create` per cache. Pass specs and stateless functions, never L2 instances, stores, or stamps.
