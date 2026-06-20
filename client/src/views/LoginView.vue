<script setup lang="ts">
// LoginView：路由 /login（默认） 与 /login?mode=register（注册模式）
// 登录/注册合一视图：复用同一张卡片，通过 URL ?mode=register 切换。
// 与 authStore / useRememberCredentials 协作：
//   - authStore.login 负责真正的登录态与 token 持久化；
//   - useRememberCredentials 用登录密码派生密钥 AES 加密本地凭据，
//     二次访问时需用户输入密码解锁（不解密拿不到 password）。
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useThemeStore } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'
import { useRememberCredentials } from '@/composables/useRememberCredentials'
import { Sun, Moon } from 'lucide-vue-next'
import { register } from '@/api/auth'

const router = useRouter()
const route = useRoute()
const themeStore = useThemeStore()
const authStore = useAuthStore()
const remember = useRememberCredentials()

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

// UI 状态
const loading = ref(false)
const error = ref('')
const usernameError = ref('')
const usernameAvailable = ref<boolean | null>(null)

// Unlock dialog (用于「记住我」已勾选但凭据已加密的场景)
const unlockDialogOpen = ref(false)
const unlockPassword = ref('')
const unlockError = ref('')
const unlocking = ref(false)

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

// Try to unlock previously stored credentials. Resolves true on success.
// 解锁凭据后顺手把 identifier/password 写入当前表单，省去用户再次输入。
async function tryUnlockStoredCreds(passphrase: string): Promise<boolean> {
  const creds = await remember.unlock(passphrase)
  if (!creds) return false
  identifier.value = creds.identifier
  password.value = creds.password
  return true
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
    if (password.value.length < 6) {
      error.value = '密码长度至少为 6 个字符'
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
      // 「记住我」勾选时，用登录密码作为主密码加密保存凭据
      if (remember.enabled.value) {
        await remember.setCredentials(identifier.value, password.value)
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
    // 如果 query 里带 username 则预填 identifier
    if (route.query.username) {
      identifier.value = route.query.username as string
    } else if (remember.enabled.value && remember.identifier.value && !identifier.value) {
      identifier.value = remember.identifier.value
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
//   1) 注册跳转带的 username query（最确定的用户意图）；
//   2) 已加密的本地凭据 → 弹解锁框（不解密拿不到密码）；
//   3) 旧版明文 identifier（迁移期兼容，后续会淘汰）。
// 50ms 延后打开 isVisible 是为了让 CSS 过渡动画能触发。
onMounted(async () => {
  // 重置前先保存 username
  const savedUsername = route.query.username as string || ''
  // 重置表单，如果 identifier 等于 query 中的 username 则保留
  resetForm()
  if (route.query.mode === 'register') {
    isRegisterMode.value = true
  }
  // 从 query 参数预填 identifier（例如刚完成注册）
  // query param 优先级高于"记住我"
  if (savedUsername) {
    identifier.value = savedUsername
  } else if (remember.enabled.value && remember.hasStoredCreds()) {
    // 存在加密凭据 → 提示解锁（identifier 需解锁后才能拿到）
    unlockDialogOpen.value = true
  } else if (remember.enabled.value && remember.identifier.value) {
    // 兼容旧明文 identifier（迁移场景）
    identifier.value = remember.identifier.value
  }
  setTimeout(() => {
    isVisible.value = true
  }, 50)
})

async function handleUnlock() {
  // 解锁对话框的提交逻辑：密码错时不弹后端错误，统一用「密码错误」文案
  // 避免泄漏后端密钥派生/解密失败的细节给攻击者。
  if (!unlockPassword.value) {
    unlockError.value = '请输入密码'
    return
  }
  unlocking.value = true
  unlockError.value = ''
  try {
    const ok = await tryUnlockStoredCreds(unlockPassword.value)
    if (ok) {
      unlockDialogOpen.value = false
      unlockPassword.value = ''
    } else {
      unlockError.value = '密码错误，无法解锁已保存的凭据'
    }
  } finally {
    unlocking.value = false
  }
}
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

              <!-- 记住我（仅登录） -->
              <label
                v-if="!isRegisterMode"
                class="remember-me"
              >
                <input
                  type="checkbox"
                  class="remember-checkbox"
                  :checked="remember.enabled.value"
                  @change="(e: Event) => { remember.enabled.value = (e.target as HTMLInputElement).checked }"
                />
                <span class="remember-label">记住我</span>
                <span class="remember-hint">用登录密码加密保存到本机，下次输入密码即可解锁</span>
              </label>

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

              <!-- 记住我（仅登录） -->
              <label
                v-if="!isRegisterMode"
                class="mobile-remember-me"
              >
                <input
                  type="checkbox"
                  class="remember-checkbox"
                  :checked="remember.enabled.value"
                  @change="(e: Event) => { remember.enabled.value = (e.target as HTMLInputElement).checked }"
                />
                <span class="remember-label">记住我</span>
                <span class="remember-hint">用登录密码加密保存到本机，下次输入密码即可解锁</span>
              </label>

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

    <!-- 解锁弹窗：解锁「记住我」保存的加密凭据 -->
    <Teleport to="body">
      <Transition name="fade">
        <div v-if="unlockDialogOpen" class="unlock-overlay" @click.self="unlockDialogOpen = false">
          <div class="unlock-dialog">
            <h3 class="unlock-title">解锁已保存的账号</h3>
            <p class="unlock-subtitle">输入你的登录密码以自动填充表单</p>
            <input
              v-model="unlockPassword"
              type="password"
              class="form-input"
              placeholder="登录密码"
              autocomplete="current-password"
              autocapitalize="off"
              spellcheck="false"
              @keyup.enter="handleUnlock"
            />
            <p v-if="unlockError" class="unlock-error">{{ unlockError }}</p>
            <div class="unlock-actions">
              <button
                type="button"
                class="unlock-btn-secondary"
                @click="unlockDialogOpen = false"
              >
                取消
              </button>
              <button
                type="button"
                class="unlock-btn-primary"
                :disabled="unlocking"
                @click="handleUnlock"
              >
                {{ unlocking ? '解锁中...' : '解锁' }}
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
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

/* Remember me checkbox */
.remember-me {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 4px;
  cursor: pointer;
  user-select: none;
  font-size: 13px;
  color: #52525B;
}

.dark .remember-me {
  color: #A1A1AA;
}

.remember-checkbox {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  accent-color: var(--color-gold, #A78BFA);
  cursor: pointer;
  flex-shrink: 0;
}

.remember-label {
  font-weight: 500;
}

.remember-hint {
  font-size: 12px;
  color: #A1A1AA;
  margin-left: auto;
}

.dark .remember-hint {
  color: #71717A;
}

.mobile-remember-me {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 2px;
  cursor: pointer;
  user-select: none;
  font-size: 12px;
  color: #52525B;
}

.dark .mobile-remember-me {
  color: #A1A1AA;
}

.mobile-remember-me .remember-hint {
  font-size: 11px;
}

/* Unlock Dialog */
.unlock-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  padding: 24px;
}

.unlock-dialog {
  width: 100%;
  max-width: 400px;
  background: #FFFFFF;
  border-radius: 20px;
  padding: 28px 24px 24px;
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.25);
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.dark .unlock-dialog {
  background: #18181B;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.unlock-title {
  font-size: 18px;
  font-weight: 700;
  color: #18181b;
  margin: 0;
}

.dark .unlock-title {
  color: #FFFFFF;
}

.unlock-subtitle {
  font-size: 13px;
  color: #71717a;
  margin: 0 0 4px;
}

.dark .unlock-subtitle {
  color: #A1A1AA;
}

.unlock-error {
  font-size: 13px;
  color: #DC2626;
  margin: 0;
}

.unlock-actions {
  display: flex;
  gap: 12px;
  margin-top: 4px;
}

.unlock-btn-primary,
.unlock-btn-secondary {
  flex: 1;
  height: 44px;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
}

.unlock-btn-primary {
  background: #18181b;
  color: white;
}

.unlock-btn-primary:hover:not(:disabled) {
  background: #27272a;
}

.unlock-btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.dark .unlock-btn-primary {
  background: #FAFAFA;
  color: #18181B;
}

.dark .unlock-btn-primary:hover:not(:disabled) {
  background: #E4E4E7;
}

.unlock-btn-secondary {
  background: transparent;
  color: #52525B;
  border: 1px solid #E5E7EB;
}

.unlock-btn-secondary:hover {
  background: #F4F4F5;
}

.dark .unlock-btn-secondary {
  color: #A1A1AA;
  border-color: rgba(255, 255, 255, 0.15);
}

.dark .unlock-btn-secondary:hover {
  background: rgba(255, 255, 255, 0.05);
}
</style>
