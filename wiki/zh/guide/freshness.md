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

## 批量写入 {#batch-writes}

`putAll` 一次写入多个 key，每个 key 的规则与 `put` 相同：

```kotlin
userCache.putAll(users.associateBy { it.id })                          // 按 ttlOf 或策略，每个 key 各自抖动
userCache.putAll(users.associateBy { it.id }, Duration.ofMinutes(5))   // 统一指定寿命
```

- **不会写回旧值。** 整批是一次写入：本实例在写 Redis 之前和之后各丢弃一次所有 key 的 L2 副本，覆盖写入同时销毁加载用的 lease。开始于 `putAll` 之前的加载无法把旧值缓存下来，L2 和 Redis 都不会。与 `put` 一样，`putAll` 不填充本实例的 L2。
- **一次往返，一条消息。** Redis 收到一个脚本，写入全部 key，并为它们发布一条失效消息。在 Redis Cluster 中，key 按 slot 分组并按 `cocache.redis.invalidate-batch-size` 切分，每组一个脚本并发执行。每个 key 原子写入，整批不保证原子。
- **编码后的 key 冲突：** 两个 key 编码成同一个存储 key 时，后出现的为准。空 map 不做任何事。
- **失败：** 与 `put` 一样，Redis 故障时 `putAll` 抛出 `CacheWriteException`，部分 key 可能已经写入。可以安全重试。
- **`SetCache.putAll`** 整体替换每个集合，空集合清除对应的 key，与 `put` 完全一致。

没有重写 `putAll` 的自定义 `Cache` 实现会退回为逐个调用 `put`。

## 条件写入 {#conditional-writes}

Redis 是唯一副本、多个写入方以“读取—修改—写回”更新同一个值时（例如一份包含许多条记录的文档），普通的 `put` 会丢失更新：两个写入方读到同一个版本，后一次 `put` 覆盖前一次。6.7 起，`putIfMatch` 只在 Redis 中仍是写入方读到的那个值时写入，`getIfChanged` 从 Redis 读取这个值及其摘要：

```kotlin
fun update(key: String, change: (Document?) -> Document) {
    while (true) {
        val current = cache.getIfChanged(key, null)              // 读 Redis，从不读 L2
        val (document, digest) = when (current) {
            is ConditionalGet.Present -> current.value to current.digest
            else -> null to null                                  // Absent：还没有值
        }
        when (cache.putIfMatch(key, digest, change(document))) {
            is ConditionalPut.Written -> return
            is ConditionalPut.Conflict -> continue               // 别人先写了：重新读取
        }
    }
}
```

- **摘要**是值经编解码器编码后的字节的 SHA-1。`ConditionalGet.Present` 与 `ConditionalPut.Written` 都返回它。编解码器把相等的值编码成不同的字节时（例如无序的 map），摘要也不同。
- **`putIfMatch(key, expected, value)`** 在一个 Redis 脚本中比较并写入。`expected = null` 时只在 Redis 中没有值时写入：key 不存在、有加载用的 lease，或者是负缓存。否则只在值的摘要等于 `expected` 时写入。冲突返回 `Conflict(current)`（Redis 中没有值时为 `null`），什么都不改：不写入、不改寿命、不发失效消息。写入成功就是一次 `put`：寿命规则相同（`ttlOf` 或策略，或 `ttl` 参数），同样失效所有实例的 L2，本实例的下一次读取能看到它。
- **`getIfChanged(key, known)`** 询问 Redis 值的摘要是否仍是 `known`。是则返回 `Unchanged`，不传输值：Redis 只回复两个整数，确认一个大值只花一次往返，而不是它在网络上的大小。它不省 Redis 的 CPU：脚本每次都读取这个值并计算 SHA-1，期间 Redis 不处理其它命令（在 Redis 7.4 上 8 MB 的值约 20 ms，其中约 13 ms 是普通的 L1 读取同样要付的读取）。按实例数 × 值的个数 × 大小估算确认周期。否则返回 `Present(value, digest)` 或 `Absent`。用它重新确认内存中持有的值：保存摘要，定期或在收到失效时调用 `getIfChanged`。
- **两者都绕过 L2。** 只读写 Redis，从不经 loader 加载，也不填充 L2。缓存的 L2 可能比 `getIfChanged` 返回的旧，差距在 `localMaxAge` 之内。
- **失败：** Redis 故障时 `getIfChanged` 抛出 `CacheReadException`，与 `read-failure` 无关，因为没有可以降级的答案。`putIfMatch` 抛出 `CacheWriteException`。它不是幂等的：超时之后先调用 `getIfChanged`，当前摘要正是 `Written` 本应返回的那个时，说明已经写入。
- **读不懂的内容**不会被一个方法当作空值、被另一个方法当作值。更高版本的 CoCache 写入的值或集合：两个方法都抛出，`cause` 为 `ForeignFormatException`，key 不被修改。编解码器无法解码的值：`getIfChanged` 抛出；`putIfMatch` 看不到编解码器，遇到它只会返回 `Conflict`，从不覆盖。不是 CoCache 6 写入的内容（5.x 的数据）按 `foreign-format` 处理，与没有 loader 的缓存相同：`fail`（默认）时两个方法都抛出；`reclaim` 时视为没有值，`getIfChanged` 返回 `Absent`，`putIfMatch(key, null, value)` 会替换它。
- **自定义后端**必须原子地实现条件写入，否则就不实现：SPI 的 `putIfMatch` 默认抛出 `UnsupportedOperationException`，调用方会看到它。
- **所有写入方都必须用 `putIfMatch`。** 普通的 `put` 不检查，仍会压过并发的“读取—修改—写回”。
- `SetCache` 不支持它们，因为它的 `add` 与 `remove` 本身就是原子的。异步视图与[挂起视图](./coroutines.md)有同名方法。

## 按版本写入 {#versioned-writes}

有些写入方知道自己写入的每个值的版本：事件溯源的聚合只有一个写入方、版本严格递增，CDC 流带着提交序号。写入方可以在提交之后立即把新状态推入 Redis，但重投与重放会再次送来旧版本，普通的 `put` 写入旧版本就会替换新版本。6.8 起，声明为**带版本**的缓存把版本与值保存在一起，并按版本对写入排序：

```kotlin
val views = runtime.create(cacheSpec<String, OrderView>("order-view") {
    codec = jacksonCodec<OrderView>(mapper)
    versionedLoader = CacheLoader { id ->
        val state = repository.find(id) ?: return@CacheLoader null          // 从来不存在：普通负缓存
        Versioned(if (state.deleted) null else state.view, state.version)   // 在 state.version 时已删除：墓碑
    }
})

// 写入方，每次提交之后（重放与重投时也是）
when (views.putIfNewer(id, view, version)) {
    VersionedPut.Written -> Unit                  // 更新：已写入并广播
    is VersionedPut.Stale -> Unit                 // Redis 中已是这个版本或更新的版本
}
views.invalidateIfNewer(id, deletedAtVersion)     // 删除：写入墓碑，挡住旧版本

views.getVersioned(id)                            // Versioned(view, version)，或 null
```

- **声明它。** 设置 `versioned = true`，或返回 `Versioned(value, version)` 的 `versionedLoader` / `versionedAsyncLoader`（[挂起](./coroutines.md)的构建器有 `suspendingVersionedLoader`）。loader 返回 `Versioned(null, n)` 表示 key 在版本 `n` 时已删除：回源写入与 `invalidateIfNewer(key, n)` 相同的墓碑，重放的旧状态不能让它重新出现。返回 `null` 表示 key 从来不存在，缓存一个寿命为 `absent-ttl` 的普通负缓存，任何带版本的写入都会替换它。其它缓存上的按版本方法抛出 `UnsupportedOperationException`；后端不支持版本时，创建带版本的缓存立即失败。Redis 与内存后端都支持。
- **`putIfNewer(key, value, version[, ttl])`** 在一个 Redis 脚本中比较并写入，一次往返。只在 `version` 大于 Redis 中的版本时写入，返回 `Written`。版本相等是重复投递，更小是迟到的旧版本：两者都返回 `Stale(current)`，什么都不改：不写入、不改寿命、不发失效消息。版本按有符号 64 位整数比较。没有版本的值排在任何版本之前：普通 `put` 写入的值、不知道版本的 loader 加载的值、负缓存、加载用的 lease、不存在的 key。写入成功就是一次 `put`：在途的加载都写不回，所有实例的 L2 都被淘汰。
- **在途的加载不会胜出。** 读取方持有加载用的 lease 时 `putIfNewer` 落地，它的写回被拒绝，哪怕它从数据源读到的版本更高：写入方的下一个版本反正会经 `putIfNewer` 到来。先完成的加载保存 loader 给出的版本，之后的写入与它比较。
- **`invalidateIfNewer(key, version[, ttl])`** 写入墓碑：读取方看到 key 不存在（不调用 loader），这个版本及更旧版本的 `putIfNewer` 返回 `Stale`。更新的版本可以让 key 重新出现。墓碑的寿命与值相同（带抖动的 `ttl`，或 `ttl` 参数），不是 `absent-ttl`。到期之后迟到的旧版本可以再次写入，所以墓碑的寿命要覆盖重投的时间窗口。
- **`getVersioned(key)`** 与 `get` 一样读穿透，返回值及其版本。墓碑与不存在的 key 一样返回 `null`；墓碑存活期间，它的版本及更旧版本的 `putIfNewer` 仍被拒绝。没有版本的值的版本是 `Versioned.NO_VERSION`。
- **版本顺序只在条目存活期间成立。** 值或墓碑到期之后，key 中没有版本，任何版本（包括更旧的）都能再次写入。按重投的时间窗口设置 `ttl`（以及墓碑的 `ttl`）。重放到最新位置的写入方最终总会写入最新的版本，所以到期之后的陈旧度以那次重放为界。
- **普通写入会丢掉版本。** `put`、`putAll`、`putIfMatch` 与 `invalidate` 写入没有版本的内容，到期也一样，之后任何版本都能写入。需要顺序保证的写入方全部使用 `putIfNewer` / `invalidateIfNewer`，`invalidate` 留给运维清除。
- **不支持提前刷新。** 带版本的缓存拒绝 `refresh-window`；`cocache.defaults.refresh-window` 不作用于带版本的缓存。写入方会推送新版本，值不需要刷新。
- **失败：** Redis 故障时 `putIfNewer` 抛出 `CacheWriteException`。重试同一个版本是安全的：第一次已经写入时，重试返回 `Stale(version)`。读不懂的内容的分类与[条件写入](#conditional-writes)相同。
- **混合版本：** 6.0 到 6.7 的实例可以与 6.8 共用 Redis 并读取带版本的值，但它们的写入不保留版本。带版本的缓存的写入方全部升级到 6.8。
- 异步视图与挂起视图有同名方法。指标 `cocache.versioned.stale` 统计被拒绝的版本。

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
- 回滚时同样失效已登记的 key。loader 在你的线程上执行并参与事务，写入之后的读取可能已经把未提交的行放进 Redis，所有实例在 `ttl` 内都会读到它。多余的失效无害。
- `REQUIRES_NEW` 的内层事务在它自己结束时失效。
- 迟到的登记不会丢失。在其它同步的 `afterCommit` 中登记的失效立即执行；如果它是这个事务的第一次登记，则在 `afterCompletion` 中执行。在 `afterCompletion` 中（例如 `@TransactionalEventListener(AFTER_COMMIT)`）登记时立即失效。
- 一个缓存失败不影响其余缓存：全部失效之后，抛出第一个异常，其余作为它的 suppressed（只在 [`invalidate-failure: throw`](#redis-down) 时）。回滚之后的失败由 Spring 记录日志，不抛出。
- 没有活动事务时立即失效。
- 它只看得到线程绑定的事务（`PlatformTransactionManager`，如 JDBC、JPA）。在响应式事务中（`ReactiveTransactionManager`，如 R2DBC，包括 `@Transactional` 挂起函数），它立即失效，也就是在提交之前。这种情况请在事务结束之后、事务边界之外失效。

CoCache 缓存上的 `@CacheEvict` 与 `@CachePut` 同样推迟到提交之后，但回滚时不执行，见 [Spring Cache](./spring-cache.md#transactions)。

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

// 使用非默认 key 前缀的缓存用单独的实例
val shopInvalidator = ExternalInvalidator(backend, "shop:")
shopInvalidator.invalidate("ProductCache", "7")
```

```java [Java]
ExternalInvalidator invalidator = new ExternalInvalidator(backend);

invalidator.invalidate("UserCache", "42");
invalidator.invalidate("UserCache", List.of("42", "43"));
new ExternalInvalidator(backend, "shop:").invalidate("ProductCache", "7");
```

:::

- 传入缓存的 **namespace**（未设置时就是缓存名）和**编码后的 key**：即 `toString()` 或 `keyExpression` 的结果，与它在存储 key 中的样子一致。
- 缓存不用 `cocache:` 前缀时，在构造函数中传入 `keyPrefix`。
- 调用是幂等的：抛出异常时（例如删除成功而广播失败）请重试。
- 它从 Redis 删除 key 并广播失效（默认通道模式下在同一个脚本中完成）。所有实例，包括同一进程中的缓存，都会丢弃 L2 副本。
- 它不重试。Redis 故障时直接抛出，请自行重试。
- 编码后的 key 为空串时抛出 `IllegalArgumentException`；传入空集合什么也不做。`close()` 之后的任何调用都抛出 `IllegalStateException`。`close()` 只释放失效器自己创建的存储句柄，不关闭后端。

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
- 每个 key 只有一个实例能在 Redis 中抢到刷新令牌并调用 loader。集群对每个 key 只重新加载一次。
- 其它实例照常返回自己的副本，退避一段时间后再读一次 Redis：`lease-wait`（至少 100 ms），且不超过旧值剩余寿命的一半。所以它们在旧值过期之前取到重新加载的值；重新加载期间，无论 key 多热，它们读 Redis 的次数都有上界。
- 寿命不长于窗口的值（来自较短的 `ttlOf` 或 `put(key, value, ttl)`）不刷新：它一出生就在窗口内，每次命中都会重新加载它。它照常过期，在下一次未命中时重新加载。同样，在值的窗口内才第一次读到它的实例，不为这份副本发起刷新；其它实例上的副本照常发起。
- 只有带 loader 的缓存会刷新。负缓存不刷新。
- 刷新失败或被跳过只记日志。值照常过期，所以陈旧度上界从不依赖刷新。
- 刷新在一个有界线程池中执行（最多 4 个线程，队列 1024）。池满时跳过刷新，计入 `cocache.refresh.rejections`。[`cocache.refresh-executor`](./configuration.md#executors) 可以指定改用另一个 `Executor` bean。

要关闭声明中开启的窗口，可以任选其一：在[配置项](./configuration.md#policy-properties)中写 `refresh-window: 0`、`@CoCache(refreshWindow = 0)`，或在[定制器](./caches.md#customizer)中把 `refreshWindow` 设为 `null`。

## 写入时 Redis 故障 {#redis-down}

**`invalidate`** 先执行本地效果（丢弃本实例的 L2）。如果 Redis 删除失败：

- 默认（`invalidate-failure: retry`）调用**不抛出**。失效通常在提交之后执行，抛出异常什么也回滚不了。删除交给有界的重试队列：最多 8 次，退避从 100 毫秒起翻倍到 10 秒，最多同时排队 10 000 个。
- 重试期间，本实例上该 key 的未命中跳过 Redis（其中仍是旧值），直接从数据源加载。
- 重试耗尽或队列溢出时，CoCache 记录错误日志并计入 `cocache.invalidations.dropped`。其它实例此后可能从 Redis 重新加载旧值，直到它过期，所以上界是该值的剩余寿命。`ttl-forever` 时没有上界：Redis 恢复后请再失效一次。
- 设置 `invalidate-failure: throw` 时，调用改为抛出 `CacheWriteException`，由你决定如何处理。

**`put`** 与 **`putAll`**，以及 `SetCache` 的写入 `add`、`remove`、`clear`，会抛出 `CacheWriteException`。超时之后结果可能未知，但这些操作都是幂等的，可以安全重试。

重试队列的上限来自 `CacheRuntime` 上的 `RetryPolicy`。在 Spring Boot 中修改它，需要声明自己的 `CacheRuntime` bean（见[配置参考](./configuration.md#beyond-properties)）。在健康检查详情（`pendingInvalidations`）和[可观测性](../operations/observability.md)中观察这个队列。
