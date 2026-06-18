// 全局用户领域模型，同时承载登录态（auth store）与个人设置（主题等）。
// 后端字段可选：老 token 可能不含 themeMode/isAdmin，需要前端安全降级。
export interface User {
  id: string
  username: string
  displayName: string
  email: string
  avatarUrl?: string
  lastUsernameChangeAt?: string // ISO date string
  createdAt?: string // ISO date string
  // 用户偏好主题；缺省时前端按 'system' 处理，避免对老数据强制覆盖
  themeMode?: 'system' | 'light' | 'dark'
  isAdmin?: boolean
}

// 登录/刷新接口统一返回体：accessToken + user，便于 axios interceptor 直接写入 auth store。
export interface AuthResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: User
}

// 登录入参：identifier 设计为 username 或 email 二选一
// （避免前端维护两套表单字段；由后端在 controller 层做归一化）
export interface LoginRequest {
  identifier: string
  password: string
}

// 注册入参：email 可选，是为了让第三方账号/纯用户名账号的扩展场景预留空间。
export interface RegisterRequest {
  email?: string
  password: string
  username: string
}

// 统一的 axios 错误载荷：所有 catch 分支直接展示 message，并按 status 路由 401/403/5xx。
export interface ApiError {
  message: string
  status: number
}

// 聊天室领域模型；同时承载列表摘要（characterCount）与详情（characters）。
// characterCount 冗余存储是为了列表渲染时少一次 N+1 查询。
export interface Room {
  id: string
  name: string
  topic?: string
  ownerId: string
  ownerName: string
  // 列表场景下后端只回 count 不回全量，避免大列表 payload 过大
  characterCount: number
  characters?: Character[]
  // dialogue = 用户与单一角色对话；discussion = 多角色圆桌（由 Moderator 编排）
  chatMode: 'dialogue' | 'discussion'
  // 圆桌模式下一轮最多自动轮换发言的次数；前端 UI 用来倒计时和中止按钮
  maxDiscussionRounds: number
  mode: 'single' | 'group'
  createdAt: string
  updatedAt: string
  lastEnterTime?: string
}

// 创建房间时只携带必要的成员关系；其余字段由后端默认填充（如 ownerId=当前用户）。
export interface CreateRoomRequest {
  name: string
  topic?: string
  characterIds?: string[]
  mode?: 'single' | 'group'
}

// PATCH 风格更新：所有字段可选，前端只回传用户实际改动的部分，避免覆盖其他设置。
export interface UpdateRoomModeRequest {
  chatMode?: 'dialogue' | 'discussion'
  maxDiscussionRounds?: number
}

// 角色领域模型：preset 表示系统内置角色（所有人可见），非 preset 为用户私有。
// preset 与 isPreset 并存是为了兼容后端不同版本返回的字段命名。
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

// 创建/更新角色请求体；ownerId 由后端从 token 注入，禁止前端伪造（防止越权创建他人角色）。
export interface CharacterRequest {
  name: string
  description?: string
  avatarUrl?: string
  prompt?: string
  ownerId?: string
}

// 聊天消息：senderType 用判别字段区分人与角色，便于 UI 头像/气泡样式直接路由。
export interface Message {
  id: string
  roomId: string
  senderId: string
  senderType: 'user' | 'character'
  senderName: string
  content: string
  createdAt: string
}

// 发送消息入参：characterId 仅在 single 模式下必填，用于指明与哪个角色对话。
export interface SendMessageRequest {
  content: string
  characterId?: string
}
