import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@/types'
import { login as loginApi, register as registerApi, updateProfile as updateProfileApi } from '@/api/auth'
import { getProfile, uploadAvatar as uploadAvatarApi } from '@/api/user'
import { useThemeStore } from '@/stores/theme'

// 客户端降级场景下，后端 User 可能不带 id；前端本地需要稳定主键
// 用于跨页面持久化、用户列表去重、头像缓存匹配等场景
function generateId(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0
    const v = c === 'x' ? r : (r & 0x3 | 0x8)
    return v.toString(16)
  })
}

// 一次性兼容老数据：早期版本 User 没有 id 字段，本地为现有用户补 id
// 避免迁移过程中出现「同一用户被当成多人」导致的列表重复、头像错乱
function migrateUserData(): void {

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

// 认证状态中心：单点维护 user / accessToken / isAuthenticated
// 同时承担「后端数据 → 本地 localStorage 镜像」职责，使刷新页面和路由切换能瞬时恢复登录态
export const useAuthStore = defineStore('auth', () => {
  // Run migration on init
  migrateUserData()

  // State
  // user 与 accessToken 都同步挂在 localStorage：刷新/新标签页时 store 重新挂载能直接恢复登录态，
  // 避免依赖接口调用来「猜」当前是否登录，否则首屏会出现「几秒钟的未登录态」导致受保护路由闪退
  const user = ref<User | null>(loadUserFromStorage())
  const accessToken = ref<string | null>(localStorage.getItem('accessToken'))

  // 读取失败（JSON 损坏、隐私模式）时回退 null，保证 store 不会因为持久化层故障而启动失败
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
  // 必须「token 和 user 同时存在」才视为已登录：仅剩 token 但 user 已被清理（例如部分登出流程）时
  // 路由守卫放行后会因拿不到用户信息再 401，反而更糟；这里用「两者齐全」做最严格判定
  const isAuthenticated = computed(() => !!accessToken.value && !!user.value)

  // Actions
  // 登录契约：identifier 支持用户名或邮箱（由后端/UI 区分），成功后写 token + user + users 列表三处 localStorage
  // 头像优先保留本地缓存：后端 user 表若未持久化头像，避免每次登录都把用户头像「重置成空」
  // localStorage 写入失败仅打印日志不抛错：网络/隐私模式下 localStorage 不可用，不应阻断登录流程本身
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

  // 注册契约：新用户直接 push 进 users 列表，不覆盖已存在条目，避免在同一浏览器多次注册时丢数据
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

  // 拉取最新 profile：刷新页面或回到设置页时同步后端数据，避免本地缓存与服务端漂移
  // 顺带把主题偏好推给 themeStore，让「服务端保存的主题」在多端保持一致
  // 静默吞错：调用方多为「启动期/页面回到前台」等弱关键场景，不希望失败时打断主流程
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

  // 头像上传：仅同步本地 user 镜像；不做跨用户列表同步，因为 avatar 属于「当前用户私有资源」
  // 返回结构化结果而不是抛异常，方便 UI 层直接 toast 错误信息
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
  // 以「后端返回值」为权威源，避免本地字段与服务端冲突；同时双写 user / users 两处 localStorage
  // 保证设置页与用户列表缓存看到的字段一致（否则头像/昵称刷新后会回退）
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
  // 暴露只读快照而非响应式引用，避免外部组件直接篡改 users 缓存导致与 store 状态不一致
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
