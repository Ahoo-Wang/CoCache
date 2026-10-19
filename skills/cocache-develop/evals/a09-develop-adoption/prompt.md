---
name: a09-develop-adoption
tags: [activation, trigger, adoption]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

我们的用户服务目前直接查数据库，想引入 CoCache 做二级缓存（本地 Caffeine + Redis），请帮我接入并写好失效逻辑。
