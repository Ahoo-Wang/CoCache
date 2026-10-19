---
title: 保持数据新鲜
description: 先写数据源再失效、在提交之后失效、处理应用之外的写入方、选择 TTL 与提前刷新，以及写入时 Redis 故障会发生什么。
---

# 保持数据新鲜

缓存的新鲜程度取决于它听到了哪些写入。本页讲如何把变更告诉 CoCache，以及如何选择寿命，为它听不到的一切设定上界。

## 规则：先写，再失效 {#rule}

**先修改数据源。变更提交之后，再失效 key。**

```kotlin
repo.rename(id, name)        // 已提交
userCache.invalidate(id)     // 所有实例丢弃该 key
```

先失效看起来更保险，其实是个漏洞。在失效与提交之间，另一个请求可能未命中、读到*旧*数据并缓存起来，这个旧值会一直活到 `ttl`。提交之后再失效就关上了这个窗口：开始于失效之前的加载被栅栏挡住，开始于失效之后的加载读到的是新数据。

多个 key 用 `invalidateAll(keys)`，它会用尽可能少的 Redis 脚本完成。

## `invalidate` 还是 `put`？ {#invalidate-vs-put}

| | `invalidate(key)` | `put(key, value)` |
|-|-------------------|-------------------|
| Redis | 删除 key | 覆盖值 |
| 其它实例 | 丢弃 L2 副本 | 丢弃 L2 副本 |
| 本实例 | 丢弃 L2 副本 | 丢弃 L2 副本（不填充） |
| 下一次读取 | 从数据源加载 | 从 Redis 读到新值 |
| Redis 故障时 | 默认在后台重试 | 抛出 `CacheWriteException` |

**优先用 `invalidate`。** 它不可能缓存一个与数据源不一致的值，下一次读取加载的正是已提交的数据。只有写入方手里已有最终值、重新加载代价又很高时才用 `put`。`put(key, value, ttl)` 指定寿命；普通的 `put` 使用缓存的 `ttlOf` 或策略。

无论哪种调用，返回之后同一实例上的下一次读取都能看到变化。其它实例在失效消息到达后看到，通常是毫秒级。

## 在事务中 {#transactions}

在 Spring 事务中，“提交之后”并不是你的代码运行的位置。`TransactionalInvalidator`（classpath 中有 `spring-tx` 时由 starter 注册）把失效挪到那里：

::: code-group

```kotlin [Kotlin]
@Transactional
fun rename(id: String, name: String) {
    repo.rename(id, name)
    invalidator.invalidate(userCache, id)    // 在 afterCommit 中执行
}
```

```java [Java]
@Transactional
public void rename(String id, String name) {
    repo.rename(id, name);
    invalidator.invalidate(userCache, id);   // 在 afterCommit 中执行
}
```

:::

- 同一事务内对同一缓存的多次失效合并为一次 `invalidateAll`，去重并保持顺序。
- 回滚时什么都不失效，因为数据没有变化。
- `REQUIRES_NEW` 的内层事务在它自己提交之后失效。
- 没有活动事务时立即失效。

CoCache 缓存上的 `@CacheEvict` 与 `@CachePut` 同样推迟到提交之后，见 [Spring Cache](./spring-cache.md#transactions)。

## 应用之外的写入方 {#external-writers}

批处理、CDC 消费者、运维工具与其它服务会绕过你的缓存修改数据库。它们不失效的话，缓存会陈旧到 `ttl`。

在 JVM 上，这类写入方使用 `cocache-core` 中的 `ExternalInvalidator`。它与缓存说同一套协议、用同一个后端，不需要声明任何缓存：

::: code-group

```kotlin [Kotlin]
@Bean(destroyMethod = "close")
fun externalInvalidator(backend: CoherenceBackend) = ExternalInvalidator(backend)

// 提交变更之后：
externalInvalidator.invalidate("UserCache", "42")                         // namespace，编码后的 key
externalInvalidator.invalidate("UserCache", listOf("42", "43"))           // 多个 key
externalInvalidator.invalidate("ProductCache", "7", keyPrefix = "shop:")  // 非默认前缀
```

```java [Java]
ExternalInvalidator invalidator = new ExternalInvalidator(backend);

invalidator.invalidate("UserCache", "42");
invalidator.invalidate("UserCache", List.of("42", "43"));
invalidator.invalidate("ProductCache", "7", "shop:");
```

:::

- 传入缓存的 **namespace**（未设置时就是缓存名）和**编码后的 key**：即 `toString()` 或 `keyExpression` 的结果，与它在存储 key 中的样子一致。
- 缓存不用 `cocache:` 前缀时传入 `keyPrefix`。
- 它从 Redis 删除 key 并广播失效（默认通道模式下在同一个脚本中完成）。所有实例，包括同一进程中的缓存，都会丢弃 L2 副本。
- 它不重试。Redis 故障时直接抛出，请自行重试。

在 JVM 之外，任何 Redis 客户端都能用两条命令做到同样的事：`DEL {keyPrefix}{namespace}:{encodedKey}`，然后向 `cocache:inv:{namespace}` `PUBLISH` 一个小的二进制帧（sharded 模式用 `SPUBLISH`）。帧格式见[故障排查](../operations/troubleshooting.md#external-invalidation)。省略 `PUBLISH` 会让其它实例的 L2 陈旧最多 `localMaxAge`。

## 选择寿命 {#lifetimes}

每个寿命约束一类陈旧。按它兜底的故障来选：

| 配置 | 默认值 | 约束 | 更短意味着 | 建议 |
|------|--------|------|------------|------|
| `ttl` | 1 小时 | CoCache 听不到的变更 | 更多重新加载 | 所有写入方都失效时可以很长，此时 `ttl-forever` 也安全。有写入方无法失效时调短。 |
| `ttl-jitter` | 1 分钟 | — | — | 打散过期时间，一起加载的 key 不会一起过期。实际幅度不超过 `ttl / 2`。 |
| `absent-ttl` | 1 分钟 | 查询缓存了“不存在”之后，新建的行多久不可见 | 不存在的 key 被加载得更频繁 | 保持较短，或者在新建行时失效该 key。 |
| `local-max-age` | 5 分钟 | 失效消息丢失时 L2 的陈旧 | 每个 key 每个周期多一次 Redis 读取 | 对命中路径没有任何开销。几分钟的偏差都不能接受的数据可以调低。 |
| `degraded-local-max-age` | 10 秒 | 无法写入 Redis 的加载 | Redis 故障期间加载更多 | 很少需要修改。 |
| `lease-wait` | 200 毫秒 | — | — | 其它实例等待进行中加载的时长。loader 更慢时调大，以保持全集群只加载一次。 |
| `lease-ttl` | 10 秒 | — | — | 崩溃的 loader 留下的 lease 会阻塞 key 多久。应大于 loader 的 p99。 |

```yaml
cocache:
  defaults:
    local-max-age: 2m
  caches:
    "[UserCache]":
      ttl-forever: true      # 所有写入方都会失效
    "[PriceCache]":
      ttl: 30s               # 由无法失效的系统写入
      absent-ttl: 5s
```

不写单位的数字按秒解析。更短的时长请写单位：`lease-wait: 500ms`，而不是 `lease-wait: 0.5`。

## 提前刷新 {#refresh-ahead}

热点 key 过期时，下一个读取者要承担完整的加载延迟。提前刷新把它隐藏起来：读到的值离 Redis 过期不足 `refreshWindow` 时，立即返回该值，并安排一次后台重新加载。

```yaml
cocache:
  caches:
    "[UserCache]":
      ttl: 10m
      refresh-window: 1m
```

- 窗口必须小于 `ttl − ttl-jitter`，也必须小于 `local-max-age`，否则每个新副本一出生就在窗口内。按默认值（`local-max-age` 为 5 分钟），窗口必须小于 5 分钟。
- 每个 key 只有一个实例能在 Redis 中抢到刷新令牌并调用 loader，其它实例读回刷新后的值。集群对每个 key 只重新加载一次。
- 只有带 loader 的缓存会刷新。负缓存不刷新。
- 刷新失败或被跳过只记日志。值照常过期，所以陈旧度上界从不依赖刷新。
- 刷新在一个有界线程池中执行（最多 4 个线程，队列 1024）。池满时跳过刷新，计入 `cocache.refresh.rejections`。

要关闭注解开启的窗口，设置 `@CoCache(refreshWindow = 0)`，或在[定制器](./caches.md#customizer)中把 `refreshWindow` 设为 `null`。

## 写入时 Redis 故障 {#redis-down}

**`invalidate`** 先执行本地效果（丢弃本实例的 L2）。如果 Redis 删除失败：

- 默认（`invalidate-failure: retry`）调用**不抛出**。失效通常在提交之后执行，抛出异常什么也回滚不了。删除交给有界的重试队列：最多 8 次，退避从 100 毫秒起翻倍到 10 秒，最多同时排队 10 000 个。
- 重试期间，本实例上该 key 的未命中跳过 Redis（其中仍是旧值），直接从数据源加载。
- 重试耗尽或队列溢出时，CoCache 记录错误日志并计入 `cocache.invalidations.dropped`。其它实例此后可能从 Redis 重新加载旧值，直到它过期，所以上界是该值的剩余寿命。`ttl-forever` 时没有上界：Redis 恢复后请再失效一次。
- 设置 `invalidate-failure: throw` 时，调用改为抛出 `CacheWriteException`，由你决定如何处理。

**`put`**，以及 `SetCache` 的写入 `add`、`remove`、`clear`，会抛出 `CacheWriteException`。超时之后结果可能未知，但这些操作都是幂等的，可以安全重试。

重试队列的上限来自 `CacheRuntime` 上的 `RetryPolicy`。在 Spring Boot 中修改它，需要声明自己的 `CacheRuntime` bean（见[配置参考](./configuration.md#beyond-properties)）。在健康检查详情（`pendingInvalidations`）和[可观测性](../operations/observability.md)中观察这个队列。
