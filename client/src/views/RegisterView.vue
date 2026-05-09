<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import Input from '@/components/ui/Input.vue'
import Button from '@/components/ui/Button.vue'

const router = useRouter()
const authStore = useAuthStore()

const name = ref('')
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const error = ref('')

async function handleSubmit() {
  // Validation
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
</script>

<template>
  <div class="min-h-screen flex items-center justify-center px-4 py-12">
    <div class="w-full max-w-md">
      <!-- Logo Area -->
      <div class="text-center mb-12 animate-fade-in-up">
        <div class="inline-flex items-center justify-center w-20 h-20 mb-6 rounded-full bg-gradient-to-br from-[var(--color-navy)] to-[var(--color-navy-light)] shadow-lg">
          <svg class="w-10 h-10 text-[var(--color-gold)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
          </svg>
        </div>
        <h1 class="text-display mb-3">IdeaParty</h1>
        <p class="text-subheading text-[var(--color-text-secondary)] italic">开启你的智慧之旅</p>
        <div class="flex items-center justify-center gap-2 mt-4">
          <span class="w-12 h-px bg-gradient-to-r from-transparent to-[var(--color-gold)]"></span>
          <span class="text-[var(--color-gold)] text-sm">✦</span>
          <span class="w-12 h-px bg-gradient-to-l from-transparent to-[var(--color-gold)]"></span>
        </div>
      </div>

      <!-- Register Card -->
      <div class="card animate-fade-in-up stagger-2">
        <div class="absolute top-0 left-1/2 -translate-x-1/2 -translate-y-1/2 w-24 h-1 bg-gradient-to-r from-[var(--color-gold-dark)] via-[var(--color-gold)] to-[var(--color-gold-dark)] rounded-full"></div>

        <h2 class="text-heading text-center mb-8">创建账户</h2>

        <form @submit.prevent="handleSubmit" class="space-y-5">
          <div class="animate-fade-in-up stagger-3">
            <Input
              v-model="name"
              type="text"
              label="名称"
              placeholder="请输入名称"
              :disabled="loading"
            />
          </div>

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
              placeholder="请输入密码（至少6个字符）"
              :disabled="loading"
            />
          </div>

          <div class="animate-fade-in-up stagger-4">
            <Input
              v-model="confirmPassword"
              type="password"
              label="确认密码"
              placeholder="请再次输入密码"
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
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
              </svg>
              创建账户
            </Button>
          </div>
        </form>

        <div class="flex items-center justify-center gap-3 mt-8">
          <span class="w-16 h-px bg-[var(--color-border)]"></span>
          <span class="text-[var(--color-text-muted)] text-sm">已有账户</span>
          <span class="w-16 h-px bg-[var(--color-border)]"></span>
        </div>

        <p class="text-center mt-4">
          <router-link
            to="/login"
            class="inline-flex items-center gap-1 text-[var(--color-navy)] hover:text-[var(--color-gold)] font-medium transition-colors"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 8l4 4m0 0l-4 4m4-4H3" />
            </svg>
            <span>返回登录</span>
          </router-link>
        </p>
      </div>
    </div>
  </div>
</template>
