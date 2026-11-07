---
title: Changelog
description: Release history and notable changes for CoCache.
---

# Changelog

## Unreleased {#unreleased}

- Fixed: closing a `CacheRuntime` from code that runs inside one of its own coroutines but not on a thread its dispatcher handed out waited for itself until `closeTimeout` (5 s by default), then logged "CacheRuntime closed with N coroutines still running". This happened when a `LoadObserver` closed the runtime while observing an async or suspending load, and when a data source closed it before the call's first suspension, which runs on the caller's thread. These threads now carry the runtime's own mark, as dispatched tasks already did (#872), so `close` does not wait for itself; closing a different runtime from them still waits for that runtime to drain. (#892)
- Fixed: `invalidationsReceived` (`cocache.invalidations.received`) counted a message from another instance before applying it. Code that waited for the count to rise and then read the cache, as the TCK suites do, could still see the evicted copy, so cross-node TCK cases failed now and then (seen on Valkey). The message now takes effect first: when the count rises, the local copies of its keys are already evicted.
- Fixed: `InMemoryBackend.writeForeign` accepted any `key`, though it needs the full storage key (`keyspace.storageKey(encodedKey)`). Given the encoded key, which is what a downstream test naturally holds, it wrote into a slot no cache reads, so the following assertions passed for the wrong reason. A key that doesn't start with the keyspace's storage prefix is now rejected with `IllegalArgumentException`.
- Changed: a key CoCache doesn't define under `cocache.defaults` or `cocache.overrides` now follows `cocache.caches-validation`, as one under `cocache.caches.[name]` already did (#846): `fail`, the default, stops startup and `warn` logs it. Most such keys are left over from 6.x, such as `cocache.defaults.ttl` (8.x fields sit under `cocache.defaults.cache` or `cocache.defaults.store`) or `cocache.defaults.key-prefix` (now `cocache.key-prefix`), and Spring Boot used to ignore them silently. A silently ignored `key-prefix` put applications that shared one Redis under different prefixes onto the same keys and channels. The message says where each layer's keys go. Other keys under `cocache`, such as `cocache.redis.*`, are unaffected ([startup validation](./configuration.md#validation)).
- Fixed: when the backend threw an `Error` while a cache was being created, for example a `TODO()` in an unimplemented SPI method or an `ExceptionInInitializerError`, `CacheRuntime.create` didn't roll the registration back. The cache name and keyspace stayed taken and the engine stayed open, so retrying the same name failed with "already exists". Creation now rolls back on any throwable and rethrows it unchanged.
- Changed: the messages of `CacheReadException` and `CacheWriteException` end with a summary of the cause, its class and message, so a one-line log names the reason. For example: `Cache[x] - putIfNewer failed on the remote store (not executed): ForeignFormatException: …`. Before, the reason was only in the cause.
- Changed: when an unknown key under `cocache.caches` looks like a cache name with dots that is missing its brackets, such as `cocache.caches.wow.shop.order.ttl`, the startup message suggests the bracketed form `cocache.caches.[wow.shop.order].ttl`.
- Added: `HookedBackend.failReads` (`cocache-test`) fails only reads (single-key, batch and conditional), so an application test can check how reads degrade while writes keep working.
- Fixed: a refresh that didn't get the refresh token treated an `Error` thrown by the codec while re-reading Redis as unreadable content and fell back to a full load; the error now propagates, as on every other read path. An async `SetStore` read in flight when the runtime closed, whose Redis read then failed, completed with `CacheReadException`; it now fails with the original exception, as scalar caches already did. A cache without a loader that declares `refresh-window` no longer allocates on every local hit inside the window. (#911)
- Changed (breaking, SPI): `SetL1Store.addMembers` / `removeMembers` (and `FutureSetL1Store`) return the sealed `L1SetWrite` (`Written` or `Unreadable(format)`) instead of `Int?`. A Java future completing with `null` now fails instead of meaning "written". (#912)
- Changed (breaking): `CachePolicy` has no public constructor and no `builder()` without a base. Build a whole policy from a type default with `DefaultPolicies.STORE.toBuilder()` or `CachePolicy.builder(DefaultPolicies.STORE)`, so an authoritative cache can no longer inherit a derived cache's 1 h `ttl` by accident (`storeSpec { policy = CachePolicy(localMaxAge = …) }` used to do that silently). (#913)
- Fixed: Redis Cluster in `pubsub` mode: a failed heartbeat now forces the subscription connection to reconnect, as on a single node, instead of only warning and waiting for TCP keepalive (meanwhile every heartbeat cleared L2). In `sharded` mode the heartbeat pings every subscribed node concurrently, so several bad nodes no longer delay detection by one command timeout each. (#914)
- Changed: `KeyFilter.notExist` now receives the encoded key (the `KeyEncoder` output, without `{keyPrefix}{namespace}:`) instead of the full storage key, the same key `CacheAdmin`, `ExternalInvalidator` and invalidation listeners use, so a filter keeps answering after an operator changes `key-prefix`. A filter built from storage keys must be rebuilt from encoded keys. `CacheAdmin`'s per-key operations, the actuator endpoint and `ExternalInvalidator` reject a key that starts with the cache's `{keyPrefix}{namespace}:` (a pasted full Redis key) with `IllegalArgumentException`; before, the call addressed a key no cache reads and appeared to succeed. (#915)
- Added: the async views gain `suspending()` and the suspending views gain `sync()` (default methods with covariant return types), so each view reaches the other two directly: `asyncStore.suspending()` instead of `asyncStore.sync().suspending()`. (#917)
- Fixed: Spring Boot starter: under `spring.main.lazy-initialization`, the warning for `cocache.caches.[name]` on an unlayered cache works again, and a `Cache` used only through `@Cacheable` is created on the first call instead of failing with "Cannot find cache" (`CoCacheSpringCacheManager` takes an optional `createDeclared` function for this). Spring AOT detects the Jackson fallback by the same rule as the runtime when a child context's alias shadows a parent `Codec`, so the native image gets its binding hints. (#918)
- Added: `CacheFailures.isTransient(error)` tells whether a failure is temporary: source-load timeout, rejection or cancellation, or an L1 read or write failure that isn't unreadable content. Callers no longer catch each exception type ([retry or degrade](./caches.md#unknown)). (#919)
- Added: Spring Boot starter: new `CacheNameContributor`. A library that creates caches at runtime publishes the names it may create, so `cocache.caches.[name]` entries for them no longer fail startup as undeclared caches. Contributors are read once all singletons exist, lazy ones included, and only the contributed names are relaxed ([startup validation](./configuration.md#validation)). (#921)
- Changed (breaking): `putIfNewer`, `invalidateIfNewer` and `deleteIfNewer` accept a `null` `ttl`, meaning the policy's lifetime (`ttlOf` first; the tombstone lifetime for tombstones), so callers with an optional lifetime need no branches. Callers compile unchanged; only a hand-written implementation of these interfaces changes its signatures. (#922)
- Changed: a runtime without an explicit clock adopts the clock an `InMemoryBackend` was built with (`InMemoryBackend.clock`), so tests pass a `ManualClock` once. `ManualClock.advance` rejects sub-millisecond durations instead of silently dropping them. (#924)
- Added: typed sentinel setters `ttl(Ttl)`, `tombstoneTtl(Ttl)`, `refreshWindow(RefreshWindow)` and `maxConcurrent(Concurrency)`, the constants `Ttl.FOREVER`, `RefreshWindow.OFF` and `Concurrency.UNLIMITED`, and `PolicyFields` (one Java constant per policy field). In Java, `ttl(null)`, `tombstoneTtl(null)`, `refreshWindow(null)` and `maxConcurrent(null)` are now ambiguous: use the constants. (#927)
- Added: `Locks.Builder.pin(field, value)` (also in the `CoCacheFactory.create(spec) { … }` DSL) locks a field and fixes its value in one step. It's reported as `pinned by <owner>`, and `cocache.caches.[name]` can't override it, not even with `unlock` ([locking fields](./configuration.md#locks)). (#936)

## v8.0.0-alpha.6 (Pre-release) {#v8-0-0-alpha-6}

**Module Group:** `me.ahoo.cocache` · Sixth pre-release of 8.0.0, with the fixes from the third round of the GA invariant audit. Like every pre-release, it carries no compatibility promise; see [v8.0.0-alpha.1](#v8-0-0-alpha-1) for the upgrade notes and the full list of 8.0 changes. One behaviour change: `JacksonCodec` now encodes with the declared type, so with a declared type that is not polymorphic, properties that only a subclass has are no longer written (#873).

### Fixed: Spring Boot configuration and startup {#alpha-6-startup}

- When a child application context defined a bean with the same name as a scoped-proxy loader, codec or `JoinKeyExtractor` (`@Scope(proxyMode = …)`) in a parent context, the parent's internal `scopedTarget.<name>` bean was still matched by type. Startup then failed with "Multiple CacheLoader beans … [loader, scopedTarget.loader]", or, when the child bean had another type, the hidden parent target was injected as the data source, bypassing its scope. A same-name child bean now hides the parent's proxy together with its target. (#865)
- In a child application context, an alias that points a name at a bean in a parent context was ignored by the starter's name checks. A join cache whose `firstCacheName` or `joinCacheName` was that alias silently joined the parent's spec cache with that cache name instead of the alias target, and a loader name that such an alias redirected to a bean of another type was still matched by type, so startup failed with a false "Multiple CacheLoader beans". A child's alias now hides a parent's bean of the same name, as it does for `getBean`. (#887)
- In a child application context, `@JoinCacheable.firstCacheName` and `joinCacheName` could name a spec cache declared in a parent context only by its bean name; its cache name failed startup with "does not match any cache". Names now resolve through parent contexts by cache name or bean name, and a child bean still hides a parent's bean of the same name. (#875)
- Another `@ConfigurationProperties` class bound to the `cocache` prefix (for example one that reads only `cocache.enabled`) replaced the record of unknown keys, so a valid key such as `cocache.caches.user.ttl` failed startup as unknown. Only the binding of CoCache's own properties is checked now. (#867)
- An application that supplied its own `CacheRuntime` without also exposing its backend as a `CoherenceBackend` bean failed startup in `cocacheStoreEvictionCheck` ("No qualifying bean of type CoherenceBackend"). The eviction check now uses the runtime's own backend, so a custom runtime on Redis is still checked. (#874)
- With two `ObjectMapper` beans and no `@Primary`, startup failed with `NoUniqueBeanDefinitionException` even when no cache needed the Jackson codec (every interface cache names a codec or matches a `Codec` bean, or the application declares only specs). The `ObjectMapper` is now looked up only when an interface cache falls back to Jackson, and that cache then fails with a message naming it and the candidates. (#883)

### Fixed: interface caches, join views and codecs {#alpha-6-interfaces}

- `CoCacheFactory.create(type, spec)` with a `sealed` interface created and registered the cache and notified the creation listeners, and only then failed because a JDK proxy can't implement a sealed interface; a retry with a valid interface then failed with "Cache[users] already exists.". Sealed interfaces, and any other interface a JDK proxy can't implement, are now rejected before anything is created. (#879)
- `JoinView.get` treated a missing `SetStore` set as present, because `SetStore.get` returns an empty set for a missing key: a missing first key returned `JoinValue([], null, null)` instead of `null`, and a missing joined set gave `secondValue = []`, while `getAll` left both out. Single and batch reads now agree, and an empty set stored as a value in a scalar cache is still present. (#870)
- `JacksonCodec` encoded a value by its runtime type and decoded it by the declared type, so a polymorphic element type in a generic container (`List<Animal>` or `Map<String, Animal>` with `@JsonTypeInfo`) lost its type id: a `Store.put` succeeded and the next read threw `CacheReadException`. It now encodes with the declared type as well. With a declared type that is not polymorphic, properties that only a subclass has are no longer written; decoding never read them back. (#873)

### Fixed: transactions {#alpha-6-transactions}

- A load that a creation listener started while a cache was being created, before its `fill-in-transaction` and `invalidate-fills-on-rollback` modes took effect, could read one mode before they took effect and the other after. With `private` and `invalidate-fills-on-rollback: false`, the load's fill was then shared but not invalidated on rollback, and the rolled-back value stayed in Redis until `ttl`. A call now reads both modes once and uses them for its whole run. (#868)
- When a `CacheErrorHandler` compensated for a failed eviction during commit through the transaction-aware cache it was handed (an `evict` or a `put` of another key), a `put` of that key registered earlier in the transaction ran afterwards and wrote its older value back. An operation issued while the transaction's cache operations run now supersedes the same key's pending writes. (#878)

### Fixed: reads, loads and local copies {#alpha-6-consistency}

- A value read from Redis got a local copy whose lifetime was counted from when decoding finished, not from when Redis was read. A slow `Codec.decode`, or the decoding of earlier keys in the same `getAll`, therefore let the local copy outlive the value in Redis, and new reads kept returning it after it had expired there. The local copy's lifetime now starts before the read (and before a fill is sent), for every cache type, `SetStore` included. (#877)
- A `getAll` whose keys included one that can't be encoded losslessly as UTF-8 (an unpaired surrogate) threw `IllegalArgumentException`, but an earlier key of the batch that was inside `refresh-window` had already started a refresh-ahead from the source. The whole batch is now checked before any refresh, whatever the key order. (#866)
- A batch read whose `Codec.decode` threw an `Error` (such as `AssertionError`) for a key found in Redis kept the leases it had taken for the batch's missing keys, so later reads of those keys loaded from the source but bypassed Redis until the leases expired (10 s by default). Every lease the batch took is now released before the original error is rethrown. (#886)
- When a `LoadObserver` read another cache while recording a failure, an `AsyncCacheLoader` future of the outer load failing with `InterruptedException` was again taken for an interrupt of the leading caller, and the other sync readers of the key each called the source again; the alpha.5 fix covered only loads without nesting. Each sync load now keeps its own record. (#864)
- When the method behind `@Cacheable(sync = true)` (or the `Callable` passed to the Spring cache's `get(key, valueLoader)`) was interrupted, the callers waiting on the same key got the interrupted caller's failure instead of running the method themselves. They now load on their own, and the interrupted caller sees the same exception as before. (#871)

### Fixed: observation and shutdown {#alpha-6-runtime}

- A suspending load under a propagated parent observation (a `ThreadContextElement` that opens the parent's scope) ran the loader with the parent as the current observation instead of the new `cocache.load`, for a declared `AsyncCacheLoader` and for a call-level suspending loader alike. The loader now starts under `cocache.load`; the caller's other context elements, such as MDC, are kept. (#876)
- `CacheRuntime.close()` called in an async result's callback that ran after the runtime's executor had rejected a task waited the whole `closeTimeout` (5 s by default) for itself. It now returns without waiting, as it does when the executor accepts the task. (#872)

## v8.0.0-alpha.5 (Pre-release) {#v8-0-0-alpha-5}

**Module Group:** `me.ahoo.cocache` · Fifth pre-release of 8.0.0, with the fixes from the second round of the GA invariant audit. Like every pre-release, it carries no compatibility promise; see [v8.0.0-alpha.1](#v8-0-0-alpha-1) for the upgrade notes and the full list of 8.0 changes.

### Fixed: configuration and startup {#alpha-5-startup}

- The unknown-key check under `cocache.caches.[name]` added in alpha.4 bound the properties a second time with Spring Boot's default converters, so a value that only an application's `@ConfigurationPropertiesBinding` converter accepts (such as a custom alias for `read-failure`) failed startup. The check now runs inside the regular binding of `CoCacheProperties` and uses the same converters.
- A rejected creation through `CoCacheFactory` (for example a duplicate name) had already rewritten the existing cache's `fill-in-transaction` and `invalidate-fills-on-rollback` modes, so rolled-back data could stay cached. The modes now take effect only when the cache they were computed for is created.
- A cache interface ignored a `CacheLoader`, `AsyncCacheLoader`, `Codec` or `JoinKeyExtractor` declared in a parent application context when matching by type. A `Cache` then failed startup with "no data source", and a cache without a codec silently fell back to Jackson instead of the parent's `Codec<V>`. Matching by type now includes ancestor contexts; a child bean with the same name still hides the parent's, and a match in each context is ambiguous.
- A loader, codec or `JoinKeyExtractor` declared as a scoped proxy (`@Scope(proxyMode = …)`) and matched by type was injected as its target, so its scope no longer applied (a prototype loader kept one instance). The proxy is now injected. Naming a scoped `JoinKeyExtractor` with `joinKeyExtractor = "…"` no longer fails startup with "is not a `JoinKeyExtractor<V1, K2>`".
- A spec bean declared as a `FactoryBean` with full generics (`fun recordsSpec(): FactoryBean<StoreSpec<String, String>>`) failed startup with "must declare its generic types". `K` and `V` now come from the factory's product type, without creating the factory.
- `@CaffeineCache(maximumSize = 10000)`, an explicit value equal to the default, wasn't recorded as written, so `cocache.defaults.*.local-maximum-size` replaced it. Any `maximumSize` other than `UNSET` now counts as written. `CachePolicy.Builder` has `localMaximumSize` (Java: `localMaximumSize(Long)`), which always counts as written.
- A cache interface that inherits its type arguments through a generic intermediate interface (`interface UserStore : BaseStore<User>`, `BaseStore<V> : Store<String, V>`) decoded values read from Redis as `LinkedHashMap` with the default Jackson codec, so reads threw `ClassCastException`; members of `List<User>` and set elements were affected too. The codec, and the AOT binding hints, now use the inherited types.

### Fixed: creating and closing caches {#alpha-5-lifecycle}

- `CacheRuntime.create` racing `close()` could finish after close returned, adding a cache and leaving its invalidation subscription open. A cache is now handed to the runtime only once its subscription is confirmed and while the runtime is open; otherwise the creating call closes what it built and fails with `CacheRuntime is closed.`
- A cache whose subscription was still pending was already visible to `cacheNames`, `cache`, `admin` and `metrics`, so the Micrometer binder could bind an engine that then rolled back; after a same-name retry the meters kept reading the discarded engine. A cache is now visible only once it has been created.
- Two caches whose storage prefixes `{keyPrefix}{namespace}:` were equal or one started the other (for example `app:` + `users` and `app:users:` + `v2`) could both be created, overwrote each other's keys in Redis and missed each other's invalidations. Creating the second in the same `CacheRuntime` now fails, naming the first. Caches of different runtimes or applications sharing one Redis are not checked: give them prefixes that don't start one another.
- An async or suspending call issued after `CacheRuntime.close()` still ran: a `put` changed Redis and other instances read the new value, while the caller got `IllegalStateException("CacheRuntime closed.")`. Such a call now fails without running.
- A refresh-ahead queued on a caller-provided `refreshExecutor` before `CacheRuntime.close()` but started after it still loaded from the source and wrote the new value to Redis. Such a refresh is now skipped.

### Fixed: transactions {#alpha-5-transactions}

- A value loaded in a transaction for the empty business key `""` (or a key a custom `KeyEncoder` encodes as `""`) is now invalidated on rollback and on a rollback to a savepoint. Before, the invalidation was rejected as an empty encoded key and the uncommitted value stayed cached until `ttl`. A `VersionedCache` keeps its version fence. (#823)
- A value that another synchronization's `savepointRollback` callback loads, before Spring rolls the database back to the savepoint, no longer survives the outer commit. A transaction that rolled back to a savepoint now also invalidates what it loaded when it commits, and keys it had loaded by then load privately for the rest of the transaction under `registered-keys`. (#814)
- An eviction or `put(key, null)` that another synchronization makes in `afterCommit`, after CoCache's own has run, now hands its failure to the `CacheErrorHandler` instead of throwing it into the committed transaction and skipping the later `afterCommit` callbacks. (#838)

### Fixed: interface caches {#alpha-5-interfaces}

- An interface cache could narrow a fixed return type, such as `override fun getAll(keys: Collection<String>): SortedMap<String, String>` in a `Store` (or the result of a `suspend` method), and creating it succeeded; the first call then failed with `ClassCastException`, because the cache returns a `LinkedHashMap`. Creating the cache now fails, before anything is registered. Narrowing the value type (`get` returning `User` instead of `V`) still works. (#843)
- A join cache interface could declare an abstract method whose parameters the target can't take, such as `getAll(keys: String)`, and only the first call failed with `ClassCastException`. Creating the proxy now fails, like for interface caches. (#833)
- A default method declared in a package-private interface, either the interface you declare or a package-private base of a public one, failed on every call with `IllegalAccessException` (wrapped in `UndeclaredThrowableException`). It now runs, in interface caches, their async and suspending views, and join caches. In a named module, open the package to CoCache, or creating the cache fails. (#827)

### Fixed: Redis commands and invalidation channels {#alpha-5-redis}

- When Redis answered a script's `EVALSHA` with `NOSCRIPT` before Lettuce handed the command back, a command timeout no longer lets the queued fallback `EVAL` run afterwards. A timed-out write that was not yet sent is now always dropped, instead of possibly overwriting a newer value later.
- A batch load whose L1 fills were partly rejected before they were sent was reported as not executed as a whole, so on a `VersionedCache` a fill that did advance the version skipped its local fence and an earlier reader on the same instance could leave the old version in L2. A batch with mixed outcomes is now outcome unknown, and every versioned fill in it is fenced.
- A `SetStore` hash with an explicit empty `!m` field could be read while writes rejected it as a higher format. Reads now treat it as a higher format as well: they fail with `CacheReadException` and keep the content.
- Creating a cache on a thread whose interrupt flag was set could fail with `InterruptedException` while waiting for Redis to confirm the subscription, and cleared the flag. The wait now ignores interrupts: the cache is created and the flag is kept.
- An invalidation listener whose `onReset` threw when it joined an existing channel made `subscribe` fail but stayed registered, receiving invalidations with no way to unsubscribe. The failure is now logged and `subscribe` returns a closable handle, on the Redis and in-memory backends.

### Fixed: loads, refreshes and writes {#alpha-5-consistency}

- When a `FillScope.filled` hook (transactional fill recording) threw, a read kept the Redis leases it had taken, so other reads of those keys waited up to `lease-wait` and bypassed Redis until the lease expired (10 s by default). The read still fails with the hook's exception, and now releases every lease first, for a single key and for every key of a `getAll`, and fills neither layer.
- A sync loader that read its own key again after running longer than `load-timeout` started a second load of that key instead of failing with `IllegalStateException`, so a recursive loader could load without bound. Reentry now fails fast whether or not the load has expired, and other threads still take over an expired load.
- When an `AsyncCacheLoader`'s future failed with `InterruptedException` (the source was interrupted on its own thread), sync readers of the same key treated it as an interrupt of the leading caller, and each of them called the source again. They now share the failure like any other source failure: every caller gets the same exception, with no interrupt flag set, from one source call. An interrupt of the calling thread itself, including one inside `loadAsync` before it returns its future, still lets the other readers load on their own.
- Two concurrent writes of the same key whose outcome was unknown could shorten the window in which the instance applies its own late invalidation (`localMaxAge` after the later failure), when the write that read the clock first registered last. A late write inside the later window was then filtered, and the instance kept serving its old local copy until `localMaxAge`. The window now always ends at the latest deadline registered for the key.
- A `SetStore.putAll` that mixes non-empty and empty sets reported `outcomeUnknown = false` when its delete was rejected after the replacements had reached Redis, and on a backend without atomic broadcast other instances kept their old copies of the replaced keys. It now reports `outcomeUnknown = true` and broadcasts the whole batch.
- When a `VersionedCache` refresh was refused because the source returned an older version (or a `null` without a version), the local copy it kept got the lifetime Redis reported before the load, so after a slow load it outlived the value in Redis. It now expires with that value, and a copy that expired in Redis during the load isn't put back in L2.
- `get(key, loader)` on the sync, async and suspending views of a `Cache` didn't start refresh-ahead when it hit a local copy inside `refresh-window`. It now schedules the refresh through the declared loader, like `get`; the per-call loader still only serves misses.
- A `Cache` read that found content written in an older format (or not by CoCache) could, if a `put` of the same bytes landed before the read reclaimed the key, reclaim that new value and write its own reload over it; other instances then read the stale reload until `ttl`. The reclaim fingerprint now also covers the content kind (value, negative entry or set) and the format the read reported, so the new value stays. SPI: `ContentFingerprint.of(envelope)` is replaced by `ContentFingerprint.of(envelope, format)`; `InMemoryBackend.writeForeign` and the `EngineSpec.writeForeign` hook write lower-format content for tests. (#834)

### Fixed: keys and diagnostics {#alpha-5-keys}

- A key whose encoded form contains an unpaired UTF-16 surrogate (for example `"bad-\uD800"`) was accepted, but Redis keys and invalidation frames are UTF-8, which turns it into `?`. The key then shared its Redis key with another key, and other instances evicted that other key instead, keeping their stale copy until `localMaxAge`. Such a key now fails with `IllegalArgumentException` before it reaches Redis, on reads, writes, `CacheAdmin` and `ExternalInvalidator`; emoji, CJK and other well-formed keys are unaffected ([keys](./caches.md#keys)).
- `CacheAdmin.inspect` and the `cocache` endpoint showed a versioned tombstone that this instance loaded itself (`Versioned(null, v)` from the loader) as a local `VALUE` holding the internal `Versioned` wrapper. It is now `ABSENT` with no value, like `get` and the remote copy.

### For backend and test authors {#alpha-5-spi}

- `ContentFingerprint.of(envelope, format)` replaces `of(envelope)`: the fingerprint that conditional reclaim compares now starts with the content's class (scalar, negative entry, set) and the format the read reported, and a backend's `reclaim` must compute the same over the current content. The client and its scripts ship together, so mixed versions stay consistent ([extending](../architecture/extending.md)).
- `InMemoryBackend.writeForeign` stores content of an older format for tests, and `CachePolicy.Builder.localMaximumSize` (Java: `localMaximumSize(Long)`) always counts as written, like `maxConcurrent`.

## v8.0.0-alpha.4 (Pre-release) {#v8-0-0-alpha-4}

**Module Group:** `me.ahoo.cocache` · Fourth pre-release of 8.0.0. Like every pre-release, it carries no compatibility promise; see [v8.0.0-alpha.1](#v8-0-0-alpha-1) for the upgrade notes and the full list of 8.0 changes.

### Changed: unknown per-cache keys fail startup {#unknown-cache-keys}

- A key under `cocache.caches.[name]` that CoCache doesn't define, usually a typo such as `ttl-forever` or `max-concurrent` (for `load-max-concurrent`), now fails startup. Spring Boot used to ignore it, so the override silently had no effect. `cocache.caches-validation: warn` logs a warning instead. Relaxed forms that bind, such as `localMaxAge`, still work, and unknown keys in `cocache.defaults` and `cocache.overrides` are still ignored ([startup validation](./configuration.md#validation)).

### Fixed: consistency under faults {#consistency-fixes}

- On a backend without atomic broadcast (`sharded` channel mode, or when the atomic-broadcast probe fails), a write whose separate invalidation publish was rejected, for example while disconnected, was reported as not executed (`outcomeUnknown = false`). `Cache.put` then skipped its compensating invalidation, and other instances kept their old L2 copies until `localMaxAge`. Such a write is now outcome unknown: `put` schedules the compensating invalidation and `invalidate` retries.
- The suspending view of a `Cache` didn't apply the per-call TTL ceiling (`cocache.overrides.cache.ttl`) to `put(key, value, ttl)` and `putAll(entries, ttl)`. It now clamps them and counts them in `cocache.ttl.clamped`, like the sync and async views.
- On an externally sourced `Cache`, `getAll` without a loader capped the local copy of a key held under another instance's lease, in a newer format, or with a pending invalidation at `localMaxAge`. Like `get`, it now caps it at `degradedLocalMaxAge` and counts it in `cocache.degraded.fills`.
- With the Redis backend, removing the last member of a set left the key behind as an empty hash when the set carried metadata besides `!f` (an explicit `!m = "0"`, or any reserved `!` field). Without a TTL it never expired. The set is now deleted once no member remains.

### Fixed: content written by another cache type {#type-mismatch-fixes}

- A `Store` read a value written by a `VersionedCache` or `VersionedStore` on the same namespace (a version segment and no digest) as its own, although its conditional reads refuse it. It now fails like a value without a digest: `CacheReadException`, the copy in Redis kept.
- `SetStore` reads of unreadable content didn't all fail the same way. A newer format threw `ForeignFormatException`, a scalar on a set key threw `IllegalStateException` and an undecodable element threw the codec's exception, and the last two weren't counted. Each now throws `CacheReadException` with a `ForeignFormatException` cause and counts in `cocache.unknown.formats`, like a `Store`.
- `cocache.reclaims` counted undecodable values written by a `Store` (with a digest segment) that a derived cache read, although such values are never reclaimed. They are now counted only in `cocache.type.mismatch`.
- A `VersionedCache` logged a WARN on every `evict` refused because the key holds a value written by a `Store`. It now warns once per cache and logs later refusals at DEBUG, like reads of such values; `cocache.type.mismatch` still counts each one.

### Fixed: configuration {#configuration-fixes}

- `maxConcurrent = unlimited()` could not be declared. Its documented form, `load = LoadPolicy(maxConcurrent = unlimited())`, equals the default, so a nested assignment didn't record it as written and `cocache.defaults.*.load-max-concurrent` replaced it. `CachePolicy.Builder` now has `maxConcurrent` (Java: `maxConcurrent(Int?)`), which always counts as written: `policy { maxConcurrent = unlimited() }`.
- `refresh-window: off` written without quotes in YAML failed startup with `"false" is not a duration`, because YAML reads a bare `off` as `false`. It now turns refresh-ahead off.
- `cocache.defaults.store.tombstone-ttl: forever` failed the creation of every `VersionedStore` that didn't set its own, whose tombstones need a finite lifetime. It is now skipped for a `VersionedStore`, like any other global-layer entry that can't apply: logged as a `WARN` and listed under the endpoint's `skippedConfiguration` with the reason `a VersionedStore needs a finite tombstone-ttl`. A `VersionedCache` still takes `cocache.defaults.cache.tombstone-ttl: forever`, and `cocache.caches.[name].tombstone-ttl: forever` on a `VersionedStore` still fails startup.

### TCK: faults in `evict` {#tck-evict-faults}

- `HookedBackend` now passes `evictKeepingVersion` through `failWrites`, `rejectWrites`, `beforeInvalidate` and `afterInvalidate`, so `VersionedSpec` can inject faults into `evict`.

## v8.0.0-alpha.3 (Pre-release) {#v8-0-0-alpha-3}

**Module Group:** `me.ahoo.cocache` · Third pre-release of 8.0.0. Like every pre-release, it carries no compatibility promise; see [v8.0.0-alpha.1](#v8-0-0-alpha-1) for the upgrade notes and the full list of 8.0 changes.

### Performance: declared suspending interfaces {#suspending-proxy}

- An L2 hit through a declared suspending interface (`interface UserCache : SuspendingCache<String, User>`) costs about 2 ns more than a hit on `suspending()` directly, down from about 10 ns: roughly twice the throughput of alpha.2. The proxy calls the view's reads (`get`, `getAll`, `lookup`, `lookupAll`, `getVersioned`) through method handles, so the view's hit path no longer counts against the caller's JIT inlining budget. Behavior is unchanged: a hit returns without suspending, and exceptions arrive unwrapped.

### New: a cache's keyspace in `CacheAdmin` and the endpoint {#admin-keyspace}

- `CacheAdmin.keyspace` returns the cache's key prefix and namespace, so operators and integrations can build storage keys (`{keyPrefix}{namespace}:{encodedKey}`) and ACL patterns without repeating the configuration. The endpoint's cache report (`GET /actuator/cocache` and `GET /actuator/cocache/{name}`) adds them as `keyPrefix` and `namespace` ([Observability](../operations/observability.md)).

### Docs: upgrade guide corrections {#upgrade-corrections}

- At the cutover, delete the old keys of every `VersionedCache` too: a versioned write (`putIfNewer`, `invalidateIfNewer`, `evict`) refuses a 6.x value with `CacheWriteException` instead of overwriting it ([the cutover](./upgrade.md#steps)).
- A namespace with several `:` segments moves everything up to the last `:` into `keyPrefix`, and each key prefix this produces needs its own `&{keyPrefix}inv:*` channel pattern ([Redis ACL](./upgrade.md#acl)).
- Internal keys live under `{keyPrefix}!`, except the atomic-broadcast probe's fixed key `cocache:!probe:{a}`.

## v8.0.0-alpha.2 (Pre-release) {#v8-0-0-alpha-2}

**Module Group:** `me.ahoo.cocache` · Second pre-release of 8.0.0. Like every pre-release, it carries no compatibility promise; see [v8.0.0-alpha.1](#v8-0-0-alpha-1) for the upgrade notes and the full list of 8.0 changes.

### New: declare a cache as its async or suspending view {#declared-views}

- A `@CoCache` interface, or the type passed to `CoCacheFactory.create(type, spec)`, can extend an async or suspending view instead of a synchronous type: `interface UserCache : SuspendingCache<String, User>`. All ten views can be declared (`AsyncCache` … `AsyncSetStore`, `SuspendingCache` … `SuspendingSetStore`). Each declares the same kind of cache as its synchronous type, so a `SuspendingStore` interface takes a `storeSpec` ([interface caches](./caches.md#interface), [coroutines](./coroutines.md#declare), [async](./async.md#declare)).
- The proxy hands each call to the cache's `async()` or `suspending()` view, passing a suspending call's continuation through: an L2 hit returns without suspending, re-entry fails fast, and exceptions arrive unwrapped, including a checked exception thrown before a suspending call suspends.
- The starter registers these interfaces as beans of the interface type, with native-image hints. Spring Cache keeps using the cache by name, so `@Cacheable` on a cache declared as a `SuspendingCache` uses the `Cache` behind it.

| Before | Now | What to know |
|--------|-----|--------------|
| `CoCacheFactory.create(type: Class<C>, spec)` with `C : ReadableCache<*, *>` | `C : Any` | Source-compatible. Code compiled against 8.0.0-alpha.1 must be recompiled: the erased return type changed. |
| `@EnableCoCache(caches: Array<KClass<out ReadableCache<*, *>>>)` | `Array<KClass<*>>` | Source-compatible. A type that is not one of the fifteen declarable types still fails when its cache is created. |

## v8.0.0-alpha.1 (Pre-release) {#v8-0-0-alpha-1}

**Module Group:** `me.ahoo.cocache` · First pre-release of 8.0.0, a major release with breaking changes ([RFC 0005](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)). 8.x doesn't run next to 6.x on one Redis: stop 6.x, then start 8.x ([Upgrading from 6.x](./upgrade.md)). Fixes for 6.x continue on the `6.x` branch.

**Pre-release: no compatibility promise.** Until 8.0.0, any pre-release may change the API, the SPI and TCK, configuration keys, metric names, endpoint paths and the Redis wire format. When you upgrade from one pre-release to the next, delete every CoCache key: caches with a source reload them, and caches without a source start empty. Don't use pre-releases for data you can't rebuild.

### Upgrading from 6.x {#upgrading-from-6x}

8.x doesn't run next to 6.x on one Redis, and there is no migration tool: stop every 6.x instance, delete the keys of caches without a source, then start 8.x, and caches with a source reload from it. [Upgrading from 6.x](./upgrade.md) has the procedure and maps 6.x types, configuration keys, Redis ACLs and the backend SPI to 8.0. The sections below list every change.

### The user API is split by role {#api-types}

8.0 replaces the one `Cache` interface, with or without a loader, and `SetCache` with one type per role ([RFC 0005 §4.1](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)). A derived type holds a copy that its source can rebuild; an authoritative type holds the only copy, in Redis, and has no loader. Each type has only the operations that are safe for its role, and every method is abstract: no method throws `UnsupportedOperationException` because the cache was declared one way and not the other. See [Choosing a type](./caches.md#types).

| 6.x | 8.x | What changes |
|-----|-----|--------------|
| `Cache<K, V>` with a loader | `Cache<K, V>`, `cacheSpec { loader(…) }` | `getIfChanged`, `putIfMatch`, `putIfNewer`, `invalidateIfNewer` and `getVersioned` are gone from it. |
| `Cache<K, V>` without a loader, filled by Spring Cache or another system | `Cache<K, V>` with `externallySourced()`, or `@CoCache(source = EXTERNAL)` | A `CacheSpec` needs exactly one of `loader`, `asyncLoader` and `externallySourced()`; creating one with none fails. Its values carry no writer id or digest, and a failed `invalidate` is retried in the background, as for a cache with a loader. |
| `Cache<K, V>` without a loader, Redis holds the only copy | `Store<K, V>`, `storeSpec` | `delete` and `deleteIfMatch` replace `invalidate`: they are synchronous, throw on failure and are never retried. `getIfChanged` and `putIfMatch` live here. Reads take no lease. The store must provide `conditional()`. |
| `Cache<K, V>` with `versioned` and a versioned loader | `VersionedCache<K, V>`, `versionedCacheSpec` | `getVersioned`, `putIfNewer`, `invalidateIfNewer`, `evict` (keeps the version, state `E`) and `purge` (drops it). There is no `put` or plain `invalidate`. |
| `Cache<K, V>` with `versioned` and no loader | `VersionedStore<K, V>`, `versionedStoreSpec` | `deleteIfNewer` replaces `invalidateIfNewer`; `purge` drops the version. Tombstones expire after `tombstoneTtl` (1 day by default). |
| `SetCache<K, E>`, `SetCacheSpec` | `SetStore<K, E>`, `SetStoreSpec`, `setStoreSpec` | No `invalidate`: use `clear(key)`. |
| `AsyncCache`, `AsyncSetCache` | `AsyncCache`, `AsyncVersionedCache`, `AsyncStore`, `AsyncVersionedStore`, `AsyncSetStore` (all `AsyncReadableCache`) | The async views follow the same split, and so do the suspending views. |
| `cocache-coroutines`: `SuspendingCache`, the `suspending()` extensions, `suspendingLoader` | `suspending()` is a member of every type, and `SuspendingCache` and its siblings are in `cocache-api`. `suspendingLoader` and `suspendingVersionedLoader` are in `me.ahoo.cocache.core` | The `cocache-coroutines` module and its BOM entry are removed. Drop the dependency and the `me.ahoo.cocache.coroutines` imports. |
| `JoinCache` over any two caches | `JoinView` (read only) over any readable caches; `JoinCache` only over two derived `Cache`s | `JoinCache` is a `JoinView` with the two invalidations. |
| `CacheRuntime.create(CacheSpec)` returns `Cache` | One `create` per spec type, returning that type; `create(spec: Spec)` dispatches; `cache(name)` returns `ReadableCache` | Creation checks the capabilities the type needs: `Store` needs `conditional()`, `VersionedCache` and `VersionedStore` need `versioned()`, `SetStore` needs `sets()`. |

Reads and failures:

- `Lookup` has a third case, `Unknown(error)`: L1 failed and there was no source to fall back to, or the read has a source and `read-failure` is `throw`. It comes only from L1 faults, never from content. `Lookup.valueOrThrow()` throws its `CacheReadException`. See [Reading: present, absent, or unknown](./caches.md#unknown).
- `unknownRead` (`THROW` by default, or `ABSENT`) on `CacheSpec`, `StoreSpec` and `VersionedStoreSpec` decides what a read without a loader returns on `Unknown`; `ABSENT` reads absent and caches nothing in either layer. `SetStore` always throws. A `CacheSpec` may set `ABSENT` only together with `externallySourced()`. `readFailure` now applies only to reads with a loader, where `DEGRADE` remains the default; `readFailure = DEGRADE` on a read without a loader no longer turns a failure into absent.
- `getAll` with some keys `Unknown` completes the other keys first, then throws `CacheReadException` with `unknownKeys` and the `partialResult` of the others. The new `lookupAll` returns a `Lookup` per key instead.
- `CacheWriteException.outcomeUnknown` says whether the write may have reached Redis. It is `false` only when the write was not sent or the store refused it without changing Redis. SPI: a store reports a command rejected before it was sent as `L1RejectedException` (new), as the Redis backend does while disconnected.
- An unversioned `null` from a versioned loader over versioned content in Redis is refused: the fill writes nothing, `cocache.fill.unversioned.absent` counts it, and the cache logs a warning once.
- New meters: `cocache.reads.unknown` (`CacheMetrics.readsUnknown`) and `cocache.fill.unversioned.absent` (`CacheMetrics.unversionedAbsentRefusals`). The endpoint's local state `UNRESOLVED` is now `UNFILLED`.

Defaults and wire format:

- Authoritative types (`Store`, `VersionedStore`, `SetStore`) start from their own policy: `ttl` never expires, `localMaxAge` 30 s, `absentTtl` 10 s ([default policy](./caches.md#store-defaults)). Only the declaration and `cocache.caches.[name].ttl` set their `ttl` ([three-layer configuration](#three-layer-config)).
- `Store` and `VersionedStore` write the writer id, `Store` also writes the digest, and only these two types verify it. A `Cache`, externally sourced or not, writes neither. A derived cache that reads a value with a digest counts a type mismatch, and `evict` refuses one.
- SPI: `ConditionalL1Store.deleteIfMatch(key, expected, frame)` (abstract; `FutureConditionalL1Store` and `ForwardingConditionalL1Store` too) deletes a value whose stored digest equals `expected` and publishes the frame. `ConditionalDelete` is its result in the API.
- Spring: an `@CoCache` interface is created as the type it extends; one that extends none of the five fails at startup. A `Cache` interface with no matching loader bean fails at startup unless it declares `source = EXTERNAL`; in 6.x it silently had no loader. `loader` or `source = EXTERNAL` on an authoritative type fails. Spring Cache and `TransactionalInvalidator` take only derived `Cache`s. `cocache.redis.set-cache-eviction-check` is now `cocache.redis.store-eviction-check` and also covers `Store` and `VersionedStore`. `CoCacheProperties.setPolicyFor` is removed ([three-layer configuration](#three-layer-config)).
- TCK: `CacheAccess` creates each type (`cache`, `versionedCache`, `store`, `versionedStore`, `setStore`). `EngineSpec` covers `Cache` and `Store`, `VersionedSpec` covers `VersionedCache` and `VersionedStore`, and `SetEngineSpec` covers `SetStore`. `HookedBackend.rejectWrites` rejects writes with `L1RejectedException` before they are sent.

### Libraries create caches through `CoCacheFactory` {#cocache-factory}

- `CoCacheFactory` (`cocache-core`) is the entry point for creating caches ([RFC 0005 §4.8](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)). It has `create(spec)` for each spec type and `create(type, spec)` for an interface, which must be an interface extending the type the spec declares. The starter publishes a `CoCacheFactory` bean and creates its own interface and spec-bean caches through it. A library that injects it gets the same customizers and `cocache.*` properties, the endpoint and meters. See [CoCacheFactory](./caches.md#factory).
- A cache created directly with `CacheRuntime.create` is *unlayered*: customizers and `cocache.*` properties don't apply to it. Its start log line ends with `unlayered: …` and the endpoint reports `layered: false`. A `cocache.caches.[name]` entry for it logs a warning and is listed under the endpoint's `ignoredConfiguration`.
- `CacheAdmin` has `apiType` (the cache's API type), `layered` and `policyReport`. The endpoint's cache report adds `type`, `layered`, `ignoredConfiguration`, `policy` and `skippedConfiguration` ([three-layer configuration](#three-layer-config)).
- `CacheRuntime.addCreationListener` calls a `CacheCreationListener` after each later cache is created. The starter uses it to bind meters for every cache, including ones created directly after startup.

### Three-layer configuration {#three-layer-config}

A cache's policy is now merged field by field from three configuration layers around the declaration ([RFC 0005 §4.8](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)). See [How settings combine](./configuration.md#precedence).

- **Layers**, low to high: the type default (`DefaultPolicies`), `cocache.defaults.cache.*` / `cocache.defaults.store.*`, the declaration, `CacheSpecCustomizer` beans, `cocache.overrides.cache.*` / `cocache.overrides.store.*`, `cocache.caches.[name].*`. The `cache` sections apply to `Cache` and `VersionedCache`, the `store` sections to `Store`, `VersionedStore` and `SetStore`.
- **Defaults sit below the declaration**: they fill only the fields the declaration didn't write. A field counts as written when an annotation attribute is set, when `policy { … }` assigns it (`ttl = forever()` included; a nested `lease`, `load` or `local` writes only the fields whose value it changes), or when the spec assigns a whole `policy`, which writes every field. `Spec.declaredFields` lists them.
- **Overrides only tighten**: `local-max-age`, `degraded-local-max-age`, `absent-ttl` and a `Cache`'s `ttl` take the minimum, and `read-failure`, `fill-in-transaction` and `invalidate-fills-on-rollback` take the stricter mode. `overrides.cache.ttl` also caps per-call lifetimes (`ttlOf`, `put(key, value, ttl)`, `putAll(entries, ttl)`), counted in the new meter `cocache.ttl.clamped` (`CacheMetrics.ttlClamped`, endpoint key `ttlClamped`); a longer `cocache.caches.[name].ttl` raises that cap for its cache, and `forever` removes it. Any other key in `overrides` is skipped.
- **Applicability.** Global layers skip, per cache, the keys that don't apply to its type, that the layer doesn't allow, or that a library locked: a `WARN` line at creation and an entry in the endpoint's new `skippedConfiguration`. A `cocache.caches.[name]` key that doesn't apply fails startup, as an unknown cache name does; `cocache.caches-validation: warn` (default `fail`) only warns for both. An entry that names an unlayered cache still only warns. See [which keys apply](./configuration.md#applicability) and [validation](./configuration.md#validation).
- **Values.** Every duration needs a unit, `cocache.redis.*` included: `500ms`, `5s`, `2m`, `PT2M`. Sentinels are explicit: `forever` (`ttl`, `tombstone-ttl`), `off` or `0s` (`refresh-window`), `unlimited` (`load-max-concurrent`). Values are parsed at binding, and a malformed one fails startup naming the key, for example `cocache.defaults.cache.ttl: "60" needs a unit, e.g. 60s, 5m, PT2M.` Annotations stay in seconds.
- **New keys**: `read-timeout` (`CachePolicy.readTimeout`), `tombstone-ttl`, and, under `cocache.caches.[name]` only, `unknown-read` and `unlock`. `cocache.key-prefix` sets the prefix for every cache outside the layering; `cocache.caches.[name].key-prefix` still wins over it.
- **Locks for libraries.** `CoCacheFactory.create(spec) { lock(…); lockLayout(…); constrain(field) { … }; verify { … } }` (Java: `create(spec, Locks.builder("my-lib")….build())`) fixes policy fields and the storage layout. Locked fields report `LOCKED` (`locked by …`), global layers skip them, `cocache.caches.[name]` fails on them unless its `unlock` lists them (the field is then reported `UNLOCKED`, with a warning), and a customizer that changes one throws. A locked `KEY_PREFIX` ignores `cocache.key-prefix`. See [Locking fields](./configuration.md#locks).
- **Validation chain.** When a global layer makes `refresh-window` no shorter than `local-max-age` or `ttl` minus jitter, and `cocache.caches.[name]` set none of these fields, the window is turned off for that cache (`CLAMPED`, with a warning). A conflict caused by the declaration alone, or one that involves a field set by `cocache.caches.[name]`, fails startup.
- **Reports.** Each layered cache logs one `INFO` line with the fields that don't come from the type default and their sources. `GET /actuator/cocache/{name}` adds `policy` (every field's effective value and source: `TYPE_DEFAULT`, `DEFAULTS`, `DECLARATION`, `CUSTOMIZER`, `OVERRIDES`, `CACHES`, `CLAMPED`, `LOCKED`, `UNLOCKED`) and `skippedConfiguration`. `CacheAdmin.policyReport` returns the same `PolicyReport`, `null` for an unlayered cache. See [Effective values and sources](./configuration.md#policy-report).
- **Eviction check.** With an authoritative cache, `allkeys-*` always fails startup, and so does a `maxmemory-policy` that can't be read without `cocache.redis.assume-maxmemory-policy`. `cocache.redis.store-eviction-check` (`warn`, `fail`, `off`) now governs only `volatile-*` with an authoritative cache whose `ttl` is finite, and `fail` fails there. See [the eviction check](./configuration.md#eviction-check).
- API (`cocache-core`): `DefaultPolicies`; `PolicyField` with one object per configurable field; the sentinel types `Ttl` (`Forever`, `Finite`, `finiteOr`, `min`), `RefreshWindow` (`Off`, `Of`) and `Concurrency` (`Unlimited`, `Max`); `Locks`, `LayoutField`, `PolicyReport` and `Layered`; `CachePolicy.ttlCeiling`; and the builder helpers `forever()`, `off()` and `unlimited()`.

What to change when you upgrade:

| Before | 8.x | What to know |
|--------|-----|--------------|
| `cocache.defaults.<field>`, such as `cocache.defaults.ttl` | `cocache.defaults.cache.<field>` (`Cache`, `VersionedCache`) or `cocache.defaults.store.<field>` (`Store`, `VersionedStore`, `SetStore`) | **The meaning changed.** `cocache.defaults.<field>` overrode the declaration, annotations included; the new keys sit below it and fill only fields the declaration didn't write. To keep forcing a value over every declaration, use `cocache.overrides.cache.<field>` (staleness bounds, `read-failure` and the two transaction modes only) or `cocache.caches.[name].<field>`. Spring Boot ignores the old key without a warning. |
| `ttl-forever: true` | `ttl: forever` | Spring Boot ignores `ttl-forever` without a warning, so a cache that relied on it gets the type default: 1 h for a derived cache. |
| `cocache.defaults.key-prefix` | `cocache.key-prefix` | Configuration still wins over the declaration, unless a library locks `KEY_PREFIX`. Spring Boot ignores the old key without a warning, so the caches fall back to the declared prefix (`cocache:` by default): a cold start for derived caches, and empty authoritative caches. |
| A number without a unit: seconds under `cocache.defaults` and `cocache.caches` (`ttl: 600`), milliseconds under `cocache.redis` (`command-timeout: 2000`) | `ttl: 10m`, `command-timeout: 2s` | A number without a unit now fails startup, naming the key. |
| `refresh-window: 0`, `ttl-jitter: 0` | `refresh-window: "off"` (or `0s`), `ttl-jitter: 0s` | Quote `off`: YAML reads a bare `off` as `false` |
| `load-max-concurrent: 0` (unlimited) | `load-max-concurrent: unlimited` | `0` fails startup. |
| `LoadPolicy(maxConcurrent = 0)` | `LoadPolicy(maxConcurrent = null)`, or `unlimited()` inside `policy { … }` | `maxConcurrent` is an `Int?`, and `0` throws `IllegalArgumentException`. |
| `CachePolicy.DEFAULT`, `StoreSpec.DEFAULT_POLICY`, `VersionedStoreSpec.DEFAULT_POLICY`, `SetStoreSpec.DEFAULT_POLICY` | `DefaultPolicies.CACHE`, `VERSIONED_CACHE`, `STORE`, `VERSIONED_STORE`, `SET_STORE` | `CachePolicy.builder()` starts from `DefaultPolicies.CACHE`. |
| `policy = …` on a spec to change a few fields | `policy { … }` / `updatePolicy(…)` | A whole policy counts as writing every field, so `cocache.defaults` no longer applies to that cache. |
| — | `CacheLayers.apply(spec, locks)`, returning `Layered` (`spec`, `report`) | New. `CacheLayers.NONE` keeps only the declaration and runs the constraints. |
| `CoCacheProperties.policyFor`, `setPolicyFor`, `versionedPolicyFor`, `keyPrefixFor`, `PolicyProperties.applyTo` | Removed | The starter's `CacheAssembler` merges the layers. `CoCacheProperties.defaults` and `overrides` are `Layer(cache, store)`, and policy and `cocache.redis` durations bind as strings. |
| An undeclared name under `cocache.caches` always failed startup | Fails by default; `cocache.caches-validation: warn` only warns | A per-cache key that doesn't apply to the cache's type fails the same way. |
| Eviction check: `allkeys-*` and an unreadable policy failed only with `store-eviction-check: fail`; `volatile-*` with a finite `ttl` only warned, even with `fail` | `allkeys-*` and an unreadable policy without `assume-maxmemory-policy` always fail; `store-eviction-check` decides only `volatile-*` with a finite `ttl`, and `fail` fails there | `off` no longer skips the `allkeys-*` check. |

### Breaking changes

- Configuration is merged from three layers around the declaration: `cocache.defaults.<field>` is now `cocache.defaults.cache.<field>` or `cocache.defaults.store.<field>` and sits **below** the declaration, `cocache.overrides.*` only tightens, every duration needs a unit, and several keys and constants are renamed. See [three-layer configuration](#three-layer-config).
- Interface proxies come only from `CoCacheFactory.create(type, spec)`: `CacheProxies.create` is internal, and `CacheAssembler` no longer creates caches (it is the starter's `CacheLayers`). See [Libraries create caches through `CoCacheFactory`](#cocache-factory).
- The user API is split into `Cache`, `VersionedCache`, `Store`, `VersionedStore` and `SetStore`, and `cocache-coroutines` is removed: see [the user API is split by role](#api-types).
- A per-call loader on a suspending view (`suspending().get(key) { … }`) no longer runs on the caller's dispatcher or `Dispatchers.Default`: it runs inside the load, on CoCache's executor from its first suspension, and `load-timeout` cancels it. See [suspending views run on the cache core](#suspending-native).
- The baseline is JDK 21 (was JDK 17).
- Storage keys keep the 6.x shape `{keyPrefix}{namespace}:{encodedKey}`. The invalidation channel is `{keyPrefix}inv:{namespace}` (6.x: `cocache:inv:{namespace}`, without the prefix), so applications with the same namespace and different prefixes no longer receive each other's invalidations. In `pubsub` mode the startup probe uses `cocache:!probe:{a}` and `cocache:!probe:{b}` (6.x: `cocache:probe:{a}`, which is also a key of namespace `probe`). Internal keys live under `{keyPrefix}!`, which no namespace can produce, and `{keyPrefix}!meta:{namespace}` is reserved. See [storage keys](./caches.md#storage-keys).
- A namespace must match `[A-Za-z0-9._-]+`. One with `:` (or any other character outside the set) fails at creation with `IllegalArgumentException`, and the message shows how to move the prefix into `keyPrefix`: `namespace = "app:user"` becomes `keyPrefix = "cocache:app:"` and `namespace = "user"`. This makes the 6.x overlap between namespaces `a` and `a:b` impossible.
- Caches are registered by prefix and namespace. Two caches with the same namespace and different prefixes can now be created in one runtime. The duplicate error reads `Namespace[x] with keyPrefix[p] is already used by another cache.`
- Redis ACL: the channel pattern is `&{keyPrefix}inv:*` (`&cocache:inv:*` by default) and, in `pubsub` mode, `&cocache:!probe:*` (6.x: `&cocache:probe:*`); with a key prefix other than `cocache:`, the probe key also needs `~cocache:!probe:*`. With an empty `keyPrefix`, keys and channels share no prefix: list each namespace (`~{namespace}:*`, `&inv:{namespace}`), plus `~!*` and, for the probe, `~cocache:!probe:* &cocache:!probe:*`. A denied probe falls back to non-atomic broadcast with a `WARN` instead of failing startup. See [Redis ACL](../operations/index.md#acl).
- Creating a cache on Redis publishes one frame with no keys to the cache's channel, in the write script when broadcast is atomic. If the ACL denies that channel, creation fails with `IllegalStateException` naming the pattern to allow, instead of every write failing at its broadcast after it has changed Redis. Transient errors still fail as before, and a denial never downgrades the backend to non-atomic broadcast. `ExternalInvalidator` probes the same way the first time it uses a namespace.
- An invalidation frame that a channel can't read (unknown version, truncated, trailing bytes) now resets every cache on that channel, clearing its L2. 6.x logged a warning and dropped it, which lost an invalidation. Frame version `2` is reserved for a future namespace reset (`InvalidationFrame.NAMESPACE_RESET_VERSION`). A frame with no keys does nothing and isn't counted in `cocache.invalidations.received`.
- SPI: a `Keyspace(keyPrefix, namespace)` value type identifies a cache's keys and channel. `CoherenceBackend.store`, `InvalidationChannel.subscribe` / `publish` and `FutureInvalidationChannel.subscribe` / `publish` take a `Keyspace` instead of a namespace string. A backend names the channel from both parts, resets listeners on a frame it can't decode, and delivers frames with no keys. `Spec.keyspace` exposes a cache's keyspace, on every spec type.
- `LoadPolicy.asyncTimeout` is now `LoadPolicy.timeout`, and the property `load-async-timeout` is now `load-timeout` (default `30s`). It bounds more than async loads: readers waiting for another caller's load fail with `SourceLoadTimeoutException` once that load has been in flight that long, and later readers start a new load instead of waiting behind a stuck synchronous loader. In 6.x, a synchronous reader waited indefinitely. `cocache.load.timeouts` counts both kinds of timeout.
- `cocache-core` depends on `kotlinx-coroutines-core` at runtime. The blocking, async and suspending APIs of every type run on one coroutine implementation of the protocol. With assertions enabled (`-ea`, the default in test runs), kotlinx adds a coroutine suffix such as ` @coroutine#3` to thread names while a read runs, including inside a blocking loader.
- The runtime no longer starts a `cocache-timer` thread. Lease waits and load-permit waits are coroutine suspensions.
- The async and suspending views run on one executor: `cocache.executor` replaces `cocache.async-executor` and `cocache.blocking-executor`, `CacheRuntime.Builder.executor(…)` replaces `asyncExecutor(…)` and `blockingExecutor(…)`, and `CacheRuntime.executor` replaces `CacheRuntime.blockingExecutor`. Every continuation is dispatched to it, so it must tolerate blocking tasks. See [one executor](#one-executor).
- `LoadObserver.observeAsync` is removed: `observe` also sees async and suspending loads, called on an observation thread of CoCache's own. See [one executor](#one-executor).
- `cocache-redis` no longer contains the CoCache 5.x migrator: `CoCache5Migrator`, `CoCache5Migration`, `CoCache5Format`, `CoCache5KeyMapper`, `CoCache5MigrationOptions`, `CoCache5MigrationReport`, `CoCache5CacheReport` and `CoCache5MigrationException` (package `me.ahoo.cocache.redis.migration`) are removed. 8.x never needs to read 5.x data, so it doesn't carry the converter. Migrate 5.x data whose only copy is Redis with CoCache 6.x (6.5+) first; see [Migrating with `CoCache5Migrator`](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/guide/migration-6.md).
- Load permits (`load.max-concurrent`) are handed out in arrival order to sync and async callers alike. In 6.x, a blocking caller competed for a released permit directly and could take it before async callers already waiting.
- An async read in flight when its `CacheRuntime` closes fails with `IllegalStateException("CacheRuntime closed.")`. An async write in flight still finishes its Redis change, broadcast and local invalidation first, then completes with that exception if it succeeded, or with its own failure.
- A blocking call (`get`, `getAll`, `put`, `invalidate`, …) no longer blocks its thread on each Redis command. It sends commands the way the async API does and parks in `runBlocking` until they finish. As a result, interrupting the calling thread no longer abandons a Redis command. An interrupted caller can wait up to one more `command-timeout` (default `2s`). In 6.x the wait failed at once with `RedisException("Interrupted while waiting for Redis.")`: a write threw it wrapped in `CacheWriteException`, and a read treated it as a Redis failure (by default, loading from the source). A command in flight finishes or times out first. A read then releases any lease it took and doesn't call the loader, and the call throws `InterruptedException`. A call that completes before the interrupt takes effect, such as an L1 hit or a `put` whose command was in flight, returns as usual. Either way the interrupt flag stays set. A command abandoned on a timeout is still never sent later.
- **Wire format 2.** Every value 8.x writes is format 2, leases, negative entries and sets included ([wire format](../architecture/index.md#wire-format), [RFC 0005 §4.7](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)). Byte 3 of the 36-byte header, reserved in 6.x, holds flags, and segments follow the header: a version (8 bytes, flag bit 0) and an extension area of TLVs (flag bit 7). A versioned value keeps its version in that segment instead of the token slot, and the `VERSION_MARK` of 6.8 is gone. A set's `!f` field is `"2"`, and a `!m` field, when present and not `"0"`, makes the set unreadable. Values of a `Cache`, leases and negative entries are 36 bytes of header as before, and a versioned value adds its 8-byte version segment; each value a `Store` or `VersionedStore` writes carries a 28-byte writer id.
- 8.x doesn't read 6.x data. Format 1, the format of 6.x, is content the cache can't read, like content CoCache didn't write: a cache with a loader reclaims it and reloads from the source, and a cache without one fails the read. 8.x and 6.x instances can't share a cache. Format-2 content with a must-understand flag (bits 2–6) or a state this version doesn't know is a higher format, which the store reports as format 3: a cache with a loader loads around it, and nothing reclaims or overwrites it. A read grants a lease or a refresh token only on format-2 content without such a flag, a fill or release reads only the 36-byte header (`GETRANGE`), and a version segment never holds `Versioned.NO_VERSION`.
- Redis ACL: the scripts now call `GETRANGE`, `STRLEN` and `HMGET` and no longer call `HGET` or `HEXISTS`. Add `+getrange +strlen +hmget` to an existing user before you upgrade. See [Redis ACL](../operations/index.md#acl).
- `foreign-format` is removed: `CachePolicy.foreignFormat`, `CachePolicy.Builder.foreignFormat`, `ForeignFormatMode`, and the properties `cocache.defaults.foreign-format` and `cocache.caches.[name].foreign-format`. A read with a loader reclaims content the cache can't read and reloads it; a read without one fails with `CacheReadException`. In 6.x, `reclaim` on a cache without a loader deleted the only copy ([RFC 0005](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md) §4.7, §12 decision 6). Spring Boot ignores the property like any key it doesn't know.
- Conditional writes (`putIfMatch`, `deleteIfMatch`), versioned writes (`putIfNewer`, `invalidateIfNewer`, `deleteIfNewer`) and `SetStore.add` / `remove` always refuse content the cache can't read. They throw `CacheWriteException` with a `ForeignFormatException` cause and leave the key alone, and `getIfChanged` throws `CacheReadException` the same way. In 6.x, `foreign-format: reclaim` made them treat content CoCache didn't write (format 0) as absent: `putIfMatch(key, null, …)` and `putIfNewer` overwrote it, `getIfChanged` returned `Absent`, and `add` replaced the whole key with the new members.
- SPI: content the store can't read is never overwritten. Conditional and versioned writes have no `foreignAsAbsent` parameter and return `Unreadable` for such content, and the set-member writes `SetL1Store.addMembers` / `removeMembers` refuse it atomically and return its format (see the suspending L1 SPI below). The overwriting set-member writes of 6.x and the Redis scripts `ADD_MEMBERS` and `REMOVE_MEMBERS` are gone.
- SPI: the L1 SPI is suspending ([RFC 0005 §4.3](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)). `CoherenceBackend.store` returns an `L1Store`, whose operations are `suspend` functions. Conditional reads and writes, versioned writes and set-member writes moved to capability surfaces returned by `conditional()`, `versioned()` and `sets()`, which return `null` when the store doesn't support them. `CoherenceBackend.versionedWrites` is removed; `versioned()` replaces it. `InvalidationChannel.publish` is `suspend`; `subscribe` stays blocking. Java backends implement `FutureL1Store` and `FutureInvalidationChannel`, whose methods return `CompletableFuture`, and return `FutureL1Store.adapt(…)` and `FutureInvalidationChannel.adapt(…)`. Decorators extend `ForwardingL1Store`. A future the implementation cancels counts as an L1 failure, not as a cancellation of the caller. `L1Store` has no `close`: connections belong to the backend, and the runtime no longer closes stores. The engine never cancels an L1 call, so an implementation may wait with a cancellable `await()`. A backend that only implements the blocking `RemoteStore` no longer works, because the engine no longer runs such a store on the blocking executor. `RemoteStore`, `AsyncRemoteStore` and `AsyncInvalidationChannel` are removed.
- `CacheRuntime.create` checks the L1 capabilities a cache needs. A `Store` needs `conditional()`, a `VersionedCache` or `VersionedStore` needs `versioned()` and a `SetStore` needs `sets()`; when one is missing, creation fails with `UnsupportedOperationException` and registers nothing. The log line for each created cache names its type and lists what its namespace's store provides, for example `created as Store … L1 capabilities[conditional, versioned, sets]`. In 6.x, `getIfChanged` fell back to a plain read on such a store.
- SPI: the L1 result types are renamed: `RemoteRead` is now `L1Read`, `RemoteConditionalRead` / `RemoteConditionalWrite` are `L1ConditionalRead` / `L1ConditionalWrite`, and `RemoteVersionedWrite` is `L1VersionedWrite`.
- SPI: `Envelope.Scalar` has a `writer: Token?` (default `null`), a write id the engine sets on every scalar write of a `Store` or `VersionedStore`. A store saves it with every operation that writes a scalar (`put`, `putAll`, `fill`, `fillAll`, `putIfMatch`, `putIfNewer`) and returns it unchanged from `read` and `readAll`; it takes part in equality, not in the digest. `VersionedL1Store.putIfNewer` refuses `Versioned.NO_VERSION` with `IllegalArgumentException`. The engine reads only content in exactly `CoherenceBackend.format` and treats any lower format like format `0`, as content it can't read (6.x did so only for `0`); a higher format is loaded around as before. `InMemoryBackend.FORMAT` is `2`.
- `ForeignFormatException` takes the node's format: its constructor is `(cacheName, storageKey, format, nodeFormat, cause)`, and `newer` is computed from the two. The two 6.x constructors are removed. A `SetStore` that reads a set in a newer format throws this exception, still an `IllegalStateException`, instead of a plain `IllegalStateException`.
- SPI: `L1Store.fill` returns an `L1Fill` instead of a `Boolean`: `Filled(advanced)` when it wrote, `Rejected(currentVersion)` when it didn't. `fillAll` returns one per fill. A token mismatch is `Rejected(currentVersion = null)`; the version rule is described below. `reclaim` compares an SPI-owned `ContentFingerprint` (the SHA-1 of the content, computed by the script) instead of a `Digest`.
- SPI: `L1Store.read`, `readAll` and `ConditionalL1Store.readIfChanged` take a `timeout: Duration?` for that call. `null` means the store's own command timeout. A value must be positive and is capped at the command timeout, and the store bounds the call by the shorter of the two. `FutureL1Store`, its capability surfaces, `adapt` and the `Forwarding*` classes take the same parameter. The engine passes the cache's `CachePolicy.readTimeout` (see below).
- SPI contract: after any failure of a command that takes or returns a token (`read`, `readAll`, `fill`, `fillAll`, `release`, `reclaim`), the store releases that token asynchronously, unless the command was rejected before it was sent. A `RELEASE` with a token that no longer matches does nothing, so a late or repeated release is harmless. A store never reports a failure as `CancellationException`: on the JVM that is coroutine cancellation, and the engine would treat it as the caller cancelling.
- **Digest segment.** A value written by a `Store` carries the SHA-256 of its payload in its header (flag bit 1, 32 bytes), next to its writer id: such a value takes 60 bytes more than a value of a `Cache` ([wire format](../architecture/index.md#wire-format), [RFC 0005 §4.7](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)). CoCache computes it on the client and checks it whenever it receives the payload: a `Store` that reads a value whose payload doesn't match its digest, or a value that has no digest, fails the read with `CacheReadException` (`ForeignFormatException` cause) and leaves the key alone. A value with a digest never gets a refresh token and is never reclaimed. A `Cache` or `VersionedCache` that reads one counts it in the new `cocache.type.mismatch` meter (`CacheMetrics.typeMismatches`) and logs once: a `Store` shares the namespace.
- `Digest` is the SHA-256 of the payload and names its algorithm: `Digest(algorithm, bytes)` with `Digest.SHA_256`, `Digest(bytes)`, `Digest.ofPayload(bytes)`, `bytes`, and `toString()` printing `SHA-256:<hex>`. `Digest.sha1` is removed, and digests stored by 6.x (SHA-1) never match.
- Conditional reads and writes (`getIfChanged`, `putIfMatch`, `deleteIfMatch`) work only on values a `Store` wrote. A value without a digest (written by a `Cache`), a versioned value, a tombstone and an evicted versioned key are content they can't read: `getIfChanged` throws `CacheReadException` and `putIfMatch` throws `CacheWriteException`, both with a `ForeignFormatException` cause. `putIfMatch(key, null, value)` on a key without a value still writes. Their scripts read only the header (`GETRANGE` and `STRLEN`) and compare the stored digest, so an unchanged `getIfChanged` costs Redis the same whatever the value's size; Lua no longer reads the value or computes SHA-1. When `getIfChanged` returns a value, CoCache recomputes its digest and throws on a mismatch.
- **Versioned caches refresh ahead.** A versioned value gets a refresh token, and `cocache.defaults.cache.refresh-window` applies to a `VersionedCache` whose declaration doesn't write `refreshWindow`; a `VersionedCacheSpec` with `refreshWindow` is valid. A refresh writes back only a version at least as new as the one in Redis: when the loader returns an older version, the instance keeps its copy and tries again after a back-off. Set `refreshWindow = off()` (or `cocache.caches.[name].refresh-window: off`) on a versioned cache whose writer pushes every version.
- A versioned cache's local copy never moves back to an older version. When a refresh loses its token to another reader, or its write-back fails, and the loader returns an older version than the copy, the instance keeps the copy and retries after a back-off; the dropped fill counts in `cocache.stale.discards`.
- A versioned write-back (fill) that fails with an unknown outcome counts as a write: the instance bumps its stamps and drops its local copy (and publishes the invalidation when broadcast isn't atomic), because a timed-out or drained `FILL` can still advance the version later, after readers on that instance have already read the old one. Its late broadcast also drops copies on that instance on backends that broadcast atomically ([own messages after an unknown outcome](#own-frames)).
- Fills follow the version in Redis. A fill under a refresh token or lease on versioned content is refused when its version is older (or absent), and the token is cleared in the same script; an equal version renews the value without an invalidation message; a newer version writes and broadcasts, and the loading instance treats it as a write: it drops its local copy instead of filling L2 with it.
- The endpoint's per-key `DELETE` (`CacheAdmin.invalidateEncoded`) on a `VersionedCache` keeps the version: the key becomes an eviction marker (state `E`, 44 bytes, the tombstone's lifetime) that still refuses older versions. The next read takes a lease on it; if the loader is behind, its result is returned and kept in L2 for `degraded-local-max-age` but not written to Redis (`cocache.fill.rejections`). A failure throws instead of being retried in the background. See [`evict` and `purge`](./versioned-cache.md#evict).
- The endpoint's per-key `DELETE` refuses to delete the only copy unless a switch allows it: `Store` and `SetStore` need `management.endpoint.cocache.allow-store-delete=true`, `VersionedStore` needs `management.endpoint.cocache.allow-purge=true`. Without it the request returns `403` and changes nothing. See [deleting the only copy needs a switch](#endpoint-switches).
- A `Cache.put` or `putAll` whose outcome is unknown no longer throws under the default `invalidate-failure: retry`: it queues a compensating invalidation. See [compensated puts](#compensated-puts).
- **Fix:** `putIfNewer` and `invalidateIfNewer` publish the invalidation when Redis already holds the given version, without writing, on every backend; before, only the non-atomic path did. A load that filled that version onto a lease broadcast nothing, so another instance could keep an older copy until `local-max-age` even after the writer's own `putIfNewer` returned `Stale`. An older version still publishes nothing.
- SPI: `Envelope.Scalar` has a `digest: Digest?` (default `null`) that the engine sets with the writer id. A store saves it as the digest segment on every operation that writes a scalar and returns it unchanged from `read`, `readAll` and `readIfChanged`, without checking or recomputing it. `readIfChanged` and `putIfMatch` compare the stored digest and refuse a value without one and versioned content. `L1Fill` now carries the version rule: `Filled(advanced = true)` when the fill raised a version or replaced an eviction marker and the store broadcast it (a frame for the key whose origin is the fill token's node), `Rejected(currentVersion)` when the rule refused it. `VersionedL1Store` and `FutureVersionedL1Store` add `evictKeepingVersion(key, ttl, frame)`, returning the new `L1Eviction` (`Evicted`, `Authoritative`, `Unreadable(format)`). `L1ConditionalRead.Changed` has value equality.

- Spring: with `spring-tx` on the classpath, the starter's `cocacheCacheManager` is CoCache's `TransactionAwareSpringCacheManager`, and its caches are `TransactionAwareSpringCache`. It replaces Spring's `TransactionAwareCacheManagerProxy` and `TransactionAwareCacheDecorator`, so code that checks for or unwraps those types no longer finds them. Inside a transaction `@CacheEvict` now also invalidates on rollback, a later eviction of a key replaces an earlier `@CachePut` of it, and failures after the commit go to the `CacheErrorHandler` instead of being thrown. Without Spring Boot, wrap `CoCacheSpringCacheManager` in `TransactionAwareSpringCacheManager` instead of `TransactionAwareCacheManagerProxy`. See [Spring: CoCache's own transaction decorator](#spring-transaction-decorator).
- Spring: `TransactionalInvalidator` keeps one registration per transaction instead of one per instance. Invalidations of the same cache through several instances, and through `evictIfPresent`, merge into one `invalidateAll` after the commit, and their failures are thrown together (the first, with the rest as suppressed).
- Spring: `CoCacheSpringCacheManager(runtime, executor)` and `CoCacheSpringCacheManager.DEFAULT_EXECUTOR` are removed; use `CoCacheSpringCacheManager(runtime)`. `CoCacheSpringCache(cache, admin, executor)` stays: its executor runs the method body of `retrieve(key, loader)`. The property `cocache.spring-cache.retrieve-executor` is removed as well: Spring Boot no longer binds it, and setting it no longer logs a warning.

- `SourceLoadCancelledException` moved from `me.ahoo.cocache.coroutines` to `me.ahoo.cocache.core`, and it now covers every loader. A `CacheLoader` that throws its own `CancellationException`, or an `AsyncCacheLoader` whose future completes with one (a `withTimeout` inside it, an HTTP client that cancelled the request), fails with `SourceLoadCancelledException` for every caller that shares the load. The original exception is its `cause`. In 6.x those callers got the `CancellationException` itself, so an async caller's future looked cancelled.

- The starter no longer checks for the properties removed in 6.0 (`cocache.redis.strict-failure`, `cocache.redis.eviction-channel`, `cocache.redis.missing-guard-sentinel`, `cocache.metrics.client-side-hits`, `cocache.load.*`, `cocache.next.*`). They no longer fail startup with `CoCache 6.0 removed these properties`: like any unknown key, Spring Boot ignores them. Their entries in the configuration metadata are gone too.
- The runtime's default executor is always one virtual thread per task. The fallback for JDKs without virtual threads, a daemon pool of up to 512 `cocache-blocking-*` threads, is gone, and the starter no longer registers a native-image reflection hint for `Executors`.
- The wiki no longer carries the 5.x upgrade guide (`guide/migration-6`); the [6.x branch](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/guide/migration-6.md) keeps it. The `cocache-migrate` agent skill is removed, and 8.x ships no upgrade skill.

- SPI: `InvalidationListener.onInvalidated` receives **encoded keys**, the part of the storage key after `{keyPrefix}{namespace}:` (what `KeyEncoder` produces and `ExternalInvalidator` takes), not storage keys. A channel converts a frame's keys with the new `Keyspace.encodedKeys(…)`, which also drops keys outside the keyspace, and calls the listeners of a keyspace one after another in the order they subscribed, so a listener added after the cache was created runs after the cache has dropped its local copies. Code that subscribes to a channel itself and strips `"$namespace:"` from the keys must use them as they are now, or every notification is silently ignored. The `BackendProtocolSpec` case `listenersReceiveEncodedKeysInSubscriptionOrder` checks both rules.

- Spring: `CoCacheSpringCache.retrieve(key)`, which Spring calls for a `@Cacheable` method that returns `CompletableFuture`, `Mono` or `Flux`, fails with `CacheReadException` when Redis can't answer (`Lookup.Unknown`), like `get(key)`, instead of reporting a miss. Spring hands the failure to the `CacheErrorHandler` on all three kinds of method; `unknownRead = ABSENT` still reads it as a miss. See [When Redis fails](./spring-cache.md#failures).
- Spring: a cache operation (`@Cacheable`, `@CachePut` or `@CacheEvict`, with or without `sync`) that names a CoCache cache that isn't a `Cache` (`Store`, `VersionedStore`, `SetStore`, `VersionedCache`) fails startup. Before, it failed at its first call with "Cannot find cache". See [the startup check](./spring-cache.md#sync-check).
- Spring: loads inside a read-write Spring transaction are watched by default (see the next section). A rolled-back transaction now invalidates what it loaded, and a key evicted in a transaction loads privately in it: set `cocache.defaults.cache.invalidate-fills-on-rollback: false` and `cocache.defaults.cache.fill-in-transaction: allow` to keep 6.x behavior.

### New: loads inside a Spring transaction {#transactional-loads}

See [Spring Cache: loads inside a transaction](./spring-cache.md#transactional-loads).

- A synchronous read with a source (`@Cacheable(sync = true)`, `get`, `lookup`, `getAll`, `get(key, loader)`) inside a read-write transaction can load a row that the transaction wrote but never commits. CoCache now records every key such a load writes to Redis or L2, including a fill that only reached this instance's L2 because Redis failed, and invalidates them when the transaction rolls back, when its commit outcome is unknown, or when a `NESTED` transaction rolls back to its savepoint (`invalidate-fills-on-rollback`, on by default). In 6.x the row stayed in Redis until `ttl` unless the key had been evicted in the transaction.
- `fill-in-transaction` (default `registered-keys`): once a key is registered for invalidation in the transaction, loading it again there is private, with no lease, no write to Redis or L2, and no sharing with other callers. Registering also drops this instance's copy at once. `private` makes every load in a write transaction private, `allow` turns this off. Private loads count in the new `cocache.fills.gated` meter (`CacheMetrics.fillsGated`).
- When a caller inside such a transaction leads a load, concurrent callers on the same instance no longer take its result: they load for themselves.
- Both settings are per cache and layer like the policy fields: `cocache.defaults.cache.*`, `cocache.overrides.cache.*` (the stricter value wins: `private` over `registered-keys` over `allow`, `true` over `false`) and `cocache.caches.[name].*`, for `Cache` and `VersionedCache`. The endpoint's `policy` lists them with their sources. A cache with both off ignores transactions, as if no hook were installed. An unlayered cache uses the defaults of both.
- Read-only transactions, async and suspending calls, refresh-ahead and reactive transactions aren't watched. The transaction is read once, on the calling thread, when the read misses L2.
- Keys registered with the transaction match by namespace and encoded key, so a `TransactionalInvalidator` call through an interface cache's proxy also replaces a `@CachePut` of the same key made through Spring Cache.
- `cocache-core` adds the hook: `FillObserver`, `FillScope` and `FillKey`, set with `CacheRuntime.Builder.fillObserver(…)` (default `FillObserver.NONE`). The engine calls `FillObserver.enter(cacheName)`, which defaults to `enter()`. `cocache-spring` implements it as `TransactionalFillObserver(Predicate<String>, Function<String, FillInTransaction>)`, both looked up by cache name. The starter registers it as `cocacheFillObserver` when `spring-tx` is present, reading `TransactionalFillModes`.

### New: per-cache read timeout and broadcast-only invalidation

- `CachePolicy.readTimeout` (`CachePolicy.Builder.readTimeout(…)`, unset by default) bounds every Redis read of the cache (`get`, `getAll`, `getIfChanged`). The store caps it at its command timeout. A read that runs out is a failed Redis read: a read with a loader follows `read-failure`, a read without one is `Unknown` (`unknownRead` decides), `getIfChanged` throws, and any lease the read was granted is released. Use it on a cache that can't fall back to a loader but must not wait a full `command-timeout` for a stalled Redis, such as a revocation list. Its configuration key is `read-timeout` ([three-layer configuration](#three-layer-config)). `EngineSpec` has an optional `pause()` hook whose case `readTimeoutBoundsEveryL1Read` checks it.
- `ExternalInvalidator.broadcast(namespace, encodedKeys)` publishes the invalidation message without touching Redis: every instance drops its local copy and reloads from Redis. Use it when Redis already holds the right value. `POST /actuator/cocache/{name}/{key}` does the same for one key from the endpoint (`CacheAdmin.broadcastEncoded`): it needs no switch on any type, only write access to the endpoint ([broadcasting an invalidation](../operations/observability.md#broadcast-key)). `invalidate` deletes the key, which on the namespace of a cache without a loader deletes the only copy.
- Creating a cache with an empty `keyPrefix` logs a warning: its keys and channel share no prefix, so ACLs and prefix-based operations must name each namespace.

### Behavior change: suspending views run on the cache core {#suspending-native}

The suspending views no longer wrap the async views; they call the cache core directly ([RFC 0005 §4.4](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)).

- An L2 hit returns without suspending, switching dispatchers or allocating a future.
- A miss, a write or a conditional read runs on CoCache's executor from its first suspension, and the caller resumes from a CoCache thread, never from a Redis client thread. A caller on `Dispatchers.Unconfined` (Spring WebFlux handlers) therefore no longer risks continuing on Lettuce's event loop.
- The caller's context elements (`ThreadContextElement`s such as a tracing context or `MDCContext`, the coroutine name) reach the shared load, without the caller's `Job` and dispatcher. A spec-level `AsyncCacheLoader` now sees them too.
- A per-call loader runs inside the load: it starts on the caller's thread and continues on CoCache's executor (before: on the caller's dispatcher, or `Dispatchers.Default` without one or on a `runBlocking` event loop). `load-timeout` cancels it, and so does closing the runtime. Wrap blocking calls in `withContext(Dispatchers.IO)`.
- The suspending methods throw the original exception instance, also in coroutine debug mode.

### Behavior change: one executor for the async and suspending views {#one-executor}

The async and the blocking executor are merged into one ([RFC 0005 §4.4, §5](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)).

- `cocache.executor` replaces `cocache.async-executor` and `cocache.blocking-executor`. It names an `Executor` bean and follows the rules of `cocache.refresh-executor`: it is resolved by name only, startup fails when the bean is missing or isn't an `Executor`, the container owns it (`CacheRuntime.close()` doesn't shut it down), and it is destroyed after the runtime. Spring Boot's `applicationTaskExecutor` can still be named: `cocache.executor=applicationTaskExecutor`. Without Spring, `CacheRuntime.Builder.executor(…)` (Kotlin DSL: `executor = …`) replaces `asyncExecutor(…)` and `blockingExecutor(…)`, and `CacheRuntime.executor` replaces `CacheRuntime.blockingExecutor`.
- Unset, the runtime starts one virtual thread per task, named `cocache-worker-N`. Before, it was a `ForkJoinPool` (`cocache-async-*`) for continuations and unnamed virtual threads for blocking loaders.
- It runs every step of the async and suspending views: every resume after a Redis command or a loader's future completes is a new task on it, never a continuation in place on the thread that completed the step (a Lettuce I/O thread, the loader's own thread). The shortcut that ran a continuation in place when the step completed on the executor is gone. It also runs shared loads, synchronous loaders called through the async and suspending views, and the method body of Spring Cache's `retrieve(key, loader)` (`@Cacheable(sync = true)` on an async or reactive method). The blocking API never uses it.
- It must therefore tolerate blocking tasks: don't give it a queue or only a few threads. On JDK 21–23, a loader that blocks inside `synchronized` pins the carrier thread of its virtual thread (JEP 491 fixes this in JDK 24), so prefer JDK 24 or later, or a platform thread pool. See [Executors](./configuration.md#executors).
- `LoadObserver.observeAsync` is removed; `LoadObserver` has only `observe`. For an async or suspending load, CoCache calls `observe` on a virtual thread of its own, one per observed load (`cocache-observe-N`), never on the caller's thread and not on `cocache.executor`. That thread waits while the whole load runs, so the observation covers the load until the loader's future completes, and an engine timeout is recorded as outcome `timeout`. The load starts on that thread, so the synchronous part of `loadAsync` sees the observer's thread-local state, such as Micrometer's current `Observation`, and trace context can propagate. An observer that implements only `observe` now sees async loads too; before, it saw none. `MicrometerLoadObserver` has no async override any more, and its tags are unchanged: `cocache.load` with `cache`, `operation`, `outcome` (`success`, `error`, `timeout`) and `key.count`.

### Behavior change: closing the runtime waits for in-flight Redis steps

- `CacheRuntime.close()` now waits up to `closeTimeout` before it shuts down its executors: 5 seconds by default (`CacheRuntime.Builder.closeTimeout`). The Spring Boot starter sets it to twice `cocache.redis.command-timeout`, and at least 5 seconds, so a command timeout above 5 seconds no longer lets `close()` return while a command is still in flight. In that time, async calls in flight finish the Redis steps they can't skip: releasing a lease, the broadcast after a write on a backend without atomic broadcast, and commands already sent. Close the backend after the runtime. The Spring Boot starter already does that, because the runtime bean depends on the backend. Before, `close()` returned at once, and a backend closed right after it could fail those steps. Other nodes then kept their stale local copy until `local-max-age`, and the lease stayed until `lease-ttl`. Synchronous calls run on their callers' threads and aren't waited for.

### Behavior change: outcome-unknown puts are compensated {#compensated-puts}

- A `Cache.put` or `putAll` whose outcome is unknown (a timeout, a connection dropped mid-reply) no longer throws when `invalidate-failure` is `retry`, the default for a derived cache. Its value may be in Redis now or later, so CoCache queues an invalidation of those keys on the same retry queue as a failed `invalidate`, and the keys converge to "reload from the source" ([RFC 0005 §4.6](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)). While it is pending, misses on that instance skip Redis. If the late put lands after the compensating delete, its value stays until its TTL.
- A put that definitely didn't run (`CacheWriteException.outcomeUnknown = false`) still throws and queues nothing. With `invalidate-failure: throw`, every failed put throws. A `Store` or `SetStore` write is never compensated.
- The warning reads `Cache[…] - put […] failed, retrying the invalidation.` (`invalidate […] failed, retrying the invalidation.` for a failed `invalidate`).

### New: `@CoCache(unknownRead)`

- `@CoCache(unknownRead = CoCache.UnknownRead.ABSENT)` sets the spec's `unknownRead` on an interface declaration: a read without a loader returns `null` when Redis fails, instead of throwing ([`Unknown`](./caches.md#unknown)). It applies to `Store`, `VersionedStore` and `Cache` with `source = EXTERNAL`; on any other interface `ABSENT` fails startup.

### Behavior change: deleting the only copy needs a switch {#endpoint-switches}

- `DELETE /actuator/cocache/{name}/{key}` on a `Store` or `SetStore` needs `management.endpoint.cocache.allow-store-delete=true`, and on a `VersionedStore` (a purge) `management.endpoint.cocache.allow-purge=true`. Both default to `false`; without the switch the request returns `403`, names the switch, and changes nothing. Invalidating a `Cache` key and evicting a `VersionedCache` key need no switch ([Observability](../operations/observability.md#invalidate-key)).
- `DELETE /actuator/cocache/{name}/{key}?purge=true` purges a `VersionedCache` or `VersionedStore` key, dropping its version; it needs `allow-purge`. Other types return `400`.
- The per-key `DELETE` report has a new field `deletion` (`INVALIDATE`, `EVICT`, `DELETE`, `PURGE` or `CLEAR`), and `CacheAdmin` has `keyDeletion` and `purgeEncoded(encodedKey)`.

### Behavior change: readers behind an interrupted load load for themselves

- When several readers wait on the same load and the thread running it is interrupted (or its coroutine is cancelled), that thread still gets the original exception. The waiting readers no longer fail with it: each retries once and, if it joins another interrupted load, loads by itself. Batch reads do the same for the affected keys. In 6.x, every waiting reader failed with the interrupted thread's exception.
- A load counts as interrupted only when the thread running it is interrupted or its coroutine is cancelled. Any other failure is shared with the waiting readers, as in 6.x. That includes the loader's own `CancellationException` (see `SourceLoadCancelledException` above) and an `InterruptedException` from a thread other than the caller's.

### Performance: compiled key templates

- `@CoCache(keyExpression)` and `@JoinCacheable(joinKeyExpression)` templates are evaluated in SpEL's compiled mode (`SpelCompilerMode.MIXED`), since they run on every call. A template is interpreted until it has been evaluated a number of times, then compiled to bytecode. One that can't compile (projection or selection, for example) stays interpreted, and compiled code that fails, for example because the key's class changed, falls back to interpreting. Results are the same as before. Native images always interpret.

### Performance: L2 hits no longer build the Redis key

- The local (L2) copy is keyed by the encoded key instead of the full Redis key (`{keyPrefix}{namespace}:{key}`), so a read served by L2 no longer concatenates and hashes a new string. On the hit-path benchmark, an L2 hit on one cache runs at about 2.6× the previous throughput (sync, async and interface proxy alike), and alternating between three differently configured caches is 68% faster on one thread and 8% faster on eight. L1 hits are unchanged. Keys and invalidation messages in Redis are unchanged.

### Fix: closing the runtime no longer abandons an async read's Redis command

- When a `CacheRuntime` closed while an async read waited for Redis, the read stopped waiting at once and sent a release without waiting for it. A backend that doesn't apply commands in the order they were sent (or a Redis Cluster redirect) could apply the release first, and the lease the read then took stayed until `lease-ttl`; misses on that key from other instances waited `lease-wait` meanwhile. The read now waits for its command, releases what it took, then fails with `IllegalStateException("CacheRuntime closed.")` as before. This also covers a lease taken by a lease-wait reread when a `LoadObserver` is configured.

### Fix: no stale local copy after a write returns

- A load that read Redis just before a concurrent write could still put the old value into the local (L2) copy after the write returned, briefly, until its own recheck removed it. A read on the same instance right after the write could see the old value. The local copy is now checked and written in one step under the key's lock, so once a write returns, no older value can appear in that instance's local copy.

### Fix: a late write's own broadcast drops the writer's stale copy {#own-frames}

- A write whose outcome is unknown (a `put`, `putAll`, `invalidate`, set write or versioned write that timed out or lost its connection, but not one rejected before it was sent) may still run later and broadcast its invalidation. The writing instance skipped that message because it came from itself, so a stale copy it had loaded between the failure and the late write stayed in its L2 until `local-max-age`. Now, for `local-max-age` after the failure, the instance handles its own messages for those keys like another instance's: it drops its copy. These messages don't count in `cocache.invalidations.received`. A versioned write-back (fill) with an unknown outcome is covered the same way ([RFC 0005 §4.6](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)).
- Only on backends that broadcast atomically (the Redis default). With `channel-mode: sharded`, or when the atomic-broadcast probe failed, a late command carries no broadcast, and `local-max-age` remains the bound.

### Fix: `SetStore` batch reads read Redis in one batch

- `getAll` and `lookupAll` on a `SetStore` (synchronous, async and suspending views) read every key that missed L2 with one `readAll`, merged with in-flight reads of the same keys, instead of one Redis read per key ([RFC 0005 §9](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0005-v8-target-architecture.md)). A key whose content can't be read still fails the call, after the other keys are filled.

### New: `cocache.refresh.skipped`

- A counter of refresh-ahead runs that were due but didn't happen: another instance held the refresh token, so this one only re-read Redis and backed off; or a copy got no refresh point although the cache refreshes ahead, because its remaining lifetime was no longer than `refresh-window` or refreshing it would only read the same content. `CacheMetrics.refreshSkipped`; see [refresh meters](../operations/observability.md).

### Fix: a failed reload in a batch read no longer strands the rest of the batch

- `getAll` loads and fills the whole batch first, then reclaims corrupt keys one by one. If a corrupt key's reload fails, the other keys are still written back to Redis (or their leases released), and the batch then fails with the first error. In 6.x, the blocking path reclaimed corrupt keys first and stopped at the first failure, leaving the other keys' leases until they expired, so other instances waited `lease.wait` on those keys.

### Fix: Spring transactions no longer lose an invalidation

Also fixed on the `6.x` branch. See [Spring Cache transactions](./spring-cache.md#transactions).

- **`@CacheEvict(beforeInvocation = true)` inside a transaction.** Spring calls `evictIfPresent` for it, and its `TransactionAwareCacheDecorator` passes that call straight through, so the key was invalidated only before the commit. A read on another instance between the eviction and the commit could load the committed old row back into Redis, and nothing invalidated it afterwards, so every instance read the old value until `ttl`. `evictIfPresent` still invalidates at once, as Spring requires, and inside a transaction it now also registers the key with `TransactionalInvalidator`, so the commit, a rollback, or a rollback to a savepoint invalidates it again. Without a transaction, or without `spring-tx`, nothing changes.
- **Rollback to a savepoint (`NESTED`).** When a nested transaction rolls back to its savepoint, Spring doesn't call `afterCompletion` on the outer transaction's synchronizations, and the outer commit later reports a commit. An uncommitted row that a read inside the nested part had put into Redis stayed visible to every instance, and to the outer transaction itself, until the outer transaction ended. `TransactionalInvalidator` now handles `savepointRollback` (Spring 6.2+): it invalidates every key registered so far in the transaction, keeps them registered, and invalidates them again when the transaction ends. A failure there is logged, not thrown: Spring calls `savepointRollback` before it rolls the database back to the savepoint, and an exception would skip that rollback.
- On the `6.x` branch, a `@CachePut` inside a nested transaction that rolls back to its savepoint still writes its value when the outer transaction commits. 8.0 drops it; see the next section.

### Spring: CoCache's own transaction decorator {#spring-transaction-decorator}

See [Spring Cache transactions](./spring-cache.md#transactions).

- **`@CacheEvict` invalidates on rollback too.** Spring's decorator ran it only after the commit. A `@Cacheable(sync = true)` read in the same transaction could load the uncommitted row into Redis, and after a rollback that row stayed until `ttl`. The 6.9.0 changelog listed this as not covered. The key is now registered with the transaction and invalidated after the commit and after a rollback.
- **A later eviction wins.** At the commit, the registered invalidations run first, then the deferred `@CachePut` writes that weren't followed by an invalidation of the same key. A `@CachePut` and then a `@CacheEvict` of one key end with the key evicted.
- **Savepoints.** When a `NESTED` transaction or a programmatic savepoint rolls back, writes registered after the savepoint are dropped. Before, a `@CachePut` inside a rolled-back nested transaction was still written at the outer commit.
- **Failures after the commit go to the `CacheErrorHandler`** (the cache interceptor's, as outside a transaction) instead of being thrown to a caller whose data is already committed and who would retry. If the handler rethrows, as Spring's default does, the exception is logged at `ERROR` and not thrown. Spring then also runs the other synchronizations' `afterCommit`, which a thrown exception skipped.
- `@CacheEvict(allEntries = true)` still clears only this instance's L2, and now logs a `WARN` the first time on each cache. Clearing a cache cluster-wide comes with the namespace reset in a later 8.x.
- At startup, the starter logs a `WARN` when the cache advice and the transaction advice have the same order. Both default to `LOWEST_PRECEDENCE`, so which one is outermost depends on registration order. Declare `@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)` to put the transaction outermost. See [Transaction advice outermost](./spring-cache.md#advice-order).

### Spring: an optional degrading `CacheErrorHandler`

- `cocache.spring-cache.degrade-on-error: true` gives Spring's cache interceptor a `DegradingCacheErrorHandler`. For CoCache caches only, read and write failures are logged and not rethrown, so a non-sync `@Cacheable` runs its method while Redis is down, as `sync = true` already did. Failed invalidations and clears, and failures on other caches, still go to the handler configured before, which becomes its delegate. See [When Redis fails](./spring-cache.md#failures).

### Redis: the store is natively suspending

- `RedisBackend.store` returns a native suspending store. It sends Lettuce's async commands and waits without holding a thread; `readAll` and `fillAll` send every command first and wait for all of them together. The invalidation channel's `publish` suspends the same way. Commands, scripts and the wire format are unchanged.
- **Lettuce cancellations are Redis failures.** A command Lettuce cancels when it resets or closes a connection now fails as `RedisException`, with the cancellation as its cause. In 6.x it reached the core as a `CancellationException`, which the core took for the caller's own cancellation: a read didn't fall back to the source, and readers waiting on it loaded for themselves.
- **Leases come back sooner after a dropped connection.** A read, reclaim, fill or release that fails for any reason except a rejection before it was sent now releases its token. A release rejected while the connection is down is resent with backoff (100 ms doubling to 2 s) until it runs, the token expires or the backend closes. In 6.x only a timeout or an interrupt released the token, so a lease taken by a read that was in flight when the connection dropped stayed until `lease-ttl`, and misses on that key across the cluster waited `lease-wait` first.
- **Fix: a batch read that fails on a dropped connection releases the other leases.** Lettuce fails every command in flight with one shared exception. In 6.x, `getAll` then threw `IllegalArgumentException: Self-suppression not permitted` instead of the Redis error and left the leases the other reads in the batch had taken until `lease-ttl`. Batch writes (`putAll`, `invalidate`) had the same `IllegalArgumentException`. Now the Redis error is thrown and the leases are released.
- Reading a reply (parsing the header, copying the value or the set members) happens on the caller's thread for the blocking API and on the runtime's executor for the async API, no longer on a Lettuce I/O thread.
- A batch write (`putAll`, `invalidate`) that spans several groups sends all of them at once, so a failing batch waits about one `command-timeout` instead of one per group.

### TCK

- **Breaking:** every suite (`BackendProtocolSpec`, `EngineSpec`, `SetEngineSpec`, `MultiNodeSpec`, `FaultSpec`, `VersionedSpec`) has an abstract `expectedCapabilities: Set<L1Capability>` (`CONDITIONAL`, `VERSIONED`, `SETS`) with no default, so a subclass that doesn't declare it no longer compiles. Each suite's new `storeProvidesExactlyTheDeclaredCapabilities` fails when the store provides a capability you didn't declare or lacks one you did, or when `conditional()`, `versioned()` or `sets()` returns a different object on another call. Cases that need a capability are skipped only when it isn't declared: `SetEngineSpec` and `VersionedSpec` skip every case without `SETS` or `VERSIONED`. `EngineSpec.supportsConditionalWrites` is removed; leave `CONDITIONAL` out instead. `L1Capability` exists only in the TCK: the store's accessors remain the only way to state a capability. See [Declaring capabilities](../architecture/extending.md#expected-capabilities).
- `FaultSpec.cancelledCommandsAreL1FaultsNotCancellations` checks commands the client under your store cancels (a future your `FutureL1Store` cancels, a Lettuce connection reset): the read falls back to the loader and serves the readers waiting on it, `put` fails with `CacheWriteException`, no caller sees a `CancellationException`, and the cancelled read's lease is gone once L1 is back. It runs when you override the new `commandCancellation()` hook, which returns a `CommandCancellation` (`hold`, `cancel`, `close`); the default `null` skips it. CoCache runs it on the in-memory backend through `FutureL1Store.adapt` and on standalone Redis. `HookedBackend.puts` counts single-key puts.
- CoCache runs the whole TCK on its in-memory backend through `FutureL1Store.adapt`, the path a Java backend takes, and on a store with no optional capability. On Redis, the async view reruns each engine suite once, on classic Pub/Sub, since both views now call the same store.
- **Breaking:** the TCK drops `AsyncEngineSpec`, `AsyncSetEngineSpec`, `AsyncMultiNodeSpec`, `AsyncFaultSpec`, and `AsyncVersionedSpec`. Extend `EngineSpec`, `SetEngineSpec`, `MultiNodeSpec`, `FaultSpec`, or `VersionedSpec` instead; to also run a suite through the async view, override `access = CacheAccess.ASYNC`. The async-only cases (cancellation, completion threads, waits that hold no threads, timeouts, reentrancy) test the core, not a backend, and now live in CoCache's own tests.
- **Breaking:** `BackendProtocolSpec` takes the suspending shape. Its cases call `L1Store` directly through `Suspending.await` (new in the TCK; it uses only the Kotlin standard library), and the set-member cases need `sets()`. `AsyncBackendProtocolSpec` is removed; a Java backend runs `BackendProtocolSpec` on the store that `FutureL1Store.adapt` returns. `HookedBackend` wraps the store in `ForwardingL1Store` and the `Forwarding*` surfaces and keeps only the capability surfaces the wrapped store has. Its hooks run in the calling coroutine after the wrapped store returns, and `ioExecutor` delivers the result of every call. `HookedBackend.versionedWrites` is gone with the SPI property.
- **Breaking:** the TCK follows the SPI changes above: `L1Fill`, `ContentFingerprint`, the `timeout` parameter and the `L1*` result names. `HookedBackend.afterFill` receives the `L1Fill` result instead of a `Boolean`.
- **Breaking:** the TCK follows the digest segment and state `E`. The new `BackendProtocolSpec.segmentsRoundTripThroughEveryScalarWrite` checks that a store keeps `Envelope.Scalar.writer` and `digest`, and `version` when it declares `VERSIONED`, through `put`, `putAll`, `fill`, `fillAll`, `putIfMatch` and `putIfNewer`, and returns them from `read` and `readAll`; other new cases check that a value with a digest gets no refresh token and is never reclaimed, that conditional reads and writes compare the stored digest and refuse a value without one, and that a lease on `E` is taken over after its lease lifetime. `VersionedSpec.versionedContentIsNeverRefreshedOrReclaimed` is now `versionedContentIsRefreshedButNeverReclaimed`, and new cases cover the fill's version rules, every script on `E`, `evictKeepingVersion`, refresh-ahead on versioned caches and the per-key eviction. `EngineSpec` checks that only a `Store` writes a digest, that its reads fail on a tampered or missing digest, that a `Cache` counts a type mismatch, and that conditional reads verify the value they return; `writeRemoteSilently` takes an optional `digest`, and `writeAuthoritativeSilently` writes a value with one.
- `BackendProtocolSpec` has a `pause()` hook, and its new case `readTimeoutBoundsTheCallAndReleasesItsLease` checks that, while L1 stalls, a read with a short `timeout` fails as an L1 failure in about that time, and that the lease it asked for is released afterwards. The case is skipped when `pause()` returns `null`, the default. `InMemoryBackend` has `stall()` and a `commandTimeout` constructor parameter for it: while stalled, operations wait up to their time limit, and one that times out never runs.
- `FaultSpec.lateOutcomeUnknownPutEvictsTheWritersOwnStaleCopy` covers [the own-message fix](#own-frames) on backends that broadcast atomically and is skipped on the others. `HookedBackend.deferPuts` fails single-key puts with an unknown outcome and holds them; `applyDeferredPuts()` then runs them, broadcast included.
- `SetEngineSpec.getAllReadsTheMissesInOneBatch` checks that a `SetStore` batch read sends one `readAll` for the keys that missed L2 and no single-key reads, so a backend's `readAll` must read set keys the same way `read` does.
- `Concurrency.currentThreadName()` returns the thread name without the kotlinx coroutine suffix. `HookedBackend.issuer()` and the specs use it, so hooks that orchestrate races by thread name work whether or not assertions are enabled.
- `EngineSpec.corruptKeyFailingItsReloadStillCompletesTheRestOfTheBatch` covers the batch fix above, and `EngineSpec.getAllAfterOwnInvalidateNeverSharesALoadStartedBefore` covers read-your-writes for batch reads.
- `Concurrency.Forked.parked` is true while the thread waits (`WAITING` or `TIMED_WAITING`). A blocking follower now waits inside `runBlocking`, which parks with a timeout, so the specs no longer expect `WAITING` only.
- `BackendProtocolSpec` writes set members with the check-and-write operations (`SetL1Store.addMembers` / `removeMembers`). The new `memberWritesRefuseUnreadableContentAndKeepIt` checks that a member write on a scalar value returns its format and changes nothing; it replaces `removeMembersOnScalarIsNoOp`, and `membersAreAddedAtomicallyOverAnyNonSetState` is now `membersAreAddedAtomicallyOverLeases`.
- `EngineSpec` and `SetEngineSpec` drop the `reclaim` cases on caches without a loader. `EngineSpec.conditionalOperationsFailOnUnreadableContent` covers conditional reads and writes and checks that they never reclaim. With `foreign-format` removed, `EngineSpec` drops `foreignContentWithoutLoaderIsReclaimedWhenConfigured` and `foreignContentFailsWithLoaderWhenConfigured`. `SetEngineSpec.unrecognizedContentFailsByDefault` and `SetEngineSpec.memberWritesOverUnreadableContentFailByDefault` lose their `ByDefault` suffix.
- `BackendProtocolSpec.putIfNewerRejectsNoVersion` checks that `putIfNewer` refuses `Versioned.NO_VERSION` and changes nothing. `EngineSpec.onlyStoresWriteAWriterIdAndADigest` and `VersionedSpec.onlyVersionedStoresWriteAWriterId` check that only a `Store` or `VersionedStore` writes one.

## v6.10.0 (Current)

**Module Group:** `me.ahoo.cocache` · Minor release, because it adds a configuration property and `CacheRuntime.policy`, and the last 6.x feature release: later 6.x releases only fix defects. Fixes the remaining 6.x items from the 6.8.0 architecture review and isolates failing invalidation listeners. No API is removed. The Redis wire format and the invalidation channel are unchanged, so 6.10.0 instances can share Redis with any 6.x instance and be upgraded one at a time.

### Behavior change: the `SetCache` eviction check no longer skips silently

- The startup check reads `maxmemory-policy` from `INFO memory` instead of `CONFIG GET`. Managed services that block `CONFIG` usually allow `INFO`. With a `SetCache`, the Redis user needs `+info`; the [ACL starting point](../operations/index.md#acl) now includes it.
- If the policy still can't be read, the check no longer skips silently. It uses the new `cocache.redis.assume-maxmemory-policy` when set. Otherwise it logs a warning, or stops startup with `cocache.redis.set-cache-eviction-check: fail`. To keep `fail` mode on a Redis that denies `INFO`, grant `+info` or set `assume-maxmemory-policy` to the policy of your deployment.
- It checks every declared `SetCache`, including lazy beans that aren't created at startup (for example with `spring.main.lazy-initialization=true`). Their `ttl` comes from the same annotation, customizers and properties as at creation. The check doesn't create them.
- Under `volatile-*` it warns about each `SetCache` with a finite `ttl`, which Redis can evict before it expires. This is only a warning, also in `fail` mode.
- See [maxmemory-policy](../operations/index.md#maxmemory-policy).

### Behavior change: endpoint `DELETE` on an unknown cache returns `404`

- `DELETE /actuator/cocache/{name}` and `DELETE /actuator/cocache/{name}/{key}` return `404` for an unknown cache name instead of `204`, so a misspelled name no longer looks like success. Known caches still return `204` and `200`.
- `CoCacheEndpoint.clearLocal` returns `Boolean` (whether the cache exists) instead of `Unit`. Over JMX the operation returns that value.

### Fix: a failing invalidation listener no longer starves the others

- An invalidation listener that throws an `Error`, or a checked exception thrown from Kotlin, no longer keeps the listeners after it from receiving that message. In 6.9.1 and earlier the Redis channel caught only `RuntimeException` around each listener. It now catches every `Throwable`, logs it and goes on, and rethrows only a `VirtualMachineError`. Resets were already isolated.
- The in-memory backend (`InMemoryBackend`, for tests) isolates listeners the same way. A listener's exception no longer reaches the caller whose `put` or `invalidate` published the message.

### Fix: an interrupted waiter stops waiting

- A synchronous call that joins another caller's load of the same key (`get`, `getAll`) now responds to interruption. It throws `InterruptedException` with its interrupt flag still set, and only that caller stops waiting: the load continues, and the other waiters still get its result. In 6.9.1 and earlier the waiter ignored interruption until the load finished, so `Future.cancel(true)`, a `TimeLimiter` or `ExecutorService.shutdownNow` couldn't stop it.
- A call through a `@CoCache` interface receives that `InterruptedException` wrapped in `UndeclaredThrowableException`, as the JDK does for any checked exception an interface method doesn't declare (see below).

### Fix: L2 copies ignore wall-clock steps

- The default clock reads the wall clock once at startup and then advances by `System.nanoTime()`. In 6.9.1 and earlier it re-read the wall clock every 100 ms, so stepping the clock back by an hour (an NTP correction, a manual change) kept L2 copies alive for an extra hour, and stepping it forward expired them early. The hit path still does one volatile read.

### Fix: a failed cache creation can be retried

- When creating a cache failed in `backend.store` or while constructing the engine, its namespace stayed taken, so retrying the same cache failed with `Namespace[…] is already used by another cache.` The namespace is now released, and a store that was already created is closed.

### Docs

- `Subscription.close()` doesn't wait for callbacks already being delivered, so such a callback can arrive after `close()` returns. It only guarantees that messages and resets arriving after it are not delivered. See [Extending CoCache](../architecture/extending.md).
- [Deployment](../operations/index.md#channel-mode) now spells out what non-atomic broadcast (`sharded` mode, or after the probe fell back) means when the publish fails after the write succeeded.
- `@CoCache` interface proxies pass exceptions through unwrapped, except where the JDK requires otherwise: a checked exception the interface method doesn't declare, such as an `IOException` thrown by a Kotlin loader, arrives wrapped in `UndeclaredThrowableException`, with the original as its `cause`. Caches injected from a spec are the engine itself and are unaffected. See [Architecture](../architecture/index.md).

### API

- `CacheRuntime.policy(name)` returns the effective `CachePolicy` of a created cache.
- New property `cocache.redis.assume-maxmemory-policy` (see [Configuration](./configuration.md)).

## v6.9.1

**Module Group:** `me.ahoo.cocache` · Patch release that completes two Redis fixes from 6.9.0 on paths they missed, found by the second review round of RFC 0005 (appendix B). No API, wire format or channel changes, so 6.9.1 instances can share Redis with any 6.x instance and be upgraded one at a time.

### Fix: interrupted Redis calls

- **Interrupted scripts.** A script whose caller is interrupted while waiting for Redis is handled like a timeout: it is never written to Redis afterwards, and the call fails with `RedisException("Interrupted while waiting for Redis.")` with the interrupt flag still set. In 6.9.0 an interrupted `put` could still run later, in Redis Cluster while it waited for a node connection, and overwrite a newer value. Interrupts come from `Future.cancel(true)`, Resilience4j `TimeLimiter`, or `ExecutorService.shutdownNow`. Once the flag is set, every later wait on that thread fails at once in the same way, such as the remaining keys of a `getAll`.
- **Leases after an interrupted read.** A read whose wait is interrupted releases, in the background and best effort, the lease or refresh token it may have been granted, as a timed-out read already did. For `getAll`, this covers every key that hadn't returned yet. In 6.9.0 each such key kept its lease for `lease-ttl`, and other instances waited `lease-wait` on every miss of it.

### Fix: leases after a failed fill

- A fill (the write-back of a loaded value) that times out or whose wait is interrupted releases its lease in the background, best effort. In 6.9.0 a fill dropped before it was sent kept its lease for `lease-ttl`. If the fill already ran, the release finds no matching token and changes nothing. A fill rejected while disconnected still leaves its lease to `lease-ttl`.
- A batch fill (`getAll`) stops at its first failure and gives up the rest: it releases their leases instead of waiting for each, so a failing batch still waits at most one `command-timeout`.
- A lease release that times out or is interrupted is sent again, since it may have been dropped before it was sent.

## v6.9.0

**Module Group:** `me.ahoo.cocache` · Minor release with two default changes for caches without a loader, where Redis holds the only copy, and fixes from an architecture review of 6.8.0: transactional invalidation, refresh-ahead, Redis command timeouts and subscriptions, and a broadcast fix for conditional and versioned writes. No API is removed. `CachePolicy.invalidateFailure` and `CachePolicy.readFailure` become nullable; their JVM signatures are unchanged, so compiled callers keep running. The Redis wire format and the invalidation channel are unchanged, so 6.9.0 instances can share Redis with any 6.x instance and be upgraded one at a time.

### Behavior change: reads without a loader fail when Redis fails

- `read-failure` (`CachePolicy.readFailure`) is now unset by default and decided per read: `degrade` with a loader, as before, and `throw` without one. An explicit `degrade` or `throw` still applies.
- So by default a read without a loader (a `Cache` with no loader, read through `get`, `lookup` or `getAll`) now throws `CacheReadException` when the Redis read fails, and caches nothing in L2. Such a read has no source to degrade to: skipping Redis can only answer "absent". This matches `getIfChanged` and `SetCache`.
- A cache without a loader that treats "unreadable" as a safe answer, such as a revocation list where a Redis failure means "not revoked", sets `read-failure: degrade` explicitly. The read then returns `null` (absent from `getAll`, `Absent` from `lookup`) and counts in `cocache.remote.failures`, but writes nothing to L1 or L2, so every read during the failure goes to Redis again.
- In 6.8.0 and earlier the read returned `null` and kept that `null` in L2 as a negative entry for `degraded-local-max-age` (10 s); an explicit `degrade` doesn't bring that copy back. During the outage callers saw a present key as absent, and for those 10 s after recovery `get(key)` still returned `null` and `get(key, loader)` returned `null` without calling its loader.
- `get(key, loader)` has a source and still degrades. If it joins a read without a loader that fails, it loads with its own loader instead of sharing the failure.
- The other reads that skip Redis without a loader (lease wait timeout, pending invalidation, `foreign-format: reclaim` on a newer format, failed reclaim) still return `null` but no longer cache it as a negative entry, so `get(key, loader)` isn't blocked by it.
- Spring Cache: a non-sync `@Cacheable` reads without a loader, so a Redis read failure reaches Spring's `CacheErrorHandler` (see [Spring Cache](./spring-cache.md#failures)).

### Behavior change: `invalidate-failure` defaults to `throw` without a loader

- `invalidate-failure` (`CachePolicy.invalidateFailure`) is now unset by default and decided per cache: `retry` with a loader, as before, and `throw` without one. An explicit `retry` or `throw` still applies.
- Without a loader a delayed retry loses updates. Instance A's `invalidate` fails and is queued for retry; instance B then writes the key with `put`; about 100 ms later A's retry deletes B's value. With a loader, deleting is always safe because the next read reloads.
- Spring Cache caches usually have no loader, so a failed `@CacheEvict` now throws `CacheWriteException` (through Spring's `CacheErrorHandler`) instead of retrying. The annotated method is the source there and a delete is safe: set `invalidate-failure: retry` on those caches to keep the 6.8 behavior.
- `CachePolicy.invalidateFailure` and `CachePolicy.Builder.invalidateFailure` are now `InvalidateFailureMode?`, and `readFailure` is now `ReadFailureMode?`. Kotlin code that reads the property sees a nullable type. Binaries compiled against 6.8 or earlier, including the 6.4 constructor, get the new default.

### Fix: replayed conditional and versioned writes broadcast without atomic broadcast

- Without atomic broadcast (the `sharded` channel mode, or when the startup probe falls back), a `putIfNewer` or `putIfMatch` could be written to Redis while its separate publish failed. The caller got `CacheWriteException` and replayed it as documented, but the replay returned `Stale(version)` or `Conflict(own digest)` and published nothing, so other instances kept their stale L2 copy until `local-max-age`.
- The engine now also publishes when `putIfNewer` / `invalidateIfNewer` returns `Stale` with the version it was given, and when `putIfMatch` conflicts with the digest of its own value. An extra invalidation only drops copies. An older version and other conflicts still publish nothing. Atomic broadcast is unchanged.

### Fix: transactional invalidation

These fixes apply to `TransactionalInvalidator`. See [Transactions](./freshness.md#transactions).

- **Rollback.** A rollback, or a commit whose outcome is unknown, now also invalidates the keys registered in the transaction. A load inside the transaction runs on the caller's thread, so it can read the transaction's uncommitted rows and write them to Redis under its lease. In 6.8.0 a rollback left that value in Redis for its `ttl`.
- **Late registrations.** An invalidation registered from another synchronization's `afterCommit`, or after this one already ran, is no longer lost. It runs in `afterCompletion`, or at once. The same holds when an earlier synchronization's `afterCommit` throws.
- **Failures.** One failing cache no longer stops the others. Every registered cache is invalidated, then the first exception is thrown and the rest are added to it as suppressed. Exceptions in `afterCompletion` (rollback, fallback) are logged by Spring, not thrown to the caller.
- **Not covered.** `@CacheEvict` and `@CachePut` go through Spring's `TransactionAwareCacheDecorator`, which acts only after commit, so they still don't run on rollback. Reactive transactions (R2DBC, `@Transactional` suspend functions) aren't visible to this class and invalidate at once, before commit; invalidate after the transaction ends instead.

### Fix: refresh-ahead

- **Backoff without the token.** An instance that doesn't get the refresh token backs off before reading Redis again: by the smaller of `max(lease-wait, 100 ms)` and half the value's remaining lifetime. Before, it re-read Redis on every hit while another instance reloaded, and for up to `lease-ttl` if that instance crashed. Redis reads per instance and key during a refresh no longer grow with the hit rate. The instance still picks up the new value before the old one expires.
- **Short lifetimes.** A value whose remaining lifetime is no longer than `refresh-window` is no longer refreshed ahead. It expires and reloads on the next miss. This covers lifetimes set by `ttlOf` or `put(key, value, ttl)`. In 6.8.0 every hit on such a value reloaded from the source.

### Fix: Redis timeouts and subscriptions

- **Timed-out scripts.** A script that timed out is never written to Redis afterwards. Before, a command Lettuce had not sent yet could still run after the caller saw the timeout, and overwrite a newer value: in Redis Cluster, a command waiting for a node connection; with `RedisBackend.of` and a client that buffers while disconnected, a buffered command. A command already sent still runs on Redis and its reply is dropped, as before. With `RedisBackend.of`, keep `DisconnectedBehavior.REJECT_COMMANDS`: without it, commands wait the full `command-timeout` instead of failing fast.
- **Leases after a read timeout.** A read that times out releases, in the background and best effort, the lease or refresh token it may have been granted. Other instances no longer wait `lease-wait` on every miss of that key for `lease-ttl`.
- **`sharded` channel mode.** A channel whose subscription was lost and isn't confirmed again yet resets its subscribers on every heartbeat, not only once. Staleness stays within the heartbeat plus the resubscribe time.
- **Concurrent subscribe and unsubscribe.** Subscribing and unsubscribing the same channel at the same time (several runtimes sharing one backend) no longer leaves a subscriber without invalidations.
- **Format byte.** The format byte of a Redis value is read as unsigned. This only matters for format numbers of 128 and above.

### TCK

- `EngineSpec`, `FaultSpec` and `VersionedSpec` (and their async versions) gain cases for these behaviors, including `read-failure: degrade` without a loader. `VersionedSpec.refusedVersionsChangeNothingAndDoNotBroadcast` now expects equal versions to broadcast on backends without atomic broadcast.
- `EngineSpec` gains two refresh-ahead cases: an instance without the token backs off, and values that don't outlive `refresh-window` aren't refreshed. `MultiNodeSpec.refreshAheadLoadsOncePerKeyAcrossNodes` advances the clock past the backoff before it checks the other instances.

## v6.8.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive only: `Cache`, `AsyncCache` and `SuspendingCache` gain version-ordered writes as default methods (they throw `UnsupportedOperationException` in implementations that don't override them), `CacheSpec` can declare a cache versioned, and the `cocache-api` SPI gains `CoherenceBackend.versionedWrites` and `RemoteStore.putIfNewer` / `AsyncRemoteStore.putIfNewerAsync` with defaults. Existing callers, custom `Cache` / `SuspendingCache` implementations, and custom backends keep compiling and running unchanged. The Redis key layout, header layout, format version and invalidation channel are unchanged. 6.0 to 6.7 instances read versioned values (below), so 6.8.0 instances can share Redis with any 6.x instance and be upgraded one at a time.

### Versioned writes

For writers that know each value's version, such as an event-sourced aggregate with one writer and increasing versions whose events can be redelivered. See [Versioned writes](./freshness.md#versioned-writes).

- Declare a cache versioned with `versioned = true`, or with a `versionedLoader` / `versionedAsyncLoader` returning `Versioned(value, version)` (`suspendingVersionedLoader` in `cocache-coroutines`). Creating one fails at once on a backend without `versionedWrites`. The Redis and in-memory backends support it.
- `putIfNewer(key, value, version[, ttl])` writes only if `version` is greater than the version in Redis, in one Lua script. An equal version is an idempotent no-op and an older one is refused; both return `Stale(current)` and change, broadcast and re-expire nothing. A successful write is a `put`: it fences out loads in flight, including one holding the lease that read a higher version.
- `invalidateIfNewer(key, version[, ttl])` writes a tombstone: a negative entry with a version that blocks older redelivery for the value's lifetime.
- A versioned loader's miss-fill stores the version, so later writes compare against it. A loader returning `Versioned(null, n)` (deleted at version `n`) writes the same tombstone as `invalidateIfNewer(key, n)`; `null` (never existed) stays a plain negative entry. `getVersioned(key)` reads through and returns the value with its version, or `null` for an absent key or a tombstone.
- Ordering holds while the entry lives: after a value or tombstone expires, any version can be written again. Size `ttl` to your redelivery horizon.
- A versioned cache doesn't support `refresh-window`. `cocache.defaults.refresh-window` skips it.
- New meter `cocache.versioned.stale` ([Observability](../operations/observability.md#metrics-writes)).

### Wire format

A versioned value or tombstone is a format-1 String with the usual 36-byte header and payload. Only the token slot holds something new: a 16-byte mark, the 8-byte version, and an issue time of `+inf`. Older instances read the payload as before, their read script (unchanged in 6.8) never grants a refresh token on such a value, and their fill and release never match it. Their writes (`put`, `invalidate`, `putIfMatch`) replace it with content without a version, so ordering holds only once every writer of a versioned cache runs 6.8. See [Wire format](../architecture/index.md#wire-format).

### SPI

- `CoherenceBackend.versionedWrites` (default `false`), `RemoteStore.putIfNewer` / `AsyncRemoteStore.putIfNewerAsync` (defaults refuse with `UnsupportedOperationException`: a read-then-put default would let a late version overwrite a newer one), `RemoteVersionedWrite`, `Fill.version` (a new constructor; the 6.7 constructor, `copy` and `componentN` are unchanged), and `Envelope.Scalar.version`. A backend that supports versions overrides both methods atomically, stores `Envelope.Scalar.version` on `fill`, `put` and `putAll` and returns it on reads. See [Extending](../architecture/extending.md#l1-store).
- The TCK gains `VersionedSpec` and `AsyncVersionedSpec` for backends with `versionedWrites`.

## v6.7.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive only: `Cache`, `AsyncCache` and `SuspendingCache` gain conditional reads and writes as default methods (they throw `UnsupportedOperationException` in implementations that don't override them), and the `cocache-api` SPI gains `RemoteStore.readIfChanged` / `putIfMatch` and their `AsyncRemoteStore` counterparts with default implementations. Existing callers, custom `Cache` / `SuspendingCache` implementations, and custom backends keep compiling and running unchanged. The Redis wire format and the invalidation channel are unchanged (the new scripts only read the existing format), so 6.7.0 instances can share Redis with any 6.x instance and be upgraded one at a time.

### Conditional reads and writes

For caches whose only copy is Redis and whose values are updated by read-modify-write. See [Conditional writes](./store.md#conditional-writes).

- `putIfMatch(key, expected, value[, ttl])` writes only if Redis still holds the value with digest `expected` (`null`: only if Redis holds no value). Compare and write are one Lua script, and the invalidation is broadcast only when the value is written. It returns `Written(digest)` or `Conflict(current)`. A successful write follows the same TTL and fencing rules as `put`.
- `getIfChanged(key, known)` reads Redis, never L2, and returns `Unchanged` without transferring the value when its digest still equals `known`. Otherwise it returns `Present(value, digest)` or `Absent`. It saves wire bytes, not Redis CPU: the script reads and hashes the value on every call, about 20 ms for an 8 MB value on Redis 7.4 (about 13 ms of it is the read a plain L1 read also pays). The digest isn't stored at write time, because that needs a wire-format change.
- The digest is the SHA-1 of the value's encoded bytes, the same digest `reclaim` already used for scalar values.
- Read and write classify unreadable content the same way. Values written by a newer CoCache and sets always throw with a `ForeignFormatException` cause and are left untouched; `reclaim` never treats them as absent. Content CoCache 6 didn't write follows `foreign-format`: `fail` (the default) throws, `reclaim` counts it as no value. A value the codec can't decode makes `getIfChanged` throw, and `putIfMatch` only ever conflicts on it. `getIfChanged` throws `CacheReadException` when Redis fails, regardless of `read-failure`.
- New meters `cocache.conditional.conflicts` and `cocache.conditional.unchanged` ([Observability](../operations/observability.md#metrics-writes)).

### SPI

- `RemoteStore.readIfChanged` / `putIfMatch`, `AsyncRemoteStore.readIfChangedAsync` / `putIfMatchAsync`, and the result types `RemoteConditionalRead` / `RemoteConditionalWrite`. The `readIfChanged` defaults read and compare on the client: correct, but they always transfer the value. The `putIfMatch` defaults throw `UnsupportedOperationException` (the async one fails with it): a read-then-put default would lose updates under concurrency. A backend supports conditional writes only by overriding both with one atomic operation, as the Redis and in-memory backends do. See [Extending](../architecture/extending.md#l1-store).
- The TCK (`EngineSpec`) covers both operations. A backend that doesn't implement `putIfMatch` sets `supportsConditionalWrites = false` to skip those cases. Otherwise they must pass, including a concurrent case that forces every writer to race on one digest.

## v6.6.0

**Module Group:** `me.ahoo.cocache` · Minor release: the `cocache-api` SPI gains `RemoteStore.addMembersIfReadable` / `removeMembersIfReadable` and their `AsyncRemoteStore` counterparts, all with default implementations, so existing backends keep compiling and running. Two behavior changes tighten fail-closed handling for caches without a loader: `SetCache` writes fail on content they can't read, and reads without a loader fail on values written by a newer version (below). The Redis wire format and the invalidation channel are unchanged, so 6.6.0 instances can share Redis with any 6.x instance and be upgraded one at a time.

### Behavior change: `SetCache` writes fail on content they can't read

- `SetCache.add` and `remove` now follow `foreign-format` like reads do. With `fail`, the default for a `SetCache`, adding or removing members on a key that holds content the cache can't read throws `CacheWriteException` with a `ForeignFormatException` cause. Such content is an unmigrated 5.x native set, string or hash, a hash without the 6.x format field, a 6.x scalar value, or a set written by a newer CoCache version. The check and the write are one atomic step: the key keeps its content and TTL, nothing is broadcast, and the write counts in `cocache.unknown.formats` and `cocache.write.failures`.
- In 6.5.0 and earlier, one `add` on an unmigrated native set replaced all its members, and the key then looked migrated, so `CoCache5Migrator` skipped it and the old members were lost for good. `remove` silently did nothing and reported success. Set `foreign-format: reclaim` on the cache to keep that behavior.
- Whole-value writes (`put`, `putAll`, on a `Cache` or a `SetCache`) still replace any content, because the caller supplies the whole value. The replaced 5.x content isn't backed up by the migrator, which skips the key afterwards. See [Caches whose only copy is Redis](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/guide/migration-6.md).

### Behavior change: newer-format values fail without a loader

- A read without a loader that hits a value written by a newer CoCache version (during a mixed-version rollout) now fails like unreadable content under `foreign-format: fail`: it throws `CacheReadException` with a `ForeignFormatException` cause (`newer = true`), keeps the key, doesn't fill L2, and counts in `cocache.unknown.formats`. It used to read the value as absent, which silently drops data when Redis is the only copy. `foreign-format: reclaim` keeps the old behavior. Reads with a loader are unchanged: they load from the source and leave the newer value alone.

### SPI

- `RemoteStore.addMembersIfReadable` / `removeMembersIfReadable` and their `AsyncRemoteStore` counterparts: like `addMembers` / `removeMembers`, but they leave content that isn't a set of the store's protocol untouched and return its format. The defaults read first and then write, which isn't atomic and can't recognize a newer format. Backends should override them with one atomic check-and-write, as the Redis and in-memory backends do. See [Extending](../architecture/extending.md#l1-store).
- `ForeignFormatException` has a new `newer` property and constructor parameter. The 6.5.0 constructor is kept.

## v6.5.0

**Module Group:** `me.ahoo.cocache` · Minor release. Additive API in `cocache-core` and `cocache-redis`, and one changed default for reads without a loader (below). The Redis wire format and the invalidation channel are unchanged, so 6.x instances can share Redis and be upgraded one at a time.

### Migrating caches whose only copy is Redis

`CoCache5Migrator` (`cocache-redis`) converts CoCache 5.x data to the 6.x format, for caches with no loader where Redis is the store of record, and for 5.x `SetElementsCache`s. It covers every 5.x value format: JSON or plain strings, native sets (to a `SetCache` or a `Cache<K, Set<String>>`), hashes, and negative entries, and keeps each key's remaining TTL. It can be run again, reports read, written, skipped and failed keys per cache, stops at the first key it can't decode, and supports a read-only `dryRun`. In-place migration backs up each 5.x value first, and `rollback` restores the backups. See [Caches whose only copy is Redis](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/guide/migration-6.md).

```kotlin
CoCache5Migrator.standalone(uri).use { migrator ->
    migrator.migrate(CoCache5Migration.scalar(policySpec, CoCache5Format.string()).sourcePrefix("app:policy:").build())
}
```

### Behavior change: reads without a loader fail on content they can't read

- New policy `foreignFormat` (`fail` or `reclaim`; property `foreign-format`) decides what a read does when the key holds content the cache can't read: a value 6.x didn't write (format 0, for example left by 5.x, or a native set or hash), or a value its codec can't decode.
- **Default for reads without a loader (`SetCache`, and `Cache` without a loader) is now `fail`**: the read throws `CacheReadException` with a `ForeignFormatException` cause, leaves Redis and L2 unchanged, and counts in `cocache.unknown.formats`, also with `read-failure: degrade`. Up to 6.4 such a read reclaimed the key, which deletes the only copy, and returned `null`. A `SetCache` returned an empty set. Both looked like a real "not found" to the caller.
- Reads with a loader, including `get(key, loader)`, keep reclaiming and reloading (`reclaim`). Set `foreign-format: reclaim` on a cache to restore the old behavior. `cocache.defaults.foreign-format` doesn't apply to `SetCache`.
- Who sees a change: only applications that have unreadable content under a cache without a loader. Before 6.5 that content was silently discarded on the first read.

## v6.4.1

**Module Group:** `me.ahoo.cocache` · Patch release. No API or configuration change. The Redis wire format and the invalidation channel are unchanged, so 6.4.1 instances can share Redis with any 6.x instance and be upgraded one at a time.

### Async Redis command timeouts

- Async Redis commands no longer schedule their timeout on the JDK's single-threaded delay scheduler (`CompletableFuture.orTimeout`) ([RFC 0004 §14](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0004-async-api.md)). Clients that CoCache creates now time every command with Lettuce's own command timeout (`TimeoutOptions`, fixed at `cocache.redis.command-timeout`), which replaces Lettuce's default 60-second timeout at no extra cost. On a standalone Redis this is the only timer. In a Cluster, Lettuce starts timing a command only when it's written to a node connection, so a command that waits for a node connection to open keeps its own timer, now on Lettuce's Netty timer wheel. A client passed to `RedisBackend.of` keeps its own options, and CoCache times its async commands on the same wheel.
- Timeouts fail with the same `RedisException("Command timed out after …")` as before, on the synchronous and async paths. On the async path, the `EVAL` retry after `NOSCRIPT` is a new command with its own timeout.

Behavior changes:

- An async timeout is now precise to one tick of the timer wheel (100 ms by default), so it can fire up to one tick after `command-timeout`. It used to be precise to the millisecond.
- On clients that CoCache creates, Lettuce's command timeout drops from 60 seconds to `command-timeout` (2 seconds by default), and Lettuce 7's relaxed timeouts during server maintenance are turned off. This also bounds the commands that Lettuce sends itself: the re-subscribe after a reconnect, and `CLUSTER NODES` / `INFO` during a Cluster topology refresh. A re-subscribe that times out still runs on the server, and the heartbeat forces a reconnect if the connection is really gone. A topology refresh that times out is retried by the next adaptive trigger. In a very large or busy Cluster, raise `command-timeout` if `CLUSTER NODES` takes longer than that. The connection handshake isn't affected.
- On clients that CoCache creates, the cause of a timeout can now be Lettuce's `RedisCommandTimeoutException` instead of `TimeoutException`. The outer exception type and message are unchanged.

### Fewer thread hops on the async view

- An async continuation now runs in place when the step before it completed inside a task that the same async executor is already running, instead of being submitted to the executor again ([investigation](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/investigations/async-8t-gap.md)). An L1 hit through the async view now makes one executor hop instead of four, and a miss at most three instead of seven. Results that complete on a Lettuce I/O thread, a timer thread, the blocking executor or a loader's own thread are still handed to the async executor, so no continuation runs on an I/O thread. Nesting is capped at 16 levels, after which the continuation is dispatched again.
- On the default `ForkJoinPool`, each extra hop could wake an idle worker. Compared with 6.4.0 (median of paired runs), an async L1 hit is 5.5% faster with 1 thread and 8% faster with 8 threads, and an async miss is at least about 8% faster with 8 threads (only 2 valid paired runs). Writes and L2 hits are unchanged.

### Fixes

- **The heartbeat stopped for good on Lettuce 6.8 (Spring Boot 4.0).** Since 6.0.0, on the Lettuce version that Spring Boot 4.0 manages, the forced reconnect after a failed heartbeat threw `NoClassDefFoundError` (it used Lettuce 7's `Delegating` interface). The error escaped the heartbeat task, so the subscription connection was never checked again and the node could no longer heal itself after a half-open connection. Forcing a reconnect no longer uses that interface. The heartbeat task now also logs any error, including `Error`s, and carries on with the next heartbeat. The whole `cocache-redis` test suite passes on Lettuce 6.8.2 and 7.5.2.
- **No false reentry after inline continuations.** While a thread loads a key, either as the leader of a synchronous call or inside a data source, other keys' async continuations that complete on that thread are no longer run in place. This covers a data source that completes another key's load, and a synchronous leader that hands its load permit to another key's async waiter. Before this fix, a callback of that other key that read the key being loaded failed with `IllegalStateException` ("Recursive call on the same key"). It came from a change made after 6.4.0, so no released version was affected.

## v6.4.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive. No API change in existing modules, and the Redis wire format and the invalidation channel are unchanged, so 6.x instances can share Redis and be upgraded one at a time.

### Kotlin coroutines

The new optional module `cocache-coroutines` adds a suspending view over the async API ([RFC 0004 §7](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0004-async-api.md)). See [Kotlin Coroutines](./coroutines.md).

```kotlin
val users = userCache.suspending()
val user = users.get("42")                    // suspends instead of blocking; an L2 hit doesn't suspend

cacheSpec<String, User>("user") {
    suspendingLoader { id -> userRepository.findById(id) }
}
```

- `Cache.suspending()` / `SetCache.suspending()` return `SuspendingCache` / `SuspendingSetCache`, with the same methods as the synchronous API. They share the same L2, fences, in-flight loads and leases.
- `SuspendingCacheLoader` is a suspending data source. Set it with `suspendingLoader(context)` (default `Dispatchers.Default`), or convert it with `asAsyncCacheLoader()`. A per-call loader, `get(key) { … }`, runs in the caller's coroutine context without its `Job`.
- Cancelling a waiting coroutine doesn't cancel a shared load. Exceptions are the original ones. A loader that reads the key it is loading, from the same cache, fails at once with `IllegalStateException`, even after it suspends.
- A load that ends in cancellation fails with `SourceLoadCancelledException` (a `RuntimeException`) for every caller that shares it: synchronous, async or suspending. This covers a `CancellationException` from the loader itself (for example a `withTimeout` inside it) and a cancelled `Job` in the spec's `context`. Spec-level loads run under one `SupervisorJob`, so one failure doesn't cancel later loads.
- Java sees the extensions on the facade classes `SuspendingCaches` and `SuspendingCacheLoaders`.
- The module depends on `kotlinx-coroutines-core` 1.10.2. No other module needs coroutines at runtime. The `cocache-dependencies` platform declares a 1.10.2 version constraint, the same version Spring Boot 4.1 manages. The starter registers native-image hints for the suspending views when the module is on the classpath.
- The synchronous and async hit paths are unchanged.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-coroutines:6.4.0")
```

## v6.3.1

**Module Group:** `me.ahoo.cocache` · Patch release. No API or configuration change. The Redis wire format and the invalidation channel are unchanged, so 6.3.1 instances can share Redis with any 6.x instance and be upgraded one at a time.

### Fixes

- **A rejecting async executor no longer drops a write's broadcast.** Only applies when the backend broadcasts invalidations as a separate command (`channel-mode: sharded`, or when the atomic-broadcast probe failed at startup). If the `cocache.async-executor` you configured rejected the task that sends the broadcast after an async `put`, `putAll`, `invalidate` or set write, the broadcast was skipped and the write's outcome was replaced with `RejectedExecutionException`. Other instances could then serve their old local copy for up to `localMaxAge`, a successful write counted as a write failure, and a successful `invalidate` scheduled a needless retry. The broadcast now goes out on the completing thread when the executor rejects it, and the write keeps its own result or exception. If the executor also rejects completing the future returned to you, that future still fails with `RejectedExecutionException`, as before. The default executor (`ForkJoinPool`) doesn't reject tasks, so the default setup was not affected.
- **Broadcast after a command timeout: documented, not changed.** When a synchronous `putAll` or `invalidate` times out, the broadcast sent after the failure can reach other instances before the timed-out command runs in Redis. We evaluated waiting for the command to finish and kept the current behavior: after a timeout there is no way to know whether or when the command runs, and any bounded wait narrows the window without closing it. Stale copies in this window are still bounded by `localMaxAge`. See [architecture.md §4.2](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/architecture.md).

## v6.3.0

**Module Group:** `me.ahoo.cocache` · Minor release. 6.2 was not released on its own: the work planned for 6.2 ships in 6.3.0. The Redis wire format and the invalidation channel are unchanged, so 6.0, 6.1 and 6.3 instances can share Redis and be upgraded one at a time.

### Async API

Every cache now has an asynchronous view on the same engine ([RFC 0004](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0004-async-api.md)). See the [Async API](./async.md) guide.

```kotlin
val async = userCache.async()                 // same L2, fences, in-flight loads and leases
async.get("42").thenAccept { user -> ... }
async.put("42", user).thenCompose { async.get("42") }
```

- `Cache.async()` / `SetCache.async()` return `AsyncCache` / `AsyncSetCache`. The methods have the same names and semantics as the synchronous API and return `CompletableFuture`. An L2 hit returns an already-completed future.
- `AsyncCacheLoader` (`loadAsync`, `loadAllAsync`) loads from non-blocking sources. Set it as `asyncLoader` in a spec; `@CoCache` interfaces find it by type or by `loader = "…"`. Either kind of loader works with both APIs.
- No thread is parked: waits for another instance's lease and for load permits are scheduled, and futures complete on the async executor, never on the Lettuce I/O thread. Futures fail with the original exception, cancelling one doesn't cancel the shared load, and async methods never throw synchronously.
- New settings:
  - `cocache.async-executor` and `cocache.blocking-executor`, each naming an `Executor` bean;
  - `load-async-timeout` (default 30 seconds) per cache, with the new `SourceLoadTimeoutException` and the `cocache.load.timeouts` metric;
  - `cocache.observation.enabled` (default `true`) for [load-path tracing](#load-path-tracing). With Spring Boot Actuator and a Micrometer tracing bridge on the classpath, tracing is on by default; set it to `false` to turn it off.
- The Redis backend implements the new optional SPI `AsyncRemoteStore` / `AsyncInvalidationChannel` natively. Third-party backends keep working unchanged, because the engine runs their synchronous methods on the blocking executor.
- **Spring Cache:** `retrieve` uses the async view, so async and reactive `@Cacheable(sync = true)` methods load without holding a thread.
- **Performance:** the synchronous hit path is unchanged (JMH within noise of the code before the async work, at 1 and 8 threads). An async L2 hit runs at about 90% of a synchronous one. With 32 requests in flight, one thread completes about 12 times as many Redis reads or writes as a blocking thread.

### Batch writes with putAll

`putAll(entries)` and `putAll(entries, ttl)` write several keys as one write: one script per Redis slot group, one broadcast per group, and the same stale-write-back protection as `put`. See [Keeping Data Fresh](./freshness.md).

### Load-path tracing

With an `ObservationRegistry` bean, the starter registers a `MicrometerLoadObserver`. It wraps every source load and every wait for another instance's lease in a `cocache.load` observation, with tags `cache`, `operation` and `outcome`. Hits create no observation. The new `LoadObserver` SPI lets you plug in your own. See [Observability](../operations/observability.md#tracing).

### Faster SetCache reads

The `SetCache` read script fetches members with `HKEYS` and the format version with `HGET`, instead of `HGETALL` with every member's empty value. The stored layout is unchanged. In server-side throughput, reads are 1.33, 1.55 and 1.76 times as fast at 10, 100 and 1000 members. ACL errors raised inside the read script now reach the caller instead of being read as unrecognized content.

### L2 hit counter

`cocache.metrics.local-hits=true` (without Spring: `CacheRuntime.builder().recordLocalHits(true)`) counts reads answered by L2 as `cocache.hits{level=l2}` and the endpoint key `localHits`, so reads split exactly into L2 hits, misses and filtered reads. It is off by default because it adds an atomic increment to every L2 hit: with it on, L2 hit throughput drops by about 3–9% in JMH (1 and 8 threads, depending on the call site). With it off, throughput matches 6.1. The 5.x property `cocache.metrics.client-side-hits` now points to it. See [Observability](../operations/observability.md#metrics-reads).

### Fixes

- With non-atomic broadcast (`channel-mode: sharded`, or when the atomic-broadcast probe fails), a write that failed but partly took effect is now broadcast too. Before, a `putAll` that failed in one slot group, or a `put` applied in Redis whose reply was lost, left other instances reading the old local copy until `local-max-age`. Synchronous `putAll` and `invalidateAll` on Redis now wait for every slot group before they throw.
- A read that saw a local copy inside the refresh window just as the previous refresh finished could start a second refresh of the same key on the same instance. That second refresh only cost an extra Redis read, and it no longer happens.
- Executors named by bean (`cocache.refresh-executor`, and the new `cocache.async-executor` / `cocache.blocking-executor`) could be destroyed before the CoCache runtime when the application shut down, so work submitted while the runtime closed was rejected. This was present since 6.1. The runtime now closes first.

### Compatibility kit and CI

- New TCK suites: `MultiNodeSpec` (three runtimes, every invalidation interleaving, one load and one refresh per key cluster-wide), `FaultSpec` (L1 faults, retries, writes that took effect but reported an error, no replay after a reconnect), and async variants of every suite (`AsyncBackendProtocolSpec`, `AsyncEngineSpec`, `AsyncSetEngineSpec`, `AsyncMultiNodeSpec`, `AsyncFaultSpec`).
- The Redis backend and the starter are tested on Valkey 8 in CI.
- A `SetCache` read benchmark (`setRead`) confirmed the Hash encoding.

### Upgrade notes

- `cocache.spring-cache.retrieve-executor`, `CoCacheSpringCacheManager(runtime, executor)` and `CoCacheSpringCacheManager.DEFAULT_EXECUTOR` are deprecated: the executor is ignored, and the property logs a warning at startup. They are removed in 7.0. `CoCacheSpringCache(cache, admin, executor)` is not deprecated: its executor runs the method body.
- **Async and reactive `@Cacheable(sync = true)` methods** behave differently in two ways:
  - The method body runs on `cocache.blocking-executor`: virtual threads on JDK 21+. On JDK 17 it's a pool of up to 512 threads that rejects new loads when full (`RejectedExecutionException`). When full, the old retrieve executor ran them on the calling thread instead.
  - The load is bounded by `load-async-timeout` (default 30 seconds): past it the call fails with `SourceLoadTimeoutException`, while the method body keeps running.
- `Cache.async()` is a default method that throws `UnsupportedOperationException`. A `Cache` you implement yourself, for example a test double, needs to override it only if callers use the async view.
- A custom `LoadObserver` sees async loads only if it overrides `observeAsync`.
- **Kotlin callers must recompile** against 6.3, or they fail with `NoSuchMethodError`. `LoadPolicy` gained `asyncTimeout`, which changes `copy` and the synthetic constructor behind Kotlin default arguments, so this applies to Kotlin code that calls `copy` or omits `LoadPolicy` arguments. The constructors and `copy` of `CoCacheProperties` and `PolicyProperties` changed too, for code that builds the starter's properties itself. Java callers are unaffected.
- **Redis ACL:** a user with a minimal ACL needs `+hkeys +hget` before the upgrade, or `SetCache` reads fail with an ACL error. Keep `+hgetall`: conditional reclaim and 6.0/6.1 instances still use it. See [ACL](../operations/index.md#acl).
- TCK: `HookedBackend.closesDelegate` (default `false`, as before) and each suite's `closesBackend` let the suites close a backend created per case. Turn it on if your backend opens connections or threads. `BackendProtocolSpec` and `AsyncBackendProtocolSpec` always close the backend they create and have no such switch. Hooks that pick out a caller should use `HookedBackend.issuer()`, because async calls complete on other threads.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.3.0")
```

## v6.1.0

**Module Group:** `me.ahoo.cocache` · Minor release. The Redis wire format and the invalidation channel are unchanged, so 6.0 and 6.1 instances can share Redis and be upgraded one at a time.

### Spring AOT and GraalVM native images

Applications that use the starter now build with Spring AOT and run as GraalVM native images. CoCache registers the hints it needs at build time:

- JDK proxies for `@CoCache` and `@JoinCacheable` interfaces, and reflection on those interfaces (default methods included);
- the key type's `toString()` for the default key encoder;
- the SpEL root types of `keyExpression` and `joinKeyExpression`;
- Jackson binding for the value type when the default codec applies.

Cache beans are created by factory methods that AOT can generate code for. A custom `Codec` or types that only your own code reflects on still need your hints. See [Native images](./native.md).

### Configurable executors

```yaml
cocache:
  refresh-executor: applicationTaskExecutor          # refresh-ahead loads
  spring-cache:
    retrieve-executor: applicationTaskExecutor       # Spring Cache async retrieve
```

Both name an `Executor` bean, resolved by name. An unknown name or a bean that isn't an `Executor` fails startup. CoCache never shuts down an executor you supply. Prefer a virtual-thread executor for `retrieve`: a pool that queues tasks behind ones waiting on them can deadlock nested async `@Cacheable(sync = true)` calls. See [Configuration](./configuration.md#executors).

### SetCache eviction check

With any `SetCache`, CoCache reads every primary's `maxmemory-policy` at startup. `allkeys-*` can evict the set's only copy, so it logs a warning by default. `cocache.redis.set-cache-eviction-check` selects `warn`, `fail` or `off`. The check is skipped when `CONFIG` isn't available (managed Redis, ACL). `RedisBackend.maxmemoryPolicies()` exposes the lookup for applications without Spring.

### Agent skills and docs

- The agent skills are split by outcome: `cocache-develop`, `cocache-migrate` and `cocache-extend`, each with `claude plugin eval` cases and a validator in CI.
- The documentation site has an animated home page that shows the read and write paths, and a new logo.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.1.0")
```

## v6.0.0

**Module Group:** `me.ahoo.cocache` · **Major release.** CoCache is rebuilt on the coherence protocol in [RFC 0003](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0003-target-architecture.md). **No rolling upgrade:** the Redis value layout and the invalidation channel changed, so 5.x and 6.0 instances cannot share Redis. Follow the [migration guide](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/guide/migration-6.md) and switch every instance at once.

### What changes for you

- **Stale write-backs are closed.** Loads write Redis only while they hold a lease token, and every L2 fill is checked against an invalidation stamp. The 5.x "known inherent window" no longer exists: races inside the protocol leave no stale value in Redis.
- **Faster hit path.** An L2 hit is 54–59% faster than 5.4, an interface proxy hit 28–64% faster, and `put` / `invalidate` about twice as fast (JMH, RFC 0003 §7.1).
- **One loader per key across the cluster**, refresh-ahead with one refresh per key cluster-wide, and batch `getAll` that reads L2, then Redis in one round trip, then the source in one `loadAll`.
- **Failure modes you can choose:** `read-failure: degrade | throw`, `invalidate-failure: retry | throw`, a bounded invalidation retry queue, and a load bulkhead (`load-max-concurrent`).
- **Redis backend on native Lettuce** (`cocache-redis`, no Spring dependency): standalone, Sentinel and Cluster, TLS through Spring Boot SSL bundles or `rediss://`, Pub/Sub or Sharded Pub/Sub (`cocache.redis.channel-mode`).
- **Spring:** `@EnableCoCache` with `@CoCache` interfaces or `CacheSpec` beans, `CacheSpecCustomizer`, layered `cocache.defaults.*` / `cocache.caches.[name].*` properties with full IDE metadata, the `CoCacheSpringCacheManager` (use `@Cacheable(sync = true)` for coherent loads), and `TransactionalInvalidator` (invalidate after commit).
- **Observability:** the `cocache` actuator endpoint and `cocache.*` Micrometer meters tagged by `cache`.

### Developer experience

- **`JoinCache`**: a read-only view that joins two caches with two batch reads, declared with `@JoinCacheable` and registered with `@EnableCoCache(joinCaches = […])`. See [JoinCache](./join-cache.md).
- **No Redis for local development and tests:** `cocache.backend: memory` runs every cache on the in-process backend (single process only). A `CacheClock` bean such as `ManualClock` lets tests advance time.
- **Per-key troubleshooting:** `GET /actuator/cocache/{name}/{key}` shows the L2 copy and the L1 state of one key without loading it; `DELETE` on the same path invalidates it cluster-wide. See [Operations](../operations/observability.md#inspect-key).
- **Health indicator** `cocache`: DOWN when the Redis heartbeat fails; pending invalidations are listed in the details.
- **Spring Cache check:** a WARN for each `@Cacheable` on a CoCache cache without `sync = true` (the non-sync path isn't lease-protected); `cocache.spring-cache.require-sync=true` fails startup instead.
- **Global key prefix:** `cocache.defaults.key-prefix` (and per cache `cocache.caches.[name].key-prefix`), for example `${spring.application.name}:` when several apps share one Redis.
- **Key type check:** the default `toString()` key encoder rejects key types that don't override `toString()`, which would otherwise never hit.
- **`ExternalInvalidator`:** invalidate from CDC consumers or batch jobs with the same protocol, without creating a cache.
- `@JvmOverloads` on `LeasePolicy`, `LoadPolicy`, `LocalPolicy`, `RetryPolicy` and `RedisBackendOptions`, and `updatePolicy(Consumer)` on spec builders, for Java.

### Migration at a glance

| 5.x | 6.0 |
|-----|-----|
| `me.ahoo.cache.*` | `me.ahoo.cocache.*`; `@EnableCoCache` is in `me.ahoo.cocache.spring.boot` |
| `cocache-spring-redis`, `cocache-spring-cache` | `cocache-redis`, `cocache-spring` (plus `cocache-codec-jackson`) |
| `ttlAmplitude`, `missingTtl`, `clientSideTtl`, `refreshAhead` | `ttlJitter`, `absentTtl`, `localMaxAge`, `refreshWindow` |
| `{name}.CacheSource` bean | a `CacheLoader<K, V>` bean matched by type, or `@CoCache(loader = …)` |
| `{name}.ClientSideCache` and other bean suffixes | `CacheSpecCustomizer`, `cocache.caches.[name].*` |
| `getCache` / `set(key, ttlAt, value)` / `evict` | `get` / `lookup` / `put(key, value, ttl)` / `invalidate` |
| `SetElementsCache` | `SetCache` |
| `cocache.redis.strict-failure=true` | `cocache.defaults.read-failure=throw` |
| `cocache.redis.eviction-channel=sharded` | `cocache.redis.channel-mode=sharded` |
| `cocache.load.*` | `cocache.defaults.load-max-concurrent` / `load-acquire-timeout` |
| actuator `cocacheClient` | removed; use `cocache` |
| `JoinCache` (`me.ahoo.cache.api.join`) | `me.ahoo.cocache.api.join.JoinCache`: a read-only view of two caches, registered with `@EnableCoCache(joinCaches = […])`; `evict` → `invalidate`, no `set` (see [JoinCache](./join-cache.md)) |
| Jedis, pluggable L2, explicit negative-entry writes | removed |

Removed 5.x property keys fail startup with a message that names the replacement, if there is one. Some renames change meaning (for example, L2 is now always bounded by `localMaxAge`, 5 minutes by default): see [Behavior changes](https://github.com/Ahoo-Wang/CoCache/blob/6.x/wiki/guide/migration-6.md).

### Dependencies

| Dependency | Version |
|------------|---------|
| JDK (minimum) | 17 (tested on 17 and 25) |
| Spring Boot | 4.1.1 |
| Kotlin | 2.4.21 (artifacts target Kotlin API and language level 2.2) |
| Lettuce | 7.5.2.RELEASE |
| Caffeine | 3.2.4 |
| Jackson | 3.1.5 (`tools.jackson`) |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:6.0.0")
```

## v5.4.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive. The default eviction channel and the Redis wire format are unchanged, so 5.3.x and 5.4 instances can share Redis. The new channel is off by default.

### Sharded Pub/Sub eviction channel

```yaml
cocache:
  redis:
    eviction-channel: sharded   # default: pubsub
```

In Redis Cluster, a plain `PUBLISH` reaches every node. `SPUBLISH`/`SSUBSCRIBE` (Redis 7+) stays within the shard that owns the channel's slot, so the cost doesn't grow with the cluster.

- It requires Lettuce. `ShardedRedisCacheEvictedEventBus` uses Lettuce directly, because Spring Data Redis doesn't support Sharded Pub/Sub yet.
- Channel names and messages are unchanged. Resubscribing after a reconnect still clears L2.
- **The two channels don't interoperate, so switch every instance at once.**
- Failed publishes on either channel count in `cocache.eviction.publish.failures` (new interface `EvictionPublishStats`).

### Evicting from outside CoCache

The [operations guide](../operations/troubleshooting.md#external-invalidation) now documents the eviction protocol: the storage key, the channel, and the `key@@publisherId` message. It includes `redis-cli` and CDC-consumer examples. A writer that bypasses CoCache runs `DEL` on the storage key, then `PUBLISH`.

### Redis Cluster tests

A new CI job, "Test (Redis Cluster)", also runs the Redis, Spring Cache, and starter tests on a 3-master, 3-replica cluster. Start the same cluster locally with `.github/scripts/start-redis-cluster.sh`.

### TCK

The two concurrent cases in `SetElementsCacheSpec` now assert the L1 contents (the authoritative copy) immediately, and poll the per-instance reads with a timeout. Before this change they could fail intermittently where eviction events are slower, such as on Redis Cluster.

### Design documents

- [RFC 0001](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0001-l1-write-back-lease.md): L1 write-back leases for 6.0, which close the cross-instance stale write-back window.
- [RFC 0002](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0002-resp3-client-tracking.md): the RESP3 client tracking spike result (not adopted for 6.0).

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.4.0")
```

## v5.3.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive (every new interface method has a default). Redis wire format and eviction messages are unchanged, so 5.2.x and 5.3 instances can share Redis. New behaviors are off by default.

### Batch reads: `getAll` / `getCaches` / `loadCacheValues`

Reading N keys no longer costs N Redis round trips and N source loads:

```kotlin
val users: Map<String, User> = userCache.getAll(ids)
```

- CoCache reads L2 first, then the remaining keys from Redis in one pipeline, then loads the rest with one `CacheSource.loadCacheValues(keys)` call. Keys missing from that result become negative entries. Reading 100 keys from L1 drops from about 13.6 ms to about 1 ms (JMH).
- Every fill and write-back is stamp-guarded per key, as in single-key reads.
- `CacheSource.loadCacheValues` calls `loadCacheValue` per key by default. Override it with one query, such as `findAllById`.
- `SimpleJoinCache` joins with two batch reads.
- New API, all with default implementations: `CacheGetter.getCaches` / `getAll`, `CacheStore.getCaches`, `CacheSource.loadCacheValues`, and `CodecExecutor.executeAndDecodeAll`.

### Refresh ahead: `@CoCache.refreshAhead`

```kotlin
@CoCache(ttl = 600, refreshAhead = 60)
interface UserCache : Cache<String, User>
```

When a value has at most `refreshAhead` seconds left, reads still return it, and one background refresh per key per instance reloads it. Hot keys no longer stall on expiry. Expired values are never served, and negative entries are not refreshed. The default `0` turns it off. Meter: `cocache.refreshes`.

### Source load limit

```yaml
cocache:
  load:
    max-concurrent: 32
    acquire-timeout: 1s
```

While Redis is down, every L2 miss goes to the source. With a limit set, each cache runs at most `max-concurrent` source loads at once. A load beyond the limit waits `acquire-timeout`, then fails with `SourceLoadRejectedException`. Meter: `cocache.load.rejections`. Unlimited by default.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.3.0")
```

## v5.2.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive. Redis wire format and eviction messages are unchanged, so 5.1.x and 5.2 instances can share Redis.

### Bounded L2 staleness: `@CoCache.clientSideTtl`

Eviction events travel over Redis Pub/Sub, which delivers at most once. When an event is lost, other instances keep serving their L2 copy until the value's `ttl` (3600 s by default).

```kotlin
@CoCache(clientSideTtl = 30)
interface UserCache : Cache<String, User>
```

- An L2 copy lives at most `clientSideTtl` seconds, then refills from Redis. L1 keeps the full TTL.
- The default `0` means unlimited, the same as 5.1.
- The cap is computed only when writing L2, so the hit path costs nothing extra. Each L2 expiry costs one Redis round trip.
- When set, an L2 hit reports the L2 copy's expiry from `getTtlAt`.

### Micrometer metrics

With Micrometer on the classpath (`spring-boot-starter-actuator` brings it), the starter binds `cocache.*` meters tagged by `cache`: `hits{level=l2|l1}`, `misses`, `loads` (timer), `load.failures`, `filtered`, `stale.discards`, `evictions.received`, `resets`, `l2.size`, plus the untagged `eviction.publish.failures`. See [Operations](../operations/observability.md#metrics).

- The orchestration layer accumulates the counts in `LongAdder`s. Read them directly from `CoherentCache.stats`.
- The L2 hit counter (`hits{level=l2}`) is off by default. Turn it on with `cocache.metrics.client-side-hits=true`. Counting on the hottest path costs 8–15% of L2 hit throughput (JMH, 1–8 threads). No other meter touches the hit path.
- `RedisCacheEvictedEventBus.publishFailureCount` counts failed publishes.

### CosId dependency removed

`cocache-core` used CosId only to build the clientId. It now uses the JDK instead.

- `cosid-core` is no longer a transitive dependency. Projects that use CosId directly must declare it.
- `CosIdHostAddressSupplierAutoConfiguration` is removed, so a CosId `HostAddressSupplier` bean no longer feeds the clientId's host. The random suffix makes the id unique; the host is only for readability.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.2.0")
```

## v5.1.1

**Module Group:** `me.ahoo.cocache` · Patch release, backward compatible with 5.1.0. Redis wire format and eviction messages are unchanged.

- **Client ids are unique across instances.** `HostClientIdGenerator` used `{counter}:{pid}@{host}` as the clientId, but host + pid is not unique: two containers on the same machine with host networking share an IP and can both run as pid 1. Subscribers ignore eviction events whose publisherId equals their own clientId, so such instances dropped each other's evictions and served stale L2 copies until TTL. The clientId is now `{counter}:{pid}:{random suffix}@{host}`, and the `ClientIdGenerator` contract now requires ids to be unique across instances.
- Dependencies: CosId 3.4.1, fluent-assert 1.2.1.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.1.1")
```

## v5.1.0

**Module Group:** `me.ahoo.cocache` · Minor release, additive. Redis wire format and eviction messages are unchanged, so 5.0.x and 5.1 instances can share Redis.

### Atomic element updates for Set-valued caches

Concurrent "read the whole set → modify → write the whole set" updates lose writes across instances. A cache interface can now also declare `SetElementsCache<K, E>`, and each update becomes one atomic L1 operation:

```kotlin
@CoCache(ttl = TtlAt.FOREVER)
interface PolicyIndexCache : Cache<String, Set<String>>, SetElementsCache<String, String>

policyIndexCache.addElements(key, listOf(policyId))
policyIndexCache.removeElements(key, listOf(policyId))
```

- **Wiring**: the distributed cache must implement the new `SetElementsStore` SPI. For Redis, use `RedisSetDistributedCache(redisTemplate)` (native Redis Set, same layout as `SetToSetCodecExecutor`). `InMemoryDistributedCache` supports it as well. If a proxy declares `SetElementsCache` over a store that can't support it, creating the proxy fails with a message that names the fix.
- **Semantics**: L1 is the authoritative copy, and these operations never call the `CacheSource`. Adding to a negative cache replaces it, so the sentinel never ends up in the set. After every update, the key's expiry follows the cache's `TtlPolicy` (`TtlAt.FOREVER` → no expiry, even when the key previously held the 60 s negative cache). Removing from an absent key or a negative cache does nothing. Removing the last element deletes the key.
- **Coherence**: invalidation stamp → atomic L1 update (one Lua round trip) → L2 evict → eviction event. Read-your-writes and in-flight-load protection match `setCache`.
- **Failures always surface**: element updates are explicit writes, so an L1 failure always throws, even with `strictFailure = false`. The update may still have been applied, for example when the response timed out, so the local copy is evicted and the eviction is published anyway. Both operations are idempotent, so callers can retry.
- **Write-back safety**: on stores that implement `SetElementsStore`, a source load's write-back is now conditional: it writes only when the key is absent or holds a negative cache. Its undo is now compare-and-delete. Before this, a load that started before an `addElements` (for example, writing the negative cache) could overwrite or delete the added elements. Because `InMemoryDistributedCache` implements the SPI, it uses the conditional write-back for every value type.
- `TtlPolicy.valueTtlAt()` exposes the jittered value expiry. `RedisDistributedCache` is now `open`.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.1.0")
```

## v5.0.1

**Module Group:** `me.ahoo.cocache` · Patch release, backward compatible with 5.0.0. Redis wire format unchanged.

- **Corrupted payloads are no longer deleted on read.** A payload that fails to decode is still a miss. The coherent cache's reload now overwrites it through the stamp-guarded write-back. Previously the read issued an unconditional `DEL`, which could remove a valid value that another instance had just written.
- **`cocache-spring-boot-starter` drops the `actuator-support` / `cloud-support` Gradle capabilities.** Actuator is now `compileOnly`, and the endpoints still activate only when the application adds `spring-boot-starter-actuator`. The POM no longer lists actuator as an optional dependency, and nothing changes for Maven or Gradle consumers who don't request those capabilities.
- Build: ready for Gradle 10 (no deprecated APIs in the build scripts) and Kotlin's `-jvm-default=enable`, with the same binary output as `-Xjvm-default=all-compatibility`. The test suite also runs on JDK 25.
- Docs site: fixed three sequence diagrams that failed to render, and patched all Dependabot alerts in the docs toolchain.

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.0.1")
```

## v5.0.0

**Module Group:** `me.ahoo.cocache`

5.0 rebuilds the coherence core around three first-principles goals — bounded staleness, no lost invalidations, and a cheap hit path. It is a breaking release with no compatibility bridges. **Redis storage layout and the eviction message format are unchanged**, so 4.x and 5.0 instances can share Redis during a rolling upgrade.

### Correctness Fixes

- **L1 miss is never a negative cache** — Redis reads are one atomic round trip (a Lua script returns TTL + value). An absent key, a key deleted mid-read, or a corrupted payload is a miss that reloads from the source. Previously a concurrent eviction between the `TTL` and `GET` calls was decoded as a negative cache and pinned into L2 until the old TTL (forever by default).
- **Local writes invalidate in-flight loads** — `evict`/`setCache` on the same instance now discard a concurrent source load's stale write-back (previously only remote events did). L1 → L2 fills are guarded the same way.
- **Original exceptions from cache proxies** — proxies unwrap `InvocationTargetException`; callers no longer receive `UndeclaredThrowableException`.
- **Bounded staleness by default** — default `ttl` is 3600 s (was forever); the negative cache has its own `missingTtl` (default 60 s, was the value TTL); default L2 is a bounded Caffeine cache (10 000 entries); every (re)subscription of the eviction channel clears L2 so lost pub/sub messages cannot leave L2 stale.
- **Read-your-writes** — a `get` after the same thread's `evict`/`setCache` never returns the result of a load that started before that invalidation; it reloads once.
- **Spring Cache** — `get(key, valueLoader)` loads once per key (`@Cacheable(sync = true)` semantics); `retrieve` runs on a dedicated executor instead of the common ForkJoin pool.
- **Stateful components are never shared** — `ClientSideCache`, `DistributedCache` and `KeyConverter` beans resolve by name only; previously a bean matched by generic type could be shared by every cache with the same value type.
- **JoinCache.evict(key) no longer loads** — it evicts only the first cache.

### Architecture

- `cocache-api` owns all SPI: `CacheStore` → `ClientSideCache` / `DistributedCache`, `CacheEvictedEventBus` / `CacheEvictedSubscriber` / `CacheEvictedEvent`, `KeyConverter`, `KeyFilter`, `CacheSource`.
- `CacheValue` is sealed: `PresentValue(value, ttlAt)` | `MissingValue(ttlAt)`.
- Storage tiers are pure stores; TTL policy (`TtlPolicy`) lives in the orchestration layer.
- `DefaultCoherentCache` coalesces loads per key with `SingleFlight` (no striped-lock collisions) and guards write-backs with `InvalidationStamps`.
- One `CacheInvocationHandler` serves CoCache and JoinCache proxies.

### Performance

JMH, 4.3.0 vs 5.0.0, same machine and Redis (ops/s):

| Benchmark | 4.3.0 · 1 thread | 5.0.0 · 1 thread | 4.3.0 · 8 threads | 5.0.0 · 8 threads |
|---|---:|---:|---:|---:|
| L2 hit | 31.1M | 32.6M | 217.5M | 235.9M |
| L1 read | 3,188 | 6,144 | 5,438 | 10,758 |
| Miss + load | 1,487 | 3,082 | 2,467 | 5,173 |
| Set | 2,779 | 2,804 | 4,714 | 4,694 |

L1 reads and miss-loads are about 2× faster, because one Lua round trip replaces `TTL` + `GET`. L2 hits still scale with threads: plain bounded Caffeine checks expiry on read against the cached `CacheClock`. The benchmark ships as `RedisCacheBenchmark` (`./gradlew :cocache-spring-redis:jmh`).

### Migration from 4.x

| 4.x | 5.0 |
|-----|-----|
| `me.ahoo.cache.DefaultCacheValue(value, ttlAt)` / `.forever(v)` / `.missingGuard(...)` | `CacheValue.of(value, ttlAt)` / `CacheValue.forever(v)` / `CacheValue.missing(ttlAt)` |
| `cacheValue.isMissingGuard` | `cacheValue.isMissing` (or `is MissingValue`) |
| `ComputedTtlAt.at(ttl)`, `ComputedTtlAt.FOREVER`, `CacheSecondClock.INSTANCE.currentTime()` | `TtlAt.at(ttl)`, `TtlAt.FOREVER`, `TtlAt.currentTime()` |
| `ClientSideCache` / `DistributedCache` extended `Cache` (`cache[key] = v`) | They are `CacheStore`s: `getCache` / `setCache` / `evict` |
| `me.ahoo.cache.distributed.DistributedCache`, `me.ahoo.cache.consistency.CacheEvicted*`, `me.ahoo.cache.converter.KeyConverter`, `me.ahoo.cache.KeyFilter` | `me.ahoo.cache.api.distributed.DistributedCache`, `me.ahoo.cache.api.consistency.CacheEvicted*`, `me.ahoo.cache.api.converter.KeyConverter`, `me.ahoo.cache.api.filter.KeyFilter` |
| `@GuavaCache`, `GuavaClientSideCache`, `GuavaCacheEvictedEventBus` | `@CaffeineCache`, `CaffeineClientSideCache.build(...)`, `LocalCacheEvictedEventBus` |
| `MockDistributedCache` | `InMemoryDistributedCache` |
| `CoherentCache.clientSideCache` / `.distributedCache` / ... | `CoherentCache.configuration.clientSideCache` / ... |
| Custom `CacheEvictedSubscriber` | Implement `onReset()` (clear local copies) |
| Custom `CodecExecutor` | `executeAndDecode(key)` reads value and TTL itself; extend `StringCodecExecutor` / `HashCodecExecutor` or `AbstractCodecExecutor` |
| `ClientSideCache<User>` bean picked by type | Name it `UserCache.ClientSideCache` |
| `TtlConfiguration`, `TtlConfigurationAware`, `ComputedCache` | Removed — TTL comes from `@CoCache` (`TtlPolicy`) |
| `@CoCache` without `ttl` meant "forever" | Now 3600 s — set `ttl = TtlAt.FOREVER` explicitly to keep the old behavior |
| `JoinCache.evict(key)` evicted both caches | Evicts the first cache only; use `evict(firstKey, joinKey)` for both |
| A `CacheSource` reading the same key from the same cache re-entered the lock | Fails fast with `IllegalStateException` (it previously over-evicted) |

### Dependencies

| Dependency | Version |
|------------|---------|
| Spring Boot | 4.1.1 |
| CosId | 3.2.1 |
| Guava | 33.7.2-jre |
| Kotlin | 2.4.21 |
| JUnit | 6.1.3 |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.0.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>5.0.0</version>
</dependency>
```

## v4.3.0

**Module Group:** `me.ahoo.cocache`

### Highlights

- **Cache Breakdown Protection Hardened** — Replaced the per-key lock map (which had a lock-object recycling race allowing duplicate source loads) with Guava `Striped` locks in `DefaultCoherentCache` (#519).
- **Clock Thread Startup Race Fixed** — `CacheSecondClock` timer thread startup no longer races with field initialization; eliminates the "frozen clock → caches never expire" failure mode (#519).
- **Stale Write-Back Race Fixed** — A per-key invalidation generation counter makes in-flight loads detect eviction events that arrive during loading: stale results are discarded (or compensating-evicted) instead of being pinned into both cache tiers until TTL (#519).
- **Lifecycle Management** — `CoherentCache` now extends `AutoCloseable` with an idempotent `close()` (unregister from event bus + close distributed cache); Spring destroys caches automatically at shutdown (#519).
- **Corrupted Payload Self-Healing** — Undecodable Redis values are deleted and treated as a cache miss (source reload) instead of throwing on every read (#520).
- **Null Value Normalization** — `null` values are consistently written as negative-cache sentinels across all codecs (previously: empty-string corruption, NPEs, or codec-dependent semantics) (#520).
- **Redis Failure Degradation** — By default, Redis read failures now degrade to cache-miss semantics (source reload, business calls unaffected) and write/evict failures only log a warning. Set `cocache.redis.strict-failure=true` to restore the strict throwing behavior (#520).
- **Atomic Hash/Set Writes** — Structural codec writes use single-key Lua scripts (`DEL` + `HSET`/`SADD` + `EXPIRE`), eliminating concurrent-write field-union corruption. Empty Map/Set writes now silently evict the key instead of throwing (#520).
- **Configurable Missing-Guard Sentinel** — New property `cocache.redis.missing-guard-sentinel` mitigates collisions between business data and the `"_nil_"` sentinel (#520).

### Breaking Changes & Behavior Notes

- **API removal (the only breaking change)**: `AbstractCodecExecutor.setPipelined`/`serialize` members were removed — affects only third-party codecs directly extending this abstract class.
- `CodecExecutor.executeAndDecode` return type is now nullable (`CacheValue<V>?`) — covariant-compatible for implementers; direct callers must handle `null` on recompile.
- Redis failures degrade by default (previously threw); JSON codec `null` values become negative-cache sentinels instead of `"null"`-literal round-trips.
- Spring containers now auto-close caches at shutdown; `FactoryBean.getObject()` returns a memoized instance (eliminates duplicate event-bus subscriptions).
- `strict-failure` / `missing-guard-sentinel` apply only to auto-configured (fallback-created) caches; custom `DistributedCache` beans are unaffected.
- Wire formats (storage structure, eviction messages) are unchanged — rolling upgrades are fully compatible.

### Dependencies

| Dependency | Version |
|------------|---------|
| Spring Boot | 4.1.0 |
| CosId | 3.2.0 |
| Guava | 33.6.0-jre |
| Kotlin | 2.4.10 |
| JUnit | 6.1.3 |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:4.3.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>4.3.0</version>
</dependency>
```

## v4.2.0

**Module Group:** `me.ahoo.cocache`

### Dependencies

| Dependency | Version |
|------------|---------|
| Spring Boot | 4.1.0 |
| CosId | 3.2.0 |
| Guava | 33.6.0-jre |
| Kotlin | 2.4.0 |
| JUnit | 6.1.1 |

### Gradle Setup

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:4.2.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>4.2.0</version>
</dependency>
```

## Historical Releases

For the full release history, see [GitHub Releases](https://github.com/Ahoo-Wang/CoCache/releases).
