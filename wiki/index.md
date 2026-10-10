---
layout: home

hero:
  name: CoCache
  text: Level 2 Distributed Coherence Cache
  tagline: Local-memory read latency with cross-instance coherence and bounded staleness, for Java/Kotlin on Spring Boot.
  actions:
    - theme: brand
      text: Quick Start
      link: /guide/quick-start
    - theme: alt
      text: Introduction
      link: /guide/
    - theme: alt
      text: View on GitHub
      link: https://github.com/Ahoo-Wang/CoCache

features:
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
    title: Two-Level Caching
    details: In-process Caffeine (L2) in front of shared Redis (L1) in front of your data source. An L2 hit never leaves the process.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
    title: Coherent Across Instances
    details: Writes and evicts broadcast over Redis Pub/Sub, and peers drop their L2 copy. L2 is cleared on every resubscribe, so lost events can't linger.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
    title: Bounded Staleness
    details: Finite default TTLs, a short negative-cache TTL, and stamp-guarded write-backs. A slow load can't overwrite a newer invalidation.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
    title: Stampede & Penetration Safe
    details: Concurrent misses for a key share one load. Missing keys are cached as explicit negative entries. TTL jitter spreads expiries.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
    title: Declarative
    details: Declare an interface with @CoCache and list it in @EnableCoCache. CoCache generates the implementation and wires it into Spring Boot.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4-4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>
    title: JoinCache & Spring Cache
    details: Compose two caches into one lookup with @JoinCacheable, or use CoCache behind @Cacheable through CoCacheManager.
---
