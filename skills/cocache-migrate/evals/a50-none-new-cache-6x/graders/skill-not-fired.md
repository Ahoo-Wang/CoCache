---
type: tool_used
tool: Skill
input_match: '"skill"\s*:\s*"(?:[\w-]+:)?cocache-migrate"'
min: 0
max: 0
arm: both
---

New 6.x usage with nothing left from 5.x belongs to cocache-develop, so $cocache-migrate must not load. Scored in both arms (`arm: both`): the without-skill arm passes trivially, so a negative case measures trigger precision, not a with/without delta.
