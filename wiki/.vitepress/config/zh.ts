import { DefaultTheme } from 'vitepress'

const guide: DefaultTheme.SidebarItem[] = [
  {
    text: '入门',
    items: [
      { text: '为什么用 CoCache', link: '/zh/guide/' },
      { text: '快速上手', link: '/zh/guide/quick-start' },
      { text: '工作原理', link: '/zh/guide/concepts' },
    ],
  },
  {
    text: '使用',
    items: [
      { text: '声明缓存', link: '/zh/guide/caches' },
      { text: '保持数据新鲜', link: '/zh/guide/freshness' },
      { text: 'JoinCache', link: '/zh/guide/join-cache' },
      { text: 'Spring Cache', link: '/zh/guide/spring-cache' },
      { text: '测试', link: '/zh/guide/testing' },
      { text: '原生镜像', link: '/zh/guide/native' },
      { text: '配置参考', link: '/zh/guide/configuration' },
    ],
  },
  {
    text: '版本',
    items: [
      { text: '从 5.x 升级', link: '/zh/guide/migration-6' },
      { text: '更新日志', link: '/zh/guide/changelog' },
    ],
  },
]

const operations: DefaultTheme.SidebarItem[] = [
  {
    text: '运维',
    items: [
      { text: '部署', link: '/zh/operations/' },
      { text: '可观测性', link: '/zh/operations/observability' },
      { text: '故障排查', link: '/zh/operations/troubleshooting' },
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
      { text: '运维', link: '/zh/operations/' },
      { text: '架构', link: '/zh/architecture/' },
      {
        text: 'v6.1',
        items: [
          { text: '更新日志', link: '/zh/guide/changelog' },
          { text: '贡献指南', link: 'https://github.com/Ahoo-Wang/CoCache/blob/main/CONTRIBUTING.md' },
        ],
      },
    ],
    sidebar: {
      '/zh/guide/': guide,
      '/zh/operations/': operations,
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
