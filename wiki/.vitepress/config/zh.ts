import { DefaultTheme } from 'vitepress'

export const zh: DefaultTheme.Config = {
  label: '中文',
  lang: 'zh-CN',
  title: 'CoCache',
  description: '二级分布式一致性缓存框架',
  themeConfig: {
    nav: [
      { text: '指南', link: '/zh/guide/' },
      { text: '架构', link: '/zh/architecture/' },
      { text: 'API', link: '/zh/api/' },
      { text: '模块', link: '/zh/modules/' },
      { text: '入门指南', link: '/zh/onboarding/' },
      {
        text: 'v4.2',
        items: [
          { text: '更新日志', link: '/zh/guide/changelog' },
          { text: '贡献指南', link: '/zh/guide/contributing' },
        ],
      },
    ],
    sidebar: {
      '/zh/guide/': [
        {
          text: '快速开始',
          items: [
            { text: '介绍', link: '/zh/guide/' },
            { text: '快速上手', link: '/zh/guide/quick-start' },
            { text: '配置', link: '/zh/guide/configuration' },
          ],
        },
      ],
      '/zh/architecture/': [
        {
          text: '架构',
          items: [
            { text: '概览', link: '/zh/architecture/' },
            { text: '缓存层级', link: '/zh/architecture/cache-layers' },
            { text: '一致性与事件总线', link: '/zh/architecture/coherence' },
            { text: '代理与注解', link: '/zh/architecture/proxy' },
          ],
        },
      ],
      '/zh/api/': [
        {
          text: 'API 参考',
          items: [
            { text: '概览', link: '/zh/api/' },
            { text: '核心接口', link: '/zh/api/core-interfaces' },
            { text: '注解', link: '/zh/api/annotations' },
            { text: 'Spring 集成', link: '/zh/api/spring-integration' },
            { text: 'Actuator 端点', link: '/zh/api/actuator' },
          ],
        },
      ],
      '/zh/modules/': [
        {
          text: '模块',
          items: [
            { text: '概览', link: '/zh/modules/' },
            { text: 'cocache-api', link: '/zh/modules/cocache-api' },
            { text: 'cocache-core', link: '/zh/modules/cocache-core' },
            { text: 'cocache-spring', link: '/zh/modules/cocache-spring' },
            { text: 'cocache-spring-redis', link: '/zh/modules/cocache-spring-redis' },
            { text: 'cocache-spring-boot-starter', link: '/zh/modules/cocache-spring-boot-starter' },
            { text: 'cocache-spring-cache', link: '/zh/modules/cocache-spring-cache' },
          ],
        },
      ],
      '/zh/testing/': [
        {
          text: '测试',
          items: [
            { text: '概览', link: '/zh/testing/' },
            { text: '单元测试', link: '/zh/testing/unit-testing' },
            { text: '集成测试', link: '/zh/testing/integration-testing' },
            { text: '性能模式', link: '/zh/testing/performance-patterns' },
          ],
        },
      ],
      '/zh/building/': [
        {
          text: '构建',
          items: [
            { text: '构建与 CI', link: '/zh/building/' },
            { text: '贡献指南', link: '/zh/building/contributing' },
            { text: '发布', link: '/zh/building/publishing' },
          ],
        },
      ],
      '/zh/onboarding/': [
        {
          text: '入门指南',
          collapsed: false,
          items: [
            { text: '概览', link: '/zh/onboarding/' },
            { text: '贡献者指南', link: '/zh/onboarding/contributor' },
            { text: '高级工程师指南', link: '/zh/onboarding/staff-engineer' },
            { text: '管理层指南', link: '/zh/onboarding/executive' },
            { text: '产品经理指南', link: '/zh/onboarding/product-manager' },
          ],
        },
      ],
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
