---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Declares an interface extending JoinCache<String, Order, Long, Customer> with @JoinCacheable naming firstCacheName and joinCacheName explicitly
2. Uses a JoinKeyExtractor bean because the join key is Long (joinKeyExpression requires String join keys)
3. Registers the view in @EnableCoCache(joinCaches = ...) rather than caches
4. Explains that the view stores nothing, getAll does two batch reads, and freshness comes from invalidating OrderCache and CustomerCache at their own write paths
