---
title: Troubleshooting
description: Runbooks for CoCache in production — stale values, collapsed hit rates, loader stampedes, lease timeouts, dropped invalidations, Redis outages, corrupt or unknown payloads, startup failures — and the protocol for invalidating from outside CoCache.
---

# Troubleshooting

Each runbook goes from symptom to cause, check, and fix. The tools they rely on are the [actuator endpoint](./observability.md#actuator-endpoint), the [metrics](./observability.md#metrics), and [`redis-cli`](#inspecting-redis).

**First aid for any wrong value:** for a `Cache` or `VersionedCache`, `DELETE /actuator/cocache/{name}/{key}` [invalidates the key](./observability.md#invalidate-key) on every instance (a `VersionedCache` keeps the version). For a `Store`, `VersionedStore` or `SetStore`, `DELETE` deletes the only copy and needs a switch; use `POST /actuator/cocache/{name}/{key}` instead, which [drops every instance's local copy](./observability.md#broadcast-key) and leaves Redis alone. Then find out why it was wrong.

## Stale value on one instance {#stale-one-node}

**Symptom.** One instance returns an old value. Others return the new one.

**Cause.** That instance's L2 missed an invalidation message. Delivery is at most once: a message published while the subscription connection was broken, or dropped by an output-buffer limit, is lost. The copy heals on its own after at most `local-max-age` (default 5 minutes for a `Cache` or `VersionedCache`, 30 seconds for the authoritative types), or sooner when the connection is reset.

**Check.**

1. [Inspect the key](./observability.md#inspect-key) on the bad instance. `local` older than `remote` confirms a missed message. `local.expiresAt` is when it heals at the latest.
2. Check `cocache.resets` and the logs on that instance around the write time for `Invalidation channel heartbeat failed` or reconnects.
3. Check that this instance uses the same `cocache.redis.channel-mode`, the same key prefix and namespace, and the same Redis as the writer. The prefix is part of the channel name. The [health indicator](./observability.md#health) shows `channelMode`. Instances in different modes never hear each other.
4. Check Redis for dropped subscribers: `redis-cli INFO stats` (`client_output_buffer_limit_disconnections` on recent versions) and `CONFIG GET client-output-buffer-limit` (`pubsub` class).

**Fix.**

- Make the channel mode, key prefix, namespace, and Redis consistent across instances.
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

A read before the record existed cached a negative entry, and it lasts up to `absent-ttl` (default 1 minute for a `Cache` or `VersionedCache`, 10 seconds for the authoritative types). Inspection shows `ABSENT`. Invalidate the key after creating the record, or lower `absent-ttl`.

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
- **Redis Cluster in `sharded` mode.** A channel whose subscription Redis hasn't confirmed, after a slot migration or a server-side unsubscribe, clears its caches' L2 at every heartbeat until the subscription is confirmed again. Health stays `UP` meanwhile, because `PING` succeeds. Logs: `Invalidation channel[…] unsubscribed by server (slot migration) - reset subscribers and resubscribe.` or `Invalidation channel[…] - subscribed on […] but slot is owned by […] - reset subscribers and resubscribe.`

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

**Cause.** A `Cache.invalidate` couldn't reach Redis. By default (`invalidate-failure: retry`), the local effect happens at once, the call returns normally, and the invalidation is retried in the background: up to 8 attempts, backing off from 100 ms to 10 s, with up to 10,000 queued. While a retry is pending, misses for that key on this instance skip L1 and load from the source. Other instances still have the old value in L1.

An invalidation is dropped when its retries run out, the queue is full, or the application shuts down with retries pending. L1 then keeps the old value until its remaining `ttl` ends (forever with `ttl: forever`), and every instance can reload it into L2.

**Check.** Redis availability and latency, `cocache.remote.failures`, and the dropped keys in the `ERROR` log.

**Fix.**

- Invalidate the dropped keys again once Redis is back: `DELETE /actuator/cocache/{name}/{key}`, or `cache.invalidateAll(keys)`.
- To fail the caller instead of retrying, set `invalidate-failure: throw`. The call then throws `CacheWriteException`. The authoritative types' deletes (`Store.delete` and `deleteIfMatch`, `purge`, `SetStore.clear`) and `VersionedCache.evict` always throw and are never queued: a delayed delete of an only copy could remove a value written after the failure, and a retried `DEL` would drop a version. See [When Redis is down during a write](../guide/freshness.md#redis-down).
- To change the retry policy, declare your own `CacheRuntime` bean built with `RetryPolicy(maxAttempts, initialBackoff, maxBackoff, capacity)`. The starter's runtime backs off when one exists.
- Shut instances down gracefully, so the retry queue is short at exit.

## Redis down {#redis-down}

**Symptom.** `cocache.remote.failures`, `cocache.degraded.fills`, and `cocache.write.failures` rise, the source load rises, and the health indicator turns `DOWN`. Reads without a source (see the table below) raise `cocache.reads.unknown` instead of `cocache.degraded.fills`, and their callers get `CacheReadException`.

**What CoCache does.**

| Operation | Behavior while Redis fails |
|-----------|----------------------------|
| Reads with a source (`get`, `getAll`, `lookup` on a `Cache` or `VersionedCache` with a loader, or `get(key, loader)`) | Default (`read-failure: degrade`): load from the source, keep the result in L2 for `degraded-local-max-age` (10 s), write nothing to L1. With `read-failure: throw`: [`Lookup.Unknown`](../guide/caches.md#unknown), so `get` throws `CacheReadException`. |
| Reads without a source (a `Store`, `VersionedStore` or `SetStore`, or an externally sourced `Cache` read without a loader) | `Lookup.Unknown`: `get` throws `CacheReadException`, `lookup` returns `Unknown`, nothing is cached. `getAll` finishes the other keys first, then throws with `unknownKeys` and `partialResult`. With `unknownRead = ABSENT` on the spec (not a `SetStore`): `null` (absent from `getAll`), still nothing cached. Counted in `cocache.reads.unknown`. |
| Fill, lease release, refresh | Best effort: logged and counted, never thrown |
| `Cache.invalidate`, `invalidateAll` | Local effect at once, then retried in the background ([above](#invalidations-dropped)). With `invalidate-failure: throw`: `CacheWriteException`. |
| Every other write: `put`, `putAll`, conditional and versioned writes, deletes, `evict`, `purge`, `SetStore` writes | Local effect at once, then `CacheWriteException`. Its `outcomeUnknown` is `false` when the command was rejected before it was sent: it didn't happen. After a timeout it is `true`: the write may have reached Redis anyway, unless it was still waiting to be sent, in which case it is dropped and never applied later. An interrupted caller waits for its command to finish or time out, and keeps its interrupt flag. See [When Redis is down during a write](../guide/freshness.md#redis-down) for which retries are safe. |
| Channel | Heartbeat fails, every L2 is cleared, and it is cleared again when the subscription is restored |

Commands are rejected while disconnected, not buffered, and each command waits at most `command-timeout` (2 s). With Redis down, each miss therefore costs one fast failure plus a load. A read that times out may still run in Redis later and take a lease for the key, and a fill that times out may be dropped before it is sent and leave its lease behind. CoCache releases such leases right away (best effort), so other instances don't wait `lease-wait` on the key.

**Fix.** Restore Redis. Meanwhile, `load-max-concurrent` protects the source. Nothing needs to be done afterwards: retries finish on their own, and every L2 is reset when its subscription comes back. Check `cocache.invalidations.dropped` and re-invalidate any dropped keys.

**After a failover.** Asynchronous replication can lose recent writes, including invalidations, so a value invalidated just before the failover can reappear in L1. It lasts until its `ttl`. If that matters, invalidate the affected keys or caches after a failover. For a `Store`, `VersionedStore` or `SetStore` the lost writes are simply gone: Redis held the only copy ([persistence](./index.md#failover)).

## Corrupt or unreadable payloads {#corrupt-payloads}

**Symptom.** `cocache.reclaims` rises, with `WARN Cache[…] - key[…] - corrupted payload, reclaim and reload.` Inspection shows `remote.state: UNREADABLE`.

**Cause.** An L1 value couldn't be decoded:

- **Foreign content** (format `0` or `1`): content CoCache didn't write, or a format this version can't read, for example from a script or another application with the same prefix and namespace.
- **Codec failure** (format `2`): the value class changed incompatibly (renamed field, changed type), or two applications share a cache with different codecs or value types.

**What CoCache does.** A read without a source (a `Store`, `VersionedStore` or `SetStore`, or an externally sourced `Cache` read without a loader) has nothing to reload, so it throws `CacheReadException` with a `ForeignFormatException` cause (reclaiming could delete an only copy), leaves the key as it is, and counts in `cocache.unknown.formats` instead of `cocache.reclaims`. A `SetStore.add` or `remove`, a conditional or versioned write, or an `evict` on such content fails the same way, with `CacheWriteException`, and leaves the key unchanged (`put` replaces it). A read with a loader never deletes blindly either. It asks Redis to convert the key into a lease only if the key still holds exactly the bytes it read (compared by SHA-1 inside the script). If that succeeds, it reloads from the source and fills normally. If the key changed in the meantime, it loads without writing L1. Each read reclaims at most once.

**Fix.**

- After a codec change, the reclaims stop once every old value has been replaced. If old and new instances run side by side, they keep reclaiming each other's values. Prefer a new namespace for incompatible value changes, or a codec that reads both forms.
- Find foreign writers with `redis-cli MONITOR` (briefly, it is expensive) or by auditing who writes to your key prefix. Give other applications their own [key prefix](./index.md#key-layout).

## Unknown format during a deploy {#unknown-format}

**Symptom.** `cocache.unknown.formats` rises. Inspection shows `UNREADABLE` with `Written by a newer format[…] than this node[…]`.

**Cause.** Some instance writes a newer wire format than this one knows, or a must-understand flag this one doesn't know. A read with a source loads from it, doesn't write L1, and keeps the result in L2 for `degraded-local-max-age`, so old and new instances don't overwrite each other. An externally sourced `Cache` read without a loader treats the key as unfilled and returns `null`; a read with a per-call loader loads. A `Store` or `VersionedStore` has no source: the read throws `CacheReadException` with a `ForeignFormatException` cause instead of answering "not found". Reading a `SetStore` set in a newer format throws `CacheReadException` with a `ForeignFormatException` cause on this instance, as content CoCache didn't write (such as a leftover native Redis set) does (see [Corrupt or unreadable payloads](#corrupt-payloads)). A `SetStore.add` or `remove` on a set in a newer format throws `CacheWriteException` and leaves it unchanged.

**Fix.** Expected while a format upgrade rolls out. It stops when every instance runs the new version. Outside a deploy, find the instance on the newer version. See [rolling deploys](./index.md#rolling-deploys).

## Startup failures {#startup-failures}

CoCache fails fast on configuration it can't run safely. The message names the problem:

| Message (abridged) | Cause | Fix |
|--------------------|-------|-----|
| `cocache.caches configures undeclared caches [x]; declared caches: […]` | A `cocache.caches.[name]` entry for a cache that doesn't exist, usually a typo. Names match case-insensitively. | Fix the name, or remove the entry. `cocache.caches-validation: warn` turns this into a warning ([validation](../guide/configuration.md#validation)). |
| `cocache.defaults.cache.ttl: "60" needs a unit, e.g. 60s, 5m, PT2M.` / `… is not a duration; write it with a unit …` / `… must be positive; write unlimited for no limit.` | A duration without a unit (`cocache.redis.*` included), a misspelled sentinel, or `load-max-concurrent: 0` | Write the unit, or the sentinel: `forever`, `off`, `unlimited` ([durations](../guide/configuration.md#durations)) |
| `cocache.caches sets unknown keys [cocache.caches.x.…]: misspelled, or not a per-cache key.` | A per-cache key CoCache doesn't define, usually a typo such as `ttl-forever`, or a key without its prefix such as `max-concurrent` for `load-max-concurrent` ([policy properties](../guide/configuration.md#policy-properties)) | Fix the key. `cocache.caches-validation: warn` only warns, and the key has no effect. |
| `Cache[x] - cocache.caches.x.… doesn't apply to …` | A per-cache key that the cache's type doesn't have, such as `refresh-window` on a `Store` ([which keys apply](../guide/configuration.md#applicability)) | Remove the key. `cocache.caches-validation: warn` only warns. |
| `Cache[x] - cocache.caches.x.… is locked by …; list … in cocache.caches.x.unlock to override it.` | The library that created the cache locked the field | Remove the entry, or list the field under `unlock` if you mean to override the library ([locks](../guide/configuration.md#locks)) |
| `Cache[x] - customizer … changed …, which is locked by ….` / `customizer … changed the data source …` | A `CacheSpecCustomizer` writes a locked field or layout, or the loader | Exclude the cache in `supports(cacheName)`, or change only unlocked policy fields |
| `Cache[x] - … must be positive …`, `refreshWindow[…] must be less than ttl[…] - ttlJitter[…]`, `refreshWindow[…] must be less than localMaxAge[…]` | An invalid policy after all layers were applied, caused by the declaration or involving a field set by `cocache.caches.[name]`. A conflict caused only by a global layer turns the window off instead ([validation](../guide/configuration.md#validation)). | Fix the value |
| `VersionedStore[x] - tombstones need a finite lifetime: set policy.tombstoneTtl.` | `tombstone-ttl: forever` on a `VersionedStore` | Use a finite lifetime that covers your redelivery window |
| `Authoritative caches […] keep their only copy in Redis, but maxmemory-policy {…} can evict them when memory is full. …` | An `allkeys-*` policy with a `Store`, `VersionedStore` or `SetStore` | Use `noeviction`, or `volatile-*` when none of them has a finite `ttl` ([eviction check](../guide/configuration.md#eviction-check)) |
| `Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches […] keep their only copy in Redis. …` | The Redis user can't run `INFO` | Grant `+info`, or set `cocache.redis.assume-maxmemory-policy` to your deployment's policy |
| `Authoritative caches […] have a finite ttl, so maxmemory-policy {…} can evict them before they expire …` | `volatile-*` with `cocache.redis.store-eviction-check: fail` | Use `noeviction`, remove the `ttl`, or accept it with `store-eviction-check: warn` or `off` |
| `Cache[x] - @CoCache.<attribute> = FOREVER is not supported; FOREVER is only allowed for @CoCache.ttl and @CaffeineCache.expireAfterAccess.` | `FOREVER` on an attribute other than `ttl` or `@CaffeineCache.expireAfterAccess` | Use a duration |
| `Cache[x] already exists.` / `Namespace[x] with keyPrefix[p] is already used by Cache[y].` | Two declarations with the same cache name, or with the same prefix and namespace, for example an interface and a spec bean | Rename one, or set a distinct `namespace` or `keyPrefix` |
| `Cache[x] - its storage keys (…) overlap those of Cache[y] (…): one prefix starts the other …` | The storage prefixes `{keyPrefix}{namespace}:` of two caches in the same `CacheRuntime` are equal or one starts the other, for example `app:` + `users` and `app:users:` + `v2`: a key of one would be a key of the other. Nested 6.x namespaces such as `a` and `a:b` end up like this after the split into `keyPrefix` ([upgrade](../guide/upgrade.md#wire-format)). Caches of different runtimes or applications sharing one Redis are not checked. | Give one of them a `keyPrefix` or `namespace` that doesn't start the other's ([storage keys](../guide/caches.md#storage-keys)). Across applications, give each its own `cocache.key-prefix`. |
| `Cache[x] - Namespace[a:b] must match [A-Za-z0-9._-]+. A namespace cannot contain ':': move the prefix into keyPrefix, for example …` | A namespace with `:` or another character outside the set, often a key prefix written into the namespace. The message starts with the cache's type (`Cache[x]`, `Store[x]`, …). | Do what the message suggests: put the part up to the last `:` into `keyPrefix`. If another cache's storage prefix then starts this one's, or the other way round, see the previous row. |
| `Redis denied publishing to the invalidation channel[…] of Keyspace(…): allow the channel pattern [&…inv:*] …` | The Redis ACL doesn't allow the cache's channel. Creating a cache publishes one empty frame to it, so a missing permission fails at startup instead of at every write. | Allow the pattern in the message, see [Redis ACL](./index.md#acl) |
| `Cannot register cache interface[…]: the bean name[x] is already in use.` / `Cannot register cache bean[x] for spec bean[…]` | The cache's bean name collides with another bean | Rename the cache (`@CoCache(name)`) or the spec bean |
| `Cache interface[…] must extend Store with concrete type arguments.` (any of the five types) / `Cache spec bean[…] must declare its generic types` | Generics can't be resolved | Use concrete types, and declare the spec factory method's return type with them, such as `CacheSpec<K, V>` or `StoreSpec<K, V>` |
| `Cache interface[…] declares abstract methods that do not match any Store method: […]` (any of the five types) | An extra abstract method on the interface | Make it a `default` method |
| `Multiple CacheLoader beans match …` / `Multiple Codec beans match …` | Several candidates for one cache | `@CoCache(loader = "beanName")` / `@CoCache(codec = "beanName")` |
| `Both CacheLoader […] and AsyncCacheLoader […] beans match …` | A `CacheLoader` and an `AsyncCacheLoader` bean both match the cache's key and value types | Name one with `@CoCache(loader = "beanName")` |
| `… must extend exactly one of Cache, VersionedCache, Store, VersionedStore, SetStore or one of their Async* or Suspending* views.` | The interface extends none of the five [cache types](../guide/caches.md#types) or their async or suspending views, or several | Extend exactly one |
| `Cache interface[…] has no data source: no CacheLoader or AsyncCacheLoader bean matches …` / `VersionedCache interface[…] has no versioned data source …` | No loader bean of the cache's key and value types | Declare the loader bean, name one with `@CoCache(loader)`, declare `@CoCache(source = EXTERNAL)` when callers supply the values, or extend `Store` / `VersionedStore` when the data can't be rebuilt |
| `@CoCache(loader = …) is not supported on Store interface[…]` (also `VersionedStore`, `SetStore`) / `@CoCache(source = EXTERNAL) is only supported on Cache interfaces …` | An authoritative type has no source | Remove the attribute, or extend `Cache` / `VersionedCache` if the data can be rebuilt |
| `@CoCache(unknownRead = ABSENT) is not supported on …` | `unknownRead = ABSENT` on a type it doesn't apply to: it applies only to `Store`, `VersionedStore` and a `Cache` with `source = EXTERNAL` | Remove the attribute |
| `@CoCache(source = EXTERNAL) cannot name a loader …` | An externally sourced `Cache` that also names a `loader` | Remove `loader` or `source` |
| `Cannot create cache[x] - no CoherenceBackend is available: …` | No Spring Data Redis connection details and no backend bean | Configure `spring.data.redis.*`, set `cocache.backend: memory` for local runs, or declare a `CoherenceBackend` bean |
| `Cannot create cache[x] - CoCache is disabled (cocache.enabled=false)` | Caches declared while CoCache is off | Enable it, or remove the declarations |
| `CoCache does not support Redis master-replica (spring.data.redis.masterreplica.*)` | Static master-replica | Use standalone, Sentinel, or Cluster |
| `RedisConnectionException`, or `Command timed out after …` while creating `cocacheBackend` | Redis unreachable at startup. The backend connects and probes eagerly. | Fix connectivity. A probe timeout fails startup on purpose rather than silently disabling atomic broadcast. |
| `NOPERM` while creating a cache | The ACL denies the cache's channel or keys | See [ACL](./index.md#acl) |
| `@Cacheable without sync = true on CoCache caches: …` | `cocache.spring-cache.require-sync: true` and a non-sync `@Cacheable` | Add `sync = true`, or turn the check off |
| `Spring Cache operations on CoCache caches that are not a Cache: …` | A Spring Cache annotation names a `Store`, `VersionedStore`, `SetStore` or `VersionedCache`, which the CoCache `CacheManager` doesn't expose. Fails whatever `cocache.spring-cache.require-sync` says. | Use the cache's own API, or declare the cache as a `Cache` |
| `Join cache[x] - …` | A `@JoinCacheable` names a missing cache, a wrong type, or both extractor options | Follow the message. See [JoinCache](../guide/join-cache.md). |

One check runs at the first call, not at startup: `Key type [...] does not override toString()` means the cache's key type has no meaningful `toString()`, so every key object would map to a different Redis key. Use a data class or record, override `toString()`, or set a `keyExpression` or `KeyEncoder`.

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

An empty encoded key, or one with an unpaired UTF-16 surrogate (which UTF-8 can't represent, see [keys](../guide/caches.md#keys)), throws `IllegalArgumentException` before anything is deleted or published, and an empty collection does nothing. After `close()`, every call throws `IllegalStateException`; `close()` releases the invalidator's own store handles and doesn't close the backend.

`invalidate` deletes the key from Redis. On the namespace of a `Store`, `VersionedStore` or `SetStore`, Redis holds the only copy, so that deletes the data for good. When Redis already holds the right value and only other instances' L2 copies may be stale, call `broadcast` instead: it publishes the invalidation message without touching Redis, so every instance drops its copy and reloads it from Redis. It is safe on any cache.

```kotlin
externalInvalidator.broadcast("PolicySnapshot", "tenant-1")  // namespace, encoded key; Redis is left alone
```

### Any language: the raw protocol {#raw-protocol}

| Part | Value |
|------|-------|
| Storage key | `{keyPrefix}{namespace}:{encodedKey}`, for example `cocache:UserCache:42` |
| Channel | `{keyPrefix}inv:{namespace}`, for example `cocache:inv:UserCache` |
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
- The keys are full **storage keys**, including prefix and namespace. One frame can carry many keys of the same cache. A key outside the cache's `{keyPrefix}{namespace}:` is ignored without a warning.
- The publisher id must differ from every CoCache instance's id: use 16 random bytes. An instance ignores frames that carry its own id.
- A frame CoCache can't read (unknown version, truncated, trailing bytes) clears every L2 copy of the caches on that channel, with a `WARN`. It costs reloads, never a missed invalidation. A frame with no keys does nothing.

For each change, after the source commits:

1. **`DEL` the storage keys.** This removes the L1 copy and destroys any lease or refresh token, so a load already in flight can't write the old value back.
2. **Publish the frame** on the cache's channel. Every instance drops its L2 copy.

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

::: warning Authoritative types
On a `Store`, `VersionedStore` or `SetStore`, `DEL` deletes the only copy, and on a `VersionedCache` or `VersionedStore` it also drops the version. Don't invalidate these from outside: write them through their API.
:::

## Inspecting Redis by hand {#inspecting-redis}

CoCache values aren't readable JSON: each starts with a 36-byte binary header. Useful commands:

| Question | Command |
|----------|---------|
| Which keys does a cache have? | `redis-cli --scan --pattern 'cocache:UserCache:*' \| head` (per master in a cluster) |
| What state is a key in? | `redis-cli TYPE cocache:UserCache:42`: `string` (value, negative entry, or lease) or `hash` (set). For a string, `redis-cli GETRANGE cocache:UserCache:42 2 2` returns the state: `V` value, `M` negative entry, `L` lease, `E` evicted versioned key. |
| How long does it live? | `redis-cli PTTL cocache:UserCache:42` (milliseconds, `-1` never expires) |
| What's in it? | `GET /actuator/cocache/{name}/{key}` decodes both levels. Raw, for a `Cache` value: `redis-cli GETRANGE key 36 -1` returns the codec's bytes. Values of the other types carry segments after the header ([wire format](../architecture/index.md#wire-format)). |
| How much memory? | `redis-cli MEMORY USAGE cocache:UserCache:42` |
| Who listens on a cache's channel? | `redis-cli PUBSUB NUMSUB cocache:inv:UserCache`, or `PUBSUB SHARDNUMSUB` in `sharded` mode. In a cluster, counts are per node. |
| Which connections are CoCache's? | Set `cocache.redis.client-name`, then `redis-cli CLIENT LIST` and look for that `name`. Subscription connections show `sub=` or `ssub=` above 0. |
| Are invalidations flowing? | `redis-cli SUBSCRIBE cocache:inv:UserCache` (or `SSUBSCRIBE`) prints each frame. The storage keys are readable in the binary output. |

Don't modify CoCache keys by hand, except through the [invalidation protocol](#external-invalidation).
