---
type: tool_used
tool: Skill
input_match: '"skill"\s*:\s*"(?:[\w-]+:)?cocache-migrate"'
min: 0
max: 0
arm: both
---

Fixing a CoherenceBackend against the TCK belongs to cocache-extend, so $cocache-migrate must not load. Scored in both arms (`arm: both`): the without-skill arm passes trivially, so a negative case measures trigger precision, not a with/without delta.
