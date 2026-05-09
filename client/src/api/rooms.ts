import { api } from './auth'
import type { Room, CreateRoomRequest } from '@/types'

export interface RoomApi {
  list: () => Promise<Room[]>
  create: (data: CreateRoomRequest) => Promise<Room>
  remove: (id: string) => Promise<void>
  addCharacter: (roomId: string, characterId: string) => Promise<Room>
}

export const roomsApi: RoomApi = {
  list: () => api.get<Room[]>('/rooms').then(res => res.data),

  create: (data: CreateRoomRequest) => api.post<Room>('/rooms', data).then(res => res.data),

  remove: (id: string) => api.delete(`/rooms/${id}`),

  addCharacter: (roomId: string, characterId: string) =>
    api.post<Room>(`/rooms/${roomId}/characters/${characterId}`).then(res => res.data)
}
