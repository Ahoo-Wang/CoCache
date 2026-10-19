---
name: a52-none-extend-backend
tags: [activation, negative, routed, extend, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Our CoherenceBackend on Hazelcast fails EngineSpec's invalidation-during-load case on CoCache 6.0.0. Fix the backend.
