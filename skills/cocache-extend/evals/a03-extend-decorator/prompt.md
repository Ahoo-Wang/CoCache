---
name: a03-extend-decorator
tags: [activation, trigger, decorator]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

给 CoCache 写一个 CoherenceBackend 装饰器，包住 RedisBackend，为每个 RemoteStore 调用打 OpenTelemetry span，并保证仍然通过 TCK。
