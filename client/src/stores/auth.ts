// 认证 store：管理 JWT token、当前用户信息、登录/注册/登出、profile 同步与头像上传。
// 协作模块：router 守卫（判 isAuthenticated）、AppSidebar（展示用户/头像）、useSocket（注入 token）、SettingsView（改昵称/邮箱/头像）。

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
  try {
    const usersJson = localStorage.getItem('users')
    if (!usersJson) return

    const users: User[] = JSON.parse(usersJson)
    let usersChanged = false

    // 给历史遗留的「无 id 用户」补一个稳定主键，否则跨页面持久化、用户列表去重都会失效
    users.forEach(u => {
      if (!u.id) {
        u.id = generateId()
        usersChanged = true
      }
    })

    if (usersChanged) {
      localStorage.setItem('users', JSON.stringify(users))
    }

    // 老版本本地缓存里 current user 没有 id 字段（早期 User 不带主键），从 users 列表里按 username 反查补一个稳定 id
    const currentUserJson = localStorage.getItem('user')
    if (currentUserJson) {
      const currentUser: User = JSON.parse(currentUserJson)
      if (!currentUser.id) {
        const matchedUser = users.find(u =>
          u.username?.toLowerCase() === currentUser.username?.toLowerCase() &&
          u.email?.toLowerCase() === currentUser.email?.toLowerCase()
        )
        if (matchedUser) {
          currentUser.id = matchedUser.id
        } else {
          currentUser.id = generateId()
          // users 列表里没找到这个老用户，需要把它也加进去，否则后续「多用户登录/切换」会丢数据
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
  // store 创建时立刻跑一次迁移，兼容老版本 localStorage 中没有 id 的用户数据
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
      // 忽略解析错误
    }
    return null
  }

  // Computed
  // 必须「token 和 user 同时存在」才视为已登录：仅剩 token 但 user 已被清理（例如部分登出流程）时
  // 路由守卫放行后会因拿不到用户信息再 401，反而更糟；这里用「两者齐全」做最严格判定
  const isAuthenticated = computed(() => !!accessToken.value && !!user.value)

  // Actions
  /**
   * 登录契约：identifier 支持用户名或邮箱（由后端/UI 区分），成功后写 token + user + users 列表三处 localStorage。
   * 头像优先保留本地缓存：后端 user 表若未持久化头像，避免每次登录都把用户头像「重置成空」。
   * localStorage 写入失败仅打印日志不抛错：网络/隐私模式下 localStorage 不可用，不应阻断登录流程本身。
   * 调用方：LoginView 的「登录」按钮。
   */
  async function login(identifier: string, password: string): Promise<void> {
    const response = await loginApi({ identifier, password })

    // 后端 /auth/login 不返回 id 时（早期版本兼容场景）兜底生成一个，避免后续 localStorage 索引失败
    const userData = response.data.user
    if (!userData.id) {
      userData.id = generateId()
    }

    // 后端若没返回头像，优先沿用本地缓存里已有的——避免每次登录都把用户头像「重置成空」
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

      // 同步刷新 users 列表缓存：让其它视图（用户列表、邀请成员）能立即看到这个最新用户
      const usersJson = localStorage.getItem('users')
      const users: User[] = usersJson ? JSON.parse(usersJson) : []
      const existingIndex = users.findIndex(u => u.id === userData.id)
      if (existingIndex !== -1) {
        // 已有条目：保留本地头像作为兜底（响应里可能没带），其余字段以后端为准
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

  /**
   * 注册契约：新用户直接 push 进 users 列表，不覆盖已存在条目，避免在同一浏览器多次注册时丢数据。
   * 与 login 共用同样的 localStorage 镜像逻辑，但失败处理更宽松（注册失败通常意味着后端已抛错，无需本地兜底）。
   * 调用方：RegisterView 的「注册」按钮。
   */
  async function register(username: string, email: string, password: string): Promise<void> {
    const response = await registerApi({ username, email, password })

    // 兜底：后端未返回 id 时本地生成一个，确保后续 localStorage 索引可用
    const userData = response.data.user
    if (!userData.id) {
      userData.id = generateId()
    }

    accessToken.value = response.data.accessToken
    user.value = userData
    try {
      localStorage.setItem('accessToken', response.data.accessToken)
      localStorage.setItem('user', JSON.stringify(userData))

      // 第一次注册的账号要写进 users 列表缓存，否则后续「多用户登录/邀请」找不到这个用户
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
    // 阻止浏览器在下次访问时自动填充已登出账号的密码（仅阻断 silent access，不影响用户主动选择已存凭据）。
    // 能力检测 + 静默吞错：Safari / 隐私模式不应阻断登出主流程。
    if (
      typeof navigator !== 'undefined' &&
      navigator.credentials &&
      typeof navigator.credentials.preventSilentAccess === 'function'
    ) {
      navigator.credentials.preventSilentAccess().catch(() => { /* 静默降级 */ })
    }
  }

  /**
   * 拉取最新 profile：刷新页面或回到设置页时同步后端数据，避免本地缓存与服务端漂移。
   * 顺带把主题偏好推给 themeStore，让「服务端保存的主题」在多端保持一致。
   * 静默吞错：调用方多为「启动期/页面回到前台」等弱关键场景，不希望失败时打断主流程。
   * 调用方：App.vue 的 onMounted、router.beforeEach 守卫。
   */
  async function fetchProfile(): Promise<void> {
    try {
      const response = await getProfile()
      const userData = response.data
      user.value = userData
      localStorage.setItem('user', JSON.stringify(userData))

      // 把后端保存的主题档位同步到 theme store，让多端切换主题保持一致
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

  // 以「后端返回值」为权威源，避免本地字段与服务端冲突；同时双写 user / users 两处 localStorage
  // 保证设置页与用户列表缓存看到的字段一致（否则头像/昵称刷新后会回退）
  async function updateProfile(updates: { username?: string; displayName?: string; email?: string }): Promise<{ success: boolean; error?: string }> {
    console.log('[settings save] authStore.user =', user.value)
    console.log('[settings save] payload =', updates)

    if (!user.value) {
      return { success: false, error: '未登录' }
    }

    try {
      const response = await updateProfileApi({
        username: updates.username,
        displayName: updates.displayName,
        email: updates.email
      })

      const updatedUser = response.data.user
      console.log('[settings save] backend returned user:', updatedUser)

      // 直接以响应体中的 user 对象覆盖本地状态：后端是单一事实源
      user.value = updatedUser

      // 双写 user / users 两处 localStorage，让设置页与用户列表缓存保持一致
      localStorage.setItem('user', JSON.stringify(updatedUser))

      // 同步刷新 users 列表缓存：保证设置页与用户列表看到的字段一致（否则头像/昵称刷新后会回退）
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

  // 暴露只读快照而非响应式引用，避免外部组件直接篡改 users 缓存导致与 store 状态不一致
  // 调用方：内部/调试场景，不建议外部业务组件直接消费（应改用 user state + 后端接口）
  function getUsers(): User[] {
    try {
      const usersJson = localStorage.getItem('users')
      return usersJson ? JSON.parse(usersJson) : []
    } catch {
      return []
    }
  }

  // 辅助函数：根据 displayName 生成 username
  function generateUsername(displayName: string): string {
    return displayName
      .toLowerCase()
      .replace(/[^a-z0-9\s]/g, '')
      .replace(/\s+/g, '')
      .substring(0, 20)
  }

  // 辅助函数：判断 identifier 是否像邮箱
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
