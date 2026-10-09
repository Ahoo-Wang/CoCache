# AGENTS.md — CoCache Wiki (VitePress)

> Generated for the CoCache wiki documentation site.

## Build & Run Commands

```bash
# Install dependencies (use pnpm)
pnpm install

# Development server
pnpm dev

# Build static site
pnpm build

# Preview built site
pnpm preview

# Fix Mermaid syntax issues
pnpm fix:mermaid

# Validate every Mermaid diagram with the real parser (headless Chrome; CI runs this)
pnpm check:mermaid
```

## Project Structure

```
wiki/
├── .vitepress/
│   ├── config/
│   │   ├── index.ts      # Main VitePress config (locales, markdown)
│   │   ├── en.ts          # English sidebar/nav config
│   │   └── zh.ts          # Chinese sidebar/nav config
│   ├── theme/
│   │   ├── index.ts       # Custom theme entry (Mermaid renderer, page-view tracking)
│   │   └── custom.css     # Dark theme styles, Mermaid styling
├── scripts/
│   ├── fix-mermaid.mjs    # Mermaid syntax validator & fixer
│   └── check-mermaid.mjs  # Parses every diagram with mermaid in headless Chrome
├── public/
│   └── logo.svg           # Site logo
├── index.md               # English homepage (VitePress home layout)
├── guide/                 # Getting Started section
├── architecture/          # Architecture deep dives
├── api/                   # API reference
├── modules/               # Per-module documentation
├── testing/               # Testing guides
├── building/              # Build, CI, publishing
├── onboarding/            # Audience-specific onboarding guides
└── zh/                    # Chinese translations (mirrors above structure)
    ├── index.md
    ├── guide/
    ├── architecture/
    ├── api/
    ├── modules/
    ├── testing/
    ├── building/
    └── onboarding/
```

## Content Conventions

- **Frontmatter**: Every page needs `title` and `description`
- **Mermaid diagrams**: Dark-mode colors only — fills `#2d333b`, borders `#6d5dfc`, text `#e6edf3`, subgraph bg `#161b22`, lines `#8b949e`
- **Mermaid breaks**: Use `<br>` NEVER `<br>` (breaks Vue compiler)
- **Sequence diagrams**: Always include `autonumber`
- **Inline styles**: Include `,color:#e6edf3` for dark-mode text
- **Citations**: Linked format `[file_path:line](https://github.com/Ahoo-Wang/CoCache/blob/main/file_path#Lline)`
- **Tables**: Include "Source" column when listing components/APIs
- **Diagrams per page**: Minimum 3-5, using at least 2 different diagram types

## Documentation Files

- `llms.txt` — LLM-friendly project summary with wiki-relative paths
- `llms-full.txt` — Full page content inlined for LLM consumption

## Boundaries

- ✅ DO: Add new pages following the existing structure and conventions
- ✅ DO: Run `pnpm fix:mermaid` and `pnpm check:mermaid` before committing
- 🚫 DON'T: Put `;` in sequence-diagram message or note text — Mermaid treats it as a statement separator
- ✅ DO: Keep English and Chinese versions in sync
- 🚫 DON'T: Delete generated pages without updating the sidebar config
- 🚫 DON'T: Modify theme CSS without testing dark mode rendering
- 🚫 DON'T: Use light-mode colors in Mermaid diagrams
- 🚫 DON'T: Use `<br>` in Mermaid labels
- ⚠️ ASK FIRST: Changing VitePress config or adding new plugins
