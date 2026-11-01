# Production Readiness

Use this reference before CoCache reaches production, when reviewing deployment configuration, or when planning a configuration change on a running cluster. Verify every item against the target environment; record what cannot be checked as an evidence gap.

## Checklist

```
- [ ] Redis 7.0+ or Valkey 7.2+ (CI tests Valkey 8), EVAL/EVALSHA allowed, Pub/Sub (or sharded Pub/Sub) allowed
- [ ] Topology: standalone, Sentinel or Cluster (not static master-replica)
- [ ] maxmemory-policy fits the caches (below)
- [ ] ACL grants keys, channels, direct and in-script commands, including PING
- [ ] Every instance shares key prefix, namespace, codec and channel-mode
- [ ] Applications sharing a Redis use distinct key prefixes
- [ ] L2 sized for the hot set; values small
- [ ] Actuator endpoint behind auth or the management port; read-only if deletes are not wanted
- [ ] Alerts on dropped invalidations, health DOWN, remote failures, load rejections
- [ ] Tracing: source loads appear as `cocache.load` observations when an `ObservationRegistry` exists (`cocache.observation.enabled`, default on); keys are never recorded
- [ ] Persistence (AOF or RDB) wherever SetCache data lives
```

## maxmemory-policy

| Caches | Allowed | Why |
|---|---|---|
| Only `Cache` | Any; `allkeys-lru`/`allkeys-lfu` make Redis a bounded cache | Evicted values are misses; nothing goes stale |
| Any `SetCache` | `noeviction`, or `volatile-*` for sets without a `ttl` | Redis holds the only copy; `allkeys-*` silently drops members |

With a `SetCache` (created or lazy), the starter reads every primary's policy from `INFO memory` at startup: `allkeys-*` warns (`cocache.redis.set-cache-eviction-check: fail` stops startup), `volatile-*` with a finite-`ttl` set warns. Without `+info`, set `cocache.redis.assume-maxmemory-policy`; otherwise the check warns, or fails in `fail` mode.

Under `noeviction` at `maxmemory`, invalidations still work, reads stop taking leases (every miss loads; `cocache.degraded.fills` rises), and allocating writes fail with `CacheWriteException`.

## ACL

CoCache needs read/write on its key prefixes (`~cocache:*` by default, plus `~cocache:probe:*` in `pubsub` mode), channels `&cocache:inv:*` (plus `&cocache:probe:*` in `pubsub` mode), direct commands `EVALSHA`, `EVAL`, `PING`, `INFO` (eviction check, with a `SetCache`), the publish/subscribe family for the chosen mode and the connection commands, and the in-script commands `GET SET DEL PTTL TYPE HGETALL HKEYS HGET HSET HDEL HEXISTS HLEN PEXPIRE PERSIST SETRANGE TIME`. Copy the exact `ACL SETUSER` line from the deployment guide of the resolved version. Upgrading to 6.3: add `+hkeys +hget` before the upgrade, and keep `+hgetall`. Denying `PING` makes every heartbeat fail, clears every L2 each period, and keeps health `DOWN`.

## Rolling-safe changes

| Change | Rolling-safe | While old and new instances run |
|---|---|---|
| Policy fields (`ttl`, `local-max-age`, `lease-*`, `load-*`, failure modes) | Yes | Each instance applies its own |
| `channel-mode` | L1 yes, L2 no | Peers in different modes miss each other's messages up to `local-max-age`; lower it for the switch or switch all at once |
| `key-prefix`, `namespace`, or the cache name when the namespace defaults to it | No | Different keys; writes on one side leave the other stale until `ttl`. Switch all at once. |
| A codec's byte format | Works, costly | Each side reclaims the other's values (`cocache.reclaims`); prefer a new namespace or a dual-format `decode` |
| CoCache 6.x patch or minor | Yes | The wire format is frozen within a major version |

A deploy shows a burst of `cocache.misses` and `cocache.hits{level=l1}`, not of loads, because new instances fill L2 from L1.

## Sizing

- L2 heap per cache per instance is roughly entries times (decoded value + key + about 100 bytes); watch `cocache.local.size` and size for the hot set.
- Redis per entry: key + 36-byte header + codec bytes; measure with `MEMORY USAGE`.
- All commands of an instance share one connection; large values delay everything behind them.

## Alerts

| Condition | Meaning |
|---|---|
| `increase(cocache_invalidations_dropped_total[5m]) > 0` | An invalidation never reached Redis; old values can be served until their `ttl` |
| health `cocache` `DOWN` over a minute | This instance cannot receive invalidations; its L2 clears every heartbeat |
| sustained `rate(cocache_remote_failures_total[5m]) > 0` | Redis failing; reads go to the source |
| `rate(cocache_load_rejections_total[5m]) > 0` | Callers get `SourceLoadRejectedException` |
| `rate(cocache_resets_total[5m])` above zero outside deploys | Unstable subscription connection |
| lease wait timeouts above 10% of lease waits | Loaders slower than `lease-wait` |
