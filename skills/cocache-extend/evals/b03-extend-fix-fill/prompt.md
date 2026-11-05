---
name: b03-extend-fix-fill
tags: [behavior, diagnose, tck]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

EngineSpec fails `remoteInvalidationDuringLoadRejectsTheStaleFill` for our backend: an invalidation during a load gets overwritten. Our `fill` implementation:

```kotlin
override suspend fun fill(fill: Fill): L1Fill {
    kv.set(fill.key, encode(fill.envelope), fill.ttl)
    return L1Fill.Filled(advanced = false)
}
```

Explain the failure and fix it without touching the test.
