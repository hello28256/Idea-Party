import { api } from './auth'
import type { User } from '@/types'

export interface UserProfileResponse {
  id: string
  username: string
  displayName: string
  email: string
  avatarUrl?: string
  usernameUpdatedAt?: string
  themeMode: string
}

export interface UpdatePreferencesRequest {
  themeMode: 'system' | 'light' | 'dark'
}

export const getProfile = () =>
  api.get<UserProfileResponse>('/user/profile')

export const uploadAvatar = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return api.post<{ avatarUrl: string }>('/user/avatar', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

export const updatePreferences = (data: UpdatePreferencesRequest) =>
  api.put<UserProfileResponse>('/user/preferences', data)
