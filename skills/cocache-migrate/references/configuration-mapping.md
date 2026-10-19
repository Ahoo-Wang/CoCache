# 5.x to 6.x Configuration, Operations and Behavior

Use this map for properties, actuator, metrics, dashboards and the behavior changes callers notice. Removed 5.x properties fail 6.x startup with a message naming the replacement; verify every key against the pinned target's `CoCacheProperties`.

## Properties

| 5.x | 6.x |
|---|---|
| `cocache.enabled` | `cocache.enabled` |
| `cocache.redis.strict-failure: true` | `cocache.defaults.read-failure: throw` (or per cache under `cocache.caches.[name]`); explicit writes always throw in 6.x |
| `cocache.redis.eviction-channel: sharded` | `cocache.redis.channel-mode: sharded` |
| `cocache.redis.missing-guard-sentinel` | removed; no in-band sentinel |
| `cocache.load.max-concurrent` | `cocache.defaults.load-max-concurrent` |
| `cocache.load.acquire-timeout` | `cocache.defaults.load-acquire-timeout` |
| `cocache.metrics.client-side-hits` | removed; there is no L2 hit counter |
| `cocache.next.*` (6.0 previews) | `cocache.*` |

New in 6.x: every policy field under `cocache.defaults.*` and `cocache.caches.[name].*` (bare numbers are seconds), including `key-prefix` with placeholders; `cocache.redis.command-timeout`, `heartbeat`, `invalidate-batch-size`, `client-name`, `channel-mode` (bare numbers are milliseconds); `cocache.backend` (`redis` or `memory`); `cocache.spring-cache.require-sync`. A name under `cocache.caches` that matches no declared cache fails startup.

## Redis client and platform

- CoCache 6.x builds its own Lettuce client from `spring.data.redis.*`; Jedis is not supported for CoCache. Add Lettuce; the application may keep Jedis for its own access.
- Redis 7.0+ is required. Static master-replica fails startup; use standalone, Sentinel or Cluster.
- TTL precision moves from seconds to milliseconds.

## Actuator

| 5.x | 6.x |
|---|---|
| `GET /actuator/cocache`, `GET /actuator/cocache/{name}` | same paths, new report shape |
| `GET /actuator/cocache/{name}/{key}` loaded on a miss | inspects L2 and L1 without loading; `{key}` is the encoded key |
| `DELETE /actuator/cocache/{name}/{key}` | invalidates on every instance |
| `cocacheClient` endpoint | removed; `DELETE /actuator/cocache/{name}` clears this instance's L2 |
| none | `cocache` health indicator, `DOWN` while the subscription heartbeat fails |

Remove `cocacheClient` from `management.endpoints.web.exposure.include`.

## Metrics and alerts

All meters keep the `cache` tag.

| 5.x | 6.x |
|---|---|
| `cocache.hits{level=l2}` | removed |
| `cocache.evictions.received` | `cocache.invalidations.received` |
| `cocache.l2.size` | `cocache.local.size` |
| `cocache.eviction.publish.failures` | removed; failures count in `cocache.write.failures`, final failures in `cocache.invalidations.dropped` |
| `cocache.loads` (timer) | `cocache.loads` (function timer) |
| `hits{level=l1}`, `misses`, `load.failures`, `load.rejections`, `filtered`, `stale.discards`, `resets`, `refreshes` | same names (`resets` also counts heartbeat failures) |

New: `cocache.lease.waits`, `lease.wait.timeouts`, `fill.rejections`, `degraded.fills`, `reclaims`, `unknown.formats`, `refresh.rejections`, `remote.failures`, `write.failures`, `invalidation.retries`, `invalidations.dropped`. Update dashboards and alerts in the same change; add an alert on `cocache.invalidations.dropped`.

## Behavior changes callers notice

- L2 copies are bounded by `localMaxAge` (5 minutes by default). 5.x `clientSideTtl = 0` let a copy live until L1 expiry; choose an explicit finite `localMaxAge`.
- Refresh-ahead runs once per cluster; concurrent misses coalesce across instances through an L1 lease when the loader finishes within `lease-wait`.
- A slow load can no longer write an old value over a concurrent invalidation.
- `put` no longer writes L2; the next local read fills it from Redis.
- `put`, `add`, `remove` and `clear` throw `CacheWriteException` when Redis fails; 5.x only logged unless `strict-failure` was set. Review callers' error handling.
- Failed invalidations retry in the background; misses for those keys skip L1 until the retry succeeds.
- `ttlJitter` is capped at `ttl / 2`; durations are at most 100 years; `refreshWindow` must be below `ttl - ttlJitter` and `localMaxAge`.
