---
title: Actuator 端点
description: CoCache Actuator 端点详解，包括 CoCacheEndpoint 和 CoCacheClientEndpoint 的使用方法。
---

# Actuator 端点

CoCache 提供 Spring Boot Actuator 端点，用于监控缓存状态、查询缓存值和手动驱逐缓存条目。

## 启用端点

在 `application.yaml` 中暴露 CoCache 端点：

```yaml
management:
  endpoints:
    web:
      exposure:
        include:
          - cocache
          - cocacheClient
```

## CoCacheEndpoint

端点 ID：`cocache`

提供缓存级别的统计、查询和驱逐操作。

### 获取所有缓存概览

```
GET /actuator/cocache
```

返回所有 `CoherentCache` 实例的报告列表：

```json
[
  {
    "name": "UserCache",
    "clientId": "host-192.168.1.100",
    "clientSize": 1234,
    "keyConverter": "ToStringKeyConverter(prefix=user:)",
    "distributedCaching": "me.ahoo.cache.spring.redis.RedisDistributedCache",
    "clientSideCaching": "me.ahoo.cache.client.GuavaClientSideCache",
    "cacheEvictedEventBus": "me.ahoo.cache.spring.redis.RedisCacheEvictedEventBus",
    "cacheSource": "me.ahoo.cache.spring.source.SpringCacheSourceFactory$...",
    "keyFilter": "me.ahoo.cache.filter.NoOpKeyFilter"
  }
]
```

### 获取单个缓存统计

```
GET /actuator/cocache/{name}
```

返回指定缓存的统计信息，格式同上。

### 查询缓存值

```
GET /actuator/cocache/{name}/{key}
```

查询指定缓存中的条目，返回 `CacheValue` 格式：

```json
{
  "value": { ... },
  "ttlAt": 1700000000,
  "missingGuard": false
}
```

### 驱逐缓存条目

```
DELETE /actuator/cocache/{name}/{key}
```

手动驱逐指定缓存中的条目。

### CacheReport 字段说明

| 字段 | 说明 |
|------|------|
| `name` | 缓存名称 |
| `clientId` | 当前实例的客户端 ID |
| `clientSize` | L2 客户端缓存中的条目数 |
| `keyConverter` | 键转换器信息 |
| `distributedCaching` | L1 分布式缓存实现类 |
| `clientSideCaching` | L2 客户端缓存实现类 |
| `cacheEvictedEventBus` | 事件总线实现类 |
| `cacheSource` | 数据源实现类 |
| `keyFilter` | 键过滤器实现类 |

**源码参考**：[`cocache-spring-boot-starter/.../CoCacheEndpoint.kt`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-spring-boot-starter/src/main/kotlin/me/ahoo/cache/spring/boot/starter/CoCacheEndpoint.kt)

## CoCacheClientEndpoint

端点 ID：`cocacheClient`

提供客户端级别的缓存统计信息。

## 使用场景

### 开发调试

在开发过程中，通过 Actuator 端点查看缓存状态：

```bash
# 查看所有缓存
curl http://localhost:8008/actuator/cocache

# 查看特定缓存
curl http://localhost:8008/actuator/cocache/UserCache

# 查询缓存值
curl http://localhost:8008/actuator/cocache/UserCache/user:123

# 驱逐缓存条目
curl -X DELETE http://localhost:8008/actuator/cocache/UserCache/user:123
```

### 生产监控

监控缓存命中率和客户端缓存大小，判断是否需要调整缓存参数：

- `clientSize`：监控 L2 缓存使用量，判断 `maximumSize` 是否合理
- `distributedCaching` / `clientSideCaching`：确认使用的缓存实现
- `keyFilter`：确认是否启用了布隆过滤器

### 集成 Swagger

配合 springdoc-openapi 使用，可以在 Swagger UI 中直接调用 Actuator 端点：

```yaml
springdoc:
  show-actuator: true
```

## 安全注意事项

在生产环境中，建议对 Actuator 端点进行安全保护：

1. 使用 Spring Security 限制访问
2. 仅暴露必要的端点
3. `DELETE` 操作应特别注意权限控制

## 相关页面

- [配置指南](../guide/configuration.md) - 配置参数
- [cocache-spring-boot-starter](../modules/cocache-spring-boot-starter.md) - 自动配置模块
- [核心接口](./core-interfaces.md) - CacheReport 中涉及的接口
