---
name: "cocache-develop"
description: "Build, test, review or debug CoCache usage in downstream Java/Kotlin applications with me.ahoo.cocache imports or dependencies, or explicit CoCache requests: cache declarations, loaders, invalidation after commit, Spring Cache, JoinCache, SetCache, configuration, tests; review findings; stale-data, hit-rate, Redis or startup root cause. Not the CoCache repository, generic caching, 5.x upgrades (cocache-migrate) or custom backends (cocache-extend)."
---

# Develop, Review and Debug CoCache Usage

## Scope gate

Use only for a downstream application. The CoCache repository itself (its modules, `cocache-example`, docs and release work) never qualifies; its own `AGENTS.md` governs that work. Require one of: `me.ahoo.cocache` imports, a `me.ahoo.cocache:*` dependency in the target module, a failing CoCache behavior, or an explicit request to adopt, use, configure, review or explain CoCache in this application.

These do not qualify on their own: the words cache, Redis, Caffeine, Spring Cache, `@Cacheable`, TTL or invalidation; other cache libraries (Spring Data Redis `RedisCacheManager`, plain Caffeine, Redisson, JetCache, Ehcache, Hazelcast); CoCache mentioned only to compare, reject or exclude it. Otherwise state that this Skill does not apply and stop using it.

Route away and stop: an upgrade from CoCache 5.x (`me.ahoo.cache.*` packages) belongs to `cocache-migrate`, including reviews and failures of that upgrade; implementing a `CoherenceBackend` (a new L1 store or invalidation channel) belongs to `cocache-extend`. Own every other mode of the task below.

## Contract

- Treat the target checkout, its tests, its resolved dependencies and its configuration as authoritative. Confirm exact signatures, property names and defaults in the resolved CoCache version (sources jar, or the matching `vX.Y.Z` tag of the CoCache repository) before relying on them; the references give stable decisions, not an API listing.
- Protect the three guarantees: staleness stays bounded, no invalidation is lost to a concurrent load, and the hit path stays cheap. A change that weakens one is wrong however clean it looks.
- Keep read-only requests (review, diagnosis, explanation) read-only: no file edits, commits, remote changes, actuator `DELETE`, or Redis writes without explicit authorization.
- Report changed files and behavior, the exact verification commands and their results, compatibility or operational risk, and remaining evidence gaps. Never replace a test you could not run with "should pass".

## Target version

Resolve the CoCache version from the build (`./gradlew :module:dependencies --configuration runtimeClasspath`, `mvn dependency:tree`, the BOM, or the lock file) before applying anything:

- **6.x** (`me.ahoo.cocache.*` packages): the references below apply. Re-check any symbol you use against that exact version.
- **5.x** (`me.ahoo.cache.*` packages) with no upgrade requested: none of the 6.x references apply, not even symbol names. Work only from the target's own 5.x sources and label version-specific conclusions accordingly. Moving to 6.x is a `cocache-migrate` task.
- **Unresolvable**: say so and mark version-specific conclusions unverified.

## Rules that prevent most CoCache bugs

1. Change the source of truth, commit, then invalidate. Inside a transaction use `TransactionalInvalidator` (or transaction-aware `@CacheEvict`); never invalidate before the commit.
2. Prefer `invalidate` over `put`. `put` is for a writer that already holds the final value; it does not fill the local L2.
3. Every writer invalidates, including batch jobs, CDC and other services (`ExternalInvalidator` or the documented raw protocol). `ttl` is a safety net for writers you do not control, not the fix for a missing invalidation.
4. `@Cacheable` on a CoCache cache uses `sync = true`; without it Spring's unconditional `put` can overwrite a newer invalidation.
5. A loader returns `null` for "does not exist", throws when the lookup fails, reads the primary, and never reads its own cache for the same key.
6. Keys encode injectively and identically on every instance: a key type with a real `toString()`, a `keyExpression`, or a `KeyEncoder`.
7. `SetCache` only when Redis holds the only copy of the set; a set that lives in the database is `Cache<K, Set<E>>` maintained with `invalidate`.
8. Every instance sharing a cache uses the same key prefix, namespace, codec and `channel-mode`. Nothing else reads or writes CoCache keys in Redis except through the invalidation protocol.

## Modes

**Develop or explain.** Frame the outcome, writable scope and completion evidence. Find the existing declarations (`@EnableCoCache`, `@CoCache`, `CacheSpec` beans, `CacheLoader` and `Codec` beans, `cocache.*` properties) and every write path of the cached data. For a behavior change, write a failing test first (see `references/testing.md`); if the behavior cannot be unit-tested, name the narrowest replacement evidence before editing. Run the narrowest relevant test, then broaden only as the change requires.

**Review** (findings, merge readiness). Resolve the diff range (base, merge-base, staged and unstaged changes). Read each changed cache declaration with its loader, every writer of the cached data, its tests and configuration. Apply `references/review-rubric.md`. Lead with findings ordered by severity, each with file and line evidence, the trigger, the impact and the smallest correct direction; say so when there are none. List the checks you did not run.

**Review-and-fix** (only when fixes are authorized): finish the findings pass first, fix only authorized findings, add a failing test or equivalent pre-fix evidence for each, verify, then review the new diff again. Do not fix unrelated pre-existing issues.

**Diagnose** (stale value, collapsed hit rate, loader stampede, `SourceLoadRejectedException`, startup failure, Redis outage). Capture the exact symptom, cache name, key, instances, configuration, logs and metrics. Locate the first incorrect stage with `references/diagnosis-map.md`. Prefer read-only evidence: `GET /actuator/cocache/{name}/{key}` compares the L2 and L1 copies without loading; `GET /actuator/health/cocache` shows heartbeat, channel mode and pending invalidations. State one falsifiable hypothesis, run the smallest test that distinguishes it (a two-runtime `InMemoryBackend` test reproduces most coherence questions), and conclude with the cause, the affected boundary and the unknowns. Do not lower `ttl`, add sleeps or retries, or relax assertions before the cause is proven.

**Diagnose-and-fix** (only when a fix is requested): keep the reproducer or add a failing regression test, make the smallest change that addresses the confirmed cause, rerun the reproducer and the relevant tests, and report the pre-fix evidence with the fix.

## Load one reference first

| Primary scope | Load |
|---|---|
| Declaring caches: interface or spec bean, names, storage keys, key encoding, loaders, codecs, `ttlOf`, `KeyFilter`, customizers, startup validation | `references/declare-caches.md` |
| Write paths: invalidate after commit, `invalidate` vs `put`, read-modify-write with `getIfChanged` / `putIfMatch`, external writers, choosing `ttl`/`absent-ttl`/`local-max-age`, refresh-ahead, Redis down during writes | `references/freshness.md` |
| `@Cacheable`/`@CachePut`/`@CacheEvict`, `sync = true`, the sync check, several cache managers | `references/spring-cache.md` |
| A read-only view over two caches (`JoinCache`, `@JoinCacheable`) | `references/join-cache.md` |
| A Redis-only set (`SetCache`) or a set that lives in the database | `references/set-cache.md` |
| `cocache.*` properties, backend selection, Redis topology, channel mode, key prefixes, actuator and health, plain `CacheRuntime` without Spring | `references/configuration.md` |
| Production readiness: `maxmemory-policy`, ACL, sizing, rolling-safe configuration changes, alerts | `references/production.md` |
| Tests without Redis, moving time, several instances, Redis failures | `references/testing.md` |

Add `references/review-rubric.md` for a review and `references/diagnosis-map.md` for a diagnosis. Load a second domain reference only when the task genuinely crosses domains, and read only the sections you need.

## Source discovery

```bash
rg -n "me\.ahoo\.cocache" -g '*.kt' -g '*.java'
rg -n "@EnableCoCache|@CoCache|@JoinCacheable|CacheSpec<|SetCacheSpec<|CacheLoader<|Codec<|CacheSpecCustomizer" -g '*.kt' -g '*.java'
rg -n "\.invalidate|invalidateAll|\.put\(|TransactionalInvalidator|ExternalInvalidator|@Cache(able|Put|Evict)" -g '*.kt' -g '*.java'
rg -n "cocache\." -g '*.yml' -g '*.yaml' -g '*.properties'
```

Then find every writer of the cached data that does not go through those calls (repositories, SQL, jobs, consumers). Use the assertion style the target module already uses.

## Completion

Report: the resolved CoCache version; changed files and the behavior each change protects; exact test commands and results (including whether they ran on `cocache.backend=memory`, an `InMemoryBackend` runtime, or real Redis); any configuration that must match across instances; and every gap that only staging or production evidence can close. Docs: https://cocache.ahoo.me/guide/ (operations: https://cocache.ahoo.me/operations/).
