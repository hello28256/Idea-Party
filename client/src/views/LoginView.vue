<script setup lang="ts">
// LoginView：路由 /login（默认） 与 /login?mode=register（注册模式）
// 登录/注册合一视图：复用同一张卡片，通过 URL ?mode=register 切换。
// authStore.login 负责真正的登录态与 token 持久化。
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useThemeStore } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'
import { Sun, Moon } from 'lucide-vue-next'
import { register } from '@/api/auth'
import { isSupported as isCredentialSupported, storeCredential } from '@/composables/useCredentialStorage'
import { evaluatePassword } from '@/composables/usePasswordStrength'

const router = useRouter()
const route = useRoute()
const themeStore = useThemeStore()
const authStore = useAuthStore()

// === ALL REF DECLARATIONS FIRST (before any usage) ===

// 表单模式：true = 注册，false = 登录
const isRegisterMode = ref(false)

// 动画状态
const isVisible = ref(false)

// 表单字段 - 登录
const identifier = ref('')
const password = ref('')

// 表单字段 - 注册
const username = ref('')
const email = ref('')
const confirmPassword = ref('')

// 「让浏览器记住密码」复选框状态。
// 默认不勾，避免在用户不知情时把凭据写入浏览器密码管理器——只有用户主动勾选才触发 storeCredential。
const rememberPassword = ref(false)
// 浏览器是否支持 Credential Management API：false 时复选框 disabled 并跳过 storeCredential 调用。
const credentialSupported = ref(isCredentialSupported())

// UI 状态
const loading = ref(false)
const error = ref('')
const usernameError = ref('')
const usernameAvailable = ref<boolean | null>(null)

// === FUNCTIONS AFTER ALL REFS ARE DECLARED ===

// Reset form function - clears all sensitive fields
function resetForm() {
  identifier.value = ''
  password.value = ''
  username.value = ''
  email.value = ''
  confirmPassword.value = ''
  error.value = ''
  usernameError.value = ''
  usernameAvailable.value = null
}

// 在登录与注册模式之间切换
// 切换时不仅切 URL，还要 reset 表单：登录态的 identifier 和注册态的 username
// 是不同字段，混在一起会让用户感到「刚才填的东西去哪了」。
function toggleMode() {
  const newMode = !isRegisterMode.value
  isRegisterMode.value = newMode
  // 重置所有表单字段（包括敏感数据）
  resetForm()
  // 更新 URL 以反映当前模式
  if (newMode) {
    router.push('/login?mode=register')
  } else {
    router.push('/login')
  }
}

// Login/Register
// 同一个表单提交入口，按 isRegisterMode 分支：
//   - 注册：直接调 register API（不写入 authStore），成功后跳转登录页并预填用户名；
//   - 登录：调 authStore.login，仅在「记住我」开启时再把凭据加密落盘。
// 错误信息优先取后端 message，便于排查 i18n 之前保留英文返回。
async function handleSubmit() {
  error.value = ''

  if (isRegisterMode.value) {
    // 注册校验
    if (!username.value) {
      error.value = '请输入用户名'
      return
    }
    // 强度校验：与后端 @StrongPassword 完全对齐（8 字符 + 字母 + 数字 + 黑名单）。
    // score < 3 表示未通过；具体哪条不满足由 passwordStrength.message 给出。
    if (passwordStrength.value.score < 3) {
      error.value = passwordStrength.value.message
      return
    }
    if (password.value !== confirmPassword.value) {
      error.value = '两次输入的密码不一致'
      return
    }
    if (usernameAvailable.value === false) {
      error.value = '用户名已被占用'
      return
    }
  } else {
    // 登录校验
    if (!identifier.value) {
      error.value = '请输入用户名或邮箱'
      return
    }
    if (!password.value) {
      error.value = '请输入密码'
      return
    }
  }

  loading.value = true

  try {
    if (isRegisterMode.value) {
      // 直接调用注册接口，不写入 auth state
      await register({ username: username.value, email: email.value, password: password.value })
      // 跳转登录页并预填用户名
      router.push({
        path: '/login',
        query: { username: username.value }
      })
    } else {
      await authStore.login(identifier.value, password.value)
      // 用户主动勾选时才触发；fire-and-forget 不阻塞跳转，storeCredential 内部已 try/catch 静默降级。
      if (rememberPassword.value && credentialSupported.value) {
        const displayName = authStore.user?.displayName || authStore.user?.username || ''
        storeCredential(identifier.value, password.value, displayName)
      }
      router.push('/rooms')
    }
  } catch (err: any) {
    error.value = err.response?.data?.message || (isRegisterMode.value ? '注册失败，请稍后重试' : '登录失败，请检查用户名和密码')
  } finally {
    loading.value = false
  }
}

// === COMPUTED AFTER FUNCTIONS ===
// 提交按钮文案
const submitButtonText = computed(() => {
  if (loading.value) return isRegisterMode.value ? '创建中...' : '登录中...'
  return isRegisterMode.value ? '创建账号' : '登录'
})

// 密码强度评估（注册模式使用）：实时反映当前 password.value 的强度等级 + 单行提示。
// 评分 0=空 / 1=弱 / 3=强（无中间档，因为黑名单命中与字符不全都属于 score=1）。
// 仅在注册模式下有意义；登录模式不展示强度条。
const passwordStrength = computed(() => evaluatePassword(password.value))
// 强度条色块 class：score 1→is-weak 红色；3→is-strong 绿色。空态（score 0）不挂任何 class，CSS 默认灰色底。
const strengthBarClass = computed(() => ({
  'is-weak': passwordStrength.value.score === 1,
  'is-medium': passwordStrength.value.score === 2,
  'is-strong': passwordStrength.value.score === 3
}))

// === WATCHES AND ONMOUNTED LAST ===
// Watch route changes to clear password and pre-fill username on navigation
// 在 /login 路径下清空密码和确认密码（避免从注册切回登录时残留），
// 但保留 identifier 以支持「注册完直接进入登录」的体验。
// query.username 优先级高于「记住我」，因为它代表用户刚走完的注册流程。
watch(() => route.fullPath, () => {
  if (route.path === '/login') {
    // 只清空密码，保留已经填写的 identifier
    password.value = ''
    confirmPassword.value = ''
    // 如果 query 里带 username 则预填 identifier（注册完跳登录的场景）
    if (route.query.username) {
      identifier.value = route.query.username as string
    }
  }
}, { immediate: true })

// 监听路由 query 变化以同步 isRegisterMode
watch(() => route.query.mode, (newMode) => {
  isRegisterMode.value = newMode === 'register'
  error.value = ''
})

// 在挂载时检查 URL 参数以设置初始模式
// 初始化顺序很关键：先 resetForm 防止上一会话残留，再按优先级回填 identifier：
//   1) 注册跳转带的 username query（最确定的用户意图）。
// 50ms 延后打开 isVisible 是为了让 CSS 过渡动画能触发。
onMounted(() => {
  // 一次性清理：删除老版本"记住我"功能写入的 localStorage key，
  // 防止老用户浏览器残留旧密文/时间戳，避免未来误用同名 key。
  try {
    localStorage.removeItem('idea-party-creds-v1')
    localStorage.removeItem('idea-party-remember-enabled')
    localStorage.removeItem('idea-party-remember-unlocked-until')
    localStorage.removeItem('idea-party-remember')
  } catch { /* 忽略 */ }

  // 重置前先保存 username
  const savedUsername = route.query.username as string || ''
  // 重置表单，如果 identifier 等于 query 中的 username 则保留
  resetForm()
  if (route.query.mode === 'register') {
    isRegisterMode.value = true
  }
  // 从 query 参数预填 identifier（例如刚完成注册）
  if (savedUsername) {
    identifier.value = savedUsername
  }
  setTimeout(() => {
    isVisible.value = true
  }, 50)
})
</script>

<template>
  <!-- 根容器 - 无 flex 干扰布局 -->
  <div class="login-page-root">
    <!-- 头部 -->
    <header class="login-header">
      <div class="header-inner">
        <!-- 品牌 Logo -->
        <div class="brand-logo">
          <img src="/image.png" alt="logo" class="brand-logo-img" />
          <h1
            class="text-[30px] font-black tracking-[-0.03em]"
            :style="{ color: themeStore.isDark ? '#FFFFFF' : '#18181B' }"
          >
            Idea Party
          </h1>
        </div>

        <!-- 右侧按钮 -->
        <div class="flex items-center gap-3">
          <!-- 主题切换按钮 -->
          <button
            @click="themeStore.toggle"
            class="theme-toggle-btn"
            :title="themeStore.isDark ? '切换到白天模式' : '切换到暗黑模式'"
          >
            <Transition name="icon-fade" mode="out-in">
              <Moon v-if="themeStore.isDark" :size="20" :stroke-width="2" />
              <Sun v-else :size="20" :stroke-width="2" />
            </Transition>
          </button>

          <router-link to="/login">
            <button class="inline-flex h-11 min-w-[92px] items-center justify-center rounded-full border border-zinc-200 bg-white px-6 text-[14px] font-semibold text-zinc-800 shadow-sm transition-all hover:border-zinc-300 hover:bg-zinc-50 hover:-translate-y-[1px] dark:border-white/20 dark:bg-white dark:text-zinc-900 dark:hover:bg-zinc-100">
              登录
            </button>
          </router-link>
          <router-link to="/login?mode=register">
            <button class="inline-flex h-11 min-w-[92px] items-center justify-center rounded-full bg-zinc-950 px-6 text-[14px] font-semibold text-white shadow-sm transition-all hover:bg-zinc-800 hover:-translate-y-[1px] dark:bg-white dark:text-zinc-900 dark:hover:bg-zinc-100">
              注册
            </button>
          </router-link>
        </div>
      </div>
    </header>

    <!-- 主内容 -->
    <main class="login-main">
      <section class="login-section">
        <!-- 右侧大图 - 使用 inline style 确保 calc 生效 -->
        <div class="hero-image-container" :style="{ width: 'calc(100% - 300px)' }">
          <img
            src="/login-bg.png"
            alt="login background"
            class="hero-image"
          />
        </div>

        <!-- 左侧登录卡片 -->
        <div class="login-card-wrapper">
          <div class="login-card">
            <!-- 卡片头部 -->
            <div class="card-header">
              <h1 class="card-title" v-if="!isRegisterMode">
                <span class="title-line1">有些对话</span>
                <span class="title-line2">人类历史上从未发生过</span>
              </h1>
              <h1 class="card-title card-title-single" v-else>创建账号</h1>
              <p class="card-subtitle">
                {{ isRegisterMode ? '加入 Idea Party，探索 AI 角色世界' : '现在，你可以让他们第一次坐在同一张桌子前' }}
              </p>
            </div>

            <!-- 认证表单 -->
            <form @submit.prevent="handleSubmit" class="auth-form">
              <!-- 登录：用户名或邮箱 -->
              <div v-if="!isRegisterMode">
                <input
                  v-model="identifier"
                  type="text"
                  placeholder="用户名或邮箱"
                  class="form-input"
                  name="username"
                  id="login_username"
                  autocomplete="username"
                />
              </div>

              <!-- 注册：用户名 -->
              <div v-if="isRegisterMode" class="username-field">
                <input
                  v-model="username"
                  type="text"
                  placeholder="用户名"
                  class="form-input"
                  autocomplete="username"
                />
                <p v-if="username.length > 0" class="username-hint" :class="{ available: usernameAvailable === true, taken: usernameAvailable === false }">
                  {{ usernameAvailable === true ? '✓ 用户名可用' : usernameAvailable === false ? '✗ 用户名已被占用' : '' }}
                </p>
              </div>

              <!-- 邮箱（仅注册，可选） -->
              <div v-if="isRegisterMode">
                <input
                  v-model="email"
                  type="email"
                  placeholder="邮箱地址（可选）"
                  class="form-input"
                  autocomplete="email"
                />
              </div>

              <!-- 密码（登录） -->
              <div v-if="!isRegisterMode">
                <input
                  v-model="password"
                  type="password"
                  placeholder="密码"
                  class="form-input"
                  name="password"
                  id="login_password"
                  autocomplete="current-password"
                  autocapitalize="off"
                  spellcheck="false"
                />
              </div>

              <!-- 「让浏览器记住密码」复选框（仅登录模式；不支持的浏览器 disabled） -->
              <div v-if="!isRegisterMode" class="remember-row">
                <label class="remember-label">
                  <input
                    v-model="rememberPassword"
                    type="checkbox"
                    class="remember-checkbox"
                    :disabled="!credentialSupported"
                  />
                  <span class="remember-text">让浏览器记住密码</span>
                </label>
              </div>

              <!-- 密码（注册） -->
              <div v-if="isRegisterMode">
                <input
                  v-model="password"
                  type="password"
                  placeholder="密码"
                  class="form-input"
                  autocomplete="new-password"
                />
              </div>

              <!-- 密码强度条（仅注册模式实时显示） -->
              <div v-if="isRegisterMode" class="password-strength" :data-score="passwordStrength.score">
                <div class="strength-bar">
                  <div class="strength-bar-fill" :class="strengthBarClass"></div>
                </div>
                <p class="strength-hint" :class="strengthBarClass">{{ passwordStrength.message }}</p>
              </div>

              <!-- 确认密码（仅注册） -->
              <div v-if="isRegisterMode">
                <input
                  v-model="confirmPassword"
                  type="password"
                  placeholder="确认密码"
                  class="form-input"
                  autocomplete="new-password"
                />
              </div>

              <!-- 错误提示 -->
              <Transition name="fade">
                <div v-if="error" class="text-sm text-red-500 text-center py-2">
                  {{ error }}
                </div>
              </Transition>

              <!-- 提交按钮 -->
              <button
                type="submit"
                :disabled="loading"
                class="submit-button"
              >
                {{ submitButtonText }}
              </button>
            </form>

            <!-- 切换登录/注册模式 -->
            <div class="mt-8 flex items-center justify-center gap-2 text-sm text-zinc-500 dark:text-zinc-400">
              <span>{{ isRegisterMode ? '已有账号？' : '还没有账号？' }}</span>

              <button
                type="button"
                @click="toggleMode"
                class="
                  font-semibold
                  text-sky-700
                  underline-offset-4
                  transition-all
                  duration-200
                  hover:text-sky-800
                  hover:underline
                  dark:text-sky-300
                  dark:hover:text-sky-200
                "
              >
                {{ isRegisterMode ? '立即登录' : '立即注册' }}
              </button>
            </div>

            <!-- 服务条款 -->
            <p v-if="isRegisterMode" class="text-center text-xs text-zinc-400 mt-6">
              注册即表示您同意我们的
              <router-link to="/terms" class="underline hover:text-zinc-600 transition-colors">服务条款</router-link>
              和
              <router-link to="/privacy" class="underline hover:text-zinc-600 transition-colors">隐私政策</router-link>
            </p>
          </div>
        </div>

        <!-- 移动端布局 -->
        <div class="mobile-layout">
          <!-- 移动端图片 -->
          <div class="mobile-image-container">
            <img
              src="/login-bg.png"
              alt="login background"
              class="mobile-image"
            />
          </div>

          <!-- 移动端卡片 -->
          <div class="mobile-card">
            <div class="mobile-card-header">
              <h1 class="mobile-card-title" v-if="!isRegisterMode">
                <span class="mobile-title-line1">有些对话</span>
                <span class="mobile-title-line2">人类历史上从未发生过</span>
              </h1>
              <h1 class="mobile-card-title mobile-card-title-single" v-else>创建账号</h1>
              <p class="mobile-card-subtitle">
                {{ isRegisterMode ? '加入 Idea Party，探索 AI 角色世界' : '现在，你可以让他们第一次坐在同一张桌子前' }}
              </p>
            </div>

            <!-- 认证表单 -->
            <form @submit.prevent="handleSubmit" class="mobile-auth-form">
              <!-- 登录：用户名或邮箱 -->
              <div v-if="!isRegisterMode">
                <input
                  v-model="identifier"
                  type="text"
                  placeholder="用户名或邮箱"
                  class="mobile-form-input"
                  name="username"
                  id="login_username_mobile"
                  autocomplete="username"
                />
              </div>

              <!-- 注册：用户名 -->
              <div v-if="isRegisterMode">
                <input
                  v-model="username"
                  type="text"
                  placeholder="用户名"
                  class="mobile-form-input"
                  autocomplete="username"
                />
              </div>

              <!-- 邮箱（仅注册，可选） -->
              <div v-if="isRegisterMode">
                <input
                  v-model="email"
                  type="email"
                  placeholder="邮箱地址（可选）"
                  class="mobile-form-input"
                  autocomplete="email"
                />
              </div>

              <!-- 密码（登录） -->
              <div v-if="!isRegisterMode">
                <input
                  v-model="password"
                  type="password"
                  placeholder="密码"
                  class="mobile-form-input"
                  name="password"
                  id="login_password_mobile"
                  autocomplete="current-password"
                  autocapitalize="off"
                  spellcheck="false"
                />
              </div>

              <!-- 「让浏览器记住密码」复选框（移动端） -->
              <div v-if="!isRegisterMode" class="mobile-remember-row">
                <label class="mobile-remember-label">
                  <input
                    v-model="rememberPassword"
                    type="checkbox"
                    class="mobile-remember-checkbox"
                    :disabled="!credentialSupported"
                  />
                  <span class="mobile-remember-text">让浏览器记住密码</span>
                </label>
              </div>

              <!-- 密码（注册） -->
              <div v-if="isRegisterMode">
                <input
                  v-model="password"
                  type="password"
                  placeholder="密码"
                  class="mobile-form-input"
                  autocomplete="new-password"
                />
              </div>

              <!-- 密码强度条（移动端） -->
              <div v-if="isRegisterMode" class="mobile-password-strength" :data-score="passwordStrength.score">
                <div class="mobile-strength-bar">
                  <div class="mobile-strength-bar-fill" :class="strengthBarClass"></div>
                </div>
                <p class="mobile-strength-hint" :class="strengthBarClass">{{ passwordStrength.message }}</p>
              </div>

              <!-- 确认密码（仅注册） -->
              <div v-if="isRegisterMode">
                <input
                  v-model="confirmPassword"
                  type="password"
                  placeholder="确认密码"
                  class="mobile-form-input"
                  autocomplete="new-password"
                />
              </div>

              <!-- 错误提示 -->
              <Transition name="fade">
                <div v-if="error" class="text-sm text-red-500 text-center py-2">
                  {{ error }}
                </div>
              </Transition>

              <!-- 提交按钮 -->
              <button
                type="submit"
                :disabled="loading"
                class="mobile-submit-button"
              >
                {{ submitButtonText }}
              </button>
            </form>

            <!-- 切换登录/注册模式 -->
            <div class="mt-8 flex items-center justify-center gap-2 text-sm" :style="{ color: themeStore.isDark ? '#A1A1AA' : '#71717A' }">
              <span>{{ isRegisterMode ? '已有账号？' : '还没有账号？' }}</span>

              <button
                type="button"
                @click="toggleMode"
                class="font-semibold underline-offset-4 transition-all duration-200 hover:underline hover:text-sky-600 dark:hover:text-sky-400"
                :style="{ color: themeStore.isDark ? '#93C5FD' : '#1D4ED8' }"
              >
                {{ isRegisterMode ? '立即登录' : '立即注册' }}
              </button>
            </div>

            <!-- 服务条款 -->
            <p v-if="isRegisterMode" class="text-center text-xs text-zinc-400 dark:text-zinc-500 mt-4">
              注册即表示您同意我们的
              <router-link to="/terms" class="underline hover:text-zinc-600 transition-colors dark:hover:text-zinc-300">服务条款</router-link>
              和
              <router-link to="/privacy" class="underline hover:text-zinc-600 transition-colors dark:hover:text-zinc-300">隐私政策</router-link>
            </p>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
/* 根容器 */
.login-page-root {
  min-height: 100vh;
  background: var(--color-bg);
  transition: var(--transition-theme);
}

/* Brand Logo */
.brand-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}

.brand-logo-img {
  width: 32px;
  height: 32px;
  object-fit: contain;
  border-radius: 8px;
}

/* Header */
.login-header {
  height: 64px;
  border-bottom: 1px solid rgba(228, 228, 231, 0.7);
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: var(--transition-theme);
}

.dark .login-header {
  border-bottom-color: rgba(255, 255, 255, 0.1);
  background: rgba(30, 30, 35, 0.98);
}

.header-inner {
  max-width: 1440px;
  margin: 0 auto;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}

/* Theme Toggle Button */
.theme-toggle-btn {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(255, 255, 255, 0.2);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  cursor: pointer;
  transition: all 0.3s ease;
  color: #18181b;
}

.theme-toggle-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  background: rgba(255, 255, 255, 0.95);
}

.dark .theme-toggle-btn {
  background: rgba(24, 24, 27, 0.85);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #FAFAFA;
}

.dark .theme-toggle-btn:hover {
  background: rgba(39, 39, 42, 0.9);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
}

/* Icon transition */
.icon-fade-enter-active,
.icon-fade-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.icon-fade-enter-from {
  opacity: 0;
  transform: rotate(-90deg) scale(0.8);
}

.icon-fade-leave-to {
  opacity: 0;
  transform: rotate(90deg) scale(0.8);
}

/* Login toggle button - force white in dark mode */
.dark .login-card-wrapper .auth-form ~ div button {
  color: #FFFFFF !important;
}

/* Main */
.login-main {
  padding: 48px 32px 64px;
  min-height: calc(100vh - 64px);
}

/* Section - 关键：这是 relative 容器 */
.login-section {
  position: relative;
  max-width: 1440px;
  margin: 0 auto;
}

/* Hero 图片容器 */
.hero-image-container {
  height: 560px;
  border-radius: 32px;
  overflow: hidden;
  box-shadow: 0 30px 90px rgba(15, 23, 42, 0.18);
  position: relative;
}

.hero-image-container::after {
  content: '';
  position: absolute;
  inset: 0;
  background: transparent;
  transition: var(--transition-theme);
  pointer-events: none;
}

.dark .hero-image-container::after {
  background: rgba(0, 0, 0, 0.25);
}

.hero-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 登录卡片包装器 - 绝对定位 */
.login-card-wrapper {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  z-index: 20;
  width: 420px;
}

/* 登录卡片 */
.login-card {
  background: #FFFFFF;
  border: 1px solid transparent;
  border-radius: 28px;
  padding: 52px 32px 44px 32px;
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.18);
  transition: var(--transition-theme);
}

.dark .login-card {
  background: #18181B;
  border-color: rgba(255, 255, 255, 0.1);
}

/* 标题区域 */
.card-header {
  margin-bottom: 32px;
}

.card-title {
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 8px;
  line-height: 1.1;
}

.card-title-single {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #18181b;
}

.dark .card-title-single {
  color: #FFFFFF;
}

.title-line1 {
  font-size: 36px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: #18181b;
  display: block;
  line-height: 1.1;
}

.dark .title-line1 {
  color: #FFFFFF;
}

.title-line2 {
  font-size: 36px;
  font-weight: 800;
  letter-spacing: -0.04em;
  color: #18181b;
  display: block;
  line-height: 1.15;
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.dark .title-line2 {
  background: linear-gradient(135deg, #FFFFFF 0%, #E4E4E7 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.card-subtitle {
  text-align: center;
  font-size: 20px;
  color: #71717a;
  line-height: 1.5;
  letter-spacing: 0.01em;
}

.dark .card-subtitle {
  color: #A1A1AA;
}

/* 表单区域 */
.auth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 表单输入框 */
.form-input {
  width: 100%;
  height: 64px;
  border-radius: 16px;
  border: 1px solid #E5E7EB;
  background: #FFFFFF;
  padding: 0 20px;
  font-size: 15px;
  color: #18181B;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  outline: none;
  transition: all 0.2s;
}

.dark .form-input {
  background: #18181B;
  border-color: rgba(255, 255, 255, 0.1);
  color: #FAFAFA;
}

.form-input::placeholder {
  color: #A1A1AA;
}

.dark .form-input::placeholder {
  color: #71717A;
}

.form-input:focus {
  border-color: #A78BFA;
  box-shadow: 0 0 0 4px rgba(167, 139, 250, 0.15), 0 0 20px rgba(167, 139, 250, 0.1);
}

.dark .form-input:focus {
  border-color: #A78BFA;
  box-shadow: 0 0 0 4px rgba(167, 139, 250, 0.2), 0 0 20px rgba(167, 139, 250, 0.15);
}

/* Username field */
.username-field {
  position: relative;
}

.username-hint {
  font-size: 12px;
  margin: 4px 0 0 4px;
  color: #A1A1AA;
}

.username-hint.available {
  color: #059669;
}

.username-hint.taken {
  color: #DC2626;
}

/* 提交按钮 */
.submit-button {
  width: 100%;
  height: 64px;
  border-radius: 16px;
  margin-bottom: 28px;
  background: #18181b;
  color: white;
  font-size: 15px;
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  transition: all 0.2s;
  border: none;
  cursor: pointer;
}

.dark .submit-button {
  background: #FAFAFA;
  color: #18181B;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
}

.submit-button:hover:not(:disabled) {
  background: #27272a;
  transform: translateY(-1px);
}

.dark .submit-button:hover:not(:disabled) {
  background: #E4E4E7;
}

.submit-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 移动端布局 */
.mobile-layout {
  display: none;
}

.mobile-image-container {
  width: 100%;
  height: 300px;
  border-radius: 24px;
  overflow: hidden;
  box-shadow: 0 10px 40px rgba(15, 23, 42, 0.12);
  position: relative;
}

.mobile-image-container::after {
  content: '';
  position: absolute;
  inset: 0;
  background: transparent;
  transition: var(--transition-theme);
  pointer-events: none;
}

.dark .mobile-image-container::after {
  background: rgba(0, 0, 0, 0.25);
}

.mobile-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.mobile-card {
  margin-top: 24px;
  background: #FFFFFF;
  border: 1px solid transparent;
  border-radius: 24px;
  padding: 32px 24px 36px;
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.12);
  transition: var(--transition-theme);
}

.dark .mobile-card {
  background: #18181B;
  border-color: rgba(255, 255, 255, 0.1);
}

.mobile-card-header {
  margin-bottom: 24px;
}

.mobile-card-title {
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 6px;
  line-height: 1.1;
}

.mobile-card-title-single {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #18181b;
}

.dark .mobile-card-title-single {
  color: #FFFFFF;
}

.mobile-title-line1 {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: #18181b;
  display: block;
  line-height: 1.1;
}

.dark .mobile-title-line1 {
  color: #FFFFFF;
}

.mobile-title-line2 {
  font-size: 24px;
  font-weight: 800;
  letter-spacing: -0.04em;
  color: #18181b;
  display: block;
  line-height: 1.15;
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.dark .mobile-title-line2 {
  background: linear-gradient(135deg, #FFFFFF 0%, #E4E4E7 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.mobile-card-subtitle {
  text-align: center;
  font-size: 16px;
  color: #71717a;
  line-height: 1.5;
}

.dark .mobile-card-subtitle {
  color: #A1A1AA;
}

.mobile-auth-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.mobile-form-input {
  width: 100%;
  height: 48px;
  border-radius: 12px;
  border: 1px solid #E5E7EB;
  background: #FFFFFF;
  padding: 0 16px;
  font-size: 14px;
  color: #18181B;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  outline: none;
  transition: all 0.2s;
}

.dark .mobile-form-input {
  background: #18181B;
  border-color: rgba(255, 255, 255, 0.1);
  color: #FAFAFA;
}

.mobile-form-input::placeholder {
  color: #A1A1AA;
}

.dark .mobile-form-input::placeholder {
  color: #71717A;
}

.mobile-form-input:focus {
  border-color: #A78BFA;
  box-shadow: 0 0 0 4px rgba(167, 139, 250, 0.15), 0 0 20px rgba(167, 139, 250, 0.1);
}

.dark .mobile-form-input:focus {
  border-color: #A78BFA;
  box-shadow: 0 0 0 4px rgba(167, 139, 250, 0.2), 0 0 20px rgba(167, 139, 250, 0.15);
}

.mobile-submit-button {
  width: 100%;
  height: 48px;
  border-radius: 12px;
  background: #18181b;
  color: white;
  font-size: 14px;
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  transition: all 0.2s;
  border: none;
  cursor: pointer;
}

.dark .mobile-submit-button {
  background: #FAFAFA;
  color: #18181B;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
}

.mobile-submit-button:hover:not(:disabled) {
  background: #27272a;
}

.dark .mobile-submit-button:hover:not(:disabled) {
  background: #E4E4E7;
}

.mobile-submit-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 响应式 - 桌面端隐藏移动端布局 */
@media (min-width: 769px) {
  .mobile-layout {
    display: none !important;
  }
}

/* 响应式 - 移动端隐藏桌面端布局 */
@media (max-width: 768px) {
  .login-section {
    outline: none;
  }

  .hero-image-container,
  .login-card-wrapper {
    display: none !important;
  }

  .mobile-layout {
    display: block;
  }

  .login-main {
    padding: 40px 24px 32px;
  }

  .header-inner {
    padding: 0 24px;
  }
}

/* 过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* 「让浏览器记住密码」复选框（桌面） */
.remember-row {
  display: flex;
  align-items: center;
  /* 抵消 .auth-form 的 gap:16px，让复选框与密码框间距更紧凑 */
  margin-top: -4px;
}

.remember-label {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.remember-checkbox {
  width: 18px;
  height: 18px;
  margin: 0;
  cursor: pointer;
  accent-color: #A78BFA;
  flex-shrink: 0;
}

.remember-checkbox:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.remember-text {
  font-size: 14px;
  color: #71717A;
  transition: color 0.2s;
}

.dark .remember-text {
  color: #A1A1AA;
}

/* 「让浏览器记住密码」复选框（移动） */
.mobile-remember-row {
  display: flex;
  align-items: center;
  margin-top: -2px;
}

.mobile-remember-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}

.mobile-remember-checkbox {
  width: 18px;
  height: 18px;
  margin: 0;
  cursor: pointer;
  accent-color: #A78BFA;
  flex-shrink: 0;
}

.mobile-remember-checkbox:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.mobile-remember-text {
  font-size: 13px;
  color: #71717A;
  transition: color 0.2s;
}

.dark .mobile-remember-text {
  color: #A1A1AA;
}

/* 密码强度条（桌面）—— 横条 + 单行提示，规则与 usePasswordStrength / StrongPasswordValidator 对齐 */
.password-strength {
  display: flex;
  flex-direction: column;
  gap: 6px;
  /* 抵消 .auth-form 的 gap:16px 让强度条与密码框视觉更紧凑 */
  margin-top: -8px;
}

.strength-bar {
  width: 100%;
  height: 4px;
  background: #E5E7EB;
  border-radius: 999px;
  overflow: hidden;
}

.dark .strength-bar {
  background: rgba(255, 255, 255, 0.1);
}

.strength-bar-fill {
  height: 100%;
  width: 0;
  background: #A1A1AA;
  border-radius: 999px;
  transition: width 0.2s ease, background-color 0.2s ease;
}

/* 弱（score=1）：红条 + 红字 */
.strength-bar-fill.is-weak {
  width: 33%;
  background: #DC2626;
}

.strength-hint.is-weak {
  color: #DC2626;
}

/* 中（score=2，预留）：黄条 + 黄字 */
.strength-bar-fill.is-medium {
  width: 66%;
  background: #D97706;
}

.strength-hint.is-medium {
  color: #D97706;
}

/* 强（score=3）：满宽绿条 + 绿字 */
.strength-bar-fill.is-strong {
  width: 100%;
  background: #059669;
}

.strength-hint.is-strong {
  color: #059669;
}

.strength-hint {
  font-size: 12px;
  margin: 0;
  color: #71717A;
  transition: color 0.2s ease;
  min-height: 16px;
  /* 防止空态时与下方元素"跳动"：固定最小高度 */
}

.dark .strength-hint {
  color: #A1A1AA;
}

/* 密码强度条（移动） */
.mobile-password-strength {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: -6px;
}

.mobile-strength-bar {
  width: 100%;
  height: 3px;
  background: #E5E7EB;
  border-radius: 999px;
  overflow: hidden;
}

.dark .mobile-strength-bar {
  background: rgba(255, 255, 255, 0.1);
}

.mobile-strength-bar-fill {
  height: 100%;
  width: 0;
  background: #A1A1AA;
  border-radius: 999px;
  transition: width 0.2s ease, background-color 0.2s ease;
}

.mobile-strength-bar-fill.is-weak {
  width: 33%;
  background: #DC2626;
}

.mobile-strength-hint.is-weak {
  color: #DC2626;
}

.mobile-strength-bar-fill.is-medium {
  width: 66%;
  background: #D97706;
}

.mobile-strength-hint.is-medium {
  color: #D97706;
}

.mobile-strength-bar-fill.is-strong {
  width: 100%;
  background: #059669;
}

.mobile-strength-hint.is-strong {
  color: #059669;
}

.mobile-strength-hint {
  font-size: 11px;
  margin: 0;
  color: #71717A;
  transition: color 0.2s ease;
  min-height: 14px;
}

.dark .mobile-strength-hint {
  color: #A1A1AA;
}

</style>
