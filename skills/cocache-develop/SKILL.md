---
name: "cocache-develop"
description: "Build, test, review or debug CoCache usage in downstream Java/Kotlin applications with me.ahoo.cocache imports or dependencies, or explicit CoCache requests: cache types and declarations, loaders, invalidation after commit, Store and SetStore writes, Spring Cache, join views, configuration, tests; review findings; stale-data, hit-rate, Redis or startup root cause. Not the CoCache repository, generic caching or custom backends (cocache-extend)."
---

# Develop, Review and Debug CoCache Usage

## Scope gate

Use only for a downstream application. The CoCache repository itself (its modules, `cocache-example`, docs and release work) never qualifies; its own `AGENTS.md` governs that work. Require one of: `me.ahoo.cocache` imports, a `me.ahoo.cocache:*` dependency in the target module, a failing CoCache behavior, or an explicit request to adopt, use, configure, review or explain CoCache in this application.

These do not qualify on their own: the words cache, Redis, Caffeine, Spring Cache, `@Cacheable`, TTL or invalidation; other cache libraries (Spring Data Redis `RedisCacheManager`, plain Caffeine, Redisson, JetCache, Ehcache, Hazelcast); CoCache mentioned only to compare, reject or exclude it. Otherwise state that this Skill does not apply and stop using it.

Route away and stop: implementing a `CoherenceBackend` (a new L1 store or invalidation channel) belongs to `cocache-extend`. Own every other mode of the task below.

## Contract

- Treat the target checkout, its tests, its resolved dependencies and its configuration as authoritative. Confirm exact signatures, property names and defaults in the resolved CoCache version (sources jar, or the matching `vX.Y.Z` tag of the CoCache repository) before relying on them; the references give stable decisions, not an API listing.
- Protect the three guarantees: staleness stays bounded, no invalidation is lost to a concurrent load, and the hit path stays cheap. A change that weakens one is wrong however clean it looks.
- Keep read-only requests (review, diagnosis, explanation) read-only: no file edits, commits, remote changes, actuator `DELETE`, or Redis writes without explicit authorization.
- Report changed files and behavior, the exact verification commands and their results, compatibility or operational risk, and remaining evidence gaps. Never replace a test you could not run with "should pass".

## Target version

Resolve the CoCache version from the build (`./gradlew :module:dependencies --configuration runtimeClasspath`, `mvn dependency:tree`, the BOM, or the lock file) before applying anything:

- **8.x**: the references below apply. Re-check any symbol you use against that exact version.
- **Another major version**: the references describe 8.x only and do not apply, not even symbol names. Work only from the target's own resolved sources and label version-specific conclusions with that version. For a move to 8.x, follow the upgrade page (https://cocache.ahoo.me/guide/upgrade), then apply the references to the upgraded code.
- **Unresolvable**: say so and mark version-specific conclusions unverified.

## Rules that prevent most CoCache bugs

1. Pick the type by one question: if Redis loses this key, can this process rebuild it? Yes: `Cache` (or `VersionedCache`); a `Cache` whose values only callers supply is `externallySourced()`. No: `Store`, `VersionedStore` or `SetStore`. A wrong type expires the only copy or never reloads a rebuildable one.
2. Change the source of truth, commit, then invalidate. Inside a transaction use `TransactionalInvalidator` (or transaction-aware `@CacheEvict`); never invalidate before the commit.
3. Prefer `invalidate` over `put`. `put` is for a writer that already holds the final value; it does not fill the local L2.
4. Every writer invalidates, including batch jobs, CDC and other services (`ExternalInvalidator` or the documented raw protocol). `ttl` is a safety net for writers you do not control, not the fix for a missing invalidation.
5. `@Cacheable` on a CoCache cache uses `sync = true`, and the cache is an externally sourced `Cache` (`@CoCache(source = CoCache.Source.EXTERNAL)`); without it Spring's unconditional `put` can overwrite a newer invalidation.
6. A loader returns `null` for "does not exist" (a versioned loader `Versioned(null, version)` for a deletion), throws when the lookup fails, reads the primary, and never reads its own cache for the same key.
7. A read without a source (authoritative types, an externally sourced `Cache` without a loader) answers `Lookup.Unknown` when Redis fails: handle `CacheReadException`, never treat `Unknown` as absent unless `unknownRead = ABSENT` is safe for that data.
8. Keys encode injectively and identically on every instance: a key type with a real `toString()`, a `keyExpression`, or a `KeyEncoder`.
9. Every instance sharing a cache uses the same type, key prefix, namespace, codec and `channel-mode`. Nothing else reads or writes CoCache keys in Redis except through the invalidation protocol.
10. Code that creates caches outside the starter's declarations (a library, a plugin) uses the `CoCacheFactory` bean, locking the fields it depends on. A cache created with `runtime.create` is unlayered: no `cocache.*` property reaches it.

## Modes

**Develop or explain.** Frame the outcome, writable scope and completion evidence. Find the existing declarations (`@EnableCoCache`, `@CoCache`, spec beans, `CacheLoader` and `Codec` beans, `cocache.*` properties) and the type each one extends and every write path of the cached data. For a behavior change, write a failing test first (see `references/testing.md`); if the behavior cannot be unit-tested, name the narrowest replacement evidence before editing. Run the narrowest relevant test, then broaden only as the change requires.

**Review** (findings, merge readiness). Resolve the diff range (base, merge-base, staged and unstaged changes). Read each changed cache declaration with its loader, every writer of the cached data, its tests and configuration. Apply `references/review-rubric.md`. Lead with findings ordered by severity, each with file and line evidence, the trigger, the impact and the smallest correct direction; say so when there are none. List the checks you did not run.

**Review-and-fix** (only when fixes are authorized): finish the findings pass first, fix only authorized findings, add a failing test or equivalent pre-fix evidence for each, verify, then review the new diff again. Do not fix unrelated pre-existing issues.

**Diagnose** (stale value, collapsed hit rate, loader stampede, `SourceLoadRejectedException`, startup failure, Redis outage). Capture the exact symptom, cache name, key, instances, configuration, logs and metrics. Locate the first incorrect stage with `references/diagnosis-map.md`. Prefer read-only evidence: `GET /actuator/cocache/{name}/{key}` compares the L2 and L1 copies without loading; `GET /actuator/health/cocache` shows heartbeat, channel mode and pending invalidations. State one falsifiable hypothesis, run the smallest test that distinguishes it (a two-runtime `InMemoryBackend` test reproduces most coherence questions), and conclude with the cause, the affected boundary and the unknowns. Do not lower `ttl`, add sleeps or retries, or relax assertions before the cause is proven.

**Diagnose-and-fix** (only when a fix is requested): keep the reproducer or add a failing regression test, make the smallest change that addresses the confirmed cause, rerun the reproducer and the relevant tests, and report the pre-fix evidence with the fix.

## Load one reference first

| Primary scope | Load |
|---|---|
| Choosing among `Cache`, `VersionedCache`, `Store`, `VersionedStore`, `SetStore`; declaring caches: interface or spec bean, names, storage keys, key encoding, loaders and externally sourced caches, `Lookup.Unknown` and `unknownRead`, codecs, `ttlOf`, `KeyFilter`, customizers, startup validation | `references/declare-caches.md` |
| Write paths: invalidate after commit, `invalidate` vs `put`, read-modify-write on a `Store` with `getIfChanged` / `putIfMatch` / `deleteIfMatch`, versioned writers with `putIfNewer` / `invalidateIfNewer` / `evict` / `deleteIfNewer`, external writers, choosing `ttl`/`absent-ttl`/`local-max-age`, refresh-ahead, Redis down during writes and `outcomeUnknown` | `references/freshness.md` |
| `@Cacheable`/`@CachePut`/`@CacheEvict`, `sync = true`, the sync check, several cache managers | `references/spring-cache.md` |
| A view over two caches (`JoinView`, `JoinCache`, `@JoinCacheable`) | `references/join-cache.md` |
| A Redis-only set (`SetStore`) or a set that lives in the database | `references/set-store.md` |
| `cocache.*` properties and their layering (`defaults`, `overrides`, `caches.[name]`), units and sentinels, library locks, backend selection, Redis topology, channel mode, key prefixes, the eviction check, actuator and health, plain `CacheRuntime` without Spring | `references/configuration.md` |
| Production readiness: `maxmemory-policy`, ACL, sizing, rolling-safe configuration changes, alerts | `references/production.md` |
| Tests without Redis, moving time, several instances, Redis failures | `references/testing.md` |

Add `references/review-rubric.md` for a review and `references/diagnosis-map.md` for a diagnosis. Load a second domain reference only when the task genuinely crosses domains, and read only the sections you need.

## Source discovery

```bash
rg -n "me\.ahoo\.cocache" -g '*.kt' -g '*.java'
rg -n "@EnableCoCache|@CoCache|@JoinCacheable|(Cache|Store)Spec<|CacheLoader<|Codec<|CacheSpecCustomizer" -g '*.kt' -g '*.java'
rg -n ": (Cache|VersionedCache|Store|VersionedStore|SetStore)<|JoinView<|JoinCache<|Lookup\." -g '*.kt' -g '*.java'
rg -n "\.invalidate|invalidateAll|\.put\(|\.delete|IfMatch|IfNewer|\.evict|\.purge|TransactionalInvalidator|ExternalInvalidator|@Cache(able|Put|Evict)" -g '*.kt' -g '*.java'
rg -n "cocache\." -g '*.yml' -g '*.yaml' -g '*.properties'
```

Then find every writer of the cached data that does not go through those calls (repositories, SQL, jobs, consumers). Use the assertion style the target module already uses.

## Completion

Report: the resolved CoCache version; changed files and the behavior each change protects; exact test commands and results (including whether they ran on `cocache.backend=memory`, an `InMemoryBackend` runtime, or real Redis); any configuration that must match across instances; and every gap that only staging or production evidence can close. Docs: https://cocache.ahoo.me/guide/ (choosing a type: https://cocache.ahoo.me/guide/caches#types; operations: https://cocache.ahoo.me/operations/).
