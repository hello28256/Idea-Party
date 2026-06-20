import { api } from './auth'
import type { MessageFeedbackPayload } from '@/composables/useSocket'

// 用户对单条 AI 回复的反馈（点赞/点踩 + 可选分类与备注）的 REST API 封装。
// 设计目的：把后端 feedback 资源隔离在此，前端 store / 组件只通过这一层与 HTTP 交互，
// 便于后续替换底层客户端或调整后端路径而不污染业务逻辑。
export interface SubmitFeedbackBody {
  type: 'LIKE' | 'DISLIKE'
  category?: string | null
  comment?: string | null
}

export interface FeedbackResponse {
  id: string
  messageId: string
  type: 'LIKE' | 'DISLIKE'
  category: string | null
  comment: string | null
  createdAt: string
  updatedAt: string
}

// 后端管理端列表接口返回的精简视图：故意不返回 messageContent 等大字段，
// 仅提供 preview 以便后台表格/筛选在不分页详情的情况下保持响应轻量。
export interface AdminFeedbackListItem {
  id: string
  messageId: string
  messagePreview: string
  type: 'LIKE' | 'DISLIKE'
  category: string | null
  comment: string | null
  userId: string
  username: string
  displayName: string
  createdAt: string
}

// 详情视图在列表项基础上补齐原始消息内容与上下文（房间、角色、触发该回复的用户提问），
// 便于管理员排查问题反馈的来龙去脉；userPrompt 可能为 null（首条 AI 回复或上下文已过期）。
export interface AdminFeedbackDetail extends AdminFeedbackListItem {
  messageContent: string
  messageCreatedAt: string
  roomId: string
  roomName: string
  characterId: string | null
  characterName: string | null
  /** Most recent USER message that prompted the AI reply. May be null. */
  userPrompt?: string | null
  userPromptAt?: string | null
}

// 后台列表的查询参数：page/size 与后端 Pageable 对齐；from/to 为 ISO 时间字符串，
// 全部可选以便支持多条件组合筛选，调用方按需传值。
export interface AdminListParams {
  page?: number
  size?: number
  category?: string
  userId?: string
  type?: 'LIKE' | 'DISLIKE'
  from?: string
  to?: string
}

// 通用分页响应包装：与后端 Spring Data Page 序列化结构保持一致，
// 泛型化以便在不同列表接口（如 future admin endpoints）中复用。
export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

// 消息反馈 REST 客户端，对接后端 MessageFeedbackController（/messages/{id}/feedback）
// 与 AdminFeedbackController（/admin/feedbacks）。
// 职责：用户对单条 AI 回复提交/查询/取消点赞点踩；管理端拉取分页列表与详情。
// 设计目的：把后端 feedback 资源隔离在此，前端 store / 组件只通过这一层与 HTTP 交互，
// 便于后续替换底层客户端或调整后端路径而不污染业务逻辑。

// 业务侧使用的反馈 REST 客户端。每个方法返回的 Promise 已解包 .data，
// 调用方直接拿到领域模型，组件/Store 不必关心 axios 响应包装层。
export const messageFeedbackApi = {
  /**
   * 提交/覆盖对一条消息的反馈（点赞或点踩 + 分类 + 备注）。
   * HTTP POST /messages/{messageId}/feedback。
   * 调用方：MessageBubble 的点赞/点踩按钮、反馈分类 Modal。
   */
  submit: (messageId: string, body: SubmitFeedbackBody) =>
    api.post<FeedbackResponse>(`/messages/${messageId}/feedback`, body).then(res => res.data),

  /**
   * 查询当前用户对指定消息的反馈。
   * HTTP GET /messages/{messageId}/feedback。
   * 调用方：进入聊天室时初始化消息气泡的反馈态。
   */
  get: (messageId: string) =>
    api.get<FeedbackResponse>(`/messages/${messageId}/feedback`).then(res => res.data),

  /**
   * 取消当前用户对指定消息的反馈。
   * HTTP DELETE /messages/{messageId}/feedback。
   * 调用方：MessageBubble 的"撤销点赞/点踩"按钮。
   */
  remove: (messageId: string) =>
    api.delete(`/messages/${messageId}/feedback`),

  /**
   * 管理端分页拉取反馈列表。
   * HTTP GET /admin/feedbacks（支持 page/size/category/userId/type/from/to 多条件筛选）。
   * 调用方：AdminFeedbackListView 表格。
   */
  adminList: (params: AdminListParams) =>
    api.get<PageResponse<AdminFeedbackListItem>>('/admin/feedbacks', { params })
      .then(res => res.data),

  /**
   * 管理端获取单条反馈详情（含原始消息内容、房间、角色、用户提问上下文）。
   * HTTP GET /admin/feedbacks/{id}。
   * 调用方：AdminFeedbackDetailDrawer。
   */
  adminGet: (id: string) =>
    api.get<AdminFeedbackDetail>(`/admin/feedbacks/${id}`).then(res => res.data)
}

/**
 * Convert server FeedbackResponse to ChatMessage.feedback payload shape.
 * 适配层：服务端返回完整实体，而前端 ChatMessage.feedback 只需要子集字段；
 * 在此裁剪可避免调用方接触冗余字段（如 id、messageId），也便于将来后端加字段时
 * 不破坏消费端契约。
 */
export function toFeedbackPayload(r: FeedbackResponse): MessageFeedbackPayload {
  return {
    type: r.type,
    category: r.category,
    comment: r.comment,
    createdAt: r.createdAt
  }
}
