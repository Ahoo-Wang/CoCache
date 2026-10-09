---
layout: home

hero:
  name: CoCache
  text: Level 2 Distributed Coherence Cache
  tagline: A high-performance, two-level caching framework for Java/Kotlin with automatic cache coherence via event bus.
  actions:
    - theme: brand
      text: Get Started
      link: /guide/
    - theme: alt
      text: Architecture
      link: /architecture/
    - theme: alt
      text: View on GitHub
      link: https://github.com/Ahoo-Wang/CoCache

features:
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
    title: Two-Level Caching
    details: L2 (bounded local Caffeine) + L1 (distributed Redis) with stamp-guarded promotion and write-through to both tiers; staleness is bounded by finite TTLs and reset-on-resubscribe.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
    title: Event-Driven Coherence
    details: CacheEvictedEventBus enables distributed cache invalidation across all instances via Redis Pub/Sub or in-process event bus.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
    title: Annotation-Driven
    details: Declarative cache configuration with @CoCache, @CaffeineCache, and @JoinCacheable annotations.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
    title: Cache Stampede Prevention
    details: Per-key SingleFlight coalesces concurrent misses into one load, preventing thundering herd without blocking unrelated keys.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
    title: TTL Jitter
    details: Automatic TTL randomization prevents cache avalanche when multiple entries expire simultaneously.
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4-4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>
    title: JoinCache
    details: Compose multiple cached values into a single result with cross-cache join semantics and automatic invalidation.
---

