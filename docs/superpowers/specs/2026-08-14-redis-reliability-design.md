# Redis 可靠性专项设计（Redis Reliability）

- **日期**: 2026-08-14
- **状态**: 已确认（设计评审通过）
- **目标版本**: 4.3.x（minor）
- **范围**: cocache-spring-redis、cocache-spring-boot-starter

## 背景

2026-08-14 的全面代码审查在 Redis 层发现 4 项 major 问题与 1 项紧密相关的 minor，本专项一次性解决。核心并发专项（PR #519）已合入，本专项为其后继。

| # | 问题 | 位置 |
|---|------|------|
| 6 | 损坏/跨版本不兼容的 JSON 载荷无自愈，读路径持续抛异常 | `codec/ObjectToJsonCodecExecutor.kt:48-50`、`AbstractCodecExecutor.kt:56-67` |
| 7 | null 值在 String/Map/Set codec 下无法往返（抛异常或静默变空串），四 codec 语义不一致 | `StringToStringCodecExecutor.kt:28-33`、`MapToHashCodecExecutor.kt:29-34`、`SetToSetCodecExecutor.kt:30-35`、触发入口 `CoSpringCache.kt:76-78` |
| 8 | Redis 故障无降级，异常穿透到业务调用方 | `RedisDistributedCache.kt:34-61`、`RedisCacheEvictedEventBus.kt:42-44` |
| 9 | Hash/Set codec 的 `DEL + HMSET/SADD + EXPIRE` pipeline 非原子，并发写产生"字段混合"脏值 | `AbstractCodecExecutor.kt:45-52`、`MapToHashCodecExecutor.kt:48-59`、`ObjectToHashCodecExecutor.kt`、`SetToSetCodecExecutor.kt:49-60` |
| 附 | `"_nil_"` 哨兵值与真实业务数据碰撞，读侧静默误判 | `cocache-core/.../MissingGuard.kt:18-50`（契约被 redis codecs 依赖） |

### 问题机制简述

**#6**：`objectMapper.readValue` 无 try/catch，上层 `DefaultCoherentCache.getCache` 也不捕获。某 key 被外部写坏或值类型不兼容变更后，该 key 每次读缓存都抛异常直达业务方，直到 TTL 自然过期——缓存本应隔离脏数据，反而成了持续故障源。

**#7**：`ComputedCache.set` 只把 `DefaultMissingGuard` 哨兵对象识别为"空值"，Spring 桥接传入的原生 null 以 `CacheValue(value=null)` 进入 codec：String codec 在 SDR 3.x 抛异常、4.x 存成空串（null 读回变 `""`）；Map codec NPE；Set codec 的 `SADD` 无成员被 Redis 拒绝。仅 JSON codec（存 `"null"` 字面量）能往返，且其语义（往返出 null 值）与内存实现的负缓存语义（读回 missing guard）也不一致。

**#8**：所有 Redis 操作失败以 `RedisConnectionFailureException` 等异常抛出，全链路无捕获。Redis 主从切换期间，所有未命中本地缓存的请求全部失败，而不是退化为回源数据库。

**#9**：pipeline 只是批量发送不是事务。两实例并发写同一 key 可交错为 `A.DEL → B.DEL → A.HMSET → B.HMSET`，HMSET 合并语义导致最终值是两个版本字段的并集（既非 A 也非 B），脏数据存活整个 TTL。

**哨兵**：业务值恰好等于 `"_nil_"`（String）、`{"_nil_"}`（Set）、单键 `"_nil_"` 的 Map 时被误判为负缓存，静默返回 null。（实施后注：本专项的哨兵可配置仅缓解 Redis 外部写入碰撞的读侧；核心层常量判定不变，端到端语义见"哨兵值可配置"节的限定范围说明。）

## 目标

1. #6：损坏载荷自愈——decode 失败删除该 key 并按缓存未命中处理（触发回源重建），业务方不再看到持续异常。
2. #7：null 归一化——所有 codec 下 `put(key, null)` 统一成为负缓存写入（哨兵），读回 missing-guard，与内存实现语义对齐。
3. #8：故障降级——读失败按未命中处理（上层回源，业务无感）；写/evict/广播失败 WARN 后吞掉；提供 `strictFailure` 配置改回严格抛异常。
4. #9：Hash/Set 写入原子化——Lua 脚本单命令语义，消除字段混合脏值。
5. 哨兵可配置——默认不变，业务数据碰撞的用户可切换自定义哨兵。
6. 新建 codec TCK 规范，统一断言 null 往返、脏载荷自愈、哨兵识别、原子写入。

## 非目标

- 不修改任何线上存储格式与失效消息格式（字节级兼容，见约束）。
- 不解决 pub/sub 断线丢消息（属已规划的 L1 版本化专项）。
- 不处理两次 RTT（`getExpire` + GET 分离）、频道命名空间、`CoSpringCache` 过期读广播等其余 minor。
- 不引入 schema 版本号或载荷包装结构（属 L1 版本化专项）。
- 不改动 cocache-core 的 `MissingGuard` 常量与判定逻辑。

## 约束（已确认的决策）

1. **范围**：4 项 major + 哨兵值碰撞。
2. **降级语义**：默认降级 + 可配置严格模式（`strictFailure`，默认 false）。默认行为变更需进 release notes。
3. **线上格式**：字节级兼容——新实例写入的数据旧实例必须能正确读取；可进 4.3.x minor。所有修复均不改存储结构：null 归一化复用既有 missing-guard 哨兵格式；自愈只删 key；Lua 只改写入方式；哨兵默认值不变。
4. **测试验收**：修复 + codec TCK 规范（`CodecExecutorSpec`）。

## 方案选型

选定 **方案 A：集中防御层**。备选：

- **B 最小分散修复**：各 codec 就地 catch/null 检查、pipeline 换 MULTI/EXEC。改动面小，但防御逻辑在四 codec 重复四份，新增 codec 须记得复制全部防御——正是规范缺失让原始 bug 漏网。
- **C 装饰器架构**：引入 `ResilientDistributedCache`、`SelfHealingCodecExecutor` 装饰器。结构最分离但类数近翻倍，各仅单一实现，过度抽象违反 YAGNI。

选 A 的理由：`AbstractCodecExecutor` 已是模板方法模式、`RedisDistributedCache` 已是全部 Redis I/O 的唯一入口——防御逻辑放在模板层与唯一入口，零新增抽象、一处修改全覆盖，与项目现有结构同构。

## 详细设计

### #6 脏载荷自愈（`CodecExecutor` + `AbstractCodecExecutor`）

接口 `executeAndDecode` 返回类型从 `CacheValue<V>` 放宽为 `CacheValue<V>?`（实现方声明非空返回仍为协变兼容；`RedisDistributedCache.getCache` 本就返回可空）。

模板层包裹 `decode()` 调用：

```kotlin
override fun executeAndDecode(key: String, ttlAt: Long): CacheValue<V>? {
    val rawValue = getRawValue(key)
    if (rawValue == null || isMissingGuard(rawValue)) {
        return missingGuardCacheValue(ttlAt)  // 直接以绝对 ttlAt 构造（勿用 missingGuard(ttl)——其按相对时长计算）
    }
    val value = try {
        decode(rawValue)
    } catch (e: Exception) {
        log.warn(e) { "Cache Name[$key] - Corrupted payload - evict and treat as cache miss." }
        redisTemplate.delete(key)
        return null
    }
    return DefaultCacheValue(value, ttlAt)
}
```

**语义决策——返回 null 而非 missing-guard**：该 key 在 Redis 中存在、只是载荷损坏。按负缓存（"已知不存在"）处理会阻止上层回源；按未命中（null）处理让 `DefaultCoherentCache.getCache` 走回源重建，才是"自愈"。同时删除脏 key，避免后续读重复撞同一损坏载荷（删除失败被 #8 的降级捕获覆盖，整体仍按 miss 处理）。

覆盖面：当前仅 JSON codec 的 `decode` 会抛，但模板层防御对未来任何 codec 生效。

### #7 null 归一化（`AbstractCodecExecutor.executeAndEncode`）

模板层在编码前统一归一：

```kotlin
override fun executeAndEncode(key: String, cacheValue: CacheValue<V>) {
    val normalizedValue = if (cacheValue.value == null && cacheValue.isMissingGuard.not()) {
        missingGuardCacheValue(cacheValue.ttlAt)  // 直接以绝对 ttlAt 构造（勿用 missingGuard(ttl)）
    } else {
        cacheValue
    }
    if (normalizedValue.isExpired) {
        // 写入时已过期（含归一化路径与亚秒边界）：淘汰而非落盘
        redisTemplate.delete(key)
        return
    }
    if (normalizedValue.isForever) setForeverValue(key, normalizedValue)
    else setValueWithTtlAt(key, normalizedValue)
}
```

效果：所有 codec 下 null 写入成为负缓存（写哨兵、读回 missing-guard），与内存实现及 TCK 既有断言（`CoSpringCacheTest.getWithLoaderKeepsCachedNull`）的语义对齐。

**行为变更说明**：JSON codec 现有的 `"null"` 字面量往返行为被统一掉。变更方向是"四种不一致语义 → 四种一致语义"，且新语义与 TCK 已断言的内存语义相同；release notes 需记录。字节级兼容：missing-guard 哨兵写入格式已存在于线上（各 codec 的 `toRawValue` 分支），零新格式。

### 哨兵值可配置

- `AbstractCodecExecutor` 新增 `protected open val missingGuardSentinel: String`，默认 `MissingGuard.STRING_VALUE`；四个 codec 的 `toRawValue`（写入哨兵）与 `isMissingGuard`（读取判定）改用该属性，替换对常量的直接引用。
- `RedisDistributedCacheFactory` 构造函数新增 `missingGuardSentinel: String = MissingGuard.STRING_VALUE` 与 `strictFailure: Boolean = false`，透传给 fallback 构造的 executor 与 cache。
- starter `CoCacheProperties` 新增嵌套配置：

```kotlin
data class CoCacheProperties(
    val enabled: Boolean = true,
    val redis: Redis = Redis(),
) {
    data class Redis(
        val strictFailure: Boolean = false,
        val missingGuardSentinel: String = MissingGuard.STRING_VALUE,
    )
}
```

- **文档化约束**：自定义哨兵与默认哨兵互不识别——启用自定义值需全集群同时切换（或接受滚动升级期间旧实例把新哨兵当真实值的瞬态误读）。定位为高级逃生舱，非常规配置。
- **限定范围**（实施审查确认）：进程内 `Any?.isMissingGuard` / `DefaultCacheValue.isMissingGuard`（cocache-core）仍基于常量——值为 `"_nil_"` 形状的 `CacheValue` 在 core 各层（ComputedCache、client cache、DefaultCoherentCache）依旧被视为负缓存，经 API 写入的业务值 `"_nil_"` 也会被编码为哨兵。自定义哨兵仅改变 Redis 静止字节的写入与读侧识别（外部写入碰撞场景），**不改变端到端读语义与 API 写路径**。核心层常量判定的调整属 core 契约变更，超出本专项范围。

### #8 故障降级（`RedisDistributedCache` + `RedisCacheEvictedEventBus`）

`RedisDistributedCache` 构造函数新增 `strictFailure: Boolean = false`，三个操作统一模式：

| 操作 | 降级行为（默认） | 严格模式 |
|---|---|---|
| `getCache` | 捕获 `DataAccessException` → WARN → 返回 null（未命中 → 上层回源，业务无感） | 重抛 |
| `setCache` | WARN 后吞掉（缓存写入失败不阻断业务写路径） | 重抛 |
| `evict` | WARN 后吞掉 | 重抛 |

捕获范围限定 `DataAccessException`（Spring Redis 异常层级：连接失败、超时、命令错误），不捕 `Throwable`。codec 层自愈的 delete 在 `executeAndDecode` 内部，已被 `getCache` 的捕获覆盖。

`RedisCacheEvictedEventBus.publish` 的 `convertAndSend` 包裹 try/catch(DataAccessException) → WARN 吞掉，**无 strict 开关**（pub/sub 本就是 fire-and-forget，与已接受的丢消息语义一致）。

配置透传链：`cocache.redis.strict-failure` / `cocache.redis.missing-guard-sentinel` → `CoCacheAutoConfiguration` → `RedisDistributedCacheFactory` 构造参数 → cache/codec。

### #9 Lua 原子化（`AbstractCodecExecutor` 共享写入助手）

替换 `setPipelined`（DEL+写+EXPIRE 三步 pipeline）为两条 Lua 脚本助手：

```
executeAtomicHashWrite(key, hashes: Map<String,String>, ttlSeconds: Long)  // Map/ObjectToHash 共用
executeAtomicSetWrite(key, members: Set<String>, ttlSeconds: Long)        // SetToSet 用
```

脚本语义（以 Hash 为例）：

```lua
redis.call('DEL', KEYS[1])
-- HSET key f1 v1 f2 v2 ...（ARGV 为扁平 field/value 对，最后一个 ARGV 为 ttl）
local ttl = tonumber(ARGV[ARGV.length])
if ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl) end
return 1
```

- 经 `DefaultRedisScript<Long>` + `redisTemplate.execute(script, keys, *args)` 执行，Spring 自动 EVALSHA 缓存脚本；单 key 脚本在 Redis Cluster 下随 key slot 路由，天然兼容。
- `ttlSeconds = 0` 表示 FOREVER，脚本跳过 EXPIRE（保持 `setForeverValue` 语义）。
- **空集合守卫**：空 Map/Set 只执行 DEL（避免 HSET/SADD 无成员命令错误；现有代码同样存在此边界）。
- 存储结构不变（HSET 字段、SADD 成员），字节级兼容。
- `setPipelined` 与 `serialize(hashes)`/`serialize(value)` 序列化助手**移除**（实施决策，修订原"保留复用"设想）：Lua 参数由 `redisTemplate.execute` 的 String 序列化器处理，这些服务于旧 pipeline 协议的内部管道代码无剩余调用方，保留只会成为死代码。属 public API 移除（对直接继承/调用 `AbstractCodecExecutor` 这些成员的外部代码构成破坏），记入 release notes 破坏性清单。

## Codec TCK 规范（cocache-spring-redis 测试源集）

> 放置说明：`CodecExecutor` 类型在 spring-redis 模块，规范须与其同处（cocache-test 依赖实现模块是反向依赖）。断言风格沿用 cocache-test 的 fluent-assert TCK 模式。
> 现状说明：`CodecExecutorSpec` 抽象类与四个 codec 具体测试类**已存在**（含往返、TTL 容差、哨兵往返四个既有断言），本专项在其上**扩展**新断言，不新建。

新建抽象 `CodecExecutorSpec`，四个 codec 各一个具体类继承，统一断言：

1. **null 往返**：`executeAndEncode(key, CacheValue(null, ttlAt))` → `executeAndDecode` 返回 missing-guard（负缓存），无异常。
2. **脏载荷自愈**（按 codec 类型分派）：JSON codec 植入非法 JSON 字符串 → `executeAndDecode` 返回 null 且 key 被删除（#6 自愈路径，`decode` 有真实反序列化）。结构型 codec（String/Map/Set）的 `decode` 是恒等函数、不会抛数据异常——其"损坏"表现为 Redis 类型不匹配（WRONGTYPE，`DataAccessException`），由 #8 降级路径覆盖（`getCache` 返回 null），在降级测试中断言。
3. **哨兵往返**：guard 写入 → 读回 guard；普通业务值写入 → 读回原值。
4. **原子写入**：写入后断言 Redis 结构（HGETALL/SMEMBERS 内容）与 TTL（`getExpire`）均正确。

降级测试（无需 Redis，mockk 抛 `DataAccessException`）：

- `RedisDistributedCache`：默认模式三操作降级（getCache 返回 null、setCache/evict 不抛）；strict 模式三操作重抛。
- `RedisCacheEvictedEventBus.publish`：失败吞掉不抛。

集成测试（CI Redis 容器）：`CodecExecutorSpec` 四具体类 + 并发写一致性冒烟（两线程交替写不同版本，最终值是完整单一版本，无字段混合）。

## 错误处理与边界情况

- **decode 失败 + delete 也失败**（Redis 中途故障）：delete 异常被 `getCache` 的降级捕获，整体按 miss 处理；下次读取重复自愈尝试，无害。
- **null 归一化的对象图**：仅顶层 value == null 归一；集合内含 null 元素不处理（各 codec 现有序列化行为不变）。
- **strict 模式下的自愈**：decode 异常是数据问题而非连接问题，自愈捕获（`catch (e: Exception)`）在 codec 模板层、先于 strict 判定——strict 模式仍会对损坏载荷自愈（这是数据修复，不是故障降级）。
- **Lua 脚本不可用**（被 Redis 管理员禁用 EVAL）：`execute` 抛 `DataAccessException` → 被写路径降级吞掉（默认模式）/ 重抛（strict）；不回退 pipeline（避免同版本内双路径行为漂移）。
- **空集合写入**：只 DEL（等价于淘汰该 key）。
- **配置为严格模式的旧升级用户**：默认 false 与旧行为（抛异常）不同——升级即默认获得降级，release notes 显著标注。

## 影响范围

| 模块 | 文件 | 变更 |
|---|---|---|
| cocache-spring-redis | `codec/CodecExecutor.kt` | `executeAndDecode` 返回类型可空化 |
| cocache-spring-redis | `codec/AbstractCodecExecutor.kt` | 自愈 + null 归一化 + `missingGuardSentinel` + 两个 Lua 助手 |
| cocache-spring-redis | `codec/StringToStringCodecExecutor.kt`、`MapToHashCodecExecutor.kt`、`ObjectToHashCodecExecutor.kt`、`SetToSetCodecExecutor.kt` | 哨兵属性化引用；Hash/Set 改 Lua 助手 |
| cocache-spring-redis | `RedisDistributedCache.kt` | 降级 + `strictFailure` |
| cocache-spring-redis | `RedisDistributedCacheFactory.kt` | 两个构造参数透传 |
| cocache-spring-redis | `RedisCacheEvictedEventBus.kt` | publish 包裹 |
| cocache-spring-boot-starter | `CoCacheProperties.kt`、`CoCacheAutoConfiguration.kt` | `redis.strictFailure` / `redis.missingGuardSentinel` |
| 测试 | `CodecExecutorSpec`（扩展）+ 新增降级单测、总线单测、并发一致性冒烟 | 扩展/新增 |

不改动：cocache-core 任何文件（`MissingGuard.STRING_VALUE` 常量保留）、cocache-api、线上存储格式、失效消息格式。无新增依赖。

## 兼容性注记

- `executeAndDecode` 可空化：实现方协变返回（源码兼容）；JVM 方法签名不含可空性（二进制兼容）。仅"混用新旧 jar 调用"场景（不受支持）有风险。
- 默认行为变更（需 release notes）：① Redis 故障从抛异常变为降级（可经 `strictFailure` 找回旧行为）；② JSON codec 的 null 从 `"null"` 字面量往返变为负缓存哨兵；③ 所有 codec 的 null 写入从异常/静默损坏变为负缓存；④ 混合版本读取方向：滚动升级期间新实例读到旧实例写入的 `"null"` 字面量（JSON codec）会解码为 null 值命中（非自愈路径）——与旧实例自身行为一致、无回退，但这些 key 在升级窗口内延迟获得新负缓存语义；原始类型 JSON 缓存遇 `"null"` 抛异常 → Task 1 自愈删除 → 干净未命中，两种路径均健全。
- 实施中发现的既有 bug 一并修复：`executeAndDecode` 此前经 `missingGuard(ttlAt)` 构造负缓存返回值，把绝对时间戳按相对时长计算，导致读回的负缓存到期时间约为两倍纪元秒（客户端负缓存实际永不过期）。修复为直接以绝对 ttlAt 构造（`missingGuardCacheValue` 助手）。
- 降级模式的运行时权衡（release notes 需记录）：Redis 故障期间每次失败的读/写各产生一条含堆栈的 WARN（高 QPS 全故障下日志量可观，后续可考虑采样去重）；故障期间本地 L2 未命中的 key 退化为回源（每进程每 key 每客户端 TTL 至多一次回源放大，L2 持续服务已缓存热 key）。
- 空 Map/Set 写入行为变化（release notes 需记录）：此前 hMSet 空 Map / SADD 无成员会被 Redis 拒绝并抛异常；原子化后空集合写入静默淘汰该 key（与负缓存语义一致，属纯改善）。
- 最终整体审查补充（release notes 需记录）：① 写入时已过期的值现统一淘汰该 key（此前亚秒边界下结构性 codec 可能落盘为无 TTL 的永不过期 key——已修复）；② `AbstractCodecExecutor` 的 `setPipelined`/`serialize` public 成员被移除（见 #9 节修订），对外部直接继承者构成破坏；③ `strictFailure`/`missingGuardSentinel` 仅作用于**自动装配（fallback）创建**的缓存——用户自定义 `DistributedCache` Bean 的行为不受这两个配置影响。
- 存储格式、消息格式：零变更，滚动升级双向兼容。

## 验收标准

1. `./gradlew check` 全绿；CI（含 Redis 集成测试）全绿。
2. 新增 TCK 断言在修复前失败、修复后通过（脏载荷自愈、null 往返在 String/Map/Set codec 上修复前必须红）。
3. 字节级兼容：修复后实例写入的任意数据，修复前版本的读取逻辑（按 git 基线代码推演）可正确解析。
4. 降级路径单测全覆盖（默认/严格 × 读/写/evict/广播）。

## 开放问题

无。
