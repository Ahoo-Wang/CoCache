---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Sets atomicBroadcast = false because publishing cannot be atomic with the write, so the engine publishes after each write
2. Calls onReset() on every listener on each confirmed (re)subscription and whenever messages may have been lost, with the first reset done before subscribe returns
3. Implements fill as a token compare-and-set, resets expiry on every state change, never deletes on read, throws on failure and rejects commands while disconnected
4. Proves it with BackendProtocolSpec, EngineSpec and SetEngineSpec, without weakening tests, in every supported mode
