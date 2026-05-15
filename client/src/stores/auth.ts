import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@/types'
import { login as loginApi, register as registerApi, updateProfile as updateProfileApi } from '@/api/auth'
import { getProfile, uploadAvatar as uploadAvatarApi } from '@/api/user'
import { useThemeStore } from '@/stores/theme'

// Generate a stable UUID-like ID
function generateId(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0
    const v = c === 'x' ? r : (r & 0x3 | 0x8)
    return v.toString(16)
  })
}

// Migrate old user data that doesn't have an id
function migrateUserData(): void {
  try {
    const usersJson = localStorage.getItem('users')
    if (!usersJson) return

    const users: User[] = JSON.parse(usersJson)
    let usersChanged = false

    // Ensure every user has an id
    users.forEach(u => {
      if (!u.id) {
        u.id = generateId()
        usersChanged = true
      }
    })

    if (usersChanged) {
      localStorage.setItem('users', JSON.stringify(users))
    }

    // Migrate current user if needed
    const currentUserJson = localStorage.getItem('user')
    if (currentUserJson) {
      const currentUser: User = JSON.parse(currentUserJson)
      if (!currentUser.id) {
        // Find this user in the users array and use their id
        const matchedUser = users.find(u =>
          u.username?.toLowerCase() === currentUser.username?.toLowerCase() &&
          u.email?.toLowerCase() === currentUser.email?.toLowerCase()
        )
        if (matchedUser) {
          currentUser.id = matchedUser.id
        } else {
          currentUser.id = generateId()
          // Also add this new user to the users array
          users.push(currentUser)
          localStorage.setItem('users', JSON.stringify(users))
        }
        localStorage.setItem('user', JSON.stringify(currentUser))
        console.log('[DEBUG] migrateUserData: assigned id', currentUser.id, 'to user', currentUser.username)
      }
    }
  } catch (e) {
    console.error('[DEBUG] Migration failed:', e)
  }
}

export const useAuthStore = defineStore('auth', () => {
  // Run migration on init
  migrateUserData()

  // State
  const user = ref<User | null>(loadUserFromStorage())
  const accessToken = ref<string | null>(localStorage.getItem('accessToken'))

  function loadUserFromStorage(): User | null {
    try {
      const stored = localStorage.getItem('user')
      if (stored) {
        return JSON.parse(stored)
      }
    } catch {
      // ignore parse errors
    }
    return null
  }

  // Computed
  const isAuthenticated = computed(() => !!accessToken.value && !!user.value)

  // Actions
  async function login(identifier: string, password: string): Promise<void> {
    const response = await loginApi({ identifier, password })

    // Ensure the user has an id
    const userData = response.data.user
    if (!userData.id) {
      userData.id = generateId()
    }

    // Preserve local avatar if backend doesn't return one
    const existingUsersJson = localStorage.getItem('users')
    const existingUsers: User[] = existingUsersJson ? JSON.parse(existingUsersJson) : []
    const existingUser = existingUsers.find(u => u.id === userData.id)
    if (existingUser?.avatarUrl && !userData.avatarUrl) {
      userData.avatarUrl = existingUser.avatarUrl
    }

    accessToken.value = response.data.accessToken
    user.value = userData
    try {
      localStorage.setItem('accessToken', response.data.accessToken)
      localStorage.setItem('user', JSON.stringify(userData))

      // Also update the users list
      const usersJson = localStorage.getItem('users')
      const users: User[] = usersJson ? JSON.parse(usersJson) : []
      const existingIndex = users.findIndex(u => u.id === userData.id)
      if (existingIndex !== -1) {
        // Preserve avatar when updating existing user
        if (!userData.avatarUrl && users[existingIndex].avatarUrl) {
          userData.avatarUrl = users[existingIndex].avatarUrl
        }
        users[existingIndex] = userData
      } else {
        users.push(userData)
      }
      localStorage.setItem('users', JSON.stringify(users))
    } catch (e) {
      console.error('[DEBUG] Failed to save auth to localStorage:', e)
    }
  }

  async function register(username: string, email: string, password: string): Promise<void> {
    const response = await registerApi({ username, email, password })

    // Ensure the user has an id
    const userData = response.data.user
    if (!userData.id) {
      userData.id = generateId()
    }

    accessToken.value = response.data.accessToken
    user.value = userData
    try {
      localStorage.setItem('accessToken', response.data.accessToken)
      localStorage.setItem('user', JSON.stringify(userData))

      // Also update the users list
      const usersJson = localStorage.getItem('users')
      const users: User[] = usersJson ? JSON.parse(usersJson) : []
      const existingIndex = users.findIndex(u => u.id === userData.id)
      if (existingIndex === -1) {
        users.push(userData)
        localStorage.setItem('users', JSON.stringify(users))
      }
    } catch (e) {
      console.error('[DEBUG] Failed to save auth to localStorage:', e)
    }
  }

  function logout(): void {
    user.value = null
    accessToken.value = null
    localStorage.removeItem('accessToken')
    localStorage.removeItem('user')
  }

  async function fetchProfile(): Promise<void> {
    try {
      const response = await getProfile()
      const userData = response.data
      user.value = userData
      localStorage.setItem('user', JSON.stringify(userData))

      // Sync theme mode to theme store
      const themeStore = useThemeStore()
      if (userData.themeMode) {
        themeStore.setThemeMode(userData.themeMode as 'system' | 'light' | 'dark')
      }
    } catch (e) {
      console.error('[DEBUG] Failed to fetch profile:', e)
    }
  }

  async function uploadAvatar(file: File): Promise<{ success: boolean; avatarUrl?: string; error?: string }> {
    try {
      const response = await uploadAvatarApi(file)
      const avatarUrl = response.data.avatarUrl
      if (user.value) {
        user.value.avatarUrl = avatarUrl
        localStorage.setItem('user', JSON.stringify(user.value))
      }
      return { success: true, avatarUrl }
    } catch (e: any) {
      const message = e?.response?.data?.message || e?.message || '头像上传失败'
      return { success: false, error: message }
    }
  }

  // Update user profile - calls backend API and syncs localStorage
  async function updateProfile(updates: { username?: string; displayName?: string; email?: string }): Promise<{ success: boolean; error?: string }> {
    console.log('[settings save] authStore.user =', user.value)
    console.log('[settings save] payload =', updates)

    if (!user.value) {
      return { success: false, error: '未登录' }
    }

    try {
      // Call backend API to update profile in database
      const response = await updateProfileApi({
        username: updates.username,
        displayName: updates.displayName,
        email: updates.email
      })

      const updatedUser = response.data.user
      console.log('[settings save] backend returned user:', updatedUser)

      // Update authStore with the authoritative user from backend
      user.value = updatedUser

      // Update localStorage['user'] as source of truth for current user
      localStorage.setItem('user', JSON.stringify(updatedUser))

      // Also update localStorage['users'] for UI cache consistency
      const usersJson = localStorage.getItem('users')
      const users: User[] = usersJson ? JSON.parse(usersJson) : []
      const userIndex = users.findIndex(u => u.id === updatedUser.id)
      if (userIndex !== -1) {
        users[userIndex] = updatedUser
      } else {
        users.push(updatedUser)
      }
      localStorage.setItem('users', JSON.stringify(users))

      console.log('[settings save] profile updated successfully via backend')
      return { success: true }
    } catch (e: any) {
      console.error('[DEBUG] Failed to update profile:', e)
      const message = e?.response?.data?.message || e?.message || '保存失败'
      return { success: false, error: message }
    }
  }

  // Get users list (for internal use)
  function getUsers(): User[] {
    try {
      const usersJson = localStorage.getItem('users')
      return usersJson ? JSON.parse(usersJson) : []
    } catch {
      return []
    }
  }

  // Helper: generate username from display name
  function generateUsername(displayName: string): string {
    return displayName
      .toLowerCase()
      .replace(/[^a-z0-9\s]/g, '')
      .replace(/\s+/g, '')
      .substring(0, 20)
  }

  // Helper: check if identifier looks like an email
  function isEmailFormat(identifier: string): boolean {
    return identifier.includes('@')
  }

  return {
    user,
    accessToken,
    isAuthenticated,
    login,
    register,
    logout,
    generateUsername,
    isEmailFormat,
    updateProfile,
    getUsers,
    fetchProfile,
    uploadAvatar
  }
})
