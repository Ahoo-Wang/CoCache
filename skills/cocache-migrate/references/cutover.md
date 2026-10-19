# Redis Data and Switch-over

Use this reference to plan what happens in Redis and how the cluster moves from 5.x to 6.x. Every command that writes or deletes Redis data needs the exact environment, key pattern and authorization; rehearse on a non-production Redis first.

## Contents

- [What changes in Redis](#what-changes-in-redis)
- [Switch-over strategies](#switch-over-strategies)
- [Leftover 5.x values](#leftover-5x-values)
- [SetElementsCache sets](#setelementscache-sets)
- [External readers and writers](#external-readers-and-writers)
- [Rollback](#rollback)
- [Evidence gates](#evidence-gates)

## What changes in Redis

| | 5.x | 6.x |
|---|---|---|
| Key | `keyPrefix` + key, default prefix `cocache:{name}:` | `{keyPrefix}{namespace}:{key}`, default `cocache:{name}:{key}` |
| Value | JSON string, hash or native set, `_nil_` sentinel | 36-byte binary header + codec bytes; sets are hashes with `!f` and `+{member}` fields |
| Channel | the cache name | `cocache:inv:{namespace}` |
| Message | `{key}@@{publisherId}` text | binary invalidation frame |
| Invalidation | `DEL` then `PUBLISH` | one atomic script in `pubsub` mode |

Neither version understands the other's messages, and both write the same default keys. That is why there is no rolling upgrade.

## Switch-over strategies

| Strategy | Steps | Trade-off |
|---|---|---|
| Stop-all | Stop every 5.x instance, then start 6.x | Simplest; cold L2; a short outage or drain window |
| Side by side on separate keys | Give every 6.x cache a different key prefix (`cocache.defaults.key-prefix`) or namespace, or a different Redis database, then shift traffic | No shared keys; writes on one side do not invalidate the other, so data changed during the overlap can be stale on the other side until its `ttl` |

Never run both versions on the same keys and channels, even briefly during a rolling deploy. If an overlap is unavoidable, use separate keys, keep it short, and lower `ttl` on the 5.x side for its duration.

## Leftover 5.x values

- With the default layout both versions use `cocache:{cacheName}:{key}`, so 6.x reads old values: any 5.x value (string, hash or native set, including `_nil_`) is recognized as foreign content (format 0), reclaimed only if unchanged, and reloaded. No manual cleanup is required; expect a burst of `cocache.reclaims` and loads.
- A custom 5.x `keyPrefix` gives new 6.x keys, so the old keys are simply ignored and expire with their TTL.
- To free memory early, delete old keys by pattern with `SCAN` and `UNLINK`, per master in a cluster, after the switch and only with authorization. Never `FLUSHDB` a shared Redis.

## SetElementsCache sets

A 5.x `SetElementsCache` stored the only copy in a native Redis set, which a 6.x `SetCache` cannot read: a read returns an empty set (counted in `cocache.unknown.formats`, content left in Redis) and `add` replaces the old set instead of extending it.

1. Decide the 6.x shape: if the set's truth lives in the database, use `Cache<K, Set<E>>` with a loader and no data copy; otherwise `SetCache`.
2. For `SetCache`, copy before 6.x serves traffic: read each old set (`SMEMBERS`) and write it with `setCache.put(key, fullSet)`; or give the `SetCache` a new key prefix and copy from the old keys, which keeps the 5.x data intact for rollback.
3. Make the copy idempotent and resumable, count members per key on both sides, and stop all 5.x writers to those sets before the final pass.
4. Run Redis with persistence and a `noeviction` or `volatile-*` policy for set data.

## External readers and writers

- Programs that read CoCache values directly (`GET`, `HGET` of JSON) break: values are binary. Move them to the source of truth or to an API.
- Programs that evicted with `DEL` plus `PUBLISH {cacheName} key@@id` must switch to the 6.x protocol at the same moment as the cluster: `ExternalInvalidator` on the JVM, or `DEL` of the storage key then the binary frame on `cocache:inv:{namespace}` (frame layout in the operations troubleshooting guide of the target release).
- List every such program in the matrix; one left on 5.x leaves 6.x caches stale until `ttl`.

## Rollback

- Before any 6.x write, rollback is restarting 5.x.
- After 6.x has written shared default keys, treat those keys as unreadable by 5.x and verify that on a rehearsal; rollback then needs those keys deleted (authorized, scoped by pattern) or a strategy that never shared keys.
- Side by side on separate keys keeps 5.x keys untouched, which is the cleanest rollback; `SetCache` data written only under 6.x must be copied back if 5.x resumes.
- Write the rollback trigger, steps and owner into the matrix before switching.

## Evidence gates

| Gate | Evidence |
|---|---|
| Code compiles and tests pass | Exact commands and results |
| Isolated startup on Redis 7+ | No removed-property failure; health `UP`; one key inspected |
| Set copy rehearsed | Per-key member counts match; resumable |
| External writers switched | Each program listed with its new protocol and test |
| Dashboards and alerts updated | New meter names; dropped-invalidation alert |
| Switch-over and rollback rehearsed | Steps, timings, observed metrics |

A passing gate does not prove a later one. Record anything not shown as `MISSING EVIDENCE`.
