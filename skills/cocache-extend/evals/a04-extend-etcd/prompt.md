---
name: a04-extend-etcd
tags: [activation, trigger, backend]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

我们想用 etcd 做 CoCache 的 L1 和失效通道，实现 RemoteStore 和 InvalidationChannel 需要满足什么，怎么验证？
