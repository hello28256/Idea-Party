import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { updatePreferences } from '@/api/user'

export type ThemeMode = 'system' | 'light' | 'dark'

export const useThemeStore = defineStore('theme', () => {
  const themeMode = ref<ThemeMode>(
    (localStorage.getItem('themeMode') as ThemeMode) || 'system'
  )
  const isDark = ref(getIsDark(themeMode.value))

  function getIsDark(mode: ThemeMode): boolean {
    if (mode === 'dark') return true
    if (mode === 'light') return false
    // system mode
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  }

  function applyTheme() {
    const dark = getIsDark(themeMode.value)
    isDark.value = dark
    if (dark) {
      document.documentElement.classList.add('dark')
      document.documentElement.dataset.theme = 'dark'
    } else {
      document.documentElement.classList.remove('dark')
      document.documentElement.dataset.theme = 'light'
    }
  }

  async function setThemeMode(mode: ThemeMode) {
    themeMode.value = mode
    localStorage.setItem('themeMode', mode)
    applyTheme()
    try {
      await updatePreferences({ themeMode: mode })
    } catch (e) {
      console.error('[DEBUG] Failed to save theme to backend:', e)
    }
  }

  // Initialize theme on store creation
  applyTheme()

  // Watch for system theme changes
  const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
  mediaQuery.addEventListener('change', () => {
    if (themeMode.value === 'system') {
      applyTheme()
    }
  })

  // Watch for changes and apply
  watch(themeMode, applyTheme)

  return {
    themeMode,
    isDark,
    setThemeMode,
    applyTheme
  }
})
