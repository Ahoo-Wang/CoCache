---
title: Operations
description: Running CoCache in production — the actuator endpoint (per-key inspect and invalidate), health indicator, metrics, Redis failure behavior, invalidating from outside CoCache, inspecting Redis, sizing, and troubleshooting stale reads.
---

# Operations

## Actuator endpoint {#actuator-endpoint}

With `spring-boot-starter-actuator` on the classpath, the starter provides one endpoint, `cocache`. It is created only when it is available (access allowed and exposed), so expose it like any other actuator endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: cocache
```

| Request | Effect |
|---------|--------|
| `GET /actuator/cocache` | Report for every cache on this instance: `name`, `localSize` (L2 entries, estimated), `loads`, `loadNanos`, and every counter from the [metrics table](#metrics) under `metrics` |
| `GET /actuator/cocache/{name}` | Report for one cache |
| `DELETE /actuator/cocache/{name}` | Clears **this instance's** L2 for that cache. L1 is untouched. Loads in flight are discarded. |
| `GET /actuator/cocache/{name}/{key}` | Inspects one key on this instance: its L2 copy and its L1 state ([below](#inspect-key)) |
| `DELETE /actuator/cocache/{name}/{key}` | Invalidates one key **cluster-wide** through the normal invalidation path, as `cache.invalidate(key)` does. Idempotent. On a `SetCache` it is `clear(key)`, which deletes the only copy. |

An unknown cache name returns `404`. The `DELETE` operations change state, and the inspection shows values, so secure the endpoint like other write-capable actuator endpoints that can read your data.

### Inspecting a key {#inspect-key}

`{key}` is the **encoded key**: the part of the Redis key after `{keyPrefix}{namespace}:`, which is the `KeyEncoder` output (`toString()` or the `keyExpression` result). That works for any key type. It may contain `/`.

```bash
curl localhost:8080/actuator/cocache/UserCache/42
```

```json
{
  "cache": "UserCache",
  "storageKey": "cocache:UserCache:42",
  "local": { "state": "VALUE", "value": "User(id=42, name=Ada)", "expiresAt": "2026-10-19T08:05:00Z", "refreshAt": null, "expired": false },
  "remote": { "state": "VALUE", "value": "User(id=42, name=Ada)", "ttl": "PT58M", "format": 1, "error": null },
  "invalidationPending": false
}
```

| Field | Meaning |
|-------|---------|
| `local` | This instance's L2 copy, or `null`. `state` is `VALUE`, `ABSENT` (a negative entry), or `UNRESOLVED` (a miss on a cache without a loader). An expired copy that Caffeine has not evicted yet shows `expired: true`. |
| `remote.state` | `VALUE`, `ABSENT` (negative entry), `SET`, `LEASED` (a node is loading; `ttl` is the lease's remaining time), `MISS`, `UNREADABLE` (unknown or newer format, wrong shape, or the codec failed; `error` says which), or `ERROR` (the Redis read failed) |
| `value` | The value decoded with the cache's codec, as `toString()`, cut at 256 characters |
| `invalidationPending` | An invalidation of this key failed on Redis and is still being retried. Until it succeeds, misses skip L1. |

Inspecting never loads: it doesn't call the loader, take a lease or refresh token, fill either level, count in metrics, or touch Caffeine's eviction statistics. It costs one Redis read.

## Health indicator {#health}

With actuator on the classpath, the starter registers the health contributor `cocache` (bean `cocacheHealthIndicator`, `management.health.cocache.enabled` to turn it off). It reads only in-memory state and never calls Redis.

| Status | When |
|--------|------|
| `DOWN` | The Redis invalidation channel's last heartbeat failed. This instance's L2 has been cleared, and invalidations from other instances may be missed until the connection recovers. |
| `UP` | Otherwise, including while invalidations are being retried |

Details: `backend` (class name), `format`, `atomicBroadcast`, for Redis also `channelMode` and `heartbeat` (`healthy`, and `error` when failing), the total `pendingInvalidations`, and per cache `pendingInvalidations` and `localSize`. Pending invalidations don't change the status: the retry queue converges on its own, and a dropped invalidation shows up in `cocache.invalidations.dropped`. Alert on that metric.

If you don't want a Redis subscription problem to take an instance out of rotation, leave `cocache` out of your readiness group.

## Metrics {#metrics}

With Micrometer on the classpath (`spring-boot-starter-actuator` brings it), the starter binds these meters to every `MeterRegistry`. Every meter carries a `cache` tag with the cache name. All are recorded off the L2 hit path and are always on.

| Meter | Type | Meaning |
|-------|------|---------|
| `cocache.hits{level=l1}` | counter | L2 misses served by L1 |
| `cocache.misses` | counter | L2 misses, counted before concurrent misses for one key are coalesced |
| `cocache.filtered` | counter | Reads the `KeyFilter` answered as "not found" |
| `cocache.loads` | function timer | Loader calls (single and batch), with total time |
| `cocache.load.failures` | counter | Loader calls that threw |
| `cocache.load.rejections` | counter | Loads rejected by `load-max-concurrent` |
| `cocache.lease.waits` | counter | Reads that waited for another node's lease |
| `cocache.lease.wait.timeouts` | counter | Lease waits that timed out, so this node loaded without writing L1 |
| `cocache.fill.rejections` | counter | L1 fills rejected by the lease fence (an invalidation intervened, or the loader outlived `lease-ttl`) |
| `cocache.degraded.fills` | counter | Loads written to L2 only, with `degraded-local-max-age` |
| `cocache.stale.discards` | counter | L2 fills discarded because an invalidation arrived during the load |
| `cocache.reclaims` | counter | Undecodable L1 payloads reclaimed and reloaded |
| `cocache.unknown.formats` | counter | L1 values in a newer wire format than this node knows |
| `cocache.refreshes` | counter | Refresh-ahead executions |
| `cocache.refresh.rejections` | counter | Refreshes rejected by the refresh executor |
| `cocache.remote.failures` | counter | L1 operations that failed |
| `cocache.write.failures` | counter | Writes and invalidations that failed on L1 |
| `cocache.invalidation.retries` | counter | Invalidation retries scheduled |
| `cocache.invalidations.dropped` | counter | Invalidations dropped after retries ran out or the retry queue overflowed |
| `cocache.invalidations.received` | counter | Invalidation events received from other instances |
| `cocache.resets` | counter | Channel (re)subscriptions and heartbeat failures that cleared L2 |
| `cocache.local.size` | gauge | Entries in this instance's L2 (estimated) |

There is no L2 hit counter: counting the hottest path would cost measurable throughput.

What to watch:

- **`invalidations.dropped` > 0**: some instances may serve stale L2 copies until `local-max-age`, and L1 may hold the old value until its TTL. Investigate Redis availability.
- **`degraded.fills`, `remote.failures` rising**: Redis is failing and reads are going to the source.
- **`lease.wait.timeouts` high relative to `lease.waits`**: loaders are slower than `lease-wait`, so cross-instance coalescing isn't working. Raise `lease-wait` or speed up the loader.
- **`fill.rejections` without matching invalidations**: loaders are slower than `lease-ttl`.
- **`resets` rising without deploys**: the subscription connection is unstable.

## Redis failures {#redis-failures}

| Operation | When L1 fails |
|-----------|---------------|
| Read (`get`, `getAll`, `lookup`) | Default (`read-failure: degrade`): loaded from the source, written to L2 only with `degraded-local-max-age` (10 s). `read-failure: throw`: throws `CacheReadException`. |
| Fill, lease release, refresh | Always best effort: logged and counted, never thrown |
| `invalidate`, `invalidateAll` | The local effect happens first. Default (`invalidate-failure: retry`): queued for background retry with exponential backoff, not thrown. Until it succeeds, misses for that key skip L1 and load from the source. `invalidate-failure: throw`: throws `CacheWriteException`. |
| `put`, and `SetCache` `add` / `remove` / `clear` | The local effect happens first, then `CacheWriteException`. On a timeout the write may have been applied. All are idempotent, so you can retry. |

**Plan for the cost of degrading.** While Redis is down, every L2 miss goes to your data source. Concurrent misses for one key are still coalesced per instance, but distinct keys are not. To cap the load, set `load-max-concurrent` (per cache, per instance). A load beyond the cap waits up to `load-acquire-timeout` for a permit, then fails with `SourceLoadRejectedException` and counts in `load.rejections`. Callers that coalesce onto an in-flight load don't take a permit, and a batch load takes one.

The invalidation retry queue holds up to 10,000 invalidations and tries each up to 8 times, backing off from 100 ms to 10 s. To change that, build the `CacheRuntime` bean yourself with a `RetryPolicy`.

**Channel outages.** The subscription connection pings Redis every `heartbeat` (5 s). When the ping fails or the connection drops, every cache clears its L2 and logs `Cache[…] - reset: clear local copies.` at `INFO`. When the connection comes back, they clear L2 again, because events published during the outage were lost.

## Invalidating from outside CoCache {#external-eviction}

Writes that bypass CoCache, such as direct SQL, batch jobs, or services in other languages, leave caches stale until `ttl`. Any process that can reach the same Redis can invalidate a key with the protocol CoCache uses. The format is frozen within a major version.

| Part | Value |
|------|-------|
| Storage key | `{keyPrefix}{namespace}:{key}`. With the defaults, `UserCache` key `42` is `cocache:UserCache:42`. With `keyExpression`, use the expression's result. |
| Channel | `cocache:inv:{namespace}`, for example `cocache:inv:UserCache` |
| Message | A binary frame: `[version = 1 : 1 byte][publisher id : 16 bytes][key count : varint]([key length : varint][key : UTF-8])*`. The publisher id must differ from every instance's, so use a random one. |

After the source of truth commits:

1. `DEL {storageKey}` removes the L1 copy and destroys any lease, so a load in flight can no longer write the old row back.
2. `PUBLISH cocache:inv:{namespace} {frame}` makes every instance drop its L2 copy. With `channel-mode: sharded`, use `SPUBLISH`.

`InvalidationFrame` in `cocache-api` (no runtime dependencies) builds the frame:

```kotlin
class UserChangeInvalidator(private val redis: RedisCommands<String, ByteArray>) {
    private val publisher = NodeId.random()

    fun onUserChanged(userId: String) {   // called after the change is committed
        val storageKey = "cocache:UserCache:$userId"
        redis.del(storageKey)
        redis.publish("cocache:inv:UserCache", InvalidationFrame.encode(publisher, listOf(storageKey)))
    }
}
```

One frame can carry many keys of the same namespace. Delivery is at most once, like CoCache's own events: a lost message leaves peers' L2 stale until `local-max-age`. Skipping the `DEL` leaves the old value in L1, and instances reload it into L2.

## Inspecting Redis {#inspecting-redis}

Every CoCache value in Redis starts with a 36-byte binary header (magic byte `0xC0`, format version, state, token, timestamp), followed by the codec's bytes. `redis-cli GET` therefore shows binary data, and a negative entry or an in-progress lease has a header but no payload. Set caches are Redis Hashes with a `!f` format field and one `+{member}` field per member. Don't read or write CoCache keys from other programs except through the invalidation protocol above.

To see what an instance holds for a key, use [`GET /actuator/cocache/{name}/{key}`](#inspect-key), which decodes both levels. To force every instance to reload a key, invalidate it, for example with `DELETE /actuator/cocache/{name}/{key}`.

## Sizing {#sizing}

- **L2 memory** is about `maximumSize` × average value size, per cache and per instance. The default `maximumSize` is 10,000.
- **Redis memory** holds one copy of each live key plus a 36-byte header. Negative entries are header-only and expire after `absentTtl`.
- **Redis traffic.** A read that misses L1 writes a lease, and refresh-ahead writes a token, so reads create some replication and AOF traffic. All CoCache commands go to masters.
- **Pub/Sub.** One channel per cache namespace (`cocache:inv:{namespace}`) and one subscription connection per instance. Every `put` or `invalidate` publishes one message that every instance receives. In Redis Cluster, `channel-mode: sharded` keeps that traffic within one shard.
- **Large writes.** One script handles at most `invalidate-batch-size` keys (512). Writing a very large `SetCache` set in one call blocks its shard for its duration.

## Troubleshooting {#troubleshooting}

**An instance keeps serving an old value.**
1. [Inspect the key](#inspect-key) on that instance: compare its `local` copy with `remote`, and check `invalidationPending`. `DELETE /actuator/cocache/{name}/{key}` fixes it right away while you look for the cause.
2. Check that the writer invalidates *after* the database commit. Use `TransactionalInvalidator` inside transactions.
3. Check that every write path invalidates, including batch jobs and other services. Paths outside CoCache can use the [invalidation protocol](#external-eviction).
4. Check that every instance uses the same cache namespace and the same `channel-mode`. `pubsub` and `sharded` instances don't hear each other.
5. Check `cocache.invalidations.dropped` and `cocache.write.failures`.
6. With `@Cacheable` without `sync = true`, Spring's unconditional `put` can overwrite an invalidation. Use `sync = true`. The starter warns about these methods at startup ([Spring Cache](./spring-cache.md#use-sync-true)).

**A newly created record is reported as missing.** A lookup before the record existed cached a negative entry. It lasts up to `absentTtl`. Invalidate the key after creating the record, or lower `absent-ttl`.

**Startup fails with several `CacheLoader` candidates.** Two beans match the cache's `CacheLoader<K, V>` type. Name the right one with `@CoCache(loader = "beanName")`.

**Startup fails naming a `cocache.*` property.** It is a removed 5.x key, or a `cocache.caches.[name]` entry for a cache that doesn't exist. The message names the replacement. See [Migrating from 5.x](./migration-6.md#properties).
