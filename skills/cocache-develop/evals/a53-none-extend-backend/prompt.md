---
name: a53-none-extend-backend
tags: [activation, negative, routed, extend, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Implement a CoCache CoherenceBackend on top of Hazelcast IMap and topics, and make it pass the cocache-test TCK.
