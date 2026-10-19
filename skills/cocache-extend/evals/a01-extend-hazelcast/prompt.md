---
name: a01-extend-hazelcast
tags: [activation, trigger, backend, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Implement a CoCache CoherenceBackend on Hazelcast (IMap entry processors for the store, a reliable topic for invalidations) and wire the cocache-test TCK.
