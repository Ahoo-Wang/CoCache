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
- [ ] Alerts on dropped invalidations, health DOWN, remote failures, unknown reads, load rejections
- [ ] Tracing: source loads appear as `cocache.load` observations when an `ObservationRegistry` exists (`cocache.observation.enabled`, default on); keys are never recorded
- [ ] Every cache's type matches its data (the start log names it): only-copy data is a Store, VersionedStore or SetStore, never an externally sourced Cache
- [ ] Persistence (AOF or RDB) wherever Store, VersionedStore or SetStore data lives
```

## maxmemory-policy

| Caches | Allowed | Why |
|---|---|---|
| Only `Cache` and `VersionedCache` | Any; `allkeys-lru`/`allkeys-lfu` make Redis a bounded cache | Evicted values are misses; nothing goes stale |
| Any `Store`, `VersionedStore` or `SetStore` | `noeviction`, or `volatile-*` for those without a `ttl` | Redis holds the only copy; `allkeys-*` silently drops data. `VersionedStore` tombstones always expire (`tombstoneTtl`), so `volatile-*` can evict one early and let an older version back in |

With any authoritative type (created or lazy), the starter reads every primary's policy from `INFO memory` at startup. `allkeys-*` always fails startup. Without `+info`, set `cocache.redis.assume-maxmemory-policy`; otherwise startup fails too. `cocache.redis.store-eviction-check` decides only `volatile-*` with a finite-`ttl` authoritative cache: `warn` (default), `fail` or `off`.

Under `noeviction` at `maxmemory`, invalidations still work, reads stop taking leases (every miss loads; `cocache.degraded.fills` rises), and allocating writes fail with `CacheWriteException`.

## ACL

CoCache needs read/write on its key prefixes (`~cocache:*` by default; storage keys are `{keyPrefix}{namespace}:{encodedKey}` and internal keys `{keyPrefix}!…`), channels `&{keyPrefix}inv:*` for each prefix (`&cocache:inv:*` by default), direct commands `EVALSHA`, `EVAL`, `PING`, `INFO` (eviction check, with an authoritative type), the publish/subscribe family for the chosen mode and the connection commands, and the in-script commands `GET GETRANGE STRLEN SET DEL PTTL TYPE HGETALL HKEYS HMGET HSET HDEL HLEN PEXPIRE PERSIST SETRANGE TIME`. In `pubsub` mode the atomic-broadcast probe uses the key `cocache:!probe:{a}` and the channel `cocache:!probe:{b}`, which ignore the key prefix: allow `&cocache:!probe:*` (or the backend falls back to non-atomic broadcast), and with a custom prefix also `~cocache:!probe:*`. An empty prefix leaves keys and channels without a common prefix: list each namespace (`~{namespace}:*`, `&inv:{namespace}`) plus `~!*` for internal keys. A starting point for the default prefix, to confirm against the deployment guide of the resolved version (https://cocache.ahoo.me/operations/#acl):

```
ACL SETUSER cocache on >change-me ~cocache:* &cocache:inv:* &cocache:!probe:* +@connection +evalsha +eval +publish +subscribe +unsubscribe +spublish +ssubscribe +sunsubscribe +info +get +getrange +strlen +set +del +pttl +type +hgetall +hkeys +hmget +hset +hdel +hlen +pexpire +persist +setrange +time
```

On Redis Cluster also allow `+cluster|nodes` and set the user on every node (ACLs aren't replicated). A cache whose channel the ACL denies fails at creation with an `IllegalStateException` naming the pattern to allow. Denying `PING` makes every heartbeat fail, clears every L2 each period, and keeps health `DOWN`.

## Rolling-safe changes

| Change | Rolling-safe | While old and new instances run |
|---|---|---|
| Policy fields (`ttl`, `local-max-age`, `lease-*`, `load-*`, failure modes) | Yes | Each instance applies its own |
| `channel-mode` | L1 yes, L2 no | Peers in different modes miss each other's messages up to `local-max-age`; lower it for the switch or switch all at once |
| `key-prefix`, `namespace`, or the cache name when the namespace defaults to it | No | Different keys; writes on one side leave the other stale until `ttl`. Switch all at once. On an authoritative type the old keys hold the only copy: migrate the data |
| A cache's type (for example `Cache` to `Store`) | No | Every declaration of a namespace must use the same type; stop the old declarations first |
| A codec's byte format | Works, costly | Each side reclaims the other's values (`cocache.reclaims`); prefer a new namespace or a dual-format `decode` |
| A CoCache patch or minor release within the same major version | Yes | The wire format is frozen within a major version |

A deploy shows a burst of `cocache.misses` and `cocache.hits{level=l1}`, not of loads, because new instances fill L2 from L1.

## Sizing

- L2 heap per cache per instance is roughly entries times (decoded value + key + about 100 bytes); watch `cocache.local.size` and size for the hot set.
- Redis per entry: key + 36-byte header + codec bytes (a `Store` value adds 60 bytes of writer id and digest, a `VersionedStore` value 36, a `VersionedCache` value 8); measure with `MEMORY USAGE`.
- All commands of an instance share one connection; large values delay everything behind them.

## Alerts

| Condition | Meaning |
|---|---|
| `increase(cocache_invalidations_dropped_total[5m]) > 0` | An invalidation never reached Redis; old values can be served until their `ttl` |
| health `cocache` `DOWN` over a minute | This instance cannot receive invalidations; its L2 clears every heartbeat |
| sustained `rate(cocache_remote_failures_total[5m]) > 0` | Redis failing; reads with a source go to the source |
| `rate(cocache_reads_unknown_total[5m]) > 0` | Reads without a source have no answer: callers get `CacheReadException` |
| `increase(cocache_fill_unversioned_absent_total[10m]) > 0` | A versioned loader returns a plain `null` for deleted rows; return `Versioned(null, version)` |
| `rate(cocache_load_rejections_total[5m]) > 0` | Callers get `SourceLoadRejectedException` |
| `rate(cocache_resets_total[5m])` above zero outside deploys | Unstable subscription connection |
| lease wait timeouts above 10% of lease waits | Loaders slower than `lease-wait` |
