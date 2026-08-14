# 核心并发修复专项设计（Core Concurrency Fixes）

- **日期**: 2026-08-14
- **状态**: 已确认（设计评审通过）
- **目标版本**: 4.3.x（minor）
- **范围**: cocache-api、cocache-core、cocache-spring、cocache-test

## 背景

2026-08-14 的全面代码审查在核心模块发现 4 项 major 级并发问题，本设计一次性解决它们：

| # | 问题 | 位置 |
|---|------|------|
| 1 | per-key 锁"锁对象回收"竞态，可破坏击穿防护互斥 | `cocache-core/.../consistency/DefaultCoherentCache.kt:78-86, 101-134` |
| 2 | `CacheSecondClock` 先 `start()` 后赋值字段，时钟线程可能启动即 NPE 死亡 | `cocache-core/.../util/CacheSecondClock.kt:33-42, 49` |
| 3 | 失效事件与在途回源之间的旧值回填竞态 | `DefaultCoherentCache.kt:108-116` 与 `165-187` |
| 4 | 事件订阅与 `DistributedCache` 零生命周期管理（生产代码无任何 unregister/close 调用） | `DefaultCoherentCacheFactory.kt:22`、`CacheProxyFactoryBean.kt:31-34` |

### 问题机制简述

**#1 锁回收竞态**：`releaseLock` 在 `finally` 中无条件 `remove`。时序：线程 A 持锁 L1 → 线程 B `computeIfAbsent` 拿到 L1 并阻塞 → A 退出并移除映射 → 线程 C 创建新锁 L2 进入临界区 → B 在 L1 上进入临界区。B、C 在不同锁对象上并发执行，恰好可能并发调用 `cacheSource.loadCacheValue`——正是该锁要防御的重复回源。

**#2 时钟线程竞态**：`startTimer()` 内先 `timer.start()` 再返回，`secondTimer` 字段赋值发生在其后。新线程执行 `run()` 首行 `while (!secondTimer.isInterrupted)` 时字段可能未赋值，Kotlin 非空 val 读取抛 NPE，守护线程静默死亡。后果：`lastTime` 永久冻结，所有缓存条目永不过期。

**#3 旧值回填竞态**：实例 A 持锁慢速回源读到旧数据期间，节点 B 更新 DB 并广播失效；A 的 `onEvicted`（不加锁）evict 本地副本后，回源返回的旧值仍被 `setCache` 写回两级缓存并再次广播——旧值被钉死在分布式缓存直至 TTL 到期。

**#4 生命周期缺失**：`DefaultCoherentCacheFactory.create` 每次创建都向事件总线 `register`，全仓库无 `unregister`；`DistributedCache.close()` 无调用方；两个 `FactoryBean` 的 `getObject()` 每次调用都新建缓存并注册订阅，非单例场景泄漏且永不释放。

## 目标

1. 消除 #1 锁回收竞态：任意时刻同一 key 的回源临界区在单实例内严格互斥。
2. 消除 #2 时钟线程启动竞态。
3. 消除 #3 旧值回填竞态：失效事件到达后，在途回源的旧值不会被写入任何一级缓存。
4. 补齐 #4 生命周期：`CoherentCache` 可关闭（注销订阅 + 关闭分布式缓存），Spring 容器销毁时自动执行。
5. 每项修复配套 TCK 规范增强，并修复现有 flaky 测试。

## 非目标

- 不修改 `CacheEvictedEventBus` 接口与 Redis 失效消息线上格式（`key@@clientId`）。
- 不解决 pub/sub 断线丢消息（属 Redis 可靠性专项；本设计的代际机制为其铺路）。
- 不处理 Redis 故障降级、codec null 归一化、Spring 装配类型查找等审查发现的其它问题。
- 不引入新依赖、不做锁数量可配置化（YAGNI）。

## 兼容性约束

- 允许**向后兼容的新增**：接口新增带默认实现的方法、内部机制重构。
- 已有 API 签名、`CacheEvictedEvent` 字段、Redis 消息格式不变。
- 第三方 `CoherentCache` 实现者无需改动即可编译（继承默认 `close()`）。

## 方案选型

选定 **方案 A：Guava Striped 锁 + 失效代际计数器**。备选：

- **B 引用计数精确锁 + `onEvicted` 抢锁**：互斥语义直接，但自研引用计数是新的竞态面，且 `onEvicted` 会被慢速回源阻塞（SDR 订阅容器线程池共享，阻塞它延迟所有频道）。
- **C Singleflight（per-key Future 共享）**：击穿防护最彻底，但 `getCache` 返回路径重构幅度大、异常传播语义改变，与最小侵入进 minor 的目标不符。

选 A 的理由：无新增自研并发原语（Guava `Striped` 久经考验，Guava 已是 cocache-core 依赖）；失效路径非阻塞；#1 与 #3 解耦清晰；代际计数器为未来版本比对机制铺路。

## 详细设计

### 修复 #2：`CacheSecondClock` 赋值顺序（3 行改动）

`startTimer()` 去掉 `timer.start()`，只创建并返回线程；`init` 中先 `secondTimer = startTimer()` 完成字段赋值，再 `secondTimer.start()`。`Thread.start()` 自带 happens-before 边，保证新线程读到已赋值字段。

同时 `run()` 中 `secondTimer.isInterrupted` 改为 `Thread.currentThread().isInterrupted`，彻底移除 `run()` 对字段的依赖（双保险）。

无行为变化；守护线程、单例枚举语义不变。

### 修复 #1：Striped 锁替换

`DefaultCoherentCache` 删除 `keyLocks: ConcurrentHashMap<String, Any>`、`getLock`、`releaseLock`，替换为：

```kotlin
companion object {
    /**
     * Striped 锁数量。固定不回收，从根上消除锁对象回收竞态；
     * 不同 key 哈希碰撞时共享锁会被串行化，1024 个 stripe 下碰撞概率可忽略。
     */
    private const val KEY_LOCK_STRIPES = 1024
}

private val keyLocks: Striped<Lock> = Striped.lock(KEY_LOCK_STRIPES)
```

`getCache` 临界区由 `synchronized(getLock(cacheKey))` 改为：

```kotlin
val lock = keyLocks.get(cacheKey)
lock.lock()
try {
    // ... 原临界区
} finally {
    lock.unlock()
}
```

内存占用固定（1024 个 `ReentrantLock`，约几十 KB），无逐 key 累积。

### 修复 #3：失效代际计数器

新增成员（`DefaultCoherentCache`）：

```kotlin
/**
 * 回源在途的 per-key 失效代际。仅在回源临界区内存在条目，
 * 由持锁的回源线程登记并在结束时移除，不随 key 数量累积。
 */
private val loadGenerations = ConcurrentHashMap<String, Long>()
```

**`onEvicted` 增加一行**（先于现有 `clientSideCache.evict`）：

```kotlin
loadGenerations.computeIfPresent(cacheEvictedEvent.key) { _, g -> g + 1 }
```

原子自增；仅在回源在途时有条目；**不取锁、不阻塞订阅线程**。

**`getCache` 临界区完整时序**：

```
1. 二次检查 L2（现有逻辑不变）
2. 登记：val gen = loadGenerations.merge(cacheKey, 1L, Long::plus)
3. cacheSource.loadCacheValue(key)            ← 慢速回源
4. 写回前校验：loadGenerations[cacheKey] != gen
   → 丢弃结果：不写任何缓存，直接把源返回值交给调用方，
     log.warn；下一次 getCache 重新回源拿到新值
5. setCache(...) 写两级缓存（现有逻辑）
6. 写回后二次校验：loadGenerations[cacheKey] != gen
   → 补偿 evict(cacheKey)（本地 + 分布式 + 广播），
     抵消"校验与写入之间"窗口内到达的失效事件
7. 清理：loadGenerations.remove(cacheKey)
```

**missing guard 路径同样防护**：步骤 3 返回 null、写 missing guard 之前，执行与步骤 4 相同的校验——否则"DB 先删后增"场景下永久 missing guard（默认 FOREVER TTL）会被钉死。

**正确性论证**（依赖发布方既有语义"先更新 DB、后发事件"）：

- 事件在步骤 2 **之前**到达 → 发布方先更新 DB 后发布，事件到达又先于步骤 2，故回源开始时 DB 已是新数据，无需防护；
- 事件在步骤 2-6 **之间**到达 → 步骤 4（写前）或步骤 6（写后补偿）之一捕获；
- 事件在步骤 6 **之后**到达 → 事件自身的 `clientSideCache.evict` 清掉新写入的值，行为正确。

三个区间全覆盖，无需 `onEvicted` 与回源持锁互斥。

**丢弃语义说明**：步骤 4 丢弃时把源返回值直接交给调用方（不缓存）。理由：这是源的真实返回，调用方获得一次瞬时旧读（与任何并发读等价）；返回 null 会改变调用方语义（代理层会因此执行被注解方法本体），引入更大行为变化。

### 修复 #4：生命周期 close 钩子

**接口层（cocache-api）**：

```kotlin
interface CoherentCache<K, V> : ..., AutoCloseable {
    override fun close() {}   // 空默认实现；-Xjvm-default=all-compatibility 编译为 JVM default method
}
```

第三方实现者继承默认实现，二进制兼容。

**`DefaultCoherentCache.close()`**（`AtomicBoolean` 保证幂等）：

1. `cacheEvictedEventBus.unregister(this)`——Guava/Redis 两个总线的 `unregister` 均以订阅者实例为键，机制现成；
2. `distributedCache.close()`——`DistributedCache` 已继承 `AutoCloseable`。

协作式语义：不中断在途回源（回源自然结束，写回照常；实例已注销，后续事件不再接收，可接受）。`clientSideCache` 无需关闭（Guava/Caffeine/Map 实现均无 close 语义）。

**`SimpleJoinCache`**：实现 `AutoCloseable`，`close()` 关闭其组合的 firstCache 与 secondCache（各自注销自己的订阅）。不改 `JoinCache` 接口。

**Spring 集成（cocache-spring）**：`CacheProxyFactoryBean` 与 `JoinCacheProxyFactoryBean`：

- `getObject()` 的产出存入私有字段（单例 FactoryBean 由容器保证单次创建），同时消除重复创建；
- 实现 `DisposableBean.destroy()`：从代理解出 `CacheDelegated` 的 delegate，`is AutoCloseable` 则 close。

附带收益：example 等手工定义的 `CoherentCache` Bean，Spring destroy 方法推断（名为 `close`）自动调用，无需额外配置。

## TCK 增强（cocache-test）

1. **修复现有缺陷**（`DefaultCoherentCacheSpec.kt:155-196`）：线程池 try/finally `shutdown()`；`finishLatch.await(5s)` 返回值必须校验，超时即 fail（消除假通过）。
2. **击穿互斥断言**：并发 N 线程 barrier 后同时 miss 同一 key，`AtomicInteger` 计数 `CacheSource` 调用次数，断言恰好 1 次。
3. **失效-回源竞态回归**（新增）：受控 `CacheSource` 阻塞在 latch 上；回源在途时注入异构 clientId 的 `onEvicted`；释放 latch 后源返回旧值；断言旧值未留在 clientSideCache / distributedCache。全程 latch 编排，不使用 sleep。
4. **生命周期测试**（新增）：close() 后 publish 事件，断言本地缓存不再被 evict；close 幂等（二次不抛异常）；`distributedCache.close` 被调用（Mock 计数）。
5. **时钟回归**：断言 `CacheSecondClock.currentTime()` 在短暂等待后前进。

## 错误处理与边界情况

- **close 与在途回源并发**：协作式，回源自然完成；close 后到达的事件被忽略（缓存即将废弃）。
- **多实例同名缓存**（example 场景）：`unregister(this)` 按实例注销，不影响其它实例。
- **步骤 6 补偿 evict 产生的额外事件**：evict 语义幂等，多一次广播无害。
- **代际条目泄漏**：条目生命周期严格限定在临界区内（登记于步骤 2、移除于步骤 7），异常路径由 try/finally 保证移除。
- **close() 异常**：unregister 或 distributedCache.close 抛异常时记录日志并继续，保证 destroy 流程完整。

## 影响范围

| 模块 | 文件 | 变更 |
|---|---|---|
| cocache-api | `CoherentCache.kt` | + `AutoCloseable`、默认 `close()` |
| cocache-core | `consistency/DefaultCoherentCache.kt` | Striped 锁、代际计数器、`close()` |
| cocache-core | `util/CacheSecondClock.kt` | 赋值/启动顺序修复 |
| cocache-core | `join/SimpleJoinCache.kt` | 实现 `AutoCloseable` |
| cocache-spring | `proxy/CacheProxyFactoryBean.kt`、`join/JoinCacheProxyFactoryBean.kt` | 持有产出 + `DisposableBean` |
| cocache-test | `DefaultCoherentCacheSpec.kt` | 修复 flaky + 新增 3 项测试 |

不改动：`CacheEvictedEventBus` 接口、事件数据类、Redis 模块任何文件、失效消息格式。无新增依赖。

## 验收标准

1. `./gradlew check` 全绿（含 detekt、全部 TCK 参数化运行）。
2. 新增测试在修复前失败、修复后通过（竞态回归测试须能在未修复代码上复现问题）。
3. 二进制兼容性：`CoherentCache` 接口变更对既有实现者源码与二进制兼容。

## 开放问题

无。
