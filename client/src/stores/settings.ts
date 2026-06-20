import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { settingsApi } from '@/api/settings'

/**
 * 全局用户偏好与 API Key 状态中心。
 *
 * 为什么用 Pinia store 而不是组件内 state：
 * - DeepSeek API Key 由后端持久化，但前端需要在多处（设置弹窗、调用 AI 的服务层）读取同一份；
 * - 同一个进程内多组件共享，避免重复拉取 / 状态漂移。
 *
 * 职责边界：仅做"当前登录用户的全局偏好"——主题、模型配置、API Key 显示开关。
 * 不应承担单次会话的临时 UI 状态（那些属于组件本地 ref）。
 */
export const useSettingsStore = defineStore('settings', () => {
  // 从 localStorage 兜底初始化：刷新页面后保持 API Key 可见性，避免每次进入页面都触发一次后端拉取
  const deepseekApiKey = ref<string>(localStorage.getItem('deepseekApiKey') || '')
  const showApiKey = ref(false)
  const loading = ref(false)

  // 设置入口从独立路由收敛到全局弹窗，便于在任何页面唤起而不打断当前对话上下文
  const settingsModalOpen = ref(false)
  /** Tab to focus when the modal opens. Consumed (read + reset) by SettingsModal on mount. */
  // pendingTab 是"一次性信号"：modal 挂载时读取后立即清空，避免下次打开弹窗仍跳到上次的 tab
  const pendingTab = ref<string | null>(null)

  /**
   * 唤起全局设置弹窗，可指定打开时定位的 tab。
   * pendingTab 是「一次性信号」：modal 挂载时通过 consumePendingTab 读取后立即清空，
   * 避免下次打开弹窗仍跳到上次的 tab。
   * 调用方：AppHeader 的「设置」按钮、错误提示中的「去设置」链接。
   */
  function openSettings(tab?: string) {
    pendingTab.value = tab ?? null
    settingsModalOpen.value = true
  }
  /**
   * 关闭设置弹窗，同时清空 pendingTab，防止下次打开意外跳到旧 tab。
   * 调用方：SettingsModal 的「关闭」按钮、ESC 键监听、路由切换守卫。
   */
  function closeSettings() {
    settingsModalOpen.value = false
    pendingTab.value = null
  }
  /**
   * 由 SettingsModal onMount 时调用一次：读取并立即清空 pendingTab。
   * 返回 null 表示「无指定 tab，沿用默认」或「已被前次调用消费」。
   * 调用方：SettingsModal 的 onMounted。
   */
  function consumePendingTab(): string | null {
    const t = pendingTab.value
    pendingTab.value = null
    return t
  }

  const hasApiKey = computed(() => !!deepseekApiKey.value && deepseekApiKey.value.length > 0)

  /**
   * 从后端拉取当前用户的 DeepSeek API Key 并同步到 store + localStorage。
   *
   * 副作用：写入 localStorage——即使后端临时不可达也能保持本地展示，
   * 但 localStorage 已是"上次成功值"，真正的权威源仍是后端。
   * 错误被吞掉而非向上抛：拉取失败时静默降级到 localStorage 兜底值，
   * 避免阻塞首屏渲染。
   */
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

  /**
   * 保存新的 API Key 到后端，并同步刷新 store 与 localStorage。
   *
   * 与 fetchApiKey 不同，这里向上抛错：保存失败必须让 UI 提示用户重试，
   * 否则用户会以为已生效但实际后端并未更新。
   */
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

  /**
   * 清除 API Key：通知后端删除持久化记录 + 清理 store + 清理 localStorage。
   *
   * 三处都必须同步：缺一就会导致下次刷新出现"以为已清除但实际仍存在"的不一致状态。
   * 同样向上抛错以触发 UI 错误提示。
   */
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

  /**
   * 切换 API Key 明文/掩码显示。纯前端状态，不持久化——刷新即回到掩码态，避免误把 key 留在本地存储。
   * 调用方：SettingsModal 的「眼睛」图标按钮。
   */
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
