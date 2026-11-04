---
title: Kotlin Coroutines
description: Suspending CoCache from Kotlin coroutines — cache.suspending() on every cache type, SuspendingCacheLoader, loader context and dispatchers, cancellation, exceptions, re-entry, and WebFlux.
---

# Kotlin Coroutines

Every cache has a suspending view next to its [async view](./async.md). It is the same cache: the same L2, the same invalidation fences, the same in-flight loads and the same Redis leases. Methods have the same names as the synchronous API, they suspend instead of blocking, and they throw the original exception.

## Dependencies {#install}

No extra module. The suspending view interfaces are in `cocache-api` (they need only the Kotlin standard library), and their implementation, `SuspendingCacheLoader` and the `suspendingLoader` builders are in `cocache-core`, which brings `kotlinx-coroutines-core` with it (its version comes from Spring Boot's dependency management).

## Getting the view {#view}

```kotlin
val users = userCache.suspending()            // SuspendingCache<String, User>

suspend fun profile(id: String): User? = users.get(id)

suspend fun rename(id: String, user: User) {
    users.put(id, user)
}
```

- `suspending()` is a member of every [cache type](./caches.md#types), and the views mirror the synchronous hierarchy: `Cache.suspending()` returns a `SuspendingCache`, `VersionedCache` a `SuspendingVersionedCache`, `Store` a `SuspendingStore`, `VersionedStore` a `SuspendingVersionedStore`, and `SetStore` a `SuspendingSetStore`, whose `get` returns an empty set, never `null`, like `SetStore.get`. All of them extend `SuspendingReadableCache` (`get`, `getAll`, `lookup`, `lookupAll`), and `async()` on a suspending view returns the matching async view.
- `suspending()` returns the same object every time, so you can call it where you use it. Calling it through a `@CoCache` interface works too.
- Each method calls the cache core directly. An L2 hit returns at once: it doesn't suspend, switch dispatchers or allocate.
- A miss, a write or a conditional read runs on CoCache's own dispatcher. It starts on your thread and, from its first suspension, continues on CoCache's executor, so your coroutine resumes from a CoCache thread, never from a Redis client thread. With `Dispatchers.Unconfined`, the default for Spring WebFlux handlers, your code after the call therefore runs on a CoCache executor thread, not on Lettuce's event loop. Context elements of your coroutine, such as a tracing context or `MDCContext`, go along; its `Job` and dispatcher don't.
- `lookup` returns `Lookup.Unknown` when Redis fails and the read has no source, and `get` throws `CacheReadException`, as on the [synchronous API](./caches.md#unknown).
- No thread is blocked while CoCache waits for Redis, for another instance's lease, or for your data source.

## Data sources {#loader}

A suspending data source is a `SuspendingCacheLoader` (`me.ahoo.cocache.core`). Set it in the cache's spec:

```kotlin
cacheSpec<String, User>("user") {
    codec = jacksonCodec<User>(mapper)
    suspendingLoader { id -> userRepository.findById(id) }   // a suspend function
}
```

- `suspendingLoader(loader)` / `suspendingLoader(context, loader)` set the spec's `asyncLoader`. Each load starts a coroutine in `context`, which defaults to `Dispatchers.Default`. Pass `Dispatchers.IO` if the loader calls blocking code.
- The loads run under one `SupervisorJob`, so one failed load doesn't affect the others. If `context` has a `Job`, it is the parent: cancelling it cancels running loads and every later one, and they fail with `SourceLoadCancelledException` for every caller, synchronous, async or suspending. This holds even when the `Job` was already cancelled before the load started.
- That `SupervisorJob` never completes. Give `context` a long-lived `Job`, such as an application-wide one, not the `Job` of a structured scope: that scope would never finish.
- Create the loader once and reuse it. Each `suspendingLoader` or `asAsyncCacheLoader` call creates a new `SupervisorJob` under the `Job`, so creating one per load piles up child jobs.
- `loadAll` defaults to calling `load` concurrently. Override it with one batch query when the source has one.
- `suspendingVersionedLoader(loader)` / `suspendingVersionedLoader(context, loader)` on a `versionedCacheSpec` take a `SuspendingCacheLoader<K, Versioned<V?>>` (return `Versioned(null, n)` for a key deleted at version `n`) and set the spec's `versionedAsyncLoader` ([versioned loaders](./versioned-cache.md#versioned-loaders)). They behave like `suspendingLoader` in every other way. The `SuspendingVersionedCache` view has `putIfNewer`, `invalidateIfNewer`, `evict`, `purge` and `getVersioned`.
- `asAsyncCacheLoader()` / `asAsyncCacheLoader(context)` turn a `SuspendingCacheLoader` into an `AsyncCacheLoader` if you want to set it yourself. Used that way, it has no [re-entry check](#reentry), because it doesn't know which cache it belongs to.
- Don't call the **synchronous** API from a coroutine running on a single-thread dispatcher, if the cache's loader runs on that same dispatcher. The thread blocks waiting for a load that needs that thread, so it deadlocks. Use the suspending view there.

A per-call loader works like `get(key, loader)` on a synchronous `Cache`, and it gets the same protection:

```kotlin
val user = users.get(id) { key -> userClient.fetch(key) }
```

The per-call loader runs **inside the load**, with the caller's context elements but without its `Job` or dispatcher:
- It is called directly, without a coroutine of its own. It starts on the caller's thread and, from its first suspension, continues on CoCache's executor. Wrap blocking calls in `withContext(Dispatchers.IO)`.
- `ThreadContextElement`s go with it, such as a tracing context, or MDC through `MDCContext` from `kotlinx-coroutines-slf4j` (plain MDC is a thread-local and doesn't follow a coroutine to another thread).
- Other callers may share the load, so cancelling the caller or ending the caller's scope doesn't cancel it. Closing the runtime does.

If a loader itself throws `CancellationException` while its coroutine is still active (for example from a `withTimeout` inside it), CoCache rethrows it as `SourceLoadCancelledException` (`me.ahoo.cocache.core`), whose `cause` is the original. A load may be shared, and every caller would otherwise take the exception as its own cancellation (`launch` swallows it silently). Blocking and async loaders get the same treatment, see [Async API](./async.md#exceptions).

The engine's `load-timeout` applies to both kinds of loader. When it fires, the callers fail with `SourceLoadTimeoutException` and the lease is released. A spec-level loader's coroutine is not cancelled and its late result is discarded, the same as for an [`AsyncCacheLoader`](./async.md#timeouts). A per-call loader runs inside the load, so it is cancelled.

## Cancellation {#cancellation}

Cancelling a coroutine that is waiting on the cache ends only that wait. A load that other callers share keeps running and fills the cache, as with the async view. The next read hits.

## Exceptions {#exceptions}

The suspending methods throw the original exception, the same instance even in coroutine debug mode: your loader's exception (or `SourceLoadCancelledException`, see [Data sources](#loader)), `CacheReadException`, `CacheWriteException`, `SourceLoadRejectedException`, `SourceLoadTimeoutException` and so on. They are never wrapped in `CompletionException`.

In coroutine debug mode, which is on whenever the JVM runs with `-ea`, kotlinx-coroutines does two things:
- it may rethrow a copy of the exception with a recovered stack trace (the original is its `cause`);
- it adds ` @coroutine#N` to thread names.

Production JVMs normally don't run with `-ea`. If your tests compare exception instances or thread names, set `kotlinx.coroutines.debug=off`.

## Re-entry {#reentry}

A loader that reads the key it is loading, from the same cache, would wait for itself until `load-timeout`. Instead, it fails at once with `IllegalStateException`, as it does on the synchronous API. The check goes with the loader's coroutine context, so it holds after the loader suspends and resumes on another thread. It covers reads through the suspending view (`get`, `getAll`, `lookup`, `lookupAll`, `getVersioned`). Other keys, and other caches, are not affected: for per-call loaders that means caches with other names or other instances, for spec-level loaders caches with other names (see below). Keys are compared as `K` values, not as encoded storage keys: two unequal keys that encode to the same storage key aren't caught, and fail only at `load-timeout`.

- **Per-call loaders** are matched by cache instance: a same-named cache on another runtime, as in a multi-node test, is a different cache.
- **Spec-level loaders** (`suspendingLoader`) are matched by cache name, because the spec doesn't know its runtime yet.
- A spec-level load has no caller, so it doesn't inherit the caller's check. A cycle across caches, such as A's loader reading B and B's loader reading the same key of A, fails only at `load-timeout`.
- A loader set with `asAsyncCacheLoader` on its own has no check.
- The check adds to the synchronous API's own: a blocking loader that reads the same key, on its own thread, still fails at once. A read that leaves the coroutine, through `runBlocking` or a future that continues on another thread, isn't caught and fails at `load-timeout`.

## Spring {#spring}

The starter doesn't look for `SuspendingCacheLoader` beans. Declare the cache as a `CacheSpec` bean and set `suspendingLoader { … }` in it, or adapt the loader yourself with `asAsyncCacheLoader()` and expose it as an `AsyncCacheLoader` bean. The starter registers native-image hints for every suspending view.

## WebFlux {#webflux}

In a WebFlux application, controller methods can be `suspend fun`s that call the suspending view. Spring needs `kotlinx-coroutines-reactor` on the classpath to run them; `spring-boot-starter-webflux` doesn't bring it, and its version comes from Spring Boot.

```kotlin
@RestController
class ProfileController(
    @Qualifier("profile") profileCache: Cache<String, String>,
    private val profileRepository: ProfileRepository,
) {
    private val profiles = profileCache.suspending()   // create once, keep in a field

    @GetMapping("profile/{id}")
    suspend fun profile(@PathVariable id: String): String? = profiles.get(id)

    @PutMapping("profile/{id}")
    suspend fun update(@PathVariable id: String, @RequestParam nickname: String) {
        profileRepository.save(id, nickname)   // update the source first
        profiles.invalidate(id)                // then invalidate (inside a transaction: after commit)
    }
}
```

The [`cocache-example`](https://github.com/Ahoo-Wang/CoCache/tree/main/example) app has the complete version: `CoroutineController` reads with `cache.suspending()`, uses a per-call loader, and invalidates after a write; the `profile` cache in `UserCacheConfiguration` declares its source with `suspendingLoader { … }`.
