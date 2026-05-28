import { api } from './auth'
import type { Room, CreateRoomRequest, UpdateRoomModeRequest } from '@/types'

export interface RoomApi {
  list: () => Promise<Room[]>
  getMyRooms: () => Promise<Room[]>
  getById: (id: string) => Promise<Room>
  create: (data: CreateRoomRequest) => Promise<Room>
  remove: (id: string) => Promise<void>
  addCharacter: (roomId: string, characterId: string) => Promise<Room>
  updateMode: (roomId: string, data: UpdateRoomModeRequest) => Promise<Room>
  getRoomMembers: (roomId: string) => Promise<RoomMemberResponse[]>
  inviteMember: (roomId: string, keyword: string) => Promise<RoomMemberResponse>
  recordEnter: (roomId: string) => Promise<void>
}

export interface RoomMemberResponse {
  userId: string
  username: string
  displayName: string
  avatarUrl: string | null
  role: string
  status: string
  joinedAt: string
}

export const roomsApi: RoomApi = {
  list: () => api.get<Room[]>('/rooms')
    .then(res => res.data)
    .catch(() => []),

  getMyRooms: () => api.get<Room[]>('/rooms')
    .then(res => res.data)
    .catch(() => []),

  getById: (id: string) => api.get<Room>(`/rooms/${id}`).then(res => res.data),

  create: (data: CreateRoomRequest) => api.post<Room>('/rooms', data).then(res => res.data),

  remove: (id: string) => api.delete(`/rooms/${id}`),

  addCharacter: (roomId: string, characterId: string) =>
    api.post<Room>(`/rooms/${roomId}/characters/${characterId}`).then(res => res.data),

  updateMode: (roomId: string, data: UpdateRoomModeRequest) =>
    api.patch<Room>(`/rooms/${roomId}/mode`, data).then(res => res.data),

  getRoomMembers: (roomId: string) =>
    api.get<RoomMemberResponse[]>(`/rooms/${roomId}/members`).then(res => res.data),

  inviteMember: (roomId: string, keyword: string) =>
    api.post<RoomMemberResponse>(`/rooms/${roomId}/members/invite`, { keyword }).then(res => res.data),

  recordEnter: (roomId: string) =>
    api.patch(`/rooms/${roomId}/enter`).then(res => res.data)
}
