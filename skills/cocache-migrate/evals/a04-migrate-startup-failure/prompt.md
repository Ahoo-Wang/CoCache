---
name: a04-migrate-startup-failure
tags: [activation, trigger, diagnose]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

依赖升到 CoCache 6.0.0 之后启动失败："CoCache 6.0 removed these properties: [cocache.redis.strict-failure, cocache.load.max-concurrent]"，帮我把 5.x 的配置迁移过来。
