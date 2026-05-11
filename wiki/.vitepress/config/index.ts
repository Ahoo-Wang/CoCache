import { defineConfig } from 'vitepress'
import { en } from './en'
import { zh } from './zh'

export default defineConfig({
  title: 'CoCache',
  description: 'Level 2 Distributed Coherence Cache Framework',
  lastUpdated: true,
  cleanUrls: true,
  ignoreDeadLinks: [
    /localhost/,
  ],
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
  ],
  locales: {
    root: {
      ...en,
    },
    zh: {
      ...zh,
    },
  },
  markdown: {
    lineNumbers: true,
  },
  vite: {
    plugins: [],
  },
})
