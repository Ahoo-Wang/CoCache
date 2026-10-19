# CoCache Agent Skills

本目录提供三个按用户交付结果划分的 CoCache Agent Skills，服务于使用 CoCache 的下游 Java/Kotlin 应用：日常开发、测试、审查与诊断合为 `cocache-develop`，5.x 到 6.x 的破坏性升级为 `cocache-migrate`，实现 `CoherenceBackend` 扩展并通过 TCK 为 `cocache-extend`。被选中的 Skill 负责从取证到完成验证的整个任务。

这些 Skills 不复制 CoCache 的 API 文档（文档站 https://cocache.ahoo.me/ 与 `docs/architecture.md` 才是事实来源），而是补充工作流、使用不变量、授权边界和完成证据。具体签名、属性名、默认值和线格式必须在目标应用解析到的 CoCache 版本（sources jar 或对应的 `vX.Y.Z` tag）中重新确认。

## 激活边界

所有 Skill 仅服务于下游应用。**CoCache 仓库自身一律不激活**（包括各模块、`cocache-example`、文档与发布工作），那部分由仓库根目录的 `AGENTS.md` 约束。

下游任务必须满足其一：目标模块源码存在 `me.ahoo.cocache` import（5.x 应用为 `me.ahoo.cache`），构建中存在 `me.ahoo.cocache:*` 依赖，存在明确失败的 CoCache 行为，或任务明确要求引入、使用、配置、审查、解释或迁移到 CoCache。仅在否定、比较或排除语境中提到 CoCache 不能触发；cache、Redis、Caffeine、Spring Cache、`@Cacheable`、TTL、失效等通用词也不能；相邻工具（Spring Data Redis 的 `RedisCacheManager`、裸 Caffeine、Redisson、JetCache、Ehcache、Hazelcast）的任务同样不激活。

6.x 是当前维护基线和默认术语。`cocache-develop` 遇到仍在 5.x、且没有升级诉求的应用时，只按目标自身的 5.x 源码工作，不套用 6.x 的符号；5.x 到 6.x 的映射只保存在 `cocache-migrate`。

## Skills

| Skill | Primary outcome | Boundary |
|---|---|---|
| `cocache-develop` | 引入 CoCache、声明缓存（接口或 `CacheSpec`）、loader 与 codec、提交后失效与外部写入方、Spring Cache、`JoinCache`、`SetCache`、配置与生产就绪、测试；审查 findings；陈旧数据、命中率、回源打满、Redis 故障或启动失败的根因，授权后修复 | 不做 5.x 升级及其审查/诊断（`cocache-migrate`），不实现 `CoherenceBackend`（`cocache-extend`） |
| `cocache-migrate` | 5.x 到 6.x：模块、包、注解、`CacheSource`、API、bean 命名约定、属性、指标与 actuator、Redis 线格式与频道、`SetElementsCache` 数据拷贝、外部失效方、无滚动升级的切换与回滚；以及对这次升级的审查和故障 | 不用于纯 6.x 的新功能或问题（`cocache-develop`），不用于其他缓存库或通用 Spring Boot 升级 |
| `cocache-extend` | 在 CoCache 仓库之外实现或修复 `CoherenceBackend`（`RemoteStore` + `InvalidationChannel`），包括新存储、新失效通道与装饰器，以 `BackendProtocolSpec`、`EngineSpec`、`SetEngineSpec` 在每种模式下通过为完成证据 | 不用于应用里的 `Codec`/`KeyEncoder`/配置 shipped `RedisBackend`（`cocache-develop`），不改 CoCache 自己的后端 |

## Selection

按交付结果选择，不按涉及的组件名选择：交付物是一个通过 TCK 的后端实现，选 `cocache-extend`；主问题是 5.x 遗留（代码、配置、Redis 数据、外部程序或切换）选 `cocache-migrate`，包括对这类升级的审查与诊断；其余在应用里使用 CoCache 的开发、测试、审查、诊断都选 `cocache-develop`。

拆分理由：三者的完成证据与风险不同。`cocache-develop` 以应用测试（`cocache.backend=memory`、`InMemoryBackend` + `ManualClock`、`HookedBackend`）和 actuator 只读检查为证据；`cocache-migrate` 以迁移矩阵、Redis 数据演练与切换/回滚门禁为证据，涉及生产数据写入授权；`cocache-extend` 以 TCK 全部套件在每种模式下通过为证据，风险在一致性协议本身。应用里的 `Codec` 只需往返测试，因此留在 `cocache-develop`。

`review-and-fix` 与 `diagnose-and-fix` 都由 `cocache-develop` 完成，修复只在用户授权后进行。

### 改名（插件 0.2.0）

单一的 `cocache` Skill 拆为 `cocache-develop`、`cocache-migrate` 与 `cocache-extend`。旧名称不保留别名；既有安装需要刷新或重新安装插件。

## Content model

- `SKILL.md` 只保存入口契约（何时激活、何时不激活、相邻工具边界）、核心流程、授权边界和 reference 选择规则。
- `references/` 保存稳定决策、源码发现方法和风险边界，按需加载，均从 `SKILL.md` 直接引用（只有一层）；较长的 reference 带目录。
- `assets/` 保存可复制到输出中的模板，不作为推理资料默认加载；当前仅 `cocache-migrate/assets/migration-matrix.md`。
- Skill 内的 `scripts/` 只承载重复、确定且容易手写出错的操作；当前仅 `cocache-migrate/scripts/audit-5x-usage.sh`（只读、只用 `grep -E`，列出 5.x 用法线索，不证明兼容性）。
- `evals/<case>/prompt.md` 与 `evals/<case>/graders/*.md` 是 `claude plugin eval` 的用例：`prompt.md` 是交给 Agent 的请求（setup 文件内联在请求中），frontmatter 的 `tags` 恰含 `activation` 或 `behavior` 之一。
  - `activation` 用例设 `max_turns: 2`，只有一个 `tool_used: Skill` grader：`trigger` 用例要求本 Skill 加载；`negative` 用例（`min: 0`、`max: 0`、`arm: both`）要求它不加载，包括 CoCache 仓库自身、通用缓存请求、应由相邻 Skill 处理的请求，以及相邻工具（Spring Data Redis、Caffeine、Redisson、JetCache 等）。
  - `behavior` 用例用 `llm` grader 的 PASS 条目评判答案，并用 `regex` grader 要求答案点出关键符号；其 `tool_used: Skill` grader 只是加载指示。
- eval 用例不属于安装后工作流，也不由 Skill 加载。

安装后的三个 Skill 仅依赖各自目录中的 `SKILL.md`、`agents/` 和按需资源，不依赖仓库根目录的维护脚本。

## Validation

运行轻量结构校验与边界回归测试（CI 的 Skills 工作流 `.github/workflows/skills.yml` 在 `skills/` 或校验脚本变更时执行同样两条命令）：

```bash
python3 -S scripts/validate_cocache_skills.py
python3 -S -m unittest scripts.test_validate_cocache_skills
```

validator 只使用 Python 标准库，检查：

- `SKILL.md` frontmatter（双引号字符串、仅 `name` 与 `description`）、Skill 名称和目录一致性；
- `agents/openai.yaml` 必需字段及 `$skill-name` 默认提示；
- `plugins.json` include 与三个 Skill 目录的一致性；
- `references/`、`assets/`、`scripts/` 引用存在、都被 `SKILL.md` 直接引用且不能越出 Skill 目录；
- 运行时 Skill 内容不能引用父目录、本机绝对文件系统路径或 `evals/`、`agents/` 等维护内容；
- 每个 Skill 的 eval 套件形状：至少 3 个用例、kebab-case 目录、`prompt.md` frontmatter 键与 `claude plugin eval` 一致、grader 类型与 `arm` 合法、至少一个加载用例、一个 `arm: both` 的不加载用例和一个 behavior 用例；
- `SKILL.md` description 不超过 60 个词（超出即失败）。

回归测试另外覆盖 `audit-5x-usage.sh` 的输出与边界（需要目标根目录、跳过 `evals` 目录与 dotenv 文件）。

静态校验不会证明自然语言触发或答案正确。用 `claude plugin eval` 在本地执行用例（每次运行都是一次真实 Agent 会话，按登录账号计费，CI 不执行）。在 Skill 目录内分两轮运行：

```bash
cd skills/cocache-develop
claude plugin eval . --trust-plugin --no-publish --tag activation --ablation none --threshold 0
claude plugin eval . --trust-plugin --no-publish --tag behavior --ablation with-without --threshold 0
```

`activation` 轮用 `--ablation none`（不加载 Skill 的对照臂对触发判断没有意义）；`behavior` 轮默认带 with/without 对照。`--case 'b0*'` 只重跑部分用例，`--runs`、`--max-cost-usd`、`--json` 控制次数、费用与报告。报告写入不纳入版本控制的 `skills/<name>/evals/results/`。只有被测 Skill 会加载，所以相邻 Skill 的竞争不在评估范围内。

## Distribution

`plugins.json` 显式列出可分发的三个 Skill。Ahoo Skills Hub 负责同步、生成和验证插件产物；CoCache 仓库拥有并维护 Skill 内容。

框架版本与 Skill 插件版本独立。仓库源码修订不代表 Hub 已分发，也不会更新既有安装；源码验证、插件发布和安装刷新应分别报告状态。
