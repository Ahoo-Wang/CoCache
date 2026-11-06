---
name: a03-develop-spring-cache-sync
tags: [activation, trigger, spring-cache]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

这个服务用 CoCache 的 CoCacheSpringCacheManager 承接 @Cacheable，启动日志里出现了 "@Cacheable on com.example.user.UserService#findUser uses CoCache cache [UserCache] without sync = true: Spring then loads and puts unconditionally, which is not lease-protected (a concurrent invalidation can be overwritten by a stale value). Use @Cacheable(sync = true)." 的 WARN，帮我改好。
