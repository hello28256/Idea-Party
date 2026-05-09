import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@/types'
import { login as loginApi, register as registerApi } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  // State
  const user = ref<User | null>(loadUserFromStorage())
  const accessToken = ref<string | null>(localStorage.getItem('accessToken'))

  function loadUserFromStorage(): User | null {
    const stored = localStorage.getItem('user')
    if (stored) {
      try {
        return JSON.parse(stored)
      } catch {
        return null
      }
    }
    return null
  }

  // Computed
  const isAuthenticated = computed(() => !!accessToken.value && !!user.value)

  // Actions
  async function login(email: string, password: string): Promise<void> {
    const response = await loginApi({ email, password })
    accessToken.value = response.data.accessToken
    user.value = response.data.user
    localStorage.setItem('accessToken', response.data.accessToken)
    localStorage.setItem('user', JSON.stringify(response.data.user))
  }

  async function register(name: string, email: string, password: string): Promise<void> {
    const response = await registerApi({ name, email, password })
    accessToken.value = response.data.accessToken
    user.value = response.data.user
    localStorage.setItem('accessToken', response.data.accessToken)
    localStorage.setItem('user', JSON.stringify(response.data.user))
  }

  function logout(): void {
    user.value = null
    accessToken.value = null
    localStorage.removeItem('accessToken')
    localStorage.removeItem('user')
    window.location.href = '/login'
  }

  return {
    user,
    accessToken,
    isAuthenticated,
    login,
    register,
    logout
  }
})
