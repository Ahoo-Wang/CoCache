---
layout: home

hero:
  name: CoCache
  text: 二级分布式一致性缓存框架
  tagline: 高性能 Java/Kotlin 二级缓存框架，通过事件总线实现自动缓存一致性。
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/guide/
    - theme: alt
      text: 架构设计
      link: /zh/architecture/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/CoCache

features:
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
    title: 二级缓存
    details: L2（本地内存缓存，支持 Guava/Caffeine）+ L1（分布式缓存，Redis）自动缓存提升和双写。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
    title: 事件驱动一致性
    details: CacheEvictedEventBus 通过 Redis Pub/Sub 或进程内事件总线实现跨实例分布式缓存失效。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
    title: 注解驱动
    details: 通过 @CoCache、@GuavaCache、@CaffeineCache 和 @JoinCacheable 注解声明式配置缓存。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
    title: 缓存击穿防护
    details: 细粒度的逐键锁配合双重检查模式，防止缓存未命中时的惊群效应。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
    title: TTL 抖动
    details: 自动 TTL 随机化防止多个缓存条目同时过期导致的缓存雪崩。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4-4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>
    title: JoinCache
    details: 将多个缓存值组合成单一结果，支持跨缓存 Join 语义和自动失效。
---

