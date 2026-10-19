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

- `Cache.async()` returns an `AsyncCache<K, V>`, and `SetCache.async()` returns an `AsyncSetCache<K, E>`. The methods have the same names and the same semantics as the synchronous ones: `get`, `get(key, loader)`, `getAll`, `lookup`, `put`, `putAll`, `invalidate`, `invalidateAll`, and for sets `add`, `remove`, `clear`.
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
- A spec sets `loader` or `asyncLoader`, not both. CoCache adapts either way: a synchronous `get` waits for an async loader (bounded by `load-async-timeout`), and an asynchronous `get` runs a synchronous loader on the blocking executor.
- With `@CoCache` interfaces, `loader = "beanName"` can name either kind. Without it, CoCache looks for a unique `CacheLoader<K, V>`, then a unique `AsyncCacheLoader<K, V>`. One of each is ambiguous and fails startup. A bean that implements both is used as a synchronous loader.
- `loadAsync` must return quickly and must not block. It is called on the calling thread or on the async executor, so it can't see the caller's thread-locals (MDC, security context). Propagate context through your reactive library, or through Micrometer context propagation.

## Threads and executors {#threads}

| Situation | Where it runs |
|-----------|---------------|
| L2 hit | Returns an already-completed future on the calling thread |
| A result that is already available (an in-memory backend, a loader that completed synchronously) | Continues on the calling thread |
| A Redis result or a loader future that completes later | Continues on the **async executor**, never on the Lettuce I/O thread |
| A synchronous loader called through the async view | Runs on the **blocking executor** only |
| Waiting for another instance's lease, or for a load permit | Holds no thread: a timer re-reads Redis, and a released permit wakes the next waiter |

Your own dependent stages (`thenApply`, `whenComplete`) follow the JDK's rules: they run on the thread that completes the future, or on a thread that later calls `get()` or `join()` on it.

The async executor and the blocking executor are configurable. See [Configuration](./configuration.md#executors).

## Timeouts {#timeouts}

- An `AsyncCacheLoader` load may take at most `load-async-timeout` (30 seconds by default). Past it, the call fails with `SourceLoadTimeoutException`, the lease is released, and `cocache.load.timeouts` counts it. CoCache doesn't cancel your loader's future, and a late result is dropped. A `TimeoutException` your loader raises itself (for example from Reactor's `timeout`) is passed through unchanged.
- Each Redis command is bounded by `cocache.redis.command-timeout` (2 seconds by default) and fails with the same `RedisException` as the synchronous path.
- Synchronous loaders have no async timeout: a blocked thread can't be interrupted.

## Exceptions and cancellation {#exceptions}

- A failed call completes its future with the **original** exception: `CacheReadException`, `CacheWriteException`, `SourceLoadRejectedException`, `SourceLoadTimeoutException`, or whatever your loader threw. It is never wrapped in `CompletionException`. `join()` and `get()` still wrap it, as the JDK always does. `handle`, `exceptionally`, Reactor's `Mono.fromFuture` and Kotlin's `await()` all see the original.
- Async methods never throw synchronously. A failing key encoder or codec, a reentrant call, or an unsupported `SetCache` operation all come back as a failed future.
- Each call returns its own future. Cancelling it cancels only your wait: the shared load continues for the other callers and still writes Redis. Write ordering is unaffected, because the second invalidation step of a write runs whether or not you cancel.
- A loader that reads its own key fails fast with `IllegalStateException` when it does so before returning its future. If it does so after an I/O step, the call waits until `load-async-timeout`.

## Spring Cache {#spring-cache}

`retrieve(key)` and `retrieve(key, loader)` use the async view, so async and reactive `@Cacheable(sync = true)` methods load through the lease-protected path without holding a thread. The old `cocache.spring-cache.retrieve-executor` is deprecated and ignored. See [Spring Cache](./spring-cache.md#executor).

## Kotlin coroutines {#coroutines}

A `cocache-coroutines` module with suspending extensions is planned for 6.4. Until then, `kotlinx.coroutines.future.await()` works on every future the async view returns, and it rethrows the original exception.

## Operating it {#operations}

- **Redis**: no new commands, scripts, keys or channels. The async view runs the same Lua scripts as the synchronous API, so ACL rules and the wire format don't change.
- **Metrics**: the same counters as the synchronous API, plus `cocache.load.timeouts`. With tracing on, async loads produce the same `cocache.load` observation, and an engine timeout is tagged `outcome=timeout`. A custom `LoadObserver` must override `observeAsync` to see async loads; the default doesn't observe them.
- **Performance**: an async L2 hit costs one extra small allocation and runs at roughly 90% of a synchronous hit. A closed loop that waits for every call is somewhat slower than the synchronous API under load, because each result switches to the async executor. The gain shows when one thread keeps many requests in flight: with 32 in flight, a thread completes about 12 times as many Redis reads or writes as one blocking thread.

## Custom backends {#backends}

A backend doesn't have to change. A store that implements only the synchronous `RemoteStore` keeps working: the async view calls it on the blocking executor. To avoid those threads, implement `AsyncRemoteStore` and `AsyncInvalidationChannel`, and run `AsyncBackendProtocolSpec` and the other async TCK specs. See [Extending](../architecture/extending.md#async).
