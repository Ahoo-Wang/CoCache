# CoCache Usage Review Rubric

Use this rubric after resolving the actual diff and reading each changed cache declaration together with its loader, every writer of the cached data, the tests and the configuration. Verify exact APIs in the resolved CoCache version before citing them.

## Type (highest severity)

- Each cache's type answers "if Redis loses this key, can this process rebuild it?": a rebuildable copy is a `Cache` or `VersionedCache`; only-copy data is a `Store`, `VersionedStore` or `SetStore`. Only-copy data declared as an externally sourced `Cache` (expires after the default 1 h `ttl`, deletes retried later) and a rebuildable copy declared as a `Store` (absent forever after Redis loses it) are defects.
- Every declaration of a namespace, across applications, uses the same type.
- Code that switches over `Lookup` handles `Unknown`; `as? Lookup.Present` or `?: null` on a lookup silently turns a Redis failure into "absent". `unknownRead = ABSENT` is justified by data where "absent" is safe.
- A versioned loader expresses deletions as `Versioned(null, version)`, not a plain `null`.

## Write paths (highest severity)

- A write path of cached data invalidates after the change is committed. Inside `@Transactional`, a direct `cache.invalidate` before or during the transaction is a defect; use `TransactionalInvalidator` or transaction-aware `@CacheEvict`.
- A write path the diff adds or changes (repository method, SQL, job, consumer, admin endpoint) has a matching invalidation. Writers outside the application use `ExternalInvalidator` or the documented raw protocol, and delete before they publish.
- `put` after a write is justified (the writer holds the final value); otherwise prefer `invalidate`. `put` is not expected to fill L2.
- Several writers that read, change and write one `Store` value use `getIfChanged` / `putIfMatch` / `deleteIfMatch`, not `put`; ordered writers use a versioned type with `putIfNewer`.
- Callers of authoritative writes and deletes (`Store.delete`, `deleteIfMatch`, `purge`, `SetStore` writes, `VersionedCache.evict`) handle `CacheWriteException`: these never retry. A retry after `outcomeUnknown = true` is safe for the operation used.
- The invalidated key encodes exactly as the reader's key: same type, `keyExpression`/`KeyEncoder`, namespace and prefix.
- `ttl` was not shortened, or `ttl-forever` removed, to hide a missing invalidation.

## Reads and loaders

- `@Cacheable` on a CoCache cache has `sync = true`; new code ideally runs with `cocache.spring-cache.require-sync: true`.
- The loader returns `null` only for "does not exist" and throws on failure; it does not swallow exceptions into `null` (that caches a false negative for `absent-ttl`).
- The loader reads the primary, does not read its own cache for the same key, and overrides `loadAll` with a batch query when `getAll` is used.
- Negative caching is intended: code that creates a row invalidates its key, or `absent-ttl` is short enough.
- The key type overrides `toString()` (data class, record) or has an injective `keyExpression`/`KeyEncoder`; a hot-path `keyExpression` is noted as a cost.

## Shapes

- `SetStore` holds only data whose only copy is Redis; database-backed sets are `Cache<K, Set<E>>` with `invalidate`. No loader on a `SetStore`, no external `DEL` of any authoritative key.
- A join view is registered in `joinCaches`, names both caches explicitly, uses `joinKeyExpression` only for `String` join keys, extends `JoinView` when a side isn't a `Cache`, and its component caches are kept fresh at their own write paths.
- Spring Cache annotations and `TransactionalInvalidator` name only `Cache`s; a `Cache` used only through Spring Cache declares `source = EXTERNAL`.
- Interface caches add helpers as default methods only; spec beans declare generic return types and set a codec.

## Configuration and compatibility

- Properties exist in the resolved version; bare numbers are seconds under `defaults`/`caches` and milliseconds under `redis`. Names under `cocache.caches` match declared caches.
- `refresh-window` stays below `ttl - ttl-jitter` and `local-max-age`.
- A change of `key-prefix`, `namespace`, cache name (when the namespace defaults to it), codec byte format or `channel-mode` is called out as not rolling-safe, with a switch plan.
- Applications sharing a Redis use distinct key prefixes; authoritative types run on a Redis with a non-`allkeys-*` eviction policy and persistence.
- Nothing outside CoCache reads or writes CoCache keys directly.
- `cocache.backend=memory` is confined to tests and local profiles, never a deployed profile.
- Retry, bulkhead and failure modes (`invalidate-failure`, `read-failure`, `unknownRead`, `load-max-concurrent`) match how callers handle `CacheWriteException`, `CacheReadException` and `SourceLoadRejectedException`.

## Tests and evidence

- Behavior changes have focused tests: read caching, invalidation after commit, the committed value after a rollback, negative entries, expiry with a `ManualClock`, cross-instance invalidation with two runtimes, and failure behavior with `HookedBackend` where relevant.
- Race tests use latches and polling with timeouts, not sleeps.
- Memory-backend evidence is not presented as proof of Redis topology, ACL or channel behavior.

## Finding threshold

Report a finding only when the diff introduces a concrete defect, regression, unsafe operational change, or a claim without the evidence it needs. Give severity, file and line, trigger, impact, and the smallest correct direction.
