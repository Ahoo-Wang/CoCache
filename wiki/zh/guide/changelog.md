---
title: 更新日志
description: CoCache 版本发布历史和重要变更。
---

# 更新日志

## v4.3.0（当前版本）

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
