---
name: a01-develop-declare-cache
tags: [activation, trigger, declare]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

在这个使用 me.ahoo.cocache 的订单服务里新增一个 OrderCache（key 是订单号，value 是 OrderView），TTL 10 分钟，并提供从 OrderRepository 加载的 CacheLoader。
