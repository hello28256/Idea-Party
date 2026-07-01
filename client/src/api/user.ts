import { api } from './auth'
import { uploadToOss } from '@/utils/ossUploader'

// 用户域 REST 客户端，对接后端 UserController（/user/*）。
// 职责：当前用户资料读取、头像上传、个人偏好（主题）更新。
// 复用 auth 模块的 axios 实例，确保所有请求自动带上 JWT 与统一拦截行为。
export interface UserProfileResponse {
  id: string
  username: string
  displayName: string
  email: string
  avatarUrl?: string
  // 用户名变更冷却依据：业务规则限制用户名短期重复修改，前端据此提示是否在冷却期内。
  usernameUpdatedAt?: string
  themeMode: string
}

// 仅暴露主题偏好为可写字段，避免误把其他敏感字段暴露给前端更新入口。
export interface UpdatePreferencesRequest {
  themeMode: 'system' | 'light' | 'dark'
}

/**
 * 读取当前用户资料（含主题偏好与最近用户名修改时间）。
 * HTTP GET /user/profile。
 * 调用方：UserProfileView 加载、App.vue 主题初始化。
 */
export const getProfile = () =>
  api.get<UserProfileResponse>('/user/profile')

/**
 * 上传当前用户头像。
 * 1) 拿 STS 临时凭证 → 2) 浏览器直传阿里云 OSS → 3) PUT URL 到后端
 * 签名保留 { avatarUrl } 兼容 store 调用方。
 * 调用方：UserProfileView 头像裁剪组件。
 */
export const uploadAvatar = async (file: File) => {
  const avatarUrl = await uploadToOss(file)
  // 通知后端把这个 URL 存到 users.avatar_url
  const resp = await api.put<{ avatarUrl: string }>('/user/avatar', { avatarUrl })
  return { data: resp.data }
}

/**
 * 更新当前用户偏好（目前仅主题）。
 * HTTP PUT /user/preferences。
 * 调用方：SettingsPanel 主题切换。
 */
export const updatePreferences = (data: UpdatePreferencesRequest) =>
  api.put<UserProfileResponse>('/user/preferences', data)
