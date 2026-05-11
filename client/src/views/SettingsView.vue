<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import Button from '@/components/ui/Button.vue'

const router = useRouter()
const settingsStore = useSettingsStore()

const apiKey = ref(settingsStore.deepseekApiKey)
const saved = ref(false)
const error = ref<string | null>(null)

onMounted(async () => {
  await settingsStore.fetchApiKey()
  apiKey.value = settingsStore.deepseekApiKey
})

async function handleSave() {
  error.value = null
  try {
    await settingsStore.setApiKey(apiKey.value)
    saved.value = true
    setTimeout(() => {
      saved.value = false
    }, 2000)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败'
  }
}

async function handleClear() {
  if (confirm('确定要清除 API Key 吗？')) {
    try {
      await settingsStore.clearApiKey()
      apiKey.value = ''
    } catch (e) {
      error.value = e instanceof Error ? e.message : '清除失败'
    }
  }
}

function handleClose() {
  router.push('/rooms')
}

const menuItems = [
  { id: 'ai', label: 'AI 配置', icon: '🤖' },
  { id: 'account', label: '账户', icon: '👤' },
  { id: 'preferences', label: '偏好设置', icon: '⚙️' },
  { id: 'advanced', label: '高级', icon: '🔧' },
]

const activeMenu = ref('ai')
</script>

<template>
  <div class="settings-page">
    <div class="settings-modal">
      <!-- Left Sidebar -->
      <aside class="settings-sidebar">
        <div class="sidebar-header">
          <span class="sidebar-brand">Idea Party</span>
        </div>
        <nav class="sidebar-nav">
          <button
            v-for="item in menuItems"
            :key="item.id"
            class="nav-item"
            :class="{ active: activeMenu === item.id }"
            @click="activeMenu = item.id"
          >
            <span class="nav-icon">{{ item.icon }}</span>
            <span class="nav-label">{{ item.label }}</span>
          </button>
        </nav>
      </aside>

      <!-- Right Content -->
      <main class="settings-content">
        <!-- Close Button -->
        <button class="close-btn" @click="handleClose">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M18 6L6 18M6 6l12 12"/>
          </svg>
        </button>

        <!-- Page Title -->
        <div class="content-header">
          <h1 class="content-title">设置</h1>
          <p class="content-subtitle">配置你的 AI 服务</p>
        </div>

        <!-- API Key Card -->
        <div class="api-key-card">
          <div class="api-key-header">
            <div class="api-key-icon">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z"/>
              </svg>
            </div>
            <div class="api-key-info">
              <h2 class="api-key-title">DeepSeek API Key</h2>
              <p class="api-key-desc">用于 AI 角色回复的生成</p>
            </div>
          </div>

          <div class="api-key-input-wrapper">
            <input
              v-model="apiKey"
              :type="settingsStore.showApiKey ? 'text' : 'password'"
              placeholder="请输入 DeepSeek API Key"
              class="api-key-input"
            />
            <button
              class="toggle-visibility-btn"
              @click="settingsStore.toggleShowKey"
            >
              <svg v-if="!settingsStore.showApiKey" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                <path d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
              </svg>
              <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"/>
              </svg>
            </button>
          </div>

          <div class="api-key-actions">
            <button class="btn-save" @click="handleSave" :disabled="settingsStore.loading">
              保存
            </button>
            <button
              class="btn-clear"
              @click="handleClear"
              :disabled="!settingsStore.hasApiKey || settingsStore.loading"
            >
              清除
            </button>
          </div>

          <Transition name="fade">
            <div v-if="saved" class="status-message success">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M5 13l4 4L19 7"/>
              </svg>
              已保存
            </div>
          </Transition>

          <Transition name="fade">
            <div v-if="error" class="status-message error">
              {{ error }}
            </div>
          </Transition>

          <!-- Status -->
          <div class="api-key-status">
            <div class="status-dot" :class="{ active: settingsStore.hasApiKey }"></div>
            <span>{{ settingsStore.hasApiKey ? 'API Key 已配置' : 'API Key 未配置' }}</span>
          </div>
        </div>

        <!-- Info Card -->
        <div class="info-card">
          <h3 class="info-title">如何获取 API Key?</h3>
          <ol class="info-list">
            <li>访问 <a href="https://platform.deepseek.com" target="_blank" class="info-link">DeepSeek 开放平台</a></li>
            <li>注册账号并完成认证</li>
            <li>在 API Keys 页面创建一个新的 Key</li>
            <li>复制并粘贴到上方输入框</li>
          </ol>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* Page Container */
.settings-page {
  min-height: 100vh;
  background: #f5f5f6;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
}

/* Modal */
.settings-modal {
  width: min(760px, calc(100vw - 48px));
  border-radius: 28px;
  background: #e5e5e8;
  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.18);
  overflow: hidden;
  display: grid;
  grid-template-columns: 180px 1fr;
}

/* Sidebar */
.settings-sidebar {
  background: rgba(255, 255, 255, 0.25);
  border-right: 1px solid rgba(0, 0, 0, 0.08);
  padding: 28px 20px;
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  margin-bottom: 28px;
}

.sidebar-brand {
  font-size: 15px;
  font-weight: 800;
  color: #202124;
  letter-spacing: -0.02em;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.nav-item {
  height: 44px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  padding: 0 14px;
  font-weight: 700;
  font-size: 13px;
  color: #8b8b94;
  background: transparent;
  border: none;
  cursor: pointer;
  transition: all 0.15s ease;
  gap: 10px;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.35);
  color: #202124;
}

.nav-item.active {
  background: rgba(255, 255, 255, 0.55);
  color: #202124;
}

.nav-icon {
  font-size: 16px;
}

.nav-label {
  flex: 1;
  text-align: left;
}

/* Content */
.settings-content {
  padding: 34px 36px 40px;
  position: relative;
}

.close-btn {
  position: absolute;
  top: 22px;
  right: 22px;
  width: 32px;
  height: 32px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  color: #666;
  background: transparent;
  border: none;
  cursor: pointer;
  transition: all 0.15s ease;
}

.close-btn:hover {
  background: rgba(0, 0, 0, 0.06);
  color: #333;
}

.content-header {
  margin-bottom: 28px;
}

.content-title {
  font-size: 28px;
  font-weight: 800;
  color: #202124;
  letter-spacing: -0.04em;
  margin: 0 0 6px;
}

.content-subtitle {
  font-size: 14px;
  color: #6b6b73;
  margin: 0;
}

/* API Key Card */
.api-key-card {
  background: rgba(255, 255, 255, 0.5);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 20px;
  padding: 24px;
  margin-bottom: 20px;
}

.api-key-header {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin-bottom: 20px;
}

.api-key-icon {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  background: linear-gradient(135deg, #1e3a5f 0%, #2d5a87 100%);
  display: grid;
  place-items: center;
  color: #f0d78c;
  flex-shrink: 0;
}

.api-key-info {
  flex: 1;
}

.api-key-title {
  font-size: 16px;
  font-weight: 700;
  color: #1e3a5f;
  margin: 0 0 4px;
}

.api-key-desc {
  font-size: 13px;
  color: #6b6b73;
  margin: 0;
}

/* Input */
.api-key-input-wrapper {
  position: relative;
  margin-bottom: 16px;
}

.api-key-input {
  width: 100%;
  height: 58px;
  padding: 0 56px 0 18px;
  border-radius: 14px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: #d9d9dd;
  font-size: 15px;
  color: #202124;
  outline: none;
  transition: all 0.15s ease;
}

.api-key-input:focus {
  border-color: rgba(0, 0, 0, 0.2);
  background: #d5d5d9;
}

.api-key-input::placeholder {
  color: #9b9ba3;
}

.toggle-visibility-btn {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  background: transparent;
  border: none;
  cursor: pointer;
  color: #6b6b73;
  padding: 6px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  transition: all 0.15s ease;
}

.toggle-visibility-btn:hover {
  color: #202124;
  background: rgba(0, 0, 0, 0.04);
}

/* Buttons */
.api-key-actions {
  display: flex;
  gap: 12px;
}

.btn-save,
.btn-clear {
  flex: 1;
  height: 46px;
  border-radius: 14px;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s ease;
  border: none;
}

.btn-save {
  background: #202124;
  color: white;
}

.btn-save:hover:not(:disabled) {
  background: #333;
}

.btn-save:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-clear {
  background: rgba(255, 255, 255, 0.7);
  color: #202124;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.btn-clear:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.9);
}

.btn-clear:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* Status Message */
.status-message {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
  font-size: 13px;
  font-weight: 600;
}

.status-message.success {
  color: #059669;
}

.status-message.error {
  color: #dc2626;
}

.api-key-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
  font-size: 13px;
  color: #6b6b73;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #9b9ba3;
}

.status-dot.active {
  background: #059669;
}

/* Info Card */
.info-card {
  background: rgba(255, 255, 255, 0.5);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 18px;
  padding: 20px 24px;
}

.info-title {
  font-size: 14px;
  font-weight: 700;
  color: #202124;
  margin: 0 0 14px;
}

.info-list {
  margin: 0;
  padding: 0 0 0 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-list li {
  font-size: 13px;
  color: #6b6b73;
  line-height: 1.5;
}

.info-link {
  color: #2563eb;
  text-decoration: none;
  font-weight: 500;
}

.info-link:hover {
  text-decoration: underline;
}

/* Transitions */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* Dark Mode */
@media (prefers-color-scheme: dark) {
  .settings-page {
    background: #0f1115;
  }

  .settings-modal {
    background: #1b1c20;
    border: 1px solid rgba(255, 255, 255, 0.08);
  }

  .settings-sidebar {
    background: rgba(255, 255, 255, 0.04);
    border-right: 1px solid rgba(255, 255, 255, 0.08);
  }

  .sidebar-brand {
    color: white;
  }

  .nav-item {
    color: #9b9ba3;
  }

  .nav-item:hover {
    background: rgba(255, 255, 255, 0.08);
    color: white;
  }

  .nav-item.active {
    background: rgba(255, 255, 255, 0.1);
    color: white;
  }

  .close-btn {
    color: #9b9ba3;
  }

  .close-btn:hover {
    background: rgba(255, 255, 255, 0.08);
    color: white;
  }

  .content-title {
    color: white;
  }

  .content-subtitle {
    color: #a1a1aa;
  }

  .api-key-card {
    background: rgba(255, 255, 255, 0.05);
    border: 1px solid rgba(255, 255, 255, 0.08);
  }

  .api-key-title {
    color: white;
  }

  .api-key-desc {
    color: #a1a1aa;
  }

  .api-key-icon {
    background: linear-gradient(135deg, #1e3a5f 0%, #2d5a87 100%);
  }

  .api-key-input {
    background: #25262b;
    border: 1px solid rgba(255, 255, 255, 0.08);
    color: white;
  }

  .api-key-input:focus {
    background: #2d2e32;
    border-color: rgba(255, 255, 255, 0.15);
  }

  .api-key-input::placeholder {
    color: #6b6b73;
  }

  .toggle-visibility-btn {
    color: #9b9ba3;
  }

  .toggle-visibility-btn:hover {
    background: rgba(255, 255, 255, 0.08);
    color: white;
  }

  .btn-save {
    background: white;
    color: #202124;
  }

  .btn-save:hover:not(:disabled) {
    background: #f0f0f0;
  }

  .btn-clear {
    background: rgba(255, 255, 255, 0.08);
    color: white;
    border: 1px solid rgba(255, 255, 255, 0.1);
  }

  .btn-clear:hover:not(:disabled) {
    background: rgba(255, 255, 255, 0.12);
  }

  .api-key-status {
    border-top-color: rgba(255, 255, 255, 0.08);
    color: #a1a1aa;
  }

  .info-card {
    background: rgba(255, 255, 255, 0.05);
    border: 1px solid rgba(255, 255, 255, 0.08);
  }

  .info-title {
    color: white;
  }

  .info-list li {
    color: #a1a1aa;
  }
}
</style>
