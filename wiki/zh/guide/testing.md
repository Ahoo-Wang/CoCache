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

- 即使配置了 Spring Data Redis，CoCache 也不会连接 Redis。starter 会记录一条 `WARN`，说明该后端只适用于单进程。classpath 中有 Spring Data Redis 与 actuator 时，Spring Boot 自带的 Redis 健康检查仍会连接 Redis；用 `management.health.redis.enabled=false` 关闭它。
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

`ManualClock` 驱动 CoCache 计算的所有寿命：`ttl`、`absent-ttl`、`local-max-age` 与 `refresh-window`。它不驱动 Caffeine 自己的 `expireAfterAccess`，也不驱动失败失效的后台重试，这两者按真实时间运行。`ManualClock.advance` 只接受整毫秒（时钟的精度）：不足一毫秒的时长，如 `Duration.ofNanos(500)`，抛出 `IllegalArgumentException`，而不是被静默截掉。

## 普通单元测试 {#unit}

不用 Spring 时，在 `InMemoryBackend` 上构建运行时。共享同一个后端的两个运行时，表现得就像共享同一个 Redis 的两个实例，在一个 JVM 里就能测试跨实例行为。`ManualClock` 只需交给后端一次：没有指定时钟的运行时沿用构造 `InMemoryBackend` 时给出的时钟（`InMemoryBackend.clock`），L1 与 L2 因此按同一个时间到期。

```kotlin
class UserCacheTest {
    private val clock = ManualClock()
    private val backend = InMemoryBackend(clock)
    private val db = ConcurrentHashMap(mapOf("42" to "Ada"))
    private val loads = AtomicInteger()
    private val runtimes = mutableListOf<CacheRuntime>()

    private fun instance(): Cache<String, String> {
        val runtime = CacheRuntime.builder().backend(backend).build().also { runtimes += it }   // runtime.clock === clock
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
| `failAll` | L1 存储上的所有操作（失效频道照常工作） |
| `failReads` | 只有读取：单 key、批量与条件读取。写入照常，测试可以检查读取如何降级（`read-failure`、`unknownRead`），而写入方继续工作 |
| `failWrites` | Redis 写入：`put`、`putAll`、`invalidate`、集合成员写入、条件写入与按版本的写入。结果未知，与超时之后一样，所以 `Cache.put` 被补偿而不抛出 |
| `rejectWrites` | 同样的写入，在到达被包装的后端之前以 `L1RejectedException` 拒绝：确定没有执行，所以 `CacheWriteException.outcomeUnknown` 为 `false` |
| `failFill` | 把加载结果写入 Redis |
| `failRelease` | 释放 lease |

`HookedBackend` 这样的包装不会把内层后端的时钟传出来，所以运行时也要给出时钟：

```kotlin
val hooked = HookedBackend(InMemoryBackend(clock))
val runtime = CacheRuntime.builder().backend(hooked).clock(clock).build()
val users = runtime.create(cacheSpec<String, String>("UserCache") {
    codec = StringCodec
    loader = CacheLoader { id -> db[id] }
})

hooked.failAll = true
assertEquals("Ada", users["42"])                         // 降级：从数据源加载
users.invalidate("42")                                   // 不抛出：进入重试队列
users.put("43", "x")                                     // 结果未知：被补偿，不抛出
assertEquals(2, runtime.admin("UserCache")!!.pendingInvalidationCount)

hooked.failAll = false
hooked.rejectWrites = true
assertThrows<CacheWriteException> { users.put("42", "x") } // 确定没有执行：抛出
hooked.rejectWrites = false
// 第一次重试大约在 100 毫秒真实时间之后：轮询直到计数降为 0
```

它的钩子（`afterRead`、`afterPut`、`beforeInvalidate` 等）收到的是完整的存储 key `{keyPrefix}{namespace}:{encodedKey}`（`Keyspace.storageKey`），不是编码后的 key。

在 Spring Boot 测试中，把包装器声明为 `CoherenceBackend` bean 即可；显式声明的后端优先于 `cocache.backend`：

```kotlin
@Bean
fun backend(clock: ManualClock): CoherenceBackend = HookedBackend(InMemoryBackend(clock))
```

这些测试应当预期的行为，列在[工作原理](./concepts.md#failures)中。

## 测试自己的后端 {#tck}

如果你为 Redis 之外的存储实现了 `CoherenceBackend`，`cocache-test` 同时也是兼容性测试套件：继承 `BackendProtocolSpec`、`EngineSpec`（`Cache` 与 `Store`）、`VersionedSpec`（`VersionedCache` 与 `VersionedStore`）、`SetEngineSpec`（`SetStore`）、`MultiNodeSpec` 与 `FaultSpec`。每个规范都声明 `expectedCapabilities`，即你的存储提供的可选能力面（`CONDITIONAL`、`VERSIONED`、`SETS`），存储提供的能力与之不同时失败。除 `BackendProtocolSpec` 之外的每个规范都可以把 `access` 覆盖为 `CacheAccess.ASYNC`，经[异步视图](./async.md)重跑同一组用例。见[扩展 CoCache](../architecture/extending.md#tck)。
