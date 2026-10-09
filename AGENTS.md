# AGENTS.md — CoCache Root

**CoCache** — Level 2 Distributed Coherence Cache Framework (Kotlin/JVM 17, Spring Boot 4.1). Two-level caching (L2 in-memory + L1 Redis) with event-driven coherence, annotation/proxy-based cache interfaces. Published to Maven Central under `me.ahoo.cocache`.

## Project Structure

```
cocache-api          — User API + all SPI (Cache, sealed CacheValue, CacheStore/ClientSideCache/DistributedCache, CacheSource, KeyConverter, KeyFilter, CacheEvictedEventBus)
cocache-core         — Orchestration + defaults (DefaultCoherentCache, TtlPolicy, SingleFlight, Caffeine L2, proxies, JoinCache)
cocache-spring       — Spring integration (@EnableCoCache, factory beans, FactoryBeans)
cocache-spring-redis — Redis distributed cache + codecs + eviction event bus
cocache-spring-cache — Spring Cache abstraction bridge (CoSpringCache/CoCacheManager)
cocache-spring-boot-starter — Auto-configuration + CoCacheProperties + actuator endpoints
cocache-test         — Shared TCK specs (CacheSpec, DistributedCacheSpec, ...)
cocache-example      — Example application
cocache-bom / cocache-dependencies — BOM + centralized version catalog
code-coverage-report — Aggregated JaCoCo coverage
docs/architecture.md — Architecture goals, module boundaries and invariants (single source of truth)
wiki/                — VitePress docs site, bilingual: en at root, zh mirror under zh/ (keep in parity)
```

## Build & Run Commands

```bash
./gradlew build -x test        # Build without tests
./gradlew check                # Full gate: tests + detekt + dokka + license headers + coverage gate (run before committing; needs Redis)
./gradlew test                 # All tests
./gradlew :cocache-core:test   # Single module
./gradlew :cocache-core:test --tests "me.ahoo.cache.proxy.ProxyCacheTest"  # Single class
./gradlew detekt               # Code quality
./gradlew detektAutoFix        # Auto-fix
./gradlew publishToMavenLocal
./gradlew :cocache-spring-redis:jmh -PjmhThreads=8 -PjmhIncludes=l2Hit  # Hit-path JMH (needs Redis; not part of check)

# Wiki (VitePress)
cd wiki && pnpm install && pnpm dev     # Dev server
cd wiki && pnpm build                   # Production build — the ONLY dead-link/mermaid verification
```

- **Integration tests** (`:cocache-spring-redis:*`, `:cocache-spring-boot-starter:*`) require Redis at localhost:6379 (CI uses a service container).

## Testing

- JUnit 5 (Jupiter) + mockk + fluent-assert. mockk is available on every module's test classpath (root build script).
- **Fluent assert** — `import me.ahoo.test.asserts.assert` then `.assert()`:
  - NEVER use AssertJ `assertThat()` in Kotlin tests. `Offset.offset(n)` as an argument is allowed.
  - `assert()` accepts nullable receivers; `isTrue {}` does NOT exist — use `.withFailMessage { ... }`.
  - Prefer `requireNotNull(...)` over `!!` chains (clearer failures, avoids detekt `UnnecessaryNotNullOperator`).
- **TCK specs** (`cocache-test`): stores extend `ClientSideCacheSpec` / `DistributedCacheSpec` (both `CacheStoreSpec`); `Cache` implementations extend `CacheSpec`; orchestration/channels extend `DefaultCoherentCacheSpec`, `MultipleInstanceSyncSpec`, `CacheEvictedEventBusSpec`.
  - Redis-style stores rebuild `ttlAt` from Redis expiry (±1s drift) — `CacheStoreSpec.setWithTtlAt`/`setMissingWithTtlAt` are `open` for `isCloseTo(..., Offset.offset(1))` overrides.
  - Codec-layer specs live in `cocache-spring-redis` test sources (`CodecExecutorSpec` + one class per codec), NOT in `cocache-test`.
  - Redis integration tests use `RedisTestSupport`, whose listener container runs on a `SyncTaskExecutor` so `register()` returns only after subscription callbacks (`onReset`) ran — otherwise late resets make L2 assertions flaky.
  - Local runs: if the shell exports `SPRING_DATA_REDIS_CLUSTER_NODES` etc., starter tests bind to that cluster; unset them to use localhost.
- Race-condition tests: orchestrate with latches (never sleeps); wait on a `finished` latch so a dead background thread fails the test instead of passing vacuously. Assert eventual behavior (e.g. cross-instance propagation) by polling with a timeout.
- Every fixed defect gets a reproducing test.
- Logback configured via `config/logback.xml`.

## Code Style

- Detekt config: `config/detekt/detekt.yml`. Disabled: `LongParameterList`, `TooManyFunctions`, `ReturnCount`, `MagicNumber`, `UnusedPrivateMember`. `MaxLineLength` = 300; `WildcardImport` allowed for `java.util.*`.
- **Detekt gotchas**:
  - `ArgumentListWrapping` is NOT overridden → uses detekt's default `maxLineLength: 120` independent of the project's 300. Multi-argument calls longer than 120 chars (e.g. `log.warn(e) { ... }`) must wrap.
  - Per-source-set tasks `detektMain`/`detektTest` are NOT wired into `check` (only the aggregate `detekt` is) and resolve different config — don't treat their failures as the project gate, but don't add new violations.
- Kotlin compiler: `-Xjsr305=strict`, `-Xjvm-default=all-compatibility` (interface methods with bodies compile to default methods → backward-compatible additions are possible).
- Java compiler: `-parameters`.
- Conventions: Apache-2.0 license header on every source file (including tests); Chinese comments/KDoc are the established style.

## Architecture Invariants (5.0)

`docs/architecture.md` is the single source of truth for design goals and invariants — read it before touching coherence, storage, codec, or proxy code, and update it when behavior changes. The non-negotiables:

- **Storage tiers only store.** `CacheStore` (L2 `ClientSideCache`, L1 `DistributedCache`) holds no TTL/negative-cache policy; `TtlPolicy` lives in the orchestration layer.
- **Negative cache is explicit.** `CacheValue` is sealed (`PresentValue` | `MissingValue`). The `_nil_` sentinel exists only in the Redis codec wire format.
- **L1 miss is never a negative cache.** Absent key, key deleted mid-read (TTL -2), or corrupted payload → `null` (reload). Only a stored sentinel decodes to `MissingValue`.
- **Every write-back is stamp-guarded.** Invalidators bump `InvalidationStamps` *before* evicting; write-backs (L1→L2 fill, source load) take a stamp first and re-check before *and* after writing. Local `evict`/`setCache`, remote `onEvicted`, and `onReset` all invalidate.
- **`onReset` clears L2.** Event channels call it on every (re)subscription; it bounds staleness after lost pub/sub messages.
- **Source loads are coalesced per key** via `SingleFlight` (original exceptions propagate to all waiters; same-key reentrancy fails fast; a call is deregistered *before* its result is published, so a woken waiter that retries always starts a fresh load — read-your-writes depends on this). A successful load does not broadcast.
- **Read-your-writes:** a reader records the stamp before reading and rejects a shared load whose starting stamp is older (it began before an invalidation the reader has already observed); it then reloads once.
- **Proxies unwrap `InvocationTargetException`.**
- **Hit-path performance (JMH-verified):** L2 is a plain bounded Caffeine cache — no per-entry `Expiry` (it writes node metadata on every read; a hot key stopped scaling: 224M → 11M ops/s at 8 threads). Expiry is checked on read via `TtlAt.isExpired`, which reads the cached `CacheClock` (not `System.currentTimeMillis()`, which did not scale on macOS). Redis reads use one atomic Lua script on the shared connection — never `executePipelined` (Lettuce pipelines take a dedicated connection; ~3× slower than two plain round trips without a pool). Re-run `RedisCacheBenchmark` (`./gradlew :cocache-spring-redis:jmh`, threads 1 and 8) against the previous version when touching these paths.
- **Stateful Spring components resolve by bean name only** (`{cacheName}.ClientSideCache|.DistributedCache|.KeyConverter`); `CacheSource`/`JoinKeyExtractor` may fall back to a unique generic type.
- **Wire compatibility:** Redis storage layout and the eviction message (`key@@publisherId`, split on the last `@@`) stay byte-compatible across versions.

## Release Process

1. Bump `version=` in `gradle.properties` via PR (`chore(release): bump version to X.Y.Z`).
2. Create the GitHub Release `vX.Y.Z` → triggers `package-deploy.yml`: verify (Redis integration) → github-deploy → central-deploy (Maven Central).
3. Wiki deploys to GitHub Pages automatically on push to `main` (`deploy-wiki.yml`). On releases: update `wiki/guide/changelog.md` (+zh), sync current-version references in quick-start/index/unit-testing/publishing (keep historical entries in old changelog sections and release-process examples untouched), and bump the nav version badge in `.vitepress/config/{en,zh}.ts`.

## Git Workflow

- Main branch: `main` (protected; changes land via squash-merged PRs). Branch names: `<type>/<short-description>` (the type drives PR labels and release-note categories).
- CI: ci.yml (Static Analysis: actionlint + detekt → code scanning + license headers; Test & Coverage: `check` with Redis + coverage gate + Codecov; Test (JDK 25): same suite on the latest LTS via `-PtestJavaVersion=25`), labeler.yml, deploy-wiki.yml (build on PR, deploy on main), package-deploy.yml (on release published), renovate.yml, gitee-sync.yml. Every workflow: least-privilege `permissions`, `timeout-minutes`, lint with `actionlint`.
- Commits / PR titles: Conventional format (`feat(scope):`, `fix(scope):`, `perf:`, `docs(scope):`, `refactor!:` for breaking). See CONTRIBUTING.md.

## Boundaries

- ✅ Always: Run `./gradlew check` before committing
- ✅ Always: Use fluent-assert `.assert()` in Kotlin tests
- ✅ Always: Follow Detekt rules
- ✅ Always: Keep the Apache-2.0 license header on every source file and build script (`checkLicenseHeader`)
- ✅ Always: Keep aggregate coverage ≥ 90% lines / 80% branches (`codeCoverageVerification`)
- ✅ Always: Extend TCK specs for new cache/codec implementations
- ⚠️ Ask first: Adding new dependencies to version catalog
- ⚠️ Ask first: Modifying cocache-api interfaces or wire formats (breaking-change risk)
- ✅ Always: Update `docs/architecture.md` when changing coherence/storage/proxy behavior
- 🚫 Never: Use AssertJ `assertThat()` in Kotlin tests
- 🚫 Never: Commit without running tests
- 🚫 Never: Push directly to main (all changes go through PRs; squash-merge is the convention)
