---
title: Configuration
description: Every CoCache setting — @CoCache and @CaffeineCache, cocache.* properties and their precedence, Redis connection and channel mode, codecs, per-entry TTLs, Set caches, and building caches in code.
---

# Configuration

A cache's settings come from four layers. Each later layer overrides the earlier ones, field by field:

1. **Built-in defaults** (`CachePolicy`).
2. **The declaration**: `@CoCache` / `@CaffeineCache` on an interface, or the `CacheSpec` you build.
3. **`CacheSpecCustomizer` beans**, applied in Spring order.
4. **Properties**: first `cocache.defaults.*`, then `cocache.caches.[name].*`.

Properties come last so operators can tune a cache without recompiling. Note that `cocache.defaults.*` therefore overrides what an annotation says.

## `@CoCache`

Durations are in **seconds**. Every attribute defaults to `CoCache.UNSET`, which means "use the next layer down".

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `name` | interface simple name | Cache name, and the bean name |
| `namespace` | the cache name | Part of every Redis key and of the invalidation channel |
| `keyPrefix` | `cocache:` | Start of every Redis key. Redis keys are `{keyPrefix}{namespace}:{key}`. |
| `keyExpression` | `""` (uses `key.toString()`) | SpEL template evaluated with the key as root object, for example `#{tenantId}:#{id}` |
| `ttl` | `3600` | How long an L1 value lives. `CoCache.FOREVER` means it never expires. |
| `ttlJitter` | `60` | Random jitter: each value lives `ttl ± ttlJitter`. `0` turns it off. The effective jitter never exceeds `ttl / 2`. |
| `absentTtl` | `60` | How long a negative entry ("not found") lives. No jitter. |
| `localMaxAge` | `300` | Maximum age of an L2 copy. It is always finite: this is the staleness bound when invalidation events are lost. |
| `refreshWindow` | off | When a value has at most this long left, one node in the cluster reloads it in the background. `0` means off. |
| `loader` | `""` | Name of the `CacheLoader` bean. Empty: the unique bean of type `CacheLoader<K, V>`, else no loader. Several candidates fail startup. |
| `codec` | `""` | Name of the `Codec` bean. Empty: the unique bean of type `Codec<V>`, else Jackson for the value type. Several candidates fail startup. |

`CoCache.FOREVER` is accepted only by `ttl` (never expires) and by `@CaffeineCache.expireAfterAccess` (no idle eviction). Any other attribute set to `FOREVER`, or set to `0` (except `ttlJitter` and `refreshWindow`), fails startup.

The interface must extend `Cache<K, V>` or `SetCache<K, E>` with concrete type arguments. Annotations must sit directly on the interface: meta-annotations and inherited annotations are not read. Default methods on the interface run as written, so you can add helpers. An abstract method that doesn't match a `Cache` (or `SetCache`) method fails startup.

## `@CaffeineCache`

Sizes the L2. L2 is always Caffeine and always bounded.

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `maximumSize` | `10000` | Maximum entries per instance |
| `initialCapacity` | unset | Initial hash-table size |
| `expireAfterAccess` | off | Evict entries idle for this many seconds |

You don't need `expireAfterAccess` for correctness. Every entry already expires after `localMaxAge` and at its L1 expiry. Idle eviction only frees memory sooner, and it writes the access time on every read, which costs throughput on very hot keys.

## Properties {#properties}

| Property | Default | Meaning |
|----------|---------|---------|
| `cocache.enabled` | `true` | `false` turns off all CoCache auto-configuration, including the actuator endpoint and metrics |
| `cocache.defaults.*` | | Policy for every cache (table below) |
| `cocache.caches.[name].*` | | Policy for one cache, by cache name (case-insensitive). A name that no cache declares fails startup (usually a typo). |
| `cocache.redis.*` | | The Redis backend ([below](#redis-connection)) |

### Policy properties

These keys work under both `cocache.defaults.` and `cocache.caches.[name].`. Unset keys keep the value from the layer below.

| Key | Built-in default | Meaning |
|-----|------------------|---------|
| `ttl` | `1h` | L1 value lifetime |
| `ttl-forever` | `false` | `true`: values never expire (overrides `ttl`) |
| `ttl-jitter` | `1m` | Random jitter of the value lifetime (effective jitter ≤ `ttl / 2`) |
| `absent-ttl` | `1m` | Negative-entry lifetime |
| `local-max-age` | `5m` | Maximum age of an L2 copy |
| `degraded-local-max-age` | `10s` | Maximum age of an L2 copy that could not be written to L1 (Redis failure, lease-wait timeout, rejected fill) |
| `refresh-window` | off | Refresh-ahead window |
| `lease-ttl` | `10s` | How long an L1 lease lives if its holder never fills it (a crashed node). Keep it above your loader's p99. |
| `lease-wait` | `200ms` | How long a miss waits for another node's lease before loading itself |
| `load-max-concurrent` | `0` (unlimited) | Maximum concurrent source loads per cache per instance |
| `load-acquire-timeout` | `1s` | How long a load waits for a permit before failing with `SourceLoadRejectedException` |
| `local-maximum-size` | `10000` | L2 maximum entries |
| `invalidate-failure` | `retry` | `retry`: a failed L1 invalidation goes to a bounded background retry queue. `throw`: it throws `CacheWriteException`. |
| `read-failure` | `degrade` | `degrade`: a failed L1 read loads from the source. `throw`: it throws `CacheReadException`. |

A bare number is **seconds** (`ttl: 120`). Spring duration strings also work (`120s`, `2m`, `PT2M`).

```yaml
cocache:
  defaults:
    local-max-age: 60s
  caches:
    "[UserCache]":
      ttl: 10m
      load-max-concurrent: 32
      read-failure: throw
```

### Validation

A policy that breaks one of these rules fails startup:

- Every duration is positive and at most 100 years. Only `ttl-jitter`, `lease-wait`, and `load-acquire-timeout` may be zero.
- `refresh-window` is less than `ttl − ttl-jitter` (with the effective jitter) and less than `local-max-age`.
- Removed 5.x keys (`cocache.redis.strict-failure`, `cocache.redis.eviction-channel`, `cocache.redis.missing-guard-sentinel`, `cocache.load.*`, `cocache.metrics.client-side-hits`, `cocache.next.*`) fail startup with a message naming the replacement. See [Migrating from 5.x](./migration-6.md#properties).

## Choosing values {#choosing-values}

- **`ttl` bounds L1 staleness from changes CoCache never sees**: writes that bypass the cache, writes lost in a Redis failover, or a loader that read an old database replica. Races inside the protocol don't need it, because the lease fence already prevents them. `ttl-forever` is safe when every writer invalidates.
- **`local-max-age` bounds L2 staleness when invalidation events are lost**: a half-open connection, a network partition, or a non-atomic publish that failed. A shorter value costs one more Redis round trip per key per expiry and nothing on the hit path.
- **`absent-ttl` is how long a newly created row can stay invisible** after a lookup cached its absence. Keep it short, or invalidate the key after creating the row.
- **`refresh-window` removes the latency spike when a hot key expires.** A read inside the window returns the current value and triggers one background reload. Only the node that wins a refresh token in Redis reloads from the source: one reload per key for the whole cluster. Expired values are never served. Negative entries are not refreshed.
- **`ttl-jitter` spreads expiries** so keys loaded together don't all expire in the same second.
- **`lease-wait`**: concurrent misses on different nodes share one load only if the loader finishes within `lease-wait`. Raise it for slow loaders, up to the latency your callers accept.

## Redis connection {#redis-connection}

The backend is built from Spring Boot's `spring.data.redis.*` connection details.

| Topology | Support |
|----------|---------|
| Standalone | Yes |
| Sentinel | Yes |
| Cluster | Yes. Scripts are routed by key, and the client refreshes the topology adaptively. |
| Static master-replica | No. Startup fails. |

TLS comes from the Spring Boot SSL bundle (`spring.data.redis.ssl.bundle`): key and trust managers, enabled protocols, and cipher suites. A `rediss://` URL enables TLS with the JDK defaults. Sentinel nodes authenticate with `spring.data.redis.sentinel.username` / `password`, data nodes with `spring.data.redis.username` / `password`. Only Lettuce is supported. CoCache always reads and writes the master, because its read script also writes leases and refresh tokens, and its connections reject commands while disconnected instead of replaying them later.

| Property | Default | Meaning |
|----------|---------|---------|
| `cocache.redis.channel-mode` | `pubsub` | `pubsub`: `PUBLISH`/`SUBSCRIBE`, atomic with the L1 change. `sharded`: `SPUBLISH`/`SSUBSCRIBE` (Redis 7+), published right after the L1 change. See [Consistency](../architecture/consistency.md#invalidation-channel). **Every instance must use the same mode.** |
| `cocache.redis.command-timeout` | `2s` | Maximum wait for one command or script |
| `cocache.redis.heartbeat` | `5s` | `PING` interval on the subscription connection. A failed or slow ping forces a reconnect, which clears L2. |
| `cocache.redis.invalidate-batch-size` | `512` | Maximum keys per invalidation script, so one call doesn't block a shard for long |
| `cocache.redis.client-name` | unset | `CLIENT SETNAME` for CoCache's connections, to find them in `CLIENT LIST` |

A bare number here is **milliseconds**, as for `spring.data.redis.timeout`, so write a unit (`2s`, `500ms`).

To use another backend, or a Lettuce client you configure yourself, declare your own `CoherenceBackend` bean. To change the runtime (clock, refresh executor, invalidation retry policy), declare your own `CacheRuntime` bean. Both auto-configured beans are `@ConditionalOnMissingBean`.

## Codecs {#codecs}

A `Codec<V>` turns a value into the bytes stored in Redis. The starter picks, in order: `@CoCache(codec = "beanName")`, the unique `Codec<V>` bean, then `JacksonCodec` for the value type with your application's `ObjectMapper` (Jackson 3, `tools.jackson`). A spec bean must set its codec explicitly.

| Codec | Module |
|-------|--------|
| `JacksonCodec` / `jacksonCodec<V>(mapper)` | `cocache-codec-jackson` |
| `StringCodec` (UTF-8) | `cocache-core` |

If a payload in Redis can't be decoded, CoCache treats it as a miss, reclaims the key only if it still holds the bytes it read, and reloads. It never deletes on the read path.

Redis values carry a 36-byte binary header in front of the codec's bytes, so `redis-cli GET` no longer shows plain JSON. Other services must not read CoCache keys directly.

## Per-entry TTL {#per-entry-ttl}

To decide the lifetime from the key or the value, set `ttlOf` on the spec (directly, or in a `CacheSpecCustomizer`):

```kotlin
cacheSpec<String, Session>("session") {
    codec = jacksonCodec(mapper)
    loader = CacheLoader { id -> sessionRepository.find(id) }
    ttlOf = TtlFunction { _, session -> Duration.between(Instant.now(), session.expiresAt) }
}
```

A non-null result overrides the policy `ttl` and gets no jitter. `null` falls back to the policy. Negative entries always use `absentTtl`. The `ttl` property doesn't affect `ttlOf`.

## Customizing a declaration {#customizing}

`CacheSpecCustomizer` replaces the bean-name conventions of 5.x (`{name}.ClientSideCache`, …). It sees the spec builder before the properties are applied:

```kotlin
@Bean
fun userCacheCustomizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
    override fun supports(cacheName: String) = cacheName == "UserCache"

    override fun customize(spec: CacheSpec.Builder<*, *>) {
        spec.policy = spec.policy.toBuilder().absentTtl(Duration.ofSeconds(30)).build()
    }
}
```

Use it for anything the annotations don't cover: lease settings, `degradedLocalMaxAge`, `ttlOf`, a `KeyFilter`, or a custom codec.

## Set caches: atomic element updates {#set-elements}

`SetCache<K, E>` is for sets whose **only** copy is in Redis, such as an index or a membership list. Elements are added and removed atomically in Redis, so two instances updating one set at the same time never lose an update.

```kotlin
@CoCache
interface PolicyIndexCache : SetCache<String, String>

policyIndexCache.add(key, listOf("p1"))
policyIndexCache.remove(key, listOf("p1"))
policyIndexCache.clear(key)
val members: Set<String> = policyIndexCache[key]   // empty set when absent
```

- A `SetCache` has no loader (`@CoCache(loader = …)` on a `SetCache` fails startup). Redis is the authoritative copy, so its default `ttl` is forever.
- `add` creates the set if the key is absent. `remove` deletes the key when the set becomes empty. Both reset the expiry and notify peers.
- `invalidate` throws `UnsupportedOperationException`, because it would destroy the only copy. Use `clear(key)`.
- A Redis failure throws `CacheWriteException`. The calls are idempotent, so you can retry.
- The set is stored as a Redis Hash. Compared with a native Set, each non-integer member costs about 16 extra bytes, and integer members lose the compact intset encoding.
- Redis must not use an `allkeys-*` `maxmemory-policy`, which could evict the only copy. Durability equals Redis durability: an asynchronous-replication failover can lose recent writes.

If the set's data lives in your database, don't use `SetCache`. Declare a plain `Cache<K, Set<E>>`, update the database, and `invalidate`.

## Building a cache in code {#building-a-cache-in-code}

Without Spring, create a `CacheRuntime` (one per process) and create caches from specs:

```kotlin
val backend = RedisBackend.standalone(RedisURI.create("redis://localhost:6379"))
val runtime = CacheRuntime.builder().backend(backend).build()

val users: Cache<String, User> = runtime.create(
    cacheSpec<String, User>("user") {
        codec = jacksonCodec(mapper)
        loader = CacheLoader { id -> userRepository.findById(id) }
        keyFilter = KeyFilter { storageKey -> !bloomFilter.mightContain(storageKey) }
        policy {
            ttl = Duration.ofMinutes(2)
            local = LocalPolicy(maximumSize = 1_000_000)
        }
    }
)

// on shutdown
runtime.close()
backend.close()
```

- `RedisBackend.cluster(uris)` connects to a cluster. `RedisBackend.of(client)` uses a Lettuce client you manage.
- Each runtime has one node identity and one subscription connection. Cache names and namespaces must be unique within a runtime.
- `runtime.close()` closes the caches in reverse order. The backend is closed by whoever created it.
- A `KeyFilter` is consulted after an L2 miss. A key it rejects returns "not found" without touching Redis or the source. It receives the full storage key (`{keyPrefix}{namespace}:{key}`), and must reject only keys that certainly don't exist.
- In a Boot application, the same spec as a `@Bean` method is registered automatically ([Quick Start](./quick-start.md#spec-bean)).
