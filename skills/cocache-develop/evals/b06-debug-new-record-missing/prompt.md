---
name: b06-debug-new-record-missing
tags: [behavior, diagnose, negative-cache]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

With CoCache 6 in front of our user table, a user who registers right after someone looked up their id is reported as "not found" for about a minute, then appears. Why, and what should we change?
