import axios, { type AxiosInstance } from 'axios'
import type { AuthResponse, LoginRequest, RegisterRequest } from '@/types'

/**
 * 共享 axios 实例：作为整个前端所有 API 调用的统一入口。
 * 通过 baseURL `/api` 让 Vite 代理转发到后端，避免在浏览器侧硬编码后端域名。
 * timeout 设为 60s 是为兼容大文件上传场景（如头像/附件），
 * 普通 JSON 请求的响应时间远小于此值，不影响体感。
 */
const api: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 60000 // 60 seconds for large file uploads
})

/**
 * 请求拦截器：两件事——自动附带 JWT、为非 FormData 请求兜底 JSON 头。
 * token 键名 `accessToken` 与后端 AuthFilter 解析逻辑约定一致，必须保持同步；
 * Content-Type 仅在非 FormData 时设置，是为了不覆盖上传文件时浏览器自动生成的 multipart boundary，
 * 否则文件上传会因 boundary 丢失而失败。
 */
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('accessToken')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    // Set JSON content-type for non-FormData requests that have data
    if (config.data && !(config.data instanceof FormData)) {
      config.headers['Content-Type'] = 'application/json'
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

/**
 * 响应拦截器：集中处理 401（token 过期或无效）。
 * 选择 hard redirect 而非 router.push，是因为 store 外的拦截器拿不到 Vue Router 实例，
 * 且 token 已失效时通常意味着状态不可信，全量刷新到登录页更安全。
 * 排除已在 `/login` 的场景，防止拦截器与登录页自身 401 形成重定向死循环。
 */
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('accessToken')
      // Only redirect if not already on login page
      if (!window.location.pathname.includes('/login')) {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

// 鉴权域 REST 客户端，对接后端 AuthController（/auth/*）。
// 职责：登录、注册、修改资料、改密，并对外暴露带拦截器的 axios 实例，
// 供其它 API 模块（characters/rooms/scenarios/...）复用，避免各自维护一份 JWT 注入。

/**
 * 登录。
 * HTTP POST /auth/login。
 * 调用方：LoginView 表单提交、AuthStore.login。
 */
export const login = (data: LoginRequest) =>
  api.post<AuthResponse>('/auth/login', data)

/**
 * 注册。
 * HTTP POST /auth/register。
 * 调用方：RegisterView 表单提交。
 */
export const register = (data: RegisterRequest) =>
  api.post<AuthResponse>('/auth/register', data)

/**
 * 更新个人资料请求体：所有字段可选。
 * 因为是 PATCH 语义（部分更新），后端只覆盖传入的非空字段，
 * 调用方只需传"想改的那一项"，不必先 GET 再 PUT。
 */
export interface UpdateProfileRequest {
  username?: string
  displayName?: string
  email?: string
}

/**
 * 修改密码请求体：当前密码必填。
 * 用于后端二次校验身份，防止 token 被劫持后攻击者直接改密码锁定原用户。
 */
export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

/**
 * 更新个人资料（displayName / email / username）。
 * HTTP PUT /auth/profile。
 * 调用方：UserProfileView 编辑表单提交。
 */
export const updateProfile = (data: UpdateProfileRequest) =>
  api.put<AuthResponse>('/auth/profile', data)

/**
 * 修改密码（需提供当前密码做身份复核）。
 * HTTP PATCH /auth/change-password。
 * 调用方：UserProfileView 改密表单。
 */
export const changePassword = (data: ChangePasswordRequest) =>
  api.patch('/auth/change-password', data)

// Export the configured axios instance for use in stores
export { api }
