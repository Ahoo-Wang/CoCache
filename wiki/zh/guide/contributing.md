---
title: 贡献指南
description: 如何为 CoCache 项目贡献代码。
---

# 贡献指南

欢迎为 CoCache 贡献代码！以下是快速入门指南。

## 快速开始

```bash
# 克隆仓库
git clone https://github.com/Ahoo-Wang/CoCache.git
cd CoCache

# 构建（不运行测试）
./gradlew build -x test

# 完整检查（测试 + 代码质量）
./gradlew check
```

## 开发环境

- **JDK 17+**（通过 [build.gradle.kts](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts) 中的 `jvmToolchain` 配置）
- **Gradle 9.4.1**（包含 wrapper）
- **Kotlin 2.3.20**，编译参数 `-Xjsr305=strict`、`-Xjvm-default=all-compatibility`

## 代码风格

- **Detekt** 配置文件：`config/detekt/detekt.yml`
- 运行 `./gradlew detekt` 检查，`./gradlew detektAutoFix` 自动修复
- 关键规则：`MaxLineLength` = 300，允许 `java.util.*` 通配符导入

## 测试

- JUnit 5 + **mockk** + **fluent-assert**
- 使用 `.assert()` 扩展函数，Kotlin 测试中不要使用 AssertJ 的 `assertThat()`
- 新的缓存实现应继承 `cocache-test` 中的 TCK 规范

## Pull Request 流程

1. Fork 仓库
2. 从 `main` 分支创建功能分支
3. 确保 `./gradlew check` 通过
4. 提交 PR，清晰描述变更内容

## 报告问题

请使用 [GitHub Issues](https://github.com/Ahoo-Wang/CoCache/issues) 报告 Bug 或提出功能请求。

---

更多详情请参阅[贡献指南](/zh/building/contributing)。
