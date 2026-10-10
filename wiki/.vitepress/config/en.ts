import { DefaultTheme } from 'vitepress'

const guide: DefaultTheme.SidebarItem[] = [
  {
    text: 'Getting Started',
    items: [
      { text: 'Introduction', link: '/guide/' },
      { text: 'Quick Start', link: '/guide/quick-start' },
      { text: 'Configuration', link: '/guide/configuration' },
    ],
  },
  {
    text: 'Features',
    items: [
      { text: 'JoinCache', link: '/guide/join-cache' },
      { text: 'Spring Cache', link: '/guide/spring-cache' },
      { text: 'Operations', link: '/guide/operations' },
    ],
  },
  {
    text: 'Releases',
    items: [
      { text: 'Changelog', link: '/guide/changelog' },
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
      { text: 'Architecture', link: '/architecture/' },
      {
        text: 'v5.0',
        items: [
          { text: 'Changelog', link: '/guide/changelog' },
          { text: 'Contributing', link: 'https://github.com/Ahoo-Wang/CoCache/blob/main/CONTRIBUTING.md' },
        ],
      },
    ],
    sidebar: {
      '/guide/': guide,
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
