# AGENTS.md — CoCache Root

**CoCache** — Level 2 Distributed Coherence Cache Framework (Kotlin/JVM 17, Spring Boot 4.1). Two-level caching (L2 in-memory + L1 Redis) with event-driven coherence, annotation/proxy-based cache interfaces. Published to Maven Central under `me.ahoo.cocache`.

## Project Structure

```
cocache-api          — Core interfaces (Cache, CacheValue, ClientSideCache, CacheSource)
cocache-core         — Default implementations (DefaultCoherentCache, proxies, clock, MissingGuard)
cocache-spring       — Spring integration (@EnableCoCache, factory beans, FactoryBeans)
cocache-spring-redis — Redis distributed cache + codecs + eviction event bus
cocache-spring-cache — Spring Cache abstraction bridge (CoSpringCache/CoCacheManager)
cocache-spring-boot-starter — Auto-configuration + CoCacheProperties + actuator endpoints
cocache-test         — Shared TCK specs (CacheSpec, DistributedCacheSpec, ...)
cocache-example      — Example application
cocache-bom / cocache-dependencies — BOM + centralized version catalog
code-coverage-report — Aggregated JaCoCo coverage
docs/superpowers/    — Design specs (specs/) and implementation plans (plans/) — read before touching concurrency/codec areas
wiki/                — VitePress docs site, bilingual: en at root, zh mirror under zh/ (keep in parity)
```

## Build & Run Commands

```bash
./gradlew build -x test        # Build without tests
./gradlew check                # Full gate: tests + detekt + dokka (run before committing)
./gradlew test                 # All tests
./gradlew :cocache-core:test   # Single module
./gradlew :cocache-core:test --tests "me.ahoo.cache.proxy.ProxyCacheTest"  # Single class
./gradlew detekt               # Code quality
./gradlew detektAutoFix        # Auto-fix
./gradlew publishToMavenLocal

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
- **TCK specs** (`cocache-test`): extend `CacheSpec`, `ClientSideCacheSpec`, `DistributedCacheSpec`, `DefaultCoherentCacheSpec`, `MultipleInstanceSyncSpec`, `CacheEvictedEventBusSpec`.
  - Redis-style implementations (TTL reconstructed from Redis expiry) drift ±1s across the write→read second boundary — `CacheSpec.setWithTtl`/`setWithTtlAmplitude` are `open` so they can override exact equality with `isCloseTo(..., Offset.offset(1))` (in-memory impls inherit exact assertions).
  - Codec-layer specs live in `cocache-spring-redis` test sources (`CodecExecutorSpec` + one concrete class per codec), NOT in `cocache-test` (would be a reverse dependency on the implementation).
- Race-condition tests: orchestrate with latches (never sleeps); capture loader results via `AtomicReference` + a `finished` latch so a dead loader thread fails the test instead of passing vacuously.
- Logback configured via `config/logback.xml`.

## Code Style

- Detekt config: `config/detekt/detekt.yml`. Disabled: `LongParameterList`, `TooManyFunctions`, `ReturnCount`, `MagicNumber`, `UnusedPrivateMember`. `MaxLineLength` = 300; `WildcardImport` allowed for `java.util.*`.
- **Detekt gotchas**:
  - `ArgumentListWrapping` is NOT overridden → uses detekt's default `maxLineLength: 120` independent of the project's 300. Multi-argument calls longer than 120 chars (e.g. `log.warn(e) { ... }`) must wrap.
  - Per-source-set tasks `detektMain`/`detektTest` are NOT wired into `check` (only the aggregate `detekt` is) and resolve different config — don't treat their failures as the project gate, but don't add new violations.
- Kotlin compiler: `-Xjsr305=strict`, `-Xjvm-default=all-compatibility` (interface methods with bodies compile to default methods → backward-compatible additions are possible).
- Java compiler: `-parameters`.
- Conventions: Apache-2.0 license header on every source file (including tests); Chinese comments/KDoc are the established style.

## Architecture Invariants (v4.3.0 — read before touching these areas)

**`DefaultCoherentCache` (cocache-core)**

- Per-key locks are Guava `Striped.lock(1024)` — lock objects are never removed/recycled (removal caused the old mutual-exclusion race). Stripe collisions only serialize, never break correctness.
- `loadGenerations` (invalidation counter): entries exist ONLY during an in-flight load (registered before source load, removed in `finally` while still holding the stripe lock). The bump in `onEvicted` MUST stay AFTER the cacheName-mismatch and self-publish filters — self-published events must not invalidate the cache's own in-flight loads.
- `close()` is idempotent (atomic CAS), cooperative (does not interrupt in-flight loads): unregisters from the event bus + closes the distributed cache.

**Codec layer (`AbstractCodecExecutor`, cocache-spring-redis)**

- `ttlAt` parameters are ABSOLUTE deadlines. Never build guards via `DefaultCacheValue.missingGuard(ttlAt)` — that overload treats its argument as a RELATIVE duration (now+ttl) and would double-count the epoch. Use `missingGuardCacheValue(ttlAt)`.
- `executeAndDecode` return semantics are contract: `null` = corrupted payload (self-heal: key deleted, caller treats as miss → source reload); a missing-guard value = negative cache (suppresses reload).
- Empty Map/Set writes evict the key. Lua write helpers treat `ttlSeconds = 0` as FOREVER → callers pass `expiredDuration.seconds.coerceAtLeast(1)`.
- Missing-guard sentinel is per-codec constructor-injectable, default `MissingGuard.STRING_VALUE`. The default wire format must stay byte-identical.
- Redis storage structure and the eviction message format (`key@@clientId`) must remain byte-level compatible (rolling upgrades).

**`RedisDistributedCache` (cocache-spring-redis)**

- Catches `DataAccessException` only (never Throwable): read failure → return null (miss → upper layer reloads from source); write/evict failure → WARN + swallow; `strictFailure = true` rethrows. These properties reach only auto-configured (fallback-created) caches — custom `DistributedCache` beans manage their own policy.

## Release Process

1. Bump `version=` in `gradle.properties` via PR (`chore(release): bump version to X.Y.Z`).
2. Create the GitHub Release `vX.Y.Z` → triggers `package-deploy.yml`: verify (Redis integration) → github-deploy → central-deploy (Maven Central).
3. Wiki deploys to GitHub Pages automatically on push to `main` (`deploy-wiki.yml`). On releases: update `wiki/guide/changelog.md` (+zh), sync current-version references in quick-start/index/unit-testing/publishing (keep historical entries in old changelog sections and release-process examples untouched), and bump the nav version badge in `.vitepress/config/{en,zh}.ts`.

## Git Workflow

- Main branch: `main`. CI: integration-test.yml, codecov.yml, package-deploy.yml, deploy-wiki.yml, gitee-sync.yml, renovate.yml.
- Commits: Conventional format (`feat(scope):`, `fix(scope):`, `docs(scope):`, `chore(release):`, `test:`).

## Boundaries

- ✅ Always: Run `./gradlew check` before committing
- ✅ Always: Use fluent-assert `.assert()` in Kotlin tests
- ✅ Always: Follow Detekt rules
- ✅ Always: Extend TCK specs for new cache/codec implementations
- ⚠️ Ask first: Adding new dependencies to version catalog
- ⚠️ Ask first: Modifying cocache-api interfaces or wire formats (breaking-change risk)
- 🚫 Never: Use AssertJ `assertThat()` in Kotlin tests
- 🚫 Never: Commit without running tests
- 🚫 Never: Push directly to main (all changes go through PRs; squash-merge is the convention)
