---
name: a04-develop-join-cache
tags: [activation, trigger, join-cache, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Add a CoCache JoinCache that returns each order together with its customer from our existing OrderCache and CustomerCache (customerId is a Long).
