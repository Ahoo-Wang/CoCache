---
title: Extending CoCache
description: Implementing a CoherenceBackend (RemoteStore and InvalidationChannel) or a Codec — the exact SPI contracts, the cocache-test TCK suites and how to run them, codec rules, and what not to customize.
---

# Extending CoCache

| You want to | Implement | Verify with |
|-------------|-----------|-------------|
| Store L1 somewhere other than Redis, or talk to Redis differently | [`CoherenceBackend`](#backend) | [`BackendProtocolSpec`, `EngineSpec`, `SetEngineSpec`](#tck) |
| Change how values become bytes | [`Codec<V>`](#codecs) | Your own round-trip tests |
| Load, encode keys, reject impossible keys, choose lifetimes per entry | [Functions](#functions): `CacheLoader`, `KeyEncoder`, `KeyFilter`, `TtlFunction` | Your own tests |

The SPI lives in `me.ahoo.cocache.api.spi` (`cocache-api`, no dependencies). A backend needs only `cocache-api` at runtime and `cocache-test` for its tests.

In a Spring Boot application, a `CoherenceBackend` bean replaces the Redis backend (the starter backs off), and Spring closes it on shutdown. A `Codec<V>` bean is used by every interface cache whose value type is `V`, unless the cache names another with `@CoCache(codec = "beanName")`.

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
    fun invalidate(keys: Collection<String>, frame: ByteArray)
    fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean
    fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray)
    fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray)
}
```

Keys arrive complete, with prefix and namespace. A store handles bytes and tokens only, and holds no policy: every TTL, window, and token is an argument. The types:

| Type | Shape |
|------|-------|
| `Envelope` | `Scalar(bytes)`, `Members(list of bytes)`, or `Absent` (a negative entry) |
| `RemoteRead` | `Hit(envelope, format, ttl, refreshGranted)`, `Miss(leaseGranted)`, or `Leased(remaining)`. `ttl == null` means no expiry. |
| `Token` | 24 bytes: `NodeId` (16) + sequence (8). Unique, never reused. Compare by bytes. |
| `LeaseRequest(token, ttl)` | Lease to place on a miss, expiring after `ttl` |
| `RefreshRequest(window, token, tokenTtl)` | Refresh token to place on a value inside `window`. A token older than `tokenTtl` may be taken over. |
| `Fill(key, token, envelope, ttl)` | Write `envelope` with `ttl` only if `key` holds `token` |
| `Digest` | SHA-1 of the content: scalar → payload bytes, set → each member as `int32 length + bytes` in returned order, negative entry → empty |

The contract, method by method. The state machine is in [Consistency](./consistency.md#l1-fence).

| Method | Must |
|--------|------|
| `read` | Absent key and `lease != null`: place the lease (expiring after `lease.ttl`) and return `Miss(true)`. Absent and no lease: `Miss(false)`, write nothing. Leased key: `Leased(remaining)`. Value: `Hit` with the remaining TTL. A scalar value whose remaining TTL is `> 0` and `≤ refresh.window`, with no token or a token older than `refresh.tokenTtl`: store the refresh token (keeping the TTL) and return `refreshGranted = true`. Never on a value without expiry. Content you didn't write: `Hit(…, format = 0)`. With both requests `null`, `read` is pure and writes nothing. |
| `readAll` | `read` for each key, requesting a lease only for keys in `leases`. Return every key. If any key fails, release the leases already granted in this call (otherwise every instance waits for `lease-ttl`), then throw. |
| `fill` / `fillAll` | Write only if the key currently holds exactly `fill.token`, as a lease or a refresh token, and reset the expiry to `fill.ttl`. Return whether you wrote. |
| `release` | Token matches a lease: delete the key. Token matches a refresh token on a value: clear the token, keep the value and its TTL. Otherwise do nothing. |
| `put` | Overwrite any state, destroying any token, set the expiry, broadcast `frame`. A `Members` envelope replaces the whole set. |
| `invalidate` | Delete every key, destroying any token, and broadcast `frame`. Atomic per key. Batches may split. |
| `reclaim` | Only if the key's current content has digest `observed` and isn't a lease: replace it with the lease. Return whether you did. Never delete content that changed. |
| `addMembers` | Key holds a set: add the members atomically. Otherwise (absent, negative entry, lease, scalar): replace it with a set of these members. Destroy any token, set the expiry (`null` removes it), broadcast. |
| `removeMembers` | Only if the key holds a set: remove the members, delete the key when the set becomes empty, otherwise reset the expiry. Broadcast in every case. |

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
| `BackendProtocolSpec` | The `RemoteStore` contract: lease grant, token checks, expiry resets, release, refresh tokens, reclaim, batch reads, set updates, broadcast frames, reset on subscribe | `createBackend()`, `elapse(duration)`. Optionally `shortTtl` (default 300 ms). |
| `EngineSpec` | Two runtimes on your backend: single load across instances, lease-wait timeout, invalidation during a load, same-instance invalidate-vs-read race, concurrent `put`s, read-your-writes, loader failure, Redis failure, invalidation retry, reconnect reset, `local-max-age`, batch reads, refresh-ahead, reclaim, newer format, load limit | `createBackend()`, `clock`, `elapse(duration)`, `simulateReconnect()` |
| `SetEngineSpec` | `SetCache` across two runtimes: atomic concurrent adds, empty-set deletion, `invalidate` rejected, write and read failures | `createBackend()`, `clock`, `elapse(duration)` |

The suites wrap your backend in `HookedBackend`, which injects faults (`failAll`, `failWrites`, `failFill`, `failRelease`), rewrites read results, runs hooks before and after writes so races are orchestrated with latches instead of sleeps, and can override `atomicBroadcast` to exercise the non-atomic path. Use it in your own tests too.

`elapse(duration)` must move time forward for both your store and the engine:

- **In-process backend:** advance a `ManualClock` shared by the backend and `clock`.
- **Real server:** advance `ManualClock` for the engine, and shorten the remaining TTL of the test keys on the server by `duration`. For short durations in `BackendProtocolSpec`, sleeping also works. Token issue times come from the server clock.

`simulateReconnect()` must break the subscription so your channel resubscribes, for example by killing its connection on the server.

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
```

Run them with your build's normal test task (JUnit 5). `cocache-redis` runs all three suites three times: classic Pub/Sub, non-atomic (an ACL denies the probe channel), and sharded. Do the same for every mode your backend supports.

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
- **Keep payloads small.** Every instance shares one connection, and a large value delays commands behind it.

`StringCodec` (UTF-8) is in `cocache-core`, and `JacksonCodec` (`jacksonCodec<V>(mapper)`) in `cocache-codec-jackson`. Interface caches without a codec bean use `JacksonCodec` with the application's `ObjectMapper`.

## Functions {#functions}

| Function | Contract |
|----------|----------|
| `CacheLoader<K, V>` | `load(key)` returns the value, or `null` for "doesn't exist" (stored as a negative entry for `absent-ttl`). `loadAll(keys)` loads a batch, and keys missing from its result are negative. The default calls `load` per key, so override it with one query. Runs on the caller's thread. Must not read its own cache for the same key (that fails fast). Must read the primary, not a lagging replica. |
| `KeyEncoder<K>` | Turns a key into the string after `{keyPrefix}{namespace}:`. Must be **injective** (distinct keys, distinct strings), deterministic, and identical on every instance and version. The default is `toString()`, which rejects types that don't override it. |
| `KeyFilter` | `notExist(storageKey)` receives the full storage key. Return `true` only for keys that certainly don't exist: a Bloom filter must contain every existing key. A `true` answer skips Redis and the loader, and caches nothing. |
| `TtlFunction<K, V>` (`ttlOf`) | A per-entry lifetime. A non-null result replaces `ttl` and gets no jitter. Clamped to between 1 ms and 100 years. Not applied to negative entries. |

## What not to customize {#not-pluggable}

- **L2.** It is always a bounded Caffeine cache, with expiry checked on read against the ticking clock. A pluggable L2 would make the hit path polymorphic and let two caches share one by mistake. Tune it with `local-maximum-size`.
- **The fences and the write order.** Leases, refresh tokens, stamps, the double bump, and filling L2 only from L1 are the protocol. A backend implements the L1 side, and the engine owns the rest.
- **Policy inside a store.** Jitter, negative TTLs, and refresh windows belong to the engine. A store only sees the durations it is given.
- **The Redis wire format within a major version.** Other code may read CoCache keys for diagnosis, but must write them only through the [invalidation protocol](../operations/troubleshooting.md#external-invalidation).
- **Shared state between caches.** Every stateful component is created by `CacheRuntime.create` per cache. Pass specs and stateless functions, never L2 instances, stores, or stamps.
