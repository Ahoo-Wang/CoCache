---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Explains the earlier lookup cached a negative entry because the loader returned null, and it lasts absent-ttl (1 minute by default)
2. Recommends invalidating the key after the user is created (after commit), or shortening absent-ttl
3. Mentions how to confirm it, such as actuator key inspection showing ABSENT or lookup returning Lookup.Absent
