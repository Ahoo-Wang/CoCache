---
title: 更新日志
description: CoCache 版本发布历史和重要变更。
---

# 更新日志

## v5.0.1（当前版本）

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
