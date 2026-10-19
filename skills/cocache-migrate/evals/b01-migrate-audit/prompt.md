---
name: b01-migrate-audit
tags: [behavior, audit, read-only, synthetic-fixture]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读审计这个使用 CoCache 5.4.0 的服务升级到固定版本 6.0.0 的风险，列出每一项需要的改动和不能从这些文件证明的内容。不要修改文件。

Files from the user's project, shown inline:

`build.gradle.kts`:

```kotlin
dependencies {
    implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.4.0")
    implementation("me.ahoo.cocache:cocache-spring-redis:5.4.0")
    implementation("redis.clients:jedis")
}
```

`src/main/resources/application.yml`:

```yaml
cocache:
  redis:
    strict-failure: true
    eviction-channel: sharded
  load:
    max-concurrent: 32
management.endpoints.web.exposure.include: health,cocache,cocacheClient
```

`src/main/kotlin/demo/Caches.kt`:

```kotlin
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.api.source.CacheSource

@CoCache(ttlAmplitude = 30, clientSideTtl = 0, keyPrefix = "user:")
interface UserCache : Cache<String, User>

@CoCache
interface OnlineCache : SetElementsCache<String, String>

@Bean("UserCache.CacheSource")
fun userSource(repo: UserRepository) = CacheSource<String, User> { id -> repo.find(id)?.let { CacheValue.of(it, TtlAt.at(120)) } }
```
