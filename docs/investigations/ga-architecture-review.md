# 8.0 GA 前的架构审查

2026-11-07，`main` @ c679f676（8.0.0-alpha.6 之后，含 #897、#904–#907）。目的：GA 之前按内聚、耦合、性能、可用性、扩展性与易用性审查全部模块，结合两个下游（CoSec #1203、Wow #5436）在 alpha.6 上的试用体验，定出 GA 之前要做的重构与要维护者决定的接口。

来源：四份只读审查（核心引擎包；核心运行时与 `cocache-api`；`cocache-redis`；`cocache-spring` 与 starter），每条结论都对照代码核实；CoSec 与 Wow 对“胶水代码、别扭的 API、缺少的扩展点、性能与可用性、多余或易配错的概念、测试支持”六个问题的回答。

所有改动都不得削弱 [architecture.md](../architecture.md) §15 的不变量；行号以审查时的 `main` 为准。

## 总评

- 没有发现削弱不变量的代码。协议核心的正确性由 TCK、确定性仿真、Lincheck 与 TLC 守护，这次审查不改协议。
- 最突出的内聚问题是 `CoherentEngine.kt`（2731 行，第二大的主源文件的三倍多）：一个类里有结果换算、命中快路径、填充事务、单 key 读取与填充、内容规则、数据源调用、批量读取、提前刷新与写入九组职责。
- 两个下游都能不改代码升级，没有阻塞 rc 的问题；它们自己写的胶水代码指出了几个缺少的扩展点，其中“库声明可能创建的缓存名”是两边共同的。
- 可用性上有一个实际缺口：Cluster 上的经典 Pub/Sub 连接半开时从不强制重连。

## 1. 缺陷（先修，每个都要复现用例）

| # | 模块 | 问题 | 后果 |
|---|------|------|------|
| D1（#909） | core | `CacheRuntime.register` 只在 `Exception` 时回滚（`CacheRuntime.kt:249`） | SPI 抛出 `Error`（`TODO()`、`ExceptionInInitializerError`）时缓存名与 keyspace 一直被占用、订阅不关闭，同名重试报“已存在” |
| D2 | core/engine | `ttlCeiling` 按代码路径而不是按类型生效：填充对任何引擎钳制（`CoherentEngine.kt:787`），`putTtl` 对 `Store` 也钳制，按版本写入与 `Store.put(k, v, ttl)` 不钳制 | 与文档（只对 `Cache`，architecture.md §10.2、`CachePolicy.kt:35-37`）不一致；只能经核心 API 的 `CachePolicy(ttlCeiling = …)` 触发 |
| D3 | core/engine | `refreshStep` 用 `runCatching` 包住校验与解码（`CoherentEngine.kt:1898-1901`），吞掉 `Error` 当作重载 | 与命中分类只捕获 `Exception`、#886 的做法不一致（此时不持有令牌，不泄漏） |
| D4 | core/engine | 没有数据源、却声明了 `refreshWindow` 的缓存，在窗口内每次 L2 命中都新建一个字符串（`storageKeyOfChecked` 在 `source == null` 检查之前，`CoherentEngine.kt:1790-1792`） | 命中路径多一次分配；调换顺序即可，行为不变 |
| D5 | core/engine | `SetEngine` 的读失败处理缺少 `rethrowIfFatal` 中运行时已关闭的分支（`SetEngine.kt:290-298`、`308-318`） | 关闭之后异步的 `SetStore` 读取以 `CacheReadException` 失败，标量读取则原样抛出 |
| D6（记录，不修） | core | `CacheLayers` 以 `policy = draft.policy.build()` 重建 spec，生效 spec 的 `declaredFields` 被标为全部字段（`CacheLayers.kt:35-60`） | 只有库自己的 `verify` 检查读得到生效 spec 的 `declaredFields`，引擎与报告都不用它；starter 的 `PolicyLayering` 同样重建，两边一致。生效声明的值都已确定，视为写出全部字段也说得通 |
| D7 | starter | `spring.main.lazy-initialization` 下 `cocacheUnlayeredConfigurationCheck` 从不创建（`CoCacheAutoConfiguration.kt:191-196`） | 指向未分层缓存的配置不再告警（S20） |
| D8 | starter | 懒加载下，只给 `@Cacheable` 用的外部来源 `Cache` 没有注入点、从不创建；`CoCacheSpringCacheManager.getCache` 返回 `null`，而 `SpringCacheSyncCheck` 把它算作已声明 | 首次调用 “Cannot find cache”（先写复现测试确认） |
| D9 | starter | AOT 判断是否用默认编解码按 `beanNamesForTypeIncludingAncestors`（`CoCacheAotProcessor.kt:116`），运行时按 `BeanCandidates` | 子容器的别名遮蔽父容器的 Codec 时（#887），原生镜像缺 Jackson 绑定提示 |

## 2. 不改公开 API 的重构（可以直接做）

按模块分成三个互不重叠的切片组（每组只改自己的目录；changelog 与 todo 由协调的会话统一写，避免冲突）。

### 2.1 核心引擎包（`core/.../engine/`）

拆分 `CoherentEngine`。每个协作者都是引擎持有的 `final internal` 类，命中路径上不多一次虚调用（architecture.md:115：子类只适配 API，命中路径就是引擎对象本身）。

1. **`SourceCalls`**（1072-1290 及相关常量、`AsyncSource`/`CoreSource`，约 250 行）：回源许可、超时、观测与取消的转换；没有失效戳状态。守护：`LoadObserverTest`、`AsyncLoadObserverTest`、`CloseFromScopeThreadTest`、`EngineSpec`。
2. **`ScalarContent`**：命中分类、摘要校验、类型不匹配、`decode`/`encode`/`envelopeOf`/`writerId`/`fillOf`/`describe`；把按角色派生的标志（140-150）集中到一处。守护：`BackendProtocolSpec` 的摘要用例、`corruptPayloadIsReclaimedAndReloaded`。
3. **类型专属的写入移出基类**：`ConditionalWrites`（2006-2180，只给 `StoreEngine`）、`VersionedWrites`（2182-2261 与 `evictKeepingVersion`，只给版本引擎）。之后 `VersionedCacheEngine` 够不到 `putIfMatch`，I-A 由结构保证。守护：`replayOfAnApplied*WriteBroadcasts`、`VersionedSpec`。
4. **`PendingInvalidations`**（180、2297-2362）；`retryInvalidate` 改用 `AbstractEngine` 的 `atomicBroadcast`/`publish`，不再重读 `context.backend`。
5. **统一填充收尾**：单 key（829-843）与批量（1644-1664、1704-1734）重复的 `VERSION_REJECTED → unversionedAbsentRefused → completeLoad → unfilterOwnFrames → afterAdvancedFill` 收成一个 `settleFills`（P12 要求先解除过滤、再递增）。
6. **`TtlRules`**：`ttlOf`、`putTtl`、`versionedTtl` 三份近似的规则合一；与 D2 的修复一起做。
7. **“未知格式”的报告**：五份“计数、告警、包装”（585-589、2077-2082、2132-2137，`SetEngine.kt` 360-364、487-495）收成 `AbstractEngine.unknownFormat`。
8. **卫生**：错位的 KDoc（114-117 写的是 `permits`）、未使用的 `UNRECOGNIZED_FORMAT`、别名 `Loaded.answer()`、`conditionalDelete` 中脆弱的 `as?`、`fillOf` 重复 `versionOf`、三份合并循环。
9. **命中路径上的两项（要 JMH）**：六份“到期、刷新、计数”的命中判定收成一个 `private inline fun freshHit`（字节码不变）；结果换算上移到 `AbstractEngine`（去掉 `SetEngine` 的近似重复、死参数 `withLoader`，以及版本缓存 `lookup` 的二次分配）。

留在 `CoherentEngine` 的：命中快路径、单 key 与批量的读取核心（共享约 25 个原语，拆开只会搬走耦合）、刷新协议、普通的 `put`/`putAll`/`invalidate`/`delete` 与三种变体的入口。预计降到约 1500 行。

### 2.2 Redis 后端（`redis/`）

1. **Cluster 上经典 Pub/Sub 的强制重连**（可用性，先做）：`disconnect()` 只拆到 `DefaultEndpoint`（`RedisInvalidationChannel.kt:218-222`、`357-369`），Cluster 的外层写入器是 `ClusterDistributionChannelWriter`，于是每次心跳只告警、不重连；半开时每 5 s 清空一次 L2，没有 epoll/io_uring 时要等内核重传超时（Linux 约 15 分钟）。改用公开的 `ClusterDistributionChannelWriter.disconnectDefaultEndpoint()`；`RedisRobustnessTest.heartbeatFailureForcesReconnect` 扩到 Cluster，改 §9.1。
2. **Sharded 模式的心跳并发 ping**（`ShardedInvalidationChannel.kt:115-129`）：K 个坏节点不再让检测延迟 K × `commandTimeout`。
3. **线格式的解码移入 `RedisScripts`**（`RedisL1Store` companion 547-786，纯移动）：AGENTS.md 规定线格式在 `RedisScripts`。
4. **`TokenReleaser`**（`RedisL1Store.kt:377-439`）：时钟可注入、退避测试确定；放弃令牌的告警聚合并计数，命令超时风暴时不刷屏。
5. **Lua 写入少调用**：标量写入 `DEL + SET + PEXPIRE` 改为 `SET k v [PX ms]`（字节与到期时间相同，I-8 照常）；集合按块用多字段 `HSET`/`HDEL`。脚本 SHA 变化，线格式不变。
6. **批量命令共享一个截止时间**（`RedisConnections.kt:207-230`、`258-276`）：现在每条命令一个时间轮任务与一个 future；要 `getAll` 的 JMH。
7. **卫生**：约 10 处重复的 `awaitCommand(evalAsync(…))`；`readTimeout(null)` 的含义；未使用的 `TLV_MAX_LENGTH`；与 `notExecuted` 不符的注释（487）；单 key 写入同步派发失败原样抛出、批量转为 `L1RejectedException` 的不一致；`invalidateBatchSize` 也限制 `putAll`（改文档）；每次发布重建频道字节；两份重复的客户端选项（`RedisBackend.kt:358-390`）。

### 2.3 Spring 与 starter（`spring/`、`spring-boot-starter/`）

1. **“已声明缓存”的目录**：五处各自扫描 bean 定义（`CacheBeanDefinitions`、`SpringCacheSyncCheck`、`StoreEvictionCheck`、`JoinCacheFactory`、`CoCacheAotProcessor`），“是不是权威类型”写了六遍，启动时同一份扫描至少跑三次。收成 internal 的 `DeclaredCaches.of(level)` 与 `ApiTypes.authoritative`；组合视图的逐级遮蔽不动（I-16）。
2. **删掉冗余的 `cocacheMeterBindingPostProcessor`**（`CoCacheMetricsAutoConfiguration.kt:48-68`）：`CoCacheMeterBinder` 自 #770 起经创建监听绑定任何时候创建的缓存。
3. **配置只解析一次**：`PolicyLayering` 中 section 与路径的选择写了两遍、`cacheProperties(name)` 每个缓存线性扫描三次、各层条目每创建一个缓存解析一遍、`command-timeout` 解析三次。
4. **卫生**：两份 SpEL 模板解析（异常类型还不同）、两份 `TRANSACTION_SYNCHRONIZATION`、两份 `SIMPLE_ERROR_HANDLER`。
5. **可选**：Spring Cache 的接线独立成一个顶层 auto-configuration（多出一个用户可见的类名，影响 `exclude`）。

### 2.4 下游指出、不改公开 API 的易用性（#910）

- 版本写入遇到旧格式时，`CacheWriteException` 的顶层消息只写“not executed”，原因只在 cause 里（Wow）：把原因的摘要并进顶层消息。
- 未知键是带点的缓存名漏了方括号（`cocache.caches.wow.shop.order.ttl`）时，提示改写成 `cocache.caches.[wow.shop.order].ttl`（Wow）。
- `HookedBackend` 增加只让读失败的 `failReads`，并在应用测试指南中介绍它（Wow 自己写了一个故障注入后端）；钩子参数是存储 key，补 KDoc。
- 文档：协程代码该用哪个视图、两者都满足“本地命中不挂起”；按调用给的 loader 是否随调用方取消（CoSec）。
- KDoc：权威类型的 `policy =` 整体赋值从派生默认值起步（见 3.1）；库取后端用 `factory.runtime.backend`，不要注入 bean。

## 3. 需要维护者决定

GA 之后兼容性承诺开始生效：**改变已有签名或语义的**必须在 GA 之前决定；只增不改的（默认方法、新类型、新重载）也可以在 8.x 中加。

### 3.1 GA 之前必须决定（改变已有接口或语义）

| # | 提议 | 依据 | 范围 |
|---|------|------|------|
| P1 | 权威类型整体赋值策略的陷阱：`storeSpec { policy = CachePolicy(localMaxAge = 10s) }` 让唯一副本 1 小时后过期（构造器默认值是派生缓存的）。加 `CachePolicy.builder(base)`，废弃无参 `builder()` 与构造器的默认参数（或在权威 spec 上拒绝/告警） | 核心审查；`DefaultPolicies.kt:21` 自己称之为最严重的误分类 | core 公开 API |
| P2 | `KeyFilter.notExist` 改收编码 key（现在收存储 key，运维改 `key-prefix` 之后按声明前缀建的 Bloom 过滤器把所有 key 判为不存在）；`ExternalInvalidator`、`CacheAdmin` 收到以存储前缀开头的编码 key 时拒绝 | 核心审查，与 #905 同类 | core 公开 API 语义 |
| P3 | SPI 的 `SetL1Store` 以 `Int?` 表示结果（`null` 为成功，Java 实现者以 `0` 完成会变成“格式 0”）：改为 sealed 的 `L1SetWrite` | 核心审查 | `cocache-api` SPI |
| P4 | `VersionedCache.invalidateIfNewer`（写墓碑）与 `VersionedStore.deleteIfNewer` 命名不对称 | Wow | `cocache-api` |

### 3.2 扩展点（只增不改，下游需要）

| # | 提议 | 依据 |
|---|------|------|
| X1 | **库声明可能创建的缓存名**：`fun interface CacheNameContributor { fun cacheNames(): Collection<String> }`，名称校验在所有单例创建之后经 `ObjectProvider` 取（懒 bean 也取得到）；只放宽被贡献的名称 | CoSec 与 Wow 共同的痛点；推翻“用 warn / `@Lazy(false)`、不改”的旧决定。rc.1 在 starter 中发布；Wow 试用后指出库已不依赖 starter，GA 前移到 `cocache-spring`（`me.ahoo.cocache.spring`） |
| X2 | **驱逐检查可复用**：启动之后创建的权威缓存也检查（创建监听，只告警）；`cocache-redis` 公开 `StoreEvictionRisk.check(runtime)`（读 `runtime.backend`，签名本身避开 #874）；可选的周期重读，健康检查只读快照、默认不 DOWN | CoSec 自写约 180 行；`HookedBackend` 包装让它看不穿后端 |
| X3 | 权威类型明确的远端读取（只返回 Present / Absent），替代被当作“绕过本地”的 `getIfChanged(key, null)`；`lookup` 在权威类型上不由本地负缓存回答 `Absent` | CoSec |
| X4 | “暂时不可用”的公共父类型或判定方法（`SourceLoadTimeoutException`、`SourceLoadRejectedException`、`SourceLoadCancelledException`、`CacheReadException`） | Wow 逐个捕获 |
| X5 | `putIfNewer` / `invalidateIfNewer` 接受可空的 `ttl`（`null` 为策略寿命） | Wow 为两种情况各写一组分支 |
| X6 | 运行时与后端的定制器（`CacheRuntimeBuilderCustomizer`、`RedisBackendOptionsCustomizer`）：现在替换 `cocacheRuntime` 会丢掉时钟、观测器、执行器与关闭超时的接线 | starter 审查 |
| X7 | 类型化的哨兵 setter（`ttl(Ttl)` 等）与 Java 门面（`PolicyFields`、`Ttl.FOREVER`）：`ttl = off()` 现在静默等于永不过期 | 核心审查 |
| X8 | 测试：运行时没有显式时钟时沿用 `InMemoryBackend` 的显式时钟（现在要接两次）；`CacheLayers` 的策略快捷方式 | 核心审查、Wow |
| X9 | 视图对称：异步视图加 `suspending()`、挂起视图加 `sync()` | 核心审查 |
| X10 | `lock` + `constrain` 总是成对出现：`pin(field, value)` 一步到位 | CoSec |

### 3.3 设计问题（GA 之后也可以）

- 命令连接半开时从不替换（心跳只 ping Pub/Sub 连接）：心跳也 ping 命令连接、连续失败后断开重连；误判会把在途写入报为结果未知（I-E）。
- 写入方推送每个版本的 `VersionedCache` 是否默认不受全局 `refresh-window` 影响（Wow）。
- 五种类型的知识分散在六处：`CacheType` 枚举（core 公开 API 新增）。
- `create` 的结果看不到生效的 keyspace、`localMaxAge` 与频道；失效监听收到编码 key、`KeyEncoder` 没有解码；本地副本不带摘要（CoSec 每次本地命中重算 SHA-256）；结果未知的写不暴露写入标识；通道重置没有可编程的次数与时间（CoSec）。
- 面向真实 `RedisBackend` 的故障注入工具（断连、丢帧、重置），以及在 `InMemoryBackend` 上丢失一条失效（CoSec 自写 TCP 中继）。

## 3.4 维护者的决定（2026-11-07）

- 改变已有接口的：P1、P2、P3 在 GA 前做；P4 不改（`invalidate` 与 `delete` 按类型的角色命名，派生副本可以重建、权威副本没有了就没有了，I-A），在文档中写明。
- 扩展点 X1–X10 全部在 GA 前做。
- 原本推荐放到 GA 之后的也在 GA 前做：`CoherentEngine` 的拆分（2.1 第 1–3 项）、命中路径与命令路径上的性能项（2.1 第 9 项，2.2 第 5、6 项与 `readAll` 的多 key 脚本）、命令连接的健康检查（3.3 第一项）。
- 分三组并行，各组只改自己的目录：
  - **公开 API**（`cocache-api`、`cocache-core` 中引擎包之外、`cocache-test`）：P1、P3、X3、X4、X5、X7、X8、X9、X10。
  - **核心引擎**（`core/.../engine/`，以及 `KeyFilter` 与 `ExternalInvalidator`）：缺陷 D2–D5、P2、2.1 全部。
  - **Redis 与 Spring**（`cocache-redis`、`cocache-spring`、starter、`codec-jackson`）：2.2 全部（含命令连接的健康检查）、2.3 全部、缺陷 D7–D9、X1、X2、X6。
- changelog 与 todo 由协调的会话统一写；行为变化的 architecture.md 与 wiki 随各自的 PR。
- GA 之前的切片不单独跑 JMH（AGENTS.md）；全部合并之后、GA 之前做一次对 v6.10.0 的整体对比，超出预算的格子按 PR 二分。

## 4. 保持现状

审查中看起来奇怪、但有理由的：每种读取三个入口（保住命中路径内联、没有状态机，K2）；视图中预分配的 lambda；`LocalEntry` 按身份比较（`fillGuarded` 依赖它）；`releaseAll` 中不可取消与 `UNDISPATCHED` 的释放、`Produced`、`ASYNC_WRITE`（I-F、K18）；`SetEngine` 不继承 `CoherentEngine`；五个几乎相同的 spec 构建器与 `CoCacheFactory` 的 18 个重载（保住 Java 链式调用的返回类型）；`CacheProxies` 按方法名分派（实测的命中路径）；`L1Store` 的能力访问器没有默认 `null`（防止装饰器静默剥掉能力）；`EVALSHA` 加 `EVAL` 回退而不用 `FUNCTION LOAD`（滚动升级与托管 Redis 的 ACL）；Cluster 上每个 slot 组一次 `PUBLISH`（I-4）；`onReset` 与派发在 Lettuce 的 I/O 线程上（“订阅在重置之后返回”依赖它）；启动时 Redis 不可用即失败（订阅必须早于任何 L2 填充，I-12）；健康检查只在心跳失败时 DOWN、不访问 Redis；`BeanCandidates` 与 `cacheBeanNameOf` 的复杂度（编码 #865、#875、#887 的遮蔽规则）。

## 5. 顺序与验证

1. 缺陷 D1–D9 与 2.2 的第 1 项（可用性）先做，各自带复现用例。
2. 2.1–2.3 三组并行，每组按条目逐个 PR；每个 PR `./gradlew check` 全绿；改到 flight 层、L2 或戳的跑 `./gradlew :cocache-core:lincheck -Plincheck.full`。
3. 2.1 第 9 项与 2.2 第 5、6 项改动命中路径或命令路径：按 AGENTS.md，GA 之前的切片不单独对比，GA 前做一次对 v6.10.0 的整体对比（`scripts/jmh-compare.sh`，含 `l2HitMixed`），超出预算的格子按 PR 二分。
4. 第 3 节按维护者的决定排期；决定之后才动 `cocache-api`。

## 6. 进展

截至 2026-11-07，各条目已合并的 PR（`gh pr list --state merged`）如下。公共文档与 changelog 由协调会话分批写：#920、#943、#948，第四批（#929–#931、#933–#935、#949–#951、#953、#955），以及第五批（#923、#944–#947、#952、#954）。

- **§1 缺陷**：D1 #909；D2 #929（与 `TtlRules` 一起：上限只作用于 `Cache`，其它类型的 spec 拒绝它）；D3–D5 #911；D7–D9 #918，后续 #951（`createDeclared` 改为 `java.util.function.Consumer<String>`）。D6 按决定只记录、不修。
- **§2.1 核心引擎**：第 1 项 `SourceCalls` #930；第 2 项 `ScalarContent` #931；第 3 项 `ConditionalWrites` / `VersionedWrites` #933；第 4 项 `PendingInvalidations` #916；第 5 项 `settleFills` #926；第 6 项 `TtlRules` #929；第 7、8 项 #928；第 9 项：结果换算上移到 `AbstractEngine` #934，`freshHit` #935。
- **§2.2 Redis**：第 1、2 项 #914，原生镜像的反射提示 #950；第 3 项 #932；第 4 项 #937；第 5 项 #938；第 6 项 #939；第 7 项 #944。另有 #949：命令超时之后轮询令牌的释放，修掉 `timedOutTokenCommandsReleaseTheirTokens` 的偶发失败。
- **§2.3 Spring 与 starter**：第 1 项 #945（`DeclaredCaches` 与 `ApiTypes.authoritative`）；第 2 项 #940；第 3 项 #941；第 4 项 #942；第 5 项 #954（`CoCacheSpringCacheAutoConfiguration`）。
- **§2.4 下游易用性**：#910。
- **§3.1**：P1 #913；P2 #915；P3 #912；P4 按决定不改，理由写在 `VersionedCache` 的指南中。
- **§3.2**：X1 #921；X2：`CoherenceBackend.unwrap` #946，创建时与周期的驱逐检查、`StoreEvictionRisk` 与健康详情 #947；X3 #955（`lookupRemote` 与 `Lookup.Known`；`lookup` 不变：权威类型的 `lookup` 在 `absentTtl` / `localMaxAge` 之内由 L2 回答是已写明的契约）；X4 #919；X5 #922；X6 #923；X7 #927；X8 #924，包装过的 `InMemoryBackend` 的时钟 #952；X9 #917；X10 #936，库持有的提前刷新窗口与全局层冲突时创建失败 #953。
- **§3.3**：命令连接的心跳与重连 #925。
- **§3.4** 中 `readAll` 的多 key 脚本：协调会话决定不做。`getAll` 已经是一次往返（#939 之后每批共用一个截止时间），在 Cluster 上随机 key 分散在不同 slot 组，收益很小，新脚本却带来新的风险；GA 前的整体 JMH 若显示 `getAll` 是瓶颈再考虑。

仍未完成：

- GA 前对 v6.10.0 的一次整体 JMH 对比：已完成（2026-11-07，main 6704481a），RFC 0005 §7 的预算全部满足，见 [jmh-8.0-ga](jmh-8.0-ga.md)“GA 前复测”。#939 没有可对比的批量基准，记为未覆盖。
