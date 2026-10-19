---
title: Observability
description: Every cocache.* Micrometer meter and what it means, which ones to alert on, tracing of source loads with Micrometer Observation, the cocache health indicator, the actuator endpoint (list caches, clear L2, inspect and invalidate one key), and the log lines worth alerting on.
---

# Observability

CoCache reports through four channels, all provided by the starter when `spring-boot-starter-actuator` is on the classpath:

| Channel | What for |
|---------|----------|
| [Metrics](#metrics) (`cocache.*`) | Trends and alerts |
| [Tracing](#tracing) (`cocache.load`) | Where a slow miss spent its time |
| [Health indicator](#health) (`cocache`) | Is this instance receiving invalidations? |
| [Actuator endpoint](#actuator-endpoint) (`/actuator/cocache`) | Per-instance counters, inspecting one key, forcing an invalidation |

Plus the [logs](#logs). None of them touch the L2 hit path, except the [L2 hit counter](#metrics-reads), which is off by default.

## Metrics {#metrics}

The starter binds these meters to every `MeterRegistry`. Each carries a `cache` tag with the cache name. They read counters the engine keeps anyway, are cumulative since startup, and can't be turned off one by one. Caches created lazily are bound when their bean is initialized.

Prometheus names follow Micrometer's conventions: `cocache.misses` becomes `cocache_misses_total`, the timer `cocache.loads` becomes `cocache_loads_seconds_count` and `cocache_loads_seconds_sum`, and the gauge becomes `cocache_local_size`.

### Reads {#metrics-reads}

| Meter | Type | Counts |
|-------|------|--------|
| `cocache.misses` | counter | Reads that missed L2, counted per call before concurrent misses for a key are coalesced. Reads answered by the `KeyFilter` are not included. For a `SetCache`: reads that found no set in L1. |
| `cocache.hits{level=l2}` | counter | Reads answered by L2, including negative entries. Registered only with `cocache.metrics.local-hits=true`. |
| `cocache.hits{level=l1}` | counter | L2 misses answered by L1, including negative entries |
| `cocache.filtered` | counter | Reads the `KeyFilter` answered as "doesn't exist", without touching Redis |
| `cocache.lease.waits` | counter | Misses that found another instance loading the key and waited for it |
| `cocache.lease.wait.timeouts` | counter | Misses that stopped waiting (after `lease-wait`, or after the other lease's remaining time if that is shorter) and loaded themselves without writing L1 |

Every read (every key of a `getAll`) counts exactly once in `hits{level=l2}`, `misses`, or `filtered`. An L2 miss then counts in `hits{level=l1}` if Redis had the key, or in `loads` if the loader ran.

The L2 hit counter is **off by default**. Counting adds an atomic increment to every L2 hit, the hottest path, and costs measurable throughput under many threads. Turn it on with `cocache.metrics.local-hits=true` (without Spring: `CacheRuntime.builder().recordLocalHits(true)`). It applies to every cache of the runtime. With it off, L2 hits are your request rate minus `cocache.misses` and `cocache.filtered`.

### Loads {#metrics-loads}

| Meter | Type | Counts |
|-------|------|--------|
| `cocache.loads` | function timer | Loader calls, with total time. A `loadAll` batch counts once. Includes calls that threw. |
| `cocache.load.failures` | counter | Loader calls that threw. The exception reaches the caller and every coalesced waiter. |
| `cocache.load.rejections` | counter | Loads rejected by `load-max-concurrent`: no permit within `load-acquire-timeout`. The caller got `SourceLoadRejectedException`. |
| `cocache.refreshes` | counter | Refresh-ahead tasks that ran |
| `cocache.refresh.rejections` | counter | Refreshes the refresh executor rejected. The value is served until it expires normally. |

### Fills and fences {#metrics-fills}

| Meter | Type | Counts |
|-------|------|--------|
| `cocache.fill.rejections` | counter | Loads that held a lease, but Redis refused the fill: an invalidation destroyed the lease, the loader outlived `lease-ttl`, or the fill failed |
| `cocache.degraded.fills` | counter | Loads written to L2 only, for `degraded-local-max-age`: Redis read failed, lease wait timed out, fill rejected, value in an unknown format, or an invalidation of the key still pending |
| `cocache.stale.discards` | counter | L2 fills dropped because an invalidation of the key arrived while the read was in flight. This is the L2 fence working. |
| `cocache.reclaims` | counter | L1 values this instance couldn't decode (foreign content or codec failure). A separate reclaim script then converts the value into a lease, if it is still unchanged, and the instance reloads. |
| `cocache.unknown.formats` | counter | L1 values written in a newer wire format than this instance knows. Loaded from the source, nothing overwritten. |

### Writes, invalidations, channel {#metrics-writes}

| Meter | Type | Counts |
|-------|------|--------|
| `cocache.remote.failures` | counter | Redis operations on the read side that failed: read, fill, release, reclaim, refresh |
| `cocache.write.failures` | counter | `put`, `invalidate`, `invalidateAll`, and `SetCache` writes that failed on Redis (first attempt) |
| `cocache.invalidation.retries` | counter | Retry attempts of failed invalidations |
| `cocache.invalidations.dropped` | counter | Invalidations given up after all retries, on a full retry queue, or at shutdown. L1 still holds the old value. |
| `cocache.invalidations.received` | counter | Invalidation messages from other instances or external writers (one per message, which may carry many keys) |
| `cocache.resets` | counter | Times this cache cleared its whole L2 because messages may have been lost: each confirmed (re)subscription, including the first one at startup, and each failed heartbeat |
| `cocache.local.size` | gauge | Entries in this instance's L2 (estimated) |

### Alerts {#alerts}

| Condition | Severity | Meaning | Runbook |
|-----------|----------|---------|---------|
| `increase(cocache_invalidations_dropped_total[5m]) > 0` | page | An invalidation never reached Redis. Every instance may serve the old value until its `ttl`. | [Invalidations dropped](./troubleshooting.md#invalidations-dropped) |
| health `cocache` is `DOWN` for more than a minute | page | This instance can't receive invalidations, and its L2 is cleared at every heartbeat | [Hit rate collapsed](./troubleshooting.md#resets) |
| `rate(cocache_remote_failures_total[5m]) > 0` sustained | warn | Redis is failing. Reads go to the source. | [Redis down](./troubleshooting.md#redis-down) |
| `rate(cocache_load_rejections_total[5m]) > 0` | warn | Callers are getting `SourceLoadRejectedException` | [Load rejections](./troubleshooting.md#load-rejections) |
| `rate(cocache_resets_total[5m])` above zero outside deploys | warn | The subscription connection is unstable | [Hit rate collapsed](./troubleshooting.md#resets) |
| `cocache_lease_wait_timeouts_total` / `cocache_lease_waits_total` above 10% | warn | Loaders are slower than `lease-wait`, so concurrent misses across instances each load | [Lease wait timeouts](./troubleshooting.md#lease-wait-timeouts) |
| `increase(cocache_unknown_formats_total[10m]) > 0` outside a deploy | warn | Some instance writes a newer format | [Unknown format](./troubleshooting.md#unknown-format) |
| `rate(cocache_reclaims_total[5m]) > 0` sustained | warn | Something writes values this instance can't decode | [Corrupt payloads](./troubleshooting.md#corrupt-payloads) |
| `rate(cocache_degraded_fills_total[5m])` rising | info | More loads bypass L1, so the source carries more load | [Redis down](./troubleshooting.md#redis-down) |

## Tracing {#tracing}

When `micrometer-observation` is on the classpath and the context has an `ObservationRegistry` bean (Spring Boot's `ObservationAutoConfiguration` provides one with the actuator), the starter registers the bean `cocacheLoadObserver`, a `MicrometerLoadObserver`. It wraps every source load in a Micrometer `Observation`. Your tracing bridge (for example `micrometer-tracing-bridge-otel`) turns it into a span, and Spring Boot's metrics handler into a timer.

| Field | Value |
|-------|-------|
| Name | `cocache.load` |
| Contextual (span) name | `cocache {operation} {cache}`, for example `cocache load UserCache` |
| `cache` (low cardinality) | The cache name |
| `operation` (low cardinality) | `load` (a miss, `get(key, loader)`, or a batch falling back to one key), `loadAll` (one batch load), `refresh` (a refresh-ahead load), `lease.wait` (waiting for another instance's lease) |
| `outcome` (low cardinality) | `success` or `error`. On `error` the exception is recorded on the observation and rethrown unchanged. |
| `key.count` (high cardinality, spans only) | Keys in the call: 1, or the batch size for `loadAll` |

What is observed, and what isn't:

- Only the loader call itself, and the wait for another instance's lease when there is one. L2 hits, L1 reads, and fills create no observation, and with no observer configured the engine doesn't even allocate. A load rejected by `load-max-concurrent` is not observed.
- The key is never recorded: it is high cardinality and may be personal data.
- The loader runs on the caller's thread inside the observation's scope, so spans your loader creates (JDBC, HTTP) become children of `cocache.load`, which is itself a child of the request span. Refresh-ahead runs on the refresh executor, so its spans have a parent only if that executor propagates context.
- With Spring Boot's metrics, each observation also feeds a `cocache.load` timer and a `cocache.load.active` long task timer, tagged `cache`, `operation`, `outcome` (plus Micrometer's own `error` tag on the timer). They overlap with the [`cocache.loads`](#metrics-loads) function timer, which stays the cheap always-on one.

Turn it off with `cocache.observation.enabled: false`, or with Spring Boot's `management.observations.enable.cocache: false`. A `LoadObserver` bean of your own replaces the Micrometer one. Without Spring, pass it to `CacheRuntime.builder().loadObserver(…)`. Implementations must run the block once on the calling thread and rethrow its exceptions unchanged.

## Health indicator {#health}

The starter registers the health contributor `cocache` (bean `cocacheHealthIndicator`). It reads in-memory state only and never calls Redis, so it is cheap to poll. Turn it off with `management.health.cocache.enabled: false`.

| Status | When |
|--------|------|
| `DOWN` | The last heartbeat of the Redis subscription connection failed. Invalidations from other instances may be missed, and this instance's L2 has been cleared. L1 may still work. |
| `UP` | Otherwise, including while invalidations are being retried |

`GET /actuator/health/cocache` (with `management.endpoint.health.show-details` allowing details):

```json
{
  "status": "UP",
  "details": {
    "backend": "RedisBackend",
    "format": 1,
    "atomicBroadcast": true,
    "channelMode": "pubsub",
    "heartbeat": { "healthy": true },
    "pendingInvalidations": 0,
    "caches": {
      "UserCache": { "pendingInvalidations": 0, "localSize": 8123 }
    }
  }
}
```

| Detail | Meaning |
|--------|---------|
| `backend` | `RedisBackend`, `InMemoryBackend`, or your own class |
| `format` | Wire format version this instance writes |
| `atomicBroadcast` | `false` means writes publish after the script, see [channel mode](./index.md#channel-mode) |
| `channelMode` | `pubsub` or `sharded` (Redis only) |
| `heartbeat` | `healthy`, and `error` with the last failure (Redis only). Recovers on the next successful ping. |
| `pendingInvalidations` | Keys whose invalidation failed on Redis and is being retried, in total and per cache. Until a retry succeeds, misses for these keys skip L1. |
| `caches.*.localSize` | L2 entries (estimated) |

Pending invalidations don't change the status: the retry queue converges on its own, and a final failure shows in `cocache.invalidations.dropped`.

**Readiness.** Leave `cocache` out of the liveness group: a restart doesn't fix Redis. Whether to add it to readiness (`management.endpoint.health.group.readiness.include: readinessState,cocache`) is a trade-off. While `DOWN`, the instance serves from L1 and the source, with staleness bounded by `local-max-age`, so it usually is still fit to serve. Add it only if missing invalidations is worse than losing capacity. Be aware that a Redis outage then takes every instance out of rotation.

## Actuator endpoint {#actuator-endpoint}

The endpoint `cocache` exists only when it is available, so expose it:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, cocache
```

| Request | Effect | Scope |
|---------|--------|-------|
| `GET /actuator/cocache` | Report for every cache | this instance |
| `GET /actuator/cocache/{name}` | Report for one cache | this instance |
| `DELETE /actuator/cocache/{name}` | Clears L2 for this cache. L1 is untouched. Loads in flight don't write their result into L2. | this instance |
| `GET /actuator/cocache/{name}/{key}` | [Inspects one key](#inspect-key) in L2 and L1 | this instance, plus one Redis read |
| `DELETE /actuator/cocache/{name}/{key}` | [Invalidates one key](#invalidate-key) | **every instance** |

`{name}` is the cache name. For an unknown name, `GET` returns `404` and `DELETE` returns `204` with no body (a successful per-key `DELETE` returns `200` with a report). An empty `{key}` is rejected with `400`. Spring drops empty path segments and surrounding whitespace, so a key containing `//`, a leading or trailing `/`, or surrounding spaces can't be addressed exactly through the endpoint.

A cache report:

```json
{
  "name": "UserCache",
  "localSize": 8123,
  "loads": 5120,
  "loadNanos": 912000000,
  "metrics": { "remoteHits": 40211, "misses": 45342, "filtered": 0, "loadFailures": 2, "loadRejected": 0, "leaseWaits": 31, "leaseWaitTimeouts": 0, "fillRejected": 1, "degradedFills": 1, "staleFillsDiscarded": 3, "reclaims": 0, "unknownFormats": 0, "refreshes": 0, "refreshRejected": 0, "remoteFailures": 0, "invalidationsReceived": 977, "writeFailures": 0, "invalidationRetries": 0, "invalidationsDropped": 0, "resets": 1 }
}
```

The `metrics` keys map to the meters in order: `localHits` is `cocache.hits{level=l2}` (present only when the L2 hit counter is on), `remoteHits` is `cocache.hits{level=l1}`, `loadRejected` is `cocache.load.rejections`, `fillRejected` is `cocache.fill.rejections`, `staleFillsDiscarded` is `cocache.stale.discards`, `refreshRejected` is `cocache.refresh.rejections`, and the rest match their meter names. `loads` and `loadNanos` are the `cocache.loads` timer.

### Encoded keys {#encoded-key}

`{key}` is the **encoded key**: the part of the Redis key after `{keyPrefix}{namespace}:`. That is the key's `toString()`, or the result of the cache's `keyExpression`, so the endpoint works for any key type.

| Cache key | Encoded key | Request |
|-----------|-------------|---------|
| `"42"` in `UserCache` | `42` | `/actuator/cocache/UserCache/42` |
| `OrderId(tenant=7, id=9)` as a data class | `OrderId(tenant=7, id=9)` | `/actuator/cocache/OrderCache/OrderId(tenant=7,%20id=9)` |
| `keyExpression = "#{tenant}/#{id}"` | `7/9` | `/actuator/cocache/OrderCache/7/9` |

- A `/` in the key is written as is: every path segment after the cache name is joined with `/`.
- Percent-encode spaces, `%`, `?`, and `#`.
- Keys with empty segments (`a//b`, a leading or trailing `/`), `.` or `..` segments, `;`, or an encoded `%2F` may be changed or rejected by the web server's path handling. For those, call `CacheAdmin` from code: `runtime.admin("OrderCache")?.inspect(encodedKey)`.

### Inspecting a key {#inspect-key}

```bash
curl -s localhost:8080/actuator/cocache/UserCache/42
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

| Field | Values |
|-------|--------|
| `storageKey` | The full Redis key, for `redis-cli` |
| `local` | This instance's L2 copy, or `null`. `state`: `VALUE`, `ABSENT` (negative entry), or `UNRESOLVED` (a miss on a cache without a loader). `expired: true` is a copy past its `expiresAt` that Caffeine hasn't removed yet. It is never served. |
| `remote.state` | `VALUE`, `ABSENT` (negative entry), `SET` (`SetCache`), `LEASED` (an instance is loading it, `ttl` is the lease's remaining time), `MISS`, `UNREADABLE` (foreign content, a newer format, wrong shape, or a codec failure, with the reason in `error`), or `ERROR` (the Redis read failed) |
| `remote.ttl` | Remaining lifetime in L1, `null` if it never expires |
| `value` | The value decoded with the cache's codec, as `toString()`, cut at 256 characters |
| `invalidationPending` | An invalidation of this key failed on Redis and is still being retried. Until it succeeds, misses skip L1. |

Inspecting is read-only. It doesn't call the loader, take a lease or refresh token, fill either level, coalesce with loads, count in metrics, reclaim corrupt values, or disturb Caffeine's eviction statistics. It costs one Redis read.

How to read the result:

| `local` vs. `remote` | Meaning |
|----------------------|---------|
| Same value | Consistent |
| `local` older than `remote` | This instance missed an invalidation. It heals by `local.expiresAt` at the latest. See [stale on one node](./troubleshooting.md#stale-one-node). |
| `local` present, `remote` `MISS` | The key was invalidated or expired in L1. If `local` is older than the invalidation, this instance missed the message. |
| Both old | Nothing invalidated the key after the source changed. See [stale everywhere](./troubleshooting.md#stale-everywhere). |
| `remote` `UNREADABLE` | See [corrupt payloads](./troubleshooting.md#corrupt-payloads) or [unknown format](./troubleshooting.md#unknown-format) |

### Invalidating a key {#invalidate-key}

```bash
curl -s -X DELETE localhost:8080/actuator/cocache/UserCache/42
# {"cache":"UserCache","key":"42"}
```

This runs the normal invalidation path, as `cache.invalidate(key)` does: L1 is deleted, every instance drops its L2 copy, and the next read loads from the source. It is idempotent. If Redis fails, it follows the cache's `invalidate-failure` setting (by default queued for retry and `200` returned; with `throw`, an error). On a `SetCache` it is `clear(key)`, which **deletes the set's only copy**.

## Logs {#logs}

Loggers live under `me.ahoo.cocache`. Lines worth an alert or a dashboard:

| Level | Message (prefix) | Meaning |
|-------|------------------|---------|
| `ERROR` | `Cache[…] - invalidate […] dropped: L1 keeps the stale value until its remote TTL[…] expires.` | An invalidation was given up. Same as `cocache.invalidations.dropped`. |
| `WARN` | `Cache[…] - invalidate […] failed, retrying.` | An invalidation failed on Redis and was queued |
| `WARN` | `Invalidation channel heartbeat failed - reset all subscribers and force reconnect.` | The subscription connection failed its ping. Every L2 is cleared. |
| `WARN` | `Invalidation channel - cannot force reconnect of the subscription connection, rely on TCP keepalive.` | Redis Cluster in `pubsub` mode: CoCache can't drop the connection itself, so it relies on TCP keepalive |
| `WARN` | `Invalidation channel[…] unsubscribed by server (slot migration) - reset subscribers and resubscribe.` | `sharded` mode, slot moved. Normal during resharding. |
| `WARN` | `Invalidation channel[…] - subscribed on […] but slot is owned by […] - reset subscribers and resubscribe.` | `sharded` mode, failover detected by the heartbeat |
| `WARN` | `Invalidation channel[…] - resubscribe failed, retry on next heartbeat.` | Resubscription will be retried |
| `WARN` | `Invalidation channel[…] - subscribe acknowledgement not received in …` | Redis didn't confirm a subscription within `command-timeout` |
| `WARN` | `Ignore malformed invalidation frame on channel[…]` | Someone publishes non-CoCache messages on a CoCache channel. See [external invalidation](./troubleshooting.md#external-invalidation). |
| `WARN` | `Cache[…] - key[…] - remote … failed, degrading.` | A Redis read, fill, release, or reclaim failed. The read was served from the source. |
| `WARN` | `Cache[…] - key[…] - corrupted payload, reclaim and reload.` | An L1 value couldn't be decoded |
| `WARN` | `Cache[…] - key[…] - refresh failed.` / `refresh rejected.` | Refresh-ahead didn't run or failed. The value expires normally. |
| `WARN` | `Atomic broadcast is not supported by this Redis deployment - publish after writes instead.` | Startup probe fell back, see [channel mode](./index.md#channel-mode) |
| `WARN` | `@Cacheable on … uses CoCache cache … without sync = true …` | A Spring Cache method isn't lease-protected, see [Spring Cache](../guide/spring-cache.md#use-sync-true) |
| `WARN` | `CoCache uses the in-memory backend …` | `cocache.backend=memory` in a deployed environment is a misconfiguration |
| `INFO` | `Invalidation channel[…] subscribed - reset subscribers.` and `Cache[…] - reset: clear local copies.` | A (re)subscription cleared L2. Expected at startup and after reconnects. |
