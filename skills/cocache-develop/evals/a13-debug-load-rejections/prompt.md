---
name: a13-debug-load-rejections
tags: [activation, trigger, diagnose]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Redis 抖动的时候我们的应用开始大量抛 SourceLoadRejectedException，数据库也被打满，CoCache 的配置该怎么看、怎么调？
