---
title: 测试
description: 不依赖 Redis 测试使用 CoCache 的应用：在 @SpringBootTest 中使用内存后端、用 ManualClock 推进时间、用 CacheRuntime 写普通单元测试，以及用 HookedBackend 模拟 Redis 故障。
---

# 测试

CoCache 提供了不依赖 Redis、也不需要 `sleep` 就能测试缓存代码的全部工具：一个完整实现协议的内存后端、一个手动推进的时钟，以及一个能注入 Redis 故障的后端包装器。

## 不依赖 Redis 的 `@SpringBootTest` {#spring-boot}

一个配置项切换后端：

```kotlin
@SpringBootTest(properties = ["cocache.backend=memory"])
class UserServiceTest {
    @Autowired lateinit var users: UserService
    @MockitoBean lateinit var repo: UserRepository

    @Test
    fun secondReadIsCached() {
        given(repo.findById("42")).willReturn(User("42", "Ada"))

        users.find("42")
        users.find("42")

        then(repo).should(times(1)).findById("42")
    }
}
```

- 即使配置了 Spring Data Redis，也不会连接 Redis。starter 会记录一条 `WARN`，说明该后端只适用于单进程。
- 缓存、loader、`TransactionalInvalidator`、Spring Cache 与 actuator 端点都与生产环境一样工作。
- 内存中的“Redis”与 Spring 上下文同生命周期，而 Spring 会在测试类之间复用上下文。请失效测试用到的 key（或使用 `@DirtiesContext`），避免一个测试的条目泄漏到下一个。

内存后端只用于测试与本地开发。每个进程各有一份，实例之间永远看不到彼此的写入。

## 用 `ManualClock` 推进时间 {#manual-clock}

声明一个 `ManualClock` bean。运行时与内存后端都会使用它，推进它就能让 L2 与“Redis”一起到期。这里的 `UserCache` 就是[快速上手](./quick-start.md#declare)中的声明 `@CoCache(ttl = 600)`：

```kotlin
@SpringBootTest(properties = ["cocache.backend=memory"])
class ExpiryTest {
    @TestConfiguration
    class Clock {
        @Bean
        fun manualClock() = ManualClock()
    }

    @Autowired lateinit var clock: ManualClock
    @Autowired lateinit var userCache: UserCache
    @MockitoBean lateinit var repo: UserRepository

    @Test
    fun reloadsAfterTtl() {
        given(repo.findById("42")).willReturn(User("42", "Ada"))
        userCache["42"]

        clock.advance(Duration.ofMinutes(6))     // 超过 local-max-age（5m）：从 L1 读回
        userCache["42"]
        then(repo).should(times(1)).findById("42")

        clock.advance(Duration.ofMinutes(10))    // 超过 ttl（600 秒 ± 60 秒）：从数据源重新加载
        userCache["42"]
        then(repo).should(times(2)).findById("42")
    }
}
```

`ManualClock` 驱动 CoCache 计算的所有寿命：`ttl`、`absent-ttl`、`local-max-age` 与 `refresh-window`。它不驱动 Caffeine 自己的 `expireAfterAccess`，也不驱动失败失效的后台重试，这两者按真实时间运行。

## 普通单元测试 {#unit}

不用 Spring 时，在 `InMemoryBackend` 上构建运行时。共享同一个后端的两个运行时，表现得就像共享同一个 Redis 的两个实例，在一个 JVM 里就能测试跨实例行为：

```kotlin
class UserCacheTest {
    private val clock = ManualClock()
    private val backend = InMemoryBackend(clock)
    private val db = ConcurrentHashMap(mapOf("42" to "Ada"))
    private val loads = AtomicInteger()
    private val runtimes = mutableListOf<CacheRuntime>()

    private fun instance(): Cache<String, String> {
        val runtime = CacheRuntime.builder().backend(backend).clock(clock).build().also { runtimes += it }
        return runtime.create(
            cacheSpec<String, String>("UserCache") {
                codec = StringCodec
                loader = CacheLoader { id -> loads.incrementAndGet(); db[id] }
            }
        )
    }

    @AfterEach
    fun close() = runtimes.forEach { it.close() }

    @Test
    fun invalidationReachesOtherInstances() {
        val a = instance()
        val b = instance()
        assertEquals("Ada", a["42"])
        assertEquals("Ada", b["42"])            // 来自共享的 L1
        assertEquals(1, loads.get())

        db["42"] = "Grace"
        a.invalidate("42")                      // 内存后端同步投递

        assertEquals("Grace", b["42"])
    }
}
```

需要对计数（加载次数、L1 命中、降级填充等）断言时，用 `runtime.metrics("UserCache")`。

## 模拟 Redis 故障 {#faults}

`me.ahoo.cocache:cocache-test` 制品（作为测试依赖引入）中的 `HookedBackend` 可以包装任意后端，按需让它的操作失败：

| 开关 | 失败的操作 |
|------|------------|
| `failAll` | 所有 Redis 操作 |
| `failWrites` | `put`、`invalidate` 与集合元素写入 |
| `failFill` | 把加载结果写入 Redis |
| `failRelease` | 释放 lease |

```kotlin
val hooked = HookedBackend(InMemoryBackend(clock))
val runtime = CacheRuntime.builder().backend(hooked).clock(clock).build()
val users = runtime.create(cacheSpec<String, String>("UserCache") {
    codec = StringCodec
    loader = CacheLoader { id -> db[id] }
})

hooked.failAll = true
assertEquals("Ada", users["42"])                         // 降级：从数据源加载
assertThrows<CacheWriteException> { users.put("42", "x") }
users.invalidate("42")                                   // 不抛出：进入重试队列
assertEquals(1, runtime.admin("UserCache")!!.pendingInvalidationCount)

hooked.failAll = false
// 第一次重试大约在 100 毫秒真实时间之后：轮询直到计数降为 0
```

在 Spring Boot 测试中，把包装器声明为 `CoherenceBackend` bean 即可；显式声明的后端优先于 `cocache.backend`：

```kotlin
@Bean
fun backend(clock: ManualClock): CoherenceBackend = HookedBackend(InMemoryBackend(clock))
```

这些测试应当预期的行为，列在[工作原理](./concepts.md#failures)中。

## 测试自己的后端 {#tck}

如果你为 Redis 之外的存储实现了 `CoherenceBackend`，`cocache-test` 同时也是兼容性测试套件：继承 `BackendProtocolSpec`、`EngineSpec` 与 `SetEngineSpec`。见[扩展 CoCache](../architecture/extending.md)。
