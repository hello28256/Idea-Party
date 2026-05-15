import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// Initialize theme after Pinia is set up
import { useThemeStore } from './stores/theme'
const themeStore = useThemeStore()
themeStore.applyTheme()

app.mount('#app')
