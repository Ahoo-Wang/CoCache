---
name: a50-none-cocache-repository
tags: [activation, negative, framework-repository]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

In the CoCache repository, fix RedisBackend so sharded mode resubscribes after a slot migration, and add a test in cocache-redis.
