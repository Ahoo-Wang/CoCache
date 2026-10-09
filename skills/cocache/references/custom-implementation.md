# Custom Cache Implementations

## Contents

- [Custom ClientSideCache (L2)](#custom-clientsidecache-l2)
- [Custom DistributedCache (L1)](#custom-distributedcache-l1)
- [Custom CacheEvictedEventBus](#custom-cacheevictedeventbus)
- [Custom KeyConverter](#custom-keyconverter)
- [Custom CacheSource](#custom-cachesource)
- [Registering Custom Implementations](#registering-custom-implementations)

All SPI interfaces live in `cocache-api`. An implementation only needs `cocache-api`, plus `cocache-test` to run the TCK specs.

## The Store Contract

L2 and L1 are both `CacheStore`s. A store **only stores**: TTL, jitter and negative-cache policy are decided above it, and they reach the store already encoded in `CacheValue.ttlAt`.

```kotlin
interface CacheStore<V> {
    fun getCache(key: String): CacheValue<V>?      // may return expired entries; callers check isExpired
    fun setCache(key: String, value: CacheValue<V>) // an expired value means evict
    fun evict(key: String)
}
```

`CacheValue<V>` is sealed:

```kotlin
when (value) {
    is PresentValue -> /* value.value, value.ttlAt */
    is MissingValue -> /* negative entry, value.ttlAt */
}
```

Store `MissingValue` as an explicit negative record; never infer one from absence.

## Custom ClientSideCache (L2)

```kotlin
interface ClientSideCache<V> : CacheStore<V> {
    val size: Long
    fun clear()
}
```

```kotlin
class Cache2kClientSideCache<V>(private val cache: org.cache2k.Cache<String, CacheValue<V>>) : ClientSideCache<V> {
    override fun getCache(key: String): CacheValue<V>? = cache.peek(key)

    override fun setCache(key: String, value: CacheValue<V>) {
        if (value.isExpired) {
            evict(key)
            return
        }
        cache.put(key, value)
    }

    override fun evict(key: String) = cache.remove(key)
    override val size: Long get() = cache.asMap().size.toLong()
    override fun clear() = cache.clear()
}
```

Keep L2 **bounded**. Avoid per-entry expiry hooks that write metadata on every read (e.g. a Caffeine `Expiry`): a hot key then stops scaling across threads. The coherent cache checks `isExpired` on read and evicts expired entries itself.

Test it:

```kotlin
class Cache2kClientSideCacheTest : ClientSideCacheSpec<String>() {
    override fun createCacheStore(): ClientSideCache<String> = Cache2kClientSideCache(newCache2k())
    override fun createCacheEntry(): Pair<String, String> = UUID.randomUUID().toString() to "v"
}
```

## Custom DistributedCache (L1)

```kotlin
interface DistributedCache<V> : CacheStore<V>, AutoCloseable
```

Contract:

- `getCache` returns `null` for a **miss** (absent key, key deleted mid-read, corrupted payload). The coherent cache then reloads from the source.
- Return `MissingValue(ttlAt)` only when the store holds a negative record you wrote earlier.
- Read the value and its remaining TTL in one round trip (or atomically), and derive `ttlAt` from the store's own expiry.
- On write, clamp the remaining TTL to at least one second; write `FOREVER` entries without expiry.

```kotlin
class MemcachedDistributedCache<V>(
    private val client: MemcachedClient,
    private val codec: Codec<V>,
) : DistributedCache<V> {
    override fun getCache(key: String): CacheValue<V>? {
        val record = client.gets(key) ?: return null            // miss
        return record.decode(codec)                              // PresentValue or MissingValue with stored ttlAt
    }

    override fun setCache(key: String, value: CacheValue<V>) {
        if (value.isExpired) {
            evict(key)
            return
        }
        val ttlSeconds = if (value.isForever) 0 else value.expiredDuration.seconds.coerceAtLeast(1).toInt()
        client.set(key, ttlSeconds, codec.encode(value))         // encode MissingValue as your sentinel
    }

    override fun evict(key: String) {
        client.delete(key)
    }

    override fun close() = client.shutdown()
}
```

For Redis structures, extend `AbstractCodecExecutor` (or `StringCodecExecutor` / `HashCodecExecutor`) and wrap it in `RedisDistributedCache`. You provide `readScript` (built with `readScript("GET" | "HGETALL" | "SMEMBERS" | ...)`, an atomic one-round-trip Lua read returning `{ttl, ...raw}`), `toRaw`, `isMissingGuard`, `decode`, `encode`, `encodeMissingGuard`, and `writeRaw`. The base class supplies the atomic read, the miss semantics, self-healing of corrupted payloads, and TTL clamping.

Test it (stores that rebuild `ttlAt` from server expiry should override the `open` TTL tests with a ±1 s tolerance):

```kotlin
class MemcachedDistributedCacheTest : DistributedCacheSpec<String>() {
    override fun createCacheStore(): DistributedCache<String> = MemcachedDistributedCache(client, StringCodec())
    override fun createCacheEntry(): Pair<String, String> = UUID.randomUUID().toString() to "v"
}
```

## Custom CacheEvictedEventBus

```kotlin
interface CacheEvictedEventBus {
    fun publish(event: CacheEvictedEvent)            // best effort; must not throw on transport failure
    fun register(subscriber: CacheEvictedSubscriber)
    fun unregister(subscriber: CacheEvictedSubscriber)
}

interface CacheEvictedSubscriber : NamedCache {
    fun onEvicted(cacheEvictedEvent: CacheEvictedEvent)
    fun onReset()
}
```

**The reset contract is mandatory.** Call `subscriber.onReset()` whenever its subscription is (re)established: on registration, and after every reconnect or consumer-group rebalance that may have skipped messages. The subscriber clears its L2, and that bounds staleness after lost events. Route events by `cacheName`; subscribers ignore events whose `publisherId` is their own `clientId`.

```kotlin
class KafkaCacheEvictedEventBus(
    private val producer: KafkaProducer<String, CacheEvictedEvent>,
    private val consumerFactory: () -> KafkaConsumer<String, CacheEvictedEvent>,
    private val topic: String,
) : CacheEvictedEventBus {
    private val subscribers = ConcurrentHashMap<String, MutableSet<CacheEvictedSubscriber>>()

    init {
        thread(isDaemon = true, name = "cocache-kafka-evicted") {
            consumerFactory().use { consumer ->
                consumer.subscribe(listOf(topic), object : ConsumerRebalanceListener {
                    override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) = Unit
                    override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
                        subscribers.values.flatten().forEach { it.onReset() }   // events may have been skipped
                    }
                })
                while (true) {
                    consumer.poll(Duration.ofMillis(100)).forEach { record ->
                        val event = record.value()
                        subscribers[event.cacheName]?.forEach { it.onEvicted(event) }
                    }
                }
            }
        }
    }

    override fun publish(event: CacheEvictedEvent) {
        runCatching { producer.send(ProducerRecord(topic, event.cacheName, event)) }
    }

    override fun register(subscriber: CacheEvictedSubscriber) {
        if (subscribers.computeIfAbsent(subscriber.cacheName) { CopyOnWriteArraySet() }.add(subscriber)) {
            subscriber.onReset()
        }
    }

    override fun unregister(subscriber: CacheEvictedSubscriber) {
        subscribers[subscriber.cacheName]?.remove(subscriber)
    }
}
```

Test it with `CacheEvictedEventBusSpec` (it asserts that registration triggers `onReset`).

## Custom KeyConverter

```kotlin
fun interface KeyConverter<K> {
    fun toStringKey(sourceKey: K): String
}
```

Built-ins: `ToStringKeyConverter(prefix)` and `ExpKeyConverter(prefix, "#{...}")` (compiled SpEL template). Include a cache-specific prefix so caches never share keys.

```kotlin
class TenantKeyConverter(private val prefix: String) : KeyConverter<TenantKey> {
    override fun toStringKey(sourceKey: TenantKey): String = "$prefix${sourceKey.tenantId}:${sourceKey.id}"
}
```

## Custom CacheSource

```kotlin
fun interface CacheSource<K, V> {
    fun loadCacheValue(key: K): CacheValue<V>?
}
```

| Returns | CoCache behavior |
|---------|------------------|
| `CacheValue.of(value, TtlAt.at(seconds))` | Positive entry with its own TTL |
| `CacheValue.forever(value)` | Positive entry without expiry (only if writers always evict) |
| `null` | Negative entry for the cache's `missingTtl` (cache-penetration protection); `get(key)` returns `null` |
| `CacheValue.missing(TtlAt.at(seconds))` | Negative entry with a custom window |
| throws | The exception reaches every caller waiting on this key's load (unwrapped); nothing is cached |

```kotlin
@Bean("UserCache.CacheSource")
fun userCacheSource(userRepository: UserRepository): CacheSource<String, User> = CacheSource { id ->
    userRepository.findById(id).orElse(null)?.let { CacheValue.of(it, TtlAt.at(300)) }
}
```

Concurrent misses of the same key are coalesced, so the source is called once per key at a time per instance. Do not read the same key from the same cache inside `loadCacheValue`; that recursive load fails fast.

## Registering Custom Implementations

| Component | Bean name (per cache) | By type? |
|-----------|-----------------------|----------|
| `ClientSideCache<V>` | `{cacheName}.ClientSideCache` | No (stateful) |
| `DistributedCache<V>` | `{cacheName}.DistributedCache` | No (stateful) |
| `KeyConverter<K>` | `{cacheName}.KeyConverter` | No (prefix must be cache-specific) |
| `CacheSource<K, V>` | `{cacheName}.CacheSource` | Yes, if unique for `<K, V>` |
| `JoinKeyExtractor<V1, K2>` | `{cacheName}.JoinKeyExtractor` | Yes, if unique |
| `CacheEvictedEventBus` | any | Global bean replaces the Redis bus |

```kotlin
@Bean("UserCache.ClientSideCache")
fun userClientSideCache(): ClientSideCache<User> = CaffeineClientSideCache.build(maximumSize = 100_000)
```
