import { DefaultTheme } from 'vitepress'

const guide: DefaultTheme.SidebarItem[] = [
  {
    text: '快速开始',
    items: [
      { text: '介绍', link: '/zh/guide/' },
      { text: '快速上手', link: '/zh/guide/quick-start' },
      { text: '配置', link: '/zh/guide/configuration' },
    ],
  },
  {
    text: '功能',
    items: [
      { text: 'JoinCache', link: '/zh/guide/join-cache' },
      { text: 'Spring Cache', link: '/zh/guide/spring-cache' },
      { text: '运维', link: '/zh/guide/operations' },
    ],
  },
  {
    text: '版本',
    items: [
      { text: '更新日志', link: '/zh/guide/changelog' },
    ],
  },
]

const architecture: DefaultTheme.SidebarItem[] = [
  {
    text: '架构',
    items: [
      { text: '概览', link: '/zh/architecture/' },
      { text: '一致性', link: '/zh/architecture/consistency' },
      { text: '扩展 CoCache', link: '/zh/architecture/extending' },
    ],
  },
]

export const zh: DefaultTheme.Config = {
  label: '中文',
  lang: 'zh-CN',
  title: 'CoCache',
  description: '二级分布式一致性缓存框架',
  themeConfig: {
    nav: [
      { text: '指南', link: '/zh/guide/' },
      { text: '架构', link: '/zh/architecture/' },
      {
        text: 'v5.0',
        items: [
          { text: '更新日志', link: '/zh/guide/changelog' },
          { text: '贡献指南', link: 'https://github.com/Ahoo-Wang/CoCache/blob/main/CONTRIBUTING.md' },
        ],
      },
    ],
    sidebar: {
      '/zh/guide/': guide,
      '/zh/architecture/': architecture,
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/Ahoo-Wang/CoCache' },
    ],
    footer: {
      message: '基于 Apache License 2.0 发布。',
      copyright: 'Copyright 2022-present Ahoo Wang',
    },
    editLink: {
      pattern: 'https://github.com/Ahoo-Wang/CoCache/edit/main/wiki/:path',
      text: '在 GitHub 上编辑此页面',
    },
    outline: {
      label: '页面导航',
    },
    lastUpdated: {
      text: '最后更新于',
    },
    docFooter: {
      prev: '上一页',
      next: '下一页',
    },
  },
}
