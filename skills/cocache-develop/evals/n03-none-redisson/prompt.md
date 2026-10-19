---
name: n03-none-redisson
tags: [activation, negative, neighbour, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Replace our hand-rolled near cache with Redisson's RLocalCachedMap and configure invalidation on update.
