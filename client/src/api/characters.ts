import { api } from './auth'
import type { Character, CharacterRequest } from '@/types'

export const charactersApi = {
  list: () => api.get<Character[]>('/characters'),

  getPresets: () => api.get<Character[]>('/characters/presets'),

  getById: (id: string) => api.get<Character>(`/characters/${id}`),

  create: (data: CharacterRequest) => api.post<Character>('/characters', data),

  update: (id: string, data: CharacterRequest) => api.put<Character>(`/characters/${id}`, data),

  remove: (id: string) => api.delete(`/characters/${id}`)
}
