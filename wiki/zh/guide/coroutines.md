---
title: Kotlin 协程
description: 经可选模块 cocache-coroutines 在 Kotlin 协程中挂起地使用 CoCache：cache.suspending()、SuspendingCacheLoader、loader 的上下文与 dispatcher、取消、异常与重入。
---

# Kotlin 协程

可选模块 `cocache-coroutines`（6.4+，尚未发布）在[异步 API](./async.md) 之上提供挂起视图。它就是同一个缓存：同一个 L2、同样的失效栅栏、同样的在途加载与 Redis lease。方法与同步 API 同名，等待时挂起而不是阻塞，抛出原始异常。

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

- `Cache.suspending()` 返回 `SuspendingCache`，`SetCache.suspending()` 返回带 `add`、`remove`、`clear` 的 `SuspendingSetCache`；`AsyncCache.suspending()` 同样可用。
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

- `suspendingLoader(context, loader)` 设置 spec 的 `asyncLoader`。每次加载在 `context` 中启动一个协程，默认是 `Dispatchers.Default`；loader 会调用阻塞代码时请传 `Dispatchers.IO`。
- `loadAll` 默认并发调用 `load`；数据源支持批量查询时请覆盖为一次批量查询。
- 想自己设置时，`asAsyncCacheLoader(context)` 把 `SuspendingCacheLoader` 转为 `AsyncCacheLoader`。

调用级 loader 与同步 API 的 `get(key, loader)` 一样，受同样的保护：

```kotlin
val user = users.get(id) { key -> userClient.fetch(key) }
```

调用级 loader 在**调用方的协程上下文去掉 `Job`** 之后的上下文中执行：
- 在调用方的 dispatcher 上执行（没有时为 `Dispatchers.Default`）；
- MDC、追踪上下文等 `ThreadContextElement` 随之传递；
- 调用方被取消不会取消这次加载，因为它可能被其它调用方共享。

引擎的 `load-async-timeout` 对两种 loader 都生效。到期时调用方以 `SourceLoadTimeoutException` 失败，lease 被释放。loader 的协程不会被取消，迟到的结果被丢弃，与 [`AsyncCacheLoader`](./async.md#timeouts) 相同。

## 取消 {#cancellation}

取消一个正在等待缓存的协程，只结束这次等待。被其它调用方共享的加载照常执行并填充缓存，与异步视图相同，之后的读取会命中。

## 异常 {#exceptions}

挂起方法抛出异步视图以之完成的那个异常：你的 loader 的异常、`CacheWriteException`、`SourceLoadRejectedException`、`SourceLoadTimeoutException` 等，从不包装为 `CompletionException`。

协程的调试模式在 JVM 带 `-ea` 时自动开启，这时 kotlinx-coroutines 会做两件事：
- 可能抛出补全了堆栈的异常副本（原始异常是它的 `cause`）；
- 在线程名后加上 ` @coroutine#N`。

生产环境的 JVM 通常不带 `-ea`。测试中如果比较异常实例或线程名，请设置 `kotlinx.coroutines.debug=off`。

## 重入 {#reentry}

loader 读取同一缓存中自己正在加载的 key，会一直等待自己直到 `load-async-timeout`。CoCache 让它立即以 `IllegalStateException` 失败，与同步 API 一样。这项检查随 loader 的协程上下文传递，所以 loader 挂起后在别的线程上继续时同样有效。它覆盖经挂起视图的读取（`get`、`getAll`、`lookup`），其它 key 与其它缓存不受影响。

## Spring {#spring}

starter 不会查找 `SuspendingCacheLoader` bean。请把缓存声明为 `CacheSpec` bean，在其中设置 `suspendingLoader { … }`；或者自己用 `asAsyncCacheLoader()` 转换后，作为 `AsyncCacheLoader` bean 暴露。模块在类路径上时，starter 为挂起视图登记原生镜像提示。
