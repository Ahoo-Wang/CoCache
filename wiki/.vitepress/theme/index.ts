import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import { useRouter } from 'vitepress'
import { defineAsyncComponent } from 'vue'
import './custom.css'

export default {
  extends: DefaultTheme,
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
