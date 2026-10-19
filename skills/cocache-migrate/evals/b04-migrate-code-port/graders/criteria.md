---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Replaces CacheSource with CacheLoader returning the value (null for missing)
2. Moves the per-entry lifetime to ttlOf on a CacheSpec or CacheSpecCustomizer
3. Replaces evict with invalidate and moves it after the delete is committed
4. Replaces getTtlAt with lookup(key).expiresAt or actuator/CacheAdmin inspection for the Redis TTL
