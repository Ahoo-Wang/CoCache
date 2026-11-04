---
title: Async API
description: Read and write CoCache without blocking a thread — cache.async(), AsyncCacheLoader, threads and executors, timeouts, exceptions, cancellation, Spring Cache retrieve, and what changes for operators.
---

# Async API

Every cache has an asynchronous view. It is the same cache: the same L2, the same invalidation fences, the same in-flight loads and the same Redis leases. Only the way you wait changes. Reads and writes return a `CompletableFuture`, and no thread is parked while CoCache waits for Redis, for another instance's lease, or for your data source.

Use it on non-blocking stacks: WebFlux, R2DBC, WebClient, or anything that runs on an event loop. On a blocking stack, the synchronous API with virtual threads is simpler and just as fast.

## Getting the view {#view}

```kotlin
val users: Cache<String, User> = ...           // a spec bean or a @CoCache interface
val async: AsyncCache<String, User> = users.async()

async.get("42").thenAccept { user -> ... }
async.getAll(listOf("1", "2")).thenAccept { found -> ... }
async.put("42", user).thenCompose { async.get("42") }   // reads its own write
async.invalidate("42")
```

- `async()` is a member of every [cache type](./caches.md#types), and the views mirror the synchronous hierarchy: `Cache.async()` returns an `AsyncCache<K, V>`, `VersionedCache` an `AsyncVersionedCache`, `Store` an `AsyncStore`, `VersionedStore` an `AsyncVersionedStore`, and `SetStore` an `AsyncSetStore<K, E>`. All of them extend `AsyncReadableCache` (`get`, `getAll`, `lookup`, `lookupAll`). The methods have the same names and the same semantics as the synchronous ones, including `Lookup.Unknown` and `CacheReadException` for reads without a source when Redis fails.
- `async()` returns the same object every time, and `async().sync()` is the engine. Calling it through a `@CoCache` interface works too.
- Synchronous and asynchronous calls share one cache. A concurrent synchronous `get` and asynchronous `get` of the same key load it once.

## Async data sources {#loader}

A cache can load from an `AsyncCacheLoader` instead of a `CacheLoader`:

```kotlin
@Bean
fun userSpec(repo: ReactiveUserRepository): CacheSpec<String, User> = cacheSpec("user") {
    codec = jacksonCodec<User>(mapper)
    asyncLoader = AsyncCacheLoader { id -> repo.findById(id).toFuture() }
}
```

- `loadAsync(key)` returns a `CompletionStage`; completing it with `null` caches a negative entry. Override `loadAllAsync(keys)` for batch reads; the default calls `loadAsync` per key.
- A spec sets `loader` or `asyncLoader`, not both. CoCache adapts either way: a synchronous `get` waits for an async loader (bounded by `load-timeout`), and an asynchronous `get` runs a synchronous loader on the runtime's executor.
- With `@CoCache` interfaces, `loader = "beanName"` can name either kind. Without it, CoCache looks for a unique `CacheLoader<K, V>`, then a unique `AsyncCacheLoader<K, V>`. One of each is ambiguous and fails startup. A bean that implements both is used as a synchronous loader.
- `loadAsync` must return quickly and must not block. It is called on the calling thread or on a CoCache thread (the runtime's executor, or the observation thread when a `LoadObserver` is set), so it can't rely on the caller's thread-locals (MDC, security context). Propagate context through your reactive library, or through Micrometer context propagation.

## Threads and executors {#threads}

| Situation | Where it runs |
|-----------|---------------|
| L2 hit | Returns an already-completed future on the calling thread |
| A result that is already available (an in-memory backend, a loader that completed synchronously) | Continues on the calling thread |
| A Redis result or a loader future that completes later | Continues on the **runtime's executor**, never on the Lettuce I/O thread or the loader's own thread. Every such resume is a new task on the executor. |
| A synchronous loader called through the async view | Runs on the **runtime's executor** too |
| A load observed by a `LoadObserver` (tracing) | `observe` runs on a virtual thread of its own, one per observed load (`cocache-observe-N`), which waits until the load finishes. It doesn't use the runtime's executor. |
| Waiting for another instance's lease, or for a load permit | Holds no thread: the read is suspended and re-reads Redis after a short backoff, and a released permit goes to the next waiter in arrival order (sync and async callers share one queue) |

Your own dependent stages (`thenApply`, `whenComplete`) follow the JDK's rules: they run on the thread that completes the future, or on a thread that later calls `get()` or `join()` on it.

The runtime has one executor for the async and suspending views: `cocache.executor` names it (without Spring, `CacheRuntime.builder().executor(…)`). By default it starts one virtual thread per task, named `cocache-worker-N`. It runs blocking loaders as well as continuations, so it must tolerate blocking tasks: don't give it a queue or only a few threads. See [Configuration](./configuration.md#executors).

## Timeouts {#timeouts}

- Readers waiting for another caller's load (sync or async) wait at most until that load has been in flight for `load-timeout`, then fail with `SourceLoadTimeoutException`. A reader arriving after that starts a new load instead of joining the stuck one.
- An `AsyncCacheLoader` load may take at most `load-timeout` (30 seconds by default). Past it, the call fails with `SourceLoadTimeoutException`, the lease is released, and `cocache.load.timeouts` counts it. CoCache doesn't cancel your loader's future, and a late result is dropped. A `TimeoutException` your loader raises itself (for example from Reactor's `timeout`) is passed through unchanged.
- Each Redis command is bounded by `cocache.redis.command-timeout` (2 seconds by default) and fails with the same `RedisException` as the synchronous path. The timer is Lettuce's timer wheel, so an async timeout can fire up to one tick (100 ms by default) late.
- Synchronous loaders have no async timeout: a blocked thread can't be interrupted.

## Exceptions and cancellation {#exceptions}

- A failed call completes its future with the **original** exception: `CacheReadException`, `CacheWriteException`, `SourceLoadRejectedException`, `SourceLoadTimeoutException`, or whatever your loader threw. It is never wrapped in `CompletionException`. `join()` and `get()` still wrap it, as the JDK always does. `handle`, `exceptionally`, Reactor's `Mono.fromFuture` and Kotlin's `await()` all see the original.
- The one exception is a loader's own `CancellationException`: a `CacheLoader` that throws it, or an `AsyncCacheLoader` whose future completes with it (a cancelled HTTP request, a `withTimeout` inside it). It becomes `SourceLoadCancelledException`, with the original as its `cause`, for every caller that shares the load. Passed through as is, it would make each caller's future look cancelled.
- Async methods never throw synchronously. A failing key encoder or codec, a reentrant call, or an invalid version all come back as a failed future.
- Each call returns its own future. Cancelling it cancels only your wait: the shared load continues for the other callers and still writes Redis. Write ordering is unaffected, because the second invalidation step of a write runs whether or not you cancel.
- A loader that reads its own key fails fast with `IllegalStateException` when it does so before returning its future. If it does so after an I/O step, the call waits until `load-timeout`.

## Spring Cache {#spring-cache}

`retrieve(key)` and `retrieve(key, loader)` use the async view, so async and reactive `@Cacheable(sync = true)` methods load through the lease-protected path without holding a thread while they wait, and they need no executor of their own. The method body runs on the runtime's executor. See [Spring Cache](./spring-cache.md#executor).

## Kotlin coroutines {#coroutines}

Every cache also has a suspending view, `cache.suspending()`, and a spec can take a `SuspendingCacheLoader`. See [Kotlin Coroutines](./coroutines.md). `kotlinx.coroutines.future.await()` also works on every future the async view returns, and it rethrows the original exception.

## Operating it {#operations}

- **Redis**: no new commands, scripts, keys or channels. The async view runs the same Lua scripts as the synchronous API, so ACL rules and the wire format don't change.
- **Metrics**: the same counters as the synchronous API, plus `cocache.load.timeouts`. With tracing on, async loads produce the same `cocache.load` observation, and an engine timeout is tagged `outcome=timeout`. A custom `LoadObserver` sees async loads through the same `observe` method: CoCache calls it on an observation thread of its own, never on your thread, and the observation covers the whole load.
- **Large values**: the Redis backend reads and decodes a reply on the runtime's executor, never on Lettuce's I/O thread, but all of an instance's commands share one connection. A very large value delays the other replies on it (head-of-line blocking), for the synchronous API too. Keep values small, or split them.
- **Performance**: an async L2 hit costs one extra small allocation and runs at roughly 90% of a synchronous hit. A closed loop that waits for every call is somewhat slower than the synchronous API under load, because each result switches to the runtime's executor. The gain shows when one thread keeps many requests in flight: with 32 in flight, a thread completes about 12 times as many Redis reads or writes as one blocking thread.

## Custom backends {#backends}

The L1 SPI is suspending: a backend's store is an `L1Store`, and its invalidation channel publishes with a `suspend` function. Both views call the same store, and neither holds a thread while Redis answers. A Java backend implements `FutureL1Store` and `FutureInvalidationChannel` and adapts them with `FutureL1Store.adapt` and `FutureInvalidationChannel.adapt`. Prove a backend with `BackendProtocolSpec` and the engine suites; to run a suite through the async view too, override `access = CacheAccess.ASYNC`. The contract is in [Extending](../architecture/extending.md#l1-store), and the [changelog](./changelog.md#unreleased) lists the full SPI change.
