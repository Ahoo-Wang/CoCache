# CoCache 架构与不变量（5.0）

本文是 CoCache 的**唯一架构事实来源**：设计目标、模块边界、核心算法与必须保持的不变量。修改一致性、存储或代理相关代码前先读本文；行为变化时同步更新本文。

## 1. 第一性目标

二级一致性缓存的本质是“用陈旧度换延迟”。CoCache 的全部设计服务于三条约束：

| # | 约束 | 实现手段 |
|---|------|----------|
| G1 | **陈旧度有上界**：任何不一致都在有限时间内自愈 | 有限的默认 TTL；负缓存独立的短 TTL；失效通道重订阅时清空 L2 |
| G2 | **失效不被吞没**：并发回源、事件乱序/丢失不会把旧值写回 | 失效戳（InvalidationStamps）保护所有写回；L1 读不到即未命中，绝不推断为负缓存 |
| G3 | **命中路径够便宜**：L2 命中近零开销且随线程扩展，L1 命中单次往返 | 有界 Caffeine L2（无条目级 Expiry，过期在读取时判断）；缓存秒级时钟 `CacheClock`；Redis 读为一次 Lua（EVALSHA）往返取回 TTL + 值；按 key 合并的 SingleFlight |

非目标：强一致（线性一致）读；跨实例的回源互斥（各实例独立合并回源，L1 吸收重复）。

## 2. 模块边界

```
cocache-api                  用户 API + 全部 SPI（零运行时依赖）
  Cache / CacheGetter / CacheSetter / NamedCache
  CacheValue = PresentValue | MissingValue（密封）、TtlAt
  CacheStore → ClientSideCache（L2）/ DistributedCache（L1）
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
cocache-spring-redis         RedisDistributedCache、codec、RedisCacheEvictedEventBus
cocache-spring-cache         Spring Cache 适配（CoSpringCache / CoCacheManager）
cocache-spring-boot-starter  自动配置、CoCacheProperties、actuator 端点
cocache-test                 TCK：CacheSpec、CacheStoreSpec、DefaultCoherentCacheSpec ...
```

依赖方向严格单向：`api ← core ← spring ← {spring-redis, spring-cache} ← starter`。新的存储/通道实现只需依赖 `cocache-api`（实现 SPI）与 `cocache-test`（验证 TCK）。

### 职责划分

- **存储层（`CacheStore`）只存不判**：按字符串 key 存取 `CacheValue`，不持有 TTL、抖动、负缓存等策略。写入已过期条目等价于淘汰。
- **策略层（`TtlPolicy`）**：命中值 `ttl ± ttlAmplitude`（防雪崩）；负缓存 `missingTtl`（不抖动）。由编排层持有，存储层只看到绝对到期时间 `ttlAt`。
- **编排层（`DefaultCoherentCache`）**：读穿透、回源合并、写回保护、失效广播。
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

- **L1 读取**：一个 Lua 读脚本原子地返回 `{ttl, ...原始值}`，走共享连接（不要用 pipeline：Lettuce 的 pipeline 需要专用连接，无连接池时代价远高于两次往返）。
- **L1 未命中的定义**：key 不存在（TTL = -2）、值缺失、或载荷损坏（自愈删除）。三者都返回 `null` 并触发回源；**不得**推断为负缓存。
- **回源成功不广播**：L1 为空时，其它实例 L2 中的副本必然已过期（L2 复制 L1 的 ttlAt）或已被失效事件清除。
- SingleFlight 将 leader 的结果或**原始异常**共享给所有等待者；同线程对同一 key 的重入（CacheSource 内再次读同一 key）快速失败。
- **读己之写**：每次读取在开始时记录失效戳；若共享到的加载开始于更早的戳（即开始于本次读取已观察到的某次失效之前，如本线程先 `evict` 再 `get`），则放弃该结果重新加载。新一轮加载必然开始于上一轮结束之后，至多重试一次——前提是 SingleFlight **先注销调用、再发布结果**，否则被唤醒的等待者重试时会再次加入同一个已结束的调用。

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
| L2 | Caffeine，`maximumSize = 10000`；过期条目在读取时淘汰 | 有界内存；条目级 `Expiry` 会让每次读取写节点元数据，热点 key 无法随线程扩展（JMH 实测） |
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

## 11. 测试约定

- 新存储实现扩展 `ClientSideCacheSpec` / `DistributedCacheSpec`；新编排或通道实现扩展 `DefaultCoherentCacheSpec`、`MultipleInstanceSyncSpec`、`CacheEvictedEventBusSpec`。
- 竞态用例用 latch 编排，禁止以 sleep 制造时序；断言“最终”行为时用带超时的轮询。
- 每个修复的缺陷都必须有能复现它的用例（见 `DefaultCoherentCacheSpec` 中的 in-flight 系列与 `CodecExecutorSpec.absentKeyIsMiss`）。
