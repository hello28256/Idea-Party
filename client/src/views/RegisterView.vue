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
  <div class="min-h-screen flex items-center justify-center bg-white px-4">
    <div class="w-full max-w-md">
      <!-- Logo/Title -->
      <div class="text-center mb-8">
        <h1 class="text-display text-text-primary">IdeaParty</h1>
        <p class="text-text-secondary mt-2">AI 多角色聊天室</p>
      </div>

      <!-- Register Card -->
      <div class="card p-6">
        <h2 class="text-heading mb-6 text-center">注册</h2>

        <form @submit.prevent="handleSubmit" class="space-y-4">
          <Input
            v-model="name"
            type="text"
            label="名称"
            placeholder="请输入名称"
            :disabled="loading"
          />

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
            placeholder="请输入密码（至少6个字符）"
            :disabled="loading"
          />

          <Input
            v-model="confirmPassword"
            type="password"
            label="确认密码"
            placeholder="请再次输入密码"
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
            注册
          </Button>
        </form>

        <p class="text-center text-label text-text-secondary mt-6">
          已有账号？
          <router-link
            to="/login"
            class="text-accent hover:text-accent-hover font-medium"
          >
            登录
          </router-link>
        </p>
      </div>
    </div>
  </div>
</template>
