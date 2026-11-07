---
title: Spring Cache
description: 在 @Cacheable、@CachePut 与 @CacheEvict 背后使用 CoCache：为什么 sync = true 很重要、启动检查、null 的处理、Redis 故障、事务，以及与其它缓存管理器并存。
---

# Spring Cache

如果代码已经在用 Spring 的缓存注解，CoCache 可以直接放在它们背后。starter 注册 `CoCacheSpringCacheManager`，把每个 CoCache `Cache`（派生副本）以同名的 Spring `Cache` 暴露出来；其它四种类型[不暴露](#sync-check)。

## 用法 {#usage}

```kotlin
@CoCache(ttl = 600, source = CoCache.Source.EXTERNAL)
interface UserCache : Cache<String, User>          // 外部来源：由被注解的方法加载

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
- 只有 `Cache` 能接在 Spring Cache 背后：它是派生副本，被注解的方法就是它的数据源。不要在缓存注解里使用 `Store`、`VersionedStore` 或 `SetStore` 的名字。
- 被注解的方法充当 loader，所以缓存通常不需要自己的 `CacheLoader`。把它声明为[外部来源](./cache.md#externally-sourced)：接口上写 `source = CoCache.Source.EXTERNAL`，或在 `cacheSpec` 中调用 `externallySourced()`。既没有匹配的 loader bean、也没有 `source = EXTERNAL` 的 `Cache` 接口会让启动失败。

## 始终使用 `sync = true` {#use-sync-true}

设置 `sync = true` 时，Spring 调用 `get(key, valueLoader)`，CoCache 让你的方法走与自身读取相同的受保护加载：

- 同一实例上的并发调用共享一次方法调用，
- 其它实例上的调用借助 Redis lease 等待这次调用（最多 `lease-wait`），
- 方法执行期间发生的失效，永远不会被它的结果覆盖，并且
- 方法执行期间调用方线程被中断时，只有这个调用方失败；合并到这次调用的调用方自己执行方法，而不是收到这个中断。

不设置 `sync` 时，Spring 自己完成：读取、未命中、调用方法、`put`。这个 `put` 是无条件的。如果方法执行期间数据被更新并失效，旧结果会被写回，并一直返回到 `ttl`。并发未命中也会各自调用方法。

### 启动检查 {#sync-check}

启用 `@EnableCaching` 时，starter 在启动时检查每个 bean 的缓存操作（包括 `@CacheConfig` 中的缓存名）。对每个指向 CoCache 缓存却没有 `sync = true` 的 `@Cacheable`，记录一条 `WARN`，给出 `类名#方法名` 与缓存名。要改为启动失败：

```yaml
cocache:
  spring-cache:
    require-sync: true
```

检查按缓存名匹配。指定了其它 `cacheManager` 或自定义 `cacheResolver` 的操作不一定经过 CoCache，检查会跳过。

缓存通知与事务通知的 order 相同时，同一个检查也记录一条 `WARN`。见[事务通知在外层](#advice-order)。

此外，任何缓存操作（`@Cacheable`、`@CachePut` 或 `@CacheEvict`，不论 `sync`）指向不是 `Cache` 的 CoCache 缓存（`Store`、`VersionedStore`、`SetStore` 或 `VersionedCache`）时，不论 `require-sync` 如何，启动都失败。Spring Cache 只接入派生副本，CoCache 的管理器不暴露其它类型；没有这项检查，调用要到运行时才以“找不到缓存”失败。

只有存在名为 `cocacheCacheManager` 的 bean 时检查才会运行：可以是 starter 自己的管理器，也可以是你以这个名字声明的管理器（见[与其它缓存管理器并存](#other-managers)）。你声明任何其它 `CacheManager` 时 starter 的管理器会退让，因此如果你以别的 bean 名自己装配 `CoCacheSpringCacheManager`，检查不适用，`sync = true` 需要你自己保证。

## Spring 操作如何映射 {#semantics}

| Spring | CoCache |
|--------|---------|
| `get(key)` | 有值即命中。不存在与**负缓存**都视为未命中。 |
| `get(key, valueLoader)`（`sync = true`） | `cache.get(key, loader)`。loader 的异常包装为 `ValueRetrievalException`，交给合并到这次调用的调用方。例外是方法抛出的 `InterruptedException`（调用方线程被中断）：只有这个调用方失败（同样包装为 `ValueRetrievalException`），合并到这次调用的调用方自己执行方法。 |
| `put(key, value)`（`@CachePut`） | `cache.put(key, value)`。值为 `null` 时改为 `invalidate(key)`。 |
| `evict(key)`（`@CacheEvict`） | 在所有实例上 `cache.invalidate(key)`。事务内见[事务](#transactions)。 |
| `clear()`（`allEntries = true`） | **只清空本实例的 L2。** Redis 与其它实例的副本保持不变：CoCache 不能在全集群清空一个缓存。每个缓存第一次调用时记录一条 `WARN` 说明这一点。请改为按 key 驱逐。 |
| `retrieve(key)`（异步与响应式） | 缓存[异步视图](./async.md)上的异步 `lookup`。L2 命中返回已完成的 future，不切换线程；负缓存以 `null` 完成（视为未命中）。 Redis 读取失败时 future 失败，与 `get(key)` 一样（见 [Redis 故障时](#failures)）。 |
| `retrieve(key, loader)`（`sync = true` 的异步与响应式方法） | [异步视图](./async.md)上的异步 `get(key, loader)`：与 `get(key, loader)` 一样经受 lease 保护的路径回源，**没有线程等待**异步 loader。方法体在运行时的执行器（`cocache.executor`）上执行，不在调用方线程上，所以先阻塞再返回 future 的方法体（如 `completedFuture(repository.find(id))`）占用的是该执行器的一个线程，从不是调用方的线程。方法体看不到调用方的 ThreadLocal。失败时 future 以原始异常完成。 |

### null 结果 {#null}

设置 `sync = true` 时，返回 `null` 的方法会产生一条负缓存。在 `absent-ttl`（默认 1 分钟）内，后续调用直接返回 `null`，不再执行方法。新建该行时请失效对应的 key，或调短 `absent-ttl`。

通过 `get(key)` 读到负缓存时视为未命中，这是有意为之：CoCache 在 loader 找不到数据时都会写负缓存，若把它当作缓存的 `null`，不带 sync 的 `@Cacheable` 方法将永远不会执行。

### Redis 故障时 {#failures}

经 Spring Cache 使用的缓存通常是[外部来源](./cache.md#externally-sourced)的：被注解的方法就是数据源。带着方法的读取可以退回到方法，不带的读取没有答案：

- `@Cacheable(sync = true)` 把方法作为 loader 传入，Redis 读取失败时照常执行方法，结果以 `degraded-local-max-age` 留在 L2 中。
- 不带 sync 的 `@Cacheable` 经 `get(key)` 读取，没有 loader；返回 `CompletableFuture`、`Mono` 或 `Flux` 的方法上，Spring 改经 `retrieve(key)` 读取。Redis 读取失败是 [`Lookup.Unknown`](./caches.md#unknown)：`get(key)` 抛出它的 `CacheReadException`，`retrieve(key)` 的 future 以它失败，三种方法上 Spring 都把它交给 `CacheErrorHandler`：默认的处理器重新抛出，`LoggingCacheErrorHandler` 记录日志后改为执行方法。也可以在缓存的 spec 上声明 `unknownRead = UnknownReadMode.ABSENT`（写在 `cacheSpec` 中；接口缓存用 `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)`、写在[定制器](./caches.md#customizer)中，或配置 `cocache.caches.[name].unknown-read: absent`）：读取返回未命中且不缓存，Spring 随后执行方法。
- `@CacheEvict` 调用 `invalidate`。Redis 删除失败时，key 进入后台重试队列，因为每个 `Cache` 的 `invalidate-failure` 默认都是 `retry`：方法就是数据源，删除副本总是安全的。设置 `invalidate-failure: throw` 则改为抛出 `CacheWriteException`。见[配置](./configuration.md#policy-properties)。

于是同一个缓存上的两种 `@Cacheable` 表现不同。Redis 故障期间，不带 sync 的 `@Cacheable` 调用全部失败，因为 Spring 默认的 `CacheErrorHandler` 重新抛出读取失败；`@Cacheable(sync = true)` 则改为执行方法。`unknownRead = ABSENT` 也不能让不带 sync 的方法可用：随后的 `put` 同样失败。

要放行这些失败，开启 starter 的降级处理器：

```yaml
cocache:
  spring-cache:
    degrade-on-error: true
```

starter 随后为 Spring 的缓存拦截器换上 `DegradingCacheErrorHandler`。它只对 CoCache 缓存生效：读取失败或写入失败记录为一条 `WARN`（不带堆栈，不含 key；该 logger 开启 `DEBUG` 时改为以 `DEBUG` 级别输出，带堆栈），不重新抛出。Spring 随后把这次读取当作未命中、执行方法，结果不缓存。方法就是数据源，缓存的副本可以跳过。其余失败交给原来的处理器（`CachingConfigurer` 给出的，或 Spring 默认的）：

- 其它缓存的失败；
- CoCache 缓存上失败的失效与清空（`@CacheEvict`，以及结果为 `null` 的 `@CachePut`）。默认的 `invalidate-failure: retry` 下它们不会失败；设置为 `throw` 就是要求看到失败。

不用 Spring Boot 时，从 `CachingConfigurer.errorHandler()` 返回 `DegradingCacheErrorHandler(yourHandler)`。

## 执行器 {#executor}

`retrieve` 经缓存的[异步视图](./async.md)，不需要单独的执行器：等待加载的调用不占用线程。

- `CoCacheSpringCacheManager(runtime)` 不接收执行器。它以运行时的执行器（`CacheRuntime.executor`）创建每个 `CoCacheSpringCache(cache, admin, executor)`，由该执行器执行 `retrieve(key, loader)` 的方法体。
- 异步视图在 `cocache.executor` 上运行；经 `retrieve` 调用的同步 loader 与异步 `@Cacheable(sync = true)` 方法的方法体也在那里执行，所以该执行器必须容得下阻塞的任务（默认每个任务一个虚拟线程）。见[配置参考](./configuration.md#executors)。

Spring Cache 的 `put` 与 `evict` 只有阻塞签名。响应式方法上的 `@CachePut`、`@CacheEvict` 与不带 sync 的 `@Cacheable` 会在完成回调中调用它们，占用该线程一次 Redis 往返。请继续使用 `sync = true`；写路径不能阻塞时调用 `cache.async().invalidate(key)`。

## 优先 evict 而不是 put {#evict}

写入之后，优先用 `@CacheEvict` 而不是 `@CachePut`。驱逐让下一次读取加载的正是已提交的数据，所有实例都会丢弃副本。见 [`invalidate` 还是 `put`？](./freshness.md#invalidate-vs-put)。

## 事务 {#transactions}

classpath 中有 `spring-tx` 时，starter 用 CoCache 的事务装饰器 `TransactionAwareSpringCacheManager` 包装管理器，取代 Spring 的 `TransactionAwareCacheManagerProxy`。事务内：

| 注解 | Spring 调用 | 行为 |
|------|-------------|------|
| `@CacheEvict` | `evict(key)` | 登记到事务。提交之后**以及**回滚之后都失效这个 key。 |
| `@CacheEvict(beforeInvocation = true)` | `evictIfPresent(key)` | 按 Spring 的要求立即失效，同时登记，提交或回滚之后再失效一次。 |
| `@CachePut` | `put(key, value)` | 提交之后写入，回滚时丢弃。结果为 `null` 时是一次失效，按 `@CacheEvict` 登记。 |
| `@CacheEvict(allEntries = true)` | `clear()` | 提交之后清空本实例的 L2，回滚时丢弃。 |
| `@CacheEvict(allEntries = true, beforeInvocation = true)` | `invalidate()` | 立即清空本实例的 L2。 |

事务之外，所有操作立即执行。

装饰器这样做的原因：

- **提交之后失效**。在提交之前失效，并发的读取可能把旧数据缓存起来。对于 `beforeInvocation = true`，提交之后的失效会清掉驱逐与提交之间另一个实例上的读取加载的旧数据。
- **回滚之后同样失效**。同一事务内的 `@Cacheable(sync = true)` 读取在事务中执行，可能加载到你写入但从未提交的行。Spring 的装饰器在回滚时不执行 `@CacheEvict`，这一行于是会留在 Redis 中直到 `ttl`。
- **后面的驱逐优先**。提交时 CoCache 先执行失效，再执行写入。对同一个 key 先 `@CachePut` 再 `@CacheEvict`，结果是 key 被驱逐；先 `@CacheEvict` 再 `@CachePut`，结果是新值。
- **savepoint**。`NESTED` 事务（或编程式 savepoint）回滚时，丢弃 savepoint 之后登记的写入，并立即失效已登记的 key。

### 事务内的回源 {#transactional-loads}

同步读取还会关注它所在的事务。这包括 `@Cacheable(sync = true)`，以及在任何带数据源的 CoCache 缓存上直接调用的 `get`、`lookup`、`getAll`、`lookupAll` 与 `get(key, loader)`。只作用于 `PlatformTransactionManager` 的读写事务；只读事务、异步与挂起调用、响应式事务都不受影响。

- **回滚时失效事务加载的内容**（`invalidate-fills-on-rollback`，默认开启）。事务内的加载可能读到你写入但尚未提交的行，并把它缓存到 Redis；Redis 故障时只缓存到本实例的 L2。CoCache 记录事务加载进缓存的每个 key，在事务回滚、提交结果未知，或 `NESTED` 事务回滚到 savepoint 时失效它们。提交时不动它们：那是已提交的数据。例外是回滚过 savepoint 的事务：Spring 先调用全部同步的 `savepointRollback`，再回滚数据库，其它同步在这里读取缓存时加载到的是即将回滚的行。因此这样的事务提交时同样失效它加载过的全部内容，在推迟的 `@CachePut` 写入之前执行，这些写入保留。回滚时已经加载过的 key 在事务的其余部分也与已登记的 key 一样私有加载。正常结束的嵌套事务不改变什么。不需要登记任何东西，从不调用 `@CacheEvict` 的事务也在覆盖范围内。回滚之前，其它调用方仍可能从 Redis 读到未提交的值：回滚把这个窗口从 `ttl` 缩短到事务的时长。
- **已登记的 key 私有加载**（`fill-in-transaction`，默认 `registered-keys`）。一个 key 在事务中登记失效之后（`@CacheEvict`、`TransactionalInvalidator`），同一事务内再次加载它不碰 Redis、L2 与其它调用方：值只交给这次调用，计入 `cocache.fills.gated`。登记时还会立即丢弃本实例的副本，所以事务内的下一次读取未命中，加载到你自己的写入。这不是保证：其它线程可能先把已提交的值放回 L2。`private` 让读写事务内的全部加载都是私有的，L2 未命中的读取因此每次都调用数据源；`allow` 关闭这一机制。
- **其它调用方不共享事务内的加载**。事务内的调用方领头加载时，同一实例上并发的调用方各自加载，而不是拿它可能永远不会提交的结果。

key 按 keyspace（key 前缀与命名空间）与编码后的 key 匹配，所以经接口缓存的代理驱逐，同样覆盖经 `cacheSpec` bean 或 Spring Cache 的读取。两项配置按缓存生效，写在 `cocache.defaults.cache`、`cocache.overrides.cache`（取更严的值）与 `cocache.caches.[name]` 中（见[各层能设置什么](./configuration.md#field-layers)）。应用自己声明的 `FillObserver` bean 会取代 starter 的，这时两项配置都不再生效。对一个缓存两项都关闭（`invalidate-fills-on-rollback: false` 与 `fill-in-transaction: allow`）时，它的加载不再关注事务：

```yaml
cocache:
  defaults:
    cache:
      fill-in-transaction: private      # 所有缓存：写事务内的加载都是私有的
  caches:
    ReportCache:
      fill-in-transaction: allow        # 这个缓存：不关注事务
      invalidate-fills-on-rollback: false
```

一个缓存的两项配置在它创建完成时生效。更早开始的读取（来自你在自己的运行时上注册的 `CacheCreationListener`）整次调用都使用默认值（两项都开启），所以回滚照样失效它加载的值。

### 提交之后的失败 {#after-commit-failures}

推迟的写入或失效在提交之后失败时，交给缓存拦截器的 `CacheErrorHandler`，与事务之外的失败一样。在 CoCache 自己的同步执行之后、其它同步的 `afterCommit` 中做的失效也是如此：它立即执行，失败同样交给处理器，排在后面的同步照常执行。调用方不会因为一个数据已经提交的操作收到异常，也就不会去重试它。处理器重新抛出时（Spring 默认的 `SimpleCacheErrorHandler` 就是如此），CoCache 以 `ERROR` 记录这个异常，不再抛出。处理器收到的 `cache` 参数是装饰后的缓存。开启 [`degrade-on-error`](#failures) 时，失败的写入记录为一条 `WARN`。

处理器可以经这个缓存补偿，例如失效或重写与失败的那一项相关的条目。这样的操作立即执行，并取代本事务推迟、尚未执行的同一 key 的写入，之后不会再写回旧值。已经执行的写入不受影响；本事务登记的失效照常执行，所以重写的 key 若还有尚未执行的失效，结果只是未命中。

### 事务通知在外层 {#advice-order}

`@EnableCaching` 与 `@EnableTransactionManagement` 的 order 默认都是 `LOWEST_PRECEDENCE`。两者相同时，先注册的 advisor 在外层。上面的描述都假定事务通知在外层，缓存操作因此在事务内执行。

缓存通知在外层时，缓存操作在事务开始之前或结束之后执行，装饰器看不到事务：

- `@CacheEvict` 在提交之后执行，不登记，回滚时也不执行。
- `@CacheEvict(beforeInvocation = true)` 在事务开始之前失效一次。提交之后不再失效，其间加载的旧数据留到 `ttl`。
- `@Cacheable(sync = true)` 的方法在加载中开启自己的事务，结果在那个事务结束之后才缓存。加载开始时不在任何事务中，因此[不被关注](#transactional-loads)：事务回滚而方法仍返回了值时，这个值留在缓存中直到 `ttl`。

响应式方法上的 `@CacheEvict` 在 `doOnSuccess` 中执行；事务在外层时，这早于提交。装饰器看不到响应式事务（见[保持数据新鲜](./freshness.md#transactions)）。

两者 order 相同时，starter 在启动时记录一条 `WARN`。给事务通知一个更小的 order，让它在外层。自己声明 `@EnableTransactionManagement` 后，Spring Boot 自动配置的那一个会退让：

```kotlin
@EnableCaching
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)
@SpringBootApplication
class App
```

直接调用时使用 `TransactionalInvalidator`（见[保持数据新鲜](./freshness.md#transactions)）。它与装饰器共用事务的登记，所以它的失效同样取代之前同一 key 的 `@CachePut`。key 按 keyspace（key 前缀与命名空间）与编码后的 key 匹配，传入这个缓存的任何对象都可以：`cacheSpec` 声明的 bean、接口式缓存的代理，或 Spring Cache 背后的缓存。

## 与其它缓存管理器并存 {#other-managers}

应用自己定义了 `CacheManager` 时，starter 的管理器会退让。要让 CoCache 与其它管理器并存，自己声明它，并在注解中指向它：

```kotlin
@Bean
fun cocacheCacheManager(runtime: CacheRuntime, interceptor: ObjectProvider<CacheInterceptor>): CacheManager =
    TransactionAwareSpringCacheManager(CoCacheSpringCacheManager(runtime)) {
        interceptor.getIfUnique()?.errorHandler ?: SimpleCacheErrorHandler()
    }

@Primary
@Bean
fun localCacheManager(): CacheManager = CaffeineCacheManager()

@Cacheable(cacheNames = ["UserCache"], key = "#id", sync = true, cacheManager = "cocacheCacheManager")
fun getUser(id: String): User? = repo.findById(id)
```

把 bean 命名为 `cocacheCacheManager` 可让 [sync 检查](#sync-check)继续运行。检查按缓存名匹配，并把没有 `cacheManager` 属性的操作视为指向 CoCache，所以请给 CoCache 缓存起一个其它管理器不用的名字。

## 不用 Spring Boot {#without-spring-boot}

```kotlin
@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun cacheManager(runtime: CacheRuntime, interceptor: ObjectProvider<CacheInterceptor>): CacheManager =
        TransactionAwareSpringCacheManager(CoCacheSpringCacheManager(runtime)) {
            interceptor.getIfUnique()?.errorHandler ?: SimpleCacheErrorHandler()
        }

    @Bean
    fun transactionalInvalidator() = TransactionalInvalidator()
}
```

管理器提供的是该 `CacheRuntime` 中已经创建的缓存。lambda 把提交之后的失败交给拦截器的错误处理器，每次失败时才取得，此时容器已经启动。不传时装饰器使用 `SimpleCacheErrorHandler`，这些失败只记录日志。不使用事务时去掉装饰器即可。

要[关注事务内的回源](#transactional-loads)，构建运行时时传入 `TransactionalFillObserver`：`CacheRuntime.builder().backend(backend).fillObserver(TransactionalFillObserver()).build()`。它的构造函数接受上述两项配置。
