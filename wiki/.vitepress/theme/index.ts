import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import { useRouter } from 'vitepress'
import { defineAsyncComponent, h } from 'vue'
import HeroAnimation from './HeroAnimation.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  // 首页右侧的动效：读未命中只回源一次、写入后各实例的本地副本失效
  Layout: () => h(DefaultTheme.Layout, null, {
    'home-hero-image': () => h(HeroAnimation),
  }),
  enhanceApp({ app }) {
    // 与 Wow 文档一致：vitepress-plugin-mermaid 把 ```mermaid 代码块转换为 <Mermaid>，组件负责渲染、明暗主题与缩放
    app.component('Mermaid', defineAsyncComponent(() => import('./Mermaid.vue')))
  },
  setup() {
    if (typeof window !== 'undefined') {
      const router = useRouter()
      router.onAfterRouteChanged = (to: string) => {
        ;(window as any).gtag?.('event', 'page_view', { page_path: to })
      }
    }
  },
} satisfies Theme
