---
title: Operations
description: Running CoCache in production — actuator endpoints, Redis failure behavior, the negative-cache sentinel, sizing, and troubleshooting stale reads.
---

# Operations

## Actuator endpoints

With `spring-boot-starter-actuator` on the classpath, the starter registers two endpoints. Expose them like any other actuator endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: cocache, cocacheClient
```

| Request | Effect |
|---------|--------|
| `GET /actuator/cocache` | Report for every cache: name, `clientId`, L2 size, TTL policy, and component classes |
| `GET /actuator/cocache/{name}` | Report for one cache |
| `GET /actuator/cocache/{name}/{key}` | Reads the entry through the cache. **On a miss, this loads from the source.** |
| `DELETE /actuator/cocache/{name}/{key}` | Evicts the key from L2 and L1 and notifies every instance |
| `GET /actuator/cocacheClient/{name}` | Number of entries in this instance's L2 |
| `GET /actuator/cocacheClient/{name}/{key}` | This instance's L2 entry, without loading |
| `DELETE /actuator/cocacheClient/{name}` | Clears this instance's L2 |

The `DELETE` operations change the cache. Secure the endpoints the same way you secure other write-capable actuator endpoints.

## Redis failures

With the default `cocache.redis.strict-failure=false`, a Redis error never reaches your business code:

| Operation | Default (degrade) | `strict-failure=true` |
|-----------|-------------------|------------------------|
| L1 read | Logged at `WARN` and treated as a miss. The value loads from the source. | Rethrows `DataAccessException` |
| L1 write / evict | Logged at `WARN` | Rethrows |
| Publishing an eviction event | Logged at `WARN` (in both modes) | Logged at `WARN` |

Plan for the cost of degrading. While Redis is down, every L2 miss goes to your data source. That is at most one load per key per instance at a time, because concurrent misses are coalesced. Make sure the source can absorb that load, or choose `strict-failure=true` and handle the exceptions yourself.

When the eviction channel reconnects, each instance clears its L2 and logs `Channel[…] subscribed - reset subscriber` at `INFO`. Events published during the outage were lost, so the clear stops the old L2 entries from outliving it.

## The negative-cache sentinel

In Redis, a negative entry is stored as a sentinel value, `_nil_` by default:

| Redis type | Negative entry |
|------------|----------------|
| String | `_nil_` |
| Hash | a single field `{_nil_: <written-at>}` |
| Set | a single member `{_nil_}` |

In memory, negative entries are a distinct type (`MissingValue`), so the sentinel can collide only inside Redis. A string cache whose real value can be exactly `_nil_` would read that value back as "not found". A hash or set written by another program could collide the same way. In those cases, set a different sentinel:

```yaml
cocache:
  redis:
    missing-guard-sentinel: "\u0000myapp:nil"
```

The default and a custom sentinel do not recognize each other, so **change it on every instance at once**. The value must not be blank.

## Sizing

- **L2 memory** is about `maximumSize` × average value size, per cache and per instance. The default `maximumSize` is 10,000.
- **Redis memory** holds one copy of each live key. Negative entries are small and expire after `missingTtl`.
- **Redis Pub/Sub** uses one channel per cache, named after the cache. Every write or evict publishes one message, and every instance receives it.

## Troubleshooting

**An instance keeps serving an old value.**
1. Check that the writer evicts *after* the database commit. Evicting before the commit lets a concurrent read reload the old row.
2. Check that every write path evicts, including batch jobs and other services that write the same data.
3. Check `GET /actuator/cocache/{name}`: every instance must use the same cache name. The name is the channel.
4. The remaining source of staleness is the cache-aside race described in [Consistency](../architecture/consistency.md#what-is-not-guaranteed). `ttl` bounds it.

**A newly created record is reported as missing.** A lookup before the record existed cached a negative entry. It lasts up to `missingTtl` seconds. Evict the key after creating the record, or lower `missingTtl`.

**Startup fails with several `CacheSource` candidates.** Two beans match the cache's `CacheSource<K, V>` type. Name the right one `{cacheName}.CacheSource`, or mark it `@Primary`.
