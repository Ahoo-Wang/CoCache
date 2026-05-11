import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import './custom.css'

const hasSetup = Symbol('mermaid-setup')

export default {
  extends: DefaultTheme,
  setup() {
    if (typeof window !== 'undefined' && !(window as any)[hasSetup]) {
      ;(window as any)[hasSetup] = true
      import('vitepress-mermaid-renderer').then(({ createMermaidRenderer }) => {
        const isDark = document.documentElement.classList.contains('dark')
        createMermaidRenderer({
          theme: isDark ? 'dark' : 'default',
          startOnLoad: true,
          flowchart: { useMaxWidth: true, htmlLabels: true },
          sequence: { diagramMarginX: 50, diagramMarginY: 10 },
          themeVariables: {
            primaryColor: '#2d333b',
            primaryTextColor: '#e6edf3',
            primaryBorderColor: '#6d5dfc',
            lineColor: '#8b949e',
            secondaryColor: '#161b22',
            tertiaryColor: '#21262d',
            fontFamily: 'Inter, sans-serif',
            fontSize: '14px',
          },
        })
      })
    }
  },
} satisfies Theme
