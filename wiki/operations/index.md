---
title: Deployment
description: Running CoCache in production — Redis versions and topologies, maxmemory-policy, TLS, channel modes, sizing L2 and Redis, rolling deploys, key layout when apps share a Redis, and the ACL permissions CoCache needs.
---

# Deployment

This page covers what to decide before CoCache goes to production. [Observability](./observability.md) covers what to watch afterwards, and [Troubleshooting](./troubleshooting.md) covers what to do when something looks wrong.

## What runs where {#topology}

Every application instance holds its own bounded L2 (Caffeine) for each cache. All instances share one Redis, which holds L1 and carries the invalidation messages. Per instance, CoCache opens:

| Connection or thread | Purpose |
|----------------------|---------|
| 1 command connection | Every cache operation, as one Lua script per call (`EVALSHA`), multiplexed. In Redis Cluster, Lettuce adds a connection per master it routes to. |
| 1 subscription connection | Invalidation messages for every cache namespace on this instance. Pinged every `heartbeat`. |
| `cocache-redis-heartbeat` thread | Heartbeat and resubscription |
| `cocache-clock` thread | Updates the cached clock every 100 ms |
| `cocache-refresh-N` threads | Refresh-ahead, at most 4, only when a cache uses `refresh-window` |
| `cocache-retry-1` thread | Background retries of invalidations that failed on Redis |

CoCache builds its own Lettuce client from `spring.data.redis.*`. It doesn't share your application's Redis connection factory, so its client options never change yours. Its connections only talk to masters, even if your application reads from replicas.

## Redis requirements {#redis}

| Item | Requirement |
|------|-------------|
| Version | **Redis 7.0 or later.** The scripts use `TIME` and `SETRANGE`, and `sharded` mode uses `SPUBLISH`. Valkey 7.2+ implements the same commands. CI runs the Redis backend and starter tests on standalone Valkey 8. |
| Client | Lettuce, which the starter brings. Jedis is not supported, and adding Lettuce doesn't affect a Jedis-based application client. |
| Scripting | `EVAL` / `EVALSHA` must be allowed. The scripts carry no shebang flags. |
| Pub/Sub | `SUBSCRIBE` / `PUBLISH`, or `SSUBSCRIBE` / `SPUBLISH` in `sharded` mode |

### Topologies {#topologies}

The starter picks the topology from Spring Boot's Redis connection details, in this order:

| `spring.data.redis.*` | CoCache connects as | Notes |
|-----------------------|---------------------|-------|
| `cluster.nodes` | Redis Cluster | Adaptive topology refresh. Scripts are routed by key, so there are no `CROSSSLOT` errors. Atomicity holds per slot. |
| `sentinel.master` + `sentinel.nodes` | Sentinel | Follows the current master. Sentinel nodes use `sentinel.username` / `sentinel.password`, data nodes use `username` / `password`. |
| `host` / `port` / `url` | Standalone | `database` is honored for keys. Pub/Sub ignores databases, see [Key layout](#key-layout). |
| `masterreplica.nodes` | **Startup fails** | Static master-replica is not supported: the read script writes leases and refresh tokens, so it must run on a master. Use Sentinel or Cluster, or declare your own `CoherenceBackend` bean. |

Without the starter, build the backend yourself with `RedisBackend.standalone(uri, options)` (standalone or Sentinel URI), `RedisBackend.cluster(uris, options)`, or `RedisBackend.of(client, options)` for a client you own. With `of`, set the client's `disconnectedBehavior` to `REJECT_COMMANDS`, otherwise CoCache logs a warning: Lettuce's default buffers commands while disconnected, so during an outage every command waits the full `command-timeout` instead of failing at once. A buffered write that times out is dropped, not replayed after the reconnect.

### Persistence and failover {#failover}

For a `Cache` or a `VersionedCache`, Redis is a cache: losing data means misses. Asynchronous replication can lose a recent invalidation in a failover, and the old value can come back into L1. It lasts at most until that value's `ttl` expires. If that matters for a cache, keep `ttl` short, or invalidate the affected caches after a failover.

A [`Store`, `VersionedStore` or `SetStore`](../guide/caches.md#store) is different: Redis holds its only copy, so its durability is Redis's durability. Enable persistence (AOF or RDB) on any Redis that holds their data.

## `maxmemory-policy` {#maxmemory-policy}

| Your caches | Allowed policies | Why |
|-------------|------------------|-----|
| Only `Cache` and `VersionedCache` | Any. `allkeys-lru` or `allkeys-lfu` turn Redis into a bounded cache. | An evicted value or negative entry is a miss. An evicted lease means the loader's fill is rejected, and the value stays in that instance's L2 for `degraded-local-max-age`. Nothing goes stale. |
| Any `Store`, `VersionedStore` or `SetStore` | `noeviction`, or a `volatile-*` policy | Redis holds their only copy, so `allkeys-*` would silently lose data. Their values have no expiry by default, so `volatile-*` never evicts them. One given a finite `ttl` can still be evicted under `volatile-*`, and so can a `VersionedStore` tombstone, which always expires after `tombstoneTtl`: once it is gone, an older version is accepted again. |

At startup, if any `Store`, `VersionedStore` or `SetStore` is declared (created or still lazy), CoCache reads every primary's policy from `INFO memory`. It doesn't need `CONFIG`, which managed services often block. `allkeys-*` on any primary stops startup, whatever `cocache.redis.store-eviction-check` says. If the Redis user can't run `INFO`, the check uses `cocache.redis.assume-maxmemory-policy`; without that setting, startup stops too, rather than skipping the check silently. Under `volatile-*`, an authoritative cache with a finite `ttl` is the only case `store-eviction-check` decides: `warn` (default) logs it, `fail` stops startup, `off` accepts it. See [the eviction check](../guide/configuration.md#eviction-check).

When Redis reaches `maxmemory` under `noeviction`:

- Invalidations keep working. Deletes don't need memory, and the scripts carry no flags that would make Redis reject them.
- Reads stop taking leases. Every miss loads from the source, nothing is written to L1, and the result stays in L2 for `degraded-local-max-age`. `cocache.degraded.fills` rises, and cross-instance coalescing stops.
- Writes that allocate memory, such as adding members to an existing set, can fail with an OOM error, which reaches the caller as `CacheWriteException`.
- A write never half-applies. Redis rejects a script at its first memory-allocating command only if the script hasn't written anything yet, so a rejected write changes nothing. A script whose first write is a delete (a `put` replaces the old value this way) runs to the end, even if that takes Redis past `maxmemory`.

## TLS {#tls}

TLS is configured with the standard Spring Boot properties. CoCache applies it to every node it connects to, including Sentinel and Cluster nodes.

| Configuration | Effect |
|---------------|--------|
| `spring.data.redis.ssl.enabled: true` | TLS with the JDK's default trust store |
| `spring.data.redis.ssl.bundle: my-bundle` | TLS with a Spring Boot SSL bundle (`spring.ssl.bundle.*`): key store, trust store, enabled protocols, and cipher suites |
| `spring.data.redis.url: rediss://…` | TLS even without `ssl.enabled`, with the default trust store |

Without the starter, pass a Lettuce `SslOptions` in `RedisBackendOptions(sslOptions = …)` and enable SSL on the `RedisURI`.

## Channel mode {#channel-mode}

`cocache.redis.channel-mode` chooses how invalidations travel. **Every instance that shares a cache must use the same mode.** The two modes don't hear each other.

| | `pubsub` (default) | `sharded` |
|-|--------------------|-----------|
| Commands | `SUBSCRIBE` / `PUBLISH` | `SSUBSCRIBE` / `SPUBLISH` |
| Broadcast | Inside the write script, atomic with the L1 change | Right after the write script succeeds |
| Redis Cluster | Every message crosses the cluster bus to every node | A message stays in the shard that owns the channel's slot |
| Use when | Standalone, Sentinel, small or medium clusters | Large clusters where Pub/Sub fan-out on the cluster bus costs too much |

In `pubsub` mode, the backend checks at startup whether a script may publish to a channel in another slot. Some managed services, such as ElastiCache Serverless, run `PUBLISH` as `SPUBLISH` and reject that. An ACL that denies the probe channel has the same effect. The backend then publishes after each write instead, and logs `Atomic broadcast is not supported by this Redis deployment - publish after writes instead.` The [health indicator](./observability.md#health) shows `atomicBroadcast: false`. Only these definite errors cause the fallback. A timeout during the probe fails startup instead of downgrading for good.

::: warning Without atomic broadcast
In `sharded` mode, or after the probe fell back, the publish is a separate command that runs after the write script. So:

- A crash between the write and the publish loses that message.
- A publish that fails after the write succeeded reaches the caller as a failed write. `put` throws `CacheWriteException` although Redis already holds the new value. `Cache.invalidate` follows `invalidate-failure`, and a retried invalidation publishes again; the authoritative types' deletes throw. Retrying the `put` is safe and publishes again.

In both cases L1 is already correct, and instances that missed the message keep their L2 copy for up to `local-max-age`. The order of a write is in [architecture.md §4.2](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/architecture.md).
:::

## Sizing {#sizing}

### L2 (heap) {#sizing-l2}

Each cache has its own L2 per instance, bounded by `local-maximum-size` (default 10,000 entries; `@CaffeineCache(maximumSize)` on an interface). Heap use is roughly entries × (decoded value + key string + about 100 bytes of entry overhead), summed over every cache. Watch `cocache.local.size`. Size L2 for the hot set, not the whole key space: an L2 miss costs one Redis round trip, not a load.

The L2 hit counter `cocache.hits{level=l2}` is off by default because it slows down the hottest path. To judge L2, compare your request rate with `cocache.misses`, or turn the counter on with [`cocache.metrics.local-hits`](../guide/configuration.md#properties).

### Redis memory {#sizing-redis}

| Entry | Stored as | Size |
|-------|-----------|------|
| Value | String | key + **36-byte header** + codec bytes. A `Store` value adds a 28-byte writer id and a 32-byte digest (60 bytes), a `VersionedStore` value the writer id and an 8-byte version (36 bytes), and a `VersionedCache` value the 8-byte version ([wire format](../architecture/index.md#wire-format)). |
| Negative entry | String | key + 36-byte header, for `absent-ttl`. A versioned tombstone adds the 8-byte version. |
| Evicted versioned key | String | key + 44 bytes (header and version), for the tombstone lifetime |
| Lease | String | key + 36-byte header, while a load runs (at most `lease-ttl`) |
| Set (`SetStore`) | Hash | key + a `!f` field + one `+{member}` field per member |

Add Redis's own per-key overhead. Measure a typical key with `redis-cli MEMORY USAGE cocache:UserCache:42`. Every value has an expiry (`ttl`, plus or minus `ttl-jitter`) unless the cache uses `ttl: forever`, as a `Store`, `VersionedStore` or `SetStore` does by default.

### Redis traffic {#sizing-traffic}

- **Reads.** An L1 hit is one `EVALSHA` (`PTTL` + `GET`). A miss also writes a lease, and refresh-ahead writes a token, so reads cause some replication and AOF traffic.
- **Writes.** `put` and `invalidate` are one script each and publish one message. `invalidateAll` runs one script per `invalidate-batch-size` keys (default 512), and per slot in Redis Cluster.
- **Pub/Sub.** Every instance subscribed to a namespace receives every message for it. A message is about 18 bytes plus each storage key. In `pubsub` mode on Redis Cluster, every node relays every message.
- **Head-of-line blocking.** All of an instance's commands share one connection. Very large values delay everything queued behind them. Keep values small, or put large blobs elsewhere.

## Rolling deploys {#rolling-deploys}

The wire format (key layout, value header and its segments, set fields, message frame, channel names) is frozen within a major version, so instances of one major version can run side by side. A major upgrade is not rolling: stop every instance of the old major version before you start the new one ([upgrade steps](../guide/changelog.md#upgrading-from-6x)). Values in a format this version can't read are reloaded by reads with a source and fail reads without one. A later must-understand change ships in two phases: first a release that reads it but doesn't write it, then, once every instance runs it, a release that writes it. While instances disagree, a value an instance can't read in full is loaded from the source without overwriting it (`cocache.unknown.formats`).

Each new instance starts with an empty L2 and fills it from L1, so a deploy shows a burst of `cocache.misses` and `cocache.hits{level=l1}`, not of loads.

Some configuration changes are not safe to roll out one instance at a time:

| Change | Rolling-safe? | What happens while old and new instances run |
|--------|---------------|-----------------------------------------------|
| Policy fields (`ttl`, `local-max-age`, `lease-*`, `load-*`, failure modes, …) | Yes | Each instance applies its own policy |
| `channel-mode` | L1 yes, L2 no | Instances in different modes don't hear each other. L1 stays correct, but peers' L2 copies can be stale for up to `local-max-age`. Lower `local-max-age` for the switch, or switch every instance at once. |
| `key-prefix`, `namespace`, or the cache name when the namespace defaults to it | **No** | Old and new instances use different keys and ignore each other's invalidations, so a write on one side leaves the other side stale until `ttl`. Switch every instance at once. A changed prefix or namespace also changes the channel. |
| A codec's byte format | Works, but costly | Each side sees the other's values as corrupt, reclaims them, and reloads (`cocache.reclaims`). Prefer a new namespace, or a `decode` that accepts both formats. |

Keys under an old prefix or namespace expire with their `ttl`. Delete them earlier with `redis-cli --scan --pattern 'old-prefix:*' | xargs redis-cli unlink` (run per master in a cluster).

## Key layout and sharing a Redis {#key-layout}

| What | Format | Example |
|------|--------|---------|
| Storage key | `{keyPrefix}{namespace}:{encodedKey}` | `cocache:UserCache:42` |
| Channel | `{keyPrefix}inv:{namespace}` | `cocache:inv:UserCache` |

`keyPrefix` defaults to `cocache:` and may be empty, `namespace` defaults to the cache name, and `encodedKey` is the key's `toString()` or its `keyExpression` result. CoCache's internal keys live under `{keyPrefix}!`, which no namespace can produce. See [storage keys](../guide/caches.md#storage-keys). Instances of different major versions don't share keys: see [rolling deploys](#rolling-deploys).

Several applications can share one Redis:

- **Give each application its own key prefix**, for example `cocache.key-prefix: ${spring.application.name}:`. Without that, two applications with a cache of the same name read and overwrite each other's values. A cache that you deliberately share between applications needs the same prefix, namespace, codec, and channel mode everywhere.
- **The channel carries the prefix.** Applications with different prefixes never receive each other's messages, even when their namespaces coincide. Redis Pub/Sub ignores the database number, though: applications on different databases with identical prefixes and namespaces share a channel, and messages from the other application evict L2 copies needlessly. Use distinct prefixes.

## Security {#security}

### Redis ACL {#acl}

CoCache needs these permissions on its Redis user:

| Area | Needed |
|------|--------|
| Keys | Read and write on your key prefixes (`~cocache:*` by default). In `pubsub` mode also the probe key `cocache:!probe:{a}`, which `~cocache:*` already covers. |
| Channels | `&{keyPrefix}inv:*` for each prefix (`&cocache:inv:*` by default). In `pubsub` mode also `&cocache:!probe:*`, or the backend falls back to non-atomic broadcast. |
| Commands, direct | `EVALSHA`, `EVAL` (fallback after `NOSCRIPT`), `PING` (heartbeat), `PUBLISH`, `SUBSCRIBE`, `UNSUBSCRIBE`, or in `sharded` mode `SPUBLISH`, `SSUBSCRIBE`, `SUNSUBSCRIBE`, plus the connection commands (`HELLO`, `AUTH`, `CLIENT`, `SELECT`). `INFO` for the [startup eviction check](#maxmemory-policy) when you use a `Store`, `VersionedStore` or `SetStore`. |
| Commands, inside scripts | Redis 7 checks these against the caller's ACL too: `GET`, `GETRANGE`, `STRLEN`, `SET`, `DEL`, `PTTL`, `TYPE`, `HGETALL`, `HKEYS`, `HMGET`, `HSET`, `HDEL`, `HLEN`, `PEXPIRE`, `PERSIST`, `SETRANGE`, `TIME` |

A starting point, checked against Redis 7.4 by running every CoCache script as this user:

```
ACL SETUSER cocache on >change-me ~cocache:* &cocache:inv:* &cocache:!probe:* +@connection +evalsha +eval +publish +subscribe +unsubscribe +spublish +ssubscribe +sunsubscribe +info +get +getrange +strlen +set +del +pttl +type +hgetall +hkeys +hmget +hset +hdel +hlen +pexpire +persist +setrange +time
```

The scripts read a set's format fields with `HMGET`, read only the start of the header with `GETRANGE` when they write back or release a token, read only the header with `GETRANGE` and `STRLEN` for conditional reads and writes, and use `HGETALL` for conditional reclaim. A user that lacks a command the scripts call fails reads, fills and conditional calls with an ACL error, worded differently across Redis versions.

Creating a cache publishes one empty frame to the cache's channel, the way its writes will. If the ACL denies that channel, creation fails with an `IllegalStateException` that names the pattern to allow, for example `[&cocache:inv:*]`. Otherwise every later write would change Redis and then fail at its broadcast, and other instances would stay stale until `local-max-age`.

With a custom `key-prefix`, replace `~cocache:*` with your prefixes and `&cocache:inv:*` with `&{keyPrefix}inv:*`. In `pubsub` mode, keep `~cocache:!probe:*` and `&cocache:!probe:*` for the atomic-broadcast probe, which doesn't use the prefix. An empty prefix leaves keys and channels without a common prefix: list each namespace instead (`~{namespace}:*` and `&inv:{namespace}`), plus `~!*` for internal keys. A creation the ACL denies then names its own channel, for example `[&inv:user]`. On Redis Cluster, also allow the commands Lettuce uses for topology discovery (`+cluster|nodes`, `+info`). Set the user on every node: ACLs aren't replicated.

Denying `PING` is a common mistake. The heartbeat then fails every period, each failure clears every L2, and the health indicator stays `DOWN`.

### The actuator endpoint {#endpoint-security}

`GET /actuator/cocache/{name}/{key}` shows cached values, and `DELETE` invalidates them cluster-wide. Expose the endpoint only on the management port or behind authentication, like other actuator endpoints that read your data. To allow inspection but no deletes, set `management.endpoint.cocache.access: read-only`. See [the endpoint](./observability.md#actuator-endpoint).
