<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import Input from '@/components/ui/Input.vue'
import Button from '@/components/ui/Button.vue'

const router = useRouter()
const authStore = useAuthStore()

const email = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function handleSubmit() {
  if (!email.value || !password.value) {
    error.value = '请输入邮箱和密码'
    return
  }

  loading.value = true
  error.value = ''

  try {
    await authStore.login(email.value, password.value)
    router.push('/rooms')
  } catch (err: any) {
    error.value = err.response?.data?.message || '登录失败，请检查邮箱和密码'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center px-4 py-12">
    <div class="w-full max-w-md">
      <!-- Decorative Logo Area -->
      <div class="text-center mb-12 animate-fade-in-up">
        <!-- Decorative Icon -->
        <div class="inline-flex items-center justify-center w-20 h-20 mb-6 rounded-full bg-gradient-to-br from-[var(--color-navy)] to-[var(--color-navy-light)] shadow-lg">
          <svg class="w-10 h-10 text-[var(--color-gold)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
          </svg>
        </div>
        <h1 class="text-display mb-3">IdeaParty</h1>
        <p class="text-subheading text-[var(--color-text-secondary)] italic">智慧的沙龙，思想的盛宴</p>
        <div class="flex items-center justify-center gap-2 mt-4">
          <span class="w-12 h-px bg-gradient-to-r from-transparent to-[var(--color-gold)]"></span>
          <span class="text-[var(--color-gold)] text-sm">✦</span>
          <span class="w-12 h-px bg-gradient-to-l from-transparent to-[var(--color-gold)]"></span>
        </div>
      </div>

      <!-- Login Card -->
      <div class="card animate-fade-in-up stagger-2">
        <!-- Decorative top border -->
        <div class="absolute top-0 left-1/2 -translate-x-1/2 -translate-y-1/2 w-24 h-1 bg-gradient-to-r from-[var(--color-gold-dark)] via-[var(--color-gold)] to-[var(--color-gold-dark)] rounded-full"></div>

        <h2 class="text-heading text-center mb-8">欢迎回来</h2>

        <form @submit.prevent="handleSubmit" class="space-y-5">
          <div class="animate-fade-in-up stagger-3">
            <Input
              v-model="email"
              type="email"
              label="邮箱地址"
              placeholder="请输入邮箱"
              :disabled="loading"
            />
          </div>

          <div class="animate-fade-in-up stagger-4">
            <Input
              v-model="password"
              type="password"
              label="密码"
              placeholder="请输入密码"
              :disabled="loading"
            />
          </div>

          <div
            v-if="error"
            class="text-sm text-[var(--color-destructive)] text-center py-2 px-3 bg-red-50 rounded-lg border border-red-100 animate-fade-in-up"
          >
            {{ error }}
          </div>

          <div class="pt-2 animate-fade-in-up stagger-5">
            <Button
              type="submit"
              variant="primary"
              :loading="loading"
              class="w-full"
            >
              <svg v-if="!loading" class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 16l-4-4m0 0l4-4m-4 4h14m-5 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h7a3 3 0 013 3v1" />
              </svg>
              登录
            </Button>
          </div>
        </form>

        <div class="flex items-center justify-center gap-3 mt-8">
          <span class="w-16 h-px bg-[var(--color-border)]"></span>
          <span class="text-[var(--color-text-muted)] text-sm">还没有账号</span>
          <span class="w-16 h-px bg-[var(--color-border)]"></span>
        </div>

        <p class="text-center mt-4">
          <router-link
            to="/register"
            class="inline-flex items-center gap-1 text-[var(--color-navy)] hover:text-[var(--color-gold)] font-medium transition-colors"
          >
            <span>创建账户</span>
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 8l4 4m0 0l-4 4m4-4H3" />
            </svg>
          </router-link>
        </p>
      </div>

      <!-- Footer -->
      <p class="text-center text-[var(--color-text-muted)] text-sm mt-8 animate-fade-in-up stagger-5">
        与历史上的伟大思想家对话
      </p>
    </div>
  </div>
</template>
