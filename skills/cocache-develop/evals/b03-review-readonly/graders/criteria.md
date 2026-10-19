---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Flags the invalidation before the commit inside @Transactional and directs it to after commit (TransactionalInvalidator or equivalent)
2. Flags @Cacheable without sync = true
3. Flags ProductKey lacking an overridden toString() (not a data class or record), so the first read fails with IllegalArgumentException, and directs to a data class, an override, a keyExpression or a KeyEncoder
4. Flags SetCache used for tags whose truth is a database table and directs to Cache<Long, Set<String>> with invalidate
5. Gives file and line evidence for each finding and states it made no changes
