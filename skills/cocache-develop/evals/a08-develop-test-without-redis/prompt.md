---
name: a08-develop-test-without-redis
tags: [activation, trigger, testing, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Write a @SpringBootTest for our CoCache-backed UserService that proves the second read hits the cache, without needing Redis in CI.
