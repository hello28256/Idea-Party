<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useAuthStore } from '@/stores/auth'
import { changePassword as changePasswordApi } from '@/api/auth'
import RoomListView from '@/views/RoomListView.vue'

const router = useRouter()
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
const accountError = ref<string | null>(null)
const accountSuccess = ref<string | null>(null)

// Password change state
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordError = ref<string | null>(null)
const passwordSuccess = ref<string | null>(null)
const passwordLoading = ref(false)
const showCurrentPassword = ref(false)
const showNewPassword = ref(false)
const showConfirmPassword = ref(false)

// Theme preference state
type ThemeMode = 'system' | 'light' | 'dark'
const currentTheme = ref<ThemeMode>('system')

// Theme options
const themeOptions = [
  { id: 'system', label: '系统', icon: '🌗' },
  { id: 'light', label: '浅色', icon: '☀️' },
  { id: 'dark', label: '深色', icon: '🌙' },
] as const

// Apply theme to document
function applyTheme(theme: ThemeMode) {
  const root = document.documentElement
  if (theme === 'system') {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    root.setAttribute('data-theme', prefersDark ? 'dark' : 'light')
  } else {
    root.setAttribute('data-theme', theme)
  }
}

// Handle theme change
function handleThemeChange(theme: ThemeMode) {
  currentTheme.value = theme
  localStorage.setItem('theme-mode', theme)
  applyTheme(theme)
}

// Initialize theme on mount
onMounted(() => {
  // Load saved theme - key is 'theme-mode' to match main.ts
  const savedTheme = localStorage.getItem('theme-mode') as ThemeMode | null
  if (savedTheme && ['system', 'light', 'dark'].includes(savedTheme)) {
    currentTheme.value = savedTheme
  } else {
    currentTheme.value = 'system'
  }
  applyTheme(currentTheme.value)

  // Listen for system theme changes
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (currentTheme.value === 'system') {
      applyTheme('system')
    }
  })
})

// Username modification status
const usernameModifiable = computed(() => {
  if (!authStore.user?.lastUsernameChangeAt) return true
  const lastUpdate = new Date(authStore.user.lastUsernameChangeAt)
  const daysSinceUpdate = (Date.now() - lastUpdate.getTime()) / (1000 * 60 * 60 * 24)
  return daysSinceUpdate >= 30
})

const usernameNextModifiableDate = computed(() => {
  if (!authStore.user?.lastUsernameChangeAt) return null
  const lastUpdate = new Date(authStore.user.lastUsernameChangeAt)
  const nextDate = new Date(lastUpdate.getTime() + 30 * 24 * 60 * 60 * 1000)
  return nextDate
})

const daysUntilModifiable = computed(() => {
  if (!usernameNextModifiableDate.value) return 0
  const days = (usernameNextModifiableDate.value.getTime() - Date.now()) / (1000 * 60 * 60 * 24)
  return Math.max(0, Math.ceil(days))
})

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

// Account handlers
async function handleAccountSave() {
  accountError.value = null
  accountSuccess.value = null

  if (!accountDisplayName.value.trim()) {
    accountError.value = '显示名不能为空'
    return
  }

  // Check if username is being changed and if it's allowed
  const currentUsername = authStore.user?.username || ''
  const newUsername = accountUsername.value.trim()
  if (newUsername !== currentUsername && !usernameModifiable.value) {
    accountError.value = `用户名 30 天内只能修改一次，还需 ${daysUntilModifiable.value} 天才能再次修改`
    return
  }

  const result = await authStore.updateProfile({
    username: accountUsername.value.trim(),
    displayName: accountDisplayName.value.trim(),
    email: accountEmail.value.trim()
  })

  if (result.success) {
    accountSuccess.value = '资料已保存'
    setTimeout(() => {
      accountSuccess.value = null
    }, 2000)
  } else {
    accountError.value = result.error || '保存失败'
  }
}

async function handleChangePassword() {
  console.log('[change password] start')
  passwordError.value = null
  passwordSuccess.value = null

  // Validation
  if (!currentPassword.value) {
    passwordError.value = '当前密码不能为空'
    return
  }
  if (!newPassword.value) {
    passwordError.value = '新密码不能为空'
    return
  }
  if (!confirmPassword.value) {
    passwordError.value = '确认密码不能为空'
    return
  }
  if (newPassword.value.length < 6) {
    passwordError.value = '密码至少需要 6 位'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    passwordError.value = '两次输入的密码不一致'
    return
  }
  if (currentPassword.value === newPassword.value) {
    passwordError.value = '新密码不能与当前密码相同'
    return
  }

  console.log('[change password] current user id =', authStore.user?.id)
  console.log('[change password] verify current password')

  passwordLoading.value = true
  try {
    await changePasswordApi({
      currentPassword: currentPassword.value,
      newPassword: newPassword.value
    })
    console.log('[change password] password updated success')
    passwordSuccess.value = '密码修改成功'
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    setTimeout(() => {
      authStore.logout()
      router.push('/login')
    }, 1500)
  } catch (e: any) {
    console.error('[DEBUG] [change password] failed:', e)
    const message = e?.response?.data?.message || e?.message || '密码修改失败，请稍后重试'
    passwordError.value = message
  } finally {
    passwordLoading.value = false
  }
}

function handleClose() {
  router.push('/rooms')
}

const menuItems = [
  { id: 'account', label: '账户设置', icon: '👤' },
  { id: 'ai', label: 'AI 配置', icon: '🤖' },
  { id: 'preferences', label: '偏好设置', icon: '⚙️' },
  { id: 'advanced', label: '高级', icon: '🔧' },
]

const activeMenu = ref('account')

// Avatar upload state
const avatarError = ref(false)

// Avatar URL - use user's avatar or default
const avatarUrl = computed(() => authStore.user?.avatarUrl || '/image.png')

// Trigger avatar file input
function triggerAvatarInput() {
  const input = document.getElementById('avatar-file-input') as HTMLInputElement
  if (input) input.click()
}

// Handle avatar file selection
function handleAvatarChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  // Validate file type
  const allowedTypes = ['image/png', 'image/jpeg', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    alert('仅支持 PNG、JPG、WebP 格式')
    return
  }

  // Validate file size (2MB)
  if (file.size > 2 * 1024 * 1024) {
    alert('头像大小不能超过 2MB')
    return
  }

  // Read file as data URL and update user
  const reader = new FileReader()
  reader.onload = async (e) => {
    const dataUrl = e.target?.result as string
    await updateAvatar(dataUrl)
  }
  reader.readAsDataURL(file)
}

// Update avatar in local state
async function updateAvatar(avatarDataUrl: string) {
  try {
    // Update local user state
    if (authStore.user) {
      authStore.user.avatarUrl = avatarDataUrl
      localStorage.setItem('user', JSON.stringify(authStore.user))

      // Update users list
      const usersJson = localStorage.getItem('users')
      const users = usersJson ? JSON.parse(usersJson) : []
      const userIndex = users.findIndex((u: any) => u.id === authStore.user?.id)
      if (userIndex !== -1) {
        users[userIndex].avatarUrl = avatarDataUrl
        localStorage.setItem('users', JSON.stringify(users))
      }
    }

    avatarError.value = false
  } catch (error) {
    console.error('[DEBUG] Failed to update avatar:', error)
    alert('头像上传失败，请重试')
  }
}
</script>

<template>
  <div class="settings-route">
    <!-- Background RoomList -->
    <div class="settings-background">
      <RoomListView />
    </div>

    <!-- Overlay (no blur) -->
    <div class="settings-overlay" @click="handleClose"></div>

    <!-- Modal -->
    <div class="settings-modal-wrap">
      <div class="settings-modal" @click.stop>
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
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 6L6 18M6 6l12 12"/>
            </svg>
          </button>

          <!-- Account Settings -->
          <div v-if="activeMenu === 'account'" class="account-settings">
            <!-- Page Header -->
            <div class="content-header">
              <h1 class="content-title">账户设置</h1>
            </div>

            <!-- Account Card -->
            <div class="account-card">
              <!-- User Info Header -->
              <div class="account-header">
                <div class="avatar-column">
                  <label class="avatar-upload">
                    <input
                      type="file"
                      id="avatar-file-input"
                      accept="image/png,image/jpeg,image/webp"
                      class="avatar-input"
                      @change="handleAvatarChange"
                    />
                    <div class="avatar">
                      <img
                        :src="avatarUrl"
                        :alt="authStore.user?.displayName"
                        @error="avatarError = true"
                      />
                      <div class="avatar-overlay">
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M23 19a2 2 0 01-2 2H3a2 2 0 01-2-2V8a2 2 0 012-2h4l2-3h6l2 3h4a2 2 0 012 2z"/>
                          <circle cx="12" cy="13" r="4"/>
                        </svg>
                        <span>更换头像</span>
                      </div>
                    </div>
                  </label>
                  <button type="button" class="change-avatar-btn" @click="triggerAvatarInput">
                    📷 修改头像
                  </button>
                </div>
                <div class="account-info">
                  <div class="display-name">{{ accountDisplayName || authStore.user?.displayName || '未设置' }}</div>
                  <div class="username">@{{ accountUsername || authStore.user?.username || 'unknown' }}</div>
                  <div v-if="usernameModifiable" class="username-badge modifiable">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                      <path d="M5 13l4 4L19 7"/>
                    </svg>
                    可修改
                  </div>
                </div>
              </div>

              <!-- Form Content -->
              <div class="form-content">
                <!-- Username Field -->
                <div class="field-group">
                  <label class="field-label">用户名</label>
                  <input
                    v-model="accountUsername"
                    type="text"
                    placeholder="请输入用户名"
                    class="field-input"
                    :class="{ disabled: !usernameModifiable }"
                    :disabled="!usernameModifiable"
                    autocomplete="username"
                  />
                  <div class="username-tip-row">
                    <span class="tip-text">用户名用于登录，每 30 天只能修改一次</span>
                    <span v-if="!usernameModifiable" class="tip-date">
                      {{ usernameNextModifiableDate?.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }) }} 后可修改
                    </span>
                  </div>
                </div>

                <!-- Display Name Field -->
                <div class="field-group">
                  <label class="field-label">显示名</label>
                  <input
                    v-model="accountDisplayName"
                    type="text"
                    placeholder="请输入显示名"
                    class="field-input"
                    autocomplete="name"
                  />
                  <p class="field-hint">显示名可随时修改</p>
                </div>

                <!-- Email Field -->
                <div class="field-group">
                  <label class="field-label">登录邮箱</label>
                  <input
                    v-model="accountEmail"
                    type="email"
                    placeholder="请输入登录邮箱"
                    class="field-input"
                    autocomplete="email"
                  />
                  <p class="field-hint">邮箱可用于登录</p>
                </div>

                <!-- Save Button -->
                <div class="form-actions">
                  <button class="btn-save" @click="handleAccountSave">
                    保存资料
                  </button>
                </div>
              </div>

              <!-- Success/Error Messages -->
              <Transition name="fade">
                <div v-if="accountSuccess" class="toast success">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                    <path d="M5 13l4 4L19 7"/>
                  </svg>
                  {{ accountSuccess }}
                </div>
              </Transition>

              <Transition name="fade">
                <div v-if="accountError" class="toast error">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                    <circle cx="12" cy="12" r="10"/>
                    <path d="M15 9l-6 6M9 9l6 6"/>
                  </svg>
                  {{ accountError }}
                </div>
              </Transition>
            </div>

            <!-- Password Change Card -->
            <div class="account-card password-card">
              <h2 class="password-card-title">修改密码</h2>

              <!-- Form Content -->
              <div class="form-content">
                <!-- Current Password -->
                <div class="field-group">
                  <label class="field-label">当前密码</label>
                  <div class="password-input-wrapper">
                    <input
                      v-model="currentPassword"
                      :type="showCurrentPassword ? 'text' : 'password'"
                      placeholder="请输入当前密码"
                      class="field-input"
                      autocomplete="current-password"
                      @keyup.enter="handleChangePassword"
                    />
                    <button class="toggle-password-btn" @click="showCurrentPassword = !showCurrentPassword">
                      <svg v-if="!showCurrentPassword" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                        <path d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
                      </svg>
                      <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"/>
                      </svg>
                    </button>
                  </div>
                </div>

                <!-- New Password -->
                <div class="field-group">
                  <label class="field-label">新密码</label>
                  <div class="password-input-wrapper">
                    <input
                      v-model="newPassword"
                      :type="showNewPassword ? 'text' : 'password'"
                      placeholder="请输入新密码"
                      class="field-input"
                      autocomplete="new-password"
                      @keyup.enter="handleChangePassword"
                    />
                    <button class="toggle-password-btn" @click="showNewPassword = !showNewPassword">
                      <svg v-if="!showNewPassword" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                        <path d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
                      </svg>
                      <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"/>
                      </svg>
                    </button>
                  </div>
                </div>

                <!-- Confirm New Password -->
                <div class="field-group">
                  <label class="field-label">确认新密码</label>
                  <div class="password-input-wrapper">
                    <input
                      v-model="confirmPassword"
                      :type="showConfirmPassword ? 'text' : 'password'"
                      placeholder="请再次输入新密码"
                      class="field-input"
                      autocomplete="new-password"
                      @keyup.enter="handleChangePassword"
                    />
                    <button class="toggle-password-btn" @click="showConfirmPassword = !showConfirmPassword">
                      <svg v-if="!showConfirmPassword" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                        <path d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
                      </svg>
                      <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"/>
                      </svg>
                    </button>
                  </div>
                </div>

                <!-- Change Password Button -->
                <div class="form-actions">
                  <button class="btn-save" @click="handleChangePassword" :disabled="passwordLoading">
                    修改密码
                  </button>
                </div>
              </div>

              <!-- Success/Error Messages -->
              <Transition name="fade">
                <div v-if="passwordSuccess" class="toast success">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                    <path d="M5 13l4 4L19 7"/>
                  </svg>
                  {{ passwordSuccess }}
                </div>
              </Transition>

              <Transition name="fade">
                <div v-if="passwordError" class="toast error">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                    <circle cx="12" cy="12" r="10"/>
                    <path d="M15 9l-6 6M9 9l6 6"/>
                  </svg>
                  {{ passwordError }}
                </div>
              </Transition>
            </div>
          </div>

          <!-- AI Settings -->
          <div v-if="activeMenu === 'ai'" class="ai-settings">
            <!-- Page Header -->
            <div class="content-header">
              <h1 class="content-title">AI 配置</h1>
              <p class="content-subtitle">配置你的 AI 服务</p>
            </div>

            <!-- API Key Card -->
            <div class="account-card">
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
                  class="field-input"
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

          <!-- Placeholder for other settings -->
          <div v-if="activeMenu === 'preferences'" class="preferences-settings">
            <div class="content-header">
              <h1 class="content-title">偏好设置</h1>
            </div>

            <!-- Theme Settings -->
            <div class="account-card">
              <h2 class="section-title">主题模式</h2>
              <p class="section-desc">选择应用外观模式</p>

              <div class="theme-selector">
                <button
                  v-for="option in themeOptions"
                  :key="option.id"
                  class="theme-option"
                  :class="{ active: currentTheme === option.id }"
                  @click="handleThemeChange(option.id)"
                >
                  <span class="theme-icon">{{ option.icon }}</span>
                  <span class="theme-label">{{ option.label }}</span>
                </button>
              </div>
            </div>
          </div>

          <div v-if="activeMenu === 'advanced'" class="placeholder-settings">
            <div class="content-header">
              <h1 class="content-title">高级</h1>
              <p class="content-subtitle">暂未开放</p>
            </div>
          </div>
        </main>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* Route Container */
.settings-route {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
}

/* Background */
.settings-background {
  position: absolute;
  inset: 0;
  z-index: 0;
}

/* Overlay - no blur, just semi-transparent */
.settings-overlay {
  position: absolute;
  inset: 0;
  z-index: 10;
  background: var(--overlay-bg);
  transition: background-color 0.25s ease;
}

/* Modal Wrap */
.settings-modal-wrap {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  animation: modalIn 0.28s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes modalIn {
  from {
    opacity: 0;
    transform: translateY(16px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

/* Modal */
.settings-modal {
  width: 1100px;
  height: 85vh;
  border-radius: 28px;
  background: var(--modal-bg);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  box-shadow:
    0 20px 60px rgba(0, 0, 0, 0.15),
    0 0 0 1px var(--border-color);
  overflow: hidden;
  display: grid;
  grid-template-columns: 240px 1fr;
  max-height: 85vh;
  transition: var(--transition-theme);
}

/* Sidebar */
.settings-sidebar {
  background: var(--sidebar-bg);
  border-right: 1px solid var(--border-color);
  padding: 20px 12px;
  display: flex;
  flex-direction: column;
  transition: var(--transition-theme);
}

.sidebar-header {
  padding: 12px 12px 28px;
}

.sidebar-brand {
  font-size: 15px;
  font-weight: 800;
  color: var(--text-primary);
  letter-spacing: -0.03em;
  transition: color 0.25s ease;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.nav-item {
  height: 58px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  font-weight: 600;
  font-size: 14px;
  color: var(--text-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: all 0.2s ease;
  gap: 12px;
}

.nav-item:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
}

.nav-item.active {
  background: var(--bg-secondary);
  color: var(--text-primary);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.08);
}

.nav-icon {
  font-size: 18px;
  opacity: 0.75;
}

.nav-label {
  flex: 1;
  text-align: left;
}

/* Content */
.settings-content {
  padding: 48px 56px 32px;
  position: relative;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.settings-content > div {
  width: 100%;
  max-width: 760px;
  margin: 0 auto;
}

/* Close Button */
.close-btn {
  position: absolute;
  top: 28px;
  right: 28px;
  width: 40px;
  height: 40px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  color: var(--text-muted);
  background: var(--bg-primary);
  border: none;
  cursor: pointer;
  transition: all 0.2s ease;
}

.close-btn:hover {
  background: var(--border-color);
  color: var(--text-primary);
  transform: scale(1.05);
}

/* Content Header */
.content-header {
  margin-bottom: 28px;
}

.content-title {
  font-size: 32px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: -0.03em;
  margin: 0;
  transition: color 0.25s ease;
}

.content-subtitle {
  font-size: 15px;
  color: var(--text-secondary);
  margin: 8px 0 0;
  transition: color 0.25s ease;
}

/* Account Card */
.account-card {
  background: var(--card-bg-alpha);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid var(--border-color);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.06);
  border-radius: 28px;
  padding: 36px 40px;
  margin-bottom: 24px;
  transition: var(--transition-theme);
}

.account-card:hover {
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.08);
}

/* Account Header - Profile Header Style */
.account-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 0 32px;
  margin-bottom: 0;
}

.avatar-column {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.avatar-upload {
  cursor: pointer;
  position: relative;
}

.avatar-input {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  overflow: hidden;
}

.avatar {
  width: 96px;
  height: 96px;
  border-radius: 10px;
  overflow: hidden;
  flex-shrink: 0;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
  border: 1px solid rgba(255, 255, 255, 0.1);
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  position: relative;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.25s ease;
  color: white;
  font-size: 12px;
  font-weight: 500;
}

.avatar-upload:hover .avatar-overlay {
  opacity: 1;
}

.avatar-upload:hover .avatar {
  transform: scale(1.02);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.2);
}

.change-avatar-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  padding: 0 16px;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.change-avatar-btn:hover {
  background: var(--card-bg);
  border-color: var(--accent-blue);
  color: var(--text-primary);
}

.account-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  justify-content: center;
}

.display-name {
  font-size: 40px;
  font-weight: 800;
  color: var(--text-primary);
  letter-spacing: -0.03em;
  line-height: 1.1;
  transition: color 0.25s ease;
}

.username {
  font-size: 18px;
  color: var(--text-secondary);
  font-weight: 400;
  transition: color 0.25s ease;
}

.username-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #ecfdf5;
  color: #059669;
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  margin-top: 6px;
  width: fit-content;
}

/* Password Card */
.password-card {
  margin-top: 24px;
}

.password-card-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 20px;
  transition: color 0.25s ease;
}

/* Form Content */
.form-content {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

/* Password Input */
.password-input-wrapper {
  position: relative;
}

.password-input-wrapper .field-input {
  padding-right: 56px;
  max-width: 520px;
}

.toggle-password-btn {
  position: absolute;
  right: 14px;
  top: 50%;
  transform: translateY(-50%);
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--text-secondary);
  padding: 10px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  transition: all 0.2s ease;
}

.toggle-password-btn:hover {
  color: var(--text-primary);
  background: var(--bg-primary);
}

/* Field Group */
.field-group {
  display: flex;
  flex-direction: column;
  margin-bottom: 20px;
}

.field-label {
  display: block;
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 8px;
  letter-spacing: 0.01em;
  transition: color 0.25s ease;
}

.field-input {
  width: 100%;
  max-width: 520px;
  height: 56px;
  padding: 0 18px;
  border-radius: 16px;
  border: 1px solid var(--border-color);
  background: var(--input-bg);
  font-size: 16px;
  font-weight: 500;
  color: var(--text-primary);
  outline: none;
  transition: var(--transition-theme);
}

.field-input:focus {
  border-color: var(--accent-blue);
  background: var(--input-bg);
  box-shadow: 0 0 0 4px rgba(79, 125, 243, 0.08);
}

.field-input::placeholder {
  color: var(--text-secondary);
  font-weight: 400;
}

.field-input.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.field-hint {
  font-size: 13px;
  color: var(--text-secondary);
  margin-top: 6px;
  margin-bottom: 0;
  transition: color 0.25s ease;
}

.username-tip-row {
  display: flex;
  align-items: center;
  margin-top: 6px;
  font-size: 13px;
}

.username-tip-row .tip-text {
  color: var(--text-secondary);
}

.username-tip-row .tip-date {
  color: var(--text-muted);
  margin-left: 4px;
  flex-shrink: 0;
}

/* Form Actions */
.form-actions {
  width: 100%;
  display: flex;
  justify-content: flex-end;
  margin-top: 24px;
}

.btn-save {
  width: 140px;
  height: 50px;
  padding: 0 28px;
  border-radius: 16px;
  background: var(--button-bg);
  color: var(--button-text);
  font-size: 16px;
  font-weight: 700;
  border: none;
  cursor: pointer;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.btn-save:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.15);
}

.btn-save:active:not(:disabled) {
  transform: scale(0.98);
}

.btn-save:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

/* Toast Messages */
.toast {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 500;
}

.toast.success {
  background: #f0fdf4;
  color: #059669;
  border: 1px solid #bbf7d0;
}

.toast.error {
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
}

/* API Key Card */
.api-key-header {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 20px;
}

.api-key-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background: linear-gradient(135deg, #1e3a5f 0%, #2d5a87 100%);
  display: grid;
  place-items: center;
  color: #f0d78c;
  flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(30, 58, 95, 0.25);
}

.api-key-info {
  flex: 1;
}

.api-key-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 6px;
  transition: color 0.25s ease;
}

.api-key-desc {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
  transition: color 0.25s ease;
}

/* API Key Input */
.api-key-input-wrapper {
  position: relative;
  margin-bottom: 16px;
}

.api-key-input-wrapper .field-input {
  padding-right: 56px;
  max-width: 520px;
}

.toggle-visibility-btn {
  position: absolute;
  right: 14px;
  top: 50%;
  transform: translateY(-50%);
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--text-secondary);
  padding: 10px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  transition: all 0.2s ease;
}

.toggle-visibility-btn:hover {
  color: var(--text-primary);
  background: var(--bg-primary);
}

/* API Key Actions */
.api-key-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

.btn-clear {
  width: 140px;
  height: 50px;
  border-radius: 16px;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  background: var(--bg-primary);
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
}

.btn-clear:hover:not(:disabled) {
  background: var(--border-color);
  color: var(--text-primary);
}

.btn-clear:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* API Key Status */
.api-key-status {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid var(--border-color);
  font-size: 14px;
  color: var(--text-secondary);
  transition: var(--transition-theme);
}

.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #d1d5db;
}

.status-dot.active {
  background: #059669;
  box-shadow: 0 0 8px rgba(5, 150, 105, 0.4);
}

/* Info Card */
.info-card {
  margin-top: 20px;
}

.info-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 18px;
  transition: color 0.25s ease;
}

.info-list {
  margin: 0;
  padding: 0 0 0 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-list li {
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.5;
  transition: color 0.25s ease;
}

.info-link {
  color: var(--accent-blue);
  text-decoration: none;
  font-weight: 500;
  transition: color 0.25s ease;
}

.info-link:hover {
  text-decoration: underline;
}

/* Placeholder */
.placeholder-settings {
  display: flex;
  flex-direction: column;
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
  .settings-overlay {
    background: rgba(0, 0, 0, 0.6);
  }

  .settings-modal {
    background: rgba(17, 19, 24, 0.92);
    backdrop-filter: blur(24px);
    -webkit-backdrop-filter: blur(24px);
    box-shadow:
      0 20px 60px rgba(0, 0, 0, 0.4),
      0 0 0 1px rgba(255, 255, 255, 0.08);
  }

  .settings-sidebar {
    background: linear-gradient(180deg, #0d0f14 0%, #111318 100%);
    border-right: 1px solid rgba(255, 255, 255, 0.08);
  }

  .sidebar-brand {
    color: #f5f7fa;
  }

  .nav-item {
    color: #64748b;
  }

  .nav-item:hover {
    background: rgba(255, 255, 255, 0.06);
    color: #f5f7fa;
  }

  .nav-item.active {
    background: #1a1d24;
    color: #f5f7fa;
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
  }

  .nav-icon {
    opacity: 0.85;
  }

  .close-btn {
    color: #888;
    background: rgba(255, 255, 255, 0.06);
  }

  .close-btn:hover {
    background: rgba(255, 255, 255, 0.1);
    color: #f5f7fa;
  }

  .content-title {
    color: #f5f7fa;
  }

  .content-subtitle {
    color: #888;
  }

  .account-card {
    background: rgba(26, 29, 36, 0.8);
    border: 1px solid rgba(255, 255, 255, 0.08);
    box-shadow: 0 10px 40px rgba(0, 0, 0, 0.2);
  }

  .account-card:hover {
    box-shadow: 0 16px 48px rgba(0, 0, 0, 0.25);
  }

  .display-name {
    color: #f5f7fa;
  }

  .username {
    color: #888;
  }

  .username-badge {
    background: rgba(5, 150, 105, 0.15);
    color: #34d399;
  }

  .password-card-title {
    color: #f5f7fa;
  }

  .field-label {
    color: #9ca3af;
  }

  .field-input {
    background: rgba(35, 39, 52, 0.85);
    border-color: rgba(255, 255, 255, 0.1);
    color: #f5f7fa;
  }

  .field-input:focus {
    border-color: #6366f1;
    background: rgba(35, 39, 52, 0.95);
    box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.15);
  }

  .field-input::placeholder {
    color: #6b7280;
  }

  .field-input.disabled {
    opacity: 0.4;
  }

  .field-hint {
    color: #6b7280;
  }

  .toggle-password-btn {
    color: #6b7280;
  }

  .toggle-password-btn:hover {
    color: #9ca3af;
    background: rgba(255, 255, 255, 0.06);
  }

  .toast.success {
    background: rgba(5, 150, 105, 0.15);
    color: #34d399;
    border-color: rgba(52, 211, 153, 0.3);
  }

  .toast.error {
    background: rgba(220, 38, 38, 0.15);
    color: #f87171;
    border-color: rgba(248, 113, 113, 0.3);
  }

  .btn-save {
    background: linear-gradient(135deg, #f5f7fa, #e5e7eb);
    color: #111;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
  }

  .btn-save:hover:not(:disabled) {
    box-shadow: 0 10px 24px rgba(0, 0, 0, 0.25);
  }

  .btn-clear {
    background: rgba(35, 39, 52, 0.85);
    color: #9ca3af;
    border-color: rgba(255, 255, 255, 0.1);
  }

  .btn-clear:hover:not(:disabled) {
    background: rgba(45, 49, 66, 0.95);
    border-color: rgba(255, 255, 255, 0.15);
  }

  .api-key-icon {
    background: linear-gradient(135deg, #1e3a5f 0%, #2d5a87 100%);
  }

  .api-key-title {
    color: #f5f7fa;
  }

  .api-key-desc {
    color: #888;
  }

  .api-key-status {
    border-top-color: rgba(255, 255, 255, 0.08);
    color: #888;
  }

  .info-title {
    color: #f5f7fa;
  }

  .info-list li {
    color: #888;
  }

  .info-link {
    color: #818cf8;
  }
}

/* Theme Selector - Segmented Control Style */
.section-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 6px;
  transition: color 0.25s ease;
}

.section-desc {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0 0 20px;
  transition: color 0.25s ease;
}

.theme-selector {
  display: flex;
  gap: 12px;
  align-items: center;
}

.theme-option {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 40px;
  padding: 0 20px;
  border-radius: 10px;
  border: 1px solid var(--border-color);
  background: var(--card-bg);
  cursor: pointer;
  transition: all 0.2s ease;
  min-width: 108px;
}

.theme-option:hover {
  background: var(--bg-primary);
  border-color: var(--accent-blue);
}

.theme-option.active {
  background: linear-gradient(135deg, var(--color-space), var(--color-space-light));
  border-color: var(--color-space);
  color: var(--color-gold-light);
  box-shadow: 0 2px 8px rgba(17, 24, 39, 0.15);
}

.theme-icon {
  font-size: 18px;
  line-height: 1;
}

.theme-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
  transition: color 0.2s ease;
}

.theme-option.active .theme-label {
  color: white;
}

/* Dark Mode - both prefers-color-scheme and data-theme */
@media (prefers-color-scheme: dark) {
  html[data-theme="dark"] .settings-overlay {
    background: rgba(0, 0, 0, 0.6);
  }

  html[data-theme="dark"] .settings-modal {
    background: rgba(17, 19, 24, 0.92);
    backdrop-filter: blur(24px);
    -webkit-backdrop-filter: blur(24px);
    box-shadow:
      0 20px 60px rgba(0, 0, 0, 0.4),
      0 0 0 1px rgba(255, 255, 255, 0.08);
  }

  html[data-theme="dark"] .settings-sidebar {
    background: linear-gradient(180deg, #0d0f14 0%, #111318 100%);
    border-right: 1px solid rgba(255, 255, 255, 0.08);
  }

  html[data-theme="dark"] .sidebar-brand {
    color: #f5f7fa;
  }

  html[data-theme="dark"] .nav-item {
    color: #64748b;
  }

  html[data-theme="dark"] .nav-item:hover {
    background: rgba(255, 255, 255, 0.06);
    color: #f5f7fa;
  }

  html[data-theme="dark"] .nav-item.active {
    background: #1a1d24;
    color: #f5f7fa;
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
  }

  html[data-theme="dark"] .nav-icon {
    opacity: 0.85;
  }

  html[data-theme="dark"] .close-btn {
    color: #888;
    background: rgba(255, 255, 255, 0.06);
  }

  html[data-theme="dark"] .close-btn:hover {
    background: rgba(255, 255, 255, 0.1);
    color: #f5f7fa;
  }

  html[data-theme="dark"] .content-title {
    color: #f5f7fa;
  }

  html[data-theme="dark"] .content-subtitle {
    color: #888;
  }

  html[data-theme="dark"] .account-card {
    background: rgba(26, 29, 36, 0.8);
    border: 1px solid rgba(255, 255, 255, 0.08);
    box-shadow: 0 10px 40px rgba(0, 0, 0, 0.2);
  }

  html[data-theme="dark"] .account-card:hover {
    box-shadow: 0 16px 48px rgba(0, 0, 0, 0.25);
  }

  html[data-theme="dark"] .display-name {
    color: #f5f7fa;
  }

  html[data-theme="dark"] .username {
    color: #888;
  }

  html[data-theme="dark"] .username-badge {
    background: rgba(5, 150, 105, 0.15);
    color: #34d399;
  }

  html[data-theme="dark"] .password-card-title {
    color: #f5f7fa;
  }

  html[data-theme="dark"] .field-label {
    color: #9ca3af;
  }

  html[data-theme="dark"] .field-input {
    background: rgba(35, 39, 52, 0.85);
    border-color: rgba(255, 255, 255, 0.1);
    color: #f5f7fa;
  }

  html[data-theme="dark"] .field-input:focus {
    border-color: #6366f1;
    background: rgba(35, 39, 52, 0.95);
    box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.15);
  }

  html[data-theme="dark"] .field-input::placeholder {
    color: #6b7280;
  }

  html[data-theme="dark"] .field-input.disabled {
    opacity: 0.4;
  }

  html[data-theme="dark"] .field-hint {
    color: #6b7280;
  }

  html[data-theme="dark"] .toggle-password-btn {
    color: #6b7280;
  }

  html[data-theme="dark"] .toggle-password-btn:hover {
    color: #9ca3af;
    background: rgba(255, 255, 255, 0.06);
  }

  html[data-theme="dark"] .toast.success {
    background: rgba(5, 150, 105, 0.15);
    color: #34d399;
    border-color: rgba(52, 211, 153, 0.3);
  }

  html[data-theme="dark"] .toast.error {
    background: rgba(220, 38, 38, 0.15);
    color: #f87171;
    border-color: rgba(248, 113, 113, 0.3);
  }

  html[data-theme="dark"] .btn-save {
    background: linear-gradient(135deg, #f5f7fa, #e5e7eb);
    color: #111;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
  }

  html[data-theme="dark"] .btn-save:hover:not(:disabled) {
    box-shadow: 0 10px 24px rgba(0, 0, 0, 0.25);
  }

  html[data-theme="dark"] .btn-clear {
    background: rgba(35, 39, 52, 0.85);
    color: #9ca3af;
    border-color: rgba(255, 255, 255, 0.1);
  }

  html[data-theme="dark"] .btn-clear:hover:not(:disabled) {
    background: rgba(45, 49, 66, 0.95);
    border-color: rgba(255, 255, 255, 0.15);
  }

  html[data-theme="dark"] .api-key-icon {
    background: linear-gradient(135deg, #1e3a5f 0%, #2d5a87 100%);
  }

  html[data-theme="dark"] .api-key-title {
    color: #f5f7fa;
  }

  html[data-theme="dark"] .api-key-desc {
    color: #888;
  }

  html[data-theme="dark"] .api-key-status {
    border-top-color: rgba(255, 255, 255, 0.08);
    color: #888;
  }

  html[data-theme="dark"] .info-title {
    color: #f5f7fa;
  }

  html[data-theme="dark"] .info-list li {
    color: #888;
  }

  html[data-theme="dark"] .info-link {
    color: #818cf8;
  }

  /* Theme Selector Dark Mode */
  html[data-theme="dark"] .section-title {
    color: #f5f7fa;
  }

  html[data-theme="dark"] .section-desc {
    color: #888;
  }

  html[data-theme="dark"] .theme-option {
    background: rgba(35, 39, 52, 0.85);
    border-color: rgba(255, 255, 255, 0.1);
  }

  html[data-theme="dark"] .theme-option:hover {
    background: rgba(45, 49, 66, 0.95);
    border-color: rgba(255, 255, 255, 0.15);
  }

  html[data-theme="dark"] .theme-option.active {
    background: linear-gradient(135deg, #f5f7fa, #e5e7eb);
    border-color: #f5f7fa;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
  }

  html[data-theme="dark"] .theme-option.active .theme-label {
    color: #111;
  }

  html[data-theme="dark"] .theme-label {
    color: #9ca3af;
  }
}
</style>
