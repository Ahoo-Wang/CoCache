---
layout: home

hero:
  name: CoCache
  text: 面向 JVM 的二级一致性缓存
  tagline: 本地内存级读取，Redis 共享状态，任何副本的陈旧程度都有硬上限。适用于 Spring Boot 上的 Java 与 Kotlin 应用。
  actions:
    - theme: brand
      text: 快速上手
      link: /zh/guide/quick-start
    - theme: alt
      text: 为什么选择 CoCache
      link: /zh/guide/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/CoCache

features:
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
    title: 快在关键路径上
    details: 进程内的 Caffeine 副本（L2）位于共享 Redis（L1）之前。L2 命中只是一次查表加一次时间比较，没有任何 I/O。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
    title: 跨实例一致
    details: 每次写入与失效都修改 Redis 并通知所有实例，默认在同一个原子脚本中完成。其它实例随即丢弃本地副本。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
    title: 陈旧度有界
    details: 丢失的事件不会让副本永远陈旧。每个本地副本都在 localMaxAge 后过期；每次写回 Redis 都受 lease 保护，慢加载永远覆盖不了更新的失效。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
    title: 全集群每个 key 只加载一次
    details: 并发未命中在实例内合并为一次加载，借助 lease 在集群内也只加载一次。不存在的 key 写入负缓存，TTL 抖动打散过期时间。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
    title: 声明即可用
    details: 缓存就是一个带注解的接口或一个 CacheSpec bean。Spring Boot 负责创建，cocache.* 配置项无需重新编译即可调整任意缓存。
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11"/></svg>
    title: 不依赖 Redis 也能测试
    details: 一个配置项切换到内存后端，用 ManualClock 推进时间。Spring Cache、事务与 actuator 开箱即用。
---

## 十几行代码的缓存 {#example}

```kotlin
@CoCache(ttl = 600)                                   // 单位：秒
interface UserCache : Cache<String, User>

@Bean                                                 // 数据源，按泛型类型匹配
fun userLoader(repo: UserRepository) = CacheLoader<String, User> { id -> repo.findById(id) }

@EnableCoCache(caches = [UserCache::class])
@SpringBootApplication
class App

// 任何地方
val user = userCache["42"]                            // L2 → Redis → loader
invalidator.invalidate(userCache, "42")               // 事务提交后，在所有实例上失效
```

[五分钟上手 →](/zh/guide/quick-start)
