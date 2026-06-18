import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)

// 顺序固定：Pinia 必须先于 router 和任何 store 消费者注册，否则 router 守卫 / 组件中
// useStore() 会因 activePinia 为空而抛错；router 紧跟其后，确保首屏路由守卫可访问 store。
app.use(createPinia())
app.use(router)

// 主题初始化故意放在 mount 之前：若等组件挂载后再切换 class，浏览器会先以默认主题
// 渲染一帧再切换，导致首屏闪屏（FOUC）。必须在 Vue 接管 DOM 前应用 data-theme。
// Initialize theme after Pinia is set up
import { useThemeStore } from './stores/theme'
const themeStore = useThemeStore()
themeStore.applyTheme()

app.mount('#app')
