---
name: b04-debug-stale-everywhere
tags: [behavior, diagnose, read-only]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

生产上所有实例都返回同一个旧的商品描述，已经持续了半小时（ProductCache 的 ttl 是 1 小时）。应用只在 ProductService.update 里写库后调用 productCache.invalidate。另外有一个运营后台（另一个服务）会直接 UPDATE product 表。请只读地定位原因，给出修复方案，先不要改代码或执行任何写操作。
