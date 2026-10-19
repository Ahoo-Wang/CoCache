# RFC 0001：L1 写回 lease（6.0）

- 状态：**已被 [RFC 0003](0003-target-architecture.md) 取代**。lease 并入 6.0 整体重构，成为唯一的写回保护原语；令牌改放在新线格式的固定位置（标量头部 / 集合的 `!t` 字段），不再使用本文 §4 的带内标记，也不需要 §5 的两步滚动升级。本文保留作为 lease 语义与已定参数（§9）的来源
- 原目标版本：6.0（改变 Redis 线格式）
- Issue：#608

## 1. 问题

[architecture.md §5](../architecture.md) 记录了一个“已知的固有窗口”：

1. 实例 A 的 L2、L1 均未命中，开始回源，读到旧数据。
2. 实例 B 更新数据源，`DEL` L1，广播失效。
3. A 在收到事件之前把旧值写入 L1。

A 收到事件后撤销自己的 L2，但 L1 中的旧值会被所有实例读到，直到 TTL 到期。这是目前 G1（陈旧度有上界）完全依赖有限 TTL 的唯一原因，也是默认 TTL 不能设为 `FOREVER` 的原因。

`InvalidationStamps` 只在进程内有效：B 的 `DEL` 不会推进 A 的戳，除非事件先于 A 的写回到达。要在跨实例时也保证 G2（失效不被吞没），写回保护必须下沉到 L1 本身。

## 2. 目标与非目标

目标：

- **G2 跨实例成立**：开始于某次 `evict` 之前的回源，永远不能在该 `evict` 之后把结果写入 L1。
- **命中路径不变**：L2 命中零额外开销，L1 命中仍是一次往返（G3）。
- **可滚动升级**：5.x 与 6.0 实例共享 Redis 期间不出现错误结果（允许多余的回源）。
- **附带收益（可选）**：跨实例回源互斥，用同一个 lease 消除多实例同时回源同一 key。

非目标：

- 线性一致读。
- 解决失效事件丢失：那是通道问题，由 `clientSideTtl` 与通道重置兜底（见 RFC 之外的 RESP3 预研 #607）。

## 3. 设计：lease

借鉴 memcache 的 lease（Nishtala et al., *Scaling Memcache at Facebook*, NSDI 2013）。

### 3.1 读（L1 未命中时取得 lease）

读脚本从 `{ttl, raw}` 扩展为三种结果：

```
local ttl = redis.call('TTL', KEYS[1])
if ttl == -2 then
  -- 未命中：原子地写入 lease 标记，返回令牌
  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2], 'NX')   -- ARGV[1] = lease 标记（含随机令牌）
  return {-2, ARGV[1]}
end
local raw = 读原始值
if raw 是 lease 标记 then return {-3, raw} end               -- 他人持有 lease
return {ttl, raw}                                             -- 命中（不变）
```

- 命中路径与现在相同：一次 EVALSHA，返回 `{ttl, raw}`。
- 未命中路径在同一次往返内取得 lease，没有额外 RTT。
- lease 的存活时间 `leaseTtl` 必须覆盖正常回源耗时（默认建议 10 s，可配置）。

### 3.2 写回（条件写）

回源写回改为 Lua 条件写：只有 L1 中仍是**本次读取取得的 lease 标记**时才写入值并设置 TTL；否则放弃写回（并照常返回加载结果）。

```
if redis.call('GET', KEYS[1]) == ARGV[1] then   -- 仍是自己的 lease
  SET 值 + TTL
  return 1
end
return 0
```

### 3.3 淘汰

`evict` 仍是 `DEL`。`DEL` 删除 lease 标记，因此任何开始于该 `DEL` 之前的写回都会因令牌不匹配而失败。这正是 §1 的窗口：步骤 3 的条件写失败，L1 保持为空，下一次读取取得新 lease 并读到新数据。

### 3.4 lease 被他人持有时（跨实例回源互斥）

读到 `{-3, lease}` 表示另一个实例正在回源：

- **默认**：短暂轮询 L1（例如每 20 ms，最多 `leaseWait`，默认 200 ms），期间值写入则直接使用；超时后自行回源但**不写回**（避免与持有者竞争），结果仍受进程内失效戳保护地填充 L2。
- 这把“各实例独立合并回源”（当前非目标）变为“全集群同一 key 至多一次回源”，L1 不再需要吸收重复写。

### 3.5 lease 超时

回源超过 `leaseTtl`：lease 过期，下一个读者取得新 lease 并回源。原持有者的条件写因令牌不匹配失败——安全，代价是一次多余的回源。

## 4. 数据结构与 Redis Cluster

lease 标记必须与值存放在**同一个 key** 中：

- 放在旁路 key（如 `{key}:lease`）需要 `DEL` 与条件写同时操作两个 key，Redis Cluster 要求二者在同一 slot。只有给用户 key 加 hash tag 才能保证，而这会改变存储 key 本身。
- 同一个 key 意味着 lease 标记必须能被每种 codec 的读脚本识别、且不会与合法值混淆：

| Codec | lease 标记形态 | 说明 |
|-------|----------------|------|
| String / JSON | 字符串 `_lease_:{token}` | 与负缓存哨兵同理，按前缀识别；业务值不得以该前缀开头（与 `missing-guard-sentinel` 同类约束） |
| Hash | 单字段 `{_lease_: token}` | 与 Hash 负缓存哨兵（单字段 `{_nil_: …}`）同构 |
| Set | 单成员 `_lease_:{token}` | 与 Set 负缓存哨兵同构；`addElements` 遇到 lease 标记时视为“不存在”并替换它 |

集合元素增删（§5.1）：`addElements` 与 lease 并发时，元素写入会替换 lease 标记；随后持有者的条件写因令牌不匹配失败——元素写入优先，与现有 `fillCache` 语义一致。

## 5. 滚动升级（5.x ↔ 6.0 共享 Redis）

| 场景 | 结果 |
|------|------|
| 5.x 读到 6.0 写入的 lease 标记 | String/JSON：标记不是合法载荷，按“载荷损坏”处理为未命中并回源；5.x 无条件 `SET` 覆盖标记。Hash/Set：5.x 视为一个单字段/单成员的普通值——**错误结果**，见下 |
| 6.0 条件写遇到 5.x 写入的值 | 令牌不匹配，放弃写回；使用 5.x 的值。安全 |
| 5.x `DEL` | 同样删除 lease。安全 |

Hash/Set codec 下 5.x 会把 lease 标记当作业务值返回。因此：

- String/JSON 缓存可以滚动升级。
- Hash/Set 缓存需要先全集群升级到 5.x 的最后一个次版本，让它把 `_lease_` 标记识别为未命中（只读兼容，不取 lease），再升级到 6.0。即：**5.x 最后一个次版本加入“读兼容”**，6.0 才开始写入 lease。

## 6. 代价

| 路径 | 现在 | lease |
|------|------|-------|
| L2 命中 | 0 RTT | 不变 |
| L1 命中 | 1 RTT（EVALSHA） | 不变 |
| 未命中 + 回源 | 1 RTT 读 + 回源 + 1 RTT 写 | 不变（lease 在读脚本内取得，写回改为 Lua） |
| 他人持有 lease | 各实例各自回源 | 轮询最多 `leaseWait`，通常节省一次回源 |
| `evict` | DEL | 不变 |
| Redis 内存 | — | 每个在途回源一个短 TTL 标记 |

需要 JMH 验证 L1 命中路径（读脚本多一次 lease 判断）。

## 7. 备选方案

| 方案 | 不采用的原因 |
|------|--------------|
| 延迟双删（evict 后延迟再删一次） | 只缩小窗口，不能保证；延迟取多少没有正确答案 |
| 写入时携带数据源版本号，L1 比较版本 | 要求每个数据源提供单调版本（行版本/时间戳），侵入 `CacheSource` 契约；对无版本的数据源无效 |
| 墓碑（evict 写短 TTL 墓碑，写回用 NX） | 墓碑存续期间所有回源都无法写回，命中率下降；墓碑 TTL 难以选择。lease 只阻止“开始于淘汰之前”的写回 |
| RESP3 client tracking | 解决的是失效投递，不是写回竞态；与本 RFC 正交（#607） |

## 8. 验收

- `MultipleInstanceSyncSpec` 用 latch 复现 §1 的三步交错，6.0 下 L1 最终为空或为新值。
- 所有 codec 通过 lease 相关的 `CodecExecutorSpec` 用例；集合元素增删与 lease 交错的用例。
- architecture.md 删除“已知的固有窗口”，G1 的实现手段不再只依赖 TTL。
- 升级指南（changelog 迁移表）写明 §5 的两步升级。

## 9. 已定事项

维护者于 2026-10-12 确认：

1. `leaseTtl` 默认 10 s，`leaseWait` 默认 200 ms，均为全局属性（`cocache.redis.lease-ttl`、`cocache.redis.lease-wait`）；需要时再考虑按缓存覆盖。
2. 他人持有 lease 时：轮询 L1 至多 `leaseWait`，超时后自行回源但不写回 L1（§3.4）。
3. 6.0 不调整默认 `ttl`（3600 s）。lease 上线后用指标（`cocache.stale.discards` 与 lease 写回失败数）评估，再决定是否调长。
4. `strictFailure = false` 下条件写失败（Redis 故障）按放弃写回处理，不影响返回值。
