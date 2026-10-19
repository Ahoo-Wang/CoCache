---
name: a02-develop-invalidate-after-commit
tags: [activation, trigger, write-path, english]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Our Spring Boot app uses CoCache 6 (UserCache via @EnableCoCache). Make UserService.rename invalidate the cached user correctly inside its @Transactional method.
