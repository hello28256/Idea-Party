import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'

// BUILD-MARKER-2026-06-26-A: 如果你在 DevTools Console 看到这行，说明这个 tab 加载的是新代码。
console.log('%c[IDEA-PARTY] BUILD 2026-06-26-A 已加载 ✅', 'color:#16a34a;font-weight:bold;font-size:14px')

const app = createApp(App)

// Vue 应用初始化顺序固定为：Pinia → Router → Theme → mount，每一步都有强约束：
//   1) Pinia 必须先于 router：全局 beforeEach 守卫与首屏组件会 useStore()，
//      若 activePinia 不存在会抛 "getActivePinia()" was called with no active Pinia。
//   2) Router 紧随其后：保证首屏 beforeEach 守卫能拿到 auth store 里的 token / user。
//   3) Theme 在 mount 前同步应用：避免 Vue 接管 DOM 后再切换 <html class="dark">
//      导致首屏闪一帧默认主题（FOUC），影响视觉稳定性。
//   4) 最后才 mount：此时 store / router / theme 全部就绪，首屏组件渲染时上下文完备。
app.use(createPinia())
app.use(router)

// 主题初始化故意放在 mount 之前：若等组件挂载后再切换 class，浏览器会先以默认主题
// 渲染一帧再切换，导致首屏闪屏（FOUC）。必须在 Vue 接管 DOM 前应用 data-theme。
import { useThemeStore } from './stores/theme'
const themeStore = useThemeStore()
themeStore.applyTheme()

app.mount('#app')
