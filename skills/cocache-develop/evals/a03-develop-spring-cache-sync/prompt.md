---
name: a03-develop-spring-cache-sync
tags: [activation, trigger, spring-cache]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

这个服务用 CoCache 的 CoCacheSpringCacheManager 承接 @Cacheable，启动日志里出现了 "@Cacheable without sync = true on CoCache caches" 的 WARN，帮我改好。
