# Custom Cache Implementations

This guide covers creating custom implementations of CoCache's core interfaces: `ClientSideCache`, `DistributedCache`, and `CacheEvictedEventBus`.

## Custom ClientSideCache

`ClientSideCache<V>` is the L2 local in-memory cache. Implement this if you need a local cache backend not covered by the built-in implementations (Map, Guava, Caffeine).

### Interface

```kotlin
interface ClientSideCache<V> : Cache<String, V> {
    val size: Long
    fun clear()
}
```

### Implementation Template

```kotlin
class RedissonClientSideCache<V>(
    private val cache: RMapCache<String, CacheValue<V>>
) : ClientSideCache<V> {

    override val size: Long
        get() = cache.size.toLong()

    override fun get(key: String): V? {
        return getCache(key)?.value
    }

    override fun getCache(key: String): CacheValue<V>? {
        val cacheValue = cache[key] ?: return null
        if (cacheValue.isExpired()) {
            cache.remove(key)
            return null
        }
        return cacheValue
    }

    override fun getTtlAt(key: String): Long? {
        return getCache(key)?.ttlAt
    }

    override fun set(key: String, value: V) {
        setCache(key, DefaultCacheValue(value))
    }

    override fun set(key: String, ttlAt: Long, value: V) {
        setCache(key, DefaultCacheValue(value, ttlAt))
    }

    override fun setCache(key: String, cacheValue: CacheValue<V>) {
        if (cacheValue.isMissingGuard) {
            cache[key] = cacheValue
        } else {
            val ttl = cacheValue.ttlAt - System.currentTimeMillis() / 1000
            if (ttl > 0) {
                cache.put(key, cacheValue, ttl, TimeUnit.SECONDS)
            }
        }
    }

    override fun evict(key: String) {
        cache.remove(key)
    }

    override fun clear() {
        cache.clear()
    }

    private fun CacheValue<V>.isExpired(): Boolean {
        return ttlAt < System.currentTimeMillis() / 1000
    }
}
```

### Testing

Extend `ClientSideCacheSpec`:

```kotlin
class RedissonClientSideCacheTest : ClientSideCacheSpec<String>() {

    override fun createCache(): ClientSideCache<String> {
        return RedissonClientSideCache(Redisson.create().getMapCache("test"))
    }

    override fun createCacheEntry(): CacheValue<String> {
        return DefaultCacheValue("test_value")
    }
}
```

## Custom DistributedCache

`DistributedCache<V>` is the L1 shared cache layer. Implement this if you need a distributed cache backend other than Redis.

### Interface

```kotlin
interface DistributedCache<V> : ComputedCache<String, V>, AutoCloseable {
    // Inherited from ComputedCache<String, V>:
    // fun get(key: String): V?
    // fun getCache(key: String): CacheValue<V>?
    // fun set(key: String, value: V)
    // fun set(key: String, ttlAt: Long, value: V)
    // fun setCache(key: String, cacheValue: CacheValue<V>)
    // fun evict(key: String)
}
```

### Implementation Template

```kotlin
class MemcachedDistributedCache<V>(
    private val cacheName: String,
    private val client: MemcachedClient,
    private val codec: Codec<V>
) : DistributedCache<V> {

    private val keyPrefix = "$cacheName:"

    override fun get(key: String): V? {
        return getCache(key)?.value
    }

    override fun getCache(key: String): CacheValue<V>? {
        val raw = client.get("$keyPrefix$key") as? ByteArray ?: return null
        return codec.decode(raw)
    }

    override fun set(key: String, value: V) {
        setCache(key, DefaultCacheValue(value))
    }

    override fun set(key: String, ttlAt: Long, value: V) {
        setCache(key, DefaultCacheValue(value, ttlAt))
    }

    override fun setCache(key: String, cacheValue: CacheValue<V>) {
        val ttl = (cacheValue.ttlAt - System.currentTimeMillis() / 1000).toInt()
        if (ttl > 0) {
            client.set("$keyPrefix$key", ttl, codec.encode(cacheValue))
        }
    }

    override fun evict(key: String) {
        client.delete("$keyPrefix$key")
    }

    override fun close() {
        client.shutdown()
    }
}
```

### Testing

Extend `DistributedCacheSpec`:

```kotlin
class MemcachedDistributedCacheTest : DistributedCacheSpec<String>() {

    override fun createCache(): DistributedCache<String> {
        return MemcachedDistributedCache("test", memcachedClient, StringCodec())
    }

    override fun createCacheEntry(): CacheValue<String> {
        return DefaultCacheValue("test_value")
    }
}
```

## Custom CacheEvictedEventBus

`CacheEvictedEventBus` enables distributed cache invalidation. Implement this if you need a message broker other than Redis (e.g., Kafka, RabbitMQ, NATS).

### Interface

```kotlin
interface CacheEvictedEventBus {
    fun publish(event: CacheEvictedEvent)
    fun register(subscriber: CacheEvictedSubscriber)
    fun unregister(subscriber: CacheEvictedSubscriber)
}
```

### CacheEvictedEvent Structure

```kotlin
data class CacheEvictedEvent(
    val cacheName: String,    // which cache was evicted
    val key: String,          // the evicted key
    val clientId: String      // which instance performed the eviction
)
```

### Implementation Template

```kotlin
class KafkaCacheEvictedEventBus(
    private val producer: KafkaProducer<String, CacheEvictedEvent>,
    private val consumer: KafkaConsumer<String, CacheEvictedEvent>,
    private val topic: String
) : CacheEvictedEventBus {

    private val subscribers = ConcurrentHashMap.newKeySet<CacheEvictedSubscriber>()

    init {
        // Start consumer thread
        thread(isDaemon = true) {
            consumer.subscribe(listOf(topic))
            while (true) {
                val records = consumer.poll(Duration.ofMillis(100))
                records.forEach { record ->
                    val event = record.value()
                    subscribers.forEach { subscriber ->
                        subscriber.onEvicted(event)
                    }
                }
            }
        }
    }

    override fun publish(event: CacheEvictedEvent) {
        producer.send(ProducerRecord(topic, event.cacheName, event))
    }

    override fun register(subscriber: CacheEvictedSubscriber) {
        subscribers.add(subscriber)
    }

    override fun unregister(subscriber: CacheEvictedSubscriber) {
        subscribers.remove(subscriber)
    }
}
```

### Testing

Extend `CacheEvictedEventBusSpec`:

```kotlin
class KafkaCacheEvictedEventBusTest : CacheEvictedEventBusSpec() {

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus {
        return KafkaCacheEvictedEventBus(testProducer, testConsumer, "test-topic")
    }
}
```

## Custom KeyConverter

`KeyConverter<K>` converts cache keys to strings for the distributed cache.

### Interface

```kotlin
interface KeyConverter<K> {
    fun asKey(key: K): String
}
```

### Built-in Implementations

- `ToStringKeyConverter(prefix)` - `prefix + key.toString()`
- `ExpKeyConverter(expression, parserContext)` - SpEL-based key conversion

### Custom Implementation

```kotlin
class CompositeKeyConverter<K>(
    private val prefix: String,
    private val separator: String = ":"
) : KeyConverter<K> {

    override fun asKey(key: K): String {
        return when (key) {
            is Pair<*, *> -> "$prefix${key.first}$separator${key.second}"
            else -> "$prefix$key"
        }
    }
}
```

## Custom CacheSource

`CacheSource<K, V>` loads data from the underlying data store.

### Interface

```kotlin
interface CacheSource<K, V> {
    fun loadCacheValue(key: K): CacheValue<V>?

    companion object {
        fun <K, V> noOp(): CacheSource<K, V> = NoOpCacheSource
    }
}
```

### Implementation Patterns

```kotlin
// Pattern 1: Using CacheSource functional interface
@Bean("UserCache.CacheSource")
fun userCacheSource(userRepository: UserRepository): CacheSource<String, User> {
    return CacheSource { key ->
        userRepository.findById(key).orElse(null)?.let {
            DefaultCacheValue(it, ttl = 300)  // 5 min TTL
        }
    }
}

// Pattern 2: Class-based for complex logic
class UserServiceCacheSource(
    private val userService: UserService,
    private val objectMapper: ObjectMapper
) : CacheSource<String, User> {

    override fun loadCacheValue(key: String): CacheValue<User>? {
        return try {
            val user = userService.findById(key)
            user?.let { DefaultCacheValue(it) }
        } catch (e: Exception) {
            // Return missing guard to prevent cache penetration
            DefaultCacheValue.missingGuard()
        }
    }
}
```

## Registering Custom Implementations

### As a Named Bean (per-cache)

```kotlin
@Bean("UserCache.ClientSideCache")
fun userClientSideCache(): ClientSideCache<User> {
    return MyCustomClientSideCache()
}
```

### As a Type Bean (global override)

```kotlin
@Bean
fun distributedCache(): DistributedCache<*> {
    return MyCustomDistributedCache()
}
```

### Via Auto-Configuration

If you're building a reusable library, create a Spring Boot auto-configuration:

```kotlin
@AutoConfiguration
@ConditionalOnClass(MyCustomDistributedCache::class)
class MyCustomCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DistributedCache::class)
    fun distributedCache(): DistributedCache<*> {
        return MyCustomDistributedCache()
    }
}
```

Register in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.example.MyCustomCacheAutoConfiguration
```
