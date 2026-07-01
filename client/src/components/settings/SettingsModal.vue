<script setup lang="ts">
// SettingsModal: 聚合账户、偏好、AI 配置与高级选项的左侧栏 + 右侧内容布局弹窗。
// 由 settingsStore.settingsModalOpen 控制显隐；外部触发（如缺 API Key 提示）可写入 pendingTab 让弹窗落到指定 tab。
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useSettingsStore } from '@/stores/settings'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import { useToast } from '@/composables/useToast'
import { changePassword } from '@/api/auth'
import { evaluatePassword } from '@/composables/usePasswordStrength'
import { BRAND_LOGO } from '@/constants/brand'

// TabKey: 强类型约束，避免与 store / 模板里的字符串拼接散落。
type TabKey = 'account' | 'preferences' | 'ai' | 'advanced' | 'privacy' | 'terms'

const router = useRouter()
const authStore = useAuthStore()
const themeStore = useThemeStore()
const settingsStore = useSettingsStore()
const toast = useToast()

const activeTab = ref<TabKey>('account')

// consumePendingTab 是“读后即清”的语义：调用方（如缺 Key 弹窗）只能把用户跳一次到目标 tab，
// 之后再开设置就不会被旧请求劫持——所以这里不在 watch 里轮询，只在 mount 时取一次。
const validTabs: TabKey[] = ['account', 'preferences', 'ai', 'advanced', 'privacy', 'terms']
const requested = settingsStore.consumePendingTab()
if (requested && (validTabs as string[]).includes(requested)) {
  activeTab.value = requested as TabKey
}

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
const showClearApiKeyConfirm = ref(false)

// API Key state
const apiKey = ref('')
const loading = ref(false)

// 修改密码表单状态：与「个人资料」独立的两张卡片，避免表单错误相互干扰。
// 三个字段都是 ref 而非 reactive 对象：保持与项目其他表单（如 LoginView）风格一致。
const passwordForm = ref({
  currentPassword: '',
  newPassword: '',
  confirmNewPassword: ''
})
// 改密提交期间禁用按钮，防止重复点击产生并发 PATCH 请求。
const changingPassword = ref(false)
// 改密专用的错误 message（与 saveError 独立，避免与个人资料的错误串扰）。
const passwordChangeError = ref('')
const passwordChangeSuccess = ref(false)

// 实时评估新密码强度：复用 usePasswordStrength 与注册场景同源 composable，
// 保证前后端校验规则（8 字符 + 字母 + 数字 + 30 条黑名单）严格一致。
const newPasswordStrength = computed(() => evaluatePassword(passwordForm.value.newPassword))
const strengthBarClass = computed(() => ({
  'is-weak': newPasswordStrength.value.score === 1,
  'is-medium': newPasswordStrength.value.score === 2,
  'is-strong': newPasswordStrength.value.score === 3
}))

// Theme state
const selectedTheme = ref<'system' | 'light' | 'dark'>('system')

// Tabs definition
const settingTabs = [
  { key: 'account' as TabKey, label: '账户设置', icon: 'user' },
  { key: 'preferences' as TabKey, label: '偏好设置', icon: 'settings' },
  { key: 'ai' as TabKey, label: 'AI 配置', icon: 'bot' },
  { key: 'advanced' as TabKey, label: '高级', icon: 'wrench' },
  { key: 'privacy' as TabKey, label: '隐私条款', icon: 'shield' },
  { key: 'terms' as TabKey, label: '服务条款', icon: 'file-text' }
]

const tabTitles: Record<TabKey, { title: string; desc: string }> = {
  account: { title: '账户设置', desc: '管理你的个人资料和账户信息' },
  preferences: { title: '偏好设置', desc: '自定义界面外观和行为' },
  ai: { title: 'AI 配置', desc: '配置 AI 服务和 API 设置' },
  advanced: { title: '高级设置', desc: '高级选项和开发者设置' },
  privacy: { title: '隐私条款', desc: '我们如何收集、使用和保护你的数据' },
  terms: { title: '服务条款', desc: '你与平台之间的使用约定' }
}

// Computed
// canChangeUsername: 用户名是稳定身份标识，后端策略限定 30 天内只能改一次；前端禁用输入框避免无效请求。
const canChangeUsername = computed(() => {
  if (!authStore.user?.lastUsernameChangeAt) return true
  const lastChange = new Date(authStore.user.lastUsernameChangeAt)
  const daysSinceChange = (Date.now() - lastChange.getTime()) / (1000 * 60 * 60 * 24)
  return daysSinceChange >= 30
})

const hasChanges = computed(() => {
  if (!authStore.user) return false
  return accountForm.value.username !== authStore.user.username ||
         accountForm.value.displayName !== authStore.user.displayName ||
         accountForm.value.email !== authStore.user.email ||
         avatarFile.value !== null
})

// loadData: 进入弹窗时一次性拉取 API Key 状态 + 个人资料 + 当前主题，用于回填表单与状态展示。
// 注意 fetchApiKey 与 fetchProfile 串行是因为两者都会改 store，串行避免响应交叉覆盖 UI 状态。
async function loadData() {
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
}

onMounted(() => {
  loadData()
  window.addEventListener('keydown', handleEsc)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleEsc)
})

function handleEsc(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    emit('close')
  }
}

// 监听 user 变化：账户页可能由其他视图（顶部栏、抽屉）更新资料后回到设置页，
// watch + immediate 保证表单始终与服务端最新值同步，避免脏表单被误提交。
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

// Avatar handling
// handleAvatarChange: 前端先做 5MB 限制，避免无意义的上传带宽浪费；
// URL.createObjectObjectURL 生成的本地预览 URL 在保存或关闭弹窗时由浏览器释放，无需手动 revoke。
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

// saveAccount: 头像必须先于资料保存——只有拿到后端返回的新 URL 后，写库才不会出现“资料里引用旧头像 URL”的不一致。
// 成功后 2 秒自动隐藏提示，避免常驻 UI 干扰后续操作。
async function saveAccount() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false

  try {
    if (avatarFile.value) {
      const result = await authStore.uploadAvatar(avatarFile.value)
      if (!result.success) {
        saveError.value = result.error || '头像上传失败'
        saving.value = false
        return
      }
      accountForm.value.avatarUrl = result.avatarUrl!
    }

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
// handleSaveApiKey: 设置失败时回退到通用“保存失败”——避免向用户暴露后端原始错误，便于调试收敛。
async function handleSaveApiKey() {
  try {
    await settingsStore.setApiKey(apiKey.value)
    saveSuccess.value = true
    setTimeout(() => { saveSuccess.value = false }, 2000)
  } catch (e) {
    saveError.value = '保存失败'
  }
}

// handleClearApiKey: 删除是不可逆操作，必须经 ConfirmDialog 二次确认，避免误触导致 AI 角色不可用。
async function handleClearApiKey() {
  showClearApiKeyConfirm.value = true
}

async function confirmClearApiKey() {
  try {
    await settingsStore.clearApiKey()
    apiKey.value = ''
    toast.success('已清除 API Key')
    showClearApiKeyConfirm.value = false
  } catch (e: any) {
    toast.error(e?.message || '清除失败')
  }
}

// handleChangePassword: 三道本地校验 → 调 changePassword → 强制重登。
// 校验顺序按「用户最容易犯错」排列：空字段 > 两次不一致 > 强度不足，让最直观的错误先暴露。
// 改密成功后调用 authStore.logout()：触发上一轮已实现的 navigator.credentials.preventSilentAccess，
// 同时清掉 localStorage 中的 token，避免旧会话被劫持后还能继续操作。
async function handleChangePassword() {
  passwordChangeError.value = ''

  if (!passwordForm.value.currentPassword) {
    passwordChangeError.value = '请输入当前密码'
    return
  }
  if (!passwordForm.value.newPassword) {
    passwordChangeError.value = '请输入新密码'
    return
  }
  if (passwordForm.value.newPassword !== passwordForm.value.confirmNewPassword) {
    passwordChangeError.value = '两次输入的新密码不一致'
    return
  }
  // 强度校验：与后端 @StrongPassword 对齐；score<3 即拒绝
  if (newPasswordStrength.value.score < 3) {
    passwordChangeError.value = newPasswordStrength.value.message
    return
  }

  changingPassword.value = true

  try {
    await changePassword({
      currentPassword: passwordForm.value.currentPassword,
      newPassword: passwordForm.value.newPassword
    })
    // 成功：清空表单 + toast + 强制重登（authStore.logout 内已含 preventSilentAccess）
    passwordForm.value = { currentPassword: '', newPassword: '', confirmNewPassword: '' }
    passwordChangeSuccess.value = true
    setTimeout(() => { passwordChangeSuccess.value = false }, 2000)
    toast.success('密码修改成功，请重新登录')
    // 短暂延迟让用户看到 toast 再跳走；同步清掉 store 状态与跳转
    setTimeout(() => {
      authStore.logout()
      settingsStore.closeSettings()
      router.push('/login')
    }, 800)
  } catch (e: any) {
    // 优先展示后端 message（如「当前密码错误」/「密码强度不足…」），其次通用兜底
    passwordChangeError.value = e?.response?.data?.message || '密码修改失败，请稍后重试'
  } finally {
    changingPassword.value = false
  }
}

const emit = defineEmits<{
  close: []
}>()

// handleClose: 既要通知父组件（关闭弹窗 + 路由后退），也要同步全局 store 标志位，
// 否则下次打开会因 store 仍为 open 状态而 v-if 永远命中（与 Teleport 配合时容易踩坑）。
function handleClose() {
  settingsStore.closeSettings()
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="settingsStore.settingsModalOpen" class="settings-overlay" @click.self="handleClose">
      <div class="settings-modal">
        <!-- 左侧边栏 -->
        <aside class="settings-sidebar">
          <div class="sidebar-header">
            <h1 class="sidebar-brand">Idea Party</h1>
            <span class="sidebar-subtitle">设置</span>
          </div>
          <nav class="sidebar-nav">
            <button
              v-for="tab in settingTabs"
              :key="tab.key"
              class="nav-item"
              :class="{ active: activeTab === tab.key }"
              @click="activeTab = tab.key"
            >
              <svg v-if="tab.icon === 'user'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
                <circle cx="12" cy="7" r="4"/>
              </svg>
              <svg v-else-if="tab.icon === 'settings'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="3"/>
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>
              </svg>
              <svg v-else-if="tab.icon === 'bot'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 8V4H8"/>
                <rect x="3" y="8" width="18" height="12" rx="2"/>
                <path d="M8 12h.01M12 12h.01M16 12h.01"/>
              </svg>
              <svg v-else-if="tab.icon === 'wrench'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>
              </svg>
              <svg v-else-if="tab.icon === 'shield'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
              </svg>
              <svg v-else-if="tab.icon === 'file-text'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                <polyline points="14 2 14 8 20 8"/>
                <line x1="8" y1="13" x2="16" y2="13"/>
                <line x1="8" y1="17" x2="16" y2="17"/>
                <line x1="8" y1="9" x2="10" y2="9"/>
              </svg>
              <span>{{ tab.label }}</span>
            </button>
          </nav>
        </aside>

        <!-- 右侧内容 -->
        <main class="settings-content">
          <!-- 头部:privacy/terms tab 用 iframe 内嵌完整页面,自带标题,这里重复显示,直接隐藏 -->
          <header v-if="activeTab !== 'privacy' && activeTab !== 'terms'" class="content-header">
            <div class="header-text">
              <h2 class="header-title">{{ tabTitles[activeTab].title }}</h2>
              <p class="header-desc">{{ tabTitles[activeTab].desc }}</p>
            </div>
            <button class="close-btn" @click="handleClose">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M18 6L6 18M6 6l12 12"/>
              </svg>
            </button>
          </header>

          <!-- 可滚动内容区 -->
          <div class="content-body">
            <!-- 账号设置 -->
            <div v-if="activeTab === 'account'" class="settings-section">
              <div class="section-card">
                <h3 class="card-title">个人资料</h3>

                <!-- 头像 -->
                <div class="avatar-section">
                  <div class="avatar-wrapper">
                    <img
                      :src="avatarPreview || accountForm.avatarUrl || BRAND_LOGO"
                      alt="Avatar"
                      class="avatar-img"
                    />
                    <label class="avatar-edit">
                      <input type="file" accept="image/jpeg,image/png,image/webp" @change="handleAvatarChange" hidden />
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                        <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                      </svg>
                    </label>
                  </div>
                  <div class="avatar-info">
                    <p class="avatar-hint">支持 jpg/png/webp，最大 5MB</p>
                  </div>
                </div>

                <!-- 用户名 -->
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

                <!-- 显示名 -->
                <div class="field-group">
                  <label class="field-label">显示名</label>
                  <input
                    v-model="accountForm.displayName"
                    class="field-input"
                    placeholder="1-50位字符"
                  />
                </div>

                <!-- 邮箱 -->
                <div class="field-group">
                  <label class="field-label">邮箱</label>
                  <input
                    v-model="accountForm.email"
                    type="email"
                    class="field-input"
                    placeholder="example@domain.com"
                  />
                </div>

                <!-- 保存按钮 -->
                <div class="form-actions">
                  <button
                    class="btn-save"
                    @click="saveAccount"
                    :disabled="saving || !hasChanges"
                  >
                    {{ saving ? '保存中...' : '保存更改' }}
                  </button>
                </div>

                <!-- 提示消息 -->
                <Transition name="fade">
                  <div v-if="saveSuccess" class="toast success">保存成功</div>
                </Transition>
                <Transition name="fade">
                  <div v-if="saveError" class="toast error">{{ saveError }}</div>
                </Transition>
              </div>

              <!-- 修改密码（独立卡片，与个人资料表单解耦，避免错误信息互相覆盖） -->
              <div class="section-card">
                <h3 class="card-title">修改密码</h3>
                <p class="card-desc">修改成功后需要重新登录。新密码需至少 8 位、含字母和数字。</p>

                <!-- 当前密码 -->
                <div class="field-group">
                  <label class="field-label">当前密码</label>
                  <input
                    v-model="passwordForm.currentPassword"
                    type="password"
                    class="field-input"
                    placeholder="请输入当前密码"
                    autocomplete="current-password"
                  />
                </div>

                <!-- 新密码 -->
                <div class="field-group">
                  <label class="field-label">新密码</label>
                  <input
                    v-model="passwordForm.newPassword"
                    type="password"
                    class="field-input"
                    placeholder="至少 8 位，含字母和数字"
                    autocomplete="new-password"
                  />
                  <!-- 实时强度条：复用 usePasswordStrength composable，颜色规则与 LoginView 注册模式一致 -->
                  <div class="pwd-strength" :data-score="newPasswordStrength.score">
                    <div class="pwd-strength-bar">
                      <div class="pwd-strength-bar-fill" :class="strengthBarClass"></div>
                    </div>
                    <p class="pwd-strength-hint" :class="strengthBarClass">{{ newPasswordStrength.message }}</p>
                  </div>
                </div>

                <!-- 确认新密码 -->
                <div class="field-group">
                  <label class="field-label">确认新密码</label>
                  <input
                    v-model="passwordForm.confirmNewPassword"
                    type="password"
                    class="field-input"
                    placeholder="再次输入新密码"
                    autocomplete="new-password"
                  />
                </div>

                <!-- 改密按钮 -->
                <div class="form-actions">
                  <button
                    class="btn-save"
                    @click="handleChangePassword"
                    :disabled="changingPassword"
                  >
                    {{ changingPassword ? '修改中...' : '修改密码' }}
                  </button>
                </div>

                <!-- 改密提示消息 -->
                <Transition name="fade">
                  <div v-if="passwordChangeSuccess" class="toast success">密码修改成功，正在跳转登录…</div>
                </Transition>
                <Transition name="fade">
                  <div v-if="passwordChangeError" class="toast error">{{ passwordChangeError }}</div>
                </Transition>
              </div>
            </div>

            <!-- 偏好设置 -->
            <div v-else-if="activeTab === 'preferences'" class="settings-section">
              <div class="section-card">
                <h3 class="card-title">主题模式</h3>
                <p class="card-desc">选择你的界面外观</p>

                <div class="theme-options">
                  <button
                    class="theme-option"
                    :class="{ active: selectedTheme === 'system' }"
                    @click="handleThemeChange('system')"
                  >
                    <div class="theme-icon">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
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
                    </div>
                    <div class="theme-text">
                      <span class="theme-title">跟随系统</span>
                      <span class="theme-desc">根据系统外观自动切换</span>
                    </div>
                    <div v-if="selectedTheme === 'system'" class="theme-check">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                        <polyline points="20 6 9 17 4 12"/>
                      </svg>
                    </div>
                  </button>

                  <button
                    class="theme-option"
                    :class="{ active: selectedTheme === 'light' }"
                    @click="handleThemeChange('light')"
                  >
                    <div class="theme-icon">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
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
                    </div>
                    <div class="theme-text">
                      <span class="theme-title">浅色模式</span>
                      <span class="theme-desc">始终使用明亮界面</span>
                    </div>
                    <div v-if="selectedTheme === 'light'" class="theme-check">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                        <polyline points="20 6 9 17 4 12"/>
                      </svg>
                    </div>
                  </button>

                  <button
                    class="theme-option"
                    :class="{ active: selectedTheme === 'dark' }"
                    @click="handleThemeChange('dark')"
                  >
                    <div class="theme-icon">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
                      </svg>
                    </div>
                    <div class="theme-text">
                      <span class="theme-title">深色模式</span>
                      <span class="theme-desc">始终使用暗色界面</span>
                    </div>
                    <div v-if="selectedTheme === 'dark'" class="theme-check">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                        <polyline points="20 6 9 17 4 12"/>
                      </svg>
                    </div>
                  </button>
                </div>
              </div>
            </div>

            <!-- AI Config -->
            <div v-else-if="activeTab === 'ai'" class="settings-section">
              <div class="section-card">
                <h3 class="card-title">DeepSeek API Key</h3>
                <p class="card-desc">用于 AI 角色对话的 API 密钥</p>

                <div class="field-group">
                  <div class="password-input-wrapper">
                    <input
                      v-model="apiKey"
                      :type="settingsStore.showApiKey ? 'text' : 'password'"
                      placeholder="sk-..."
                      class="field-input"
                    />
                    <button class="toggle-password-btn" @click="settingsStore.toggleShowKey" type="button">
                      <svg v-if="!settingsStore.showApiKey" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                        <path d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
                      </svg>
                      <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"/>
                      </svg>
                    </button>
                  </div>
                </div>

                <div class="api-key-actions">
                  <button class="btn-save" @click="handleSaveApiKey" :disabled="loading">
                    保存
                  </button>
                  <button
                    class="btn-clear"
                    @click="handleClearApiKey"
                    :disabled="!settingsStore.hasApiKey || loading"
                  >
                    清除
                  </button>
                </div>

                <div class="api-key-status">
                  <div class="status-dot" :class="{ active: settingsStore.hasApiKey }"></div>
                  <span>{{ settingsStore.hasApiKey ? 'API Key 已配置' : 'API Key 未配置' }}</span>
                </div>

                <div class="info-box">
                  <h4>如何获取 API Key?</h4>
                  <ol>
                    <li>访问 <a href="https://platform.deepseek.com" target="_blank">DeepSeek 开放平台</a></li>
                    <li>注册账号并完成认证</li>
                    <li>在 API Keys 页面创建一个新的 Key</li>
                    <li>复制并粘贴到上方输入框</li>
                  </ol>
                </div>
              </div>
            </div>

            <!-- 高级设置 -->
            <div v-else-if="activeTab === 'advanced'" class="settings-section">
              <div class="section-card">
                <h3 class="card-title">高级设置</h3>
                <p class="card-desc">高级选项和开发者设置</p>

                <div class="advanced-info">
                  <p>更多高级设置正在开发中...</p>
                </div>
              </div>
            </div>

            <!-- 隐私条款 tab:复用 /privacy 路由(注册页同款),不在内部弹窗渲染,
                 而是用 iframe 内嵌,保持"在设置里看完就走"的一站式体验。
                 ?embed=1 让 LegalLayout 隐藏顶栏的 logo 和"返回登录",避免在弹窗里多余 -->
            <div v-else-if="activeTab === 'privacy'" class="settings-section">
              <div class="section-card privacy-card">
                <iframe
                  src="/privacy?embed=1"
                  class="privacy-iframe"
                  title="隐私政策"
                ></iframe>
              </div>
            </div>

            <!-- 服务条款 tab:与隐私 tab 同款处理,用 iframe 内嵌 /terms。
                 复用 privacy-card / privacy-iframe 样式,避免重复定义。 -->
            <div v-else-if="activeTab === 'terms'" class="settings-section">
              <div class="section-card privacy-card">
                <iframe
                  src="/terms?embed=1"
                  class="privacy-iframe"
                  title="服务条款"
                ></iframe>
              </div>
            </div>
          </div>
        </main>
      </div>
    </div>
  </Teleport>

  <ConfirmDialog
    :show="showClearApiKeyConfirm"
    title="清除 API Key"
    message="确定要清除 API Key 吗？清除后使用 AI 角色对话将需要重新设置。"
    confirm-text="清除"
    cancel-text="取消"
    @confirm="confirmClearApiKey"
    @cancel="showClearApiKeyConfirm = false"
  />
</template>

<style scoped>
/*** CSS Variables for Light Mode ***/
.settings-overlay {
  --modal-bg: rgba(255, 255, 255, 0.98);
  --sidebar-bg: rgba(248, 250, 252, 0.95);
  --content-bg: rgba(255, 255, 255, 0.95);
  --card-bg: #ffffff;
  --text-primary: #0f172a;
  --text-secondary: #475569;
  --text-muted: #94a3b8;
  --border-color: rgba(226, 232, 240, 0.9);
  --nav-item-hover: rgba(241, 245, 249, 0.8);
  --nav-item-active-bg: #1e293b;
  --nav-item-active-text: #ffffff;
  --theme-card-bg: #ffffff;
  --theme-card-border: rgba(226, 232, 240, 0.9);
  --theme-card-active-bg: #1e293b;
  --theme-card-active-border: #1e293b;
  --input-bg: #ffffff;
  --input-border: rgba(226, 232, 240, 0.9);
  --btn-primary-bg: #1e293b;
  --btn-primary-text: #ffffff;
  --btn-secondary-bg: transparent;
  --btn-secondary-border: rgba(226, 232, 240, 0.9);
  --info-box-bg: linear-gradient(135deg, #fefce8 0%, #fef9c3 100%);
  --info-box-border: #fde047;
  --info-box-text: #0f172a;
  --close-btn-hover-bg: rgba(241, 245, 249, 0.8);
}

/*** Dark Mode Variables ***/
.dark .settings-overlay {
  --modal-bg: rgba(17, 24, 39, 0.98);
  --sidebar-bg: rgba(15, 23, 42, 0.98);
  --content-bg: rgba(17, 24, 39, 0.95);
  --card-bg: rgba(30, 41, 59, 0.9);
  --text-primary: #f8fafc;
  --text-secondary: #94a3b8;
  --text-muted: #64748b;
  --border-color: rgba(71, 85, 105, 0.8);
  --nav-item-hover: rgba(30, 41, 59, 0.9);
  --nav-item-active-bg: #3b82f6;
  --nav-item-active-text: #ffffff;
  --theme-card-bg: rgba(30, 41, 59, 0.8);
  --theme-card-border: rgba(71, 85, 105, 0.8);
  --theme-card-active-bg: #475569;
  --theme-card-active-border: #64748b;
  --input-bg: rgba(15, 23, 42, 0.95);
  --input-border: rgba(71, 85, 105, 0.8);
  --btn-primary-bg: #3b82f6;
  --btn-primary-text: #ffffff;
  --btn-secondary-bg: transparent;
  --btn-secondary-border: rgba(71, 85, 105, 0.8);
  --info-box-bg: rgba(30, 41, 59, 0.9);
  --info-box-border: rgba(71, 85, 105, 0.8);
  --info-box-text: #f8fafc;
  --close-btn-hover-bg: rgba(30, 41, 59, 0.9);
}

/*** Overlay ***/
.settings-overlay {
  position: fixed;
  inset: 0;
  /* 必须高于 CustomScenarioModal 等普通业务弹窗（z-index 1000），
     否则当用户在 CustomScenarioModal 内点"去设置 API Key"时设置弹窗会被遮挡。 */
  z-index: 1100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: transparent !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

/*** Modal ***/
.settings-modal {
  width: min(960px, calc(100vw - 48px));
  height: min(760px, calc(100vh - 64px));
  display: grid;
  grid-template-columns: 240px 1fr;
  background: var(--modal-bg);
  border: 1px solid var(--border-color);
  border-radius: 24px;
  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.18);
  overflow: hidden;
}

/*** Left Sidebar ***/
.settings-sidebar {
  background: var(--sidebar-bg);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: 24px 20px;
  border-bottom: 1px solid var(--border-color);
}

.sidebar-brand {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.sidebar-subtitle {
  font-size: 13px;
  color: var(--text-muted);
}

.sidebar-nav {
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  text-align: left;
  width: 100%;
}

.nav-item:hover {
  background: var(--nav-item-hover);
}

.nav-item.active {
  background: var(--nav-item-active-bg);
  color: var(--nav-item-active-text);
}

.nav-icon {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

/*** Right Content ***/
.settings-content {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--content-bg);
}

.content-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 28px 32px 20px;
  border-bottom: 1px solid var(--border-color);
}

.header-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 4px;
}

.header-desc {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
}

.close-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}

.close-btn:hover {
  background: var(--close-btn-hover-bg);
  color: var(--text-primary);
}

.close-btn svg {
  width: 20px;
  height: 20px;
}

.content-body {
  flex: 1;
  padding: 28px 32px;
  overflow-y: auto;
}

.settings-section {
  max-width: 640px;
}

.section-card {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  padding: 24px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 4px;
}

.card-desc {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0 0 20px;
}

/*** Avatar ***/
.avatar-section {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 24px;
}

.avatar-wrapper {
  position: relative;
  width: 80px;
  height: 80px;
  flex-shrink: 0;
}

.avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--border-color);
}

.avatar-edit {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border: 2px solid var(--card-bg);
}

.avatar-edit svg {
  width: 14px;
  height: 14px;
}

.avatar-info {
  flex: 1;
}

.avatar-hint {
  font-size: 13px;
  color: var(--text-muted);
  margin: 0;
}

/*** Field Groups ***/
.field-group {
  margin-bottom: 20px;
}

.field-label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: 8px;
}

.field-input {
  width: 100%;
  padding: 10px 14px;
  font-size: 14px;
  border: 1px solid var(--input-border);
  border-radius: 10px;
  background: var(--input-bg);
  color: var(--text-primary);
  transition: all 0.15s;
}

.field-input:focus {
  outline: none;
  border-color: #eab308;
  box-shadow: 0 0 0 3px rgba(234, 179, 8, 0.1);
}

.field-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.field-hint {
  font-size: 13px;
  color: var(--text-muted);
  margin: 6px 0 0;
}

.field-hint.error {
  color: #ef4444;
}

/*** Form Actions ***/
.form-actions {
  margin-top: 24px;
}

.btn-save {
  padding: 10px 24px;
  font-size: 14px;
  font-weight: 500;
  border: none;
  border-radius: 10px;
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  cursor: pointer;
  transition: all 0.15s;
}

.btn-save:hover:not(:disabled) {
  opacity: 0.9;
}

.btn-save:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/*** Theme Options ***/
.theme-options {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.theme-option {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  border: 2px solid var(--theme-card-border);
  border-radius: 12px;
  background: var(--theme-card-bg);
  cursor: pointer;
  transition: all 0.15s;
  text-align: left;
  width: 100%;
}

.theme-option:hover {
  border-color: #94a3b8;
}

.theme-option.active {
  border-color: var(--theme-card-active-border);
  background: var(--theme-card-active-bg);
}

.theme-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--nav-item-hover);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  flex-shrink: 0;
}

.theme-option.active .theme-icon {
  background: rgba(255, 255, 255, 0.15);
  color: white;
}

.theme-icon svg {
  width: 22px;
  height: 22px;
}

.theme-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.theme-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.theme-option.active .theme-title {
  color: white;
}

.theme-desc {
  font-size: 13px;
  color: var(--text-secondary);
}

.theme-option.active .theme-desc {
  color: rgba(255, 255, 255, 0.75);
}

.theme-check {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #eab308;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.theme-check svg {
  width: 14px;
  height: 14px;
}

/*** API Key ***/
.password-input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.password-input-wrapper .field-input {
  padding-right: 44px;
}

.toggle-password-btn {
  position: absolute;
  right: 12px;
  background: none;
  border: none;
  cursor: pointer;
  color: var(--text-muted);
  padding: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.toggle-password-btn:hover {
  color: var(--text-secondary);
}

.toggle-password-btn svg {
  width: 20px;
  height: 20px;
}

.api-key-actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}

.btn-clear {
  padding: 10px 24px;
  font-size: 14px;
  font-weight: 500;
  border: 1px solid var(--btn-secondary-border);
  border-radius: 10px;
  background: var(--btn-secondary-bg);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s;
}

.btn-clear:hover:not(:disabled) {
  border-color: #ef4444;
  color: #ef4444;
}

.btn-clear:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.api-key-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border-color);
  font-size: 13px;
  color: var(--text-secondary);
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

/*** Info Box ***/
.info-box {
  margin-top: 24px;
  padding: 16px;
  background: var(--info-box-bg);
  border: 1px solid var(--info-box-border);
  border-radius: 12px;
}

.info-box h4 {
  font-size: 14px;
  font-weight: 600;
  color: var(--info-box-text);
  margin: 0 0 12px;
}

.info-box ol {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.8;
}

.info-box a {
  color: #a16207;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.dark .info-box a {
  color: #fbbf24;
}

/*** Advanced ***/
.advanced-info {
  padding: 20px 0;
  text-align: center;
  color: var(--text-muted);
}

/* 隐私条款 tab:用 iframe 内嵌 /privacy,撑满右侧内容区并自带滚动 */
.privacy-card {
  /* 顶掉 section-card 的内边距,让 iframe 边缘贴合卡片圆角 */
  padding: 0;
  overflow: hidden;
}

.privacy-iframe {
  display: block;
  width: 100%;
  /* header 已隐藏,直接撑满设置弹窗右侧内容区 */
  height: calc(100vh - 140px);
  min-height: 500px;
  border: 0;
  background: var(--card-bg);
}

/*** Toast ***/
.toast {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-radius: 10px;
  font-size: 14px;
  margin-top: 16px;
}

.toast.success {
  background: rgba(240, 253, 244, 0.95);
  color: #166534;
  border: 1px solid #bbf7d0;
}

.dark .toast.success {
  background: rgba(20, 83, 45, 0.95);
  color: #bbf7d0;
  border-color: #166534;
}

.toast.error {
  background: rgba(254, 242, 242, 0.95);
  color: #991b1b;
  border: 1px solid #fecaca;
}

.dark .toast.error {
  background: rgba(127, 29, 29, 0.95);
  color: #fecaca;
  border-color: #991b1b;
}

/* 改密卡片的强度条 —— 复用与 LoginView / RegisterView 相同的色阶 token，
   但用 .pwd- 前缀避免与已有 .password-input-wrapper 等类名混淆 */
.pwd-strength {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.pwd-strength-bar {
  width: 100%;
  height: 4px;
  background: #E5E7EB;
  border-radius: 999px;
  overflow: hidden;
}

.dark .pwd-strength-bar {
  background: rgba(255, 255, 255, 0.1);
}

.pwd-strength-bar-fill {
  height: 100%;
  width: 0;
  background: #A1A1AA;
  border-radius: 999px;
  transition: width 0.2s ease, background-color 0.2s ease;
}

.pwd-strength-bar-fill.is-weak {
  width: 33%;
  background: #DC2626;
}

.pwd-strength-hint.is-weak {
  color: #DC2626;
}

.pwd-strength-bar-fill.is-medium {
  width: 66%;
  background: #D97706;
}

.pwd-strength-hint.is-medium {
  color: #D97706;
}

.pwd-strength-bar-fill.is-strong {
  width: 100%;
  background: #059669;
}

.pwd-strength-hint.is-strong {
  color: #059669;
}

.pwd-strength-hint {
  font-size: 12px;
  margin: 0;
  color: #71717A;
  transition: color 0.2s ease;
  min-height: 16px;
  /* 防止空态时与下方输入框"跳动" */
}

.dark .pwd-strength-hint {
  color: #A1A1AA;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/*** Responsive ***/
@media (max-width: 768px) {
  .settings-overlay {
    padding: 0;
    align-items: flex-end;
  }

  .settings-modal {
    width: 100vw;
    height: 90vh;
    border-radius: 24px 24px 0 0;
    grid-template-columns: 1fr;
  }

  .settings-sidebar {
    display: none;
  }
}
</style>
