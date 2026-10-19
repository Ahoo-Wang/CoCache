---
name: a05-review-diff
tags: [activation, trigger, review]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

审查当前分支相对 origin/main 的改动：它给商品服务加了 me.ahoo.cocache 的 ProductCache 和相关写路径，只报告合并阻塞项，不要改文件。
