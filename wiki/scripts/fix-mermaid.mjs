#!/usr/bin/env node

/**
 * Automated Mermaid syntax validator and fixer for CoCache wiki.
 *
 * Fixes:
 * 1. Replace `<br/>` with `<br>` (Vue compiler compatibility)
 * 2. Replace light-mode inline styles with dark-mode equivalents
 * 3. Add `autonumber` to sequenceDiagram blocks, remove duplicates
 * 4. Remove `style "quoted name"` directives on subgraphs (parse errors)
 * 5. Remove `linkStyle` directives (index out of bounds errors)
 * 6. Remove `style` directives inside sequenceDiagram blocks (not supported)
 * 7. Clean up blank lines left by removals
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

function fixMermaidBlocks(content) {
  let issues = []

  // Process each mermaid block individually
  const result = content.replace(/```mermaid\n([\s\S]*?)```/g, (match, block) => {
    let fixed = block
    const blockType = fixed.trim().split('\n')[0].trim()

    // Fix: Replace <br/> with <br>
    const brSlashCount = (fixed.match(/<br\/>/g) || []).length
    if (brSlashCount > 0) {
      fixed = fixed.replace(/<br\/>/g, '<br>')
      issues.push(`Replaced ${brSlashCount}x <br/> with <br>`)
    }

    // Fix: Remove style "quoted name" directives (causes parse errors in all diagram types)
    const quotedStyleRegex = /^\s*style\s+"[^"]+"\s+fill:.*$/gm
    const quotedStyleCount = (fixed.match(quotedStyleRegex) || []).length
    if (quotedStyleCount > 0) {
      fixed = fixed.replace(quotedStyleRegex, '')
      issues.push(`Removed ${quotedStyleCount}x style "quoted name" directives`)
    }

    // Fix: Remove ALL style directives from sequenceDiagram (not supported)
    if (blockType === 'sequenceDiagram') {
      const styleRegex = /^\s*style\s+\S+\s+fill:.*$/gm
      const styleCount = (fixed.match(styleRegex) || []).length
      if (styleCount > 0) {
        fixed = fixed.replace(styleRegex, '')
        issues.push(`Removed ${styleCount}x style directives from sequenceDiagram`)
      }

      // Fix: Remove duplicate autonumber in sequenceDiagram
      fixed = fixed.replace(/^(autonumber\n)(autonumber\n)+/gm, '$1')

      // Fix: Add autonumber if missing
      if (!fixed.includes('autonumber')) {
        fixed = 'autonumber\n' + fixed
        issues.push('Added autonumber to sequenceDiagram')
      }
    }

    // Fix: Remove linkStyle directives (causes index errors in all diagram types)
    const linkStyleRegex = /^\s*linkStyle\s+.*$/gm
    const linkStyleCount = (fixed.match(linkStyleRegex) || []).length
    if (linkStyleCount > 0) {
      fixed = fixed.replace(linkStyleRegex, '')
      issues.push(`Removed ${linkStyleCount}x linkStyle directives`)
    }

    // Clean up excessive blank lines
    fixed = fixed.replace(/\n{3,}/g, '\n\n')

    return '```mermaid\n' + fixed + '```'
  })

  // Fix: Fix light-mode colors globally
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

  let finalResult = result
  for (const [light, dark] of Object.entries(lightColorMap)) {
    const count = (finalResult.match(new RegExp(light, 'gi')) || []).length
    if (count > 0) {
      finalResult = finalResult.replace(new RegExp(light, 'gi'), dark)
      issues.push(`Replaced ${count}x ${light} with ${dark}`)
    }
  }

  return { fixed: finalResult, issues }
}

// Main execution
const wikiDir = join(import.meta.dirname, '..')
const files = walkDir(wikiDir)

let totalIssues = 0
let filesFixed = 0
let hasWarnings = false

for (const file of files) {
  const content = readFileSync(file, 'utf-8')
  const { fixed, issues } = fixMermaidBlocks(content)

  if (issues.length > 0) {
    console.log(`\n${file.replace(wikiDir, '.')}:`)
    for (const issue of issues) {
      console.log(`  - ${issue}`)
    }
    writeFileSync(file, fixed, 'utf-8')
    filesFixed++
    totalIssues += issues.length
  }
}

console.log(`\n--- Summary ---`)
console.log(`Files scanned: ${files.length}`)
console.log(`Files fixed: ${filesFixed}`)
console.log(`Issues found and fixed: ${totalIssues}`)

if (totalIssues === 0) {
  console.log('All Mermaid blocks look good!')
}

process.exit(0)
