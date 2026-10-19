---
type: tool_used
tool: Skill
input_match: '"skill"\s*:\s*"(?:[\w-]+:)?cocache-develop"'
min: 0
max: 0
arm: both
---

CoCache is mentioned only to reject it; the work is Spring Data Redis caching, so $cocache-develop must not load. Scored in both arms (`arm: both`): the without-skill arm passes trivially, so a negative case measures trigger precision, not a with/without delta.
