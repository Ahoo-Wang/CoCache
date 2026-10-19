---
title: Troubleshooting
description: Runbooks for CoCache in production — stale values, collapsed hit rates, loader stampedes, lease timeouts, dropped invalidations, Redis outages, corrupt or unknown payloads, startup failures — and the protocol for invalidating from outside CoCache.
---

# Troubleshooting

Each runbook goes from symptom to cause, check, and fix. The tools they rely on are the [actuator endpoint](./observability.md#actuator-endpoint), the [metrics](./observability.md#metrics), and [`redis-cli`](#inspecting-redis).

**First aid for any wrong value:** `DELETE /actuator/cocache/{name}/{key}` invalidates the key on every instance. Then find out why it was wrong.

## Stale value on one instance {#stale-one-node}

**Symptom.** One instance returns an old value. Others return the new one.

**Cause.** That instance's L2 missed an invalidation message. Delivery is at most once: a message published while the subscription connection was broken, or dropped by an output-buffer limit, is lost. The copy heals on its own after at most `local-max-age` (default 5 minutes), or sooner when the connection is reset.

**Check.**

1. [Inspect the key](./observability.md#inspect-key) on the bad instance. `local` older than `remote` confirms a missed message. `local.expiresAt` is when it heals at the latest.
2. Check `cocache.resets` and the logs on that instance around the write time for `Invalidation channel heartbeat failed` or reconnects.
3. Check that this instance uses the same `cocache.redis.channel-mode`, the same namespace, and the same Redis as the writer. The [health indicator](./observability.md#health) shows `channelMode`. Instances in different modes never hear each other.
4. Check Redis for dropped subscribers: `redis-cli INFO stats` (`client_output_buffer_limit_disconnections` on recent versions) and `CONFIG GET client-output-buffer-limit` (`pubsub` class).

**Fix.**

- Make the channel mode, namespace, and Redis consistent across instances.
- If subscribers are dropped for output-buffer limits, raise the `pubsub` limit, or reduce message volume (batch with `invalidateAll`, or use `sharded` mode in Redis Cluster).
- If the window is too long for your data, lower `local-max-age`. It is the bound for this failure.

## Stale value everywhere {#stale-everywhere}

**Symptom.** Every instance returns an old value, and inspection shows the same old value in `local` and `remote`.

**Cause.** Nothing invalidated the key after the source changed, or the invalidation ran before the change was visible. Then L1 holds the old value until its `ttl`.

**Check, in this order.**

1. **A write path that doesn't invalidate.** Direct SQL, batch jobs, other services, admin tools. Each must invalidate, see [external invalidation](#external-invalidation).
2. **Invalidating before the commit.** A concurrent miss can load the uncommitted (old) row after the invalidation and fill it. Invalidate after commit. Inside transactions, use `TransactionalInvalidator`, which defers to `afterCommit` ([Spring Cache](../guide/spring-cache.md#transactions)).
3. **`@Cacheable` without `sync = true`.** Spring then loads and calls an unconditional `put`, which can write an old value over a newer invalidation. The starter logs a `WARN` for each such method at startup. Use `sync = true`, or set `cocache.spring-cache.require-sync: true` to fail startup.
4. **A loader that reads a lagging replica.** The load can return the old row after the invalidation. Load from the primary, or accept staleness up to `ttl`.
5. **Dropped invalidations.** Check `cocache.invalidations.dropped`, see [below](#invalidations-dropped).
6. **A Redis failover** right after the write can lose the `DEL`. See [Redis down](#redis-down).
7. **Mismatched key encoding.** The writer invalidates a different encoded key than the reader uses, for example a different `keyExpression`, `key-prefix`, or namespace. Compare `storageKey` from the inspection with what the writer deletes.

**Fix.** Invalidate the key now, then close the gap you found. Keep `ttl` finite as a safety net for writes you don't control.

### A new record is reported as missing {#negative-entry}

A read before the record existed cached a negative entry, and it lasts up to `absent-ttl` (default 1 minute). Inspection shows `ABSENT`. Invalidate the key after creating the record, or lower `absent-ttl`.

## Hit rate collapsed: repeated resets {#resets}

**Symptom.** `cocache.misses` and `cocache.hits{level=l1}` jump, `cocache.local.size` keeps dropping to near zero, and `cocache.resets` rises steadily. The health indicator may be `DOWN`.

**Cause.** Every failed heartbeat and every (re)subscription clears every L2 on the instance, because messages may have been lost. If the heartbeat keeps failing, L2 is cleared every `heartbeat` period (5 s).

**Check.**

- `GET /actuator/health/cocache`: `heartbeat.error` shows the last failure.
- Logs: `Invalidation channel heartbeat failed - reset all subscribers and force reconnect.`
- **`PING` denied by ACL.** `heartbeat.error` contains `NOPERM`. See [ACL](./index.md#acl).
- **Network.** Timeouts point to a proxy, load balancer, or NAT that drops idle or long-lived connections, or to an overloaded Redis.
- **`command-timeout` too low.** The heartbeat waits up to `command-timeout` (default 2 s) for the `PONG`.
- **Redis Cluster in `pubsub` mode.** CoCache can't force the cluster subscription connection to reconnect (`cannot force reconnect … rely on TCP keepalive`), so a half-open connection lasts until TCP keepalive detects it (about 30 s).

**Fix.** Allow `PING`, fix the network path, or raise `cocache.redis.command-timeout`. Raising `cocache.redis.heartbeat` makes resets rarer but delays detecting a broken connection. Resets during deploys and Redis failovers are expected.

## Loader stampede and load rejections {#load-rejections}

**Symptom.** The data source is overloaded, or callers get `SourceLoadRejectedException` and `cocache.load.rejections` rises.

**Cause.** Concurrent misses for one key are coalesced: one load per instance, and one per cluster while the loader finishes within `lease-wait`. Distinct keys aren't coalesced. Loads spike when:

- Redis fails, so every L2 miss goes to the source ([Redis down](#redis-down)).
- Many keys expire together. `ttl-jitter` spreads expiry, but not when it is `0` or tiny compared to `ttl`.
- L2 is cleared (deploys, [resets](#resets)) and L1 is cold too.
- A traffic pattern hits many keys that aren't cached.

`load-max-concurrent` caps concurrent loads per cache and per instance. A load that can't get a permit within `load-acquire-timeout` (default 1 s) fails with `SourceLoadRejectedException`. Coalesced waiters don't take a permit, and a batch load takes one.

**Check.** `cocache.loads` rate and `cocache_loads_seconds_sum / cocache_loads_seconds_count` (mean load time), `cocache.remote.failures`, `cocache.degraded.fills`, `cocache.resets`, and the source's own metrics.

**Fix.**

- Rejections protect the source. Handle `SourceLoadRejectedException` in callers (fallback, `503`), or raise `load-max-concurrent` if the source has headroom.
- Keep `ttl-jitter` meaningful (the default 1 minute against a 1 hour `ttl`).
- Use `refresh-window` for hot keys, so they reload in the background, once per cluster, before they expire.
- For keys that don't exist, a `KeyFilter` answers without a load, and negative entries absorb repeats for `absent-ttl`.

## Lease wait timeouts {#lease-wait-timeouts}

**Symptom.** `cocache.lease.wait.timeouts` is a large share of `cocache.lease.waits`, and the source sees several loads for one key across instances.

**Cause.** When one instance holds the lease for a key, others re-read with backoff for up to `lease-wait` (default 200 ms). If the loader takes longer, they load themselves without writing L1, and keep the result in L2 for `degraded-local-max-age`. Correct, but the cluster-wide coalescing is lost.

**Fix.** Raise `lease-wait` above the loader's p99 if callers can afford to wait that long, or make the loader faster. Keep `lease-ttl` (default 10 s) above the loader's worst case too: a load that outlives its lease can't fill L1 (`cocache.fill.rejections`).

## Invalidations retrying or dropped {#invalidations-dropped}

**Symptom.** `cocache.write.failures` and `cocache.invalidation.retries` rise. The health details show `pendingInvalidations` above 0. Worst case, `cocache.invalidations.dropped` rises with the `ERROR` log `Cache[…] - invalidate […] dropped: L1 keeps the stale value until its remote TTL[…] expires.`

**Cause.** An `invalidate` couldn't reach Redis. By default (`invalidate-failure: retry`), the local effect happens at once, the call returns normally, and the invalidation is retried in the background: up to 8 attempts, backing off from 100 ms to 10 s, with up to 10,000 queued. While a retry is pending, misses for that key on this instance skip L1 and load from the source. Other instances still have the old value in L1.

An invalidation is dropped when its retries run out, the queue is full, or the application shuts down with retries pending. L1 then keeps the old value until its remaining `ttl` ends (forever with `ttl-forever`), and every instance can reload it into L2.

**Check.** Redis availability and latency, `cocache.remote.failures`, and the dropped keys in the `ERROR` log.

**Fix.**

- Invalidate the dropped keys again once Redis is back: `DELETE /actuator/cocache/{name}/{key}`, or `cache.invalidateAll(keys)`.
- To fail the caller instead of retrying, set `invalidate-failure: throw`. The call then throws `CacheWriteException`.
- To change the retry policy, declare your own `CacheRuntime` bean built with `RetryPolicy(maxAttempts, initialBackoff, maxBackoff, capacity)`. The starter's runtime backs off when one exists.
- Shut instances down gracefully, so the retry queue is short at exit.

## Redis down {#redis-down}

**Symptom.** `cocache.remote.failures`, `cocache.degraded.fills`, and `cocache.write.failures` rise, the source load rises, and the health indicator turns `DOWN`.

**What CoCache does.**

| Operation | Behavior while Redis fails |
|-----------|----------------------------|
| `get`, `getAll`, `lookup` | Default (`read-failure: degrade`): load from the source, keep the result in L2 for `degraded-local-max-age` (10 s), write nothing to L1. With `read-failure: throw`: `CacheReadException`. |
| Fill, lease release, refresh | Best effort: logged and counted, never thrown |
| `invalidate`, `invalidateAll` | Local effect at once, then retried in the background ([above](#invalidations-dropped)). With `invalidate-failure: throw`: `CacheWriteException`. |
| `put`, `SetCache` writes | Local effect at once, then `CacheWriteException`. After a timeout the write may have reached Redis anyway. These calls are idempotent, so retrying is safe. |
| `SetCache` reads | Throw the Redis exception: there is no source to fall back to |
| Channel | Heartbeat fails, every L2 is cleared, and it is cleared again when the subscription is restored |

Commands are rejected while disconnected, not buffered, and each command waits at most `command-timeout` (2 s). With Redis down, each miss therefore costs one fast failure plus a load.

**Fix.** Restore Redis. Meanwhile, `load-max-concurrent` protects the source. Nothing needs to be done afterwards: retries finish on their own, and every L2 is reset when its subscription comes back. Check `cocache.invalidations.dropped` and re-invalidate any dropped keys.

**After a failover.** Asynchronous replication can lose recent writes, including invalidations, so a value invalidated just before the failover can reappear in L1. It lasts until its `ttl`. If that matters, invalidate the affected keys or caches after a failover.

## Corrupt or unreadable payloads {#corrupt-payloads}

**Symptom.** `cocache.reclaims` rises, with `WARN Cache[…] - key[…] - corrupted payload, reclaim and reload.` Inspection shows `remote.state: UNREADABLE`.

**Cause.** An L1 value couldn't be decoded:

- **Foreign content** (format `0`): something other than CoCache wrote the key, such as an old client, a script, or another application with the same prefix and namespace.
- **Codec failure** (format `1`): the value class changed incompatibly (renamed field, changed type), or two applications share a cache with different codecs or value types.

**What CoCache does.** The read path never deletes blindly. It asks Redis to convert the key into a lease only if the key still holds exactly the bytes it read (compared by SHA-1 inside the script). If that succeeds, it reloads from the source and fills normally. If the key changed in the meantime, it loads without writing L1. Each read reclaims at most once.

**Fix.**

- After a codec change, the reclaims stop once every old value has been replaced. If old and new instances run side by side, they keep reclaiming each other's values. Prefer a new namespace for incompatible value changes, or a codec that reads both forms.
- Find foreign writers with `redis-cli MONITOR` (briefly, it is expensive) or by auditing who writes to your key prefix. Give other applications their own [key prefix](./index.md#key-layout).

## Unknown format during a deploy {#unknown-format}

**Symptom.** `cocache.unknown.formats` rises. Inspection shows `UNREADABLE` with `Written by a newer format[…] than this node[…]`.

**Cause.** Some instance writes a newer wire format than this one knows. This instance loads from the source, doesn't write L1, and keeps the result in L2 for `degraded-local-max-age`, so old and new instances don't overwrite each other. A `SetCache` has no source to load from: reading a set in a newer format throws `IllegalStateException` on this instance, and content CoCache didn't write (such as a leftover native Redis set) reads as an empty set until a `put` or `add` replaces it.

**Fix.** Expected while a format upgrade rolls out. It stops when every instance runs the new version. Outside a deploy, find the instance on the newer version. See [rolling deploys](./index.md#rolling-deploys).

## Startup failures {#startup-failures}

CoCache fails fast on configuration it can't run safely. The message names the problem:

| Message (abridged) | Cause | Fix |
|--------------------|-------|-----|
| `CoCache 6.0 removed these properties: [cocache.…] - …` | A property that no longer exists | Rename as the message says. See [Upgrading from 5.x](../guide/migration-6.md#properties). |
| `cocache.caches configures undeclared caches [x]; declared caches: […]` | A `cocache.caches.[name]` entry for a cache that doesn't exist, usually a typo. Names match case-insensitively. | Fix the name, or remove the entry |
| `Cache[x] - … must be positive …`, `refreshWindow[…] must be less than ttl[…] - ttlJitter[…]`, `refreshWindow[…] must be less than localMaxAge[…]` | An invalid policy after all layers were applied | Fix the value. Durations without a unit are seconds. |
| `Cache[x] - @CoCache.… = FOREVER is not supported` | `FOREVER` on an attribute other than `ttl` or `@CaffeineCache.expireAfterAccess` | Use a duration |
| `Cache[x] already exists.` / `Namespace[x] is already used by another cache.` | Two declarations with the same cache name or namespace, for example an interface and a spec bean | Rename one, or set a distinct `namespace` |
| `Cannot register cache interface[…]: the bean name[x] is already in use.` / `Cannot register cache bean[x] for spec bean[…]` | The cache's bean name collides with another bean | Rename the cache (`@CoCache(name)`) or the spec bean |
| `Cache interface[…] must extend Cache with concrete type arguments.` / `Cache spec bean[…] must declare its generic types` | Generics can't be resolved | Use concrete types, and declare the spec factory method's return type as `CacheSpec<K, V>` |
| `Cache interface[…] declares abstract methods that do not match any Cache method: […]` | An extra abstract method on the interface | Make it a `default` method |
| `Multiple CacheLoader beans match …` / `Multiple Codec beans match …` | Several candidates for one cache | `@CoCache(loader = "beanName")` / `@CoCache(codec = "beanName")` |
| `@CoCache(loader = …) is not supported on SetCache interface[…]` | A `SetCache` has no source | Remove the loader, or use `Cache<K, Set<E>>` |
| `Cannot create cache[x] - no CoherenceBackend is available: …` | No Spring Data Redis connection details and no backend bean | Configure `spring.data.redis.*`, set `cocache.backend: memory` for local runs, or declare a `CoherenceBackend` bean |
| `Cannot create cache[x] - CoCache is disabled (cocache.enabled=false)` | Caches declared while CoCache is off | Enable it, or remove the declarations |
| `CoCache does not support Redis master-replica (spring.data.redis.masterreplica.*)` | Static master-replica | Use standalone, Sentinel, or Cluster |
| `RedisConnectionException`, or `Command timed out after …` while creating `cocacheBackend` | Redis unreachable at startup. The backend connects and probes eagerly. | Fix connectivity. A probe timeout fails startup on purpose rather than silently disabling atomic broadcast. |
| `NOPERM` while creating a cache | The ACL denies the cache's channel or keys | See [ACL](./index.md#acl) |
| `@Cacheable without sync = true on CoCache caches: …` | `cocache.spring-cache.require-sync: true` and a non-sync `@Cacheable` | Add `sync = true`, or turn the check off |
| `Join cache[x] - …` | A `@JoinCacheable` names a missing cache, a wrong type, or both extractor options | Follow the message. See [JoinCache](../guide/join-cache.md). |

One check runs at the first call, not at startup: `Key type [...] does not override toString()` means the cache's key type has no meaningful `toString()`, so every instance would map to a different Redis key. Use a data class or record, override `toString()`, or set a `keyExpression` or `KeyEncoder`.

## Writes from outside CoCache {#external-invalidation}

A write that bypasses CoCache (direct SQL, a batch job, a service in another language, a CDC pipeline) leaves the old value cached until `ttl`. Any process that reaches the same Redis can invalidate with the protocol CoCache itself uses. The protocol is frozen within a major version.

**Always change the source first, then invalidate**, as with `cache.invalidate`.

### On the JVM: `ExternalInvalidator` {#external-invalidator}

`ExternalInvalidator` (in `cocache-core`) does exactly what `invalidate` does, without creating a cache. With a backend that broadcasts atomically, the delete and the broadcast run in one script.

```kotlin
@Bean
fun externalInvalidator(backend: CoherenceBackend) = ExternalInvalidator(backend)

@Bean
fun orderInvalidator(backend: CoherenceBackend) = ExternalInvalidator(backend, "orders:")  // non-default key prefix

// after the change is committed
externalInvalidator.invalidate("UserCache", listOf("42", "43"))  // namespace, encoded keys
orderInvalidator.invalidate("OrderCache", "7/9")
```

The first argument is the **namespace** (the cache name unless the cache sets `namespace`), and the keys are **encoded keys** ([what that means](./observability.md#encoded-key)). Outside Spring, pass the same `CoherenceBackend` the caches use, or any `RedisBackend` with the same Redis and channel mode.

### Any language: the raw protocol {#raw-protocol}

| Part | Value |
|------|-------|
| Storage key | `{keyPrefix}{namespace}:{encodedKey}`, for example `cocache:UserCache:42` |
| Channel | `cocache:inv:{namespace}`, for example `cocache:inv:UserCache`. No key prefix. |
| Message | A binary frame, below |
| Publish command | `PUBLISH`, or `SPUBLISH` when the caches use `channel-mode: sharded` |

The frame:

```
[version : 1 byte = 0x01]
[publisher id : 16 bytes]
[key count : varint]
repeated key count times:
  [key length in bytes : varint]
  [storage key : UTF-8]
```

- Varints are unsigned LEB128: 7 bits per byte, low bits first, high bit set on every byte except the last. Values below 128 are one byte.
- The keys are full **storage keys**, including prefix and namespace. One frame can carry many keys of the same namespace.
- The publisher id must differ from every CoCache instance's id: use 16 random bytes. An instance ignores frames that carry its own id.
- Malformed frames are dropped with a `WARN` and have no effect.

For each change, after the source commits:

1. **`DEL` the storage keys.** This removes the L1 copy and destroys any lease or refresh token, so a load already in flight can't write the old value back.
2. **Publish the frame** on the namespace's channel. Every instance drops its L2 copy.

Delete first. If you publish first, an instance can reload the old L1 value into L2 before the delete, and keep it until `local-max-age`. If you skip the publish, instances keep their L2 copies until `local-max-age`. If you skip the delete, they reload the old value from L1.

Python with `redis-py`:

```python
import os
import redis

def varint(n: int) -> bytes:
    out = bytearray()
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)

def frame(storage_keys: list[str]) -> bytes:
    body = bytearray(b"\x01") + os.urandom(16) + varint(len(storage_keys))
    for key in storage_keys:
        raw = key.encode("utf-8")
        body += varint(len(raw)) + raw
    return bytes(body)

r = redis.Redis()
keys = ["cocache:UserCache:42"]
r.delete(*keys)                                  # in Redis Cluster, one key per call
r.publish("cocache:inv:UserCache", frame(keys))  # r.spublish(...) in sharded mode
```

From a shell, for one ASCII key shorter than 128 bytes (`bash`):

```bash
key='cocache:UserCache:42'
redis-cli DEL "$key"
{ printf '\x01'; head -c 16 /dev/urandom; printf '\x01'; printf "\\x$(printf %02x ${#key})"; printf '%s' "$key"; } \
  | redis-cli -x PUBLISH cocache:inv:UserCache
```

On the JVM without `cocache-core`, `InvalidationFrame.encode(NodeId.random(), storageKeys)` in `cocache-api` (no runtime dependencies) builds the frame.

Delivery is at most once, as for CoCache's own messages: a lost message leaves L2 copies stale until `local-max-age`.

::: warning SetCache
On a `SetCache`, `DEL` deletes the set's only copy. Don't invalidate set caches from outside: write them through `SetCache`.
:::

## Inspecting Redis by hand {#inspecting-redis}

CoCache values aren't readable JSON: each starts with a 36-byte binary header. Useful commands:

| Question | Command |
|----------|---------|
| Which keys does a cache have? | `redis-cli --scan --pattern 'cocache:UserCache:*' \| head` (per master in a cluster) |
| What state is a key in? | `redis-cli TYPE cocache:UserCache:42`: `string` (value, negative entry, or lease) or `hash` (set). For a string, `redis-cli GETRANGE cocache:UserCache:42 2 2` returns the state: `V` value, `M` negative entry, `L` lease. |
| How long does it live? | `redis-cli PTTL cocache:UserCache:42` (milliseconds, `-1` never expires) |
| What's in it? | `GET /actuator/cocache/{name}/{key}` decodes both levels. Raw: `redis-cli GETRANGE key 36 -1` returns the codec's bytes. |
| How much memory? | `redis-cli MEMORY USAGE cocache:UserCache:42` |
| Who listens on a namespace? | `redis-cli PUBSUB NUMSUB cocache:inv:UserCache`, or `PUBSUB SHARDNUMSUB` in `sharded` mode. In a cluster, counts are per node. |
| Which connections are CoCache's? | Set `cocache.redis.client-name`, then `redis-cli CLIENT LIST` and look for that `name`. Subscription connections show `sub=` or `ssub=` above 0. |
| Are invalidations flowing? | `redis-cli SUBSCRIBE cocache:inv:UserCache` (or `SSUBSCRIBE`) prints each frame. The storage keys are readable in the binary output. |

Don't modify CoCache keys by hand, except through the [invalidation protocol](#external-invalidation).
