---
name: b02-migrate-switch-over
tags: [behavior, cutover]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

We run 12 instances of a service on CoCache 5.4.0 against one shared Redis Cluster and want to move to CoCache 6.0.0 with as little downtime as possible. Our usual practice is a rolling deploy. Give the switch-over plan.
