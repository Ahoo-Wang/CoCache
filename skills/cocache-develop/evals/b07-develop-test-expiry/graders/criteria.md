---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Runs without Redis using cocache.backend=memory in @SpringBootTest or an InMemoryBackend runtime
2. Uses a ManualClock (bean or passed to runtime and backend) and advances it instead of sleeping
3. Asserts the loader call count across the local-max-age and ttl boundaries, accounting for ttl jitter
4. Notes test isolation (shared context or in-memory store) or that memory-backend evidence does not prove Redis behavior
