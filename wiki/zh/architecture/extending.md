---
title: 扩展 CoCache
description: 自定义 L2/L1 存储、淘汰事件通道、key 转换器与数据源的 SPI 约定，以及验证它们的 TCK 套件。
---

# 扩展 CoCache

每个扩展点都是 `cocache-api` 中的接口。实现接口，用 `cocache-test` 中对应的测试套件验证，再注册为[按缓存定制的 bean](../guide/configuration.md#per-cache-components) 或全局 bean。

```kotlin
testImplementation("me.ahoo.cocache:cocache-test")
```

| 你实现的 | 约定 | 验证 |
|----------|------|------|
| `ClientSideCache<V>`（L2） | [存储](#stores) | `ClientSideCacheSpec` |
| `DistributedCache<V>`（L1） | [存储](#stores) | `DistributedCacheSpec` |
| `CacheEvictedEventBus` | [失效通道](#invalidation-channel) | `CacheEvictedEventBusSpec`、`MultipleInstanceSyncSpec` |
| `Cache<K, V>`（完整缓存） | `Cache` API | `CacheSpec` |
| `KeyConverter<K>`、`CacheSource<K, V>`、`KeyFilter`、`JoinKeyExtractor` | 单方法函数式接口 | 自行测试 |

`DefaultCoherentCacheSpec` 针对 L2、L1 与通道的组合运行完整的一致性套件。替换多个组件时使用它。

## 存储 {#stores}

```kotlin
interface CacheStore<V> {
    fun getCache(key: String): CacheValue<V>?
    fun setCache(key: String, value: CacheValue<V>)
    fun evict(key: String)
}
interface ClientSideCache<V> : CacheStore<V> { val size: Long; fun clear() }
interface DistributedCache<V> : CacheStore<V>, AutoCloseable
```

所有存储都必须：

- **只存储。** 按原样保存 `CacheValue`；不要应用 TTL 默认值、抖动或负缓存策略，编排层已经做过了。
- `setCache` 写入已过期的值时等同于 `evict`。
- `getCache` 可以返回已过期的条目，由调用方检查 `isExpired`。

**L2** 存储还必须**有界**。避免每次读取都写元数据的条目级过期回调（如 Caffeine 的 `Expiry`），否则热点 key 无法随线程扩展。

**L1** 存储还必须：

- **任何未命中都返回 `null`**：不存在、读取途中被删除、无法解码。只有当存储中确有你写入的负缓存记录时，才返回 `MissingValue`。把未命中当作“不存在”会隐藏真实存在的数据。
- 原子地（最好一次往返）读取值与剩余 TTL，并根据存储自身的过期时间重建 `ttlAt`。
- 写入时把剩余 TTL 向上取整到至少一秒；`TtlAt.FOREVER` 的条目不设过期。
- 读取时绝不删除：删除可能误删其它实例刚写入的有效值。

要支持新的 Redis 数据结构，继承 `AbstractCodecExecutor` 并用 `RedisDistributedCache` 包装。基类提供原子的 Lua 读取、未命中语义与 TTL 处理。

根据服务端过期时间重建 `ttlAt` 的存储可能有最多一秒的偏差。`CacheStoreSpec` 的 TTL 用例是 `open` 的，可以覆盖为 `isCloseTo(expected, Offset.offset(1))`。

## 失效通道 {#invalidation-channel}

```kotlin
interface CacheEvictedEventBus {
    fun publish(event: CacheEvictedEvent)          // CacheEvictedEvent(cacheName, key, publisherId)
    fun register(subscriber: CacheEvictedSubscriber)
    fun unregister(subscriber: CacheEvictedSubscriber)
}
interface CacheEvictedSubscriber : NamedCache {
    fun onEvicted(cacheEvictedEvent: CacheEvictedEvent)
    fun onReset()
}
```

- **`publish` 尽力而为。** 传输失败时不得抛出异常：记录日志后返回。
- **按 `cacheName` 投递。** 订阅者通过比较 `publisherId` 与自身 `clientId` 忽略自己的事件，通道无需过滤。
- **`onReset` 是强制的。** 每当订阅建立或重新建立时都要调用：`register` 时、每次重连后、以及任何可能跳过消息的消费组再平衡后。订阅者会清空 L2。没有重置，一条丢失的事件就可能让 L2 一直陈旧到 TTL 过期。

## 数据源与 key

- **`CacheSource<K, V>`** 返回带绝对 `ttlAt` 的 `CacheValue`，或返回 `null` 表示“不存在”。CoCache 把 `null` 存为负缓存，保留 `missingTtl` 秒。在每个实例上，同一 key 同时最多只有一次数据源调用。数据源不得读取自身缓存中的同一 key，否则调用会快速失败。
- **`KeyConverter<K>`** 必须为每个 key 生成不同的字符串，并包含缓存专属前缀，因为所有缓存共享 L1。
- **`KeyFilter.notExist(key)`** 只能对确定不存在的 key 返回 `true`。对布隆过滤器而言，意味着它必须由全部已存在的 key 构建。
