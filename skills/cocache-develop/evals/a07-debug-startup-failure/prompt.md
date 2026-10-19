---
name: a07-debug-startup-failure
tags: [activation, trigger, diagnose, startup]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

升级依赖后应用启动失败："Multiple CacheLoader beans match cache UserCache"。我们用的是 cocache-spring-boot-starter 6.0.0，帮我定位并修复。
