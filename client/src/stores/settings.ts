import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { settingsApi } from '@/api/settings'

export const useSettingsStore = defineStore('settings', () => {
  const deepseekApiKey = ref<string>(localStorage.getItem('deepseekApiKey') || '')
  const showApiKey = ref(false)
  const loading = ref(false)

  // Settings modal open/close (replaces the /settings route)
  const settingsModalOpen = ref(false)
  /** Tab to focus when the modal opens. Consumed (read + reset) by SettingsModal on mount. */
  const pendingTab = ref<string | null>(null)

  function openSettings(tab?: string) {
    pendingTab.value = tab ?? null
    settingsModalOpen.value = true
  }
  function closeSettings() {
    settingsModalOpen.value = false
    pendingTab.value = null
  }
  function consumePendingTab(): string | null {
    const t = pendingTab.value
    pendingTab.value = null
    return t
  }

  const hasApiKey = computed(() => !!deepseekApiKey.value && deepseekApiKey.value.length > 0)

  async function fetchApiKey() {
    loading.value = true
    try {
      const response = await settingsApi.getApiKey()
      const apiKey = response.data.apiKey
      deepseekApiKey.value = apiKey
      localStorage.setItem('deepseekApiKey', apiKey)
    } catch (error) {
      console.error('[DEBUG] Failed to fetch API key:', error)
    } finally {
      loading.value = false
    }
  }

  async function setApiKey(key: string) {
    loading.value = true
    try {
      await settingsApi.setApiKey(key)
      deepseekApiKey.value = key
      localStorage.setItem('deepseekApiKey', key)
    } catch (error) {
      console.error('[DEBUG] Failed to save API key:', error)
      throw error
    } finally {
      loading.value = false
    }
  }

  async function clearApiKey() {
    loading.value = true
    try {
      await settingsApi.clearApiKey()
      deepseekApiKey.value = ''
      localStorage.removeItem('deepseekApiKey')
    } catch (error) {
      console.error('[DEBUG] Failed to clear API key:', error)
      throw error
    } finally {
      loading.value = false
    }
  }

  function toggleShowKey() {
    showApiKey.value = !showApiKey.value
  }

  return {
    deepseekApiKey,
    showApiKey,
    hasApiKey,
    loading,
    fetchApiKey,
    setApiKey,
    clearApiKey,
    toggleShowKey,
    settingsModalOpen,
    pendingTab,
    openSettings,
    closeSettings,
    consumePendingTab
  }
})
