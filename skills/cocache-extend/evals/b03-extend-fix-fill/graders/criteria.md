---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Explains that fill writes without checking that the key still holds fill.token, so a load that started before an invalidation writes its stale value back
2. Rewrites fill as an atomic compare-and-set on the token (lease or refresh token) that resets the expiry and returns whether it wrote
3. Checks that invalidate destroys tokens and does not edit or skip the TCK case; reruns the case and the suites
