---
title: 模块概览
description: CoCache 模块概览，介绍各子模块的职责、依赖关系和关键类。
---

# 模块概览

CoCache 采用模块化设计，各模块职责清晰，可按需引入依赖。

## 模块依赖图

```mermaid
graph TB
    API["cocache-api<br>核心接口"]
    Core["cocache-core<br>核心实现"]
    Spring["cocache-spring<br>Spring 集成"]
    Redis["cocache-spring-redis<br>Redis 实现"]
    Starter["cocache-spring-boot-starter<br>Spring Boot 自动配置"]
    SpringCache["cocache-spring-cache<br>Spring Cache 桥接"]
    Test["cocache-test<br>测试规范"]
    BOM["cocache-bom<br>BOM"]
    Deps["cocache-dependencies<br>依赖版本管理"]

    API --> Core
    Core --> Spring
    Spring --> Redis
    Spring --> Starter
    Spring --> SpringCache
    Core --> Test
    BOM -.-> API
    BOM -.-> Core
    BOM -.-> Spring
    BOM -.-> Redis
    BOM -.-> Starter
    BOM -.-> SpringCache

    style API fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Core fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Spring fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Redis fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Starter fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SpringCache fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Test fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style BOM fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Deps fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## 模块列表

| 模块 | 类型 | 说明 |
|------|------|------|
| [cocache-api](./cocache-api.md) | 库 | 核心接口定义 |
| [cocache-core](./cocache-core.md) | 库 | 默认实现 |
| [cocache-spring](./cocache-spring.md) | 库 | Spring 集成 |
| [cocache-spring-redis](./cocache-spring-redis.md) | 库 | Redis 实现 |
| [cocache-spring-boot-starter](./cocache-spring-boot-starter.md) | Starter | Spring Boot 自动配置 |
| [cocache-spring-cache](./cocache-spring-cache.md) | 库 | Spring Cache 桥接 |
| cocache-test | 测试 | 共享测试规范 |
| cocache-bom | BOM | 版本管理 |
| cocache-dependencies | 依赖 | 中央版本目录 |
| cocache-example | 示例 | 示例应用 |
| code-coverage-report | 报告 | JaCoCo 覆盖率聚合 |

## 最小依赖

如果只需要核心缓存能力（不依赖 Spring）：

```kotlin
implementation("me.ahoo.cococache:cocache-core")
```

## Spring Boot 项目

推荐使用 Spring Boot Starter：

```kotlin
implementation(platform("me.ahoo.cococache:cocache-bom:latest.version"))
implementation("me.ahoo.cococache:cocache-spring-boot-starter")
```

## 相关页面

- [cocache-api](./cocache-api.md) - 核心接口模块
- [cocache-core](./cocache-core.md) - 核心实现模块
- [cocache-spring](./cocache-spring.md) - Spring 集成模块
- [cocache-spring-redis](./cocache-spring-redis.md) - Redis 实现模块
- [cocache-spring-boot-starter](./cocache-spring-boot-starter.md) - 自动配置模块
- [cocache-spring-cache](./cocache-spring-cache.md) - Spring Cache 桥接模块
