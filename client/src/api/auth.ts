import axios, { type AxiosInstance } from 'axios'
import type { AuthResponse, LoginRequest, RegisterRequest, ApiError } from '@/types'

// Base axios instance with configuration
const api: AxiosInstance = axios.create({
  baseURL: 'http://localhost:8080/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// Request interceptor: add JWT token from localStorage
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('accessToken')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
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

// Export the configured axios instance for use in stores
export { api }
