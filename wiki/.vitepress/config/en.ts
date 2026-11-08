import { DefaultTheme } from 'vitepress'

const guide: DefaultTheme.SidebarItem[] = [
  {
    text: 'Getting Started',
    items: [
      { text: 'Why CoCache', link: '/guide/' },
      { text: 'Quick Start', link: '/guide/quick-start' },
      { text: 'How It Works', link: '/guide/concepts' },
    ],
  },
  {
    text: 'Cache Types',
    items: [
      { text: 'Choosing and Declaring', link: '/guide/caches' },
      { text: 'Cache', link: '/guide/cache' },
      { text: 'VersionedCache', link: '/guide/versioned-cache' },
      { text: 'Store', link: '/guide/store' },
      { text: 'VersionedStore', link: '/guide/versioned-store' },
      { text: 'SetStore', link: '/guide/set-store' },
    ],
  },
  {
    text: 'Using CoCache',
    items: [
      { text: 'Keeping Data Fresh', link: '/guide/freshness' },
      { text: 'JoinCache', link: '/guide/join-cache' },
      { text: 'Spring Cache', link: '/guide/spring-cache' },
      { text: 'Async API', link: '/guide/async' },
      { text: 'Kotlin Coroutines', link: '/guide/coroutines' },
      { text: 'Testing', link: '/guide/testing' },
      { text: 'Native Images', link: '/guide/native' },
      { text: 'Configuration Reference', link: '/guide/configuration' },
    ],
  },
  {
    text: 'Releases',
    items: [
      { text: 'Upgrading from 6.x', link: '/guide/upgrade' },
      { text: 'Changelog', link: '/guide/changelog' },
    ],
  },
]

const operations: DefaultTheme.SidebarItem[] = [
  {
    text: 'Operations',
    items: [
      { text: 'Deployment', link: '/operations/' },
      { text: 'Observability', link: '/operations/observability' },
      { text: 'Troubleshooting', link: '/operations/troubleshooting' },
    ],
  },
]

const architecture: DefaultTheme.SidebarItem[] = [
  {
    text: 'Architecture',
    items: [
      { text: 'Overview', link: '/architecture/' },
      { text: 'Consistency', link: '/architecture/consistency' },
      { text: 'Extending CoCache', link: '/architecture/extending' },
    ],
  },
]

export const en: DefaultTheme.Config = {
  label: 'English',
  lang: 'en',
  title: 'CoCache',
  description: 'Level 2 Distributed Coherence Cache Framework',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/' },
      { text: 'Operations', link: '/operations/' },
      { text: 'Architecture', link: '/architecture/' },
      {
        text: 'v8.0.0',
        items: [
          { text: 'Changelog', link: '/guide/changelog' },
          { text: '6.x docs', link: 'https://github.com/Ahoo-Wang/CoCache/tree/6.x/wiki' },
          { text: 'Contributing', link: 'https://github.com/Ahoo-Wang/CoCache/blob/main/CONTRIBUTING.md' },
        ],
      },
    ],
    sidebar: {
      '/guide/': guide,
      '/operations/': operations,
      '/architecture/': architecture,
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/Ahoo-Wang/CoCache' },
    ],
    footer: {
      message: 'Released under the Apache License 2.0.',
      copyright: 'Copyright 2022-present Ahoo Wang',
    },
    editLink: {
      pattern: 'https://github.com/Ahoo-Wang/CoCache/edit/main/wiki/:path',
      text: 'Edit this page on GitHub',
    },
  },
}
