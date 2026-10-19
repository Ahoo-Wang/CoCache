---
name: b02-develop-spring-cache-sync
tags: [behavior, spring-cache]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

我们的服务用 CoCache 6 承接 Spring Cache。下面的方法偶尔在用户改名后仍返回旧名字，直到一小时后才恢复。解释原因并修复，同时防止以后有人再写错。

`UserQueries.kt`:

```kotlin
@Cacheable(cacheNames = ["UserCache"], key = "#id")
fun getUser(id: String): User? = repo.findById(id)

@Transactional
@CacheEvict(cacheNames = ["UserCache"], key = "#user.id")
fun save(user: User): User = repo.save(user)
```
