# 调查：Lincheck 模型检验真实引擎的可行性（Q18）

> 状态：实验完成（2026-11-02），操作集在 M8 补全（2026-11-04，见文末）；结论：**可行，有条件**。来源：[RFC 0005](../rfc/0005-v8-target-architecture.md) §4.10“Lincheck”与 Q18（维护者决定：先做可行性实验，可行再正式加入），里程碑 M2f。

## 结论摘要

- **能检验真实代码。** 对真实的“SingleFlight + Caffeine L2 + 失效戳 + 内存存储”（经同步 API，`CoherentEngineLincheckTest`）做模型检验能跑通、可重放，没有误报。不需要按 RFC 的退路改用 `ConcurrentHashMap` 替身。
- **有三个前提**，缺一个就会报“执行挂起”并随后报“Non-determinism found”：
  1. **不开 JaCoCo。** 覆盖率代理在每个方法里插入探针数组（`$jacocoData`），Lincheck 把每次探针写入当作共享内存访问：既增加切换点，懒初始化又让两次执行不一致。
  2. **关闭 kotlinx 调试模式**（`kotlinx.coroutines.debug=off`）。调试模式下每个协程从全局 `AtomicLong` 取编号并写进线程名，编号在首次执行与重放之间不同。测试以 `-ea` 运行时调试模式默认打开。
  3. **缩小被检验的范围**（`ManagedStrategyGuarantee`）：Caffeine 的每个调用当作原子步骤（`treatAsAtomic`），`LongAdder` / `Striped64`（指标）与日志忽略（`ignore`）。不加时 Caffeine 的内部缓冲与 `LongAdder` 的分段竞争让单个操作产生数千个事件，执行被判定为挂起。Caffeine 当作原子之后，`compute` 与 `clear` 的语义由定向用例覆盖（RFC 的 C4，M2a 已有）。
- **慢。** `runBlocking` 的事件循环、协程上下文与 SingleFlight 都被插桩，2 线程 × 2 操作的场景每次调用仍有上千个事件。实测（Apple Silicon，14 核）：

  | 配置 | 耗时 |
  |------|------|
  | 默认选项，平凡的计数器（冒烟） | 85 s |
  | 引擎，随机场景 20 × 1000 次调用 | 481 s |
  | 引擎，随机场景 10 × 300 次调用（`modelCheck`） | 75 s |
  | 引擎，两个定向场景各 2000 次调用（`targetedInterleavings`） | 48 s |

- **能发现真实的缺陷，但随机场景不够，需要定向场景。** 把与 M2e 相同的单节点缺陷注入引擎：

  | 注入的缺陷 | 随机场景（10 × 300） | 定向场景 | M2e 仿真 |
  |------|------|------|------|
  | 忽略别的节点的失效事件 | 8 s 发现 | — | 种子 0 |
  | 去掉 L2 填充的戳校验 | 30 s 发现 | — | 种子 4 |
  | 去掉读己之写的复核 | 未发现 | 6 s 发现 | 种子 0 |
  | 写入方不做第二次淘汰 | 未发现 | 24 s 发现 | 种子 68 |

  后两个缺陷都要一个线程停在加载中途（注册之后、发布之前，或读到旧的 L1 之后、填 L2 之前），另一个线程完成写入再读。随机生成的场景在这个预算内很少同时碰上“这组操作”与“这种交错”。为它们写的定向场景（固定操作组合，模型检验穷举交错）在几秒到几十秒内就能发现。
- **与 M2e 仿真互补，不是替代。** 仿真在跨节点、事件乱序与丢失、崩溃、命令结局上更强，而且快（300 个种子约 1.5 s）。Lincheck 的长处是对固定的操作组合穷举线程交错（包括引擎内部的每个共享内存访问，而不只是 L1 命令与事件的边界），给出最小化的反例与完整的执行轨迹。

## 建议（已在 M2f 的 PR 中实施，合并与否待维护者决定）

1. **正式加入** `org.jetbrains.lincheck:lincheck:3.7`，只作 `cocache-core` 的 `testImplementation`。
2. **单独的 Gradle 任务** `./gradlew :cocache-core:lincheck`：只运行 `*LincheckTest`，关闭 JaCoCo 与 kotlinx 调试模式；`test`（因此 `check`）排除这些测试，不增加常规构建的时间（约 2 分钟）。
3. 保留随机场景与定向场景。之后每修一个单节点竞态，就为它加一个定向场景（与 `EngineSpec` 的手写交错一样作为回归用例）。
4. 是否在 CI 中单独加一个运行 `lincheck` 任务的作业（并行，不阻塞其它作业），由维护者决定；本 PR 不改工作流。

## 方法

- **被检验的对象**：直接构造 `CoherentEngine`（模块内部），每次调用新建引擎与内存存储，共享一个 `EngineContext`。时钟固定、计时源（`Ticker`，M2e 引入）固定为 0，让每次执行可以重放。数据源是 `AtomicIntegerArray` 中的版本号，loader 返回 `"$key@$version"`。
- **操作**：`get(key)`（返回版本）、`write(key)`（数据源提交新版本，再 `invalidate`）、`remoteWrite(key)`（提交，删除 L1 并广播，随即把失效事件投递给本节点）。key 取 0 或 1。
- **顺序规格**：带版本的数据源，`get` 返回当前版本，两种写入都把版本加一。单节点上引擎对它可线性化：读到的版本在读取期间的某个时刻是当前版本；`invalidate` 返回之后的读取不会读到更旧的版本。
- **注入缺陷**：逐个修改引擎源码（与 M2e 的变异实验相同的位置），运行之后还原；每次只改一处。
- **未覆盖**：多节点交互由 M2e 仿真与 TLA+ 模型覆盖。RFC §4.10 的其余操作在 M8 加入，见下一节。

## M8：完整的操作集（2026-11-04）

来源：RFC 0005 §4.10 的操作集与两种顺序规格，以及 M2 留下的事项（`docs/todo.md` §0）。

**五个类，每个类一种缓存、一个顺序规格**（都在 `cocache-core` 的 `engine` 包，`LincheckSupport` 是共用的设置）：

| 类 | 缓存 | 操作 | 规格 |
|----|------|------|------|
| `CoherentEngineLincheckTest`（M2f，未改） | 有数据源的 `Cache` | `get`、`write`、`remoteWrite` | 带版本的数据源 + 读己之写 |
| `EngineOperationsLincheckTest` | 有数据源的 `Cache` | 上面三个，加 `get(key, loader)`（P3）、`getAll`（与单 key 的 flight 合并，P10）、`put`、`clearLocal`、`onReset`、写事务中的读取与私有回源（RFC §4.9）、被中断的同步调用方、被取消的挂起调用方 | 同上 |
| `ExternalSourceLincheckTest` | 外部来源的 `Cache` | `get`、`get(key, loader)`、`write` | 不变式：不陈旧，带 loader 的读取总有值 |
| `StoreLincheckTest` | `Store` | `get`、`put`、`delete` | 按 key 的寄存器；读取从不申请 lease |
| `VersionedCacheLincheckTest` | `VersionedCache` | `get`、`putIfNewer`、`invalidateIfNewer`、`evict`、`clearLocal` | 带版本的写入方 + 读快照的 loader |

**建模上的取舍**：

1. `InvalidationStamps.invalidateAll`（`clearLocal`、`onReset`）逐个递增 4096 个分段，每次递增都是一次共享内存访问，执行被判定为挂起。它当作一个原子步骤：读取方只看自己 key 所在的分段，能观察到的结局与“一次递增全部分段”相同。
2. 私有回源的结果不检验，只检验它对其它读取的影响（不写任何共享层、不留 lease）。私有回源直接读数据源：写入方提交之后、失效之前，它读到新版本，随后普通的读取读到缓存中的旧版本，与任何直接读数据库的读取一样，不可线性化为“带版本的数据源”。真实的私有回源只读本事务正在写的 key。
3. 外部来源的缓存检验不变式，不检验可线性化。loader 在写入方提交与失效之间读到新版本时，失效栅栏让它不写入任何一层（它无法知道自己读在提交之前还是之后），之后不带 loader 的读取得到“没有”。“没有”何时出现由交错决定，写不成确定的顺序规格，而它总是安全的答案（调用方随即自己回源）。每次读取改为在写入方的锁内取一个下限（之前完成的写入的版本），断言读到的版本不低于它、带 loader 的读取总有值。
4. `put` 与其它写入方按行加锁：两个写入方各自提交、再无序地写缓存时，后写者胜的写入在任何缓存中都会留下较旧的值，这是 `put` 的约定，不是这里要检验的性质。带版本的写入方不加锁：版本有序的写入在 L1 中原子地比较。
5. 调用方取消。同步变体被中断：线程带着中断标志调用，命中照常返回，回源之前以 `InterruptedException` 结束，作为 leader 时交还 lease、跟随者自行回源。挂起变体在第一次挂起时被取消：内存后端不挂起，所以只有合并到别的调用方的加载的跟随者能被取消到。事后校验 L1 上没有留下 lease。
6. 写事务（§4.9）只在 `EngineOperationsLincheckTest` 中接入：它的引擎上下文在每次同步未命中时读一次线程局部的事务，增加事件数。回滚时失效填充的语义在 Spring 中，不在这里。

**没有发现缺陷。** 过程中的三次失败都是规格的问题，分别对应上面的 1、2、3。

**注入缺陷**（每次只改一处，运行对应的类，之后还原）：

| 注入的缺陷 | 检验它的场景 | 结果 |
|------|------|------|
| 批量读取接受开始于本次读取已观察到的失效之前的共享加载（逐 key 的读己之写复核） | `EngineOperationsLincheckTest.readInterleavings` | 33 s 发现（结果不可线性化） |
| 回源之前被中断或回源失败时不交还 lease | `EngineOperationsLincheckTest.callerCancellation` | 49 s 发现（之后的读取一直等 lease，执行挂起） |
| `Store` 的读取申请 lease | `StoreLincheckTest.targetedInterleavings` | 22 s 发现（校验失败） |
| 带 loader 的调用方接受“未填充”的共享结果（P3） | `ExternalSourceLincheckTest.targetedInterleavings` | 23 s 发现（带 loader 的读取没有值） |

**耗时**（Apple Silicon，18 核；每个类一个 JVM，至多 4 个并行，空闲的 JVM 接着取下一个类）：

| 类 | 耗时 |
|----|------|
| `CoherentEngineLincheckTest` | 239 s（随机 105 s，定向 82 s，取消中的数据源 52 s） |
| `EngineOperationsLincheckTest` | 169 s（随机 3 × 100 次调用 32 s，读取与写入的定向场景 97 s，调用方取消 40 s） |
| `ExternalSourceLincheckTest` | 78 s |
| `StoreLincheckTest` | 77 s |
| `VersionedCacheLincheckTest` | 86 s |
| `./gradlew :cocache-core:lincheck` 墙钟（含 Gradle） | 263 s |

墙钟时间取决于最慢的类（M2f 的 `CoherentEngineLincheckTest`）。CI 的 runner 慢得多：M2f 一个类时 CI 作业就要约 10 分钟，补全之后按 4 个 JVM 并行，预计相近。
