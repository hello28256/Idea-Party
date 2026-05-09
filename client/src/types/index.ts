// User types for authentication
export interface User {
  id: string
  email: string
  name: string
}

// Authentication response from backend
export interface AuthResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: User
}

// Login request payload
export interface LoginRequest {
  email: string
  password: string
}

// Register request payload
export interface RegisterRequest {
  email: string
  password: string
  name: string
}

// API error response
export interface ApiError {
  message: string
  status: number
}
