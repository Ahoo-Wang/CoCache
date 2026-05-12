#!/usr/bin/env node

/**
 * Automated Mermaid syntax validator and fixer for CoCache wiki.
 *
 * Fixes:
 * 1. Replace `<br/>` with `<br>` (Vue compiler compatibility)
 * 2. Replace light-mode inline styles with dark-mode equivalents
 * 3. Add `autonumber` to sequenceDiagram blocks
 * 4. Remove `style "quoted name"` directives on subgraphs (causes parse errors)
 * 5. Remove `linkStyle N` and `linkStyle default` directives (causes index errors)
 * 6. Remove duplicate `autonumber` lines
 * 7. Validate basic Mermaid syntax
 */

import { readFileSync, writeFileSync, readdirSync, statSync } from 'fs'
import { join, extname } from 'path'

const MD_EXTENSIONS = new Set(['.md'])

function walkDir(dir) {
  const files = []
  for (const entry of readdirSync(dir)) {
    const fullPath = join(dir, entry)
    const stat = statSync(fullPath)
    if (stat.isDirectory() && !entry.startsWith('.') && entry !== 'node_modules') {
      files.push(...walkDir(fullPath))
    } else if (MD_EXTENSIONS.has(extname(entry))) {
      files.push(fullPath)
    }
  }
  return files
}

function fixMermaidBlocks(content, filePath) {
  let fixed = content
  let issues = []

  // Fix 1: Replace <br/> with <br>
  const brSlashCount = (fixed.match(/<br\/>/g) || []).length
  if (brSlashCount > 0) {
    fixed = fixed.replace(/<br\/>/g, '<br>')
    issues.push(`Replaced ${brSlashCount}x <br/> with <br>`)
  }

  // Fix 2: Fix light-mode colors in inline styles
  const lightColorMap = {
    'fill:#fff': 'fill:#2d333b',
    'fill:#ffffff': 'fill:#2d333b',
    'fill:white': 'fill:#2d333b',
    'stroke:#000': 'stroke:#6d5dfc',
    'stroke:#333': 'stroke:#8b949e',
    'color:#000': 'color:#e6edf3',
    'color:#333': 'color:#e6edf3',
    'fill:#f9f9f9': 'fill:#161b22',
    'fill:#fafafa': 'fill:#161b22',
    'fill:#f5f5f5': 'fill:#21262d',
  }

  for (const [light, dark] of Object.entries(lightColorMap)) {
    const count = (fixed.match(new RegExp(light, 'gi')) || []).length
    if (count > 0) {
      fixed = fixed.replace(new RegExp(light, 'gi'), dark)
      issues.push(`Replaced ${count}x ${light} with ${dark}`)
    }
  }

  // Fix 3: Add autonumber to sequenceDiagram blocks (skip if already has it)
  const seqDiagramRegex = /```mermaid\n(sequenceDiagram)\n(?!autonumber)/g
  const seqCount = (fixed.match(seqDiagramRegex) || []).length
  if (seqCount > 0) {
    fixed = fixed.replace(seqDiagramRegex, '```mermaid\n$1\nautonumber\n')
    issues.push(`Added autonumber to ${seqCount} sequenceDiagram blocks`)
  }

  // Fix 4: Remove style directives on quoted subgraph names (causes parse errors)
  // Pattern: style "some name" fill:... (with double quotes around name containing spaces/special chars)
  const quotedStyleRegex = /^\s*style\s+"[^"]+"\s+fill:.*$/gm
  const quotedStyleCount = (fixed.match(quotedStyleRegex) || []).length
  if (quotedStyleCount > 0) {
    fixed = fixed.replace(quotedStyleRegex, '')
    issues.push(`Removed ${quotedStyleCount}x style "quoted name" directives (causes parse errors)`)
  }

  // Fix 5: Remove all linkStyle directives (causes index out of bounds errors)
  const linkStyleRegex = /^\s*linkStyle\s+.*$/gm
  const linkStyleCount = (fixed.match(linkStyleRegex) || []).length
  if (linkStyleCount > 0) {
    fixed = fixed.replace(linkStyleRegex, '')
    issues.push(`Removed ${linkStyleCount}x linkStyle directives`)
  }

  // Fix 6: Remove duplicate autonumber lines
  const dupAutonumberRegex = /\nautonumber\nautonumber/g
  const dupCount = (fixed.match(dupAutonumberRegex) || []).length
  if (dupCount > 0) {
    fixed = fixed.replace(dupAutonumberRegex, '\nautonumber')
    issues.push(`Removed ${dupCount}x duplicate autonumber lines`)
  }

  // Fix 7: Clean up excessive blank lines left by removals
  fixed = fixed.replace(/\n{4,}/g, '\n\n\n')

  // Validation: Check for remaining problematic patterns
  const mermaidBlockRegex = /```mermaid\n([\s\S]*?)```/g
  let match
  while ((match = mermaidBlockRegex.exec(fixed)) !== null) {
    const block = match[1]

    // Check for style with quoted names (should be removed by fix 4)
    if (/style\s+"[^"]+"/.test(block)) {
      issues.push(`WARN: Remaining style "quoted name" in Mermaid block`)
    }

    // Check for linkStyle (should be removed by fix 5)
    if (/linkStyle/.test(block)) {
      issues.push(`WARN: Remaining linkStyle in Mermaid block`)
    }

    // Check for unmatched brackets
    const openBrackets = (block.match(/\{/g) || []).length
    const closeBrackets = (block.match(/\}/g) || []).length
    if (openBrackets !== closeBrackets) {
      issues.push(`WARN: Unmatched curly braces (open: ${openBrackets}, close: ${closeBrackets})`)
    }
  }

  return { fixed, issues }
}

// Main execution
const wikiDir = join(import.meta.dirname, '..')
const files = walkDir(wikiDir)

let totalIssues = 0
let filesFixed = 0
let hasErrors = false

for (const file of files) {
  const content = readFileSync(file, 'utf-8')
  const { fixed, issues } = fixMermaidBlocks(content, file)

  if (issues.length > 0) {
    console.log(`\n${file.replace(wikiDir, '.')}:`)
    for (const issue of issues) {
      const isWarn = issue.startsWith('WARN:')
      if (isWarn) hasErrors = true
      console.log(`  - ${issue}`)
    }
    writeFileSync(file, fixed, 'utf-8')
    filesFixed++
    totalIssues += issues.filter(i => !i.startsWith('WARN:')).length
  }
}

console.log(`\n--- Summary ---`)
console.log(`Files scanned: ${files.length}`)
console.log(`Files fixed: ${filesFixed}`)
console.log(`Issues found and fixed: ${totalIssues}`)

if (hasErrors) {
  console.log('\nSome warnings remain — manual review needed.')
  process.exit(1)
} else if (totalIssues === 0) {
  console.log('All Mermaid blocks look good!')
}
