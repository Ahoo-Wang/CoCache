## What & Why

<!-- What does this change do, and why? Link the issue: Closes #123 -->

## How

<!-- Key design decisions. If coherence, storage, codec or proxy behavior changes, update docs/architecture.md. -->

## Checklist

- [ ] PR title follows [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `perf:`, `refactor:`, `docs:`, `chore:` …; `!` for breaking changes)
- [ ] `./gradlew check` passes locally (tests + detekt + license headers + coverage gate)
- [ ] Every fixed defect has a reproducing test; new behavior is covered by tests
- [ ] Docs updated (`docs/architecture.md`, wiki en + zh) when behavior or API changes
- [ ] Breaking changes are described in the wiki changelog migration table
