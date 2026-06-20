import { api } from './auth'
import type { Room, CreateRoomRequest, UpdateRoomModeRequest } from '@/types'

// 聊天室域 REST 客户端，对接后端 RoomController（/rooms/*）。
// 职责：聊天室 CRUD、成员管理、模式切换（dialogue/discussion）、记录进入事件。
// 统一收敛 `/rooms/*` 端点，便于在 Pinia store 与视图层做依赖替换和单测打桩；
// 新增端点时在此补齐方法签名与默认实现即可。
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

// 房间成员视图：刻意只暴露展示所需的标量字段（用户身份 + 加入时间 + 在线态），
// 不回传 Room 等聚合体，避免列表页一次性把全部角色/消息拉回前端造成冗余。
export interface RoomMemberResponse {
  userId: string
  username: string
  displayName: string
  avatarUrl: string | null
  role: string
  status: string
  joinedAt: string
}

// 列表接口在 401/网络抖动时静默降级为空数组：上层 store 仍能渲染空态，
// 避免一次未登录就炸出红色错误屏导致用户体验割裂。
export const roomsApi: RoomApi = {
  /**
   * 列出当前用户可见的全部聊天室。
   * HTTP GET /rooms。
   * 失败时静默降级为空数组，供 RoomListView 渲染空态。
   */
  list: () => api.get<Room[]>('/rooms')
    .then(res => res.data)
    .catch(() => []),

  /**
   * 列出「我创建的/我加入的」聊天室。
   * HTTP GET /rooms（与 list 同路径，靠 token 区分语义）。
   * 失败时同样静默降级为空数组。
   * 调用方：MyRoomsTab。
   */
  // 与 list 同源同路径，区分语义而非 URL：用于「我创建的/我加入的」个人维度列表。
  getMyRooms: () => api.get<Room[]>('/rooms')
    .then(res => res.data)
    .catch(() => []),

  /**
   * 获取聊天室详情（含成员与角色）。
   * HTTP GET /rooms/{id}。
   * 调用方：ChatRoomView 挂载时拉取。
   */
  getById: (id: string) => api.get<Room>(`/rooms/${id}`).then(res => res.data),

  /**
   * 创建聊天室。
   * HTTP POST /rooms。
   * 调用方：CreateRoomModal 提交；后端会异步触发 Moderator 首次编排。
   */
  create: (data: CreateRoomRequest) => api.post<Room>('/rooms', data).then(res => res.data),

  /**
   * 删除聊天室。
   * HTTP DELETE /rooms/{id}。
   * 调用方：RoomActionsMenu 的「删除」按钮（带二次确认弹窗）。
   */
  remove: (id: string) => api.delete(`/rooms/${id}`),

  /**
   * 把已有角色挂到房间上。
   * HTTP POST /rooms/{roomId}/characters/{characterId}。
   * 后端要求角色先独立创建再关联，因此这里是「引用」而非「内联创建」。
   * 调用方：RoomMembersPanel 添加角色弹窗。
   */
  addCharacter: (roomId: string, characterId: string) =>
    api.post<Room>(`/rooms/${roomId}/characters/${characterId}`).then(res => res.data),

  /**
   * 切换房间的聊天模式与最大圆桌轮数。
   * HTTP PATCH /rooms/{roomId}/mode。
   * 仅 PATCH 局部字段（chatMode / maxDiscussionRounds）：保持 PUT-like 语义但避免未传字段被覆盖为 null。
   * 调用方：RoomSettingsModal。
   */
  updateMode: (roomId: string, data: UpdateRoomModeRequest) =>
    api.patch<Room>(`/rooms/${roomId}/mode`, data).then(res => res.data),

  /**
   * 列出房间成员。
   * HTTP GET /rooms/{roomId}/members。
   * 调用方：RoomMembersPanel。
   */
  getRoomMembers: (roomId: string) =>
    api.get<RoomMemberResponse[]>(`/rooms/${roomId}/members`).then(res => res.data),

  /**
   * 按用户名/昵称关键字搜索并邀请成员。
   * HTTP POST /rooms/{roomId}/members/invite（body: { keyword }）。
   * keyword 既支持 username 也支持 displayName：由后端做模糊匹配与去重，前端只透传用户原始输入。
   * 调用方：RoomMembersPanel 邀请输入框。
   */
  inviteMember: (roomId: string, keyword: string) =>
    api.post<RoomMemberResponse>(`/rooms/${roomId}/members/invite`, { keyword }).then(res => res.data),

  /**
   * 记录「用户进入房间」事件，用于排序最近访问与活跃度统计。
   * HTTP PATCH /rooms/{roomId}/enter。
   * 调用方应静默吞错，不阻断主流程。
   * 调用方：ChatRoomView onMounted。
   */
  recordEnter: (roomId: string) =>
    api.patch(`/rooms/${roomId}/enter`).then(res => res.data)
}
