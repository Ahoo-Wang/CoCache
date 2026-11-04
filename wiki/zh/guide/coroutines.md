---
title: Kotlin 协程
description: 在 Kotlin 协程中挂起地使用 CoCache：每种缓存类型上的 cache.suspending()、SuspendingCacheLoader、loader 的上下文与 dispatcher、取消、异常、重入与 WebFlux。
---

# Kotlin 协程

每个缓存在[异步视图](./async.md)之外还有挂起视图。它就是同一个缓存：同一个 L2、同样的失效栅栏、同样的在途加载与 Redis lease。方法与同步 API 同名，等待时挂起而不是阻塞，抛出原始异常。

## 依赖 {#install}

不需要额外的模块。挂起视图的接口在 `cocache-api` 中（只需要 Kotlin 标准库），它们的实现、`SuspendingCacheLoader` 与 `suspendingLoader` 构建方法在 `cocache-core` 中；`cocache-core` 会带上 `kotlinx-coroutines-core`（版本由 Spring Boot 的依赖管理决定）。

## 拿到视图 {#view}

```kotlin
val users = userCache.suspending()            // SuspendingCache<String, User>

suspend fun profile(id: String): User? = users.get(id)

suspend fun rename(id: String, user: User) {
    users.put(id, user)
}
```

- `suspending()` 是每种[缓存类型](./caches.md#types)的成员，视图与同步的层次同构：`Cache.suspending()` 返回 `SuspendingCache`，`VersionedCache` 返回 `SuspendingVersionedCache`，`Store` 返回 `SuspendingStore`，`VersionedStore` 返回 `SuspendingVersionedStore`，`SetStore` 返回 `SuspendingSetStore`，后者的 `get` 与 `SetStore.get` 一样不存在时返回空集合，从不返回 `null`。它们都继承 `SuspendingReadableCache`（`get`、`getAll`、`lookup`、`lookupAll`），挂起视图上的 `async()` 返回对应的异步视图。
- `suspending()` 每次返回同一个对象，可以在用到的地方直接调用。经 `@CoCache` 接口调用也可以。
- 每个方法直接调用缓存的核心。L2 命中立即返回：不挂起、不切换 dispatcher、不分配。
- 未命中、写入与条件读取在 CoCache 自己的 dispatcher 上执行：在你的线程上开始，第一次挂起之后在 CoCache 的执行器上继续，所以你的协程从 CoCache 的线程上恢复，从不从 Redis 客户端的线程上恢复。用 `Dispatchers.Unconfined`（Spring WebFlux 处理器的默认值）时，调用之后你的代码因此在 CoCache 的执行器线程上执行，不在 Lettuce 的事件循环上。你的协程的上下文元素（如追踪上下文或 `MDCContext`）随之传递，`Job` 与 dispatcher 不传递。
- Redis 故障、而这次读取没有数据源时，`lookup` 返回 `Lookup.Unknown`，`get` 抛出 `CacheReadException`，与[同步 API](./caches.md#unknown) 相同。
- CoCache 等待 Redis、其它实例的 lease 或你的数据源时，不阻塞任何线程。

## 数据源 {#loader}

挂起的数据源是 `SuspendingCacheLoader`（`me.ahoo.cocache.core`），在缓存的 spec 中设置：

```kotlin
cacheSpec<String, User>("user") {
    codec = jacksonCodec<User>(mapper)
    suspendingLoader { id -> userRepository.findById(id) }   // 挂起函数
}
```

- `suspendingLoader(loader)` / `suspendingLoader(context, loader)` 设置 spec 的 `asyncLoader`。每次加载在 `context` 中启动一个协程，默认是 `Dispatchers.Default`；loader 会调用阻塞代码时请传 `Dispatchers.IO`。
- 这些加载都在同一个 `SupervisorJob` 下运行，一次加载失败不影响其它加载。`context` 带 `Job` 时，它就是父 Job：取消它会取消进行中与之后的所有加载，这些加载对同步、异步与挂起的调用方都以 `SourceLoadCancelledException` 失败；加载开始之前 Job 就已取消时同样如此。
- 这个 `SupervisorJob` 永远不会完成。请给 `context` 一个长生命周期的 `Job`（例如应用级的），不要用结构化作用域的 `Job`，否则那个作用域永远结束不了。
- loader 请创建一次、反复使用。每次调用 `suspendingLoader` 或 `asAsyncCacheLoader` 都会在 `Job` 下新建一个 `SupervisorJob`，每次加载都新建会让子 Job 越积越多。
- `loadAll` 默认并发调用 `load`；数据源支持批量查询时请覆盖为一次批量查询。
- `versionedCacheSpec` 上的 `suspendingVersionedLoader(loader)` / `suspendingVersionedLoader(context, loader)` 接收 `SuspendingCacheLoader<K, Versioned<V?>>`（在版本 `n` 时已删除的 key 返回 `Versioned(null, n)`），设置 spec 的 `versionedAsyncLoader`（见[带版本的 loader](./versioned-cache.md#versioned-loaders)）；其它行为与 `suspendingLoader` 相同。`SuspendingVersionedCache` 视图有 `putIfNewer`、`invalidateIfNewer`、`evict`、`purge` 与 `getVersioned`。
- 想自己设置时，`asAsyncCacheLoader()` / `asAsyncCacheLoader(context)` 把 `SuspendingCacheLoader` 转为 `AsyncCacheLoader`。这样单独使用时没有[重入检查](#reentry)，因为它不知道自己属于哪个缓存。
- 缓存的 loader 跑在某个单线程 dispatcher 上时，不要在跑在同一个 dispatcher 上的协程里调用**同步** API：线程阻塞等待一次需要这个线程才能完成的加载，会死锁。这种情况请用挂起视图。

调用级 loader 与同步 API 的 `get(key, loader)` 一样，受同样的保护：

```kotlin
val user = users.get(id) { key -> userClient.fetch(key) }
```

调用级 loader **在这次加载之中**执行，带调用方协程上下文的元素，不带它的 `Job` 与 dispatcher：
- 直接调用，不另起协程：在调用方线程上开始，第一次挂起之后在 CoCache 的执行器上继续。阻塞调用请包在 `withContext(Dispatchers.IO)` 中。
- `ThreadContextElement` 随之传递，如追踪上下文，或经 `kotlinx-coroutines-slf4j` 的 `MDCContext` 传递的 MDC（普通 MDC 是 ThreadLocal，不会跟着协程换线程）。
- 加载可能被其它调用方共享，所以调用方被取消、调用方的作用域结束都不会取消它；运行时关闭会。

loader 的协程仍处于活动状态时，如果 loader 自己抛出 `CancellationException`（例如其中的 `withTimeout`），CoCache 会把它转成 `SourceLoadCancelledException`（`me.ahoo.cocache.core`）重新抛出，原始异常是它的 `cause`。因为加载可能被共享，否则每个调用方都会把它当作自己被取消（`launch` 会悄悄吞掉）。阻塞与异步数据源同样如此，见[异步 API](./async.md#exceptions)。

引擎的 `load-timeout` 对两种 loader 都生效。到期时调用方以 `SourceLoadTimeoutException` 失败，lease 被释放。spec 级 loader 的协程不会被取消，迟到的结果被丢弃，与 [`AsyncCacheLoader`](./async.md#timeouts) 相同；调用级 loader 在这次加载之中执行，会被取消。

## 取消 {#cancellation}

取消一个正在等待缓存的协程，只结束这次等待。被其它调用方共享的加载照常执行并填充缓存，与异步视图相同，之后的读取会命中。

## 异常 {#exceptions}

挂起方法抛出原始异常，即使在协程的调试模式下也是同一个实例：你的 loader 的异常（或 `SourceLoadCancelledException`，见[数据源](#loader)）、`CacheReadException`、`CacheWriteException`、`SourceLoadRejectedException`、`SourceLoadTimeoutException` 等，从不包装为 `CompletionException`。

协程的调试模式在 JVM 带 `-ea` 时自动开启，这时 kotlinx-coroutines 会做两件事：
- 可能抛出补全了堆栈的异常副本（原始异常是它的 `cause`）；
- 在线程名后加上 ` @coroutine#N`。

生产环境的 JVM 通常不带 `-ea`。测试中如果比较异常实例或线程名，请设置 `kotlinx.coroutines.debug=off`。

## 重入 {#reentry}

loader 读取同一缓存中自己正在加载的 key，会一直等待自己直到 `load-timeout`。CoCache 让它立即以 `IllegalStateException` 失败，与同步 API 一样。这项检查随 loader 的协程上下文传递，所以 loader 挂起后在别的线程上继续时同样有效。它覆盖经挂起视图的读取（`get`、`getAll`、`lookup`、`lookupAll`、`getVersioned`），其它 key 与其它缓存不受影响：对调用级 loader 指其它名字或其它实例的缓存，对 spec 级 loader 指其它名字的缓存（见下文）。key 按 `K` 的值比较，不按编码后的存储 key：两个不相等的 key 编码成同一个存储 key 时检查不到，要到 `load-timeout` 才失败。

- **调用级 loader** 按缓存实例匹配：另一个运行时上的同名缓存（如多节点测试）是另一个缓存。
- **spec 级 loader**（`suspendingLoader`）按缓存名匹配，因为声明时还不知道它属于哪个运行时。
- spec 级加载没有调用方，不继承调用方的检查：跨缓存的循环（A 的 loader 读 B，B 的 loader 又读 A 的同一个 key）要到 `load-timeout` 才失败。
- 单独用 `asAsyncCacheLoader` 设置的 loader 没有这项检查。
- 这项检查是在同步 API 自己的检查之上增加的：阻塞的 loader 在自己的线程上读取同一个 key，照样立即失败。离开协程的读取（经 `runBlocking`，或在别的线程上继续的 future）检查不到，要到 `load-timeout` 才失败。

## Spring {#spring}

starter 不会查找 `SuspendingCacheLoader` bean。请把缓存声明为 `CacheSpec` bean，在其中设置 `suspendingLoader { … }`；或者自己用 `asAsyncCacheLoader()` 转换后，作为 `AsyncCacheLoader` bean 暴露。starter 为每种挂起视图登记原生镜像提示。

## WebFlux {#webflux}

在 WebFlux 应用中，控制器方法可以写成调用挂起视图的 `suspend fun`。Spring 执行它们需要类路径上有 `kotlinx-coroutines-reactor`；`spring-boot-starter-webflux` 不带它，版本由 Spring Boot 管理。

```kotlin
@RestController
class ProfileController(
    @Qualifier("profile") profileCache: Cache<String, String>,
    private val profileRepository: ProfileRepository,
) {
    private val profiles = profileCache.suspending()   // 创建一次，保存在字段中复用

    @GetMapping("profile/{id}")
    suspend fun profile(@PathVariable id: String): String? = profiles.get(id)

    @PutMapping("profile/{id}")
    suspend fun update(@PathVariable id: String, @RequestParam nickname: String) {
        profileRepository.save(id, nickname)   // 先更新数据源
        profiles.invalidate(id)                // 再失效（在事务中时：提交之后）
    }
}
```

完整版本见 [`cocache-example`](https://github.com/Ahoo-Wang/CoCache/tree/main/example)：`CoroutineController` 用 `cache.suspending()` 读取、使用调用级 loader、写后失效；`UserCacheConfiguration` 中的 `profile` 缓存用 `suspendingLoader { … }` 声明数据源。
