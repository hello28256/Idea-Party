<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useSettingsStore } from '@/stores/settings'

const authStore = useAuthStore()
const themeStore = useThemeStore()
const settingsStore = useSettingsStore()

// Tab state
const activeTab = ref<'account' | 'preferences' | 'ai'>('account')

// Account form state
const accountForm = ref({
  username: '',
  displayName: '',
  email: '',
  avatarUrl: ''
})
const avatarFile = ref<File | null>(null)
const avatarPreview = ref<string | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)
const saveSuccess = ref(false)

// API Key state
const apiKey = ref(settingsStore.deepseekApiKey)
const loading = ref(false)

// Theme state
const selectedTheme = ref<'system' | 'light' | 'dark'>('system')

// Load data on mount
onMounted(async () => {
  await settingsStore.fetchApiKey()
  apiKey.value = settingsStore.deepseekApiKey
  await authStore.fetchProfile()

  if (authStore.user) {
    accountForm.value = {
      username: authStore.user.username || '',
      displayName: authStore.user.displayName || '',
      email: authStore.user.email || '',
      avatarUrl: authStore.user.avatarUrl || ''
    }
  }

  selectedTheme.value = themeStore.themeMode
})

// Watch for user changes
watch(() => authStore.user, (user) => {
  if (user) {
    accountForm.value = {
      username: user.username || '',
      displayName: user.displayName || '',
      email: user.email || '',
      avatarUrl: user.avatarUrl || ''
    }
  }
}, { immediate: true })

// Computed: check if username can be changed
const canChangeUsername = computed(() => {
  if (!authStore.user?.lastUsernameChangeAt) return true
  const lastChange = new Date(authStore.user.lastUsernameChangeAt)
  const daysSinceChange = (Date.now() - lastChange.getTime()) / (1000 * 60 * 60 * 24)
  return daysSinceChange >= 30
})

// Avatar handling
function handleAvatarChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files && input.files[0]) {
    const file = input.files[0]
    if (file.size > 5 * 1024 * 1024) {
      saveError.value = '头像文件过大，最大 5MB'
      return
    }
    avatarFile.value = file
    avatarPreview.value = URL.createObjectURL(file)
  }
}

// Save account
async function saveAccount() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false

  try {
    // Upload avatar first if changed
    if (avatarFile.value) {
      const result = await authStore.uploadAvatar(avatarFile.value)
      if (!result.success) {
        saveError.value = result.error || '头像上传失败'
        saving.value = false
        return
      }
      accountForm.value.avatarUrl = result.avatarUrl!
    }

    // Update profile
    const result = await authStore.updateProfile({
      username: accountForm.value.username,
      displayName: accountForm.value.displayName,
      email: accountForm.value.email
    })

    if (!result.success) {
      saveError.value = result.error || '保存失败'
      saving.value = false
      return
    }

    saveSuccess.value = true
    setTimeout(() => { saveSuccess.value = false }, 2000)
  } catch (e: any) {
    saveError.value = e?.response?.data?.message || e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

// Theme change
function handleThemeChange(mode: 'system' | 'light' | 'dark') {
  selectedTheme.value = mode
  themeStore.setThemeMode(mode)
}

// API Key handlers
async function handleSave() {
  try {
    await settingsStore.setApiKey(apiKey.value)
  } catch (e) {
    console.error(e)
  }
}

async function handleClear() {
  if (confirm('确定要清除 API Key 吗？')) {
    await settingsStore.clearApiKey()
    apiKey.value = ''
  }
}

// Check if form has changes
const hasChanges = computed(() => {
  if (!authStore.user) return false
  return accountForm.value.username !== authStore.user.username ||
         accountForm.value.displayName !== authStore.user.displayName ||
         accountForm.value.email !== authStore.user.email ||
         avatarFile.value !== null
})
</script>

<template>
  <div class="settings-page">
    <div class="settings-container">
      <!-- Tab Navigation -->
      <div class="tab-nav">
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'account' }"
          @click="activeTab = 'account'"
        >
          账户设置
        </button>
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'preferences' }"
          @click="activeTab = 'preferences'"
        >
          偏好设置
        </button>
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'ai' }"
          @click="activeTab = 'ai'"
        >
          AI 配置
        </button>
      </div>

      <!-- Account Settings Tab -->
      <div v-if="activeTab === 'account'" class="tab-content">
        <div class="account-card">
          <h2 class="card-title">账户设置</h2>

          <!-- Avatar -->
          <div class="avatar-section">
            <div class="avatar-wrapper">
              <img
                :src="avatarPreview || accountForm.avatarUrl || '/default-avatar.png'"
                alt="Avatar"
                class="avatar-img"
              />
              <label class="avatar-edit">
                <input type="file" accept="image/jpeg,image/png,image/webp" @change="handleAvatarChange" hidden />
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                </svg>
              </label>
            </div>
            <p class="avatar-hint">支持 jpg/png/webp，最大 5MB</p>
          </div>

          <!-- Username -->
          <div class="field-group">
            <label class="field-label">用户名</label>
            <input
              v-model="accountForm.username"
              :disabled="!canChangeUsername"
              class="field-input"
              placeholder="3-20位字母、数字、下划线"
            />
            <p v-if="!canChangeUsername" class="field-hint error">
              用户名 30 天只能修改一次
            </p>
          </div>

          <!-- Display Name -->
          <div class="field-group">
            <label class="field-label">显示名</label>
            <input
              v-model="accountForm.displayName"
              class="field-input"
              placeholder="1-50位字符"
            />
          </div>

          <!-- Email -->
          <div class="field-group">
            <label class="field-label">邮箱</label>
            <input
              v-model="accountForm.email"
              type="email"
              class="field-input"
              placeholder="example@domain.com"
            />
          </div>

          <!-- Save Button -->
          <div class="form-actions">
            <button
              class="btn-save"
              @click="saveAccount"
              :disabled="saving || !hasChanges"
            >
              {{ saving ? '保存中...' : '保存' }}
            </button>
          </div>

          <!-- Messages -->
          <Transition name="fade">
            <div v-if="saveSuccess" class="toast success">
              保存成功
            </div>
          </Transition>
          <Transition name="fade">
            <div v-if="saveError" class="toast error">
              {{ saveError }}
            </div>
          </Transition>
        </div>
      </div>

      <!-- Preferences Tab -->
      <div v-if="activeTab === 'preferences'" class="tab-content">
        <div class="account-card">
          <h2 class="card-title">偏好设置</h2>

          <!-- Theme Mode -->
          <div class="field-group">
            <label class="field-label">主题模式</label>
            <div class="theme-options">
              <button
                class="theme-option"
                :class="{ active: selectedTheme === 'system' }"
                @click="handleThemeChange('system')"
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="5"/>
                  <line x1="12" y1="1" x2="12" y2="3"/>
                  <line x1="12" y1="21" x2="12" y2="23"/>
                  <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/>
                  <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/>
                  <line x1="1" y1="12" x2="3" y2="12"/>
                  <line x1="21" y1="12" x2="23" y2="12"/>
                  <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/>
                  <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>
                </svg>
                <span>跟随系统</span>
              </button>
              <button
                class="theme-option"
                :class="{ active: selectedTheme === 'light' }"
                @click="handleThemeChange('light')"
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="5"/>
                  <line x1="12" y1="1" x2="12" y2="3"/>
                  <line x1="12" y1="21" x2="12" y2="23"/>
                  <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/>
                  <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/>
                  <line x1="1" y1="12" x2="3" y2="12"/>
                  <line x1="21" y1="12" x2="23" y2="12"/>
                  <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/>
                  <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>
                </svg>
                <span>浅色模式</span>
              </button>
              <button
                class="theme-option"
                :class="{ active: selectedTheme === 'dark' }"
                @click="handleThemeChange('dark')"
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
                </svg>
                <span>深色模式</span>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- AI Config Tab -->
      <div v-if="activeTab === 'ai'" class="tab-content">
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
              <button class="toggle-password-btn" @click="settingsStore.toggleShowKey" type="button">
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
            <button class="btn-clear" @click="handleClear" :disabled="!settingsStore.hasApiKey || loading">
              清除
            </button>
          </div>

          <div class="api-key-status">
            <div class="status-dot" :class="{ active: settingsStore.hasApiKey }"></div>
            <span>{{ settingsStore.hasApiKey ? 'API Key 已配置' : 'API Key 未配置' }}</span>
          </div>
        </div>
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

/* Tab Navigation */
.tab-nav {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 0;
  background: var(--color-ivory);
  padding: 0.5rem;
  border-radius: 1rem;
  border: 1px solid var(--color-border);
}

.tab-btn {
  flex: 1;
  padding: 0.75rem 1rem;
  font-size: 0.875rem;
  font-weight: 500;
  border: none;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: all 0.2s;
  background: transparent;
  color: var(--color-text-secondary);
}

.tab-btn.active {
  background: var(--color-navy);
  color: white;
}

.tab-btn:hover:not(.active) {
  background: var(--color-cream);
}

/* Account Card */
.account-card {
  background: var(--color-ivory);
  border: 1px solid var(--color-border);
  border-radius: 1rem;
  padding: 1.5rem;
}

.card-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--color-navy);
  margin: 0 0 1.5rem 0;
}

/* Avatar */
.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 1.5rem;
}

.avatar-wrapper {
  position: relative;
  width: 80px;
  height: 80px;
}

.avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--color-border);
}

.avatar-edit {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--color-navy);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border: 2px solid white;
}

.avatar-hint {
  font-size: 0.75rem;
  color: var(--color-text-muted);
  margin-top: 0.5rem;
}

/* Field Groups */
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

.field-input {
  width: 100%;
  padding: 0.625rem 0.875rem;
  font-size: 0.875rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  background: white;
  color: var(--color-text-primary);
}

.field-input:focus {
  outline: none;
  border-color: var(--color-gold);
  box-shadow: 0 0 0 3px var(--color-gold-bg);
}

.field-input:disabled {
  background: var(--color-cream);
  cursor: not-allowed;
}

.field-hint {
  font-size: 0.75rem;
  color: var(--color-text-muted);
  margin-top: 0.25rem;
}

.field-hint.error {
  color: var(--color-destructive);
}

/* Theme Options */
.theme-options {
  display: flex;
  gap: 0.75rem;
}

.theme-option {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 1rem;
  border: 2px solid var(--color-border);
  border-radius: 0.75rem;
  background: white;
  cursor: pointer;
  transition: all 0.2s;
  color: var(--color-text-secondary);
}

.theme-option:hover {
  border-color: var(--color-gold);
}

.theme-option.active {
  border-color: var(--color-navy);
  background: var(--color-navy);
  color: white;
}

.theme-option span {
  font-size: 0.75rem;
  font-weight: 500;
}

/* Form Actions */
.form-actions {
  margin-top: 1.5rem;
}

.btn-save {
  width: 100%;
  padding: 0.75rem 1rem;
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

/* Toast */
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

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* API Key Styles */
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

.password-input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
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

.api-key-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1rem;
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
</style>
