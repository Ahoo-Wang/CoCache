---
name: a02-extend-tck-failure
tags: [activation, trigger, diagnose, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Our custom CoCache backend passes BackendProtocolSpec but EngineSpec fails on the invalidation-during-load case. Find the bug in our RemoteStore.
