<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { useAuthStore } from '@/stores/auth'

const settingsStore = useSettingsStore()
const authStore = useAuthStore()

// API Key state
const apiKey = ref(settingsStore.deepseekApiKey)
const saved = ref(false)
const error = ref<string | null>(null)
const loading = ref(false)

// Account state
const accountUsername = ref('')
const accountDisplayName = ref('')
const accountEmail = ref('')

// Load user data on mount
onMounted(async () => {
  await settingsStore.fetchApiKey()
  apiKey.value = settingsStore.deepseekApiKey

  if (authStore.user) {
    accountUsername.value = authStore.user.username || ''
    accountDisplayName.value = authStore.user.displayName || ''
    accountEmail.value = authStore.user.email || ''
  }
})

watch(() => authStore.user, (newUser) => {
  if (newUser) {
    accountUsername.value = newUser.username || ''
    accountDisplayName.value = newUser.displayName || ''
    accountEmail.value = newUser.email || ''
  }
}, { immediate: true })

// API Key handlers
async function handleSave() {
  error.value = null
  loading.value = true
  try {
    await settingsStore.setApiKey(apiKey.value)
    saved.value = true
    setTimeout(() => {
      saved.value = false
    }, 2000)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    loading.value = false
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
</script>

<template>
  <div class="settings-page">
    <div class="settings-container">
      <!-- API Key Card -->
      <div class="account-card">
        <div class="api-key-header">
          <div class="api-key-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/>
            </svg>
          </div>
          <div class="api-key-info">
            <h2 class="api-key-title">DeepSeek API Key</h2>
            <p class="api-key-desc">用于 AI 角色对话的 API 密钥</p>
          </div>
        </div>

        <div class="field-group">
          <div class="password-input-wrapper">
            <input
              v-model="apiKey"
              :type="settingsStore.showApiKey ? 'text' : 'password'"
              placeholder="sk-..."
              class="field-input"
            />
            <button
              class="toggle-password-btn"
              @click="settingsStore.toggleShowKey"
              type="button"
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
        </div>

        <div class="api-key-actions">
          <button class="btn-save" @click="handleSave" :disabled="loading">
            保存
          </button>
          <button
            class="btn-clear"
            @click="handleClear"
            :disabled="!settingsStore.hasApiKey || loading"
          >
            清除
          </button>
        </div>

        <Transition name="fade">
          <div v-if="saved" class="toast success">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <path d="M5 13l4 4L19 7"/>
            </svg>
            已保存
          </div>
        </Transition>

        <Transition name="fade">
          <div v-if="error" class="toast error">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <circle cx="12" cy="12" r="10"/>
              <path d="M15 9l-6 6M9 9l6 6"/>
            </svg>
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
      <div class="account-card info-card">
        <h3 class="info-title">如何获取 API Key?</h3>
        <ol class="info-list">
          <li>访问 <a href="https://platform.deepseek.com" target="_blank" class="info-link">DeepSeek 开放平台</a></li>
          <li>注册账号并完成认证</li>
          <li>在 API Keys 页面创建一个新的 Key</li>
          <li>复制并粘贴到上方输入框</li>
        </ol>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-page {
  min-height: 100vh;
  background: linear-gradient(135deg, var(--color-ivory) 0%, var(--color-cream) 100%);
  padding: 2rem;
}

.settings-container {
  max-width: 600px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.account-card {
  background: var(--color-ivory);
  border: 1px solid var(--color-border);
  border-radius: 1rem;
  padding: 1.5rem;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.api-key-header {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.api-key-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--color-gold) 0%, var(--color-gold-dark) 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  flex-shrink: 0;
}

.api-key-info {
  flex: 1;
}

.api-key-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--color-navy);
  margin: 0 0 0.25rem 0;
}

.api-key-desc {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  margin: 0;
}

.field-group {
  margin-bottom: 1rem;
}

.field-label {
  display: block;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-navy);
  margin-bottom: 0.5rem;
}

.password-input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.field-input {
  width: 100%;
  padding: 0.625rem 2.5rem 0.625rem 0.875rem;
  font-size: 0.875rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  background: white;
  color: var(--color-text-primary);
  transition: all 0.2s;
}

.field-input:focus {
  outline: none;
  border-color: var(--color-gold);
  box-shadow: 0 0 0 3px var(--color-gold-bg);
}

.toggle-password-btn {
  position: absolute;
  right: 0.75rem;
  background: none;
  border: none;
  cursor: pointer;
  color: var(--color-text-muted);
  padding: 0.25rem;
  display: flex;
  align-items: center;
  justify-content: center;
}

.toggle-password-btn:hover {
  color: var(--color-text-secondary);
}

.api-key-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1rem;
}

.btn-save {
  flex: 1;
  padding: 0.625rem 1rem;
  font-size: 0.875rem;
  font-weight: 500;
  border: none;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: all 0.2s;
  background: var(--color-navy);
  color: white;
}

.btn-save:hover:not(:disabled) {
  opacity: 0.9;
}

.btn-save:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-clear {
  flex: 1;
  padding: 0.625rem 1rem;
  font-size: 0.875rem;
  font-weight: 500;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  cursor: pointer;
  transition: all 0.2s;
  background: white;
  color: var(--color-text-secondary);
}

.btn-clear:hover:not(:disabled) {
  border-color: var(--color-destructive);
  color: var(--color-destructive);
}

.btn-clear:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.toast {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  font-size: 0.875rem;
  margin-top: 1rem;
}

.toast.success {
  background: #f0fdf4;
  color: #166534;
  border: 1px solid #bbf7d0;
}

.toast.error {
  background: #fef2f2;
  color: #991b1b;
  border: 1px solid #fecaca;
}

.api-key-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 1px solid var(--color-border);
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #d1d5db;
}

.status-dot.active {
  background: #22c55e;
}

.info-card {
  background: linear-gradient(135deg, #fefce8 0%, #fef9c3 100%);
  border-color: #fde047;
}

.info-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-navy);
  margin: 0 0 1rem 0;
}

.info-list {
  margin: 0;
  padding-left: 1.25rem;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
  line-height: 1.75;
}

.info-link {
  color: var(--color-gold-dark);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.info-link:hover {
  color: var(--color-gold);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
