---
name: a03-migrate-setelements
tags: [activation, trigger, data, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

We use CoCache 5 SetElementsCache for online sessions. What happens to that Redis data when we move to CoCache 6 SetCache, and how do we carry it over?
