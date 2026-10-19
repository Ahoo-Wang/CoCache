# CoCache 5.x to 6.x Migration Matrix

## Baseline and target

| Item | Current evidence | Pinned target evidence | Status |
|---|---|---|---|
| CoCache version (resolved) | | | |
| Spring Boot / JDK / Jackson | | | |
| CoCache Redis client (Jedis or Lettuce) | | | |
| Redis version and topology | | | |
| Instances and deployment strategy | | | |

## Caches

| Cache | 5.x shape and prefix | 6.x shape, namespace and storage key | Loader / codec | Data copy needed | Status |
|---|---|---|---|---|---|
| | | | | | |

## Code and configuration items

| Scope | 5.x usage (file:line) | 6.x replacement | Verification | Status |
|---|---|---|---|---|
| Build and modules | | | | |
| Imports and annotations | | | | |
| `CacheSource` and bean conventions | | | | |
| API calls | | | | |
| JoinCache | | | | |
| Spring Cache (`sync = true`) | | | | |
| Properties | | | | |
| Actuator exposure | | | | |
| Dashboards and alerts | | | | |
| Tests | | | | |

## Redis data and external programs

| Item | Plan | Authorization | Rehearsal evidence | Status |
|---|---|---|---|---|
| Leftover 5.x keys | | | | |
| `SetElementsCache` sets | | | | |
| External readers of CoCache keys | | | | |
| External invalidators (`key@@id`) | | | | |

## Switch-over

| Gate | Evidence | Result |
|---|---|---|
| Strategy (stop-all or separate keys) | | |
| All 5.x writers stopped or isolated | | |
| Isolated 6.x startup and key inspection | | |
| Set copy reconciled | | |
| Rollback trigger, steps and owner | | |
| Post-switch metrics observed | | |

Use `MISSING EVIDENCE` for any field that cannot be proved. Do not replace it with an assumption.
