---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Rejects a rolling deploy on the same keys because 5.x and 6.x do not see each other's invalidations and overwrite each other's values
2. Offers stop-all-then-start, or side by side on separate keys (distinct key prefix, namespace or Redis database) with the caveat that writes on one side do not invalidate the other
3. Covers leftover 5.x keys (read as foreign content and reloaded, optional SCAN plus UNLINK per master with authorization)
4. Defines a rollback path and the evidence gates before production
