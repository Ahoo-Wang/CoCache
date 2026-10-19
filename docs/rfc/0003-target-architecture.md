# RFC 0003：目标架构（第一性原理重构，6.0）

- 状态：**已实施（6.0）**。草案 v3 经两轮对抗验证（§10）；§9 步骤 0–5 已完成，6.0 已取代 5.x，现行规范见 [architecture.md](../architecture.md)。未随 6.0 交付的部分（`MultiNodeSpec`、`FaultSpec`、Valkey CI 等）已在正文中标注“推迟”，并列入 [roadmap](../roadmap.md)“6.0 之后”。Q9 经维护者复议改为：保留 `@CoCache` 接口式声明，主接口命名为 `Cache`（见 §8 Q9）。SPI 与线格式以代码为准（`me.ahoo.cocache.api.spi`、`me.ahoo.cocache.redis.RedisScripts`）
- 前提：**不考虑兼容性**。API、SPI、模块、Redis 线格式、配置项都可以推倒重来
- 关系：吸收并取代 [RFC 0001](0001-l1-write-back-lease.md)（lease 成为唯一的写回保护原语，6.0 只破坏一次线格式，不再单独发布 lease）；[RFC 0002](0002-resp3-client-tracking.md) 变为新后端 SPI 下的一种可选通道

---

## 0. 结论摘要

| # | 决定 | 一句话理由 |
|---|------|-----------|
| D1 | **L1 是一致性权威**：每个 key 的写入在 Redis 中串行化，写回保护（lease）、失效、广播尽量在 L1 的同一次原子操作里完成 | G2 需要“每个 key 有一个串行化点”。进程内的失效戳只能覆盖单进程；跨进程的唯一串行化点就是 L1 |
| D2 | **一个原语：fence（栅栏）**。L1 用 lease 令牌，L2 用进程内失效戳。规则：*填充必须持有读取前取得的令牌；失效必须在修改上游之前**和之后**各销毁一次令牌* | 现在的 `fillCache` / `evictIfUnchanged` / 写后复核再撤销 / RFC 0001 lease 都是这条规则的特例。“之后再销毁一次”修复了对抗验证发现的同实例失效吞没问题（§10 C1），5.x 也受这个问题影响 |
| D3 | **能原子广播时就原子广播**：失效 + 广播在一次 Lua 中完成（`DEL` + `PUBLISH`）；启动时探测，环境不支持时退回非原子模式并告警 | 消除“DEL 成功、PUBLISH 失败”的窗口，失效从 2 次往返降为 1 次。原子性只在单个 slot 内成立；ElastiCache Serverless 这类把 PUBLISH 当作 SPUBLISH 执行的环境做不到（§4.7） |
| D4 | **新线格式，形状决定编码**：标量是 String 加 36 字节定长二进制头；集合是 Hash，控制字段与成员分属不同命名空间 | 去掉带内哨兵 `_nil_`；lease、刷新令牌、格式版本都有固定位置。标量保持 `GET` 的读取成本和 String 的内存效率（v1 的“全部用 Hash”在载荷 > 64 B 时每个 key 多出约 200 B，已否决，见 §10 R5） |
| D5 | **Redis 后端改为 Lettuce 原生，不依赖 Spring** | 当前使用的 Spring Data Redis 不支持 Sharded Pub/Sub、client tracking、连接事件，pipeline 还需要专用连接。Lettuce 的共享连接可以多路复用并发命令 |
| D6 | **L2 固定为 Caffeine，不可替换** | 少一层接口分发，少一个扩展点，也没有比 Caffeine 更好的选择。注意：Caffeine 内部会按配置生成不同的节点类，所以“单态”只针对 CoCache 自己的调用层，基准必须用多种配置的缓存验证 |
| D7 | **声明即数据**：`CacheSpec` + `CacheRuntime.create(spec)`；Spring 只负责把 spec 变成 bean | 取代 9 种工厂、bean 名后缀约定和“有状态组件只按名称解析”这类陷阱。用户只提供工厂，不提供实例，所以组件按构造就不可能被共享 |
| D8 | **数据源只加载，策略决定寿命** | 现在 `CacheSource` 返回带 `ttlAt` 的 `CacheValue`，TTL 策略被数据源和编排层各持一半。按 key 或值决定寿命用 `CacheSpec.ttlOf(K, V)` |
| D9 | **时间可注入、毫秒精度** | 现在 API 模块里的 `object CacheClock` 会启动静态线程，测试无法控制时间，Redis 秒级 TTL 带来 ±1 s 漂移 |
| D10 | **失败语义：填充可降级；显式写入抛出；失效默认可靠重试** | `invalidate` 通常在数据库事务提交后调用，抛出异常既不能回滚，也不该回滚，所以默认交给有界重试队列。`put`/`add`/`remove` 是调用方的显式写入，失败就抛出 |
| D11 | **删除 JoinCache 子系统**（`@JoinCacheable`、`JoinCache`、SpEL 提取器、代理与工厂） | 组合读模型是用户层逻辑，两次 `getAll` 就能写出来，不值得在核心里保留一整套声明与代理机制。接口式声明 `@CoCache` 保留（Q9），但只是 spec 的另一种写法 |

三个目标不变，但要说得更精确（§1.2）。

---

## 1. 第一性原理

### 1.1 缓存到底是什么

缓存是“某个权威数据的副本，副本的陈旧度有上界”。它能做的只有三件事：**复制**（填充）、**作废**（失效）、**限龄**（TTL）。所有正确性问题都出在“复制”和“作废”的交错上：一次开始得早的复制，在作废之后才落地。

由此可以推出几条公理：

- **A1 串行化点**：要判断“复制开始于作废之前”，同一个 key 的复制与作废必须经过同一个串行化点，在那里比较先后。进程内的串行化点是 `AtomicLong`（失效戳）；跨进程的串行化点只能是共享存储本身（Redis 对单个 key 是串行执行的）。
- **A2 令牌先于数据**：复制者在读取**之前**拿到令牌，写入时出示令牌；作废者销毁令牌，再删除数据。当“数据”本身在上游（L2 的上游是 L1）时，作废者在修改上游之后必须**再销毁一次**令牌：否则一个在第一次销毁之后、上游修改之前读到旧值的复制者，持有的是有效令牌（§4.4 给出证明）。
- **A3 权威发出失效**：谁串行化了写入，谁就应该发出失效通知。写入与通知分开执行，中间一定有窗口，可能写成功但通知丢失。
- **A4 TTL 是安全网，不是机制**：TTL 兜底的是协议之外的问题，比如绕过缓存的写入、Redis 故障切换时丢失的写入、loader 读到了数据库从库上的旧数据。协议之内的竞态不应该靠 TTL 收敛。
- **A5 命中路径只做必要的事**：L2 命中的下限是“一次并发哈希表读取 + 一次过期比较”。在它之上多出来的每一项（反射分发、虚调用、计数）都要有数据证明值得。
- **A6 声明与实例分离**：有状态组件（L2、L1 连接、栅栏）的生命周期必须和缓存一一对应。最可靠的保证方式是让用户无法传入实例。

### 1.2 目标（精确化）

| # | 目标 | 可度量的表述 |
|---|------|--------------|
| G1 | 陈旧度有上界 | **L1**：协议内竞态造成的陈旧为 0；协议外（绕过缓存的写入、故障切换丢写）≤ `ttl`。**L2**：事件送达时 ≤ 事件延迟；事件丢失时 ≤ `min(localMaxAge, 心跳超时 + 重连时长)`；`invalidate` 写入 L1 失败且重试放弃时 ≤ L1 剩余 TTL（L2 到期后从旧的 L1 重新填充；`ttl = null` 时无上界，计数并告警）；降级填充 ≤ `degradedLocalMaxAge`。`localMaxAge` 默认 5 分钟（有限值） |
| G2 | 失效不被吞没 | **L1**：读取开始于某次失效之前的填充，永远不会在该失效之后出现在 L1。**L2**：在某个节点处理完一次失效（本地调用返回，或收到并处理了远端事件，或执行了重置）之后，该节点的 L2 永远不会再出现读取开始于这次失效之前的填充 |
| G3 | 命中路径便宜 | L2 命中（spec bean 注入的就是引擎本身，没有代理层）与 5.4 直接调用引擎相比不退化；L1 命中一次往返；单 key 的失效、写入各一次往返；同一 key 全集群并发未命中时，若 loader 耗时小于 `lease.wait`，只回源一次 |

非目标：线性一致读；api 与 core 中的异步 API 和协程（同步 API 对虚拟线程友好；预留的扩展方式见 §8 Q8）。`SetCache` 把 L1 当作唯一存储（§4.8），这是有意的例外。

---

## 2. 现状审查

先说结论：5.x 的核心算法（失效戳、SingleFlight 的发布顺序、L1 未命中即未命中）方向是对的，并且有大量 in-flight 测试覆盖。但对抗验证发现，**同实例的写入路径有两处失效吞没**（下表前两行），所以“5.x 的核心算法已被充分验证”这个判断不成立。其余问题在于：一致性分散在三个机制里，每个机制只覆盖一部分；外围的抽象层次、扩展点和装配方式也远多于问题本身需要的。

### 2.1 正确性

| 发现 | 证据 | 影响 |
|------|------|------|
| **同实例 `evict` 被并发读吞没**（对抗验证 C1） | [`DefaultCoherentCache.kt:423`](../../cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt) 只在 `DEL` 之前递增一次失效戳。另一个线程在递增之后取戳、在 `DEL` 之前读到 L1 旧值，它的 L2 填充能通过前后两次校验；本实例发出的事件又会被自己过滤 | 该实例 L2 的旧值持续到 `clientSideTtl` 或 TTL（默认 1 h）。违反 G2 |
| **`setCache` 写 L2 没有戳保护**（对抗验证 C1） | [`DefaultCoherentCache.kt:362`](../../cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt)：戳 → 写 L1 → 写 L2。另一实例更晚写入 L1 的事件如果在“写 L1”与“写 L2”之间到达，或者本实例的并发读在写 L1 之前读到旧值，L2 都会留下旧值 | 同上 |
| 跨实例写回竞态只能靠 TTL 收敛 | architecture.md §5“已知的固有窗口”；`InvalidationStamps` 只在进程内有效 | 违反 A1。默认 TTL 不能放长，`FOREVER` 只能靠显式失效 |
| 失效与广播分两次执行 | 先 `distributedCache.evict` 再 `publish`；[`RedisCacheEvictedEventBus.kt:56`](../../cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt) 发布失败只计数 | 违反 A3。其它实例的 L2 陈旧到 `clientSideTtl` 或 TTL |
| L1 失败降级后，L2 仍以完整 TTL 填充 | `writeBack` 在 L1 `setCache` 被降级吞掉之后照常写 L2（[`DefaultCoherentCache.kt:319`](../../cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt)） | 如果 Redis 故障时 Pub/Sub 连接没有断开，失效发不出去，`onReset` 也不会触发，L2 陈旧度的上界退化为 TTL |
| 写回保护有三种形态 | 写后复核后的 `invalidate`、Set 的 `fillCache` + `evictIfUnchanged`、RFC 0001 准备再加的 lease | 每种形态都要单独证明、单独测试；Set 已经因此有了专用 SPI |
| 集合元素写入与数据源语义冲突 | `addElements` 在 key 不存在时直接创建集合，不经过数据源；增量操作与数据库中的写入顺序无关 | 若集合的真实数据在数据源中，L1 会长期只保存部分元素，或因增量操作乱序留下已删除的元素（§4.8 重新定义） |
| 带内哨兵 | `_nil_` 字符串、Hash 单字段、Set 单成员；[`SetToSetCodecExecutor.kt:133`](../../cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/SetToSetCodecExecutor.kt) 禁止元素等于哨兵；自定义哨兵需要全集群同时切换 | 类型混淆隐患；RFC 0001 的 lease 标记还要在每种 codec 里再加一个带内形态 |
| 时间精度 | `TTL` 秒级回读，`ttlSecondsOf` 的 `coerceAtLeast(1)`（[`AbstractCodecExecutor.kt:218`](../../cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt)）；TCK 需要 `Offset.offset(1)` | ±1 s 漂移；短 TTL（例如 1–2 s 的负缓存）的语义不精确 |

### 2.2 性能

| 发现 | 证据 | 影响 |
|------|------|------|
| **JMH 测不到用户真实的命中路径** | 基准直接调用 `DefaultCoherentCache`（[`RedisCacheBenchmark.kt:77`](../../cocache-spring-redis/src/jmh/kotlin/me/ahoo/cache/spring/redis/RedisCacheBenchmark.kt)）；用户调用经过 JDK 动态代理，有 `declaringClass` 分支和 `Method.invoke`（[`CacheInvocationHandler.kt:62`](../../cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CacheInvocationHandler.kt)） | G3 有盲区：被精细优化的路径不是用户实际走的路径 |
| L2 可替换导致多一层接口分发 | `ClientSideCache` 接口；example 里同时存在 `MapClientSideCache` 与 Caffeine 两种实现 | 同一进程加载多种实现后，调用点变成多态 |
| 写入、失效各 2 次往返 | `SET`/`DEL` 与 `PUBLISH` 分开 | 写入延迟翻倍 |
| 批量读需要专用连接 | `executePipelined`（[`AbstractCodecExecutor.kt:136`](../../cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt)），pipeline 内只能用 EVAL | 这是 Spring Data Redis 抽象的限制，Redis 和 Lettuce 本身没有这个限制 |
| 跨实例重复回源 | 非目标“各实例独立合并回源” | 热点 key 过期时，N 个实例同时回源 N 次 |
| 批量读绕过 SingleFlight | architecture.md §4.2 | 同一 key 的单 key 读与批量读会各自回源 |
| L2 命中计数默认关闭 | `recordClientSideHits` | 最重要的命中率指标默认缺失 |

### 2.3 复杂度与可演进性

| 发现 | 证据 |
|------|------|
| 9 种工厂接口 | `CacheFactory`、`CoherentCacheFactory`、`CacheProxyFactory`、`ClientSideCacheFactory`、`DistributedCacheFactory`、`CacheSourceFactory`、`KeyConverterFactory`、`JoinKeyExtractorFactory`、`JoinCacheProxyFactory` |
| 字符串约定式装配 | `{cacheName}.ClientSideCache` 等 5 种后缀（[`AbstractCacheFactory.kt:39`](../../cocache-spring/src/main/kotlin/me/ahoo/cache/spring/AbstractCacheFactory.kt)）；“有状态组件只按名称解析”需要靠文档提醒 |
| codec 组合爆炸 | `CodecExecutor` 把 Redis 命令和序列化耦合在一起：String/Hash/Set × String/Object/JSON；Hash 负缓存还存了一个没人读的写入时间（[`HashCodecExecutor.kt:48`](../../cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/HashCodecExecutor.kt)） |
| 用户 API 的三态返回 | `getCache(): CacheValue?` 中 `null` 与 `MissingValue` 的区别需要 KDoc 专门解释；`set(key, ttlAt, value)` 暴露绝对时间 |
| 配置分散在四处 | `@CoCache`（TTL）、`@CaffeineCache`（L2）、`cocache.redis.*`（降级、哨兵、通道）、`cocache.load.*`（回源并发） |
| core 依赖过重 | core 用 `implementation` 依赖 `spring-expression`，用 `api` 依赖 `kotlin-reflect`；API 模块里有静态线程 |
| clientId 每个缓存一个 | `DefaultCacheProxyFactory.kt:44`。身份属于进程，不属于缓存 |
| 每个缓存一个订阅 | 注册新监听者会让同频道重新 SUBSCRIBE，并重置已有监听者（文档注明“无害”） |
| 兼容包袱 | `TtlPolicy` 的隐藏构造函数等 |

---

## 3. 目标架构总览

```
                        ┌────────────────────────────────────────────┐
  用户代码  ───────────▶ │ Cache<K,V> / SetCache<K,E>   (cocache-api) │
                        └───────────────┬────────────────────────────┘
                                        │ CacheRuntime.create(CacheSpec)
┌───────────────────────────────────────▼─────────────────────────────────────┐
│ cocache-core：引擎                                                            │
│                                                                             │
│  CoherentEngine<K,V>                                                        │
│   ├─ LocalTier   (Caffeine，固定实现)  + LocalFence (失效戳)                   │
│   ├─ Coalescer   (SingleFlight，支持单 key 与批量)                            │
│   ├─ Policy      (TTL/抖动/负缓存/本地限龄/提前刷新/降级限龄)                   │
│   ├─ LoadGuard   (回源并发上限)                                               │
│   └─ Metrics     (默认全开)                                                   │
│                                                                             │
│  CacheRuntime：NodeId、Clock、Executors、Backend、InvalidationRouter（每进程一个）│
└───────────────────────────────────────┬─────────────────────────────────────┘
                                        │ CoherenceBackend SPI（字节 + 信封）
            ┌───────────────────────────┴──────────────────────────┐
            ▼                                                      ▼
  cocache-redis（Lettuce 原生）                         core 内置 InMemoryBackend
   ├─ RemoteStore：Lua 协议（读+lease、fence 填充、            （单进程模拟同一协议，
   │   原子写入+广播、原子失效+广播、集合增删）                  供测试与 TCK 使用）
   └─ InvalidationChannel：Pub/Sub | Sharded | (tracking)
            │
            ▼
         Redis / Valkey（单机 / Cluster；只访问主节点）
```

### 3.1 模块

```
cocache-api                    用户 API + SPI，零依赖
cocache-core                   引擎 + InMemoryBackend + 默认时钟；依赖 Caffeine
cocache-redis                  Lettuce 后端；依赖 lettuce-core，不依赖 Spring
cocache-codec-jackson          Jackson 3（tools.jackson，与 Spring Boot 4 一致）Codec，可选
cocache-spring                 Spring Cache 适配（CacheManager），供不用 Boot 的 Spring 应用使用
cocache-spring-boot-starter    自动配置、spec bean 注册、配置分层、actuator、Micrometer
cocache-test                   TCK：BackendProtocolSpec、EngineSpec、SetEngineSpec、HookedBackend（MultiNodeSpec、FaultSpec 推迟到 6.0 之后）
cocache-bom
```

删除：`cocache-spring-redis`、`cocache-spring-cache`（分别并入 `cocache-redis` 与 `cocache-spring`），以及整个 JoinCache 子系统。

依赖方向：`api ← core ← redis`，`api ← codec-jackson`，`core ← spring`，starter 依赖以上全部。core 不再依赖 `spring-expression` 和 `kotlin-reflect`：注解解析与 SpEL 只在 starter 中出现。

基础包名改为 `me.ahoo.cocache`（与 Maven groupId 一致），使 6.0 能在同一仓库中与 5.x 并行开发（§9）。

---

## 4. 核心设计

### 4.1 用户 API（cocache-api）

API 与 SPI 中的时间类型一律使用 `java.time.Duration` / `java.time.Instant`。`kotlin.time.Duration` 是内联值类，会让 JVM 方法名被改写（例如 `put-<hash>`），Java 调用方和第三方实现都无法使用。

```kotlin
interface Cache<K : Any, V : Any> {
    val name: String

    /** 读穿透；不存在（含负缓存）返回 null。 */
    operator fun get(key: K): V?

    /** 读穿透，未命中时用调用方给出的 loader 回源，同样受 lease 与失效戳保护（供 Spring @Cacheable(sync = true) 等使用）。 */
    fun get(key: K, loader: CacheLoader<K, V>): V?

    /** 批量读穿透：一次 L1 往返（单机；Cluster 下按节点并发）+ 一次批量回源；与并发的单 key 读共享在途加载。 */
    fun getAll(keys: Collection<K>): Map<K, V>

    /** 需要区分“确认不存在”时使用。 */
    fun lookup(key: K): Lookup<V>

    /** 写穿透到 L1 并广播；原子地使所有在途填充失效。失败时抛出 CacheWriteException。 */
    fun put(key: K, value: V)
    fun put(key: K, value: V, ttl: Duration)

    /** 必须在数据源提交之后调用（事务内用 afterCommit）。L1 删除与广播尽量原子完成；失败默认进入重试队列（§4.13）。 */
    fun invalidate(key: K)
    fun invalidateAll(keys: Collection<K>)
}

sealed interface Lookup<out V> {
    /** expiresAt：返回的这份副本的到期时间（命中 L2 时是 L2 副本的时间）；null 表示永不过期。精度为时钟刻度（默认 100 ms）。 */
    data class Present<V>(val value: V, val expiresAt: Instant?) : Lookup<V>
    data class Absent(val expiresAt: Instant?) : Lookup<Nothing>
}

interface SetCache<K : Any, E : Any> : Cache<K, Set<E>> {
    fun add(key: K, elements: Collection<E>)
    fun remove(key: K, elements: Collection<E>)
}
```

与 5.x 相比：

- 没有三态 `CacheValue?`。不回源的查看能力只用于运维，放在单独的 `CacheAdmin` 接口里（§4.14），不属于用户 API。
- 没有绝对时间 `ttlAt` 入参。
- `CacheValue`（内部改名 `Entry`）退回到引擎内部。
- `put(key, null)` 不再存在。Spring Cache 适配器把 `put(k, null)`（例如 `@CachePut` 返回 null）映射为 `invalidate(k)`。

### 4.2 SPI（cocache-api）

```kotlin
/** 数据源只负责加载；寿命由 Policy 与 CacheSpec.ttlOf 决定。loadAll 结果中缺少的 key 视为不存在（写入负缓存）。 */
fun interface CacheLoader<K : Any, V : Any> {
    fun load(key: K): V?
    fun loadAll(keys: Collection<K>): Map<K, V> {
        val result = LinkedHashMap<K, V>()
        for (key in keys) load(key)?.let { result[key] = it }
        return result
    }
}

interface Codec<V> {
    fun encode(value: V): ByteArray
    fun decode(bytes: ByteArray): V
}

/** L1 与失效通道是同一个后端的两个面：失效应由串行化写入的那一方发出（A3）。 */
interface CoherenceBackend : AutoCloseable {
    /** 后端能否在同一次原子操作中写入并广播。false 时引擎在写入成功后调用 channel.publish。 */
    val atomicBroadcast: Boolean
    fun store(namespace: String): RemoteStore
    val channel: InvalidationChannel
}
```

基础类型：

```kotlin
class NodeId(val bytes: ByteArray)                        // 16 B，每个 CacheRuntime 一个，随机生成
class Token(val node: NodeId, val seq: Long)               // 24 B，由客户端生成并作为参数传入脚本：构造上唯一，不依赖 Lua 随机数
class Digest(val sha1: ByteArray)                          // 20 B，见 §4.4“损坏与格式演进”

sealed interface Envelope {
    data class Scalar(val bytes: ByteArray) : Envelope
    data class Members(val members: Set<ByteArray>) : Envelope
    data object Absent : Envelope                          // 负缓存
}
data class Fill(val key: String, val token: Token, val envelope: Envelope, val ttl: Duration?)
```

（出现在 API/SPI 中的类型一律是普通类，不使用 `value class`：值类类型的参数和属性会让 JVM 名称被改写，Java 实现方无法使用。）

`RemoteStore` 只处理字节和信封，不知道值的类型，也不持有策略：刷新窗口、TTL 都由引擎作为参数传入。

```kotlin
interface RemoteStore : AutoCloseable {
    /** 读；未命中时原子地取得 lease（lease != null，带自动回收寿命）。剩余寿命 ≤ refresh.window 时可取得刷新令牌。 */
    fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead
    /** 批量读：只为 leases 中的 key 申请 lease；批量路径不申请刷新令牌。 */
    fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead>

    /** 仅当 L1 中仍是该令牌时写入，并按 ttl 重设到期时间。 */
    fun fill(fill: Fill): Boolean
    fun fillAll(fills: List<Fill>): BooleanArray

    /** 释放未使用的令牌：仅当 L1 中仍是该令牌时清除（lease 状态删除 key；刷新令牌只清令牌）。loader 失败、被拒绝、刷新任务被拒绝时调用。 */
    fun release(key: String, token: Token)

    /** 原子：覆盖写入 + 重设到期时间 + 广播；销毁任何令牌。 */
    fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray)

    /** 原子（每个 slot 内）：删除 + 广播。 */
    fun invalidate(keys: Collection<String>, frame: ByteArray)

    /** 载荷损坏时：仅当原始字节仍等于 observed 时转为 lease（条件回收，不会误删并发写入）。 */
    fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean

    /** 集合元素增删，语义见 §4.8。 */
    fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray)
    fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray)
}

data class LeaseRequest(val token: Token, val ttl: Duration)
data class RefreshRequest(val window: Duration, val token: Token, val tokenTtl: Duration)

sealed interface RemoteRead {
    data class Hit(val envelope: Envelope, val format: Int, val ttl: Duration?, val refreshGranted: Boolean) : RemoteRead
    data class Miss(val leaseGranted: Boolean) : RemoteRead
    data class Leased(val remaining: Duration) : RemoteRead   // 其它节点正在回源
}

interface InvalidationChannel {
    fun subscribe(namespace: String, listener: InvalidationListener): Subscription
    /** 仅当 backend.atomicBroadcast == false 时由引擎调用。 */
    fun publish(namespace: String, frame: ByteArray)
}

interface InvalidationListener {
    fun onInvalidated(keys: List<String>, origin: NodeId)
    /** 订阅（重新）建立并得到服务端确认之后调用：此前的事件可能已丢失。 */
    fun onReset()
}
```

消息帧由客户端构造（Lua 只负责 `PUBLISH ARGV[n]`）：`[版本:1B][nodeId:16B][count:varint]([len:varint][key])*`。

5.x 中的 `CacheStore`、`ClientSideCache`、`DistributedCache`、`SetElementsStore`、`CacheEvictedEventBus`、`KeyConverter`、`CodecExecutor` 由上面这组 SPI 取代。L2 不再是 SPI（D6）。`KeyFilter`（含布隆过滤器实现）保留，作为 `CacheSpec` 中的一个可选函数，不再是独立 SPI。

### 4.3 L1 线格式

**key 布局**：`{prefix}{namespace}:{encodedKey}`，`prefix` 默认 `cocache:`，`namespace` 默认是缓存名，二者都可以在 spec 中配置。key 布局属于冻结的线格式（不变量 #9）。

**标量（`Cache<K, V>`）：String = 36 字节定长头 + 载荷**

| 偏移 | 长度 | 字段 |
|------|------|------|
| 0 | 1 | magic `0xC0` |
| 1 | 1 | 格式版本 `f` |
| 2 | 1 | 状态：`V` 值、`M` 负缓存、`L` lease 占位 |
| 3 | 1 | 保留 |
| 4 | 24 | 令牌：`L` 状态下是 lease 令牌，`V` 状态下是刷新令牌，全零表示无 |
| 28 | 8 | 令牌签发时间（服务端 `TIME` 的纪元毫秒，大端 IEEE 754 double，即 Lua `struct.pack('>d')`） |
| 36 | n | 载荷（`Codec<V>` 的输出；`M`、`L` 状态无载荷） |

- 读取：`PTTL` + `GET`，与 5.x 的 `TTL` + `GET` 成本相同。
- 写刷新令牌用 `SETRANGE` 改写头部，不重写载荷，并保留原有 TTL。
- 内存：比 5.x 多 36 B（1 KB 值约 +3%）。

**集合（`SetCache<K, E>`）：Hash**

| 字段 | 含义 |
|------|------|
| `!f` | 格式版本 |
| `+<member>` | 集合成员（`Codec<E>` 的输出），值为空串 |

Hash 只用于状态 `S`：lease 占位与负缓存总是标量形状的 String 头（状态 `L` / `M`），集合也不使用刷新令牌，所以 Hash 不需要状态与令牌字段。key 的类型（String / Hash）本身就区分了形状；任何写入都先 `DEL` 再按新形状写入。

- 读取：`PTTL` + `HGETALL`，客户端去掉成员前缀。
- 代价（必须写进用户文档）：与原生 Set 相比，非整数成员每个多约 16 B（约 +25–35%）；整数成员失去 intset 编码，最多约 7 倍。若基准表明不可接受，备选方案是“原生 Set + 同 slot 的控制 Hash”（§8 Q1）。

**到期时间**：一律用 `PEXPIRE` / `PTTL`，毫秒精度，以相对时长传输，不受实例间时钟偏差影响。**每次状态转换都必须显式重设到期时间**（`PEXPIRE`，永不过期用 `PERSIST`），因为 `HSET`、`SETRANGE` 会保留旧 TTL，否则 lease 的 10 s TTL、负缓存的 TTL 会泄漏到新值上。`PEXPIRE` 的参数钳到至少 1 ms（≤ 0 会删除 key）。

**外部失效**：外部系统不需要理解线格式。随 `cocache-redis` 发布官方的 `invalidate` 脚本和一个小型 Java/CLI 工具，负责构造帧并原子执行 `DEL` + `PUBLISH`。（推迟：6.0 未提供该工具，外部写入方按 architecture.md §6.2 自行 `DEL` 并发布帧。）

### 4.4 一致性协议

#### L1 状态机

```
            read(miss, lease=t)                 fill(t 匹配)
  ABSENT ───────────────────────▶ LEASED(t) ────────────────────▶ VALUE / MISSING
    ▲                               │  │                            │   │
    │  release(t) / lease 到期 /     │  │                            │   │ read(进入刷新窗口, r)
    │  invalidate                   │  │                            │   ▼
    └───────────────────────────────┘  │                     VALUE + 刷新令牌 r
    ▲                                  │                            │ fill(r 匹配) → VALUE（重设 TTL）
    │ invalidate（DEL + PUBLISH）       │                            │ release(r) / r 过期 → VALUE
    └──────────────────────────────────┴────────────────────────────┘
  put：任意状态 → VALUE（整体覆盖，销毁令牌，重设 TTL）+ PUBLISH
  集合元素写入：见 §4.8
```

每个转换都由一个 Lua 脚本原子完成。所有脚本**不带 shebang 标志**：带标志的脚本在 OOM 状态下会被拒绝，而 `invalidate` 恰恰会释放内存。

#### 两层栅栏

| 层 | 令牌 | 填充方 | 失效方 |
|----|------|--------|--------|
| L1 | lease（未命中时由读脚本原子取得）或刷新令牌 | Lua 比较令牌后写入 | `DEL` / 整体覆盖，销毁令牌 |
| L2 | 进程内失效戳（分段 `AtomicLongArray`） | 取戳 → 读 L1 → 写前校验 → 写 L2 → 写后复核，失败则撤销 | **戳 → 修改 L1 → 戳 → 处理 L2**（修改 L1 之后再递增一次） |

L2 写入方的完整顺序：

| 操作 | 顺序 |
|------|------|
| `invalidate` | 戳 → 淘汰 L2 → Lua `DEL`+`PUBLISH` → 戳（无论成功失败）→ 淘汰 L2 |
| `put` | 戳 → 淘汰 L2 → Lua 覆盖+`PUBLISH` → 戳（无论成功失败）→ 淘汰 L2。**不写 L2**，下一次读取从 L1 填充 |
| `add` / `remove` | 戳 → Lua 元素写入+`PUBLISH` → 戳 → 淘汰 L2 |
| 收到远端事件 | 戳 → 淘汰 L2 |
| `onReset` | 全部戳递增 → 清空 L2 |

#### G2 的证明草图

**L1。** 设 A 在 `t_l` 取得 lease `a` 后读取数据源；B 在 `t_c` 提交数据源，在 `t_d` 执行 `invalidate`。

- `t_d < t_l`：A 的数据源读取发生在 `t_l` 之后，也就在 `t_c` 之后，读到新值。
- `t_d > t_l`：`DEL` 销毁了 `a`。令牌由 `NodeId + 序号` 构造，全局唯一，不会被再次签发，所以 A 之后的 fill 必然失败；若 A 的 fill 早于 `t_d`，`t_d` 的 `DEL` 会删掉它。lease 过期的情形同理，不存在 ABA，也不需要约束回源耗时。

**L2（同一节点）。** 设填充者 F 在 `s_F` 取戳后读 L1，失效者 W 在 `s1` 和 `s2` 两次递增戳，中间修改 L1（时刻 `t_m`）。

- F 在 `t_m` 之后读 L1：读到的是新状态，安全。
- F 在 `t_m` 之前读 L1，于是 `s_F` 在 `s2` 之前取得。若 F 的写后复核晚于 `s2`，复核失败，F 撤销自己的写入；若 F 的整个写入早于 `s2`，W 在 `s2` 之后的“淘汰 L2”会删掉它。两种情况下，W 返回时 L2 中都没有 F 的旧值。

对抗验证中的反例（W 只在 `t_m` 之前递增一次：F 在 `s1` 之后取戳，在 `t_m` 之前读到旧值，写入通过两次校验，而本节点自己的事件又被过滤掉）正是被 `s2` 消除的。

**为什么 `put` 不写 L2。** 戳只能判断“写入期间有没有发生失效”，判断不了两次写入在 L1 中的先后。同一节点上两个并发的 `put`：T1 先执行 Lua 写 v1，T2 后执行 Lua 写 v2（L1 = v2），T2 递增戳并写 L2 = v2，T1 随后才递增戳并写 L2 = v1，前后两次校验都能通过；两次写入发出的事件都被本节点过滤，L2 留下 v1 直到 `localMaxAge`（§10 v3-1）。L2 的内容只能来自“读 L1”这一个方向，才能保证它与 L1 的顺序一致。代价是写入后的第一次读取多一次 L1 往返；读己之写仍然成立（`put` 返回时 L2 已淘汰，随后的读取从 L1 读到新值或更新的值）。

**L2（跨节点）。** 节点 X 的 L2 在处理远端事件时递增戳并淘汰，与上面的“`s2` + 淘汰”同构。所以在 X 处理事件之后，X 的 L2 不会保留开始于该失效之前的填充。X 处理事件之前，X 的 L2 可以合法地返回旧值，这就是 G1 中的“事件延迟”。

#### 其它节点持有 lease

读到 `Leased(remaining)` 时，在 `loadThrough` 内部循环：等待 `min(remaining, lease.wait)`（`lease.wait` 默认 200 ms），期间以短退避重新读 L1，**沿用进入 `loadThrough` 时取得的戳**：重读命中就使用，得到 `Miss(leaseGranted = true)` 就由自己回源。等待超时后自行回源，但不写 L1，结果按戳保护填入 L2，并以 `degradedLocalMaxAge` 限龄。

所以“全集群同一 key 只回源一次”只在 loader 耗时小于 `lease.wait` 时成立。对于耗时较长的 loader，应调大 `lease.wait`（以调用方可接受的延迟为限）。

#### lease 的释放与寿命

- loader 抛出异常、被 LoadGuard 拒绝，或者引擎决定不写回时，在 `finally` 中调用 `release(key, token)`。否则所有节点要白白等待 `lease.ttl`，期间没有人能写 L1。
- 节点崩溃留下的 lease 由 `lease.ttl` 回收（默认 10 s）。`lease.ttl` 应大于 loader 的 p99 耗时。
- fill 失败（令牌不匹配）时，结果照常返回，并以 `degradedLocalMaxAge` 戳保护地填入 L2。失败的原因可能是期间发生了失效（本节点的失效由戳拦截，其它节点的失效由事件在事件延迟内淘汰），也可能是 loader 慢于 `lease.ttl`。后一种情况下，如果完全不填 L2，这个 key 会一直未命中，这是活性问题。

#### 提前刷新

读脚本发现剩余寿命不超过 `refresh.window`，并且头部没有未过期的令牌时，写入调用方提供的刷新令牌和签发时间（服务端 `TIME`），返回 `refreshGranted = true`。只有拿到令牌的节点在后台回源，然后用 `fill(r)` 覆盖数据并重设 TTL。

- `invalidate`、`put`、集合元素写入都会销毁令牌，所以刷新也受栅栏保护。
- 令牌签发超过 `lease.ttl` 视为过期，可以被其它节点抢占；被抢占者的 fill 因令牌不匹配而失败。签发时间来自主节点的时钟，故障切换到时钟有偏差的副本只会让令牌提前或推迟过期，不影响正确性。
- 刷新任务被执行器拒绝或执行失败时，调用 `release(key, r)`。
- 效果：全集群每个 key 只有一次刷新，而 5.x 是每个实例各刷新一次。

#### 损坏与格式演进

- **同一格式版本下解码失败**：按未命中处理，由客户端对本次读到的原始字节计算 SHA-1，调用 `reclaim(key, digest, lease)`；Lua 侧用 `redis.sha1hex` 对当前内容计算并比较。只有当内容仍是本次读到的那份时，才把它原子地转为 lease，然后正常回源和 fill。每次读取至多回收一次，并计入指标。digest 只在解码失败时计算，命中路径不付出哈希成本。
  - 标量：只对载荷计算（不含头部：头部中的刷新令牌可能被 `SETRANGE` 改写，内容并未变化）。
  - 负缓存：空字节串。
  - 集合：按 L1 返回的成员顺序拼接 `int32 长度 + 成员字节` 后计算（只含成员，不含控制字段）。Hash 内容不变时返回顺序确定，所以两侧结果一致；内容变了，顺序或字节不同，比较必然失败，正好符合“只在未变时回收”。
- **格式版本高于本节点所知**（滚动升级期间的旧节点）：按未命中处理，**不回收、不写 L1**，回源结果只以 `degradedLocalMaxAge` 进 L2。否则新旧节点会互相覆盖。
- **格式升级分两阶段**：先发布能读取 N+1 版本、但仍写 N 版本的版本；全集群升级完后，再发布写 N+1 的版本。

读路径仍然从不无条件删除。

### 4.5 读路径

```
get(key):
  k = keyEncoder(key)
  e = local.getIfPresent(k)                       // Caffeine
  if e != null && e.expiresAt > clock.now:        // 粗粒度时钟，一次 volatile 读
      if refreshEnabled && e.refreshDue: engine.refreshAsync(key, k)
      return e.valueOrNull
  return engine.miss(key, k)                      // 独立方法，保证 get 足够小、可内联

miss(key, k):
  if keyFilter?.notExist(k): return null          // 负缓存，不写任何层
  observed = stamps.current(k)
  r = coalescer.run(k) { loadThrough(key, k) }    // SingleFlight：先注销，再发布结果；同线程重入快速失败
  if r.stamp < observed: r = coalescer.run(k) { loadThrough(key, k) }   // 读己之写，至多重试一次
  return r.value

loadThrough(key, k):                              // 在 leader（调用方）线程上执行
  stamp = stamps.current(k)                       // 在 SingleFlight 注册之后取戳；整个调用（含等待 lease 的重读）只取这一次
  if pendingInvalidations.contains(k): return loadBypassingL1(key, k, stamp)   // §4.13：失效仍在重试中
  t = tokens.next()
  deadline = now + lease.wait
  rr = remote.read(k, lease = t, refresh = refreshRequest)
  while rr is Leased && now < deadline: backoff(); rr = remote.read(k, lease = t, refresh = refreshRequest)
  when rr:
    Hit    -> entry = decodeOrReclaim(rr)         // 见 §4.4“损坏与格式演进”
              fillLocal(k, entry, stamp, maxAge = localMaxAge)
    Miss   -> try { v = guard { loader.load(key) } } catch (e) { if rr.leaseGranted: remote.release(k, t); throw e }
              entry = policy.entryOf(key, v)      // ttlOf(K, V) 优先，否则按策略（含抖动）；null → 负缓存
              filled = rr.leaseGranted && remote.fill(Fill(k, t, encode(entry), entry.ttl))
              fillLocal(k, entry, stamp, maxAge = if (filled) localMaxAge else degradedLocalMaxAge)
    Leased -> loadBypassingL1(key, k, stamp)      // 等待超时：回源，不写 L1
  L1 异常 -> loadBypassingL1(key, k, stamp)

loadBypassingL1(key, k, stamp):                   // 回源，不写 L1，L2 以 degradedLocalMaxAge 限龄（仍受戳保护）
```

**线程模型（不变量）**：loader 总是在调用方（SingleFlight leader）线程上同步执行，所以事务、SecurityContext、MDC 等 ThreadLocal 都可用，同线程重入检测也照常有效。Future 只包装 L1 I/O：引擎在调用方线程上等待 Lettuce 的 Future，**不在 Lettuce/Netty 事件循环线程上执行任何续接**。这样以后增加异步 API 时（§8 Q8），只需把最外层的等待换成组合，不需要再实现一遍协议。

**批量读**：`getAll` 对未命中的 key 调用 `coalescer.runAll(keys)`：

- 已在途的 key 等待现有调用；其余 key 由本线程注册为 leader，**先完成自己负责的整批加载，再等待别人的**（避免两个批量线程互相等待）。
- 一批 key：一次 `readAll`（共享连接上的命令多路复用：每条命令一个 key，Cluster 按 key 路由），一次 `loader.loadAll`，一次 `fillAll`。
- 每个 key 的戳在注册之后取得。
- `refresh` 同样适用于批量读。

### 4.6 写路径

| 操作 | 步骤（L2 部分见 §4.4 表） | 往返 |
|------|------|------|
| `put` | Lua：整体覆盖 + `PEXPIRE`/`PERSIST` + `PUBLISH` | 1 |
| `invalidate` | Lua：`DEL` + `PUBLISH` | 1 |
| `invalidateAll(N)` | 单机：一个脚本处理全部 key（按批次上限切分）。Cluster：按 slot 分组，每组一个脚本、一次 `PUBLISH`；随机 key 很少同 slot，所以约 N 个脚本、N 次集群总线广播，原子性只在单个 slot 内成立 | 单机 1，Cluster 约 N（并发执行） |
| `add` / `remove` | Lua：元素写入 + TTL + 清除令牌 + `PUBLISH`（§4.8） | 1 |

- `PUBLISH` 在 Lua 中是写命令，所以这些脚本不能用 `EVAL_RO`。
- 每个脚本都有批次上限（默认 512 个 key 或成员），避免长时间阻塞分片。超大集合的整体写入会阻塞分片数十毫秒，这一点要写进用户文档。
- 原子广播消除的是客户端在 `DEL` 和 `PUBLISH` 之间的窗口。投递仍是至多一次：订阅者断线、被输出缓冲区限制断开时仍会丢消息，由 `onReset` 和 `localMaxAge` 兜底。

### 4.7 Redis 后端（Lettuce）

**连接**

- **命令连接**：每个节点一条共享连接，并发的异步命令在其上多路复用（每条命令单独 flush，不依赖 `setAutoFlushCommands(false)`）。大载荷会造成队头阻塞，建议对超大值或大集合的缓存单独配置连接。
- **断线行为**：`DisconnectedBehavior.REJECT_COMMANDS`，并设置命令超时。Lettuce 默认会缓冲断线期间的命令，重连后重放。这样一个已经向调用方抛出异常的 `put` 可能在之后执行，覆盖更新的值，所以必须关闭缓冲重放。
- **只访问主节点**：读脚本会写入 lease 和刷新令牌，不能在副本上执行。CoCache 自己的连接固定访问主节点（`ReadFrom.UPSTREAM`），不继承应用的从库读取配置。由此推出：L1 读不能分流到副本，lease 和令牌写入会产生复制与 AOF 流量。
- **脚本缓存**：Lettuce 不会在 `NOSCRIPT` 时自动回退。后端对每条命令执行 `EVALSHA`，遇到 `NOSCRIPT` 改用 `EVAL` 重试（`EVAL` 会重新缓存脚本）。故障切换、重启、resharding 后新主节点都没有脚本缓存，所以这条路径必须测试。备选方案是 Redis Functions，但新分片需要预先加载。
- **Spring Boot**：由 `DataRedisConnectionDetails` 构造后端（单机、Sentinel、Cluster，用户名与密码），后端自建 Lettuce 客户端，不复用应用的连接工厂（客户端选项是全局的，不能为 CoCache 改动应用的连接行为）。SSL bundle 映射为 Lettuce 的 `SslOptions`（密钥与信任管理器、启用的协议与加密套件）。只支持 Lettuce：5.x 经 Spring Data Redis 支持的 Jedis 在 6.0 中不再支持（见 §11）。

**失效通道**

- **每个进程一个 `InvalidationRouter`**：一条订阅连接，按 namespace 把消息分发给对应引擎。
- **重置时机**：只有在（重新）订阅得到服务端确认之后，才对该连接承载的全部 namespace 调用一次 `onReset`。注册新缓存不会重置已有缓存。
- **心跳**：订阅连接每隔 `heartbeat`（默认 5 s）发送 PING，超时就强制重连，从而触发 `onReset`。这样半开 TCP 连接在秒级内被发现，而不是数分钟后。
- **身份**：`NodeId` 每个 `CacheRuntime` 一个，用于过滤自己发出的事件。

**通道模式**

| 模式 | 广播 | 说明 |
|------|------|------|
| `pubsub`（默认） | Lua 内 `PUBLISH`，原子 | Cluster 中 PUBLISH 经集群总线发往全部节点 |
| `sharded` | 脚本执行后单独 `SPUBLISH`，**非原子**（`atomicBroadcast = false`） | 脚本内的 `SPUBLISH` 要求频道与 key 同 slot。原子版本需要分桶布局（§8 Q2）。收到服务端主动发送的 `sunsubscribed`（slot 迁移）时视为重置并重新订阅，重新订阅失败由心跳重试；Cluster 中心跳 PING 承载订阅的各主节点连接（已在 Cluster 上验证，见 §10） |
| `tracking`（6.x 预研） | 服务端产生 | RFC 0002；在 Lettuce 原生后端下不再受 Spring 限制 |

**环境探测**：启动时执行一次探测脚本（向不在 key 所在 slot 的频道 `PUBLISH`）。在 ElastiCache Serverless 这类把 `PUBLISH` 当作 `SPUBLISH` 执行的环境中，探测会失败并报跨 slot 错误，此时后端以 `atomicBroadcast = false` 运行，并输出告警和指标。

**兼容性**：Redis 7.0+ 与 Valkey 7.2+（用到 `TIME`、`SETRANGE`、`SPUBLISH`）。Valkey 未纳入 6.0 的 CI。不支持静态主从（master-replica）拓扑。

### 4.8 集合：`SetCache` 只用于 L1 是唯一副本的场景

v2 曾把 `SetCache` 分成 `SOURCE` 与 `REMOTE` 两种模式。第二轮验证发现 `SOURCE` 模式下的增量 `add`/`remove` 无法保证正确（§10 v3-2）：两个写者先后在数据库中加入、删除元素 x，它们对 L1 的增量操作可能以相反顺序到达，`remove x` 先执行（集合中还没有 x，什么也不做），`add x` 后执行，L1 里留下 x，直到 TTL。失效是幂等且可交换的，增量操作两者都不满足；而数据库中的顺序，L1 无从得知。

所以 v3 的划分是：

| 数据的权威在哪里 | 声明方式 | 写法 |
|------------------|----------|------|
| 数据源 | 普通的 `Cache<K, Set<E>>`（集合作为一个标量值，由 `Codec` 编码） | 先更新数据源，再 `invalidate` |
| L1 本身 | `SetCache<K, E>`（集合以 Hash 形状存储，§4.3） | 只用 `add` / `remove`，由 L1 串行化 |

`SetCache` 的规则：

| 项 | 规则 |
|----|------|
| loader | **禁止**（spec 校验时拒绝）：没有数据源，也就没有“种子覆盖元素写入”的竞态 |
| `add` | key 不存在、为负缓存或处于 lease 状态时，先清除再创建只含这些成员的集合；为 `S` 时原子加入。重设 TTL，并 `PUBLISH` |
| `remove` | 为 `S` 时原子移除，移空则删除 key；其它状态什么也不做。并 `PUBLISH` |
| 并发顺序 | 同一元素的并发 `add`/`remove` 以 L1 的串行化顺序为准：L1 就是权威，不存在“另一个顺序” |
| 未命中 | 返回空集合，不取 lease；L2 以戳保护缓存这个结果（其它节点的 `add` 通过事件在事件延迟内淘汰它） |
| `invalidate` | **拒绝**（它会销毁唯一副本）；删除数据用 `clear(key)` |
| 提前刷新 | 不适用 |
| 默认 TTL | `FOREVER` |
| 迁移已有数据 | 一次性的 `put(key, fullSet)`（运维操作） |

**L1 作为唯一副本的前提**（写入用户文档；启动时尽力检查，`CONFIG` 被禁用的托管环境中只告警。6.0 未实现启动检查，只写在文档中）：

- Redis 的 `maxmemory-policy` 不能是 `allkeys-*`，否则内存压力下会淘汰权威数据。没有 TTL 的 key 在 `volatile-*` 与 `noeviction` 策略下不会被淘汰。
- 数据的持久性等于 Redis 的持久性（RDB/AOF、复制）。异步复制在故障切换时可能丢失最近的写入，所以 `SetCache` 不适合保存不能丢失的数据。

### 4.9 时间

```kotlin
interface CacheClock { fun nowMillis(): Long }   // cocache-api
```

- 默认实现 `TickingClock` 放在 core，每 100 ms 刷新一次，由 `CacheRuntime` 启动和停止，不再是静态线程。
- 测试注入 `ManualClock`，TTL、刷新窗口、限龄相关的用例全部变成确定性测试，不需要 sleep，也不需要“±1 s”偏差。
- L2 条目存放 `expiresAtMillis`（本地时钟加上 L1 返回的 `PTTL`），精度为一个时钟刻度。命中判断与 5.x 一样是一次 volatile 读加一次比较。

### 4.10 策略与配置

```kotlin
class CachePolicy(                                // 不可变，带 Builder；Java 可用
    val ttl: Duration = Duration.ofHours(1),
    val ttlJitter: Duration = Duration.ofMinutes(1),
    val absentTtl: Duration = Duration.ofMinutes(1),
    val localMaxAge: Duration = Duration.ofMinutes(5),       // 有限默认值：事件丢失时 L2 陈旧的上界（G1）
    val degradedLocalMaxAge: Duration = Duration.ofSeconds(10),
    val refreshWindow: Duration? = null,
    val lease: LeasePolicy = LeasePolicy(ttl = Duration.ofSeconds(10), wait = Duration.ofMillis(200)),
    val load: LoadPolicy = LoadPolicy(maxConcurrent = 0, acquireTimeout = Duration.ofSeconds(1)),
    val local: LocalPolicy = LocalPolicy(maximumSize = 10_000, initialCapacity = null, expireAfterAccess = null),
    val invalidateFailure: InvalidateFailureMode = InvalidateFailureMode.RETRY,
    val readFailure: ReadFailureMode = ReadFailureMode.DEGRADE,
)
```

- **按 key 或值决定寿命**：`CacheSpec<K, V>.ttlOf: ((K, V) -> Duration?)`。返回非 null 时优先于 `ttl`，**不加抖动**；返回 null 时按 `ttl` 加抖动。负缓存总是用 `absentTtl`。
- **来源与优先级**（后者覆盖前者）：内置默认值 → 声明（spec DSL）→ `CacheSpecCustomizer` → 配置文件 `cocache.defaults.*` 与 `cocache.caches.[<name>].*`。配置文件放在最后，运维调参不需要重新编译。配置文件中的 `ttl` 不影响 `ttlOf`。
- **校验**：所有缓存创建完成后（`SmartInitializingSingleton`）统一校验。配置文件中出现未声明的缓存名、`refreshWindow ≥ ttl − 实际抖动`（抖动幅度不超过 `ttl / 2`）、`refreshWindow ≥ localMaxAge`、时长超过 100 年、使用已删除的 5.x 配置项等都会导致启动失败。

### 4.11 运行时与声明

```kotlin
val runtime = CacheRuntime.builder()
    .backend(RedisBackend.standalone(RedisURI.create("localhost", 6379)))
    .clock(TickingClock())
    .build()

val users: Cache<String, User> = runtime.create(
    cacheSpec<String, User>("user") {
        keyEncoder { it }                 // 默认 toString
        codec = jacksonCodec<User>(jsonMapper)
        loader { id -> userRepository.findById(id).orElse(null) }
        policy { ttl = Duration.ofMinutes(2); local = LocalPolicy(maximumSize = 1_000_000) }
    }
)
```

- `CacheSpec` 是不可变数据。所有有状态组件都由 `runtime.create` 按 spec 新建，所以不存在“两个缓存共享一个 L2”的可能。
- 同一个 runtime 中缓存名重复时，`create` 失败。
- `CacheRuntime` 关闭时，按创建的逆序关闭所有引擎、订阅和连接。

### 4.12 Spring 集成（starter）

- **两种声明方式，同一个引擎**：接口式声明在启动时翻译为 spec，之后与 spec bean 完全相同（同一套 `CacheSpecCustomizer`、配置文件、引擎与 TCK）。
- **声明方式一：接口式（推荐给 Spring 用户，与 5.x 写法一致）**：

  ```kotlin
  @CoCache(ttl = 120, localMaxAge = 60)
  @CaffeineCache(maximumSize = 100_000)
  interface UserCache : Cache<String, User>

  @EnableCoCache(caches = [UserCache::class])
  @SpringBootApplication
  class App
  ```

  - 每个接口注册为一个 bean：bean 名为缓存名（默认接口简单类名，与 5.x 相同），类型为接口本身。与已有 bean 重名时启动失败。
  - 泛型取自接口继承的 `Cache<K, V>` / `SetCache<K, E>`，必须是具体类型，否则启动失败。
  - 数据源：`@CoCache(loader = "beanName")`，否则按 `CacheLoader<K, V>` 唯一匹配（多个候选时启动失败），找不到则没有数据源。
  - 编解码：`@CoCache(codec = "beanName")`，否则按 `Codec<V>` 唯一匹配，找不到则按值类型使用 Jackson。
  - key：`@CoCache(keyExpression = "#{id}")` 为 SpEL 模板（以业务 key 为根对象，只在 starter 中解析），否则 `toString()`。
  - 注解中的时长单位为秒（与 5.x 一致），`UNSET` 表示交给默认值与配置文件；配置文件 `cocache.caches.[name].*` 的优先级高于注解。
  - 代理（`CacheProxies`，core）：`Cache` / `SetCache` 的方法按方法名直接调用引擎，不经 `Method.invoke`，异常原样抛出；用户接口的 default 方法经 `InvocationHandler.invokeDefault` 执行。代理开销见 §7.1。
  - 命名：主接口叫 `Cache`、注解叫 `@CoCache`，与 5.x 的心智模型一致（`Cache` 与 Spring 的 `org.springframework.cache.Cache` 同名，5.x 已是如此；CoCache 自己的 Spring Cache 适配在内部以别名引用后者）。
- **声明方式二：spec bean（底层方式，也用于不用注解的场景）**：`@Bean fun userSpec(): CacheSpec<String, User> = cacheSpec("user") { … }`。
  - starter 的 `BeanFactoryPostProcessor` 从工厂方法**声明的泛型返回类型**解析 `K`、`V`，不实例化 spec。
  - 为每个 spec 注册一个 `Cache<K, V>` bean（`SetCacheSpec` 对应 `SetCache<K, E>`）：bean 名为 spec bean 名去掉 `Spec` 后缀（`userSpec` → `user`），没有该后缀时加 `Cache` 后缀；用 `setTargetType(ResolvableType)` 声明泛型，实例延迟创建。与已有 bean 重名时启动失败。
  - 泛型无法解析时启动失败，例如返回原始类型、通配符，或者通过 `registerBean(Supplier)` 注册。
  - 数据源与 key 编码在 spec 中显式指定（`loader`、`keyEncoder`）。
- 同一个缓存名既有接口声明又有 spec bean 时，创建第二个时因缓存名重复而启动失败。
- v1 中“MethodHandle 分发表”的说法已撤回：JDK 18+ 的 `Method.invoke` 本身就基于 MethodHandle，存在表里的 MethodHandle 不是常量，JIT 无法内联。当前代理改为按方法名直接分派。
- **Spring Cache 适配**：`cocache-spring` 提供 `CoCacheSpringCacheManager`（starter 自动配置）。语义：
  - 负缓存视为未命中；`put(k, null)` 映射为 `invalidate`；`clear` 只清本节点的本地副本。
  - `@Cacheable(sync = true)`（`get(key, valueLoader)`）经引擎的 `get(key, loader)` 回源：受 lease 与失效戳保护，本节点经 SingleFlight、跨节点经 lease 合并加载，回源期间发生的失效不会被旧结果覆盖。
  - 不带 `sync` 的 `@Cacheable` 由 Spring 执行“读未命中 → 调用方法 → `put`”，`put` 是无条件写入，没有这层保护；需要 6.0 的一致性保证时应使用 `sync = true`（写进用户文档）。
  - 有 spring-tx 时，starter 用 Spring 的 `TransactionAwareCacheManagerProxy` 包装 CacheManager：`@CacheEvict` / `@CachePut` 在事务提交之后执行，与 `TransactionalInvalidator` 一致。
- **事务**：`invalidate` 必须在提交之后执行。`cocache-spring` 提供 `TransactionalInvalidator`（starter 在有 spring-tx 时自动配置）：事务内调用时登记到当前事务的 `afterCommit`，同一事务内对同一缓存的失效合并为一次 `invalidateAll`，回滚时不失效；没有事务时立即失效。登记按事务的同步列表划分作用域，`REQUIRES_NEW` 的内层事务在自己提交之后失效。在提交之前失效本身就是一个 G2 漏洞：并发的回源可能读到提交前的数据。

### 4.13 失败语义

| 类别 | 操作 | L1 故障时 |
|------|------|-----------|
| 填充 | 读、fill、刷新、种子 | 按未命中处理，回源结果只进 L2 并按 `degradedLocalMaxAge` 限龄，计数并告警。可以按缓存配置 `readFailure = THROW`（对应 5.x 的 `strictFailure = true`）：L1 读失败直接抛出 `CacheReadException`，fill、释放令牌等尽力而为的操作仍然降级 |
| 失效 | `invalidate`、`invalidateAll` | 本地效果（两次递增戳、淘汰 L2）先生效；L1 失败时进入有界重试队列（默认开启；指数退避；操作幂等），**不抛出**。排队期间，L1 中仍是旧值：该 key 的未命中**跳过 L1** 直接回源，并以 `degradedLocalMaxAge` 填 L2（这个检查只在未命中路径上，不影响命中路径）；重试成功后，本节点再递增一次戳并淘汰 L2（本节点自己的事件会被过滤，不能依赖它）。队列溢出或重试耗尽时记录错误并计数，此时其它节点的陈旧上界为 L1 剩余 TTL（L2 到期后会从旧的 L1 重新填充；运行时关闭时仍在排队的重试同样计为放弃）。需要同步结果的调用方可以配置 `invalidateFailure = THROW` |
| 显式写入 | `put`、`add`、`remove` | 本地效果先生效，然后抛出 `CacheWriteException`。结果未知（超时）时，L1 可能已经生效；这些操作都是幂等的，可以安全重试 |

回源并发上限（bulkhead）保持 5.3 的语义：只计 leader，批量回源只占一个许可。

### 4.14 可观测与运维

- **默认全开的指标**：分层命中（L2/L1）、未命中、回源（耗时、异常、拒绝）、合并数、lease（取得、等待、等待超时、fill 失败、释放）、刷新、降级填充、回收、未知格式、失效（发出、收到、写入失败、重试、溢出）、重置、心跳超时、原子广播是否可用。（6.0 实际提供的计数见 `CacheMetrics` 与 starter 的 `CacheCounters`：L1 命中、未命中、过滤、回源耗时与失败与拒绝、lease 等待与等待超时、fill 被拒、降级填充、戳作废、回收、未知格式、刷新与刷新被拒、L1 失败、收到的失效、写入失败、失效重试与丢弃、重置；没有 L2 命中、合并数、lease 取得与释放、心跳超时与原子广播能力。）
- **L2 命中计数**：使用分条带计数器（条带数为 2 的幂，每条带独占缓存行，按线程哈希选择条带，CAS 或 `getAndAdd`）。验收：8 线程、至少 5 轮交替运行、比较中位数与 95% 置信区间，回退不超过测量噪声（约 ±4%）。达不到就保持默认关闭。
- **`CacheAdmin`**（运维接口，不属于用户 API）：按字符串 key 查看 L2 与 L1（不回源，不取 lease）、强制失效、查看策略与指标，供 actuator 端点使用。（6.0 只实现了 `name`、`metrics`、`localSize` 与 `clearLocal()`；按 key 查看、强制失效与策略查看推迟。actuator 端点 `cocache` 提供缓存列表、单个缓存的计数与清空本节点 L2。）
- **迁移说明**：L1 不再能直接用 `redis-cli GET` 读出 JSON；跨服务直接 `HGET` 读取缓存字段的用法不再支持。这两点都要写进迁移指南。

---

## 5. 删除清单

| 删除 | 由什么取代 |
|------|------------|
| 5.x 的 `Cache`/`CacheGetter`/`CacheSetter`、三态 `getCache`、`set(key, ttlAt, value)`、`set(key, null)` | 6.0 的 `Cache`（Q9）、`Lookup`、`invalidate` |
| `CacheValue` 出现在用户 API 中 | 内部 `Entry` |
| `CacheStore`、`ClientSideCache`、`DistributedCache`、`MapClientSideCache` | 固定 Caffeine L2 + `RemoteStore`；测试用 `InMemoryBackend` 与 `ManualClock` |
| `SetElementsStore.fillCache`/`evictIfUnchanged`、写后复核后撤销 L1 | L1 lease 栅栏 + §4.8 的模式规则 |
| `_nil_` 哨兵、`missing-guard-sentinel` 配置、元素不得等于哨兵的约束 | 线格式中的状态字节（集合 Hash 只有 `!f` 控制字段） |
| `CodecExecutor` 体系（String/Hash/Set × String/Object/JSON）、Hash 形状（`ObjectToHash`） | `Codec<V>` + 标量 / 集合两种形状 |
| `CacheEvictedEventBus`、`CacheEvictedEvent`、`key@@publisherId` 文本格式、每个缓存一个订阅 | `InvalidationChannel` + 进程级 Router + 二进制帧 + 官方外部失效工具 |
| `ClientIdGenerator`（每个缓存一个 id） | 每个进程一个 `NodeId` |
| 9 种工厂、bean 名后缀约定、`AbstractCacheFactory` | `CacheSpec` + `CacheRuntime` + `CacheSpecCustomizer` |
| `me.ahoo.cache` 包下的 `@CaffeineCache` | `CachePolicy.local`（保留 `maximumSize`、`initialCapacity`、`expireAfterAccess`；未加入 `weigher`）；接口式声明仍可用 `me.ahoo.cocache.api.annotation.CaffeineCache` |
| `object CacheClock`（静态线程） | 可注入的 `CacheClock` |
| `TtlPolicy`（含兼容构造函数）、`strictFailure`、`recordClientSideHits` | `CachePolicy`、§4.13 的规则、分条带计数器 |
| `CacheSource` 返回带 `ttlAt` 的 `CacheValue` | `CacheLoader` + `ttlOf(K, V)` |
| JoinCache 全套（`JoinCache`、`JoinValue`、`JoinKeyExtractor`、`@JoinCacheable`、SpEL 提取器、代理与工厂） | 文档中的组合示例（两次 `getAll`） |
| 5.x 接口式声明的实现：`me.ahoo.cache` 包下的 `@CoCache`、`@CaffeineCache`、`@EnableCoCache`、`CacheInvocationHandler`、`CacheProxyFactory`、`CacheDelegated`/`CacheMetadataCapable`、基于 `kotlin-reflect` 的元数据解析 | 6.0 的同名注解（`me.ahoo.cocache.api.annotation`）、`@EnableCoCache`（starter）、`CacheProxies`（core），注解启动时翻译为 spec |
| core 对 `spring-expression`、`kotlin-reflect` 的依赖 | 移到 starter |
| `StringRedisTemplate`、`RedisMessageListenerContainer` | Lettuce 原生 |

---

## 6. 新不变量（取代 AGENTS.md 中的对应列表）

1. **栅栏规则**：任何填充都必须持有读取之前取得的令牌（L1 lease 或刷新令牌；L2 失效戳）。L1 的失效通过销毁令牌完成；L2 的失效方（含 `put`、元素写入）在修改上游**之前和之后**各递增一次戳并淘汰 L2。**L2 只由读 L1 的路径填充**，写入方从不直接写 L2。
2. **L1 未命中就是未命中**：key 不存在或处于 `L` 状态都不代表负缓存。负缓存只有状态 `M` 一种形态。
3. **读路径从不无条件删除**：同版本的损坏只能通过 `reclaim`（比较 digest）转为 lease；未知的更高版本既不回收也不写回。
4. **能原子广播就原子广播**：后端以 `atomicBroadcast` 声明能力；为 false 时由引擎在写入成功后发布，TCK 按能力分别验证。
5. **SingleFlight 先注销调用、再发布结果**（读己之写依赖这个顺序）；同线程对同一 key 的重入快速失败；批量合并先完成自己负责的 key 再等待别人。
6. **loader 在调用方线程上执行**；Future 只包装 I/O，事件循环线程上不执行续接。
7. **令牌必须释放或过期**：loader 失败、被拒绝，以及刷新任务被拒绝时都要 `release`。
8. **每次 L1 状态转换都显式重设到期时间。**
9. **线格式在一个大版本内冻结**：key 布局、标量头、集合字段、消息帧、频道命名。格式演进通过版本号两阶段完成。
10. **集合元素增删只用于 L1 是唯一副本的 `SetCache`**，在 L1 中原子完成，且 `SetCache` 不得配置 loader。数据源中的集合只能用 `invalidate` 维护。
11. **有状态组件只能由运行时创建**：用户只提供 spec 和工厂。
12. **CoCache 的 L1 连接只访问主节点**，断线时拒绝命令而不是缓冲重放。
13. **存储脚本只执行协议，不持有策略**：TTL、刷新窗口、令牌都由引擎作为参数传入。
14. **代理不包装异常**：接口式声明的代理按方法名直接调用引擎，异常原样抛出；spec bean 注入的就是引擎本身，没有代理层。
15. **命中路径的改动必须附带数据**：1 线程与 8 线程，至少 5 轮交替运行，报告中位数与置信区间，并包含至少三个配置不同的缓存。

---

## 7. 性能预算与验证

| 路径 | 5.4 | 6.0 目标 | 验证方式 |
|------|-----|----------|----------|
| L2 命中（spec bean） | 直接调用引擎（基线） | 不退化（差异在噪声内） | `l2Hit`，三个配置不同的缓存 |
| L2 命中（接口式声明，经代理） | 5.4 经代理：比直接调用慢约 14% | 不劣于 5.4 经代理 | `l2HitViaInterface` 对 `l2HitViaProxy`，见 §7.1 |
| L1 命中 | 1 RTT（TTL + GET） | 1 RTT（PTTL + GET），吞吐不退化；每个 key 内存 +36 B | `l1Read`，标量值 64 B / 1 KB / 10 KB；测内存 |
| 集合 L1 命中 | 1 RTT（TTL + SMEMBERS） | 1 RTT（PTTL + HGETALL）；公布吞吐与内存差异 | 新增 `setRead`，成员数 10 / 100 / 1000（推迟，6.0 未提供） |
| 未命中回源 | 读 + 回源 + 写，共 2 RTT | 读（含 lease）+ 回源 + fill，共 2 RTT | `missLoad` |
| 单 key `put` / `invalidate` | 各 2 RTT | 各 1 RTT | `set`、新增 `invalidate` |
| 批量 N 个 key | pipeline（专用连接、EVAL） | 共享连接多路复用（EVALSHA，NOSCRIPT 回退） | `l1BatchRead`，N = 10/100 |
| M 个实例同时未命中同一 key | M 次回源 | 1 次（loader 耗时 < `lease.wait` 时） | `MultiNodeSpec` 计数断言（推迟；6.0 由 `EngineSpec.concurrentMissesAcrossNodesLoadOnceThroughLease` 在两个运行时上断言） |
| 热点 key 提前刷新 | 每个实例各 1 次 | 全集群 1 次 | 同上（6.0：`EngineSpec.refreshAheadReloadsOnceAcrossNodes`） |

### 7.1 实测（2026-10-19，本机单机 Redis 7，JDK 17）

`CoCacheBenchmark`（6.0，`cocache-redis/src/jmh`）与 `RedisCacheBenchmark`（5.4，`cocache-spring-redis/src/jmh`）方法一一对应。命中路径两侧交替运行至少 3 轮，取中位数；其余路径各 1 轮。单位 ops/s。

| 路径 | 线程 | 5.4 | 6.0 | 变化 |
|------|------|-----|-----|------|
| L2 命中（直接调用引擎 / spec bean） | 1 | 34,539,255 | 54,828,186 | +59% |
| | 8 | 95,243,583 | 146,660,460 | +54% |
| L2 命中（5.4 经 JDK 代理） | 1 | 29,550,427 | — | 代理本身比直接调用慢约 14% |
| | 8 | 96,508,118 | — | |
| L2 命中（接口式声明，经 6.0 代理；另一组 3 轮交替运行） | 1 | 5.4 经代理 29,543,891 | 48,601,358 | +64%（同组 6.0 直接调用为 59,754,507，代理开销约 19%） |
| | 8 | 5.4 经代理 116,307,947 | 148,915,869 | +28%（同组 6.0 直接调用为 209,251,809，代理开销约 29%） |
| L2 命中（轮流访问三个配置不同的缓存） | 1 | 5,002,489 | 6,150,139 | +23% |
| | 8 | 11,692,804 | 12,958,644 | +11% |
| L1 读 | 1 | 9,854 | 9,771 | −1%（噪声内） |
| | 8 | 49,918 | 47,093 | −6%（两侧区间大幅重叠：6.0 为 46,730–58,313，5.4 为 46,934–54,110） |
| 批量读 100 个 key | 1 | 1,027 | 1,397 | +36% |
| | 8 | 1,322 | 1,792 | +36% |
| 未命中回源 | 1 | 4,768 | 4,649 | −2%（噪声内） |
| | 8 | 20,798 | 19,666 | −5%（5.4 误差 ±85%） |
| 写入 `put` | 1 | 4,072 | 7,481 | +84% |
| | 8 | 15,425 | 38,170 | +147% |
| 失效 `invalidate` | 1 | 4,459 | 7,629 | +71% |
| | 8 | 14,825 | 39,346 | +165% |

结论：

- **G3 成立**：L2 命中显著提升；写入与失效从 2 次往返降为 1 次，吞吐接近翻倍；批量读改为共享连接多路复用后提升约三分之一。
- **接口式声明**：经 6.0 代理的命中仍比用户现在使用的 5.4（同样经代理）快 28%–64%；需要极致命中性能的缓存可以改用 spec bean（直接注入引擎）。
- **多缓存的命中代价**：轮流访问三个配置不同的缓存时，每次命中的成本约为单缓存的 2.7 倍（5.4 约为 2 倍），原因是调用点变成多态（Caffeine 按配置生成不同的节点类，`KeyEncoder` 等 lambda 也各不相同）。两个版本都受影响，6.0 仍然更快。D6 的“单态”只针对 CoCache 自己的调用层，这一点已在 D6 中说明。
- **L1 读**：第一版读脚本多一次 `TYPE` 并在每次执行时重新定义写路径的辅助函数，8 线程下低约 8%（6 轮中位数）。改为 `PTTL` + `pcall GET` 并精简读脚本之后，1 线程持平，8 线程的差异落在多轮波动范围内。

所有阈值都以统计结果判定：至少 5 轮交替运行，比较中位数，置信区间不得与退化区间重叠。单次运行的差异小于 ±4% 噪声时不作结论。

---

## 8. 风险、权衡与未决问题

| # | 问题 | 倾向 |
|---|------|------|
| Q1 | 集合的编码：Hash（当前方案） vs 原生 Set + 同 slot 控制 Hash | 先实现 Hash，由 `setRead` 的吞吐与内存决定。备选方案需要 hash tag，并且每次操作涉及两个 key |
| Q2 | Sharded 模式的原子广播：分桶布局 `cocache:{name#b}:…` / `cocache:inv:{name#b}` | 默认非原子。分桶数 B 会成为线格式的一部分，并把单个缓存的数据限制在 B 个 slot 上；需要在大集群中实测分布与订阅数后再决定 |
| Q3 | 等待 lease 时轮询 L1 vs 订阅单 key 通知 | 先轮询；指标显示等待占比高时再考虑通知 |
| Q4 | `put` 是否保留 | 保留，但文档首推 `invalidate`。写穿透只适合由写入方算出最终值的场景 |
| Q5 | 令牌过期的实现 | 头部 / `!i` 记录签发时间（服务端 `TIME`），超过 `lease.ttl` 可以被抢占；不使用字段级 TTL（`HEXPIRE` 需要 Redis 7.4+ / Valkey 9） |
| Q6 | 故障切换丢写 | Redis 异步复制可能丢失 `DEL` 或 fill，旧值回到 L1。旧主节点在丢写之前可能已经发出广播，各节点淘汰 L2 后又会从旧值重新填充；非 sharded 的集群 Pub/Sub 也不一定会因此重连。所以这种情况的上界是 L1 侧的 `ttl` 与 L2 侧的 `localMaxAge`（A4）。需要更强保证的部署可以在切换后执行全量失效（运维操作） |
| Q7 | JDK 基线 | 维持 17（Spring Boot 4 的下限）。代码对虚拟线程友好：I/O 路径上没有 `synchronized` |
| Q8 | 是否引入协程 / 异步 API | **6.0 不引入**。L2 命中是纳秒级、没有 I/O，挂起函数只会增加成本（G3）；api 必须零依赖，Java 用户无法自然使用挂起函数；同步与挂起两套 API 会让协议证明和 TCK 都翻倍；阻塞 loader 的高并发由虚拟线程解决。只有在 WebFlux / 协程栈配合非阻塞 loader（R2DBC、WebClient）时才有收益。**预留**：§4.5 的未命中路径用 Future 包装 I/O。有需求时，在 core 中增加基于 `CompletableFuture` 的 `AsyncCoCache` 与 `AsyncCacheLoader`（L2 命中返回已完成的 Future；异步 loader 的线程模型单独定义），并新增可选模块 `cocache-coroutines`，用少量扩展函数提供挂起版本：先同步查 L2，未命中再 `await()`。协程依赖只出现在该模块中。触发条件：reactive 用户提出需求，或者指标显示事件循环线程上出现阻塞的未命中读取 |
| Q9 | `@CoCache` 接口式声明 | **已定（维护者复议，2026-10-19）：保留**。初版结论“只保留 spec bean”低估了接口式声明对用户的便利；代理成本应与用户现在使用的 5.4（同样经代理）比较，而不是与 6.0 直接调用比较。实现：注解启动时翻译为 spec，JDK 代理按方法名直接分派（不引入 ByteBuddy）；主接口改名为 `Cache`、注解沿用 `@CoCache`，与 5.x 写法一致 |
| Q10 | `invalidateAll` 在 Cluster 下的成本 | 默认按 slot 原子（约 N 个脚本、N 次广播）。若 N 很大，可以选择“按 slot `DEL`，最后发一个合并帧”的非原子模式，用一次广播换掉 N 次 |

---

## 9. 实施顺序

6.0 在新包 `me.ahoo.cocache` 下与 5.x 并行开发，新旧模块在同一个仓库中共存，所以每一步都能独立合入并通过 `check`；最后一步删除旧模块。

0. **先修 5.x**：§10 C1（同实例失效吞没、`setCache` 的 L2 写入无保护）是 5.x 现存的缺陷，先在 5.4.x 中修复并补上复现用例（[#628](https://github.com/Ahoo-Wang/CoCache/issues/628)）。
1. **TCK 先行**：`cocache-test` 新增 `BackendProtocolSpec`（状态机、令牌、到期时间重设、原子广播能力、reclaim、release、NOSCRIPT 回退）和 `ManualClock`；`InMemoryBackend` 首先通过。（NOSCRIPT 回退已实现于 `RedisConnections`，但 6.0 没有专门用例；`ManualClock` 放在 `cocache-core`。）
2. **api + core**：`Cache`、`CacheSpec`、`CacheRuntime`、`CoherentEngine`、批量 SingleFlight、`CachePolicy`。把 `DefaultCoherentCacheSpec` 的 in-flight 系列用例迁移到新引擎，并补上 §10 中每个协议发现的复现用例。
3. **cocache-redis**：Lua 协议、线格式、命令多路复用、Router、心跳、环境探测；单机、Cluster 与 Valkey CI；JMH 对比 5.4（§7）。（Valkey CI 推迟到 6.0 之后。）
4. **cocache-spring + starter**：spec 注册、`@CoCache` 接口式声明与 `@EnableCoCache`、配置分层与校验、Spring Cache 适配、事务内失效 `TransactionalInvalidator`；actuator、Micrometer 随后补齐。（已完成；过渡前缀 `cocache.next.*` 已在第 5 步改为 `cocache.*`。）
5. **切换、删除与文档**（已完成；一个破坏性 PR，前置条件：观测接入与 `TransactionalInvalidator` 已合入；Sharded 通道已合入或明确推迟到 6.x）：
   - **切换**：6.0 自动配置改为默认启用，过渡前缀 `cocache.next.*` 改为正式前缀 `cocache.*`，`CoCacheNextAutoConfiguration` 等类去掉 `Next`。
   - **删除 5.x**：`cocache-spring-redis`、`cocache-spring-cache` 两个模块；`cocache-api`、`cocache-core`、`cocache-spring`、starter、`cocache-test` 中 `me.ahoo.cache.*` 包下的全部代码，包括 `@CoCache` / `@CaffeineCache` / `@JoinCacheable` 注解、`@EnableCoCache`、代理与工厂、JoinCache、SpEL key 转换器（6.0 有各自的替代，D11 的 JoinCache 除外）；core 去掉 `spring-expression` 与 `kotlin-reflect` 依赖；5.x 的 JMH 基准。
   - **示例**：`cocache-example` 改用 6.0 的 `@CoCache` 与 `@EnableCoCache`（另附一个 spec bean 示例），starter 测试随之迁移。
   - **文档**：重写 `docs/architecture.md`（以本 RFC 为准，删除“已知的固有窗口”）、wiki（中英文）、`skills/cocache/`、AGENTS.md 的不变量与 TCK 表；版本号改为 6.0.0。
   - **迁移指南**（changelog）：
     - 接口式声明：写法不变，只换 import（`me.ahoo.cache.api.annotation.CoCache` → `me.ahoo.cocache.api.annotation.CoCache`，`me.ahoo.cache.api.Cache` → `me.ahoo.cocache.api.Cache`，`me.ahoo.cache.spring.EnableCoCache` → `me.ahoo.cocache.spring.boot.EnableCoCache`）。注解属性改名：`ttlAmplitude` → `ttlJitter`、`missingTtl` → `absentTtl`、`clientSideTtl` → `localMaxAge`、`refreshAhead` → `refreshWindow`。数据源由 `{name}.CacheSource` bean 改为按 `CacheLoader<K, V>` 泛型匹配（或 `@CoCache(loader = …)`）。缓存名保持不变；L1 key 改为 `{keyPrefix}{namespace}:{key}`（`keyPrefix` 默认 `cocache:`，`namespace` 默认等于缓存名），5.x 的 `keyPrefix + key` 不再沿用。
     - `{cacheName}.ClientSideCache` 等 bean 名后缀约定 → `CacheSpecCustomizer` 与 `cocache.caches.[name].*`。
     - `getCache` / `CacheValue` / `set(key, ttlAt, value)` → `get` / `lookup` / `put(key, value, ttl)`；`set(key, null)` → `invalidate`。
     - `SetElementsCache` → `SetCache`（只用于 L1 是唯一副本的集合；数据源中的集合改用 `Cache<K, Set<E>>` + `invalidate`）。
     - `cocache.redis.strict-failure=true` → `readFailure = THROW`（配置项 `cocache.caches.[name].read-failure=throw`）。
     - **不再支持的能力**（见 §11）：Jedis（6.0 只支持 Lettuce；使用 Jedis 的应用需要引入 Lettuce，CoCache 自建客户端，与应用自己的 Redis 客户端互不影响）；显式写入负缓存 `setCache(key, MissingValue)`（改为 `invalidate`，或让数据源返回 `null`，由引擎写入负缓存）；可替换的 L2（`{name}.ClientSideCache`，如 `MapClientSideCache`；6.0 固定为 Caffeine，容量用 `@CaffeineCache` 或 `cocache.caches.[name].local-maximum-size` 配置，测试改用 `InMemoryBackend` 与 `ManualClock`）。
     - **不能滚动升级**：线格式与失效频道都已改变，5.x 与 6.0 节点不能在同一集群中混跑，需要整体切换。切换后 L1 中 5.x 留下的值会被识别为格式 0，按损坏条件回收后回源，无需手工清理；也可以在切换时按前缀删除旧 key 以立即释放内存。

验收：architecture.md 删除“已知的固有窗口”（已完成）；`MultiNodeSpec` 与 `FaultSpec` 推迟到 6.0 之后（见 roadmap），6.0 由 `EngineSpec`（两个运行时共享一个后端、`HookedBackend` 注入故障）覆盖其中大部分交错。原计划：`MultiNodeSpec` 覆盖 §4.4 的全部交错（失效先于 lease、晚于 lease、晚于 fill；同节点失效与并发读；同节点与跨节点的并发 `put`；失效重试期间的未命中）、全集群单次回源和单次刷新；`FaultSpec` 覆盖 Redis 故障时的降级限龄、失效重试与溢出、写入抛出后重试的幂等性、断线时命令不被重放、故障切换后的 NOSCRIPT。

---

## 10. 对抗验证记录

### v1 → v2

v1 由三路独立的对抗审查验证：协议正确性（构造交错反例）、Redis/Lettuce 事实核查、API 与可运维性。下表列出被采纳的发现。“5.x”一列表示 5.x 现有实现是否同样受影响。

| ID | 严重度 | 发现 | 5.x | 处理 |
|----|--------|------|-----|------|
| C1 | 严重 | 同实例失效吞没：失效方只在修改 L1 之前递增一次戳，并发读在递增之后取戳、在修改之前读到旧值，写入 L2 后通过两次校验，本节点自己的事件又被过滤。`put` 的 L2 写入同样无保护，另一实例更晚的写入事件若在“写 L1”与“写 L2”之间到达，也会留下旧值 | 是（`DefaultCoherentCache.kt:362`、`:423`；[#628](https://github.com/Ahoo-Wang/CoCache/issues/628)） | D2、A2、§4.4 两次递增、不变量 #1；§9 第 0 步先修 5.x |
| C2 | 严重 | 集合元素写入：lease 期间的 `remove` 被种子填充复活；`add` 覆盖 lease 后丢掉种子；`SOURCE` 模式下 `add` 在 key 不存在时产生部分集合；元素写入不清除刷新令牌，被刷新覆盖；移空后重新播种会复活已删元素 | 部分是（部分集合） | §4.8 重新定义（v3 进一步收窄：`SetCache` 只用于 L1 是唯一副本的场景，禁止 loader） |
| C3 | 严重 | TTL 泄漏：`HSET` 保留旧 TTL，lease 的 10 s、负缓存的 TTL 会泄漏到新值 | 否（5.x 先 `DEL`） | §4.3、不变量 #8 |
| C4 | 严重 | Lettuce 默认缓冲并重放断线期间的命令：已抛出异常的 `put` 可能稍后执行，覆盖更新的值 | 否（Spring 模板） | §4.7 `REJECT_COMMANDS`、不变量 #12 |
| C5 | 严重 | Lettuce 不在 `NOSCRIPT` 时回退；故障切换、重启、resharding 后脚本缓存丢失 | 否（Spring 会回退） | §4.7 EVALSHA → EVAL 回退；TCK 覆盖 |
| C6 | 严重 | 在把 PUBLISH 当作 SPUBLISH 执行的环境（ElastiCache Serverless）中，脚本内 `PUBLISH` 报跨 slot 错误，D3 失效 | — | D3 改为“能原子就原子”；启动探测、`atomicBroadcast` 能力位 |
| M1 | 重要 | loader 失败或被拒绝时 lease 不释放，全集群白等 `lease.ttl` | — | `release`、不变量 #7 |
| M2 | 重要 | 慢于 `lease.wait` 的 loader 仍会导致 M 次回源；慢于 `lease.ttl` 的 loader 永远写不进 L1，若同时不填 L2 则一直未命中 | — | §4.4 限定说法；fill 失败也以降级限龄填 L2 |
| M3 | 重要 | G1 中 L2 的上界假设事件丢失必然导致重连；半开连接、集群总线分区、sharded 非原子发布都不会重连，而 `localMaxAge` 默认不限 | 是 | `localMaxAge` 默认 5 分钟；订阅心跳；重写 G1 |
| M4 | 重要 | 写入抛异常与事务冲突：事务内抛出会回滚业务写入，`afterCommit` 中抛出会被吞掉 | — | D10、§4.13 失效默认可靠重试；`TransactionalInvalidator` |
| M5 | 重要 | “未命中路径用 Future”没有说明 loader 在哪个线程执行：在事件循环上执行会阻塞 I/O，丢失 ThreadLocal，破坏重入检测 | — | §4.5 线程模型、不变量 #6 |
| M6 | 重要 | `kotlin.time.Duration` 出现在 API/SPI 中，Java 无法使用；接口方法上的默认参数在 Java 中没有重载 | — | §4.1 改用 `java.time`，`put` 拆成两个重载 |
| M7 | 重要 | “MethodHandle 分发表让代理更快”不成立 | — | 撤回；§4.12、Q9 |
| M8 | 重要 | spec bean 注册方案描述不足：bean 名依赖实例化后才知道的 `spec.name`；泛型无法解析的情形；与接口声明重名 | — | §4.12 重新规定 |
| R5 | 重要 | 全部用 Hash 的代价被低估：载荷 > 64 B 时转为 hashtable 编码，每个 key 多约 200 B（1 KB 值约 +15–17%）；集合成员失去 intset | — | D4 改为标量用 String + 定长头；集合代价写入文档；Q1 |
| R6 | 重要 | Cluster 下批量失效约等于 N 个脚本加 N 次全集群广播，原子性只在单个 slot 内 | — | §4.6、Q10 |
| m | 次要 | 令牌应由客户端构造（`NodeId + 序号`），不依赖 Lua 随机数；Lua 脚本不带 shebang（否则 OOM 时被拒）；批次上限；帧由客户端构造；格式升级两阶段、未知版本不回收；`reclaim` 每次读至多一次；`onReset` 在订阅确认之后；批量戳在注册之后取；批量合并先做自己的；`Lookup.expiresAt` 可空并说明来自哪一层；`ttlOf(K, V)`；配置第四层来源与校验；`CacheLoader` 改为 `fun interface`；`KeyFilter` 描述自相矛盾；`@CaffeineCache` 字段丢失；actuator 需要不回源的查看接口；Spring Cache 的 null；Jackson 3；非 Boot 的 Spring 用户；“auto-pipelining”用词；实施步骤无法各自通过 `check`；阈值低于测量噪声 | — | 已分别并入各节 |

**已验证（原“待验证”）**：Redis 7 在 slot 迁移时（原节点执行 `CLUSTER SETSLOT … NODE`）会向 shard channel 的订阅者发送主动的 `sunsubscribe`；Lettuce 7.5 只把该频道从节点连接的订阅集合中移除，不会自动重新订阅。不处理它的通道在迁移后静默停止接收事件，也不会触发重置：5.4 的 `ShardedRedisCacheEvictedEventBus` 确实存在这个缺陷（[#629](https://github.com/Ahoo-Wang/CoCache/issues/629)，5.x 不再维护，已关闭）。6.0 的 `ShardedInvalidationChannel` 把非主动退订视为重置并重新订阅（Lettuce 按新拓扑或 `MOVED` 路由到新的所属节点），由 `RedisShardedChannelTest` 在 Cluster 上迁移频道所在 slot 验证；去掉该处理后同一用例超时失败。

**尝试攻破但成立的论断**：L1 fill 与失效（`DEL` 销毁令牌，令牌唯一，无 ABA）；lease 过期与慢 loader；两个读者同时未命中（读脚本原子，恰好一个取得 lease）；等待 lease 超时后本地回源再填 L2（受戳保护，跨节点受事件延迟约束）；A 写 L1 成功后 B 失效、A 再写 L2（三种到达时刻都会被戳或事件清除）；刷新令牌与 `put`、失效、抢占；`reclaim` 与并发写入；读己之写至多重试一次；结果未知的 fill；故障切换丢失 lease（最多多一次回源）；脚本内 `PUBLISH` 合法、按顺序执行并在 Cluster 中广播；脚本内可以使用 `TIME`；`PEXPIRE`/`PTTL` 的毫秒语义；Valkey 7.2+ 兼容。

### v2 → v3

第二轮由作者对 v2 重新推演，发现下列问题，均已修订。“5.x”一列含义同上。

| ID | 严重度 | 发现 | 5.x | 处理 |
|----|--------|------|-----|------|
| v3-1 | 重要 | 同一节点两个并发的 `put`：后执行 Lua 的线程先写 L2，先执行 Lua 的线程后写 L2，前后两次戳校验都能通过，L2 留下较旧的值；两次写入的事件都被本节点过滤。v2 的“修改 L1 后用新戳保护写 L2”挡不住这种情况，因为戳只表达“期间有没有失效”，不表达 L1 中的写入顺序 | 是（[#628](https://github.com/Ahoo-Wang/CoCache/issues/628) 的修复方案需相应调整） | §4.4：`put` 不写 L2，修改 L1 前后各递增一次戳并淘汰 L2；不变量 #1“L2 只由读 L1 的路径填充” |
| v3-2 | 重要 | `SOURCE` 模式的增量 `add`/`remove` 与数据库中的写入顺序无关，乱序到达时 L1 留下已删除的元素，直到 TTL | 是 | §4.8：`SetCache` 只用于 L1 是唯一副本的场景；数据源中的集合用普通 `Cache<K, Set<E>>` + `invalidate` |
| v3-3 | 重要 | 失效写 L1 失败进入重试队列后，L1 仍是旧值，本节点的未命中会把旧值以 `localMaxAge` 填入 L2；重试成功时本节点的事件又被过滤 | 不适用（5.x 不重试） | §4.13：排队中的 key 未命中时跳过 L1；重试成功后本地再递增戳并淘汰 L2 |
| v3-4 | 次要 | `RemoteRead.Hit` 带 digest，每次 L1 命中都要在 Lua 里计算 SHA-1，违反 G3 | — | digest 只在解码失败时由客户端计算 |
| v3-5 | 次要 | L1 作为唯一副本的两个前提没有写明：`maxmemory-policy` 不能是 `allkeys-*`；持久性等于 Redis 的持久性 | 是（5.x `SetElementsCache` 同样如此，未写明） | §4.8 前提与启动检查 |
| v3-6 | 次要 | `Token` 的属性类型是 `value class NodeId`，出现在 SPI 中，Java 实现方访问时会遇到名称改写 | — | §4.2：API/SPI 中不使用 `value class` |
| v3-7 | 次要 | §4.4 说等待 lease 时沿用最初的戳，§4.5 的伪代码却每次进入 `loadThrough` 都重新取戳 | — | 等待循环移入 `loadThrough` 内部 |
| v3-8 | 次要 | 集合的 digest 计算方式未定义 | — | §4.4：按 `HGETALL` 返回顺序拼接后计算 |

---

## 11. 与 5.x 的能力对照

“状态”一列：✅ 等价或增强；🔁 写法变化，能力保留；❌ 有意移除；🚧 尚未完成。

| 能力 | 5.x | 6.0 | 状态 |
|------|-----|-----|------|
| 读写 API | `getCache` / `get` / `getTtlAt` / `getCaches` / `getAll` / `set` / `setCache` / `evict` | `get` / `get(key, loader)` / `lookup` / `getAll` / `put` / `invalidate` / `invalidateAll` | 🔁 |
| 负缓存 | `MissingValue`，可显式写入 | 数据源返回 `null` 时由引擎写入；不能显式写入 | 🔁（显式写入 ❌） |
| 接口式声明 | `@CoCache` / `@CaffeineCache` / `@EnableCoCache` | 同名注解，启动时翻译为 spec；另有 spec bean | ✅（属性改名，见 §9） |
| key 编码 | SpEL `keyExpression` | 注解支持 SpEL；spec 用 `keyEncoder` | ✅ |
| 寿命策略 | `ttl` / `ttlAmplitude` / `missingTtl` / `clientSideTtl` | `ttl` / `ttlJitter` / `absentTtl` / `localMaxAge`（默认 5 分钟） | ✅ |
| 按条目决定寿命 | `CacheSource` 返回带 `ttlAt` 的值 | `ttlOf(K, V)` | 🔁 |
| 提前刷新 | `refreshAhead`，每个实例各刷新一次 | `refreshWindow`，全集群一次（刷新令牌） | ✅ |
| 批量读 | `getCaches`，不合并在途加载 | `getAll`，与单 key 读合并；共享连接多路复用 | ✅ |
| 回源并发上限 | `cocache.load.max-concurrent` | `LoadPolicy` | ✅ |
| 跨实例回源去重 | 无（各实例各回源一次） | lease | ✅ 新增 |
| 跨实例写回竞态 | 依赖 TTL 收敛（“固有窗口”） | lease 栅栏消除 | ✅ 新增 |
| 失效与广播 | 两次往返，可能 DEL 成功而 PUBLISH 失败 | 一次 Lua 原子完成（环境不支持时退回非原子） | ✅ |
| 失效失败 | 只告警 | 重试队列，排队期间未命中跳过 L1；可配置为抛出 | ✅ |
| 事务内失效 | 无 | `TransactionalInvalidator`；CacheManager 事务感知 | ✅ 新增 |
| L1 故障 | `strictFailure`：读按未命中，或全部抛出 | 读默认降级、可配置 `readFailure = THROW`；显式写入总是抛出 | ✅ |
| L2 实现 | 可替换（Caffeine、`MapClientSideCache`） | 固定 Caffeine | ❌ |
| L1 实现 | 可替换 `DistributedCache`，Redis codec：String / JSON / Hash / Set | `CoherenceBackend` SPI；Redis 后端只有标量与集合两种形状，值格式由 `Codec` 决定 | 🔁（Hash 形状 ❌） |
| Redis 客户端 | 经 Spring Data Redis：Lettuce 或 Jedis | Lettuce 原生，自建客户端 | 🔁（Jedis ❌） |
| Redis 拓扑 | 单机 / Sentinel / Cluster | 单机 / Sentinel / Cluster | ✅ |
| TLS | 经 Spring Data Redis | SSL bundle → Lettuce `SslOptions` | ✅ |
| 失效通道 | Pub/Sub、Sharded Pub/Sub | Pub/Sub、Sharded（slot 迁移后自动重新订阅并重置，5.x 的 #629 在 6.0 中已处理） | ✅ |
| 集合元素增删 | `SetElementsCache`（与数据源语义混用） | `SetCache`（L1 是唯一副本，禁止 loader） | 🔁（收窄） |
| JoinCache | `@JoinCacheable`、`JoinCache` | 无，用两次 `getAll` 组合 | ❌ |
| 布隆过滤器 | `BloomKeyFilter`（Guava） | `KeyFilter` 函数，由用户包装 | 🔁 |
| Spring Cache | `CoCacheManager`，`sync` 按 key 合并 | `CoCacheSpringCacheManager`，`sync` 走 lease 保护的回源 | ✅ |
| 指标 | Micrometer 计数器、L2 命中计数可选 | `CacheMetrics` + Micrometer 绑定（`cocache.*`）；L2 命中计数暂不提供（需 JMH 证明开销） | ✅（L2 命中计数 🚧） |
| actuator | `cocache`、`cocacheClient` 端点 | `cocache` 端点（列表、查看、清空本地副本），基于 `CacheAdmin`；按 key 查看与强制失效 🚧 | ✅ |
| 自定义组件装配 | `{name}.ClientSideCache` 等 bean 名后缀 | `CacheSpecCustomizer`、配置文件 `cocache.caches.[name].*` | 🔁 |
| 进程身份 | `ClientIdGenerator`，每个缓存一个 | `NodeId`，每个运行时一个 | 🔁 |
| 第三方实现的 TCK | `CacheSpec`、`DistributedCacheSpec` 等 | `BackendProtocolSpec`、`EngineSpec`、`SetEngineSpec`、`HookedBackend`；`MultiNodeSpec`、`FaultSpec` 🚧 | ✅ |
| 测试时间控制 | 真实时钟 | `ManualClock`，确定性 TTL 测试 | ✅ 新增 |
