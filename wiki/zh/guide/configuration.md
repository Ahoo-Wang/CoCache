---
title: 配置
description: CoCache 的全部配置：@CoCache 与 @CaffeineCache、cocache.* 配置项及其优先级、Redis 连接与通道模式、编解码、按条目决定寿命、集合缓存，以及用代码构建缓存。
---

# 配置

缓存的配置来自四层，后一层按字段覆盖前一层：

1. **内置默认值**（`CachePolicy`）。
2. **声明**：接口上的 `@CoCache` / `@CaffeineCache`，或你构建的 `CacheSpec`。
3. **`CacheSpecCustomizer` bean**，按 Spring 顺序依次应用。
4. **配置文件**：先 `cocache.defaults.*`，再 `cocache.caches.[name].*`。

配置文件放在最后，运维调参不需要重新编译。注意这意味着 `cocache.defaults.*` 会覆盖注解中的设置。[`key-prefix`](#key-prefix) 同样如此：配置文件甚至会覆盖 `@CoCache` 或 spec 中显式写出的 `keyPrefix`。

## `@CoCache`

时长单位为**秒**。所有属性默认为 `CoCache.UNSET`，表示“交给下一层”。

| 属性 | 默认值 | 含义 |
|------|--------|------|
| `name` | 接口简单类名 | 缓存名，也是 bean 名 |
| `namespace` | 缓存名 | 每个 Redis key 和失效频道的一部分 |
| `keyPrefix` | `cocache:` | 每个 Redis key 的开头。Redis key 为 `{keyPrefix}{namespace}:{key}` |
| `keyExpression` | `""`（使用 `key.toString()`） | 以 key 为根对象求值的 SpEL 模板，例如 `#{tenantId}:#{id}` |
| `ttl` | `3600` | L1 中值的寿命。`CoCache.FOREVER` 表示永不过期 |
| `ttlJitter` | `60` | 随机抖动：每个值存活 `ttl ± ttlJitter`。`0` 表示关闭。实际抖动不超过 `ttl / 2` |
| `absentTtl` | `60` | 负缓存（“不存在”）的寿命，不抖动 |
| `localMaxAge` | `300` | L2 副本的最长寿命。它总是有限值：这就是失效事件丢失时的陈旧度上界 |
| `refreshWindow` | 关闭 | 值的剩余寿命不超过该值时，集群中只有一个节点在后台重新加载。`0` 表示关闭 |
| `loader` | `""` | `CacheLoader` 的 bean 名。为空时按 `CacheLoader<K, V>` 类型唯一匹配，找不到则没有 loader；有多个候选时启动失败 |
| `codec` | `""` | `Codec` 的 bean 名。为空时按 `Codec<V>` 类型唯一匹配，找不到则按值类型使用 Jackson；有多个候选时启动失败 |

`CoCache.FOREVER` 只能用于 `ttl`（永不过期）和 `@CaffeineCache.expireAfterAccess`（不启用空闲淘汰）。其它属性设为 `FOREVER`，或设为 `0`（`ttlJitter` 与 `refreshWindow` 除外），都会导致启动失败。

接口必须以具体类型参数继承 `Cache<K, V>` 或 `SetCache<K, E>`。注解必须直接标在接口上：不读取元注解，也不读取继承来的注解。接口上的 default 方法按原样执行，可以用来添加辅助方法；与 `Cache`（或 `SetCache`）的方法都不匹配的抽象方法会导致启动失败。

## `@CaffeineCache`

设置 L2 的容量。L2 固定为 Caffeine，并且总是有界。

| 属性 | 默认值 | 含义 |
|------|--------|------|
| `maximumSize` | `10000` | 每个实例的最大条目数 |
| `initialCapacity` | 未设置 | 哈希表初始大小 |
| `expireAfterAccess` | 关闭 | 空闲超过该秒数的条目被淘汰 |

正确性不需要 `expireAfterAccess`：每个条目本来就会在 `localMaxAge` 和 L1 到期时过期。空闲淘汰只是更早释放内存，而且每次读取都要写访问时间，在极热的 key 上会降低吞吐。

## 配置项 {#properties}

| 配置项 | 默认值 | 含义 |
|--------|--------|------|
| `cocache.enabled` | `true` | `false` 关闭 CoCache 的全部自动配置，包括 Actuator 端点、健康检查与指标 |
| `cocache.backend` | `redis` | `redis`：Redis 后端（[见下文](#redis-connection)）。`memory`：用于开发与测试的进程内后端（[见下文](#memory-backend)）。自己声明的 `CoherenceBackend` bean 优先于两者 |
| `cocache.defaults.*` | | 所有缓存的策略（见下表） |
| `cocache.caches.[name].*` | | 按缓存名（不区分大小写）设置单个缓存的策略。出现未声明的缓存名时启动失败（多半是拼写错误） |
| `cocache.redis.*` | | Redis 后端（[见下文](#redis-connection)） |
| `cocache.spring-cache.require-sync` | `false` | `true`：指向 CoCache 缓存的 `@Cacheable` 没有 `sync = true` 时启动失败。`false`：只记录告警。见 [Spring Cache](./spring-cache.md#use-sync-true) |

### 策略配置项

以下配置项在 `cocache.defaults.` 与 `cocache.caches.[name].` 下都可用。未设置的项沿用下一层的值。

| 配置项 | 内置默认值 | 含义 |
|--------|-----------|------|
| `ttl` | `1h` | L1 中值的寿命 |
| `ttl-forever` | `false` | `true` 时值永不过期（覆盖 `ttl`） |
| `ttl-jitter` | `1m` | 值寿命的随机抖动（实际抖动 ≤ `ttl / 2`） |
| `absent-ttl` | `1m` | 负缓存寿命 |
| `local-max-age` | `5m` | L2 副本的最长寿命 |
| `degraded-local-max-age` | `10s` | 未能写入 L1 的填充（Redis 故障、等待 lease 超时、填充被拒绝）在 L2 中的最长寿命 |
| `refresh-window` | 关闭 | 提前刷新窗口 |
| `lease-ttl` | `10s` | 持有者一直不填充（节点崩溃）时 L1 lease 的寿命，应大于 loader 的 p99 耗时 |
| `lease-wait` | `200ms` | 未命中时等待其它节点 lease 的最长时间，超时后自行回源 |
| `load-max-concurrent` | `0`（不限制） | 每个缓存、每个实例同时进行的回源数上限 |
| `load-acquire-timeout` | `1s` | 等待回源许可的最长时间，超时抛出 `SourceLoadRejectedException` |
| `local-maximum-size` | `10000` | L2 最大条目数 |
| `invalidate-failure` | `retry` | `retry`：失效写入 L1 失败时进入有界的后台重试队列。`throw`：抛出 `CacheWriteException` |
| `read-failure` | `degrade` | `degrade`：读取 L1 失败时直接回源。`throw`：抛出 `CacheReadException` |
| `key-prefix` | `cocache:` | 每个 Redis key 的开头（[见下文](#key-prefix)）。不属于策略，但按同样的方式分层 |

不带单位的数字表示**秒**（`ttl: 120`），也可以使用 Spring 的时长写法（`120s`、`2m`、`PT2M`）。

```yaml
cocache:
  defaults:
    local-max-age: 60s
  caches:
    "[UserCache]":
      ttl: 10m
      load-max-concurrent: 32
      read-failure: throw
```

### key 前缀 {#key-prefix}

Redis key 为 `{keyPrefix}{namespace}:{key}`。多个应用共用一个 Redis 时，可以为所有缓存统一设置前缀，个别缓存再单独覆盖。占位符与其它 Spring 配置一样解析：

```yaml
cocache:
  defaults:
    key-prefix: ${spring.application.name}:
  caches:
    "[PolicyIndexCache]":
      key-prefix: "shared:"
```

`cocache.caches.[name].key-prefix` 优先于 `cocache.defaults.key-prefix`，后者优先于声明（`@CoCache(keyPrefix = …)` 或 `CacheSpec.keyPrefix`）与 `CacheSpecCustomizer`。没有设置配置项时使用声明中的前缀，默认 `cocache:`。

- 修改运行中缓存的前缀等于冷启动：旧 key 不再被读取，随 TTL 过期。所有实例必须使用同一个前缀。
- 失效频道 `cocache:inv:{namespace}` 不含前缀。同一个 Redis 上前缀不同、namespace 相同的应用会收到彼此的失效消息，只是多余的处理，因为 key 永远不会相同。

### 校验

违反以下任一规则的策略会导致启动失败：

- 所有时长必须为正数，且不超过 100 年。只有 `ttl-jitter`、`lease-wait` 与 `load-acquire-timeout` 可以为零。
- `refresh-window` 小于 `ttl − ttl-jitter`（按实际抖动计算），并且小于 `local-max-age`。
- 已删除的 5.x 配置项（`cocache.redis.strict-failure`、`cocache.redis.eviction-channel`、`cocache.redis.missing-guard-sentinel`、`cocache.load.*`、`cocache.metrics.client-side-hits`、`cocache.next.*`）会导致启动失败，错误信息给出替代项。见[从 5.x 迁移](./migration-6.md#properties)。

## 如何选择 {#choosing-values}

- **`ttl` 兜底 CoCache 看不到的变化造成的 L1 陈旧**：绕过缓存的写入、Redis 故障切换丢失的写入、loader 读到了数据库从库上的旧数据。协议内的竞态不需要它，lease 栅栏已经防止了这些竞态。所有写入方都会失效时，`ttl-forever` 是安全的。
- **`local-max-age` 兜底失效事件丢失造成的 L2 陈旧**：半开连接、网络分区、非原子发布失败。值越小，每个 key 每次过期多一次 Redis 往返，命中路径没有任何额外成本。
- **`absent-ttl` 是新建数据可能“不可见”的时长**：之前的查询缓存了它不存在。应保持较短，或者在新建数据后失效该 key。
- **`refresh-window` 消除热点 key 过期时的延迟尖刺。** 窗口内的读取返回当前值并触发一次后台重新加载。只有在 Redis 中抢到刷新令牌的节点回源：整个集群每个 key 只重新加载一次。从不返回已过期的值，负缓存不刷新。
- **`ttl-jitter` 打散到期时间**，一起加载的 key 不会在同一秒过期。
- **`lease-wait`**：只有 loader 在 `lease-wait` 内完成时，不同节点的并发未命中才会合并为一次加载。loader 较慢时调大它，以调用方可接受的延迟为限。

## Redis 连接 {#redis-connection}

后端由 Spring Boot 的 `spring.data.redis.*` 连接信息构造。

| 拓扑 | 支持情况 |
|------|----------|
| 单机 | 支持 |
| Sentinel | 支持 |
| Cluster | 支持。脚本按 key 路由，客户端自适应刷新拓扑 |
| 静态主从（master-replica） | 不支持，启动失败 |

TLS 来自 Spring Boot 的 SSL bundle（`spring.data.redis.ssl.bundle`）：密钥与信任管理器、启用的协议与加密套件。`rediss://` URL 会以 JDK 默认配置启用 TLS。Sentinel 节点使用 `spring.data.redis.sentinel.username` / `password` 认证，数据节点使用 `spring.data.redis.username` / `password`。只支持 Lettuce。CoCache 总是读写主节点（它的读脚本还会写入 lease 和刷新令牌），并且连接断开时拒绝命令，而不是在之后重放。

| 配置项 | 默认值 | 含义 |
|--------|--------|------|
| `cocache.redis.channel-mode` | `pubsub` | `pubsub`：`PUBLISH`/`SUBSCRIBE`，与 L1 修改原子完成。`sharded`：`SPUBLISH`/`SSUBSCRIBE`（Redis 7+），在 L1 修改之后立即发布。见[一致性](../architecture/consistency.md#invalidation-channel)。**所有实例必须使用同一种模式** |
| `cocache.redis.command-timeout` | `2s` | 单条命令或脚本的最长等待时间 |
| `cocache.redis.heartbeat` | `5s` | 订阅连接的 `PING` 间隔。PING 失败或超时会强制重连，并清空 L2 |
| `cocache.redis.invalidate-batch-size` | `512` | 单个失效脚本处理的 key 数上限，避免一次调用长时间阻塞分片 |
| `cocache.redis.client-name` | 未设置 | CoCache 连接的 `CLIENT SETNAME`，便于在 `CLIENT LIST` 中识别 |

这里不带单位的数字表示**毫秒**（与 `spring.data.redis.timeout` 一致），所以请写上单位（`2s`、`500ms`）。

要使用其它后端，或自己配置的 Lettuce 客户端，声明自己的 `CoherenceBackend` bean。要修改运行时（时钟、刷新执行器、失效重试策略），声明自己的 `CacheRuntime` bean。两个自动配置的 bean 都带有 `@ConditionalOnMissingBean`。存在唯一的 `CacheClock` bean 时，自动配置的运行时（以及[内存后端](#memory-backend)）使用它。

## 内存后端 {#memory-backend}

本地开发与测试可以不依赖 Redis：

```yaml
cocache:
  backend: memory
```

此时 starter 以 `cocache-core` 中的 `InMemoryBackend` 作为 `CoherenceBackend` bean。它在进程内完整实现 L1 协议（lease、刷新令牌、负缓存、集合、原子广播）。Spring Data Redis 可以不配置，也可以不在 classpath 中。starter 启动时记录一条 `WARN`。

- **只适用于单进程。** 每个实例有自己的 L1，只能收到自己的失效。不要在多实例部署中使用。
- 存在 `CacheClock` bean 时，运行时与内存后端都使用它。测试可以声明一个 `ManualClock`，推进它让 L2 与 L1 到期，而不需要 sleep：

```kotlin
@TestConfiguration
class ClockConfig {
    @Bean
    fun clock() = ManualClock()
}

clock.advance(Duration.ofMinutes(6))   // 超过 local-max-age：下一次读取从 L1 读回
```

- 自己声明的 `CoherenceBackend` bean 仍然优先于 `cocache.backend`。

## 编解码 {#codecs}

`Codec<V>` 把值转换为存入 Redis 的字节。starter 依次选择：`@CoCache(codec = "beanName")`、唯一的 `Codec<V>` bean、使用应用 `ObjectMapper`（Jackson 3，`tools.jackson`）的 `JacksonCodec`。spec bean 必须显式设置 codec。

| Codec | 模块 |
|-------|------|
| `JacksonCodec` / `jacksonCodec<V>(mapper)` | `cocache-codec-jackson` |
| `StringCodec`（UTF-8） | `cocache-core` |

Redis 中的载荷无法解码时，CoCache 按未命中处理：只有当 key 仍是读到的那份字节时才回收它，然后重新加载。读路径从不删除。

Redis 中的值在 codec 字节之前带有 36 字节的二进制头，所以 `redis-cli GET` 看到的不再是纯 JSON。其它服务不得直接读取 CoCache 的 key。

## 按条目决定寿命 {#per-entry-ttl}

要根据 key 或值决定寿命，在 spec 上设置 `ttlOf`（直接设置，或在 `CacheSpecCustomizer` 中设置）：

```kotlin
cacheSpec<String, Session>("session") {
    codec = jacksonCodec(mapper)
    loader = CacheLoader { id -> sessionRepository.find(id) }
    ttlOf = TtlFunction { _, session -> Duration.between(Instant.now(), session.expiresAt) }
}
```

返回非 null 时优先于策略中的 `ttl`，并且不加抖动；返回 `null` 时按策略。负缓存总是使用 `absentTtl`。配置项 `ttl` 不影响 `ttlOf`。

## 定制声明 {#customizing}

`CacheSpecCustomizer` 取代了 5.x 的 bean 名约定（`{name}.ClientSideCache` 等）。它在配置文件生效之前修改 spec 构建器：

```kotlin
@Bean
fun userCacheCustomizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
    override fun supports(cacheName: String) = cacheName == "UserCache"

    override fun customize(spec: CacheSpec.Builder<*, *>) {
        spec.policy = spec.policy.toBuilder().absentTtl(Duration.ofSeconds(30)).build()
    }
}
```

注解覆盖不到的设置都用它：lease 设置、`degradedLocalMaxAge`、`ttlOf`、`KeyFilter`、自定义 codec。

## 集合缓存：原子增删元素 {#set-elements}

`SetCache<K, E>` 用于**唯一**副本在 Redis 中的集合，例如索引或成员列表。元素在 Redis 中原子地增删，两个实例同时修改同一个集合也不会丢失更新。

```kotlin
@CoCache
interface PolicyIndexCache : SetCache<String, String>

policyIndexCache.add(key, listOf("p1"))
policyIndexCache.remove(key, listOf("p1"))
policyIndexCache.clear(key)
val members: Set<String> = policyIndexCache[key]   // 不存在时为空集合
```

- `SetCache` 没有 loader（在 `SetCache` 上设置 `@CoCache(loader = …)` 会导致启动失败）。Redis 就是权威副本，所以默认 `ttl` 为永不过期。
- key 不存在时 `add` 创建集合；集合移空时 `remove` 删除 key。两者都会重设到期时间并通知其它实例。
- `invalidate` 抛出 `UnsupportedOperationException`，因为它会销毁唯一副本。删除用 `clear(key)`。
- Redis 失败时抛出 `CacheWriteException`。这些调用是幂等的，可以重试。
- 集合以 Redis Hash 存储。与原生 Set 相比，每个非整数成员多约 16 字节，整数成员失去紧凑的 intset 编码。
- Redis 的 `maxmemory-policy` 不能是 `allkeys-*`，否则可能淘汰唯一副本。持久性等于 Redis 的持久性：异步复制的故障切换可能丢失最近的写入。

如果集合的数据在数据库中，不要用 `SetCache`：声明普通的 `Cache<K, Set<E>>`，更新数据库，然后 `invalidate`。

## 用代码构建缓存 {#building-a-cache-in-code}

不用 Spring 时，创建一个 `CacheRuntime`（每个进程一个），再按 spec 创建缓存：

```kotlin
val backend = RedisBackend.standalone(RedisURI.create("redis://localhost:6379"))
val runtime = CacheRuntime.builder().backend(backend).build()

val users: Cache<String, User> = runtime.create(
    cacheSpec<String, User>("user") {
        codec = jacksonCodec(mapper)
        loader = CacheLoader { id -> userRepository.findById(id) }
        keyFilter = KeyFilter { storageKey -> !bloomFilter.mightContain(storageKey) }
        policy {
            ttl = Duration.ofMinutes(2)
            local = LocalPolicy(maximumSize = 1_000_000)
        }
    }
)

// 关闭时
runtime.close()
backend.close()
```

- `RedisBackend.cluster(uris)` 连接集群；`RedisBackend.of(client)` 使用你自己管理的 Lettuce 客户端。
- 每个运行时有一个节点身份和一条订阅连接。同一运行时内缓存名与命名空间都不得重复。
- `runtime.close()` 按创建的逆序关闭缓存。后端由创建它的一方关闭。
- `KeyFilter` 在 L2 未命中后调用。被它拒绝的 key 直接返回“不存在”，不访问 Redis 和数据源。它收到的是完整的存储 key（`{keyPrefix}{namespace}:{key}`），只能拒绝确定不存在的 key。
- 在 Boot 应用中，把同样的 spec 写成 `@Bean` 方法即可自动注册（[快速上手](./quick-start.md#spec-bean)）。
