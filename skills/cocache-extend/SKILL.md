---
name: "cocache-extend"
description: "Implement or fix a CoCache CoherenceBackend (L1Store or FutureL1Store, plus InvalidationChannel) outside the CoCache repository: a new L1 store, a different invalidation transport, or a decorating backend, verified with every cocache-test TCK suite (BackendProtocolSpec, EngineSpec, MultiNodeSpec, FaultSpec, SetEngineSpec, VersionedSpec). Not the CoCache repository, application caches, codecs or Redis tuning (cocache-develop)."
---

# Implement a CoCache Backend

## Scope gate

Use only outside the CoCache repository, for code that implements `me.ahoo.cocache.api.spi.CoherenceBackend`, `L1Store`, `FutureL1Store` or `InvalidationChannel`: a backend on another store, a different invalidation transport, a wrapper or decorator around an existing backend, or a failing TCK run of such code. Changing CoCache's own `cocache-redis` or in-memory backend is CoCache repository work and never qualifies.

Route away and stop: declaring caches, writing a `Codec`, `KeyEncoder`, `KeyFilter` or `TtlFunction` for an application, configuring or wiring the shipped `RedisBackend` (including `RedisBackend.of(client, options)`), and testing application code with `HookedBackend` belong to `cocache-develop`. A Spring `CacheManager` or cache provider unrelated to CoCache does not qualify. Otherwise state that this Skill does not apply and stop using it.

## Contract

- The SPI in `me.ahoo.cocache.api.spi` of the pinned CoCache version and the published `cocache-test` suites are the contract. Read the SPI sources, KDoc and the in-memory backend of that exact version before writing code; `references/backend-contract.md` is a map of what to verify, not the definition.
- Completion means every suite the pinned `cocache-test` ships (`BackendProtocolSpec`, `EngineSpec`, `SetEngineSpec`, `MultiNodeSpec`, `FaultSpec`, `VersionedSpec`) passes for every mode the backend offers (for example atomic and non-atomic broadcast). Never weaken, skip or override a TCK test to make it pass; implement the abstract members and override only the optional hooks the suites expose (`shortTtl`, `pause()`, `disconnect()`, `commandCancellation()`, `closesBackend`, `access`, `configureNode`, `policy()`).
- `expectedCapabilities` declares exactly the capability surfaces the store returns; the TCK fails closed on any difference. Never leave a capability out to skip its failing cases: return `null` from that accessor instead, and say the capability is unsupported.
- The backend holds no policy. Every TTL, window, time limit and token arrives as an argument; jitter, negative lifetimes and refresh decisions belong to the engine.
- Declare only what you guarantee: `atomicBroadcast = true` only if every write publishes its frame in the same atomic step, and a capability surface only if its compare-and-write is atomic.
- Failure rules: bound every call, release tokens after a failure with an unknown outcome, and never report a failure as `CancellationException` (a command the client cancelled is a failure).
- Keep read-only requests (review, diagnosis) read-only; report exact TCK commands and results, and what the TCK cannot prove (failover, durability, real network partitions).

## Workflow

```
- [ ] 1 Pin the CoCache version; read the SPI, the in-memory backend and the TCK sources of that version
- [ ] 2 Map the store's primitives to the L1 state machine (value, negative entry, lease, refresh token, set)
- [ ] 3 Decide atomicBroadcast, the capabilities and the reset signal of the channel
- [ ] 4 Wire every TCK suite of that version first, with expectedCapabilities, elapse() and, for EngineSpec, simulateReconnect() (red)
- [ ] 5 Implement the store (L1Store, or FutureL1Store from Java) method by method against BackendProtocolSpec
- [ ] 6 Implement InvalidationChannel; pass EngineSpec, SetEngineSpec, MultiNodeSpec and FaultSpec
- [ ] 7 Run the suites per mode; review against the invariants; document limits
```

1. **Pin and read.** Resolve the CoCache version from the build. Read `CoherenceBackend`, the store interfaces (`L1Store` and its capability surfaces, `FutureL1Store` for Java, `ForwardingL1Store` for decorators), `InvalidationChannel`, `InvalidationFrame` and the envelope, token and result types, then the in-memory backend as the reference implementation.
2. **Map the state machine.** Each key is absent, a value, a negative entry, a lease, a value carrying a refresh token, or a set. Every transition must be atomic per key and reset the expiry. If the store cannot do a per-key compare-and-set, it cannot implement the lease fence; stop and say so.
3. **Capabilities and channel.** Offer a capability surface (conditional, versioned, sets) only if the store can do its compare-and-write atomically; otherwise its accessor returns `null`. Choose a transport that can tell when messages may have been lost; that moment must call `onReset()`. Without resets, a lost message leaves L2 stale until `local-max-age`.
4. **TCK first.** Extend the suites as in `references/tck.md`, declaring `expectedCapabilities`. Implement `elapse` so the store and the engine clock move together.
5. **Store.** Follow `references/backend-contract.md` method by method, including the failure, time-limit and cancellation rules; make `BackendProtocolSpec` pass before engine suites.
6. **Channel and engine.** Pass `EngineSpec` (two runtimes on your backend), `SetEngineSpec` and `VersionedSpec` for the capabilities you offer, then `MultiNodeSpec` (three runtimes, cluster-wide load and refresh counts) and `FaultSpec` (L1 faults; implement `disconnect()` and `commandCancellation()` if the backend holds connections or its client can cancel commands).
7. **Verify and document.** Run every suite in every supported mode. Review the diff against the invariants in `references/backend-contract.md`. Document the store's durability, failover behavior and any capability the backend does not offer.

## Load by scope

| Scope | Load |
|---|---|
| SPI shapes, capabilities, per-method obligations, failures and cancellation, Java and decorator shapes, invariants, channel semantics | `references/backend-contract.md` |
| Wiring and running the suites, `expectedCapabilities`, `elapse`, `simulateReconnect`, `pause`, `disconnect`, `commandCancellation`, `HookedBackend`, diagnosing a failing TCK case | `references/tck.md` |

## Diagnosing a failing TCK case

Read the failing test in the pinned `cocache-test` sources, name the protocol obligation it checks, and write one falsifiable hypothesis about which method breaks it (for example "`fill` writes without comparing the token, so an invalidation during a load is overwritten"). Fix the backend, never the test. Rerun the single case, then the whole suite in every mode.

## Completion

Report: pinned CoCache version; the store primitives used for each state transition; the `atomicBroadcast` decision, the declared capabilities and the reset signal; exact TCK commands and per-suite, per-mode results; documented limits (durability, failover, unsupported modes); and anything only an environment test can prove. Extending docs: https://cocache.ahoo.me/architecture/extending.
