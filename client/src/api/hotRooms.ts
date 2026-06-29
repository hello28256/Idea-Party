// 热门聊天室静态配置（hotRooms.json）的客户端入口。
//
// 数据来源：后端 HotRoomController GET /api/rooms/hot，classpath 热加载而非 DB。
// 这里仅做 thin wrapper + 类型定义，不混入 RoomApi（避免污染 room CRUD 端点契约）。

export interface HotRoomLatestMessage {
  sender: string
  text: string
}

export interface HotRoom {
  id: string
  title: string
  cover: string
  participants: string[]
  latestMessage: HotRoomLatestMessage
  onlineCount: number
  messageCount: number
  isHot: boolean
}

import { api } from './auth'

export const hotRoomsApi = {
  // baseURL='/api' 已在 auth.ts 的 axios 实例注入（与 roomsApi 等保持一致风格）。
  // 后端 HotRoomController 是 @RequestMapping("/api/hot-rooms")，这里只写路径尾巴。
  list: () => api.get<HotRoom[]>('/hot-rooms').then(res => res.data)
}
