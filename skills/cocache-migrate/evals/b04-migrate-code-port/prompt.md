---
name: b04-migrate-code-port
tags: [behavior, code]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Port this CoCache 5.x code to CoCache 6.0.0 and explain each change.

```kotlin
@Bean("SessionCache.CacheSource")
fun sessionSource(store: SessionStore) = CacheSource<String, Session> { id ->
    store.find(id)?.let { CacheValue.of(it, TtlAt.at(it.expiresAtEpochSecond)) }
}

fun logout(id: String) {
    sessionCache.evict(id)
    store.delete(id)
}

fun peek(id: String): Long? = sessionCache.getTtlAt(id)
```
