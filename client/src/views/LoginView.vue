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
  <div class="min-h-screen flex items-center justify-center bg-white px-4">
    <div class="w-full max-w-md">
      <!-- Logo/Title -->
      <div class="text-center mb-8">
        <h1 class="text-display text-text-primary">IdeaParty</h1>
        <p class="text-text-secondary mt-2">AI 多角色聊天室</p>
      </div>

      <!-- Login Card -->
      <div class="card p-6">
        <h2 class="text-heading mb-6 text-center">登录</h2>

        <form @submit.prevent="handleSubmit" class="space-y-4">
          <Input
            v-model="email"
            type="email"
            label="邮箱"
            placeholder="请输入邮箱"
            :disabled="loading"
          />

          <Input
            v-model="password"
            type="password"
            label="密码"
            placeholder="请输入密码"
            :disabled="loading"
          />

          <div
            v-if="error"
            class="text-sm text-destructive text-center"
          >
            {{ error }}
          </div>

          <Button
            type="submit"
            variant="primary"
            :loading="loading"
            class="w-full"
          >
            登录
          </Button>
        </form>

        <p class="text-center text-label text-text-secondary mt-6">
          还没有账号？
          <router-link
            to="/register"
            class="text-accent hover:text-accent-hover font-medium"
          >
            注册
          </router-link>
        </p>
      </div>
    </div>
  </div>
</template>
