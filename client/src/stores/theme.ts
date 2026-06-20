// 主题 store：维护用户档位（system/light/dark）、解析后的 isDark，并与 <html> 的 .dark / data-theme 同步。
// 协作模块：SettingsModal（用户切换）、authStore.fetchProfile（登录后从后端回灌）、所有 Tailwind 暗色变体消费方。

import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { updatePreferences } from '@/api/user'

// 用户可见的主题档位：'system' 跟随 OS 偏好切换，另外两档是用户显式覆盖
export type ThemeMode = 'system' | 'light' | 'dark'

// 全局主题状态：同时维护用户选择（持久化）与解析后的 isDark（响应式消费）。
// 与 <html> 上的 .dark / data-theme 配合，由 Tailwind dark 变体与自定义 CSS 钩子共同读取。
export const useThemeStore = defineStore('theme', () => {
  // 优先读取 localStorage，保证首屏前即可应用正确主题，避免主题闪烁
  const themeMode = ref<ThemeMode>(
    (localStorage.getItem('themeMode') as ThemeMode) || 'system'
  )
  const isDark = ref(getIsDark(themeMode.value))

  // 将用户档位解析为最终布尔值。'system' 时跟随 OS，其余档位为用户显式覆盖。
  function getIsDark(mode: ThemeMode): boolean {
    if (mode === 'dark') return true
    if (mode === 'light') return false
    // system mode：实时跟随系统颜色方案，确保用户未显式选择时也能贴合 OS
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  }

  // 把主题应用到 DOM 与响应式状态：同时写 classList（Tailwind dark 变体）
  // 与 dataset.theme（项目内自定义 CSS 钩子），两套消费方缺一不可。
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

  // 设置主题档位：本地立即落盘 + 同步 DOM，让用户操作无延迟反馈；
  // 后端持久化为最佳努力，失败仅记录日志，不影响本地体验。
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

  // 监听 OS 主题变化：仅在用户处于 'system' 档位时才重算，
  // 避免覆盖用户的显式选择（用户选了 dark 就一直 dark，不被系统切换反向覆盖）。
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
