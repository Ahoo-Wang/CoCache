---
name: a06-debug-stale-one-node
tags: [activation, trigger, diagnose, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

One of our four instances keeps returning an old price from the CoCache PriceCache for a few minutes after updates; the others are correct. Find out why.
