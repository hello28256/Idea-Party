import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'

// Initialize theme from localStorage before mounting
function initTheme() {
  const savedTheme = localStorage.getItem('theme-mode')
  if (savedTheme === 'dark' || savedTheme === 'light') {
    document.documentElement.dataset.theme = savedTheme
  } else {
    // System preference
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    document.documentElement.dataset.theme = prefersDark ? 'dark' : 'light'
  }

  // Listen for system theme changes
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
    if (!localStorage.getItem('theme-mode') || localStorage.getItem('theme-mode') === 'system') {
      document.documentElement.dataset.theme = e.matches ? 'dark' : 'light'
    }
  })
}

initTheme()

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
