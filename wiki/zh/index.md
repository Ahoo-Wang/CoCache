---
layout: home

hero:
  name: CoCache
  text: 二级分布式一致性缓存框架
  tagline: 本地内存级读取延迟，跨实例一致，陈旧度有界。面向 Spring Boot 上的 Java/Kotlin 应用。
  actions:
    - theme: brand
      text: 快速上手
      link: /zh/guide/quick-start
    - theme: alt
      text: 介绍
      link: /zh/guide/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/CoCache

features:
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
    title: 二级缓存
    details: 进程内 Caffeine（L2）→ 共享 Redis（L1）→ 数据源。L2 命中不离开进程。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
    title: 跨实例一致
    details: 写入与淘汰经 Redis Pub/Sub 广播，其它实例丢弃 L2 副本；每次重新订阅都清空 L2，丢失的事件不会遗留陈旧数据。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
    title: 陈旧度有界
    details: 有限的默认 TTL、较短的负缓存 TTL、受失效戳保护的写回：慢加载无法覆盖更新的失效。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
    title: 防击穿与穿透
    details: 同一 key 的并发未命中只加载一次；不存在的 key 以显式负缓存记录；TTL 随机抖动打散到期时间。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
    title: 声明式
    details: 用 @CoCache 声明接口并列入 @EnableCoCache，CoCache 生成实现并接入 Spring Boot。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4-4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>
    title: JoinCache 与 Spring Cache
    details: 用 @JoinCacheable 把两个缓存组合为一次查询，或通过 CoCacheManager 在 @Cacheable 背后使用 CoCache。
---
