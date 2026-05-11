---
title: 高级工程师指南
description: CoCache 高级工程师入门指南，深入理解架构设计决策、扩展点和性能优化策略。
---

# 高级工程师指南

本指南面向高级工程师，深入介绍 CoCache 的架构设计决策、扩展点和性能优化策略。

## 架构设计决策

### 接口驱动设计

所有缓存层级通过接口定义，实现与契约分离：

```
cocache-api（接口） -> cocache-core（实现） -> cocache-spring（集成）
```

这使得：
- 可以独立替换任意层级的实现
- 测试时可以使用 Mock 实现
- 新增实现不需要修改现有代码

### 代理模式 vs 继承

CoCache 使用 **JDK 动态代理**而非继承来创建缓存实例：

```kotlin
// 开发者只需定义接口
@CoCache(keyPrefix = "user:", ttl = 120)
interface UserCache : Cache<String, User>

// 框架自动创建代理实例
// 代理内部委托给 CoherentCache
```

优势：
- 接口可以自由扩展业务方法
- 不侵入用户代码
- 支持 Kotlin 和 Java

### 事件驱动一致性

使用观察者模式而非轮询：

- `CacheEvictedEventBus` 解耦发布者和订阅者
- Redis Pub/Sub 实现跨实例事件传递
- 通过 `publisherId` 避免事件循环

### 逐键锁

使用 `ConcurrentHashMap<String, Any>` 而非全局锁：

```kotlin
private val keyLocks = ConcurrentHashMap<String, Any>()

private fun getLock(cacheKey: String): Any {
    return keyLocks.computeIfAbsent(cacheKey) { Any() }
}
```

优势：
- 不同键的访问互不阻塞
- 锁粒度最细
- 使用后自动清理

## 扩展点

### 1. 自定义 L2 缓存

实现 `ClientSideCache<V>` 接口：

```kotlin
class CustomClientSideCache<V> : ClientSideCache<V> {
    override fun getCache(key: String): CacheValue<V>? { ... }
    override fun setCache(key: String, value: CacheValue<V>) { ... }
    override fun evict(key: String) { ... }
    override val size: Long get() = ...
    override fun clear() { ... }
}
```

### 2. 自定义 L1 缓存

实现 `DistributedCache<V>` 接口：

```kotlin
class CustomDistributedCache<V> : DistributedCache<V> {
    override fun getCache(key: String): CacheValue<V>? { ... }
    override fun setCache(key: String, value: CacheValue<V>) { ... }
    override fun evict(key: String) { ... }
    override fun close() { ... }
}
```

### 3. 自定义事件总线

实现 `CacheEvictedEventBus` 接口：

```kotlin
class CustomEventBus : CacheEvictedEventBus {
    override fun publish(event: CacheEvictedEvent) { ... }
    override fun register(subscriber: CacheEvictedSubscriber) { ... }
    override fun unregister(subscriber: CacheEvictedSubscriber) { ... }
}
```

### 4. 自定义键过滤器

实现 `KeyFilter` 接口：

```kotlin
class CustomKeyFilter : KeyFilter {
    override fun notExist(key: String): Boolean { ... }
}
```

### 5. 自定义键转换器

实现 `KeyConverter<K>` 接口：

```kotlin
class CustomKeyConverter<K> : KeyConverter<K> {
    override fun toStringKey(sourceKey: K): String { ... }
}
```

## 性能优化策略

### TTL 抖动

通过 `ttlAmplitude` 参数防止缓存雪崩。建议设置为 TTL 的 5%~10%：

```kotlin
@CoCache(ttl = 300, ttlAmplitude = 15)  // TTL 285s ~ 315s
```

### MissingGuard

缓存空值防止缓存穿透。自动生效，无需额外配置。

### KeyFilter

对于可能大量查询不存在键的场景，使用布隆过滤器：

```kotlin
@Bean
fun keyFilter(): KeyFilter {
    val bloomFilter = BloomFilter.create(Funnels.stringFunnel(Charset.defaultCharset()), 1000_000, 0.01)
    // 从数据库加载所有键到布隆过滤器
    return BloomKeyFilter(bloomFilter)
}
```

### L2 缓存大小

根据可用内存和访问模式设置合理的 `maximumSize`：
- 太小：频繁 L1 查询
- 太大：内存浪费、GC 压力

## 设计模式应用

| 模式 | 应用位置 |
|------|----------|
| 代理模式 | `CoCacheInvocationHandler`、`CoCacheProxy` |
| 工厂模式 | `CacheProxyFactory`、`ClientSideCacheFactory`、`DistributedCacheFactory` |
| 观察者模式 | `CacheEvictedEventBus`、`CacheEvictedSubscriber` |
| 模板方法 | `ComputedCache`、`AbstractCacheFactory` |
| 策略模式 | `KeyConverter`、`KeyFilter`、`CodecExecutor` |
| 装饰器模式 | `DefaultCoherentCache` 装饰各层缓存 |

## 代码审查要点

1. **锁的使用**：确保逐键锁正确获取和释放
2. **事件循环**：确保 `publisherId` 检查正确
3. **TTL 管理**：确保过期值不被写入缓存
4. **MissingGuard**：确保空值正确缓存
5. **线程安全**：确保并发场景下数据一致性

## 相关页面

- [架构概览](../architecture/index.md) - 系统架构
- [缓存层级](../architecture/cache-layers.md) - 三层缓存详解
- [一致性与事件总线](../architecture/coherence.md) - 一致性机制
- [性能模式](../testing/performance-patterns.md) - 性能优化
- [贡献者指南](./contributor.md) - 新人入门
