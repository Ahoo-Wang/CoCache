---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Rejects SetCache because the set's truth lives in the database and SetCache is only for sets whose only copy is Redis
2. Declares Cache<Long, Set<String>> with a loader reading article_tag
3. Maintains it by writing the database and then invalidating the key after commit
