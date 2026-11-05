---
title: 保持数据新鲜
description: 先写数据源再失效、在提交之后失效、处理应用之外的写入方、选择 TTL 与提前刷新，以及写入时 Redis 故障会发生什么。
---

# 保持数据新鲜

缓存的新鲜程度取决于它听到了哪些写入。本页讲如何把变更告诉 CoCache，以及如何选择寿命，为它听不到的一切设定上界。缓存有哪些写入取决于它的[类型](./caches.md#types)：[`Cache`](./cache.md) 在数据源变更之后失效，[`Store`](./store.md) 直接写入（“读取—修改—写回”用[条件写入](./store.md#conditional-writes)），带版本的类型按版本对每次写入排序。

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
| Redis 故障时 | 默认在后台重试 | 结果未知时以重试的失效补偿（默认）；确定未执行时抛出 |

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
- **失败：** 与 `put` 一样，结果未知的 `Cache.putAll` 不抛出，而是登记这些 key 的失效（见[下文](#redis-down)）；确定未执行时抛出 `CacheWriteException`。`Store.putAll` 总是抛出，部分 key 可能已经写入。可以安全重试。
- **`Store.putAll`** 与 `Store.put` 一样，每个值都带写入标识与摘要。**`SetStore.putAll`** 整体替换每个集合，空集合清除对应的 key，与 `put` 完全一致。

## 按版本写入 {#versioned-writes}

有些写入方知道它写入的每个值的版本：事件溯源的聚合只有一个写入方、版本严格递增，CDC 流带有提交序号。写入方可以在每次提交后立即把新状态推进 Redis，但重复投递与重放会再次送来旧版本，用普通的 `put` 写入旧版本就会替换掉新版本。带版本的类型在每个值旁边保存版本，并按版本对写入排序。[`VersionedCache`](./versioned-cache.md) 有可以重新加载的带版本数据源，[`VersionedStore`](./versioned-store.md) 没有。以下规则对两者都适用：

```kotlin
val views = runtime.create(versionedCacheSpec<String, OrderView>("order-view") {
    codec = jacksonCodec<OrderView>(mapper)
    versionedLoader = CacheLoader { id ->
        val state = repository.find(id) ?: return@CacheLoader null          // 从来没有：普通的负缓存
        Versioned(if (state.deleted) null else state.view, state.version)   // 在 state.version 时已删除：墓碑
    }
})

// 写入方，每次提交之后（重放与重复投递时也一样）
when (views.putIfNewer(id, view, version)) {
    VersionedPut.Written -> Unit                  // 更新：已写入并广播
    is VersionedPut.Stale -> Unit                 // Redis 中已是这个版本或更新的版本
}
views.invalidateIfNewer(id, deletedAtVersion)     // 删除：挡住旧版本的墓碑

views.getVersioned(id)                            // Versioned(view, version)，或 null
```

- **数据源。** `VersionedCache` 需要返回 `Versioned(value, version)` 的 `versionedLoader` / `versionedAsyncLoader`（[挂起](./coroutines.md)构建器有 `suspendingVersionedLoader`）。返回 `Versioned(null, n)` 表示这个 key 在版本 `n` 时已删除：这次加载写入与 `invalidateIfNewer(key, n)` 相同的墓碑，重放的旧状态因此无法让它复活。返回 `null` 表示这个 key 从来没有，写入普通的负缓存（存活 `absent-ttl`），任何按版本的写入都能替换它；落在带版本的内容上时（`evict` 之后）会被[拒绝](./versioned-cache.md#versioned-loaders)。后端不支持版本时，创建带版本的类型立即失败。Redis 与内存后端都支持。
- **`putIfNewer(key, value, version[, ttl])`** 在一个 Redis 脚本中比较并写入，只需一次往返。只有 `version` 大于 Redis 中的版本时才写入，返回 `Written`。相等的版本是重复投递，更旧的版本是迟到；两者都返回 `Stale(current)`，Redis 中什么也不改：不写入、不改 TTL。相等的版本仍然发布失效消息：可能有一次加载写入了这个版本而没有发消息，同时另一个实例持有更旧的副本。更旧的版本不发布任何东西。版本按有符号 64 位整数比较。没有版本的内容排在任何版本之前：负缓存、加载 lease，或者 key 不存在。写入成功会把所有进行中的加载挡在栅栏外，并淘汰每个实例的 L2。
- **进行中的加载永远赢不了。** 读取方持有加载 lease 时 `putIfNewer` 先落地，它的写回会被拒绝，即使它从数据源读到的版本更高。写入方的下一个版本反正会经 `putIfNewer` 到来。先完成的加载保存 loader 给出的版本，之后的写入与它比较。写回成功且让版本前进时，这次写回就是一次写入：它广播失效，执行加载的实例丢弃自己的副本，而不是把它填进 L2，它的下一次读取从 Redis 读。
- **`invalidateIfNewer(key, version[, ttl])`**（`VersionedStore` 上是 `deleteIfNewer`）写入墓碑：读取把这个 key 看作不存在（不调用 loader），版本相同或更旧的 `putIfNewer` 返回 `Stale`。更新的版本可以让 key 重新出现。墓碑的寿命是策略的 `tombstoneTtl`（`VersionedCache` 默认取值的 `ttl` 加抖动，`VersionedStore` 默认 1 天），或 `ttl` 参数，不是 `absent-ttl`。墓碑过期之后，迟到的旧版本又能写入，所以墓碑的寿命要覆盖重复投递的时间窗口。
- **`getVersioned(key)`** 与 `get` 一样读穿透，同时返回值的版本。遇到墓碑返回 `null`，与 key 不存在相同；墓碑存活期间，版本相同或更低的 `putIfNewer` 仍会被拒绝。没有版本的值报告 `Versioned.NO_VERSION`。
- **顺序只在条目存活期间成立。** 值或墓碑过期之后，key 中没有版本，任何版本（包括更旧的）都能再次写入。按重复投递的时间范围设置 `ttl`（以及 `tombstoneTtl`）。把流重放到最新位置的写入方最终总会写到最新版本，所以过期之后的陈旧以那次重放为界。
- **没有丢掉版本的写入。** 带版本的类型没有 `put`、`putIfMatch` 与普通的 `invalidate`。去掉版本的途径只有 `purge(key)`（删除 key，之后任何版本都能写入；从不重试，失败时抛出）、过期，以及 CoCache 之外的工具。
- **`evict(key)` 保留版本。** `VersionedCache` 在每个实例上丢弃副本，并把 Redis 中带版本的值或墓碑换成一个保留版本的淘汰标记：下一次读取从数据源加载，迟到的旧版本仍被拒绝。数据源落后于 Redis 中的版本时，这次加载的结果照常返回（在 L2 中保留 `degraded-local-max-age`），但不写入 Redis，计入 `cocache.fill.rejections`，直到数据源追上。没有版本的值照常删除。标记存活 `tombstoneTtl`。失败时抛出，不在后台重试。端点的按 key `DELETE`（`CacheAdmin.invalidateEncoded`）在 `VersionedCache` 上是 `evict`，在 `VersionedStore` 上是 `purge`。
- **提前刷新照常工作**（`VersionedCache`）。带版本的值像其它值一样取得刷新令牌，声明没有写出 `refreshWindow` 时，`cocache.defaults.cache.refresh-window` 同样作用于它。刷新只写回不低于 Redis 中版本的值。数据源返回的版本比 Redis 中的更旧时（写入方推得比数据源快），Redis 拒绝它，实例保留更新的副本，退避后再试。更一般地，本地副本从不退回更旧的版本：刷新的令牌被别的读取抢走、或写回 Redis 失败，而数据源仍返回更旧的版本时，实例保留已有的副本（计入 `cocache.stale.discards`）。结果未知的写回按写入处理：实例丢掉自己的副本，下一次读取读 Redis。写入方的推送已经足够时，在声明中设置 `refreshWindow = off()`（或 `cocache.caches.[name].refresh-window: off`）。
- **失败：** Redis 故障时 `putIfNewer` 抛出 `CacheWriteException`。重试同一个版本是安全的：第一次已经生效时，重试返回 `Stale(version)` 并再次发布失效，以防第一次写入生效而单独的发布失败了。读不懂的内容的分类同[条件写入](./store.md#conditional-writes)。
- 异步视图与挂起视图有同样的方法。指标 `cocache.versioned.stale` 统计被拒绝的版本。

## 在事务中 {#transactions}

在 Spring 事务中，“提交之后”并不是你的代码运行的位置。`TransactionalInvalidator`（classpath 中有 `spring-tx` 时由 starter 注册）把失效挪到那里。它接受 `Cache`：其它类型没有 `invalidate`。

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
- 回滚时同样失效已登记的 key。loader 在你的线程上执行并参与事务，写入之后的读取可能已经把未提交的行放进 Redis，所有实例在 `ttl` 内都会读到它。多余的失效无害。事务内的加载即使没有登记，回滚时同样失效；已登记的 key 在事务的其余部分私有加载。见 [Spring Cache](./spring-cache.md#transactional-loads)。
- 回滚到 savepoint 时（`NESTED` 事务），立即失效到目前为止登记的全部 key，事务结束时再失效一次。Spring 不把这次回滚报告给 `afterCompletion`，否则嵌套段内加载的未提交行会在外层事务结束之前对所有实例（包括外层事务自己）可见。
- `REQUIRES_NEW` 的内层事务在它自己结束时失效。
- 迟到的登记不会丢失。在其它同步的 `afterCommit` 中登记的失效立即执行；如果它是这个事务的第一次登记，则在 `afterCompletion` 中执行。在 `afterCompletion` 中（例如 `@TransactionalEventListener(AFTER_COMMIT)`）登记时立即失效。
- 一个缓存失败不影响其余缓存：全部失效之后，抛出第一个异常，其余作为它的 suppressed（只在 [`invalidate-failure: throw`](#redis-down) 时）。回滚之后的失败由 Spring 记录日志，不抛出。
- 没有活动事务时立即失效。
- 它只看得到线程绑定的事务（`PlatformTransactionManager`，如 JDBC、JPA）。在响应式事务中（`ReactiveTransactionManager`，如 R2DBC，包括 `@Transactional` 挂起函数），它立即失效，也就是在提交之前。这种情况请在事务结束之后、事务边界之外失效。

CoCache 缓存上的 `@CacheEvict` 与 `@CachePut` 同样推迟到提交之后，但回滚时不执行；`@CacheEvict(beforeInvocation = true)` 立即失效，事务结束时再失效一次。见 [Spring Cache](./spring-cache.md#transactions)。

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
- 缓存不用 `cocache:` 前缀时，在构造函数中传入 `keyPrefix`。前缀也决定频道，传错时帧到不了该缓存的任何实例。
- namespace 不符合 `[A-Za-z0-9._-]+` 时抛出 `IllegalArgumentException`，与缓存相同。
- 调用是幂等的：抛出异常时（例如删除成功而广播失败）请重试。
- 它从 Redis 删除 key 并广播失效（默认通道模式下在同一个脚本中完成）。所有实例，包括同一进程中的缓存，都会丢弃 L2 副本。
- 它不重试。Redis 故障时直接抛出，请自行重试。
- 编码后的 key 为空串时抛出 `IllegalArgumentException`；传入空集合什么也不做。`close()` 之后的任何调用都抛出 `IllegalStateException`。`close()` 只释放失效器自己创建的存储句柄，不关闭后端。
- **`invalidate` 删除 key。** 在 `Store`、`VersionedStore` 或 `SetStore` 的 namespace 上，Redis 保存的是唯一副本，删除就是永久删掉这份数据。Redis 中已是正确的值、只有其它实例的 L2 副本可能陈旧时，改用 `broadcast(namespace, encodedKeys)`：它只发布失效、不触碰 Redis，每个实例丢弃副本并重新读 Redis，对任何类型都安全。

在 JVM 之外，任何 Redis 客户端都能用两条命令做到同样的事：`DEL {keyPrefix}{namespace}:{encodedKey}`，然后向 `{keyPrefix}inv:{namespace}` `PUBLISH` 一个小的二进制帧（sharded 模式用 `SPUBLISH`）。帧格式见[故障排查](../operations/troubleshooting.md#external-invalidation)。省略 `PUBLISH` 会让其它实例的 L2 陈旧最多 `localMaxAge`。

## 选择寿命 {#lifetimes}

每个寿命约束一类陈旧。按它兜底的故障来选：

| 配置 | 默认值 | 约束 | 更短意味着 | 建议 |
|------|--------|------|------------|------|
| `ttl` | 1 小时 | CoCache 听不到的变更 | 更多重新加载 | 所有写入方都失效时可以很长，此时 `ttl: forever` 也安全。有写入方无法失效时调短。 |
| `ttl-jitter` | 1 分钟 | — | — | 打散过期时间，一起加载的 key 不会一起过期。实际幅度不超过 `ttl / 2`。 |
| `absent-ttl` | 1 分钟 | 查询缓存了“不存在”之后，新建的行多久不可见 | 不存在的 key 被加载得更频繁 | 保持较短，或者在新建行时失效该 key。 |
| `local-max-age` | 5 分钟 | 失效消息丢失时 L2 的陈旧 | 每个 key 每个周期多一次 Redis 读取 | 对命中路径没有任何开销。几分钟的偏差都不能接受的数据可以调低。 |
| `degraded-local-max-age` | 10 秒 | 无法写入 Redis 的加载 | Redis 故障期间加载更多 | 很少需要修改。 |
| `lease-wait` | 200 毫秒 | — | — | 其它实例等待进行中加载的时长。loader 更慢时调大，以保持全集群只加载一次。 |
| `lease-ttl` | 10 秒 | — | — | 崩溃的 loader 留下的 lease 会阻塞 key 多久。应大于 loader 的 p99。 |

```yaml
cocache:
  defaults:
    cache:
      local-max-age: 2m      # 没有自己声明的派生缓存都用它
  caches:
    "[UserCache]":
      ttl: forever           # 所有写入方都会失效
    "[PriceCache]":
      ttl: 30s               # 由无法失效的系统写入
      absent-ttl: 5s
```

每个时长都必须带单位：`lease-wait: 500ms`、`ttl: 30s`。不带单位的数字让启动失败（见[时长](./configuration.md#durations)）。事故中，`cocache.overrides.cache.local-max-age` 或 `cocache.overrides.cache.ttl` 一次缩短所有派生缓存的界限，且不削弱任何声明（见[各层能设置什么](./configuration.md#field-layers)）。

表中是派生类型的默认值。`Store`、`VersionedStore` 与 `SetStore` 以自己的[默认策略](./caches.md#store-defaults)为起点：`ttl` 永不过期、`local-max-age` 30 秒、`absent-ttl` 10 秒。

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

要关闭声明中开启的窗口，可以任选其一：在 `cocache.caches.[name]` 下写 `refresh-window: off`（见[配置项](./configuration.md#policy-properties)）、`@CoCache(refreshWindow = 0)`，或在[定制器](./caches.md#customizer)中设置 `refreshWindow = off()`。全局层让窗口对 `ttl` 或 `local-max-age` 而言过长时，这个缓存的窗口被关闭并告警（见[校验](./configuration.md#validation)）。

## 写入时 Redis 故障 {#redis-down}

失败的写入一律抛出 `CacheWriteException`，在后台重试的 `Cache.invalidate` 与被补偿的 `Cache.put` 除外（见下）。它的 `outcomeUnknown` 说明你知道什么：

- `false`：确定未执行。客户端在发出之前拒绝了命令（比如断线期间），或 Redis 因为 key 中是本版本读不懂的内容而拒绝写入。什么也没改。
- `true`：结果未知。命令可能已经执行（超时、应答途中断线），也可能稍后才执行。迟到执行仍然无害的只有有序的写入：重放的 `putIfNewer`、[条件写入](./store.md#conditional-writes)（注意它的 ABA 问题）。

结果未知的写入可能稍后才执行，并照常广播它的失效。实例平常跳过自己发出的消息，所以它在失败与迟到执行之间加载的旧副本会留下来。因此从失败起 `local-max-age` 之内，写入方实例对这些 key 上自己的消息与其它实例的消息一样处理，迟到的广播同样淘汰这份副本。`channel-mode: sharded`（或原子广播探测失败）时，迟到执行的写入不带广播，这份副本以 `local-max-age` 为界。

**`Cache.invalidate`** 先执行本地效果（丢弃本实例的 L2）。如果 Redis 删除失败：

- 默认（`invalidate-failure: retry`）调用**不抛出**：删除派生副本在任何时刻都安全，而失效通常在提交之后执行，抛出异常什么也回滚不了。删除交给有界的重试队列：最多 8 次，退避从 100 毫秒起翻倍到 10 秒，最多同时排队 10 000 个。外部来源的缓存也是如此。
- 重试期间，本实例上该 key 的未命中跳过 Redis（其中仍是旧值），直接从数据源加载。外部来源的缓存这期间读到 `null`，那是未填充的答案而不是负缓存，因此 `get(key, loader)` 仍会调用它的 loader。
- 重试耗尽或队列溢出时，CoCache 记录错误日志并计入 `cocache.invalidations.dropped`。其它实例此后可能从 Redis 重新加载旧值，直到它过期，所以上界是该值的剩余寿命。`ttl: forever` 时没有上界：Redis 恢复后请再失效一次。
- 设置 `invalidate-failure: throw` 时，调用改为抛出 `CacheWriteException`，由你决定如何处理。

派生缓存上的 **`Cache.put`** 与 **`putAll`** 同样按 `invalidate-failure` 处理：

- 默认的 `retry` 下，结果未知的写入**不抛出**。它的值现在或之后可能在 Redis 中，也可能不在，所以 CoCache 用与失败的 `invalidate` 相同的重试队列登记这些 key 的失效。这些 key 收敛到“从数据源重新加载”（数据源中是你写入的值）；重试期间，本实例上的未命中跳过 Redis。迟到的写入晚于补偿的删除执行时，它写入的值保留到自己的 TTL。
- 确定未执行的写入（`outcomeUnknown = false`，比如断线期间被拒绝）不会迟到执行，不登记补偿，照常抛出。
- 设置 `invalidate-failure: throw` 时，失败的写入一律抛出，由你决定如何处理。

**`Store.delete`** 与 **`deleteIfMatch`**，以及带版本的类型上的 `purge`，失败时抛出，从不重试：对唯一副本延迟执行的删除可能删掉失败之后写入的值。同样的理由，`Store` 与 `SetStore` 的写入从不补偿。

**`Store.put`** 与 **`putAll`**，以及 `SetStore` 的写入 `add`、`remove`、`put`、`clear`，总是抛出 `CacheWriteException`。结果未知时，重试 `Store.put` 是一次新的后写者胜的写入；重试 `add` 或 `remove` 是安全的。

重试队列的上限来自 `CacheRuntime` 上的 `RetryPolicy`。在 Spring Boot 中修改它，需要声明自己的 `CacheRuntime` bean（见[配置参考](./configuration.md#beyond-properties)）。在健康检查详情（`pendingInvalidations`）和[可观测性](../operations/observability.md)中观察这个队列。
