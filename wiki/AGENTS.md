# AGENTS.md — CoCache Wiki (VitePress)

The user-facing docs site, published at https://cocache.ahoo.me. English at the root, Chinese mirror under `zh/`.

## Commands

```bash
pnpm install
pnpm dev             # dev server
pnpm build           # production build; fails on dead links
pnpm check:mermaid   # parses every Mermaid diagram in headless Chrome (CI runs this too)
pnpm fix:mermaid     # auto-fixes common Mermaid syntax issues
```

## Structure

```
guide/          for users: introduction, quick-start, configuration, join-cache, spring-cache, operations, changelog
architecture/   for integrators: index (goals, modules, data model, wiring), consistency, extending (SPI + TCK)
zh/             one-to-one Chinese mirror of the above
.vitepress/config/{en,zh}.ts   nav + sidebar (keep the two in step)
```

## What belongs here

The wiki explains **how to use** CoCache and **why it behaves** as it does. Other facts have a single home elsewhere, and the wiki links to them instead of copying them:

| Fact | Single source |
|------|---------------|
| Design invariants (normative) | `docs/architecture.md` |
| Contribution workflow, quality gates, release process | `CONTRIBUTING.md` |
| Build, test, and code-style rules for agents | root `AGENTS.md` |
| API details | KDoc in the source |

## Conventions

- Every page has `title` and `description` frontmatter.
- **Keep en and zh in parity.** Same pages, same sections, same anchors. In zh, give any heading that another page links to an explicit English id, for example `## 存储 {#stores}`, so links work in both languages.
- **Verify every claim against the code**, including defaults, bean names, and behavior. A wrong doc is worse than a missing one.
- **Don't cite line numbers.** Link to files or directories. Line anchors rot with every commit.
- **Add a diagram only when it explains a mechanism** that prose explains worse, such as a read path or a sequence. Don't add decorative diagrams. The theme styles Mermaid globally, so don't add per-node `style` lines.
- Mermaid: use `<br>` for line breaks, and never `;` in sequence-diagram message text (Mermaid reads it as a statement separator).
- Write any text containing `<…>`, such as `Cache<K, V>`, in backticks. Otherwise Vue parses it as HTML.
- The current version appears only in `guide/quick-start.md` and `guide/changelog.md` (both languages), the root `README.md`, and the `v5.0` nav label. The release steps are in `CONTRIBUTING.md`.
