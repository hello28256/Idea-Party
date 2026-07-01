<script setup lang="ts">
// RegisterView：路由 /register
// 独立注册页（与 LoginView 的 ?mode=register 模式并存的入口，老链接/老用户可能仍走这里）。
// 仅处理注册流程：调 register API，成功后跳回登录页并 query 携带用户名以便自动回填。
// 不维护任何持久化状态——token 的存取由登录流程负责，避免在两处重复实现鉴权副作用。
// 关键依赖：register()（@/api/auth）——直接调 API，不写 authStore。
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { register } from '@/api/auth'
import { evaluatePassword } from '@/composables/usePasswordStrength'
import { BRAND_LOGO, BRAND_LOGIN_BG } from '@/constants/brand'

const router = useRouter()

// 注册页表单的本地状态：所有字段在客户端临时保存，
// 只在成功提交时通过 register() 发往后端，不在页面间共享，因此无需 pinia。
const username = ref('')
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
// loading 在请求期间禁用提交按钮，防止重复点击产生并发注册请求。
const loading = ref(false)
// error 集中展示所有校验/服务端失败信息；模板里仅渲染这一个变量，
// 是为了避免每个字段各管一段错误样式导致 UI 不一致。
const error = ref('')

// 密码强度评估：实时反映当前 password.value 的强度，与 LoginView 注册模式共用 composable，
// 保证前后端规则严格一致（8 字符 + 字母 + 数字 + 30 条黑名单）。
const passwordStrength = computed(() => evaluatePassword(password.value))
const strengthBarClass = computed(() => ({
  'is-weak': passwordStrength.value.score === 1,
  'is-medium': passwordStrength.value.score === 2,
  'is-strong': passwordStrength.value.score === 3
}))

// 注册提交处理器：先做本地校验（必填/一致/强度），再调用 register API。
// 校验顺序按"用户最常犯的错误"排序——空字段 > 密码不一致 > 强度不足，
// 让最直观的错误最先暴露，减少用户来回修改的次数。
// 入参约束：username/password 必填，email 可选；密码强度由 passwordStrength.score 控制（< 3 即拒绝），
// 与后端 @StrongPassword 完全对齐（8 字符 + 字母 + 数字 + 黑名单）。
// 副作用：成功后清空密码字段并跳转到登录页（query 携带用户名以便自动回填），
// 失败时展示后端 message 或兜底文案，不写任何持久化状态——token 的存取由 login 流程负责。
async function handleSubmit() {
  if (!username.value || !password.value) {
    error.value = '请填写用户名和密码'
    return
  }

  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }

  // 强度校验：与后端 @StrongPassword 一字不差；具体不满足哪条由 message 给出。
  if (passwordStrength.value.score < 3) {
    error.value = passwordStrength.value.message
    return
  }

  loading.value = true
  error.value = ''

  try {
    // 直接调用注册接口，不写入 auth state
    await register({ username: username.value, email: email.value, password: password.value })
    // 注册成功后清空密码字段
    password.value = ''
    confirmPassword.value = ''
    // 跳转登录页并预填用户名
    router.push({
      path: '/login',
      query: { username: username.value }
    })
  } catch (err: any) {
    error.value = err.response?.data?.message || '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

// 第三方登录占位：保留按钮是为了一期 UI 不变，二期接 OAuth 时只替换实现，
// 调用方（模板按钮）无需改动。
function handleGoogleLogin() {
  console.log('Google login not implemented yet')
}

function handleAppleLogin() {
  console.log('Apple login not implemented yet')
}
</script>

<template>
  <div class="min-h-screen bg-[#f7f7f8] flex flex-col">
    <!-- 头部 -->
    <header class="h-[88px] border-b border-gray-200 bg-white">
      <div class="h-full px-10 flex items-center justify-between">
        <!-- 品牌 Logo -->
        <div class="flex items-center gap-3">
          <img :src="BRAND_LOGO" alt="Idea Party" class="w-9 h-9" />
          <span class="text-xl font-bold text-gray-900">Idea Party</span>
        </div>

        <!-- 右侧按钮 -->
        <div class="flex items-center gap-3">
          <router-link to="/register">
            <button class="px-5 py-2.5 text-base font-medium bg-gray-900 text-white rounded-full hover:bg-gray-800 transition-colors">
              注册聊天
            </button>
          </router-link>
          <router-link to="/login">
            <button class="px-5 py-2.5 text-base font-medium text-gray-700 hover:text-gray-900 transition-colors">
              登录
            </button>
          </router-link>
        </div>
      </div>
    </header>

    <!-- 主内容 -->
    <main class="flex-1 flex items-center justify-center pt-[72px] pb-12 px-6">
      <div class="relative w-full" style="max-width: min(1180px, 76vw);">
        <!-- 背景图容器 -->
        <div class="relative h-[520px] overflow-hidden rounded-[28px]">
          <img
            :src="BRAND_LOGIN_BG"
            alt="Register background"
            class="w-full h-full object-cover"
          />
        </div>

        <!-- 左侧注册卡片（浮层） -->
        <div class="absolute left-6 top-1/2 -translate-y-1/2 w-[420px] bg-white rounded-3xl shadow-xl p-8">
          <!-- 卡片头部 -->
          <div class="mb-8">
            <h2 class="text-3xl font-bold text-gray-900 mb-1">可访问</h2>
            <p class="text-4xl font-bold text-gray-900 mb-1">超 1000 万个角色</p>
            <p class="text-base text-gray-500 mt-4">十秒就能完成注册</p>
          </div>

          <!-- 注册按钮组 -->
          <div class="space-y-3">
            <!-- Google 登录按钮 -->
            <button
              @click="handleGoogleLogin"
              class="w-full h-[52px] flex items-center justify-center gap-3 bg-gray-900 text-white rounded-xl font-medium hover:bg-gray-800 transition-colors"
            >
              <svg class="w-5 h-5" viewBox="0 0 24 24">
                <path fill="#fff" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
                <path fill="#fff" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                <path fill="#fff" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
                <path fill="#fff" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
              </svg>
              使用 Google 继续
            </button>

            <!-- Apple 登录按钮 -->
            <button
              @click="handleAppleLogin"
              class="w-full h-[52px] flex items-center justify-center gap-3 bg-gray-900 text-white rounded-xl font-medium hover:bg-gray-800 transition-colors"
            >
              <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z"/>
              </svg>
              使用 Apple 继续
            </button>

            <!-- 分隔线 -->
            <div class="flex items-center gap-4 py-2">
              <div class="flex-1 h-px bg-gray-200"></div>
              <span class="text-sm text-gray-500">或者</span>
              <div class="flex-1 h-px bg-gray-200"></div>
            </div>

            <!-- 邮箱注册表单 -->
            <form @submit.prevent="handleSubmit" class="space-y-4">
              <div>
                <input
                  v-model="username"
                  type="text"
                  placeholder="用户名"
                  class="w-full h-12 px-4 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
                />
                <p v-if="usernameError" class="text-xs text-red-500 mt-1">{{ usernameError }}</p>
              </div>
              <div>
                <input
                  v-model="email"
                  type="email"
                  placeholder="邮箱地址（可选）"
                  class="w-full h-12 px-4 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
                />
              </div>
              <div>
                <input
                  v-model="password"
                  type="password"
                  placeholder="密码（至少 8 位，含字母和数字）"
                  class="w-full h-12 px-4 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
                  autocomplete="new-password"
                />
                <!-- 密码强度条：实时反映 evaluatePassword 结果；与 LoginView 注册模式共用 composable 保证规则一致 -->
                <div class="rv-strength" :data-score="passwordStrength.score">
                  <div class="rv-strength-bar">
                    <div class="rv-strength-bar-fill" :class="strengthBarClass"></div>
                  </div>
                  <p class="rv-strength-hint" :class="strengthBarClass">{{ passwordStrength.message }}</p>
                </div>
              </div>
              <div>
                <input
                  v-model="confirmPassword"
                  type="password"
                  placeholder="确认密码"
                  class="w-full h-12 px-4 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
                  autocomplete="new-password"
                />
              </div>

              <Transition name="fade">
                <div
                  v-if="error"
                  class="text-sm text-red-600 text-center py-2"
                >
                  {{ error }}
                </div>
              </Transition>

              <button
                type="submit"
                :disabled="loading"
                class="w-full h-[52px] flex items-center justify-center bg-gray-900 text-white rounded-xl font-medium hover:bg-gray-800 transition-colors disabled:opacity-50"
              >
                {{ loading ? '注册中...' : '使用电子邮件继续' }}
              </button>
            </form>
          </div>

          <!-- 底部协议 -->
          <p class="text-center text-xs text-gray-400 mt-6">
            若要继续，您需要同意
            <router-link to="/terms" class="font-medium text-sky-700 underline-offset-4 hover:underline dark:text-sky-300 dark:hover:text-sky-200">服务条款</router-link>
            和
            <router-link to="/privacy" class="font-medium text-sky-700 underline-offset-4 hover:underline dark:text-sky-300 dark:hover:text-sky-200">隐私政策</router-link>
          </p>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
/* 密码强度条 —— 与 LoginView / usePasswordStrength / StrongPasswordValidator 严格对齐 */
.rv-strength {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.rv-strength-bar {
  width: 100%;
  height: 4px;
  background: #E5E7EB;
  border-radius: 999px;
  overflow: hidden;
}

.rv-strength-bar-fill {
  height: 100%;
  width: 0;
  background: #A1A1AA;
  border-radius: 999px;
  transition: width 0.2s ease, background-color 0.2s ease;
}

.rv-strength-bar-fill.is-weak {
  width: 33%;
  background: #DC2626;
}

.rv-strength-hint.is-weak {
  color: #DC2626;
}

.rv-strength-bar-fill.is-medium {
  width: 66%;
  background: #D97706;
}

.rv-strength-hint.is-medium {
  color: #D97706;
}

.rv-strength-bar-fill.is-strong {
  width: 100%;
  background: #059669;
}

.rv-strength-hint.is-strong {
  color: #059669;
}

.rv-strength-hint {
  font-size: 12px;
  margin: 0;
  color: #71717A;
  transition: color 0.2s ease;
  min-height: 16px;
}

/* Responsive for mobile */
@media (max-width: 1024px) {
  main > div {
    display: flex;
    flex-direction: column;
  }

  main > div > div:first-child {
    height: 400px;
  }

  main > div > div:last-child {
    position: relative;
    left: 0;
    top: 0;
    transform: none;
    width: 100%;
    margin-top: -3rem;
    border-radius: 1.5rem 1.5rem 0 0;
  }
}
</style>
