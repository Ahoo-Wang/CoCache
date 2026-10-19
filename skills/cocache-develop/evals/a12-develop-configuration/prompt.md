---
name: a12-develop-configuration
tags: [activation, trigger, configuration, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Tune our CoCache caches: UserCache should keep values forever because every writer invalidates, PriceCache must expire within 30 seconds, and local copies should never be older than 2 minutes.
