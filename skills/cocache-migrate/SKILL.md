---
name: "cocache-migrate"
description: "Upgrade downstream Java/Kotlin applications from CoCache 5.x (me.ahoo.cache packages) to 6.x: imports, annotations, CacheSource to CacheLoader, removed properties, metrics and actuator, the Redis wire-format and channel change, switch-over without rolling upgrade, SetElementsCache data copy, external invalidators; also reviews and failures of that upgrade. Not the CoCache repository, new 6.x usage (cocache-develop) or other cache libraries."
---

# Migrate CoCache 5.x to 6.x

## Scope gate

Use only for a downstream application that uses or used CoCache 5.x: `me.ahoo.cache.*` imports, `me.ahoo.cocache:*` dependencies at a 5.x version, or 5.x configuration (`cocache.redis.strict-failure`, `cocache.load.*`, `{name}.CacheSource` beans) that a 6.x upgrade must replace. Reviews of such an upgrade, startup or runtime failures caused by it, and its Redis data and cutover planning qualify. The CoCache repository itself, including its release work, never qualifies.

Route away and stop: new or ongoing 6.x usage with nothing left from 5.x belongs to `cocache-develop`; implementing a `CoherenceBackend` belongs to `cocache-extend`. Upgrades of other cache libraries, generic Spring Boot 3 to 4 upgrades without CoCache, and generic Redis data migrations do not qualify. Otherwise state that this Skill does not apply and stop using it.

Own the whole migration: code, configuration, dashboards, external writers, Redis data and switch-over are one change, not a dependency bump.

## Contract

- Pin the exact source version (from the resolved build) and the exact 6.x target release before editing. "6.x" alone is not a target.
- The target release's sources, tests, migration guide (https://cocache.ahoo.me/guide/migration-6) and changelog are the authority. The references here are a map to verify, not a substitute.
- Separate authorizations: code and local writes; non-production Redis writes; production Redis writes (copying sets, deleting old keys); production switch-over. One never implies another.
- There is no rolling upgrade. 5.x and 6.x instances must never share the same keys and channels, because neither sees the other's invalidations and they overwrite each other's values.
- Mark everything you cannot prove as `MISSING EVIDENCE`. Never claim completion from compilation, a green unit suite or a startup alone.

## Workflow

Run only the stages the request and its authorization cover. Audit, planning and review stay read-only.

```
- [ ] 1 Baseline: versions, caches, custom components, writers, configuration, dashboards
- [ ] 2 Target: pin the 6.x release; confirm platform prerequisites
- [ ] 3 Matrix: copy assets/migration-matrix.md, one row per item
- [ ] 4 Adapt code and configuration (needs code-write authorization)
- [ ] 5 Prove locally: compile, tests, isolated startup on Redis 7+, key inspection
- [ ] 6 Plan Redis data: leftover keys, SetElementsCache sets, external writers
- [ ] 7 Switch over: stop-all or side-by-side on separate keys (needs production authorization)
- [ ] 8 Observe and keep a rollback path
```

1. **Baseline.** Resolve the CoCache, Spring Boot, JDK, Jackson and Redis client versions from the build. Inventory every cache (interface, `JoinCache`, `SetElementsCache`), every bean-name convention (`{name}.CacheSource`, `{name}.KeyConverter`, `{name}.ClientSideCache`, `{name}.DistributedCache`, `{name}.JoinKeyExtractor`), Spring Cache usage, `cocache.*` properties, actuator exposure (`cocacheClient`), dashboards and alerts on `cocache.*` meters, and every process that reads CoCache keys or publishes `key@@publisherId` messages. Run `scripts/audit-5x-usage.sh` with the application root (run it with `--help` first); it lists leads, not proof.
2. **Target.** 6.x needs JDK 17+, Spring Boot 4, Jackson 3 for the default codec, Lettuce (not Jedis) for CoCache's own client, and Redis 7.0+ in standalone, Sentinel or Cluster topology. A platform upgrade the application still needs is its own prerequisite step.
3. **Matrix.** Fill `assets/migration-matrix.md` with evidence only.
4. **Adapt**, in dependency order: build and modules, imports, annotations, loaders, API calls, bean conventions to customizers or properties, JoinCache, Spring Cache (`sync = true`), properties, tests. Use `references/api-mapping.md` and `references/configuration-mapping.md`.
5. **Prove locally.** Compile; run the tests (`cocache.backend=memory` or an `InMemoryBackend` runtime for coherence tests); start the application once against an isolated Redis 7+ so removed properties and wiring fail fast; inspect one key with `GET /actuator/cocache/{name}/{key}`. Keep isolation-only settings out of the committed diff.
6. **Plan Redis data** with `references/cutover.md`: what happens to 5.x values, how each `SetElementsCache` set is copied, how external writers switch to the 6.x protocol, and when old keys are deleted.
7. **Switch over** with the chosen strategy from `references/cutover.md`.
8. **Observe** `cocache.reclaims`, `cocache.unknown.formats`, `cocache.load.*`, health and dropped invalidations; keep the rollback path the plan defined.

## Load by scope

| Scope | Load |
|---|---|
| Modules, imports, annotations, `CacheSource`, API calls, bean conventions, JoinCache, Spring Cache, tests | `references/api-mapping.md` |
| Properties, actuator, metrics and alerts, behavior changes callers notice | `references/configuration-mapping.md` |
| Redis wire format, leftover keys, `SetElementsCache` copy, external writers, switch-over and rollback | `references/cutover.md` |
| Static discovery of 5.x usage | `scripts/audit-5x-usage.sh` with the explicit application root, not a monorepo default |
| Migration plan or status report | Copy `assets/migration-matrix.md` and fill only evidence-backed fields |

## Hard safety gates

- Do not run 5.x and 6.x writers against the same keys and channels.
- Do not delete Redis keys, copy sets or publish invalidations without the exact environment, key pattern and authorization. In Redis Cluster, scan per master.
- Never switch a cache whose only copy is Redis (no `CacheSource`, or a `SetElementsCache`) without migrating its data first with `CoCache5Migrator` (6.5+): before 6.5 a 6.x read deletes or ignores the old data, and `add` replaces old sets.
- Do not leave external readers or writers of CoCache keys on the 5.x protocol.
- Do not translate `clientSideTtl = 0` into an unbounded L2; 6.x `localMaxAge` is always finite.

## Completion

Report the pinned source and target versions; changed files grouped by matrix row; test, startup and inspection evidence with exact commands; the Redis data plan and what was executed under which authorization; the switch-over strategy and rollback path; and every `MISSING EVIDENCE` item. State separately whether the code migration is complete, local runtime passed, data steps are done, and production switch-over is proved; none implies the next.
