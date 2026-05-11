---
title: 集成测试
description: CoCache 集成测试指南，包括 Redis 集成测试、多实例同步测试和 CI 配置。
---

# 集成测试

CoCache 的集成测试验证与 Redis 等外部组件的集成正确性，以及多实例间的缓存一致性。

## 需要外部依赖的模块

| 模块 | 外部依赖 | 测试要求 |
|------|----------|----------|
| cocache-spring-redis | Redis | 需要 Redis 实例 |
| cocache-spring-boot-starter | Redis + Spring Boot | 需要 Redis 实例 |

## Redis 集成测试

### cocache-spring-redis

测试 `RedisDistributedCache` 和 `RedisCacheEvictedEventBus`：

```kotlin
class RedisDistributedCacheTest : DistributedCacheSpec<String>() {
    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    override fun createCache(): DistributedCache<String> {
        val codecExecutor = StringToStringCodecExecutor(redisTemplate)
        return RedisDistributedCache(redisTemplate, codecExecutor, ttl = 30)
    }

    override fun createCacheEntry(): Pair<String, String> {
        return "testKey" to "testValue"
    }
}
```

### 多实例同步测试

`MultipleInstanceSyncSpec` 测试多个 `CoherentCache` 实例之间的缓存一致性：

```kotlin
abstract class MultipleInstanceSyncSpec {
    // 创建两个独立的 CoherentCache 实例（模拟两个应用实例）
    // 1. 实例 A 写入缓存
    // 2. 实例 A 发布 CacheEvictedEvent
    // 3. 实例 B 收到事件后自动失效 L2
    // 4. 实例 B 从 L1 获取最新值
}
```

## CI 配置

集成测试在 CI 中使用 Redis Service Container：

```yaml
# .github/workflows/integration-test.yml
services:
  redis:
    image: redis:7
    ports:
      - 6379: 6379
    options: >-
      --health-cmd "redis-cli ping"
      --health-interval 10s
      --health-timeout 5s
      --health-retries 5
```

## 运行集成测试

```bash
# 运行 Redis 集成测试
./gradlew :cocache-spring-redis:check

# 运行 Spring Boot Starter 集成测试
./gradlew :cocache-spring-boot-starter:check

# 运行所有检查（包括集成测试）
./gradlew check
```

## 本地运行

在本地运行集成测试前，确保 Redis 已启动：

```bash
# 使用 Docker 启动 Redis
docker run -d --name redis-test -p 6379:6379 redis:7

# 运行集成测试
./gradlew :cocache-spring-redis:check
```

## 相关页面

- [测试概览](./index.md) - 测试策略
- [单元测试](./unit-testing.md) - 单元测试指南
- [构建与 CI](../building/index.md) - CI/CD 配置
- [cocache-spring-redis](../modules/cocache-spring-redis.md) - Redis 模块
