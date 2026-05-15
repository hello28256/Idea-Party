import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'
import { useThemeStore } from './stores/theme'

// Initialize theme from localStorage before mounting
const themeStore = useThemeStore()
themeStore.applyTheme()

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
