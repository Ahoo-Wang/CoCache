# Contributing to CoCache

Thanks for helping improve CoCache. This file is the short version of the process. The
[Contributor Guide](https://cocache.ahoo.me/onboarding/contributor) explains the architecture and its
invariants. Read [`docs/architecture.md`](docs/architecture.md) before changing coherence, storage,
codec, or proxy code.

## Requirements and issues

- Every change starts from an issue. Use the **Bug Report** or **Feature Request** form. A feature
  request must state its acceptance criteria.
- Report security vulnerabilities privately. See [SECURITY.md](SECURITY.md).

## Branching model

- `main` is always releasable and protected. Changes land only through pull requests that pass every
  required check.
- Work on a short-lived branch from `main`, named `<type>/<short-description>`, for example
  `fix/stale-l2-fill` or `feat/cluster-codec`. `<type>` is a Conventional Commits type (`feat`, `fix`,
  `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`). The PR labeler uses it to label the PR,
  and the labels drive the release notes.
- Pull requests are **squash-merged**. The PR title becomes the commit message, so it must follow
  [Conventional Commits](https://www.conventionalcommits.org/). Mark breaking changes with `!`, as in
  `refactor!: ...`.

## Code review

- `CODEOWNERS` requests a maintainer review on every PR.
- Reviewers check correctness first, especially concurrency and coherence invariants. Then they check
  tests, documentation, and API compatibility.

## Quality gates

`./gradlew check` must pass before you push. The `CI` workflow (`.github/workflows/ci.yml`) enforces the same gates, and `actionlint` checks workflow changes:

| Gate | Tooling | Where |
|------|---------|-------|
| Coding conventions and static analysis | Detekt + detekt-formatting (`config/detekt/detekt.yml`). Findings are uploaded to GitHub code scanning | CI · Static Analysis |
| License headers | `checkLicenseHeader` (Apache-2.0 header in every source file and build script) | CI · Static Analysis, `check` |
| Unit tests | JUnit 5, MockK, fluent-assert, shared TCK specs in `cocache-test` | CI · Test & Coverage |
| Integration tests | Real Redis service container (`cocache-spring-redis`, `cocache-spring-boot-starter`) | CI · Test & Coverage |
| Coverage | JaCoCo aggregate gate (lines ≥ 90%, branches ≥ 80%) and Codecov status (project ≥ 90%, patch ≥ 80%) | CI · Test & Coverage |
| Performance (on demand) | JMH `RedisCacheBenchmark` (`./gradlew :cocache-spring-redis:jmh`) | Run locally when touching the hit path |

Integration tests need Redis at `localhost:6379`:

```bash
docker run -d --name cocache-redis -p 6379:6379 redis:7-alpine
./gradlew check
```

Every fixed defect gets a reproducing test. Orchestrate races with latches, never with sleeps.

## Versioning and releases

- CoCache follows [Semantic Versioning](https://semver.org/). Breaking API or behavior changes bump
  MAJOR. Backward-compatible features bump MINOR. Fixes bump PATCH. The Redis wire format changes only
  in a MAJOR version.
- `version` in `gradle.properties` is the next release version.
- To release:
  1. Update the wiki changelog (en + zh), including a migration table for MAJOR versions.
  2. Publish a GitHub Release tagged `vX.Y.Z`.

  The `Packages Deploy` workflow re-runs `clean check` and publishes to Maven Central and GitHub
  Packages. Release notes are grouped by PR labels (`.github/release.yml`).

## Licensing

CoCache is licensed under the [Apache License 2.0](LICENSE). By contributing, you agree that your
contributions are licensed under the same terms. Every source file carries the Apache-2.0 header, and
`checkLicenseHeader` enforces it.
