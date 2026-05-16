// User types for authentication
export interface User {
  id: string
  username: string
  displayName: string
  email: string
  avatarUrl?: string
  lastUsernameChangeAt?: string // ISO date string
  createdAt?: string // ISO date string
  themeMode?: 'system' | 'light' | 'dark'
}

// Authentication response from backend
export interface AuthResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: User
}

// Login request payload - identifier can be username or email
export interface LoginRequest {
  identifier: string
  password: string
}

// Register request payload
export interface RegisterRequest {
  email?: string
  password: string
  username: string
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
  topic?: string
  ownerId: string
  ownerName: string
  characterCount: number
  characters?: Character[]
  chatMode: 'dialogue' | 'discussion'
  maxDiscussionRounds: number
  createdAt: string
  updatedAt: string
  lastEnterTime?: string
}

export interface CreateRoomRequest {
  name: string
  topic?: string
}

export interface UpdateRoomModeRequest {
  chatMode?: 'dialogue' | 'discussion'
  maxDiscussionRounds?: number
}

// Character types
export interface Character {
  id: string
  name: string
  description: string
  avatarUrl?: string
  prompt?: string
  ownerId?: string
  isPreset?: boolean
  preset?: boolean
  createdAt: string
  updatedAt: string
}

export interface CharacterRequest {
  name: string
  description?: string
  avatarUrl?: string
  prompt?: string
  ownerId?: string
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
