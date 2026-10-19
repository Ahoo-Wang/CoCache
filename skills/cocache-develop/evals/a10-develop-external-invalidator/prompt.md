---
name: a10-develop-external-invalidator
tags: [activation, trigger, write-path, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

A nightly batch job updates product rows directly with SQL, and our CoCache ProductCache serves stale data afterwards. Make the job invalidate the cache properly.
