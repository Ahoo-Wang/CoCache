import { DefaultTheme } from 'vitepress'

export const en: DefaultTheme.Config = {
  label: 'English',
  lang: 'en',
  title: 'CoCache',
  description: 'Level 2 Distributed Coherence Cache Framework',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/' },
      { text: 'Architecture', link: '/architecture/' },
      { text: 'API', link: '/api/' },
      { text: 'Modules', link: '/modules/' },
      { text: 'Onboarding', link: '/onboarding/' },
      {
        text: 'v4.2',
        items: [
          { text: 'Changelog', link: '/guide/changelog' },
          { text: 'Contributing', link: '/guide/contributing' },
        ],
      },
    ],
    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/' },
            { text: 'Quick Start', link: '/guide/quick-start' },
            { text: 'Configuration', link: '/guide/configuration' },
          ],
        },
      ],
      '/architecture/': [
        {
          text: 'Architecture',
          items: [
            { text: 'Overview', link: '/architecture/' },
            { text: 'Cache Layers', link: '/architecture/cache-layers' },
            { text: 'Coherence & Event Bus', link: '/architecture/coherence' },
            { text: 'Proxy & Annotations', link: '/architecture/proxy' },
          ],
        },
      ],
      '/api/': [
        {
          text: 'API Reference',
          items: [
            { text: 'Overview', link: '/api/' },
            { text: 'Core Interfaces', link: '/api/core-interfaces' },
            { text: 'Annotations', link: '/api/annotations' },
            { text: 'Spring Integration', link: '/api/spring-integration' },
            { text: 'Actuator Endpoints', link: '/api/actuator' },
          ],
        },
      ],
      '/modules/': [
        {
          text: 'Modules',
          items: [
            { text: 'Overview', link: '/modules/' },
            { text: 'cocache-api', link: '/modules/cocache-api' },
            { text: 'cocache-core', link: '/modules/cocache-core' },
            { text: 'cocache-spring', link: '/modules/cocache-spring' },
            { text: 'cocache-spring-redis', link: '/modules/cocache-spring-redis' },
            { text: 'cocache-spring-boot-starter', link: '/modules/cocache-spring-boot-starter' },
            { text: 'cocache-spring-cache', link: '/modules/cocache-spring-cache' },
          ],
        },
      ],
      '/testing/': [
        {
          text: 'Testing',
          items: [
            { text: 'Overview', link: '/testing/' },
            { text: 'Unit Testing', link: '/testing/unit-testing' },
            { text: 'Integration Testing', link: '/testing/integration-testing' },
            { text: 'Performance Patterns', link: '/testing/performance-patterns' },
          ],
        },
      ],
      '/building/': [
        {
          text: 'Building',
          items: [
            { text: 'Build & CI', link: '/building/' },
            { text: 'Contributing', link: '/building/contributing' },
            { text: 'Publishing', link: '/building/publishing' },
          ],
        },
      ],
      '/onboarding/': [
        {
          text: 'Onboarding',
          collapsed: false,
          items: [
            { text: 'Overview', link: '/onboarding/' },
            { text: 'Contributor Guide', link: '/onboarding/contributor' },
            { text: 'Staff Engineer Guide', link: '/onboarding/staff-engineer' },
            { text: 'Executive Guide', link: '/onboarding/executive' },
            { text: 'Product Manager Guide', link: '/onboarding/product-manager' },
          ],
        },
      ],
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
