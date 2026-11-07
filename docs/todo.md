# 待办清单

截至 2026-11-04（6.10.0 已发布；v8 按 RFC 0005 实施完成，8.0.0 准备预发布）。本文只列**下一步要做的事**与验收标准；目标与分期的依据在 [roadmap](roadmap.md)，设计与论证在 [RFC 0005](rfc/0005-v8-target-architecture.md)，现行规范在 [architecture.md](architecture.md)。完成一项后从本文删除，并在 roadmap 的对应版本中记为已交付。

编号沿用 RFC 0005：附录 A 的审查发现写作 A.xx，第一轮、第二轮对抗验证的发现写作 C / M / P / U，第三轮写作 K / S、维护者对它的决定写作 D1–D8，未决问题写作 Q，v8 的里程碑写作 M1–M8（RFC 0005 §10）。

## 1. 8.0 的预发布与 GA

> 状态（2026-11-07）：8.0.0-alpha.6 已发布（#895）：包含 GA 不变量审查第三轮的 18 个 8.x issue 的修复。发布之后第 18 轮的 1 个 8.x issue（#892）已修复：#897（回源观测线程与异步调用第一次挂起之前的调用方线程上关闭运行时，不再等待自己），changelog 记在 `Unreleased` 中。alpha.5 已发布（#869），包含第二轮的 33 个 8.x issue 的修复（#851–#862）。第三轮中，第 13–15 轮的 12 个 8.x issue（#864–#868、#870–#876）已修复：#880（starter 的装配与绑定、事务模式）、#881（引擎的批量读取、组合视图、观测的加载、Jackson 编解码）、#882（中断与派发），#885 汇总 changelog；第 16–17 轮的 4 个 8.x issue（#877–#879、#883）已修复：#888（提交期间的补偿、`ObjectMapper` 按需取得）、#889（L1 命中的本地寿命、sealed 接口在创建之前拒绝），#891 汇总 changelog；第 17 轮补报的 2 个 8.x issue（#886、#887）已修复：#893（批量读取解码抛出 `Error` 时释放整批 lease）、#894（子容器的别名遮蔽父容器的 bean），changelog 在版本 PR 中；没有改公开 API、线格式与依赖，行为变化只有 `JacksonCodec` 按声明类型编码（声明类型不是多态类型时不再写入子类独有的属性）。维护者的决定（2026-11-06）：#834 回收指纹带上内容类别（`ContentFingerprint.of(envelope, format)`，cocache-api 变更）；#819 同一运行时内拒绝存储前缀重叠的 keyspace，跨应用的由部署保证；#825 编码后无法无损转成 UTF-8 的 key 在 L1 边界拒绝。新增的公开成员：`CachePolicy.Builder.localMaximumSize`（同 `maxConcurrent`）、`InMemoryBackend.writeForeign`（TCK 用）。下一步：
> 1. 下游在 alpha.6 上复跑各自的测试套件；没有新问题时下一步是 rc。CoSec 已在 alpha.6 上复跑通过（#1203，2,207 个测试、0 失败，没有 CoCache 缺陷），发现的 3 处文档缺口已补（见第 4 条）。Wow 已在 alpha.6 上复跑通过（#5436，wow-cocache 不改代码，41 个测试 0 失败、ABI 不变；Redis 上的集成测试交给 Wow 的 CI），没有阻塞问题，发现的 3 个低严重度问题见第 4 条：三个都已处理（#905、#906，以及全局层的未知键校验）。
> 2. GA 放行条件第 3 条：开着的 `bug` issue 只剩 6.x 的 #747、#761、#804–#807、#884、#890，由维护者安排。
> 3. **GA 前的架构审查**（2026-11-07，[ga-architecture-review](investigations/ga-architecture-review.md)）：四份模块审查加两个下游的体验。缺陷 D1–D9 与 Cluster 上 Pub/Sub 的强制重连先修；核心引擎、Redis 后端、starter 三组不改公开 API 的重构按切片并行；改变已有接口的四项（P1–P4）GA 之前由维护者决定，扩展点（X1–X10）只增不改。维护者决定（§3.4）：除 P4 外全部在 GA 前做，分三个子会话并行（公开 API、核心引擎、Redis 与 Spring），公共文档由协调会话统一写。已合并：#909（D1）、#910（下游易用性）、#911（D3–D5）、#912（P3 `L1SetWrite`）、#913（P1 策略从起点构建）、#914（Cluster 强制重连）、#915（P2 `KeyFilter` 收编码 key）、#916（`PendingInvalidations` 从引擎拆出）、#917（X9 三种视图两两可达）、#918（D7–D9）、#919（`CacheFailures.isTransient`）、#921（X1 `CacheNameContributor`）、#922（X5 带版本写入的 `ttl` 可为 `null`）、#924（X8 沿用 `InMemoryBackend` 的时钟）、#926（单 key 与批量填充在一处结算）、#927（类型化的哨兵与 `PolicyFields`）、#928（读不懂的格式在一处报告）、#936（`Locks.Builder.pin`）、#925（命令连接的心跳）、#932（脚本结果的解码移入 `RedisScripts`）、#937–#942（`TokenReleaser`、写脚本的 `SET … PX` 与分块的 `HSET` / `HDEL`、一批命令共享一个截止时间、去掉指标的 bean 后处理器、配置只解析一次、一个 SpEL 模板解析器）、#929–#931、#933–#935（从 `CoherentEngine` 拆出 `TtlRules`（同时修复 D2：按调用寿命的上限只作用于 `Cache`）、`SourceCalls`、`ScalarContent`、`ConditionalWrites` 与 `VersionedWrites`，读取结果的换算上移到 `AbstractEngine`，六个读取入口共用一份命中判定 `freshHit`）、#949–#951、#953（令牌释放测试的轮询、原生镜像登记 Cluster 强制重连的反射方法、`createDeclared` 改为 `Consumer<String>`、库持有的提前刷新窗口与全局层冲突时创建失败）、#955（X3 `lookupRemote` 与 `Lookup.Known`）、#923、#944–#947、#952、#954（X6 运行时与 Redis 后端选项的定制器、Redis 的卫生整理（单 key 写入发出时同步失败按确定未执行报告）、已声明缓存的一份目录 `DeclaredCaches`、X2 的 `CoherenceBackend.unwrap` 与创建时、周期的驱逐检查、包装过的 `InMemoryBackend` 的时钟、Spring Cache 的接线独立为 `CoCacheSpringCacheAutoConfiguration`）。公共文档与 changelog 已分五批写完；架构审查只剩 GA 前对 v6.10.0 的一次整体 JMH 对比（§0“JMH”）。
>
> 已决定、不改代码：NOPERM 降级为非原子广播并告警（保持）；不检查 Redis 版本（文档改正 7.0 的理由）；自定义 `CacheRuntime` 时事务模式的报告（文档说明）；格式 2 的短 `E` 不自愈（已知限制）；`InMemoryBackend` 参数顺序（不改）。GA 之后：`sharded` 模式频道未确认时每次心跳清空 L2 而健康仍为 UP。

- [x] **8.0.0-alpha.1 已发布**（2026-11-04，#786）：GitHub 预发布、Maven Central 与 GitHub Packages。预发布不作兼容性承诺（CONTRIBUTING）。站点 cocache.ahoo.me 自此从 `main` 部署（#787），`6.x` 不再部署（#788）。
- [x] **8.0.0-alpha.2 已发布**（2026-11-05，#791）：声明的接口可以继承异步与挂起视图（#789）。Maven Central 可解析（8.x 没有 `cocache-coroutines`，已并入 `cocache-core`）。
- [ ] **GA（8.0.0）的放行条件**，全部满足才发：
  1. **下游试用**：CoSec 与 Wow 在各自的分支上接入最新的 8.0.0 预发布版本，跑通各自的测试套件。发现的问题修复之后再发一个预发布版本，直到没有新问题。只做适配，不做数据迁移（升级说明的“下游”一节）。
     - **Wow**：草稿 PR Ahoo-Wang/Wow#5436（8.0.0-alpha.1，不合并），跑绿后停在草稿，等 rc 时升级再跑。alpha.3 复测通过；alpha.6（2026-11-07，从 alpha.3 升级）：wow-cocache 的代码不用改，41 个测试通过（原有 39 个，新增 2 个多态值跨节点读回的用例），ABI dump 不变；逐项核对了 #846、前缀重叠、未配对代理的 key、#873、#874；发现 1 的行为在 alpha.4–alpha.6 没有变化。发现 1：`VersionedCache` 的写入方落在 6.x 旧值上以 `CacheWriteException` 失败。RFC 0005 §4.1 规定按版本写入不把读不懂的内容当作没有值，所以改升级说明：切换时 `VersionedCache` 的旧 key 也要删（#792）。
     - **CoSec**：草稿 PR Ahoo-Wang/CoSec#1203（由 CoSec 维护者决定何时合并），rc 时升级再跑。alpha.2：本地 `check` 通过，2,976 个测试。alpha.6（2026-11-07，从 alpha.3 升级，含 BOM，源码不改即可编译）：8 个模块 2,207 个测试、0 失败（16 个跳过：可选的 Valkey / Cluster 目标与一项测量），可选的 Cluster 用例与 18 个变异体全部通过；逐项核对了 alpha.4–alpha.6 的 changelog（前缀重叠、未知的按缓存配置项、未配对代理的 key、接口缓存、#873、#874）。两次都没有 CoCache 缺陷，发现的文档与 API 问题记在第 4 条。rc 时告知 CoSec：自己约 180 行的驱逐检查可以删掉，改由 starter 在创建权威缓存时检查（不用 starter 时调用 `cocache-redis` 的 `StoreEvictionRisk.check(runtime)`）；测试中为 `HookedBackend` 设置的 `cocache.redis.store-eviction-check=warn` 也可以删掉，检查经 `CoherenceBackend.unwrap` 看穿它（#946、#947）。
  2. **RFC 0005 §7 的剩余测量**：`l2HitAbsent` / `l2HitUnfilled` 的命中成本与 `l2Hit` 相同；声明的异步、挂起视图接口（#789）的命中有数据。结果记入 [jmh-8.0-ga](investigations/jmh-8.0-ga.md)。**已完成**（#793）：`Absent` 与未填充为普通 L2 命中的 97.3%–98.7%，满足；声明的异步接口比异步视图每次多 0.7 ns，保留；声明的挂起接口见下一条。
  3. **没有未关闭的 8.x 缺陷**：GitHub 上标 `bug`、影响 `main` 的 issue 都已修复。
  4. **文档定稿**：wiki（en + zh）、升级说明、changelog 与代码一致；`docs/architecture.md` 的不变量与 TCK 一致。
     - CoSec 试用发现的（2026-11-05）：
       - [x] 文档：多段前缀拆分后，每个 key 前缀各要一条频道模式；RFC §8 中 `GlobalPolicyIndexCache` 不能改为 `SetStore`；内部 key 在 `{keyPrefix}!` 之下，原子广播探测除外（固定的 `cocache:!probe:{a}`）。
       - [x] `InMemoryBackend { clock }` 的尾随 lambda 绑定到 `commandTimeout`：不改。文档与测试都用 `InMemoryBackend(clock)`，调整参数顺序会破坏这种按位置传参的写法。
       - [x] `CacheAdmin.keyspace`：运维接口（`cocache-core`）增加只读的 `keyspace`（key 前缀与命名空间），端点的缓存报告输出 `keyPrefix` 与 `namespace`。
     - CoSec 在 alpha.6 上试用发现的（2026-11-07），都是文档：
       - [x] 升级说明：6.x 中相互嵌套的命名空间（`a` 与 `a:b`）拆分出 `keyPrefix` 之后存储前缀互为开头，创建第二个失败；要把其中一个改名，它的 key 随之换成新的字符串。
       - [x] 存储前缀重叠的检查只在一个 `CacheRuntime` 之内：changelog（alpha.5）与故障排查补上这一点（`caches.md` 已有）。
       - [x] 库自己检查后端时读取 `CacheRuntime.backend` / `CoCacheFactory.runtime.backend`，不注入 `CoherenceBackend` bean（#874 的同类问题，CoSec 已在自己那边修复）：配置页的自定义运行时一节与升级说明的 CoSec 一节。
       - [x] 含未配对代理的 key 被拒绝是调用方的错误，`read-failure` 与 `unknownRead` 都不适用，不会变成未命中、`ABSENT` 或回源（`caches.md#keys`）。
       - [x] 某些主机不创建的缓存（如只在控制面创建的）的 `cocache.caches.[name]` 配置在默认的 `caches-validation: fail` 下让其它主机启动失败：配置页已说明用 `warn`，由 CoSec 在自己的文档中说明，CoCache 不改。
     - Wow 在 alpha.6 上试用发现的（2026-11-07）：
       - [x] 升级说明的 Wow 一节过时：Wow 删掉了 `StarterCaches` 与 `StateViewCaches.CacheFactory`，`StateViewCaches` 直接接收 `CoCacheFactory`；切换之前先删 `cocache:wow.*`。
       - [x] `InMemoryBackend.writeForeign` 的 `key` 必须是完整的存储 key：不以存储前缀开头时拒绝（#905）。
       - [x] 在 bean 中创建缓存的库应让这个 bean 不懒加载（`@Lazy(false)`）：否则在 `spring.main.lazy-initialization` 下它晚于名称校验创建，合法配置被当作未声明的缓存（Wow 自己的缺陷，已在 Wow 修复；配置页补上这一句，#906）。已被 `CacheNameContributor` 取代（#921）：库发布一个贡献者 bean 声明可能创建的缓存名，不再需要 `@Lazy(false)` 或 `caches-validation: warn`。
       - [x] 6.x 留在 `cocache.defaults.<field>`（含 `cocache.defaults.key-prefix`）下的键被静默忽略，未知键校验只覆盖 `cocache.caches.[name]`（#846 的决定）。#906 先在文档中写明；维护者决定（2026-11-07）扩到全局层：`cocache.defaults` / `cocache.overrides` 下不认识的键也按 `caches-validation` 处理（#907）。
  5. **发版**：按 CONTRIBUTING 发 `8.0.0`。changelog 中的预发布条目合并为 `v8.0.0`。线格式自此在 8.x 内冻结（architecture.md 的不变量），兼容性承诺开始生效。
- [x] **声明的挂起视图接口的代理开销**（GA 前）：原因是 C2 的内联深度上限，不是反射；读取改经绑定到视图的方法句柄转发，代理每次命中多 2.0 ns（原来 10.3 ns），1 线程 218%、8 线程 210%，其余基准不变（[jmh-8.0-ga](investigations/jmh-8.0-ga.md)“后续”）。原来的记录：经 `CacheProxies.ViewHandler` 转发挂起方法，L2 命中从 5.33 ns 增加到 15.65 ns，每次多 10.3 ns（同步接口的代理多 2.5 ns），0 B/op，开销是转发时的 CPU，原因没有 profiling（#793）。异步接口走同一个 `forward` 只多 0.7 ns，所以要先查清挂起方法的转发慢在哪。目标：不超过同步接口代理的 2 倍（约 5 ns）。这是命中路径的改动，要附 `scripts/jmh-compare.sh -b l2HitViaSuspendingInterface,l2HitSuspending,l2HitViaInterface,l2HitViaAsyncInterface,l2HitMixed -t 1,8` 的表。
- [x] **8.0.0-alpha.3 已发布**（2026-11-05，#801）；CoSec（#1203）与 Wow（#5436）在 alpha.3 上复测通过、没有新问题。原条目：声明的挂起接口的代理开销（#799）、`CacheAdmin.keyspace`（#800）与下游试用发现的文档更正（#792、#798）。发布之后通知 CoSec 与 Wow 升级，复跑各自的测试套件；没有新问题时下一步是 rc。
- [x] **8.0.0-alpha.4 已发布**（2026-11-06，#847）：GA 不变量审查的修复（#809、#810、#845）；`cocache.caches.[name]` 下不认识的配置项让启动失败（#846）；starter 接受 YAML 的 `off`、全局 `tombstone-ttl: forever` 对 `VersionedStore` 跳过（#820）；用户文档定稿与过期 KDoc（#811、#813）。`CachePolicy.Builder` 新增 `maxConcurrent`。发布之后通知 CoSec 与 Wow 升级，复跑各自的测试套件。
- [x] **8.0.0-alpha.5 已发布**（2026-11-07，#869）：GA 不变量审查第二轮的修复（#851–#862）：运行时注册与关闭的交接、事务回源、Redis 命令与频道、flight 与令牌、引擎写入与刷新、接口代理的声明校验、starter 的 bean 匹配、key 编码、回收指纹带格式。已通知 CoSec（暂缓，见上）；Wow 尚未复跑。
- [x] **8.0.0-alpha.6 已发布**（2026-11-07，#895）：GA 不变量审查第三轮的修复（#880–#882、#888、#889、#893、#894）：子容器的遮蔽与名称解析、未知配置项的记录、事务模式一次调用只取一次、自建运行时的驱逐检查、批量读取整批拒绝、组合视图的存在性、挂起加载的观测、多态集合的编码、嵌套加载的源中断、Spring 方法被中断、执行器拒绝之后的关闭；第 16–17 轮的修复（#888、#889）：提交期间补偿的先后、`ObjectMapper` 按需取得、L1 命中的本地副本不活过 L1、sealed 接口在创建之前拒绝；第 17 轮补报的修复（#893、#894）：批量读取解码抛出 `Error` 时释放整批 lease、子容器的别名遮蔽父容器的 bean。Maven Central 可解析。CoSec 在 alpha.6 上复测通过、没有 CoCache 缺陷（见第 1 条）；Wow 在 alpha.6 上复测通过、没有阻塞问题（见第 1 条）。

## 1a. 6.x 跟进


- [ ] **确认 6.9.1 已上 Maven Central**。
  - 发布 v6.9.1 触发的 [Packages Deploy](https://github.com/Ahoo-Wang/CoCache/actions/runs/40867726421) 要成功。
  - `cocache-core` 与 `cocache-spring-boot-starter` 的 6.9.1 要能下载。
  - 失败时按 run 页面的原因重跑，或修复后重发。
- [ ] **通知下游升级时要改的配置**（6.9.0 起没有 loader 的缓存默认 fail closed，见 changelog）：
  - CoSec（依赖 6.7.0）：
    - 撤销表 `revokedToken` / `revokedSubject` 显式配置 `read-failure: degrade`。否则 Redis 故障时撤销检查从放行变成报错（CoSec 的用例 `anUnreadableStoreMeansNotRevoked` 会发现）。
    - `MembershipCache` 视需要配置 `invalidate-failure: retry`。
  - Wow（依赖 6.8.0）：不需要改代码。
  - Spring Cache 用户：没有 loader 的缓存上，`@CacheEvict` 失败改为抛出；要保持 6.8 的重试，配置 `invalidate-failure: retry`。
- [ ] **6.x 缺陷 [#747](https://github.com/Ahoo-Wang/CoCache/issues/747)**（2026-11-03 登记，维护者：先登记、继续推进 v8）：断线时 Lettuce 以同一个异常实例使在途命令全部失败，`RedisRemoteStore` 把它 suppress 到自己身上，抛出 `IllegalArgumentException`；批量读因此漏释放其余 key 的 lease，各节点等到 `lease.ttl`。`main` 已在 #746 修复；6.x 的修复与发版由维护者安排。
- [x] **切换 `main` 到 v8**（RFC 0005 §10 第 0 步）：
  - 从 `v6.10.0` 切出 `6.x`；`6.x` 上推送触发的 CI 与 wiki 发布改为 `6.x`。
  - 仓库设置（维护者操作）：GitHub Pages 的部署环境 `github-pages` 允许 `6.x`；`6.x` 的分支保护规则与 `main` 一致。
  - `main`：基线 JDK 21；停止发布 wiki；CONTRIBUTING 的分支与发版规则。

## 0. 现状（2026-11-04）

`main` 是 8.0 开发线。RFC 0005 的 M1–M8 全部完成（§2）。GA 前的 JMH 对比已完成：代码冻结点 #780 对 v6.10.0，RFC 0005 §7 的预算全部满足，见 [jmh-8.0-ga](investigations/jmh-8.0-ga.md)（#783）。下一步是 8.0.0 的预发布（alpha）。维护者决定 8.x **不考虑兼容、不背历史债务**（2026-11-03，RFC 0005 §12）：不提供迁移工具，代码、KDoc 与文档只描述 8.x 的行为。

**JMH**
- 开发期间的切片没有跑对比，GA 前做了一次（上一段）。之后改动命中路径的 PR 按 AGENTS.md 对比上一个版本，方法固定在 `scripts/jmh-compare.sh` 与 architecture.md §14。
- GA 前的架构审查（§1 第 3 条）的切片同样不单独对比：全部合并之后、GA 之前再做一次对 v6.10.0 的整体对比，超出预算的格子按 PR 二分（[ga-architecture-review](investigations/ga-architecture-review.md) §5）。其中改动命中路径的：#934（读取结果的换算上移到 `AbstractEngine`，带版本的类型的 `lookup` 少一次分配）、#935（六个读取入口共用 `freshHit`），对比要含 `l2Hit`、`l2HitViaInterface` 与 `l2HitMixed`；改动命令路径的：#938（标量写入一条 `SET … PX`、集合成员分块写入，脚本 SHA 变化：`put`、`putAll`、集合写入）、#939（一批命令共享一个截止时间：`getAll`，以及 `fillAll`、`putAll` 与批量失效）。
- 整套构建会把负载推到 9–12：锁目录存在时，其它会话不开 `./gradlew check`、lincheck 与整套测试。逐格补跑之间锁会释放几秒，所以门槛是锁**连续 2 分钟**不存在：`while :; do while [ -d /tmp/cocache-jmh.lock ]; do sleep 30; done; sleep 120; [ -d /tmp/cocache-jmh.lock ] || break; done; ./gradlew check`。
- 基准要精简（维护者，2026-11-03）：按 diff 改动的开销，每种开销只选它占比最大的基准；`l2HitMixed` 照常包含。PR 里写明没跑的基准及理由。一次对比控制在 15 分钟内：先按 `-r 3 --warmup-iterations 2 --warmup-time 2s --iterations 3 --time 3s` 筛查，中位数退化落在 2%–4% 的格子按默认设置补跑。
- 维护者通知本机暂停时（本机借给别的项目测量），不跑 Gradle、lincheck、JMH，不起 Docker 容器，只提交、推送、改 PR 正文，由 GitHub CI 验证。

**CI**
- `Lincheck`（`./gradlew :cocache-core:lincheck`，#738）与 `TLC`（`scripts/tlc.sh`，#763）是非阻塞作业：不列入必需检查，失败要查明原因（见 [Lincheck 调查](investigations/lincheck-feasibility.md)、`docs/spec/README.md`）。

**工作方式**
- 每个切片一个分支（`<type>/<描述>`）与一个 squash 合并的 PR，标题用 Conventional Commits，PR 正文英文。维护者授权架构会话（2026-11-02）：审查通过、CI 全绿后由它合并或开启自动合并。偏离 RFC、未定的公开接口、线格式、新依赖、JMH 不达标仍先问维护者。
- 本地只跑改到的测试类与一次 `./gradlew check`，通过即推送；Cluster、Valkey、Spring Boot 4.0 与 wiki 构建交给 CI（2026-11-03）。
- 本地 Redis 与环境变量的注意事项见 AGENTS.md“Commands”；测试前取消所有 `SPRING_DATA_REDIS_*` 变量。
- 每个修复都要有复现用例。协议改动除了常规用例，再用三件工具检查：`TokenConservationPropertyTest`（令牌守恒）、`MultiNodeSimulationTest`（确定性多节点仿真，`-PsimSeed=<n>` 重放）、`./gradlew :cocache-core:lincheck`（单节点交错的模型检验）。变更行为时同步 architecture.md 与 wiki（en + zh）。

**已知事项**
- 异步数据源的超时仍由 JDK 延迟线程按真实时间计时（`completeOnTimeout`），仿真中的加载总在它触发前完成；若改为协程计时，需附 JMH。
- 失效戳与 SingleFlight 仍按存储 key（L2 按编码后的 key，#727）；只有在命中路径之外有收益时才值得改。

## 2. v8 里程碑（RFC 0005 §10）

全部完成。每个里程碑由若干 PR 组成，每个 PR 的 `./gradlew check` 全绿。

| 里程碑 | 交付 | PR |
|--------|------|----|
| M1 协程核心 | 协议改写为一份 `suspend` 实现，同步变体经 `runBlocking`、异步变体在运行时作用域；删除引擎中的异步分支、`Futures` 的续接、定时器与许可等待队列 | #716–#720 |
| M2 flight 层与协议 | L2 填充改为 `compute` + 按身份复核；合并规则逐 key；`load.timeout`；L1 命令不可取消地等待与令牌守恒性质测试；确定性多节点仿真；Lincheck 可行性；L2 key 不含前缀 | #721–#727 |
| M3 SPI | 挂起的 `L1Store`、能力面、`FutureL1Store` 与 `adapt`、`ForwardingL1Store`；Redis 原生的 `RedisL1Store`，删除异步 SPI；`L1Fill`、`ContentFingerprint`、令牌的异步释放与重发（K12、K14）；TCK 声明能力并 fail closed | #729–#751 |
| M4 线格式 2 与布局 | 没有版本标记的 key 布局、带前缀的频道与 namespace 字符集（D5）；格式 2 的头部、版本段、扩展区与写入标识（D2）；SHA-256 摘要段与读取方校验（D1、K3）、状态 `E` 与带版本的填充（P1、P14）；删除只为旧版本保留的代码 | #752–#762 |
| M5 用户 API | 五种类型与同构的异步、挂起视图（V1–V3）；`Lookup.Unknown` 与 `unknownRead`（S16）；`CoCacheFactory`（S21）；挂起视图直接运行在协程核心上（K2、K6、K7）；`InvalidationListener` 收到编码 key、空前缀告警（S4）；按缓存的读超时（D6）；结果未知的派生 `put` 的补偿失效（V7、U8） | #766、#768–#770、#772 |
| M6 配置 | 三层配置、字段三态、适用性表、`lock` / `constrain` / `unlock`（V10、S8–S12、S17、S20、S22、S23）；事务模式按缓存分层 | #773、#779 |
| M7 Spring | starter 的事务装饰器（U3、S18、S19、U19）；回滚时失效事务内的填充与已登记 key 的私有回源（S7、S15、S24）；`Unknown` 的映射（U5）；只广播失效帧的端点操作 | #759、#771、#779 |
| M8 检验与文档 | Lincheck 覆盖全部操作（#774）；TLA+ 模型与 TLC（#763、#764、#767、#781）；architecture.md 重排（V12，#778）；wiki、升级说明与 skills（#777）；P12 与 RFC 0005 §5 的删除收尾（#780）；GA 前的 JMH 对比（#783） | #763–#783 |

## 3. 需要维护者决定

均见 RFC 0005 §11。

| 问题 | 何时需要 |
|------|----------|
| Q11 重置的严格线性化（读写锁） | 已决定（维护者，2026-11-04）：不采用。重置按程序顺序生效（architecture.md I-C），TLA+ 与 Lincheck 检查这个界；读写锁会在 L2 命中路径上加锁，与 G3 冲突 |
| Q5 `cocache-spring` 可选依赖 reactor（响应式事务） | 推迟到 8.x |
| Q7 接口式代理与受检异常：构建期生成实现 | 推迟到 8.x |
