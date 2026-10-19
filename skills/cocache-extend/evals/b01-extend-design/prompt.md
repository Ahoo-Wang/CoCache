---
name: b01-extend-design
tags: [behavior, backend, design]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

We want a CoCache 6.0.0 CoherenceBackend on a key-value store that supports per-key compare-and-set and TTLs, but whose pub/sub cannot be part of a write transaction and may drop messages during reconnects. Outline the design decisions and how you will prove the backend correct.
