---
title: Kotlin Coroutines
description: Suspending CoCache from Kotlin coroutines with the optional cocache-coroutines module — cache.suspending(), SuspendingCacheLoader, loader context and dispatchers, cancellation, exceptions, and re-entry.
---

# Kotlin Coroutines

The optional `cocache-coroutines` module (6.4+, not yet released) adds a suspending view over the [async API](./async.md). It is the same cache: the same L2, the same invalidation fences, the same in-flight loads and the same Redis leases. Methods have the same names as the synchronous API, they suspend instead of blocking, and they throw the original exception.

## Adding the module {#install}

```kotlin
implementation("me.ahoo.cocache:cocache-coroutines")
```

The version comes from `cocache-bom`. The module brings `kotlinx-coroutines-core` with it. No other CoCache module depends on it.

## Getting the view {#view}

```kotlin
val users = userCache.suspending()            // SuspendingCache<String, User>

suspend fun profile(id: String): User? = users.get(id)

suspend fun rename(id: String, user: User) {
    users.put(id, user)
}
```

- `Cache.suspending()` returns a `SuspendingCache`, and `SetCache.suspending()` returns a `SuspendingSetCache` with `add`, `remove` and `clear`. `AsyncCache.suspending()` works too.
- Each method calls the async view and awaits the result. An L2 hit returns at once without suspending, because the async view returns an already-completed future.
- No thread is blocked while CoCache waits for Redis, for another instance's lease, or for your data source.

## Data sources {#loader}

A suspending data source is a `SuspendingCacheLoader`. Set it in the cache's spec:

```kotlin
cacheSpec<String, User>("user") {
    codec = jacksonCodec()
    suspendingLoader { id -> userRepository.findById(id) }   // a suspend function
}
```

- `suspendingLoader(loader)` / `suspendingLoader(context, loader)` set the spec's `asyncLoader`. Each load starts a coroutine in `context`, which defaults to `Dispatchers.Default`. Pass `Dispatchers.IO` if the loader calls blocking code.
- The loads run under one `SupervisorJob`, so one failed load doesn't affect the others. If `context` has a `Job`, it is the parent: cancelling it cancels running loads and every later one.
- `loadAll` defaults to calling `load` concurrently. Override it with one batch query when the source has one.
- `asAsyncCacheLoader()` / `asAsyncCacheLoader(context)` turn a `SuspendingCacheLoader` into an `AsyncCacheLoader` if you want to set it yourself. Used that way, it has no [re-entry check](#reentry), because it doesn't know which cache it belongs to.
- Don't call the **synchronous** API from a coroutine running on a single-thread dispatcher, if the cache's loader runs on that same dispatcher. The thread blocks waiting for a load that needs that thread, so it deadlocks. Use the suspending view there.

A per-call loader works like `get(key, loader)` on the synchronous API, and it gets the same protection:

```kotlin
val user = users.get(id) { key -> userClient.fetch(key) }
```

The per-call loader runs in the **caller's coroutine context without its `Job`**:
- It runs on the caller's dispatcher. It runs on `Dispatchers.Default` instead if there is no dispatcher, or if the caller runs on a `runBlocking` event loop.
- `ThreadContextElement`s go with it, such as a tracing context, or MDC through `MDCContext` from `kotlinx-coroutines-slf4j` (plain MDC is a thread-local and doesn't follow a coroutine to another thread).
- It is outside structured concurrency. Other callers may share the load, so cancelling the caller, ending the caller's scope, or even closing the runtime doesn't cancel it. The load's coroutine can outlive them all, until the data source returns.

If a loader itself throws `CancellationException` while its coroutine is still active (for example from a `withTimeout` inside it), CoCache rethrows it as `SourceLoadCancelledException`, whose `cause` is the original. A load may be shared, and every caller would otherwise take the exception as its own cancellation (`launch` swallows it silently).

The engine's `load-async-timeout` applies to both kinds of loader. When it fires, the caller fails with `SourceLoadTimeoutException` and the lease is released. The loader's coroutine is not cancelled; its late result is discarded. This works the same as for an [`AsyncCacheLoader`](./async.md#timeouts).

## Cancellation {#cancellation}

Cancelling a coroutine that is waiting on the cache ends only that wait. A load that other callers share keeps running and fills the cache, as with the async view. The next read hits.

## Exceptions {#exceptions}

The suspending methods throw the exception the async view completed with: your loader's exception (or `SourceLoadCancelledException`, see [Data sources](#loader)), `CacheWriteException`, `SourceLoadRejectedException`, `SourceLoadTimeoutException` and so on. They are never wrapped in `CompletionException`.

In coroutine debug mode, which is on whenever the JVM runs with `-ea`, kotlinx-coroutines does two things:
- it may rethrow a copy of the exception with a recovered stack trace (the original is its `cause`);
- it adds ` @coroutine#N` to thread names.

Production JVMs normally don't run with `-ea`. If your tests compare exception instances or thread names, set `kotlinx.coroutines.debug=off`.

## Re-entry {#reentry}

A loader that reads the key it is loading, from the same cache, would wait for itself until `load-async-timeout`. Instead, it fails at once with `IllegalStateException`, as it does on the synchronous API. The check goes with the loader's coroutine context, so it holds after the loader suspends and resumes on another thread. It covers reads through the suspending view (`get`, `getAll`, `lookup`). Other keys, and caches with other names, are not affected.

- **Per-call loaders** are matched by cache instance: a same-named cache on another runtime, as in a multi-node test, is a different cache.
- **Spec-level loaders** (`suspendingLoader`) are matched by cache name, because the spec doesn't know its runtime yet.
- A spec-level load has no caller, so it doesn't inherit the caller's check. A cycle across caches, such as A's loader reading B and B's loader reading the same key of A, fails only at `load-async-timeout`.
- A loader set with `asAsyncCacheLoader` on its own has no check.

## Spring {#spring}

The starter doesn't look for `SuspendingCacheLoader` beans. Declare the cache as a `CacheSpec` bean and set `suspendingLoader { … }` in it, or adapt the loader yourself with `asAsyncCacheLoader()` and expose it as an `AsyncCacheLoader` bean. When the module is on the classpath, the starter registers native-image hints for the suspending views.
