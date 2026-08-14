# 核心并发修复实施计划（Core Concurrency Fixes Implementation Plan）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 DefaultCoherentCache 的锁回收竞态、CacheSecondClock 启动竞态、失效-回源旧值回填竞态，并补齐 CoherentCache 生命周期（close 钩子）。

**Architecture:** 用 Guava Striped 锁（固定 1024 stripe、永不回收）替换可移除的 per-key 锁映射；用仅回源在途时存在的 per-key 失效代际计数器（ConcurrentHashMap<String, Long>）让 `onEvicted` 非阻塞地使在途回源结果失效（写前丢弃 / 写后补偿淘汰）；`CoherentCache` 接口新增默认空 `close()`（继承 AutoCloseable，二进制兼容），`DefaultCoherentCache` 覆写为注销订阅 + 关闭分布式缓存，Spring 侧 FactoryBean 实现 DisposableBean。

**Tech Stack:** Kotlin (JVM 17)、Guava Striped、java.util.concurrent、JUnit 5 + mockk + fluent-assert（`me.ahoo.test.asserts.assert`，禁用 AssertJ assertThat）、Gradle。

**设计规格:** `docs/superpowers/specs/2026-08-14-core-concurrency-fixes-design.md`

**重要约定（每个任务通用）:**
- 测试断言一律 `import me.ahoo.test.asserts.assert` + `.assert()`，禁止 AssertJ。
- mockk 由根 `build.gradle.kts` 对所有子模块生效（`testImplementation("io.mockk:mockk")`），无需改任何构建文件。
- Conventional commits：`fix(core):` / `feat(core):` / `feat(spring):` / `docs(superpowers):`。
- 所有路径均为相对仓库根 `/Users/ahoo/work/ahoo-git/CoCache`。

## 文件结构（改动总览）

| 责任 | 文件 | 动作 |
|---|---|---|
| 时钟启动竞态修复 | `cocache-core/src/main/kotlin/me/ahoo/cache/util/CacheSecondClock.kt` | 修改 |
| 时钟回归测试 | `cocache-core/src/test/kotlin/me/ahoo/cache/util/CacheSecondClockTest.kt` | 新建 |
| close 钩子接口 | `cocache-core/src/main/kotlin/me/ahoo/cache/consistency/CoherentCache.kt` | 修改 |
| 锁/代际/close 实现 | `cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt` | 修改 |
| JoinCache 组合关闭 | `cocache-core/src/main/kotlin/me/ahoo/cache/join/SimpleJoinCache.kt` | 修改 |
| TCK 规范增强 | `cocache-test/src/main/kotlin/me/ahoo/cache/test/DefaultCoherentCacheSpec.kt` | 修改 |
| 具体实现测试补充 | `cocache-core/src/test/kotlin/me/ahoo/cache/consistency/DefaultCoherentCacheTest.kt` | 修改 |
| JoinCache 测试补充 | `cocache-core/src/test/kotlin/me/ahoo/cache/join/SimpleJoinCacheTest.kt` | 修改 |
| Spring 销毁回调 ×2 | `cocache-spring/src/main/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBean.kt`、`cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBean.kt` | 修改 |
| FactoryBean 测试 ×2 | `cocache-spring/src/test/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBeanTest.kt`、`cocache-spring/src/test/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBeanTest.kt` | 新建 |

---

## Task 1: CacheSecondClock 启动顺序修复

竞态是概率性的，无法确定性复现为红测试；本任务测试作为"时钟线程死亡"的回归金丝雀（修复后线程永不因 NPE 死亡，测试稳定通过）。

**Files:**
- Create: `cocache-core/src/test/kotlin/me/ahoo/cache/util/CacheSecondClockTest.kt`
- Modify: `cocache-core/src/main/kotlin/me/ahoo/cache/util/CacheSecondClock.kt:32-53`

- [ ] **Step 1: 编写回归金丝雀测试**

创建 `cocache-core/src/test/kotlin/me/ahoo/cache/util/CacheSecondClockTest.kt`：

```kotlin
package me.ahoo.cache.util

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class CacheSecondClockTest {
    @Test
    fun currentTimeAdvances() {
        val before = CacheSecondClock.INSTANCE.currentTime()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (CacheSecondClock.INSTANCE.currentTime() <= before && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
        (CacheSecondClock.INSTANCE.currentTime() > before).assert().isTrue()
    }
}
```

- [ ] **Step 2: 运行测试（金丝雀基线，预期通过）**

Run: `./gradlew :cocache-core:test --tests "me.ahoo.cache.util.CacheSecondClockTest"`
Expected: BUILD SUCCESSFUL（该测试为金丝雀；若失败说明时钟线程已死，正是本任务要根治的故障模式）

- [ ] **Step 3: 实施修复**

修改 `CacheSecondClock.kt`——`startTimer` 改名 `createTimer` 且不再 start；`init` 先赋值后启动；`run()` 不再读取字段：

```kotlin
    init {
        // 赋值必须先于 start()：start() 的 happens-before 边保证线程读到已赋值的 secondTimer。
        secondTimer = createTimer()
        secondTimer.start()
    }

    private fun createTimer(): Thread {
        val timer = Thread(this)
        timer.name = "CacheSecondClock"
        timer.isDaemon = true
        return timer
    }

    override fun currentTime(): Long {
        return lastTime
    }

    override fun run() {
        while (!Thread.currentThread().isInterrupted) {
            lastTime = actual.currentTime()
            LockSupport.parkNanos(this, ONE_SECOND_PERIOD)
        }
    }
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :cocache-core:test --tests "me.ahoo.cache.util.CacheSecondClockTest"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add cocache-core/src/main/kotlin/me/ahoo/cache/util/CacheSecondClock.kt cocache-core/src/test/kotlin/me/ahoo/cache/util/CacheSecondClockTest.kt
git commit -m "fix(core): start CacheSecondClock timer after field assignment"
```

---

## Task 2: CoherentCache close 钩子（接口 + DefaultCoherentCache + TCK 生命周期测试）

**Files:**
- Test: `cocache-test/src/main/kotlin/me/ahoo/cache/test/DefaultCoherentCacheSpec.kt`（类末尾追加测试）
- Test: `cocache-core/src/test/kotlin/me/ahoo/cache/consistency/DefaultCoherentCacheTest.kt`（类末尾追加测试）
- Modify: `cocache-core/src/main/kotlin/me/ahoo/cache/consistency/CoherentCache.kt:25`
- Modify: `cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt`

- [ ] **Step 1: 编写失败测试（TCK 规范层）**

在 `DefaultCoherentCacheSpec` 类末尾（`should prevent cache breakdown...` 测试之后、类结束大括号之前）追加。所需 import 均已在文件头存在：

```kotlin
    @Test
    fun closeUnregistersSubscriber() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)
        coherentCache.setCache(key, cacheValue)
        val cacheKey = keyConverter.toStringKey(key)

        coherentCache.close()

        cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, "remote-client-id"))
        clientSideCache[cacheKey].assert().isEqualTo(value)
    }

    @Test
    fun closeIsIdempotentAndCacheStillUsable() {
        val (key, value) = createCacheEntry()
        CACHE_SOURCE_VALUE.set(DefaultCacheValue.forever(value))
        try {
            coherentCache.close()
            coherentCache.close()
            coherentCache[key].assert().isEqualTo(value)
        } finally {
            CACHE_SOURCE_VALUE.remove()
        }
    }
```

- [ ] **Step 2: 编写失败测试（具体实现层，验证 distributedCache.close 被调用）**

在 `DefaultCoherentCacheTest` 类末尾追加（新增 import：`io.mockk.mockk`、`io.mockk.verify`、`me.ahoo.cache.api.source.CacheSource`）：

```kotlin
    @Test
    fun closeClosesDistributedCache() {
        val distributedCache = mockk<DistributedCache<String>>(relaxUnitFun = true)
        val cache = DefaultCoherentCache(
            config = CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(""),
                clientSideCache = MapClientSideCache(),
                distributedCache = distributedCache,
                cacheSource = CacheSource.noOp()
            ),
            cacheEvictedEventBus = GuavaCacheEvictedEventBus()
        )
        cache.close()
        verify(exactly = 1) { distributedCache.close() }
    }
```

- [ ] **Step 3: 运行测试验证失败（编译错误即红）**

Run: `./gradlew :cocache-core:test --tests "me.ahoo.cache.consistency.DefaultCoherentCacheTest"`
Expected: 编译失败，`close()` 未定义（`unresolved reference: close`）

- [ ] **Step 4: 修改 CoherentCache 接口**

```kotlin
interface CoherentCache<K, V> :
    ComputedCache<K, V>,
    DistributedClientId,
    NamedCache,
    CacheEvictedSubscriber,
    AutoCloseable {
    val cacheEvictedEventBus: CacheEvictedEventBus
    val clientSideCache: ClientSideCache<V>
    val distributedCache: DistributedCache<V>
    val keyFilter: KeyFilter
    val keyConverter: KeyConverter<K>
    val cacheSource: CacheSource<K, V>

    /**
     * 空默认实现保证对既有实现者二进制兼容（-Xjvm-default=all-compatibility 编译为 default method）。
     * DefaultCoherentCache 覆写为注销事件订阅并关闭分布式缓存。
     */
    override fun close() {}
}
```

- [ ] **Step 5: DefaultCoherentCache 实现 close()**

在 `DefaultCoherentCache.kt` 中：新增 import `java.util.concurrent.atomic.AtomicBoolean`；在 `private val keyLocks = ...` 一行之后新增成员：

```kotlin
    private val closed = AtomicBoolean(false)
```

在 `evict` 方法与 `onEvicted` 方法之间插入：

```kotlin
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        log.info { "Cache Name[$cacheName] - ClientId[$clientId] - close." }
        runCatching {
            cacheEvictedEventBus.unregister(this)
        }.onFailure {
            log.warn(it) { "Cache Name[$cacheName] - ClientId[$clientId] - Failed to unregister from the evicted event bus." }
        }
        runCatching {
            distributedCache.close()
        }.onFailure {
            log.warn(it) { "Cache Name[$cacheName] - ClientId[$clientId] - Failed to close the distributed cache." }
        }
    }
```

- [ ] **Step 6: 运行测试验证通过**

Run: `./gradlew :cocache-core:test --tests "me.ahoo.cache.consistency.DefaultCoherentCacheTest"`
Expected: BUILD SUCCESSFUL（含新增的 `closeUnregistersSubscriber`、`closeIsIdempotentAndCacheStillUsable`、`closeClosesDistributedCache`）

- [ ] **Step 7: 提交**

```bash
git add cocache-core/src/main/kotlin/me/ahoo/cache/consistency/CoherentCache.kt cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt cocache-test/src/main/kotlin/me/ahoo/cache/test/DefaultCoherentCacheSpec.kt cocache-core/src/test/kotlin/me/ahoo/cache/consistency/DefaultCoherentCacheTest.kt
git commit -m "feat(core): add close hook to CoherentCache lifecycle"
```

---

## Task 3: SimpleJoinCache 实现 AutoCloseable

**Files:**
- Test: `cocache-core/src/test/kotlin/me/ahoo/cache/join/SimpleJoinCacheTest.kt`（类末尾追加测试）
- Modify: `cocache-core/src/main/kotlin/me/ahoo/cache/join/SimpleJoinCache.kt:30-34`

- [ ] **Step 1: 编写失败测试**

在 `SimpleJoinCacheTest` 类末尾追加（新增 import：`io.mockk.mockk`、`io.mockk.verify`、`me.ahoo.cache.consistency.CoherentCache`）：

```kotlin
    @Test
    fun closeClosesComposedCaches() {
        val firstCache = mockk<CoherentCache<String, Order>>(relaxUnitFun = true)
        val joinCache = mockk<CoherentCache<String, OrderAddress>>(relaxUnitFun = true)
        val joinCaching = SimpleJoinCache(firstCache, joinCache) { _ -> "" }

        joinCaching.close()

        verify(exactly = 1) { firstCache.close() }
        verify(exactly = 1) { joinCache.close() }
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :cocache-core:test --tests "me.ahoo.cache.join.SimpleJoinCacheTest"`
Expected: 编译失败，`unresolved reference: close`

- [ ] **Step 3: 实现**

`SimpleJoinCache.kt` 类声明追加 `AutoCloseable`，类末尾追加 `close()`：

```kotlin
class SimpleJoinCache<K1, V1, K2, V2>(
    val firstCache: Cache<K1, V1>,
    val joinCache: Cache<K2, V2>,
    override val joinKeyExtractor: JoinKeyExtractor<V1, K2>
) : JoinCache<K1, V1, K2, V2>, ComputedCache<K1, JoinValue<V1, K2, V2>>, AutoCloseable {
```

```kotlin
    override fun close() {
        (firstCache as? AutoCloseable)?.close()
        (joinCache as? AutoCloseable)?.close()
    }
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :cocache-core:test --tests "me.ahoo.cache.join.SimpleJoinCacheTest"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add cocache-core/src/main/kotlin/me/ahoo/cache/join/SimpleJoinCache.kt cocache-core/src/test/kotlin/me/ahoo/cache/join/SimpleJoinCacheTest.kt
git commit -m "feat(core): make SimpleJoinCache AutoCloseable"
```

---

## Task 4: Striped 锁替换 + 击穿测试加固

先加固现有击穿测试（修复线程池泄漏与 latch 假通过），再做锁替换重构，全程现有测试保持绿。

**Files:**
- Test: `cocache-test/src/main/kotlin/me/ahoo/cache/test/DefaultCoherentCacheSpec.kt:155-196`（重写击穿测试）
- Modify: `cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt:22-23, 47, 78-86, 101-134`

- [ ] **Step 1: 加固击穿测试**

用以下内容整体替换 `DefaultCoherentCacheSpec.kt` 中 `should prevent cache breakdown under high concurrency` 测试（`@ParameterizedTest` 注解起至该方法结束大括号）。变化：`try/finally` 包裹、latch 超时校验、`executor.shutdownNow()`、`concurrentCache.close()`（Task 2 产物）：

```kotlin
    @ParameterizedTest
    @ValueSource(ints = [10, 100, 1000])
    fun `should prevent cache breakdown under high concurrency`(threadCount: Int) {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)

        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = ConcurrentLinkedQueue<Any?>()
        val callCount = AtomicInteger()

        val concurrentCache = DefaultCoherentCacheFactory(cacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                clientSideCache = clientSideCache,
                distributedCache = distributedCache,
                cacheSource = object : CacheSource<K, V> {
                    override fun loadCacheValue(key: K): CacheValue<V> {
                        callCount.incrementAndGet()
                        Thread.sleep(100) // 放大并发窗口
                        return cacheValue
                    }
                }
            )
        )

        try {
            repeat(threadCount) {
                executor.submit {
                    startLatch.await()
                    results.add(concurrentCache[key])
                    finishLatch.countDown()
                }
            }

            startLatch.countDown()
            val allFinished = finishLatch.await(5, TimeUnit.SECONDS)
            allFinished.assert().isTrue()
            results.all { it == value }.assert().isTrue()
            callCount.get().assert().isOne() // 核心断言
        } finally {
            executor.shutdownNow()
            concurrentCache.close()
        }
    }
```

- [ ] **Step 2: 运行加固后的测试（基于现有实现，预期通过）**

Run: `./gradlew :cocache-core:test --tests "me.ahoo.cache.consistency.DefaultCoherentCacheTest"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 实施锁替换**

修改 `DefaultCoherentCache.kt`：

3a. 新增 import（文件头 import 区）：

```kotlin
import com.google.common.util.concurrent.Striped
import java.util.concurrent.locks.Lock
```

3b. `companion object` 增加常量：

```kotlin
    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * Striped 锁数量：固定且永不回收，从根上消除"锁对象回收"竞态。
         * 不同 key 哈希到同一 stripe 时会被串行化，1024 个 stripe 下碰撞概率可忽略。
         */
        private const val KEY_LOCK_STRIPES = 1024
    }
```

3c. 替换成员（原第 47 行 `private val keyLocks = ConcurrentHashMap<String, Any>()`）：

```kotlin
    private val keyLocks: Striped<Lock> = Striped.lock(KEY_LOCK_STRIPES)
```

3d. 删除 `getLock` 与 `releaseLock` 两个私有方法（原第 78-86 行）。

3e. `getCache` 中锁段落改为显式 lock/unlock（整体结构如下，保留原有注释与区域标记）：

```kotlin
        val lock = keyLocks.get(cacheKey)
        lock.lock()
        try {
            getL2Cache(cacheKey)?.let {
                return it
            }

            //region L0:Cache Source
            /*
             * This is a heavy-duty operation.
             */
            cacheSource.loadCacheValue(key)?.let {
                setCache(cacheKey, it)
                cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
                return it
            }

            //endregion
            log.debug {
                "Cache Name[$cacheName] - ClientId[$clientId] - getCache[$cacheKey] " +
                    "- Set missing guard,because no cache source was found."
            }
            /*
             *** Fix 缓存穿透 ***
             * 0. Db 不存在该记录
             * 1. 穿透到 Db 回源
             **** 缓存空值 ***
             */
            setCache(cacheKey, DefaultCacheValue.missingGuard(ttl, ttlAmplitude))
            return null
        } finally {
            lock.unlock()
        }
```

（原 `synchronized(lock) { try { ... } finally { releaseLock(cacheKey) } }` 的内层 `releaseLock` finally 一并删除。）

- [ ] **Step 4: 运行 core 全量测试**

Run: `./gradlew :cocache-core:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt cocache-test/src/main/kotlin/me/ahoo/cache/test/DefaultCoherentCacheSpec.kt
git commit -m "fix(core): replace per-key lock map with striped locks"
```

---

## Task 5: 失效代际计数器（红-绿）

**Files:**
- Test: `cocache-test/src/main/kotlin/me/ahoo/cache/test/DefaultCoherentCacheSpec.kt`（类末尾追加两个测试）
- Modify: `cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt`

- [ ] **Step 1: 编写两个失败测试（确定性竞态回归）**

在 `DefaultCoherentCacheSpec` 类末尾追加（import 均已存在）。用 latch 精确编排：回源在途 → 注入异构 clientId 失效事件 → 释放回源 → 断言旧值未写入任何一级缓存：

```kotlin
    @Test
    fun `eviction during in-flight load discards stale write-back`() {
        val (key, value) = createCacheEntry()
        val cacheKey = keyConverter.toStringKey(key)
        val staleValue = DefaultCacheValue.forever(value)
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)

        val cache = DefaultCoherentCacheFactory(cacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                clientSideCache = clientSideCache,
                distributedCache = distributedCache,
                cacheSource = object : CacheSource<K, V> {
                    override fun loadCacheValue(key: K): CacheValue<V> {
                        loadStarted.countDown()
                        releaseLoad.await()
                        return staleValue
                    }
                }
            )
        )
        val loaderThread = Thread { cache.getCache(key) }
        try {
            loaderThread.start()
            loadStarted.await(5, TimeUnit.SECONDS).assert().isTrue()

            // 模拟远端实例在回源在途时发布失效事件
            cache.onEvicted(CacheEvictedEvent(cacheName, cacheKey, "remote-client-id"))

            releaseLoad.countDown()
            loaderThread.join(5000)
            loaderThread.isAlive.assert().isFalse()

            clientSideCache.getCache(cacheKey).assert().isNull()
            distributedCache.getCache(cacheKey).assert().isNull()
        } finally {
            cache.close()
        }
    }

    @Test
    fun `eviction during in-flight load discards missing-guard write-back`() {
        val (key, value) = createCacheEntry()
        val cacheKey = keyConverter.toStringKey(key)
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)

        val cache = DefaultCoherentCacheFactory(cacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                clientSideCache = clientSideCache,
                distributedCache = distributedCache,
                cacheSource = object : CacheSource<K, V> {
                    override fun loadCacheValue(key: K): CacheValue<V>? {
                        loadStarted.countDown()
                        releaseLoad.await()
                        return null
                    }
                }
            )
        )
        val loaderThread = Thread { cache.getCache(key) }
        try {
            loaderThread.start()
            loadStarted.await(5, TimeUnit.SECONDS).assert().isTrue()

            cache.onEvicted(CacheEvictedEvent(cacheName, cacheKey, "remote-client-id"))

            releaseLoad.countDown()
            loaderThread.join(5000)
            loaderThread.isAlive.assert().isFalse()

            clientSideCache.getCache(cacheKey).assert().isNull()
            distributedCache.getCache(cacheKey).assert().isNull()
        } finally {
            cache.close()
        }
    }
```

- [ ] **Step 2: 运行测试验证失败（红）**

Run: `./gradlew :cocache-core:test --tests "me.ahoo.cache.consistency.DefaultCoherentCacheTest"`
Expected: 两个新测试 FAIL——未修复代码把旧值/missing guard 写回两级缓存，`getCache(cacheKey)` 非 null。（若其中任一测试意外通过，停下分析时序后再继续。）

- [ ] **Step 3: 实现代际计数器**

修改 `DefaultCoherentCache.kt`：

3a. 在 `private val closed = AtomicBoolean(false)` 之后新增成员：

```kotlin
    /**
     * 回源在途的 per-key 失效代际。仅在回源临界区内存在条目（登记于回源前、移除于临界区 finally），
     * 不随 key 数量累积。onEvicted 收到其它实例事件时自增，使在途回源在写回前后检测到失效。
     */
    private val loadGenerations = ConcurrentHashMap<String, Long>()
```

3b. 用以下实现整体替换 Task 4 Step 3e 中的 `getCache` 临界区主体（`lock.lock()` 至 `lock.unlock()`，保留外层"Fix 缓存击穿"注释）：

```kotlin
        val lock = keyLocks.get(cacheKey)
        lock.lock()
        try {
            getL2Cache(cacheKey)?.let {
                return it
            }

            val generation = loadGenerations.merge(cacheKey, 1L, Long::plus)
            try {
                //region L0:Cache Source
                /*
                 * This is a heavy-duty operation.
                 */
                cacheSource.loadCacheValue(key)?.let { cacheValue ->
                    if (isLoadInvalidated(cacheKey, generation)) {
                        logStaleLoadDiscarded(cacheKey)
                        return cacheValue
                    }
                    setCache(cacheKey, cacheValue)
                    if (isLoadInvalidated(cacheKey, generation)) {
                        logStaleLoadDiscarded(cacheKey)
                        evict(key)
                        return cacheValue
                    }
                    cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
                    return cacheValue
                }

                //endregion
                log.debug {
                    "Cache Name[$cacheName] - ClientId[$clientId] - getCache[$cacheKey] " +
                        "- Set missing guard,because no cache source was found."
                }
                /*
                 *** Fix 缓存穿透 ***
                 * 0. Db 不存在该记录
                 * 1. 穿透到 Db 回源
                 **** 缓存空值 ***
                 */
                if (isLoadInvalidated(cacheKey, generation)) {
                    logStaleLoadDiscarded(cacheKey)
                    return null
                }
                setCache(cacheKey, DefaultCacheValue.missingGuard(ttl, ttlAmplitude))
                if (isLoadInvalidated(cacheKey, generation)) {
                    logStaleLoadDiscarded(cacheKey)
                    evict(key)
                }
                return null
            } finally {
                loadGenerations.remove(cacheKey)
            }
        } finally {
            lock.unlock()
        }
```

3c. 新增两个私有方法（放在 `setCache(cacheKey: String, cacheValue: CacheValue<V>)` 之前）：

```kotlin
    private fun isLoadInvalidated(cacheKey: String, generation: Long): Boolean {
        return loadGenerations[cacheKey] != generation
    }

    private fun logStaleLoadDiscarded(cacheKey: String) {
        log.warn {
            "Cache Name[$cacheName] - ClientId[$clientId] - getCache[$cacheKey] " +
                "- Discard the load result,because it was invalidated by an eviction event during loading."
        }
    }
```

3d. `onEvicted` 在两个早退过滤器（cacheName 不匹配、自发布）之后、`clientSideCache.evict(cacheEvictedEvent.key)` 之前插入代际自增：

```kotlin
        loadGenerations.computeIfPresent(cacheEvictedEvent.key) { _, generation ->
            generation + 1
        }
        clientSideCache.evict(cacheEvictedEvent.key)
```

（自发布事件已在上方被 `publisherId == clientId` 过滤，不会误伤自己的写入——这是代际自增必须放在过滤器之后的原因。）

- [ ] **Step 4: 运行测试验证通过（绿）**

Run: `./gradlew :cocache-core:test --tests "me.ahoo.cache.consistency.DefaultCoherentCacheTest"`
Expected: BUILD SUCCESSFUL，含两个新回归测试与既有 `onEvicted*` 系列全部通过

- [ ] **Step 5: 提交**

```bash
git add cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt cocache-test/src/main/kotlin/me/ahoo/cache/test/DefaultCoherentCacheSpec.kt
git commit -m "fix(core): discard stale write-back when evicted during load"
```

---

## Task 6: FactoryBean DisposableBean（Spring 集成）

**Files:**
- Create: `cocache-spring/src/test/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBeanTest.kt`
- Create: `cocache-spring/src/test/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBeanTest.kt`
- Modify: `cocache-spring/src/main/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBean.kt`
- Modify: `cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBean.kt`

- [ ] **Step 1: 编写失败测试**

创建 `cocache-spring/src/test/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBeanTest.kt`：

```kotlin
package me.ahoo.cache.spring.proxy

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class CacheProxyFactoryBeanTest {
    interface TestCache : Cache<String, String>

    @Test
    fun getObjectCreatesOnceAndDestroyClosesProxy() {
        val closeCount = AtomicInteger(0)
        val proxy = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(TestCache::class.java, CoherentCache::class.java),
        ) { proxyInstance, method, args ->
            when (method.name) {
                "close" -> {
                    closeCount.incrementAndGet()
                    null
                }
                "equals" -> proxyInstance === args?.get(0)
                "hashCode" -> System.identityHashCode(proxyInstance)
                "toString" -> "TestCacheProxy"
                else -> null
            }
        } as TestCache

        val appContext = mockk<ApplicationContext>()
        val cacheProxyFactory = mockk<CacheProxyFactory>()
        every { appContext.getBean(CacheProxyFactory::class.java) } returns cacheProxyFactory
        every { cacheProxyFactory.create<TestCache>(any()) } returns proxy

        val factoryBean = CacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.setApplicationContext(appContext)

        factoryBean.getObject().assert().isSameAs(factoryBean.getObject())
        factoryBean.destroy()

        closeCount.get().assert().isOne()
    }

    @Test
    fun destroyWithoutGetObjectIsNoOp() {
        val factoryBean = CacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.destroy()
    }
}
```

创建 `cocache-spring/src/test/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBeanTest.kt`：

```kotlin
package me.ahoo.cache.spring.join

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.annotation.JoinCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.join.SimpleJoinCache
import me.ahoo.cache.join.proxy.JoinCacheProxyFactory
import me.ahoo.cache.proxy.CacheDelegated
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class JoinCacheProxyFactoryBeanTest {
    interface TestJoinCache : JoinCache<String, String, String, String>

    @Test
    fun destroyClosesComposedCaches() {
        val firstCloseCount = AtomicInteger(0)
        val joinCloseCount = AtomicInteger(0)
        val firstCache = newCountingCacheProxy(firstCloseCount)
        val joinCache = newCountingCacheProxy(joinCloseCount)
        val delegate = SimpleJoinCache(firstCache, joinCache) { _ -> "" }

        val proxy = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(TestJoinCache::class.java, CacheDelegated::class.java),
        ) { proxyInstance, method, args ->
            when (method.name) {
                "getDelegate" -> delegate
                "equals" -> proxyInstance === args?.get(0)
                "hashCode" -> System.identityHashCode(proxyInstance)
                "toString" -> "TestJoinCacheProxy"
                else -> null
            }
        }

        val appContext = mockk<ApplicationContext>()
        val joinCacheProxyFactory = mockk<JoinCacheProxyFactory>()
        every { appContext.getBean(JoinCacheProxyFactory::class.java) } returns joinCacheProxyFactory
        every { joinCacheProxyFactory.create<Any>(any()) } returns proxy

        val factoryBean = JoinCacheProxyFactoryBean(mockk(relaxed = true))
        factoryBean.setApplicationContext(appContext)

        factoryBean.getObject().assert().isSameAs(factoryBean.getObject())
        factoryBean.destroy()

        firstCloseCount.get().assert().isOne()
        joinCloseCount.get().assert().isOne()
    }

    private fun newCountingCacheProxy(closeCount: AtomicInteger): Cache<String, String> {
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Cache::class.java, AutoCloseable::class.java),
        ) { proxyInstance, method, _ ->
            when (method.name) {
                "close" -> {
                    closeCount.incrementAndGet()
                    null
                }
                "equals" -> false
                "hashCode" -> System.identityHashCode(proxyInstance)
                "toString" -> "CountingCacheProxy"
                else -> null
            }
        } as Cache<String, String>
    }
}
```

注意：`joinCacheProxyFactory.create<Any>(any())` 中 `Any` 不满足 `CACHE : JoinCache<*, *, *, *>` 上界约束时，改写为 `joinCacheProxyFactory.create<TestJoinCache>(any()) returns proxy as TestJoinCache`（mockk 按擦除后方法签名匹配，两种写法运行时等价，以能通过编译为准）。

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :cocache-spring:test --tests "me.ahoo.cache.spring.proxy.CacheProxyFactoryBeanTest" --tests "me.ahoo.cache.spring.join.JoinCacheProxyFactoryBeanTest"`
Expected: 编译失败，`destroy()` / `DisposableBean` 未定义

- [ ] **Step 3: 实现 CacheProxyFactoryBean**

整体替换为（新增 import：`org.springframework.beans.factory.DisposableBean`）：

```kotlin
package me.ahoo.cache.spring.proxy

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.proxy.CacheProxyFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.FactoryBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware

class CacheProxyFactoryBean(private val cacheMetadata: CoCacheMetadata) :
    FactoryBean<Cache<Any, Any>>,
    ApplicationContextAware,
    DisposableBean {
    private lateinit var appContext: ApplicationContext
    private var proxy: Cache<Any, Any>? = null
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.appContext = applicationContext
    }

    override fun getObject(): Cache<Any, Any> {
        if (proxy == null) {
            val cacheProxyFactory = appContext.getBean(CacheProxyFactory::class.java)
            proxy = cacheProxyFactory.create(cacheMetadata)
        }
        return requireNotNull(proxy)
    }

    override fun getObjectType(): Class<*> {
        return cacheMetadata.proxyInterface.java
    }

    override fun destroy() {
        (proxy as? AutoCloseable)?.close()
    }
}
```

（代理经 `DefaultCacheProxyFactory` 实现了 `CoherentCache`，故 `is AutoCloseable` 成立，`close()` 由 `CoCacheProxy` 反射转发到 delegate。）

- [ ] **Step 4: 实现 JoinCacheProxyFactoryBean**

整体替换为（新增 import：`org.springframework.beans.factory.DisposableBean`、`me.ahoo.cache.proxy.CacheDelegated`）：

```kotlin
package me.ahoo.cache.spring.join

import me.ahoo.cache.annotation.JoinCacheMetadata
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.join.proxy.JoinCacheProxyFactory
import me.ahoo.cache.proxy.CacheDelegated
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.FactoryBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware

class JoinCacheProxyFactoryBean(private val cacheMetadata: JoinCacheMetadata) :
    FactoryBean<JoinCache<Any, Any, Any, Any>>,
    ApplicationContextAware,
    DisposableBean {
    private lateinit var appContext: ApplicationContext
    private var proxy: JoinCache<Any, Any, Any, Any>? = null
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.appContext = applicationContext
    }

    override fun getObject(): JoinCache<Any, Any, Any, Any> {
        if (proxy == null) {
            val joinCacheProxyFactory = appContext.getBean(JoinCacheProxyFactory::class.java)
            proxy = joinCacheProxyFactory.create(cacheMetadata)
        }
        return requireNotNull(proxy)
    }

    override fun getObjectType(): Class<*> {
        return cacheMetadata.proxyInterface.java
    }

    override fun destroy() {
        val delegate = (proxy as? CacheDelegated<*>)?.delegate
        (delegate as? AutoCloseable)?.close()
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

Run: `./gradlew :cocache-spring:test`
Expected: BUILD SUCCESSFUL（含既有 SpringCacheFactoryTest 等）

- [ ] **Step 6: 提交**

```bash
git add cocache-spring/src/main/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBean.kt cocache-spring/src/main/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBean.kt cocache-spring/src/test/kotlin/me/ahoo/cache/spring/proxy/CacheProxyFactoryBeanTest.kt cocache-spring/src/test/kotlin/me/ahoo/cache/spring/join/JoinCacheProxyFactoryBeanTest.kt
git commit -m "feat(spring): close delegate caches on FactoryBean destroy"
```

---

## Task 7: 全量验收 + 规格文档修正

**Files:**
- Modify: `docs/superpowers/specs/2026-08-14-core-concurrency-fixes-design.md`（影响范围表）

- [ ] **Step 1: 修正规格影响表**

`CoherentCache.kt` 实际位于 cocache-core（非 cocache-api），且本计划不改动 cocache-api 任何文件。将影响范围表第一行：

```markdown
| cocache-api | `CoherentCache.kt` | + `AutoCloseable`、默认 `close()` |
```

改为（并与下一行 core 条目合并）：

```markdown
| cocache-core | `consistency/CoherentCache.kt` | + `AutoCloseable`、默认 `close()` |
```

（若表中出现重复的 cocache-core 行，合并为一行多项即可。）

- [ ] **Step 2: 全量检查**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL（detekt + 全部测试；`cocache-spring-redis`、`cocache-spring-boot-starter` 的集成测试需要本地 Redis——若本地无 Redis，改跑 `./gradlew :cocache-api:test :cocache-core:check :cocache-spring:check :cocache-test:check` 并在提交信息/PR 中注明集成测试待 CI 补跑）

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-08-14-core-concurrency-fixes-design.md
git commit -m "docs(superpowers): correct module in concurrency fixes spec impact table"
```

- [ ] **Step 4: 汇总核对**

对照规格逐项目视确认：
- #1 → Task 4（Striped 锁）
- #2 → Task 1（时钟）
- #3 → Task 5（代际计数器，含 missing guard 防护）
- #4 → Task 2/3/6（close 钩子全链路）
- TCK 五项 → Task 1 金丝雀、Task 2 生命周期、Task 4 击穿加固、Task 5 双竞态回归
- 验收标准 2（回归测试在未修复代码上失败）→ Task 5 Step 2 已验证红
