---
title: 单元测试
description: CoCache 单元测试指南，包括如何继承 TCK 规范、使用 fluent-assert 和 mockk 编写测试。
---

# 单元测试

本页面介绍如何为 CoCache 编写单元测试，包括继承 TCK 规范和编写自定义测试。

## 测试技术栈

| 工具 | 用途 |
|------|------|
| JUnit 5 (Jupiter) | 测试运行器 |
| fluent-assert | Kotlin 流式断言 |
| mockk | Mock 框架 |

## 使用 fluent-assert

CoCache 项目统一使用 `fluent-assert` 进行断言，**不使用** AssertJ 的 `assertThat()`。

```kotlin
import me.ahoo.test.asserts.assert

// 正确用法
cache[key].assert().isEqualTo(value)
cache[key].assert().isNull()
result.assert().isTrue()

// 错误用法 - 不要使用 AssertJ
// assertThat(cache[key]).isEqualTo(value)  // 不要这样写
```

## 继承 CacheSpec

为自定义缓存实现编写测试时，继承 `CacheSpec` 即可自动获得基础测试用例。

```kotlin
import me.ahoo.cache.api.Cache
import me.ahoo.cache.test.CacheSpec

class MyCacheTest : CacheSpec<String, String>() {
    override fun createCache(): Cache<String, String> {
        // 创建被测缓存实例
        return MyCacheImpl()
    }

    override fun createCacheEntry(): Pair<String, String> {
        // 创建测试数据
        return "testKey" to "testValue"
    }
}
```

`CacheSpec` 自动提供以下测试：
- `get()` - 获取不存在的键
- `getWhenExpired()` - 获取已过期的键
- `set()` - 设置缓存值
- `setWithTtl()` - 带 TTL 设置
- `setWithTtlAmplitude()` - 带 TTL 抖动设置
- `evict()` - 驱逐
- `setMissing()` - 缺失守卫值
- `setMissingTtl()` - 带 TTL 的缺失守卫值

## 继承 ClientSideCacheSpec

为 L2 客户端缓存实现编写测试：

```kotlin
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.test.ClientSideCacheSpec

class MyClientSideCacheTest : ClientSideCacheSpec<String>() {
    override fun createCache(): ClientSideCache<String> {
        return MyClientSideCacheImpl()
    }

    override fun createCacheEntry(): Pair<String, String> {
        return "testKey" to "testValue"
    }
}
```

## 继承 DistributedCacheSpec

为 L1 分布式缓存实现编写测试：

```kotlin
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.test.DistributedCacheSpec

class MyDistributedCacheTest : DistributedCacheSpec<String>() {
    override fun createCache(): DistributedCache<String> {
        return MyDistributedCacheImpl()
    }

    override fun createCacheEntry(): Pair<String, String> {
        return "testKey" to "testValue"
    }
}
```

## 继承 DefaultCoherentCacheSpec

为一致性缓存编写完整测试：

```kotlin
import me.ahoo.cache.test.DefaultCoherentCacheSpec

class MyCoherentCacheTest : DefaultCoherentCacheSpec<String, String>() {
    override fun createKeyConverter(): KeyConverter<String> {
        return ToStringKeyConverter("test:")
    }

    override fun createClientSideCache(): ClientSideCache<String> {
        return MapClientSideCache()
    }

    override fun createDistributedCache(): DistributedCache<String> {
        return MockDistributedCache()
    }

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus {
        return GuavaCacheEvictedEventBus()
    }

    override fun createCacheName(): String = "testCache"

    override fun createCacheEntry(): Pair<String, String> {
        return "testKey" to "testValue"
    }
}
```

## 编写自定义测试

### 基本模式

```kotlin
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class CustomCacheTest {

    @Test
    fun `should return null for missing key`() {
        val cache = createCache()
        cache["nonexistent"].assert().isNull()
    }

    @Test
    fun `should store and retrieve value`() {
        val cache = createCache()
        cache["key"] = "value"
        cache["key"].assert().isEqualTo("value")
    }
}
```

### 使用 mockk

```kotlin
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify

class MockCacheSourceTest {

    @Test
    fun `should call cache source on cache miss`() {
        val cacheSource = mockk<CacheSource<String, String>>()
        every { cacheSource.loadCacheValue("key") } returns DefaultCacheValue.forever("value")

        val cache = createCoherentCache(cacheSource)
        cache["key"].assert().isEqualTo("value")

        verify { cacheSource.loadCacheValue("key") }
    }
}
```

### 并发测试

CoCache 的 `DefaultCoherentCacheSpec` 包含参数化并发测试：

```kotlin
@ParameterizedTest
@ValueSource(ints = [10, 100, 1000])
fun `should prevent cache breakdown under high concurrency`(threadCount: Int) {
    // 使用 CountDownLatch 同时启动所有线程
    // 验证所有线程获取到相同值
    // 验证 CacheSource 只被调用一次
    callCount.get().assert().isOne()
}
```

## 测试目录结构

```
cocache-core/src/test/kotlin/me/ahoo/cache/
├── client/
│   ├── GuavaClientSideCacheTest.kt
│   ├── CaffeineClientSideCacheTest.kt
│   └── MapClientSideCacheTest.kt
├── consistency/
│   ├── DefaultCoherentCacheTest.kt
│   └── GuavaCacheEvictedEventBusTest.kt
├── converter/
│   ├── ToStringKeyConverterTest.kt
│   └── ExpKeyConverterTest.kt
├── filter/
│   ├── BloomKeyFilterTest.kt
│   └── NoOpKeyFilterTest.kt
└── proxy/
    └── ProxyCacheTest.kt
```

## 相关页面

- [测试概览](./index.md) - 测试策略
- [集成测试](./integration-testing.md) - 集成测试指南
- [贡献指南](../building/contributing.md) - 贡献代码
