#!/usr/bin/env node

/**
 * Automated Mermaid syntax validator and fixer for CoCache wiki.
 *
 * Fixes:
 * 1. Replace `<br/>` with `<br>` (Vue compiler compatibility)
 * 2. Replace light-mode inline styles with dark-mode equivalents
 * 3. Add `autonumber` to sequenceDiagram blocks
 * 4. Validate basic Mermaid syntax
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

  // Fix 3: Add autonumber to sequenceDiagram blocks
  const seqDiagramRegex = /```mermaid\n(sequenceDiagram)\n(?!autonumber)/g
  const seqCount = (fixed.match(seqDiagramRegex) || []).length
  if (seqCount > 0) {
    fixed = fixed.replace(seqDiagramRegex, '```mermaid\n$1\nautonumber\n')
    issues.push(`Added autonumber to ${seqCount} sequenceDiagram blocks`)
  }

  // Fix 4: Check for unmatched brackets in Mermaid blocks
  const mermaidBlockRegex = /```mermaid\n([\s\S]*?)```/g
  let match
  while ((match = mermaidBlockRegex.exec(content)) !== null) {
    const block = match[1]
    const openBrackets = (block.match(/\{/g) || []).length
    const closeBrackets = (block.match(/\}/g) || []).length
    if (openBrackets !== closeBrackets) {
      issues.push(`WARN: Unmatched curly braces in Mermaid block (open: ${openBrackets}, close: ${closeBrackets})`)
    }

    // Check for unsupported characters in node labels
    const nodeLabelRegex = /\[([^\]]*)\]/g
    let labelMatch
    while ((labelMatch = nodeLabelRegex.exec(block)) !== null) {
      const label = labelMatch[1]
      if (label.includes('<') && !label.includes('<br>')) {
        issues.push(`WARN: Possible HTML tag in node label: "${label}"`)
      }
    }
  }

  return { fixed, issues }
}

// Main execution
const wikiDir = join(import.meta.dirname, '..')
const files = walkDir(wikiDir)

let totalIssues = 0
let filesFixed = 0

for (const file of files) {
  const content = readFileSync(file, 'utf-8')
  const { fixed, issues } = fixMermaidBlocks(content, file)

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
