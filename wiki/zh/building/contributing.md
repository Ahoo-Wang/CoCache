---
title: 贡献指南
description: CoCache 贡献指南，包括代码规范、分支策略、PR 流程和测试要求。
---

# 贡献指南

感谢你对 CoCache 项目的关注！本指南帮助你了解如何为项目贡献代码。

## 开发环境准备

### 必需工具

- **JDK 17+**
- **Git**
- **IDE**：推荐 IntelliJ IDEA（安装 Kotlin 插件）
- **Docker**（可选，用于运行 Redis 集成测试）

### 克隆项目

```bash
git clone https://github.com/Ahoo-Wang/CoCache.git
cd CoCache
```

### 构建项目

```bash
# 跳过测试的快速构建
./gradlew build -x test

# 完整检查
./gradlew check
```

## 代码规范

### Kotlin 编码风格

- 遵循 Kotlin 官方编码规范
- 使用 Detekt 进行静态分析，配置位于 `config/detekt/detekt.yml`
- 提交前运行 `./gradlew detekt` 确保无问题

### 测试要求

- 新增代码必须包含对应的单元测试
- 使用 fluent-assert 进行断言（`import me.ahoo.test.asserts.assert`）
- 不使用 AssertJ 的 `assertThat()`
- 缓存实现需继承 `cocache-test` 中的 TCK 规范
- 集成测试需标记为需要外部依赖

### 提交规范

提交信息格式：

```
<type>(<scope>): <description>

[可选正文]

[可选脚注]
```

类型（type）：
- `feat`：新功能
- `fix`：Bug 修复
- `docs`：文档
- `style`：格式调整
- `refactor`：重构
- `test`：测试
- `chore`：构建/工具

示例：
```
feat(core): add BloomKeyFilter for cache penetration prevention
fix(redis): handle expired cache value in RedisDistributedCache
test(core): add concurrency test for DefaultCoherentCache
```

## 分支策略

- `main`：主分支，保持稳定
- `feature/*`：功能分支，从 `main` 创建
- `fix/*`：修复分支，从 `main` 创建

## PR 流程

1. Fork 项目仓库
2. 创建功能分支：`git checkout -b feature/my-feature`
3. 编写代码和测试
4. 确保所有检查通过：`./gradlew check`
5. 提交更改并推送到 Fork
6. 创建 Pull Request 到 `main` 分支
7. 等待 CI 检查通过
8. 等待 Code Review

### PR 检查清单

- [ ] 代码遵循 Kotlin 编码规范
- [ ] Detekt 检查通过
- [ ] 新增代码包含单元测试
- [ ] 所有测试通过
- [ ] 提交信息符合规范
- [ ] 无敏感信息（密钥、密码等）

## 项目结构

贡献代码时需了解的目录结构：

```
cocache-api/          # 核心接口（添加新接口时修改此模块）
cocache-core/         # 核心实现（添加新实现时修改此模块）
cocache-spring/       # Spring 集成
cocache-spring-redis/ # Redis 实现
cocache-test/         # 共享测试规范（添加新 TCK 时修改此模块)
config/
├── detekt/detekt.yml # Detekt 配置
└── logback.xml       # 测试日志配置
```

## 添加新的缓存实现

如果要添加新的缓存实现（如 Memcached），步骤如下：

1. 在 `cocache-core` 或新模块中实现接口
2. 继承 `cocache-test` 中的 TCK 规范编写测试
3. 如果需要 Spring 集成，在 `cocache-spring` 中添加工厂类
4. 如果需要自动配置，在 `cocache-spring-boot-starter` 中添加 Bean 定义
5. 更新文档

## 相关页面

- [构建与 CI](./index.md) - 构建系统
- [发布](./publishing.md) - 发布流程
- [测试概览](../testing/index.md) - 测试策略
- [模块概览](../modules/index.md) - 模块结构
