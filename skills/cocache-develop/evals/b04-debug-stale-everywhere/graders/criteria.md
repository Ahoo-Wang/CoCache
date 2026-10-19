---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Uses read-only evidence first, such as GET /actuator/cocache/ProductCache/{key} to compare local and remote copies, or metrics, without running DELETE or Redis writes
2. Identifies the admin service writing the table without invalidating as the likely cause of stale data on every instance
3. Proposes that the external writer invalidates after its commit, via ExternalInvalidator on the JVM or the documented DEL plus invalidation frame protocol
4. Notes that clearing the bad key now (actuator DELETE) is a cluster-wide write that needs authorization, and that lowering ttl only bounds staleness rather than fixing it
