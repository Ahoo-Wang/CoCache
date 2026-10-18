# RFC 0003：目标架构（第一性原理重构，7.0）

- 状态：草案（架构评审用）
- 前提：**不考虑兼容性**。API、SPI、模块、Redis 线格式、配置项都可以推倒重来
- 关系：吸收并取代 [RFC 0001](0001-l1-write-back-lease.md)（lease 成为唯一的写回保护原语）；[RFC 0002](0002-resp3-client-tracking.md) 变为新后端 SPI 下的一种可选通道

---

## 0. 结论摘要

| # | 决定 | 一句话理由 |
|---|------|-----------|
| D1 | **L1 是一致性权威**：每个 key 的写入在 Redis 中串行化，写回保护（lease）、失效、广播都在 L1 的同一次原子操作里完成 | G2 需要“每个 key 有一个串行化点”。进程内的失效戳只能覆盖单进程；跨进程的唯一串行化点就是 L1 |
| D2 | **一个原语：fence（栅栏）**。L1 用 lease 令牌，L2 用进程内失效戳；规则相同：*填充必须持有读取前取得的令牌，失效必须先销毁令牌再删除数据* | 现在的 `fillCache` / `evictIfUnchanged` / 写后复核再撤销 / RFC 0001 lease，都是这一条规则的特例，合并后实现与证明都只需要做一次 |
| D3 | **失效 + 广播在一次 Lua 中原子完成**（`DEL` + `PUBLISH`） | 消除“DEL 成功、PUBLISH 失败”（RFC 0002 的第 1 种丢失），失效从 2 次往返降为 1 次，不需要 client tracking |
| D4 | **统一信封线格式**：每个 L1 条目都是一个 Redis Hash，控制字段（状态、lease、刷新令牌、格式版本）与数据字段分属不同命名空间 | 去掉带内哨兵 `_nil_` 和它带来的全部约束；lease、刷新令牌、格式版本都有地方放；String/JSON/Hash/Set 四套 codec 合并为“标量 + 集合”两种形状 |
| D5 | **Redis 后端改为 Lettuce 原生，不依赖 Spring** | Spring Data Redis 不支持 Sharded Pub/Sub、client tracking、连接事件，pipeline 还需要专用连接。Lettuce 共享连接天然支持自动流水线，批量读不需要专用连接 |
| D6 | **L2 固定为 Caffeine，不可替换** | L2 命中是最热的路径，必须单态（monomorphic）调用。可替换带来的收益是零：没有比 Caffeine 更好的选择 |
| D7 | **声明即数据**：`CacheSpec` + `CacheRuntime.create(spec)`；Spring 只负责把 spec 变成 bean | 替换 9 种工厂、bean 名后缀约定和“有状态组件只按名称解析”这类陷阱。用户只提供工厂，不提供实例，所以组件按构造就不可能被共享 |
| D8 | **数据源只加载，策略决定寿命** | 现在 `CacheSource` 返回带 `ttlAt` 的 `CacheValue`，TTL 策略被数据源和编排层各持一半 |
| D9 | **时间可注入、毫秒精度** | 现在 API 模块里的 `object CacheClock` 会启动静态线程，测试无法控制时间，Redis 秒级 TTL 带来 ±1 s 漂移 |
| D10 | **失败语义一条规则：填充可降级，写入不降级** | 取代全局 `strictFailure` 开关以及“集合增删例外”这类特殊规则 |
| D11 | **删除 JoinCache 子系统**（注解、代理、SpEL 提取器、工厂） | 组合读模型是用户层逻辑，两次 `getAll` 就能写出来，不值得在核心里保留一整套声明与代理机制 |

三个目标不变，但要说得更精确（§1.2）。

---

## 1. 第一性原理

### 1.1 缓存到底是什么

缓存是“某个权威数据的副本，副本的陈旧度有上界”。它能做的只有三件事：**复制**（填充）、**作废**（失效）、**限龄**（TTL）。所有正确性问题都出在“复制”和“作废”的交错上：一次开始得早的复制，在作废之后才落地。

由此可以推出几条公理：

- **A1 串行化点**：要判断“复制开始于作废之前”，同一个 key 的复制与作废必须经过同一个串行化点，在那里比较先后。进程内的串行化点是 `AtomicLong`（失效戳）；跨进程的串行化点只能是共享存储本身（Redis 对单个 key 是串行执行的）。
- **A2 令牌先于数据**：复制者在读取**之前**拿到令牌，写入时出示令牌；作废者先销毁令牌，再删除数据。只要满足这一条，任何交错都不会让旧副本留下来（§4.4 给出证明）。
- **A3 权威发出失效**：谁串行化了写入，谁就应该发出失效通知。写入与通知分开执行，中间一定有窗口，可能写成功但通知丢失。
- **A4 TTL 是安全网，不是机制**：TTL 兜底的是协议之外的问题，比如绕过缓存的写入、Redis 故障切换时丢失的写入。协议之内的竞态不应该靠 TTL 收敛。
- **A5 命中路径只做必要的事**：L2 命中的下限是“一次并发哈希表读取 + 一次过期比较”。在它之上多出来的每一项（反射分发、虚调用、计数）都要有数据证明值得。
- **A6 声明与实例分离**：有状态组件（L2、L1 连接、栅栏）的生命周期必须和缓存一一对应。最可靠的保证方式是让用户无法传入实例。

### 1.2 目标（精确化）

| # | 目标 | 可度量的表述 |
|---|------|--------------|
| G1 | 陈旧度有上界 | L1：协议内竞态造成的陈旧为 0，协议外的陈旧 ≤ `ttl`。L2：≤ 事件延迟；事件丢失时 ≤ `min(localMaxAge, 通道重连时长)`；降级填充 ≤ `degradedLocalMaxAge` |
| G2 | 失效不被吞没 | 对每一层：读取开始于某次失效之前的填充，**永远不会**在该失效之后变得可见（同进程、跨进程都成立） |
| G3 | 命中路径便宜 | L2 命中（**经过用户实际调用的代理**）与 5.4 相比不退化；L1 命中一次往返；失效、写入各一次往返；同一 key 全集群并发未命中时只回源一次 |

非目标：线性一致读；把 L1 当作主存储（`SetCache` 的权威模式除外，见 §4.8）；异步 API（同步 API 对虚拟线程友好，内部 I/O 用 Lettuce 异步实现）。

---

## 2. 现状审查

先说结论：5.x 的核心算法（失效戳、SingleFlight 的发布顺序、L1 未命中即未命中）是对的，而且经过了充分验证。问题在于：**一致性分散在三个机制里，每个机制只覆盖一部分；外围的抽象层次、扩展点和装配方式也远多于问题本身需要的。**

### 2.1 正确性

| 发现 | 证据 | 影响 |
|------|------|------|
| 跨实例写回竞态只能靠 TTL 收敛 | architecture.md §5“已知的固有窗口”；`InvalidationStamps` 只在进程内有效 | 违反 A1。默认 TTL 不能放长，`FOREVER` 只能靠显式失效 |
| 失效与广播分两次执行 | [`DefaultCoherentCache.kt:423`](../../cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt) 先 `distributedCache.evict` 再 `publish`；[`RedisCacheEvictedEventBus.kt:56`](../../cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/RedisCacheEvictedEventBus.kt) 发布失败只计数 | 违反 A3。其它实例的 L2 陈旧到 `clientSideTtl` 或 TTL |
| L1 失败降级后，L2 仍以完整 TTL 填充 | `writeBack` 在 L1 `setCache` 被降级吞掉之后照常写 L2（[`DefaultCoherentCache.kt:319`](../../cocache-core/src/main/kotlin/me/ahoo/cache/consistency/DefaultCoherentCache.kt)） | 如果 Redis 故障时 Pub/Sub 连接没有断开，失效发不出去，`onReset` 也不会触发，L2 陈旧度的上界退化为 TTL |
| 写回保护有三种形态 | 写后复核后的 `invalidate`、Set 的 `fillCache` + `evictIfUnchanged`、RFC 0001 准备再加的 lease | 每种形态都要单独证明、单独测试；Set 已经因此有了专用 SPI |
| 带内哨兵 | `_nil_` 字符串、Hash 单字段、Set 单成员；[`SetToSetCodecExecutor.kt:133`](../../cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/SetToSetCodecExecutor.kt) 禁止元素等于哨兵；自定义哨兵需要全集群同时切换 | 类型混淆隐患；RFC 0001 的 lease 标记还要在每种 codec 里再加一个带内形态 |
| 时间精度 | `TTL` 秒级回读，`ttlSecondsOf` 的 `coerceAtLeast(1)`（[`AbstractCodecExecutor.kt:218`](../../cocache-spring-redis/src/main/kotlin/me/ahoo/cache/spring/redis/codec/AbstractCodecExecutor.kt)）；TCK 需要 `Offset.offset(1)` | ±1 s 漂移；短 TTL（例如 1–2 s 的负缓存）的语义不精确 |

### 2.2 性能

| 发现 | 证据 | 影响 |
|------|------|------|
| **JMH 测不到用户真实的命中路径** | 基准直接调用 `DefaultCoherentCache`（[`RedisCacheBenchmark.kt:77`](../../cocache-spring-redis/src/jmh/kotlin/me/ahoo/cache/spring/redis/RedisCacheBenchmark.kt)）；用户调用经过 JDK 动态代理，有 `declaringClass` 分支和 `Method.invoke`（[`CacheInvocationHandler.kt:62`](../../cocache-core/src/main/kotlin/me/ahoo/cache/proxy/CacheInvocationHandler.kt)） | G3 有盲区：被精细优化的路径不是用户实际走的路径 |
| L2 可替换导致虚调用 | `ClientSideCache` 接口；example 里同时存在 `MapClientSideCache` 与 Caffeine 两种实现 | 同一进程加载多种实现后，调用点变成多态，JIT 无法内联 |
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
  用户代码  ───────────▶ │ CoCache<K,V> / SetCache<K,E>   (cocache-api) │
                        └───────────────┬────────────────────────────┘
                                        │ CacheRuntime.create(CacheSpec)
┌───────────────────────────────────────▼─────────────────────────────────────┐
│ cocache-core：引擎                                                            │
│                                                                             │
│  CoherentEngine<K,V>                                                        │
│   ├─ LocalTier   (Caffeine，固定实现)  + LocalFence (失效戳)                   │
│   ├─ Coalescer   (SingleFlight，支持单 key 与批量)                            │
│   ├─ Policy      (TTL/抖动/负缓存/本地限龄/提前刷新/降级限龄)                   │
│   ├─ LoadGuard   (回源并发上限、超时)                                         │
│   └─ Metrics     (近似计数器，默认全开)                                        │
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
         Redis（单机 / Cluster；只读主节点）
```

### 3.1 模块

```
cocache-api                    用户 API + SPI，零依赖
cocache-core                   引擎 + InMemoryBackend + 默认时钟；依赖 Caffeine
cocache-redis                  Lettuce 后端；依赖 lettuce-core，不依赖 Spring
cocache-codec-jackson          Jackson Codec（可选；也可以接 kotlinx-serialization、protobuf 等）
cocache-spring-boot-starter    自动配置、@CoCache 声明扫描、SpEL key、Spring Cache 适配、actuator、Micrometer
cocache-test                   TCK：BackendProtocolSpec、EngineSpec、MultiNodeSpec、FaultSpec
cocache-bom
```

删除：`cocache-spring`、`cocache-spring-redis`、`cocache-spring-cache`（并入 starter 或 redis），以及整个 JoinCache 子系统。

依赖方向：`api ← core ← redis`，`api ← codec-jackson`，starter 依赖以上全部。core 不再依赖 `spring-expression` 和 `kotlin-reflect`：注解解析与 SpEL 只在 starter 中出现。

---

## 4. 核心设计

### 4.1 用户 API（cocache-api）

```kotlin
interface CoCache<K : Any, V : Any> {
    val name: String

    /** 读穿透；不存在（含负缓存）返回 null。 */
    fun get(key: K): V?

    /** 批量读穿透：一次 L1 往返 + 一次批量回源；与并发的单 key 读共享在途加载。 */
    fun getAll(keys: Collection<K>): Map<K, V>

    /** 需要区分“确认不存在”时使用。 */
    fun lookup(key: K): Lookup<V>

    /** 写穿透到 L1 并广播；原子地使所有在途填充失效。ttl 为 null 时按策略。 */
    fun put(key: K, value: V, ttl: Duration? = null)

    /** 先更新数据源，再调用。L1 删除与广播是一次原子操作。 */
    fun invalidate(key: K)
    fun invalidateAll(keys: Collection<K>)
}

sealed interface Lookup<out V> {
    data class Present<V>(val value: V, val expiresAt: Instant) : Lookup<V>
    data class Absent(val expiresAt: Instant) : Lookup<Nothing>
}

interface SetCache<K : Any, E : Any> : CoCache<K, Set<E>> {
    fun add(key: K, elements: Collection<E>)
    fun remove(key: K, elements: Collection<E>)
}
```

与 5.x 相比：

- 没有三态 `CacheValue?`。不回源的“只读视图”不再属于缓存的职责。
- 没有绝对时间 `ttlAt` 入参；时长统一用 `Duration`。
- `CacheValue`（内部改名 `Entry`）退回到引擎内部。

### 4.2 SPI（cocache-api）

```kotlin
/** 数据源只负责加载；寿命由 Policy 决定（可以按值决定，见 Policy.ttlOf）。 */
interface CacheLoader<K : Any, V : Any> {
    fun load(key: K): V?
    fun loadAll(keys: Collection<K>): Map<K, V> = keys.mapNotNull { k -> load(k)?.let { k to it } }.toMap()
}

interface Codec<V> {
    fun encode(value: V): ByteArray
    fun decode(bytes: ByteArray): V
}

/** L1 与失效通道是同一个后端的两个面：失效必须由串行化写入的那一方发出（A3）。 */
interface CoherenceBackend : AutoCloseable {
    fun store(namespace: String): RemoteStore
    val channel: InvalidationChannel
}
```

`RemoteStore` 只处理字节和信封，不知道值的类型：

```kotlin
interface RemoteStore {
    /** 读；未命中时原子地取得 lease（acquireLease=true）。refreshWindow 内可取得刷新令牌。 */
    fun read(key: String, acquireLease: Boolean, refreshWindow: Duration?): RemoteRead
    fun readAll(keys: Collection<String>, acquireLease: Boolean): Map<String, RemoteRead>

    /** 仅当 L1 中仍是该令牌时写入（lease 或刷新令牌）。 */
    fun fill(key: String, token: Token, envelope: Envelope, ttl: Duration?): Boolean
    fun fillAll(fills: List<Fill>): BooleanArray

    /** 原子：覆盖写入 + 广播；销毁任何 lease 与刷新令牌。 */
    fun put(key: String, envelope: Envelope, ttl: Duration?, origin: NodeId)

    /** 原子：删除 + 广播。 */
    fun invalidate(keys: Collection<String>, origin: NodeId)

    /** 载荷损坏或格式版本未知时：仅当数据仍为 observed 时转为 lease（条件回收，不会误删并发写入）。 */
    fun reclaim(key: String, observed: Digest): Token?

    fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, origin: NodeId)
    fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, origin: NodeId)
}

sealed interface RemoteRead {
    data class Hit(val envelope: Envelope, val ttl: Duration?, val refreshToken: Token?) : RemoteRead
    data class Miss(val lease: Token?) : RemoteRead          // lease == null：未请求或后端不支持
    data class Leased(val remaining: Duration) : RemoteRead  // 其它节点正在回源
}

interface InvalidationChannel {
    fun subscribe(namespace: String, listener: InvalidationListener): Subscription
    /** 仅供不能在存储内原子广播的后端使用；Redis 后端不经过它。 */
    fun publish(namespace: String, keys: Collection<String>, origin: NodeId)
}

interface InvalidationListener {
    fun onInvalidated(keys: List<String>, origin: NodeId)
    /** 订阅（重新）建立：此前的事件可能已丢失。 */
    fun onReset()
}
```

5.x 中的 `CacheStore`、`ClientSideCache`、`DistributedCache`、`SetElementsStore`、`CacheEvictedEventBus`、`KeyConverter`、`KeyFilter`、`CodecExecutor` 由上面这组 SPI 取代。L2 不再是 SPI（D6）。`KeyFilter` 保留为 `CacheSpec` 中的一个可选函数。

### 4.3 L1 线格式：信封（Envelope）

每个 key 都是一个 Redis Hash。字段名的第一个字符区分命名空间，**用户数据永远不会和控制字段冲突**：

| 字段 | 含义 |
|------|------|
| `!s` | 状态：`V`（标量值）、`S`（集合值）、`M`（负缓存）、`L`（lease 占位） |
| `!f` | 格式版本（整数）。引擎遇到未知版本时按“损坏”处理 |
| `!l` | lease 令牌（仅 `L` 状态） |
| `!r` | 刷新令牌（仅在提前刷新进行中） |
| `=` | 标量载荷（`Codec<V>` 的输出） |
| `+<member>` | 集合成员（`Codec<E>` 的输出），值为空串 |

到期时间统一用 `PEXPIRE`，读取用 `PTTL`，精度为毫秒，并且以相对时长传输，不受实例间时钟偏差影响。

为什么不用 String 加二进制头：lease、刷新令牌、集合成员都需要在**不重写载荷**的情况下原子地增删控制信息。Hash 让这些操作都变成 `HSET`/`HDEL`，Lua 脚本不需要解析载荷。代价是标量值多两个小字段（listpack 编码下约十几个字节），L1 命中从 `GET` 变成 `HGETALL`。这需要 JMH 验证（§7）。

外部系统只需要遵守失效协议（执行 `invalidate.lua` 或 `DEL` + `PUBLISH`），不需要理解信封格式。L1 是 CoCache 私有的存储格式。

### 4.4 一致性协议

#### L1 槽位状态机

```
          read(miss, acquireLease)               fill(token 匹配)
  ABSENT ─────────────────────────▶ LEASED(t) ─────────────────────▶ VALUE / MISSING
    ▲                                   │                              │   │
    │        invalidate / lease 到期     │                              │   │ read(进入刷新窗口)
    └───────────────────────────────────┴──────────────────────────────┘   ▼
    ▲                                                            VALUE + 刷新令牌 r
    │ invalidate（DEL + PUBLISH，原子）                                     │ fill(r 匹配) → VALUE
    └────────────────────────────────────────────────────────────────────┘
  put：任意状态 → VALUE（覆盖 Hash，销毁 l/r）+ PUBLISH，原子
  add：ABSENT/LEASED/MISSING → S(成员)；S → S ∪ 成员。原子，并 PUBLISH
```

#### 两层栅栏，同一条规则

| 层 | 令牌 | 读取前取令牌 | 填充 | 失效 |
|----|------|--------------|------|------|
| L1 | lease `!l`（随机令牌，读脚本在未命中时原子取得） | `read(acquireLease=true)` | `fill`：Lua 比较 `!l` 后写入 | `DEL` 销毁令牌与数据 |
| L2 | 进程内失效戳（分段 `AtomicLongArray`，与 5.x 相同） | `stamps.current(key)` | 写前校验 → 写 → 写后复核，失败则撤销 L2 | 先 `stamps.invalidate` 再淘汰 |

L1 的 fill 由 Redis 原子执行，所以 5.x 中“回源写回后复核失败要撤销 L1 并广播”的路径整体消失，`fillCache`/`evictIfUnchanged` 也一起消失。L2 仍然需要写后复核，因为 Caffeine 不能把“比较戳”和“写入”合并成一个原子操作。

#### G2 的证明草图（L1）

设实例 A 在时刻 `t_l` 取得 lease `a`，随后读取数据源；B 在 `t_c` 提交数据源，在 `t_d` 执行 `invalidate`（`DEL`）。

- 若 `t_d < t_l`：A 的数据源读取发生在 `t_l` 之后，也就在 `t_c` 之后，读到的是新值，fill 安全。
- 若 `t_d > t_l`：`DEL` 销毁了 `a`。A 的 fill 只有在 `!l == a` 时才会执行，而令牌是随机生成的，不会被再次取得，所以 fill 必然失败。如果 A 的 fill 在 `t_d` 之前已经执行，那么 `t_d` 的 `DEL` 会删掉它写入的值。

两种情况下，`t_d` 之后 L1 都不会留下 A 读到的旧值。L2 的证明与 5.x 相同。lease 过期后，key 要么不存在，要么已经是别人的新 lease，令牌都不匹配，所以不存在 ABA 问题，也不需要约束回源耗时。

#### 其它节点持有 lease

读到 `Leased` 时，以短退避轮询 L1，最多等 `lease.wait`（默认 200 ms）。等到值就直接使用；超时则自行回源，但**不写回 L1**，结果按戳保护填入 L2，并受 `degradedLocalMaxAge` 限龄。这样全集群同一 key 的并发未命中通常只回源一次（RFC 0001 §3.4）。

#### 提前刷新

读脚本发现剩余寿命小于 `refreshAhead` 且没有 `!r` 时，原子地设置 `!r`（带短 TTL 语义：令牌里编码签发时间，超过 `lease.ttl` 视为过期），并在 `Hit.refreshToken` 中返回。只有拿到刷新令牌的节点在后台回源，然后用 `fill(r)` 覆盖数据。`invalidate` 和 `put` 都会销毁 `!r`，所以刷新也受栅栏保护。效果是**全集群**每个 key 只有一次刷新，而 5.x 是每个实例各刷新一次。

#### 损坏与格式演进

解码失败或 `!f` 未知时，按未命中处理并调用 `reclaim(key, digest)`：只有当数据仍是本次读到的内容时，才把它原子地转为 lease，然后正常回源和 fill。读路径仍然**从不无条件删除**（5.x 不变量的精神得到保留），同时修复了一个问题：有了 lease 之后，损坏的值会挡住 fill，必须先回收。

### 4.5 读路径

```
get(key):
  k = keyEncoder(key)
  e = local.getIfPresent(k)                       // Caffeine，单态调用
  if e != null && e.expiresAt > clock.now:        // 粗粒度时钟，一次 volatile 读
      if e.refreshDue: engine.refreshAsync(k)     // 只有开启提前刷新时才编译进分支
      return e.valueOrNull
  return engine.miss(key, k)                      // 独立方法，保证 get 足够小、可内联

miss(key, k):
  if keyFilter?.notExist(k): return null
  observed = stamps.current(k)
  r = coalescer.run(k) { loadThrough(key, k) }    // SingleFlight：先注销，再发布结果
  if r.stamp < observed: r = coalescer.run(k) { loadThrough(key, k) }   // 读己之写，至多重试一次
  return r.value

loadThrough(key, k):
  stamp = stamps.current(k)
  when (val rr = remote.read(k, acquireLease = true, refreshWindow)):
    Hit    -> fillLocal(k, decode(rr), stamp)
    Miss   -> v = guard { loader.load(key) }
              entry = policy.entryOf(v)           // 值→TTL（含抖动）；null→负缓存
              filled = rr.lease == null || remote.fill(k, rr.lease, encode(entry), entry.ttl)
              if filled: fillLocal(k, entry, stamp)   // fill 失败说明期间发生过失效或元素写入：结果照常返回，但不进 L2
    Leased -> 等待或降级回源（见 §4.4）
```

`getAll` 走同一条路径：对未命中的 key 调用 `coalescer.runAll(keys)`。已在途的 key 直接等待现有调用；其余 key 作为一批，在一次 `readAll`（Lettuce 自动流水线，共享连接，每条命令一个 key，Cluster 安全）、一次 `loader.loadAll`、一次 `fillAll` 中完成。

L1 读失败（Redis 故障）时：按未命中回源，不写 L1，L2 以 `degradedLocalMaxAge`（默认 10 s）限龄。

### 4.6 写路径

| 操作 | 步骤 | 往返 |
|------|------|------|
| `put` | `stamps.invalidate` → Lua：覆盖信封 + `PEXPIRE` + `PUBLISH` → 写 L2 | 1 |
| `invalidate` | `stamps.invalidate` → 淘汰 L2 → Lua：`DEL` + `PUBLISH`（批量时一个脚本处理多个 key；Cluster 下按 slot 分组） | 1 |
| `add` / `remove` | `stamps.invalidate` → Lua：成员增删 + TTL + `PUBLISH` → 淘汰 L2 | 1 |

`PUBLISH` 在 Lua 中是允许的（它不是 key 命令）。在 Cluster 中，普通 PUBLISH 会广播到全集群。Sharded 模式见 §4.7。

### 4.7 失效通道

- **每个进程一个 `InvalidationRouter`**：一条订阅连接，按 namespace（缓存名）把消息分发给对应引擎。连接（重新）建立时，对它承载的全部 namespace 调用一次 `onReset`。注册新缓存不会再重置已有缓存。
- **身份属于进程**：`NodeId` 每个 `CacheRuntime` 一个（随机 128 位），用于过滤自己发出的事件。
- **消息帧**：`[版本:1B][nodeId:16B][count:varint]([len:varint][key])*`，二进制、带长度前缀，对 key 的内容没有任何限制，一条消息可以携带多个 key。
- **通道模式**：
  - `pubsub`（默认）：Lua 内 `PUBLISH`，失效与广播原子完成。
  - `sharded`：Redis 7 要求脚本里 `SPUBLISH` 的频道与脚本操作的 key 位于同一 slot。两种可选做法：(a) 失效脚本执行后单独 `SPUBLISH`，保留 5.4 的非原子语义；(b) 分桶布局：key 写为 `cocache:{name#b}:…`，频道写为 `cocache:inv:{name#b}`，`b ∈ [0, B)`，可以原子广播，但单个缓存的数据只分布在 B 个 slot 上。见 §8 未决问题 Q2。
  - `tracking`（6.x 预研）：RFC 0002 的结论在 Lettuce 原生后端下不再受 Spring 的限制，可以作为第三种实现接入同一个 SPI。
- **连接只读主节点**：lease 和 fill 必须落在主节点。CoCache 自己的 Lettuce 连接固定使用 `ReadFrom.UPSTREAM`，不继承应用对从节点读取的配置。

### 4.8 `SetCache` 的两种权威模式

5.x 中，元素增删把 L1 当作权威副本，但回源写回又把数据源当作权威，两种语义混在一起。7.0 中由 `CacheSpec.authority` 显式声明：

| 模式 | 权威 | 写法 | 回源 |
|------|------|------|------|
| `SOURCE`（默认，所有 `CoCache`） | 数据源 | 更新数据源后 `invalidate` | 未命中时回源 |
| `REMOTE`（仅 `SetCache`） | L1 | `add` / `remove` | 只在 key 不存在时，用 loader 做冷启动种子；提前刷新只从 L1 重新填充 L2 |

两种模式的种子填充都通过 lease，`add` 会覆盖 lease 占位，所以并发的元素写入不会被吞没。这里不需要 5.x 的专用条件操作。

### 4.9 时间

```kotlin
interface CacheClock { fun nowMillis(): Long }   // cocache-api
```

- 默认实现 `TickingClock` 放在 core，由 `CacheRuntime` 启动和停止，不再是静态线程。
- 测试注入 `ManualClock`，TTL、刷新窗口、限龄相关的用例全部变成确定性测试，不需要 sleep，也不需要“±1 s”偏差。
- L2 条目存放 `expiresAtMillis`（由本地时钟加上 L1 返回的 `PTTL` 算出）。命中判断与 5.x 一样是一次 volatile 读加一次比较。

### 4.10 策略与配置

```kotlin
data class CachePolicy(
    val ttl: Duration = 1.hours,
    val ttlJitter: Duration = 1.minutes,
    val absentTtl: Duration = 1.minutes,
    val ttlOf: ((Any) -> Duration?)? = null,      // 按值决定寿命（取代 CacheSource 返回 ttlAt）
    val localMaxAge: Duration? = null,            // 原 clientSideTtl
    val degradedLocalMaxAge: Duration = 10.seconds,
    val refreshAhead: Duration? = null,
    val lease: LeasePolicy = LeasePolicy(ttl = 10.seconds, wait = 200.milliseconds),
    val load: LoadPolicy = LoadPolicy(maxConcurrent = 0, acquireTimeout = 1.seconds),
    val local: LocalPolicy = LocalPolicy(maximumSize = 10_000, weigher = null),
)
```

**一个对象、一套校验、三层来源，后者覆盖前者**：内置默认值 → 声明（`@CoCache` 注解或 DSL）→ 配置文件 `cocache.caches.<name>.*`（全局默认放在 `cocache.defaults.*`）。运维调参不需要重新编译。

### 4.11 运行时与声明

```kotlin
val runtime = CacheRuntime.builder()
    .backend(RedisBackend.create(lettuceClient))
    .clock(TickingClock())
    .build()

val users: CoCache<String, User> = runtime.create(
    cacheSpec<String, User>("user") {
        keyEncoder { it }                 // 默认 toString
        codec(JacksonCodec(objectMapper))
        loader(userRepository::findById)
        policy { ttl = 2.minutes; local { maximumSize = 1_000_000 } }
    }
)
```

- `CacheSpec` 是不可变数据。所有有状态组件都由 `runtime.create` 按 spec 新建，所以不存在“两个缓存共享一个 L2”的可能。
- `CacheRuntime` 关闭时，按创建的逆序关闭所有引擎、订阅和连接。

### 4.12 Spring 集成（starter）

- **声明方式一（推荐）**：`@Bean fun userCache() = cacheSpec<String, User>("user") { … }`。starter 把每个 `CacheSpec` bean 注册为同名的 `CoCache<String, User>` bean，可以按泛型注入，多个候选时用 `@Qualifier("user")`。
- **声明方式二（语法糖）**：`@CoCache interface UserCache : CoCache<String, User>`，由 `@EnableCoCache` 扫描。代理在创建时把每个方法预先绑定为 `MethodHandle` 分发表，调用时不再走 `declaringClass` 分支和 `Method.invoke`。**JMH 的 L2 命中基准必须经过这个代理**。
- **定制**：`CacheSpecCustomizer` bean（按名称或谓词匹配）加上 `cocache.caches.<name>.*` 配置。没有 bean 名后缀约定。
- **数据源**：在 spec 中显式指定；接口方式下，按 `CacheLoader<K, V>` 的泛型唯一匹配，或通过 `@CoCache(loader = "beanName")` 指定。
- **Redis**：复用 Spring Boot 的 Lettuce 配置（`LettuceConnectionFactory#getNativeClient`），但 CoCache 使用自己的连接（主节点读、Pub/Sub、自动流水线）。
- **Spring Cache 适配**：语义与 5.x 相同（负缓存视为未命中、`sync = true` 按 key 合并、`clear` 只清本地）。
- **SpEL key**：`@CoCache(key = "#{id}")` 只在 starter 中解析，编译为 `(K) -> String` 后交给 core。

### 4.13 失败语义：填充可降级，写入不降级

| 类别 | 操作 | L1 故障时 |
|------|------|-----------|
| 填充 | 读、fill、刷新、种子 | 按未命中处理，回源结果只进 L2 并按 `degradedLocalMaxAge` 限龄，计数并告警 |
| 写入 | `put`、`invalidate`、`add`、`remove` | 本地效果（失效戳、L2）先生效，然后**抛出** `CacheWriteException`。这些操作都是幂等的，调用方可以重试。另外提供可选的 `RetryingInvalidator` 装饰器（有界队列、退避重试、溢出时告警） |

回源并发上限（bulkhead）保持 5.3 的语义：只计 leader，批量回源只占一个许可。

### 4.14 可观测

- 默认全开：分层命中（L2/L1）、未命中、回源（耗时、异常、拒绝）、合并数、lease（取得、等待、超时、fill 失败）、刷新、降级填充、失效（发出、收到、写入失败）、重置。
- L2 命中计数用**近似计数器**：按线程哈希分条带，每条带独占一个缓存行，普通写入，允许丢失少量更新。验收标准是 8 线程 L2 命中吞吐的回退 ≤ 2%，达不到就保持默认关闭。
- 指标放在 core 的 `CacheMetrics` 快照接口中，Micrometer 绑定在 starter 里。

---

## 5. 删除清单

| 删除 | 由什么取代 |
|------|------------|
| `Cache`/`CacheGetter`/`CacheSetter`、三态 `getCache`、`set(key, ttlAt, value)` | `CoCache`、`Lookup` |
| `CacheValue` 出现在用户 API 中 | 内部 `Entry` |
| `CacheStore`、`ClientSideCache`、`DistributedCache`、`MapClientSideCache` | 固定 Caffeine L2 + `RemoteStore` |
| `SetElementsStore.fillCache`/`evictIfUnchanged`、写后复核后撤销 L1 | L1 lease 栅栏 |
| `_nil_` 哨兵、`missing-guard-sentinel` 配置、元素不得等于哨兵的约束 | 信封控制字段 `!s` |
| `CodecExecutor` 体系（String/Hash/Set × String/Object/JSON）、Hash 形状 | `Codec<V>` + 标量/集合两种信封形状 |
| `CacheEvictedEventBus`、`CacheEvictedEvent`、`key@@publisherId` 文本格式、每个缓存一个订阅 | `InvalidationChannel` + 进程级 Router + 二进制帧 |
| `ClientIdGenerator`（每个缓存一个 id） | 每个进程一个 `NodeId` |
| 9 种工厂、bean 名后缀约定、`AbstractCacheFactory` | `CacheSpec` + `CacheRuntime` + `CacheSpecCustomizer` |
| `@CaffeineCache` | `CachePolicy.local` |
| `object CacheClock`（静态线程） | 可注入的 `CacheClock` |
| `TtlPolicy`（含兼容构造函数）、`strictFailure`、`recordClientSideHits` | `CachePolicy`、§4.13 的规则、近似计数器 |
| JoinCache 全套（`JoinCache`、`JoinValue`、`JoinKeyExtractor`、`@JoinCacheable`、SpEL 提取器、代理与工厂） | 文档中的组合示例（两次 `getAll`） |
| core 对 `spring-expression`、`kotlin-reflect` 的依赖 | 移到 starter |
| `StringRedisTemplate`、`RedisMessageListenerContainer` | Lettuce 原生 |

---

## 6. 新不变量（取代 AGENTS.md 中的对应列表）

1. **一个规则，两层栅栏**：任何填充都必须持有读取**之前**取得的令牌（L1 lease 或刷新令牌；L2 失效戳）。任何失效都必须先销毁令牌，再删除数据。
2. **L1 未命中就是未命中**：key 不存在或处于 `L` 状态都不代表负缓存。负缓存只有 `!s = M` 一种形态。
3. **读路径从不无条件删除**：损坏和未知格式只能通过 `reclaim`（比较 digest）转为 lease。
4. **失效与广播在 L1 中原子完成**。只有无法原子广播的后端才使用 `InvalidationChannel.publish`，并且必须在 TCK 中声明这一能力缺失。
5. **SingleFlight 先注销调用、再发布结果**（读己之写依赖这个顺序），批量合并同样遵守。
6. **有状态组件只能由运行时创建**：用户只提供 spec 和工厂。
7. **CoCache 的 L1 连接只读主节点**。
8. **代理抛出原始异常**。
9. **信封格式在一个大版本内冻结**；格式演进通过 `!f` 完成，不需要全集群同时切换。
10. **命中路径的改动必须附带数据**：JMH 必须经过用户实际使用的代理，1 线程与 8 线程，每侧至少交替运行三轮。

---

## 7. 性能预算与验证

| 路径 | 5.4 | 7.0 目标 | 验证方式 |
|------|-----|----------|----------|
| L2 命中（经过代理） | 未测量（基准绕过代理） | ≤ 5.4 直接调用引擎的数值 + 3% | 新增 `l2HitViaProxy`，同时测 `@CoCache` 接口与 spec bean |
| L1 命中 | 1 RTT（TTL + GET） | 1 RTT（PTTL + HGETALL），吞吐与延迟 ±5% 以内 | `l1Read`，标量值 1 KB / 10 KB |
| 未命中回源 | 读 + 回源 + 写，共 2 RTT | 读（含 lease）+ 回源 + fill，共 2 RTT | `missLoad` |
| `put` / `invalidate` | 各 2 RTT | 各 1 RTT | `set`、新增 `invalidate` |
| 批量 N 个 key | pipeline（专用连接、EVAL） | 自动流水线（共享连接、EVALSHA） | `l1BatchRead`，N = 10/100 |
| M 个实例同时未命中同一 key | M 次回源 | 1 次回源（其余等待 ≤ `lease.wait`） | `MultiNodeSpec` 计数断言 |
| 热点 key 提前刷新 | 每个实例各 1 次 | 全集群 1 次 | 同上 |

否决条件：L1 命中回退超过 5%，就改用“标量用 String + 二进制头、集合用 Hash”的混合信封（§8 Q1）。lease 与刷新令牌的语义不变，只是标量的 lease 改为写在头部。

---

## 8. 风险、权衡与未决问题

| # | 问题 | 倾向 |
|---|------|------|
| Q1 | 统一 Hash 信封 vs 混合信封 | 先实现统一 Hash 信封，由 §7 的 JMH 决定 |
| Q2 | Sharded 模式：放弃原子广播，还是用分桶布局 | 默认 (a) 非原子（与 5.4 相同）；在大规模集群里实测分桶 B=64/256 对数据分布和订阅数的影响后再决定 |
| Q3 | 等待 lease 时轮询 L1 vs 订阅单 key 通知 | 先轮询（实现简单，200 ms 上限）；指标显示等待占比高时再考虑通知 |
| Q4 | `put` 是否应该保留 | 保留，但文档首推 `invalidate`。写穿透只适合由写入方计算出最终值的场景 |
| Q5 | 提前刷新令牌的过期 | 令牌中编码签发时间（服务端 `TIME`），超过 `lease.ttl` 可以被抢占；不使用字段级 TTL（HEXPIRE 需要 7.4+） |
| Q6 | 故障切换丢写 | Redis 异步复制在故障切换时可能丢失 `DEL` 或 fill。这属于协议之外的问题，由 TTL 兜底（A4）；切换会导致订阅重连，触发 `onReset` 清空 L2 |
| Q7 | JDK 基线 | 维持 17（Spring Boot 4 的下限）。代码对虚拟线程友好：I/O 路径上没有 `synchronized` |

---

## 9. 实施顺序

不需要兼容，但每一步都应该能独立合入并通过 `check`：

1. **TCK 先行**：`cocache-test` 新增 `BackendProtocolSpec`（状态机、栅栏、原子广播、reclaim）和 `ManualClock`；`InMemoryBackend` 首先通过。
2. **api + core**：`CoCache`、`CacheSpec`、`CacheRuntime`、`CoherentEngine`、批量 SingleFlight、`CachePolicy`；把 `DefaultCoherentCacheSpec` 的 in-flight 系列用例全部迁移到新引擎。
3. **cocache-redis**：Lua 协议、信封、自动流水线、Router、重连重置；单机与 Cluster CI；JMH 对比 5.4（§7）。
4. **starter**：spec 注册、`@CoCache` 扫描与 MethodHandle 代理、配置分层、Spring Cache 适配、actuator、Micrometer。
5. **删除与文档**：删除旧模块；重写 `docs/architecture.md`、wiki（中英文）、`skills/cocache/`、AGENTS.md 不变量；在 changelog 中给出概念对照表（5.x → 7.0）。

验收：architecture.md 删除“已知的固有窗口”；`MultiNodeSpec` 覆盖 §4.4 的三种交错（失效先于 lease、晚于 lease、晚于 fill）、全集群单次回源和单次刷新；`FaultSpec` 覆盖 Redis 故障时的降级限龄，以及写入抛出异常后重试的幂等性。
