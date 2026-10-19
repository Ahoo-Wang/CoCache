---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Explains that invalidating before the commit lets a concurrent read reload and cache the old row
2. Moves the invalidation after the commit with TransactionalInvalidator (or an equivalent after-commit hook) instead of calling cache.invalidate inside the transaction
3. Prefers invalidate over put after the write, or justifies put; does not claim put fills the local L2
4. Proposes a concrete test without Redis (cocache.backend=memory or an InMemoryBackend runtime) covering the reload after commit and no invalidation on rollback
