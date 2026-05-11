# AGENTS.md — CoCache Root

## Build & Run Commands

```bash
# Build without tests
./gradlew build -x test

# Full check (tests + detekt + dokka)
./gradlew check

# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :cocache-core:test
./gradlew :cocache-spring:test
./gradlew :cocache-spring-redis:test
./gradlew :cocache-spring-boot-starter:test

# Run detekt (code quality)
./gradlew detekt

# Run detekt with auto-fix
./gradlew detektAutoFix

# Run a single test class
./gradlew :cocache-core:test --tests "me.ahoo.cache.proxy.ProxyCacheTest"

# Integration tests (requires Redis)
./gradlew :cocache-spring-redis:check
./gradlew :cocache-spring-boot-starter:check

# Publish to local Maven
./gradlew publishToMavenLocal

# Wiki (VitePress)
cd wiki && pnpm install && pnpm dev
```

## Project Structure

```
cocache-api          — Core interfaces (Cache, CacheValue, ClientSideCache, CacheSource)
cocache-core         — Default implementations (DefaultCoherentCache, proxy-based caching)
cocache-spring       — Spring integration (@EnableCoCache, factory beans)
cocache-spring-redis — Redis distributed cache implementation
cocache-spring-cache — Spring Cache abstraction bridge
cocache-spring-boot-starter — Auto-configuration for Spring Boot
cocache-test         — Shared test specs (CacheSpec, DistributedCacheSpec, etc.)
cocache-example      — Example application
cocache-bom          — Bill of Materials
cocache-dependencies — Centralized version catalog
code-coverage-report — Aggregated JaCoCo coverage
wiki/                — VitePress documentation site
```

## Testing

- **Framework**: JUnit 5 (Jupiter) with mockk and fluent-assert
- **Fluent assert**: `import me.ahoo.test.asserts.assert` then `.assert()` — NEVER use AssertJ `assertThat()`
- **TCK specs** in `cocache-test`: extend `CacheSpec`, `ClientSideCacheSpec`, `DistributedCacheSpec`, `DefaultCoherentCacheSpec`, `MultipleInstanceSyncSpec`, `CacheEvictedEventBusSpec`
- **Integration tests** require Redis (CI uses service container)
- **Logback** configured via `config/logback.xml` for tests

## Code Style

- **Detekt** config at `config/detekt/detekt.yml`
- Key overrides: `LongParameterList`, `TooManyFunctions`, `ReturnCount`, `MagicNumber`, `UnusedPrivateMember` disabled
- `MaxLineLength` = 300; `WildcardImport` allowed for `java.util.*`
- Kotlin compiler: `-Xjsr305=strict`, `-Xjvm-default=all-compatibility`
- Java compiler: `-parameters`

## Git Workflow

- **Main branch**: `main`
- **CI**: GitHub Actions — integration-test.yml, codecov.yml, package-deploy.yml, codeql-analysis.yml
- **Commits**: Conventional format (e.g., `feat(scope):`, `fix(scope):`, `docs(scope):`)

## Boundaries

- ✅ Always: Run `./gradlew check` before committing
- ✅ Always: Use fluent-assert `.assert()` in Kotlin tests
- ✅ Always: Follow Detekt rules
- ✅ Always: Extend TCK specs for new cache implementations
- ⚠️ Ask first: Adding new dependencies to version catalog
- ⚠️ Ask first: Modifying cocache-api interfaces (breaking change risk)
- 🚫 Never: Use AssertJ `assertThat()` in Kotlin tests
- 🚫 Never: Commit without running tests
- 🚫 Never: Push directly to main
