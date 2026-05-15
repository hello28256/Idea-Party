import axios, { type AxiosInstance } from 'axios'
import type { AuthResponse, LoginRequest, RegisterRequest } from '@/types'

// Base axios instance with configuration
const api: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 60000 // 60 seconds for large file uploads
})

// Request interceptor: add JWT token and set Content-Type for non-FormData requests
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

// Response interceptor: handle 401 by clearing token and redirecting to login
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

// Auth API functions
export const login = (data: LoginRequest) =>
  api.post<AuthResponse>('/auth/login', data)

export const register = (data: RegisterRequest) =>
  api.post<AuthResponse>('/auth/register', data)

export interface UpdateProfileRequest {
  username?: string
  displayName?: string
  email?: string
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

export const updateProfile = (data: UpdateProfileRequest) =>
  api.put<AuthResponse>('/auth/profile', data)

export const changePassword = (data: ChangePasswordRequest) =>
  api.patch('/auth/change-password', data)

// Export the configured axios instance for use in stores
export { api }
