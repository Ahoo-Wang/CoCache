---
name: b01-develop-write-path
tags: [behavior, write-path, transactions]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

This Spring Boot service uses CoCache 6.0.0 (cocache-spring-boot-starter). Fix the write path so caches never keep the old name, and say how you would test it.

Files from the user's project, shown inline:

`src/main/kotlin/demo/UserCache.kt`:

```kotlin
@CoCache(ttl = 3600)
interface UserCache : Cache<String, User>
```

`src/main/kotlin/demo/UserService.kt`:

```kotlin
@Service
class UserService(private val repo: UserRepository, private val userCache: UserCache) {
    fun find(id: String): User? = userCache[id]

    @Transactional
    fun rename(id: String, name: String) {
        userCache.invalidate(id)
        val user = repo.findById(id) ?: return
        repo.save(user.copy(name = name))
        userCache.put(id, user.copy(name = name))
    }
}
```
