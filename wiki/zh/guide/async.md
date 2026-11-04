---
title: 异步 API
description: 不阻塞线程地读写 CoCache：cache.async()、AsyncCacheLoader、线程与执行器、超时、异常、取消、Spring Cache retrieve，以及对运维的影响。
---

# 异步 API

每个缓存都有一个异步视图。它就是同一个缓存：同一个 L2、同一组失效栅栏、同一批在途加载、同一套 Redis lease，只是等待的方式不同。读写返回 `CompletableFuture`，CoCache 在等待 Redis、等待其它实例的 lease 或等待你的数据源时，不挂起任何线程。

在非阻塞的技术栈上使用它：WebFlux、R2DBC、WebClient，或任何运行在事件循环上的代码。在阻塞的技术栈上，同步 API 配合虚拟线程更简单，也一样快。

## 拿到视图 {#view}

```kotlin
val users: Cache<String, User> = ...           // spec bean 或 @CoCache 接口
val async: AsyncCache<String, User> = users.async()

async.get("42").thenAccept { user -> ... }
async.getAll(listOf("1", "2")).thenAccept { found -> ... }
async.put("42", user).thenCompose { async.get("42") }   // 读到自己的写入
async.invalidate("42")
```

- `async()` 是每种[缓存类型](./caches.md#types)的成员，视图与同步的层次同构：`Cache.async()` 返回 `AsyncCache<K, V>`，`VersionedCache` 返回 `AsyncVersionedCache`，`Store` 返回 `AsyncStore`，`VersionedStore` 返回 `AsyncVersionedStore`，`SetStore` 返回 `AsyncSetStore<K, E>`。它们都继承 `AsyncReadableCache`（`get`、`getAll`、`lookup`、`lookupAll`）。方法与同步 API 同名、语义相同，包括 Redis 故障时没有数据源的读取得到的 `Lookup.Unknown` 与 `CacheReadException`。
- `async()` 每次返回同一个对象，`async().sync()` 就是引擎本身。经 `@CoCache` 接口调用同样可以。
- 同步调用与异步调用共用一个缓存：同一个 key 并发的同步 `get` 与异步 `get` 只加载一次。

## 异步数据源 {#loader}

缓存可以用 `AsyncCacheLoader` 代替 `CacheLoader` 加载：

```kotlin
@Bean
fun userSpec(repo: ReactiveUserRepository): CacheSpec<String, User> = cacheSpec("user") {
    codec = jacksonCodec<User>(mapper)
    asyncLoader = AsyncCacheLoader { id -> repo.findById(id).toFuture() }
}
```

- `loadAsync(key)` 返回 `CompletionStage`；以 `null` 完成时写入负缓存。批量读取请覆盖 `loadAllAsync(keys)`，默认对每个 key 调用 `loadAsync`。
- spec 设置 `loader` 或 `asyncLoader` 之一，不能都设置。CoCache 在两个方向上适配：同步 `get` 等待异步 loader（受 `load-timeout` 约束），异步 `get` 在阻塞执行器上运行同步 loader。
- `@CoCache` 接口的 `loader = "beanName"` 可以指向任意一种。不指定时，CoCache 先找唯一的 `CacheLoader<K, V>`，再找唯一的 `AsyncCacheLoader<K, V>`；两种各有一个时存在歧义，启动失败。同时实现两者的 bean 按同步 loader 使用。
- `loadAsync` 必须很快返回，不能阻塞。它在调用方线程或异步执行器上被调用，看不到调用方的 ThreadLocal（MDC、安全上下文）。请经响应式库或 Micrometer context propagation 传递上下文。

## 线程与执行器 {#threads}

| 情形 | 在哪里执行 |
|------|------------|
| L2 命中 | 在调用方线程上返回已完成的 future |
| 结果已经可得（内存后端、同步完成的 loader） | 在调用方线程上继续 |
| 之后才完成的 Redis 结果或 loader future | 在**异步执行器**上继续，从不在 Lettuce 的 I/O 线程上 |
| 经异步视图调用的同步 loader | 只在**阻塞执行器**上执行 |
| 等待其它实例的 lease，或等待回源许可 | 不占线程：读取挂起，短暂退避后重读 Redis；许可释放时按到达顺序交给下一个等待者（同步与异步调用方排在同一个队列里） |

你自己挂上的依赖阶段（`thenApply`、`whenComplete`）遵循 JDK 的规则：在完成 future 的线程上执行，或在随后对它调用 `get()` / `join()` 的线程上执行。

异步执行器与阻塞执行器都可以配置，见[配置参考](./configuration.md#executors)。

## 超时 {#timeouts}

- 等待别的调用方加载的读取（同步与异步）最多等到这次加载进行了 `load-timeout`，然后以 `SourceLoadTimeoutException` 失败；在那之后到来的读取开始新的加载，不再合并到卡住的那次。
- 一次 `AsyncCacheLoader` 加载最多 `load-timeout`（默认 30 秒）。超时后调用以 `SourceLoadTimeoutException` 失败，释放 lease，并计入 `cocache.load.timeouts`。CoCache 不取消你的 loader 的 future，迟到的结果被丢弃。你的 loader 自己抛出的 `TimeoutException`（例如来自 Reactor 的 `timeout`）原样传递。
- 每条 Redis 命令受 `cocache.redis.command-timeout`（默认 2 秒）约束，失败时与同步路径一样是 `RedisException`。计时器是 Lettuce 的时间轮，异步超时最多晚一个刻度（默认 100 毫秒）触发。
- 同步 loader 没有异步超时：阻塞的线程无法被打断。

## 异常与取消 {#exceptions}

- 失败的调用以**原始**异常完成 future：`CacheReadException`、`CacheWriteException`、`SourceLoadRejectedException`、`SourceLoadTimeoutException`，或你的 loader 抛出的异常，从不包装为 `CompletionException`。`join()` 与 `get()` 仍会按 JDK 的规则包装；`handle`、`exceptionally`、Reactor 的 `Mono.fromFuture` 与 Kotlin 的 `await()` 都拿到原始异常。
- 唯一的例外是 loader 自己的 `CancellationException`：`CacheLoader` 抛出它，或 `AsyncCacheLoader` 的 future 以它完成（HTTP 请求被取消、其中的 `withTimeout`）。共享这次加载的每个调用方都收到 `SourceLoadCancelledException`，原始异常是它的 `cause`。原样传出的话，每个调用方的 future 都会看起来像是被取消了。
- 异步方法从不同步抛出。key 编码器或编解码器出错、重入调用、非法的版本，都以失败的 future 返回。
- 每次调用返回自己的 future。取消它只取消你的等待：共享的加载为其它调用方继续进行，照样写入 Redis。写入的顺序不受影响：写入的第二步失效无论是否取消都会执行。
- 读取自己 key 的 loader：如果在返回 future 之前这样做，快速失败（`IllegalStateException`）；如果在一次 I/O 之后这样做，调用会一直等到 `load-timeout`。

## Spring Cache {#spring-cache}

`retrieve(key)` 与 `retrieve(key, loader)` 经异步视图，所以异步与响应式的 `@Cacheable(sync = true)` 方法经受 lease 保护的路径回源，等待时不占用线程，也不需要单独的执行器；方法体在阻塞执行器上执行。见 [Spring Cache](./spring-cache.md#executor)。

## Kotlin 协程 {#coroutines}

每个缓存还有挂起视图 `cache.suspending()`，spec 可以接受 `SuspendingCacheLoader`，见 [Kotlin 协程](./coroutines.md)。`kotlinx.coroutines.future.await()` 同样适用于异步视图返回的每个 future，并抛出原始异常。

## 运维 {#operations}

- **Redis**：没有新的命令、脚本、key 或频道。异步视图执行的 Lua 脚本与同步 API 相同，ACL 规则与线格式都不变。
- **指标**：与同步 API 相同的计数，另加 `cocache.load.timeouts`。开启追踪时，异步加载产生同样的 `cocache.load` 观测，引擎超时标记为 `outcome=timeout`。自定义的 `LoadObserver` 需要覆盖 `observeAsync` 才能看到异步加载，默认实现不观测。
- **大值**：Redis 后端在异步执行器上读取并解码回复，从不在 Lettuce 的 I/O 线程上，但一个实例的所有命令共用一个连接。很大的值会推迟这个连接上其它回复（队头阻塞），同步 API 同样如此。请保持值较小，或拆分。
- **性能**：一次异步 L2 命中多一次很小的分配，约为同步命中的 90%。每次都等待结果的闭环调用在负载下比同步 API 略慢，因为每个结果都要切换到异步执行器。收益出现在一个线程同时保持多个请求时：32 个在途请求下，一个线程完成的 Redis 读写约为一个阻塞线程的 12 倍。

## 自定义后端 {#backends}

L1 SPI 是挂起的：后端的存储是 `L1Store`，失效通道以 `suspend` 函数发布。两种视图调用同一个存储，等待 Redis 应答时都不占线程。Java 后端实现 `FutureL1Store` 与 `FutureInvalidationChannel`，经 `FutureL1Store.adapt` 与 `FutureInvalidationChannel.adapt` 适配。用 `BackendProtocolSpec` 与各引擎套件检验后端；还想经异步视图运行某个套件时，覆盖 `access = CacheAccess.ASYNC`。约定见[扩展](../architecture/extending.md#l1-store)，完整的 SPI 变化见[更新日志](./changelog.md#unreleased)。
