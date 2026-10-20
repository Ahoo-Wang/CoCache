# AGENTS.md — CoCache

CoCache is a two-level coherent cache for Kotlin/Java on Spring Boot 4. Each instance keeps a bounded Caffeine copy (L2) in front of shared Redis (L1) in front of a `CacheLoader`. L1 serializes every write per key: fills are lease-fenced, and `put` / `invalidate` write and broadcast the invalidation over Redis Pub/Sub in one Lua script (one round trip; in `sharded` channel mode, or when the atomic-broadcast probe fails, the broadcast is a separate command, so two round trips). Artifacts target JDK 17 and ship to Maven Central as `me.ahoo.cocache:*`.

The product trades staleness for latency. Every rule below protects one of three goals:

1. **Staleness is bounded.** Races inside the protocol leave nothing stale. Anything outside it heals within `ttl` in L1 and `localMaxAge` in L2.
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
| Guidance for agents *using* CoCache in other projects | `skills/` (`skills/README.md`; validate with `python3 -S scripts/validate_cocache_skills.py`) |
| How to build, test, and style changes *to* CoCache | this file |

## Commands

Integration tests and benchmarks need Redis on `localhost:6379`:

```bash
docker run -d --name cocache-redis -p 6379:6379 redis:7-alpine
```

| Purpose | Command |
|---------|---------|
| Full gate (same as CI): tests, detekt, license headers, JMH compile, coverage ≥ 95% lines / ≥ 90% branches (Dokka runs when the javadoc jar is built for publishing) | `./gradlew check` |
| Compile only | `./gradlew build -x test` |
| One module | `./gradlew :cocache-core:test` |
| One class or method | `./gradlew :cocache-core:test --tests "me.ahoo.cocache.core.engine.CoherentEngineTest"` |
| Lint (**rewrites files**: `autoCorrect = true`) | `./gradlew detekt` |
| Runtime compatibility on a newer JDK | `./gradlew test -PtestJavaVersion=25` |
| Redis modules on a local Redis Cluster (needs `redis-server` 7+) | `.github/scripts/start-redis-cluster.sh`, then `SPRING_DATA_REDIS_CLUSTER_NODES=127.0.0.1:7000,127.0.0.1:7001,127.0.0.1:7002 ./gradlew :cocache-redis:test :cocache-spring-boot-starter:test` |
| Redis modules on Valkey (CI job `Test (Valkey)`; the port avoids the Redis container) | `docker run -d --name cocache-valkey -p 6380:6379 valkey/valkey:8-alpine`, then `SPRING_DATA_REDIS_PORT=6380 ./gradlew :cocache-redis:test --rerun :cocache-spring-boot-starter:test --rerun` |
| Hit-path benchmark (`CoCacheBenchmark`; never run by `check`; `--rerun` because Gradle otherwise reuses the last result; `-PjmhIncludes` is a regex, so `l2Hit` also runs `l2HitViaInterface` and `l2HitMixed`) | `./gradlew :cocache-redis:jmh --rerun -PjmhThreads=8 -PjmhIncludes=l2Hit` |
| Wiki build, the only dead-link check | `cd wiki && pnpm install && pnpm build` |

If your shell exports `SPRING_DATA_REDIS_*` variables (for example `SPRING_DATA_REDIS_CLUSTER_NODES`), the `cocache-redis` and starter tests bind to that Redis instead of localhost. Unset them first.

## Where code goes

Dependencies point one way: `api ← core ← redis`, `api ← codec-jackson`, `core ← spring`, and `core ← coroutines`. `kotlinx-coroutines-core` appears only in `cocache-coroutines`. The starter depends on `spring`, `redis`, and `codec-jackson`. Only the starter may use SpEL or annotation scanning.

- `cocache-api` holds the user API (`Cache`, `SetCache`, `CacheLoader`, `Codec`, `Lookup`, `@CoCache`) and every SPI (`me.ahoo.cocache.api.spi`), and has zero runtime dependencies. Changing it is a public-API change.
- `cocache-core` holds the runtime and engines (`CacheRuntime`, `CacheSpec`, `engine/CoherentEngine`, `engine/SetEngine`), the L2 fence and coalescing (`internal/InvalidationStamps`, `internal/SingleFlight`), policy (`policy/CachePolicy`), the interface proxy (`proxy/CacheProxies`), and test helpers that ship (`InMemoryBackend`, `ManualClock`). L2 is always Caffeine; it is not an SPI.
- `cocache-redis` is the Lettuce-native backend. Its Lua scripts and wire format live in `RedisScripts`.
- Stores only run the protocol. TTL, jitter, negative caching, refresh windows, and tokens come from the engine as arguments. A store sees a relative `Duration?` (`null` = never expires), never a policy.
- `cocache-test` is a **published** TCK. Anything added under `src/main` becomes API for third-party implementations.
- `cocache-example` is the example app, with its own smoke test.

## Invariants that "simple" refactors break

Read `docs/architecture.md` before you touch coherence, storage, codec, proxy, or Spring wiring code. If behavior changes, update it in the same PR.

- **Fence every fill.** A fill must hold a token taken *before* its read: an L1 lease or refresh token, or an L2 invalidation stamp. Writers (`invalidate`, `put`, set-element writes) bump the stamp and evict L2 both *before and after* they change L1. L2 is filled only by paths that read L1. Writers never write L2.
- **An L1 miss is a miss.** An absent key or a lease placeholder is not a negative entry. Negative entries exist only as state `M`.
- **The read path never deletes unconditionally.** A corrupt payload is reclaimed only by digest compare (`reclaim`). An unknown newer format is neither reclaimed nor written back.
- **Release every token.** When the loader, `ttlOf`, or the codec throws, or the load is rejected, `release` the lease. Set an explicit expiry on every L1 state change.
- **`SingleFlight` deregisters a call before it publishes the result.** Read-your-writes relies on that order. Same-thread reentry on a key fails fast, and a batch finishes its own keys before it waits on others.
- **The loader runs on the caller's thread.** Futures wrap I/O only. Nothing runs on a Lettuce event-loop thread.
- **Only the runtime creates stateful components.** Users pass specs and stateless functions, never an L2, store, or connection.
- **L1 connections reach primaries only and reject commands while disconnected.** Buffered replay could apply a `put` that already failed for the caller.
- **The proxy never wraps exceptions.** Callers always see the original exception.
- **Set-element writes are only for `SetCache`**, where L1 is the only copy and there is no loader. Sets owned by a data source are a plain `Cache<K, Set<E>>` maintained with `invalidate`.
- **The wire format is frozen within a major version.** This covers the key layout, the scalar header, the set fields, the invalidation frame, and channel names.
- **Hit-path changes need numbers.** Compare JMH results against the previous version at 1 and 8 threads, and put them in the PR. Run at least five alternating rounds per side with `--rerun`, report the median and confidence interval, and include `l2HitMixed` (three differently configured caches). 8-thread results vary by about ±4% between runs on one machine.

## Tests

- Use JUnit 6, MockK, and fluent-assert (`import me.ahoo.test.asserts.assert`, then `x.assert()…`).
  - `assert()` accepts nullable receivers. There is no `isTrue {}`, so use `.withFailMessage { … }`.
  - Prefer `requireNotNull(…)` to chains of `!!`.
  - Don't use AssertJ `assertThat()`. Two exceptions: `assertThat(context)` on Spring's `ApplicationContextRunner` in starter tests, and `Offset.offset(n)` as an argument.
- New implementations must pass the TCK in `cocache-test`:

  | You implement | Extend |
  |---------------|--------|
  | `CoherenceBackend` (L1 protocol and invalidation channel) | `BackendProtocolSpec` |
  | The backend under real engines (two runtimes sharing one backend) | `EngineSpec` and `SetEngineSpec` |
  | Cross-node interleavings and cluster-wide counts (three runtimes); behavior under L1 faults | `MultiNodeSpec` and `FaultSpec` (override `disconnect()` if the backend has connections) |

  `HookedBackend` wraps any backend to inject failures and orchestrate races. Expiry is millisecond-precise, so TTL assertions need no drift offset.
- Redis tests use `RedisTestSupport`. It picks standalone (`SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT`, default `localhost:6379`) or Cluster from `SPRING_DATA_REDIS_CLUSTER_NODES`, simulates time with `shiftTtl`, and kills this test's Pub/Sub connection with `killPubSubConnections` to force a reset. `subscribe` returns only after the subscription is acknowledged and `onReset` has run, so a late reset can't clear L2 mid-test.
- Concurrency tests:
  - Orchestrate races with latches, never sleeps.
  - Wait on a `finished` latch, so a dead background thread fails the test instead of passing silently.
  - Assert cross-instance effects by polling with a timeout.
- Every fixed defect gets a test that reproduces it. The same-node race cases in `EngineSpec` are the model.
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
