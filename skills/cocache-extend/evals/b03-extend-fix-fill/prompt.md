---
name: b03-extend-fix-fill
tags: [behavior, diagnose, tck]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

EngineSpec fails "invalidation during a load is never overwritten" for our backend. Our `fill` implementation:

```kotlin
override fun fill(fill: Fill): Boolean {
    kv.set(fill.key, encode(fill.envelope), fill.ttl)
    return true
}
```

Explain the failure and fix it without touching the test.
