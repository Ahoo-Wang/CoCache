---
name: b02-extend-tck-time
tags: [behavior, tck]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

我们的 CoCache 后端跑在真实的远程服务器上，TTL 由服务器管理。EngineSpec 要求实现 elapse(duration) 和 simulateReconnect()，应该怎么写？
