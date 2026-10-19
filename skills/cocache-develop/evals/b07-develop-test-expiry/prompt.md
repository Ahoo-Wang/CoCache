---
name: b07-develop-test-expiry
tags: [behavior, testing]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

为使用 CoCache 6 的 UserService 写测试：验证缓存值在 local-max-age 之后会从 L1 读回、在 ttl 之后才回源，CI 上没有 Redis，也不允许 sleep。
