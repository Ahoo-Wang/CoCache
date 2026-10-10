# AGENTS.md — CoCache

CoCache is a two-level distributed coherent cache for Kotlin/Java on Spring Boot 4: L2 bounded Caffeine per instance, L1 shared Redis, and invalidation over Redis Pub/Sub. It targets JVM 17 and is published to Maven Central as `me.ahoo.cocache:*`.

## Where each fact lives

Each fact has one home. Update that home, and link to it instead of copying it.

| Fact | Home |
|------|------|
| Design goals, module boundaries, **invariants** (normative) | `docs/architecture.md` (Chinese) |
| Workflow, branch/PR rules, quality gates, versioning, **release steps** | `CONTRIBUTING.md` |
| User docs: usage, configuration, operations, changelog | `wiki/` (en + `zh/` mirror; conventions in `wiki/AGENTS.md`) |
| Guidance for agents *using* CoCache in other projects | `skills/cocache/` |
| Build, test, and style rules for agents *changing* CoCache | this file |

## Layout

```
cocache-api                  user API + all SPI (zero runtime deps)
cocache-core                 orchestration + defaults (DefaultCoherentCache, SingleFlight, InvalidationStamps, proxies, JoinCache)
cocache-spring               @EnableCoCache, FactoryBeans, component resolution by bean name
cocache-spring-redis         Redis L1 + codecs + RedisCacheEvictedEventBus; JMH benchmark in src/jmh
cocache-spring-cache         Spring CacheManager bridge
cocache-spring-boot-starter  auto-configuration, CoCacheProperties, actuator endpoints
cocache-test                 TCK specs (published; new implementations extend them)
cocache-example              example app (also used by starter tests)
cocache-bom / cocache-dependencies / code-coverage-report
```

## Commands

```bash
./gradlew check                       # the gate: tests + detekt + dokka + license headers + coverage (≥95% lines / ≥90% branches); needs Redis
./gradlew build -x test
./gradlew :cocache-core:test --tests "me.ahoo.cache.proxy.ProxyCacheTest"
./gradlew detekt                      # detektAutoFix to auto-format
./gradlew :cocache-spring-redis:jmh -PjmhThreads=8 -PjmhIncludes=l2Hit   # hit-path JMH; needs Redis; never run by check
docker run -d --name cocache-redis -p 6379:6379 redis:7-alpine          # Redis for integration tests
cd wiki && pnpm install && pnpm build # docs build: the only dead-link check
```

## Before changing behavior

Read `docs/architecture.md` before you touch coherence, storage, codec, or proxy code, and update it in the same PR when behavior changes. The invariants most easily broken by a "simple" refactor:

- An L1 miss (absent, deleted mid-read, or corrupted) returns `null` and reloads. It is never a negative cache, and reads never delete.
- Every write-back is stamp-guarded: take the stamp, check it before the write, write, check it again after.
- `SingleFlight` deregisters a call *before* publishing its result, and read-your-writes depends on that order.
- Stateful Spring components resolve by bean name only.
- The Redis layout and the `key@@publisherId` message format are wire-compatible across versions.
- Hit-path changes need a JMH comparison against the previous version, at 1 and 8 threads.

## Testing

- JUnit 5 + mockk + fluent-assert: `import me.ahoo.test.asserts.assert`, then `.assert()`.
  - Never use AssertJ `assertThat()`. Using `Offset.offset(n)` as an argument is fine.
  - `assert()` accepts nullable receivers. `isTrue {}` does not exist, so use `.withFailMessage { … }`.
  - Prefer `requireNotNull(…)` to chains of `!!`.
- New stores extend `ClientSideCacheSpec` / `DistributedCacheSpec`. `Cache` implementations extend `CacheSpec`. Orchestration and channels extend `DefaultCoherentCacheSpec`, `MultipleInstanceSyncSpec`, and `CacheEvictedEventBusSpec`.
  - Stores that rebuild `ttlAt` from Redis expiry drift by ±1 s. Override the `open` TTL tests with `isCloseTo(…, Offset.offset(1))`.
  - Codec specs live in `cocache-spring-redis` test sources (`CodecExecutorSpec`), not in `cocache-test`.
- Redis tests use `RedisTestSupport`. Its listener container runs on a `SyncTaskExecutor`, so `onReset` completes inside `register()`. Without it, late resets make L2 assertions flaky.
- Races: orchestrate them with latches, never sleeps. Wait on a `finished` latch, so a dead background thread fails the test instead of passing it. Assert cross-instance effects by polling with a timeout.
- Every fixed defect gets a reproducing test.
- Local runs: if the shell exports `SPRING_DATA_REDIS_CLUSTER_NODES` or similar, starter tests bind to that cluster. Unset them to use localhost.

## Code style

- Detekt: `config/detekt/detekt.yml`. `MaxLineLength` is 300, but `ArgumentListWrapping` keeps detekt's default of 120, so wrap long multi-argument calls such as `log.warn(e) { … }`.
- Per-source-set `detektMain`/`detektTest` are not part of `check`, and they resolve a different config. Don't treat their output as the gate, but don't add new violations either.
- Kotlin: `-Xjsr305=strict` and `-jvm-default=enable`. Interface methods with bodies compile to default methods, so adding one is binary-compatible.
- Every source file and build script carries the Apache-2.0 header (`checkLicenseHeader`). Comments and KDoc are written in Chinese.

## Boundaries

- ✅ Run `./gradlew check` before you commit, and keep the coverage gate green.
- ✅ Extend the TCK specs for new cache, codec, or channel implementations.
- ✅ Update `docs/architecture.md` and the wiki (en + zh) in the same PR as a behavior change.
- ⚠️ Ask first: new dependencies, or changes to `cocache-api` interfaces or wire formats.
- 🚫 Never use AssertJ `assertThat()` in Kotlin tests.
- 🚫 Never push to `main`. Changes land through squash-merged PRs.
