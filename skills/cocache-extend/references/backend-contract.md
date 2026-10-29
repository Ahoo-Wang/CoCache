# Backend Contract Map

Use this map while implementing or reviewing a `CoherenceBackend`. It lists what to verify in the pinned version's SPI sources and KDoc (`me.ahoo.cocache.api.spi`); if they disagree with this map, the sources win.

## Contents

- [Shapes](#shapes)
- [Store obligations by method](#store-obligations-by-method)
- [Rules across all methods](#rules-across-all-methods)
- [Invalidation channel](#invalidation-channel)
- [Invariants to review against](#invariants-to-review-against)
- [Async store (optional, 6.3+)](#async-store-optional-63)
- [Wiring](#wiring)

## Shapes

```kotlin
interface CoherenceBackend : AutoCloseable {
    val atomicBroadcast: Boolean               // can a write publish its frame in the same atomic step?
    val format: Int                            // wire format version this implementation writes
    val versionedWrites: Boolean               // 6.8.0+, default false: putIfNewer implemented atomically
    val channel: InvalidationChannel
    fun store(namespace: String): RemoteStore  // called once per cache
}
```

- `atomicBroadcast = true`: every write method publishes its `frame` atomically with the change. `false`: the engine calls `channel.publish(namespace, frame)` after each successful write; a crash in between loses that message (bounded by `local-max-age`).
- `format`: start at `1`. Return `format = 0` in `RemoteRead.Hit` for content your protocol did not write. A higher stored format is loaded around and never overwritten; bump only through a two-phase rollout.

| Type | Shape |
|---|---|
| `Envelope` | `Scalar(bytes, version)`, `Members(list of bytes)`, `Absent` (negative entry); `version` (6.8.0+) defaults to `Versioned.NO_VERSION` |
| `RemoteRead` | `Hit(envelope, format, ttl, refreshGranted)`, `Miss(leaseGranted)`, `Leased(remaining)`; `ttl == null` means no expiry |
| `Token` | 24 bytes (node id + sequence); compare by bytes |
| `LeaseRequest(token, ttl)` | Lease to place on a miss |
| `RefreshRequest(window, token, tokenTtl)` | Refresh token to place on a value inside `window`; older than `tokenTtl` may be taken over |
| `Fill(key, token, envelope, ttl)` | Write only if the key holds `token` |
| `Digest` | SHA-1 of the content as defined in the SPI KDoc |

Keys arrive complete, with prefix and namespace.

## Store obligations by method

| Method | Must |
|---|---|
| `read` | Absent with a lease request: place the lease and return `Miss(true)`. Absent without one: `Miss(false)`, write nothing. Leased: `Leased(remaining)`. Value: `Hit` with remaining TTL; grant a refresh token (keeping the TTL) only for a scalar with `0 < ttl <= window` and no live token. Never on a value without expiry. With both requests `null`, write nothing. |
| `readAll` | `read` per key, leasing only keys in `leases`; return every key. If any key fails, release the leases granted in this call, then throw. |
| `fill` / `fillAll` | Write only if the key holds exactly `fill.token` (as a lease or refresh token); reset expiry to `fill.ttl`; return whether written. |
| `release` | Matching lease: delete the key. Matching refresh token on a value: clear the token, keep value and TTL. Otherwise nothing. |
| `put` | Overwrite any state, destroy any token, set expiry, broadcast. `Members` replaces the whole set. |
| `invalidate` | Delete every key, destroy any token, broadcast. Atomic per key; batches may split. |
| `reclaim` | Only if current content has digest `observed` and is not a lease: replace it with the lease; return whether done. Never delete changed content. |
| `addMembers` | Set: add atomically. Anything else: replace with a set of these members. Destroy tokens, set expiry (`null` removes it), broadcast. |
| `removeMembers` | Only on a set: remove, delete the key when empty, otherwise reset expiry. Broadcast in every case. |
| `addMembersIfReadable` / `removeMembersIfReadable` (6.6.0+, optional) | Like the plain methods, but if the key holds something that is not a set of your protocol (content you didn't write, a scalar, a set in a newer format): change nothing, broadcast nothing, return that content's format (`0` = not yours); return `null` after a normal write. Absent, negative and leased keys are written. `SetCache` with `foreign-format: fail` (default) uses these. Default reads then writes (not atomic, can't see newer formats): override with one atomic check-and-write. |
| `readIfChanged` (6.7.0+, optional) | Pure. A scalar of your protocol whose payload digest equals `known`: `Unchanged(format)`, payload not sent back. Other scalar: `Changed(envelope, format)`. Absent, lease, negative entry: `Absent`. Anything else (not yours, a set, a newer format): `Unreadable(format)`. Default calls `read` and compares on the client (always transfers the payload): override to compare in the store. |
| `putIfMatch` / `putIfMatchAsync` (6.7.0+, atomic or not at all) | One atomic compare-and-write: matches when `expected == null` and the key holds no value (absent, lease, negative entry), or a scalar whose payload digest equals `expected`. `Unreadable` content returns `Unreadable(format)`; only format-0 content (not yours) matches as no value, and only when `foreignAsAbsent`. Sets and newer formats never do. Match: write like `put`, return `Written`. Otherwise change nothing, broadcast nothing, return `Conflict(current digest or null)`. Defaults throw `UnsupportedOperationException` (no read-then-put fallback); a native async store must override both. TCK: `supportsConditionalWrites = false` skips the cases when unimplemented. |
| `putIfNewer` / `putIfNewerAsync` (6.8.0+, only with `versionedWrites = true`) | One atomic compare-by-version: write `envelope` (scalar, or `Absent` for a tombstone) with `version` like `put` only if `version` is greater than the stored version; content without a version, leases and absent keys count as `NO_VERSION`. Otherwise change nothing, broadcast nothing, return `Stale(current)`. Unreadable content as in `putIfMatch`. Versioned content holds no token: fill/release never match it, `read` never grants a refresh token on it, `reclaim` refuses it. `fill` / `put` / `putAll` store `Envelope.Scalar.version` and reads return it; `putIfMatch` writes without a version. Defaults throw. TCK: `VersionedSpec` / `AsyncVersionedSpec`. |

## Rules across all methods

- Atomic per key; batch methods are atomic per key, not across keys.
- Reset the expiry on every state change, at millisecond precision; a lease's or negative entry's lifetime must never leak onto a new value.
- Never delete on read.
- Throw on failure; never swallow or retry internally. The engine decides whether to degrade, retry or rethrow.
- Reject commands while disconnected instead of buffering and replaying them.
- Talk only to a primary that serializes writes per key; replicas cannot hold leases.

## Invalidation channel

```kotlin
interface InvalidationChannel {
    fun subscribe(namespace: String, listener: InvalidationListener): Subscription
    fun publish(namespace: String, frame: ByteArray)   // only when atomicBroadcast == false
}
interface InvalidationListener {
    fun onInvalidated(keys: List<String>, origin: NodeId)
    fun onReset()
}
```

- Frames are built by the engine with `InvalidationFrame.encode`; deliver them to every listener of the namespace, decoded with `InvalidationFrame.decode`. Drop malformed frames. Do not filter by origin.
- Call `onReset()` on every listener each time a subscription is established or re-established and confirmed, and whenever messages may have been lost (failed heartbeat, rebalance, sequence gap).
- The first listener's reset must have run before `subscribe` returns; later listeners on an existing subscription get one `onReset()` immediately.
- Subscribing a new namespace must not reset other namespaces; a throwing listener must not block others.
- If `subscribe` fails, throw and leave no partial registration. Closing a `Subscription` unsubscribes that listener.

## Invariants to review against

1. Every fill holds a token taken before the read; invalidation destroys tokens.
2. An L1 miss is a miss: an absent or leased key is never a negative entry; negative entries exist only as `Absent`.
3. The read path never deletes unconditionally; only `reclaim` with a matching digest converts content to a lease.
4. Broadcast atomically when you can, and declare it honestly.
5. Tokens are released or expire; every state transition sets its expiry explicitly.
6. The store executes protocol only; no policy, no shared state between caches.
7. Reject while disconnected; never replay buffered writes.

## Async store (optional, 6.3+)

A backend that implements only `RemoteStore` keeps working with `cache.async()`: the engine runs its synchronous methods on the blocking executor. To serve async callers without those threads, also implement `AsyncRemoteStore` (an `xxxAsync` twin of every `RemoteStore` method) and, for non-atomic broadcast, `AsyncInvalidationChannel.publishAsync`.

- Same semantics as the synchronous method; never throw synchronously; fail the future with the same exception types as the synchronous path, not `CompletionException`.
- Bound every operation (a command timeout): a future that never completes stalls that key until the cache's `load-async-timeout`.
- Completing on an I/O thread is fine, because the engine switches threads before engine or user code runs; keep that completion free of codecs and user code.
- Prove it with `AsyncBackendProtocolSpec` and the four async engine suites.

## Wiring

In Spring Boot, a `CoherenceBackend` bean replaces the Redis backend and Spring closes it on shutdown. Without Spring, pass it to `CacheRuntime.builder().backend(...)` and close the runtime before the backend. A backend needs only `cocache-api` at runtime and `cocache-test` for its tests.
