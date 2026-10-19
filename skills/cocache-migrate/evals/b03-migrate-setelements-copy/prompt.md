---
name: b03-migrate-setelements-copy
tags: [behavior, data]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

CoCache 5 的 OnlineUsersCache 是 SetElementsCache，Redis 是这些集合的唯一副本。升级到 CoCache 6 的 SetCache 时数据怎么办？给出具体步骤和需要的授权。
