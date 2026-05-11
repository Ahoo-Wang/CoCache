---
title: 发布
description: CoCache 发布流程详解，包括版本管理、发布命令和 Maven Central 发布。
---

# 发布

CoCache 通过 GitHub Actions 自动化发布到 Maven Central。

## 版本管理

版本号在 `gradle.properties` 中定义，遵循语义化版本规范（Semantic Versioning）：

- **MAJOR**：不兼容的 API 变更
- **MINOR**：向下兼容的功能新增
- **PATCH**：向下兼容的问题修复

## BOM（Bill of Materials）

`cocache-bom` 模块提供统一的版本管理，用户只需引入 BOM 即可管理所有 CoCache 模块的版本：

```kotlin
dependencies {
    implementation(platform("me.ahoo.cococache:cocache-bom:latest.version"))
    implementation("me.ahoo.cococache:cocache-core")
    implementation("me.ahoo.cococache:cocache-spring-redis")
}
```

## 发布到本地 Maven

```bash
./gradlew publishToMavenLocal
```

发布后可在本地项目中使用：

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("me.ahoo.cococache:cocache-core:LOCAL_VERSION")
}
```

## 发布到 Maven Central

### 自动发布（推荐）

推送到 `main` 分支或创建 Git Tag 时，GitHub Actions 自动触发发布流程：

```bash
# 创建版本标签
git tag v1.0.0
git push origin v1.0.0
```

CI 工作流（`package-deploy.yml`）会自动：
1. 运行完整测试
2. 构建所有模块
3. 生成 GPG 签名
4. 发布到 Maven Central

### 手动发布

```bash
./gradlew publish
```

需要配置 GPG 密钥和 Maven Central 凭据。

## 可发布的模块

以下模块会被发布到 Maven Central：

| 模块 | 说明 |
|------|------|
| cocache-bom | BOM |
| cocache-dependencies | 依赖版本目录 |
| cocache-api | 核心接口 |
| cocache-core | 核心实现 |
| cocache-spring | Spring 集成 |
| cocache-spring-redis | Redis 实现 |
| cocache-spring-boot-starter | Spring Boot Starter |
| cocache-spring-cache | Spring Cache 桥接 |
| cocache-test | 测试规范 |

以下模块**不会**发布：
- `cocache-example`（示例应用）
- `code-coverage-report`（覆盖率报告）

## 依赖版本目录

`cocache-dependencies` 模块使用 Gradle Version Catalog 管理所有第三方依赖版本，位于 `gradle/libs.versions.toml`。

## 相关页面

- [构建与 CI](./index.md) - 构建系统
- [贡献指南](./contributing.md) - 贡献代码
- [模块概览](../modules/index.md) - 模块结构
