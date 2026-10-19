---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. States that a 6.x SetCache cannot read the 5.x native set: reads return empty and add replaces the old set
2. Copies each set before switching, for example SMEMBERS then setCache.put(key, fullSet), or a new key prefix plus copy that keeps the old data for rollback
3. Requires stopping 5.x writers for the final pass, idempotent and resumable copying, and per-key member count reconciliation
4. Treats Redis writes as needing explicit environment authorization and mentions persistence and a non-allkeys eviction policy
