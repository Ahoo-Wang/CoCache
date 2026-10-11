# AGENTS.md — CoCache

CoCache is a two-level coherent cache for Kotlin/Java on Spring Boot 4. Each instance keeps a bounded Caffeine copy (L2) in front of shared Redis (L1) in front of a `CacheSource`. Writes and evicts broadcast invalidations over Redis Pub/Sub. Artifacts target JDK 17 and ship to Maven Central as `me.ahoo.cocache:*`.

The product trades staleness for latency. Every rule below protects one of three goals:

1. **Staleness is bounded.** Any inconsistency heals within a finite TTL.
2. **No invalidation is ever lost.** Concurrent loads and out-of-order events never write a stale value back.
3. **The hit path stays cheap.** An L2 hit costs almost nothing and scales with threads. An L1 hit is one round trip.

If a change weakens one of these goals, it is wrong, however clean it looks.

## Sources of truth

Each fact has one home. Update that home and link to it. Don't copy it here.

| Fact | Home |
|------|------|
| Design, module boundaries, read and write paths, **invariants** (normative, Chinese) | `docs/architecture.md` |
| Issues, branches, PR titles, quality gates, versioning, release steps | `CONTRIBUTING.md` |
| User docs (en + `zh/` mirror) and their conventions | `wiki/`, `wiki/AGENTS.md` |
| Guidance for agents *using* CoCache in other projects | `skills/cocache/` |
| How to build, test, and style changes *to* CoCache | this file |

## Commands

Integration tests and benchmarks need Redis on `localhost:6379`:

```bash
docker run -d --name cocache-redis -p 6379:6379 redis:7-alpine
```

| Purpose | Command |
|---------|---------|
| Full gate (same as CI): tests, detekt, dokka, license headers, JMH compile, coverage ≥ 95% lines / ≥ 90% branches | `./gradlew check` |
| Compile only | `./gradlew build -x test` |
| One module | `./gradlew :cocache-core:test` |
| One class or method | `./gradlew :cocache-core:test --tests "me.ahoo.cache.proxy.ProxyCacheTest"` |
| Lint (**rewrites files**: `autoCorrect = true`) | `./gradlew detekt` |
| Runtime compatibility on a newer JDK | `./gradlew test -PtestJavaVersion=25` |
| Hit-path benchmark (never run by `check`) | `./gradlew :cocache-spring-redis:jmh -PjmhThreads=8 -PjmhIncludes=l2Hit` |
| Wiki build, the only dead-link check | `cd wiki && pnpm install && pnpm build` |

If your shell exports `SPRING_DATA_REDIS_*` variables (for example `SPRING_DATA_REDIS_CLUSTER_NODES`), the starter tests bind to that Redis instead of localhost. Unset them first.

## Where code goes

Dependencies point one way: `api ← core ← spring ← spring-redis`, and `core ← spring-cache`. The starter depends on all three Spring modules.

- `cocache-api` holds the user API and every SPI, and has zero runtime dependencies. Changing it is a public-API change.
- `cocache-core` holds orchestration (`DefaultCoherentCache`, `SingleFlight`, `InvalidationStamps`, `TtlPolicy`), the proxies (`CacheInvocationHandler`), and in-memory defaults.
- Stores only store. TTL jitter, negative caching, and other policy live in `TtlPolicy`, owned by the orchestration layer. A store sees only an absolute `ttlAt`.
- `cocache-test` is a **published** TCK. Anything added under `src/main` becomes API for third-party implementations.
- `cocache-example` is the example app, and the starter tests run against it.

## Invariants that "simple" refactors break

Read `docs/architecture.md` before you touch coherence, storage, codec, proxy, or Spring wiring code. If behavior changes, update it in the same PR.

- **An L1 miss is a miss.** A key that is absent, deleted mid-read, or holds a corrupt payload returns `null` and reloads. Never infer a negative entry from it, and never delete from the read path. Negative entries exist only as `MissingValue`.
- **Every write-back is stamp-guarded.** Take the stamp, check it before the write, write, then check it again. If the stamp changed after the write, undo the write. Invalidators bump the stamp *before* they evict.
- **`SingleFlight` deregisters a call before it publishes the result.** Read-your-writes relies on that order.
- **Set-element add and remove are atomic in L1.** Fill-backs for Set caches are conditional (`fillCache`, `evictIfUnchanged`), so a load that started earlier can't swallow an element write.
- **Stateful Spring components resolve by bean name only.** These are the L2, the L1, and the `KeyConverter`. Resolving them by type lets two caches share one instance.
- **The proxy unwraps `InvocationTargetException`.** Callers always see the original exception.
- **The wire format is frozen within a major version.** This covers the Redis key and value layout, the `_nil_` sentinel encodings, and the `key@@publisherId` message format.
- **Hit-path changes need numbers.** Compare JMH results against the previous version at 1 and 8 threads, and put them in the PR.

## Tests

- Use JUnit 5, MockK, and fluent-assert (`import me.ahoo.test.asserts.assert`, then `x.assert()…`).
  - `assert()` accepts nullable receivers. There is no `isTrue {}`, so use `.withFailMessage { … }`.
  - Prefer `requireNotNull(…)` to chains of `!!`.
  - Don't use AssertJ `assertThat()`. Two exceptions: `assertThat(context)` on Spring's `ApplicationContextRunner` in starter tests, and `Offset.offset(n)` as an argument.
- New implementations must pass the TCK in `cocache-test`:

  | You implement | Extend |
  |---------------|--------|
  | L2 or L1 store | `ClientSideCacheSpec` / `DistributedCacheSpec` |
  | Set-element store | `SetElementsCacheSpec` |
  | `Cache` | `CacheSpec` |
  | Orchestration or invalidation channel | `DefaultCoherentCacheSpec`, `MultipleInstanceSyncSpec`, `consistency/CacheEvictedEventBusSpec` |
  | Codec | `CodecExecutorSpec` (in `cocache-spring-redis` test sources, not the TCK) |

  Stores that rebuild `ttlAt` from Redis expiry drift by ±1 s. Override the `open` TTL tests with `isCloseTo(…, Offset.offset(1))`.
- Redis tests use `RedisTestSupport`. Its listener container runs on a `SyncTaskExecutor`, so `onReset` finishes inside `register()`. Without it, a late reset clears L2 mid-test and the test flakes.
- Concurrency tests:
  - Orchestrate races with latches, never sleeps.
  - Wait on a `finished` latch, so a dead background thread fails the test instead of passing silently.
  - Assert cross-instance effects by polling with a timeout.
- Every fixed defect gets a test that reproduces it. The in-flight cases in `DefaultCoherentCacheSpec` are the model.
- The coverage gate is an aggregate across modules (TCK specs are excluded). New branches without tests can fail `check` even when every test passes.

## Code style

- Detekt config is `config/detekt/detekt.yml`. `MaxLineLength` is 300, but `ArgumentListWrapping` keeps detekt's default of 120, so wrap long multi-argument calls such as `log.warn(e) { … }`.
- Per-source-set `detektMain` and `detektTest` are not part of `check`, and they resolve a different config. Their output is not the gate, but don't add new findings to it.
- Kotlin compiles with `-Xjsr305=strict` and `-jvm-default=enable`. An interface method with a body becomes a Java default method, so adding one is binary-compatible.
- Every source file and build script carries the Apache-2.0 header. `checkLicenseHeader` enforces it, so copy the header from a neighboring file.
- Write comments and KDoc in Chinese.

## Boundaries

- ✅ Run `./gradlew check` with Redis up before you commit.
- ✅ Update `docs/architecture.md` and the wiki (en + zh) in the same PR as a behavior change.
- ⚠️ Ask first before you add a dependency, change a `cocache-api` interface, or change a wire format.
- 🚫 Never push to `main`. Work on a `<type>/<short-description>` branch. Changes land as squash-merged PRs with Conventional Commit titles.
