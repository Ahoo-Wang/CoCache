---
name: a11-develop-set-cache
tags: [activation, trigger, set-cache]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

用 CoCache 的 SetCache 维护每个聊天室当前在线的用户集合，多个实例会同时加入和离开。
