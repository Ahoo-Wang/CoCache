# CoCache Diagnosis Map

Use this map to locate the first incorrect stage before changing anything. Prefer read-only evidence. `DELETE` on the actuator endpoint, `redis-cli` writes, `MONITOR` on a busy server, restarts and configuration changes on shared environments each need explicit authorization.

## Contents

- [Evidence sources](#evidence-sources)
- [Reading a key inspection](#reading-a-key-inspection)
- [Symptom map](#symptom-map)
- [Startup failures](#startup-failures)
- [Reproducing locally](#reproducing-locally)

## Evidence sources

| Source | Shows |
|---|---|
| `GET /actuator/cocache/{name}/{key}` | L2 and L1 state of one encoded key, without loading, filling or counting |
| `GET /actuator/cocache/{name}` | Per-instance counters: loads, L1 hits, misses, lease waits, fill rejections, degraded fills, reclaims, resets, dropped invalidations, `readsUnknown`, `typeMismatches`, `unversionedAbsentRefusals` |
| `GET /actuator/health/cocache` | `heartbeat.error`, `channelMode`, `atomicBroadcast`, `pendingInvalidations` |
| Micrometer `cocache.*` meters (tag `cache`) | Rates over time; L2 hits only with `cocache.metrics.local-hits=true` |
| Logs | `Cache[x] - created as <type> on namespace[...]` (the declared type), `Invalidation channel heartbeat failed`, `... dropped: L1 keeps the stale value ...`, `corrupted payload, reclaim and reload` |
| Read-only `redis-cli` | `TYPE`, `PTTL`, `GETRANGE key 2 2` (state byte `V`/`M`/`L`, and `E` for an evicted versioned key), `PUBSUB NUMSUB {keyPrefix}inv:{namespace}` (`PUBSUB SHARDNUMSUB` in `sharded` mode), `CLIENT LIST` with `cocache.redis.client-name` |

## Reading a key inspection

| `local` vs `remote` | Meaning |
|---|---|
| Same value | Consistent |
| `local` older than `remote` | This instance missed an invalidation; heals by `local.expiresAt` |
| `local` present, `remote` `MISS` | Invalidated or expired in L1; if `local` predates the invalidation, the message was missed |
| Both old | Nothing invalidated after the source changed |
| `remote` `ABSENT` | Negative entry, for up to `absent-ttl` |
| `local` `UNFILLED` | An externally sourced `Cache` found the key absent: reads without a loader see `null`, reads with one load |
| `remote` `LEASED` | An instance is loading the key now |
| `remote` `UNREADABLE` | Foreign content, codec failure or newer format; see `error` |
| `invalidationPending: true` | An invalidation failed on Redis and is retrying; misses skip L1 meanwhile |

## Symptom map

| Symptom | First stage to check | Likely cause | Direction |
|---|---|---|---|
| One instance serves an old value | Inspection on that instance; its `cocache.resets`, logs, health `channelMode` | Lost message (at most once delivery), mismatched channel mode, namespace or Redis, pubsub output-buffer disconnects | Align configuration; raise the pubsub buffer limit or batch; lower `local-max-age` if the bound is too long |
| Every instance serves an old value | The write paths of that data | A writer that does not invalidate, invalidation before commit, non-sync `@Cacheable`, a loader reading a lagging replica, dropped invalidations, failover, mismatched key encoding | Invalidate the key once (authorized), then close the gap; keep `ttl` finite for writers you do not control |
| A newly created record reads as missing | Inspection shows `ABSENT` | Negative entry cached before the row existed; or data declared as a `Store` that nothing rewrote after Redis lost it | Invalidate on create, or lower `absent-ttl`; a rebuildable copy belongs in a `Cache` |
| `CacheReadException` while Redis is down | `cocache.reads.unknown`, the cache's type | A read without a source (authoritative type, externally sourced `Cache` without a loader) has no answer (`Lookup.Unknown`) | Expected; handle it, or declare `unknownRead = ABSENT` where "absent" is safe; data a loader can rebuild belongs in a `Cache` with a loader |
| Data gone after a restart, failover or memory pressure | The type, `maxmemory-policy`, persistence | Only-copy data declared as an externally sourced `Cache` (expires after `ttl`), `allkeys-*` eviction, no AOF/RDB | Declare a `Store` / `VersionedStore` / `SetStore`; `noeviction`; persistence |
| `cocache.type.mismatch` rising | Declarations of that namespace in every application | A `Cache` or `VersionedCache` shares a namespace with a `Store` | Give each type its own namespace |
| A deleted row keeps reloading from a `VersionedCache` | `cocache.fill.unversioned.absent`, its warning | The versioned loader returns a plain `null` over versioned content | Return `Versioned(null, version)` for deletions |
| Hit rate collapsed, `cocache.local.size` near zero | Health `heartbeat.error`, `cocache.resets` rate | Heartbeat failing: `PING` denied (`NOPERM`), network dropping connections, `command-timeout` too low | Allow `PING`, fix the network path, raise `command-timeout` |
| Source overloaded or `SourceLoadRejectedException` | `cocache.loads`, `remote.failures`, `degraded.fills`, `resets` | Redis failing, synchronized expiry (tiny jitter), cold L1 after resets, uncached key patterns | Handle rejections; raise `load-max-concurrent` only with source headroom; keep jitter; refresh-ahead; `KeyFilter` |
| Several loads per key across instances | `lease.wait.timeouts` vs `lease.waits` | Loader slower than `lease-wait` | Raise `lease-wait`, keep `lease-ttl` above the worst case, or speed up the loader |
| `pendingInvalidations` above zero, `invalidations.dropped` rising | Redis availability, `ERROR` dropped log | `Cache.invalidate` cannot reach Redis | Restore Redis; re-invalidate dropped keys; consider `invalidate-failure: throw` |
| `cocache.reclaims` rising | Inspection `UNREADABLE` | Incompatible value class change, two apps sharing keys with different codecs, foreign writer | New namespace for incompatible changes or dual-format codec; distinct key prefixes |
| `CacheReadException` (cause `ForeignFormatException`) on a read without a source (authoritative type, externally sourced `Cache` without a loader) | Inspection `UNREADABLE` | The key holds content CoCache can't read (wire format 0 or 1, a native Redis type another program wrote, or for a `Store` a tampered digest); a read with a source reclaims and reloads it, one without leaves the key alone | Find the writer; once authorized, `put` the key (or `invalidate` a `Cache`) |
| `cocache.unknown.formats` rising | Versions of all instances | Some instance writes a newer wire format | Expected during a format rollout; otherwise find the newer instance |
| `IllegalArgumentException` about `toString()` at first read | The key type | Key type without a meaningful `toString()` | Data class or record, override, `keyExpression` or `KeyEncoder` |
| `IllegalStateException` on reentrant load | The loader | Loader reads its own cache for the same key | Remove the reentrant read |

## Startup failures

| Message (abridged) | Cause |
|---|---|
| `cocache.caches configures undeclared caches [x]` | Typo in a cache name under `cocache.caches` |
| `... must be positive`, `refreshWindow ... must be less than ...` | Invalid policy after all layers; bare numbers are seconds |
| `@CoCache.<attribute> = FOREVER is not supported` | `FOREVER` outside `ttl`/`expireAfterAccess` |
| `Cache[x] already exists.` / `Namespace[x] is already used` / bean name in use | Duplicate declarations or names |
| `must extend exactly one of [...]` | The interface extends none or several of the five types |
| `must extend <type> with concrete type arguments` / `must declare its generic types` | Raw or unresolved generics |
| `declares abstract methods that do not match any <type> method` | Extra abstract method; make it default |
| `Cache interface[...] has no data source` / `VersionedCache interface[...] has no versioned data source` | No matching loader bean; name one, declare `source = EXTERNAL`, or extend `Store` / `VersionedStore` |
| `Multiple CacheLoader beans match` / `Multiple Codec beans match` | Name one in `@CoCache(loader/codec)` |
| `@CoCache(loader = ...) is not supported on Store interface` (or `VersionedStore`, `SetStore`) / `source = EXTERNAL is only supported on Cache interfaces` | A source on an authoritative type |
| `UnsupportedOperationException` creating a cache | The backend lacks the type's L1 capability (conditional, versioned, sets) |
| `no CoherenceBackend is available` | No Redis connection details and no backend; configure Redis, `cocache.backend: memory` locally, or a backend bean |
| `CoCache does not support Redis master-replica` | Static master-replica topology |
| `RedisConnectionException` / `Command timed out` creating `cocacheBackend` | Redis unreachable at startup (eager connect and probe) |
| `NOPERM` creating a cache | ACL denies keys or channels |
| `@Cacheable without sync = true on CoCache caches` | `require-sync: true` and a non-sync method |
| `Join cache[x] - ...` | Bad `@JoinCacheable` names, types or extractor options |

## Reproducing locally

Most coherence hypotheses reproduce with two `CacheRuntime`s on one `InMemoryBackend` and a `ManualClock`; failure hypotheses with `HookedBackend`; Spring wiring with `@SpringBootTest(properties = ["cocache.backend=memory"])` (see `testing.md`). Keep the failing test as the regression test when a fix is authorized.
