---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Identifies that @Cacheable without sync = true makes Spring do an unconditional put that can overwrite a newer invalidation, leaving the old value until ttl
2. Adds sync = true to the @Cacheable
3. Recommends cocache.spring-cache.require-sync: true (or the startup WARN check) to stop future non-sync methods
4. Does not propose shortening ttl as the fix
