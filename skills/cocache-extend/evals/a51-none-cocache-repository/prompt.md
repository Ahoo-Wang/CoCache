---
name: a51-none-cocache-repository
tags: [activation, negative, framework-repository]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

在 CoCache 仓库里修改 cocache-redis 的 RedisBackend，让 sharded 模式在 slot 迁移后重新订阅，并补测试。
