---
title: Spring Cache
description: 通过 CoCacheManager 在 Spring 的 @Cacheable / @CacheEvict 背后使用 CoCache 缓存。
---

# Spring Cache

`cocache-spring-cache` 把每个 CoCache 缓存暴露为 Spring `Cache`，于是 `@Cacheable`、`@CachePut`、`@CacheEvict` 都获得二级一致性缓存。只要应用没有自定义 `CacheManager`，starter 就会注册 `CoCacheManager`。

## 用法

照常声明缓存，缓存名即 Spring 的缓存名；然后开启 Spring 缓存：

```kotlin
@CoCache(ttl = 600)
interface UserCache : Cache<String, User>

@EnableCaching
@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class AppServer

@Service
class UserService(private val userRepository: UserRepository) {
    @Cacheable(cacheNames = ["UserCache"], key = "#id", sync = true)
    fun getUser(id: String): User = userRepository.findById(id)

    @CacheEvict(cacheNames = ["UserCache"], key = "#user.id")
    fun updateUser(user: User): User = userRepository.save(user)
}
```

被注解的方法就是加载器，因此这类缓存通常不配置自己的 `CacheSource`。Spring 的 key 必须与缓存的 key 类型一致（此处为 `String`）。

写入后优先用 `@CacheEvict` 而不是 `@CachePut`：淘汰使下一次读取加载已提交的状态，所有实例也都会丢弃 L2 副本。

## 语义

| Spring 操作 | CoCache 行为 |
|-------------|--------------|
| `get(key)` | 命中返回值；不存在、已过期与**负缓存**都视为未命中 |
| `get(key, valueLoader)`（`sync = true`） | 同一 key 的并发调用在每个实例内共享一次加载器调用；加载器异常包装为 `ValueRetrievalException` |
| `put(key, value)` | 按缓存的 TTL 策略执行 `cache[key] = value`；`null` 写入负缓存 |
| `evict(key)` | 对 L2、L1 执行 `cache.evict(key)`，并通知其它实例 |
| `clear()` | **只**清空本实例的 L2。L1 是共享存储，CoCache 从不整体清空它 |
| `retrieve(…)`（响应式 / 异步） | 阻塞查找在有界守护线程池上执行；队列满时由调用方线程执行 |

负缓存视为未命中是有原因的：数据源找不到数据时，CoCache 缓存就会写入负缓存；使用默认的 `noOp` 数据源时，每次未命中都是如此。若 Spring 把这类条目当作“缓存的 null”，`@Cacheable` 将永远不会调用你的方法。

## 不使用 Spring Boot

```kotlin
@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun cacheManager(cacheFactory: CacheFactory): CoCacheManager = CoCacheManager(cacheFactory)
}
```

`CoCacheManager(cacheFactory, executor)` 可指定 `retrieve` 使用的线程池。
