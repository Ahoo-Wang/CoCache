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

- `suspendingLoader(context, loader)` sets the spec's `asyncLoader`. Each load starts a coroutine in `context`, which defaults to `Dispatchers.Default`. Pass `Dispatchers.IO` if the loader calls blocking code.
- `loadAll` defaults to calling `load` concurrently. Override it with one batch query when the source has one.
- `asAsyncCacheLoader(context)` turns a `SuspendingCacheLoader` into an `AsyncCacheLoader` if you want to set it yourself.

A per-call loader works like `get(key, loader)` on the synchronous API, and it gets the same protection:

```kotlin
val user = users.get(id) { key -> userClient.fetch(key) }
```

The per-call loader runs in the **caller's coroutine context without its `Job`**:
- It runs on the caller's dispatcher (`Dispatchers.Default` if there is none).
- `ThreadContextElement`s such as MDC or tracing context go with it.
- Cancelling the caller doesn't cancel the load, because other callers may be sharing it.

The engine's `load-async-timeout` applies to both kinds of loader. When it fires, the caller fails with `SourceLoadTimeoutException` and the lease is released. The loader's coroutine is not cancelled; its late result is discarded. This works the same as for an [`AsyncCacheLoader`](./async.md#timeouts).

## Cancellation {#cancellation}

Cancelling a coroutine that is waiting on the cache ends only that wait. A load that other callers share keeps running and fills the cache, as with the async view. The next read hits.

## Exceptions {#exceptions}

The suspending methods throw the exception the async view completed with: your loader's exception, `CacheWriteException`, `SourceLoadRejectedException`, `SourceLoadTimeoutException` and so on. They are never wrapped in `CompletionException`.

In coroutine debug mode, which is on whenever the JVM runs with `-ea`, kotlinx-coroutines does two things:
- it may rethrow a copy of the exception with a recovered stack trace (the original is its `cause`);
- it adds ` @coroutine#N` to thread names.

Production JVMs normally don't run with `-ea`. If your tests compare exception instances or thread names, set `kotlinx.coroutines.debug=off`.

## Re-entry {#reentry}

A loader that reads the key it is loading, from the same cache, would wait for itself until `load-async-timeout`. Instead, it fails at once with `IllegalStateException`, as it does on the synchronous API. The check goes with the loader's coroutine context, so it holds after the loader suspends and resumes on another thread. It covers reads through the suspending view (`get`, `getAll`, `lookup`). Other keys and other caches are not affected.

## Spring {#spring}

The starter doesn't look for `SuspendingCacheLoader` beans. Declare the cache as a `CacheSpec` bean and set `suspendingLoader { … }` in it, or adapt the loader yourself with `asAsyncCacheLoader()` and expose it as an `AsyncCacheLoader` bean. When the module is on the classpath, the starter registers native-image hints for the suspending views.
