---
type: tool_used
tool: Skill
input_match: '"skill"\s*:\s*"(?:[\w-]+:)?cocache-extend"'
min: 0
max: 0
arm: both
---

Changing CoCache's own Redis backend is CoCache repository work, so $cocache-extend must not load. Scored in both arms (`arm: both`): the without-skill arm passes trivially, so a negative case measures trigger precision, not a with/without delta.
