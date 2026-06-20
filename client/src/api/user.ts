import { api } from './auth'
import type { User } from '@/types'

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
 * HTTP POST /user/avatar（multipart/form-data）。
 * 头像上传走 multipart/form-data：浏览器侧必须显式声明 Content-Type，
 * 让 axios 自动生成 boundary 而非手工拼接，否则后端解析会失败。
 * 调用方：UserProfileView 头像裁剪组件。
 */
// 头像上传走 multipart/form-data：浏览器侧必须显式声明 Content-Type，
// 让 axios 自动生成 boundary 而非手工拼接，否则后端解析会失败。
export const uploadAvatar = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return api.post<{ avatarUrl: string }>('/user/avatar', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

/**
 * 更新当前用户偏好（目前仅主题）。
 * HTTP PUT /user/preferences。
 * 调用方：SettingsPanel 主题切换。
 */
export const updatePreferences = (data: UpdatePreferencesRequest) =>
  api.put<UserProfileResponse>('/user/preferences', data)
