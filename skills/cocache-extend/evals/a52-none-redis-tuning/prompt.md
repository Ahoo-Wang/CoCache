---
name: a52-none-redis-tuning
tags: [activation, negative, routed, develop, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Our app's CoCache RedisBackend logs 'Atomic broadcast is not supported' on ElastiCache Serverless. Is that a problem and should we change cocache.redis.channel-mode?
