---
title: Spring Cache
description: 通过 CoCacheSpringCacheManager 在 Spring 的 @Cacheable / @CachePut / @CacheEvict 背后使用 CoCache，并用 TransactionalInvalidator 在提交之后失效。
---

# Spring Cache

`cocache-spring` 把每个 CoCache 缓存暴露为 Spring `Cache`，让 `@Cacheable`、`@CachePut`、`@CacheEvict` 获得二级一致性缓存。除非应用已经定义了 `CacheManager`，starter 会注册 `CoCacheSpringCacheManager`。

## 用法 {#usage}

照常声明缓存，缓存名就是 Spring 的缓存名，然后启用 Spring 缓存：

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
    fun getUser(id: String): User? = userRepository.findById(id)

    @Transactional
    @CacheEvict(cacheNames = ["UserCache"], key = "#user.id")
    fun updateUser(user: User): User = userRepository.save(user)
}
```

被注解的方法充当 loader，所以这类缓存通常没有自己的 `CacheLoader`。Spring 的 key 必须与缓存的 key 类型一致，这里是 `String`。CacheManager 只提供已声明的缓存，不会按名称自动创建缓存。

## 使用 `sync = true` {#use-sync-true}

`@Cacheable(sync = true)` 是一致的路径。Spring 调用 `get(key, valueLoader)`，CoCache 让它走与自身读取相同的受保护加载：

- 同一实例上的并发调用共享一次方法调用（`SingleFlight`）；
- 不同实例上的并发调用经 L1 lease 共享一次调用（前提是在 `lease-wait` 内完成）；
- 方法执行期间发生的失效，不会被它的结果覆盖。

不带 `sync` 时，Spring 自己执行“读取、未命中、调用方法、`put`”。这个 `put` 是无条件写入，方法执行期间发生的失效可能被旧结果覆盖，直到 `ttl` 到期。需要 CoCache 的一致性保证时，请使用 `sync = true`。

starter 在启动时检查这一点。有 `@EnableCaching` 时，它通过 Spring 的 `CacheOperationSource` 解析每个 bean 的缓存操作（包括 `@CacheConfig` 中的缓存名）。对每个指向 CoCache 缓存、没有 `sync = true` 的 `@Cacheable`，记录一条 `WARN`，给出 `类名#方法名` 与缓存名。要改为启动失败：

```yaml
cocache:
  spring-cache:
    require-sync: true
```

检查按缓存名匹配：没有写明缓存名（只用 `cacheResolver`）的操作会跳过；没有 `@EnableCaching` 时什么也不做。

写入之后，优先用 `@CacheEvict` 而不是 `@CachePut`：失效让下一次读取加载已提交的状态，并且每个实例都会丢弃 L2 副本。

## 语义 {#semantics}

| Spring 操作 | CoCache 行为 |
|-------------|--------------|
| `get(key)` | 命中时返回值；不存在与**负缓存**都视为未命中 |
| `get(key, valueLoader)`（`sync = true`） | `cache.get(key, loader)`：受 lease 与失效戳保护的加载。loader 的异常包装为 `ValueRetrievalException` |
| `put(key, value)` | `cache.put(key, value)`。`null`（例如 `@CachePut` 返回 null）变为 `invalidate(key)` |
| `evict(key)` | `cache.invalidate(key)`：删除 L2 与 L1 并通知其它实例 |
| `clear()` | **只清空本实例的 L2**。L1 是共享的，CoCache 从不整体清空 L1 |
| `retrieve(…)`（响应式 / 异步） | 在守护线程池上执行阻塞查找，不占用调用方线程 |

负缓存视为未命中是有原因的：只要 loader 找不到数据，缓存就会写入负缓存。如果 Spring 把这些条目当作缓存的 `null`，`@Cacheable` 将永远不会调用你的方法。

## 事务 {#transactions}

在事务提交之前失效本身就是一个漏洞：并发的读取可能重新加载旧数据并缓存它。classpath 上有 `spring-tx` 时，starter 会：

- 用 Spring 的 `TransactionAwareCacheManagerProxy` 包装 CacheManager，事务内的 `@CacheEvict` 与 `@CachePut` 在提交之后执行；
- 为直接调用注册 `TransactionalInvalidator`：

```kotlin
@Transactional
fun rename(id: String, name: String) {
    userRepository.rename(id, name)
    invalidator.invalidate(userCache, id)   // 在 afterCommit 中执行
}
```

`TransactionalInvalidator` 把失效推迟到 `afterCommit`，同一事务内对同一缓存的所有失效合并为一次 `invalidateAll`，回滚时什么也不做。`REQUIRES_NEW` 的内层事务在自己提交之后失效。没有活动事务时立即失效。

## 不使用 Spring Boot {#without-spring-boot}

```kotlin
@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun cacheManager(runtime: CacheRuntime): CacheManager =
        TransactionAwareCacheManagerProxy(CoCacheSpringCacheManager(runtime))

    @Bean
    fun transactionalInvalidator() = TransactionalInvalidator()
}
```

`CoCacheSpringCacheManager(runtime, executor)` 可以指定 `retrieve` 使用的执行器。不使用事务时去掉代理即可。
