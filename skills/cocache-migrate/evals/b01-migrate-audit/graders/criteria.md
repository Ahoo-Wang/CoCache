---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the CoCache sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Maps the build (cocache-spring-redis removed, Jedis not used by CoCache, Lettuce needed), imports (me.ahoo.cache to me.ahoo.cocache) and annotations (ttlAmplitude to ttlJitter, clientSideTtl = 0 to a finite localMaxAge, keyPrefix changing the storage key)
2. Maps CacheSource with TtlAt to a CacheLoader plus ttlOf or policy ttl, and the {name}.CacheSource bean convention to @CoCache(loader) or a unique bean
3. Maps the properties (read-failure: throw, channel-mode: sharded, defaults.load-max-concurrent) and removes cocacheClient
4. States there is no rolling upgrade and that the SetElementsCache sets must be copied (or kept on a database-backed shape) before 6.x serves traffic
5. Stays read-only and lists resolved dependencies, Redis contents, external writers, startup and production state as MISSING EVIDENCE
