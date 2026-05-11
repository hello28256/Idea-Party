<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const name = ref('')
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const error = ref('')

async function handleSubmit() {
  if (!name.value || !email.value || !password.value) {
    error.value = '请填写所有字段'
    return
  }

  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }

  if (password.value.length < 6) {
    error.value = '密码长度至少为 6 个字符'
    return
  }

  loading.value = true
  error.value = ''

  try {
    await authStore.register(name.value, email.value, password.value)
    router.push('/rooms')
  } catch (err: any) {
    error.value = err.response?.data?.message || '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function handleGoogleLogin() {
  console.log('Google login not implemented yet')
}

function handleAppleLogin() {
  console.log('Apple login not implemented yet')
}
</script>

<template>
  <div class="min-h-screen bg-[#f7f7f8] flex flex-col">
    <!-- Header -->
    <header class="h-[88px] border-b border-gray-200 bg-white">
      <div class="h-full px-10 flex items-center justify-between">
        <!-- Logo -->
        <div class="flex items-center gap-3">
          <img src="/image.svg" alt="Idea Party" class="w-9 h-9" />
          <span class="text-xl font-bold text-gray-900">Idea Party</span>
        </div>

        <!-- Right buttons -->
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

    <!-- Main Content -->
    <main class="flex-1 flex items-center justify-center pt-[72px] pb-12 px-6">
      <div class="relative w-full" style="max-width: min(1180px, 76vw);">
        <!-- Background Image Container -->
        <div class="relative h-[520px] overflow-hidden rounded-[28px]">
          <img
            src="/login-bg.png"
            alt="Register background"
            class="w-full h-full object-cover"
          />
        </div>

        <!-- Left Register Card - Overlaid -->
        <div class="absolute left-6 top-1/2 -translate-y-1/2 w-[420px] bg-white rounded-3xl shadow-xl p-8">
          <!-- Card Header -->
          <div class="mb-8">
            <h2 class="text-3xl font-bold text-gray-900 mb-1">可访问</h2>
            <p class="text-4xl font-bold text-gray-900 mb-1">超 1000 万个角色</p>
            <p class="text-base text-gray-500 mt-4">十秒就能完成注册</p>
          </div>

          <!-- Register Buttons -->
          <div class="space-y-3">
            <!-- Google Button -->
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

            <!-- Apple Button -->
            <button
              @click="handleAppleLogin"
              class="w-full h-[52px] flex items-center justify-center gap-3 bg-gray-900 text-white rounded-xl font-medium hover:bg-gray-800 transition-colors"
            >
              <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z"/>
              </svg>
              使用 Apple 继续
            </button>

            <!-- Divider -->
            <div class="flex items-center gap-4 py-2">
              <div class="flex-1 h-px bg-gray-200"></div>
              <span class="text-sm text-gray-500">或者</span>
              <div class="flex-1 h-px bg-gray-200"></div>
            </div>

            <!-- Email Register Form -->
            <form @submit.prevent="handleSubmit" class="space-y-4">
              <div>
                <input
                  v-model="name"
                  type="text"
                  placeholder="名称"
                  class="w-full h-12 px-4 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
                />
              </div>
              <div>
                <input
                  v-model="email"
                  type="email"
                  placeholder="邮箱地址"
                  class="w-full h-12 px-4 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
                />
              </div>
              <div>
                <input
                  v-model="password"
                  type="password"
                  placeholder="密码（至少6个字符）"
                  class="w-full h-12 px-4 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
                />
              </div>
              <div>
                <input
                  v-model="confirmPassword"
                  type="password"
                  placeholder="确认密码"
                  class="w-full h-12 px-4 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
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

          <!-- Footer Agreement -->
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
