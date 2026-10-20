---
title: Kotlin 协程
description: 经可选模块 cocache-coroutines 在 Kotlin 协程中挂起地使用 CoCache：cache.suspending()、SuspendingCacheLoader、loader 的上下文与 dispatcher、取消、异常与重入。
---

# Kotlin 协程

可选模块 `cocache-coroutines`（6.4+）在[异步 API](./async.md) 之上提供挂起视图。它就是同一个缓存：同一个 L2、同样的失效栅栏、同样的在途加载与 Redis lease。方法与同步 API 同名，等待时挂起而不是阻塞，抛出原始异常。

## 添加模块 {#install}

```kotlin
implementation("me.ahoo.cocache:cocache-coroutines")
```

版本由 `cocache-bom` 管理。本模块会带上 `kotlinx-coroutines-core`，其它 CoCache 模块都不依赖它。

## 拿到视图 {#view}

```kotlin
val users = userCache.suspending()            // SuspendingCache<String, User>

suspend fun profile(id: String): User? = users.get(id)

suspend fun rename(id: String, user: User) {
    users.put(id, user)
}
```

- `Cache.suspending()` 返回 `SuspendingCache`，`SetCache.suspending()` 返回带 `add`、`remove`、`clear` 的 `SuspendingSetCache`，它的 `get` 与 `SetCache.get` 一样不存在时返回空集合，从不返回 `null`；`AsyncCache.suspending()` 同样可用。
- 每个方法调用异步视图并等待结果。L2 命中立即返回、不挂起，因为异步视图返回的是已完成的 future。
- CoCache 等待 Redis、其它实例的 lease 或你的数据源时，不阻塞任何线程。

## 数据源 {#loader}

挂起的数据源是 `SuspendingCacheLoader`，在缓存的 spec 中设置：

```kotlin
cacheSpec<String, User>("user") {
    codec = jacksonCodec()
    suspendingLoader { id -> userRepository.findById(id) }   // 挂起函数
}
```

- `suspendingLoader(loader)` / `suspendingLoader(context, loader)` 设置 spec 的 `asyncLoader`。每次加载在 `context` 中启动一个协程，默认是 `Dispatchers.Default`；loader 会调用阻塞代码时请传 `Dispatchers.IO`。
- 这些加载都在同一个 `SupervisorJob` 下运行，一次加载失败不影响其它加载。`context` 带 `Job` 时，它就是父 Job：取消它会取消进行中与之后的所有加载，这些加载对同步、异步与挂起的调用方都以 `SourceLoadCancelledException` 失败；加载开始之前 Job 就已取消时同样如此。
- 这个 `SupervisorJob` 永远不会完成。请给 `context` 一个长生命周期的 `Job`（例如应用级的），不要用结构化作用域的 `Job`，否则那个作用域永远结束不了。
- loader 请创建一次、反复使用。每次调用 `suspendingLoader` 或 `asAsyncCacheLoader` 都会在 `Job` 下新建一个 `SupervisorJob`，每次加载都新建会让子 Job 越积越多。
- `loadAll` 默认并发调用 `load`；数据源支持批量查询时请覆盖为一次批量查询。
- 想自己设置时，`asAsyncCacheLoader()` / `asAsyncCacheLoader(context)` 把 `SuspendingCacheLoader` 转为 `AsyncCacheLoader`。这样单独使用时没有[重入检查](#reentry)，因为它不知道自己属于哪个缓存。
- 缓存的 loader 跑在某个单线程 dispatcher 上时，不要在跑在同一个 dispatcher 上的协程里调用**同步** API：线程阻塞等待一次需要这个线程才能完成的加载，会死锁。这种情况请用挂起视图。

调用级 loader 与同步 API 的 `get(key, loader)` 一样，受同样的保护：

```kotlin
val user = users.get(id) { key -> userClient.fetch(key) }
```

调用级 loader 在**调用方的协程上下文去掉 `Job`** 之后的上下文中执行：
- 在调用方的 dispatcher 上执行；没有 dispatcher，或调用方跑在 `runBlocking` 的事件循环上时，改在 `Dispatchers.Default` 上执行。
- `ThreadContextElement` 随之传递，如追踪上下文，或经 `kotlinx-coroutines-slf4j` 的 `MDCContext` 传递的 MDC（普通 MDC 是 ThreadLocal，不会跟着协程换线程）。
- 它脱离结构化并发：加载可能被其它调用方共享，所以调用方被取消、调用方的作用域结束，甚至运行时关闭，都不会取消它。加载协程可能比它们都活得更久，直到数据源返回。

loader 的协程仍处于活动状态时，如果 loader 自己抛出 `CancellationException`（例如其中的 `withTimeout`），CoCache 会把它转成 `SourceLoadCancelledException` 重新抛出，原始异常是它的 `cause`。因为加载可能被共享，否则每个调用方都会把它当作自己被取消（`launch` 会悄悄吞掉）。

引擎的 `load-async-timeout` 对两种 loader 都生效。到期时调用方以 `SourceLoadTimeoutException` 失败，lease 被释放。loader 的协程不会被取消，迟到的结果被丢弃，与 [`AsyncCacheLoader`](./async.md#timeouts) 相同。

## 取消 {#cancellation}

取消一个正在等待缓存的协程，只结束这次等待。被其它调用方共享的加载照常执行并填充缓存，与异步视图相同，之后的读取会命中。

## 异常 {#exceptions}

挂起方法抛出异步视图以之完成的那个异常：你的 loader 的异常（或 `SourceLoadCancelledException`，见[数据源](#loader)）、`CacheWriteException`、`SourceLoadRejectedException`、`SourceLoadTimeoutException` 等，从不包装为 `CompletionException`。

协程的调试模式在 JVM 带 `-ea` 时自动开启，这时 kotlinx-coroutines 会做两件事：
- 可能抛出补全了堆栈的异常副本（原始异常是它的 `cause`）；
- 在线程名后加上 ` @coroutine#N`。

生产环境的 JVM 通常不带 `-ea`。测试中如果比较异常实例或线程名，请设置 `kotlinx.coroutines.debug=off`。

## 重入 {#reentry}

loader 读取同一缓存中自己正在加载的 key，会一直等待自己直到 `load-async-timeout`。CoCache 让它立即以 `IllegalStateException` 失败，与同步 API 一样。这项检查随 loader 的协程上下文传递，所以 loader 挂起后在别的线程上继续时同样有效。它覆盖经挂起视图的读取（`get`、`getAll`、`lookup`），其它 key 与其它缓存不受影响：对调用级 loader 指其它名字或其它实例的缓存，对 spec 级 loader 指其它名字的缓存（见下文）。key 按 `K` 的值比较，不按编码后的存储 key：两个不相等的 key 编码成同一个存储 key 时检查不到，要到 `load-async-timeout` 才失败。

- **调用级 loader** 按缓存实例匹配：另一个运行时上的同名缓存（如多节点测试）是另一个缓存。
- **spec 级 loader**（`suspendingLoader`）按缓存名匹配，因为声明时还不知道它属于哪个运行时。
- spec 级加载没有调用方，不继承调用方的检查：跨缓存的循环（A 的 loader 读 B，B 的 loader 又读 A 的同一个 key）要到 `load-async-timeout` 才失败。
- 单独用 `asAsyncCacheLoader` 设置的 loader 没有这项检查。

## Spring {#spring}

starter 不会查找 `SuspendingCacheLoader` bean。请把缓存声明为 `CacheSpec` bean，在其中设置 `suspendingLoader { … }`；或者自己用 `asAsyncCacheLoader()` 转换后，作为 `AsyncCacheLoader` bean 暴露。模块在类路径上时，starter 为挂起视图登记原生镜像提示。
