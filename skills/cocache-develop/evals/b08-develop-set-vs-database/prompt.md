---
name: b08-develop-set-vs-database
tags: [behavior, set-cache]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

We store each article's tags in the `article_tag` table. A teammate proposes `@CoCache interface ArticleTagsCache : SetCache<Long, String>` and calling `add`/`remove` after each insert or delete. Is that right for CoCache 6? Show what you would declare instead if not.
