# CoCache Usage Review Rubric

Use this rubric after resolving the actual diff and reading each changed cache declaration together with its loader, every writer of the cached data, the tests and the configuration. Verify exact APIs in the resolved CoCache version before citing them.

## Write paths (highest severity)

- A write path of cached data invalidates after the change is committed. Inside `@Transactional`, a direct `cache.invalidate` before or during the transaction is a defect; use `TransactionalInvalidator` or transaction-aware `@CacheEvict`.
- A write path the diff adds or changes (repository method, SQL, job, consumer, admin endpoint) has a matching invalidation. Writers outside the application use `ExternalInvalidator` or the documented raw protocol, and delete before they publish.
- `put` after a write is justified (the writer holds the final value); otherwise prefer `invalidate`. `put` is not expected to fill L2.
- The invalidated key encodes exactly as the reader's key: same type, `keyExpression`/`KeyEncoder`, namespace and prefix.
- `ttl` was not shortened, or `ttl-forever` removed, to hide a missing invalidation.

## Reads and loaders

- `@Cacheable` on a CoCache cache has `sync = true`; new code ideally runs with `cocache.spring-cache.require-sync: true`.
- The loader returns `null` only for "does not exist" and throws on failure; it does not swallow exceptions into `null` (that caches a false negative for `absent-ttl`).
- The loader reads the primary, does not read its own cache for the same key, and overrides `loadAll` with a batch query when `getAll` is used.
- Negative caching is intended: code that creates a row invalidates its key, or `absent-ttl` is short enough.
- The key type overrides `toString()` (data class, record) or has an injective `keyExpression`/`KeyEncoder`; a hot-path `keyExpression` is noted as a cost.

## Shapes

- `SetCache` holds only data whose only copy is Redis; database-backed sets are `Cache<K, Set<E>>` with `invalidate`. No loader on a `SetCache`, no `invalidate` calls on it, no external `DEL`.
- A `JoinCache` is registered in `joinCaches`, names both caches explicitly, uses `joinKeyExpression` only for `String` join keys, and its component caches are invalidated at their own write paths.
- Interface caches add helpers as default methods only; spec beans declare generic return types and set a codec.

## Configuration and compatibility

- Properties exist in the resolved version; bare numbers are seconds under `defaults`/`caches` and milliseconds under `redis`. Names under `cocache.caches` match declared caches.
- `refresh-window` stays below `ttl - ttl-jitter` and `local-max-age`.
- A change of `key-prefix`, `namespace`, cache name (when the namespace defaults to it), codec byte format or `channel-mode` is called out as not rolling-safe, with a switch plan.
- Applications sharing a Redis use distinct key prefixes; set caches run on a Redis with a non-`allkeys-*` eviction policy.
- Nothing outside CoCache reads or writes CoCache keys directly.
- `cocache.backend=memory` is confined to tests and local profiles, never a deployed profile.
- Retry, bulkhead and failure modes (`invalidate-failure`, `read-failure`, `load-max-concurrent`) match how callers handle `CacheWriteException`, `CacheReadException` and `SourceLoadRejectedException`.

## Tests and evidence

- Behavior changes have focused tests: read caching, invalidation after commit, the committed value after a rollback, negative entries, expiry with a `ManualClock`, cross-instance invalidation with two runtimes, and failure behavior with `HookedBackend` where relevant.
- Race tests use latches and polling with timeouts, not sleeps.
- Memory-backend evidence is not presented as proof of Redis topology, ACL or channel behavior.

## Finding threshold

Report a finding only when the diff introduces a concrete defect, regression, unsafe operational change, or a claim without the evidence it needs. Give severity, file and line, trigger, impact, and the smallest correct direction.
