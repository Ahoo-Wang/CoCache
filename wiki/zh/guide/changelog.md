---
title: 更新日志
description: CoCache 版本发布历史和重要变更。
---

# 更新日志

## v5.4.0（当前版本）

**模块组：** `me.ahoo.cocache` · 次版本，仅新增 API。默认失效通道与 Redis 线格式不变，5.3.x 与 5.4 实例可共享 Redis。新通道默认关闭。

### Sharded Pub/Sub 失效通道

```yaml
cocache:
  redis:
    eviction-channel: sharded   # 默认 pubsub
```

Redis Cluster 中普通 `PUBLISH` 会到达每个节点；`SPUBLISH`/`SSUBSCRIBE`（Redis 7+）只在频道所属 slot 的分片内传播，开销不随集群规模增长。

- 需要 Lettuce（`ShardedRedisCacheEvictedEventBus` 直接使用 Lettuce，Spring Data Redis 尚不支持 Sharded Pub/Sub）。
- 频道名与消息格式不变；断线重连后重新订阅同样会清空 L2。
- **两种通道互不相通，所有实例必须同时切换。**
- 两种通道的发布失败都计入 `cocache.eviction.publish.failures`（新接口 `EvictionPublishStats`）。

### 从 CoCache 之外淘汰

[运维指南](./operations.md#external-eviction)公开了失效协议（存储 key、频道、`key@@publisherId` 消息），并给出 `redis-cli` 与 CDC 消费者示例：绕过 CoCache 的写入方先 `DEL` 存储 key，再 `PUBLISH`。

### Redis Cluster 测试

CI 新增 “Test (Redis Cluster)” 任务，Redis、Spring Cache 与 starter 的测试同时在 3 主 3 从的集群上运行。本地可用 `.github/scripts/start-redis-cluster.sh` 启动同样的集群。

### TCK

`SetElementsCacheSpec` 的两个并发用例改为：立即断言 L1（权威副本）内容，各实例的读取以带超时的轮询断言。此前它们在失效事件较慢的环境（如 Redis Cluster）下会偶发失败。

### 设计文档

- [RFC 0001](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0001-l1-write-back-lease.md)：6.0 的 L1 写回 lease，关闭跨实例的旧值写回窗口。
- [RFC 0002](https://github.com/Ahoo-Wang/CoCache/blob/main/docs/rfc/0002-resp3-client-tracking.md)：RESP3 client tracking 预研结论（6.0 不采用）。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.4.0")
```

## v5.3.0

**模块组：** `me.ahoo.cocache` · 次版本，仅新增 API（新接口方法都带默认实现）。Redis 线格式与失效消息格式不变，5.2.x 与 5.3 实例可共享 Redis。新行为默认关闭。

### 批量读取：`getAll` / `getCaches` / `loadCacheValues`

读取 N 个 key 不再需要 N 次 Redis 往返和 N 次回源：

```kotlin
val users: Map<String, User> = userCache.getAll(ids)
```

- 先读 L2，剩余 key 用一次 pipeline 读 Redis，仍未命中的 key 一次调用 `CacheSource.loadCacheValues(keys)` 加载（结果中缺少的 key 写入负缓存）。100 个 key 的 L1 读取从约 13.6 ms 降到约 1 ms（JMH）。
- 每个 key 的回填与写回都受失效戳保护，与单 key 读取相同。
- `CacheSource.loadCacheValues` 默认逐个调用 `loadCacheValue`，覆盖为一次查询（如 `findAllById`）即可。
- `SimpleJoinCache` 以两次批量读取完成组合。
- 新增 API：`CacheGetter.getCaches` / `getAll`、`CacheStore.getCaches`、`CacheSource.loadCacheValues`、`CodecExecutor.executeAndDecodeAll`，均带默认实现。

### 提前刷新：`@CoCache.refreshAhead`

```kotlin
@CoCache(ttl = 600, refreshAhead = 60)
interface UserCache : Cache<String, User>
```

值的剩余存活时间不超过 `refreshAhead` 秒时，读取照常返回当前值，并在后台刷新一次（每个实例每个 key 同一时刻至多一次）。热点 key 过期时不再出现回源延迟尖刺。不会返回已过期的值，负缓存不刷新。默认 `0` 关闭。指标：`cocache.refreshes`。

### 回源并发上限

```yaml
cocache:
  load:
    max-concurrent: 32
    acquire-timeout: 1s
```

Redis 故障期间每次 L2 未命中都会回源。设置后，每个缓存同时进行的回源数受限，超出的回源等待 `acquire-timeout` 后抛出 `SourceLoadRejectedException`。指标：`cocache.load.rejections`。默认不限制。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.3.0")
```

## v5.2.0

**模块组：** `me.ahoo.cocache` · 次版本，仅新增 API。Redis 线格式与失效消息格式不变，5.1.x 与 5.2 实例可共享 Redis。

### L2 陈旧上限：`@CoCache.clientSideTtl`

失效事件经 Redis Pub/Sub 至多投递一次。事件丢失时，其它实例会一直使用 L2 副本，直到值的 `ttl`（默认 3600 秒）到期。

```kotlin
@CoCache(clientSideTtl = 30)
interface UserCache : Cache<String, User>
```

- L2 副本最多存活 `clientSideTtl` 秒，过期后从 Redis 重新填充；L1 仍保留完整 TTL。
- 默认 `0` 不限制，与 5.1 行为一致。
- 只在写入 L2 时计算，命中路径没有额外开销；每次 L2 过期需要一次 Redis 往返。
- 启用后，L2 命中时 `getTtlAt` 返回 L2 副本的到期时间。

### Micrometer 指标

classpath 上有 Micrometer 时（`spring-boot-starter-actuator` 会引入），starter 绑定 `cocache.*` 指标，按 `cache` 标签区分：`hits{level=l2|l1}`、`misses`、`loads`（timer）、`load.failures`、`filtered`、`stale.discards`、`evictions.received`、`resets`、`l2.size`，以及不带标签的 `eviction.publish.failures`。完整说明见[运维](./operations.md#metrics)。

- 统计由编排层以 `LongAdder` 累计，可通过 `CoherentCache.stats` 直接读取。
- L2 命中计数（`hits{level=l2}`）默认关闭，用 `cocache.metrics.client-side-hits=true` 开启：在最热的路径上计数会使 L2 命中吞吐下降 8%–15%（JMH，1–8 线程）。其余指标都不在命中路径上，命中路径吞吐不变。
- `RedisCacheEvictedEventBus.publishFailureCount` 记录发布失败次数。

### 移除 CosId 依赖

`cocache-core` 只用 CosId 生成 clientId，现改用 JDK 实现。

- `cosid-core` 不再随 CoCache 传递引入。直接使用 CosId 的项目需要自行声明依赖。
- 移除 `CosIdHostAddressSupplierAutoConfiguration`：容器中的 CosId `HostAddressSupplier` 不再用于 clientId 的 host 部分。clientId 的唯一性由随机后缀保证，host 只影响可读性。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.2.0")
```

## v5.1.1

**模块组：** `me.ahoo.cocache` · 补丁版本，与 5.1.0 向后兼容，Redis 线格式与失效消息格式不变。

- **clientId 在实例间保证唯一。** `HostClientIdGenerator` 此前以 `{计数器}:{pid}@{host}` 作为 clientId，但 host + pid 并不唯一：同一宿主机上以 host 网络模式运行的两个容器 IP 相同、pid 都可能为 1。订阅方会忽略 publisherId 等于自身 clientId 的失效事件，因此这两个实例会互相丢弃对方的失效，L2 中的旧值要等到 TTL 才过期。clientId 现为 `{计数器}:{pid}:{随机后缀}@{host}`；`ClientIdGenerator` 的契约补充了“必须在所有实例间唯一”。
- 依赖：CosId 3.4.1，fluent-assert 1.2.1。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.1.1")
```

## v5.1.0

**模块组：** `me.ahoo.cocache` · 次版本，仅新增 API。Redis 线格式与失效消息格式不变，5.0.x 与 5.1 实例可共享 Redis。

### 集合值缓存的原子元素增删

并发的“读整集 → 修改 → 写整集”会在实例间丢失更新。缓存接口现在可以再声明 `SetElementsCache<K, E>`，每次增删都是一次原子的 L1 操作：

```kotlin
@CoCache(ttl = TtlAt.FOREVER)
interface PolicyIndexCache : Cache<String, Set<String>>, SetElementsCache<String, String>

policyIndexCache.addElements(key, listOf(policyId))
policyIndexCache.removeElements(key, listOf(policyId))
```

- **装配**：分布式缓存须实现新的 SPI `SetElementsStore`。Redis 使用 `RedisSetDistributedCache(redisTemplate)`（原生 Redis Set，与 `SetToSetCodecExecutor` 布局相同），`InMemoryDistributedCache` 同样支持。代理声明了 `SetElementsCache` 而存储不支持时，创建代理即失败，错误信息给出修复方法。
- **语义**：L1 是权威副本，增删不访问 `CacheSource`。加入时替换负缓存（哨兵不会残留在集合中）。每次增删后，key 的到期时间按缓存的 `TtlPolicy` 重置（`TtlAt.FOREVER` → 无到期时间，即使此前是 60 秒的负缓存）。对不存在的 key 或负缓存执行移除不做任何事；移除最后一个元素会删除 key。
- **一致性**：失效戳 → L1 原子增删（一次 Lua 往返）→ 淘汰 L2 → 广播失效事件。读己之写与在途加载保护同 `setCache`。
- **失败总会暴露**：元素增删是显式写入，L1 失败时总是抛出异常，即使 `strictFailure = false`。更新仍可能已生效（例如响应超时），因此仍会淘汰本地副本并广播失效。两个操作都是幂等的，调用方可以重试。
- **写回安全**：对实现 `SetElementsStore` 的存储，回源写回改为条件写入（仅当 key 不存在或为负缓存时写入），撤销改为比较后删除。此前，开始于 `addElements` 之前的回源（例如写入负缓存）可能覆盖或删除新加入的元素。`InMemoryDistributedCache` 实现了该 SPI，因此对任意值类型都使用条件写回。
- 新增 `TtlPolicy.valueTtlAt()`（含抖动的命中值到期时间）；`RedisDistributedCache` 改为 `open`。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.1.0")
```

## v5.0.1

**模块组：** `me.ahoo.cocache` · 补丁版本，与 5.0.0 向后兼容，Redis 线格式不变。

- **读取时不再删除损坏载荷。** 无法解码的载荷仍按未命中处理，由一致性缓存回源后的写回（受失效戳保护）覆盖。此前读取会无条件 `DEL`，可能误删其他实例刚写入的有效值。
- **`cocache-spring-boot-starter` 移除 `actuator-support` / `cloud-support` Gradle capability。** Actuator 改为 `compileOnly`，端点仍只在应用自行引入 `spring-boot-starter-actuator` 时启用。POM 不再列出 actuator 可选依赖；未使用这两个 capability 的 Maven/Gradle 用户不受影响。
- 构建：为 Gradle 10 做好准备（构建脚本不再使用已弃用 API），改用 Kotlin `-jvm-default=enable`（字节码与 `-Xjvm-default=all-compatibility` 相同）。测试同时在 JDK 25 上运行。
- 文档站：修复 3 张无法渲染的时序图，修复文档工具链的全部 Dependabot 安全告警。

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.0.1")
```

## v5.0.0

**模块组：** `me.ahoo.cocache`

5.0 以三条第一性目标重建一致性内核：陈旧度有上界、失效不被吞没、命中路径够便宜。这是不提供兼容桥接的破坏性版本。**Redis 存储结构与失效消息格式保持不变**，滚动升级期间 4.x 与 5.0 实例可以共享同一 Redis。

### 正确性修复

- **L1 读不到绝不视为负缓存**：Redis 读取改为一次原子往返（Lua 脚本同时返回 TTL 与值）。key 不存在、读取期间被删除、载荷损坏都按未命中处理并回源。此前若在 `TTL` 与 `GET` 之间发生并发淘汰，会被解码为负缓存并写入 L2，直到旧 TTL 到期（默认永久）。
- **本地写入使在途回源失效**：同实例上的 `evict`/`setCache` 现在会让并发回源放弃陈旧写回（此前只有远端事件生效）；L1 → L2 填充受同样保护。
- **代理抛出原始异常**：代理解包 `InvocationTargetException`，调用方不再收到 `UndeclaredThrowableException`。
- **默认陈旧度有界**：默认 `ttl` 为 3600 秒（原为永久）；负缓存使用独立的 `missingTtl`（默认 60 秒，原与命中值共用 TTL）；默认 L2 为有界 Caffeine（10000 条）；失效通道每次（重新）订阅都会清空 L2，丢失的 Pub/Sub 消息不会让 L2 永久陈旧。
- **读己之写**：同一线程 `evict`/`setCache` 之后的 `get`，不会返回开始于该次失效之前的加载结果，而是重新加载一次。
- **Spring Cache**：`get(key, valueLoader)` 按 key 只加载一次（满足 `@Cacheable(sync = true)`）；`retrieve` 改用专用线程池，不再占用 ForkJoin 公共池。
- **有状态组件不再共享**：`ClientSideCache`、`DistributedCache`、`KeyConverter` bean 只按名称解析；此前按泛型类型匹配的 bean 会被同值类型的所有缓存共享。
- **JoinCache.evict(key) 不再回源**：只淘汰主缓存。

### 架构

- `cocache-api` 承载全部 SPI：`CacheStore` → `ClientSideCache` / `DistributedCache`、`CacheEvictedEventBus` / `CacheEvictedSubscriber` / `CacheEvictedEvent`、`KeyConverter`、`KeyFilter`、`CacheSource`。
- `CacheValue` 为密封类型：`PresentValue(value, ttlAt)` | `MissingValue(ttlAt)`。
- 存储层只存不判；TTL 策略（`TtlPolicy`）由编排层持有。
- `DefaultCoherentCache` 以 `SingleFlight` 按 key 合并回源（无分段锁碰撞），以 `InvalidationStamps` 保护写回。
- CoCache 与 JoinCache 代理共用一个 `CacheInvocationHandler`。

### 性能

JMH，4.3.0 对比 5.0.0，同一机器、同一 Redis（ops/s）：

| 基准 | 4.3.0 · 1 线程 | 5.0.0 · 1 线程 | 4.3.0 · 8 线程 | 5.0.0 · 8 线程 |
|---|---:|---:|---:|---:|
| L2 命中 | 31.1M | 32.6M | 217.5M | 235.9M |
| L1 读取 | 3,188 | 6,144 | 5,438 | 10,758 |
| 未命中回源 | 1,487 | 3,082 | 2,467 | 5,173 |
| 写入 | 2,779 | 2,804 | 4,714 | 4,694 |

一次 Lua 往返取代 `TTL` + `GET`，L1 读取与未命中回源约快 2 倍。L2 命中保持随线程扩展：有界 Caffeine 在读取时对照缓存时钟 `CacheClock` 判断过期。基准随仓库提供：`RedisCacheBenchmark`（`./gradlew :cocache-spring-redis:jmh`）。

### 从 4.x 迁移

| 4.x | 5.0 |
|-----|-----|
| `me.ahoo.cache.DefaultCacheValue(value, ttlAt)` / `.forever(v)` / `.missingGuard(...)` | `CacheValue.of(value, ttlAt)` / `CacheValue.forever(v)` / `CacheValue.missing(ttlAt)` |
| `cacheValue.isMissingGuard` | `cacheValue.isMissing`（或 `is MissingValue`） |
| `ComputedTtlAt.at(ttl)`、`ComputedTtlAt.FOREVER`、`CacheSecondClock.INSTANCE.currentTime()` | `TtlAt.at(ttl)`、`TtlAt.FOREVER`、`TtlAt.currentTime()` |
| `ClientSideCache` / `DistributedCache` 继承 `Cache`（`cache[key] = v`） | 二者是 `CacheStore`：`getCache` / `setCache` / `evict` |
| `me.ahoo.cache.distributed.DistributedCache`、`me.ahoo.cache.consistency.CacheEvicted*`、`me.ahoo.cache.converter.KeyConverter`、`me.ahoo.cache.KeyFilter` | `me.ahoo.cache.api.distributed.DistributedCache`、`me.ahoo.cache.api.consistency.CacheEvicted*`、`me.ahoo.cache.api.converter.KeyConverter`、`me.ahoo.cache.api.filter.KeyFilter` |
| `@GuavaCache`、`GuavaClientSideCache`、`GuavaCacheEvictedEventBus` | `@CaffeineCache`、`CaffeineClientSideCache.build(...)`、`LocalCacheEvictedEventBus` |
| `MockDistributedCache` | `InMemoryDistributedCache` |
| `CoherentCache.clientSideCache` / `.distributedCache` / ... | `CoherentCache.configuration.clientSideCache` / ... |
| 自定义 `CacheEvictedSubscriber` | 实现 `onReset()`（放弃本地副本） |
| 自定义 `CodecExecutor` | `executeAndDecode(key)` 自行读取值与 TTL；继承 `StringCodecExecutor` / `HashCodecExecutor` 或 `AbstractCodecExecutor` |
| 按类型匹配的 `ClientSideCache<User>` bean | 命名为 `UserCache.ClientSideCache` |
| `TtlConfiguration`、`TtlConfigurationAware`、`ComputedCache` | 已移除，TTL 来自 `@CoCache`（`TtlPolicy`） |
| `@CoCache` 不指定 `ttl` 即永久 | 现为 3600 秒；如需旧行为请显式设置 `ttl = TtlAt.FOREVER` |
| `JoinCache.evict(key)` 同时淘汰两个缓存 | 只淘汰主缓存；同时淘汰请用 `evict(firstKey, joinKey)` |
| `CacheSource` 内部读同一缓存的同一 key 会重入锁 | 改为抛出 `IllegalStateException` 快速失败（原行为会导致错误淘汰） |

### 依赖版本

| 依赖 | 版本 |
|------|------|
| Spring Boot | 4.1.1 |
| CosId | 3.2.1 |
| Guava | 33.7.2-jre |
| Kotlin | 2.4.21 |
| JUnit | 6.1.3 |

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:5.0.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>5.0.0</version>
</dependency>
```

## v4.3.0

**模块组：** `me.ahoo.cocache`

### 亮点

- **击穿防护加固** —— `DefaultCoherentCache` 的 per-key 锁映射（存在锁对象回收竞态，可能导致并发重复回源）替换为 Guava `Striped` 锁（#519）。
- **时钟线程启动竞态修复** —— `CacheSecondClock` 计时线程启动不再与字段初始化竞态；杜绝"时钟冻结 → 缓存永不过期"的故障模式（#519）。
- **旧值回填竞态修复** —— 引入 per-key 失效代际计数器，在途回源可检测加载期间到达的失效事件：过期结果被丢弃（或补偿淘汰），不再钉死在两级缓存直至 TTL（#519）。
- **生命周期管理** —— `CoherentCache` 新增幂等 `close()`（注销事件订阅 + 关闭分布式缓存）；Spring 容器关闭时自动销毁缓存（#519）。
- **脏载荷自愈** —— 无法解码的 Redis 值被删除并按缓存未命中处理（回源重建），不再每次读取都抛异常（#520）。
- **null 值归一化** —— null 值在所有 codec 下一致写入为负缓存哨兵（此前：空串损坏、NPE 或 codec 各异的语义）（#520）。
- **Redis 故障降级** —— 默认情况下，Redis 读失败降级为缓存未命中语义（回源，业务无感），写/evict 失败仅记录告警。设置 `cocache.redis.strict-failure=true` 可恢复严格抛出行为（#520）。
- **Hash/Set 原子写入** —— 结构型 codec 写入使用单 key Lua 脚本（`DEL` + `HSET`/`SADD` + `EXPIRE`），消除并发写"字段混合"脏值。空 Map/Set 写入改为静默淘汰而非抛异常（#520）。
- **哨兵值可配置** —— 新属性 `cocache.redis.missing-guard-sentinel` 缓解业务数据与 `"_nil_"` 哨兵的碰撞（#520）。

### 破坏性变更与行为说明

- **API 移除（唯一破坏性变更）**：`AbstractCodecExecutor.setPipelined`/`serialize` 成员被移除——仅影响直接继承该抽象类的第三方 codec。
- `CodecExecutor.executeAndDecode` 返回类型可空化（`CacheValue<V>?`）——对实现者协变兼容；直接调用方重编译时需处理 `null`。
- Redis 故障默认降级（此前为抛异常）；JSON codec 的 null 值从 `"null"` 字面量往返变为负缓存哨兵。
- Spring 容器关闭时自动 close 缓存；`FactoryBean.getObject()` 返回记忆化实例（消除重复事件订阅）。
- `strict-failure` / `missing-guard-sentinel` 仅作用于自动装配（fallback）创建的缓存；自定义 `DistributedCache` Bean 不受影响。
- 线上格式（存储结构、失效消息）零变更——滚动升级完全兼容。

### 依赖版本

| 依赖 | 版本 |
|------------|---------|
| Spring Boot | 4.1.0 |
| CosId | 3.2.0 |
| Guava | 33.6.0-jre |
| Kotlin | 2.4.10 |
| JUnit | 6.1.3 |

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:4.3.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>4.3.0</version>
</dependency>
```

## v4.2.0

**模块组：** `me.ahoo.cocache`

### 依赖版本

| 依赖 | 版本 |
|------|------|
| Spring Boot | 4.1.0 |
| CosId | 3.2.0 |
| Guava | 33.6.0-jre |
| Kotlin | 2.4.0 |
| JUnit | 6.1.1 |

### Gradle 配置

```kotlin
implementation("me.ahoo.cocache:cocache-spring-boot-starter:4.2.0")
```

```xml
<dependency>
  <groupId>me.ahoo.cocache</groupId>
  <artifactId>cocache-spring-boot-starter</artifactId>
  <version>4.2.0</version>
</dependency>
```

## 历史版本

完整的发布历史请查看 [GitHub Releases](https://github.com/Ahoo-Wang/CoCache/releases)。
