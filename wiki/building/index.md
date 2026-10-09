---
title: Build & CI Overview
description: Build system configuration, Gradle setup, CI/CD pipelines, and quality tooling for the CoCache project.
---

# Build & CI Overview

CoCache uses **Gradle 9.8.1** with the Kotlin DSL, targeting **JDK 17+** across all library modules. The build pipeline integrates Detekt for static analysis, Dokka for API documentation, JaCoCo for code coverage, and GitHub Actions for continuous integration and deployment.

## Gradle Setup

The root [`build.gradle.kts`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts) applies shared configuration to all subprojects through `allprojects` and `configure` blocks. The [Gradle Version Catalog](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/libs.versions.toml) centralizes dependency versions.

```mermaid
graph LR
    subgraph Root Build["Root build.gradle.kts"]
        direction TB
        A["allprojects"] --> B["Detekt Plugin"]
        A --> C["Repository Config"]
        D["configure(libraryProjects)"] --> E["Dokka"]
        D --> F["JaCoCo"]
        D --> G["Java Library"]
        D --> H["Kotlin JVM"]
        D --> I["KotlinCompile Options"]
        D --> J["Test Config"]
    end
    style Root Build fill:#161b22,stroke:#6d5dfc,color:#e6edf3
    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style G fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style H fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style I fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style J fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

### JDK 17 Toolchain

All library modules enforce JDK 17 via the Kotlin JVM toolchain configuration in the root build script:

```kotlin
// [build.gradle.kts:88-91](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L88-L91)
configure<KotlinJvmProjectExtension> {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
```

The [`cocache-example`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-example/build.gradle.kts) module also declares its own JDK 17 toolchain explicitly.

### Kotlin Compiler Flags

Two critical Kotlin compiler flags are applied to all library modules:

| Flag | Purpose | Source |
|------|---------|--------|
| `-Xjsr305=strict` | Enforces strict null-safety for JSR-305 annotated APIs (e.g., Spring, Guava) | [`build.gradle.kts:95`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L95) |
| `-jvm-default=enable` | Generates default method implementations in interfaces for Java interoperability | [`build.gradle.kts:95`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L95) |
| `javaParameters = true` | Stores method parameter names in bytecode for reflection-based tools | [`build.gradle.kts:96`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L96) |

Java compilation also passes `-parameters` for consistent parameter name retention:

```kotlin
// [build.gradle.kts:99-101](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L99-L101)
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters"))
}
```

## Dependency Management

CoCache uses a two-tier dependency management strategy:

```mermaid
graph TD
    subgraph Dependency Strategy["Dependency Management"]
        direction TB
        BOM["cocache-dependencies<br>(BOM / Platform)"] --> API["cocache-api"]
        BOM --> CORE["cocache-core"]
        BOM --> SPRING["cocache-spring"]
        BOM --> SPRING_REDIS["cocache-spring-redis"]
        BOM --> SPRING_CACHE["cocache-spring-cache"]
        BOM --> SPRING_BOOT["cocache-spring-boot-starter"]
        BOM --> TEST["cocache-test"]
        CATALOG["gradle/libs.versions.toml<br>(Version Catalog)"] --> BOM
    end
    style Dependency Strategy fill:#161b22,stroke:#6d5dfc,color:#e6edf3
    style BOM fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CATALOG fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style API fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CORE fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SPRING fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SPRING_REDIS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SPRING_CACHE fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SPRING_BOOT fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style TEST fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

| Artifact | Role | Source |
|----------|------|--------|
| `cocache-dependencies` | Platform BOM aggregating Spring Boot, CoSid, fluent-assert, and library constraints | [`cocache-dependencies/build.gradle.kts`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-dependencies/build.gradle.kts) |
| `cocache-bom` | Published BOM exposing all library modules as dependency constraints | [`cocache-bom/build.gradle.kts`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-bom/build.gradle.kts) |
| `gradle/libs.versions.toml` | Version catalog defining all library and plugin versions | [`gradle/libs.versions.toml`](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/libs.versions.toml) |

All library modules import the platform via:

```kotlin
// [build.gradle.kts:111](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L111)
api(platform(dependenciesProject))
```

### Key Dependency Versions

| Dependency | Version | Source |
|------------|---------|--------|
| Kotlin | 2.4.0 | [`libs.versions.toml:15`](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/libs.versions.toml#L15) |
| Spring Boot | 4.1.0 | [`libs.versions.toml:3`](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/libs.versions.toml#L3) |
| CoSid | 3.2.0 | [`libs.versions.toml:4`](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/libs.versions.toml#L4) |
| Detekt | 1.23.8 | [`libs.versions.toml:13`](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/libs.versions.toml#L13) |
| Dokka | 2.2.0 | [`libs.versions.toml:14`](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/libs.versions.toml#L14) |
| JUnit | 6.1.1 | [`libs.versions.toml:9`](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/libs.versions.toml#L9) |
| fluent-assert | 1.0.0 | [`libs.versions.toml:10`](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/libs.versions.toml#L10) |
| mockk | 1.14.11 | [`libs.versions.toml:11`](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/libs.versions.toml#L11) |

## Module Build Graph

The following diagram shows the inter-module dependency relationships:

```mermaid
graph TD
    subgraph Modules["Module Dependency Graph"]
        direction TB
        API["cocache-api"]
        CORE["cocache-core"]
        SPRING["cocache-spring"]
        SPRING_CACHE["cocache-spring-cache"]
        SPRING_REDIS["cocache-spring-redis"]
        SPRING_BOOT["cocache-spring-boot-starter"]
        TEST["cocache-test"]
        BOM["cocache-bom"]
        DEPS["cocache-dependencies"]
        EXAMPLE["cocache-example"]
        COVERAGE["code-coverage-report"]

        CORE --> API
        SPRING --> CORE
        SPRING_CACHE --> CORE
        SPRING_REDIS --> CORE
        SPRING_REDIS --> SPRING
        SPRING_BOOT --> SPRING
        SPRING_BOOT --> SPRING_CACHE
        SPRING_BOOT --> SPRING_REDIS
        TEST --> CORE
        EXAMPLE --> SPRING_BOOT
        COVERAGE -.->|jacocoAggregation| CORE
        COVERAGE -.->|jacocoAggregation| SPRING
        COVERAGE -.->|jacocoAggregation| SPRING_REDIS
        COVERAGE -.->|jacocoAggregation| SPRING_BOOT
    end
    style Modules fill:#161b22,stroke:#6d5dfc,color:#e6edf3
    style API fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CORE fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SPRING fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SPRING_CACHE fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SPRING_REDIS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SPRING_BOOT fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style TEST fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style BOM fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style DEPS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style EXAMPLE fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style COVERAGE fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

The root build script classifies projects into logical groups for configuration:

| Group | Projects | Purpose | Source |
|-------|----------|---------|--------|
| `bomProjects` | `cocache-bom`, `cocache-dependencies` | Java Platform (BOM) modules | [`build.gradle.kts:29-32`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L29-L32) |
| `serverProjects` | `cocache-example` | Non-published application modules | [`build.gradle.kts:34-36`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L34-L36) |
| `libraryProjects` | All others minus BOMs and server | Published library modules with Dokka, JaCoCo, and publishing | [`build.gradle.kts:44-46`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L44-L46) |

## Quality Tooling

### Detekt (Static Analysis)

Detekt is applied to **all projects** (including BOM and server modules) via the `allprojects` block. Configuration is centralized at [`config/detekt/detekt.yml`](https://github.com/Ahoo-Wang/CoCache/blob/main/config/detekt/detekt.yml).

```kotlin
// [build.gradle.kts:54-59](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L54-L59)
allprojects {
    apply<DetektPlugin>()
    configure<DetektExtension> {
        config.setFrom(files("${rootProject.rootDir}/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        autoCorrect = true
    }
}
```

Key Detekt configuration overrides:

| Rule | Setting | Source |
|------|---------|--------|
| `LongParameterList` | disabled | [`detekt.yml:3`](https://github.com/Ahoo-Wang/CoCache/blob/main/config/detekt/detekt.yml#L3) |
| `TooManyFunctions` | disabled | [`detekt.yml:5`](https://github.com/Ahoo-Wang/CoCache/blob/main/config/detekt/detekt.yml#L5) |
| `MaxLineLength` | 300 | [`detekt.yml:10`](https://github.com/Ahoo-Wang/CoCache/blob/main/config/detekt/detekt.yml#L10) |
| `ReturnCount` | disabled | [`detekt.yml:12`](https://github.com/Ahoo-Wang/CoCache/blob/main/config/detekt/detekt.yml#L12) |
| `MagicNumber` | disabled | [`detekt.yml:18`](https://github.com/Ahoo-Wang/CoCache/blob/main/config/detekt/detekt.yml#L18) |
| `UnusedPrivateMember` | disabled | [`detekt.yml:15`](https://github.com/Ahoo-Wang/CoCache/blob/main/config/detekt/detekt.yml#L15) |
| `WildcardImport` | allows `java.util.*` | [`detekt.yml:21-24`](https://github.com/Ahoo-Wang/CoCache/blob/main/config/detekt/detekt.yml#L21-L24) |

The `detekt-formatting` plugin (from [`cocache-dependencies`](https://github.com/Ahoo-Wang/CoCache/blob/main/cocache-dependencies/build.gradle.kts)) is also applied to all projects, enforcing consistent code formatting.

### Dokka (API Documentation)

Dokka is applied to all library projects to generate Kotlin/Java API documentation:

```kotlin
// [build.gradle.kts:80](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L80)
apply<DokkaPlugin>()
```

All library modules also generate `javadocJar` and `sourcesJar` for Maven publication:

```kotlin
// [build.gradle.kts:83-86](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L83-L86)
configure<JavaPluginExtension> {
    withJavadocJar()
    withSourcesJar()
}
```

### JaCoCo (Code Coverage)

JaCoCo is applied to all library projects for per-module coverage. The [`code-coverage-report`](https://github.com/Ahoo-Wang/CoCache/blob/main/code-coverage-report/build.gradle.kts) module uses `jacoco-report-aggregation` to produce an aggregated coverage report across all library modules.

```kotlin
// [code-coverage-report/build.gradle.kts:20-26](https://github.com/Ahoo-Wang/CoCache/blob/main/code-coverage-report/build.gradle.kts#L20-L26)
val libraryProjects = rootProject.ext.get("libraryProjects") as Iterable<Project>
dependencies {
    libraryProjects.forEach {
        jacocoAggregation(it)
    }
}
```

A custom Logback configuration ([`config/logback.xml`](https://github.com/Ahoo-Wang/CoCache/blob/main/config/logback.xml)) is injected into all test tasks to ensure JaCoCo captures all logging output correctly:

```kotlin
// [build.gradle.kts:108](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L108)
jvmArgs = listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback.xml")
```

Coverage is enforced twice: the Gradle task `codeCoverageVerification` (part of `check`) fails the build below 95% line or 90% branch coverage, and [`codecov.yml`](https://github.com/Ahoo-Wang/CoCache/blob/main/codecov.yml) requires 95% project and 85% patch coverage on pull requests (1% threshold). `cocache-test` and `cocache-example` are excluded.

## Build Commands

| Command | Purpose | Notes |
|---------|---------|-------|
| `./gradlew build -x test` | Full build without tests | Fast compilation check |
| `./gradlew check` | Full check: tests + Detekt + Dokka + license headers + coverage gate | Needs Redis at `localhost:6379`; what CI runs |
| `./gradlew clean check` | Clean full check | Recommended for CI to ensure reproducibility |
| `./gradlew test` | Run all tests | JUnit 5 via Jupiter engine |
| `./gradlew :cocache-core:test` | Test a specific module | Prefix with `:` for module targeting |
| `./gradlew :cocache-core:test --tests "me.ahoo.cache.proxy.ProxyCacheTest"` | Run a single test class | Full qualified class name |
| `./gradlew detekt` | Run Detekt analysis only | Static analysis without build |
| `./gradlew detektAutoFix` | Run Detekt with auto-fix | Applies safe formatting corrections |
| `./gradlew codeCoverageReport` | Generate aggregated JaCoCo report | Uploaded to Codecov by CI |
| `./gradlew codeCoverageVerification` | Enforce coverage thresholds | ≥ 95% lines, ≥ 90% branches |
| `./gradlew checkLicenseHeader` | Verify Apache-2.0 headers | Part of `check` |
| `./gradlew publishToMavenLocal` | Publish to local Maven repo | For local integration testing |

## Test Configuration

All library modules configure JUnit 5 (Jupiter) as the test platform with full exception logging:

```kotlin
// [build.gradle.kts:102-109](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L102-L109)
tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
    jvmArgs = listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback.xml")
}
```

Test dependencies injected to all library modules:

| Dependency | Purpose | Source |
|------------|---------|--------|
| `junit-jupiter-api` | JUnit 5 test API | [`build.gradle.kts:116`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L116) |
| `junit-jupiter-params` | Parameterized test support | [`build.gradle.kts:117`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L117) |
| `fluent-assert-core` | Fluent assertion DSL for Kotlin | [`build.gradle.kts:118`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L118) |
| `mockk` | Kotlin mocking framework | [`build.gradle.kts:119`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L119) |
| `logback-classic` | Logging implementation for tests | [`build.gradle.kts:115`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L115) |
| `junit-platform-launcher` | JUnit runtime launcher | [`build.gradle.kts:122`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L122) |
| `junit-jupiter-engine` | JUnit test engine | [`build.gradle.kts:123`](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts#L123) |

## CI/CD Pipelines

All workflows live in [`.github/workflows/`](https://github.com/Ahoo-Wang/CoCache/tree/main/.github/workflows). Every workflow declares least-privilege `permissions` and a `timeout-minutes` per job, and caches Gradle through `actions/setup-java` (`cache: gradle`).

```mermaid
graph LR
    PR["Pull request / push to main"] --> CI["ci.yml"]
    CI --> SA["Static Analysis<br>actionlint · Detekt → code scanning · license headers"]
    CI --> TC["Test & Coverage<br>check with Redis · coverage gate · Codecov"]
    PR --> LB["labeler.yml<br>module / type labels"]
    PR -->|wiki/** changed| WK["deploy-wiki.yml<br>build (PR) · deploy (main)"]
    REL["Release published"] --> DEP["package-deploy.yml<br>verify → GitHub Packages + Maven Central"]

    style PR fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CI fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SA fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style TC fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style LB fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style WK fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style REL fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style DEP fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `ci.yml` | Push to `main`, pull request | **Static Analysis**: actionlint, Detekt on every module (merged SARIF uploaded to GitHub code scanning), `checkLicenseHeader`. **Test & Coverage**: `./gradlew check` against a `redis:7-alpine` service (all tests, Dokka, JMH compile, JaCoCo gate ≥ 95% lines / ≥ 90% branches), then Codecov upload. **Test (JDK 25)**: the same tests on the latest LTS runtime (`-PtestJavaVersion=25`; artifacts still compile for JDK 17). Test reports are uploaded as an artifact on failure. Superseded PR runs are cancelled. |
| `labeler.yml` | Pull request (`pull_request_target`, no checkout) | Labels PRs by module, changed paths and branch prefix; labels drive release-note categories (`.github/release.yml`). |
| `deploy-wiki.yml` | `wiki/**` changes | Builds the VitePress site on PRs; builds and deploys to GitHub Pages on `main`. |
| `package-deploy.yml` | Release **published** | Re-runs `clean check`, then publishes signed artifacts to GitHub Packages and Maven Central. One run per tag, never cancelled. |
| `renovate.yml` | Daily | Self-hosted Renovate dependency updates. |
| `gitee-sync.yml` | Push to `main`, `v*` tags, daily | Mirrors the repository to Gitee. |

Fork pull requests skip the two steps that need repository secrets or write access (SARIF upload, Codecov upload) instead of failing.

## Other Configuration

The Gradle wrapper is pinned to Gradle 9.8.1:

```properties
# [gradle-wrapper.properties:3](https://github.com/Ahoo-Wang/CoCache/blob/main/gradle/wrapper/gradle-wrapper.properties#L3)
distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.1-bin.zip
```

The [`settings.gradle.kts`](https://github.com/Ahoo-Wang/CoCache/blob/main/settings.gradle.kts) uses the `foojay-resolver-convention` plugin (v1.0.0) for automatic JDK toolchain resolution.

## Related Pages

- [Contributing Guide](/building/contributing) -- Code style, testing requirements, and PR workflow
- [Publishing & Release](/building/publishing) -- Maven Central publishing and release pipeline
- [Testing](/testing/) -- Test specifications and patterns
- [Architecture](/architecture/) -- System architecture overview
