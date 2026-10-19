---
name: "cocache-extend"
description: "Implement or fix a CoCache CoherenceBackend (RemoteStore plus InvalidationChannel) outside the CoCache repository: a new L1 store, a different invalidation transport, or a decorating backend, verified with the cocache-test TCK (BackendProtocolSpec, EngineSpec, SetEngineSpec, HookedBackend). Not the CoCache repository, application caches, codecs or Redis tuning (cocache-develop), or 5.x store ports before the migration plan (cocache-migrate)."
---

# Implement a CoCache Backend

## Scope gate

Use only outside the CoCache repository, for code that implements `me.ahoo.cocache.api.spi.CoherenceBackend`, `RemoteStore` or `InvalidationChannel`: a backend on another store, a different invalidation transport, a wrapper or decorator around an existing backend, or a failing TCK run of such code. Changing CoCache's own `cocache-redis` or in-memory backend is CoCache repository work and never qualifies.

Route away and stop: declaring caches, writing a `Codec`, `KeyEncoder`, `KeyFilter` or `TtlFunction` for an application, configuring or wiring the shipped `RedisBackend` (including `RedisBackend.of(client, options)`), and testing application code with `HookedBackend` belong to `cocache-develop`. Porting a 5.x custom store or event bus belongs to `cocache-migrate` until the migration plan hands this backend over. A Spring `CacheManager` or cache provider unrelated to CoCache does not qualify. Otherwise state that this Skill does not apply and stop using it.

## Contract

- The SPI in `me.ahoo.cocache.api.spi` of the pinned CoCache version and the published `cocache-test` suites are the contract. Read the SPI sources, KDoc and the in-memory backend of that exact version before writing code; `references/backend-contract.md` is a map of what to verify, not the definition.
- Completion means `BackendProtocolSpec`, `EngineSpec` and `SetEngineSpec` pass for every mode the backend offers (for example atomic and non-atomic broadcast). Never weaken, skip or override a TCK test to make it pass; override only the documented timing hooks.
- The backend holds no policy. Every TTL, window and token arrives as an argument; jitter, negative lifetimes and refresh decisions belong to the engine.
- Declare only capabilities you guarantee: `atomicBroadcast = true` only if every write publishes its frame in the same atomic step.
- Keep read-only requests (review, diagnosis) read-only; report exact TCK commands and results, and what the TCK cannot prove (failover, durability, real network partitions).

## Workflow

```
- [ ] 1 Pin the CoCache version; read the SPI, the in-memory backend and the TCK sources of that version
- [ ] 2 Map the store's primitives to the L1 state machine (value, negative entry, lease, refresh token, set)
- [ ] 3 Decide atomicBroadcast and the reset signal of the channel
- [ ] 4 Wire the three TCK suites first, with elapse() and simulateReconnect() (red)
- [ ] 5 Implement RemoteStore method by method against BackendProtocolSpec
- [ ] 6 Implement InvalidationChannel; pass EngineSpec and SetEngineSpec
- [ ] 7 Run the suites per mode; review against the invariants; document limits
```

1. **Pin and read.** Resolve the CoCache version from the build. Read `CoherenceBackend`, `RemoteStore`, `InvalidationChannel`, `InvalidationFrame` and the envelope and token types, then the in-memory backend as the reference implementation.
2. **Map the state machine.** Each key is absent, a value, a negative entry, a lease, a value carrying a refresh token, or a set. Every transition must be atomic per key and reset the expiry. If the store cannot do a per-key compare-and-set, it cannot implement the lease fence; stop and say so.
3. **Channel.** Choose a transport that can tell when messages may have been lost; that moment must call `onReset()`. Without resets, a lost message leaves L2 stale until `local-max-age`.
4. **TCK first.** Extend the suites as in `references/tck.md`. Implement `elapse` so the store and the engine clock move together.
5. **Store.** Follow `references/backend-contract.md` method by method; make `BackendProtocolSpec` pass before engine suites.
6. **Channel and engine.** Pass `EngineSpec` (two runtimes on your backend) and `SetEngineSpec`.
7. **Verify and document.** Run every suite in every supported mode. Review the diff against the invariants in `references/backend-contract.md`. Document the store's durability, failover behavior and any capability the backend does not offer.

## Load by scope

| Scope | Load |
|---|---|
| SPI shapes, per-method obligations, invariants, channel semantics, failure behavior | `references/backend-contract.md` |
| Wiring and running `BackendProtocolSpec`, `EngineSpec`, `SetEngineSpec`, `elapse`, `simulateReconnect`, `HookedBackend`, diagnosing a failing TCK case | `references/tck.md` |

## Diagnosing a failing TCK case

Read the failing test in the pinned `cocache-test` sources, name the protocol obligation it checks, and write one falsifiable hypothesis about which method breaks it (for example "`fill` writes without comparing the token, so an invalidation during a load is overwritten"). Fix the backend, never the test. Rerun the single case, then the whole suite in every mode.

## Completion

Report: pinned CoCache version; the store primitives used for each state transition; the `atomicBroadcast` decision and the reset signal; exact TCK commands and per-suite, per-mode results; documented limits (durability, failover, unsupported modes); and anything only an environment test can prove. Extending docs: https://cocache.ahoo.me/architecture/extending.
