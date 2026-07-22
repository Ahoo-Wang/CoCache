---
title: Contributing
description: How to contribute to the CoCache project.
---

# Contributing

We welcome contributions to CoCache! Here's how to get started.

## Quick Start

```bash
# Clone the repository
git clone https://github.com/Ahoo-Wang/CoCache.git
cd CoCache

# Build without tests
./gradlew build -x test

# Run full check (tests + detekt)
./gradlew check
```

## Development Setup

- **JDK 17+** required (configured via `jvmToolchain` in [build.gradle.kts](https://github.com/Ahoo-Wang/CoCache/blob/main/build.gradle.kts))
- **Gradle 9.6.1** (wrapper included)
- **Kotlin 2.4.0** with `-Xjsr305=strict` and `-Xjvm-default=all-compatibility`

## Code Style

- **Detekt** config at `config/detekt/detekt.yml`
- Run `./gradlew detekt` to check, `./gradlew detektAutoFix` to auto-fix
- Key rules: `MaxLineLength` = 300, wildcard imports allowed for `java.util.*`

## Testing

- JUnit 5 with **mockk** and **fluent-assert**
- Use `.assert()` extension — never AssertJ's `assertThat()` in Kotlin tests
- New cache implementations should extend TCK specs in `cocache-test`

## Pull Request Process

1. Fork the repository
2. Create a feature branch from `main`
3. Ensure `./gradlew check` passes
4. Open a PR with a clear description of changes

## Reporting Issues

Please use [GitHub Issues](https://github.com/Ahoo-Wang/CoCache/issues) to report bugs or request features.

---

For more details, see [Contributing Guide](/building/contributing).
