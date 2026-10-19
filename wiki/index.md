---
layout: home

hero:
  name: CoCache
  text: Two-level coherent cache for the JVM
  tagline: Local-memory reads, Redis-shared state, and a hard bound on how stale any copy can get. For Java and Kotlin on Spring Boot.
  actions:
    - theme: brand
      text: Quick Start
      link: /guide/quick-start
    - theme: alt
      text: Why CoCache
      link: /guide/
    - theme: alt
      text: View on GitHub
      link: https://github.com/Ahoo-Wang/CoCache

features:
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
    title: Fast where it matters
    details: An in-process Caffeine copy (L2) sits in front of shared Redis (L1). An L2 hit is a map lookup and a time check, with no I/O.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
    title: Coherent across instances
    details: Every write and invalidation changes Redis and notifies all instances, in one atomic script by default. Peers drop their local copy.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
    title: Bounded staleness
    details: Lost events can't leave a copy stale forever. Every local copy expires after localMaxAge, and a lease fences every Redis fill, so a slow load never overwrites a newer invalidation.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
    title: One load per key, cluster-wide
    details: Concurrent misses share one load per instance and, through the lease, across the cluster. Missing keys are cached as negative entries, and TTL jitter spreads expiries.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
    title: Declare, don't wire
    details: A cache is an annotated interface or a CacheSpec bean. Spring Boot creates it, and cocache.* properties tune any cache without recompiling.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11"/></svg>
    title: Testable without Redis
    details: Switch to the in-memory backend with one property and drive time with ManualClock. Spring Cache, transactions, and actuator work out of the box.
---

## A cache in a dozen lines

```kotlin
@CoCache(ttl = 600)                                   // seconds
interface UserCache : Cache<String, User>

@Bean                                                 // the data source, matched by type
fun userLoader(repo: UserRepository) = CacheLoader<String, User> { id -> repo.findById(id) }

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App

// anywhere
val user = userCache["42"]                            // L2 → Redis → loader
invalidator.invalidate(userCache, "42")               // after the commit, on every instance
```

[Get started in five minutes →](/guide/quick-start)
