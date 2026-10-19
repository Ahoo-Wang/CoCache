---
name: a01-migrate-upgrade
tags: [activation, trigger, upgrade]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

把订单服务从 CoCache 5.4.0 升级到 6.0.0，代码里还在用 me.ahoo.cache.api.Cache 和 CacheSource。
