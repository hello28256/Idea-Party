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

// Room types
export interface Room {
  id: string
  name: string
  theme?: string
  ownerId: string
  characterIds: string[]
  createdAt: string
  updatedAt: string
}

export interface CreateRoomRequest {
  name: string
  theme?: string
  characterIds: string[]
}

// Character types
export interface Character {
  id: string
  name: string
  description: string
  avatar?: string
  prompt?: string
  isPreset: boolean
  ownerId?: string
  createdAt: string
}

// Message types
export interface Message {
  id: string
  roomId: string
  senderId: string
  senderType: 'user' | 'character'
  senderName: string
  content: string
  createdAt: string
}

export interface SendMessageRequest {
  content: string
  characterId?: string
}
