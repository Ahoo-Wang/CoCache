# RFC 0004：异步 API

- 状态：**已批准（2026-10-19，维护者）**，待实施（§13）。范围按全文确定：异步读与写、`AsyncSetCache`、`AsyncInvalidationChannel`、A1–A14。本 RFC 就是 AGENTS.md 要求的“先问再改”：它修改 `cocache-api`（新增用户 API 与 SPI 接口、`Cache` / `SetCache` 各加一个 default 方法），并为可选模块 `cocache-coroutines` 引入新依赖 `kotlinx-coroutines-core`（§7，**已批准**，只用于该模块）。**不改线格式**，Lua 脚本不变。审阅中的修改：默认异步执行器由 `commonPool` 改为运行时自建的池（§5.1、§12 Q2）；补充同步与异步混合合并时不变量的论证（§5.4）；验收中写明同步命中不退化与 lease 等待的线程数测试（§10.1、§11）
- 前提：6.x 小版本，**只做增量**。同步 API、同步 SPI、同步命中路径的行为与性能不变；所有新增接口方法都是 default 方法（`-jvm-default=enable`），对已有第三方实现二进制兼容。
- 关系：兑现 [RFC 0003](0003-target-architecture.md) §8 Q8 的预留（当时的名字是 `AsyncCoCache`，本文改为 `AsyncCache`，与 `Cache` 对应）。规范以 [architecture.md](../architecture.md) 为准；本 RFC 实施时同步修改其 §1 非目标、§5 线程模型、§9.5 与 §14 不变量（§9）。
- 触发条件：维护者已决定需要异步 API（Q8 的触发条件之一：reactive 用户的需求）。

---

## 0. 结论摘要

| # | 决定 | 一句话理由 |
|---|------|-----------|
| A1 | **`Cache.async()` 返回同一个引擎上的 `AsyncCache<K, V>` 视图**；`SetCache.async()` 返回 `AsyncSetCache<K, E>`。视图与同步 API 共享 L2、失效戳、SingleFlight、lease、指标 | L2 栅栏是进程内的，只有同一组戳与同一个 SingleFlight 才能让同步写与异步读互相栅栏。另建一个异步引擎会出现两份 L2，违反 G2 |
| A2 | **方法名与 `Cache` 相同**（`get`、`getAll`、`lookup`、`put`、`putAll`、`invalidate`、`invalidateAll`），返回 `CompletableFuture`；**不在 `Cache` 上加 `getAsync` 一类方法** | `Cache` 只多一个方法，接口式声明与代理几乎不变；与 Caffeine 的 `Cache` / `AsyncCache` 心智一致 |
| A3 | **读写都有异步版本** | 写入也是一次 L1 往返；reactive 调用方在事件循环上调用同步 `invalidate` 同样会阻塞 |
| A4 | **`AsyncCacheLoader<K, V>`**（`loadAsync`、`loadAllAsync`，返回 `CompletionStage`）。一个 spec 至多有**一个**数据源，同步或异步二选一，引擎在两个方向上自动适配 | 同一个缓存有两份可能不一致的加载逻辑没有意义；适配是确定的：同步路径等待异步 loader，异步路径把同步 loader 放到阻塞执行器上 |
| A5 | **SPI：新增子接口 `AsyncRemoteStore : RemoteStore`**（以及 `AsyncInvalidationChannel`），**不给 `RemoteStore` 加 default 异步方法**。后端不实现子接口时，引擎把同步调用放到阻塞执行器上 | default 方法拿不到执行器：只能在调用线程上同步执行（阻塞异步线程）或偷用公共线程池。由引擎决定在哪里阻塞，第三方后端无需改动且行为正确 |
| A6 | **异步 SingleFlight 与同步共用一张表**：先注册、后取戳；**先注销、再发布**；读己之写至多重试一次；每个调用方拿到共享结果的**副本** | 同步与异步对同一 key 的并发未命中只回源一次；副本让取消只影响取消者 |
| A7 | **取消只作用于调用方自己的 future**：不取消共享加载，不取消 loader 返回的 future，lease 照常 fill 或释放 | 共享加载属于所有等待者；中途放弃只会让其它等待者失败、让 lease 白等 |
| A8 | **异常透明**：返回的 future 以**原始异常**异常完成（`CacheReadException`、`CacheWriteException`、loader 的异常、`SourceLoadRejectedException` 等），不以 `CompletionException` 包装；异步方法**从不同步抛出** | 与同步 API、代理“原样抛出”的不变量一致。JDK 的 `join()` / `get()` 自己会包装，这是 JDK 行为；`handle`、`exceptionally`、协程 `await()` 看到的都是原始异常 |
| A9 | **线程模型**：L2 命中在调用方线程上返回已完成的 future；其余情况 future 在**异步执行器**上完成（默认是运行时自建的 `cocache-async-*` 池，不是 `commonPool`；同步 loader 从不在该池上执行，而是在单独的阻塞执行器上）。L1 I/O 与用户 loader 的 future 完成后先切换到异步执行器，再运行任何引擎或用户代码，**Lettuce 事件循环上什么都不执行**。等待 lease、等待回源许可都用定时器调度重读，**不挂起任何线程** | 解码（用户 `Codec`）在事件循环上执行会拖慢共享连接上的全部命令；挂起线程等待 lease 正是异步 API 要消除的东西 |
| A10 | **命中路径**：同步 L2 命中的代码不改；异步 L2 命中 = 同一次 Caffeine 查找 + 一次 `completedFuture` 分配。验收：异步 L2 命中吞吐 ≥ 同轮同步 L2 命中的 80%（1 线程与 8 线程）。达不到时启用 §5.8 的备选（L2 条目缓存一个不可变的已完成 future） | 一次逃逸分配是 `CompletableFuture` 返回类型的下限；预算写死，备选方案事先设计好 |
| A11 | **Spring**：`retrieve(key)` / `retrieve(key, loader)` 改走异步视图，不再占用 retrieve 执行器线程；`cocache.spring-cache.retrieve-executor` 在 6.3 弃用（设置时告警，不再使用） | 消除“每个等待中的调用占一个线程”和嵌套调用的死锁条件（architecture.md §9.5） |
| A12 | **协程**：可选模块 `cocache-coroutines`（6.4），`cache.suspending()` 视图与 `SuspendingCacheLoader`。`kotlinx-coroutines-core` 只出现在该模块中 | api 仍零依赖；Java 用户不受影响 |
| A13 | **异步 loader 有超时**：`LoadPolicy.asyncTimeout`（默认 30 s）。超时后共享调用以 `SourceLoadTimeoutException` 失败并释放 lease | 一个永不完成的 future 会让该 key 在本节点上永远合并到一个不会结束的调用；同步路径至少还会占着一个看得见的线程 |
| A14 | **发布顺序**：6.3 交付 api + core + Redis + 内存后端 + TCK + Spring；6.4 交付协程模块（依赖已获批） | 协程只是薄封装，不应阻塞核心交付；依赖审批独立进行 |

---

## 1. 背景与目标

### 1.1 为什么现在做

RFC 0003 Q8 推迟异步 API 的理由仍然成立：L2 命中是纳秒级、没有 I/O；阻塞 loader 的高并发由虚拟线程解决。但在 WebFlux / R2DBC / WebClient 这类栈上，同步 API 有三个实际问题：

1. **未命中阻塞事件循环**。L1 读取（1 RTT）、等待其它节点的 lease（最多 `lease.wait`，默认 200 ms，期间 `parkNanos`）、回源都发生在调用线程上。在 Netty 事件循环上调用 `cache.get(k)`，一次未命中就阻塞该循环上的全部连接。
2. **异步 loader 只能被同步等待**。Spring 的 `retrieve(key, loader)` 今天在 retrieve 执行器上 `join()` 异步 loader（architecture.md §9.5），每个等待中的调用占一个线程；默认执行器在 JDK 17 上是最多 512 线程的池，嵌套调用依赖“不排队”才不死锁。
3. **Redis 后端本来就是异步的**。`RedisRemoteStore` 的每个操作都是 `evalAsync` + `connections.await`，等待只是为了适配同步 SPI。

### 1.2 目标与非目标

目标不变（G1–G3），本 RFC 要求异步路径**逐条**满足：

| # | 异步路径上的表述 |
|---|------------------|
| G1 | 与同步路径相同的上界；异步切换线程引入的延迟只推迟“L2 何时被填充”，不延长任何副本的寿命 |
| G2 | 与同步路径相同：异步填充同样持有读取之前取得的令牌（lease、刷新令牌、失效戳）；异步写入在修改 L1 之前和之后各递增一次戳并淘汰 L2，且第二次递增发生在返回的 future 完成**之前** |
| G3 | 同步 L2 命中不退化（噪声内）；异步 L2 命中 ≥ 同步的 80%；异步 L1 命中一次往返加一次线程切换；全集群同一 key 并发未命中（不论同步或异步调用）只回源一次 |
| 新 G4 | **不挂起线程**：异步路径上没有 `park`、`join`、`get`、`Semaphore.tryAcquire(timeout)`。唯一的例外是“同步数据源 + 异步调用”，同步 loader 在阻塞执行器上运行（§5.2） |

非目标：

- Reactor 模块（`Mono` / `Flux`）。`Mono.fromFuture(cache.async().get(k))` 已经足够，且其取消（dispose 时取消 future）在 A7 下是安全的。
- `JoinCache` 的异步视图（6.3 不做，见 §12 Q5）。
- 让 `@CoCache` 接口继承异步接口（§3.4）。
- 异步的失效重试队列。`InvalidationRetrier` 本来就在自己的调度线程上运行，与调用方无关。

---

## 2. 现状要点（决定设计的事实）

| 事实 | 位置 | 对设计的约束 |
|------|------|--------------|
| SingleFlight 的 `Call` 已经持有 `CompletableFuture`，等待者 `join()` | `internal/SingleFlight.kt` | 异步版本只需把“leader 同步执行 block”换成“block 返回 future、完成时先注销再发布”，表可以共用 |
| 同线程重入检测靠 `Call.owner === currentThread` | 同上 | 异步调用没有“拥有线程”，需要另一种检测（§5.4） |
| 等待 lease：`LockSupport.parkNanos` 退避 2→50 ms，至多 `min(remaining, lease.wait)` | `CoherentEngine.awaitLease` | 改为定时器调度重读，沿用同一个戳与同一个令牌 |
| 回源许可：`Semaphore.tryAcquire(acquireTimeout)` | `CoherentEngine.guarded` | 阻塞；异步路径改为非阻塞尝试 + 定时器重试 |
| 写入顺序：戳 → 淘汰 → `mutate` → （非原子时 `publish`）→ `finally` 戳 → 淘汰 | `AbstractEngine.write` | 异步版本把 `finally` 挂在 L1 future 的完成上，并在用户 future 完成之前执行 |
| Redis 的每个操作都是 `evalAsync` 后同步 `await(commandTimeout)`，超时时取消命令 future | `RedisRemoteStore`、`RedisConnections.await` | 原生异步实现只是去掉 `await`，并以 `orTimeout` 保持相同的超时与异常类型 |
| Lettuce 在事件循环线程上完成 future | Lettuce 默认行为 | 每个 L1 完成之后必须切换线程（A9） |
| `LoadObserver.observe(…, Supplier<T>): T` 同步包住回源 | `core/LoadObserver.kt` | 需要一个能跨越异步边界的变体（§8.1） |
| Spring `retrieve` 在 retrieve 执行器上阻塞 | `CoCacheSpringCache.retrieve` | 改走异步视图后该执行器不再需要 |

---

## 3. 用户 API（`cocache-api`）

### 3.1 接口

```kotlin
interface Cache<K : Any, V : Any> {
    // …现有方法不变…

    /**
     * 同一个缓存的异步视图：共享 L2、失效戳、在途加载与 lease。
     * 内置引擎与接口式声明的代理都支持；其它实现默认抛出 UnsupportedOperationException。
     */
    fun async(): AsyncCache<K, V> = throw UnsupportedOperationException("${javaClass.name} does not support async().")
}

interface SetCache<K : Any, E : Any> : Cache<K, Set<E>> {
    override fun async(): AsyncSetCache<K, E> = throw UnsupportedOperationException(…)
}

/**
 * 异步视图。所有方法都不同步抛出：失败以原始异常（不包装为 CompletionException）异常完成返回的 future。
 * L2 命中时返回已完成的 future（在调用方线程上）；否则 future 在异步执行器上完成，从不在 L1 客户端的 I/O 线程上完成。
 * 每次调用返回一个新的 future：取消它只影响本次调用，不影响共享的加载。
 */
interface AsyncCache<K : Any, V : Any> {
    val name: String
    fun get(key: K): CompletableFuture<V?>
    fun get(key: K, loader: AsyncCacheLoader<K, V>): CompletableFuture<V?>
    fun getAll(keys: Collection<K>): CompletableFuture<Map<K, V>>
    fun lookup(key: K): CompletableFuture<Lookup<V>>
    fun put(key: K, value: V): CompletableFuture<Void?>
    fun put(key: K, value: V, ttl: Duration): CompletableFuture<Void?>
    fun putAll(entries: Map<K, V>): CompletableFuture<Void?>
    fun putAll(entries: Map<K, V>, ttl: Duration): CompletableFuture<Void?>
    fun invalidate(key: K): CompletableFuture<Void?>
    fun invalidateAll(keys: Collection<K>): CompletableFuture<Void?>
    /** 回到同步 API（同一个引擎）。 */
    fun sync(): Cache<K, V>
}

interface AsyncSetCache<K : Any, E : Any> : AsyncCache<K, Set<E>> {
    // get 总以非 null 的集合完成（不存在为空集合）；泛型不变，类型上仍是 CompletableFuture<Set<E>?>，由 KDoc 约定
    fun add(key: K, elements: Collection<E>): CompletableFuture<Void?>
    fun remove(key: K, elements: Collection<E>): CompletableFuture<Void?>
    fun clear(key: K): CompletableFuture<Void?>
    override fun sync(): SetCache<K, E>
}

/**
 * 异步数据源。loadAsync 必须很快返回，不得阻塞（它在异步执行器上被调用）；
 * 同步抛出的异常与异常完成的 future 等价。返回的 future 可以在任何线程上完成，引擎会切换回异步执行器。
 */
fun interface AsyncCacheLoader<K : Any, V : Any> {
    fun loadAsync(key: K): CompletionStage<out V?>
    /** 默认对每个 key 调用 loadAsync 并合并；数据源应覆盖为一次批量查询。结果中缺少的 key 视为不存在。 */
    fun loadAllAsync(keys: Collection<K>): CompletionStage<out Map<K, V>> = …
}
```

要点：

- **返回 `CompletableFuture`，接受 `CompletionStage`**。返回具体类型对 Java 最直接（`join`、`orTimeout`、Spring 与 Reactor 的适配都以它为参数）；loader 接受最宽的 `CompletionStage`，R2DBC / `Mono.toFuture()` / `minimalCompletionStage()` 都能直接返回。
- **Java 友好**：没有挂起函数、没有 Kotlin 专有类型；`V?` 擦除为 `V`；写入返回 `CompletableFuture<Void>`。`AsyncCacheLoader` 是 `fun interface`，Java 用 lambda 实现。
- **`async()` 是 default 方法**：已有的第三方 `Cache` 实现（测试替身等）二进制兼容；调用时得到明确的 `UnsupportedOperationException`。不提供“用公共线程池包装同步实现”的默认值：那会悄悄阻塞别人的线程池。
- **视图是无状态的**：引擎持有唯一的视图实例，`async()` 每次返回同一个对象，`async().sync()` 返回引擎本身（接口式声明时也返回引擎而不是代理，所以用户接口上的 default 方法要从原来的代理调用）。
- 读写语义（栅栏、失败模式、负缓存、`SetCache` 的规则）与同名的同步方法**完全相同**，KDoc 引用同步方法而不是重复。

### 3.2 数据源的声明与共存

```kotlin
cacheSpec<String, User>("user") {
    codec = jacksonCodec<User>(mapper)
    asyncLoader = AsyncCacheLoader { id -> userRepository.findById(id).toFuture() }   // 或 loader = CacheLoader { … }
}
```

- `CacheSpec` 新增 `asyncLoader: AsyncCacheLoader<K, V>?`。`loader` 与 `asyncLoader` **至多设置一个**，构造时校验，否则失败。
- 适配规则（引擎内部，用户不可见）：

| spec 的数据源 | 同步调用（`get`） | 异步调用（`async().get`） |
|---------------|-------------------|---------------------------|
| `loader`（同步） | 现状：调用方线程上执行 | 在**阻塞执行器**上执行 `load`（§5.2），不占异步执行器 |
| `asyncLoader` | 在调用方线程上调用 `loadAsync` 并等待（受 `asyncTimeout` 约束），异常解包 | 在异步执行器上调用 `loadAsync`，不等待 |

- `get(key, loader)` 的调用方 loader 与 spec 的数据源互不影响，与现状一致：同步视图收 `CacheLoader`，异步视图收 `AsyncCacheLoader`。
- **线程上下文**：同步 loader 在同步路径上仍然在调用方线程上执行（不变量 #6 不变）。异步路径上没有“调用方线程”可言：loader（无论哪种）看不到调用方的 ThreadLocal。需要上下文的应用使用 Micrometer context-propagation、Reactor Context，或协程模块（§7，它把调用方的协程上下文带给调用级 loader）。

### 3.3 写入

- 异步写入与同步写入走同一个写入方顺序（architecture.md §4.2），区别只在等待 L1 的方式（§5.6）。
- `put` / `putAll` / `add` / `remove` / `clear` 失败：future 以 `CacheWriteException` 异常完成，本地效果（两次递增戳、淘汰 L2）已生效。
- `invalidate` / `invalidateAll`：默认 `RETRY` 时 L1 失败也正常完成（已登记重试）；`THROW` 时以 `CacheWriteException` 异常完成。
- **读己之写**：`put(k, v).thenCompose { get(k) }` 一定读到 `v` 或更新的值，因为第二次递增与淘汰发生在 put 的 future 完成之前。不等待写入 future 就发起的读取与写入是并发关系，没有顺序保证（与两个线程并发调用同步 API 相同）。

### 3.4 接口式声明与 Spring 注入

- **不支持 `@CoCache interface X : AsyncCache<K, V>`**，也不为每个缓存额外注册一个 `AsyncCache` bean。用户注入现有的 `Cache` bean（接口式或 spec bean），调用 `.async()`。
  - 理由：`Cache` 与 `AsyncCache` 的方法同名、返回类型不同，一个接口不能同时继承两者；单独的异步接口声明会让同一个缓存名对应两种 bean，注册、AOT 提示（architecture.md §9.6）、`SpringCacheSyncCheck` 都要成倍处理，换来的只是省掉一次 `.async()` 调用。
- 代理（`CacheProxies`）新增按方法名分派 `"async" -> target.async()`。返回的视图不经代理（视图的方法不会被用户接口重写），异常仍原样传递。
- 接口式声明的数据源解析（architecture.md §9.3）扩展为：`@CoCache(loader = "beanName")` 指向 `CacheLoader` 或 `AsyncCacheLoader` 均可；按类型匹配时先找 `CacheLoader<K, V>`，再找 `AsyncCacheLoader<K, V>`，**两种都有唯一候选时启动失败**（需要用 `loader = …` 指明）。
- AOT：`CoCacheRuntimeHints` 增加 `AsyncCache` / `AsyncSetCache` 的公共方法（视图本身不是代理，不需要代理提示）。

---

## 4. SPI（`cocache-api` 的 `spi` 包）

### 4.1 决定：子接口 `AsyncRemoteStore`

```kotlin
/**
 * 原生异步的 L1 协议：语义与 RemoteStore 的同名方法逐一相同（原子性、到期时间重设、广播、令牌）。
 * 返回的 future 可以在 I/O 线程上完成（引擎会切换线程）；失败时以与同步方法相同的异常类型异常完成，不得同步抛出。
 * 实现必须自行限定每个操作的耗时（如命令超时），不得返回永不完成的 future。
 */
interface AsyncRemoteStore : RemoteStore {
    fun readAsync(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead>
    fun readAllAsync(keys: Collection<String>, leases: Map<String, LeaseRequest>): CompletableFuture<Map<String, RemoteRead>>
    fun fillAsync(fill: Fill): CompletableFuture<Boolean>
    fun fillAllAsync(fills: List<Fill>): CompletableFuture<List<Boolean>>
    fun releaseAsync(key: String, token: Token): CompletableFuture<Void?>
    fun putAsync(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?>
    fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?>
    fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?>
    fun reclaimAsync(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean>
    fun addMembersAsync(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?>
    fun removeMembersAsync(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?>
}

/** 非原子广播（atomicBroadcast = false）时引擎发布帧的异步版本。 */
interface AsyncInvalidationChannel : InvalidationChannel {
    fun publishAsync(namespace: String, frame: ByteArray): CompletableFuture<Void?>
}
```

- `CoherenceBackend.store(namespace)` 的签名不变；引擎在创建缓存时检查 `store is AsyncRemoteStore`（一次，结果存为字段），不是则用内部适配器 `BlockingStoreAdapter` 把同步调用提交到**阻塞执行器**。通道同理。
- 子接口的方法**没有** default 实现：实现了子接口就必须原生实现全部方法，不会出现“半原生半阻塞”的后端。
- 内置后端：`RedisRemoteStore` 原生实现（去掉 `await`，见 §4.3）；`InMemoryBackend` 原生实现（内存操作本来就不阻塞，返回已完成的 future）；`HookedBackend` 在被包装的 store 是 `AsyncRemoteStore` 时也实现它，并对异步方法提供与同步方法相同的钩子（§10.1）。

### 4.2 被否决：给 `RemoteStore` 加 default 异步方法

| 方案 | 否决理由 |
|------|----------|
| default 方法在调用线程上执行同步方法，返回已完成的 future | 对做 I/O 的第三方后端，等于在异步执行器（或用户的事件循环）上阻塞；而且从类型上看不出该后端是否真的异步，引擎无法选择在哪里阻塞 |
| default 方法用 `ForkJoinPool.commonPool()` 执行同步方法 | 在公共池上阻塞 I/O；执行器不可配置、不受运行时管理 |
| default 方法多一个 `Executor` 参数 | 把引擎的线程策略泄漏进 SPI；原生实现会忽略它，参数只为默认实现存在 |
| 用反射判断第三方是否重写了 default 方法 | 隐式、脆弱（部分重写时语义不清） |

子接口把“是否原生异步”变成一个类型事实：第三方后端零改动、行为正确（只是多一次线程切换）；想要原生异步的后端实现子接口并通过异步 TCK。

### 4.3 Redis 后端

- 每个 `xxxAsync` 是现有实现去掉 `connections.await(…)`：`readAsync` 直接返回 `evalAsync(READ…).thenApply(::decodeRead)`；`readAllAsync` 以 `allOf` 合并，任一失败时与同步版本一样先尽力释放其余已取得的 lease，再以第一个异常完成；`putAllAsync` / `invalidateAsync` 合并各 slot 组的 future。
- **超时与异常类型与同步路径一致**：每个 future 加 `orTimeout(commandTimeout)`；超时时取消底层命令 future（与 `await` 的 `future.cancel(false)` 相同），并转换为同样的 `RedisException("Command timed out after …")`；`ExecutionException` / `CompletionException` 解包为原始的运行时异常。这样 `readFailure`、`invalidateFailure` 的判定在同步与异步路径上完全相同。
- 同步方法改为 `connections.await(xxxAsync(…))`：同步与异步只有一份协议实现。
- `NOSCRIPT` 回退（`evalAsync` 的 `exceptionallyCompose`）本来就是异步的，不变。
- 断线时拒绝命令（`REJECT_COMMANDS`）不变：被拒绝的命令立即以异常完成。

### 4.4 对第三方后端与 TCK 的影响

- 已有第三方后端：**无需任何改动**，`EngineSpec` 照常通过；新的 `AsyncEngineSpec`（§10）也会通过（异步调用经阻塞适配器走同步 SPI）。
- 想原生异步的后端：实现 `AsyncRemoteStore`，并继承 `AsyncBackendProtocolSpec`（对异步方法重跑协议用例，另加“同步与异步交替调用同一个 key 结果一致”与“不得同步抛出”的用例）。

---

## 5. 引擎

### 5.1 执行器与定时器

| 组件 | 用途 | 默认 | 配置 |
|------|------|------|------|
| **异步执行器** `asyncExecutor` | 所有异步续接：解码、L2 填充、发出下一条 L1 命令、调用 `loadAsync`、完成返回的 future | 运行时自建的 `ForkJoinPool`：并行度 = CPU 数，守护线程，命名 `cocache-async-N`，随运行时关闭。**只执行短小的非阻塞工作；同步 loader 与同步 SPI 调用从不在这里执行** | `CacheRuntime.Builder.asyncExecutor`；starter `cocache.async-executor`（bean 名，与 6.1 的执行器同一规则） |
| **阻塞执行器** `blockingExecutor` | 异步路径上的同步 loader；非原生异步后端的同步 SPI 调用 | JDK 21+：每任务一个虚拟线程；JDK 17：不排队、按需增长的守护线程池（≤ 512 线程，超出时由调用方执行）。即现在 Spring retrieve 执行器的默认值，移入 core 复用 | `CacheRuntime.Builder.blockingExecutor`；starter `cocache.blocking-executor`（bean 名） |
| **定时器** `cocache-timer` | 等待 lease 的退避重读、等待回源许可的重试、`asyncTimeout` | 运行时自建的单线程守护 `ScheduledExecutorService`。**定时任务只把工作提交给异步执行器**，自己不执行任何引擎或用户代码 | 不可配置 |

- **为什么不用 `commonPool`**（审阅决定，见 §12 Q2）：它与应用的并行流、其它库共用，被占满时所有缓存的完成都会停顿，而且从线程转储上看不出原因。自建池的线程名 `cocache-async-*` 在转储与 profiler 中一眼可辨，生命周期与运行时一致。
- **为什么仍是 `ForkJoinPool`**：它的工作线程在 `CompletableFuture.join()` 上阻塞时会经 `ManagedBlocker` 补偿线程。用户若在异步续接里调用了同步 API（不推荐），池不会因此被占满而死锁。替换为固定大小的普通线程池时没有这个保证，文档中写明。
- **两个执行器分工固定**：异步执行器上只运行引擎续接、解码、L2 填充、`loadAsync` 的同步部分与用户 future 的完成；同步 `CacheLoader.load` / `loadAll` 与非原生异步后端的同步 SPI 调用**只在阻塞执行器上执行**。`AsyncEngineSpec` 断言这一点（§10.1）。
- 不复用 `cocache-retry` 调度线程：重试任务在该线程上同步调用 `store.invalidate`，最长阻塞 `commandTimeout`，会推迟 lease 重读。
- 与现有配置项相同，执行器只按 bean 名解析，用户提供的执行器不随运行时关闭（architecture.md §9.2）；运行时自建的异步池、阻塞池（JDK 17 的平台线程池）与定时器在 `CacheRuntime.close()` 中最后关闭。
- **运行时关闭**：定时器先停止；被取消的定时任务所属的 future 以 `IllegalStateException("CacheRuntime closed")` 异常完成，并尽力释放已取得的 lease；在途的 L1 future 照常完成。

### 5.2 读路径

```
async.get(key):                                   // 与 entryOf 相同的命中判断；同步 get 的代码不改
  k = storageKey(key)
  e = local.getIfPresent(k)
  if e != null && e.expiresAt > clock.nowMillis():
      if e.refreshAt <= now: refreshAsync(key, k)  // 与同步路径共用，§5.7
      return completedFuture(e.value)
  return missAsync(key, k, source).thenApply(LocalEntry::value)

missAsync(key, k, source):
  if keyFilter?.notExist(k): return completed(负缓存)
  observed = stamps.current(k)
  return flights.executeAsync(k) { loadThroughAsync(key, k, source) }
      .thenCompose { r ->
          if r.stamp >= observed && usable(r): r
          else flights.executeAsync(k) { loadThroughAsync(…) }     // 读己之写，至多重试一次；之后同 loadMiss 的第三步
      }

loadThroughAsync(key, k, source):                 // 由 executeAsync 在注册之后调用（调用方线程或异步执行器）
  stamp = stamps.current(k)                       // 注册之后取戳；整个调用只取这一次
  if pendingInvalidations.contains(k): return bypassAsync(key, k, stamp, source)
  lease = LeaseRequest(nextToken(), lease.ttl)
  readWaitingLeaseAsync(k, lease)
    ⇒(异步执行器) Hit    → 格式检查 / 解码；失败 → onCorruptAsync（reclaimAsync）；成功 → fillLocal(k, entry, stamp)
                 Miss   → loadAndFillAsync(key, k, stamp, lease.token if granted, source)
                 Leased → metrics.leaseWaitTimeout(); bypassAsync
                 L1 异常 → THROW：以 CacheReadException 失败；DEGRADE：bypassAsync

readWaitingLeaseAsync(k, lease):
  store.readAsync(k, lease) ⇒ 若 Leased：deadline = now + min(remaining, lease.wait)；backoff = 2 ms
     loop: timer.schedule(min(backoff, deadline - now)) { asyncExecutor: store.readAsync(k, lease) } ⇒ 仍 Leased 且未超时则 backoff = min(2×, 50 ms) 继续
  // 与同步 awaitLease 相同的退避与截止时间；沿用同一个令牌与同一个戳；没有线程挂起

loadAndFillAsync(key, k, stamp, token, source):
  acquirePermitAsync()                            // §5.3
  ⇒ invokeLoader(source, key)                     // §5.2.1
  ⇒ 释放许可（恰好一次）；失败或超时 → token?.let { releaseAsync } 完成之后再以原始异常失败
  ⇒ ttlOf、encode（抛出 → releaseAsync 后失败）
  ⇒ token != null 时 fillAsync（异常按 fill 失败降级）
  ⇒ fillLocal(k, entry, stamp)，限龄按是否 fill 成功选择 localMaxAge / degradedLocalMaxAge
```

`⇒` 表示“上一个 future 完成后，在异步执行器上继续”（`thenComposeAsync(…, asyncExecutor)` 一类）。**返回给用户的 future 由引擎新建，并以 `complete` / `completeExceptionally(原始异常)` 显式完成**，不直接返回 `thenApply` / `thenCompose` 链的末端：JDK 的依赖阶段会把上游异常包装为 `CompletionException`，直接返回会违反 A8。同步路径中的每个分支（无数据源、未知格式、损坏回收、失效重试中、等待超时、fill 被拒绝）在异步路径上都有一一对应的分支，指标计数与同步路径相同。实现上协议决策（“读到什么就做什么”）抽成同步与异步共用的纯函数，避免两份逻辑分叉。

#### 5.2.1 调用 loader

| 数据源 | 调用方式 |
|--------|----------|
| `AsyncCacheLoader` | 在异步执行器上、**在重入守卫内**（§5.4）调用 `loadAsync(key)`；同步抛出视为失败；返回值 `toCompletableFuture()` 后取**副本**再加 `orTimeout(asyncTimeout)`（`orTimeout` 会修改 future 本身，不能作用于 loader 的 future） |
| 同步 `CacheLoader` | `supplyAsync({ 在重入守卫内 load(key) }, blockingExecutor)`；不加超时（与同步路径一样，阻塞的 loader 无法被打断） |

超时：共享调用以 `SourceLoadTimeoutException`（core，新增，与 `SourceLoadRejectedException` 同级）失败并释放 lease；loader 的 future 不被取消，迟到的结果被丢弃；计入新指标 `cocache.load.timeouts`。

### 5.3 回源许可（bulkhead）

- 同步与异步共用同一个 `Semaphore`，许可数的语义不变（只计 leader，批量占一个）。
- 异步获取：先 `tryAcquire()`；失败时按 lease 等待相同的退避（2→50 ms）经定时器重试，直到 `acquireTimeout`，之后以 `SourceLoadRejectedException` 失败（释放 lease）。轮询对异步等待者不公平（同步等待者可能先拿到），但等待时长有界，而且不挂起线程。
- 许可在 loader 的 future 完成（或超时）时释放，恰好一次。

### 5.4 SingleFlight

同步与异步共用一张表 `ConcurrentHashMap<K, Call<R>>`；`Call` 增加“是否异步”的标记，异步调用没有 `owner` 线程。

```
executeAsync(key, block: () -> CompletableFuture<R>): CompletableFuture<R>
  checkNotReentrant(key)                          // 守卫：本线程是否正在为该 key 调用 loader
  call = Call(owner = null)
  existing = calls.putIfAbsent(key, call)
  if existing != null:
      return if existing.async: existing.future.copy()                 // 异步 leader 本来就在异步执行器上完成
             else forwardOn(asyncExecutor, existing.future)       // 同步 leader 在自己的线程上完成：转发，不让用户续接跑在它上面
  f = try { block() } catch (e) { failedFuture(e) }   // block 内第一件事是取戳：注册之后
  f.whenComplete { r, e ->
      calls.remove(key, call)                      // 先注销
      if e != null: call.future.completeExceptionally(unwrap(e)) else call.future.complete(r)   // 再发布
  }
  return call.future.copy()
```

- **先注销再发布**、**注册之后取戳**、**读己之写至多重试一次**：与同步路径逐条相同，证明（RFC 0003 §4.4、architecture.md §5）不变：重试发起时上一轮调用已注销，新一轮调用（无论是自己发起还是合并到别人发起的）注册于注销之后，它的戳不早于本次读取观察到的戳。
- **同步与异步互相合并**：两种 leader 对表的操作完全相同，所以不变量与谁领导、谁合并无关。逐条：

  | 不变量 | 同步 leader（`execute`，现状） | 异步 leader（`executeAsync`） | 为什么混合合并时仍成立 |
  |--------|------|------|------|
  | 注册之后取戳 | `putIfAbsent` 成功后在本线程调用 `loadThrough`，其第一句取戳 | `putIfAbsent` 成功后调用 `block`，`loadThroughAsync` 的第一句取戳 | 戳只由 leader 取，合并者从不取加载戳；leader 的种类不影响“注册 → 取戳”的先后 |
  | 先注销再发布 | `calls.remove(key, call)` 后 `call.future.complete` | `whenComplete` 中先 `remove` 再 `complete` | 任何合并者（同步的 `join`、异步的副本）都只能在 `complete` 之后观察到结果，此时调用已从表中移除，它随后的重试必然注册新调用或合并到注销之后注册的调用 |
  | 读己之写至多重试一次 | `loadMiss`：`observed` 取于合并之前；结果 `stamp < observed` 时再 `execute` 一次 | `missAsync`：同样的 `observed` 与一次重试 | 重试合并到的调用注册于上一轮注销之后，即晚于本次 `observed`，其加载戳 ≥ `observed`；这一论证只用到“先注销再发布”和“注册之后取戳”，两者对两种 leader 都成立 |

  - **同步调用合并到异步调用**：在 `call.future` 上 `join()`（解包后抛出原始异常）。阻塞的只有该同步调用方自己的线程：异步 leader 的续接在异步执行器上推进，不依赖这个线程。唯一的例外是同步调用方本身就是异步执行器的线程（在异步续接里调用同步 API），此时由 `ForkJoinPool` 的补偿线程保证不死锁（§5.1）；loader 内的这种调用被重入守卫拒绝（见下）。同步调用方的拥有线程检查对异步调用不适用（异步调用没有拥有线程），由重入守卫覆盖。
  - **异步调用合并到同步调用**：不阻塞。同步 leader 在自己的线程上完成 `call.future`，如果直接返回 `copy()`，副本会在同步 leader 的线程上完成，用户的续接随之在一个不属于异步执行器的线程上运行，违反 A9。所以合并到同步调用的异步调用方拿到的是经 `whenCompleteAsync(…, asyncExecutor)` 转发的新 future（合并到异步调用时 `call.future` 本来就在异步执行器上完成，`copy()` 即可）。
  - **批量**：`executeAll`（同步）合并到异步调用时同样 `join`；`executeAllAsync` 合并到同步调用时同样经异步执行器转发。
- **副本**（`CompletableFuture.copy()`，JDK 9+）：每个调用方（含 leader）拿到不同的 future，取消、`obtrude`、`complete` 都只影响自己。
- **重入检测**：同步路径保留 `owner === currentThread`。另外引擎在**调用 loader 期间**（同步 loader 的整个执行；异步 loader 的 `loadAsync` 同步部分）把 `(SingleFlight, key)` 记入一个 ThreadLocal 守卫；同一线程在守卫内再对同一 key 发起同步或异步调用时快速失败（`IllegalStateException`，与现状同一信息）。覆盖：
  - 异步 loader 在 `loadAsync` 中直接调用 `cache.async().get(sameKey)`；
  - 阻塞执行器上的同步 loader 调用 `cache.get(sameKey)`（否则该线程会 `join` 一个等待自己的调用，永久阻塞）。
  - 不覆盖：异步 loader 在一次 I/O 之后（另一个线程上）再读同一 key。这形成一个只能由 `asyncTimeout` 打破的环，文档写明；`asyncTimeout` 正是为此设定为有限默认值。
- **批量**：`executeAllAsync(keys, block)` 先为全部 key 注册（led / joined），再启动自己负责的整批，最后 `allOf` 合并；没有线程等待，所以“先完成自己的再等别人”的死锁问题不存在，但注册顺序与同步版本相同。

### 5.5 批量读

与 architecture.md §5.3 逐条对应：L2 逐 key 检查在调用方线程上完成；剩余 key 交给 `executeAllAsync`；leader 一批：注册后取戳 → `readAllAsync`（失效重试中的 key 不读 L1）⇒ 命中解码与条件回收 ⇒ 一次 `loadAllAsync`（同步 loader 时 `loadAll` 在阻塞执行器上）⇒ `fillAllAsync` ⇒ `fillLocal`。批量路径不等待 lease。共享结果开始于已观察到的失效之前的 key 改走 `missAsync`。回源失败时释放全部 lease。

### 5.6 写路径

```
writeAsync(keys, mutateAsync, onFailure): CompletableFuture<Void?>
  keys.forEach { stamps.invalidate(it); local.invalidate(it) }          // 调用方线程
  frame = frameOf(keys)
  f = mutateAsync(frame) ⇒ 若 !atomicBroadcast: channel.publishAsync(namespace, frame)
  f ⇒(异步执行器，无论成败)
      keys.forEach { stamps.invalidate(it); local.invalidate(it) }      // 第二次递增，在用户 future 完成之前
      失败：metrics.writeFailure(); onFailure(e)  → 完成为 CacheWriteException / 正常（已登记重试）
```

- 第二次递增挂在 **L1 future** 上，不挂在返回给用户的 future 上：用户取消返回的 future 不会跳过它。
- `onFailure` 在第二次递增**之前**执行（与同步版本相同），登记的“失效重试中”状态对随后的读取可见。
- 编码（`Codec.encode`）与寿命计算在调用方线程上、第一次递增**之前**完成，与同步路径（`putWithTtl` 先编码再 `write`）相同：编码失败时 future 异常完成，戳、L2、L1 都没有变化。构造帧也在调用方线程上，写入不为此多一次线程切换。

### 5.7 提前刷新

- 命中路径（同步或异步）发现到达 `refreshAt` 时调用同一个 `refreshAsync`，`refreshing` 集合保证每节点每 key 至多一次。
- spec 是同步 loader：现状不变（刷新执行器上执行同步的 `refreshThrough`）。
- spec 是 `asyncLoader`：`asyncExecutor.execute { refreshThroughAsync }`，不占刷新执行器线程。刷新令牌的取得、`fill(r)`、`release(r)`、被拒绝与失败的处理与同步路径相同；`refreshing` 在 future 完成时移除。

### 5.8 命中路径成本与备选方案

- 同步 `get` / `getAll` / `lookup` 的代码不改；`CoherentEngine` 的字段与 `LocalEntry` 布局不变。异步视图是引擎的内部类，命中判断复制同一段代码（`local` 是同一个 Caffeine 实例），返回 `CompletableFuture.completedFuture(value)`：一次 24 B 左右的分配，返回值逃逸，无法被标量替换。
- **验收**（§11）：`l2HitAsync` ≥ 同轮 `l2Hit` 的 80%，1 线程与 8 线程都满足；`l2HitAsyncMixed` ≥ 同轮 `l2HitMixed` 的 80%。
- **备选（只在达不到时实施）**：`LocalEntry` 增加一个惰性字段，缓存一个**不可变**的已完成 future（`CompletableFuture` 子类：`obtrudeValue` / `obtrudeException` 抛出 `UnsupportedOperationException`；`complete` / `cancel` 对已完成的 future 本来就无效）。异步命中直接返回它，零分配。成本：按 64 位压缩指针估算，`LocalEntry` 现为 12 B 头 + 4 + 8 + 8 + 1 = 33 B，对齐到 40 B；加一个引用字段为 37 B，仍是 40 B，同步用户的内存不变（实施前用 JOL 验证）。代价是“每次调用返回新 future”的约定在命中时不再成立（取消、完成已完成的 future 无效，`obtrude` 抛出），KDoc 需相应说明，所以默认不采用。

---

## 6. Spring（`cocache-spring`）

```kotlin
override fun retrieve(key: Any): CompletableFuture<*>? =
    cache.async().lookup(key).thenApply { if (it is Lookup.Present) SimpleValueWrapper(it.value) else null }

override fun <T : Any> retrieve(key: Any, valueLoader: Supplier<CompletableFuture<T>>): CompletableFuture<T> =
    cache.async().get(key, AsyncCacheLoader { _ -> valueLoader.get() }) as CompletableFuture<T>
```

- **L2 命中不再切换线程**：返回已完成的 future（现状是每次 `supplyAsync` 到 retrieve 执行器）。
- **响应式 / `CompletableFuture` 方法上的 `@Cacheable(sync = true)`**：Spring 以 `retrieve(key, () -> mono.toFuture())` 调用；现在经异步视图受 lease 与失效戳保护地回源，**没有线程在等待**，architecture.md §9.5 中“替换执行器不能排队、否则嵌套调用可能死锁”的约束随之消失。`valueLoader` 在异步执行器上被调用（与 Spring 自带的 Caffeine 异步缓存在其执行器上调用相同）。
- 负缓存视为未命中的语义不变：`retrieve(key)` 对负缓存以 `null` 完成（Spring 的“迟到的未命中”）。
- 已知限制（写进文档）：Spring Cache 的 `put` / `evict` 只有同步签名。反应式方法上的 `@CachePut`、`@CacheEvict`、不带 `sync` 的 `@Cacheable` 在完成回调中同步调用它们，每次占用该线程一次 L1 往返。推荐继续使用 `sync = true`；需要非阻塞失效的代码直接调用 `cache.async().invalidate(k)`。
- `cocache.spring-cache.retrieve-executor` 与 `CoCacheSpringCache(cache, admin, executor)` 构造函数在 6.3 标记弃用：设置时启动告警，执行器不再使用；7.0 删除。
- 事务：`TransactionalInvalidator` 不变（提交后在 Spring 的同步回调中调用同步 `invalidateAll`）。

---

## 7. 协程（`cocache-coroutines`，6.4）

> **依赖需要维护者批准**：`org.jetbrains.kotlinx:kotlinx-coroutines-core`（与项目 Kotlin 版本匹配的 1.10.x），只作为本模块的 `api` 依赖；`cocache-api`、`cocache-core` 及其它模块不依赖它；`cocache-bom` 收录本模块，`cocache-dependencies` 声明协程版本。

```kotlin
fun <K : Any, V : Any> Cache<K, V>.suspending(): SuspendingCache<K, V>

interface SuspendingCache<K : Any, V : Any> {
    val name: String
    suspend fun get(key: K): V?
    suspend fun get(key: K, loader: suspend (K) -> V?): V?
    suspend fun getAll(keys: Collection<K>): Map<K, V>
    suspend fun lookup(key: K): Lookup<V>
    suspend fun put(key: K, value: V)
    suspend fun put(key: K, value: V, ttl: Duration)
    suspend fun putAll(entries: Map<K, V>)
    suspend fun invalidate(key: K)
    suspend fun invalidateAll(keys: Collection<K>)
}

fun interface SuspendingCacheLoader<K : Any, V : Any> {
    suspend fun load(key: K): V?
    suspend fun loadAll(keys: Collection<K>): Map<K, V> = …   // 默认并发调用 load
}
fun <K : Any, V : Any> SuspendingCacheLoader<K, V>.asAsyncCacheLoader(context: CoroutineContext = Dispatchers.Default): AsyncCacheLoader<K, V>
fun <K : Any, V : Any> CacheSpec.Builder<K, V>.suspendingLoader(context: CoroutineContext = Dispatchers.Default, loader: SuspendingCacheLoader<K, V>)
```

- 实现只是异步视图的薄封装：`val f = async.get(key); if (f.isDone && !f.isCompletedExceptionally) f.getNow(null) else f.await()`。L2 命中不挂起、不分配协程状态（future 的分配同 §5.8）。`await()` 解包 `CompletionException`，挂起函数抛出原始异常。
- **取消**：取消正在 `await` 的协程只取消本次等待（A7）；共享加载照常完成。
- **调用级 loader 的上下文**：`get(key) { … }` 以“调用方的协程上下文去掉 `Job`”启动 loader：MDC、追踪等 `ThreadContextElement` 随之传递，调用方被取消不会取消共享加载。spec 级 `SuspendingCacheLoader` 没有调用方上下文，在给定的 `context` 中启动（默认 `Dispatchers.Default`）。
- 用法：`cocache-example` 增加一个 WebFlux + 协程示例（同样在 6.4）。

---

## 8. 可观测、失败与配置

### 8.1 `LoadObserver` 的异步变体

```kotlin
interface LoadObserver {
    fun <T> observe(cacheName: String, operation: LoadOperation, keyCount: Int, block: Supplier<T>): T
    /**
     * 观测一次异步操作：从调用 block 到其 future 完成。实现必须在调用方线程上同步调用 block 恰好一次，
     * 并返回一个以 block 的结果或原始异常完成的 future。默认不观测（直接返回 block 的 future）。
     */
    fun <T> observeAsync(cacheName: String, operation: LoadOperation, keyCount: Int, block: Supplier<CompletableFuture<T>>): CompletableFuture<T> = block.get()
}
```

- 默认实现**不观测**而不是委托给 `observe`：委托只能测到“发起”的耗时，结果永远是成功，比没有观测更具误导性。已有的自定义观测器在异步路径上没有数据，KDoc 与 changelog 写明。
- starter 的 `MicrometerLoadObserver` 实现 `observeAsync`：`Observation.start()`，在 `openScope()` 内调用 `block`（`loadAsync` 的同步部分能看到当前观测，追踪上下文可经 context-propagation 传给 R2DBC / WebClient），future 完成时 `error` / `stop`；标签与同步路径相同（`cache`、`operation`、`outcome`），超时的 outcome 为 `timeout`。
- 引擎对 `LoadObserver.NOOP` 的识别不变：默认配置下异步路径同样不产生任何观测调用。
- 观测范围与同步相同：`load`、`loadAll`、`refresh`、`lease.wait`（异步等待从首次读到 `Leased` 到等待结束）。

### 8.2 失败模式

| 情形 | 异步路径的行为（与同步路径的对应关系） |
|------|------|
| L1 读失败，`readFailure = DEGRADE` | 不经 L1 回源，L2 降级限龄（同） |
| L1 读失败，`readFailure = THROW` | future 以 `CacheReadException` 异常完成（同） |
| fill、release、reclaim 失败 | 尽力而为，降级并计数（同） |
| loader 失败 | 释放 lease（等释放完成或失败后）再以原始异常完成；所有合并的等待者得到同一个原始异常（同） |
| loader 超时（`asyncTimeout`） | 释放 lease，以 `SourceLoadTimeoutException` 完成（新增，只在异步 loader 上） |
| 回源被拒绝 | 释放 lease，以 `SourceLoadRejectedException` 完成（同；等待改为定时重试） |
| 写入失败 | 两次递增与淘汰已完成；`put` 一类以 `CacheWriteException` 完成；`invalidate` 默认登记重试后正常完成（同） |
| 异步执行器拒绝任务 | 以 `RejectedExecutionException` 完成，已取得的 lease 经阻塞执行器尽力释放（新增；默认的自建 `ForkJoinPool` 只在运行时关闭后拒绝） |
| 重入 | `IllegalStateException`，在 future 中（同步路径是抛出） |

### 8.3 配置

| 项 | 位置 | 默认 |
|----|------|------|
| `asyncExecutor` | `CacheRuntime.Builder`；starter `cocache.async-executor`（bean 名） | 运行时自建的 `ForkJoinPool`（CPU 数，守护，`cocache-async-*`，随运行时关闭） |
| `blockingExecutor` | `CacheRuntime.Builder`；starter `cocache.blocking-executor`（bean 名） | JDK 21+ 虚拟线程；否则不排队的守护线程池（≤ 512） |
| `LoadPolicy.asyncTimeout` | `CachePolicy`；`cocache.defaults.load-async-timeout`、`cocache.caches.[name].load-async-timeout` | 30 s；必须为正且 ≤ 100 年 |
| `cocache.spring-cache.retrieve-executor` | starter | 弃用（§6） |

`asyncTimeout` 只作用于 `AsyncCacheLoader`。校验：`asyncTimeout` 应大于 loader 的 p99；它与 `lease.ttl` 无约束关系（超过 `lease.ttl` 的加载照常完成，只是 fill 被拒绝、L2 降级限龄，与同步路径相同）。

---

## 9. 不变量的修改（architecture.md §14）

| # | 修改后 |
|---|--------|
| 5 | **SingleFlight 先注销调用、再发布结果**（读己之写依赖这个顺序）；同步与异步调用共用一张表，异步调用在注册之后取戳；每个调用方拿到共享结果的副本；同步调用合并到异步调用时只阻塞自己的线程，异步调用合并到同步调用时经异步执行器拿到结果（§5.4 的逐条论证）。同线程对同一 key 的重入（同步调用的拥有线程，或正在为该 key 调用 loader 的线程）快速失败；批量合并先注册全部 key、先启动自己负责的 key |
| 6 | **同步路径上 loader 在调用方线程上执行**；异步路径上 `AsyncCacheLoader` 在异步执行器上调用，同步 loader 只在阻塞执行器上执行，从不在异步执行器上执行。L1 与 loader 的 future 完成后先切换到异步执行器再执行任何续接，**事件循环线程上不执行续接** |
| 7 | **令牌必须释放或过期**：loader、`ttlOf`、`Codec` 失败，回源被拒绝或超时，刷新令牌用不上，以及运行时关闭时取消的等待，都要 `release` |
| 17（新） | **取消不传播**：取消调用方的 future 不取消共享加载、不取消 loader 的 future、不跳过写入方的第二次递增 |
| 18（新） | **异步 API 不挂起线程、不同步抛出**：等待 lease 与回源许可经定时器调度；失败以原始异常（不包装为 `CompletionException`）完成返回的 future |

不变量 #1–#4、#8–#16 不变；栅栏规则对异步路径逐条适用（§1.2）。

---

## 10. 测试计划

### 10.1 TCK（`cocache-test`，已发布）

| 新增 | 内容 |
|------|------|
| `AsyncEngineSpec` | 继承 `EngineSpec`，把读写入口换成 `async()` 视图（`EngineSpec` 抽出 `read` / `write` 钩子，默认走同步 API；异步实现以 `join` 后解包等待）。**`EngineSpec` 的每个用例都在异步路径上重跑**，包括同节点失效与并发读、同节点并发 `put`、读己之写、跨节点单次回源、lease 等待超时、失效重试、损坏回收、未知格式、批量 |
| `AsyncEngineSpec` 的专有用例 | 取消一个等待者不影响其它等待者与 L1 fill；全部等待者取消后加载仍完成并 fill；返回的 future 不在后端 I/O 线程上完成（`HookedBackend` 在专用线程上完成 future 并断言续接线程）；**lease 等待只用 O(1) 个线程**：另一节点持有 lease 期间发起 N = 1000 个异步读取，异步执行器为单线程、阻塞执行器为计数线程工厂，断言等待期间所有线程工厂都没有创建新线程、`ThreadMXBean` 的线程数增量不超过常数（与 N 无关）、没有线程处于 `TIMED_WAITING`/`WAITING` 于引擎代码中，lease 释放后 N 个读取全部完成且只回源一次；同样的用例覆盖等待回源许可；异常为原始类型；异步方法不同步抛出；loader future 永不完成时 `asyncTimeout` 释放 lease、其它节点随后可以回源；同步与异步调用互相合并（只回源一次）；`loadAsync` 同步部分重入同一 key 快速失败；阻塞执行器上的同步 loader 重入快速失败；同步 loader 经异步调用时在阻塞执行器上执行、从不在异步执行器上执行（断言线程名）；同步调用合并到异步加载时只阻塞自己（其它异步调用照常完成）；异步调用合并到同步加载时结果在异步执行器上送达；异步 loader 经同步调用时可用 |
| `AsyncSetEngineSpec` | 同理继承 `SetEngineSpec` |
| `AsyncBackendProtocolSpec` | 继承 `BackendProtocolSpec`，经 `AsyncRemoteStore` 的方法重跑协议用例；另加同步与异步交替调用的一致性、不得同步抛出 |
| `HookedBackend` | 被包装的 store 是 `AsyncRemoteStore` 时也实现它；钩子（`failAll`、`failWrites`、`failFill`、`failRelease`、改写读取、写入前后编排）同时作用于同步与异步方法；新增“在指定线程上完成 future”的钩子 |

- 运行矩阵：`InMemoryBackend`（原生异步）、一个只实现同步 SPI 的测试后端（经阻塞适配器）、Redis 的 `pubsub` / 非原子 / `sharded` 三种后端（与 `EngineSpec` 相同的矩阵），设置 `SPRING_DATA_REDIS_CLUSTER_NODES` 时连接 Cluster。
- 竞态仍用 latch 编排，禁止 sleep；等待 `finished` latch；跨节点效果带超时轮询。
- `MultiNodeSpec` 与 `FaultSpec` 已合入（#650）：新增 `AsyncMultiNodeSpec` / `AsyncFaultSpec`，以同样的入口钩子在异步路径上重跑，并增加“同步写 + 异步读”“异步写 + 同步读”的跨节点交错。

### 10.2 模块测试

- core：`SingleFlight.executeAsync` / `executeAllAsync` 的单元测试（先注销再发布：在发布回调里重新执行必然开始新调用；副本取消；混合同步异步）；许可与超时的定时器；运行时关闭。
- redis：`orTimeout` 与同步 `await` 的异常类型一致；`readAllAsync` 部分失败时释放 lease；`NOSCRIPT` 回退在异步方法上同样生效。
- spring / starter：`retrieve` 不再使用执行器（L2 命中为已完成 future、未命中不占线程）；嵌套的异步 `sync = true` 在单线程执行器下不死锁；`AsyncCacheLoader` bean 的解析与冲突；`cocache.async-executor` / `cocache.blocking-executor` 绑定；弃用告警；`MicrometerLoadObserver.observeAsync`；AOT 提示测试。
- 覆盖率门槛（≥ 95% 行 / ≥ 90% 分支）对新代码同样适用。

---

## 11. 性能验收

按 architecture.md §13 的方法：与 6.2 交替运行至少 5 轮（`--rerun`），1 线程与 8 线程，报告中位数与置信区间。新增基准方法（`CoCacheBenchmark`）：`l2HitAsync`、`l2HitAsyncMixed`、`l1ReadAsync`、`missLoadAsync`、`setAsync`、`invalidateAsync`（基准内 `join`，测单次延迟对应的吞吐），以及信息性的 `l1ReadPipelined`（单线程同时保持 64 个在途异步读取）。

| 路径 | 验收 |
|------|------|
| 同步 `l2Hit`、`l2HitViaInterface`、`l2HitMixed` | **吞吐与 6.2 相比在噪声内**：至少 5 轮交替运行（`--rerun`），1 线程与 8 线程，比较中位数，置信区间不得与退化区间重叠（命中路径代码未改，也必须用数据证明） |
| `l2HitAsync` / `l2HitAsyncMixed` | ≥ 同轮同步对应项的 80%（1 与 8 线程）；否则实施 §5.8 备选并重测 |
| `l1ReadAsync`（存储层，`store.readAsync(…).join()`，与 `l1Read` 对应） | ≥ 同轮 `l1Read` 的 95%（同步 `read` 本身就是等待同一个异步调用） |
| `missLoadAsync`、`setAsync`、`invalidateAsync`（经引擎，含线程切换） | ≥ 同步对应项的 90% |
| `l1ReadPipelined` | 只报告，不设门槛（展示单线程可达的吞吐） |
| 分配 | `-prof gc` 报告异步 L2 命中每次操作的分配字节数（预期约 24 B） |
| lease 等待的线程数 | 不是基准而是测试：§10.1 的“N 个异步等待者只用 O(1) 个线程”用例必须通过（内存后端与 Redis 后端） |

---

## 12. 已决问题（原未决问题，2026-10-19 审阅决定）

| # | 问题 | 决定与理由 |
|---|------|-----------|
| Q1 | 是否引入 `kotlinx-coroutines-core` | **批准**，只作为可选模块 `cocache-coroutines` 的依赖，6.4 交付；api、core 及其它模块不依赖它 |
| Q2 | 默认异步执行器 | **运行时自建的 `ForkJoinPool`**（CPU 数、守护、`cocache-async-*`、随运行时关闭），不用 `commonPool`。理由：`commonPool` 与应用的并行流和其它库共用，被占满时所有缓存的完成都会停顿且难以诊断；自建池可辨识、生命周期明确，同时保留 `ForkJoinPool` 在 `join()` 上补偿线程的性质（§5.1）。同步 loader 只在阻塞执行器上执行 |
| Q3 | `asyncTimeout` 的默认值 | **30 s**：远大于常见 loader 的 p99，又让“丢失回调”的 key 在半分钟内恢复。设为不限会把一个 bug 变成永久不可用的 key |
| Q4 | 是否支持 `@CoCache interface X : AsyncCache<K, V>` 或自动注册 `AsyncCache` bean | **推迟**（6.3 不做，§3.4）。一个接口不能同时继承同名不同返回类型的两组方法；为每个缓存再注册一种 bean 会让注册、AOT 提示与 sync 检查成倍处理，收益只是省掉一次 `.async()`。有明确需求时只需在注册器中把 bean 类型改为 `AsyncCache`、实例为 `engine.async()` |
| Q5 | `JoinCache` 的异步视图 | **推迟**：它是两次 `getAll` 的组合，用户可以用两次 `async().getAll` 写出来 |
| Q6 | 同步路径是否对阻塞的 loader 设超时 | **否**：阻塞的 loader 无法被打断，超时只会让调用方先返回而线程继续占用。`asyncTimeout` 只作用于 `AsyncCacheLoader` |
| Q7 | `retrieve-executor` 如何退场 | **6.3 弃用，7.0 删除**：6.x 只做增量，删除配置项是破坏性变化（会触发 `RemovedProperties` 的启动失败）。6.3 中设置它只告警，执行器不再使用 |

---

## 13. 实施顺序

每一步都单独合入并通过 `./gradlew check`。

1. **6.3 / PR 1：api + core + 内存后端 + TCK**
   - api：`AsyncCache`、`AsyncSetCache`、`AsyncCacheLoader`、`Cache.async()`、`SetCache.async()`、`AsyncRemoteStore`、`AsyncInvalidationChannel`。
   - core：执行器与定时器、`SingleFlight` 异步化与重入守卫、`CoherentEngine` / `SetEngine` 的异步视图、阻塞适配器、`CacheSpec.asyncLoader`、`LoadPolicy.asyncTimeout`、`SourceLoadTimeoutException`、`LoadObserver.observeAsync`、`CacheMetrics.loadTimeouts`、代理分派 `async`；`InMemoryBackend` 实现 `AsyncRemoteStore`。
   - TCK：`AsyncEngineSpec`、`AsyncSetEngineSpec`、`AsyncBackendProtocolSpec`、`HookedBackend` 异步钩子。
2. **6.3 / PR 2：Redis**：`RedisRemoteStore` 与通道的原生异步实现、同步方法改为等待异步实现；Redis 上的异步 TCK 矩阵；JMH（§11，PR 中附数据）。
3. **6.3 / PR 3：Spring 与文档**：`CoCacheSpringCache.retrieve`、starter 配置项与 bean 解析、`MicrometerLoadObserver.observeAsync`、AOT 提示、弃用告警；architecture.md（§1 非目标、§5 线程模型、§7、§9.2、§9.5、§10、§13、§14）、wiki（中英文：异步 API 指南、配置、可观测、扩展后端）、`skills/`、changelog；版本号 6.3.0。
4. **6.4：`cocache-coroutines`**（Q1 已批准）：模块、BOM、示例、wiki。
