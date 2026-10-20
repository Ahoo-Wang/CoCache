# Redis Data and Switch-over

Use this reference to plan what happens in Redis and how the cluster moves from 5.x to 6.x. Every command that writes or deletes Redis data needs the exact environment, key pattern and authorization; rehearse on a non-production Redis first.

## Contents

- [What changes in Redis](#what-changes-in-redis)
- [Switch-over strategies](#switch-over-strategies)
- [Leftover 5.x values](#leftover-5x-values)
- [Caches whose only copy is Redis](#caches-whose-only-copy-is-redis)
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

- Caches with a loader: with the default layout both versions use `cocache:{cacheName}:{key}`, so 6.x reads old values: any 5.x value (string, hash or native set, including `_nil_`) is recognized as foreign content (format 0), reclaimed only if unchanged, and reloaded. No manual cleanup is required; expect a burst of `cocache.reclaims` and loads.
- Caches without a loader and every `SetElementsCache`: see the next section. Never rely on reclaim for them.
- A custom 5.x `keyPrefix` gives new 6.x keys, so the old keys are simply ignored and expire with their TTL.
- To free memory early, delete old keys by pattern with `SCAN` and `UNLINK`, per master in a cluster, after the switch and only with authorization. Never `FLUSHDB` a shared Redis.

## Caches whose only copy is Redis

A 5.x cache without a `CacheSource` (Redis is the store of record) and every 5.x `SetElementsCache` hold the only copy of their data in Redis. 6.x cannot reload it, so the data must be converted before 6.x serves traffic.

- On 6.5+, a 6.x read without a loader that finds unmigrated content throws `CacheReadException` (cause `ForeignFormatException`) and keeps the key (`foreign-format: fail`, the default without a loader). On 6.0–6.4 the same read deleted the key and returned `null`, and a `SetCache` read an empty set while `add` replaced the old members. Target 6.5+ for these caches; never set `foreign-format: reclaim` on them.
- On 6.6.0+, a `SetCache.add` or `remove` on unmigrated content throws `CacheWriteException` (cause `ForeignFormatException`) and keeps the key; on 6.0–6.5.0 one `add` replaced the whole native set and the migrator then skipped the key. Target 6.6.0+ for `SetCache`.
- Whole-value writes are not guarded: a `put` or `putAll` replaces the old data, and the migrator then skips that key (no backup).

1. Decide the 6.x shape: if the data's truth lives in a database, use a `Cache` with a loader and no data copy; otherwise `Cache` without a loader, or `SetCache` for sets with element writes.
2. Convert with `CoCache5Migrator` (`cocache-redis`, 6.5+), passing the target 6.x specs and the 5.x key prefixes or explicit keys: `CoCache5Migration.scalar(spec, CoCache5Format.string() | set { … } | hash { … })`, `CoCache5Migration.set(setSpec)`. Run `dryRun` first; it only reads. The upgrade guide section "Caches whose only copy is Redis" of the target release documents the API, the report and the steps.
3. Stop every writer (5.x and 6.x) for the final `migrate`. It is resumable: migrated keys are skipped, and it stops at the first key it can't decode with `CoCache5MigrationException`.
4. Keep the rollback path: in-place migration backs up each 5.x value under `cocache5-backup:`; `rollback` restores them, `dropBackups` removes them once 6.x is verified. Migrating to a new 6.x key prefix leaves 5.x keys untouched instead.
5. Run Redis with persistence and a `noeviction` or `volatile-*` policy for this data.

## External readers and writers

- Programs that read CoCache values directly (`GET`, `HGET` of JSON) break: values are binary. Move them to the source of truth or to an API.
- Programs that evicted with `DEL` plus `PUBLISH {cacheName} key@@id` must switch to the 6.x protocol at the same moment as the cluster: `ExternalInvalidator` on the JVM, or `DEL` of the storage key then the binary frame on `cocache:inv:{namespace}` (frame layout in the operations troubleshooting guide of the target release).
- List every such program in the matrix; one left on 5.x leaves 6.x caches stale until `ttl`.

## Rollback

- Before any 6.x write, rollback is restarting 5.x.
- After 6.x has written shared default keys, treat those keys as unreadable by 5.x and verify that on a rehearsal; rollback then needs those keys deleted (authorized, scoped by pattern) or a strategy that never shared keys.
- Side by side on separate keys keeps 5.x keys untouched, which is the cleanest rollback; `SetCache` data written only under 6.x must be copied back if 5.x resumes.
- Data converted with `CoCache5Migrator`: `rollback` with writers stopped restores the 5.x backups and deletes 6.x keys written to new prefixes; writes made by 6.x after the migration are lost.
- Write the rollback trigger, steps and owner into the matrix before switching.

## Evidence gates

| Gate | Evidence |
|---|---|
| Code compiles and tests pass | Exact commands and results |
| Isolated startup on Redis 7+ | No removed-property failure; health `UP`; one key inspected |
| Data migration rehearsed | `dryRun` and `migrate` reports per cache; values read back by 6.x; `rollback` rehearsed |
| External writers switched | Each program listed with its new protocol and test |
| Dashboards and alerts updated | New meter names; dropped-invalidation alert |
| Switch-over and rollback rehearsed | Steps, timings, observed metrics |

A passing gate does not prove a later one. Record anything not shown as `MISSING EVIDENCE`.
