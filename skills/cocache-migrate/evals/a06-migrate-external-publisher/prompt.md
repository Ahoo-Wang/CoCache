---
name: a06-migrate-external-publisher
tags: [activation, trigger, external, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

A Go service evicts our CoCache 5 caches by publishing `key@@publisherId` on the cache-name channel. What must change when the Java apps move to CoCache 6?
