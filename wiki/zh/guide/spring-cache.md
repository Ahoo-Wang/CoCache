---
title: Spring Cache
description: 在 @Cacheable、@CachePut 与 @CacheEvict 背后使用 CoCache：为什么 sync = true 很重要、启动时的 sync 检查、null 的处理、事务，以及与其它缓存管理器并存。
---

# Spring Cache

如果代码已经在用 Spring 的缓存注解，CoCache 可以直接放在它们背后。starter 注册 `CoCacheSpringCacheManager`，把每个 CoCache 缓存以同名的 Spring `Cache` 暴露出来。

## 用法 {#usage}

```kotlin
@CoCache(ttl = 600)
interface UserCache : Cache<String, User>          // 没有 CacheLoader：由被注解的方法加载

@EnableCaching
@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App

@Service
class UserService(private val repo: UserRepository) {
    @Cacheable(cacheNames = ["UserCache"], key = "#id", sync = true)
    fun getUser(id: String): User? = repo.findById(id)

    @Transactional
    @CacheEvict(cacheNames = ["UserCache"], key = "#user.id")
    fun save(user: User): User = repo.save(user)
}
```

- 缓存必须先声明；管理器从不按未见过的名字创建缓存。
- Spring 的 key 必须是缓存的 key 类型（这里是 `String`），方法必须返回缓存的值类型。
- 被注解的方法充当 loader，所以缓存通常不需要自己的 `CacheLoader`。

## 始终使用 `sync = true` {#use-sync-true}

设置 `sync = true` 时，Spring 调用 `get(key, valueLoader)`，CoCache 让你的方法走与自身读取相同的受保护加载：

- 同一实例上的并发调用共享一次方法调用，
- 其它实例上的调用借助 Redis lease 等待这次调用（最多 `lease-wait`），并且
- 方法执行期间发生的失效，永远不会被它的结果覆盖。

不设置 `sync` 时，Spring 自己完成：读取、未命中、调用方法、`put`。这个 `put` 是无条件的。如果方法执行期间数据被更新并失效，旧结果会被写回，并一直返回到 `ttl`。并发未命中也会各自调用方法。

### 启动检查 {#sync-check}

启用 `@EnableCaching` 时，starter 在启动时检查每个 bean 的缓存操作（包括 `@CacheConfig` 中的缓存名）。对每个指向 CoCache 缓存却没有 `sync = true` 的 `@Cacheable`，记录一条 `WARN`，给出 `类名#方法名` 与缓存名。要改为启动失败：

```yaml
cocache:
  spring-cache:
    require-sync: true
```

检查按缓存名匹配，跳过没有写明缓存名的操作（只用 `cacheResolver` 的那些）。

## Spring 操作如何映射 {#semantics}

| Spring | CoCache |
|--------|---------|
| `get(key)` | 有值即命中。不存在与**负缓存**都视为未命中。 |
| `get(key, valueLoader)`（`sync = true`） | `cache.get(key, loader)`。loader 的异常包装为 `ValueRetrievalException`。 |
| `put(key, value)`（`@CachePut`） | `cache.put(key, value)`。值为 `null` 时改为 `invalidate(key)`。 |
| `evict(key)`（`@CacheEvict`） | 在所有实例上 `cache.invalidate(key)` |
| `clear()`（`allEntries = true`） | **只清空本实例的 L2。** Redis 是共享的，从不整体清空。 |
| `retrieve(key)`（异步与响应式） | 在守护线程池上执行阻塞查找。 |
| `retrieve(key, loader)`（`sync = true` 的异步与响应式方法） | 与 `get(key, loader)` 一样经受 lease 保护的路径回源，在该线程池上等待异步 loader 完成。每个等待中的调用在回源期间占用一个执行器线程：默认执行器在 JDK 21+ 上每个任务一个虚拟线程，在 JDK 17 上是有上限的守护线程池（`max(8, 4 × CPU 数)` 个线程，多余任务排队）。传给 `CoCacheSpringCacheManager(runtime, executor)` 的自定义执行器不能同时是完成 loader future 的线程，否则线程池占满时会死锁。 |

### null 结果 {#null}

设置 `sync = true` 时，返回 `null` 的方法会产生一条负缓存。在 `absent-ttl`（默认 1 分钟）内，后续调用直接返回 `null`，不再执行方法。新建该行时请失效对应的 key，或调短 `absent-ttl`。

通过 `get(key)` 读到负缓存时视为未命中，这是有意为之：CoCache 在 loader 找不到数据时都会写负缓存，若把它当作缓存的 `null`，不带 sync 的 `@Cacheable` 方法将永远不会执行。

## 优先 evict 而不是 put {#evict}

写入之后，优先用 `@CacheEvict` 而不是 `@CachePut`。驱逐让下一次读取加载的正是已提交的数据，所有实例都会丢弃副本。见 [`invalidate` 还是 `put`？](./freshness.md#invalidate-vs-put)。

## 事务 {#transactions}

classpath 中有 `spring-tx` 时，starter 用 Spring 的 `TransactionAwareCacheManagerProxy` 包装管理器：事务内的 `@CacheEvict` 与 `@CachePut` 在提交之后执行，回滚时不执行。在提交之前失效，并发的读取可能把旧数据缓存起来。

直接调用时使用 `TransactionalInvalidator`（见[保持数据新鲜](./freshness.md#transactions)）。

## 与其它缓存管理器并存 {#other-managers}

应用自己定义了 `CacheManager` 时，starter 的管理器会退让。要让 CoCache 与其它管理器并存，自己声明它，并在注解中指向它：

```kotlin
@Bean
fun cocacheCacheManager(runtime: CacheRuntime): CacheManager =
    TransactionAwareCacheManagerProxy(CoCacheSpringCacheManager(runtime))

@Primary
@Bean
fun localCacheManager(): CacheManager = CaffeineCacheManager()

@Cacheable(cacheNames = ["UserCache"], key = "#id", sync = true, cacheManager = "cocacheCacheManager")
fun getUser(id: String): User? = repo.findById(id)
```

`CoCacheSpringCacheManager(runtime, executor)` 还可以传入 `retrieve` 使用的执行器。sync 检查按缓存名匹配，所以请给 CoCache 缓存起一个其它管理器不用的名字。

## 不用 Spring Boot {#without-spring-boot}

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

管理器提供的是该 `CacheRuntime` 中已经创建的缓存。不使用事务时去掉代理即可。
