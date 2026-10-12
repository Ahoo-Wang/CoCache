# CoCache 架构与不变量（5.0）

本文是 CoCache 的**唯一架构事实来源**：设计目标、模块边界、核心算法与必须保持的不变量。修改一致性、存储或代理相关代码前先读本文；行为变化时同步更新本文。

## 1. 第一性目标

二级一致性缓存的本质是“用陈旧度换延迟”。CoCache 的全部设计服务于三条约束：

| # | 约束 | 实现手段 |
|---|------|----------|
| G1 | **陈旧度有上界**：任何不一致都在有限时间内自愈 | 有限的默认 TTL；负缓存独立的短 TTL；失效通道重订阅时清空 L2；可选的 L2 副本存活上限 `clientSideTtl` |
| G2 | **失效不被吞没**：并发回源、事件乱序/丢失不会把旧值写回 | 失效戳（InvalidationStamps）保护所有写回；L1 读不到即未命中，绝不推断为负缓存 |
| G3 | **命中路径够便宜**：L2 命中近零开销且随线程扩展，L1 命中单次往返 | 有界 Caffeine L2（无条目级 Expiry，过期在读取时判断）；缓存秒级时钟 `CacheClock`；Redis 读为一次 Lua（EVALSHA）往返取回 TTL + 值；按 key 合并的 SingleFlight |

非目标：强一致（线性一致）读；跨实例的回源互斥（各实例独立合并回源，L1 吸收重复）。

## 2. 模块边界

```
cocache-api                  用户 API + 全部 SPI（零运行时依赖）
  Cache / CacheGetter / CacheSetter / NamedCache
  CacheValue = PresentValue | MissingValue（密封）、TtlAt
  CacheStore → ClientSideCache（L2）/ DistributedCache（L1）
  SetElementsCache（集合元素原子增删能力）、SetElementsStore（L1 侧 SPI）
  CacheSource、KeyConverter、KeyFilter
  CacheEvictedEventBus / CacheEvictedSubscriber / CacheEvictedEvent
  JoinCache / JoinValue / JoinKeyExtractor
  @CoCache、@CaffeineCache、@JoinCacheable
cocache-core                 编排与默认实现（Caffeine、SpEL、kotlin-logging）
  TtlPolicy、DefaultCoherentCache、SingleFlight、InvalidationStamps
  CaffeineClientSideCache、MapClientSideCache、InMemoryDistributedCache
  LocalCacheEvictedEventBus、NoOpCacheEvictedEventBus
  元数据解析、CacheInvocationHandler、SimpleJoinCache、各组件工厂
cocache-spring               @EnableCoCache、FactoryBean、按 bean 名解析组件
cocache-spring-redis         RedisDistributedCache、RedisSetDistributedCache、codec、RedisCacheEvictedEventBus
cocache-spring-cache         Spring Cache 适配（CoSpringCache / CoCacheManager）
cocache-spring-boot-starter  自动配置、CoCacheProperties、actuator 端点
cocache-test                 TCK：CacheSpec、CacheStoreSpec、DefaultCoherentCacheSpec ...
```

依赖方向严格单向：`api ← core ← spring ← spring-redis`，`core ← spring-cache`，starter 依赖 `spring`、`spring-redis` 与 `spring-cache`。新的存储/通道实现只需依赖 `cocache-api`（实现 SPI）与 `cocache-test`（验证 TCK）。

### 职责划分

- **存储层（`CacheStore`）只存不判**：按字符串 key 存取 `CacheValue`，不持有 TTL、抖动、负缓存等策略。写入已过期条目等价于淘汰。
- **策略层（`TtlPolicy`）**：命中值 `ttl ± ttlAmplitude`（防雪崩）；负缓存 `missingTtl`（不抖动）；写入 L2 的副本到期时间不晚于 `now + clientSideTtl`（`0` 不限制）。由编排层持有，存储层只看到绝对到期时间 `ttlAt`。
- **编排层（`DefaultCoherentCache`）**：读穿透、回源合并、写回保护、失效广播。
- **统计（`CoherentCacheStats`）**：编排层以 `LongAdder` 累计 L1 命中、未命中、回源、写回作废、失效事件与重置，都在未命中或失效路径上。L2 命中计数默认关闭（`DefaultCoherentCacheFactory(recordClientSideHits)`）：JMH 实测它使 L2 命中吞吐下降约 8%（1 线程）与 15%（8 线程），违背 G3。Micrometer 绑定在 starter 中，可选。
- **通道（`CacheEvictedEventBus`）**：尽力而为地广播失效；订阅（重）建立时回调 `onReset`。

## 3. 数据模型

- `CacheValue<V>` 是密封类型：`PresentValue(value, ttlAt)` 或 `MissingValue(ttlAt)`。负缓存是**显式状态**，任何业务值（包括 `"_nil_"`）都不会被误判。
- `CacheValue.of(null, ttlAt)` 归一化为负缓存；`Cache.set(key, null)` 按 `missingTtl` 写负缓存。
- `ttlAt` 为绝对纪元秒；`TtlAt.FOREVER = Long.MAX_VALUE`。当前时间取自 `CacheClock`：守护线程每 100ms 刷新的 volatile 秒值（`System.currentTimeMillis()` 在部分平台上无法随线程扩展），最多滞后 100ms。
- 负缓存哨兵（默认 `_nil_`）只存在于 Redis codec 的线格式中（String/JSON：哨兵字符串；Hash：单字段 `{sentinel: 写入时间}`；Set：单元素 `{sentinel}`）。

## 4. 读路径

```
getCache(key)
  cacheKey = keyConverter(key)
  L2 命中且未过期            → 返回
  keyFilter.notExist(cacheKey) → 返回负缓存（不写入任何层）
  SingleFlight(cacheKey) {      // 本实例内同一 key 只有一个 leader
      stamp = stamps.current(cacheKey)
      L1 命中且未过期           → 受戳保护地填充 L2 → 返回
      loaded = source.load(key) ?: 负缓存(missingTtl)
      loaded 已过期             → 直接返回（不写入）
      受戳保护地写回 L1、L2      → 返回
  }
```

- **L1 读取**：一个 Lua 读脚本原子地返回 `{ttl, ...原始值}`，走共享连接（不要用 `executePipelined`：Lettuce 的 pipeline 需要专用连接，无连接池时约比两次普通往返慢 3 倍）。
- **L1 未命中的定义**：key 不存在（TTL = -2）、值缺失、或载荷损坏（读取不修改 L1，回源后的写回覆盖它）。三者都返回 `null` 并触发回源；**不得**推断为负缓存。
- **回源成功不广播**：L1 为空时，其它实例 L2 中的副本必然已过期（L2 复制 L1 的 ttlAt）或已被失效事件清除。
- SingleFlight 将 leader 的结果或**原始异常**共享给所有等待者；同线程对同一 key 的重入（CacheSource 内再次读同一 key）快速失败。
- **读己之写**：每次读取在开始时记录失效戳；若共享到的加载开始于更早的戳（即开始于本次读取已观察到的某次失效之前，如本线程先 `evict` 再 `get`），则放弃该结果重新加载。新一轮加载必然开始于上一轮结束之后，至多重试一次——前提是 SingleFlight **先注销调用、再发布结果**，否则被唤醒的等待者重试时会再次加入同一个已结束的调用。

### 4.1 提前刷新（5.3，默认关闭）

`@CoCache.refreshAhead > 0` 时，命中值（不含负缓存与永不过期的值）的剩余存活时间不超过该值，读取照常返回当前值，并在后台刷新一次：

- 每个实例每个 key 同一时刻至多一次刷新（`refreshing` 集合），刷新经 SingleFlight 与并发的未命中合并。
- 刷新复用 `loadThrough`：L1 不在窗口内时只从 L1 重新填充 L2（例如 L2 副本因 `clientSideTtl` 先到期）；L1 也在窗口内时回源并写回，写回同样受失效戳保护。
- L1 实现 `SetElementsStore` 时是集合的权威副本，刷新只从 L1 重新填充 L2，不回源。
- 不返回已过期的值，因此 G1 不变。刷新失败或被执行器拒绝时只告警，值照常到期。执行器默认是有界守护线程池，队列满时拒绝（不能静默丢弃，否则 key 会一直停留在“刷新中”）。
- 命中路径的额外开销：关闭时是一次 final 字段判断；开启时多一次时钟读取与比较。
- `TtlPolicy` 要求 `refreshAhead < ttl`，且设置 `clientSideTtl` 时 `refreshAhead < clientSideTtl`：否则每个新副本都在窗口内，命中会持续触发刷新。数据源自己返回的 `ttlAt` 也应明显长于 `refreshAhead`。

### 4.2 批量读取（5.3）

```
getCaches(keys)
  逐个 key：L2 命中且未过期 → 结果；keyFilter.notExist → 负缓存
  剩余 key：逐个取戳 → L1 getCaches（一次往返）
      L1 命中且未过期        → 受戳保护地填充 L2
      其余                   → source.loadCacheValues（一次调用），缺少的 key 为负缓存
                               未过期的值受戳保护地写回 L1、L2
```

- 每个 key 的戳保护与单 key 读取相同（先取戳再读 L1、写回前后复核），所以批量读取不会写回旧值。
- **不经过 SingleFlight**：同一 key 的并发单 key 读取与批量读取可能各自回源一次，重复由 L1 吸收；`loads` 指标把一次批量回源计为一次。
- 被 `KeyConverter` 映射为同一 cacheKey 的多个 key 共享一次读取。
- **Redis**：多个 key 用一次 pipeline 执行读脚本（每条命令一个 key，Redis Cluster 按 key 路由，不会 CROSSSLOT）；pipeline 里用 EVAL 而非 EVALSHA，因为 NOSCRIPT 无法在 pipeline 内回退。单个 key 仍走共享连接。pipeline 需要专用连接，只在批量时值得。

## 5. 失效与写回保护（核心不变量）

`InvalidationStamps` 是按 key 哈希分段（4096）的计数器。

- **失效方**：先 `stamps.invalidate(key)`，再淘汰副本。失效来源包括本地 `evict`/`setCache`、远端事件 `onEvicted`、通道重置 `onReset`（`invalidateAll`）。
- **写回方**（L1→L2 填充、回源写回）：取戳 → 写入前校验 → 写入 → 写入后复核。
  - 写入前戳已变化：放弃写回。
  - 写入后戳变化（与失效交错）：回源写回需撤销 L1、L2 并广播；L1→L2 填充只需撤销 L2。
- 两侧顺序保证：失效与写回无论如何交错，总有一方清除陈旧副本。
- 分段碰撞不影响正确性：代价是多余地放弃写回；若碰撞恰好落在写入与复核之间，还会多一次 L1 淘汰与失效广播。

本地写入 `setCache`：失效戳 → 写 L1 → 写 L2 → 广播。本地淘汰 `evict`：失效戳 → 淘汰 L2 → 淘汰 L1 → 广播。调用方必须**先更新数据源、再淘汰缓存**。

已知的固有窗口（cache-aside 共性，不在本框架消除）：实例 A 回源读到旧数据 → B 更新 DB、删除 L1、广播 → A 在收到事件前把旧值写入 L1。A 收到事件后撤销 L2，但若该事件在 A 写入 L1 之后才到达，L1 中的旧值将持续到 TTL 到期。**这正是默认 TTL 必须有限的原因（G1）。**

### 5.1 集合元素增删（5.1）

缓存接口同时声明 `Cache<K, Set<E>>` 与 `SetElementsCache<K, E>` 时，`addElements` / `removeElements` 在 L1 中原子完成，避免“读整集 → 修改 → 写整集”在并发写者间丢失更新。L1 必须实现 `SetElementsStore`（Redis：`RedisSetDistributedCache`；内存：`InMemoryDistributedCache`），否则创建代理时失败。

- **编排**：失效戳 → L1 原子增删（到期时间由 `TtlPolicy.valueTtlAt()` 计算，FOREVER 即无到期时间）→ 淘汰 L2 → 广播。写入后的完整集合只在 L1 中，所以淘汰 L2，不填充。读己之写与在途加载的保护同 `setCache`。
- **语义**：L1 中的集合是该 key 的**权威副本**，增删不访问 `CacheSource`。加入时，key 若为负缓存则先清除；移除时，key 不存在或为负缓存则不修改，移空则删除 key（之后的读取未命中、回源，与“空集合写入即淘汰”一致）。集合的真实数据若在数据源中，应先更新数据源、再 `evict`。
- **条件写回**：L1 实现 `SetElementsStore` 时，回源写回改为 `fillCache`（仅当 key 不存在或为负缓存时写入）；写后复核失败时，撤销改为 `evictIfUnchanged`（仅当内容仍为本次写入时删除）。若无条件写回或撤销（`DEL`），开始于增删之前的回源（例如无数据源时写入负缓存）就会吞没增删，同实例与跨实例皆然。
- **失败**：元素增删是显式写入，L1 失败时总是抛出异常，不受 `strictFailure` 影响。L1 可能已生效（例如响应超时），因此仍在 `finally` 中淘汰 L2 并广播。增删幂等，调用方可安全重试。条件写回与撤销属于缓存填充，仍按 `strictFailure` 降级。
- **Redis**：每个操作一次 Lua 往返，线格式不变。负缓存判定与读取一致（恰好只含哨兵一个成员）；加入的元素不得等于哨兵。

## 6. 失效通道

- Redis Pub/Sub，频道名 = cacheName，消息体 `key@@publisherId`（按最后一个 `@@` 切分；publisherId 不得包含 `@@`）。
- 至多一次投递：发布失败仅告警。
- `RedisMessageListenerContainer` 每次（重新）订阅成功都会通知该频道的全部监听者 → `onReset` → 清空 L2 并 `invalidateAll`。断线期间丢失的事件由此兜底。
  - Spring Data Redis 的 `addListener` 总会对频道重新 SUBSCRIBE，因此注册同频道的新监听者也会重置已有监听者（无害：生产中每个进程每个频道通常只有一个监听者）。
  - 通知经容器 TaskExecutor 异步分发；集成测试给容器配置 `SyncTaskExecutor`，使 `register()` 返回时重置已完成。
- 订阅者忽略 `publisherId == clientId` 的自发事件（本地写已直接处理）。

## 7. 默认值

| 配置 | 默认 | 理由 |
|------|------|------|
| `@CoCache.ttl` | 3600 s | G1：任何不一致最多持续 1 小时 |
| `@CoCache.ttlAmplitude` | 60 s | 打散批量过期 |
| `@CoCache.missingTtl` | 60 s | 数据新建后最多 60 秒仍被视为不存在 |
| L2 | Caffeine，`maximumSize = 10000`；过期条目在读取时淘汰 | 有界内存；条目级 `Expiry` 会让每次读取写节点元数据，热点 key 无法随线程扩展（JMH 实测：8 线程下 224M → 11M ops/s） |
| `@CoCache.clientSideTtl` | 0（不限制） | L2 副本沿用 L1 到期时间，与 5.1 行为一致；设置后，失效事件丢失时 L2 的陈旧度以该值为上界。只在写入 L2 时计算，命中路径无额外开销 |
| `cocache.redis.strict-failure` | false | Redis 故障降级：读按未命中、写/淘汰仅告警 |

## 8. 代理

`CacheInvocationHandler` 统一处理 CoCache 与 JoinCache 代理：

- `CacheDelegated.delegate` / 元数据访问器 → 直接返回；
- `equals`/`hashCode`/`toString` → 代理身份语义；
- 用户接口自身声明的默认方法（委托对象未实现其声明接口）→ `invokeDefault`；
- 其余 → 反射转发给委托对象，并**解包 `InvocationTargetException`**，调用方收到原始异常。

## 9. Spring 组件解析

组件按 `{cacheName}{suffix}` 的 bean 名解析：`.ClientSideCache`、`.DistributedCache`、`.KeyConverter`、`.CacheSource`、`.JoinKeyExtractor`。

- **有状态组件（L2、L1、KeyConverter）只按名称解析**：按类型解析会让同值类型的多个缓存共享同一实例（互相 `clear`、key 前缀串用）。
- 无状态组件（`CacheSource`、`JoinKeyExtractor`）在名称未命中时可按唯一泛型类型解析。
- 生命周期：`CacheProxyFactoryBean` 关闭其 CoherentCache（注销订阅 + 关闭 L1）；JoinCache 不持有组件缓存的生命周期。

## 10. Spring Cache 适配

- 负缓存视为未命中：CoherentCache 未命中会回源，若把负缓存作为“缓存的 null”返回，`@Cacheable` 将永远不调用方法。
- `get(key, valueLoader)` 按 key 合并并发加载（满足 `sync = true`）。
- `retrieve` 在专用守护线程池（或注入的 Executor）上执行阻塞查找。
- `clear()` 只能清空本实例 L2（L1 为共享存储）。

## 11. 性能验证

命中路径（L2 命中、L1 命中）的改动必须用 JMH 与上一版本对比，线程数取 1 与 8：

```bash
./gradlew :cocache-spring-redis:jmh --rerun -PjmhThreads=8 -PjmhIncludes=l2Hit   # 需要 Redis；check 只编译不运行
```

- 必须加 `--rerun`：参数不变时 Gradle 判定 `jmh` 任务为最新，静默沿用上次的结果。
- 同一台机器上 8 线程的结果在多次运行之间波动约 ±4%：每侧至少交替运行三轮，比较中位数。

基准位于 `cocache-spring-redis/src/jmh`（`RedisCacheBenchmark`）。

## 12. 测试约定

- 新存储实现扩展 `ClientSideCacheSpec` / `DistributedCacheSpec`；新编排或通道实现扩展 `DefaultCoherentCacheSpec`、`MultipleInstanceSyncSpec`、`CacheEvictedEventBusSpec`。
- 竞态用例用 latch 编排，禁止以 sleep 制造时序；断言“最终”行为时用带超时的轮询。
- 每个修复的缺陷都必须有能复现它的用例（见 `DefaultCoherentCacheSpec` 中的 in-flight 系列与 `CodecExecutorSpec.absentKeyIsMiss`）。
