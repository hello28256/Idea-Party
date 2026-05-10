import { api } from './auth'
import type { Room, CreateRoomRequest, UpdateRoomModeRequest } from '@/types'

export interface RoomApi {
  list: () => Promise<Room[]>
  getById: (id: string) => Promise<Room>
  create: (data: CreateRoomRequest) => Promise<Room>
  remove: (id: string) => Promise<void>
  addCharacter: (roomId: string, characterId: string) => Promise<Room>
  updateMode: (roomId: string, data: UpdateRoomModeRequest) => Promise<Room>
}

export const roomsApi: RoomApi = {
  list: () => api.get<Room[]>('/rooms').then(res => res.data),

  getById: (id: string) => api.get<Room>(`/rooms/${id}`).then(res => res.data),

  create: (data: CreateRoomRequest) => api.post<Room>('/rooms', data).then(res => res.data),

  remove: (id: string) => api.delete(`/rooms/${id}`),

  addCharacter: (roomId: string, characterId: string) =>
    api.post<Room>(`/rooms/${roomId}/characters/${characterId}`).then(res => res.data),

  updateMode: (roomId: string, data: UpdateRoomModeRequest) =>
    api.patch<Room>(`/rooms/${roomId}/mode`, data).then(res => res.data)
}
